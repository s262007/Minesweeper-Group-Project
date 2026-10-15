package minesweeper

import chisel3._
import chisel3.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class S0SmokeTest extends AnyFlatSpec with ChiselScalatestTester {
  "R1 shells" should "emit Verilog at verification sizes and production capacity" in {
    val matrix = Seq((4, 4), (7, 5), (9, 9), (16, 8))
      .map { case (cols, rows) => MsParams.forVerification(cols, rows) } :+ MsParams()
    for (p <- matrix) {
      val modules: Seq[() => RawModule] = Seq(
        () => new MineGen(p), () => new Board(p),
        () => new MinesweeperCore(p), () => new UartGameBridge(p))
      for (make <- modules) {
        val rtl = (new ChiselStage).emitVerilog(make(),
          Array("--target-dir", s"generated/s0/${p.maxCols}x${p.maxRows}"))
        assert(rtl.contains("module "))
      }
    }
    val top = (new ChiselStage).emitVerilog(new FpgaTop(MsParams()),
      Array("--target-dir", "generated/s0/top"))
    assert(top.contains("module FpgaTop"))
    assert(!top.contains("testLoad"))
    assert(!top.contains("io_mines"))
    intercept[IllegalArgumentException] { new ChiselStage().emitVerilog(
      new FpgaTop(MsParams.forVerification(4, 4))) }
  }

  it should "refuse commands and never advertise a completed game or hints" in {
    test(new MinesweeperCore(MsParams.forVerification(4, 4))) { dut =>
      dut.io.cmd.valid.poke(true.B)
      dut.io.cmd.bits.kind.poke(CmdKind.NewGame)
      dut.io.cmd.bits.row.poke(0.U)
      dut.io.cmd.bits.col.poke(0.U)
      dut.io.cmd.bits.preset.poke(0.U)
      dut.io.cmd.bits.mines.poke(1.U)
      dut.io.cmd.bits.seed.poke(1.U)
      dut.io.result.ready.poke(true.B)
      for (_ <- 0 until 4) {
        dut.io.cmd.ready.expect(false.B)
        dut.io.result.valid.expect(false.B)
        dut.io.settled.expect(false.B)
        dut.io.hintsValid.expect(false.B)
        dut.io.revision.expect(0.U)
        dut.io.cells.foreach(_.active.expect(false.B))
        dut.io.hints.foreach { h => h.safe.expect(false.B); h.mine.expect(false.B) }
        dut.clock.step()
      }
    }
  }

  it should "keep the physical serial output idle and status LEDs off" in {
    test(new FpgaTop(MsParams())) { dut =>
      for (rx <- Seq(true, false, true)) {
        dut.io.rx.poke(rx.B)
        dut.clock.step()
        dut.io.tx.expect(true.B)
        dut.io.won.expect(false.B)
        dut.io.lost.expect(false.B)
      }
    }
  }
}
