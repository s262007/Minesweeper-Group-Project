package minesweeper

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class UartBridgeHarness(p: MsParams) extends Module {
  val io = IO(new Bundle {
    val rx = Flipped(Decoupled(UInt(8.W)))
    val rxFramingError = Input(Bool())
    val rxOverflow = Input(Bool())
    val tx = Decoupled(UInt(8.W))
  })
  private val core = Module(new MinesweeperCore(p))
  private val bridge = Module(new UartGameBridge(p))
  bridge.io.core <> core.io
  bridge.io.rx.valid := io.rx.valid
  bridge.io.rx.bits := io.rx.bits
  io.rx.ready := bridge.io.rx.ready
  bridge.io.rxFramingError := io.rxFramingError
  bridge.io.rxOverflow := io.rxOverflow
  io.tx.valid := bridge.io.tx.valid
  io.tx.bits := bridge.io.tx.bits
  bridge.io.tx.ready := io.tx.ready
}

class UartBridgeTest extends AnyFlatSpec with ChiselScalatestTester {
  private val p = MsParams(
    // Keep generated hardware small while retaining an inactive coordinate
    // beyond the 2x2 preset for the RANGE test below.
    maxCols = 3,
    maxRows = 3,
    presets = Vector(GridPreset(0, 2, 2)),
    minMines = 0,
    defaultPresetId = 0,
    defaultMines = 0,
    verification = true
  )

  private def initialize(dut: UartBridgeHarness): Unit = {
    dut.io.rx.valid.poke(false.B)
    dut.io.rx.bits.poke(0.U)
    dut.io.rxFramingError.poke(false.B)
    dut.io.rxOverflow.poke(false.B)
    dut.io.tx.ready.poke(true.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def sendBytes(dut: UartBridgeHarness, text: String): Unit = {
    text.getBytes("US-ASCII").foreach { b =>
      dut.io.rx.bits.poke((b & 0xff).U)
      dut.io.rx.valid.poke(true.B)
      var wait = 0
      while (!dut.io.rx.ready.peek().litToBoolean && wait < 1000) {
        dut.clock.step()
        wait += 1
      }
      assert(wait < 1000, "UART receiver did not accept a byte")
      dut.clock.step()
    }
    dut.io.rx.valid.poke(false.B)
  }
  private def sendLine(dut: UartBridgeHarness, line: String): Unit = sendBytes(dut, line + "\n")

  private def readReply(dut: UartBridgeHarness, limit: Int = 240): String = {
    val result = new StringBuilder
    val currentLine = new StringBuilder
    var clocks = 0
    var finished = false
    while (!finished && clocks < limit) {
      if (dut.io.tx.valid.peek().litToBoolean && dut.io.tx.ready.peek().litToBoolean) {
        val ch = dut.io.tx.bits.peek().litValue.toInt.toChar
        result.append(ch)
        if (ch == '\n') {
          val completed = currentLine.toString
          currentLine.clear()
          finished = completed.startsWith("END ") || completed.startsWith("ERR ")
        } else currentLine.append(ch)
      }
      dut.clock.step()
      clocks += 1
    }
    assert(finished, s"reply did not terminate within $limit clocks; got: $result")
    result.toString
  }

  "UartGameBridge" should "handle requests, retries, errors, backpressure and line recovery" in {
    test(new UartBridgeHarness(p)) { dut =>
      initialize(dut)

      sendLine(dut, "HELLO 0001")
      val hello = readReply(dut)
      assert(hello.contains("INFO 0001 2 1.2.0 03 03 000 V1 1\n"))
      assert(hello.contains("PRESET 0001 0 02 02 000 000\n"))
      assert(hello.endsWith("END 0001 0000000000\n"))

      sendLine(dut, "GET 0002")
      val snapshot = readReply(dut)
      assert(snapshot.startsWith("ACK 0002\nSNAP 0002 0000000000 IDLE 0 02 02 000 0000 0 "))
      assert(snapshot.contains("ROW 0002 00 H H\nHINT 0002 00 ..\n"))
      assert(snapshot.endsWith("END 0002 0000000000\n"))

      val request = "NEW 0010 0001 0 0"
      sendLine(dut, request)
      val first = readReply(dut)
      assert(first.startsWith("ACK 0010\nSNAP 0010 0000000001 IDLE 0 02 02 000 0000 0 "))
      assert(first.endsWith("END 0010 0000000001\n"))

      sendLine(dut, request)
      val replay = readReply(dut)
      assert(replay.startsWith("ACK 0010\nSNAP 0010 0000000001 IDLE"))
      assert(replay.endsWith("END 0010 0000000001\n"))
      sendLine(dut, "NEW 0010 0001 0 1")
      assert(readReply(dut) == "ERR 0010 SEQ\n")

      sendLine(dut, "BOGUS")
      assert(readReply(dut) == "ERR ---- BAD_CMD\n")
      sendLine(dut, "REVEAL 0004 2 0")
      assert(readReply(dut) == "ERR 0004 RANGE\n")
      sendLine(dut, "NEW 0005 0000 0 0")
      assert(readReply(dut) == "ACK 0005\nERR 0005 BAD_SEED\n")

      dut.io.tx.ready.poke(false.B)
      sendLine(dut, "HELLO 0020")
      sendLine(dut, "HELLO 0020")
      dut.io.tx.ready.poke(true.B)
      assert(readReply(dut).endsWith("END 0020 0000000001\n"))
      assert(readReply(dut) == "ERR 0020 BUSY\n")

      dut.io.tx.ready.poke(false.B)
      sendBytes(dut, "GET 00")
      dut.io.rxFramingError.poke(true.B)
      dut.clock.step()
      dut.io.rxFramingError.poke(false.B)
      dut.clock.step()
      sendBytes(dut, "damaged\n")
      dut.io.tx.ready.poke(true.B)
      assert(readReply(dut) == "ERR ---- RX_FRAME\n")

      dut.io.tx.ready.poke(false.B)
      sendBytes(dut, "GET ")
      dut.io.rxOverflow.poke(true.B)
      dut.clock.step()
      dut.io.rxOverflow.poke(false.B)
      dut.clock.step()
      sendBytes(dut, "damaged\n")
      dut.io.tx.ready.poke(true.B)
      assert(readReply(dut) == "ERR ---- RX_OVERFLOW\n")

      dut.io.tx.ready.poke(false.B)
      sendLine(dut, "A" * (UartLineReceiver.MaxLineBytes + 1))
      dut.io.tx.ready.poke(true.B)
      assert(readReply(dut) == "ERR ---- LINE_TOO_LONG\n")
      sendLine(dut, "BOGUS")
      assert(readReply(dut) == "ERR ---- BAD_CMD\n")
    }
  }
}
