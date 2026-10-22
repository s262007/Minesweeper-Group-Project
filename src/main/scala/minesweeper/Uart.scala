package minesweeper

import chisel3._

/** R3-S0 shell: delivers no bytes and reports no errors. R3-S2 adds 16x sampling and the FIFO. */
class UartRx(cfg: UartConfig) extends Module {
  val io = IO(new UartRxIO)
  io.out.valid := false.B
  io.out.bits := 0.U
  io.framingError := false.B
  io.overflow := false.B
}

/** R3-S0 shell: accepts nothing and keeps the line idle-high. R3-S2 adds the serializer. */
class UartTx(cfg: UartConfig) extends Module {
  val io = IO(new UartTxIO)
  io.in.ready := false.B
  io.tx := true.B
  io.busy := false.B
}
