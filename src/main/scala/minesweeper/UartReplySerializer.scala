package minesweeper

import chisel3._
import chisel3.util._

class UartReplyStart extends Bundle {
  val hello = Bool()
  val sequence = UInt(16.W)
}

/** Stable, public-only view supplied by Core and held through the complete reply. */
class UartPublicSnapshot(p: MsParams) extends Bundle {
  val config = new ActiveConfig(p)
  val state = GameState()
  val cells = Vec(p.cells, new PublicCellView)
  val hints = Vec(p.cells, new SolverHint)
  val hintsValid = Bool()
  val minesLeft = SInt(p.minesLeftW.W)
  val revision = UInt(p.revisionW.W)
  val generationCycles = UInt(p.cycleW.W)
  val revealCycles = UInt(p.cycleW.W)
  val revealLayers = UInt(p.cntW.W)
  val solverCycles = UInt(p.cycleW.W)
}

class UartReplySerializerIO(p: MsParams) extends Bundle {
  val start = Flipped(Decoupled(new UartReplyStart))
  val snapshot = Input(new UartPublicSnapshot(p))
  val tx = Decoupled(UInt(8.W))
  val busy = Output(Bool())
  val done = Output(Bool())
}

/** Serializes HELLO and stable public snapshots into LF-terminated ASCII lines. The caller holds the snapshot stable until done. */
class UartReplySerializer(p: MsParams) extends Module {
  private val maxLineBytes = 192
  private val idle = 0.U(3.W)
  private val info = 1.U(3.W)
  private val preset = 2.U(3.W)
  private val snap = 3.U(3.W)
  private val row = 4.U(3.W)
  private val hint = 5.U(3.W)
  private val end = 6.U(3.W)

  val io = IO(new UartReplySerializerIO(p))
  private val active = RegInit(false.B)
  private val phase = RegInit(idle)
  private val sequence = Reg(UInt(16.W))
  private val presetIndex = RegInit(0.U(p.presetW.W))
  private val rowIndex = RegInit(0.U(p.rowsW.W))
  private val charIndex = RegInit(0.U(8.W))
  private val donePulse = RegInit(false.B)

  io.start.ready := !active
  io.busy := active
  io.done := donePulse
  io.tx.valid := false.B
  io.tx.bits := 0.U
  donePulse := false.B

  private val line = Wire(Vec(maxLineBytes, UInt(8.W)))
  private val lineLength = Wire(UInt(8.W))
  line.foreach(_ := 0.U)
  lineLength := 0.U

  private def lit(s: String): Seq[UInt] = s.map(_.toInt.U(8.W))
  private def chr(c: Char): UInt = c.toInt.U(8.W)
  private def dec(value: UInt, digits: Int): Seq[UInt] = (0 until digits).map { i =>
    val divisor = BigInt(10).pow(digits - i - 1)
    (((value / divisor.U) % 10.U).pad(8) + chr('0'))(7, 0)
  }
  private def signedDec(value: SInt, digits: Int): Seq[UInt] = {
    val negative = value < 0.S
    val magnitude = Mux(negative, (-value).asUInt, value.asUInt)
    Seq(Mux(negative, '-'.toInt.U(8.W), '0'.toInt.U(8.W))) ++ dec(magnitude, digits)
  }
  private def hex16(value: UInt): Seq[UInt] = (3 to 0 by -1).map { i =>
    val nibble = value(4 * i + 3, 4 * i)
    Mux(nibble < 10.U, nibble.pad(8) + chr('0'),
      nibble.pad(8) - 10.U(8.W) + chr('A'))(7, 0)
  }
  private def lf(bytes: Seq[UInt]): Seq[UInt] = bytes :+ '\n'.toInt.U(8.W)
  private def assign(bytes: Seq[UInt]): Unit = {
    require(bytes.length <= maxLineBytes, "UART reply line exceeds serializer capacity")
    lineLength := bytes.length.U
    bytes.zipWithIndex.foreach { case (b, i) => line(i) := b }
  }
  private def presetValue(values: GridPreset => Int): UInt = MuxLookup(
    presetIndex, 0.U(p.cntW.W))(p.presets.map(g => g.id.U -> values(g).U(p.cntW.W)))
  private def cellAtCurrentRow(col: Int): PublicCellView = MuxLookup(
    rowIndex, io.snapshot.cells(col))(0.until(p.maxRows).map { r =>
      r.U -> io.snapshot.cells(r * p.maxCols + col)
    })
  private def hintAtCurrentRow(col: Int): SolverHint = MuxLookup(
    rowIndex, io.snapshot.hints(col))(0.until(p.maxRows).map { r =>
      r.U -> io.snapshot.hints(r * p.maxCols + col)
    })

  when(io.start.fire) {
    active := true.B
    sequence := io.start.bits.sequence
    presetIndex := 0.U
    rowIndex := 0.U
    charIndex := 0.U
    phase := Mux(io.start.bits.hello, info, snap)
  }

  when(phase === info) {
    assign(lf(lit("INFO ") ++ hex16(sequence) ++ lit(" 2 1.2.0 ") ++
      dec(p.maxCols.U, 2) ++ lit(" ") ++ dec(p.maxRows.U, 2) ++ lit(" ") ++
      dec(p.minMines.U, 3) ++ lit(" V1 1")))
  }.elsewhen(phase === preset) {
    val cols = presetValue(_.cols)
    val rows = presetValue(_.rows)
    val maxMines = presetValue(g => p.maxMines(g))
    assign(lf(lit("PRESET ") ++ hex16(sequence) ++ lit(" ") ++
      dec(presetIndex, 1) ++ lit(" ") ++ dec(cols, 2) ++ lit(" ") ++
      dec(rows, 2) ++ lit(" ") ++ dec(p.minMines.U, 3) ++ lit(" ") ++
      dec(maxMines, 3)))
  }.elsewhen(phase === snap) {
    val common = lit("SNAP ") ++ hex16(sequence) ++ lit(" ") ++
      dec(io.snapshot.revision, 10) ++ lit(" ")
    val suffix = lit(" ") ++ dec(io.snapshot.config.preset, 1) ++ lit(" ") ++
      dec(io.snapshot.config.cols, 2) ++ lit(" ") ++ dec(io.snapshot.config.rows, 2) ++ lit(" ") ++
      dec(io.snapshot.config.mines, 3) ++ lit(" ") ++ signedDec(io.snapshot.minesLeft, 3) ++ lit(" ") ++
      Seq(Mux(io.snapshot.hintsValid, '1'.toInt.U(8.W), '0'.toInt.U(8.W))) ++ lit(" ") ++
      dec(io.snapshot.generationCycles, 10) ++ lit(" ") ++ dec(io.snapshot.revealCycles, 10) ++ lit(" ") ++
      dec(io.snapshot.revealLayers, 3) ++ lit(" ") ++ dec(io.snapshot.solverCycles, 10)
    val stateText = Seq(
      GameState.Idle -> "IDLE", GameState.Playing -> "PLAYING", GameState.Won -> "WON",
      GameState.Lost -> "LOST", GameState.Error -> "ERROR"
    )
    val variants = stateText.map { case (s, text) =>
      val bytes = lf(common ++ lit(text) ++ suffix)
      val vec = VecInit((0 until maxLineBytes).map(i => if (i < bytes.length) bytes(i) else 0.U(8.W)))
      (s.asUInt, vec, bytes.length.U(8.W))
    }
    val default = variants.head
    line := MuxLookup(io.snapshot.state.asUInt, default._2)(variants.map(v => v._1 -> v._2))
    lineLength := MuxLookup(io.snapshot.state.asUInt, default._3)(variants.map(v => v._1 -> v._3))
  }.elsewhen(phase === row) {
    val base = lit("ROW ") ++ hex16(sequence) ++ lit(" ") ++ dec(rowIndex, 2) ++ lit(" ")
    val cells = (0 until p.maxCols).flatMap { col =>
      val cell = cellAtCurrentRow(col)
      val token = Mux(!cell.active, chr('H'),
        MuxLookup(cell.kind.asUInt, chr('H'))(Seq(
          PublicCellKind.Hidden.asUInt -> chr('H'),
          PublicCellKind.Flagged.asUInt -> chr('F'),
          PublicCellKind.Number.asUInt -> (chr('0') + cell.adj.pad(8)),
          PublicCellKind.Mine.asUInt -> Mux(io.snapshot.state === GameState.Lost, chr('M'), chr('H'))
        )))
      Seq(token) ++ (if (col == p.maxCols - 1) Seq.empty else Seq(chr(' ')))
    }
    assign(lf(base ++ cells))
    // The storage row may be wider than this active preset. Move LF to the
    // end of the active cells so padding is neither transmitted nor merged
    // into the following HINT line.
    for (cols <- 1 to p.maxCols) {
      when(io.snapshot.config.cols === cols.U) {
        line(base.length + 2 * cols - 1) := '\n'.toInt.U(8.W)
      }
    }
    lineLength := (base.length.U(8.W) + io.snapshot.config.cols.pad(8) * 2.U(8.W))
  }.elsewhen(phase === hint) {
    val base = lit("HINT ") ++ hex16(sequence) ++ lit(" ") ++ dec(rowIndex, 2) ++ lit(" ")
    val marks = (0 until p.maxCols).map { col =>
      val h = hintAtCurrentRow(col)
      val cell = cellAtCurrentRow(col)
      Mux(!io.snapshot.hintsValid || !cell.active, chr('.'),
        Mux(h.safe, chr('S'), Mux(h.mine, chr('M'), chr('.'))))
    }
    assign(lf(base ++ marks))
    // HINT rows likewise omit inactive storage columns.
    for (cols <- 1 to p.maxCols) {
      when(io.snapshot.config.cols === cols.U) {
        line(base.length + cols) := '\n'.toInt.U(8.W)
      }
    }
    lineLength := (base.length.U(8.W) + io.snapshot.config.cols.pad(8) + 1.U(8.W))
  }.elsewhen(phase === end) {
    assign(lf(lit("END ") ++ hex16(sequence) ++ lit(" ") ++ dec(io.snapshot.revision, 10)))
  }

  when(active) {
    io.tx.valid := charIndex < lineLength
    io.tx.bits := line(charIndex)
  }
  when(io.tx.fire) {
    when(charIndex + 1.U === lineLength) {
      charIndex := 0.U
      when(phase === info) {
        phase := preset
      }.elsewhen(phase === preset) {
        when(presetIndex === (p.presets.size - 1).U) { phase := end }
          .otherwise { presetIndex := presetIndex + 1.U }
      }.elsewhen(phase === snap) {
        phase := row
        rowIndex := 0.U
      }.elsewhen(phase === row) {
        phase := hint
      }.elsewhen(phase === hint) {
        when(rowIndex + 1.U === io.snapshot.config.rows) { phase := end }
          .otherwise { rowIndex := rowIndex + 1.U; phase := row }
      }.elsewhen(phase === end) {
        active := false.B
        phase := idle
        donePulse := true.B
      }
    }.otherwise {
      charIndex := charIndex + 1.U
    }
  }
}
