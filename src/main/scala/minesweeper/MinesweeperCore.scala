package minesweeper

import chisel3._
import chisel3.util.UIntToOH

class MinesweeperCore(p: MsParams) extends Module {
  val io = IO(new CoreIO(p))

  private val board = Module(new Board(p))
  private val mineGen = Module(new MineGen(p))

  private val phaseIdle = 0.U(2.W)
  private val phaseGenerating = 1.U(2.W)
  private val phaseRevealFirst = 2.U(2.W)
  private val phase = RegInit(phaseIdle)
  private val resultValid = RegInit(false.B)
  private val resultBits = RegInit(0.U.asTypeOf(new CommandResult(p)))

  private def defaultConfig: ActiveConfig = {
    val cfg = Wire(new ActiveConfig(p))
    val preset = p.preset(p.defaultPresetId).get
    cfg.preset := p.defaultPresetId.U
    cfg.cols := preset.cols.U
    cfg.rows := preset.rows.U
    cfg.mines := p.defaultMines.U
    cfg
  }

  private val config = RegInit(defaultConfig)
  private val gameState = RegInit(GameState.Idle)
  private val generated = RegInit(false.B)
  private val firstIdx = RegInit(0.U(p.idxW.W))
  private val revision = RegInit(0.U(p.revisionW.W))
  private val generationCycles = RegInit(0.U(p.cycleW.W))
  private val revealCycles = RegInit(0.U(p.cycleW.W))
  private val revealLayers = RegInit(0.U(p.cntW.W))
  private val solverCycles = RegInit(0.U(p.cycleW.W))
  private val explodedIdx = RegInit(0.U(p.idxW.W))
  private val seedReg = RegInit(1.U(p.seedW.W))
  private val pendingRevision = RegInit(0.U(p.revisionW.W))

  private val presetValid = WireDefault(false.B)
  private val selectedCols = WireDefault(0.U(p.colsW.W))
  private val selectedRows = WireDefault(0.U(p.rowsW.W))
  private val selectedMaxMines = WireDefault(0.U(p.cntW.W))
  for (g <- p.presets) {
    when(io.cmd.bits.preset === g.id.U) {
      presetValid := true.B
      selectedCols := g.cols.U
      selectedRows := g.rows.U
      selectedMaxMines := p.maxMines(g).U
    }
  }
  private val activeIndex = io.cmd.bits.row * p.maxCols.U + io.cmd.bits.col
  private val coordinateActive = io.cmd.bits.row < config.rows && io.cmd.bits.col < config.cols
  private val terminal = gameState === GameState.Won || gameState === GameState.Lost ||
    gameState === GameState.Error

  io.cmd.ready := phase === phaseIdle && !resultValid && !reset.asBool
  io.result.valid := resultValid
  io.result.bits := resultBits
  io.config := config
  io.state := gameState
  io.busy := phase =/= phaseIdle
  io.settled := phase === phaseIdle
  io.revision := revision
  io.generationCycles := generationCycles
  io.revealCycles := revealCycles
  io.revealLayers := revealLayers
  io.solverCycles := solverCycles
  io.exploded.valid := gameState === GameState.Lost
  io.exploded.bits := explodedIdx
  io.hintsValid := false.B
  io.hints := 0.U.asTypeOf(io.hints)

  board.io.config := config
  board.io.clear := false.B
  board.io.loadMines.valid := false.B
  board.io.loadMines.bits := 0.U
  board.io.revealSet := 0.U
  board.io.revealAllMines := false.B
  board.io.flagToggle.valid := false.B
  board.io.flagToggle.bits := 0.U

  mineGen.io.clear := false.B
  mineGen.io.start.valid := false.B
  mineGen.io.start.bits.config := config
  mineGen.io.start.bits.seed := 1.U
  mineGen.io.start.bits.safeIdx := 0.U

  private val activeCells = config.cols * config.rows
  io.minesLeft := config.mines.zext - board.io.flagCount.zext
  for (idx <- 0 until p.cells) {
    val raw = board.io.view(idx)
    io.cells(idx).active := raw.active
    io.cells(idx).kind := PublicCellKind.Hidden
    io.cells(idx).adj := 0.U
    when(raw.active && raw.flagged) { io.cells(idx).kind := PublicCellKind.Flagged }
    when(raw.active && raw.revealed && !raw.mine) {
      io.cells(idx).kind := PublicCellKind.Number
      io.cells(idx).adj := raw.adj
    }
    when(raw.active && gameState === GameState.Lost && raw.mine) {
      io.cells(idx).kind := PublicCellKind.Mine
    }
  }

  when(resultValid && io.result.ready) {
    resultValid := false.B
  }

  when(io.cmd.fire) {
    when(io.cmd.bits.kind === CmdKind.NewGame) {
      when(!presetValid) {
        resultValid := true.B
        resultBits.status := CommandStatus.BadPreset
        resultBits.changed := false.B
        resultBits.revision := revision
      }.elsewhen(io.cmd.bits.mines < p.minMines.U ||
          io.cmd.bits.mines > selectedMaxMines) {
        resultValid := true.B
        resultBits.status := CommandStatus.BadMines
        resultBits.changed := false.B
        resultBits.revision := revision
      }.elsewhen(io.cmd.bits.seed === 0.U) {
        resultValid := true.B
        resultBits.status := CommandStatus.BadSeed
        resultBits.changed := false.B
        resultBits.revision := revision
      }.otherwise {
        config.preset := io.cmd.bits.preset
        config.cols := selectedCols
        config.rows := selectedRows
        config.mines := io.cmd.bits.mines
        board.io.clear := true.B
        generated := false.B
        gameState := GameState.Idle
        revision := revision + 1.U
        generationCycles := 0.U
        revealCycles := 0.U
        revealLayers := 0.U
        solverCycles := 0.U
        explodedIdx := 0.U
        resultValid := true.B
        resultBits.status := CommandStatus.Ok
        resultBits.changed := true.B
        resultBits.revision := revision + 1.U
      }
    }.elsewhen(io.cmd.bits.kind === CmdKind.Flag || io.cmd.bits.kind === CmdKind.Reveal) {
      when(terminal) {
        resultValid := true.B
        resultBits.status := CommandStatus.Ok
        resultBits.changed := false.B
        resultBits.revision := revision
      }.elsewhen(!coordinateActive) {
        resultValid := true.B
        resultBits.status := CommandStatus.Range
        resultBits.changed := false.B
        resultBits.revision := revision
      }.otherwise {
        // A legal active coordinate always fits the physical vector. Trim padded
        // arithmetic bits to the vector index width to avoid an oversized mux.
        val cell = board.io.view(activeIndex(p.idxW - 1, 0))
        when(io.cmd.bits.kind === CmdKind.Flag) {
          when(cell.revealed) {
            resultValid := true.B
            resultBits.status := CommandStatus.Ok
            resultBits.changed := false.B
            resultBits.revision := revision
          }.otherwise {
            board.io.flagToggle.valid := true.B
            board.io.flagToggle.bits := activeIndex
            gameState := GameState.Playing
            revision := revision + 1.U
            generationCycles := 0.U
            revealCycles := 0.U
            revealLayers := 0.U
            solverCycles := 0.U
            resultValid := true.B
            resultBits.status := CommandStatus.Ok
            resultBits.changed := true.B
            resultBits.revision := revision + 1.U
          }
        }.elsewhen(cell.revealed || cell.flagged) {
          resultValid := true.B
          resultBits.status := CommandStatus.Ok
          resultBits.changed := false.B
          resultBits.revision := revision
        }.elsewhen(!generated) {
          mineGen.io.start.valid := true.B
          mineGen.io.start.bits.config := config
          mineGen.io.start.bits.seed := 1.U
          // Seed is selected by the accepted NewGame; retain it across commands.
          mineGen.io.start.bits.seed := seedReg
          mineGen.io.start.bits.safeIdx := activeIndex
          when(mineGen.io.start.ready) {
            firstIdx := activeIndex
            gameState := GameState.Generating
            phase := phaseGenerating
            generationCycles := 0.U
            revealCycles := 0.U
            revealLayers := 0.U
            solverCycles := 0.U
            pendingRevision := revision + 1.U
          }
        }.elsewhen(cell.mine) {
          board.io.revealAllMines := true.B
          gameState := GameState.Lost
          explodedIdx := activeIndex
          revision := revision + 1.U
          generationCycles := 0.U
          revealCycles := 0.U
          revealLayers := 0.U
          solverCycles := 0.U
          resultValid := true.B
          resultBits.status := CommandStatus.Ok
          resultBits.changed := true.B
          resultBits.revision := revision + 1.U
        }.otherwise {
          board.io.revealSet := UIntToOH(activeIndex, p.cells)
          gameState := Mux(board.io.revealedSafeCount + 1.U >= activeCells - config.mines,
            GameState.Won, GameState.Playing)
          revision := revision + 1.U
          generationCycles := 0.U
          revealCycles := 0.U
          revealLayers := 0.U
          solverCycles := 0.U
          resultValid := true.B
          resultBits.status := CommandStatus.Ok
          resultBits.changed := true.B
          resultBits.revision := revision + 1.U
        }
      }
    }.otherwise {
      resultValid := true.B
      resultBits.status := CommandStatus.BadCommand
      resultBits.changed := false.B
      resultBits.revision := revision
    }
  }

  when(io.cmd.fire && io.cmd.bits.kind === CmdKind.NewGame && presetValid &&
      io.cmd.bits.mines >= p.minMines.U && io.cmd.bits.mines <= selectedMaxMines &&
      io.cmd.bits.seed =/= 0.U) {
    seedReg := io.cmd.bits.seed
  }

  when(phase === phaseGenerating) {
    when(mineGen.io.done) {
      board.io.loadMines.valid := true.B
      board.io.loadMines.bits := mineGen.io.mines
      generated := true.B
      generationCycles := mineGen.io.cycles
      gameState := GameState.Revealing
      phase := phaseRevealFirst
    }
  }.elsewhen(phase === phaseRevealFirst) {
    board.io.revealSet := UIntToOH(firstIdx, p.cells)
    gameState := Mux(board.io.revealedSafeCount + 1.U >= activeCells - config.mines,
      GameState.Won, GameState.Playing)
    revision := pendingRevision
    resultValid := true.B
    resultBits.status := CommandStatus.Ok
    resultBits.changed := true.B
    resultBits.revision := pendingRevision
    phase := phaseIdle
  }
}
