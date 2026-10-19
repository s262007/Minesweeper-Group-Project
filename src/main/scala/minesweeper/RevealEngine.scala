package minesweeper

import chisel3._

/** S0 shell (R2). Refuses every start and reveals nothing. R2-S1 replaces the body
  * with the registered frontier expansion of CONTRACT section 5.5.
  */
class RevealEngine(p: MsParams) extends Module {
  val io = IO(new RevealIO(p))
  io.start.ready := false.B
  io.revealSet := 0.U
  io.busy := false.B
  io.done := false.B
  io.cycles := 0.U
  io.layers := 0.U
}
