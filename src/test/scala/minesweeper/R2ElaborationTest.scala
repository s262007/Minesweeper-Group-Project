package minesweeper

import circt.stage.ChiselStage
import org.scalatest.flatspec.AnyFlatSpec

class R2ElaborationTest extends AnyFlatSpec {
  private val matrix = Seq((4, 4), (7, 5), (9, 9), (16, 8))
    .map { case (cols, rows) => MsParams.forVerification(cols, rows) } :+ MsParams()
  private val solverConfigs = Seq(SolverConfig(), SolverConfig(subsetRule = true, lanes = 4))

  "R2 modules" should "emit Verilog at verification sizes and production capacity" in {
    for (p <- matrix) {
      assert(ChiselStage.emitSystemVerilog(new RevealEngine(p)).contains("module RevealEngine"))
      for (cfg <- solverConfigs) {
        assert(ChiselStage.emitSystemVerilog(new Solver(p, cfg)).contains("module Solver"))
      }
    }
  }

  it should "reject more solver lanes than cells" in {
    val p = MsParams.forVerification(4, 4)
    ChiselStage.emitCHIRRTL(new Solver(p, SolverConfig(subsetRule = true, lanes = p.cells)))
    intercept[IllegalArgumentException] {
      ChiselStage.emitCHIRRTL(new Solver(p, SolverConfig(subsetRule = true, lanes = p.cells + 1)))
    }
  }
}
