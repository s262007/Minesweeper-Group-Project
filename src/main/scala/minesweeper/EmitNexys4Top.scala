package minesweeper

import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

object EmitNexys4Top {
  def main(args: Array[String]): Unit = {
    val output = Paths.get("generated", "nexys4", "top", "Nexys4Top.sv")
    Files.createDirectories(output.getParent)
    val systemVerilog = ChiselStage.emitSystemVerilog(new Nexys4Top())
    Files.write(output, systemVerilog.getBytes(StandardCharsets.UTF_8))
    println(s"Generated $output")
  }
}
