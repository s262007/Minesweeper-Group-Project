package minesweeper

import chisel3._
import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec

/** Contract tests only: no game, UART engine or solver implementation is claimed. */
class ContractTest extends AnyFlatSpec {
  private val p = MsParams()

  "Runtime settings" should "offer exactly the five agreed column-by-row presets" in {
    assert(p.presets.map(g => (g.id, g.cols, g.rows)) ==
      Vector((0, 8, 8), (1, 9, 9), (2, 16, 16), (3, 30, 16), (4, 30, 24)))
    assert(p.cells == 720)
    assert(p.minMines == 14)
    assert(p.defaultSettings == GameSettings(1, 14, 1))
  }

  it should "accept both mine-count boundaries and reject values outside them" in {
    val expectedMax = Vector(55, 72, 247, 471, 711)
    for ((g, max) <- p.presets.zip(expectedMax)) {
      assert(p.maxMines(g) == max)
      assert(p.validate(GameSettings(g.id, 14, 1)).isRight)
      assert(p.validate(GameSettings(g.id, max, 65535)).isRight)
      assert(p.validate(GameSettings(g.id, 13, 1)) == Left(ConfigError.BadMines))
      assert(p.validate(GameSettings(g.id, max + 1, 1)) == Left(ConfigError.BadMines))
    }
  }

  it should "reject unsupported presets and invalid seeds without truncation" in {
    assert(p.validate(GameSettings(-1, 14, 1)) == Left(ConfigError.BadPreset))
    assert(p.validate(GameSettings(5, 14, 1)) == Left(ConfigError.BadPreset))
    for (seed <- Seq(-1, 0, 65536)) {
      assert(p.validate(GameSettings(1, 14, seed)) == Left(ConfigError.BadSeed))
    }
    assert(p.validate(GameSettings(1, Int.MaxValue, 1)) == Left(ConfigError.BadMines))
  }

  it should "derive widths for capacity rather than the smallest active game" in {
    assert(p.idxW == 10 && p.cntW == 10)
    assert(p.rowW == 5 && p.colW == 5)
    assert(p.rowsW == 5 && p.colsW == 5)
    assert(p.presetW == 3 && p.minesLeftW == 11)
    assert((BigInt(1) << p.cntW) > p.cells)
  }

  it should "validate the generation-time capacity and default settings" in {
    intercept[IllegalArgumentException] { MsParams(maxCols = 16) }
    intercept[IllegalArgumentException] { MsParams(minMines = 13) }
    intercept[IllegalArgumentException] { MsParams(defaultMines = 13) }
    intercept[IllegalArgumentException] {
      MsParams(presets = Vector(GridPreset(0, 8, 8), GridPreset(0, 9, 9)))
    }
    val small = MsParams.forVerification(cols = 4, rows = 4)
    assert(small.cells == 16 && small.minMines == 0)
    assert(small.validate(GameSettings(0, 7, 1)).isRight)
    assert(small.validate(GameSettings(0, 8, 1)) == Left(ConfigError.BadMines))
  }

  "Storage indexing" should "keep a fixed physical stride and exclude padding" in {
    val g = p.presets.head
    assert(p.index(1, 0) == 30)
    assert(p.neighborIdx(p.index(0, 7), g).toSet == Set(6, 36, 37))
    assert(!p.isActive(p.index(0, 8), g))
    assert(p.activeIndices(g).size == 64)
    assert(p.activeMask(g).bitCount == 64)
    intercept[IllegalArgumentException] { p.neighborIdx(p.index(0, 8), g) }
  }

  it should "match independent coordinate enumeration on every active cell" in {
    for (g <- p.presets; row <- 0 until g.rows; col <- 0 until g.cols) {
      val expected = (for {
        rr <- 0 until g.rows
        cc <- 0 until g.cols
        if math.abs(rr - row) <= 1 && math.abs(cc - col) <= 1
        if rr != row || cc != col
      } yield rr * p.maxCols + cc).toSet
      val idx = row * p.maxCols + col
      assert(p.neighborIdx(idx, g).toSet == expected)
      assert(p.safeZone(idx, g) == expected + idx)
    }
  }

  "Density conversion" should "round down and still enforce the runtime limits" in {
    assert(p.fromDensity(2, 0.20, 1).mines == 51)
    for (d <- Seq(Double.NaN, Double.PositiveInfinity, -0.1, 1.1, 0.0, 1.0)) {
      intercept[IllegalArgumentException] { p.fromDensity(0, d, 1) }
    }
  }

  "LFSR16" should "visit every nonzero 16-bit state before repeating" in {
    val seen = scala.collection.mutable.Set.empty[Int]
    var state = 1
    for (_ <- 0 until Lfsr16.period) {
      assert(state != 0 && !seen(state))
      seen += state
      state = Lfsr16.next(state)
    }
    assert(seen.size == 65535 && state == 1)
    intercept[IllegalArgumentException] { Lfsr16.next(0) }
  }

  it should "place exact legal mine counts within the active grid and outside the safe zone" in {
    for (g <- p.presets; mines <- Seq(14, p.maxMines(g)); seed <- Seq(1, 65535);
         (row, col) <- Seq((0, 0), (g.rows / 2, g.cols / 2))) {
      val settings = GameSettings(g.id, mines, seed)
      val layout = Lfsr16.placeMines(p, settings, row, col)
      assert(layout.size == mines)
      assert(layout.forall { idx =>
        val rr = idx / p.maxCols
        val cc = idx % p.maxCols
        rr < g.rows && cc < g.cols &&
          (math.abs(rr - row) > 1 || math.abs(cc - col) > 1)
      })
      assert(Lfsr16.placeMines(p, settings, row, col) == layout)
    }
  }

  "UART configuration" should "require an explicit clock and validate sampling accuracy" in {
    val u = UartConfig(clockHz = 100000000L)
    assert(u.baud == 115200 && u.rxFifoDepth == 128)
    assert(u.sampleDivisor == 54)
    assert(u.relativeBaudError < 0.02)
    intercept[IllegalArgumentException] { UartConfig(clockHz = 0L) }
    intercept[IllegalArgumentException] { UartConfig(clockHz = 100000000L, rxFifoDepth = 32) }
  }

  "Shared interfaces" should "elaborate with the Chisel 6 compiler plugin" in {
    for (params <- Seq(p, MsParams.forVerification(4, 4))) {
      val text = ChiselStage.emitCHIRRTL(new ContractInterfaceProbe(params))
      assert(text.contains("module ContractInterfaceProbe"))
      assert(text.contains("core_cmd_bits_mines") || text.contains("core :"))
      assert(!text.contains("testLoad"))
    }
  }
}

/** Elaborates all production interfaces and ties every output, without game logic. */
private class ContractInterfaceProbe(p: MsParams) extends Module {
  val core = IO(new CoreIO(p))
  core.cmd.ready := false.B
  core.result.valid := false.B
  core.result.bits := 0.U.asTypeOf(new CommandResult(p))
  core.config := 0.U.asTypeOf(new ActiveConfig(p))
  core.state := GameState.Idle
  core.busy := false.B
  core.settled := true.B
  core.cells := 0.U.asTypeOf(core.cells)
  core.hints := 0.U.asTypeOf(core.hints)
  core.hintsValid := false.B
  core.minesLeft := 0.S
  core.exploded.valid := false.B
  core.exploded.bits := 0.U
  core.revision := 0.U
  core.generationCycles := 0.U
  core.revealCycles := 0.U
  core.revealLayers := 0.U
  core.solverCycles := 0.U

  val gen = IO(new MineGenIO(p))
  gen.start.ready := false.B
  gen.busy := false.B
  gen.done := false.B
  gen.mines := 0.U
  gen.cycles := 0.U

  val board = IO(new BoardIO(p))
  board.view := 0.U.asTypeOf(board.view)
  board.revealView := 0.U.asTypeOf(board.revealView)
  board.solverView := 0.U.asTypeOf(board.solverView)
  board.flagCount := 0.U
  board.revealedSafeCount := 0.U

  val reveal = IO(new RevealIO(p))
  reveal.start.ready := false.B
  reveal.revealSet := 0.U
  reveal.busy := false.B
  reveal.done := false.B
  reveal.cycles := 0.U
  reveal.layers := 0.U

  val solver = IO(new SolverIO(p))
  solver.start.ready := false.B
  solver.hints := 0.U.asTypeOf(solver.hints)
  solver.valid := false.B
  solver.busy := false.B
  solver.done := false.B
  solver.contradiction := false.B
  solver.cycles := 0.U

  val rx = IO(new UartRxIO)
  rx.out.valid := false.B
  rx.out.bits := 0.U
  rx.framingError := false.B
  rx.overflow := false.B
  val tx = IO(new UartTxIO)
  tx.in.ready := false.B
  tx.tx := true.B
  tx.busy := false.B

  val bridge = IO(new UartGameBridgeIO(p))
  bridge.rx.ready := false.B
  bridge.tx.valid := false.B
  bridge.tx.bits := 0.U
  bridge.core.cmd.valid := false.B
  bridge.core.cmd.bits := 0.U.asTypeOf(new CmdBundle(p))
  bridge.core.result.ready := false.B
}
