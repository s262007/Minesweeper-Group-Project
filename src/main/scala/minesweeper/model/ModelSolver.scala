package minesweeper.model

/**
  * Plain Scala solvers (CONTRACT 5.6), written from the rule text for co-simulation of
  * the hardware hints: `solveV1` (rule 1) and `solveV2` (rules 1 and 2). They share no
  * code with Solver.scala.
  *
  * Every round evaluates all clues against the knowledge of the previous round,
  * then adds the new deductions. The loop stops at the first round that adds
  * nothing, so `rounds` includes that final round.
  */
object ModelSolver {
  type Cell = (Int, Int)

  final case class Result(safe: Set[Cell], mine: Set[Cell], rounds: Int, contradiction: Boolean)

  /**
    * @param grid  active grid
    * @param clues revealed safe cells and their numbers; every other active cell is unknown.
    *              Flags are not an input: they are player annotations, not facts.
    */
  def solveV1(grid: RefGrid, clues: Map[Cell, Int]): Result = {
    var safe = Set.empty[Cell]
    var mine = Set.empty[Cell]
    var rounds = 0
    var progress = true
    var contradiction = false
    while (progress && !contradiction) {
      rounds += 1
      var newSafe = Set.empty[Cell]
      var newMine = Set.empty[Cell]
      for ((cell, number) <- clues) {
        val hidden = grid.neighbours(cell._1, cell._2).filterNot(clues.contains)
        val knownMines = hidden.count(mine.contains)
        val unresolved = hidden.filterNot(c => mine.contains(c) || safe.contains(c))
        // Residual k = number - M must lie in 0..|U|; check before any subtraction is trusted.
        val k = number - knownMines
        if (k < 0 || k > unresolved.size) contradiction = true
        else if (unresolved.nonEmpty && k == 0) newSafe ++= unresolved
        else if (unresolved.nonEmpty && k == unresolved.size) newMine ++= unresolved
      }
      if ((newSafe & newMine).nonEmpty) contradiction = true
      progress = (newSafe -- safe).nonEmpty || (newMine -- mine).nonEmpty
      safe ++= newSafe
      mine ++= newMine
    }
    if (contradiction) Result(Set.empty, Set.empty, rounds, contradiction = true)
    else Result(safe, mine, rounds, contradiction = false)
  }

  /**
    * Two clues taken together (CONTRACT 5.6, rule 2), decided by enumeration rather than with
    * the contract's L and U bounds: every placement of mines on the union of the two
    * unresolved sets is tried, and only the placements with exactly k1 mines in u1 and k2 in
    * u2 are kept. A cell that is a mine in every kept placement is a mine; a cell that is a
    * mine in none is safe. Returns None when no placement is kept (the clues contradict each
    * other), otherwise the safe and mine cells. A clue has at most 8 neighbours, so the union
    * has at most 16 cells.
    */
  def pairDecision(u1: Seq[Cell], k1: Int, u2: Seq[Cell], k2: Int): Option[(Set[Cell], Set[Cell])] = {
    val union = (u1 ++ u2).distinct
    require(union.size <= 16, s"${union.size} cells: too many to enumerate")
    val bit = union.zipWithIndex.toMap
    def mask(cells: Seq[Cell]): Int = cells.distinct.map(c => 1 << bit(c)).sum
    val (m1, m2) = (mask(u1), mask(u2))
    var inAll = (1 << union.size) - 1 // mine in every kept placement
    var inAny = 0 // mine in at least one kept placement
    var kept = false
    for (placement <- 0 until (1 << union.size)) {
      if (Integer.bitCount(placement & m1) == k1 && Integer.bitCount(placement & m2) == k2) {
        kept = true
        inAll &= placement
        inAny |= placement
      }
    }
    if (!kept) None
    else Some((union.filter(c => (inAny >> bit(c) & 1) == 0).toSet, union.filter(c => (inAll >> bit(c) & 1) == 1).toSet))
  }

  /**
    * Plain Scala v2 solver (CONTRACT 5.6, rules 1 and 2), for co-simulation of the v2 hints.
    * Every round applies rule 1 to each clue and `pairDecision` to each pair of clues whose
    * unresolved sets overlap, all against the knowledge of the previous round. Pairs that do
    * not overlap give nothing beyond rule 1. The loop stops at the first round that adds
    * nothing; `rounds` includes that round.
    */
  def solveV2(grid: RefGrid, clues: Map[Cell, Int]): Result = {
    val ordered = clues.toVector.sortBy(_._1)
    var safe = Set.empty[Cell]
    var mine = Set.empty[Cell]
    var rounds = 0
    var progress = true
    var contradiction = false
    while (progress && !contradiction) {
      rounds += 1
      // Each clue as its unresolved cells and the number of mines among them.
      val constraints = ordered.map { case (cell, number) =>
        val hidden = grid.neighbours(cell._1, cell._2).filterNot(clues.contains)
        (hidden.filterNot(c => mine(c) || safe(c)), number - hidden.count(mine))
      }
      var newSafe = Set.empty[Cell]
      var newMine = Set.empty[Cell]
      for ((unresolved, k) <- constraints) {
        if (k < 0 || k > unresolved.size) contradiction = true
        else if (unresolved.nonEmpty && k == 0) newSafe ++= unresolved
        else if (unresolved.nonEmpty && k == unresolved.size) newMine ++= unresolved
      }
      for (i <- constraints.indices; j <- i + 1 until constraints.size) {
        val (u1, k1) = constraints(i)
        val (u2, k2) = constraints(j)
        if (u1.exists(u2.contains)) {
          pairDecision(u1, k1, u2, k2) match {
            case None => contradiction = true
            case Some((s, m)) =>
              newSafe ++= s
              newMine ++= m
          }
        }
      }
      if ((newSafe & newMine).nonEmpty) contradiction = true
      progress = (newSafe -- safe).nonEmpty || (newMine -- mine).nonEmpty
      safe ++= newSafe
      mine ++= newMine
    }
    if (contradiction) Result(Set.empty, Set.empty, rounds, contradiction = true)
    else Result(safe, mine, rounds, contradiction = false)
  }
}
