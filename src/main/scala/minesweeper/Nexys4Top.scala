package minesweeper

import chisel3._
import chisel3.util._

class Nexys4TopIO extends Bundle {
  val buttonUp = Input(Bool())
  val buttonDown = Input(Bool())
  val buttonLeft = Input(Bool())
  val buttonRight = Input(Bool())
  val buttonCenter = Input(Bool())
  val switches = Input(UInt(4.W))
  val uartRx = Input(Bool())
  val uartTx = Output(Bool())
  val segmentsN = Output(UInt(7.W))
  val decimalPointN = Output(Bool())
  val anodesN = Output(UInt(8.W))
  val leds = Output(UInt(4.W))
  val led16B = Output(Bool())
  val led16G = Output(Bool())
  val led16R = Output(Bool())
  val led17B = Output(Bool())
  val led17G = Output(Bool())
  val led17R = Output(Bool())
}

class Nexys4Top(p: MsParams = MsParams()) extends RawModule {
  require(!p.verification, "Nexys4 top must use production parameters")

  val clock100MHz = IO(Input(Clock()))
  val cpuResetN = IO(Input(Bool()))
  val io = IO(new Nexys4TopIO)

  private val rawReset = (!cpuResetN).asAsyncReset
  private val resetRelease = withClockAndReset(clock100MHz, rawReset) {
    val resetPipe = RegInit(0.U(2.W))
    resetPipe := Cat(resetPipe(0), 1.U(1.W))
    !resetPipe(1)
  }

  withClockAndReset(clock100MHz, resetRelease.asAsyncReset) {
    val params = p
    val core = Module(new MinesweeperCore(params))
    val bridge = Module(new UartGameBridge(params))
    val controls = Module(new Nexys4InputConditioner())
    val selected = Module(new Nexys4HighlightedCell(params))
    val gameController = Module(new Nexys4GameController(params))
    val sevenSegment = Module(new Nexys4SevenSegment(params))
    val receiver = Module(new UartRx(UartConfig(clockHz = 100000000L)))
    val transmitter = Module(new UartTx(UartConfig(clockHz = 100000000L)))
    val commandArbiter = Module(new Arbiter(new CmdBundle(params), 2))
    val selectionMode = RegInit(true.B)
    val selectedPreset = RegInit(0.U(params.presetW.W))
    val blink = RegInit(false.B)
    val blinkDivider = RegInit(0.U(25.W))

    when(blinkDivider === 9999999.U) {
      blinkDivider := 0.U
      blink := !blink
    }.otherwise {
      blinkDivider := blinkDivider + 1.U
    }

    when(selectionMode) {
      when(controls.io.moveRight || controls.io.moveDown) {
        selectedPreset := Mux(selectedPreset === (params.presets.size - 1).U,
          0.U, selectedPreset + 1.U)
      }.elsewhen(controls.io.moveLeft || controls.io.moveUp) {
        selectedPreset := Mux(selectedPreset === 0.U,
          (params.presets.size - 1).U, selectedPreset - 1.U)
      }
      when(controls.io.restartEvent) {
        selectionMode := false.B
      }
    }.elsewhen(core.io.state === GameState.Won || core.io.state === GameState.Lost) {
      when(controls.io.restartEvent) {
        selectionMode := true.B
      }
    }

    controls.io.buttonUp := io.buttonUp
    controls.io.buttonDown := io.buttonDown
    controls.io.buttonLeft := io.buttonLeft
    controls.io.buttonRight := io.buttonRight
    controls.io.buttonCenter := io.buttonCenter
    controls.io.hintSwitch := io.switches(0)
    controls.io.flagSwitch := io.switches(1)
    controls.io.revealSwitch := io.switches(2)
    controls.io.autoplaySwitch := io.switches(3)

    selected.io.rows := core.io.config.rows
    selected.io.cols := core.io.config.cols
    selected.io.moveUp := controls.io.moveUp && !selectionMode
    selected.io.moveDown := controls.io.moveDown && !selectionMode
    selected.io.moveLeft := controls.io.moveLeft && !selectionMode
    selected.io.moveRight := controls.io.moveRight && !selectionMode
    selected.io.resetSelection := controls.io.restartEvent && !selectionMode

    gameController.io.restartEvent := selectionMode && controls.io.restartEvent
    gameController.io.requestedPreset := selectedPreset
    gameController.io.flagEvent := controls.io.flagEvent
    gameController.io.revealEvent := controls.io.revealEvent
    gameController.io.autoplayEvent := controls.io.autoplayEvent
    gameController.io.selectedRow := selected.io.selectedRow
    gameController.io.selectedCol := selected.io.selectedCol
    gameController.io.coreConfig := core.io.config
    gameController.io.coreState := core.io.state
    gameController.io.coreSettled := core.io.settled
    gameController.io.coreCells := core.io.cells
    gameController.io.coreHints := core.io.hints
    gameController.io.coreHintsValid := core.io.hintsValid

    commandArbiter.io.in(0) <> gameController.io.coreCmd
    commandArbiter.io.in(1) <> bridge.io.core.cmd
    core.io.cmd <> commandArbiter.io.out

    val resultToBridge = RegInit(false.B)
    when(commandArbiter.io.out.fire) {
      resultToBridge := !commandArbiter.io.in(0).valid
    }

    gameController.io.coreResult.valid := core.io.result.valid && !resultToBridge
    gameController.io.coreResult.bits := core.io.result.bits
    bridge.io.core.result.valid := core.io.result.valid && resultToBridge
    bridge.io.core.result.bits := core.io.result.bits
    core.io.result.ready := Mux(resultToBridge,
      bridge.io.core.result.ready, gameController.io.coreResult.ready)

    bridge.io.core.config := core.io.config
    bridge.io.core.state := core.io.state
    bridge.io.core.busy := core.io.busy
    bridge.io.core.settled := core.io.settled
    bridge.io.core.cells := core.io.cells
    bridge.io.core.hints := core.io.hints
    bridge.io.core.hintsValid := core.io.hintsValid
    bridge.io.core.minesLeft := core.io.minesLeft
    bridge.io.core.exploded := core.io.exploded
    bridge.io.core.revision := core.io.revision
    bridge.io.core.generationCycles := core.io.generationCycles
    bridge.io.core.revealCycles := core.io.revealCycles
    bridge.io.core.revealLayers := core.io.revealLayers
    bridge.io.core.solverCycles := core.io.solverCycles

    receiver.io.rx := io.uartRx
    bridge.io.rx <> receiver.io.out
    bridge.io.rxFramingError := receiver.io.framingError
    bridge.io.rxOverflow := receiver.io.overflow
    transmitter.io.in <> bridge.io.tx

    sevenSegment.io.minesLeft := core.io.minesLeft
    io.segmentsN := sevenSegment.io.segmentsN
    io.decimalPointN := sevenSegment.io.decimalPointN
    io.anodesN := sevenSegment.io.anodesN
    io.uartTx := transmitter.io.tx

    val terminalWon = core.io.state === GameState.Won
    val terminalLost = core.io.state === GameState.Lost
    val playing = !selectionMode && !terminalWon && !terminalLost
    io.leds := Mux(selectionMode, selectedPreset + 1.U,
      Mux(terminalWon && blink, "b1111".U(4.W), 0.U(4.W)))
    io.led16G := (playing || (terminalWon && blink))
    io.led16R := terminalLost && blink
    io.led16B := false.B
    io.led17G := terminalWon && blink
    io.led17R := terminalLost && blink
    io.led17B := false.B
  }
}
