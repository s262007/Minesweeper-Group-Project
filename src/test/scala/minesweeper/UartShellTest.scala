package minesweeper

import chisel3._
import circt.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class UartShellTest extends AnyFlatSpec with ChiselScalatestTester {
  private val clocks = Seq(100000000L, 50000000L, 48000000L)

  "UART shells" should "emit Verilog for several board clocks" in {
    for (hz <- clocks) {
      val cfg = UartConfig(clockHz = hz)
      assert(ChiselStage.emitSystemVerilog(new UartRx(cfg)).contains("module UartRx"))
      assert(ChiselStage.emitSystemVerilog(new UartTx(cfg)).contains("module UartTx"))
    }
  }

  "UartRx shell" should "deliver no bytes and report no errors" in {
    test(new UartRx(UartConfig(clockHz = 100000000L))) { dut =>
      dut.io.out.ready.poke(true.B)
      for (rx <- Seq(true, false, false, true, false, true)) {
        dut.io.rx.poke(rx.B)
        dut.clock.step()
        dut.io.out.valid.expect(false.B)
        dut.io.framingError.expect(false.B)
        dut.io.overflow.expect(false.B)
      }
    }
  }

  "UartTx shell" should "keep the line idle-high and accept nothing" in {
    test(new UartTx(UartConfig(clockHz = 100000000L))) { dut =>
      dut.io.in.valid.poke(true.B)
      dut.io.in.bits.poke('G'.toInt.U)
      for (_ <- 0 until 4) {
        dut.io.tx.expect(true.B)
        dut.io.in.ready.expect(false.B)
        dut.io.busy.expect(false.B)
        dut.clock.step()
      }
    }
  }
}
