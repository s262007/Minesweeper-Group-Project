package minesweeper

import chisel3._
import chisel3.util.{Cat, UIntToOH}

/** One LFSR candidate per clock, using the sequence specified in CONTRACT.md. */
class MineGen(p: MsParams) extends Module {
  val io = IO(new MineGenIO(p))

  private val busy = RegInit(false.B)
  private val done = RegInit(false.B)
  private val config = RegInit(0.U.asTypeOf(new ActiveConfig(p)))
  private val state = RegInit(1.U(16.W))
  private val safeRow = RegInit(0.U(p.rowW.W))
  private val safeCol = RegInit(0.U(p.colW.W))
  private val placed = RegInit(0.U(p.cntW.W))
  private val mask = RegInit(0.U(p.cells.W))
  private val cycles = RegInit(0.U(p.cycleW.W))

  // Each supported preset uses constant divisors, rather than a runtime divider.
  // Compact candidate coordinates are converted back to the capacity stride.
  private val candidateIdx = WireDefault(0.U(p.idxW.W))
  private val protectedCell = WireDefault(false.B)
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
  private val candidateBit = UIntToOH(candidateIdx, p.cells)
  private val accept = !protectedCell && !mask(candidateIdx)

  io.start.ready := !busy && !done && !io.clear && !reset.asBool
  io.busy := busy
  io.done := done
  io.mines := mask
  io.cycles := cycles

  done := false.B
  when(io.clear) {
    busy := false.B
    done := false.B
    config := 0.U.asTypeOf(config)
    state := 1.U
    safeRow := 0.U
    safeCol := 0.U
    placed := 0.U
    mask := 0.U
    cycles := 0.U
  }.elsewhen(io.start.fire) {
    // Core validates external commands. These assertions catch invalid internal requests.
    val request = io.start.bits
    val matchingPreset = p.presets.map { g =>
      request.config.preset === g.id.U && request.config.cols === g.cols.U &&
        request.config.rows === g.rows.U && request.config.mines >= p.minMines.U &&
        request.config.mines <= p.maxMines(g).U
    }.reduce(_ || _)
    assert(matchingPreset, "MineGen needs a validated preset and mine count")
    assert(request.seed =/= 0.U, "MineGen seed must be nonzero")
    assert(request.safeIdx < p.cells.U &&
      request.safeIdx / p.maxCols.U < request.config.rows &&
      request.safeIdx % p.maxCols.U < request.config.cols,
      "MineGen safe index must be active")

    config := request.config
    state := request.seed
    safeRow := request.safeIdx / p.maxCols.U
    safeCol := request.safeIdx % p.maxCols.U
    placed := 0.U
    mask := 0.U
    cycles := 0.U
    busy := request.config.mines =/= 0.U
    // Only verification configurations allow zero mines; no candidates are needed.
    done := request.config.mines === 0.U
  }.elsewhen(busy) {
    assert(cycles < 65535.U, "MineGen exceeded one complete LFSR period")
    state := Cat(0.U(1.W), state(15, 1)) ^ Mux(state(0), "hb400".U(16.W), 0.U(16.W))
    cycles := cycles + 1.U
    when(accept) {
      mask := mask | candidateBit
      placed := placed + 1.U
      when(placed +& 1.U === config.mines) {
        busy := false.B
        done := true.B
      }
    }
  }
}
