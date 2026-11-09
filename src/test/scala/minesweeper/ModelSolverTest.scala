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

  // ---------------------------------------------------------------- v2 (R3-S4)

  "ModelSolver v2" should "decide the contract's partial-overlap example" in {
    // CONTRACT 5.6: A = {i1, i2, x1, x2} with 1 mine, B = {i1, i2, y} with 2 mines.
    val (i1, i2, x1, x2, y) = ((0, 0), (0, 1), (1, 0), (1, 1), (2, 0))
    val d = ModelSolver.pairDecision(Seq(i1, i2, x1, x2), 1, Seq(i1, i2, y), 2)
    assert(d.contains((Set(x1, x2), Set(y))), s"got $d")
  }

  it should "agree with the contract's L and U bounds for every small pair" in {
    // Parts X = A - B, I = A & B, Y = B - A of up to 3 cells each, every residual pair,
    // including impossible ones. The bounds are written here from the CONTRACT 5.6 text.
    def cells(tag: Int, n: Int): Seq[Cell] = (0 until n).map(k => (tag, k))
    var exact = 0
    for (nx <- 0 to 3; ni <- 0 to 3; ny <- 0 to 3; a <- -1 to nx + ni + 1; b <- -1 to ni + ny + 1) {
      val (xs, is, ys) = (cells(0, nx), cells(1, ni), cells(2, ny))
      val lo = Seq(0, a - nx, b - ny).max
      val hi = Seq(ni, a, b).min
      val expected =
        if (lo > hi) None
        else if (lo < hi) Some((Set.empty[Cell], Set.empty[Cell]))
        else {
          exact += 1
          val q = lo
          def part(c: Seq[Cell], count: Int, size: Int): (Set[Cell], Set[Cell]) =
            (if (count == 0) c.toSet else Set.empty[Cell], if (count == size) c.toSet else Set.empty[Cell])
          val parts = Seq(part(xs, a - q, nx), part(is, q, ni), part(ys, b - q, ny))
          Some((parts.flatMap(_._1).toSet, parts.flatMap(_._2).toSet))
        }
      val got = ModelSolver.pairDecision(xs ++ is, a, is ++ ys, b)
      assert(got == expected, s"|X|=$nx |I|=$ni |Y|=$ny a=$a b=$b: got $got, expected $expected")
    }
    assert(exact > 0)
  }

  it should "solve 1-2-1 against a wall, which rule 1 alone cannot" in {
    val g = RefGrid(rows = 2, cols = 3)
    val mines = Set((0, 0), (0, 2))
    val clues = cluesOf(g, mines, Set((1, 0), (1, 1), (1, 2)))
    val v1 = ModelSolver.solveV1(g, clues)
    assert(v1.safe.isEmpty && v1.mine.isEmpty)
    val v2 = ModelSolver.solveV2(g, clues)
    assert(v2.safe == Set((0, 1)) && v2.mine == mines && !v2.contradiction)
  }

  it should "deduce from a partial overlap on a board" in {
    // (1,1) = 1 sees {(0,0), (0,1), (0,2), (1,0)}; (1,2) = 3 sees {(0,1), (0,2), (0,3), (1,3)}.
    // I = {(0,1), (0,2)}, L = U = 1: the cells only (1,1) sees are safe, those only (1,2) sees are mines.
    val g = RefGrid(rows = 2, cols = 4)
    val clues = cluesOf(g, Set((0, 1), (0, 3), (1, 3)), Set((1, 1), (1, 2)))
    assert(clues == Map((1, 1) -> 1, (1, 2) -> 3))
    val v1 = ModelSolver.solveV1(g, clues)
    assert(v1.safe.isEmpty && v1.mine.isEmpty)
    val v2 = ModelSolver.solveV2(g, clues)
    assert(v2.safe == Set((0, 0), (1, 0)) && v2.mine == Set((0, 3), (1, 3)))
  }

  it should "detect clues that contradict each other, even when each alone is satisfiable" in {
    // B = {b1, b2, b3} holds 1 mine and A = B + {x} holds 3, so x would hold 2: no placement.
    // Neither clue alone is 0 or full, so rule 1 sees nothing.
    val (b1, b2, b3, x) = ((0, 0), (0, 1), (0, 2), (1, 0))
    assert(ModelSolver.pairDecision(Seq(b1, b2, b3, x), 3, Seq(b1, b2, b3), 1).isEmpty)
    // On a board: a 1 and a 0 that share their only hidden cell.
    val r = ModelSolver.solveV2(RefGrid(rows = 1, cols = 3), Map((0, 0) -> 1, (0, 2) -> 0))
    assert(r.contradiction && r.safe.isEmpty && r.mine.isEmpty)
  }

  it should "be sound, contain v1, be closed under both rules and only gain knowledge" in {
    val rnd = new Random(11)
    var beyondV1 = 0
    for (_ <- 0 until 200) {
      val g = RefGrid(rows = 2 + rnd.nextInt(5), cols = 2 + rnd.nextInt(5))
      val all = g.cells.toSet
      val mines = all.filter(_ => rnd.nextInt(4) == 0)
      val safeCells = (all -- mines).toVector
      val order = rnd.shuffle(safeCells)
      var previous = ModelSolver.Result(Set.empty, Set.empty, 0, contradiction = false)
      for (k <- Seq(1, safeCells.size / 4, safeCells.size / 2, safeCells.size) if k > 0) {
        val revealed = order.take(k).toSet
        val clues = cluesOf(g, mines, revealed)
        val r = ModelSolver.solveV2(g, clues)
        val v1 = ModelSolver.solveV1(g, clues)
        assert(!r.contradiction)
        assert(r.mine.subsetOf(mines) && (r.safe & mines).isEmpty, "unsound hint")
        assert((r.safe ++ r.mine).forall(c => all(c) && !revealed(c)))
        assert(v1.safe.subsetOf(r.safe) && v1.mine.subsetOf(r.mine), "v2 lost a v1 deduction")
        if (r.safe.size + r.mine.size > v1.safe.size + v1.mine.size) beyondV1 += 1
        // Every layout that fits the clues agrees with the hints (small boards only).
        val hidden = (all -- revealed).toVector
        if (hidden.size <= 10) {
          for (bits <- 0 until (1 << hidden.size)) {
            val layout = hidden.indices.filter(i => (bits >> i & 1) == 1).map(hidden).toSet
            if (cluesOf(g, layout, revealed) == clues)
              assert(r.mine.subsetOf(layout) && (r.safe & layout).isEmpty, "a hint contradicts a fitting layout")
          }
        }
        // Closure: neither rule adds anything at the fixpoint.
        val constraints = clues.toVector.map { case (cell, n) =>
          val h = g.neighbours(cell._1, cell._2).filterNot(clues.contains)
          (h.filterNot(c => r.mine(c) || r.safe(c)), n - h.count(r.mine))
        }
        for ((u, k2) <- constraints)
          assert(u.isEmpty || (k2 > 0 && k2 < u.size), "rule 1 still applies")
        for (i <- constraints.indices; j <- i + 1 until constraints.size) {
          val ((u1, k1), (u2, k2)) = (constraints(i), constraints(j))
          if (u1.exists(u2.contains)) {
            val Some((s, m)) = ModelSolver.pairDecision(u1, k1, u2, k2)
            assert(s.isEmpty && m.isEmpty, "rule 2 still applies")
          }
        }
        assert(previous.mine.subsetOf(r.mine) && (previous.safe -- revealed).subsetOf(r.safe))
        previous = r
      }
    }
    assert(beyondV1 > 0, "no random board needed rule 2")
  }
}
