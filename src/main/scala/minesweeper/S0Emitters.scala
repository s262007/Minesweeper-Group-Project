package minesweeper

import chisel3.RawModule
import circt.stage.ChiselStage
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

private[minesweeper] object S0EmitterSupport {
  private val matrix = Seq((4, 4), (7, 5), (9, 9), (16, 8))
    .map { case (cols, rows) => MsParams.forVerification(cols, rows) } :+ MsParams()

  def emit(moduleName: String, args: Array[String], build: MsParams => RawModule): Unit = {
    val params = args.toList match {
      case Nil => matrix
      case List("--production-only") => Seq(MsParams())
      case _ => throw new IllegalArgumentException("usage: [--production-only]")
    }

    params.foreach { p =>
      val rtl = ChiselStage.emitSystemVerilog(build(p))
      val directory = Paths.get("generated", "s0", s"${p.maxCols}x${p.maxRows}")
      Files.createDirectories(directory)
      val output = directory.resolve(s"$moduleName.sv")
      Files.write(output, rtl.getBytes(StandardCharsets.UTF_8))
      println(s"Generated $output")
    }
  }
}

object EmitMineGen {
  def main(args: Array[String]): Unit =
    S0EmitterSupport.emit("MineGen", args, p => new MineGen(p))
}

object EmitBoard {
  def main(args: Array[String]): Unit =
    S0EmitterSupport.emit("Board", args, p => new Board(p))
}

object EmitMinesweeperCore {
  def main(args: Array[String]): Unit =
    S0EmitterSupport.emit("MinesweeperCore", args, p => new MinesweeperCore(p))
}

object EmitUartGameBridge {
  def main(args: Array[String]): Unit =
    S0EmitterSupport.emit("UartGameBridge", args, p => new UartGameBridge(p))
}
