package minesweeper

import chisel3._
import chisel3.util.{PopCount, UIntToOH}

/** Cell storage. Core owns command validation, game state and configuration changes. */
class Board(p: MsParams) extends Module {
  val io = IO(new BoardIO(p))

  private val mines = RegInit(0.U(p.cells.W))
  private val revealed = RegInit(0.U(p.cells.W))
  private val flags = RegInit(0.U(p.cells.W))
  private val active = VecInit((0 until p.cells).map { idx =>
    (idx / p.maxCols).U < io.config.rows && (idx % p.maxCols).U < io.config.cols
  })
  private val activeMask = active.asUInt
  private val activeMines = mines & activeMask
  private val activeRevealed = revealed & activeMask
  private val activeFlags = flags & activeMask

  io.flagCount := PopCount(activeFlags)
  io.revealedSafeCount := PopCount(activeRevealed & ~activeMines)

  for (idx <- 0 until p.cells) {
    val mine = activeMines(idx)
    val seen = activeRevealed(idx)
    val flagged = activeFlags(idx)
    // Static capacity neighbours are filtered by the active mask, avoiding row wrap.
    val adj = Mux(active(idx), PopCount(p.capacityNeighbors(idx).map(activeMines(_))), 0.U(4.W))

    io.view(idx).active := active(idx)
    io.view(idx).mine := mine
    io.view(idx).revealed := seen
    io.view(idx).flagged := flagged
    io.view(idx).adj := adj

    io.revealView(idx).active := active(idx)
    io.revealView(idx).revealed := seen
    io.revealView(idx).flagged := flagged
    io.revealView(idx).adj := adj

    // A revealed mine after loss is not a numbered clue. Flags are not solver inputs.
    io.solverView(idx).revealed := seen && !mine
    io.solverView(idx).adj := Mux(seen && !mine, adj, 0.U)
  }

  when(!io.clear && !reset.asBool) {
    assert(PopCount(Seq(io.loadMines.valid, io.revealSet.orR,
      io.revealAllMines, io.flagToggle.valid)) <= 1.U,
      "Core must issue at most one Board write per clock")
  }

  when(io.clear) {
    mines := 0.U
    revealed := 0.U
    flags := 0.U
  }.elsewhen(io.loadMines.valid) {
    // First-click generation must preserve flags already placed by the player.
    mines := io.loadMines.bits & activeMask
  }.elsewhen(io.revealSet.orR) {
    revealed := (revealed | (io.revealSet & ~flags)) & activeMask
  }.elsewhen(io.revealAllMines) {
    revealed := (revealed | activeMines) & activeMask
    flags := activeFlags & ~activeMines
  }.elsewhen(io.flagToggle.valid) {
    // Out-of-capacity indices produce a zero one-hot mask and cannot alias a cell.
    val toggle = UIntToOH(io.flagToggle.bits, p.cells) & activeMask & ~revealed
    flags := activeFlags ^ toggle
  }
}
