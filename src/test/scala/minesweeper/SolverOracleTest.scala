package minesweeper

import minesweeper.model.{CellKind, GoldenModel, ModelSolver, RefGrid, State}
import minesweeper.model.SolverOracle
import minesweeper.model.SolverOracle.{Exact, GaveUp, Inconsistent}
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

class SolverOracleTest extends AnyFlatSpec {
  private type Cell = (Int, Int)

  /** Number of mines next to (r, c), with plain coordinate loops. */
  private def count(mines: Set[Cell], r: Int, c: Int): Int =
    (for (dr <- -1 to 1; dc <- -1 to 1 if (dr, dc) != ((0, 0)) && mines((r + dr, c + dc))) yield 1).sum

  private def cluesOf(mines: Set[Cell], revealed: Iterable[Cell]): Map[Cell, Int] =
    revealed.map { case (r, c) => (r, c) -> count(mines, r, c) }.toMap

  /**
    * Every layout of the hidden cells is tried; a layout is kept when each clue counts exactly its
    * number of mines (and the total matches, if given). Returns the cells that are safe and mined
    * in every kept layout, or None when no layout is kept.
    */
  private def bruteForce(rows: Int, cols: Int, clues: Map[Cell, Int], total: Option[Int]): Option[(Set[Cell], Set[Cell])] = {
    val hidden = for (r <- 0 until rows; c <- 0 until cols if !clues.contains((r, c))) yield (r, c)
    require(hidden.size <= 14, s"${hidden.size} hidden cells: too many to enumerate")
    var inAll = hidden.toSet
    var inAny = Set.empty[Cell]
    var kept = false
    for (bits <- 0 until (1 << hidden.size)) {
      val mines = hidden.indices.filter(i => (bits >> i & 1) == 1).map(hidden).toSet
      if (total.forall(_ == mines.size) && clues.forall { case ((r, c), k) => count(mines, r, c) == k }) {
        kept = true
        inAll = inAll intersect mines
        inAny = inAny union mines
      }
    }
    if (kept) Some((hidden.toSet -- inAny, inAll)) else None
  }

  private def exact(o: SolverOracle.Outcome): Exact = o match {
    case e: Exact => e
    case other => fail(s"expected an exact answer, got $other")
  }

  "SolverOracle" should "find the cells of the contract's partial-overlap example" in {
    // CONTRACT 5.6 on a board (same board as ModelSolverTest): (1,1) = 1 sees A = (0,0) (0,1) (0,2) (1,0);
    // (1,2) = 3 sees B = (0,1) (0,2) (0,3) (1,3). One mine in (0,1)/(0,2), none in (0,0)/(1,0), two in (0,3)/(1,3).
    val g = RefGrid(rows = 2, cols = 4)
    val clues = cluesOf(Set((0, 1), (0, 3), (1, 3)), Seq((1, 1), (1, 2)))
    val e = exact(SolverOracle.certain(g, clues))
    assert(e.safe == Set((0, 0), (1, 0)) && e.mine == Set((0, 3), (1, 3)))
    assert(e.frontier == 6 && e.interior == 0)
  }

  it should "decide the interior only from the total number of mines" in {
    // 1x4: (0,0) = 1 forces (0,1); (0,2) and (0,3) touch no clue.
    val g = RefGrid(rows = 1, cols = 4)
    val clues = Map((0, 0) -> 1)
    val free = exact(SolverOracle.certain(g, clues))
    assert(free.mine == Set((0, 1)) && free.safe.isEmpty && free.interior == 2)
    val one = exact(SolverOracle.certain(g, clues, totalMines = Some(1)))
    assert(one.mine == Set((0, 1)) && one.safe == Set((0, 2), (0, 3)))
    val two = exact(SolverOracle.certain(g, clues, totalMines = Some(2)))
    assert(two.mine == Set((0, 1)) && two.safe.isEmpty)
    val three = exact(SolverOracle.certain(g, clues, totalMines = Some(3)))
    assert(three.mine == Set((0, 1), (0, 2), (0, 3)) && three.safe.isEmpty)
    assert(SolverOracle.certain(g, clues, totalMines = Some(4)).isInstanceOf[Inconsistent])
    assert(SolverOracle.certain(g, clues, totalMines = Some(0)).isInstanceOf[Inconsistent])
  }

  it should "use the total to choose among the layouts of a component" in {
    // 3x3 with the four corners revealed, each showing 1. Three layouts fit: a mine at (1,1) alone,
    // mines at (0,1) and (2,1), or mines at (1,0) and (1,2). Without the total nothing is certain.
    val g = RefGrid(rows = 3, cols = 3)
    val clues = Map((0, 0) -> 1, (0, 2) -> 1, (2, 0) -> 1, (2, 2) -> 1)
    val free = exact(SolverOracle.certain(g, clues))
    assert(free.safe.isEmpty && free.mine.isEmpty && free.frontier == 5)
    val one = exact(SolverOracle.certain(g, clues, totalMines = Some(1)))
    assert(one.mine == Set((1, 1)) && one.safe == Set((0, 1), (1, 0), (1, 2), (2, 1)))
    val two = exact(SolverOracle.certain(g, clues, totalMines = Some(2)))
    assert(two.safe == Set((1, 1)) && two.mine.isEmpty)
    assert(SolverOracle.certain(g, clues, totalMines = Some(3)).isInstanceOf[Inconsistent])
  }

  it should "report clues that no layout fits" in {
    // (0,1) must be a mine for (0,0) = 1 and safe for (0,2) = 0.
    assert(SolverOracle.certain(RefGrid(1, 3), Map((0, 0) -> 1, (0, 2) -> 0)).isInstanceOf[Inconsistent])
    // A number larger than the hidden neighbours.
    assert(SolverOracle.certain(RefGrid(1, 2), Map((0, 0) -> 2)) == Inconsistent(0))
    // A 2 whose two hidden neighbours are each made safe by a 0.
    val g = RefGrid(rows = 1, cols = 5)
    assert(SolverOracle.certain(g, Map((0, 0) -> 0, (0, 2) -> 2, (0, 4) -> 0)).isInstanceOf[Inconsistent])
  }

  it should "agree with an enumeration of every layout on random small boards" in {
    val rnd = new Random(301)
    var inconsistent = 0
    for (n <- 0 until 400) {
      val rows = 2 + rnd.nextInt(4)
      val cols = 2 + rnd.nextInt(4)
      val cells = for (r <- 0 until rows; c <- 0 until cols) yield (r, c)
      val shuffled = rnd.shuffle(cells)
      val mineCount = rnd.nextInt(math.min(7, cells.size))
      val mines = shuffled.take(mineCount).toSet
      // Hide the mines and a few safe cells; reveal the rest. At most 12 hidden cells.
      val extra = rnd.nextInt(math.max(1, math.min(12 - mineCount, cells.size - mineCount) + 1))
      val hidden = mines ++ shuffled.drop(mineCount).take(extra)
      var clues = cluesOf(mines, cells.filterNot(hidden))
      // One board in five gets a wrong number, which may make it inconsistent.
      if (clues.nonEmpty && rnd.nextInt(5) == 0) {
        val (cell, k) = clues.toVector(rnd.nextInt(clues.size))
        clues = clues.updated(cell, math.max(0, k + (if (rnd.nextBoolean()) 1 else -1)))
      }
      val totals = Seq(None, Some(mines.size), Some(mines.size + rnd.nextInt(3) - 1))
      for (total <- totals) {
        val expected = bruteForce(rows, cols, clues, total)
        SolverOracle.certain(RefGrid(rows, cols), clues, total) match {
          case e: Exact =>
            assert(expected.contains((e.safe, e.mine)), s"board $n ${cols}x$rows, clues $clues, total $total: oracle $e, enumeration $expected")
          case _: Inconsistent =>
            assert(expected.isEmpty, s"board $n: oracle says inconsistent, enumeration $expected")
            inconsistent += 1
          case g: GaveUp => fail(s"board $n: gave up on a small board ($g)")
        }
      }
    }
    assert(inconsistent > 0, "no inconsistent board was generated")
  }

  /** Clue maps after each move of model games: reveals of random safe hidden cells. */
  private def playedBoards(cols: Int, rows: Int, games: Int, moves: Int, seed: Int): Seq[(RefGrid, Map[Cell, Int], Set[Cell], Int)] = {
    val p = MsParams.forVerification(cols, rows)
    val rnd = new Random(seed)
    val out = Seq.newBuilder[(RefGrid, Map[Cell, Int], Set[Cell], Int)]
    for (_ <- 0 until games) {
      val model = new GoldenModel(p)
      val mines = math.round((0.12 + 0.2 * rnd.nextDouble()) * cols * rows).toInt
      model.newGame(0, math.min(mines, p.maxMines(p.presets(0))), 1 + rnd.nextInt(0xfffe))
      var move = 0
      while (move < moves && (move == 0 || model.snapshot.state == State.Playing)) {
        val s = model.snapshot
        val truth = model.mineCells
        val hidden = for (r <- 0 until rows; c <- 0 until cols
                          if s.cells(r * cols + c).kind == CellKind.Hidden && !truth((r, c))) yield (r, c)
        if (hidden.nonEmpty) {
          val (r, c) = hidden(rnd.nextInt(hidden.size))
          model.reveal(r, c)
          val after = model.snapshot
          if (after.state == State.Playing) {
            val revealed = for (i <- after.cells.indices if after.cells(i).kind == CellKind.Number) yield (i / cols, i % cols)
            out += ((RefGrid(rows, cols), cluesOf(model.mineCells, revealed), model.mineCells, after.mines))
          }
        }
        move += 1
      }
    }
    out.result()
  }

  it should "be sound on played games and contain every ModelSolver deduction" in {
    val boards = playedBoards(9, 9, games = 60, moves = 6, seed = 302) ++ playedBoards(6, 5, games = 60, moves = 5, seed = 303)
    var beyondV2 = 0
    var totalHelps = 0
    for ((g, clues, truth, mines) <- boards) {
      val free = exact(SolverOracle.certain(g, clues))
      val counted = exact(SolverOracle.certain(g, clues, totalMines = Some(mines)))
      for (e <- Seq(free, counted)) {
        assert(e.safe.intersect(truth).isEmpty, s"clues $clues: a certain-safe cell is a mine")
        assert(e.mine.subsetOf(truth), s"clues $clues: a certain mine is safe")
      }
      assert(free.safe.subsetOf(counted.safe) && free.mine.subsetOf(counted.mine), "the total removed a certain cell")
      val v1 = ModelSolver.solveV1(g, clues)
      val v2 = ModelSolver.solveV2(g, clues)
      assert(!v1.contradiction && !v2.contradiction)
      assert(v1.safe.subsetOf(free.safe) && v1.mine.subsetOf(free.mine), s"clues $clues: v1 found a cell the oracle does not")
      assert(v2.safe.subsetOf(free.safe) && v2.mine.subsetOf(free.mine), s"clues $clues: v2 found a cell the oracle does not")
      if (free.safe.size + free.mine.size > v2.safe.size + v2.mine.size) beyondV2 += 1
      if (counted.safe.size + counted.mine.size > free.safe.size + free.mine.size) totalHelps += 1
    }
    info(s"${boards.size} boards: the oracle finds more than v2 on $beyondV2, the total count adds cells on $totalHelps")
    assert(boards.size > 200)
    assert(beyondV2 > 0, "no board where the oracle goes beyond rule 2")
  }

  it should "give up above its budget and say so" in {
    // 3x60 with the middle row revealed: the top and bottom rows are one component of 120 cells.
    // A column with one mine can hold it on top or below, so there are about 2^30 layouts.
    val rnd = new Random(304)
    val g = RefGrid(rows = 3, cols = 60)
    val mines = (for (c <- 0 until 60; r <- Seq(0, 2) if rnd.nextInt(2) == 0) yield (r, c)).toSet
    val clues = cluesOf(mines, (0 until 60).map(c => (1, c)))
    val start = System.nanoTime()
    SolverOracle.certain(g, clues) match {
      case GaveUp(budget, largest) => assert(budget == (1L << 20) && largest == 120)
      case other => fail(s"expected GaveUp, got $other")
    }
    assert((System.nanoTime() - start) / 1e9 < 30, "giving up took too long")

    // A board the default budget solves gives up under a small one, never with a partial answer.
    val small = RefGrid(rows = 3, cols = 8)
    val smallMines = Set((0, 1), (2, 3), (0, 6), (2, 6))
    val smallClues = cluesOf(smallMines, (0 until 8).map(c => (1, c)))
    val full = exact(SolverOracle.certain(small, smallClues))
    assert(full.nodes > 20)
    assert(SolverOracle.certain(small, smallClues, budget = 20) == GaveUp(20, 16))
  }
}
