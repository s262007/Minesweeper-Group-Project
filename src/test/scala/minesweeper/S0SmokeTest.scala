package minesweeper

import chisel3._
import circt.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class S0SmokeTest extends AnyFlatSpec with ChiselScalatestTester {
  private def emitTo(module: => RawModule, directory: String): String = {
    val rtl = ChiselStage.emitSystemVerilog(module)
    val name = "(?m)^module\\s+(\\w+)".r.findFirstMatchIn(rtl).get.group(1)
    val path = java.nio.file.Paths.get(directory)
    java.nio.file.Files.createDirectories(path)
    java.nio.file.Files.write(path.resolve(s"$name.sv"), rtl.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    rtl
  }

  private def topPortHeader(rtl: String, moduleName: String): String = {
    val start = rtl.indexOf(s"module $moduleName(")
    assert(start >= 0, s"missing module declaration for $moduleName")
    val end = rtl.indexOf(");", start)
    assert(end >= 0, s"missing end of port declaration for $moduleName")
    rtl.substring(start, end + 2)
  }

  "R1 shells" should "emit Verilog at verification sizes and production capacity" in {
    val matrix = Seq((4, 4), (7, 5), (9, 9), (16, 8))
      .map { case (cols, rows) => MsParams.forVerification(cols, rows) } :+ MsParams()
    for (p <- matrix) {
      val modules: Seq[() => RawModule] = Seq(
        () => new MineGen(p), () => new Board(p),
        () => new MinesweeperCore(p), () => new UartGameBridge(p))
      for (make <- modules) {
        val rtl = emitTo(make(), s"generated/s0/${p.maxCols}x${p.maxRows}")
        assert(rtl.contains("module "))
      }
    }
    val top = emitTo(new FpgaTop(MsParams()), "generated/s0/top")
    assert(top.contains("module FpgaTop"))
    val ports = topPortHeader(top, "FpgaTop")
    assert(!ports.contains("testLoad"))
    assert(!ports.contains("io_mines"))
    Seq("io_rx", "io_tx", "io_won", "io_lost").foreach { port =>
      assert(ports.contains(port), s"FpgaTop is missing logical pin $port")
    }
    intercept[IllegalArgumentException] { ChiselStage.emitSystemVerilog(
      new FpgaTop(MsParams.forVerification(4, 4))) }
  }

  it should "provide a stable empty default game without revealing truth or hints" in {
    test(new MinesweeperCore(MsParams.forVerification(4, 4))) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.cmd.valid.poke(false.B)
      dut.io.result.ready.poke(false.B)
      dut.io.cmd.ready.expect(true.B)
      dut.io.result.valid.expect(false.B)
      dut.io.settled.expect(true.B)
      dut.io.state.expect(GameState.Idle)
      dut.io.hintsValid.expect(false.B)
      dut.io.revision.expect(0.U)
      dut.io.cells.foreach { cell =>
        cell.active.expect(true.B)
        cell.kind.expect(PublicCellKind.Hidden)
        cell.adj.expect(0.U)
      }
      dut.io.hints.foreach { h => h.safe.expect(false.B); h.mine.expect(false.B) }
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
