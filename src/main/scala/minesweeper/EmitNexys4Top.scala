package minesweeper

import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object EmitNexys4Top {
  def main(args: Array[String]): Unit = {
    val options = Set("--solver-v1", "--stream-adjacency", "--board-unmasked-solver-adj",
      "--uart-hex-metrics", "--uart-decimal-metrics", "--legacy-minegen",
      "--pipeline-core-command", "--board-pipeline-reveal-write", "--solver-start-reset-only",
      "--board-pipelined-flag-count", "--board-incremental-safe-reveal-count")
    require(args.distinct.length == args.length && args.toSet.subsetOf(options) &&
      !(args.contains("--solver-v1") && args.contains("--stream-adjacency")) &&
      !(args.contains("--uart-hex-metrics") && args.contains("--uart-decimal-metrics")),
      "usage: EmitNexys4Top [--solver-v1] [--stream-adjacency] " +
        "[--board-unmasked-solver-adj] [--uart-decimal-metrics] [--uart-hex-metrics] " +
        "[--legacy-minegen] [--pipeline-core-command] [--board-pipeline-reveal-write] " +
        "[--solver-start-reset-only] [--board-pipelined-flag-count] " +
        "[--board-incremental-safe-reveal-count]")
    val solverV2 = !args.contains("--solver-v1")
    val baseName = if (args.contains("--solver-v1") && args.contains("--stream-adjacency")) {
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
    val experimentSuffix =
      (if (args.contains("--uart-decimal-metrics")) "_uart_decimal_metrics"
        else if (args.contains("--uart-hex-metrics")) "_uart_hex_metrics" else "") +
        (if (args.contains("--legacy-minegen")) "_legacy_minegen" else "") +
        (if (args.contains("--pipeline-core-command")) "_pipeline_core_command" else "") +
        (if (args.contains("--board-pipeline-reveal-write")) "_board_pipeline_reveal_write" else "") +
        (if (args.contains("--solver-start-reset-only")) "_solver_start_reset_only" else "") +
        (if (args.contains("--board-pipelined-flag-count")) "_board_pipelined_flag_count" else "") +
        (if (args.contains("--board-incremental-safe-reveal-count"))
          "_board_incremental_safe_reveal_count" else "")
    val outputName = baseName.stripSuffix(".sv") + experimentSuffix + ".sv"
    val output = Paths.get("generated", "nexys4", "top", outputName)
    Files.createDirectories(output.getParent)
    val params = MsParams(
      streamAdjacency = args.contains("--stream-adjacency"),
      solverV2 = solverV2,
      maskSolverAdjacency = !args.contains("--board-unmasked-solver-adj"),
      uartHexMetrics = !args.contains("--uart-decimal-metrics"),
      pipelineMineGen = !args.contains("--legacy-minegen"),
      pipelineCoreCommand = args.contains("--pipeline-core-command"),
      pipelineBoardRevealWrite = args.contains("--board-pipeline-reveal-write"),
      incrementalSafeRevealCount = args.contains("--board-incremental-safe-reveal-count"),
      solverClearOnStartOnly = args.contains("--solver-start-reset-only"),
      incrementalFlagCount = args.contains("--board-pipelined-flag-count"),
      pipelineFlagCountUpdate = args.contains("--board-pipelined-flag-count"),
      pipelineBoardFlagWrite = args.contains("--board-pipelined-flag-count")
    )
    val systemVerilog = ChiselStage.emitSystemVerilog(new Nexys4Top(params))
    Files.write(output, systemVerilog.getBytes(StandardCharsets.UTF_8))
    println(s"Generated $output")
  }
}
