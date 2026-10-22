package minesweeper

import minesweeper.model.RefGrid
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

class RefGridTest extends AnyFlatSpec {
  // Hand-written expectations; none of them are computed from Params helpers.
  "RefGrid" should "give the explicit corner, edge and interior neighbours of an 8x8 grid" in {
    val g = RefGrid(rows = 8, cols = 8)
    assert(g.neighbours(0, 0).toSet == Set((0, 1), (1, 0), (1, 1)))
    assert(g.neighbours(0, 7).toSet == Set((0, 6), (1, 6), (1, 7)))
    assert(g.neighbours(7, 0).toSet == Set((6, 0), (6, 1), (7, 1)))
    assert(g.neighbours(7, 7).toSet == Set((6, 6), (6, 7), (7, 6)))
    assert(g.neighbours(0, 3).toSet == Set((0, 2), (0, 4), (1, 2), (1, 3), (1, 4)))
    assert(g.neighbours(5, 0).toSet == Set((4, 0), (6, 0), (4, 1), (5, 1), (6, 1)))
    assert(g.neighbours(4, 4).toSet ==
      Set((3, 3), (3, 4), (3, 5), (4, 3), (4, 5), (5, 3), (5, 4), (5, 5)))
  }

  it should "keep rectangular rows and columns apart and never wrap the right edge" in {
    val g = RefGrid(rows = 16, cols = 30) // preset 3: 30 columns x 16 rows
    assert(g.neighbours(0, 29).toSet == Set((0, 28), (1, 28), (1, 29)))
    assert(g.neighbours(1, 29).toSet == Set((0, 28), (0, 29), (1, 28), (2, 28), (2, 29)))
    assert(g.neighbours(15, 29).toSet == Set((14, 28), (14, 29), (15, 28)))
    assert(g.neighbours(15, 0).toSet == Set((14, 0), (14, 1), (15, 1)))
    assert(!g.contains(16, 0) && !g.contains(0, 30) && g.contains(15, 29))
  }

  it should "handle the smallest legal grid" in {
    val g = RefGrid(rows = 2, cols = 2)
    for ((r, c) <- g.cells)
      assert(g.neighbours(r, c).toSet == g.cells.toSet - ((r, c)))
  }

  it should "be symmetric and match the king-move edge count on every tested size" in {
    val sizes = MsParams.standardPresets.map(g => (g.rows, g.cols)) ++
      Seq((4, 4), (5, 7), (9, 9), (16, 8), (2, 3), (3, 2))
    for ((rows, cols) <- sizes) {
      val g = RefGrid(rows, cols)
      for (a <- g.cells; b <- g.neighbours(a._1, a._2)) {
        assert(g.neighbours(b._1, b._2).contains(a), s"$rows x $cols: $a/$b")
        assert(math.abs(a._1 - b._1) <= 1 && math.abs(a._2 - b._2) <= 1 && a != b)
      }
      val degreeSum = g.cells.map { case (r, c) => g.neighbours(r, c).size }.sum
      val edges = rows * (cols - 1) + cols * (rows - 1) + 2 * (rows - 1) * (cols - 1)
      assert(degreeSum == 2 * edges, s"$rows x $cols")
    }
  }

  it should "count adjacent mines on a hand-made board" in {
    val g = RefGrid(rows = 4, cols = 5)
    val mines = Set((0, 0), (0, 2), (3, 4))
    assert(g.adjacentMines(mines, 0, 1) == 2)
    assert(g.adjacentMines(mines, 1, 1) == 2)
    assert(g.adjacentMines(mines, 1, 3) == 1)
    assert(g.adjacentMines(mines, 2, 3) == 1)
    assert(g.adjacentMines(mines, 2, 1) == 0)
    // A mine's own bit is not part of its count.
    assert(g.adjacentMines(mines, 0, 0) == 0)
  }

  it should "map coordinates to the contract's physical stride" in {
    val g = RefGrid(rows = 8, cols = 8)
    assert(g.physicalIndex(1, 0, maxCols = 30) == 30) // contract 3.1 example
    assert(g.physicalIndex(7, 7, maxCols = 30) == 217)
    intercept[IllegalArgumentException](g.physicalIndex(0, 8, maxCols = 30))
    intercept[IllegalArgumentException](g.physicalIndex(0, 0, maxCols = 7))
  }

  // Agreement between two separately written implementations. The explicit tests
  // above, not this comparison, are the reference for correctness.
  it should "agree with the hardware wiring helper on every production preset" in {
    val p = MsParams()
    for (preset <- p.presets) {
      val g = RefGrid(preset.rows, preset.cols)
      for ((r, c) <- g.cells) {
        val ref = g.neighbours(r, c).map { case (nr, nc) => g.physicalIndex(nr, nc, p.maxCols) }
        assert(ref.toSet == p.neighborIdx(p.index(r, c), preset).toSet,
          s"preset ${preset.id} at ($r, $c)")
      }
    }
  }

  "The reference model sources" should "not use the hardware neighbour or masking helpers" in {
    val dir = Paths.get("src/main/scala/minesweeper/model")
    val files = Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".scala")).toSeq
    assert(files.nonEmpty)
    val forbidden = Seq("neighborIdx", "capacityNeighbors", "safeZone", "activeIndices",
      "activeMask", "isActive", "chisel3")
    for (f <- files; word <- forbidden)
      assert(!Files.readString(f).contains(word), s"$f mentions $word")
  }
}
