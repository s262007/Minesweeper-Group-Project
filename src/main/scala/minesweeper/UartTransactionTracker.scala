package minesweeper

import chisel3._
import chisel3.util._

object UartTransactionDisposition extends ChiselEnum {
  val Execute, Busy, Replay, SequenceError, DecodeError = Value
}

class UartTransactionDecision(p: MsParams) extends Bundle {
  val disposition = UartTransactionDisposition()
  val request = new UartDecodedRequest(p)
}

class UartTransactionTrackerIO(p: MsParams) extends Bundle {
  val request = Flipped(Decoupled(new UartDecodedRequest(p)))
  val coreBusy = Input(Bool())
  /** Assert after the current transaction's complete response has been sent. */
  val complete = Input(Bool())
  val decision = Decoupled(new UartTransactionDecision(p))
}

/** Admits one transaction at a time and remembers the most recent request identity. */
class UartTransactionTracker(p: MsParams) extends Module {
  import UartDecodeError._
  import UartTransactionDisposition._

  val io = IO(new UartTransactionTrackerIO(p))

  private val cacheValid = RegInit(false.B)
  private val cacheInFlight = RegInit(false.B)
  private val cachedRequest = Reg(new UartDecodedRequest(p))
  private val decisionValid = RegInit(false.B)
  private val decisionBits = RegInit(0.U.asTypeOf(new UartTransactionDecision(p)))

  private val incoming = io.request.bits
  private val sameIdentity = incoming.kind === cachedRequest.kind &&
    incoming.sequenceValid === cachedRequest.sequenceValid &&
    incoming.sequence === cachedRequest.sequence &&
    incoming.command.asUInt === cachedRequest.command.asUInt
  private val sameSequence = incoming.sequenceValid && cachedRequest.sequenceValid &&
    incoming.sequence === cachedRequest.sequence

  io.request.ready := !decisionValid || io.decision.ready
  io.decision.valid := decisionValid
  io.decision.bits := decisionBits

  when(io.decision.fire) {
    decisionValid := false.B
  }

  when(io.request.fire) {
    decisionValid := true.B
    decisionBits.request := incoming

    when(incoming.error =/= Ok) {
      decisionBits.disposition := DecodeError
    }.elsewhen(cacheValid && sameSequence && !sameIdentity) {
      decisionBits.disposition := SequenceError
    }.elsewhen(cacheInFlight || io.coreBusy) {
      decisionBits.disposition := Busy
    }.elsewhen(cacheValid && sameSequence) {
      decisionBits.disposition := Replay
    }.otherwise {
      decisionBits.disposition := Execute
      cachedRequest := incoming
      cacheValid := true.B
      cacheInFlight := true.B
    }
  }

  // A simultaneous request is classified using the pre-completion in-flight
  // state (conservatively BUSY), then completion releases the slot.
  when(io.complete && cacheInFlight) {
    cacheInFlight := false.B
  }
}
