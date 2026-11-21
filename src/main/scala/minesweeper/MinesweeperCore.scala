package minesweeper

import chisel3._

class MinesweeperCore(p: MsParams) extends Module {
  val io = IO(new CoreIO(p))

  private val board = Module(new Board(p))
  private val mineGen = Module(new MineGen(p))
  private val reveal = Module(new RevealEngine(p))
  // Use the row-by-row Solver for both configurations. Its subset rule is integrated
  // into the row sweep to keep the production hardware manageable.
  private val solverIO: SolverIO =
    Module(new Solver(p, SolverConfig(subsetRule = p.solverV2))).io

  private val phaseIdle = 0.U(4.W)
  private val phaseGenerating = 1.U(4.W)
  private val phaseRevealFirst = 2.U(4.W)
  private val phaseReveal = 3.U(4.W)
  private val phaseSolverStart = 4.U(4.W)
  private val phaseSolver = 5.U(4.W)
  private val phaseAdjacency = 6.U(4.W)
  private val phaseFlagWrite = 7.U(4.W)
  private val phaseRevealDrain = 8.U(4.W)
  private val phaseFlagLossCount = 9.U(4.W)
  private val phaseLossReveal = 10.U(4.W)
  private val phaseRevealStart = 11.U(4.W)
  private val phaseMineGenStart = 12.U(4.W)
  private val phaseRevealWinCheck = 13.U(4.W)
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
  private val resumeState = RegInit(GameState.Idle)
  private val hintsFresh = RegInit(false.B)
  private val firstIdx = RegInit(0.U(p.idxW.W))
  private val revision = RegInit(0.U(p.revisionW.W))
  private val generationCycles = RegInit(0.U(p.cycleW.W))
  private val revealCycles = RegInit(0.U(p.cycleW.W))
  private val revealLayers = RegInit(0.U(p.cntW.W))
  private val solverCycles = RegInit(0.U(p.cycleW.W))
  private val explodedIdx = RegInit(0.U(p.idxW.W))
  private val seedReg = RegInit(1.U(p.seedW.W))
  private val pendingRevision = RegInit(0.U(p.revisionW.W))
  private val pendingFlagIndex = RegInit(0.U(p.idxW.W))
  private val pendingRevealIndex = RegInit(0.U(p.idxW.W))
  private val pendingMineGenRequest = RegInit(0.U.asTypeOf(new MineGenRequest(p)))
  private val pendingSafeRevealCount = RegInit(0.U(p.cntW.W))
  private val pendingRevealSet = RegInit(0.U(p.cells.W))

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
  private val clearSolver = WireDefault(false.B)

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
  io.hintsValid := hintsFresh && solverIO.valid && !solverIO.contradiction
  for (idx <- 0 until p.cells) {
    io.hints(idx).safe := hintsFresh && solverIO.hints(idx).safe
    io.hints(idx).mine := hintsFresh && solverIO.hints(idx).mine
  }

  board.io.config.cols := config.cols
  board.io.config.rows := config.rows
  board.io.clear := false.B
  board.io.loadMines.valid := false.B
  board.io.loadMines.bits := 0.U
  board.io.beginAdjacency := p.streamAdjacency.B && mineGen.io.start.fire
  board.io.placedMine.valid := p.streamAdjacency.B && mineGen.io.placedValid
  board.io.placedMine.bits := mineGen.io.placedIdx
  if (p.pipelineBoardRevealWrite) {
    // Optional timing experiment: register each flood-fill layer before Board writes it.
    // Core remains in phaseReveal until RevealEngine finishes, so the final buffered
    // layer is committed before Core launches the Solver.
    board.io.revealSet := pendingRevealSet
    pendingRevealSet := reveal.io.revealSet
  } else {
    board.io.revealSet := reveal.io.revealSet
  }
  board.io.revealAllMines := false.B
  if (p.pipelineLossReveal) {
    when(phase === phaseLossReveal) {
      board.io.revealAllMines := true.B
    }
  }
  board.io.flagToggle.valid := false.B
  board.io.flagToggle.bits := 0.U
  if (p.pipelineBoardFlagWrite) {
    when(phase === phaseFlagWrite) {
      board.io.flagToggle.valid := true.B
      board.io.flagToggle.bits := pendingFlagIndex
    }
  }

  reveal.io.clear := board.io.clear
  reveal.io.view := board.io.revealView
  reveal.io.start.valid := false.B
  reveal.io.start.bits.config := config
  reveal.io.start.bits.idx := 0.U
  if (p.pipelineRevealStart) {
    when(phase === phaseRevealStart) {
      reveal.io.start.valid := true.B
      reveal.io.start.bits.idx := pendingRevealIndex
    }
  }

  mineGen.io.clear := false.B
  mineGen.io.start.valid := false.B
  mineGen.io.start.bits.config.preset := config.preset
  mineGen.io.start.bits.config.mines := config.mines
  mineGen.io.start.bits.seed := 1.U
  mineGen.io.start.bits.safeIdx := 0.U
  mineGen.io.start.bits.safeRow := 0.U
  mineGen.io.start.bits.safeCol := 0.U
  if (p.pipelineMineGenStart) {
    when(phase === phaseMineGenStart) {
      mineGen.io.start.valid := true.B
      mineGen.io.start.bits.config.preset := pendingMineGenRequest.config.preset
      mineGen.io.start.bits.config.mines := pendingMineGenRequest.config.mines
      mineGen.io.start.bits.seed := pendingMineGenRequest.seed
      mineGen.io.start.bits.safeIdx := pendingMineGenRequest.safeIdx
      mineGen.io.start.bits.safeRow := pendingMineGenRequest.safeRow
      mineGen.io.start.bits.safeCol := pendingMineGenRequest.safeCol
    }
  }

  // Optional timing experiment: the Core accepts commands only while the Solver is idle,
  // hides stale public hints with hintsFresh, and Solver.start.fire resets its working state.
  // This removes the command-decode-driven high-fanout clear cone from the production path.
  solverIO.clear := (if (p.solverClearOnStartOnly) false.B else board.io.clear || clearSolver)
  solverIO.board := board.io.solverView
  solverIO.start.valid := phase === phaseSolverStart
  solverIO.start.bits.config := config

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
        hintsFresh := false.B
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
        // Select within each row first, then select the requested row.
        // Keep the command coordinates through this cell lookup.
        val selectedByRow = VecInit((0 until p.maxRows).map { row =>
          VecInit((0 until p.maxCols).map { col =>
            board.io.view(row * p.maxCols + col)
          })(io.cmd.bits.col)
        })
        val cell = selectedByRow(io.cmd.bits.row)
        when(io.cmd.bits.kind === CmdKind.Flag) {
          when(cell.revealed) {
            resultValid := true.B
            resultBits.status := CommandStatus.Ok
            resultBits.changed := false.B
            resultBits.revision := revision
          }.otherwise {
            hintsFresh := false.B
            generationCycles := 0.U
            revealCycles := 0.U
            revealLayers := 0.U
            solverCycles := 0.U
            if (p.pipelineBoardFlagWrite) {
              pendingFlagIndex := activeIndex
              phase := phaseFlagWrite
            } else {
              board.io.flagToggle.valid := true.B
              board.io.flagToggle.bits := activeIndex
              revision := revision + 1.U
              when(generated) {
                resumeState := gameState
                gameState := GameState.Solving
                phase := phaseSolverStart
                pendingRevision := revision + 1.U
              }.otherwise {
                resultValid := true.B
                resultBits.status := CommandStatus.Ok
                resultBits.changed := true.B
                resultBits.revision := revision + 1.U
              }
            }
          }
        }.elsewhen(cell.revealed || cell.flagged) {
          resultValid := true.B
          resultBits.status := CommandStatus.Ok
          resultBits.changed := false.B
          resultBits.revision := revision
        }.elsewhen(!generated) {
          hintsFresh := false.B
          if (p.pipelineMineGenStart) {
            pendingMineGenRequest.config.preset := config.preset
            pendingMineGenRequest.config.mines := config.mines
            pendingMineGenRequest.seed := seedReg
            pendingMineGenRequest.safeIdx := activeIndex
            pendingMineGenRequest.safeRow := io.cmd.bits.row
            pendingMineGenRequest.safeCol := io.cmd.bits.col
            firstIdx := activeIndex
            gameState := GameState.Generating
            generationCycles := 0.U
            revealCycles := 0.U
            revealLayers := 0.U
            solverCycles := 0.U
            pendingRevision := revision + 1.U
            phase := phaseMineGenStart
          } else {
            mineGen.io.start.valid := true.B
            mineGen.io.start.bits.config.preset := config.preset
            mineGen.io.start.bits.config.mines := config.mines
            mineGen.io.start.bits.seed := seedReg
            mineGen.io.start.bits.safeIdx := activeIndex
            mineGen.io.start.bits.safeRow := io.cmd.bits.row
            mineGen.io.start.bits.safeCol := io.cmd.bits.col
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
          }
        }.elsewhen(cell.mine) {
          if (!p.pipelineLossReveal) board.io.revealAllMines := true.B
          hintsFresh := false.B
          clearSolver := true.B
          gameState := GameState.Lost
          explodedIdx := activeIndex
          revision := revision + 1.U
          generationCycles := 0.U
          revealCycles := 0.U
          revealLayers := 0.U
          solverCycles := 0.U
          if (p.pipelineLossReveal) {
            phase := phaseLossReveal
          } else if (p.pipelineFlagLossRecount) {
            phase := phaseFlagLossCount
          } else {
            resultValid := true.B
          }
          resultBits.status := CommandStatus.Ok
          resultBits.changed := true.B
          resultBits.revision := revision + 1.U
        }.otherwise {
          hintsFresh := false.B
          if (p.pipelineRevealStart) {
            pendingRevealIndex := activeIndex
            phase := phaseRevealStart
          } else {
            reveal.io.start.valid := true.B
            reveal.io.start.bits.config := config
            reveal.io.start.bits.idx := activeIndex
            phase := phaseReveal
          }
          gameState := GameState.Revealing
          pendingRevision := revision + 1.U
          generationCycles := 0.U
          revealCycles := 0.U
          revealLayers := 0.U
          solverCycles := 0.U
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

  when(p.pipelineBoardFlagWrite.B && phase === phaseFlagWrite) {
    revision := revision + 1.U
    when(generated) {
      resumeState := gameState
      gameState := GameState.Solving
      phase := phaseSolverStart
      pendingRevision := revision + 1.U
    }.otherwise {
      resultValid := true.B
      resultBits.status := CommandStatus.Ok
      resultBits.changed := true.B
      resultBits.revision := revision + 1.U
      phase := phaseIdle
    }
  }.elsewhen(phase === phaseMineGenStart) {
    when(mineGen.io.start.fire) {
      phase := phaseGenerating
    }
  }.elsewhen(phase === phaseGenerating) {
    when(mineGen.io.done) {
      board.io.loadMines.valid := true.B
      board.io.loadMines.bits := mineGen.io.mines
      generated := true.B
      generationCycles := mineGen.io.cycles
      gameState := GameState.Playing
      phase := phaseAdjacency
    }
  }.elsewhen(phase === phaseAdjacency) {
    when(board.io.adjacencyReady) {
      phase := phaseRevealFirst
    }
  }.elsewhen(phase === phaseRevealFirst) {
    reveal.io.start.valid := true.B
    reveal.io.start.bits.config := config
    reveal.io.start.bits.idx := firstIdx
    when(reveal.io.start.ready) {
      phase := phaseReveal
    }
  }.elsewhen(phase === phaseRevealStart) {
    when(reveal.io.start.ready) {
      phase := phaseReveal
    }
  }.elsewhen(phase === phaseReveal) {
    when(reveal.io.done) {
      revealCycles := reveal.io.cycles
      revealLayers := reveal.io.layers
      if (p.pipelineBoardRevealWrite) {
        // Let Board commit the final buffered reveal layer before checking for a win.
        phase := phaseRevealDrain
      } else if (p.pipelineRevealWinCheck) {
        pendingSafeRevealCount := board.io.revealedSafeCount
        phase := phaseRevealWinCheck
      } else {
        resumeState := Mux(board.io.revealedSafeCount >= activeCells - config.mines,
          GameState.Won, GameState.Playing)
        gameState := GameState.Solving
        phase := phaseSolverStart
      }
    }
  }.elsewhen(phase === phaseRevealDrain) {
    if (p.pipelineRevealWinCheck) {
      // Register the row-counter reduction separately from the win-threshold compare.
      pendingSafeRevealCount := board.io.revealedSafeCount
      phase := phaseRevealWinCheck
    } else {
      resumeState := Mux(board.io.revealedSafeCount >= activeCells - config.mines,
        GameState.Won, GameState.Playing)
      gameState := GameState.Solving
      phase := phaseSolverStart
    }
  }.elsewhen(phase === phaseRevealWinCheck) {
    resumeState := Mux(pendingSafeRevealCount >= activeCells - config.mines,
      GameState.Won, GameState.Playing)
    gameState := GameState.Solving
    phase := phaseSolverStart
  }.elsewhen(phase === phaseSolverStart) {
    when(solverIO.start.fire) {
      phase := phaseSolver
    }
  }.elsewhen(phase === phaseSolver) {
    when(solverIO.done) {
      solverCycles := solverIO.cycles
      revision := pendingRevision
      resultValid := true.B
      resultBits.status := Mux(solverIO.contradiction,
        CommandStatus.InternalError, CommandStatus.Ok)
      resultBits.changed := true.B
      resultBits.revision := pendingRevision
      when(solverIO.contradiction) {
        hintsFresh := false.B
        gameState := GameState.Error
      }.otherwise {
        hintsFresh := true.B
        gameState := resumeState
      }
      phase := phaseIdle
    }
  }.elsewhen(phase === phaseFlagLossCount) {
    resultValid := true.B
    phase := phaseIdle
  }.elsewhen(phase === phaseLossReveal) {
    phase := phaseFlagLossCount
  }
}
