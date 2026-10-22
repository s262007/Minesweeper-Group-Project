package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class CoreTest extends AnyFlatSpec with ChiselScalatestTester {
  private val p = MsParams.forVerification(7, 5).copy(
    presets = Vector(GridPreset(0, 4, 4), GridPreset(1, 7, 5), GridPreset(2, 6, 5)))

  private def initialize(dut: MinesweeperCore): Unit = {
    dut.io.cmd.valid.poke(false.B)
    dut.io.cmd.bits.kind.poke(CmdKind.NewGame)
    dut.io.cmd.bits.row.poke(0.U)
    dut.io.cmd.bits.col.poke(0.U)
    dut.io.cmd.bits.preset.poke(0.U)
    dut.io.cmd.bits.mines.poke(1.U)
    dut.io.cmd.bits.seed.poke(1.U)
    dut.io.result.ready.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def send(dut: MinesweeperCore, kind: CmdKind.Type, row: Int = 0, col: Int = 0,
                   preset: Int = 0, mines: Int = 1, seed: Int = 1): Unit = {
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
  }

  private def awaitResult(dut: MinesweeperCore, limit: Int = 100): Unit = {
    var cycles = 0
    while (!dut.io.result.valid.peek().litToBoolean && cycles < limit) {
      dut.clock.step()
      cycles += 1
    }
    assert(cycles < limit, s"command did not complete within $limit cycles")
  }

  "MinesweeperCore" should "reset to the documented default, stable empty snapshot" in {
    test(new MinesweeperCore(MsParams())) { dut =>
      initialize(dut)
      dut.io.state.expect(GameState.Idle)
      dut.io.busy.expect(false.B)
      dut.io.settled.expect(true.B)
      dut.io.config.preset.expect(1.U)
      dut.io.config.cols.expect(9.U)
      dut.io.config.rows.expect(9.U)
      dut.io.config.mines.expect(14.U)
      dut.io.revision.expect(0.U)
      dut.io.minesLeft.expect(14.S)
      dut.io.exploded.valid.expect(false.B)
      dut.io.hintsValid.expect(false.B)
      dut.io.cells.foreach { cell =>
        cell.kind.expect(PublicCellKind.Hidden)
        cell.adj.expect(0.U)
      }
      assert(dut.io.cells.count(_.active.peek().litToBoolean) == 81)
    }
  }

  it should "accept a validated NewGame and hold its single result under backpressure" in {
    test(new MinesweeperCore(p)) { dut =>
      initialize(dut)
      send(dut, CmdKind.NewGame, preset = 0, mines = 2, seed = 0xace1)
      awaitResult(dut)
      dut.io.result.bits.status.expect(CommandStatus.Ok)
      dut.io.result.bits.changed.expect(true.B)
      dut.io.result.bits.revision.expect(1.U)
      dut.io.busy.expect(false.B)
      dut.io.settled.expect(true.B)
      dut.io.cmd.ready.expect(false.B)
      dut.io.config.preset.expect(0.U)
      dut.io.config.cols.expect(4.U)
      dut.io.config.rows.expect(4.U)
      dut.io.config.mines.expect(2.U)
      dut.io.revision.expect(1.U)
      dut.io.generationCycles.expect(0.U)
      dut.io.revealCycles.expect(0.U)
      dut.io.solverCycles.expect(0.U)
      dut.clock.step(3)
      dut.io.result.valid.expect(true.B)
      dut.io.result.bits.status.expect(CommandStatus.Ok)
      dut.io.result.bits.revision.expect(1.U)
      dut.io.config.preset.expect(0.U)
      dut.io.cmd.ready.expect(false.B)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.valid.expect(false.B)
      dut.io.cmd.ready.expect(true.B)
    }
  }

  it should "reject invalid settings and coordinates without mutating the snapshot" in {
    test(new MinesweeperCore(p)) { dut =>
      initialize(dut)
      val invalid = Seq(
        (3, 1, 1, CommandStatus.BadPreset),
        (0, 17, 1, CommandStatus.BadMines),
        (0, 1, 0, CommandStatus.BadSeed)
      )
      for ((preset, mines, seed, status) <- invalid) {
        send(dut, CmdKind.NewGame, preset = preset, mines = mines, seed = seed)
        awaitResult(dut)
        dut.io.result.bits.status.expect(status)
        dut.io.result.bits.changed.expect(false.B)
        dut.io.config.preset.expect(0.U)
        dut.io.config.cols.expect(4.U)
        dut.io.config.rows.expect(4.U)
        dut.io.revision.expect(0.U)
        dut.io.result.ready.poke(true.B)
        dut.clock.step()
        dut.io.result.ready.poke(false.B)
      }

      send(dut, CmdKind.Flag, row = 4, col = 0)
      awaitResult(dut)
      dut.io.result.bits.status.expect(CommandStatus.Range)
      dut.io.result.bits.changed.expect(false.B)
      dut.io.revision.expect(0.U)
    }
  }

  it should "allow over-flagging and report signed mines left" in {
    test(new MinesweeperCore(p)) { dut =>
      initialize(dut)
      send(dut, CmdKind.NewGame, mines = 1)
      awaitResult(dut)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      for (row <- 0 until 4; col <- 0 until 4) {
        send(dut, CmdKind.Flag, row = row, col = col)
        awaitResult(dut)
        dut.io.result.bits.status.expect(CommandStatus.Ok)
        dut.io.result.bits.changed.expect(true.B)
        dut.io.result.ready.poke(true.B)
        dut.clock.step()
        dut.io.result.ready.poke(false.B)
      }
      dut.io.minesLeft.expect((-15).S)
      dut.io.revision.expect(17.U)
      assert(dut.io.cells.count(_.kind.peek().litValue == PublicCellKind.Flagged.litValue) == 16)
    }
  }

  it should "generate mines on the first eligible reveal and preserve earlier flags" in {
    test(new MinesweeperCore(p)) { dut =>
      initialize(dut)
      send(dut, CmdKind.NewGame, mines = 1, seed = 1)
      awaitResult(dut)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      send(dut, CmdKind.Flag, row = 3, col = 3)
      awaitResult(dut)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      send(dut, CmdKind.Reveal, row = 3, col = 3)
      awaitResult(dut)
      dut.io.result.bits.changed.expect(false.B)
      dut.io.revision.expect(2.U)
      dut.io.generationCycles.expect(0.U)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      send(dut, CmdKind.Reveal, row = 0, col = 0)
      dut.io.busy.expect(true.B)
      dut.io.settled.expect(false.B)
      dut.io.state.expect(GameState.Generating)
      dut.io.cmd.ready.expect(false.B)
      awaitResult(dut, limit = 64)
      dut.io.result.bits.status.expect(CommandStatus.Ok)
      dut.io.result.bits.changed.expect(true.B)
      dut.io.state.expect(GameState.Won)
      dut.io.config.cols.expect(4.U)
      dut.io.config.rows.expect(4.U)
      dut.io.revision.expect(3.U)
      dut.io.generationCycles.expect(2.U)
      dut.io.cells(0).kind.expect(PublicCellKind.Number)
      dut.io.cells(0).adj.expect(0.U)
      // The deterministic first mine is at (3,3); public output keeps it hidden.
      dut.io.cells(24).kind.expect(PublicCellKind.Flagged)
      dut.io.cells(24).adj.expect(0.U)
      dut.io.hintsValid.expect(false.B)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      // Repeating the click is a no-op and retains the previous work metric.
      send(dut, CmdKind.Flag, row = 0, col = 0)
      awaitResult(dut)
      dut.io.result.bits.changed.expect(false.B)
      dut.io.generationCycles.expect(2.U)
      dut.io.revision.expect(3.U)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      send(dut, CmdKind.Reveal, row = 0, col = 0)
      awaitResult(dut)
      dut.io.result.bits.changed.expect(false.B)
      dut.io.generationCycles.expect(2.U)
      dut.io.revision.expect(3.U)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      // NewGame clears old flags, reveals and generation metadata atomically.
      send(dut, CmdKind.NewGame, preset = 1, mines = 2, seed = 7)
      awaitResult(dut)
      dut.io.result.bits.changed.expect(true.B)
      dut.io.config.preset.expect(1.U)
      dut.io.config.cols.expect(7.U)
      dut.io.config.rows.expect(5.U)
      dut.io.revision.expect(4.U)
      dut.io.generationCycles.expect(0.U)
      dut.io.minesLeft.expect(2.S)
      dut.io.cells.foreach(_.kind.expect(PublicCellKind.Hidden))
    }
  }

  it should "hide mine truth until a losing reveal, then report the exploded cell" in {
    test(new MinesweeperCore(p)) { dut =>
      initialize(dut)
      send(dut, CmdKind.NewGame, preset = 1, mines = 14, seed = 1)
      awaitResult(dut)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      send(dut, CmdKind.Reveal, row = 0, col = 0)
      awaitResult(dut, limit = 64)
      dut.io.state.expect(GameState.Playing)
      dut.io.cells(9).kind.expect(PublicCellKind.Hidden)
      dut.io.cells(9).adj.expect(0.U)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      send(dut, CmdKind.Reveal, row = 1, col = 2)
      awaitResult(dut)
      dut.io.result.bits.status.expect(CommandStatus.Ok)
      dut.io.result.bits.changed.expect(true.B)
      dut.io.state.expect(GameState.Lost)
      dut.io.exploded.valid.expect(true.B)
      dut.io.exploded.bits.expect(9.U)
      dut.io.cells(9).kind.expect(PublicCellKind.Mine)
      dut.io.cells(9).adj.expect(0.U)
      dut.io.hintsValid.expect(false.B)
      dut.io.revision.expect(3.U)
    }
  }

  it should "win after a flood-fill reveal and keep terminal commands as no-ops" in {
    test(new MinesweeperCore(p)) { dut =>
      initialize(dut)
      send(dut, CmdKind.NewGame, mines = 1, seed = 1)
      awaitResult(dut)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      send(dut, CmdKind.Reveal, row = 0, col = 0)
      awaitResult(dut, limit = 32)
      dut.io.result.bits.status.expect(CommandStatus.Ok)
      dut.io.result.bits.changed.expect(true.B)
      dut.io.state.expect(GameState.Won)
      dut.io.cells(0).kind.expect(PublicCellKind.Number)
      dut.io.cells(1).kind.expect(PublicCellKind.Number)
      dut.io.cells(24).kind.expect(PublicCellKind.Hidden)
      assert(dut.io.revealLayers.peek().litValue > 1,
        "a zero click should complete multiple reveal layers")
      dut.io.revealCycles.expect(dut.io.revealLayers.peek().litValue.U)
      dut.io.revision.expect(2.U)
      dut.io.exploded.valid.expect(false.B)
      dut.io.result.ready.poke(true.B)
      dut.clock.step()
      dut.io.result.ready.poke(false.B)

      send(dut, CmdKind.Flag, row = 0, col = 1)
      awaitResult(dut)
      dut.io.result.bits.status.expect(CommandStatus.Ok)
      dut.io.result.bits.changed.expect(false.B)
      dut.io.revision.expect(2.U)
    }
  }


  private def consume(dut: MinesweeperCore): Unit = {
    dut.io.result.ready.poke(true.B)
    dut.clock.step()
    dut.io.result.ready.poke(false.B)
  }

  it should "report minesLeft correctly when the mine count sets the counter's top bit" in {
    test(new MinesweeperCore(MsParams.forVerification(9, 9))) { dut =>
      initialize(dut)
      send(dut, CmdKind.NewGame, preset = 0, mines = 70)
      awaitResult(dut)
      dut.io.result.bits.status.expect(CommandStatus.Ok)
      consume(dut)
      dut.io.minesLeft.expect(70.S)
    }
  }

  it should "report minesLeft correctly across the flag-count sign boundary" in {
    test(new MinesweeperCore(MsParams.forVerification(9, 9))) { dut =>
      initialize(dut)
      send(dut, CmdKind.NewGame, preset = 0, mines = 1)
      awaitResult(dut)
      consume(dut)

      var flags = 0
      for (row <- 0 until 9; col <- 0 until 9) {
        send(dut, CmdKind.Flag, row = row, col = col)
        awaitResult(dut)
        dut.io.result.bits.status.expect(CommandStatus.Ok)
        consume(dut)
        flags += 1
        if (flags == 63 || flags == 64 || flags == 65 || flags == 81) {
          dut.io.minesLeft.expect((1 - flags).S)
        }
      }
    }
  }
}
