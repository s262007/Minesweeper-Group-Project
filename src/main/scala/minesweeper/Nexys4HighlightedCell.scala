package minesweeper

import chisel3._
import chisel3.util.UIntToOH

class Nexys4HighlightedCellIO(p: MsParams) extends Bundle {
  val rows = Input(UInt(p.rowsW.W))
  val cols = Input(UInt(p.colsW.W))
  val moveUp = Input(Bool())
  val moveDown = Input(Bool())
  val moveLeft = Input(Bool())
  val moveRight = Input(Bool())
  val resetSelection = Input(Bool())
  val selectedRow = Output(UInt(p.rowW.W))
  val selectedCol = Output(UInt(p.colW.W))
  val selectedIndex = Output(UInt(p.idxW.W))
  val highlightMask = Output(UInt(p.cells.W))
}

class Nexys4HighlightedCell(p: MsParams) extends Module {
  val io = IO(new Nexys4HighlightedCellIO(p))

  private val selectedRow = RegInit(0.U(p.rowW.W))
  private val selectedCol = RegInit(0.U(p.colW.W))
  private val visibleRow = Mux(io.rows =/= 0.U && selectedRow >= io.rows,
    io.rows - 1.U, selectedRow)
  private val visibleCol = Mux(io.cols =/= 0.U && selectedCol >= io.cols,
    io.cols - 1.U, selectedCol)
  private val selectedIndex = visibleRow * p.maxCols.U + visibleCol

  when(io.resetSelection || io.rows === 0.U || io.cols === 0.U) {
    selectedRow := 0.U
    selectedCol := 0.U
  }.otherwise {
    when(selectedRow >= io.rows) {
      selectedRow := io.rows - 1.U
    }.elsewhen(io.moveUp && !io.moveDown && selectedRow =/= 0.U) {
      selectedRow := selectedRow - 1.U
    }.elsewhen(io.moveDown && !io.moveUp && selectedRow +& 1.U < io.rows) {
      selectedRow := selectedRow + 1.U
    }

    when(selectedCol >= io.cols) {
      selectedCol := io.cols - 1.U
    }.elsewhen(io.moveLeft && !io.moveRight && selectedCol =/= 0.U) {
      selectedCol := selectedCol - 1.U
    }.elsewhen(io.moveRight && !io.moveLeft && selectedCol +& 1.U < io.cols) {
      selectedCol := selectedCol + 1.U
    }
  }

  io.selectedRow := visibleRow
  io.selectedCol := visibleCol
  io.selectedIndex := selectedIndex
  io.highlightMask := Mux(io.rows =/= 0.U && io.cols =/= 0.U,
    UIntToOH(selectedIndex, p.cells), 0.U(p.cells.W))
}
