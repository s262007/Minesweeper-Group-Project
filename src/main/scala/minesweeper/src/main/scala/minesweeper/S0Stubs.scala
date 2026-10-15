package minesweeper

import chisel3._

/** S0 shells only. Replace each body in its owning story before using it in a game. */
class MineGen(p: MsParams) extends Module {
  val io = IO(new MineGenIO(p))
  io.start.ready := false.B
  io.busy := false.B
  io.done := false.B
  io.mines := 0.U
  io.cycles := 0.U
}

class Board(p: MsParams) extends Module {
  val io = IO(new BoardIO(p))
  io.view := 0.U.asTypeOf(io.view)
  io.revealView := 0.U.asTypeOf(io.revealView)
  io.solverView := 0.U.asTypeOf(io.solverView)
  io.flagCount := 0.U
  io.revealedSafeCount := 0.U
}

class MinesweeperCore(p: MsParams) extends Module {
  val io = IO(new CoreIO(p))
  io.cmd.ready := false.B
  io.result.valid := false.B
  io.result.bits := 0.U.asTypeOf(new CommandResult(p))
  io.config := 0.U.asTypeOf(new ActiveConfig(p))
  io.state := GameState.Idle
  io.busy := false.B
  // No snapshot is available from a shell, including immediately after reset.
  io.settled := false.B
  io.cells := 0.U.asTypeOf(io.cells)
  io.hints := 0.U.asTypeOf(io.hints)
  io.hintsValid := false.B
  io.minesLeft := 0.S
  io.exploded.valid := false.B
  io.exploded.bits := 0.U
  io.revision := 0.U
  io.generationCycles := 0.U
  io.revealCycles := 0.U
  io.revealLayers := 0.U
  io.solverCycles := 0.U
}

class UartGameBridge(p: MsParams) extends Module {
  val io = IO(new UartGameBridgeIO(p))
  io.rx.ready := false.B
  io.tx.valid := false.B
  io.tx.bits := 0.U
  io.core.cmd.valid := false.B
  io.core.cmd.bits := 0.U.asTypeOf(new CmdBundle(p))
  io.core.result.ready := false.B
}

/** Logical top shell; no selected board, baud generator or serial hardware yet. */
class FpgaTop(p: MsParams) extends Module {
  require(!p.verification, "FPGA top must use production parameters")
  val io = IO(new FpgaTopIO)
  val core = Module(new MinesweeperCore(p))
  val bridge = Module(new UartGameBridge(p))
  bridge.io.core <> core.io
  bridge.io.rx.valid := false.B
  bridge.io.rx.bits := 0.U
  bridge.io.rxFramingError := false.B
  bridge.io.rxOverflow := false.B
  bridge.io.tx.ready := false.B
  io.tx := true.B
  io.won := false.B
  io.lost := false.B
}
