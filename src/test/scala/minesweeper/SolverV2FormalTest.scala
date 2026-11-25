package minesweeper

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec

class SolverV2Step(p: MsParams, witness: Boolean = false) extends Module {
  val start = IO(Input(Bool()))
  val clear = IO(Input(Bool()))

  val layoutIn = IO(Input(Vec(p.cells, Bool())))
  val revealedIn = IO(Input(Vec(p.cells, Bool())))
  val presetIn = IO(Input(UInt(p.presetW.W)))
  val layout = Reg(Vec(p.cells, Bool()))
  val revealed = Reg(Vec(p.cells, Bool()))
  val preset = Reg(UInt(p.presetW.W))
  layout := Mux(reset.asBool, layoutIn, layout)
  revealed := Mux(reset.asBool, revealedIn, revealed)
  preset := Mux(reset.asBool, presetIn, preset)

  val dut = withReset(false.B) { Module(new Solver(p, SolverConfig(subsetRule = true))) }
  val (rows, cols) = FormalBoards.dims(p, preset)
  val active = VecInit((0 until p.cells).map(i => (i / p.maxCols).U < rows && (i % p.maxCols).U < cols)).asUInt
  val mine = layout.asUInt & active
  val count = VecInit((0 until p.cells).map(i => PopCount(FormalBoards.neighbours(p, i).map(mine(_))).pad(4)))

  dut.io.clear := clear
  dut.io.start.valid := start
  dut.io.start.bits.config.preset := preset
  dut.io.start.bits.config.rows := rows
  dut.io.start.bits.config.cols := cols
  dut.io.start.bits.config.mines := 0.U
  for (i <- 0 until p.cells) {
    dut.io.board(i).revealed := revealed(i)
    dut.io.board(i).adj := Mux(revealed(i), count(i), 0.U)
  }

  val knownSafe = BoringUtils.bore(dut.knownSafe)
  val knownMine = BoringUtils.bore(dut.knownMine)
  val running = BoringUtils.bore(dut.running)
  val inconsistent = BoringUtils.bore(dut.inconsistent)
  val dutRows = BoringUtils.bore(dut.rows)
  val dutCols = BoringUtils.bore(dut.cols)
  val rowOn = BoringUtils.bore(dut.rowOn)
  val colOn = BoringUtils.bore(dut.colOn)
  val row = BoringUtils.bore(dut.row)
  val phase = BoringUtils.bore(dut.phase)
  val winUnk = BoringUtils.bore(dut.winUnk)
  val winMine = BoringUtils.bore(dut.winMine)
  val winClue = BoringUtils.bore(dut.winClue)
  val winAdj = BoringUtils.bore(dut.winAdj)
  val ruleSafe = BoringUtils.bore(dut.ruleSafe)
  val ruleMine = BoringUtils.bore(dut.ruleMine)
  val ruleBad = BoringUtils.bore(dut.ruleBad)
  val pairMode = BoringUtils.bore(dut.pairMode)
  val pphase = BoringUtils.bore(dut.pphase)
  val pairRow = BoringUtils.bore(dut.pairRow)
  val col = BoringUtils.bore(dut.col)
  val pwUnk = BoringUtils.bore(dut.pwUnk)
  val pwMine = BoringUtils.bore(dut.pwMine)
  val pwClue = BoringUtils.bore(dut.pwClue)
  val pwAdj = BoringUtils.bore(dut.pwAdj)
  val selUnk = BoringUtils.bore(dut.selUnk)
  val selMine = BoringUtils.bore(dut.selMine)
  val selClue = BoringUtils.bore(dut.selClue)
  val selAdj = BoringUtils.bore(dut.selAdj)
  val cons = BoringUtils.bore(dut.cons)
  val consNext = BoringUtils.bore(dut.consNext)
  val consBadR = BoringUtils.bore(dut.consBadR)
  val consBad = BoringUtils.bore(dut.consBad)
  val bnd = BoringUtils.bore(dut.bnd)
  val bndNext = BoringUtils.bore(dut.bndNext)
  val dec = BoringUtils.bore(dut.dec)
  val decNext = BoringUtils.bore(dut.decNext)
  val pendSafe = BoringUtils.bore(dut.pendSafe)
  val pendMine = BoringUtils.bore(dut.pendMine)
  val pairBadAcc = BoringUtils.bore(dut.pairBadAcc)

  val rowSlots = 1 << dut.rowW
  val colSlots = 1 << p.colW
  def inGrid(r: Int, c: Int): Boolean = r >= 0 && r < p.maxRows && c >= 0 && c < p.maxCols
  def idx(r: Int, c: Int): Int = r * p.maxCols + c

  def rowAt[T <: Data](sel: UInt, dr: Int, zero: T)(f: (Int, Int) => T): Vec[T] =
    VecInit((0 until p.maxCols).map { c =>
      VecInit((0 until rowSlots).map(r => if (inGrid(r + dr, c)) f(r + dr, c) else zero))(sel)
    })
  def cellAt[T <: Data](selR: UInt, dr: Int, selC: UInt, dc: Int, zero: T)(f: (Int, Int) => T): T =
    VecInit((0 until rowSlots).map { r =>
      VecInit((0 until colSlots).map(c => if (inGrid(r + dr, c + dc)) f(r + dr, c + dc) else zero))(selC)
    })(selR)

  val actF = (r: Int, c: Int) => active(idx(r, c))
  val mineF = (r: Int, c: Int) => mine(idx(r, c))
  val revF = (r: Int, c: Int) => revealed(idx(r, c))
  val countF = (r: Int, c: Int) => count(idx(r, c))

  def cellOk(a: Bool, m: Bool, rv: Bool, u: Bool, k: Bool): Bool =
    (!u || (a && !rv)) && (!k || m) && !(u && k) && (!(a && !rv && !u && !k) || !m)

  def rowOk(sel: UInt, dr: Int, unk: UInt, km: UInt): Bool = {
    val (a, m, rv) = (rowAt(sel, dr, false.B)(actF), rowAt(sel, dr, false.B)(mineF), rowAt(sel, dr, false.B)(revF))
    VecInit((0 until p.maxCols).map(c => cellOk(a(c), m(c), rv(c), unk(c), km(c)))).asUInt.andR
  }
  def clueRowOk(sel: UInt, dr: Int, clue: UInt, adj: Vec[UInt]): Bool = {
    val (a, rv) = (rowAt(sel, dr, false.B)(actF), rowAt(sel, dr, false.B)(revF))
    val n = rowAt(sel, dr, 0.U(4.W))(countF)
    VecInit((0 until p.maxCols).map(c => !clue(c) || (a(c) && rv(c) && adj(c) === n(c)))).asUInt.andR
  }

  def sound(safe: UInt, mines: UInt): Bool =
    (mines & ~mine) === 0.U && (safe & mine) === 0.U && ((safe | mines) & ~active) === 0.U

  val runV1 = running && !pairMode
  val runPair = running && pairMode
  def ph(k: Int): Bool = pphase === k.U
  def phIn(a: Int, b: Int): Bool = pphase >= a.U && pphase <= b.U

  val hCfg = dutRows === rows && dutCols === cols &&
    VecInit((0 until p.maxRows).map(r => rowOn(r) === (r.U < rows))).asUInt.andR &&
    VecInit((0 until p.maxCols).map(c => colOn(c) === (c.U < cols))).asUInt.andR
  val hKnow = sound(knownSafe, knownMine)

  val v1Window = (0 until 3).map(d => rowOk(row, d - 1, winUnk(d), winMine(d))).reduce(_ && _) &&
    clueRowOk(row, 0, winClue, winAdj)
  val mineW = (0 until 3).map(d => rowAt(row, d - 1, false.B)(mineF))
  def ruleNbrs(c: Int): Seq[(Int, Int)] =
    for (d <- 0 until 3; cc <- c - 1 to c + 1 if cc >= 0 && cc < p.maxCols && !(d == 1 && cc == c)) yield (d, cc)
  val v1Rules = VecInit((0 until p.maxCols).map { c =>
    val ok = ruleNbrs(c).map { case (d, cc) =>
      val u = winUnk(d)(cc)
      (!(ruleSafe(c) && u) || !mineW(d)(cc)) && (!(ruleMine(c) && u) || mineW(d)(cc))
    }
    !ruleBad(c) && VecInit(ok).asUInt.andR
  }).asUInt.andR
  val hV1Window = !(runV1 && (phase === 1.U || phase === 2.U)) || v1Window
  val hV1Rules = !(runV1 && phase === 2.U) || v1Rules

  val rowWhere = MuxLookup(pphase, row === pairRow)(Seq(
    1.U -> (row === pairRow + 1.U),
    2.U -> (row === pairRow + 2.U),
    9.U -> (row === pairRow + 2.U)))
  val hControl = !runPair || (pphase <= 9.U && pairRow < rows && rowWhere)

  def pwRows(n: Int): Bool = (0 until n).map(i => rowOk(pairRow, i - 1, pwUnk(i), pwMine(i))).reduce(_ && _)
  def pwClues(n: Int): Bool = (0 until n).map(k => clueRowOk(pairRow, k, pwClue(k), pwAdj(k))).reduce(_ && _)
  val hWindow = !runPair ||
    ((!ph(1) || (pwRows(3) && pwClues(1))) && (!ph(2) || (pwRows(4) && pwClues(2))) && (!phIn(3, 7) || (pwRows(5) && pwClues(3))))

  val regionOk = {
    val cells = for (i <- 0 until PairRule.regionRows; j <- 0 until PairRule.regionCols) yield {
      def at(f: (Int, Int) => Bool) = cellAt(pairRow, i - 1, col, j - 3, false.B)(f)
      cellOk(at(actF), at(mineF), at(revF), selUnk(i)(j), selMine(i)(j))
    }
    val clues = PairRule.clueCells.zipWithIndex.map { case ((cr, cj), n) =>
      def at(f: (Int, Int) => Bool) = cellAt(pairRow, cr - 1, col, cj - 3, false.B)(f)
      !selClue(n) || (at(actF) && at(revF) && selAdj(n) === cellAt(pairRow, cr - 1, col, cj - 3, 0.U(4.W))(countF))
    }
    VecInit(cells ++ clues).asUInt.andR
  }
  val hRegion = !(runPair && phIn(4, 7)) || regionOk
  val hCons = !(runPair && phIn(5, 7)) || cons.asUInt === consNext.asUInt
  val hConsBad = !(runPair && ph(5)) || consBadR.asUInt === consBad.asUInt
  val hBounds = !(runPair && phIn(6, 7)) || bnd.asUInt === bndNext.asUInt
  val hDecisions = !(runPair && ph(7)) || dec.asUInt === decNext.asUInt

  val pendOk = (0 until PairRule.regionRows).map { i =>
    val (a, m) = (rowAt(pairRow, i - 1, false.B)(actF), rowAt(pairRow, i - 1, false.B)(mineF))
    VecInit((0 until p.maxCols).map(c => (!pendSafe(i)(c) || (a(c) && !m(c))) && (!pendMine(i)(c) || m(c)))).asUInt.andR
  }.reduce(_ && _)
  val hPending = !(runPair && phIn(1, 9)) || (pendOk && !pairBadAcc)

  val invariant = Seq(
    "configuration" -> hCfg, "knowledge" -> hKnow, "v1 window" -> hV1Window, "v1 rules" -> hV1Rules,
    "pair control" -> hControl, "pair window" -> hWindow, "region" -> hRegion, "constraints" -> hCons,
    "constraint check" -> hConsBad, "bounds" -> hBounds, "decisions" -> hDecisions, "pending rows" -> hPending)

  val step = RegInit(0.U(2.W))
  when(step =/= 3.U) { step := step + 1.U }
  val first = !reset.asBool && step === 0.U
  val second = !reset.asBool && step === 1.U

  assume(preset < p.presets.size.U)
  for (i <- 0 until p.cells) assume(!revealed(i) || (active(i) && !layout(i)))

  val nontrivial = VecInit(dec.zip(bnd).map { case (d, s) =>
    ((d.xSafe || d.xMine) && s.xSize =/= 0.U) || ((d.ySafe || d.yMine) && s.ySize =/= 0.U) ||
      ((d.iSafe || d.iMine) && s.iSize =/= 0.U)
  }).asUInt.orR
  if (witness) when(first) {
    for ((_, h) <- invariant) assume(h)
    assert(!(runPair && ph(7) && nontrivial), "witness: a nontrivial pair decision")
  }

  if (!witness) when(first) {
    for ((_, h) <- invariant) assume(h)
    assert(!(running && inconsistent), "consistent clues reported as a contradiction")
    for (i <- 0 until p.cells) {
      val h = dut.io.hints(i)
      assert(!(h.safe && mine(i)), s"cell $i: safe hint on a mine")
      assert(!(h.mine && !mine(i)), s"cell $i: mine hint on a safe cell")
      assert(!(h.safe && h.mine), s"cell $i: both hints")
      assert(!((h.safe || h.mine) && (revealed(i) || !active(i))), s"cell $i: hint on a revealed or inactive cell")
    }
  }
  if (!witness) when(second) {
    for ((name, h) <- invariant) assert(h, s"invariant not kept: $name")
  }
}

class SolverV2FormalTest extends AnyFlatSpec with ChiselScalatestTester with Formal {
  "The Solver v2 induction hypothesis" should "allow a nontrivial pair decision (non-vacuity, 3x3)" taggedAs FormalTag in {
    assertThrows[FailedBoundedCheckException] {
      verify(new SolverV2Step(FormalBoards.configs.head._2, witness = true), Seq(BoundedCheck(2)))
    }
  }

  for ((label, p) <- FormalBoards.configs) {
    s"Solver v2 on $label" should "keep the pair pass and the knowledge sound at every step (induction)" taggedAs (FormalTag, SlowTag) in {
      verify(new SolverV2Step(p), Seq(BoundedCheck(3)))
    }
  }
}
