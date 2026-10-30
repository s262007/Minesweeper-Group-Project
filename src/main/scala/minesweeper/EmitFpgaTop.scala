package minesweeper

import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object EmitFpgaTop {
  def main(args: Array[String]): Unit = {
    require(args.toList == Nil || args.toList == List("--stream-adjacency"),
      "usage: EmitFpgaTop [--stream-adjacency]")
    val output = Paths.get("generated", "s0", "top", "FpgaTop.sv")
    Files.createDirectories(output.getParent)
    val params = MsParams(streamAdjacency = args.contains("--stream-adjacency"))
    val systemVerilog = ChiselStage.emitSystemVerilog(new FpgaTop(params))
    Files.write(output, systemVerilog.getBytes(StandardCharsets.UTF_8))
    println(s"Generated $output")
  }
}
