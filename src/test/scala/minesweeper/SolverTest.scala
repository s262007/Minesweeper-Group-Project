package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

case class SolveRun(
    safe: Set[(Int, Int)],
    mine: Set[(Int, Int)],
    valid: Boolean,
    contradiction: Boolean,
    cycles: Int
)

/** Reference single-clue propagation (CONTRACT 5.6, v1), written from the rule text with
  * coordinate-based neighbours. Returns None on inconsistent clues, otherwise the deduced
  * safe and mine sets and the number of rule evaluations until the fixpoint.
  */
object ReferenceV1 {
  def solve(
      b: RevealBoard,
      revealed: Set[(Int, Int)],
      clue: ((Int, Int)) => Int
  ): Option[(Set[(Int, Int)], Set[(Int, Int)], Int)] = {
    var safe = Set.empty[(Int, Int)]
    var mine = Set.empty[(Int, Int)]
    var evaluations = 0
    var changed = true
    while (changed) {
      evaluations += 1
      var newSafe = Set.empty[(Int, Int)]
      var newMine = Set.empty[(Int, Int)]
      for (n <- revealed) {
        val nb = b.neighbours(n)
        val known = nb.count(mine)
        val unknown = nb.filter(c => !revealed(c) && !safe(c) && !mine(c))
        val k = clue(n) - known
        if (k < 0 || k > unknown.size) return None
        if (k == 0) newSafe ++= unknown
        if (k == unknown.size) newMine ++= unknown
      }
      if ((newSafe & newMine).nonEmpty) return None
      changed = newSafe.nonEmpty || newMine.nonEmpty
      safe ++= newSafe
      mine ++= newMine
    }
    Some((safe, mine, evaluations))
  }
}

/** Reference v2 (CONTRACT 5.6): rule 1 plus the pair rule on clues at most two cells apart,
  * applied in rounds to the closure. Written from the rule text, like ReferenceV1.
  * Returns None on inconsistent clues, otherwise the deduced safe and mine sets.
  */
object ReferenceV2 {
  def solve(
      b: RevealBoard,
      revealed: Set[(Int, Int)],
      clue: ((Int, Int)) => Int
  ): Option[(Set[(Int, Int)], Set[(Int, Int)])] = {
    var safe = Set.empty[(Int, Int)]
    var mine = Set.empty[(Int, Int)]
    var changed = true
    while (changed) {
      // Normalized constraint of each clue: its unresolved cells and their number of mines.
      val cons = revealed.toSeq.map { n =>
        val nb = b.neighbours(n)
        n -> (nb.filter(c => !revealed(c) && !safe(c) && !mine(c)).toSet, clue(n) - nb.count(mine))
      }
      var newSafe = Set.empty[(Int, Int)]
      var newMine = Set.empty[(Int, Int)]
      def decide(cells: Set[(Int, Int)], k: Int): Unit = {
        if (k == 0) newSafe ++= cells
        if (k == cells.size) newMine ++= cells
      }
      for ((_, (a, k)) <- cons) {
        if (k < 0 || k > a.size) return None
        decide(a, k)
      }
      for (((n1, (a1, k1)), i) <- cons.zipWithIndex; (n2, (a2, k2)) <- cons.drop(i + 1)
           if math.max((n1._1 - n2._1).abs, (n1._2 - n2._2).abs) <= 2) {
        val (in, x, y) = (a1 & a2, a1 -- a2, a2 -- a1)
        val lo = Seq(0, k1 - x.size, k2 - y.size).max
        val hi = Seq(in.size, k1, k2).min
        if (lo > hi) return None
        if (lo == hi) {
          decide(x, k1 - lo)
          decide(y, k2 - lo)
          decide(in, lo)
        }
      }
      if ((newSafe & newMine).nonEmpty) return None
      changed = newSafe.nonEmpty || newMine.nonEmpty
      safe ++= newSafe
      mine ++= newMine
    }
    Some((safe, mine))
  }
}

/** R2-S2 acceptance tests for the v1 solver and R2-S4 tests for v2 (CONTRACT 5.6). */
class SolverTest extends AnyFlatSpec with ChiselScalatestTester {
  private val configs = Seq((4, 4), (7, 5), (9, 9), (16, 8))
    .map { case (cols, rows) => MsParams.forVerification(cols, rows) }

  private val padded = MsParams(
    maxCols = 8, maxRows = 6,
    presets = Vector(GridPreset(0, 8, 6), GridPreset(1, 5, 4)),
    minMines = 0, defaultPresetId = 0, defaultMines = 0, verification = true
  )

  private def cellOf(p: MsParams, i: Int): (Int, Int) = (i / p.maxCols, i % p.maxCols)

  /** Drives the masked SolverCellView like the Board: adj only on revealed active cells. */
  private def pokeBoard(dut: Solver, p: MsParams, b: RevealBoard, clue: ((Int, Int)) => Int): Unit =
    for (i <- 0 until p.cells) {
      val cell = cellOf(p, i)
      val shown = b.inGrid(cell) && b.revealed(cell)
      dut.io.board(i).revealed.poke(shown.B)
      dut.io.board(i).adj.poke((if (shown) clue(cell) else 0).U)
    }

  private def readHints(dut: Solver, p: MsParams): (Set[(Int, Int)], Set[(Int, Int)]) = {
    val all = (0 until p.cells).map(i => (cellOf(p, i), dut.io.hints(i)))
    val safe = all.collect { case (c, h) if h.safe.peekBoolean() => c }.toSet
    val mine = all.collect { case (c, h) if h.mine.peekBoolean() => c }.toSet
    (safe, mine)
  }

  /** One solver run with the handshake and done timing of CONTRACT 5.6. */
  private def solve(dut: Solver, p: MsParams, b: RevealBoard, clue: ((Int, Int)) => Int,
      maxCycles: Int = -1): SolveRun = {
    val bound = if (maxCycles < 0) p.cells + 2 else maxCycles
    dut.io.clear.poke(false.B)
    pokeBoard(dut, p, b, clue)
    val req = dut.io.start.bits.config
    req.preset.poke(b.g.id.U)
    req.cols.poke(b.g.cols.U)
    req.rows.poke(b.g.rows.U)
    req.mines.poke(b.mines.size.U)
    dut.io.start.valid.poke(true.B)
    dut.io.start.ready.expect(true.B)
    dut.clock.step() // admission edge
    dut.io.start.valid.poke(false.B)
    var waited = 0
    while (!dut.io.done.peekBoolean()) {
      assert(waited <= bound, s"solver did not reach a fixpoint within $bound cycles")
      dut.io.busy.expect(true.B)
      dut.io.start.ready.expect(false.B)
      dut.io.valid.expect(false.B) // a new start invalidates the previous hints
      dut.clock.step()
      waited += 1
    }
    dut.io.busy.expect(false.B)
    val valid = dut.io.valid.peekBoolean()
    val contradiction = dut.io.contradiction.peekBoolean()
    val cycles = dut.io.cycles.peek().litValue.toInt
    val (safe, mine) = readHints(dut, p)
    dut.clock.step()
    dut.io.done.expect(false.B) // one-cycle pulse
    dut.io.valid.expect(valid.B) // results stay until clear or a new start
    assert(readHints(dut, p) == ((safe, mine)))
    SolveRun(safe, mine, valid, contradiction, cycles)
  }

  private def truthClue(b: RevealBoard): ((Int, Int)) => Int = b.adj

  /** Hints that every consistent run must respect, whatever the board. */
  private def checkInvariants(b: RevealBoard, run: SolveRun): Unit = {
    assert(run.valid && !run.contradiction)
    assert((run.safe & run.mine).isEmpty, "a cell is both safe and mine")
    val all = run.safe ++ run.mine
    assert(all.forall(b.inGrid), "hint on a padding cell")
    assert(!all.exists(b.revealed), "hint on a revealed cell")
    assert(!run.safe.exists(b.mines), "a mine was declared safe")
    assert(run.mine.subsetOf(b.mines), "a safe cell was declared a mine")
  }

  /** Exact equality with the reference fixpoint, including the evaluation count. */
  private def checkAgainstReference(b: RevealBoard, run: SolveRun): Unit = {
    val Some((safe, mine, evaluations)) = ReferenceV1.solve(b, b.revealed, b.adj)
    assert(run.safe == safe && run.mine == mine, "hints differ from the reference v1 fixpoint")
    assert(run.cycles == evaluations, s"cycles ${run.cycles} != rule evaluations $evaluations")
  }

  /** A board revealed by a few real moves: each click reveals its cascade, as in a game. */
  private def playedBoard(g: GridPreset, rnd: Random, density: Double, clicks: Int): RevealBoard = {
    val all = for (r <- 0 until g.rows; c <- 0 until g.cols) yield (r, c)
    val mines = all.filter(_ => rnd.nextDouble() < density).toSet
    var b = RevealBoard(g, mines)
    for (_ <- 0 until clicks) {
      val candidates = all.filterNot(c => mines(c) || b.revealed(c))
      if (candidates.nonEmpty) {
        val click = candidates(rnd.nextInt(candidates.size))
        b = b.copy(revealed = b.revealed ++ b.expectedLayers(click).flatten)
      }
    }
    b
  }

  "Solver v1" should "find the single hidden neighbour of a 1 in a corner" in {
    val p = MsParams.forVerification(2, 2)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0)), revealed = Set((0, 1), (1, 0), (1, 1)))
    test(new Solver(p)) { dut =>
      val run = solve(dut, p, b, truthClue(b))
      checkInvariants(b, run)
      assert(run.mine == Set((0, 0)) && run.safe.isEmpty)
    }
  }

  it should "chain deductions until the fixpoint" in {
    // Truth: one mine at (0,0). Revealed bottom row: 1 1 0.
    // The 0 makes (0,1) and (0,2) safe; then both 1s leave only (0,0): a mine.
    val p = MsParams.forVerification(3, 2)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0)), revealed = Set((1, 0), (1, 1), (1, 2)))
    test(new Solver(p)) { dut =>
      val run = solve(dut, p, b, truthClue(b))
      checkInvariants(b, run)
      assert(run.safe == Set((0, 1), (0, 2)) && run.mine == Set((0, 0)))
      assert(run.cycles == 3, "two productive evaluations, then one without progress")
      checkAgainstReference(b, run)
    }
  }

  it should "complete with valid and no hints when nothing can be deduced" in {
    val p = MsParams.forVerification(4, 4)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0)))
    test(new Solver(p)) { dut =>
      val run = solve(dut, p, b, truthClue(b))
      checkInvariants(b, run)
      assert(run.safe.isEmpty && run.mine.isEmpty && run.cycles == 1)
    }
  }

  it should "not make subset deductions (left for v2)" in {
    // Classic 1-1 against the left wall: only the subset rule shows (0,2) is safe.
    // Truth: mine at (0,0). Revealed: (1,0)=1, (1,1)=1; everything else hidden.
    val p = MsParams.forVerification(4, 3)
    val g = p.presets.head
    val b = RevealBoard(g, mines = Set((0, 0)), revealed = Set((1, 0), (1, 1), (2, 0), (2, 1)))
    test(new Solver(p)) { dut =>
      val run = solve(dut, p, b, truthClue(b))
      checkInvariants(b, run)
      assert(!run.safe.contains((0, 2)), "v1 must not use the subset rule")
      checkAgainstReference(b, run)
    }
  }

  it should "treat padding as absent, not as unknown cells" in {
    // 5x4 game inside 8x6 storage. Truth: mine at (0,4), the active top-right corner.
    // Everything around it is revealed, so (1,4)=1 has a single active unknown neighbour.
    // If padding counted as unknown, (1,4) would see (0,5), (1,5), (2,5) too and deduce nothing.
    val g = padded.presets(1)
    val revealed = Set((0, 3), (1, 3), (1, 4), (2, 3), (2, 4))
    val b = RevealBoard(g, mines = Set((0, 4)), revealed = revealed)
    test(new Solver(padded)) { dut =>
      val run = solve(dut, padded, b, truthClue(b))
      checkInvariants(b, run)
      assert(run.mine.contains((0, 4)))
      checkAgainstReference(b, run)
    }
  }

  it should "report a contradiction with no hints on inconsistent clues" in {
    val p = MsParams.forVerification(2, 2)
    val b = RevealBoard(p.presets.head, mines = Set.empty, revealed = Set((0, 1), (1, 0), (1, 1)))
    test(new Solver(p)) { dut =>
      // A 3 with a single hidden neighbour cannot be satisfied.
      val run = solve(dut, p, b, cell => if (cell == ((1, 1))) 3 else 1)
      assert(run.contradiction && !run.valid)
      assert(run.safe.isEmpty && run.mine.isEmpty, "hints must be zero after a contradiction")
    }
  }

  it should "refuse a start while busy, cancel on clear and start again" in {
    val p = MsParams.forVerification(3, 2)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0)), revealed = Set((1, 0), (1, 1), (1, 2)))
    test(new Solver(p)) { dut =>
      val first = solve(dut, p, b, truthClue(b))
      assert(first.valid)
      // New start: the previous hints are invalidated at once.
      dut.io.start.valid.poke(true.B)
      dut.clock.step()
      dut.io.valid.expect(false.B)
      dut.io.busy.expect(true.B)
      dut.io.start.ready.expect(false.B)
      dut.io.hints.foreach { h => h.safe.expect(false.B); h.mine.expect(false.B) }
      dut.io.clear.poke(true.B)
      dut.io.start.ready.expect(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)
      dut.io.start.valid.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.done.expect(false.B)
      dut.io.valid.expect(false.B)
      dut.io.contradiction.expect(false.B)
      dut.io.cycles.expect(0.U)
      dut.io.start.ready.expect(true.B)
      val again = solve(dut, p, b, truthClue(b))
      assert(again == first, "the same board must give the same result after a clear")
    }
  }

  it should "be sound for every consistent layout on random 4x4 games" in {
    val p = MsParams.forVerification(4, 4)
    val g = p.presets.head
    val rnd = new Random(7)
    val all = for (r <- 0 until 4; c <- 0 until 4) yield (r, c)
    test(new Solver(p)) { dut =>
      var deductions = 0
      for (_ <- 0 until 30) {
        val b = playedBoard(g, rnd, density = 0.15 + 0.2 * rnd.nextDouble(), clicks = 1 + rnd.nextInt(2))
        val run = solve(dut, p, b, truthClue(b))
        checkInvariants(b, run)
        checkAgainstReference(b, run)
        // Every layout that explains the visible numbers must agree with every hint.
        val hidden = all.filterNot(b.revealed)
        for (bits <- 0 until (1 << hidden.size)) {
          val layout = hidden.indices.filter(k => (bits >> k & 1) == 1).map(hidden).toSet
          val candidate = RevealBoard(g, layout)
          if (b.revealed.forall(c => candidate.adj(c) == b.adj(c))) {
            assert(!run.safe.exists(layout), "a hinted safe cell is a mine in a consistent layout")
            assert(run.mine.subsetOf(layout), "a hinted mine is safe in a consistent layout")
          }
        }
        deductions += run.safe.size + run.mine.size
      }
      assert(deductions > 0, "the random games never exercised a deduction")
    }
  }

  it should "match the reference fixpoint on 20 played games per configuration" in {
    for (p <- configs) {
      val g = p.presets.head
      val rnd = new Random(2000 + 100 * g.cols + g.rows)
      test(new Solver(p)) { dut =>
        for (_ <- 0 until 20) {
          val b = playedBoard(g, rnd, density = 0.1 + 0.15 * rnd.nextDouble(), clicks = 1 + rnd.nextInt(3))
          val run = solve(dut, p, b, truthClue(b))
          checkInvariants(b, run)
          checkAgainstReference(b, run)
        }
      }
    }
  }

  it should "work at production capacity (30x24 storage) on 9x9 and 30x16 games" taggedAs (SlowTag) in {
    val p = MsParams()
    val rnd = new Random(99)
    test(new Solver(p)) { dut =>
      for (g <- Seq(p.presets(1), p.presets(3)); _ <- 0 until 2) {
        val b = playedBoard(g, rnd, density = 0.15, clicks = 3)
        val run = solve(dut, p, b, truthClue(b))
        checkInvariants(b, run)
        checkAgainstReference(b, run)
      }
    }
  }

  // ---------------------------------------------------------------- v2 (R2-S4)

  private def v2(lanes: Int): SolverConfig = SolverConfig(subsetRule = true, lanes = lanes)

  /** Each productive round adds a deduction; a round is a rule-1 phase plus one pair sweep. */
  private def v2Bound(p: MsParams, lanes: Int): Int = (p.cells + 1) * ((p.cells + lanes - 1) / lanes + 2)

  /** True if the pair rule finds more than v1 on this board. */
  private def needsPairs(b: RevealBoard): Boolean = {
    val Some((s1, m1, _)) = ReferenceV1.solve(b, b.revealed, b.adj)
    val Some((s2, m2)) = ReferenceV2.solve(b, b.revealed, b.adj)
    s2.size + m2.size > s1.size + m1.size
  }

  private def checkAgainstReferenceV2(b: RevealBoard, run: SolveRun): Unit = {
    val Some((safe, mine)) = ReferenceV2.solve(b, b.revealed, b.adj)
    assert(run.safe == safe && run.mine == mine,
      s"hints differ from the reference v2 closure: got safe ${run.safe} mine ${run.mine}, expected safe $safe mine $mine")
  }

  /** One v2 run on a hand-made board, compared with the expected hints and the reference. */
  private def handCase(p: MsParams, b: RevealBoard, lanes: Int, safe: Set[(Int, Int)], mine: Set[(Int, Int)]): SolveRun = {
    var run: SolveRun = null
    test(new Solver(p, v2(lanes))) { dut =>
      run = solve(dut, p, b, truthClue(b), v2Bound(p, lanes))
      checkInvariants(b, run)
      assert(run.safe == safe && run.mine == mine, s"got safe ${run.safe} mine ${run.mine}")
      checkAgainstReferenceV2(b, run)
    }
    run
  }

  "Solver v2" should "solve the 1-1 pattern against a wall" in {
    // Same board as the v1 test above: (1,0)=1 sees {(0,0),(0,1)}, (1,1)=1 sees those and (0,2).
    val p = MsParams.forVerification(4, 3)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0)), revealed = Set((1, 0), (1, 1), (2, 0), (2, 1)))
    handCase(p, b, lanes = 1, safe = Set((0, 2), (1, 2), (2, 2)), mine = Set.empty)
  }

  it should "solve 1-2-1 against a wall" in {
    // Hidden top row over a revealed 1 2 1: the outer cells are mines, the middle one is safe.
    val p = MsParams.forVerification(3, 2)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0), (0, 2)), revealed = Set((1, 0), (1, 1), (1, 2)))
    assert(ReferenceV1.solve(b, b.revealed, b.adj).get._1.isEmpty, "v1 alone must not solve this board")
    handCase(p, b, lanes = 2, safe = Set((0, 1)), mine = Set((0, 0), (0, 2)))
  }

  it should "deduce from a partial overlap, the contract's example" in {
    // (0,0)=1 sees A = {(1,0),(1,1),(0,1)}; (2,0)=2 sees B = {(1,0),(1,1),(2,1)}.
    // Neither contains the other. I = {(1,0),(1,1)}, L = U = 1: (0,1) is safe, (2,1) is a mine.
    val p = MsParams.forVerification(5, 3)
    val mines = Set((0, 3), (0, 4), (1, 0), (1, 3), (1, 4), (2, 1), (2, 3))
    val b = RevealBoard(p.presets.head, mines = mines, revealed = Set((0, 0), (2, 0)))
    val v1 = ReferenceV1.solve(b, b.revealed, b.adj).get
    assert(v1._1.isEmpty && v1._2.isEmpty, "v1 alone must find nothing here")
    handCase(p, b, lanes = 1, safe = Set((0, 1)), mine = Set((2, 1)))
  }

  it should "prove mines from a partial overlap" in {
    // (0,1)=4 sees 5 hidden cells, (0,3)=1 sees 3; they share (0,2) and (1,2).
    // L = U = 1: the three cells only (0,1) sees are mines, (1,3) is safe.
    val p = MsParams.forVerification(4, 2)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0), (0, 2), (1, 0), (1, 1)), revealed = Set((0, 1), (0, 3)))
    handCase(p, b, lanes = p.cells, safe = Set((1, 3)), mine = Set((0, 0), (1, 0), (1, 1)))
  }

  it should "give the same hints with 1, 4 and all lanes (only cycles differ)" in {
    val p = MsParams.forVerification(5, 4)
    val g = p.presets.head
    val rnd = new Random(41)
    // Half of the boards need the pair rule, so the lanes really sweep pairs.
    def played() = playedBoard(g, rnd, density = 0.2, clicks = 1 + rnd.nextInt(2))
    val boards = Iterator.continually(played()).filter(needsPairs).take(4).toSeq ++ Seq.fill(4)(played())
    val results = for (lanes <- Seq(1, 4, p.cells)) yield {
      var runs = Seq.empty[SolveRun]
      test(new Solver(p, v2(lanes))) { dut =>
        runs = boards.map { b =>
          val run = solve(dut, p, b, truthClue(b), v2Bound(p, lanes))
          checkInvariants(b, run)
          checkAgainstReferenceV2(b, run)
          run
        }
      }
      lanes -> runs
    }
    val hints = results.map { case (_, runs) => runs.map(r => (r.safe, r.mine)) }
    assert(hints.distinct.size == 1, "the lane count changed the hints")
    val cycles = results.map { case (lanes, runs) => lanes -> runs.map(_.cycles).sum }
    info(s"cycles over the ${boards.size} boards: " + cycles.map { case (l, c) => s"$l lane(s) $c" }.mkString(", "))
    assert(cycles.head._2 >= cycles.last._2, s"one lane should not be faster than all lanes: $cycles")
  }

  it should "be sound for every consistent layout on random 4x4 games" in {
    val p = MsParams.forVerification(4, 4)
    val g = p.presets.head
    val rnd = new Random(8)
    val all = for (r <- 0 until 4; c <- 0 until 4) yield (r, c)
    test(new Solver(p, v2(4))) { dut =>
      var beyondV1 = 0
      for (_ <- 0 until 20) {
        val b = playedBoard(g, rnd, density = 0.15 + 0.2 * rnd.nextDouble(), clicks = 1 + rnd.nextInt(2))
        val run = solve(dut, p, b, truthClue(b), v2Bound(p, 4))
        checkInvariants(b, run)
        checkAgainstReferenceV2(b, run)
        val hidden = all.filterNot(b.revealed)
        for (bits <- 0 until (1 << hidden.size)) {
          val layout = hidden.indices.filter(k => (bits >> k & 1) == 1).map(hidden).toSet
          val candidate = RevealBoard(g, layout)
          if (b.revealed.forall(c => candidate.adj(c) == b.adj(c))) {
            assert(!run.safe.exists(layout), "a hinted safe cell is a mine in a consistent layout")
            assert(run.mine.subsetOf(layout), "a hinted mine is safe in a consistent layout")
          }
        }
        val Some((s1, m1, _)) = ReferenceV1.solve(b, b.revealed, b.adj)
        if (run.safe.size + run.mine.size > s1.size + m1.size) beyondV1 += 1
      }
      assert(beyondV1 > 0, "no random game needed the pair rule")
    }
  }

  it should "match the reference v2 closure on played games" in {
    // 4x4 and 7x5: a 7x5 run costs about 40 s of simulation, so it gets fewer games.
    for ((p, games) <- configs.take(2).zip(Seq(6, 3))) {
      val g = p.presets.head
      val rnd = new Random(3000 + 100 * g.cols + g.rows)
      test(new Solver(p, v2(4))) { dut =>
        for (_ <- 0 until games) {
          val b = playedBoard(g, rnd, density = 0.1 + 0.15 * rnd.nextDouble(), clicks = 1 + rnd.nextInt(3))
          val run = solve(dut, p, b, truthClue(b), v2Bound(p, 4))
          checkInvariants(b, run)
          checkAgainstReferenceV2(b, run)
        }
      }
    }
  }

  it should "report a contradiction with no hints on inconsistent clues" in {
    val p = MsParams.forVerification(2, 2)
    val b = RevealBoard(p.presets.head, mines = Set.empty, revealed = Set((0, 1), (1, 0), (1, 1)))
    test(new Solver(p, v2(1))) { dut =>
      val run = solve(dut, p, b, cell => if (cell == ((1, 1))) 3 else 1, v2Bound(p, 1))
      assert(run.contradiction && !run.valid)
      assert(run.safe.isEmpty && run.mine.isEmpty)
    }
  }

  it should "match the reference v2 closure on 9x9 games" taggedAs (SlowTag) in {
    val p = MsParams.forVerification(9, 9)
    val rnd = new Random(77)
    // About 20 minutes of simulation per game; 6 games with this seed passed locally (2 h 7 min).
    test(new Solver(p, v2(4))) { dut =>
      for (_ <- 0 until 2) {
        val b = playedBoard(p.presets.head, rnd, density = 0.15, clicks = 2)
        val run = solve(dut, p, b, truthClue(b), v2Bound(p, 4))
        checkInvariants(b, run)
        checkAgainstReferenceV2(b, run)
      }
    }
  }
}
