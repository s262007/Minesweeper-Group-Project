package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/** A plain Scala board that drives the RevealEngine in tests. Neighbours and the BFS
  * are written here from coordinates, independently of MsParams.capacityNeighbors.
  */
case class RevealBoard(
    g: GridPreset,
    mines: Set[(Int, Int)],
    flags: Set[(Int, Int)] = Set.empty,
    revealed: Set[(Int, Int)] = Set.empty
) {
  def inGrid(cell: (Int, Int)): Boolean =
    cell._1 >= 0 && cell._1 < g.rows && cell._2 >= 0 && cell._2 < g.cols

  def neighbours(cell: (Int, Int)): Seq[(Int, Int)] =
    for {
      dr <- -1 to 1
      dc <- -1 to 1
      if dr != 0 || dc != 0
      n = (cell._1 + dr, cell._2 + dc)
      if inGrid(n)
    } yield n

  def adj(cell: (Int, Int)): Int = neighbours(cell).count(mines)

  def cells: Seq[(Int, Int)] = for (r <- 0 until g.rows; c <- 0 until g.cols) yield (r, c)

  /** Expected reveal layers: BFS from the click. Zero cells expand, numbered cells stop,
    * flagged and already revealed cells are never reached.
    */
  def expectedLayers(click: (Int, Int)): Vector[Set[(Int, Int)]] = {
    var layers = Vector(Set(click))
    var seen = Set(click)
    var frontier = Set(click)
    while (frontier.nonEmpty) {
      val next = frontier.filter(adj(_) == 0).flatMap(neighbours)
        .filterNot(n => seen(n) || flags(n) || revealed(n))
      seen ++= next
      frontier = next
      if (next.nonEmpty) layers :+= next
    }
    layers
  }
}

case class RevealRun(layersSeen: Vector[Set[(Int, Int)]], layers: Int, cycles: Int)

/** R2-S1 acceptance tests for CONTRACT 5.5 (frontier flood fill, one ring per cycle). */
class RevealEngineTest extends AnyFlatSpec with ChiselScalatestTester {
  // Same verification matrix as the S0 tests, as (cols, rows).
  private val configs = Seq((4, 4), (7, 5), (9, 9), (16, 8))
    .map { case (cols, rows) => MsParams.forVerification(cols, rows) }

  /** 8x6 storage with a smaller 5x4 preset, to check that padding is never touched. */
  private val padded = MsParams(
    maxCols = 8, maxRows = 6,
    presets = Vector(GridPreset(0, 8, 6), GridPreset(1, 5, 4)),
    minMines = 0, defaultPresetId = 0, defaultMines = 0, verification = true
  )

  private def cellOf(p: MsParams, i: Int): (Int, Int) = (i / p.maxCols, i % p.maxCols)

  /** The test plays the Board: it drives the whole RevealCellView vector. */
  private def pokeView(dut: RevealEngine, p: MsParams, b: RevealBoard): Unit =
    for (i <- 0 until p.cells) {
      val cell = cellOf(p, i)
      val on = b.inGrid(cell)
      val v = dut.io.view(i)
      v.active.poke(on.B)
      v.revealed.poke((on && b.revealed(cell)).B)
      v.flagged.poke((on && b.flags(cell)).B)
      v.adj.poke((if (on) b.adj(cell) else 0).U)
    }

  /** Starts one reveal, commits every emitted layer like the Board does, and checks the
    * handshake and done timing of CONTRACT 5.5 on the way.
    */
  private def reveal(dut: RevealEngine, p: MsParams, b: RevealBoard, click: (Int, Int)): RevealRun = {
    dut.io.clear.poke(false.B)
    pokeView(dut, p, b)
    val req = dut.io.start.bits
    req.config.preset.poke(b.g.id.U)
    req.config.cols.poke(b.g.cols.U)
    req.config.rows.poke(b.g.rows.U)
    req.config.mines.poke(b.mines.size.U)
    req.idx.poke((click._1 * p.maxCols + click._2).U)
    dut.io.start.valid.poke(true.B)
    dut.io.start.ready.expect(true.B)
    dut.io.busy.expect(false.B)
    dut.clock.step() // admission edge
    dut.io.start.valid.poke(false.B)

    val seen = ArrayBuffer[Set[(Int, Int)]]()
    while (!dut.io.done.peekBoolean()) {
      assert(seen.size <= p.cells, "reveal did not finish within cells cycles")
      dut.io.busy.expect(true.B)
      dut.io.start.ready.expect(false.B)
      val mask = dut.io.revealSet.peek().litValue
      val layer = (0 until p.cells).filter(mask.testBit).map(cellOf(p, _)).toSet
      seen += layer
      // The Board commits this layer on the coming edge.
      for (cell <- layer if b.inGrid(cell))
        dut.io.view(cell._1 * p.maxCols + cell._2).revealed.poke(true.B)
      dut.clock.step()
    }
    // Done cycle: busy has fallen and nothing more is revealed.
    dut.io.busy.expect(false.B)
    dut.io.revealSet.expect(0.U)
    val layers = dut.io.layers.peek().litValue.toInt
    val cycles = dut.io.cycles.peek().litValue.toInt
    dut.clock.step()
    dut.io.done.expect(false.B) // one-cycle pulse
    dut.io.busy.expect(false.B)
    dut.io.layers.expect(layers.U) // results stay until the next start
    dut.io.cycles.expect(cycles.U)
    RevealRun(seen.toVector, layers, cycles)
  }

  /** Acceptance check shared by every scenario. */
  private def checkAgainstModel(b: RevealBoard, click: (Int, Int), run: RevealRun): Unit = {
    val expected = b.expectedLayers(click)
    assert(run.layersSeen == expected, s"layers differ for click $click on ${b.g.cols}x${b.g.rows}")
    assert(run.layers == expected.size, "layers counter differs from the BFS depth")
    assert(run.cycles == expected.size, "cycles must equal layers (one ring per cycle)")
    val all = run.layersSeen.flatten
    assert(all.forall(b.inGrid), "revealed a padding cell")
    assert(!all.exists(b.mines), "revealed a mine")
    assert(!all.exists(b.flags), "revealed a flagged cell")
    assert(!all.exists(b.revealed), "revealed an already revealed cell again")
    assert(all.size == all.toSet.size, "revealed a cell twice")
  }

  "RevealEngine" should "reveal only the clicked cell when it has a number" in {
    val p = MsParams.forVerification(4, 4)
    val b = RevealBoard(p.presets.head, mines = Set((0, 0)))
    test(new RevealEngine(p)) { dut =>
      val run = reveal(dut, p, b, (1, 1))
      assert(run.layersSeen == Vector(Set((1, 1))))
      assert(run.layers == 1 && run.cycles == 1)
    }
  }

  it should "cascade through a zero region and stop at the numbered boundary" in {
    val p = MsParams.forVerification(7, 5)
    // . . . . . * .
    // . . . . . . .
    // . . * . . . .
    // . . . . . . *
    // . . . . . . .
    val b = RevealBoard(p.presets.head, mines = Set((0, 5), (2, 2), (3, 6)))
    test(new RevealEngine(p)) { dut =>
      val run = reveal(dut, p, b, (4, 0))
      checkAgainstModel(b, (4, 0), run)
      val all = run.layersSeen.flatten.toSet
      assert(all.contains((3, 1)) && b.adj((3, 1)) == 1, "numbered boundary cell must be revealed")
      assert(run.layers > 1)
    }
  }

  it should "not cross a wall of flags" in {
    val p = MsParams.forVerification(7, 5)
    val wall = (0 until 5).map(r => (r, 3)).toSet
    val b = RevealBoard(p.presets.head, mines = Set.empty, flags = wall)
    test(new RevealEngine(p)) { dut =>
      val run = reveal(dut, p, b, (2, 0))
      checkAgainstModel(b, (2, 0), run)
      assert(run.layersSeen.flatten.forall(_._2 < 3), "the cascade crossed the flag wall")
    }
  }

  it should "need max(rows, cols) layers on an empty board clicked in a corner" in {
    for (p <- configs) {
      val g = p.presets.head
      val b = RevealBoard(g, mines = Set.empty)
      test(new RevealEngine(p)) { dut =>
        val run = reveal(dut, p, b, (0, 0))
        checkAgainstModel(b, (0, 0), run)
        assert(run.layers == math.max(g.rows, g.cols))
        assert(run.layersSeen.flatten.size == g.cells)
      }
    }
  }

  it should "skip cells revealed by an earlier move" in {
    val p = MsParams.forVerification(9, 9)
    val g = p.presets.head
    val earlier = (for (r <- 0 until 9; c <- 0 until 4) yield (r, c)).toSet
    val b = RevealBoard(g, mines = Set.empty, revealed = earlier)
    test(new RevealEngine(p)) { dut =>
      val run = reveal(dut, p, b, (8, 8))
      checkAgainstModel(b, (8, 8), run)
      assert(run.layersSeen.flatten.size == 9 * 5)
    }
  }

  it should "stay inside a smaller active preset and never touch the padding" in {
    val g = padded.presets(1) // 5 columns x 4 rows inside 8x6 storage
    val b = RevealBoard(g, mines = Set.empty)
    test(new RevealEngine(padded)) { dut =>
      val run = reveal(dut, padded, b, (0, 0))
      checkAgainstModel(b, (0, 0), run)
      assert(run.layersSeen.flatten.size == 20 && run.layers == 5)
    }
  }

  it should "ignore a start on a flagged, revealed or padding cell" in {
    val g = padded.presets(1)
    val b = RevealBoard(g, mines = Set.empty, flags = Set((1, 1)), revealed = Set((2, 2)))
    test(new RevealEngine(padded)) { dut =>
      for (click <- Seq((1, 1), (2, 2), (0, 6), (5, 0))) {
        val run = reveal(dut, padded, b, click)
        assert(run.layersSeen.isEmpty && run.layers == 0 && run.cycles == 0, s"click $click")
      }
    }
  }

  it should "refuse a start while busy and cancel cleanly on clear" in {
    val p = MsParams.forVerification(9, 9)
    val b = RevealBoard(p.presets.head, mines = Set.empty)
    test(new RevealEngine(p)) { dut =>
      dut.io.clear.poke(false.B)
      pokeView(dut, p, b)
      dut.io.start.bits.idx.poke(p.index(0, 0).U)
      dut.io.start.valid.poke(true.B)
      dut.clock.step() // admitted
      // The request stays valid: a second start must not be taken while busy.
      dut.io.busy.expect(true.B)
      dut.io.start.ready.expect(false.B)
      dut.clock.step()
      dut.io.busy.expect(true.B)
      dut.io.clear.poke(true.B)
      dut.io.start.ready.expect(false.B)
      dut.io.revealSet.expect(0.U)
      dut.io.done.expect(false.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)
      dut.io.start.valid.poke(false.B)
      dut.io.busy.expect(false.B)
      dut.io.done.expect(false.B)
      dut.io.revealSet.expect(0.U)
      dut.io.layers.expect(0.U)
      dut.io.cycles.expect(0.U)
      dut.io.start.ready.expect(true.B)
      // The engine still works after a clear.
      val run = reveal(dut, p, b, (8, 8))
      checkAgainstModel(b, (8, 8), run)
    }
  }

  it should "match the independent BFS on 20 random layouts per configuration" in {
    for (p <- configs) {
      val g = p.presets.head
      val rnd = new Random(1000 + 100 * g.cols + g.rows)
      test(new RevealEngine(p)) { dut =>
        var checked = 0
        for (_ <- 0 until 20) {
          val density = 0.05 + 0.25 * rnd.nextDouble()
          val all = for (r <- 0 until g.rows; c <- 0 until g.cols) yield (r, c)
          val mines = all.filter(_ => rnd.nextDouble() < density).toSet
          val flags = rnd.shuffle(all).take(rnd.nextInt(4)).toSet
          val base = RevealBoard(g, mines, flags)
          // Sometimes an earlier move has already revealed a region.
          val safe = all.filterNot(c => mines(c) || flags(c))
          val earlier =
            if (safe.nonEmpty && rnd.nextBoolean()) base.expectedLayers(safe(rnd.nextInt(safe.size))).flatten.toSet
            else Set.empty[(Int, Int)]
          val b = base.copy(revealed = earlier)
          val candidates = safe.filterNot(earlier)
          if (candidates.nonEmpty) {
            val click = candidates(rnd.nextInt(candidates.size))
            checkAgainstModel(b, click, reveal(dut, p, b, click))
            checked += 1
          }
        }
        assert(checked >= 15, s"too few usable random layouts on ${g.cols}x${g.rows}")
      }
    }
  }

  it should "work at production capacity (30x24 storage) on 8x8 and 30x16 games" in {
    val p = MsParams()
    val rnd = new Random(42)
    test(new RevealEngine(p)) { dut =>
      for (g <- Seq(p.presets(0), p.presets(3)); mineCount <- Seq(14, g.cells / 6)) {
        val all = for (r <- 0 until g.rows; c <- 0 until g.cols) yield (r, c)
        val mines = rnd.shuffle(all).take(mineCount).toSet
        val b = RevealBoard(g, mines)
        val zeros = all.filter(c => !mines(c) && b.adj(c) == 0)
        val click = if (zeros.nonEmpty) zeros(rnd.nextInt(zeros.size)) else all.filterNot(mines).head
        checkAgainstModel(b, click, reveal(dut, p, b, click))
      }
    }
  }
}
