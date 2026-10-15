package minesweeper

import chisel3._
import chisel3.util.{Decoupled, Valid}

// Enum declaration order is the hardware encoding; change it only with the contract.
object CmdKind extends ChiselEnum {
  val Reveal, Flag, NewGame = Value
}
object GameState extends ChiselEnum {
  val Idle, Generating, Revealing, Solving, Playing, Won, Lost, Error = Value
}
object CommandStatus extends ChiselEnum {
  val Ok, BadPreset, BadMines, BadSeed, Range, BadCommand, InternalError = Value
}
object PublicCellKind extends ChiselEnum {
  val Hidden, Flagged, Number, Mine = Value
}

class ActiveConfig(p: MsParams) extends Bundle {
  val preset = UInt(p.presetW.W)
  val cols = UInt(p.colsW.W)
  val rows = UInt(p.rowsW.W)
  val mines = UInt(p.cntW.W)
}

/** Only NewGame reads preset/mines/seed; Reveal/Flag read row/col. */
class CmdBundle(p: MsParams) extends Bundle {
  val kind = CmdKind()
  val row = UInt(p.rowW.W)
  val col = UInt(p.colW.W)
  val preset = UInt(p.presetW.W)
  val mines = UInt(p.cntW.W)
  val seed = UInt(p.seedW.W)
}

class CommandResult(p: MsParams) extends Bundle {
  val status = CommandStatus()
  val changed = Bool()
  val revision = UInt(p.revisionW.W)
}

/** Internal board state; UART uses PublicCellView. */
class CellView extends Bundle {
  val active = Bool()
  val mine = Bool()
  val revealed = Bool()
  val flagged = Bool()
  val adj = UInt(4.W)
}

/** Reveal may inspect hidden adjacent counts, but not truth mine bits. */
class RevealCellView extends Bundle {
  val active = Bool()
  val revealed = Bool()
  val flagged = Bool()
  val adj = UInt(4.W)
}

/** Set adj to zero for hidden cells. Flags and mine bits are excluded. */
class SolverCellView extends Bundle {
  val revealed = Bool()
  val adj = UInt(4.W)
}

/** Set adj to zero unless kind is Number. */
class PublicCellView extends Bundle {
  val active = Bool()
  val kind = PublicCellKind()
  val adj = UInt(4.W)
}

class SolverHint extends Bundle {
  val safe = Bool()
  val mine = Bool()
}

/** From the perspective of MinesweeperCore. No production test-load port. */
class CoreIO(p: MsParams) extends Bundle {
  val cmd = Flipped(Decoupled(new CmdBundle(p)))
  val result = Decoupled(new CommandResult(p))
  val config = Output(new ActiveConfig(p))
  val state = Output(GameState())
  val busy = Output(Bool())
  val settled = Output(Bool())
  val cells = Output(Vec(p.cells, new PublicCellView))
  val hints = Output(Vec(p.cells, new SolverHint))
  val hintsValid = Output(Bool())
  val minesLeft = Output(SInt(p.minesLeftW.W))
  val exploded = Output(Valid(UInt(p.idxW.W)))
  val revision = Output(UInt(p.revisionW.W))
  val generationCycles = Output(UInt(p.cycleW.W))
  val revealCycles = Output(UInt(p.cycleW.W))
  val revealLayers = Output(UInt(p.cntW.W))
  val solverCycles = Output(UInt(p.cycleW.W))
}

class MineGenRequest(p: MsParams) extends Bundle {
  val config = new ActiveConfig(p)
  val seed = UInt(p.seedW.W)
  val safeIdx = UInt(p.idxW.W)
}
class MineGenIO(p: MsParams) extends Bundle {
  val start = Flipped(Decoupled(new MineGenRequest(p)))
  val clear = Input(Bool())
  val busy = Output(Bool())
  val done = Output(Bool())
  val mines = Output(UInt(p.cells.W))
  val cycles = Output(UInt(p.cycleW.W))
}

class BoardIO(p: MsParams) extends Bundle {
  val config = Input(new ActiveConfig(p))
  val clear = Input(Bool())
  val loadMines = Flipped(Valid(UInt(p.cells.W)))
  val revealSet = Input(UInt(p.cells.W))
  val revealAllMines = Input(Bool())
  val flagToggle = Flipped(Valid(UInt(p.idxW.W)))
  val view = Output(Vec(p.cells, new CellView))
  val revealView = Output(Vec(p.cells, new RevealCellView))
  val solverView = Output(Vec(p.cells, new SolverCellView))
  val flagCount = Output(UInt(p.cntW.W))
  val revealedSafeCount = Output(UInt(p.cntW.W))
}

class RevealRequest(p: MsParams) extends Bundle {
  val config = new ActiveConfig(p)
  val idx = UInt(p.idxW.W)
}
class RevealIO(p: MsParams) extends Bundle {
  val start = Flipped(Decoupled(new RevealRequest(p)))
  val clear = Input(Bool())
  val view = Input(Vec(p.cells, new RevealCellView))
  val revealSet = Output(UInt(p.cells.W))
  val busy = Output(Bool())
  val done = Output(Bool())
  val cycles = Output(UInt(p.cycleW.W))
  val layers = Output(UInt(p.cntW.W))
}

class SolverRequest(p: MsParams) extends Bundle {
  val config = new ActiveConfig(p)
}
class SolverIO(p: MsParams) extends Bundle {
  val start = Flipped(Decoupled(new SolverRequest(p)))
  val clear = Input(Bool())
  val board = Input(Vec(p.cells, new SolverCellView))
  val hints = Output(Vec(p.cells, new SolverHint))
  val valid = Output(Bool())
  val busy = Output(Bool())
  val done = Output(Bool())
  val contradiction = Output(Bool())
  val cycles = Output(UInt(p.cycleW.W))
}

class UartRxIO extends Bundle {
  val rx = Input(Bool())
  val out = Decoupled(UInt(8.W))
  val framingError = Output(Bool())
  val overflow = Output(Bool())
}
class UartTxIO extends Bundle {
  val in = Flipped(Decoupled(UInt(8.W)))
  val tx = Output(Bool())
  val busy = Output(Bool())
}
class UartGameBridgeIO(p: MsParams) extends Bundle {
  val rx = Flipped(Decoupled(UInt(8.W)))
  val rxFramingError = Input(Bool())
  val rxOverflow = Input(Bool())
  val tx = Decoupled(UInt(8.W))
  val core = Flipped(new CoreIO(p))
}

/** Test/formal harness only. This bundle is not a member of CoreIO or FpgaTopIO. */
class TestLoadRequest(p: MsParams) extends Bundle {
  val config = new ActiveConfig(p)
  val seed = UInt(p.seedW.W)
  val mines = UInt(p.cells.W)
  val revealed = UInt(p.cells.W)
  val flagged = UInt(p.cells.W)
  val state = GameState()
}

/** Logical pins only. The chosen board supplies pin mapping and clock/reset. */
class FpgaTopIO extends Bundle {
  val rx = Input(Bool())
  val tx = Output(Bool())
  val won = Output(Bool())
  val lost = Output(Bool())
}
