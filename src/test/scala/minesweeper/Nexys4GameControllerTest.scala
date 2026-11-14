package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class Nexys4GameControllerTest extends AnyFlatSpec with ChiselScalatestTester {
  private val p = MsParams.forVerification(3, 3)

  "Nexys4GameController" should "preserve the row and column in row-local autoplay selection" in {
    test(new Nexys4GameController(p)) { dut =>
      dut.io.restartEvent.poke(false.B)
      dut.io.requestedPreset.poke(0.U)
      dut.io.flagEvent.poke(false.B)
      dut.io.revealEvent.poke(false.B)
      dut.io.autoplayEvent.poke(false.B)
      dut.io.selectedRow.poke(0.U)
      dut.io.selectedCol.poke(0.U)
      dut.io.coreCmd.ready.poke(true.B)
      dut.io.coreResult.valid.poke(false.B)
      dut.io.coreResult.bits.status.poke(CommandStatus.Ok)
      dut.io.coreResult.bits.changed.poke(true.B)
      dut.io.coreResult.bits.revision.poke(0.U)
      dut.io.coreConfig.preset.poke(0.U)
      dut.io.coreConfig.cols.poke(3.U)
      dut.io.coreConfig.rows.poke(3.U)
      dut.io.coreConfig.mines.poke(0.U)
      dut.io.coreState.poke(GameState.Playing)
      dut.io.coreSettled.poke(true.B)
      dut.io.coreHintsValid.poke(true.B)

      val targetRow = 1
      val targetCol = 1
      val targetIndex = targetRow * p.maxCols + targetCol
      for (idx <- 0 until p.cells) {
        dut.io.coreCells(idx).active.poke(true.B)
        dut.io.coreCells(idx).kind.poke(PublicCellKind.Hidden)
        dut.io.coreCells(idx).adj.poke(0.U)
        dut.io.coreHints(idx).safe.poke((idx == targetIndex).B)
        dut.io.coreHints(idx).mine.poke(false.B)
      }

      dut.clock.step()
      dut.io.autoplayEvent.poke(true.B)
      dut.clock.step()
      dut.io.autoplayEvent.poke(false.B)

      var waited = 0
      while (!dut.io.coreCmd.valid.peek().litToBoolean && waited < p.cells + 4) {
        dut.clock.step()
        waited += 1
      }

      assert(waited < p.cells + 4, "autoplay did not produce a reveal command")
      dut.io.coreCmd.bits.kind.expect(CmdKind.Reveal)
      dut.io.coreCmd.bits.row.expect(targetRow.U)
      dut.io.coreCmd.bits.col.expect(targetCol.U)
    }
  }
}
