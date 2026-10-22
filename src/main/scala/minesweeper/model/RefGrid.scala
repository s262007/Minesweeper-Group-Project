package minesweeper.model

/**
  * Independent reference geometry for R3's model and checkers (CONTRACT 3.1).
  * Neighbours come from explicit row/column bounds on the active grid only;
  * this file deliberately shares no code with the hardware wiring helpers.
  */
final case class RefGrid(rows: Int, cols: Int) {
  require(rows >= 1 && cols >= 1, "a grid needs at least one row and column")

  def contains(row: Int, col: Int): Boolean =
    row >= 0 && row < rows && col >= 0 && col < cols

  /** Every (row, col) of the active grid in row-major order. */
  def cells: Vector[(Int, Int)] =
    Vector.tabulate(rows * cols)(i => (i / cols, i % cols))

  /** Active cells at most one row and one column away, excluding the cell itself. */
  def neighbours(row: Int, col: Int): Vector[(Int, Int)] = {
    require(contains(row, col), s"($row, $col) is outside the $cols x $rows grid")
    val top = math.max(row - 1, 0)
    val bottom = math.min(row + 1, rows - 1)
    val left = math.max(col - 1, 0)
    val right = math.min(col + 1, cols - 1)
    for {
      r <- (top to bottom).toVector
      c <- left to right
      if r != row || c != col
    } yield (r, c)
  }

  def adjacentMines(mines: Set[(Int, Int)], row: Int, col: Int): Int =
    neighbours(row, col).count(mines.contains)

  /** Storage index with the contract's fixed stride; padding columns are never produced. */
  def physicalIndex(row: Int, col: Int, maxCols: Int): Int = {
    require(cols <= maxCols, "active grid wider than the storage stride")
    require(contains(row, col), s"($row, $col) is outside the $cols x $rows grid")
    row * maxCols + col
  }
}
