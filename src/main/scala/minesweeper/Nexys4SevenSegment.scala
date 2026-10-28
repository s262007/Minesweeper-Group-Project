package minesweeper

import chisel3._
import chisel3.util.{Cat, MuxLookup, UIntToOH, log2Ceil}

class Nexys4SevenSegmentIO(p: MsParams) extends Bundle {
  val minesLeft = Input(SInt(p.minesLeftW.W))
  val segmentsN = Output(UInt(7.W))
  val decimalPointN = Output(Bool())
  val anodesN = Output(UInt(8.W))
}

class Nexys4SevenSegment(
    p: MsParams,
    clockHz: Int = 100000000,
    frameRateHz: Int = 250
) extends Module {
  require(clockHz > 0 && frameRateHz > 0, "clock and frame rates must be positive")
  require(p.cells <= 999, "three magnitude digits support capacities up to 999 cells")
  private val cyclesPerDigit = clockHz / (4 * frameRateHz)
  require(cyclesPerDigit >= 1, "display refresh rate exceeds the clock")

  val io = IO(new Nexys4SevenSegmentIO(p))

  private val scanCounterWidth = math.max(1, log2Ceil(cyclesPerDigit))
  private val scanCounter = RegInit(0.U(scanCounterWidth.W))
  private val digitIndex = RegInit(0.U(2.W))

  private val negative = io.minesLeft < 0.S
  private val magnitude = Mux(negative, (-io.minesLeft).asUInt, io.minesLeft.asUInt)
  private val ones = (magnitude % 10.U)(3, 0)
  private val tens = ((magnitude / 10.U) % 10.U)(3, 0)
  private val hundreds = (magnitude / 100.U)(3, 0)
  private val blank = 15.U(4.W)
  private val sign = Mux(negative, 10.U(4.W), blank)
  private val digitValue = MuxLookup(digitIndex, blank)(Seq(
    0.U -> ones,
    1.U -> tens,
    2.U -> hundreds,
    3.U -> sign
  ))

  private val segments = MuxLookup(digitValue, "b1111111".U(7.W))(Seq(
    0.U -> "b0000001".U(7.W),
    1.U -> "b1001111".U(7.W),
    2.U -> "b0010010".U(7.W),
    3.U -> "b0000110".U(7.W),
    4.U -> "b1001100".U(7.W),
    5.U -> "b0100100".U(7.W),
    6.U -> "b0100000".U(7.W),
    7.U -> "b0001111".U(7.W),
    8.U -> "b0000000".U(7.W),
    9.U -> "b0000100".U(7.W),
    10.U -> "b1111110".U(7.W)
  ))

  when(scanCounter === (cyclesPerDigit - 1).U) {
    scanCounter := 0.U
    digitIndex := digitIndex + 1.U
  }.otherwise {
    scanCounter := scanCounter + 1.U
  }

  io.segmentsN := segments
  io.decimalPointN := true.B
  io.anodesN := Cat("b1111".U(4.W), ~UIntToOH(digitIndex, 4))
}
