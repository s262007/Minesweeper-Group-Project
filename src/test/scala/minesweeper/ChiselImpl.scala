package minesweeper

import chisel3._
import chiseltest._
import minesweeper.model._

/**
  * Drives a simulated MinesweeperCore through its command handshake (CONTRACT 5.2) and
  * reads its public outputs as a model Snapshot. The snapshot is sampled while the
  * result is held, before result.fire, as the contract requires.
  */
final class ChiselImpl(dut: MinesweeperCore, p: MsParams) extends MinesweeperImpl {
  val name = "chisel"
  /** One LFSR period plus the reveal/solver work of the largest board, with margin. */
  val timeout: Int = 65535 + 4 * p.cells + 100
  private var cyclesSpent = 0L
  private var last: Snapshot = _

  private val stateOf: Map[BigInt, State] = Seq(
    GameState.Idle -> State.Idle, GameState.Generating -> State.Generating,
    GameState.Revealing -> State.Revealing, GameState.Solving -> State.Solving,
    GameState.Playing -> State.Playing, GameState.Won -> State.Won,
    GameState.Lost -> State.Lost, GameState.Error -> State.Error
  ).map { case (h, m) => h.litValue -> m }.toMap
  private val statusOf: Map[BigInt, Status] = Seq(
    CommandStatus.Ok -> Status.Ok, CommandStatus.BadPreset -> Status.BadPreset,
    CommandStatus.BadMines -> Status.BadMines, CommandStatus.BadSeed -> Status.BadSeed,
    CommandStatus.Range -> Status.Range, CommandStatus.BadCommand -> Status.BadCommand,
    CommandStatus.InternalError -> Status.InternalError
  ).map { case (h, m) => h.litValue -> m }.toMap
  private val kindOf: Map[BigInt, CellKind] = Seq(
    PublicCellKind.Hidden -> CellKind.Hidden, PublicCellKind.Flagged -> CellKind.Flagged,
    PublicCellKind.Number -> CellKind.Number, PublicCellKind.Mine -> CellKind.Mine
  ).map { case (h, m) => h.litValue -> m }.toMap

  /** Physical reset, then the default game. Call once before any command. */
  def init(): Unit = {
    dut.clock.setTimeout(0)
    dut.io.cmd.valid.poke(false.B)
    dut.io.cmd.bits.kind.poke(CmdKind.NewGame)
    dut.io.cmd.bits.row.poke(0.U)
    dut.io.cmd.bits.col.poke(0.U)
    dut.io.cmd.bits.preset.poke(0.U)
    dut.io.cmd.bits.mines.poke(0.U)
    dut.io.cmd.bits.seed.poke(1.U)
    dut.io.result.ready.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
    dut.clock.step()
    last = read()
  }

  /** Clock cycles spent in commands since init (for timing displays). */
  def cycles: Long = cyclesSpent

  private def step(): Unit = { dut.clock.step(); cyclesSpent += 1 }

  private def command(kind: CmdKind.Type, row: Int, col: Int, preset: Int, mines: Int, seed: Int): CmdResult = {
    dut.io.cmd.bits.kind.poke(kind)
    dut.io.cmd.bits.row.poke(row.U)
    dut.io.cmd.bits.col.poke(col.U)
    dut.io.cmd.bits.preset.poke(preset.U)
    dut.io.cmd.bits.mines.poke(mines.U)
    dut.io.cmd.bits.seed.poke(seed.U)
    dut.io.cmd.valid.poke(true.B)
    var waited = 0
    while (!dut.io.cmd.ready.peek().litToBoolean) {
      require(waited < timeout, s"$kind was not accepted within $timeout cycles")
      step(); waited += 1
    }
    step()
    dut.io.cmd.valid.poke(false.B)
    waited = 0
    while (!dut.io.result.valid.peek().litToBoolean) {
      require(waited < timeout, s"$kind did not complete within $timeout cycles")
      step(); waited += 1
    }
    val result = CmdResult(
      statusOf(dut.io.result.bits.status.peek().litValue),
      dut.io.result.bits.changed.peek().litToBoolean,
      dut.io.result.bits.revision.peek().litValue.toLong)
    last = read()
    dut.io.result.ready.poke(true.B)
    step()
    dut.io.result.ready.poke(false.B)
    result
  }

  private def read(): Snapshot = {
    val io = dut.io
    val cells = Vector.tabulate(p.cells) { i =>
      PublicCell(io.cells(i).active.peek().litToBoolean, kindOf(io.cells(i).kind.peek().litValue),
        io.cells(i).adj.peek().litValue.toInt)
    }
    val hints = Vector.tabulate(p.cells)(i =>
      Hint(io.hints(i).safe.peek().litToBoolean, io.hints(i).mine.peek().litToBoolean))
    Snapshot(
      maxCols = p.maxCols,
      preset = io.config.preset.peek().litValue.toInt,
      cols = io.config.cols.peek().litValue.toInt,
      rows = io.config.rows.peek().litValue.toInt,
      mines = io.config.mines.peek().litValue.toInt,
      state = stateOf(io.state.peek().litValue),
      cells = cells,
      hintsValid = io.hintsValid.peek().litToBoolean,
      hints = hints,
      minesLeft = io.minesLeft.peek().litValue.toInt,
      exploded = if (io.exploded.valid.peek().litToBoolean) Some(io.exploded.bits.peek().litValue.toInt) else None,
      revision = io.revision.peek().litValue.toLong,
      generationCycles = io.generationCycles.peek().litValue.toLong,
      revealCycles = io.revealCycles.peek().litValue.toLong,
      revealLayers = io.revealLayers.peek().litValue.toLong,
      solverCycles = io.solverCycles.peek().litValue.toLong
    )
  }

  def newGame(preset: Int, mines: Int, seed: Int): CmdResult = command(CmdKind.NewGame, 0, 0, preset, mines, seed)
  def reveal(row: Int, col: Int): CmdResult = command(CmdKind.Reveal, row, col, 0, 0, 1)
  def flag(row: Int, col: Int): CmdResult = command(CmdKind.Flag, row, col, 0, 0, 1)
  def snapshot: Snapshot = last
}
