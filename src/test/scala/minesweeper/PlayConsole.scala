package minesweeper

import chiseltest.RawTester
import minesweeper.model._

import scala.io.StdIn

/**
  * Terminal game on the simulated MinesweeperCore (MineGen, Board, RevealEngine, Solver).
  * The golden model runs alongside and checks every move. --model plays on the model only.
  *
  *   sbt "set Test / run / fork := true" "set Test / run / connectInput := true" "Test/runMain minesweeper.PlayConsole 6 6 5 1"
  *
  * Arguments: cols rows mines [seed] [--model] [--script "r 2 2; f 0 1; a; q"]. Rows and columns start at 0.
  */
object PlayConsole {
  val help: String =
    """Commands: r <row> <col> reveal | f <row> <col> flag | a reveal a safe hint | h hints on/off | n [seed] new game | q quit
      |Board: . hidden, F flag, 0-8 revealed, s/m solver hint (safe/mine), * mine, X the mine you hit""".stripMargin

  private final class Session(game: MinesweeperImpl, checker: Option[GoldenModel], clockCycles: () => Long) {
    def run(cmd: CoSimCmd): String = {
      val c0 = clockCycles()
      val t0 = System.nanoTime()
      val result = cmd.run(game)
      val ms = (System.nanoTime() - t0) / 1000000
      val timing = if (checker.isDefined) s"${clockCycles() - c0} clock cycles, simulated in $ms ms" else s"$ms ms"
      val status = s"$cmd: ${result.status}${if (result.changed) "" else " (no change)"}  [$timing]"
      val check = checker.map { model =>
        val expected = cmd.run(model)
        val diffs = (if (expected != result) Vector(s"result: expected $expected, got $result") else Vector.empty) ++
          Snapshot.differences(model.snapshot, game.snapshot)
        if (diffs.isEmpty) "\nGolden model agrees (board, hints, counters)."
        else "\nMISMATCH with the golden model:\n  " + diffs.mkString("\n  ")
      }.getOrElse("")
      status + check
    }
  }

  private def loop(p: MsParams, mines: Int, seed0: Int, game: MinesweeperImpl, checker: Option[GoldenModel],
      clockCycles: () => Long, commands: Iterator[String], echo: Boolean): Int = {
    val session = new Session(game, checker, clockCycles)
    var seed = seed0
    var showHints = true
    println(session.run(CoSimCmd.New(0, mines, seed)))
    println(game.snapshot.render(showHints) + game.snapshot.summary)
    var running = true
    var linesRead = 0
    while (running && commands.hasNext) {
      val line = commands.next()
      linesRead += 1
      if (echo) println(s"> $line")
      val words = line.trim.split("\\s+").filter(_.nonEmpty).toList
      def num(s: String) = s.nonEmpty && s.forall(_.isDigit) && s.length < 6
      val message = words match {
        case List("q") | List("quit") => running = false; "Bye."
        case List("h") => showHints = !showHints; if (showHints) "Hints on." else "Hints off."
        case List("n") => seed = seed % 0xffff + 1; session.run(CoSimCmd.New(0, mines, seed))
        case List("n", s) if num(s) && s.toInt >= 1 && s.toInt <= 0xffff => seed = s.toInt; session.run(CoSimCmd.New(0, mines, seed))
        case List("r", r, c) if num(r) && num(c) && r.toInt < p.maxRows && c.toInt < p.maxCols =>
          session.run(CoSimCmd.Reveal(r.toInt, c.toInt))
        case List("f", r, c) if num(r) && num(c) && r.toInt < p.maxRows && c.toInt < p.maxCols =>
          session.run(CoSimCmd.Flag(r.toInt, c.toInt))
        case List("a") =>
          val s = game.snapshot
          s.hints.indices.find(i => s.hintsValid && s.hints(i).safe && s.cells(i).kind == CellKind.Hidden) match {
            case Some(i) => session.run(CoSimCmd.Reveal(i / p.maxCols, i % p.maxCols))
            case None => "No safe hint on a hidden cell: you have to guess (or unflag a cell marked s)."
          }
        case Nil => ""
        case _ => help
      }
      if (message.nonEmpty) println(message)
      if (running && words.nonEmpty) println(game.snapshot.render(showHints) + game.snapshot.summary)
    }
    linesRead
  }

  def main(args: Array[String]): Unit = {
    val (beforeScript, script) = args.indexOf("--script") match {
      case -1 => (args.toSeq, None)
      case k => (args.take(k).toSeq ++ args.drop(k + 2), args.lift(k + 1))
    }
    val modelOnly = beforeScript.contains("--model")
    val nums = beforeScript.filterNot(_ == "--model").map(_.toInt)
    val cols = nums.lift(0).getOrElse(6)
    val rows = nums.lift(1).getOrElse(6)
    val mines = nums.lift(2).getOrElse(5)
    val seed = nums.lift(3).getOrElse(1)
    val p = MsParams.forVerification(cols, rows)
    p.validate(GameSettings(0, mines, seed)) match {
      case Left(err) =>
        println(s"Illegal settings: $err (mines must be 0..${p.maxMines(p.presets.head)}, seed 1..65535)")
        return
      case Right(_) =>
    }
    val commands: Iterator[String] = script match {
      case Some(s) => s.split(";").iterator.map(_.trim)
      case None => Iterator.continually { print("> "); Console.flush(); StdIn.readLine() }.takeWhile(_ != null)
    }
    println(help)
    val linesRead =
      if (modelOnly) {
        println(s"${cols}x$rows, $mines mines, on the golden model (plain Scala).")
        loop(p, mines, seed, new GoldenModel(p), None, () => 0L, commands, script.isDefined)
      } else {
        println(s"${cols}x$rows, $mines mines, on the simulated MinesweeperCore, checked by the golden model.")
        var n = 0
        RawTester.test(new MinesweeperCore(p)) { dut =>
          val hw = new ChiselImpl(dut, p)
          hw.init()
          n = loop(p, mines, seed, hw, Some(new GoldenModel(p)), () => hw.cycles, commands, script.isDefined)
        }
        n
      }
    if (script.isEmpty && linesRead == 0)
      println("\nNo keyboard input reached the program. sbt only forwards it to a forked JVM, without -batch:\n" +
        "  sbt \"set Test / run / fork := true\" \"set Test / run / connectInput := true\" " +
        "\"Test/runMain minesweeper.PlayConsole 6 6 5 1\"\nor use --script \"r 2 2; a; q\".")
  }
}
