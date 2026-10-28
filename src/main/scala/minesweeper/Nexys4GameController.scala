package minesweeper

import chisel3._
import chisel3.util._

class Nexys4GameControllerIO(p: MsParams) extends Bundle {
  val restartEvent = Input(Bool())
  val requestedPreset = Input(UInt(p.presetW.W))
  val flagEvent = Input(Bool())
  val revealEvent = Input(Bool())
  val autoplayEvent = Input(Bool())
  val selectedRow = Input(UInt(p.rowW.W))
  val selectedCol = Input(UInt(p.colW.W))
  val coreCmd = Decoupled(new CmdBundle(p))
  val coreResult = Flipped(Decoupled(new CommandResult(p)))
  val coreConfig = Input(new ActiveConfig(p))
  val coreState = Input(GameState())
  val coreSettled = Input(Bool())
  val coreCells = Input(Vec(p.cells, new PublicCellView))
  val coreHints = Input(Vec(p.cells, new SolverHint))
  val coreHintsValid = Input(Bool())
  val autoplayActive = Output(Bool())
  val requestOverflow = Output(Bool())
}

class Nexys4GameController(p: MsParams) extends Module {
  val io = IO(new Nexys4GameControllerIO(p))

  private val requests = Module(new Queue(new CmdBundle(p), 8))
  private val waitingResult = RegInit(false.B)
  private val nextSeed = RegInit(1.U(p.seedW.W))
  private val overflow = RegInit(false.B)
  private val autoplay = RegInit(false.B)
  private val autoCommandValid = RegInit(false.B)
  private val autoCommand = RegInit(0.U.asTypeOf(new CmdBundle(p)))
  private val autoInFlight = RegInit(false.B)
  private val scanActive = RegInit(false.B)
  private val scanIndex = RegInit(0.U(p.idxW.W))
  private val scanRow = RegInit(0.U(p.rowW.W))
  private val scanCol = RegInit(0.U(p.colW.W))

  private val request = Wire(new CmdBundle(p))
  request := 0.U.asTypeOf(new CmdBundle(p))
  when(io.restartEvent) {
    request.kind := CmdKind.NewGame
    request.preset := io.requestedPreset
    request.mines := MuxLookup(io.requestedPreset, io.coreConfig.mines)(Seq(
      0.U -> 14.U(10.W),
      1.U -> 14.U(10.W),
      2.U -> 40.U(10.W),
      3.U -> 99.U(10.W),
      4.U -> 130.U(10.W)
    ))
    request.seed := nextSeed
  }.elsewhen(io.flagEvent) {
    request.kind := CmdKind.Flag
    request.row := io.selectedRow
    request.col := io.selectedCol
  }.elsewhen(io.revealEvent) {
    request.kind := CmdKind.Reveal
    request.row := io.selectedRow
    request.col := io.selectedCol
  }

  private val hasManualRequest = io.restartEvent || io.flagEvent || io.revealEvent
  private val simultaneousRequests = PopCount(Seq(io.restartEvent, io.flagEvent, io.revealEvent)) > 1.U
  requests.io.enq.valid := hasManualRequest
  requests.io.enq.bits := request
  requests.io.deq.ready := !waitingResult && io.coreCmd.ready && requests.io.deq.valid

  private val coreTerminal = io.coreState === GameState.Won ||
    io.coreState === GameState.Lost || io.coreState === GameState.Error
  private val autoCell = io.coreCells(scanIndex)
  private val autoHint = io.coreHints(scanIndex)
  private val autoCellIsSafe = autoHint.safe && autoCell.kind === PublicCellKind.Hidden

  io.coreCmd.valid := !waitingResult && (requests.io.deq.valid || autoCommandValid)
  io.coreCmd.bits := Mux(requests.io.deq.valid, requests.io.deq.bits, autoCommand)
  io.coreResult.ready := waitingResult
  io.autoplayActive := autoplay
  io.requestOverflow := overflow

  when(hasManualRequest && (!requests.io.enq.ready || simultaneousRequests)) {
    overflow := true.B
  }

  when(io.restartEvent && requests.io.enq.ready) {
    nextSeed := Mux(nextSeed === "hffff".U, 1.U, nextSeed + 1.U)
  }

  when(io.autoplayEvent && !io.restartEvent) {
    autoplay := true.B
    scanActive := true.B
    scanIndex := 0.U
    scanRow := 0.U
    scanCol := 0.U
  }

  when(hasManualRequest) {
    autoCommandValid := false.B
    scanActive := false.B
  }

  when(io.restartEvent) {
    autoplay := false.B
    autoInFlight := false.B
  }

  when(io.coreCmd.fire) {
    waitingResult := true.B
    when(requests.io.deq.valid) {
      autoInFlight := false.B
    }.otherwise {
      autoCommandValid := false.B
      autoInFlight := true.B
    }
  }

  when(io.coreResult.fire) {
    waitingResult := false.B
    autoInFlight := false.B
    when(coreTerminal || (autoInFlight &&
        (io.coreResult.bits.status =/= CommandStatus.Ok || !io.coreResult.bits.changed))) {
      autoplay := false.B
      scanActive := false.B
      autoCommandValid := false.B
    }.elsewhen(autoplay && io.coreHintsValid) {
      scanActive := true.B
      scanIndex := 0.U
      scanRow := 0.U
      scanCol := 0.U
    }.otherwise {
      autoplay := false.B
      scanActive := false.B
    }
  }

  when(scanActive && !waitingResult && !requests.io.deq.valid && !autoCommandValid &&
      !io.restartEvent && io.coreSettled) {
    when(coreTerminal) {
      autoplay := false.B
      scanActive := false.B
    }.elsewhen(!io.coreHintsValid) {
      autoplay := false.B
      scanActive := false.B
    }.elsewhen(autoCellIsSafe) {
      autoCommand.kind := CmdKind.Reveal
      autoCommand.row := scanRow
      autoCommand.col := scanCol
      autoCommand.preset := 0.U
      autoCommand.mines := 0.U
      autoCommand.seed := 0.U
      autoCommandValid := true.B
      scanActive := false.B
    }.elsewhen(scanIndex === (p.cells - 1).U) {
      autoplay := false.B
      scanActive := false.B
    }.otherwise {
      scanIndex := scanIndex + 1.U
      when(scanCol === (p.maxCols - 1).U) {
        scanCol := 0.U
        scanRow := scanRow + 1.U
      }.otherwise {
        scanCol := scanCol + 1.U
      }
    }
  }
}
