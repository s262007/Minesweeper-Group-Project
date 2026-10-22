package minesweeper

import chisel3._

/** UART bridge and FPGA top shells. */
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
