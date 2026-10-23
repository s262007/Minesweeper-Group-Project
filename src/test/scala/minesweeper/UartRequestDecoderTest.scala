package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class UartRequestDecoderTest extends AnyFlatSpec with ChiselScalatestTester {
  private val p = MsParams()

  private def initialize(dut: UartRequestDecoder): Unit = {
    dut.io.line.valid.poke(false.B)
    dut.io.line.bits.length.poke(0.U)
    dut.io.line.bits.data.foreach(_.poke(0.U))
    dut.io.config.preset.poke(1.U)
    dut.io.config.cols.poke(30.U)
    dut.io.config.rows.poke(24.U)
    dut.io.config.mines.poke(14.U)
    dut.io.out.ready.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def send(dut: UartRequestDecoder, text: String): Unit = {
    require(text.length <= UartLineReceiver.MaxLineBytes)
    dut.io.line.bits.data.foreach(_.poke(0.U))
    text.zipWithIndex.foreach { case (ch, i) => dut.io.line.bits.data(i).poke(ch.toInt.U) }
    dut.io.line.bits.length.poke(text.length.U)
    dut.io.line.valid.poke(true.B)
    dut.io.line.ready.expect(true.B)
    dut.clock.step()
    dut.io.line.valid.poke(false.B)
    var cycles = 0
    while (!dut.io.out.valid.peek().litToBoolean && cycles < 80) {
      dut.clock.step()
      cycles += 1
    }
    assert(dut.io.out.valid.peek().litToBoolean, s"decoder did not finish parsing: $text")
  }

  private def finish(dut: UartRequestDecoder): Unit = {
    dut.io.out.ready.poke(true.B)
    dut.clock.step()
    dut.io.out.ready.poke(false.B)
  }

  "UartRequestDecoder" should "decode HELLO, GET, NEW, REVEAL and FLAG without narrowing early" in {
    test(new UartRequestDecoder(p)) { dut =>
      initialize(dut)

      send(dut, "HELLO 0001")
      dut.io.out.bits.kind.expect(UartRequestKind.Hello)
      dut.io.out.bits.sequence.expect(1.U)
      dut.io.out.bits.sequenceValid.expect(true.B)
      dut.io.out.bits.error.expect(UartDecodeError.Ok)
      finish(dut)

      send(dut, "GET FFFF")
      dut.io.out.bits.kind.expect(UartRequestKind.Get)
      dut.io.out.bits.sequence.expect(0xffff.U)
      dut.io.out.bits.error.expect(UartDecodeError.Ok)
      finish(dut)

      send(dut, "NEW 00AF 0042 3 80")
      dut.io.out.bits.kind.expect(UartRequestKind.NewGame)
      dut.io.out.bits.sequence.expect(0xaf.U)
      dut.io.out.bits.error.expect(UartDecodeError.Ok)
      dut.io.out.bits.command.kind.expect(CmdKind.NewGame)
      dut.io.out.bits.command.seed.expect(0x42.U)
      dut.io.out.bits.command.preset.expect(3.U)
      dut.io.out.bits.command.mines.expect(80.U)
      finish(dut)

      send(dut, "REVEAL 1234 23 29")
      dut.io.out.bits.kind.expect(UartRequestKind.Reveal)
      dut.io.out.bits.sequence.expect(0x1234.U)
      dut.io.out.bits.command.kind.expect(CmdKind.Reveal)
      dut.io.out.bits.command.row.expect(23.U)
      dut.io.out.bits.command.col.expect(29.U)
      dut.io.out.bits.error.expect(UartDecodeError.Ok)
      finish(dut)

      send(dut, "FLAG 0002 0 0")
      dut.io.out.bits.kind.expect(UartRequestKind.Flag)
      dut.io.out.bits.command.kind.expect(CmdKind.Flag)
      dut.io.out.bits.command.row.expect(0.U)
      dut.io.out.bits.command.col.expect(0.U)
      dut.io.out.bits.error.expect(UartDecodeError.Ok)
      finish(dut)
    }
  }

  it should "reject unknown commands, malformed tokens, signs, extra fields and numeric overflow" in {
    test(new UartRequestDecoder(p)) { dut =>
      initialize(dut)
      val invalid = Seq(
        "BOGUS 0001" -> UartDecodeError.BadCommand,
        "get 0001" -> UartDecodeError.BadCommand,
        "GET 0001 " -> UartDecodeError.BadArgument,
        "GET 0001 2" -> UartDecodeError.BadArgument,
        "GET 00g1" -> UartDecodeError.BadArgument,
        "NEW 0001 000g 0 14" -> UartDecodeError.BadArgument,
        "REVEAL 0001 -1 0" -> UartDecodeError.BadArgument,
        "REVEAL 0001 4294967296 0" -> UartDecodeError.BadArgument,
        "REVEAL 0001 0 1e3" -> UartDecodeError.BadArgument,
        "NEW 0001 0001 8 14" -> UartDecodeError.BadArgument,
        "NEW 0001 0001 0 1024" -> UartDecodeError.BadArgument
      )
      invalid.foreach { case (text, expected) =>
        send(dut, text)
        dut.io.out.bits.error.expect(expected)
        finish(dut)
      }
    }
  }

  it should "report representable but inactive coordinates as RANGE" in {
    test(new UartRequestDecoder(p)) { dut =>
      initialize(dut)
      Seq("REVEAL 0001 24 0", "FLAG 0002 0 30").foreach { text =>
        send(dut, text)
        dut.io.out.bits.error.expect(UartDecodeError.Range)
        dut.io.out.bits.sequenceValid.expect(true.B)
        finish(dut)
      }
    }
  }

  it should "keep the parsed result stable while the receiver applies backpressure" in {
    test(new UartRequestDecoder(p)) { dut =>
      initialize(dut)
      send(dut, "REVEAL 00AB 1 2")
      val seq = dut.io.out.bits.sequence.peek().litValue
      val row = dut.io.out.bits.command.row.peek().litValue
      dut.clock.step(4)
      dut.io.out.valid.expect(true.B)
      dut.io.out.bits.sequence.expect(seq.U)
      dut.io.out.bits.command.row.expect(row.U)
      finish(dut)
    }
  }
}
