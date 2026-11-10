package minesweeper

import chiseltest.RawTester
import minesweeper.model._
import minesweeper.model.SolverOracle.{Exact, GaveUp}

import java.io.{File, PrintWriter}
import scala.util.Random

/**
  * R3-S4 step 2: plays games with the solver hints and writes results/solver_stats.csv.
  *
  * {{{
  * sbt "Test/runMain minesweeper.AutoPlay"            1000 model games per setting, 25 hardware games per size
  * sbt "Test/runMain minesweeper.AutoPlay 100 2"      fewer games: model games, hardware games per size
  * }}}
  *
  * The player reveals every safe hint. When there is none, it guesses: a hidden cell that is not a
  * mine hint, drawn with a seeded RNG. The first click is always safe and is not counted as a guess.
  * Flags are never placed (they do not change the hints).
  *
  * On every board where the player chooses a move (a "decision board"), the hints are measured
  * against SolverOracle: the cells certain from the revealed numbers alone, and those certain when
  * the total mine count is also used. v1 hints come from the implementation (the hardware or the
  * golden model); v2 hints from ModelSolver.solveV2, since MinesweeperCore builds the v1 Solver.
  *
  * Hardware games run in lockstep with the golden model (every public field compared after every
  * command), and the same games are replayed on the model alone: their records must be equal.
  */
object AutoPlay {
  type Cell = (Int, Int)

  /** One game; the measures are summed over its decision boards. */
  final case class GameRecord(
      won: Boolean,
      guesses: Int,
      commands: Int,
      boards: Int,
      certain: Int,
      foundV1: Int,
      foundV2: Int,
      certainWithTotal: Int,
      gaveUp: Int,
      solverCycles: Long,
      solverRuns: Int
  ) {
    /** The part both implementations must agree on: solverCycles is the hardware's cycle count, or the model's rounds. */
    def comparable: GameRecord = copy(solverCycles = 0, solverRuns = 0)
  }

  final case class Setting(label: String, p: MsParams, mines: Int)

  /** Beginner, 9x9 with 10 mines, and 9x9 with 14 mines (the production default game). */
  val modelSettings: Seq[Setting] = Seq(
    Setting("9x9", MsParams.forVerification(9, 9), 10),
    Setting("9x9", MsParams.forVerification(9, 9), 14))

  /** About one cell in six is a mine, as in Beginner. */
  val hardwareSettings: Seq[Setting] = Seq(
    Setting("4x4", MsParams.forVerification(4, 4), 3),
    Setting("7x5", MsParams.forVerification(7, 5), 6))

  /** Game i of every setting uses this seed, so v1 and v2 players and model and hardware play the same layouts. */
  def gameSeed(i: Int): Int = 1 + ((i.toLong * 40503L + 12345L) % 0xffff).toInt
  def guessRng(i: Int): Random = new Random(1000003L * (i + 1))

  private def at(s: Snapshot, c: Cell): Int = c._1 * s.maxCols + c._2
  private def hidden(s: Snapshot): Vector[Cell] = GameProperties.cellsOf(s, CellKind.Hidden).toVector.sorted

  /**
    * Plays one game. `run` applies a command, `view` reads the view of the implementation under
    * test. With `useV2` the player follows ModelSolver.solveV2 instead of the implementation's hints.
    */
  def play(run: CoSimCmd => CmdResult, view: () => Snapshot, preset: Int, mines: Int, seed: Int,
           useV2: Boolean, rng: Random): GameRecord = {
    var commands = 0
    var solverCycles = 0L
    var solverRuns = 0
    def command(cmd: CoSimCmd): Unit = {
      run(cmd)
      commands += 1
      val c = view().solverCycles
      if (c > 0) { solverCycles += c; solverRuns += 1 }
    }
    command(CoSimCmd.New(preset, mines, seed))
    val first = hidden(view())
    val (r0, c0) = first(rng.nextInt(first.size))
    command(CoSimCmd.Reveal(r0, c0))

    var guesses, boards, certain, foundV1, foundV2, certainWithTotal, gaveUp = 0
    while (view().state == State.Playing) {
      val s = view()
      val grid = RefGrid(s.rows, s.cols)
      val clues = GameProperties.cellsOf(s, CellKind.Number).map(c => c -> s.cells(at(s, c)).adj).toMap
      val v1Safe = grid.cells.filter(c => s.hintsValid && s.hints(at(s, c)).safe).toSet
      val v1Mine = grid.cells.filter(c => s.hintsValid && s.hints(at(s, c)).mine).toSet
      val v2 = ModelSolver.solveV2(grid, clues)
      require(!v2.contradiction, s"ModelSolver v2 found a contradiction on a real game: $clues")

      (SolverOracle.certain(grid, clues), SolverOracle.certain(grid, clues, Some(s.mines))) match {
        case (e: Exact, t: Exact) =>
          require(v1Safe.subsetOf(e.safe) && v1Mine.subsetOf(e.mine), s"a v1 hint is not certain: $clues")
          require(v2.safe.subsetOf(e.safe) && v2.mine.subsetOf(e.mine), s"a v2 hint is not certain: $clues")
          boards += 1
          certain += e.safe.size + e.mine.size
          foundV1 += v1Safe.size + v1Mine.size
          foundV2 += v2.safe.size + v2.mine.size
          certainWithTotal += t.safe.size + t.mine.size
        case (_: GaveUp, _) | (_, _: GaveUp) => gaveUp += 1
        case other => throw new IllegalStateException(s"the oracle finds a real game inconsistent: $other, $clues")
      }

      val (safe, mine) = if (useV2) (v2.safe, v2.mine) else (v1Safe, v1Mine)
      val todo = hidden(s).filter(safe)
      if (todo.nonEmpty) {
        for ((r, c) <- todo if view().state == State.Playing && hidden(view()).contains((r, c)))
          command(CoSimCmd.Reveal(r, c))
      } else {
        val options = hidden(s).filterNot(mine)
        val (r, c) = options(rng.nextInt(options.size))
        command(CoSimCmd.Reveal(r, c))
        guesses += 1
      }
    }
    val end = view().state
    require(end == State.Won || end == State.Lost, s"the game ended in $end")
    GameRecord(end == State.Won, guesses, commands, boards, certain, foundV1, foundV2, certainWithTotal, gaveUp,
      solverCycles, solverRuns)
  }

  def modelGames(s: Setting, useV2: Boolean, games: Int): Seq[GameRecord] =
    (0 until games).map { i =>
      val model = new GoldenModel(s.p)
      play(cmd => cmd.run(model), () => model.snapshot, 0, s.mines, gameSeed(i), useV2, guessRng(i))
    }

  /** v1 games on a simulated MinesweeperCore, in lockstep with the golden model. */
  def hardwareGames(s: Setting, games: Int): Seq[GameRecord] = {
    var out = Seq.empty[GameRecord]
    RawTester.test(new MinesweeperCore(s.p)) { dut =>
      val hw = new ChiselImpl(dut, s.p)
      hw.init()
      val ls = new Lockstep(s.p, s"autoplay ${s.label}", new GoldenModel(s.p), hw)
      out = (0 until games).map { i =>
        play(cmd => ls(cmd), () => hw.snapshot, 0, s.mines, gameSeed(i), useV2 = false, guessRng(i))
      }
    }
    out
  }

  /** One line of the CSV: the games of one source, setting and player. */
  final case class Row(source: String, setting: Setting, player: String, games: Seq[GameRecord]) {
    private def pct(a: Long, b: Long): String = if (b == 0) "" else f"${100.0 * a / b}%.1f"
    def csv: String = {
      val g = games
      val won = g.count(_.won)
      val guesses = g.map(_.guesses).sum
      val certain = g.map(_.certain.toLong).sum
      val withTotal = g.map(_.certainWithTotal.toLong).sum
      val (v1, v2) = (g.map(_.foundV1.toLong).sum, g.map(_.foundV2.toLong).sum)
      val runs = g.map(_.solverRuns).sum
      val cycles = if (source == "hardware" && runs > 0) f"${g.map(_.solverCycles).sum.toDouble / runs}%.1f" else ""
      Seq(source, setting.label, setting.mines, player, g.size, won, g.size - won, g.count(r => r.won && r.guesses == 0),
        guesses, f"${guesses.toDouble / g.size}%.2f", g.map(_.boards).sum, certain, v1, v2, pct(v1, certain), pct(v2, certain),
        withTotal, pct(v1, withTotal), pct(v2, withTotal), g.map(_.gaveUp).sum, cycles).mkString(",")
    }
  }

  val header: String = Seq("source", "grid", "mines", "player", "games", "won", "lost", "won_without_guessing",
    "guesses", "guesses_per_game", "decision_boards", "oracle_certain", "found_v1", "found_v2", "v1_percent",
    "v2_percent", "oracle_certain_with_total", "v1_percent_with_total", "v2_percent_with_total", "oracle_gave_up",
    "hw_solver_cycles_mean").mkString(",")

  /**
    * Every row: model games on the 9x9 settings with v1 and v2 players; then, per hardware size,
    * the hardware v1 games, the same games on the model (must be equal) and a v2 player on them.
    */
  def collect(modelCount: Int, hardwareCount: Int, log: String => Unit = println): Seq[Row] = {
    val rows = Seq.newBuilder[Row]
    for (s <- modelSettings; (player, v2) <- Seq("v1" -> false, "v2" -> true)) {
      log(s"model ${s.label}, ${s.mines} mines, $player: $modelCount games")
      rows += Row("model", s, player, modelGames(s, v2, modelCount))
    }
    if (hardwareCount > 0) for (s <- hardwareSettings) {
      log(s"hardware ${s.label}, ${s.mines} mines, v1: $hardwareCount games")
      val hw = hardwareGames(s, hardwareCount)
      val model = modelGames(s, useV2 = false, hardwareCount)
      for (i <- hw.indices)
        require(hw(i).comparable == model(i).comparable,
          s"${s.label} game $i (seed ${gameSeed(i)}): hardware ${hw(i)}, model ${model(i)}")
      rows += Row("hardware", s, "v1", hw)
      rows += Row("model", s, "v1", model)
      rows += Row("model", s, "v2", modelGames(s, useV2 = true, hardwareCount))
    }
    rows.result()
  }

  def write(path: String, rows: Seq[Row]): Unit = {
    val f = new File(path)
    Option(f.getParentFile).foreach(_.mkdirs())
    val out = new PrintWriter(f, "UTF-8")
    // "\n" rather than println: the same file on every system.
    try (header +: rows.map(_.csv)).foreach(line => out.print(line + "\n")) finally out.close()
  }

  def main(args: Array[String]): Unit = {
    val modelCount = args.lift(0).map(_.toInt).getOrElse(1000)
    val hardwareCount = args.lift(1).map(_.toInt).getOrElse(25)
    val path = args.lift(2).getOrElse("results/solver_stats.csv")
    val start = System.nanoTime()
    val rows = collect(modelCount, hardwareCount)
    write(path, rows)
    println(f"Wrote $path in ${(System.nanoTime() - start) / 1e9}%.0f s:")
    (header +: rows.map(_.csv)).foreach(println)
  }
}
