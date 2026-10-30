package minesweeper

import chisel3._
import chisel3.util._

/** Single-clue propagation, iterated to a fixpoint (CONTRACT 5.6, v1), one row per cycle.
  *
  * Each cycle evaluates the clues of one row r. Each revealed clue counts its deduced-mine
  * neighbours M and its unknown neighbours U (active, hidden, not yet deduced). With
  * k = clue - M: k == 0 makes all of U safe, k == |U| makes all of U mines. The deductions
  * lie in rows r - 1 to r + 1 and are added at once; the next cycle evaluates row r + 1 on
  * the updated knowledge. A pass covers the active rows. A pass without a new deduction is
  * the fixpoint: done pulses on the next cycle with valid set. Clues with k < 0 or k > |U|,
  * or a cell deduced both ways in one step, mean the input is inconsistent: done pulses
  * with contradiction set, valid low and no hints.
  *
  * There is one row of rule logic, fed by a three-row window that row multiplexers select.
  * The fully parallel version (one copy per cell) used about 26,600 LUTs at 30x24. On a
  * consistent board the rules only add true facts, so the row order does not change the
  * fixpoint; it equals the parallel one, only the cycle count differs.
  *
  * cycles counts row evaluations of the last run, including the final pass without
  * progress (or the step that found the contradiction). Bound: every pass but the last
  * adds a deduction, so at most (unknown cells + 1) passes of rows cycles.
  *
  * The board input has no active bit, so the active area is rebuilt from the config
  * captured at start. The Core holds the board stable during a run (CONTRACT 5.1);
  * it is not copied. A hint is never shown on a cell that is revealed now.
  * cfg.subsetRule and cfg.lanes are accepted but unused until v2, so a v2 configuration
  * currently behaves as v1.
  */
class Solver(p: MsParams, cfg: SolverConfig = SolverConfig()) extends Module {
  require(cfg.lanes <= p.cells, s"solver lanes (${cfg.lanes}) must not exceed the ${p.cells} cells")
  val io = IO(new SolverIO(p))

  val running = RegInit(false.B)
  val doneReg = RegInit(false.B)
  val validReg = RegInit(false.B)
  val contraReg = RegInit(false.B)
  val knownSafe = RegInit(0.U(p.cells.W))
  val knownMine = RegInit(0.U(p.cells.W))
  val cycles = RegInit(0.U(p.cycleW.W))
  val rows = RegInit(0.U(p.rowsW.W))
  val cols = RegInit(0.U(p.colsW.W))
  val row = RegInit(0.U(p.rowW.W)) // row evaluated this cycle
  val passProgress = RegInit(false.B)

  // Active area of the captured configuration: one comparator per row and per column.
  val rowOn = VecInit((0 until p.maxRows).map(r => r.U < rows))
  val colOn = VecInit((0 until p.maxCols).map(c => c.U < cols))
  val active = VecInit((0 until p.cells).map(i => rowOn(i / p.maxCols) && colOn(i % p.maxCols))).asUInt

  val shown = VecInit(io.board.map(_.revealed)).asUInt
  val revealed = shown & active
  val unknown = active & ~revealed & ~knownSafe & ~knownMine

  // Window rows: dr = -1, 0, 1 around row. The multiplexers have one input per value of
  // the row register; rows outside the storage read as empty.
  val slots = 1 << p.rowW
  def inRows(r: Int): Boolean = r >= 0 && r < p.maxRows
  def bitsOf(v: UInt, r: Int): UInt = v((r + 1) * p.maxCols - 1, r * p.maxCols)
  def window(v: UInt, dr: Int): UInt =
    VecInit((0 until slots).map(r => if (inRows(r + dr)) bitsOf(v, r + dr) else 0.U(p.maxCols.W)))(row)
  val unkWin = Seq(-1, 0, 1).map(window(unknown, _))
  val mineWin = Seq(-1, 0, 1).map(window(knownMine, _))
  val clueRow = window(revealed, 0)
  val zeroAdj = VecInit(Seq.fill(p.maxCols)(0.U(4.W)))
  val adjRow = VecInit((0 until slots).map { r =>
    if (inRows(r)) VecInit((0 until p.maxCols).map(c => io.board(r * p.maxCols + c).adj)) else zeroAdj
  })(row)

  def inCols(c: Int): Boolean = c >= 0 && c < p.maxCols

  val allSafe = Wire(Vec(p.maxCols, Bool()))
  val allMine = Wire(Vec(p.maxCols, Bool()))
  val badClue = Wire(Vec(p.maxCols, Bool()))
  for (c <- 0 until p.maxCols) {
    val nbrs = for (d <- 0 until 3; cc <- c - 1 to c + 1 if inCols(cc) && !(d == 1 && cc == c)) yield (d, cc)
    val m = PopCount(nbrs.map { case (d, cc) => mineWin(d)(cc) })
    val u = PopCount(nbrs.map { case (d, cc) => unkWin(d)(cc) })
    val clue = adjRow(c)
    val mPlusU = m +& u // widened: no unsigned underflow anywhere
    badClue(c) := clueRow(c) && (clue < m || clue > mPlusU)
    allSafe(c) := clueRow(c) && clue === m
    allMine(c) := clueRow(c) && clue === mPlusU
  }

  /** Deductions in window row d: unknown cells next to a clue of the evaluated row that applies the rule. */
  def deduce(rule: Vec[Bool]): Seq[UInt] = Seq.tabulate(3) { d =>
    VecInit((0 until p.maxCols).map { c =>
      val clues = (c - 1 to c + 1).filter(cc => inCols(cc) && !(d == 1 && cc == c))
      unkWin(d)(c) && VecInit(clues.map(rule(_))).asUInt.orR
    }).asUInt
  }
  val safeWin = deduce(allSafe)
  val mineWin2 = deduce(allMine)

  /** The window deductions at their grid positions. */
  val at = VecInit((0 until slots).map(k => row === k.U))
  def spread(win: Seq[UInt]): UInt = VecInit((0 until p.cells).map { i =>
    val (r, c) = (i / p.maxCols, i % p.maxCols)
    val terms = for (d <- 0 until 3 if r - (d - 1) >= 0 && r - (d - 1) < slots) yield at(r - (d - 1)) && win(d)(c)
    VecInit(terms).asUInt.orR
  }).asUInt

  val newSafe = spread(safeWin)
  val newMine = spread(mineWin2)
  val inconsistent = badClue.asUInt.orR || (0 until 3).map(d => (safeWin(d) & mineWin2(d)).orR).reduce(_ || _)
  val progress = (0 until 3).map(d => (safeWin(d) | mineWin2(d)).orR).reduce(_ || _)

  io.start.ready := !running && !io.clear
  io.busy := running && !io.clear
  io.done := doneReg && !io.clear
  io.valid := validReg && !io.clear
  io.contradiction := contraReg && !io.clear
  io.cycles := cycles
  for (i <- 0 until p.cells) {
    val show = validReg && !io.clear && !shown(i)
    io.hints(i).safe := show && knownSafe(i)
    io.hints(i).mine := show && knownMine(i)
  }

  doneReg := false.B
  when(running) {
    cycles := cycles + 1.U
    when(inconsistent) {
      running := false.B
      doneReg := true.B
      contraReg := true.B
      knownSafe := 0.U
      knownMine := 0.U
    }.otherwise {
      knownSafe := knownSafe | newSafe
      knownMine := knownMine | newMine
      when(row >= rows - 1.U) {
        when(passProgress || progress) {
          row := 0.U
          passProgress := false.B
        }.otherwise {
          running := false.B
          doneReg := true.B
          validReg := true.B
        }
      }.otherwise {
        row := row + 1.U
        passProgress := passProgress || progress
      }
    }
  }

  when(io.start.fire) {
    running := true.B
    validReg := false.B
    contraReg := false.B
    knownSafe := 0.U
    knownMine := 0.U
    cycles := 0.U
    rows := io.start.bits.config.rows
    cols := io.start.bits.config.cols
    row := 0.U
    passProgress := false.B
  }

  when(io.clear) {
    running := false.B
    doneReg := false.B
    validReg := false.B
    contraReg := false.B
    knownSafe := 0.U
    knownMine := 0.U
    cycles := 0.U
  }
}
