package minesweeper

import minesweeper.model.{ModelSolver, RefGrid}
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

class ModelSolverTest extends AnyFlatSpec {
  private type Cell = (Int, Int)

  /** Clues of the revealed cells, counted from the true layout with plain coordinate loops. */
  private def cluesOf(g: RefGrid, mines: Set[Cell], revealed: Set[Cell]): Map[Cell, Int] =
    revealed.map { case (r, c) =>
      (r, c) -> (for (dr <- -1 to 1; dc <- -1 to 1 if (dr, dc) != ((0, 0)) && mines((r + dr, c + dc))) yield 1).sum
    }.toMap

  "ModelSolver v1" should "mark the only hidden neighbour of a 1 as a mine" in {
    val g = RefGrid(rows = 2, cols = 2)
    val r = ModelSolver.solveV1(g, Map((0, 1) -> 1, (1, 0) -> 1, (1, 1) -> 1))
    assert(r.mine == Set((0, 0)) && r.safe.isEmpty && r.rounds == 2 && !r.contradiction)
  }

  it should "mark the hidden neighbours of a satisfied number as safe" in {
    val g = RefGrid(rows = 1, cols = 3)
    val r = ModelSolver.solveV1(g, Map((0, 1) -> 0))
    assert(r.safe == Set((0, 0), (0, 2)) && r.mine.isEmpty)
  }

  it should "chain deductions until nothing changes" in {
    // Mine at (0,0). (1,2) = 0 makes (0,1), (0,2) safe; then (1,0) = 1 forces (0,0).
    val g = RefGrid(rows = 2, cols = 3)
    val r = ModelSolver.solveV1(g, Map((1, 0) -> 1, (1, 1) -> 1, (1, 2) -> 0))
    assert(r.safe == Set((0, 1), (0, 2)) && r.mine == Set((0, 0)) && r.rounds == 3)
  }

  it should "report no hints on a board without information" in {
    val g = RefGrid(rows = 3, cols = 3)
    val r = ModelSolver.solveV1(g, Map((1, 1) -> 1))
    assert(r.safe.isEmpty && r.mine.isEmpty && r.rounds == 1 && !r.contradiction)
  }

  it should "detect inconsistent clues and then give no hints" in {
    val g = RefGrid(rows = 1, cols = 2)
    val r = ModelSolver.solveV1(g, Map((0, 0) -> 2))
    assert(r.contradiction && r.safe.isEmpty && r.mine.isEmpty)
  }

  it should "be sound, closed under rule 1 and only gain knowledge as cells are revealed" in {
    val rnd = new Random(7)
    for (_ <- 0 until 300) {
      val g = RefGrid(rows = 2 + rnd.nextInt(6), cols = 2 + rnd.nextInt(6))
      val all = g.cells.toSet
      val mines = all.filter(_ => rnd.nextInt(5) == 0)
      val safeCells = (all -- mines).toVector
      val order = rnd.shuffle(safeCells)
      var previous = ModelSolver.Result(Set.empty, Set.empty, 0, contradiction = false)
      for (k <- Seq(1, safeCells.size / 3, safeCells.size / 2, safeCells.size) if k > 0) {
        val revealed = order.take(k).toSet
        val clues = cluesOf(g, mines, revealed)
        val r = ModelSolver.solveV1(g, clues)
        assert(!r.contradiction)
        assert(r.mine.subsetOf(mines) && (r.safe & mines).isEmpty, "unsound hint")
        assert((r.safe ++ r.mine).forall(c => all(c) && !revealed(c)))
        // Closure: no rule-1 deduction is still applicable at the fixpoint.
        for ((cell, n) <- clues) {
          val hidden = g.neighbours(cell._1, cell._2).filterNot(clues.contains)
          val unresolved = hidden.filterNot(c => r.mine(c) || r.safe(c))
          val k2 = n - hidden.count(r.mine)
          assert(unresolved.isEmpty || (k2 > 0 && k2 < unresolved.size), s"rule still applies at $cell")
        }
        assert(previous.mine.subsetOf(r.mine) && (previous.safe -- revealed).subsetOf(r.safe))
        previous = r
      }
    }
  }
}
