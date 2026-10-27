package minesweeper

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec

/** Board sizes for the proofs, and harness geometry independent of MsParams helpers. */
object FormalBoards {
  val configs: Seq[(String, MsParams)] = Seq(
    "3x3" -> MsParams.forVerification(3, 3),
    "4x4" -> MsParams.forVerification(4, 4),
    // Padding on both axes: a 3x2 game inside 4x3 storage, or the full 4x3 grid.
    "4x3 storage with 4x3 and 3x2 games" -> MsParams.forVerification(4, 3).copy(
      presets = Vector(GridPreset(0, 4, 3), GridPreset(1, 3, 2)))
  )

  /** Neighbours from explicit row and column loops over the storage grid. */
  def neighbours(p: MsParams, i: Int): Seq[Int] = {
    val (r, c) = (i / p.maxCols, i % p.maxCols)
    for {
      dr <- -1 to 1
      dc <- -1 to 1
      if dr != 0 || dc != 0
      rr = r + dr
      cc = c + dc
      if rr >= 0 && rr < p.maxRows && cc >= 0 && cc < p.maxCols
    } yield rr * p.maxCols + cc
  }

  /** Active rows and columns of the preset chosen by the prover. */
  def dims(p: MsParams, preset: UInt): (UInt, UInt) = (
    MuxLookup(preset, 0.U(p.rowsW.W))(p.presets.map(g => g.id.U -> g.rows.U(p.rowsW.W))),
    MuxLookup(preset, 0.U(p.colsW.W))(p.presets.map(g => g.id.U -> g.cols.U(p.colsW.W))))
}

/** Inductive step of the Solver v1 soundness proof (CONTRACT 5.6, 7).
  *
  * Invariant: every deduced mine is a mine of the layout, every deduced safe cell is not,
  * and all deductions lie in the active grid. Every start (and reset) empties the
  * knowledge, so the invariant holds when a run begins (base case, from the RTL).
  *
  * The check starts the solver in ANY internal state that satisfies the invariant (no reset),
  * on any preset, any layout and any board consistent with it: revealed cells are safe and
  * active and show their true count, flags are not inputs. It then proves, within that one
  * cycle, that the clues are never judged inconsistent, that the deductions of this step
  * keep the invariant, and that the visible hints agree with the layout. By induction the
  * hints are sound after any number of evaluations and runs, for every board of this size.
  * The board need not be held for this: the Core holds it anyway (CONTRACT 5.1).
  *
  * A bounded unrolling of whole runs was tried first: z3 had not finished 3x3 after
  * 80 minutes, because of the counting arguments over many steps.
  */
class SolverStep(p: MsParams) extends Module {
  val layout = IO(Input(Vec(p.cells, Bool())))
  val revealed = IO(Input(Vec(p.cells, Bool())))
  val preset = IO(Input(UInt(p.presetW.W)))
  val start = IO(Input(Bool()))

  val dut = Module(new Solver(p))
  val (rows, cols) = FormalBoards.dims(p, preset)
  val active = VecInit((0 until p.cells).map(i => (i / p.maxCols).U < rows && (i % p.maxCols).U < cols)).asUInt
  val mine = (layout.asUInt & active)

  dut.io.clear := false.B
  dut.io.start.valid := start
  dut.io.start.bits.config.preset := preset
  dut.io.start.bits.config.rows := rows
  dut.io.start.bits.config.cols := cols
  dut.io.start.bits.config.mines := 0.U
  for (i <- 0 until p.cells) {
    dut.io.board(i).revealed := revealed(i)
    dut.io.board(i).adj := Mux(revealed(i), PopCount(FormalBoards.neighbours(p, i).map(mine(_))), 0.U)
  }

  // Internal state and next-step deductions of the solver.
  val knownSafe = BoringUtils.bore(dut.knownSafe)
  val knownMine = BoringUtils.bore(dut.knownMine)
  val newSafe = BoringUtils.bore(dut.newSafe)
  val newMine = BoringUtils.bore(dut.newMine)
  val inconsistent = BoringUtils.bore(dut.inconsistent)
  val running = BoringUtils.bore(dut.running)
  val dutRows = BoringUtils.bore(dut.rows)
  val dutCols = BoringUtils.bore(dut.cols)

  def sound(safe: UInt, mines: UInt): Bool =
    (mines & ~mine) === 0.U && (safe & mine) === 0.U && ((safe | mines) & ~active) === 0.U

  // Environment: a consistent board of the chosen preset, and the configuration the solver captured.
  assume(!reset.asBool)
  assume(preset < p.presets.size.U)
  assume(dutRows === rows && dutCols === cols)
  for (i <- 0 until p.cells) assume(!revealed(i) || (active(i) && !layout(i)))
  // Induction hypothesis.
  assume(sound(knownSafe, knownMine))

  // Induction step.
  when(running) {
    assert(!inconsistent, "consistent clues judged inconsistent")
    assert(sound(knownSafe | newSafe, knownMine | newMine), "a new deduction is wrong")
  }
  // Visible hints under the hypothesis.
  for (i <- 0 until p.cells) {
    val h = dut.io.hints(i)
    assert(!(h.safe && mine(i)), s"cell $i: safe hint on a mine")
    assert(!(h.mine && !mine(i)), s"cell $i: mine hint on a safe cell")
    assert(!(h.safe && h.mine), s"cell $i: both hints")
    assert(!((h.safe || h.mine) && (revealed(i) || !active(i))), s"cell $i: hint on a revealed or inactive cell")
  }
}

class SolverFormalTest extends AnyFlatSpec with ChiselScalatestTester with Formal {
  for ((label, p) <- FormalBoards.configs) {
    s"Solver v1 on $label" should "keep only correct deductions at every step (induction)" taggedAs FormalTag in {
      // No reset: the first cycle starts from any state allowed by the hypothesis.
      verify(new SolverStep(p), Seq(BoundedCheck(1), ResetOption(0)))
    }
  }
}
