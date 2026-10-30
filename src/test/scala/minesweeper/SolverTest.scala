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
  * coordinate-based neighbours.
  */
object ReferenceV1 {
  type Cells = Set[(Int, Int)]

  /** One evaluation of the given clues on the same knowledge: the new safe and mine cells,
    * or None if a clue is unsatisfiable or a cell is deduced both ways.
    */
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

  /** All clues in parallel until nothing changes. Returns None on inconsistent clues,
    * otherwise the deduced safe and mine sets and the number of evaluations.
    */
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

  /** The hardware schedule: the clues of one row per cycle, passes over the active rows
    * until a pass adds nothing. Returns None on inconsistent clues, otherwise the deduced
    * sets and the number of cycles.
    */
  def solveByRows(b: RevealBoard, revealed: Cells, clue: ((Int, Int)) => Int): Option[(Cells, Cells, Int)] = {
    var safe = Set.empty[(Int, Int)]
    var mine = Set.empty[(Int, Int)]
    var cycles = 0
    var passProgress = true
    while (passProgress) {
      passProgress = false
      for (r <- 0 until b.g.rows) {
        cycles += 1
        val (newSafe, newMine) = evaluate(b, revealed, clue, revealed.filter(_._1 == r), safe, mine) match {
          case Some(found) => found
          case None => return None
        }
        passProgress ||= newSafe.nonEmpty || newMine.nonEmpty
        safe ++= newSafe
        mine ++= newMine
      }
    }
    Some((safe, mine, cycles))
  }
}

/** R2-S2 acceptance tests for the v1 solver (CONTRACT 5.6). */
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
  private def solve(dut: Solver, p: MsParams, b: RevealBoard, clue: ((Int, Int)) => Int): SolveRun = {
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
    // Every pass but the last adds a deduction: at most cells + 1 passes of maxRows cycles.
    val bound = (p.cells + 1) * p.maxRows + 2
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

  /** Exact equality with the parallel reference fixpoint, and with the cycle count of the
    * row schedule (which must reach the same fixpoint).
    */
  private def checkAgainstReference(b: RevealBoard, run: SolveRun): Unit = {
    val Some((safe, mine, _)) = ReferenceV1.solve(b, b.revealed, b.adj)
    assert(run.safe == safe && run.mine == mine, "hints differ from the reference v1 fixpoint")
    val Some((rowSafe, rowMine, cycles)) = ReferenceV1.solveByRows(b, b.revealed, b.adj)
    assert(rowSafe == safe && rowMine == mine, "the row order changed the reference fixpoint")
    assert(run.cycles == cycles, s"cycles ${run.cycles} != row schedule $cycles")
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
      // Pass 1: row 1 makes (0,1), (0,2) safe. Pass 2: row 1 finds the mine. Pass 3 adds nothing.
      assert(run.cycles == 6, "three passes of two rows")
      checkAgainstReference(b, run)
    }
  }

  it should "complete with valid and no hints when nothing can be deduced" in {
    val p = MsParams.forVerification(4, 4)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0)))
    test(new Solver(p)) { dut =>
      val run = solve(dut, p, b, truthClue(b))
      checkInvariants(b, run)
      assert(run.safe.isEmpty && run.mine.isEmpty && run.cycles == 4, "one pass of four rows")
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
}
