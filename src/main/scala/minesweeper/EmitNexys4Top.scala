package minesweeper

import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object EmitNexys4Top {
  def main(args: Array[String]): Unit = {
    val options = Set("--solver-v1", "--stream-adjacency", "--board-unmasked-solver-adj",
      "--uart-hex-metrics", "--uart-decimal-metrics", "--legacy-minegen",
      "--legacy-core-command", "--legacy-board-reveal-write", "--solver-clear-every-cycle",
      "--legacy-flag-count", "--legacy-safe-reveal-count", "--legacy-timing-bundle",
      "--pipeline-uart-tx-ingress", "--pipeline-reveal-win-check", "--pipeline-minegen-modulo")
    require(args.distinct.length == args.length && args.toSet.subsetOf(options) &&
      !(args.contains("--solver-v1") && args.contains("--stream-adjacency")) &&
      !(args.contains("--uart-hex-metrics") && args.contains("--uart-decimal-metrics")),
      "usage: EmitNexys4Top [--solver-v1] [--stream-adjacency] " +
        "[--board-unmasked-solver-adj] [--uart-decimal-metrics] [--uart-hex-metrics] " +
        "[--legacy-minegen] [--legacy-core-command] [--legacy-board-reveal-write] " +
        "[--solver-clear-every-cycle] [--legacy-flag-count] [--legacy-safe-reveal-count] " +
        "[--legacy-timing-bundle] [--pipeline-uart-tx-ingress] " +
        "[--pipeline-reveal-win-check] [--pipeline-minegen-modulo]")
    val timingBundle = !args.contains("--legacy-timing-bundle")
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
        (if (args.contains("--legacy-core-command")) "_legacy_core_command" else "") +
        (if (args.contains("--legacy-board-reveal-write")) "_legacy_board_reveal_write" else "") +
        (if (args.contains("--solver-clear-every-cycle")) "_solver_clear_every_cycle" else "") +
        (if (args.contains("--legacy-flag-count")) "_legacy_flag_count" else "") +
        (if (args.contains("--legacy-safe-reveal-count")) "_legacy_safe_reveal_count" else "") +
        (if (args.contains("--pipeline-uart-tx-ingress")) "_pipeline_uart_tx_ingress" else "") +
        (if (args.contains("--pipeline-reveal-win-check")) "_pipeline_reveal_win_check" else "") +
        (if (args.contains("--pipeline-minegen-modulo")) "_pipeline_minegen_modulo" else "")
    val compactExperimentSuffix =
      (if (args.contains("--uart-decimal-metrics")) "_ud" else
        if (args.contains("--uart-hex-metrics")) "_uh" else "") +
        (if (args.contains("--legacy-minegen")) "_lmg" else "") +
        (if (args.contains("--legacy-core-command")) "_lcc" else "") +
        (if (args.contains("--legacy-board-reveal-write")) "_lbrw" else "") +
        (if (args.contains("--solver-clear-every-cycle")) "_scec" else "") +
        (if (args.contains("--legacy-flag-count")) "_lfc" else "") +
        (if (args.contains("--legacy-safe-reveal-count")) "_lsrc" else "") +
        (if (args.contains("--pipeline-uart-tx-ingress")) "_utx" else "") +
        (if (args.contains("--pipeline-reveal-win-check")) "_rwc" else "") +
        (if (args.contains("--pipeline-minegen-modulo")) "_mgm" else "")
    val bundleSuffix = if (timingBundle) "_pipeline_timing_bundle" else ""
    val longOutputName = baseName.stripSuffix(".sv") + bundleSuffix + experimentSuffix + ".sv"
    val compactOutputName = baseName.stripSuffix(".sv") + bundleSuffix + compactExperimentSuffix + ".sv"
    val outputDir = Paths.get("generated", "nexys4", "top")
    val longOutput = outputDir.resolve(longOutputName)
    val compactOutput = outputDir.resolve(compactOutputName)
    // Keep full option names for normal runs; use compact tags when the absolute path
    // approaches the traditional Windows MAX_PATH limit.
    val output = if (longOutput.toAbsolutePath.toString.length <= 240) longOutput else compactOutput
    Files.createDirectories(output.getParent)
    val baseParams = MsParams(
      streamAdjacency = args.contains("--stream-adjacency"),
      solverV2 = solverV2,
      maskSolverAdjacency = !args.contains("--board-unmasked-solver-adj"),
      uartHexMetrics = !args.contains("--uart-decimal-metrics"),
      pipelineMineGen = !args.contains("--legacy-minegen"),
      pipelineCoreCommand = !args.contains("--legacy-core-command"),
      pipelineCommandIngress = true,
      pipelineUartTxIngress = args.contains("--pipeline-uart-tx-ingress"),
      pipelineRevealWinCheck = args.contains("--pipeline-reveal-win-check"),
      pipelineBoardRevealWrite = !args.contains("--legacy-board-reveal-write"),
      incrementalSafeRevealCount = !args.contains("--legacy-safe-reveal-count"),
      solverClearOnStartOnly = !args.contains("--solver-clear-every-cycle"),
      incrementalFlagCount = !args.contains("--legacy-flag-count"),
      pipelineFlagCountUpdate = !args.contains("--legacy-flag-count"),
      pipelineBoardFlagWrite = !args.contains("--legacy-flag-count"),
      pipelineMineGenModulo = args.contains("--pipeline-minegen-modulo")
    )
    val params = if (timingBundle) MsParams.nexys4TimingBundle(baseParams) else baseParams
    val systemVerilog = ChiselStage.emitSystemVerilog(new Nexys4Top(params))
    Files.write(output, systemVerilog.getBytes(StandardCharsets.UTF_8))
    println(s"Generated $output")
  }
}
