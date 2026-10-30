package minesweeper

import chisel3._
import chisel3.util.Valid
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class BoardTest extends AnyFlatSpec with ChiselScalatestTester {
  private def bits(indices: Iterable[Int]): BigInt =
    indices.foldLeft(BigInt(0))((mask, idx) => mask.setBit(idx))

  private def configure(dut: Board, g: GridPreset, count: Int = 0): Unit = {
    dut.io.config.preset.poke(g.id.U)
    dut.io.config.cols.poke(g.cols.U)
    dut.io.config.rows.poke(g.rows.U)
    dut.io.config.mines.poke(count.U)
  }

  private def idle(dut: Board): Unit = {
    dut.io.clear.poke(false.B)
    dut.io.loadMines.valid.poke(false.B)
    dut.io.loadMines.bits.poke(0.U)
    dut.io.beginAdjacency.poke(false.B)
    dut.io.placedMine.valid.poke(false.B)
    dut.io.placedMine.bits.poke(0.U)
    dut.io.revealSet.poke(0.U)
    dut.io.revealAllMines.poke(false.B)
    dut.io.flagToggle.valid.poke(false.B)
    dut.io.flagToggle.bits.poke(0.U)
  }

  private def initialize(dut: Board, g: GridPreset, count: Int = 0): Unit = {
    idle(dut)
    configure(dut, g, count)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def load(dut: Board, indices: Iterable[Int]): Unit = {
    dut.io.loadMines.bits.poke(bits(indices).U)
    dut.io.loadMines.valid.poke(true.B)
    dut.clock.step()
    dut.io.loadMines.valid.poke(false.B)
    var cycles = 0
    while (!dut.io.adjacencyReady.peek().litToBoolean && cycles < 65) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.adjacencyReady.peek().litToBoolean, "board adjacency scan did not finish")
  }

  private def flag(dut: Board, idx: Int): Unit = {
    dut.io.flagToggle.bits.poke(idx.U)
    dut.io.flagToggle.valid.poke(true.B)
    dut.clock.step()
    dut.io.flagToggle.valid.poke(false.B)
  }

  private def reveal(dut: Board, indices: Iterable[Int]): Unit = {
    dut.io.revealSet.poke(bits(indices).U)
    dut.clock.step()
    dut.io.revealSet.poke(0.U)
  }
  private def active(p: MsParams, g: GridPreset): Set[Int] =
    (for (r <- 0 until g.rows; c <- 0 until g.cols) yield r * p.maxCols + c).toSet

  private def check(dut: Board, p: MsParams, g: GridPreset,
      mines: Set[Int] = Set.empty, revealed: Set[Int] = Set.empty,
      flags: Set[Int] = Set.empty): Unit = {
    val enabled = active(p, g)
    dut.io.flagCount.expect(flags.intersect(enabled).size.U)
    dut.io.revealedSafeCount.expect((revealed.intersect(enabled) -- mines).size.U)
    for (idx <- 0 until p.cells) {
      val on = enabled(idx)
      val mine = on && mines(idx)
      val seen = on && revealed(idx)
      val flagged = on && flags(idx)
      val row = idx / p.maxCols
      val col = idx % p.maxCols
      val adj = if (!on) 0 else (for {
        rr <- 0 until g.rows
        cc <- 0 until g.cols
        if (rr != row || cc != col) && math.abs(rr - row) <= 1 && math.abs(cc - col) <= 1
        if mines(rr * p.maxCols + cc)
      } yield 1).sum
      withClue(s"cell=$idx ($row,$col): ") {
        dut.io.view(idx).active.expect(on.B)
        dut.io.view(idx).mine.expect(mine.B)
        dut.io.view(idx).revealed.expect(seen.B)
        dut.io.view(idx).flagged.expect(flagged.B)
        dut.io.view(idx).adj.expect(adj.U)
        dut.io.revealView(idx).active.expect(on.B)
        dut.io.revealView(idx).revealed.expect(seen.B)
        dut.io.revealView(idx).flagged.expect(flagged.B)
        dut.io.revealView(idx).adj.expect(adj.U)
        dut.io.solverView(idx).revealed.expect((seen && !mine).B)
        dut.io.solverView(idx).adj.expect((if (seen && !mine) adj else 0).U)
        assert(!(seen && flagged))
      }
    }
  }

  "Board" should "use the fixed stride and mask padding at every production preset" in {
    val p = MsParams()
    test(new Board(p)) { dut =>
      initialize(dut, p.presets.head, 14)
      for (g <- p.presets) {
        configure(dut, g, 14)
        dut.io.clear.poke(true.B)
        dut.clock.step()
        dut.io.clear.poke(false.B)
        check(dut, p, g)
        val enabled = active(p, g)
        val mines = enabled.toVector.sorted.take(14).toSet
        val padding = (0 until p.cells).toSet -- enabled
        load(dut, mines ++ padding)
        check(dut, p, g, mines)
        for (idx <- padding.headOption) flag(dut, idx)
        flag(dut, (1 << p.idxW) - 1)
        reveal(dut, padding)
        check(dut, p, g, mines)
        val hiddenSafe = enabled -- mines
        reveal(dut, hiddenSafe)
        check(dut, p, g, mines, hiddenSafe)
      }
    }
  }

  it should "accumulate streamed mine neighbors in parallel when enabled" in {
    val p = MsParams.forVerification(4, 4).copy(streamAdjacency = true)
    val g = p.presets.head
    val mines = Set(0, 5, 15)
    test(new Board(p)) { dut =>
      initialize(dut, g, mines.size)
      dut.io.beginAdjacency.poke(true.B)
      dut.clock.step()
      dut.io.beginAdjacency.poke(false.B)
      for (idx <- mines) {
        dut.io.placedMine.bits.poke(idx.U)
        dut.io.placedMine.valid.poke(true.B)
        dut.clock.step()
      }
      dut.io.placedMine.valid.poke(false.B)
      dut.io.loadMines.bits.poke(bits(mines).U)
      dut.io.loadMines.valid.poke(true.B)
      dut.clock.step()
      dut.io.loadMines.valid.poke(false.B)
      check(dut, p, g, mines)
      dut.io.adjacencyReady.expect(true.B)
    }
  }

  it should "count all eight neighbours without wrapping across rows" in {
    val p = MsParams.forVerification(4, 4)
    val g = p.presets.head
    test(new Board(p)) { dut =>
      initialize(dut, g)
      val ring = Set(0, 1, 2, 4, 6, 8, 9, 10)
      load(dut, ring)
      dut.io.view(5).adj.expect(8.U)
      dut.io.view(0).adj.expect(2.U)
      dut.io.solverView(5).adj.expect(0.U)
      check(dut, p, g, ring)
      reveal(dut, Set(5))
      dut.io.solverView(5).adj.expect(8.U)
      check(dut, p, g, ring, Set(5))
      load(dut, Set(3))
      dut.io.view(4).adj.expect(0.U)
      check(dut, p, g, Set(3), Set(5))
    }
  }

  it should "preserve flags during mine loading and ignore reveals of flagged cells" in {
    val p = MsParams.forVerification(7, 5)
    val g = p.presets.head
    test(new Board(p)) { dut =>
      initialize(dut, g)
      flag(dut, 0)
      flag(dut, 10)
      load(dut, Set(0, 8, 34))
      check(dut, p, g, Set(0, 8, 34), flags = Set(0, 10))
      reveal(dut, Set(0, 9, 10, 11))
      check(dut, p, g, Set(0, 8, 34), Set(9, 11), Set(0, 10))
      flag(dut, 9)
      flag(dut, 10)
      reveal(dut, Set(9, 10))
      check(dut, p, g, Set(0, 8, 34), Set(9, 10, 11), Set(0))
      dut.clock.step(3)
      check(dut, p, g, Set(0, 8, 34), Set(9, 10, 11), Set(0))
    }
  }

  it should "expose mines on loss while keeping them out of solver clues" in {
    val p = MsParams.forVerification(4, 4)
    val g = p.presets.head
    test(new Board(p)) { dut =>
      initialize(dut, g)
      val mines = Set(0, 6, 15)
      load(dut, mines)
      flag(dut, 0)
      flag(dut, 5)
      reveal(dut, Set(5, 14))
      dut.io.revealAllMines.poke(true.B)
      dut.clock.step()
      dut.io.revealAllMines.poke(false.B)
      check(dut, p, g, mines, mines + 14, Set(5))
      flag(dut, 6) 
      check(dut, p, g, mines, mines + 14, Set(5))
    }
  }

  it should "let clear dominate every write and reset all stored state" in {
    val p = MsParams.forVerification(7, 5).copy(
      presets = Vector(GridPreset(0, 7, 5), GridPreset(1, 4, 4)))
    test(new Board(p)) { dut =>
      initialize(dut, p.presets.head)
      load(dut, Set(0, 8, 34))
      flag(dut, 10)
      reveal(dut, Set(9))
      configure(dut, p.presets(1))
      dut.io.clear.poke(true.B)
      dut.io.loadMines.valid.poke(true.B)
      dut.io.loadMines.bits.poke(((BigInt(1) << p.cells) - 1).U)
      dut.io.revealSet.poke(((BigInt(1) << p.cells) - 1).U)
      dut.io.revealAllMines.poke(true.B)
      dut.io.flagToggle.valid.poke(true.B)
      dut.io.flagToggle.bits.poke(0.U)
      dut.clock.step()
      idle(dut)
      check(dut, p, p.presets(1))
      load(dut, Set(0, 8))
      flag(dut, 3)
      reveal(dut, Set(9))
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      check(dut, p, p.presets(1))
    }
  }

  it should "match an independent state model over mixed updates and grid changes" in {
    val p = MsParams.forVerification(5, 4).copy(
      presets = Vector(GridPreset(0, 5, 4), GridPreset(1, 3, 3)))
    test(new Board(p)) { dut =>
      initialize(dut, p.presets.head)
      val random = new scala.util.Random(20261022L)
      var g = p.presets.head
      var mines = Set.empty[Int]
      var seen = Set.empty[Int]
      var flags = Set.empty[Int]
      for (step <- 0 until 120) {
        val enabled = active(p, g)
        random.nextInt(6) match {
          case 0 =>
            val input = (0 until p.cells).filter(_ => random.nextBoolean()).toSet
            load(dut, input)
            mines = input.intersect(enabled)
          case 1 =>
            val input = (0 until p.cells).filter(_ => random.nextBoolean()).toSet
            reveal(dut, input)
            seen ++= input.intersect(enabled) -- flags
          case 2 =>
            val idx = random.nextInt(1 << p.idxW)
            flag(dut, idx)
            if (enabled(idx) && !seen(idx)) {
              flags = if (flags(idx)) flags - idx else flags + idx
            }
          case 3 =>
            dut.io.revealAllMines.poke(true.B)
            dut.clock.step()
            dut.io.revealAllMines.poke(false.B)
            seen ++= mines
            flags --= mines
          case 4 =>
            g = p.presets(random.nextInt(p.presets.size))
            configure(dut, g)
            dut.io.clear.poke(true.B)
            dut.clock.step()
            dut.io.clear.poke(false.B)
            mines = Set.empty; seen = Set.empty; flags = Set.empty
          case _ => dut.clock.step()
        }
        withClue(s"update=$step: ") { check(dut, p, g, mines, seen, flags) }
      }
    }
  }

  it should "represent full-capacity counters without truncation" in {
    val p = MsParams()
    test(new BoardCounterProbe(p)) { dut =>
      dut.io.clear.poke(false.B)
      dut.io.flagToggle.valid.poke(false.B)
      dut.io.flagToggle.bits.poke(0.U)
      dut.io.loadMines.valid.poke(false.B)
      dut.io.loadMines.bits.poke(0.U)
      dut.io.revealSet.poke(0.U)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.flagCount.expect(0.U)
      dut.io.revealedSafeCount.expect(0.U)
      dut.io.flagToggle.valid.poke(true.B)
      for (idx <- 0 until p.cells) {
        dut.io.flagToggle.bits.poke(idx.U)
        dut.clock.step()
        dut.io.flagCount.expect((idx + 1).U)
      }
      dut.io.flagToggle.valid.poke(false.B)
      dut.io.flagCount.expect(720.U)
      dut.io.revealedSafeCount.expect(0.U)
      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.clear.poke(false.B)
      dut.io.flagCount.expect(0.U)
      dut.io.revealSet.poke(((BigInt(1) << p.cells) - 1).U)
      dut.clock.step()
      dut.io.revealSet.poke(0.U)
      dut.io.revealedSafeCount.expect(720.U)
      dut.io.loadMines.bits.poke(bits(0 until 14).U)
      dut.io.loadMines.valid.poke(true.B)
      dut.clock.step()
      dut.io.loadMines.valid.poke(false.B)
      dut.io.revealedSafeCount.expect(706.U)
      dut.io.flagCount.expect(0.U)
    }
  }
}
private class BoardCounterProbe(p: MsParams) extends Module {
  val io = IO(new Bundle {
    val clear = Input(Bool())
    val flagToggle = Flipped(Valid(UInt(p.idxW.W)))
    val loadMines = Flipped(Valid(UInt(p.cells.W)))
    val revealSet = Input(UInt(p.cells.W))
    val flagCount = Output(UInt(p.cntW.W))
    val revealedSafeCount = Output(UInt(p.cntW.W))
  })
  private val board = Module(new Board(p))
  private val g = p.presets.last
  board.io.config.preset := g.id.U
  board.io.config.cols := g.cols.U
  board.io.config.rows := g.rows.U
  board.io.config.mines := 14.U
  board.io.clear := io.clear
  board.io.beginAdjacency := false.B
  board.io.placedMine.valid := false.B
  board.io.placedMine.bits := 0.U
  board.io.flagToggle := io.flagToggle
  board.io.loadMines := io.loadMines
  board.io.revealSet := io.revealSet
  board.io.revealAllMines := false.B
  io.flagCount := board.io.flagCount
  io.revealedSafeCount := board.io.revealedSafeCount
}
