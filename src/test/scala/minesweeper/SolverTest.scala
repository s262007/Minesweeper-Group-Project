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

object ReferenceV1 {
  type Cells = Set[(Int, Int)]

  private def evaluate(b: RevealBoard, revealed: Cells, clue: ((Int, Int)) => Int,
                       clues: Iterable[(Int, Int)], safe: Cells, mine: Cells): Option[(Cells, Cells)] = {
    var newSafe = Set.empty[(Int, Int)]
    var newMine = Set.empty[(Int, Int)]
    for (n <- clues) {
      val nb = b.neighbours(n)
      val known = nb.count(mine)
      val unknown = nb.filter(c => !revealed(c) && !safe(c) && !mine(c))
      val k = clue(n) - known
      if (k < 0 || k > unknown.size) return None
      if (k == 0) newSafe ++= unknown
      if (k == unknown.size) newMine ++= unknown
    }
    if ((newSafe & newMine).nonEmpty) None else Some((newSafe, newMine))
  }

  def solve(b: RevealBoard, revealed: Cells, clue: ((Int, Int)) => Int): Option[(Cells, Cells, Int)] = {
    var safe = Set.empty[(Int, Int)]
    var mine = Set.empty[(Int, Int)]
    var evaluations = 0
    var changed = true
    while (changed) {
      evaluations += 1
      val (newSafe, newMine) = evaluate(b, revealed, clue, revealed, safe, mine) match {
        case Some(found) => found
        case None => return None
      }
      changed = newSafe.nonEmpty || newMine.nonEmpty
      safe ++= newSafe
      mine ++= newMine
    }
    Some((safe, mine, evaluations))
  }

  def solveByRows(b: RevealBoard, revealed: Cells, clue: ((Int, Int)) => Int): Option[(Cells, Cells, Int)] = {
    var safe = Set.empty[(Int, Int)]
    var mine = Set.empty[(Int, Int)]
    var steps = 0
    var passProgress = true
    while (passProgress) {
      passProgress = false
      for (r <- 0 until b.g.rows) {
        steps += 1
        val (newSafe, newMine) = evaluate(b, revealed, clue, revealed.filter(_._1 == r), safe, mine) match {
          case Some(found) => found
          case None => return None
        }
        passProgress ||= newSafe.nonEmpty || newMine.nonEmpty
        safe ++= newSafe
        mine ++= newMine
      }
    }
    Some((safe, mine, steps))
  }
}

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

class SolverTest extends AnyFlatSpec with ChiselScalatestTester {
  private val configs = Seq((4, 4), (7, 5), (9, 9), (16, 8))
    .map { case (cols, rows) => MsParams.forVerification(cols, rows) }

  private val padded = MsParams(
    maxCols = 8, maxRows = 6,
    presets = Vector(GridPreset(0, 8, 6), GridPreset(1, 5, 4)),
    minMines = 0, defaultPresetId = 0, defaultMines = 0, verification = true
  )

  private def cellOf(p: MsParams, i: Int): (Int, Int) = (i / p.maxCols, i % p.maxCols)

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

  private def v1Bound(p: MsParams): Int = 3 * (p.cells + 1) * p.maxRows + 2

  private def solve(dut: Solver, p: MsParams, b: RevealBoard, clue: ((Int, Int)) => Int,
                    bound: Int = -1): SolveRun = {
    dut.clock.setTimeout(0)
    dut.io.clear.poke(false.B)
    pokeBoard(dut, p, b, clue)
    val req = dut.io.start.bits.config
    req.preset.poke(b.g.id.U)
    req.cols.poke(b.g.cols.U)
    req.rows.poke(b.g.rows.U)
    req.mines.poke(b.mines.size.U)
    dut.io.start.valid.poke(true.B)
    dut.io.start.ready.expect(true.B)
    dut.clock.step()
    dut.io.start.valid.poke(false.B)
    var waited = 0
    val limit = if (bound < 0) v1Bound(p) else bound
    while (!dut.io.done.peekBoolean()) {
      assert(waited <= limit, s"solver did not reach a fixpoint within $limit cycles")
      dut.io.busy.expect(true.B)
      dut.io.start.ready.expect(false.B)
      dut.io.valid.expect(false.B)
      dut.clock.step()
      waited += 1
    }
    dut.io.busy.expect(false.B)
    val valid = dut.io.valid.peekBoolean()
    val contradiction = dut.io.contradiction.peekBoolean()
    val cycles = dut.io.cycles.peek().litValue.toInt
    val (safe, mine) = readHints(dut, p)
    dut.clock.step()
    dut.io.done.expect(false.B)
    dut.io.valid.expect(valid.B)
    assert(readHints(dut, p) == ((safe, mine)))
    SolveRun(safe, mine, valid, contradiction, cycles)
  }

  private def truthClue(b: RevealBoard): ((Int, Int)) => Int = b.adj

  private def checkInvariants(b: RevealBoard, run: SolveRun): Unit = {
    assert(run.valid && !run.contradiction)
    assert((run.safe & run.mine).isEmpty, "a cell is both safe and mine")
    val all = run.safe ++ run.mine
    assert(all.forall(b.inGrid), "hint on a padding cell")
    assert(!all.exists(b.revealed), "hint on a revealed cell")
    assert(!run.safe.exists(b.mines), "a mine was declared safe")
    assert(run.mine.subsetOf(b.mines), "a safe cell was declared a mine")
  }

  private def checkAgainstReference(b: RevealBoard, run: SolveRun): Unit = {
    val Some((safe, mine, _)) = ReferenceV1.solve(b, b.revealed, b.adj)
    assert(run.safe == safe && run.mine == mine, "hints differ from the reference v1 fixpoint")
    val Some((rowSafe, rowMine, steps)) = ReferenceV1.solveByRows(b, b.revealed, b.adj)
    assert(rowSafe == safe && rowMine == mine, "the row order changed the reference fixpoint")
    assert(run.cycles == 3 * steps, s"cycles ${run.cycles} != 3 x $steps row steps")
  }

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
    val p = MsParams.forVerification(3, 2)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0)), revealed = Set((1, 0), (1, 1), (1, 2)))
    test(new Solver(p)) { dut =>
      val run = solve(dut, p, b, truthClue(b))
      checkInvariants(b, run)
      assert(run.safe == Set((0, 1), (0, 2)) && run.mine == Set((0, 0)))
      assert(run.cycles == 18, "three passes of two rows, three cycles per row")
      checkAgainstReference(b, run)
    }
  }

  it should "complete with valid and no hints when nothing can be deduced" in {
    val p = MsParams.forVerification(4, 4)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0)))
    test(new Solver(p)) { dut =>
      val run = solve(dut, p, b, truthClue(b))
      checkInvariants(b, run)
      assert(run.safe.isEmpty && run.mine.isEmpty && run.cycles == 12, "one pass of four rows, three cycles per row")
    }
  }

  it should "not make subset deductions (left for v2)" in {
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

  private val v2 = SolverConfig(subsetRule = true)

  private def v2Bound(p: MsParams): Int =
    2 * (p.cells + 1) * (3 * p.maxRows + 1) + (p.cells + 1) * p.maxRows * (5 * p.maxCols + 5) + 2

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

  private def handCase(p: MsParams, b: RevealBoard, safe: Set[(Int, Int)], mine: Set[(Int, Int)]): Unit =
    test(new Solver(p, v2)) { dut =>
      val run = solve(dut, p, b, truthClue(b), v2Bound(p))
      checkInvariants(b, run)
      assert(run.safe == safe && run.mine == mine, s"got safe ${run.safe} mine ${run.mine}")
      checkAgainstReferenceV2(b, run)
    }

  "Solver v2" should "solve the 1-1 pattern against a wall" in {
    val p = MsParams.forVerification(4, 3)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0)), revealed = Set((1, 0), (1, 1), (2, 0), (2, 1)))
    handCase(p, b, safe = Set((0, 2), (1, 2), (2, 2)), mine = Set.empty)
  }

  it should "solve 1-2-1 against a wall" in {
    val p = MsParams.forVerification(3, 2)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0), (0, 2)), revealed = Set((1, 0), (1, 1), (1, 2)))
    assert(ReferenceV1.solve(b, b.revealed, b.adj).get._1.isEmpty, "v1 alone must not solve this board")
    handCase(p, b, safe = Set((0, 1)), mine = Set((0, 0), (0, 2)))
  }

  it should "deduce from a partial overlap, the contract's example" in {
    val p = MsParams.forVerification(5, 3)
    val mines = Set((0, 3), (0, 4), (1, 0), (1, 3), (1, 4), (2, 1), (2, 3))
    val b = RevealBoard(p.presets.head, mines = mines, revealed = Set((0, 0), (2, 0)))
    val v1 = ReferenceV1.solve(b, b.revealed, b.adj).get
    assert(v1._1.isEmpty && v1._2.isEmpty, "v1 alone must find nothing here")
    handCase(p, b, safe = Set((0, 1)), mine = Set((2, 1)))
  }

  it should "prove mines from a partial overlap" in {
    val p = MsParams.forVerification(4, 2)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0), (0, 2), (1, 0), (1, 1)), revealed = Set((0, 1), (0, 3)))
    handCase(p, b, safe = Set((1, 3)), mine = Set((0, 0), (1, 0), (1, 1)))
  }

  it should "be sound for every consistent layout on random 4x4 games" in {
    val p = MsParams.forVerification(4, 4)
    val g = p.presets.head
    val rnd = new Random(8)
    val all = for (r <- 0 until 4; c <- 0 until 4) yield (r, c)
    def played() = playedBoard(g, rnd, density = 0.15 + 0.2 * rnd.nextDouble(), clicks = 1 + rnd.nextInt(2))
    val boards = Iterator.continually(played()).filter(needsPairs).take(4).toSeq ++ Seq.fill(4)(played())
    test(new Solver(p, v2)) { dut =>
      var beyondV1 = 0
      for (b <- boards) {
        val run = solve(dut, p, b, truthClue(b), v2Bound(p))
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

  private def closureGames(p: MsParams, games: Int): Unit = {
    val g = p.presets.head
    val rnd = new Random(3000 + 100 * g.cols + g.rows)
    def played() = playedBoard(g, rnd, density = 0.1 + 0.15 * rnd.nextDouble(), clicks = 1 + rnd.nextInt(3))
    val boards = Iterator.continually(played()).filter(needsPairs).take(games / 2).toSeq ++ Seq.fill(games / 2)(played())
    test(new Solver(p, v2)) { dut =>
      val cycles = boards.map { b =>
        val run = solve(dut, p, b, truthClue(b), v2Bound(p))
        checkInvariants(b, run)
        checkAgainstReferenceV2(b, run)
        run.cycles
      }
      info(s"${g.cols}x${g.rows}: cycles per solve ${cycles.mkString(", ")}")
    }
  }

  it should "match the reference v2 closure on played 4x4 games, half of them needing pairs" in {
    closureGames(configs.head, 4)
  }

  it should "match the reference v2 closure on played 7x5 games" taggedAs (SlowTag) in {
    closureGames(configs(1), 4)
  }

  it should "treat padding as absent in the pair pass" in {
    val g = padded.presets(1)
    val rnd = new Random(55)
    def played() = playedBoard(g, rnd, density = 0.2, clicks = 1 + rnd.nextInt(2))
    val boards = Iterator.continually(played()).filter(needsPairs).take(2).toSeq
    test(new Solver(padded, v2)) { dut =>
      for (b <- boards) {
        val run = solve(dut, padded, b, truthClue(b), v2Bound(padded))
        checkInvariants(b, run)
        checkAgainstReferenceV2(b, run)
      }
    }
  }

  it should "report a contradiction with no hints on inconsistent clues" in {
    val p = MsParams.forVerification(2, 2)
    val b = RevealBoard(p.presets.head, mines = Set.empty, revealed = Set((0, 1), (1, 0), (1, 1)))
    test(new Solver(p, v2)) { dut =>
      val run = solve(dut, p, b, cell => if (cell == ((1, 1))) 3 else 1, v2Bound(p))
      assert(run.contradiction && !run.valid)
      assert(run.safe.isEmpty && run.mine.isEmpty)
    }
  }

  it should "match the reference v2 closure on 9x9 games" taggedAs (SlowTag) in {
    val p = MsParams.forVerification(9, 9)
    val rnd = new Random(77)
    test(new Solver(p, v2)) { dut =>
      val cycles = (0 until 2).map { _ =>
        val b = playedBoard(p.presets.head, rnd, density = 0.15, clicks = 2)
        val run = solve(dut, p, b, truthClue(b), v2Bound(p))
        checkInvariants(b, run)
        checkAgainstReferenceV2(b, run)
        run.cycles
      }
      info(s"9x9: cycles per solve ${cycles.mkString(", ")}")
    }
  }
}
