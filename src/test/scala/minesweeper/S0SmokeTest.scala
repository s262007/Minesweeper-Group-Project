package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class S0SmokeTest extends AnyFlatSpec with ChiselScalatestTester {
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
