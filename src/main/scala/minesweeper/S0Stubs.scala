package minesweeper

import chisel3._
import chisel3.util._

/** UART bridge command and reply sequencer. */
class UartGameBridge(p: MsParams) extends Module {
  val io = IO(new UartGameBridgeIO(p))

  private val idle = 0.U(3.W)
  private val controlLaunch = 1.U(3.W)
  private val controlSend = 2.U(3.W)
  private val issueCommand = 3.U(3.W)
  private val waitResult = 4.U(3.W)
  private val serializerLaunch = 5.U(3.W)
  private val serializeReply = 6.U(3.W)
  private val releaseResult = 7.U(3.W)
  private val afterIssue = 0.U(3.W)
  private val afterSerialize = 1.U(3.W)
  private val afterError = 2.U(3.W)
  private val afterFinish = 3.U(3.W)
  private val afterRelease = 4.U(3.W)

  private val receiver = Module(new UartLineReceiver)
  private val decoder = Module(new UartRequestDecoder(p))
  private val tracker = Module(new UartTransactionTracker(p))
  private val decisions = Module(new Queue(new UartTransactionDecision(p), 4))
  private val rxErrors = Module(new Queue(UartWireError(), 4))
  private val controlTx = Module(new UartControlResponder)
  private val serializer = Module(new UartReplySerializer(p))

  receiver.io.rx.valid := io.rx.valid
  receiver.io.rx.bits := io.rx.bits
  io.rx.ready := receiver.io.rx.ready
  receiver.io.rxFramingError := io.rxFramingError
  receiver.io.rxOverflow := io.rxOverflow
  decoder.io.line <> receiver.io.line
  decoder.io.config := io.core.config
  tracker.io.request <> decoder.io.out
  tracker.io.coreBusy := io.core.busy || !io.core.settled
  decisions.io.enq <> tracker.io.decision
  decisions.io.deq.ready := false.B
  tracker.io.complete := false.B

  private val rxErrorValid = receiver.io.framingError || receiver.io.lineTooLongError ||
    receiver.io.overflowError
  rxErrors.io.enq.valid := rxErrorValid
  rxErrors.io.enq.bits := Mux(receiver.io.framingError, UartWireError.RxFrame,
    Mux(receiver.io.lineTooLongError, UartWireError.LineTooLong, UartWireError.RxOverflow))
  rxErrors.io.deq.ready := false.B

  private val state = RegInit(idle)
  private val nextAfterControl = RegInit(afterFinish)
  private val activeDecision = Reg(new UartTransactionDecision(p))
  private val controlRequest = Reg(new UartControlReplyRequest)
  private val currentIsExecute = RegInit(false.B)
  private val currentIsMutation = RegInit(false.B)
  private val cachedFailure = RegInit(false.B)
  private val cachedError = RegInit(UartWireError.Internal)

  controlTx.io.request.valid := state === controlLaunch
  controlTx.io.request.bits := controlRequest
  serializer.io.start.valid := state === serializerLaunch
  serializer.io.start.bits.hello := activeDecision.request.kind === UartRequestKind.Hello
  serializer.io.start.bits.sequence := activeDecision.request.sequence

  serializer.io.snapshot.config := io.core.config
  serializer.io.snapshot.state := io.core.state
  serializer.io.snapshot.cells := io.core.cells
  serializer.io.snapshot.hints := io.core.hints
  serializer.io.snapshot.hintsValid := io.core.hintsValid
  serializer.io.snapshot.minesLeft := io.core.minesLeft
  serializer.io.snapshot.revision := io.core.revision
  serializer.io.snapshot.generationCycles := io.core.generationCycles
  serializer.io.snapshot.revealCycles := io.core.revealCycles
  serializer.io.snapshot.revealLayers := io.core.revealLayers
  serializer.io.snapshot.solverCycles := io.core.solverCycles

  io.core.cmd.valid := state === issueCommand
  io.core.cmd.bits := activeDecision.request.command
  io.core.result.ready := state === releaseResult

  io.tx.valid := false.B
  io.tx.bits := 0.U
  controlTx.io.tx.ready := false.B
  serializer.io.tx.ready := false.B
  when(controlTx.io.tx.valid) {
    io.tx.valid := controlTx.io.tx.valid
    io.tx.bits := controlTx.io.tx.bits
    controlTx.io.tx.ready := io.tx.ready
  }.otherwise {
    io.tx.valid := serializer.io.tx.valid
    io.tx.bits := serializer.io.tx.bits
    serializer.io.tx.ready := io.tx.ready
  }

  private def launchError(
      code: UartWireError.Type,
      sequenceValid: Bool,
      sequence: UInt,
      next: UInt
  ): Unit = {
    controlRequest.ack := false.B
    controlRequest.sequenceValid := sequenceValid
    controlRequest.sequence := sequence
    controlRequest.code := code
    nextAfterControl := next
    state := controlLaunch
  }
  private def launchAck(sequence: UInt, next: UInt): Unit = {
    controlRequest.ack := true.B
    controlRequest.sequenceValid := true.B
    controlRequest.sequence := sequence
    controlRequest.code := UartWireError.Busy
    nextAfterControl := next
    state := controlLaunch
  }
  private def decodeError(error: UartDecodeError.Type): UartWireError.Type = {
    val code = WireDefault(UartWireError.BadArgument)
    when(error === UartDecodeError.BadCommand) { code := UartWireError.BadCommand }
    when(error === UartDecodeError.Range) { code := UartWireError.Range }
    code
  }
  private def coreError(status: CommandStatus.Type): UartWireError.Type = {
    val code = WireDefault(UartWireError.Internal)
    when(status === CommandStatus.BadPreset) { code := UartWireError.BadPreset }
    when(status === CommandStatus.BadMines) { code := UartWireError.BadMines }
    when(status === CommandStatus.BadSeed) { code := UartWireError.BadSeed }
    when(status === CommandStatus.Range) { code := UartWireError.Range }
    when(status === CommandStatus.BadCommand) { code := UartWireError.BadCommand }
    code
  }

  when(state === idle) {
    when(rxErrors.io.deq.valid) {
      rxErrors.io.deq.ready := true.B
      launchError(rxErrors.io.deq.bits, false.B, 0.U, afterFinish)
    }.elsewhen(decisions.io.deq.valid) {
      decisions.io.deq.ready := true.B
      activeDecision := decisions.io.deq.bits
      val disp = decisions.io.deq.bits.disposition
      val req = decisions.io.deq.bits.request
      when(disp === UartTransactionDisposition.DecodeError) {
        launchError(decodeError(req.error), req.sequenceValid, req.sequence, afterFinish)
      }.elsewhen(disp === UartTransactionDisposition.SequenceError) {
        launchError(UartWireError.Sequence, req.sequenceValid, req.sequence, afterFinish)
      }.elsewhen(disp === UartTransactionDisposition.Busy) {
        launchError(UartWireError.Busy, req.sequenceValid, req.sequence, afterFinish)
      }.elsewhen(disp === UartTransactionDisposition.Replay && cachedFailure) {
        currentIsExecute := false.B
        currentIsMutation := false.B
        launchAck(req.sequence, afterError)
      }.otherwise {
        val isExecute = disp === UartTransactionDisposition.Execute
        currentIsExecute := isExecute
        currentIsMutation := isExecute &&
          (req.kind === UartRequestKind.NewGame || req.kind === UartRequestKind.Reveal ||
            req.kind === UartRequestKind.Flag)
        when(isExecute) { cachedFailure := false.B }
        when(req.kind === UartRequestKind.Hello) {
          state := serializerLaunch
        }.elsewhen(req.kind === UartRequestKind.Get || disp === UartTransactionDisposition.Replay) {
          launchAck(req.sequence, afterSerialize)
        }.otherwise {
          launchAck(req.sequence, afterIssue)
        }
      }
    }
  }

  when(state === controlLaunch && controlTx.io.request.fire) { state := controlSend }
  when(state === controlSend && controlTx.io.done) {
    when(nextAfterControl === afterIssue) {
      state := issueCommand
    }.elsewhen(nextAfterControl === afterSerialize) {
      state := serializerLaunch
    }.elsewhen(nextAfterControl === afterError) {
      launchError(cachedError, activeDecision.request.sequenceValid,
        activeDecision.request.sequence, afterFinish)
    }.elsewhen(nextAfterControl === afterRelease) {
      state := releaseResult
    }.otherwise {
      state := idle
    }
  }

  when(state === issueCommand && io.core.cmd.fire) { state := waitResult }
  when(state === waitResult && io.core.result.valid) {
    when(io.core.result.bits.status === CommandStatus.Ok) {
      state := serializerLaunch
    }.otherwise {
      cachedFailure := true.B
      cachedError := coreError(io.core.result.bits.status)
      launchError(coreError(io.core.result.bits.status), true.B,
        activeDecision.request.sequence, afterRelease)
    }
  }
  when(state === serializerLaunch && serializer.io.start.fire) { state := serializeReply }
  when(state === serializeReply && serializer.io.done) {
    when(currentIsMutation) {
      state := releaseResult
    }.otherwise {
      when(currentIsExecute) { tracker.io.complete := true.B }
      state := idle
    }
  }
  when(state === releaseResult) {
    when(currentIsExecute) { tracker.io.complete := true.B }
    currentIsExecute := false.B
    currentIsMutation := false.B
    state := idle
  }
}

class FpgaTop(p: MsParams) extends Module {
  require(!p.verification, "FPGA top must use production parameters")
  val io = IO(new FpgaTopIO)
  val core = Module(new MinesweeperCore(p))
  val bridge = Module(new UartGameBridge(p))
  bridge.io.core <> core.io
  bridge.io.rx.valid := false.B
  bridge.io.rx.bits := 0.U
  bridge.io.rxFramingError := false.B
  bridge.io.rxOverflow := false.B
  bridge.io.tx.ready := false.B
  io.tx := true.B
  io.won := false.B
  io.lost := false.B
}
