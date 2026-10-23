package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class UartReplySerializerTest extends AnyFlatSpec with ChiselScalatestTester {
  private val p = MsParams.forVerification(9, 9)
  private val production = MsParams()

  private def initialize(dut: UartReplySerializer, params: MsParams): Unit = {
    dut.io.start.valid.poke(false.B)
    dut.io.start.bits.hello.poke(false.B)
    dut.io.start.bits.sequence.poke(0.U)
    dut.io.snapshot.config.preset.poke(0.U)
    dut.io.snapshot.config.cols.poke(params.maxCols.U)
    dut.io.snapshot.config.rows.poke(params.maxRows.U)
    dut.io.snapshot.config.mines.poke(params.defaultMines.U)
    dut.io.snapshot.state.poke(GameState.Idle)
    for (i <- 0 until params.cells) {
      dut.io.snapshot.cells(i).active.poke(false.B)
      dut.io.snapshot.cells(i).kind.poke(PublicCellKind.Hidden)
      dut.io.snapshot.cells(i).adj.poke(0.U)
      dut.io.snapshot.hints(i).safe.poke(false.B)
      dut.io.snapshot.hints(i).mine.poke(false.B)
    }
    dut.io.snapshot.hintsValid.poke(false.B)
    dut.io.snapshot.minesLeft.poke(0.S)
    dut.io.snapshot.revision.poke(0.U)
    dut.io.snapshot.generationCycles.poke(0.U)
    dut.io.snapshot.revealCycles.poke(0.U)
    dut.io.snapshot.revealLayers.poke(0.U)
    dut.io.snapshot.solverCycles.poke(0.U)
    dut.io.tx.ready.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def start(dut: UartReplySerializer, hello: Boolean, sequence: Int): Unit = {
    dut.io.start.bits.hello.poke(hello.B)
    dut.io.start.bits.sequence.poke(sequence.U)
    dut.io.start.valid.poke(true.B)
    dut.io.start.ready.expect(true.B)
    dut.clock.step()
    dut.io.start.valid.poke(false.B)
  }

  private def drain(dut: UartReplySerializer, limit: Int = 10000): String = {
    val chars = new StringBuilder
    dut.io.tx.ready.poke(true.B)
    var clocks = 0
    while (dut.io.busy.peek().litToBoolean && clocks < limit) {
      if (dut.io.tx.valid.peek().litToBoolean)
        chars.append(dut.io.tx.bits.peek().litValue.toInt.toChar)
      dut.clock.step()
      clocks += 1
    }
    assert(clocks < limit, "reply serializer did not finish")
    dut.io.done.expect(true.B)
    chars.toString
  }

  "UartReplySerializer" should "emit HELLO information, every preset and END" in {
    test(new UartReplySerializer(production)) { dut =>
      initialize(dut, production)
      dut.io.snapshot.revision.poke(42.U)
      start(dut, hello = true, sequence = 0x2a)
      val reply = drain(dut)
      assert(reply ==
        "INFO 002A 2 1.2.0 30 24 014 V1 1\n" +
        "PRESET 002A 0 08 08 014 055\n" +
        "PRESET 002A 1 09 09 014 072\n" +
        "PRESET 002A 2 16 16 014 247\n" +
        "PRESET 002A 3 30 16 014 471\n" +
        "PRESET 002A 4 30 24 014 711\n" +
        "END 002A 0000000042\n")
      dut.io.busy.expect(false.B)
    }
  }

  it should "stream a stable public snapshot as ordered row and hint lines" in {
    test(new UartReplySerializer(p)) { dut =>
      initialize(dut, p)
      dut.io.snapshot.config.cols.poke(9.U)
      dut.io.snapshot.config.rows.poke(9.U)
      dut.io.snapshot.config.mines.poke(14.U)
      dut.io.snapshot.state.poke(GameState.Playing)
      dut.io.snapshot.hintsValid.poke(true.B)
      dut.io.snapshot.minesLeft.poke((-2).S)
      dut.io.snapshot.revision.poke(9.U)
      for (i <- 0 until 9; j <- 0 until 9)
        dut.io.snapshot.cells(i * p.maxCols + j).active.poke(true.B)
      dut.io.snapshot.cells(0).kind.poke(PublicCellKind.Number)
      dut.io.snapshot.cells(0).adj.poke(0.U)
      dut.io.snapshot.cells(1).kind.poke(PublicCellKind.Flagged)
      dut.io.snapshot.cells(2).kind.poke(PublicCellKind.Number)
      dut.io.snapshot.cells(2).adj.poke(8.U)
      dut.io.snapshot.hints(0).safe.poke(true.B)
      dut.io.snapshot.hints(p.maxCols + 1).mine.poke(true.B)
      start(dut, hello = false, sequence = 0x1234)
      val reply = drain(dut)
      assert(reply.startsWith("SNAP 1234 0000000009 PLAYING 0 09 09 014 -002 1 "))
      assert(reply.contains("ROW 1234 00 0 F 8 H H H H H H\nHINT 1234 00 S........\n"))
      assert(reply.contains("ROW 1234 01 H H H H H H H H H\nHINT 1234 01 .M.......\n"))
      assert(reply.endsWith("END 1234 0000000009\n"))
    }
  }

  it should "hold the current byte stable under TX backpressure" in {
    test(new UartReplySerializer(p)) { dut =>
      initialize(dut, p)
      start(dut, hello = true, sequence = 3)
      dut.io.tx.valid.expect(true.B)
      val first = dut.io.tx.bits.peek().litValue
      dut.clock.step(4)
      dut.io.tx.valid.expect(true.B)
      dut.io.tx.bits.expect(first.U)
      dut.io.tx.ready.poke(true.B)
      dut.clock.step()
      dut.io.tx.ready.poke(false.B)
      dut.io.tx.valid.expect(true.B)
      dut.io.tx.bits.expect('N'.toInt.U)
    }
  }
}
