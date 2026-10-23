package minesweeper

import chisel3._
import chisel3.util._

object UartRequestKind extends ChiselEnum {
  val Hello, Get, NewGame, Reveal, Flag = Value
}

object UartDecodeError extends ChiselEnum {
  val Ok, BadCommand, BadArgument, Range = Value
}

class UartDecodedRequest(p: MsParams) extends Bundle {
  val kind = UartRequestKind()
  val sequence = UInt(16.W)
  val sequenceValid = Bool()
  val error = UartDecodeError()
  val command = new CmdBundle(p)
}

class UartRequestDecoderIO(p: MsParams) extends Bundle {
  val line = Flipped(Decoupled(new UartLine))
  val config = Input(new ActiveConfig(p))
  val out = Decoupled(new UartDecodedRequest(p))
}

/** Strict hardware parser for the UART v2 request grammar. */
class UartRequestDecoder(p: MsParams) extends Module {
  import UartDecodeError._
  import UartRequestKind._

  private val idle = 0.U(2.W)
  private val reading = 1.U(2.W)
  private val checking = 2.U(2.W)
  private val emitting = 3.U(2.W)

  val io = IO(new UartRequestDecoderIO(p))

  private val state = RegInit(idle)
  private val line = Reg(new UartLine)
  private val lineLength = RegInit(0.U(6.W))
  private val charIndex = RegInit(0.U(6.W))
  private val tokenNumber = RegInit(0.U(3.W))
  private val expectedArgs = RegInit(0.U(3.W))
  private val digits = RegInit(0.U(6.W))
  private val value = RegInit(0.U(32.W))
  private val tokenBad = RegInit(false.B)
  private val args = Reg(Vec(4, UInt(32.W)))
  private val result = RegInit(0.U.asTypeOf(new UartDecodedRequest(p)))

  private def commandMatches(word: String): Bool = {
    val letters = word.indices.map(i => io.line.bits.data(i) === word.charAt(i).toInt.U)
      .reduce(_ && _)
    letters && (io.line.bits.length === word.length.U ||
      (io.line.bits.length > word.length.U &&
        io.line.bits.data(word.length) === ' '.toInt.U))
  }

  private val isHello = commandMatches("HELLO")
  private val isGet = commandMatches("GET")
  private val isNew = commandMatches("NEW")
  private val isReveal = commandMatches("REVEAL")
  private val isFlag = commandMatches("FLAG")
  private val recognized = isHello || isGet || isNew || isReveal || isFlag

  private val commandLength = Mux(isHello, 5.U,
    Mux(isGet, 3.U, Mux(isNew, 3.U, Mux(isReveal, 6.U, 4.U))))
  private val detectedKind = Wire(UartRequestKind())
  detectedKind := Flag
  when(isHello) { detectedKind := Hello }
  when(isGet) { detectedKind := Get }
  when(isNew) { detectedKind := NewGame }
  when(isReveal) { detectedKind := Reveal }

  io.line.ready := state === idle
  io.out.valid := state === emitting
  io.out.bits := result
  when(io.out.fire) { state := idle }

  private def storeCurrentArgument(): Unit = {
    for (i <- 1 to 4) {
      when(tokenNumber === i.U) { args(i - 1) := value }
    }
  }

  when(io.line.fire) {
    line := io.line.bits
    lineLength := io.line.bits.length
    result := 0.U.asTypeOf(new UartDecodedRequest(p))
    result.kind := detectedKind
    args.foreach(_ := 0.U)
    when(!recognized) {
      result.error := BadCommand
      state := emitting
    }.elsewhen(io.line.bits.length <= commandLength ||
      io.line.bits.data(commandLength.pad(6)) =/= ' '.toInt.U) {
      result.error := BadArgument
      state := emitting
    }.otherwise {
      charIndex := commandLength + 1.U
      tokenNumber := 1.U
      expectedArgs := Mux(isHello || isGet, 1.U, Mux(isNew, 4.U, 3.U))
      digits := 0.U
      value := 0.U
      tokenBad := false.B
      state := reading
    }
  }

  private val currentChar = line.data(charIndex)
  private val isDecimal = currentChar >= '0'.toInt.U && currentChar <= '9'.toInt.U
  private val isHexAlpha = currentChar >= 'A'.toInt.U && currentChar <= 'F'.toInt.U
  private val isHex = isDecimal || isHexAlpha
  private val hexDigit = Mux(isDecimal, currentChar - '0'.toInt.U,
    currentChar - 'A'.toInt.U + 10.U)
  private val isHexToken = tokenNumber === 1.U ||
    (result.kind === NewGame && tokenNumber === 2.U)
  private val hexLengthBad = isHexToken && digits =/= 4.U
  private val tokenInvalid = digits === 0.U || tokenBad || hexLengthBad
  private val decimalDigit = currentChar - '0'.toInt.U
  private val decimalWide = Cat(0.U(1.W), value) * 10.U(4.W) + decimalDigit
  private val maxDecimal = BigInt("4294967295").U(37.W)

  when(state === reading) {
    when(charIndex >= lineLength) {
      when(tokenNumber =/= expectedArgs || tokenNumber > 4.U || tokenInvalid) {
        result.error := BadArgument
        state := emitting
      }.otherwise {
        storeCurrentArgument()
        when(tokenNumber === 1.U) {
          result.sequence := value(15, 0)
          result.sequenceValid := true.B
        }
        state := checking
      }
    }.elsewhen(currentChar === ' '.toInt.U) {
      when(tokenNumber > expectedArgs || tokenNumber > 4.U || tokenInvalid) {
        result.error := BadArgument
        state := emitting
      }.otherwise {
        storeCurrentArgument()
        when(tokenNumber === 1.U) {
          result.sequence := value(15, 0)
          result.sequenceValid := true.B
        }
        tokenNumber := tokenNumber + 1.U
        digits := 0.U
        value := 0.U
        tokenBad := false.B
        charIndex := charIndex + 1.U
      }
    }.otherwise {
      charIndex := charIndex + 1.U
      digits := digits + 1.U
      when(isHexToken) {
        when(!isHex) {
          tokenBad := true.B
        }.otherwise {
          value := Cat(value(27, 0), hexDigit(3, 0))
        }
      }.otherwise {
        when(!isDecimal || decimalWide > maxDecimal) {
          tokenBad := true.B
        }.otherwise {
          value := decimalWide(31, 0)
        }
      }
    }
  }

  when(state === checking) {
    when(result.kind === NewGame) {
      when(args(2) > ((BigInt(1) << p.presetW) - 1).U ||
        args(3) > ((BigInt(1) << p.cntW) - 1).U) {
        result.error := BadArgument
      }.otherwise {
        result.error := Ok
        result.command.kind := CmdKind.NewGame
        result.command.seed := args(1)(15, 0)
        result.command.preset := args(2)(p.presetW - 1, 0)
        result.command.mines := args(3)(p.cntW - 1, 0)
      }
      state := emitting
    }.elsewhen(result.kind === Reveal || result.kind === Flag) {
      when(args(1) > ((BigInt(1) << p.rowW) - 1).U ||
        args(2) > ((BigInt(1) << p.colW) - 1).U) {
        result.error := BadArgument
      }.elsewhen(args(1) >= io.config.rows || args(2) >= io.config.cols) {
        result.error := Range
      }.otherwise {
        result.error := Ok
        result.command.kind := Mux(result.kind === Reveal, CmdKind.Reveal, CmdKind.Flag)
        result.command.row := args(1)(p.rowW - 1, 0)
        result.command.col := args(2)(p.colW - 1, 0)
      }
      state := emitting
    }.otherwise {
      result.error := Ok
      state := emitting
    }
  }
}
