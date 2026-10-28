package minesweeper

import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object EmitFpgaTop {
  def main(args: Array[String]): Unit = {
    val output = Paths.get("generated", "s0", "top", "FpgaTop.sv")
    Files.createDirectories(output.getParent)
    val systemVerilog = ChiselStage.emitSystemVerilog(new FpgaTop(MsParams()))
    Files.write(output, systemVerilog.getBytes(StandardCharsets.UTF_8))
    println(s"Generated $output")
  }
}
