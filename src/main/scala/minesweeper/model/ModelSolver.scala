package minesweeper.model

object ModelSolver {
  type Cell = (Int, Int)

  final case class Result(safe: Set[Cell], mine: Set[Cell], rounds: Int, contradiction: Boolean)

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

  def pairDecision(u1: Seq[Cell], k1: Int, u2: Seq[Cell], k2: Int): Option[(Set[Cell], Set[Cell])] = {
    val union = (u1 ++ u2).distinct
    require(union.size <= 16, s"${union.size} cells: too many to enumerate")
    val bit = union.zipWithIndex.toMap
    def mask(cells: Seq[Cell]): Int = cells.distinct.map(c => 1 << bit(c)).sum
    val (m1, m2) = (mask(u1), mask(u2))
    var inAll = (1 << union.size) - 1
    var inAny = 0
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

  def solveV2(grid: RefGrid, clues: Map[Cell, Int]): Result = {
    val ordered = clues.toVector.sortBy(_._1)
    var safe = Set.empty[Cell]
    var mine = Set.empty[Cell]
    var rounds = 0
    var progress = true
    var contradiction = false
    while (progress && !contradiction) {
      rounds += 1
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
