package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class MineGenTest extends AnyFlatSpec with ChiselScalatestTester {
  private def initialize(dut: MineGen): Unit = {
    dut.io.clear.poke(false.B)
    dut.io.start.valid.poke(false.B)
    dut.io.start.bits.config.preset.poke(0.U)
    dut.io.start.bits.config.cols.poke(0.U)
    dut.io.start.bits.config.rows.poke(0.U)
    dut.io.start.bits.config.mines.poke(0.U)
    dut.io.start.bits.seed.poke(1.U)
    dut.io.start.bits.safeIdx.poke(0.U)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def request(dut: MineGen, p: MsParams, g: GridPreset,
      count: Int, seed: Int, row: Int, col: Int): Unit = {
    dut.io.start.bits.config.preset.poke(g.id.U)
    dut.io.start.bits.config.cols.poke(g.cols.U)
    dut.io.start.bits.config.rows.poke(g.rows.U)
    dut.io.start.bits.config.mines.poke(count.U)
    dut.io.start.bits.seed.poke(seed.U)
    dut.io.start.bits.safeIdx.poke((row * p.maxCols + col).U)
  }

  private def start(dut: MineGen): Unit = {
    dut.io.start.ready.expect(true.B)
    dut.io.start.valid.poke(true.B)
    dut.clock.step()
    dut.io.start.valid.poke(false.B)
  }

  // Count candidate attempts independently of the hardware's completion signal.
  private def attempts(g: GridPreset, count: Int, seed: Int, row: Int, col: Int): Int = {
    var state = seed
    var clocks = 0
    val placed = scala.collection.mutable.Set.empty[(Int, Int)]
    while (placed.size < count && clocks < 65535) {
      val candidate = (state - 1) % (g.cols * g.rows)
      val rr = candidate / g.cols
      val cc = candidate % g.cols
      if (math.abs(rr - row) > 1 || math.abs(cc - col) > 1) placed += ((rr, cc))
      state = (state >>> 1) ^ (if ((state & 1) == 1) 0xb400 else 0)
      clocks += 1
    }
    assert(placed.size == count)
    clocks
  }

  private def checkLayout(mask: BigInt, p: MsParams, g: GridPreset,
      count: Int, row: Int, col: Int): Unit = {
    assert(mask.bitCount == count)
    for (idx <- 0 until p.cells if mask.testBit(idx)) {
      val rr = idx / p.maxCols
      val cc = idx % p.maxCols
      assert(rr < g.rows && cc < g.cols, s"mine in padding at $rr,$cc")
      assert(math.abs(rr - row) > 1 || math.abs(cc - col) > 1,
        s"mine in protected zone at $rr,$cc")
    }
  }

  "MineGen" should "match the exact sequence at every production preset and mine-count boundary" in {
    val p = MsParams()
    test(new MineGen(p)) { dut =>
      initialize(dut)
      dut.clock.setTimeout(0)
      for (g <- p.presets; count <- Seq(14, p.maxMines(g)); seed <- Seq(1, 65535);
           (row, col) <- Seq((0, 0), (0, g.cols / 2), (g.rows - 1, g.cols - 1),
             (g.rows / 2, 0), (g.rows / 2, g.cols / 2))) {
        withClue(s"preset=${g.id}, mines=$count, seed=$seed, click=$row,$col: ") {
          request(dut, p, g, count, seed, row, col)
          start(dut)
          dut.io.busy.expect(true.B)
          dut.io.done.expect(false.B)
          dut.io.mines.expect(0.U)
          dut.io.cycles.expect(0.U)
          val n = attempts(g, count, seed, row, col)
          assert(n <= 65535)
          // The admission edge is not counted. Each subsequent edge processes one candidate.
          if (n > 1) {
            dut.clock.step(n - 1)
            dut.io.busy.expect(true.B)
            dut.io.done.expect(false.B)
            dut.io.cycles.expect((n - 1).U)
          }
          dut.clock.step()
          dut.io.done.expect(true.B)
          dut.io.busy.expect(false.B)
          dut.io.cycles.expect(n.U)
          val expected = Lfsr16.placeMines(p, GameSettings(g.id, count, seed), row, col)
            .foldLeft(BigInt(0))((bits, idx) => bits.setBit(idx))
          dut.io.mines.expect(expected.U)
          checkLayout(dut.io.mines.peek().litValue, p, g, count, row, col)
          dut.clock.step()
          dut.io.done.expect(false.B)
          dut.io.start.ready.expect(true.B)
          dut.clock.step(2)
          dut.io.mines.expect(expected.U)
          dut.io.cycles.expect(n.U)
        }
      }
      // Repeat an earlier request after other presets to catch stale placement state.
      val g = p.presets.head
      request(dut, p, g, 14, 1, 0, 0)
      start(dut)
      dut.clock.step(attempts(g, 14, 1, 0, 0))
      val expected = Lfsr16.placeMines(p, GameSettings(g.id, 14, 1), 0, 0)
        .foldLeft(BigInt(0))((bits, idx) => bits.setBit(idx))
      dut.io.mines.expect(expected.U)
      dut.io.done.expect(true.B)
    }
  }

  it should "reject protected and duplicate candidates while processing exactly one per clock" in {
    val p = MsParams.forVerification(4, 4)
    val g = p.presets.head
    test(new MineGen(p)) { dut =>
      initialize(dut)
      request(dut, p, g, 7, 1, 1, 1)
      start(dut)
      var state = 1
      var mask = BigInt(0)
      var n = 0
      var protectedRejected = 0
      var duplicateRejected = 0
      while (mask.bitCount < 7 && n < 65535) {
        val idx = (state - 1) % 16
        val rr = idx / 4
        val cc = idx % 4
        val protectedCell = math.abs(rr - 1) <= 1 && math.abs(cc - 1) <= 1
        if (protectedCell) protectedRejected += 1
        else if (mask.testBit(idx)) duplicateRejected += 1
        else mask = mask.setBit(idx)
        state = (state >>> 1) ^ (if ((state & 1) == 1) 0xb400 else 0)
        dut.clock.step()
        n += 1
        dut.io.cycles.expect(n.U)
        dut.io.mines.expect(mask.U)
        dut.io.done.expect((mask.bitCount == 7).B)
      }
      assert(mask.bitCount == 7 && protectedRejected > 0 && duplicateRejected > 0)
    }
  }

  it should "latch requests and refuse a second start while busy" in {
    val p = MsParams.forVerification(7, 5).copy(
      presets = Vector(GridPreset(0, 7, 5), GridPreset(1, 4, 4)))
    val g = p.presets.head
    test(new MineGen(p)) { dut =>
      initialize(dut)
      request(dut, p, g, 10, 0xace1, 2, 3)
      start(dut)
      // Hold a different, valid request while the first operation is active.
      request(dut, p, p.presets(1), 0, 1, 0, 0)
      dut.io.start.valid.poke(true.B)
      val n = attempts(g, 10, 0xace1, 2, 3)
      for (_ <- 0 until n) {
        dut.io.start.ready.expect(false.B)
        dut.clock.step()
      }
      dut.io.done.expect(true.B)
      val expected = Lfsr16.placeMines(p, GameSettings(0, 10, 0xace1), 2, 3)
        .foldLeft(BigInt(0))((bits, idx) => bits.setBit(idx))
      dut.io.mines.expect(expected.U)
      dut.io.cycles.expect(n.U)
      dut.io.start.ready.expect(false.B)
      dut.clock.step()
      dut.io.done.expect(false.B)
      dut.io.start.ready.expect(true.B)
      dut.io.mines.expect(expected.U)
      dut.clock.step()
      dut.io.start.valid.poke(false.B)
      dut.io.done.expect(true.B)
      dut.io.mines.expect(0.U)
      dut.io.cycles.expect(0.U)
    }
  }

  it should "let clear cancel work and dominate a simultaneous start" in {
    val p = MsParams.forVerification(4, 4)
    val g = p.presets.head
    test(new MineGen(p)) { dut =>
      initialize(dut)
      request(dut, p, g, 7, 1, 1, 1)
      start(dut)
      dut.clock.step(3)
      dut.io.clear.poke(true.B)
      dut.io.start.valid.poke(true.B)
      dut.io.start.ready.expect(false.B)
      dut.clock.step()
      dut.io.busy.expect(false.B)
      dut.io.done.expect(false.B)
      dut.io.mines.expect(0.U)
      dut.io.cycles.expect(0.U)
      dut.io.start.valid.poke(false.B)
      dut.io.clear.poke(false.B)
      dut.clock.step(3)
      dut.io.done.expect(false.B)
      dut.io.start.ready.expect(true.B)
      request(dut, p, g, 1, 65535, 0, 0)
      start(dut)
      dut.clock.step(attempts(g, 1, 65535, 0, 0))
      dut.io.done.expect(true.B)
      checkLayout(dut.io.mines.peek().litValue, p, g, 1, 0, 0)
      dut.io.clear.poke(true.B)
      dut.clock.step()
      dut.io.done.expect(false.B)
      dut.io.mines.expect(0.U)
      dut.io.cycles.expect(0.U)
    }
  }

  it should "reset an in-flight operation and complete zero-mine verification requests" in {
    val p = MsParams.forVerification(4, 4)
    val g = p.presets.head
    test(new MineGen(p)) { dut =>
      initialize(dut)
      request(dut, p, g, 7, 1, 1, 1)
      start(dut)
      dut.clock.step(3)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.io.busy.expect(false.B)
      dut.io.done.expect(false.B)
      dut.io.mines.expect(0.U)
      dut.io.cycles.expect(0.U)
      dut.reset.poke(false.B)
      request(dut, p, g, 0, 1, 3, 3)
      start(dut)
      dut.io.done.expect(true.B)
      dut.io.busy.expect(false.B)
      dut.io.mines.expect(0.U)
      dut.io.cycles.expect(0.U)
      dut.clock.step()
      dut.io.done.expect(false.B)
      dut.io.start.ready.expect(true.B)
    }
  }
}
