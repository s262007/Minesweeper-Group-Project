package minesweeper

import chisel3._
import chisel3.util.Decoupled
import circt.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable.ArrayBuffer

/** TX wired straight into RX: what one board sees when its pins are looped back. */
class UartLoopback(cfg: UartConfig) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(8.W)))
    val out = Decoupled(UInt(8.W))
    val framingError = Output(Bool())
    val overflow = Output(Bool())
  })
  val tx = Module(new UartTx(cfg))
  val rx = Module(new UartRx(cfg))
  tx.io.in <> io.in
  rx.io.rx := tx.io.tx
  io.out <> rx.io.out
  io.framingError := rx.io.framingError
  io.overflow := rx.io.overflow
}

class UartTest extends AnyFlatSpec with ChiselScalatestTester {
  // 16x sample tick every 4 clocks, so one bit is 64 clocks: short enough to simulate quickly.
  private val cfg = UartConfig(clockHz = 64000L, baud = 1000)
  private val bit = cfg.sampleDivisor * cfg.oversample
  private val board = UartConfig(clockHz = 100000000L)

  private def frame(byte: Int, stop: Boolean = true): Seq[Boolean] =
    false +: (0 until 8).map(i => ((byte >> i) & 1) == 1) :+ stop

  /** Records RX output events cycle by cycle while the serial line is driven. */
  private final class RxLine(dut: UartRx) {
    val bytes = ArrayBuffer.empty[Int]
    /** Everything delivered, in order: "x41" for a byte, "F" framing error, "O" overflow. */
    val events = ArrayBuffer.empty[String]
    var framingErrors = 0
    var overflows = 0

    def step(n: Int): Unit = for (_ <- 0 until n) {
      if (dut.io.out.valid.peek().litToBoolean && dut.io.out.ready.peek().litToBoolean) {
        val b = dut.io.out.bits.peek().litValue.toInt
        bytes += b
        events += f"x$b%02x"
      }
      if (dut.io.framingError.peek().litToBoolean) { framingErrors += 1; events += "F" }
      if (dut.io.overflow.peek().litToBoolean) { overflows += 1; events += "O" }
      dut.clock.step()
    }

    def idle(bits: Int): Unit = { dut.io.rx.poke(true.B); step(bits * bit) }

    /** One frame with a whole number of clocks per bit. */
    def send(byte: Int, bitCycles: Int = bit, stop: Boolean = true): Unit = {
      for (b <- frame(byte, stop)) { dut.io.rx.poke(b.B); step(bitCycles) }
      dut.io.rx.poke(true.B)
    }

    /** One frame at an exact (fractional) bit time, as a PC's UART would produce it. */
    def sendExact(byte: Int, cyclesPerBit: Double): Unit = {
      var clocks = 0L
      for ((b, k) <- frame(byte).zipWithIndex) {
        val edge = math.round((k + 1) * cyclesPerBit)
        dut.io.rx.poke(b.B)
        step((edge - clocks).toInt)
        clocks = edge
      }
      dut.io.rx.poke(true.B)
    }

    /** Accept everything queued, including error markers behind the last byte. */
    def drain(): Unit = {
      dut.io.out.ready.poke(true.B)
      while (dut.io.out.valid.peek().litToBoolean) step(1)
      step(4)
    }
  }

  private def rxTest(config: UartConfig = cfg)(body: (UartRx, RxLine) => Unit): Unit =
    test(new UartRx(config)) { dut =>
      dut.clock.setTimeout(0) // a serial line legitimately holds one level for many clocks
      dut.io.rx.poke(true.B)
      dut.io.out.ready.poke(true.B)
      val line = new RxLine(dut)
      line.idle(2)
      body(dut, line)
    }

  "UART" should "emit Verilog for several board clocks" in {
    for (hz <- Seq(100000000L, 50000000L, 48000000L)) {
      val c = UartConfig(clockHz = hz)
      assert(ChiselStage.emitSystemVerilog(new UartRx(c)).contains("module UartRx"))
      assert(ChiselStage.emitSystemVerilog(new UartTx(c)).contains("module UartTx"))
    }
  }

  "UartTx" should "idle high and accept a byte" in {
    test(new UartTx(cfg)) { dut =>
      for (_ <- 0 until 3 * bit) {
        dut.io.tx.expect(true.B)
        dut.io.busy.expect(false.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
      }
    }
  }

  it should "send start, eight data bits LSB first and stop, each exactly one bit long" in {
    test(new UartTx(cfg)) { dut =>
      for (byte <- Seq(0x00, 0xff, 0x55, 0xa3, '\n'.toInt, 'H'.toInt)) {
        dut.io.in.bits.poke(byte.U)
        dut.io.in.valid.poke(true.B)
        dut.io.in.ready.expect(true.B)
        dut.clock.step()
        dut.io.in.valid.poke(false.B)
        val wire = for (_ <- 0 until 10 * bit) yield {
          dut.io.busy.expect(true.B)
          dut.io.in.ready.expect(false.B)
          val level = dut.io.tx.peek().litToBoolean
          dut.clock.step()
          level
        }
        assert(wire == frame(byte).flatMap(b => Seq.fill(bit)(b)), f"byte 0x$byte%02x")
        dut.io.tx.expect(true.B)
        dut.io.busy.expect(false.B)
        dut.io.in.ready.expect(true.B)
      }
    }
  }

  it should "hold the next byte until the current frame is finished" in {
    test(new UartTx(cfg)) { dut =>
      dut.io.in.bits.poke(0x41.U)
      dut.io.in.valid.poke(true.B)
      dut.clock.step()
      dut.io.in.bits.poke(0x42.U) // presented immediately, accepted only after the stop bit
      var waited = 0
      while (!dut.io.in.ready.peek().litToBoolean) { dut.io.tx.peek(); dut.clock.step(); waited += 1 }
      assert(waited == 10 * bit)
      dut.clock.step()
      dut.io.in.valid.poke(false.B)
      val wire = for (_ <- 0 until 10 * bit) yield { val l = dut.io.tx.peek().litToBoolean; dut.clock.step(); l }
      assert(wire == frame(0x42).flatMap(b => Seq.fill(bit)(b)))
    }
  }

  "UartRx" should "receive every byte value" in {
    rxTest() { (_, line) =>
      for (b <- 0 until 256) line.send(b)
      line.idle(1)
      assert(line.bytes == (0 until 256))
      assert(line.framingErrors == 0 && line.overflows == 0)
    }
  }

  it should "tolerate a sender 3 percent too slow or too fast" in {
    val data = Seq(0x00, 0xff, 0x55, 0xaa, 0x0f, 0xf0, 0x81)
    for (cycles <- Seq(math.round(bit * 0.97).toInt, math.round(bit * 1.03).toInt)) {
      rxTest() { (_, line) =>
        data.foreach(line.send(_, bitCycles = cycles))
        line.idle(1)
        assert(line.bytes == data, s"$cycles clocks per bit")
        assert(line.framingErrors == 0)
      }
    }
  }

  it should "flag a missing stop bit once, drop that byte and recover" in {
    rxTest() { (dut, line) =>
      line.send(0x41, stop = false)
      dut.io.rx.poke(false.B) // the line stays low (a break) before it recovers
      line.step(3 * bit)
      line.idle(2)
      line.send(0x42)
      line.idle(1)
      assert(line.framingErrors == 1)
      assert(line.bytes == Seq(0x42))
    }
  }

  it should "ignore a glitch shorter than half a bit" in {
    rxTest() { (dut, line) =>
      dut.io.rx.poke(false.B)
      line.step(bit / 4)
      line.idle(3)
      line.send(0x5a)
      line.idle(1)
      assert(line.bytes == Seq(0x5a))
      assert(line.framingErrors == 0)
    }
  }

  it should "buffer a full FIFO under backpressure and report the byte it drops" in {
    rxTest() { (dut, line) =>
      dut.io.out.ready.poke(false.B)
      val sent = (0 until cfg.rxFifoDepth + 1).map(i => (i * 37 + 11) & 0xff)
      sent.foreach(line.send(_))
      line.idle(1)
      dut.io.out.valid.expect(true.B)
      val held = dut.io.out.bits.peek()
      line.step(5)
      dut.io.out.bits.expect(held) // stable while not accepted
      assert(line.overflows == 0) // reported in order, after the bytes queued before it
      line.drain()
      assert(line.bytes == sent.take(cfg.rxFifoDepth))
      assert(line.overflows == 1 && line.events.last == "O")
    }
  }

  it should "keep a framing error behind the bytes queued before it" in {
    rxTest() { (dut, line) =>
      dut.io.out.ready.poke(false.B) // the bridge is busy, so bytes wait in the FIFO
      line.send('A')
      line.send('B')
      line.send('?', stop = false)
      line.idle(2)
      line.send('C')
      line.idle(1)
      assert(line.events.isEmpty)
      line.drain()
      assert(line.events == Seq("x41", "x42", "F", "x43"))
    }
  }

  it should "receive a 115200-baud sender on the 100 MHz board clock" in {
    rxTest(board) { (_, line) =>
      val text = "GET 0001\r\n".map(_.toInt)
      text.foreach(line.sendExact(_, cyclesPerBit = 100e6 / 115200))
      line.step(board.sampleDivisor * 16)
      assert(line.bytes == text)
      assert(line.framingErrors == 0)
    }
  }

  "UartTx and UartRx" should "carry a request line through a loopback" in {
    for (config <- Seq(cfg, board)) {
      test(new UartLoopback(config)) { dut =>
        dut.clock.setTimeout(0)
        val text = (if (config == board) "OK\n" else "HELLO 0001\n").map(_.toInt)
        val got = ArrayBuffer.empty[Int]
        var framing = false
        dut.io.out.ready.poke(true.B)
        var pending = text.toList
        var cycles = 0
        val limit = (text.length + 2) * 12 * config.sampleDivisor * 16
        while (got.length < text.length && cycles < limit) {
          dut.io.in.valid.poke(pending.nonEmpty.B)
          dut.io.in.bits.poke(pending.headOption.getOrElse(0).U)
          if (pending.nonEmpty && dut.io.in.ready.peek().litToBoolean) pending = pending.tail
          if (dut.io.out.valid.peek().litToBoolean) got += dut.io.out.bits.peek().litValue.toInt
          framing ||= dut.io.framingError.peek().litToBoolean
          dut.clock.step()
          cycles += 1
        }
        assert(got == text, s"${config.clockHz} Hz")
        assert(!framing)
      }
    }
  }
}

/** UartRx feeding the bridge's first stage, the line receiver, as in the FPGA top. */
class UartRxLines(cfg: UartConfig) extends Module {
  val io = IO(new Bundle {
    val rx = Input(Bool())
    val line = Decoupled(new UartLine)
  })
  private val receiver = Module(new UartRx(cfg))
  private val lines = Module(new UartLineReceiver)
  receiver.io.rx := io.rx
  lines.io.rx <> receiver.io.out
  lines.io.rxFramingError := receiver.io.framingError
  lines.io.rxOverflow := receiver.io.overflow
  io.line <> lines.io.line
}

class UartRxLinesTest extends AnyFlatSpec with ChiselScalatestTester {
  private val cfg = UartConfig(clockHz = 16000L, baud = 1000) // 16 clocks per bit
  private val bit = cfg.sampleDivisor * cfg.oversample

  private def frame(byte: Int, stop: Boolean = true): Seq[Boolean] =
    false +: (0 until 8).map(i => ((byte >> i) & 1) == 1) :+ stop

  "UartRx and the bridge's line receiver" should
    "drop only the damaged request when it arrives behind requests still waiting" in {
    test(new UartRxLines(cfg)) { dut =>
      dut.clock.setTimeout(0)
      val got = ArrayBuffer.empty[String]
      def step(n: Int): Unit = for (_ <- 0 until n) {
        if (dut.io.line.valid.peek().litToBoolean && dut.io.line.ready.peek().litToBoolean) {
          val n = dut.io.line.bits.length.peek().litValue.toInt
          got += (0 until n).map(i => dut.io.line.bits.data(i).peek().litValue.toChar).mkString
        }
        dut.clock.step()
      }
      def send(text: String): Unit = text.foreach { c =>
        for (level <- frame(c.toInt)) { dut.io.rx.poke(level.B); step(bit) }
      }
      dut.io.rx.poke(true.B)
      dut.io.line.ready.poke(false.B) // the bridge is still busy with an earlier request
      step(4 * bit)
      send("GET 0001\n")
      send("GET 0002\r\n")
      send("GET 00")
      for (level <- frame('3'.toInt, stop = false)) { dut.io.rx.poke(level.B); step(bit) }
      dut.io.rx.poke(true.B)
      step(2 * bit)
      send("3\n")
      send("GET 0004\n")
      step(2 * bit)
      assert(got.isEmpty)
      dut.io.line.ready.poke(true.B)
      step(400)
      assert(got == Seq("GET 0001", "GET 0002", "GET 0004"))
    }
  }
}
