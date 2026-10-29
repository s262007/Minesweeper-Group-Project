package minesweeper

import chisel3._
import chisel3.util._

import scala.collection.mutable.ArrayBuffer

/** Normalized constraint of one cell for the pair rule (CONTRACT 5.6, v2): there are
  * `residual` mines among the unresolved cells of its 3x3 window (row-major, centre unused).
  */
class ClueConstraint extends Bundle {
  val clue = Bool() // a revealed active cell whose count is consistent with the knowledge
  val residual = UInt(4.W)
  val window = UInt(9.W)
  val size = UInt(4.W)
}

/** Constraint solver (CONTRACT 5.6).
  *
  * v1, single-clue propagation to a fixpoint. Every cycle, each revealed clue n counts its
  * deduced-mine neighbours M and its unknown neighbours U (active, hidden, not yet deduced).
  * With k = clue - M: k == 0 makes all of U safe, k == |U| makes all of U mines. All clues
  * apply the rules in parallel on the same knowledge, then the new deductions are added.
  * A cycle without a new deduction is the fixpoint: done pulses on the next cycle with valid
  * set. Clues with k < 0 or k > |U|, or a cell deduced both ways, mean the input is
  * inconsistent: done pulses with contradiction set, valid low and no hints.
  *
  * v2 (cfg.subsetRule) keeps v1 and adds the pair rule. At each v1 fixpoint it sweeps the
  * clues, cfg.lanes per cycle; each one is compared with the clues up to two cells away
  * (half of the 5x5 window, so each pair once): I = A & B, X = A - B, Y = B - A,
  * L = max(0, a - |X|, b - |Y|), U = min(|I|, a, b). L > U is a contradiction; L == U == q
  * gives q mines in I, a - q in X and b - q in Y, each tested for zero or all. Deductions are
  * added at once. A sweep that adds something goes back to v1; one that adds nothing is the
  * fixpoint. The rules are monotone, so every lane count reaches the same fixpoint.
  *
  * cycles counts evaluation cycles of the last run (v1 and sweep steps), including the
  * final one without progress (or the one that found the contradiction).
  *
  * The board input has no active bit, so the active area is rebuilt from the config
  * captured at start. The Core holds the board stable during a run (CONTRACT 5.1);
  * it is not copied. A hint is never shown on a cell that is revealed now.
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
  val residual = Wire(Vec(p.cells, UInt(4.W)))
  val unknownCount = Wire(Vec(p.cells, UInt(4.W)))
  for (i <- 0 until p.cells) {
    val nbrs = p.capacityNeighbors(i)
    val m = PopCount(nbrs.map(knownMine(_)))
    val u = PopCount(nbrs.map(unknown(_)))
    val clue = io.board(i).adj
    val mPlusU = m +& u // widened: no unsigned underflow anywhere
    badClue(i) := revealed(i) && (clue < m || clue > mPlusU)
    allSafe(i) := revealed(i) && clue === m
    allMine(i) := revealed(i) && clue === mPlusU
    residual(i) := clue - m // only read where badClue is false, so clue >= m
    unknownCount(i) := u
  }

  def fromAnyNeighbour(rule: Vec[Bool]): UInt =
    VecInit((0 until p.cells).map { i =>
      unknown(i) && VecInit(p.capacityNeighbors(i).map(rule(_))).asUInt.orR
    }).asUInt

  val rule1Safe = fromAnyNeighbour(allSafe)
  val rule1Mine = fromAnyNeighbour(allMine)
  val rule1Inconsistent = badClue.asUInt.orR || (rule1Safe & rule1Mine).orR

  // v2 sweep state; constants in v1, so v1 elaborates exactly as before.
  val sweepSteps = (p.cells + cfg.lanes - 1) / cfg.lanes
  val sweeping = if (cfg.subsetRule) RegInit(false.B) else false.B
  val step = if (cfg.subsetRule) RegInit(0.U(math.max(1, log2Ceil(sweepSteps)).W)) else 0.U
  val sweepProgress = if (cfg.subsetRule) RegInit(false.B) else false.B

  /** Storage index of the cell (dr, dc) away from i, if it exists. */
  def at(i: Int, dr: Int, dc: Int): Option[Int] = {
    val (r, c) = (i / p.maxCols + dr, i % p.maxCols + dc)
    if (r >= 0 && r < p.maxRows && c >= 0 && c < p.maxCols) Some(r * p.maxCols + c) else None
  }

  /** The 3x3 window offsets, in the bit order of ClueConstraint.window. */
  val window: Seq[(Int, Int)] = for (dr <- -1 to 1; dc <- -1 to 1) yield (dr, dc)

  /** The pair rule for the clue b at the sweep centre and the clue a at (dr, dc) from it.
    * Returns the deductions in b's window, those in a's window, and the contradiction flag.
    */
  def pairRule(b: ClueConstraint, a: ClueConstraint, dr: Int, dc: Int): (UInt, UInt, UInt, UInt, Bool) = {
    // Window positions the two windows share, in b's and in a's numbering.
    val shared = for {
      ((wr, wc), k) <- window.zipWithIndex
      (ar, ac) = (wr - dr, wc - dc)
      if ar.abs <= 1 && ac.abs <= 1
    } yield (k, (ar + 1) * 3 + (ac + 1))
    val inB = shared.map(s => 1 << s._1).sum.U(9.W)
    val inA = shared.map(s => 1 << s._2).sum.U(9.W)
    val iSize = PopCount(b.window & inB) // the same cells seen from b or from a
    val xSize = a.size - iSize
    val ySize = b.size - iSize
    def max(x: UInt, y: UInt) = Mux(x > y, x, y)
    def min(x: UInt, y: UInt) = Mux(x < y, x, y)
    val lo = max(Mux(a.residual > xSize, a.residual - xSize, 0.U), Mux(b.residual > ySize, b.residual - ySize, 0.U))
    val hi = min(iSize, min(a.residual, b.residual))
    val on = a.clue && b.clue
    val exact = on && lo === hi
    def decide(mines: UInt, size: UInt): (Bool, Bool) = (exact && mines === 0.U, exact && mines === size)
    val (xSafe, xMine) = decide(a.residual - lo, xSize)
    val (ySafe, yMine) = decide(b.residual - lo, ySize)
    val (iSafe, iMine) = decide(lo, iSize)
    val bSafe = ((Fill(9, iSafe) & inB) | (Fill(9, ySafe) & ~inB)) & b.window
    val bMine = ((Fill(9, iMine) & inB) | (Fill(9, yMine) & ~inB)) & b.window
    val aSafe = Fill(9, xSafe) & ~inA & a.window
    val aMine = Fill(9, xMine) & ~inA & a.window
    (bSafe, bMine, aSafe, aMine, on && lo > hi)
  }

  /** One sweep step: lane l handles the centre l + step * lanes. */
  def pairSweep(): (UInt, UInt, Bool) = {
    val cons = Wire(Vec(p.cells, new ClueConstraint))
    for (i <- 0 until p.cells) {
      cons(i).clue := revealed(i) && !badClue(i)
      cons(i).residual := residual(i)
      cons(i).window := VecInit(window.map { case (dr, dc) =>
        if (dr == 0 && dc == 0) false.B else at(i, dr, dc).map(unknown(_)).getOrElse(false.B)
      }).asUInt
      cons(i).size := unknownCount(i)
    }
    val none = 0.U.asTypeOf(new ClueConstraint)
    val partners = for (dr <- 0 to 2; dc <- -2 to 2 if dr > 0 || dc > 0) yield (dr, dc)
    val safeTerms = Array.fill(p.cells)(ArrayBuffer.empty[Bool])
    val mineTerms = Array.fill(p.cells)(ArrayBuffer.empty[Bool])
    val badTerms = ArrayBuffer.empty[Bool]
    for (l <- 0 until cfg.lanes) {
      val centres = (l until p.cells by cfg.lanes).toIndexedSeq
      def pick(dr: Int, dc: Int): ClueConstraint =
        MuxLookup(step, none)(centres.zipWithIndex.map { case (c, s) => s.U -> at(c, dr, dc).map(cons(_)).getOrElse(none) })
      val centre = pick(0, 0)
      val centreSafe = ArrayBuffer.empty[UInt]
      val centreMine = ArrayBuffer.empty[UInt]
      for ((dr, dc) <- partners) {
        val (bSafe, bMine, aSafe, aMine, bad) = pairRule(centre, pick(dr, dc), dr, dc)
        centreSafe += bSafe
        centreMine += bMine
        badTerms += bad
        for ((c, s) <- centres.zipWithIndex; pc <- at(c, dr, dc); ((wr, wc), k) <- window.zipWithIndex; g <- at(pc, wr, wc)) {
          safeTerms(g) += step === s.U && aSafe(k)
          mineTerms(g) += step === s.U && aMine(k)
        }
      }
      val bSafeAll = centreSafe.reduce(_ | _)
      val bMineAll = centreMine.reduce(_ | _)
      for ((c, s) <- centres.zipWithIndex; ((wr, wc), k) <- window.zipWithIndex; g <- at(c, wr, wc)) {
        safeTerms(g) += step === s.U && bSafeAll(k)
        mineTerms(g) += step === s.U && bMineAll(k)
      }
    }
    def gather(terms: Array[ArrayBuffer[Bool]]): UInt =
      VecInit(terms.toSeq.map(t => if (t.isEmpty) false.B else VecInit(t.toSeq).asUInt.orR)).asUInt
    (gather(safeTerms), gather(mineTerms), VecInit(badTerms.toSeq).asUInt.orR)
  }

  val (newSafe, newMine, inconsistent) =
    if (!cfg.subsetRule) (rule1Safe, rule1Mine, rule1Inconsistent)
    else {
      val (pairSafe, pairMine, pairBad) = pairSweep()
      (Mux(sweeping, pairSafe, rule1Safe), Mux(sweeping, pairMine, rule1Mine),
        Mux(sweeping, pairBad || (pairSafe & pairMine).orR, rule1Inconsistent))
    }
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

  def finish(): Unit = {
    running := false.B
    doneReg := true.B
    validReg := true.B
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
    }.elsewhen(progress && !sweeping) {
      knownSafe := knownSafe | newSafe
      knownMine := knownMine | newMine
    }.elsewhen(!sweeping) {
      // v1 fixpoint: v1 is done, v2 starts a pair sweep.
      if (cfg.subsetRule) {
        sweeping := true.B
        step := 0.U
        sweepProgress := false.B
      } else finish()
    }.otherwise {
      if (cfg.subsetRule) {
        knownSafe := knownSafe | newSafe
        knownMine := knownMine | newMine
        val anyProgress = sweepProgress || progress
        when(step === (sweepSteps - 1).U) {
          when(anyProgress) { sweeping := false.B }.otherwise { finish() }
        }.otherwise {
          step := step + 1.U
          sweepProgress := anyProgress
        }
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
    if (cfg.subsetRule) sweeping := false.B
  }

  when(io.clear) {
    running := false.B
    doneReg := false.B
    validReg := false.B
    contraReg := false.B
    knownSafe := 0.U
    knownMine := 0.U
    cycles := 0.U
    if (cfg.subsetRule) sweeping := false.B
  }
}
