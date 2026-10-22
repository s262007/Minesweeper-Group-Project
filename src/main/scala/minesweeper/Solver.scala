package minesweeper

import chisel3._
import chisel3.util._

/** Single-clue propagation, iterated to a fixpoint (CONTRACT 5.6, v1).
  *
  * Every cycle, each revealed clue n counts its deduced-mine neighbours M and its unknown
  * neighbours U (active, hidden, not yet deduced). With k = clue - M:
  * k == 0 makes all of U safe, k == |U| makes all of U mines. All clues apply the rules
  * in parallel on the same knowledge, then the new deductions are added. A cycle without
  * a new deduction is the fixpoint: done pulses on the next cycle with valid set.
  * Clues with k < 0 or k > |U|, or a cell deduced both ways, mean the input is
  * inconsistent: done pulses with contradiction set, valid low and no hints.
  *
  * cycles counts rule evaluations of the last run, including the final one without
  * progress (or the one that found the contradiction).
  *
  * The board input has no active bit, so the active area is rebuilt from the config
  * captured at start. The Core holds the board stable during a run (CONTRACT 5.1);
  * it is not copied. A hint is never shown on a cell that is revealed now.
  * v1 is fully parallel; cfg.subsetRule and cfg.lanes are accepted but unused until v2
  * (R2-S4), so a v2 configuration currently behaves as v1.
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

  // Active area of the captured configuration: one comparator per row and per column.
  val rowOn = VecInit((0 until p.maxRows).map(r => r.U < rows))
  val colOn = VecInit((0 until p.maxCols).map(c => c.U < cols))
  val active = VecInit((0 until p.cells).map(i => rowOn(i / p.maxCols) && colOn(i % p.maxCols))).asUInt

  val shown = VecInit(io.board.map(_.revealed)).asUInt
  val revealed = shown & active
  val unknown = active & ~revealed & ~knownSafe & ~knownMine

  val allSafe = Wire(Vec(p.cells, Bool()))
  val allMine = Wire(Vec(p.cells, Bool()))
  val badClue = Wire(Vec(p.cells, Bool()))
  for (i <- 0 until p.cells) {
    val nbrs = p.capacityNeighbors(i)
    val m = PopCount(nbrs.map(knownMine(_)))
    val u = PopCount(nbrs.map(unknown(_)))
    val clue = io.board(i).adj
    val mPlusU = m +& u // widened: no unsigned underflow anywhere
    badClue(i) := revealed(i) && (clue < m || clue > mPlusU)
    allSafe(i) := revealed(i) && clue === m
    allMine(i) := revealed(i) && clue === mPlusU
  }

  def fromAnyNeighbour(rule: Vec[Bool]): UInt =
    VecInit((0 until p.cells).map { i =>
      unknown(i) && VecInit(p.capacityNeighbors(i).map(rule(_))).asUInt.orR
    }).asUInt

  val newSafe = fromAnyNeighbour(allSafe)
  val newMine = fromAnyNeighbour(allMine)
  val inconsistent = badClue.asUInt.orR || (newSafe & newMine).orR
  val progress = (newSafe | newMine).orR

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
    }.elsewhen(progress) {
      knownSafe := knownSafe | newSafe
      knownMine := knownMine | newMine
    }.otherwise {
      running := false.B
      doneReg := true.B
      validReg := true.B
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
