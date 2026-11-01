package minesweeper

import chisel3._
import chisel3.util._

/** Single-clue propagation, iterated to a fixpoint (CONTRACT 5.6, v1), one row per step.
  *
  * A step evaluates the clues of one row r in three cycles, so that each cycle has a short
  * logic path (100 MHz target):
  *  - read: a window of three rows, r - 1 to r + 1, is copied into registers: the unknown
  *    cells (active, hidden, not yet deduced), the deduced mines and the clues of row r;
  *  - count: each revealed clue of row r counts its deduced-mine neighbours M and its
  *    unknown neighbours U in the window. With k = clue - M: k == 0 makes all of U safe,
  *    k == |U| makes all of U mines, k < 0 or k > |U| is a contradiction. The results are
  *    registered;
  *  - write: the deductions, in rows r - 1 to r + 1, are added to the knowledge.
  * A pass covers the active rows. A pass without a new deduction is the fixpoint: done
  * pulses on the next cycle with valid set. An unsatisfiable clue, or a cell deduced both
  * ways in one step, ends the run with contradiction set, valid low and no hints. The
  * knowledge is not cleared then: hints are only shown while valid is set.
  *
  * There is one row of rule logic instead of one copy per cell: the fully parallel version
  * used about 26,600 LUTs at 30x24. On a consistent board the rules only add true facts,
  * so the row order does not change the fixpoint; it equals the parallel one, only the
  * cycle count differs.
  *
  * cycles counts the cycles of the last run: three per row step, over all passes.
  * Bound: every pass but the last adds a deduction, so at most (unknown cells + 1) passes
  * of 3 * rows cycles.
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
  val row = RegInit(0.U(p.rowW.W)) // row of the current step
  val passProgress = RegInit(false.B)

  // Phases of a step. The unused encoding behaves as read.
  val count = 1.U(2.W)
  val write = 2.U(2.W)
  val phase = RegInit(0.U(2.W))
  val reading = phase =/= count && phase =/= write

  // Active area of the captured configuration: one comparator per row and per column.
  val rowOn = VecInit((0 until p.maxRows).map(r => r.U < rows))
  val colOn = VecInit((0 until p.maxCols).map(c => c.U < cols))
  val active = VecInit((0 until p.cells).map(i => rowOn(i / p.maxCols) && colOn(i % p.maxCols))).asUInt

  val shown = VecInit(io.board.map(_.revealed)).asUInt
  val revealed = shown & active
  val unknown = active & ~revealed & ~knownSafe & ~knownMine

  def inRows(r: Int): Boolean = r >= 0 && r < p.maxRows
  def inCols(c: Int): Boolean = c >= 0 && c < p.maxCols

  // Read: row multiplexers with one input per value of the row register. Window row d holds
  // grid row row + d - 1; rows outside the storage read as empty.
  val slots = 1 << p.rowW
  def bitsOf(v: UInt, r: Int): UInt = v((r + 1) * p.maxCols - 1, r * p.maxCols)
  def window(v: UInt, dr: Int): UInt =
    VecInit((0 until slots).map(r => if (inRows(r + dr)) bitsOf(v, r + dr) else 0.U(p.maxCols.W)))(row)
  val zeroAdj = VecInit(Seq.fill(p.maxCols)(0.U(4.W)))
  val readUnk = VecInit(Seq(-1, 0, 1).map(window(unknown, _)))
  val readMine = VecInit(Seq(-1, 0, 1).map(window(knownMine, _)))
  val readClue = window(revealed, 0)
  val readAdj = VecInit((0 until slots).map { r =>
    if (inRows(r)) VecInit((0 until p.maxCols).map(c => io.board(r * p.maxCols + c).adj)) else zeroAdj
  })(row)

  // Written in every read phase before use, so no reset is needed.
  val winUnk = Reg(Vec(3, UInt(p.maxCols.W)))
  val winMine = Reg(Vec(3, UInt(p.maxCols.W)))
  val winClue = Reg(UInt(p.maxCols.W))
  val winAdj = Reg(Vec(p.maxCols, UInt(4.W)))

  // Count: the rule for each clue of the window's middle row.
  val cntSafe = Wire(Vec(p.maxCols, Bool()))
  val cntMine = Wire(Vec(p.maxCols, Bool()))
  val cntBad = Wire(Vec(p.maxCols, Bool()))
  for (c <- 0 until p.maxCols) {
    val nbrs = for (d <- 0 until 3; cc <- c - 1 to c + 1 if inCols(cc) && !(d == 1 && cc == c)) yield (d, cc)
    val m = PopCount(nbrs.map { case (d, cc) => winMine(d)(cc) })
    val u = PopCount(nbrs.map { case (d, cc) => winUnk(d)(cc) })
    val clue = winAdj(c)
    val mPlusU = m +& u // widened: no unsigned underflow anywhere
    cntBad(c) := winClue(c) && (clue < m || clue > mPlusU)
    cntSafe(c) := winClue(c) && clue === m
    cntMine(c) := winClue(c) && clue === mPlusU
  }
  val ruleSafe = Reg(Vec(p.maxCols, Bool()))
  val ruleMine = Reg(Vec(p.maxCols, Bool()))
  val ruleBad = Reg(Vec(p.maxCols, Bool()))

  // Write: window cells that are unknown and next to a clue applying the rule.
  def deduce(rule: Vec[Bool]): Seq[UInt] = Seq.tabulate(3) { d =>
    VecInit((0 until p.maxCols).map { c =>
      val clues = (c - 1 to c + 1).filter(cc => inCols(cc) && !(d == 1 && cc == c))
      winUnk(d)(c) && VecInit(clues.map(rule(_))).asUInt.orR
    }).asUInt
  }
  val safeWin = deduce(ruleSafe)
  val mineWin = deduce(ruleMine)

  /** The window deductions at their grid positions, only in the write phase. */
  val writing = running && phase === write
  val writeRow = VecInit((0 until slots).map(k => writing && row === k.U))
  def spread(win: Seq[UInt]): UInt = VecInit((0 until p.cells).map { i =>
    val (r, c) = (i / p.maxCols, i % p.maxCols)
    val terms = for (d <- 0 until 3 if r - (d - 1) >= 0 && r - (d - 1) < slots) yield writeRow(r - (d - 1)) && win(d)(c)
    VecInit(terms).asUInt.orR
  }).asUInt

  val newSafe = spread(safeWin)
  val newMine = spread(mineWin)
  val inconsistent = writing &&
    (ruleBad.asUInt.orR || (0 until 3).map(d => (safeWin(d) & mineWin(d)).orR).reduce(_ || _))
  val progress = (0 until 3).map(d => (safeWin(d) | mineWin(d)).orR).reduce(_ || _)

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
    knownSafe := knownSafe | newSafe
    knownMine := knownMine | newMine
    when(phase === count) {
      ruleSafe := cntSafe
      ruleMine := cntMine
      ruleBad := cntBad
      phase := write
    }.elsewhen(phase === write) {
      phase := 0.U
      when(inconsistent) {
        running := false.B
        doneReg := true.B
        contraReg := true.B
      }.elsewhen(row >= rows - 1.U) {
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
    }.otherwise {
      winUnk := readUnk
      winMine := readMine
      winClue := readClue
      winAdj := readAdj
      phase := count
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
    phase := 0.U
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
