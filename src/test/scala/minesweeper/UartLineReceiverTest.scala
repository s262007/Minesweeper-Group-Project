package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class UartLineReceiverTest extends AnyFlatSpec with ChiselScalatestTester {
  private def initialize(dut: UartLineReceiver): Unit = {
    dut.io.rx.valid.poke(false.B)
    dut.io.rx.bits.poke(0.U)
    dut.io.rxFramingError.poke(false.B)
    dut.io.rxOverflow.poke(false.B)
    dut.io.line.ready.poke(true.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def send(dut: UartLineReceiver, byte: Int): Unit = {
    dut.io.rx.bits.poke(byte.U)
    dut.io.rx.valid.poke(true.B)
    dut.io.rx.ready.expect(true.B)
    dut.clock.step()
    dut.io.rx.valid.poke(false.B)
  }

  private def expectLine(dut: UartLineReceiver, text: String): Unit = {
    dut.io.line.valid.expect(true.B)
    dut.io.line.bits.length.expect(text.length.U)
    text.indices.foreach { i =>
      dut.io.line.bits.data(i).expect(text.charAt(i).toInt.U)
    }
    dut.io.line.ready.poke(true.B)
    dut.clock.step()
    dut.io.line.valid.expect(false.B)
  }

  private def sendText(dut: UartLineReceiver, text: String): Unit =
    text.getBytes("US-ASCII").foreach(b => send(dut, b & 0xff))

  "UartLineReceiver" should "deliver LF and CRLF lines without their terminators" in {
    test(new UartLineReceiver) { dut =>
      initialize(dut)
      sendText(dut, "GET 0001\n")
      expectLine(dut, "GET 0001")
      sendText(dut, "HELLO 00AF\r\n")
      expectLine(dut, "HELLO 00AF")
    }
  }

  it should "accept exactly 64 wire bytes and hold a line under backpressure" in {
    test(new UartLineReceiver) { dut =>
      initialize(dut)
      sendText(dut, "A" * UartLineReceiver.MaxLineBytes)
      send(dut, '\n')
      dut.io.line.valid.expect(true.B)
      dut.io.line.bits.length.expect(UartLineReceiver.MaxLineBytes.U)

      dut.io.line.ready.poke(false.B)
      val held = dut.io.line.bits.data.map(_.peek().litValue)
      dut.io.rx.ready.expect(false.B)
      dut.clock.step(3)
      dut.io.line.valid.expect(true.B)
      dut.io.line.bits.length.expect(UartLineReceiver.MaxLineBytes.U)
      assert(dut.io.line.bits.data.map(_.peek().litValue) == held)

      dut.io.line.ready.poke(true.B)
      dut.clock.step()
      dut.io.line.valid.expect(false.B)
    }
  }

  it should "discard an overlong line through LF and resume at the next line" in {
    test(new UartLineReceiver) { dut =>
      initialize(dut)
      sendText(dut, "A" * UartLineReceiver.MaxLineBytes)
      send(dut, 'B')
      dut.io.overflowError.expect(true.B)
      dut.io.lineTooLongError.expect(true.B)
      dut.io.line.valid.expect(false.B)
      sendText(dut, "ignored remainder\nGET\n")
      expectLine(dut, "GET")
    }
  }

  it should "discard a framing-error or RX-overflow line through LF" in {
    test(new UartLineReceiver) { dut =>
      initialize(dut)
      sendText(dut, "GET 00")
      dut.io.rxFramingError.poke(true.B)
      dut.clock.step()
      dut.io.rxFramingError.poke(false.B)
      dut.io.framingError.expect(true.B)
      dut.clock.step()
      dut.io.framingError.expect(false.B)
      sendText(dut, "bad tail\nGET 0002\n")
      expectLine(dut, "GET 0002")

      sendText(dut, "NEW ")
      dut.io.rxOverflow.poke(true.B)
      dut.clock.step()
      dut.io.rxOverflow.poke(false.B)
      dut.io.overflowError.expect(true.B)
      dut.clock.step()
      dut.io.overflowError.expect(false.B)
      sendText(dut, "discarded\nGET 0003\n")
      expectLine(dut, "GET 0003")
    }
  }
}
