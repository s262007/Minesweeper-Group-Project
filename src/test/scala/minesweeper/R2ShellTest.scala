package minesweeper

import chisel3._
import circt.stage.ChiselStage
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** R2-S0: the RevealEngine and Solver shells elaborate everywhere and never claim results. */
class R2ShellTest extends AnyFlatSpec with ChiselScalatestTester {
  private val matrix = Seq((4, 4), (7, 5), (9, 9), (16, 8))
    .map { case (cols, rows) => MsParams.forVerification(cols, rows) } :+ MsParams()
  private val solverConfigs = Seq(SolverConfig(), SolverConfig(subsetRule = true, lanes = 4))

  "R2 shells" should "emit Verilog at verification sizes and production capacity" in {
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

  "RevealEngine shell" should "refuse a start and reveal nothing" in {
    val p = MsParams.forVerification(4, 4)
    test(new RevealEngine(p)) { dut =>
      dut.io.clear.poke(false.B)
      dut.io.start.valid.poke(true.B)
      dut.io.start.bits.idx.poke(p.index(1, 1).U)
      for (_ <- 0 until 4) {
        dut.io.start.ready.expect(false.B)
        dut.io.revealSet.expect(0.U)
        dut.io.busy.expect(false.B)
        dut.io.done.expect(false.B)
        dut.io.layers.expect(0.U)
        dut.clock.step()
      }
    }
  }

  "Solver shell" should "refuse a start and never certify hints" in {
    val p = MsParams.forVerification(4, 4)
    for (cfg <- solverConfigs) {
      test(new Solver(p, cfg)) { dut =>
        dut.io.clear.poke(false.B)
        dut.io.start.valid.poke(true.B)
        // A revealed 1 in the corner: a real solver could deduce something here.
        dut.io.board(p.index(0, 0)).revealed.poke(true.B)
        dut.io.board(p.index(0, 0)).adj.poke(1.U)
        for (_ <- 0 until 4) {
          dut.io.start.ready.expect(false.B)
          dut.io.valid.expect(false.B)
          dut.io.done.expect(false.B)
          dut.io.contradiction.expect(false.B)
          dut.io.hints.foreach { h => h.safe.expect(false.B); h.mine.expect(false.B) }
          dut.clock.step()
        }
      }
    }
  }
}
