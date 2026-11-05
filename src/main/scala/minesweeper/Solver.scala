package minesweeper

import chisel3._
import chisel3.util._

import scala.collection.mutable.ArrayBuffer

/** The constraint of one clue in the pair pass (v2): its unknown neighbours and their mines. */
class ClueConstraint extends Bundle {
  val clue = Bool()        // a revealed clue (an unsatisfiable one ends the run as a contradiction)
  val residual = UInt(4.W) // the clue minus the deduced mines around it
  val window = UInt(9.W)   // its unknown neighbours, 3x3 row-major (the centre bit is 0)
  val size = UInt(4.W)     // the number of unknown neighbours
}

/** First half of the pair rule for a clue b and a clue a: the part sizes and the terms of L and U. */
class PairBounds extends Bundle {
  val on = Bool()     // both are clues
  val iSize = UInt(4.W)
  val xSize = UInt(4.W)
  val ySize = UInt(4.W)
  val loA = UInt(4.W) // max(0, a - |X|)
  val loB = UInt(4.W) // max(0, b - |Y|)
  val hi = UInt(4.W)  // U = min(|I|, a, b)
}

/** Second half: with L == U == q each part is tested for zero or all mines; L > U is a contradiction. */
class PairDecision extends Bundle {
  val xSafe = Bool()
  val xMine = Bool()
  val ySafe = Bool()
  val yMine = Bool()
  val iSafe = Bool()
  val iMine = Bool()
  val bad = Bool()
}

/** The pair rule of CONTRACT 5.6 (v2) as functions, so that the formal harness checks the same logic.
  *
  * For the clues b and a with unknown sets A and B: I = A & B, X = A - B, Y = B - A,
  * L = max(0, a - |X|, b - |Y|), U = min(|I|, a, b). L > U is a contradiction; L == U == q
  * gives q mines in I, a - q in X and b - q in Y, each tested for zero or all.
  *
  * The pair pass works on a region of 5 rows by 7 columns: grid rows r - 1 to r + 3 and
  * columns c - 3 to c + 3. The centre clue b is at (1, 3); its partners are the clues in half
  * of its 5x5 window, so each pair of clues at most two cells apart is compared once.
  */
object PairRule {
  /** 3x3 window offsets, in the bit order of ClueConstraint.window. */
  val window: Seq[(Int, Int)] = for (dr <- -1 to 1; dc <- -1 to 1) yield (dr, dc)
  val partners: Seq[(Int, Int)] = for (dr <- 0 to 2; dc <- -2 to 2 if dr > 0 || dc > 0) yield (dr, dc)
  val regionRows = 5
  val regionCols = 7
  /** The clue cells of the region: the centre, then its partners in the order of `partners`. */
  val clueCells: Seq[(Int, Int)] = (1, 3) +: partners.map { case (dr, dc) => (1 + dr, 3 + dc) }

  /** Window positions shared by b and the clue a at (dr, dc) from b, as (bit in b, bit in a). */
  def shared(dr: Int, dc: Int): Seq[(Int, Int)] = for {
    ((wr, wc), k) <- window.zipWithIndex
    (ar, ac) = (wr - dr, wc - dc)
    if ar.abs <= 1 && ac.abs <= 1
  } yield (k, (ar + 1) * 3 + (ac + 1))

  /** A clue's constraint from the unknown and deduced-mine bits of its 3x3 neighbourhood
    * (row-major, centre ignored). Also returns whether the clue is unsatisfiable on its own.
    */
  def constraint(isClue: Bool, adj: UInt, unk: Seq[Bool], mine: Seq[Bool]): (ClueConstraint, Bool) = {
    val nb = window.indices.filter(_ != 4)
    val m = PopCount(nb.map(mine(_)))
    val u = PopCount(nb.map(unk(_)))
    val bad = isClue && (adj < m || adj > (m +& u))
    val c = Wire(new ClueConstraint)
    c.clue := isClue
    c.residual := adj - m // meaningful where bad is false, so adj >= m
    c.window := VecInit(window.indices.map(k => if (k == 4) false.B else unk(k))).asUInt
    c.size := u
    (c, bad)
  }

  def bounds(b: ClueConstraint, a: ClueConstraint, dr: Int, dc: Int): PairBounds = {
    val inB = shared(dr, dc).map(s => 1 << s._1).sum.U(9.W)
    val iSize = PopCount(b.window & inB)
    val xSize = a.size - iSize // I's unknown cells are unknown in both windows, so no underflow
    val ySize = b.size - iSize
    val s = Wire(new PairBounds)
    s.on := a.clue && b.clue
    s.iSize := iSize
    s.xSize := xSize
    s.ySize := ySize
    s.loA := Mux(a.residual > xSize, a.residual - xSize, 0.U)
    s.loB := Mux(b.residual > ySize, b.residual - ySize, 0.U)
    val ab = Mux(a.residual < b.residual, a.residual, b.residual)
    s.hi := Mux(iSize < ab, iSize, ab)
    s
  }

  def decide(b: ClueConstraint, a: ClueConstraint, s: PairBounds): PairDecision = {
    val lo = Mux(s.loA > s.loB, s.loA, s.loB)
    val exact = s.on && lo === s.hi
    val xMines = a.residual - lo // lo <= hi <= a when exact
    val yMines = b.residual - lo
    val d = Wire(new PairDecision)
    d.xSafe := exact && xMines === 0.U
    d.xMine := exact && xMines === s.xSize
    d.ySafe := exact && yMines === 0.U
    d.yMine := exact && yMines === s.ySize
    d.iSafe := exact && lo === 0.U
    d.iMine := exact && lo === s.iSize
    d.bad := s.on && lo > s.hi
    d
  }

  /** The pair's deductions in b's window (I and Y) and in a's window (X only), as 9-bit masks. */
  def deductions(b: ClueConstraint, a: ClueConstraint, dr: Int, dc: Int, d: PairDecision): (UInt, UInt, UInt, UInt) = {
    val sh = shared(dr, dc)
    val inB = sh.map(s => 1 << s._1).sum.U(9.W)
    val inA = sh.map(s => 1 << s._2).sum.U(9.W)
    val bSafe = ((Fill(9, d.iSafe) & inB) | (Fill(9, d.ySafe) & ~inB)) & b.window
    val bMine = ((Fill(9, d.iMine) & inB) | (Fill(9, d.yMine) & ~inB)) & b.window
    val aSafe = Fill(9, d.xSafe) & ~inA & a.window
    val aMine = Fill(9, d.xMine) & ~inA & a.window
    (bSafe, bMine, aSafe, aMine)
  }

  /** All deductions of the region, from the 13 constraints and the 12 pair decisions. */
  def regionDeductions(cons: Seq[ClueConstraint], dec: Seq[PairDecision]): (Seq[Seq[Bool]], Seq[Seq[Bool]]) = {
    val safe = Array.fill(regionRows, regionCols)(ArrayBuffer.empty[Bool])
    val mine = Array.fill(regionRows, regionCols)(ArrayBuffer.empty[Bool])
    for (((dr, dc), n) <- partners.zipWithIndex) {
      val (bS, bM, aS, aM) = deductions(cons(0), cons(n + 1), dr, dc, dec(n))
      for (((wr, wc), k) <- window.zipWithIndex if k != 4) {
        safe(1 + wr)(3 + wc) += bS(k)
        mine(1 + wr)(3 + wc) += bM(k)
        safe(1 + dr + wr)(3 + dc + wc) += aS(k)
        mine(1 + dr + wr)(3 + dc + wc) += aM(k)
      }
    }
    def orAll(t: ArrayBuffer[Bool]): Bool = if (t.isEmpty) false.B else VecInit(t.toSeq).asUInt.orR
    (safe.toSeq.map(_.toSeq.map(orAll)), mine.toSeq.map(_.toSeq.map(orAll)))
  }
}

/** Constraint solver (CONTRACT 5.6): v1 single-clue propagation, and v2 pair rule when
  * cfg.subsetRule is set. One row of clues is evaluated per step, so there is one row of
  * rule logic instead of one copy per cell: the fully parallel v1 used about 26,600 LUTs at 30x24.
  *
  * v1, a step on row r in three cycles, so that each cycle has a short logic path (100 MHz target):
  *  - read: a window of three rows, r - 1 to r + 1, is copied into registers: the unknown
  *    cells (active, hidden, not yet deduced), the deduced mines and the clues of row r;
  *  - count: each revealed clue of row r counts its deduced-mine neighbours M and its
  *    unknown neighbours U in the window. With k = clue - M: k == 0 makes all of U safe,
  *    k == |U| makes all of U mines, k < 0 or k > |U| is a contradiction. The results are
  *    registered;
  *  - write: the deductions, in rows r - 1 to r + 1, are added to the knowledge.
  * A pass covers the active rows. A pass without a new deduction is the v1 fixpoint.
  *
  * v2, after each v1 fixpoint: a pair pass over the active rows (PairRule). For row r it reads
  * rows r - 1 to r + 3 into registers through the same row multiplexers (three cycles), then
  * takes each active column c in turn: select the 5x7 region around (r, c), compute the 13
  * constraints, the bounds, the decisions, and add the region's deductions to a pending row
  * buffer (five cycles per column). Finally the pending rows are written with the v1 write
  * path (two cycles). A pair pass that adds something goes back to v1 passes; one that adds
  * nothing is the fixpoint. cfg.lanes is not used: there is one centre clue per column step.
  *
  * The fixpoint: done pulses on the next cycle with valid set. An unsatisfiable clue, a pair
  * with L > U, or a cell deduced both ways in one step, ends the run with contradiction set,
  * valid low and no hints. The knowledge is not cleared then: hints are only shown while
  * valid is set. On a consistent board the rules only add true facts, so the order of the
  * rows and columns does not change the fixpoint; only the cycle count differs.
  *
  * cycles counts the cycles of the last run. Bound, v1: every pass but the last adds a
  * deduction, so at most (unknown cells + 1) passes of 3 * rows cycles. v2: a v1 pass takes one
  * more cycle (the end-of-pass decision, from registers only); at most (unknown cells + 1) pair
  * passes of rows * (5 * cols + 5) cycles, with v1 passes between them.
  *
  * The board input has no active bit, so the active area is rebuilt from the config
  * captured at start. The Core holds the board stable during a run (CONTRACT 5.1);
  * it is not copied. A hint is never shown on a cell that is revealed now.
  */
class Solver(p: MsParams, cfg: SolverConfig = SolverConfig()) extends Module {
  require(cfg.lanes <= p.cells, s"solver lanes (${cfg.lanes}) must not exceed the ${p.cells} cells")
  val io = IO(new SolverIO(p))
  private val v2 = cfg.subsetRule

  val running = RegInit(false.B)
  val doneReg = RegInit(false.B)
  val validReg = RegInit(false.B)
  val contraReg = RegInit(false.B)
  val knownSafe = RegInit(0.U(p.cells.W))
  val knownMine = RegInit(0.U(p.cells.W))
  val cycles = RegInit(0.U(p.cycleW.W))
  val rows = RegInit(0.U(p.rowsW.W))
  val cols = RegInit(0.U(p.colsW.W))
  // Row of the current step. The pair pass also points it at rows up to r + 3.
  val rowW = if (v2) log2Ceil(p.maxRows + 3) else p.rowW
  val row = RegInit(0.U(rowW.W))
  val passProgress = RegInit(false.B)

  // Phases of a step. The unused encoding behaves as read.
  val count = 1.U(2.W)
  val write = 2.U(2.W)
  val phase = RegInit(0.U(2.W))
  val reading = phase =/= count && phase =/= write

  // Active area of the captured configuration: one comparator per row and per column. v2
  // registers the comparisons at start, which keeps them off its longer read path.
  val rowOn =
    if (v2) RegInit(VecInit(Seq.fill(p.maxRows)(false.B))) else VecInit((0 until p.maxRows).map(r => r.U < rows))
  val colOn =
    if (v2) RegInit(VecInit(Seq.fill(p.maxCols)(false.B))) else VecInit((0 until p.maxCols).map(c => c.U < cols))
  val active = VecInit((0 until p.cells).map(i => rowOn(i / p.maxCols) && colOn(i % p.maxCols))).asUInt

  val shown = VecInit(io.board.map(_.revealed)).asUInt
  val revealed = shown & active
  val unknown = active & ~revealed & ~knownSafe & ~knownMine

  def inRows(r: Int): Boolean = r >= 0 && r < p.maxRows
  def inCols(c: Int): Boolean = c >= 0 && c < p.maxCols

  // Read: row multiplexers with one input per value of the row register. Window row d holds
  // grid row row + d - 1; rows outside the storage read as empty.
  val slots = 1 << rowW
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
  val v1Bad = ruleBad.asUInt.orR || (0 until 3).map(d => (safeWin(d) & mineWin(d)).orR).reduce(_ || _)
  val progress = (0 until 3).map(d => (safeWin(d) | mineWin(d)).orR).reduce(_ || _)

  // ---------------------------------------------------------------- v2 pair pass
  // Pair phases: read rows r - 1 .. r + 3 (three cycles), then per column: select, constraints,
  // bounds, decisions, accumulate; then write the pending rows (two cycles).
  val pRead0 = 0
  val pRead1 = 1
  val pRead2 = 2
  val pSelect = 3
  val pConstrain = 4
  val pBound = 5
  val pDecide = 6
  val pAccumulate = 7
  val pWrite0 = 8
  val pWrite1 = 9
  val pairMode = if (v2) RegInit(false.B) else false.B
  val pphase = RegInit(0.U(4.W))
  def inPhase(k: Int): Bool = pphase === k.U
  val pairRow = RegInit(0.U(rowW.W))
  val pairProgress = RegInit(false.B)
  val pairBadAcc = RegInit(false.B)
  val colSlots = 1 << p.colW
  val col = RegInit(0.U(p.colW.W))
  // Pair window: unknown and deduced-mine rows r - 1 .. r + 3, clue rows r .. r + 2.
  val pwUnk = Reg(Vec(PairRule.regionRows, UInt(p.maxCols.W)))
  val pwMine = Reg(Vec(PairRule.regionRows, UInt(p.maxCols.W)))
  val pwClue = Reg(Vec(3, UInt(p.maxCols.W)))
  val pwAdj = Reg(Vec(3, Vec(p.maxCols, UInt(4.W))))
  // The region around column col, and the registered stages of the pair rule.
  val selUnk = Reg(Vec(PairRule.regionRows, Vec(PairRule.regionCols, Bool())))
  val selMine = Reg(Vec(PairRule.regionRows, Vec(PairRule.regionCols, Bool())))
  val selClue = Reg(Vec(PairRule.clueCells.size, Bool()))
  val selAdj = Reg(Vec(PairRule.clueCells.size, UInt(4.W)))
  val cons = Reg(Vec(PairRule.clueCells.size, new ClueConstraint))
  val consBadR = Reg(Vec(PairRule.clueCells.size, Bool()))
  val bnd = Reg(Vec(PairRule.partners.size, new PairBounds))
  val dec = Reg(Vec(PairRule.partners.size, new PairDecision))
  val pendSafe = Reg(Vec(PairRule.regionRows, UInt(p.maxCols.W)))
  val pendMine = Reg(Vec(PairRule.regionRows, UInt(p.maxCols.W)))

  def colPick(v: UInt, dj: Int): Bool =
    VecInit((0 until colSlots).map(k => if (inCols(k + dj)) v(k + dj) else false.B))(col)
  def colPickAdj(v: Vec[UInt], dj: Int): UInt =
    VecInit((0 until colSlots).map(k => if (inCols(k + dj)) v(k + dj) else 0.U(4.W)))(col)

  val consNext = Wire(Vec(PairRule.clueCells.size, new ClueConstraint))
  val consBad = Wire(Vec(PairRule.clueCells.size, Bool()))
  for (((cr, cj), n) <- PairRule.clueCells.zipWithIndex) {
    val unk9 = PairRule.window.map { case (wr, wc) => selUnk(cr + wr)(cj + wc) }
    val mine9 = PairRule.window.map { case (wr, wc) => selMine(cr + wr)(cj + wc) }
    val (c, bad) = PairRule.constraint(selClue(n), selAdj(n), unk9, mine9)
    consNext(n) := c
    consBad(n) := bad
  }
  val bndNext = VecInit(PairRule.partners.zipWithIndex.map { case ((dr, dc), n) =>
    PairRule.bounds(cons(0), cons(n + 1), dr, dc)
  })
  val decNext = VecInit(PairRule.partners.zipWithIndex.map { case (_, n) =>
    PairRule.decide(cons(0), cons(n + 1), bnd(n))
  })
  val (regSafe, regMine) = PairRule.regionDeductions(cons, dec)
  // Pending row bit (i, gc) takes region bit (i, gc - col + 3).
  def accumulate(pend: UInt, reg: Seq[Bool]): UInt = pend | VecInit((0 until p.maxCols).map { gc =>
    val terms = for (j <- 0 until PairRule.regionCols if gc - j + 3 >= 0 && gc - j + 3 < colSlots)
      yield col === (gc - j + 3).U && reg(j)
    VecInit(terms).asUInt.orR
  }).asUInt
  val pendConflict = (0 until PairRule.regionRows).map(i => (pendSafe(i) & pendMine(i)).orR).reduce(_ || _)
  val pendAny = (0 until PairRule.regionRows).map(i => (pendSafe(i) | pendMine(i)).orR).reduce(_ || _)
  val pairWriting = inPhase(pWrite0) || inPhase(pWrite1)
  val pairBad = pairBadAcc || pendConflict

  // ---------------------------------------------------------------- write path
  /** The window deductions at their grid positions, only in a write phase. In the pair pass the
    * window is pending rows 0..2 (row = r), then pending rows 2..4 (row = r + 2).
    */
  val writing = running && (if (v2) Mux(pairMode, pairWriting, phase === write) else phase === write)
  val writeRow = VecInit((0 until slots).map(k => writing && row === k.U))
  def spread(win: Seq[UInt]): UInt = VecInit((0 until p.cells).map { i =>
    val (r, c) = (i / p.maxCols, i % p.maxCols)
    val terms = for (d <- 0 until 3 if r - (d - 1) >= 0 && r - (d - 1) < slots) yield writeRow(r - (d - 1)) && win(d)(c)
    VecInit(terms).asUInt.orR
  }).asUInt
  def writeWindow(v1Win: Seq[UInt], pend: Vec[UInt]): Seq[UInt] =
    if (!v2) v1Win
    else Seq.tabulate(3)(d => Mux(pairMode, Mux(inPhase(pWrite0), pend(d), pend(d + 2)), v1Win(d)))

  val newSafe = spread(writeWindow(safeWin, pendSafe))
  val newMine = spread(writeWindow(mineWin, pendMine))
  val inconsistent =
    if (v2) running && Mux(pairMode, inPhase(pWrite0) && pairBad, phase === write && v1Bad)
    else writing && v1Bad

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
  def contradiction(): Unit = {
    running := false.B
    doneReg := true.B
    contraReg := true.B
  }
  def startV1Pass(): Unit = {
    row := 0.U
    passProgress := false.B
    phase := 0.U
  }

  /** One cycle of a v1 step. In v2 the end of a pass is decided one cycle later (phase 3), from
    * registers only, and a pass without progress starts a pair pass.
    */
  val decide = 3.U(2.W)
  def v1Cycle(): Unit = {
    val steps = when(phase === count) {
      ruleSafe := cntSafe
      ruleMine := cntMine
      ruleBad := cntBad
      phase := write
    }.elsewhen(phase === write) {
      phase := 0.U
      when(inconsistent) {
        contradiction()
      }.elsewhen(row >= rows - 1.U) {
        if (v2) {
          passProgress := passProgress || progress
          phase := decide
        } else {
          when(passProgress || progress) {
            startV1Pass()
          }.otherwise {
            finish()
          }
        }
      }.otherwise {
        row := row + 1.U
        passProgress := passProgress || progress
      }
    }
    val withDecide = if (!v2) steps else steps.elsewhen(phase === decide) {
      when(passProgress) {
        startV1Pass()
      }.otherwise {
        pairMode := true.B
        pairRow := 0.U
        row := 0.U
        pairProgress := false.B
        pphase := pRead0.U
        phase := 0.U
      }
    }
    withDecide.otherwise {
      winUnk := readUnk
      winMine := readMine
      winClue := readClue
      winAdj := readAdj
      phase := count
    }
  }

  /** One cycle of the pair pass on row pairRow. */
  def pairCycle(): Unit = {
    when(inPhase(pRead0)) {
      for (d <- 0 until 3) {
        pwUnk(d) := readUnk(d)
        pwMine(d) := readMine(d)
      }
      pwClue(0) := readClue
      pwAdj(0) := readAdj
      pendSafe.foreach(_ := 0.U)
      pendMine.foreach(_ := 0.U)
      pairBadAcc := false.B
      row := pairRow + 1.U
      pphase := pRead1.U
    }.elsewhen(inPhase(pRead1)) {
      pwUnk(3) := readUnk(2)
      pwMine(3) := readMine(2)
      pwClue(1) := readClue
      pwAdj(1) := readAdj
      row := pairRow + 2.U
      pphase := pRead2.U
    }.elsewhen(inPhase(pRead2)) {
      pwUnk(4) := readUnk(2)
      pwMine(4) := readMine(2)
      pwClue(2) := readClue
      pwAdj(2) := readAdj
      row := pairRow
      col := 0.U
      pphase := pSelect.U
    }.elsewhen(inPhase(pSelect)) {
      for (i <- 0 until PairRule.regionRows; j <- 0 until PairRule.regionCols) {
        selUnk(i)(j) := colPick(pwUnk(i), j - 3)
        selMine(i)(j) := colPick(pwMine(i), j - 3)
      }
      for (((cr, cj), n) <- PairRule.clueCells.zipWithIndex) {
        selClue(n) := colPick(pwClue(cr - 1), cj - 3)
        selAdj(n) := colPickAdj(pwAdj(cr - 1), cj - 3)
      }
      pphase := pConstrain.U
    }.elsewhen(inPhase(pConstrain)) {
      cons := consNext
      consBadR := consBad
      pphase := pBound.U
    }.elsewhen(inPhase(pBound)) {
      bnd := bndNext
      pairBadAcc := pairBadAcc || consBadR.asUInt.orR
      pphase := pDecide.U
    }.elsewhen(inPhase(pDecide)) {
      dec := decNext
      pphase := pAccumulate.U
    }.elsewhen(inPhase(pAccumulate)) {
      for (i <- 0 until PairRule.regionRows) {
        pendSafe(i) := accumulate(pendSafe(i), regSafe(i))
        pendMine(i) := accumulate(pendMine(i), regMine(i))
      }
      pairBadAcc := pairBadAcc || VecInit(dec.map(_.bad)).asUInt.orR
      when(col >= cols - 1.U) {
        pphase := pWrite0.U
      }.otherwise {
        col := col + 1.U
        pphase := pSelect.U
      }
    }.elsewhen(inPhase(pWrite0)) {
      when(inconsistent) {
        contradiction()
      }.otherwise {
        pairProgress := pairProgress || pendAny
        row := pairRow + 2.U
        pphase := pWrite1.U
      }
    }.otherwise {
      // pWrite1 (and unused encodings): rows r + 1 .. r + 3, then the next row or the end of the pass.
      when(pairRow >= rows - 1.U) {
        when(pairProgress) {
          pairMode := false.B
          startV1Pass()
        }.otherwise {
          finish()
        }
      }.otherwise {
        pairRow := pairRow + 1.U
        row := pairRow + 1.U
        pphase := pRead0.U
      }
    }
  }

  doneReg := false.B
  when(running) {
    cycles := cycles + 1.U
    knownSafe := knownSafe | newSafe
    knownMine := knownMine | newMine
    if (v2) {
      when(pairMode) { pairCycle() }.otherwise { v1Cycle() }
    } else v1Cycle()
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
    startV1Pass()
    if (v2) {
      pairMode := false.B
      for (r <- 0 until p.maxRows) rowOn(r) := r.U < io.start.bits.config.rows
      for (c <- 0 until p.maxCols) colOn(c) := c.U < io.start.bits.config.cols
    }
  }

  when(io.clear) {
    running := false.B
    doneReg := false.B
    validReg := false.B
    contraReg := false.B
    knownSafe := 0.U
    knownMine := 0.U
    cycles := 0.U
    if (v2) pairMode := false.B
  }
}
