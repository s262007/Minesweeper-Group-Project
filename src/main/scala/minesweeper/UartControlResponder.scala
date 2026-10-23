package minesweeper

import chisel3._
import chisel3.util._

object UartWireError extends ChiselEnum {
  val Busy, BadCommand, BadArgument, BadPreset, BadMines, BadSeed, Range,
      LineTooLong, Sequence, Internal, RxFrame, RxOverflow = Value
}

class UartControlReplyRequest extends Bundle {
  val ack = Bool()
  val sequenceValid = Bool()
  val sequence = UInt(16.W)
  val code = UartWireError()
}

class UartControlResponderIO extends Bundle {
  val request = Flipped(Decoupled(new UartControlReplyRequest))
  val tx = Decoupled(UInt(8.W))
  val busy = Output(Bool())
  val done = Output(Bool())
}

/** Sends one ACK or ERR line, holding each byte until the UART accepts it. */
class UartControlResponder extends Module {
  private val maxBytes = 32
  val io = IO(new UartControlResponderIO)
  private val active = RegInit(false.B)
  private val bytes = Reg(Vec(maxBytes, UInt(8.W)))
  private val length = RegInit(0.U(6.W))
  private val index = RegInit(0.U(6.W))
  private val donePulse = RegInit(false.B)

  private def lit(s: String): Seq[UInt] = s.map(_.toInt.U(8.W))
  private def hex16(value: UInt): Seq[UInt] = (3 to 0 by -1).map { i =>
    val n = value(4 * i + 3, 4 * i)
    Mux(n < 10.U, n.pad(8) + '0'.toInt.U(8.W), n.pad(8) - 10.U(8.W) + 'A'.toInt.U(8.W))
  }
  private val codeText = Seq(
    UartWireError.Busy -> "BUSY",
    UartWireError.BadCommand -> "BAD_CMD",
    UartWireError.BadArgument -> "BAD_ARG",
    UartWireError.BadPreset -> "BAD_PRESET",
    UartWireError.BadMines -> "BAD_MINES",
    UartWireError.BadSeed -> "BAD_SEED",
    UartWireError.Range -> "RANGE",
    UartWireError.LineTooLong -> "LINE_TOO_LONG",
    UartWireError.Sequence -> "SEQ",
    UartWireError.Internal -> "INTERNAL",
    UartWireError.RxFrame -> "RX_FRAME",
    UartWireError.RxOverflow -> "RX_OVERFLOW"
  )
  private val codeVecs = codeText.map { case (code, text) =>
    val padded = VecInit((0 until 16).map(i => if (i < text.length) text(i).toInt.U(8.W) else 0.U(8.W)))
    (code.asUInt, padded, text.length.U(5.W))
  }
  private val defaultCode = codeVecs.head
  private val selectedCode = MuxLookup(io.request.bits.code.asUInt, defaultCode._2)(
    codeVecs.map(v => v._1 -> v._2))
  private val selectedLength = MuxLookup(io.request.bits.code.asUInt, defaultCode._3)(
    codeVecs.map(v => v._1 -> v._3))
  private val sequenceHex = hex16(io.request.bits.sequence)
  private val errorSeq = (0 until 4).map(i =>
    Mux(io.request.bits.sequenceValid, sequenceHex(i), '-'.toInt.U(8.W)))
  private val errPrefix = lit("ERR ") ++ errorSeq ++ lit(" ")
  private val ackBytes = lit("ACK ") ++ hex16(io.request.bits.sequence) ++ lit("\n")
  private val line = Wire(Vec(maxBytes, UInt(8.W)))
  for (i <- 0 until maxBytes) {
    line(i) := Mux(io.request.bits.ack,
      (if (i < ackBytes.length) ackBytes(i) else 0.U(8.W)),
      (if (i < errPrefix.length) errPrefix(i)
       else if (i < errPrefix.length + 16)
         Mux(selectedLength === (i - errPrefix.length).U,
           '\n'.toInt.U(8.W), selectedCode(i - errPrefix.length))
       else 0.U(8.W)))
  }
  private val errLength = (errPrefix.length.U(6.W) + selectedLength.pad(6) + 1.U(6.W))

  io.request.ready := !active
  io.tx.valid := active
  io.tx.bits := bytes(index(4, 0))
  io.busy := active
  io.done := donePulse
  donePulse := false.B

  when(io.request.fire) {
    active := true.B
    index := 0.U
    length := Mux(io.request.bits.ack, ackBytes.length.U, errLength)
    bytes := line
  }
  when(io.tx.fire) {
    when(index + 1.U === length) {
      active := false.B
      index := 0.U
      donePulse := true.B
    }.otherwise {
      index := index + 1.U
    }
  }
}
