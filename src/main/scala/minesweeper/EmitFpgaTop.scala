package minesweeper

import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object EmitFpgaTop {
  def main(args: Array[String]): Unit = {
    val options = Set("--stream-adjacency", "--uart-decimal-metrics")
    require(args.distinct.length == args.length && args.toSet.subsetOf(options),
      "usage: EmitFpgaTop [--stream-adjacency] [--uart-decimal-metrics]")
    val stream = args.contains("--stream-adjacency")
    val decimal = args.contains("--uart-decimal-metrics")
    val suffix =
      (if (stream) ".stream_adjacency" else "") +
        (if (decimal) ".uart_decimal_metrics" else "")
    val outputName = s"FpgaTop$suffix.sv"
    val output = Paths.get("generated", "s0", "top", outputName)
    Files.createDirectories(output.getParent)
    val params = MsParams(streamAdjacency = stream, uartHexMetrics = !decimal)
    val systemVerilog = ChiselStage.emitSystemVerilog(new FpgaTop(params))
    Files.write(output, systemVerilog.getBytes(StandardCharsets.UTF_8))
    println(s"Generated $output")
  }
}
