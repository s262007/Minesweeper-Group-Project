package minesweeper

import chisel3._
import chisel3.util.{Cat, MuxLookup, UIntToOH}

class MineGen(p: MsParams) extends Module {
  val io = IO(new MineGenIO(p))

  private val busy = RegInit(false.B)
  private val done = RegInit(false.B)
  private val config = RegInit(0.U.asTypeOf(new MineGenConfig(p)))
  private val state = RegInit(1.U(16.W))
  private val safeRow = RegInit(0.U(p.rowW.W))
  private val safeCol = RegInit(0.U(p.colW.W))
  private val placed = RegInit(0.U(p.cntW.W))
  private val maskBits = RegInit(VecInit(Seq.fill(p.cells)(false.B)))
  private val mask = maskBits.asUInt
  private val pipeValid = RegInit(false.B)
  private val pipeIdx = RegInit(0.U(p.idxW.W))
  private val pipeProtected = RegInit(false.B)
  private val compactValid = RegInit(false.B)
  private val compactIdx = RegInit(0.U(p.idxW.W))
  private val moduloValid = RegInit(VecInit(Seq.fill(4)(false.B)))
  private val moduloRemainder = RegInit(VecInit(Seq.fill(4)(0.U(p.idxW.W))))
  private val moduloTail0 = RegInit(0.U(12.W))
  private val moduloTail1 = RegInit(0.U(8.W))
  private val moduloTail2 = RegInit(0.U(4.W))
  private val decodeValid = RegInit(false.B)
  private val decodeRowReg = RegInit(0.U(p.rowW.W))
  private val decodeColReg = RegInit(0.U(p.colW.W))
  private val decodeProtectedReg = RegInit(false.B)
  private val cycles = RegInit(0.U(p.cycleW.W))

  private val candidateIdx = WireDefault(0.U(p.idxW.W))
  private val protectedCell = WireDefault(false.B)
  private val compactCandidate = WireDefault(0.U(p.idxW.W))
  private val decodedIdx = WireDefault(0.U(p.idxW.W))
  private val decodedRow = WireDefault(0.U(p.rowW.W))
  private val decodedCol = WireDefault(0.U(p.colW.W))
  private val decodedProtected = WireDefault(false.B)
  private val candidateModulus = MuxLookup(config.preset, 1.U(p.cntW.W))(
    p.presets.map(g => g.id.U -> g.cells.U(p.cntW.W)))

  private def moduloDigitContribution(digit: UInt, place: Int): UInt = {
    val contribution = WireDefault(0.U(p.idxW.W))
    for (g <- p.presets) {
      val weight = BigInt(16).pow(place) % BigInt(g.cells)
      val table = VecInit((0 until 16).map { value =>
        ((BigInt(value) * weight % BigInt(g.cells)).toInt).U(p.idxW.W)
      })
      when(config.preset === g.id.U) {
        contribution := table(digit)
      }
    }
    contribution
  }

  private def addModuloDigit(remainder: UInt, digit: UInt, place: Int): UInt = {
    val contribution = moduloDigitContribution(digit, place)
    val sum = remainder +& contribution
    val reduced = Mux(sum >= candidateModulus, sum - candidateModulus, sum)
    reduced(p.idxW - 1, 0)
  }

  if (p.pipelineMineGen) {
    if (!p.pipelineMineGenModulo) {
      for (g <- p.presets) {
        val compact = (state - 1.U) % g.cells.U
        when(config.preset === g.id.U) {
          compactCandidate := compact
        }
      }
    }
    for (g <- p.presets) {
      val row = compactIdx / g.cols.U
      val col = compactIdx % g.cols.U
      when(config.preset === g.id.U) {
        if (p.pipelineMineGenDecode) {
          decodedRow := row
          decodedCol := col
        } else {
          decodedIdx := row * p.maxCols.U + col
        }
        decodedProtected := row +& 1.U >= safeRow && row <= safeRow +& 1.U &&
          col +& 1.U >= safeCol && col <= safeCol +& 1.U
      }
    }
  } else {
    for (g <- p.presets) {
      val compact = (state - 1.U) % g.cells.U
      val row = compact / g.cols.U
      val col = compact % g.cols.U
      when(config.preset === g.id.U) {
        candidateIdx := row * p.maxCols.U + col
        protectedCell := row +& 1.U >= safeRow && row <= safeRow +& 1.U &&
          col +& 1.U >= safeCol && col <= safeCol +& 1.U
      }
    }
  }
  private val candidateBit = UIntToOH(pipeIdx, p.cells)
  private val accept = pipeValid && !pipeProtected && !(candidateBit & mask).orR

  io.start.ready := !busy && !done && !io.clear && !reset.asBool
  io.busy := busy
  io.done := done
  io.mines := mask
  io.cycles := cycles
  io.placedValid := busy && accept
  io.placedIdx := pipeIdx

  done := false.B
  when(io.clear) {
    busy := false.B
    done := false.B
    config := 0.U.asTypeOf(config)
    state := 1.U
    safeRow := 0.U
    safeCol := 0.U
    placed := 0.U
    maskBits.foreach(_ := false.B)
    pipeValid := false.B
    compactValid := false.B
    moduloValid.foreach(_ := false.B)
    decodeValid := false.B
    cycles := 0.U
  }.elsewhen(io.start.fire) {
    val request = io.start.bits
    val matchingPreset = p.presets.map { g =>
      request.config.preset === g.id.U && request.config.mines >= p.minMines.U &&
        request.config.mines <= p.maxMines(g).U
    }.reduce(_ || _)
    val activeSafeCoordinate = p.presets.map { g =>
      if (p.directSafeCoordinates) {
        request.config.preset === g.id.U &&
          request.safeRow < g.rows.U && request.safeCol < g.cols.U
      } else {
        request.config.preset === g.id.U &&
          request.safeIdx / p.maxCols.U < g.rows.U &&
          request.safeIdx % p.maxCols.U < g.cols.U
      }
    }.reduce(_ || _)
    assert(matchingPreset, "MineGen needs a validated preset and mine count")
    assert(request.seed =/= 0.U, "MineGen seed must be nonzero")
    assert(activeSafeCoordinate,
      "MineGen safe index must be active")

    config := request.config
    state := request.seed
    if (p.directSafeCoordinates) {
      safeRow := request.safeRow
      safeCol := request.safeCol
    } else {
      safeRow := request.safeIdx / p.maxCols.U
      safeCol := request.safeIdx % p.maxCols.U
    }
    placed := 0.U
    maskBits.foreach(_ := false.B)
    pipeValid := false.B
    compactValid := false.B
    moduloValid.foreach(_ := false.B)
    decodeValid := false.B
    cycles := 0.U
    busy := request.config.mines =/= 0.U
    done := request.config.mines === 0.U
  }.elsewhen(busy) {
    assert(cycles < 65535.U, "MineGen exceeded one complete LFSR period")
    if (p.pipelineMineGen) {
      when(pipeValid) { cycles := cycles + 1.U }
    }
    when(accept) {
      for (i <- 0 until p.cells) {
        when(candidateBit(i)) { maskBits(i) := true.B }
      }
      placed := placed + 1.U
      when(placed +& 1.U === config.mines) {
        busy := false.B
        done := true.B
        pipeValid := false.B
        compactValid := false.B
        moduloValid.foreach(_ := false.B)
        decodeValid := false.B
      }
    }
    when(busy && !(accept && placed +& 1.U === config.mines)) {
      if (p.pipelineMineGen) {
        if (p.pipelineMineGenDecode) {
          pipeIdx := decodeRowReg * p.maxCols.U + decodeColReg
          pipeProtected := decodeProtectedReg
          pipeValid := decodeValid
          decodeRowReg := decodedRow
          decodeColReg := decodedCol
          decodeProtectedReg := decodedProtected
          decodeValid := compactValid
        } else {
          pipeIdx := decodedIdx
          pipeProtected := decodedProtected
          pipeValid := compactValid
        }
        if (p.pipelineMineGenModulo) {
          val candidate = state - 1.U
          moduloRemainder(0) := moduloDigitContribution(candidate(15, 12), 3)
          moduloTail0 := candidate(11, 0)
          moduloValid(0) := true.B

          moduloRemainder(1) := addModuloDigit(moduloRemainder(0), moduloTail0(11, 8), 2)
          moduloTail1 := moduloTail0(7, 0)
          moduloValid(1) := moduloValid(0)

          moduloRemainder(2) := addModuloDigit(moduloRemainder(1), moduloTail1(7, 4), 1)
          moduloTail2 := moduloTail1(3, 0)
          moduloValid(2) := moduloValid(1)

          moduloRemainder(3) := addModuloDigit(moduloRemainder(2), moduloTail2(3, 0), 0)
          moduloValid(3) := moduloValid(2)

          compactIdx := moduloRemainder(3)
          compactValid := moduloValid(3)
        } else {
          compactIdx := compactCandidate
          compactValid := true.B
        }
      } else {
        pipeIdx := candidateIdx
        pipeProtected := protectedCell
        pipeValid := true.B
        cycles := cycles + 1.U
      }
      state := Cat(0.U(1.W), state(15, 1)) ^ Mux(state(0), "hb400".U(16.W), 0.U(16.W))
    }
  }
}
