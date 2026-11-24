package minesweeper

import chisel3._
import chisel3.util._

object UartLineReceiver {
  val MaxRequestBytes: Int = 64
  val MaxLineBytes: Int = MaxRequestBytes - 1
}

class UartLine extends Bundle {
  val data = Vec(UartLineReceiver.MaxLineBytes, UInt(8.W))
  val length = UInt(log2Ceil(UartLineReceiver.MaxRequestBytes).W)
}

class UartLineReceiverIO extends Bundle {
  val rx = Flipped(Decoupled(UInt(8.W)))
  val rxFramingError = Input(Bool())
  val rxOverflow = Input(Bool())
  val line = Decoupled(new UartLine)
  val framingError = Output(Bool())
  val overflowError = Output(Bool())
  val lineTooLongError = Output(Bool())
}

class UartLineReceiver extends Module {
  import UartLineReceiver._

  val io = IO(new UartLineReceiverIO)

  val bytes = Reg(Vec(MaxLineBytes, UInt(8.W)))
  val count = RegInit(0.U(log2Ceil(MaxRequestBytes).W))
  val lastWasCr = RegInit(false.B)
  val discarding = RegInit(false.B)
  val lineBits = RegInit(0.U.asTypeOf(new UartLine))
  val lineValid = RegInit(false.B)
  val framingPulse = RegInit(false.B)
  val overflowPulse = RegInit(false.B)
  val tooLongPulse = RegInit(false.B)

  io.line.valid := lineValid
  io.line.bits := lineBits
  io.rx.ready := !lineValid || io.line.ready
  io.framingError := framingPulse
  io.overflowError := overflowPulse
  io.lineTooLongError := tooLongPulse

  framingPulse := false.B
  overflowPulse := false.B
  tooLongPulse := false.B

  when(io.line.fire) {
    lineValid := false.B
  }

  when(io.rxFramingError || io.rxOverflow) {
    framingPulse := io.rxFramingError
    overflowPulse := io.rxOverflow
    count := 0.U
    lastWasCr := false.B
    discarding := true.B
  }.elsewhen(io.rx.fire) {
    when(discarding) {
      when(io.rx.bits === 0x0a.U) {
        discarding := false.B
        count := 0.U
        lastWasCr := false.B
      }
    }.elsewhen(io.rx.bits === 0x0a.U) {
      lineBits.data := bytes
      lineBits.length := count - lastWasCr.asUInt
      lineValid := true.B
      count := 0.U
      lastWasCr := false.B
    }.otherwise {
      when(count === MaxLineBytes.U) {
        count := 0.U
        lastWasCr := false.B
        discarding := true.B
        overflowPulse := true.B
        tooLongPulse := true.B
      }.otherwise {
        bytes(count) := io.rx.bits
        count := count + 1.U
        lastWasCr := io.rx.bits === 0x0d.U
      }
    }
  }
}
