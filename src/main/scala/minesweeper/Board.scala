package minesweeper

import chisel3._
import chisel3.util._

/** Cell storage. Core owns command validation, game state and configuration changes. */
class Board(p: MsParams) extends Module {
  val io = IO(new BoardIO(p))

  private val mines = RegInit(0.U(p.cells.W))
  private val revealed = RegInit(0.U(p.cells.W))
  private val flags = RegInit(0.U(p.cells.W))
  private val adjacency = RegInit(VecInit(Seq.fill(p.cells)(0.U(4.W))))
  private val adjacencyBusy = RegInit(false.B)
  private val scanRow = RegInit(0.U(p.rowW.W))
  private val active = VecInit((0 until p.cells).map { idx =>
    (idx / p.maxCols).U < io.config.rows && (idx % p.maxCols).U < io.config.cols
  })
  private val activeMask = active.asUInt

  io.flagCount := PopCount(flags)
  io.revealedSafeCount := PopCount(revealed & ~mines)
  io.adjacencyReady := !adjacencyBusy && !io.clear && !io.loadMines.valid

  if (p.streamAdjacency) {
    val streamedNeighbors = Wire(Vec(p.cells, Bool()))
    for (idx <- 0 until p.cells) {
      val row = idx / p.maxCols
      val col = idx % p.maxCols
      val neighborBits = for {
        dr <- -1 to 1
        dc <- -1 to 1
        if (dr != 0 || dc != 0) && row + dr >= 0 && row + dr < p.maxRows &&
          col + dc >= 0 && col + dc < p.maxCols
      } yield UIntToOH(io.placedMine.bits, p.cells)(idx + dr * p.maxCols + dc)
      streamedNeighbors(idx) := io.placedMine.valid &&
        (idx / p.maxCols).U < io.config.rows && (idx % p.maxCols).U < io.config.cols &&
        neighborBits.reduce(_ || _)
    }
    when(io.placedMine.valid && !io.clear) {
      for (idx <- 0 until p.cells) {
        when(streamedNeighbors(idx)) {
          adjacency(idx) := adjacency(idx) + 1.U
        }
      }
    }
  }

  private val mineRows = VecInit((0 until p.maxRows).map { row =>
    mines((row + 1) * p.maxCols - 1, row * p.maxCols)
  })
  private val previousRow = Mux(scanRow === 0.U, 0.U(p.rowW.W), scanRow - 1.U)
  private val nextRow = Mux(scanRow === (p.maxRows - 1).U,
    (p.maxRows - 1).U(p.rowW.W), (scanRow +& 1.U)(p.rowW - 1, 0))
  private val northMines = Mux(scanRow === 0.U, 0.U(p.maxCols.W), mineRows(previousRow))
  private val middleMines = mineRows(scanRow)
  private val southMines = Mux(scanRow === (p.maxRows - 1).U,
    0.U(p.maxCols.W), mineRows(nextRow))
  private def west(row: UInt): UInt = Cat(row(p.maxCols - 2, 0), 0.U(1.W))
  private def east(row: UInt): UInt = Cat(0.U(1.W), row(p.maxCols - 1, 1))
  private val rowAdjacency = VecInit((0 until p.maxCols).map { col =>
    PopCount(Seq(
      west(northMines)(col), northMines(col), east(northMines)(col),
      west(middleMines)(col), east(middleMines)(col),
      west(southMines)(col), southMines(col), east(southMines)(col)
    ))
  })
  private val finalScanRow = scanRow === (io.config.rows - 1.U)

  for (idx <- 0 until p.cells) {
    val mine = Mux(active(idx), mines(idx), false.B)
    val seen = Mux(active(idx), revealed(idx), false.B)
    val flagged = Mux(active(idx), flags(idx), false.B)
    val adj = Mux(active(idx), adjacency(idx), 0.U(4.W))

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
    adjacency.foreach(_ := 0.U)
    adjacencyBusy := false.B
    scanRow := 0.U
  }.elsewhen(io.loadMines.valid) {
    // First-click generation must preserve flags already placed by the player.
    mines := io.loadMines.bits & activeMask
    if (!p.streamAdjacency) adjacency.foreach(_ := 0.U)
    adjacencyBusy := !p.streamAdjacency.B
    scanRow := 0.U
  }.elsewhen(io.revealSet.orR) {
    revealed := (revealed | (io.revealSet & ~flags)) & activeMask
  }.elsewhen(io.revealAllMines) {
    revealed := (revealed | mines) & activeMask
    flags := flags & ~mines
  }.elsewhen(io.flagToggle.valid) {
    // Out-of-capacity indices produce a zero one-hot mask and cannot alias a cell.
    val toggle = UIntToOH(io.flagToggle.bits, p.cells) & activeMask & ~revealed
    flags := flags ^ toggle
  }

  when(io.beginAdjacency && !io.clear) {
    adjacency.foreach(_ := 0.U)
    adjacencyBusy := false.B
    scanRow := 0.U
  }

  if (!p.streamAdjacency) {
  when(adjacencyBusy && !io.clear && !io.loadMines.valid) {
    for (row <- 0 until p.maxRows) {
      when(scanRow === row.U && row.U < io.config.rows) {
        for (col <- 0 until p.maxCols) {
          adjacency(row * p.maxCols + col) := Mux(
            col.U < io.config.cols, rowAdjacency(col), 0.U)
        }
      }
    }
    when(finalScanRow) {
      adjacencyBusy := false.B
    }.otherwise {
      scanRow := scanRow + 1.U
    }
  }
  }
}
