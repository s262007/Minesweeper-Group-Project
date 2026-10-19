package minesweeper

import chisel3._

/** S0 shell (R2). Refuses every start and certifies no hints. R2-S2 (v1) and R2-S4 (v2)
  * replace the body with the rules of CONTRACT section 5.6.
  */
class Solver(p: MsParams, cfg: SolverConfig = SolverConfig()) extends Module {
  require(cfg.lanes <= p.cells, s"solver lanes (${cfg.lanes}) must not exceed the ${p.cells} cells")
  val io = IO(new SolverIO(p))
  io.start.ready := false.B
  io.hints := 0.U.asTypeOf(io.hints)
  io.valid := false.B
  io.busy := false.B
  io.done := false.B
  io.contradiction := false.B
  io.cycles := 0.U
}
