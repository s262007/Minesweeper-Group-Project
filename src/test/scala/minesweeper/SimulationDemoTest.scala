package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Simulation-only walkthrough of the integrated reveal engine and current limits. */
class SimulationDemoTest extends AnyFlatSpec with ChiselScalatestTester {
  private val p = MsParams.forVerification(7, 5).copy(
    presets = Vector(GridPreset(0, 4, 4), GridPreset(1, 7, 5)))

  private def command(dut: MinesweeperCore, kind: CmdKind.Type, row: Int = 0,
      col: Int = 0, preset: Int = 0, mines: Int = 1, seed: Int = 1): Unit = {
    dut.io.cmd.bits.kind.poke(kind)
    dut.io.cmd.bits.row.poke(row.U)
    dut.io.cmd.bits.col.poke(col.U)
    dut.io.cmd.bits.preset.poke(preset.U)
    dut.io.cmd.bits.mines.poke(mines.U)
    dut.io.cmd.bits.seed.poke(seed.U)
    dut.io.cmd.valid.poke(true.B)
    dut.io.cmd.ready.expect(true.B)
    dut.clock.step()
    dut.io.cmd.valid.poke(false.B)
    var clocks = 0
    while (!dut.io.result.valid.peek().litToBoolean && clocks < 64) {
      dut.clock.step()
      clocks += 1
    }
    assert(clocks < 64, s"$kind command did not complete")
    dut.io.result.bits.status.expect(CommandStatus.Ok)
    dut.io.result.ready.poke(true.B)
    dut.clock.step()
    dut.io.result.ready.poke(false.B)
  }

  "Simulation walkthrough" should "show the implemented game commands and current limits" in {
    test(new MinesweeperCore(p)) { dut =>
      dut.io.cmd.valid.poke(false.B)
      dut.io.result.ready.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      command(dut, CmdKind.NewGame, preset = 1, mines = 14, seed = 1)
      println("DEMO: new 7x5 game, fourteen mines, deterministic seed 1")

      command(dut, CmdKind.Flag, row = 3, col = 3)
      dut.io.cells(24).kind.expect(PublicCellKind.Flagged)
      command(dut, CmdKind.Flag, row = 3, col = 3)
      dut.io.cells(24).kind.expect(PublicCellKind.Hidden)
      println("DEMO: flag a cell, then toggle the flag off")

      command(dut, CmdKind.Reveal, row = 0, col = 0)
      dut.io.cells(0).kind.expect(PublicCellKind.Number)
      dut.io.cells(0).adj.expect(0.U)
      dut.io.cells(1).kind.expect(PublicCellKind.Number)
      dut.io.cells(1).adj.expect(2.U)
      println("DEMO: zero click expands through its safe region and stops at numbered cells")

      command(dut, CmdKind.Reveal, row = 1, col = 2)
      dut.io.state.expect(GameState.Lost)
      dut.io.cells(9).kind.expect(PublicCellKind.Mine)
      println("DEMO: mine click reports loss and exposes mines")

      command(dut, CmdKind.NewGame, preset = 1, mines = 2, seed = 7)
      dut.io.config.cols.expect(7.U)
      dut.io.config.rows.expect(5.U)
      dut.io.config.mines.expect(2.U)
      dut.io.state.expect(GameState.Idle)
      dut.io.cells.foreach(_.kind.expect(PublicCellKind.Hidden))
      println("DEMO: restart at a different preset and mine count within 7x5 verification capacity")

      command(dut, CmdKind.NewGame, mines = 1, seed = 1)
      command(dut, CmdKind.Reveal, row = 0, col = 0)
      dut.io.state.expect(GameState.Won)
      dut.io.cells(1).kind.expect(PublicCellKind.Number)
      println("DEMO: one zero click expands to the safe cells and wins the 4x4 game")

      command(dut, CmdKind.NewGame, preset = 1, mines = 2, seed = 9)
      dut.io.state.expect(GameState.Idle)
      dut.io.revision.expect(9.U)
      println("DEMO: new game restarts from the won state")
    }
  }
}
