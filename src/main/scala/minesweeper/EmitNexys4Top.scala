package minesweeper

import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object EmitNexys4Top {
  def main(args: Array[String]): Unit = {
    val options = Set("--solver-v1", "--stream-adjacency", "--board-unmasked-solver-adj")
    require(args.distinct.length == args.length && args.toSet.subsetOf(options) &&
      !(args.contains("--solver-v1") && args.contains("--stream-adjacency")),
      "usage: EmitNexys4Top [--solver-v1] [--stream-adjacency] " +
        "[--board-unmasked-solver-adj]")
    val solverV2 = !args.contains("--solver-v1")
    val outputName = if (args.contains("--solver-v1") && args.contains("--stream-adjacency")) {
      "Nexys4Top.solver_v1_stream_adjacency.sv"
    } else if (args.contains("--solver-v1") && args.contains("--board-unmasked-solver-adj")) {
      "Nexys4Top.solver_v1_board_unmasked_solver_adj.sv"
    } else if (args.contains("--solver-v1")) {
      "Nexys4Top.solver_v1.sv"
    } else if (args.contains("--stream-adjacency")) {
      "Nexys4Top.stream_adjacency.sv"
    } else if (args.contains("--board-unmasked-solver-adj")) {
      "Nexys4Top.solver_v2_board_unmasked_solver_adj.sv"
    } else {
      "Nexys4Top.solver_v2.sv"
    }
    val output = Paths.get("generated", "nexys4", "top", outputName)
    Files.createDirectories(output.getParent)
    val params = MsParams(
      streamAdjacency = args.contains("--stream-adjacency"),
      solverV2 = solverV2,
      maskSolverAdjacency = !args.contains("--board-unmasked-solver-adj")
    )
    val systemVerilog = ChiselStage.emitSystemVerilog(new Nexys4Top(params))
    Files.write(output, systemVerilog.getBytes(StandardCharsets.UTF_8))
    println(s"Generated $output")
  }
}
