package minesweeper

import chisel3._
import chisel3.util._

private object SampleTick {
  def apply(cfg: UartConfig): Bool = {
    val count = RegInit(0.U(log2Ceil(cfg.sampleDivisor + 1).W))
    val tick = count === (cfg.sampleDivisor - 1).U
    count := Mux(tick, 0.U, count + 1.U)
    tick
  }
}

private class UartRxEntry extends Bundle {
  val framing = Bool()
  val overflow = Bool()
  val data = UInt(8.W)
}

class UartRx(cfg: UartConfig) extends Module {
  val io = IO(new UartRxIO)

  private val line = ShiftRegister(io.rx, 2, true.B, true.B)
  private val tick = SampleTick(cfg)

  private object S extends ChiselEnum {
    val Idle, Start, Data, Stop, Break = Value
  }
  private val state = RegInit(S.Idle)
  private val ticks = RegInit(0.U(4.W))
  private val bitIndex = RegInit(0.U(3.W))
  private val shift = RegInit(0.U(8.W))

  private val fifo = Module(new Queue(new UartRxEntry, cfg.rxFifoDepth))
  private val received = WireDefault(false.B)
  private val badStop = WireDefault(false.B)
  private val pendingFraming = RegInit(false.B)
  private val pendingOverflow = RegInit(false.B)

  when(tick) {
    switch(state) {
      is(S.Idle) {
        when(!line) {
          state := S.Start
          ticks := 0.U
        }
      }
      is(S.Start) {
        ticks := ticks + 1.U
        when(ticks === 7.U) {
          state := Mux(line, S.Idle, S.Data)
          ticks := 0.U
          bitIndex := 0.U
        }
      }
      is(S.Data) {
        ticks := ticks + 1.U
        when(ticks === 15.U) {
          shift := Cat(line, shift(7, 1))
          bitIndex := bitIndex + 1.U
          when(bitIndex === 7.U) { state := S.Stop }
        }
      }
      is(S.Stop) {
        ticks := ticks + 1.U
        when(ticks === 15.U) {
          received := line
          badStop := !line
          state := Mux(line, S.Idle, S.Break)
        }
      }
      is(S.Break) {
        when(line) { state := S.Idle }
      }
    }
  }

  private val pending = pendingFraming || pendingOverflow
  private val event = received || badStop
  private val enqByte = received && !pending
  private val enqMarker = pending && !event
  fifo.io.enq.valid := enqByte || enqMarker || (badStop && !pending)
  fifo.io.enq.bits.data := shift
  fifo.io.enq.bits.framing := Mux(enqMarker, pendingFraming, badStop)
  fifo.io.enq.bits.overflow := enqMarker && pendingOverflow

  when(enqMarker && fifo.io.enq.ready) {
    pendingFraming := false.B
    pendingOverflow := false.B
  }
  when(event && (pending || !fifo.io.enq.ready)) {
    pendingFraming := pendingFraming || badStop
    pendingOverflow := pendingOverflow || received
  }

  private val isMarker = fifo.io.deq.bits.framing || fifo.io.deq.bits.overflow
  io.out.valid := fifo.io.deq.valid && !isMarker
  io.out.bits := fifo.io.deq.bits.data
  fifo.io.deq.ready := io.out.ready || isMarker
  io.framingError := fifo.io.deq.valid && fifo.io.deq.bits.framing
  io.overflow := fifo.io.deq.valid && fifo.io.deq.bits.overflow
}

class UartTx(cfg: UartConfig) extends Module {
  val io = IO(new UartTxIO)

  private val bitCycles = cfg.sampleDivisor * cfg.oversample
  private val count = RegInit(0.U(log2Ceil(bitCycles).W))
  private val shift = RegInit(0x3ff.U(10.W))
  private val bitsLeft = RegInit(0.U(4.W))
  private val busy = bitsLeft =/= 0.U

  io.in.ready := !busy
  when(io.in.fire) {
    shift := Cat(1.U(1.W), io.in.bits, 0.U(1.W))
    bitsLeft := 10.U
    count := 0.U
  }.elsewhen(busy) {
    when(count === (bitCycles - 1).U) {
      count := 0.U
      shift := Cat(1.U(1.W), shift(9, 1))
      bitsLeft := bitsLeft - 1.U
    }.otherwise {
      count := count + 1.U
    }
  }

  io.tx := !busy || shift(0)
  io.busy := busy
}
