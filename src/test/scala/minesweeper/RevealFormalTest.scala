package minesweeper

import chisel3._
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec

/** Safety harness for Board + RevealEngine (CONTRACT 5.3, 5.5, 7).
  *
  * The prover picks any preset and any mine layout (loaded once, right after reset), toggles
  * flags whenever no reveal runs, and clicks whenever it likes, but only on a hidden,
  * unflagged, active, safe cell: the Core handles mine clicks itself. Then no cascade may
  * reveal a mine or a flagged cell, a revealed cell never hides again, and every operation
  * ends within cells + 1 cycles.
  */
class RevealSafety(p: MsParams) extends Module {
  val layout = IO(Input(Vec(p.cells, Bool())))
  val preset = IO(Input(UInt(p.presetW.W)))
  val start = IO(Input(Bool()))
  val startIdx = IO(Input(UInt(p.idxW.W)))
  val flag = IO(Input(Bool()))
  val flagIdx = IO(Input(UInt(p.idxW.W)))

  val board = Module(new Board(p))
  val reveal = Module(new RevealEngine(p))
  val (rows, cols) = FormalBoards.dims(p, preset)
  val cfg = Wire(new ActiveConfig(p))
  cfg.preset := preset
  cfg.rows := rows
  cfg.cols := cols
  cfg.mines := 0.U

  val loaded = RegInit(false.B)
  loaded := true.B
  val idle = reveal.io.start.ready

  // At most one Board write per clock, as the Core guarantees: load, then reveals or flags.
  board.io.config := cfg
  board.io.clear := false.B
  board.io.loadMines.valid := !loaded
  board.io.loadMines.bits := layout.asUInt
  board.io.revealSet := reveal.io.revealSet
  board.io.revealAllMines := false.B
  board.io.flagToggle.valid := loaded && idle && flag && !start
  board.io.flagToggle.bits := flagIdx
  reveal.io.clear := false.B
  reveal.io.view := board.io.revealView
  reveal.io.start.valid := loaded && start
  reveal.io.start.bits.config := cfg
  reveal.io.start.bits.idx := startIdx

  val view = board.io.view
  val safeHidden = VecInit(view.map(v => v.active && !v.revealed && !v.flagged && !v.mine))

  val started = RegInit(false.B)
  started := true.B
  val runCycles = RegInit(0.U(8.W))
  runCycles := Mux(!idle, runCycles + 1.U, 0.U)

  when(!reset.asBool) {
    assume(preset < p.presets.size.U)
    when(loaded && start) { assume(startIdx < p.cells.U && safeHidden(startIdx)) }
  }
  when(started) {
    assume(preset === past(preset))
    for (i <- 0 until p.cells) assume(layout(i) === past(layout(i)))
  }

  when(!reset.asBool) {
    for (i <- 0 until p.cells) {
      assert(!(view(i).revealed && view(i).mine), s"cell $i: the cascade revealed a mine")
      assert(!(view(i).revealed && view(i).flagged), s"cell $i: a flagged cell was revealed")
    }
    assert(runCycles <= (p.cells + 1).U, "a reveal took more than cells + 1 cycles")
  }
  when(started) {
    for (i <- 0 until p.cells) assert(view(i).revealed || !past(view(i).revealed), s"cell $i: hidden again")
  }
}

class RevealFormalTest extends AnyFlatSpec with ChiselScalatestTester with Formal {
  for ((label, p) <- FormalBoards.configs) {
    s"RevealEngine with Board on $label" should "never reveal a mine or a flag and always finish" taggedAs FormalTag in {
      // Load, one click, up to cells layers and the completion cycle.
      verify(new RevealSafety(p), Seq(BoundedCheck(p.cells + 4)))
    }
  }
}
