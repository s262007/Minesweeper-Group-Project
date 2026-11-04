package minesweeper.model

/**
  * Plain Scala v1 solver (CONTRACT 5.6, rule 1), written from the rule text for
  * co-simulation of the hardware hints. It shares no code with Solver.scala.
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
}
