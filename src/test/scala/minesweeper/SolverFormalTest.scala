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
  * Invariant, for any layout and any board consistent with it (revealed cells are safe and
  * active and show their true count; flags are not inputs):
  *  - knowledge: every deduced mine is a mine, every deduced safe cell is not, and all
  *    deductions lie in the active grid;
  *  - in the count and write phases, the window registers are a sound snapshot of rows
  *    row - 1 to row + 1: unknown cells are active and hidden, deduced mines are mines,
  *    the other active hidden cells are safe, and each clue is a revealed cell's true count;
  *  - in the write phase, the registered rule results are true for the unknown window
  *    cells around each clue, and no clue is judged unsatisfiable.
  * Start and reset empty the knowledge and enter the read phase (base case, from the RTL).
  *
  * The check starts the solver in ANY state that satisfies the invariant (no reset), on any
  * preset, and proves within that cycle: consistent clues are never judged inconsistent,
  * the deductions written keep the knowledge invariant, the window read in the read phase
  * is a sound snapshot, the rule results computed in the count phase are true, and the
  * visible hints agree with the layout. The registers take exactly these values on the
  * next edge and keep them in the following phases (from the RTL), so by induction the
  * hints are sound after any number of steps and runs. This needs the board to be the same
  * from a read to its write; the Core holds it during a run (CONTRACT 5.1).
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
  val count = VecInit((0 until p.cells).map(i => PopCount(FormalBoards.neighbours(p, i).map(mine(_))).pad(4)))

  dut.io.clear := false.B
  dut.io.start.valid := start
  dut.io.start.bits.config.preset := preset
  dut.io.start.bits.config.rows := rows
  dut.io.start.bits.config.cols := cols
  dut.io.start.bits.config.mines := 0.U
  for (i <- 0 until p.cells) {
    dut.io.board(i).revealed := revealed(i)
    dut.io.board(i).adj := Mux(revealed(i), count(i), 0.U)
  }

  // Internal state and next-step values of the solver.
  val knownSafe = BoringUtils.bore(dut.knownSafe)
  val knownMine = BoringUtils.bore(dut.knownMine)
  val newSafe = BoringUtils.bore(dut.newSafe)
  val newMine = BoringUtils.bore(dut.newMine)
  val inconsistent = BoringUtils.bore(dut.inconsistent)
  val running = BoringUtils.bore(dut.running)
  val dutRows = BoringUtils.bore(dut.rows)
  val dutCols = BoringUtils.bore(dut.cols)
  val row = BoringUtils.bore(dut.row)
  val phase = BoringUtils.bore(dut.phase)
  val reading = BoringUtils.bore(dut.reading)
  val winUnk = BoringUtils.bore(dut.winUnk)
  val winMine = BoringUtils.bore(dut.winMine)
  val winClue = BoringUtils.bore(dut.winClue)
  val winAdj = BoringUtils.bore(dut.winAdj)
  val readUnk = BoringUtils.bore(dut.readUnk)
  val readMine = BoringUtils.bore(dut.readMine)
  val readClue = BoringUtils.bore(dut.readClue)
  val readAdj = BoringUtils.bore(dut.readAdj)
  val cntSafe = BoringUtils.bore(dut.cntSafe)
  val cntMine = BoringUtils.bore(dut.cntMine)
  val cntBad = BoringUtils.bore(dut.cntBad)
  val ruleSafe = BoringUtils.bore(dut.ruleSafe)
  val ruleMine = BoringUtils.bore(dut.ruleMine)
  val ruleBad = BoringUtils.bore(dut.ruleBad)
  val countPhase = 1.U
  val writePhase = 2.U

  def sound(safe: UInt, mines: UInt): Bool =
    (mines & ~mine) === 0.U && (safe & mine) === 0.U && ((safe | mines) & ~active) === 0.U

  // Truth around the solver's row, from the harness's own geometry: window row d is grid
  // row row + d - 1, and rows outside the storage are empty.
  val slots = 1 << p.rowW
  val zeroBits = VecInit(Seq.fill(p.maxCols)(false.B))
  def bitsRow(v: UInt)(r: Int): Vec[Bool] = VecInit((0 until p.maxCols).map(c => v(r * p.maxCols + c)))
  def around[T <: Data](f: Int => T, zero: T, dr: Int): T =
    VecInit((0 until slots).map(r => if (r + dr >= 0 && r + dr < p.maxRows) f(r + dr) else zero))(row)
  val actW = Seq(-1, 0, 1).map(around(bitsRow(active), zeroBits, _))
  val mineW = Seq(-1, 0, 1).map(around(bitsRow(mine), zeroBits, _))
  val revW = Seq(-1, 0, 1).map(around(bitsRow(revealed.asUInt), zeroBits, _))
  val countMid = around(r => VecInit((0 until p.maxCols).map(c => count(r * p.maxCols + c))),
    VecInit(Seq.fill(p.maxCols)(0.U(4.W))), 0)

  def windowSound(unk: Vec[UInt], km: Vec[UInt], clue: UInt, adj: Vec[UInt]): Bool = {
    val cells = for (d <- 0 until 3; c <- 0 until p.maxCols) yield {
      val (a, m, rv, u, k) = (actW(d)(c), mineW(d)(c), revW(d)(c), unk(d)(c), km(d)(c))
      (!u || (a && !rv)) && (!k || m) && !(u && k) && (!(a && !rv && !u && !k) || !m)
    }
    val clues = (0 until p.maxCols).map(c => !clue(c) || (actW(1)(c) && revW(1)(c) && adj(c) === countMid(c)))
    VecInit(cells ++ clues).asUInt.andR
  }

  def ruleNbrs(c: Int): Seq[(Int, Int)] =
    for (d <- 0 until 3; cc <- c - 1 to c + 1 if cc >= 0 && cc < p.maxCols && !(d == 1 && cc == c)) yield (d, cc)
  def rulesSound(safe: Vec[Bool], mines: Vec[Bool], bad: Vec[Bool]): Bool = VecInit((0 until p.maxCols).map { c =>
    val ok = ruleNbrs(c).map { case (d, cc) =>
      val u = winUnk(d)(cc)
      (!(safe(c) && u) || !mineW(d)(cc)) && (!(mines(c) && u) || mineW(d)(cc))
    }
    !bad(c) && VecInit(ok).asUInt.andR
  }).asUInt.andR

  // Environment: a consistent board of the chosen preset, and the configuration the solver captured.
  assume(!reset.asBool)
  assume(preset < p.presets.size.U)
  assume(dutRows === rows && dutCols === cols)
  for (i <- 0 until p.cells) assume(!revealed(i) || (active(i) && !layout(i)))
  // Induction hypothesis.
  assume(sound(knownSafe, knownMine))
  assume(!running || reading || windowSound(winUnk, winMine, winClue, winAdj))
  assume(!running || phase =/= writePhase || rulesSound(ruleSafe, ruleMine, ruleBad))

  // Induction step.
  when(running) {
    assert(!inconsistent, "consistent clues judged inconsistent")
    assert(sound(knownSafe | newSafe, knownMine | newMine), "a new deduction is wrong")
    when(reading) { assert(windowSound(readUnk, readMine, readClue, readAdj), "the window read is not a sound snapshot") }
    when(phase === countPhase) { assert(rulesSound(cntSafe, cntMine, cntBad), "a rule result is wrong") }
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
