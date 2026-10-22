package minesweeper

import chisel3._
import chisel3.util._

/** Frontier flood fill, one ring per cycle (CONTRACT 5.5).
  *
  * The frontier register holds the layer being revealed this cycle. The next layer is
  * every eligible neighbour of a zero cell in the current layer. Eligible means active,
  * hidden, unflagged and not yet visited by this operation. The private visited mask
  * covers the cycle in which the Board has not committed a layer yet, so revealSet
  * depends only on registers and the Board's registered view, never on itself.
  *
  * Timing: start fires on edge 0; the clicked cell is revealSet in the next cycle; each
  * following cycle emits the next nonempty layer; the cycle after the last layer pulses
  * done with busy low and revealSet zero. cycles == layers.
  *
  * A start on a cell that is not eligible (flagged, revealed, padding, out of range)
  * reveals nothing and completes with zero layers. The Core never issues one, so this
  * is a defensive choice, not part of the contract.
  */
class RevealEngine(p: MsParams) extends Module {
  val io = IO(new RevealIO(p))

  val running = RegInit(false.B)
  val frontier = RegInit(0.U(p.cells.W))
  val visited = RegInit(0.U(p.cells.W))
  val layers = RegInit(0.U(p.cntW.W))
  val cycles = RegInit(0.U(p.cycleW.W))

  val eligible = VecInit(io.view.map(v => v.active && !v.revealed && !v.flagged)).asUInt
  val zero = VecInit(io.view.map(_.adj === 0.U)).asUInt
  val expandFrom = frontier & zero
  // Static wiring from the capacity grid; padding is removed by `eligible`.
  val reached = VecInit((0 until p.cells).map { i =>
    VecInit(p.capacityNeighbors(i).map(n => expandFrom(n))).asUInt.orR
  }).asUInt
  val next = reached & eligible & ~visited

  val finishing = running && frontier === 0.U

  io.start.ready := !running && !io.clear
  io.busy := running && frontier =/= 0.U && !io.clear
  io.done := finishing && !io.clear
  io.revealSet := Mux(running && !io.clear, frontier, 0.U)
  io.layers := layers
  io.cycles := cycles

  when(running) {
    when(frontier =/= 0.U) {
      layers := layers + 1.U
      cycles := cycles + 1.U
      visited := visited | next
      frontier := next
    }.otherwise {
      running := false.B
    }
  }

  when(io.start.fire) {
    val click = (UIntToOH(io.start.bits.idx, 1 << p.idxW) & eligible.pad(1 << p.idxW))(p.cells - 1, 0)
    running := true.B
    frontier := click
    visited := click
    layers := 0.U
    cycles := 0.U
  }

  when(io.clear) {
    running := false.B
    frontier := 0.U
    visited := 0.U
    layers := 0.U
    cycles := 0.U
  }
}
