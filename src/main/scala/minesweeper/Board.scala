package minesweeper

import chisel3._
import chisel3.util._


class Board(p: MsParams) extends Module {
  val io = IO(new BoardIO(p))

  private val mines = RegInit(0.U(p.cells.W))
  private val revealed = RegInit(0.U(p.cells.W))
  private val flags = RegInit(0.U(p.cells.W))
  // Optional timing experiment: maintain safe reveals by row instead of reducing
  // the complete 720-bit revealed/mines vectors for the Core's win check.
  private val rowSafeRevealCountW = log2Ceil(p.maxCols + 1).max(1)
  private val rowSafeRevealCounts = RegInit(
    VecInit(Seq.fill(p.maxRows)(0.U(rowSafeRevealCountW.W))))
  private val rowFlagCountW = log2Ceil(p.maxCols + 1).max(1)
  private val rowFlagCounts = RegInit(VecInit(Seq.fill(p.maxRows)(0.U(rowFlagCountW.W))))
  private val pendingFlagRowOH = RegInit(0.U(p.maxRows.W))
  private val pendingFlagWasSet = RegInit(false.B)
  private val pendingFlagValid = RegInit(false.B)
  private val adjacency = RegInit(VecInit(Seq.fill(p.cells)(0.U(4.W))))
  private val adjacencyBusy = RegInit(false.B)
  private val scanRow = RegInit(0.U(p.rowW.W))
  private val active = VecInit((0 until p.cells).map { idx =>
    (idx / p.maxCols).U < io.config.rows && (idx % p.maxCols).U < io.config.cols
  })
  private val activeMask = active.asUInt
  private val legalFlagToggle = UIntToOH(io.flagToggle.bits, p.cells) & activeMask & ~revealed
  private def balancedSum(values: Seq[UInt]): UInt = {
    require(values.nonEmpty)
    if (values.size == 1) values.head
    else balancedSum(values.grouped(2).map { pair =>
      if (pair.size == 1) pair.head else pair.head +& pair(1)
    }.toSeq)
  }

  if (p.incrementalFlagCount) {
    val totalFlags = rowFlagCounts.reduce(_ +& _)
    val committedCount = totalFlags(p.cntW - 1, 0)
    if (p.pipelineFlagCountUpdate) {
      io.flagCount := Mux(pendingFlagValid,
        Mux(pendingFlagWasSet, committedCount - 1.U, committedCount + 1.U),
        committedCount)
    } else {
      io.flagCount := committedCount
    }
  } else {
    io.flagCount := PopCount(flags)
  }
  if (p.incrementalSafeRevealCount) {
    val totalSafeRevealed = balancedSum(rowSafeRevealCounts.toSeq)
    io.revealedSafeCount := totalSafeRevealed(p.cntW - 1, 0)
  } else {
    io.revealedSafeCount := PopCount(revealed & ~mines)
  }
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

  // Inactive cells always hold zero: every write is masked to the active grid, reset and
  // clear zero everything, and the config only changes together with clear (asserted
  // below). So the stored bits are read without masking them again, which saves one gate
  // per output bit (several thousand LUTs at 30x24).
  for (idx <- 0 until p.cells) {
    val mine = mines(idx)
    val seen = revealed(idx)
    val flagged = flags(idx)
    val adj = adjacency(idx)

    io.view(idx).active := active(idx)
    io.view(idx).mine := mine
    io.view(idx).revealed := seen
    io.view(idx).flagged := flagged
    io.view(idx).adj := adj

    io.revealView(idx).active := active(idx)
    io.revealView(idx).revealed := seen
    io.revealView(idx).flagged := flagged
    io.revealView(idx).adj := adj

    io.solverView(idx).revealed := seen && !mine
    if (p.maskSolverAdjacency) io.solverView(idx).adj := Mux(seen && !mine, adj, 0.U)
    else io.solverView(idx).adj := adj
  }

  when(!io.clear && !reset.asBool) {
    assert(PopCount(Seq(io.loadMines.valid, io.revealSet.orR,
      io.revealAllMines, io.flagToggle.valid)) <= 1.U,
      "Core must issue at most one Board write per clock")
  }

  private val previousConfig = RegNext(io.config.asUInt)
  private val clearedLastCycle = RegNext(io.clear, true.B)
  when(!reset.asBool && !io.clear && !clearedLastCycle) {
    assert(io.config.asUInt === previousConfig,
      "the active grid may only change together with clear")
  }

  when(io.clear) {
    mines := 0.U
    revealed := 0.U
    flags := 0.U
    adjacency.foreach(_ := 0.U)
    adjacencyBusy := false.B
    scanRow := 0.U
  }.elsewhen(io.loadMines.valid) {
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
    flags := flags ^ legalFlagToggle
  }

  if (p.incrementalSafeRevealCount) {
    when(io.clear) {
      rowSafeRevealCounts.foreach(_ := 0.U)
    }.elsewhen(io.loadMines.valid) {
      for (row <- 0 until p.maxRows) {
        val lo = row * p.maxCols
        val hi = lo + p.maxCols - 1
        val safeAlreadyRevealed = revealed(hi, lo) & ~io.loadMines.bits(hi, lo) &
          activeMask(hi, lo)
        rowSafeRevealCounts(row) := PopCount(safeAlreadyRevealed)
      }
    }.elsewhen(io.revealSet.orR) {
      for (row <- 0 until p.maxRows) {
        val lo = row * p.maxCols
        val hi = lo + p.maxCols - 1
        val newSafeCells = io.revealSet(hi, lo) & ~revealed(hi, lo) &
          ~flags(hi, lo) & ~mines(hi, lo) & activeMask(hi, lo)
        when(newSafeCells.orR) {
          rowSafeRevealCounts(row) := rowSafeRevealCounts(row) + PopCount(newSafeCells)
        }
      }
    }
  }

  if (p.incrementalFlagCount) {
    if (p.pipelineFlagCountUpdate) {
      when(io.clear) {
        rowFlagCounts.foreach(_ := 0.U)
        pendingFlagRowOH := 0.U
        pendingFlagWasSet := false.B
        pendingFlagValid := false.B
      }.elsewhen(io.revealAllMines) {
        for (row <- 0 until p.maxRows) {
          val lo = row * p.maxCols
          val hi = lo + p.maxCols - 1
          rowFlagCounts(row) := PopCount(flags(hi, lo) & ~mines(hi, lo))
        }
        pendingFlagRowOH := 0.U
        pendingFlagWasSet := false.B
        pendingFlagValid := false.B
      }.otherwise {
        when(pendingFlagValid) {
          for (row <- 0 until p.maxRows) {
            when(pendingFlagRowOH(row)) {
              rowFlagCounts(row) := Mux(pendingFlagWasSet,
                rowFlagCounts(row) - 1.U, rowFlagCounts(row) + 1.U)
            }
          }
        }
        pendingFlagValid := false.B
        when(io.flagToggle.valid && legalFlagToggle.orR) {
          pendingFlagRowOH := VecInit((0 until p.maxRows).map { row =>
            val lo = row * p.maxCols
            val hi = lo + p.maxCols - 1
            legalFlagToggle(hi, lo).orR
          }).asUInt
          pendingFlagWasSet := (flags & legalFlagToggle).orR
          pendingFlagValid := true.B
        }
      }
    } else {
      when(io.clear) {
        rowFlagCounts.foreach(_ := 0.U)
      }.elsewhen(io.revealAllMines) {
        for (row <- 0 until p.maxRows) {
          val lo = row * p.maxCols
          val hi = lo + p.maxCols - 1
          rowFlagCounts(row) := PopCount(flags(hi, lo) & ~mines(hi, lo))
        }
      }.elsewhen(io.flagToggle.valid && legalFlagToggle.orR) {
        for (row <- 0 until p.maxRows) {
          val lo = row * p.maxCols
          val hi = lo + p.maxCols - 1
          val rowToggle = legalFlagToggle(hi, lo)
          val wasFlagged = (flags(hi, lo) & rowToggle).orR
          when(rowToggle.orR) {
            rowFlagCounts(row) := Mux(wasFlagged,
              rowFlagCounts(row) - 1.U, rowFlagCounts(row) + 1.U)
          }
        }
      }
    }
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
