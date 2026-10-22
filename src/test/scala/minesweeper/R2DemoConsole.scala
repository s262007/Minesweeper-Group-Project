package minesweeper

import chisel3._
import chiseltest._

import scala.io.StdIn

/** Plan-B demo hardware: the two R2 engines side by side, each with its own ports. */
class R2DemoHarness(p: MsParams) extends Module {
  val io = IO(new Bundle {
    val reveal = new RevealIO(p)
    val solver = new SolverIO(p)
  })
  val revealEngine = Module(new RevealEngine(p))
  val solver = Module(new Solver(p))
  io.reveal <> revealEngine.io
  io.solver <> solver.io
}

/** One game in the terminal. Cascades come from the simulated RevealEngine and hints
  * from the simulated Solver. The Scala side only does what MinesweeperCore will do:
  * mine placement (Lfsr16.placeMines, the contract's exact sequence), flags, win and loss.
  * This is not the MVP PlayConsole (R3), which will drive the real core.
  */
final class R2DemoGame(p: MsParams, val mineCount: Int, dut: R2DemoHarness) {
  val g: GridPreset = p.presets.head
  var seed: Int = 1
  var mines = Set.empty[(Int, Int)]
  var revealed = Set.empty[(Int, Int)]
  var flags = Set.empty[(Int, Int)]
  var state = "PLAYING"
  var exploded: Option[(Int, Int)] = None
  var lastReveal: Option[(Int, Int, Int)] = None // (new cells, layers, cycles)
  var hintSafe = Set.empty[(Int, Int)]
  var hintMine = Set.empty[(Int, Int)]
  var solverNote = "no solver run yet"

  // Last values poked per cell, so each run only pokes what changed (much faster in simulation).
  private val revealPoked = Array.fill(p.cells)(-1)
  private val solverPoked = Array.fill(p.cells)(-1)

  private def started = mines.nonEmpty || revealed.nonEmpty
  private def cellOf(i: Int): (Int, Int) = (i / p.maxCols, i % p.maxCols)
  private def index(cell: (Int, Int)): Int = cell._1 * p.maxCols + cell._2
  private def board = RevealBoard(g, mines, flags, revealed)

  def newGame(newSeed: Int): String = {
    require(newSeed >= 1 && newSeed <= 0xffff, "seed must be 1..65535")
    seed = newSeed
    mines = Set.empty; revealed = Set.empty; flags = Set.empty
    state = "PLAYING"; exploded = None; lastReveal = None
    hintSafe = Set.empty; hintMine = Set.empty; solverNote = "no solver run yet"
    s"New ${g.cols}x${g.rows} game, $mineCount mines, seed $seed. Mines are placed at the first reveal."
  }

  def reveal(r: Int, c: Int): String = reveal(r, c, solve = true)

  private def reveal(r: Int, c: Int, solve: Boolean): String = {
    val cell = (r, c)
    if (state != "PLAYING") return s"Game is $state: type n to start a new one."
    if (!board.inGrid(cell)) return s"($r,$c) is outside the ${g.rows}x${g.cols} board."
    if (flags(cell)) return s"($r,$c) is flagged: unflag it first (f $r $c)."
    if (revealed(cell)) return s"($r,$c) is already revealed."
    if (!started) {
      val placed = Lfsr16.placeMines(p, GameSettings(g.id, mineCount, seed), r, c)
      mines = placed.map(cellOf)
    }
    if (mines(cell)) {
      state = "LOST"; exploded = Some(cell)
      hintSafe = Set.empty; hintMine = Set.empty; solverNote = "Game over: no hints."
      return s"BOOM: ($r,$c) was a mine."
    }
    val (fresh, layers, cycles) = runRevealEngine(cell)
    revealed ++= fresh
    lastReveal = Some((fresh.size, layers, cycles))
    if (revealed.size == g.cells - mines.size) {
      state = "WON"; hintSafe = Set.empty; hintMine = Set.empty; solverNote = "Game won: no hints needed."
    } else if (solve) runSolver()
    s"RevealEngine: ${fresh.size} cell(s) in $layers layer(s), $cycles cycle(s)." +
      (if (state == "WON") " All safe cells revealed: you WIN." else "")
  }

  /** Reveals every cell the Solver proved safe, again and again, until none is left. */
  def autoplay(): String = {
    if (state != "PLAYING") return s"Game is $state: type n to start a new one."
    if (!started) return "Reveal a first cell before autoplay."
    var moves = 0
    // Reveal every proven-safe cell (each through the RevealEngine), then ask the Solver again.
    while (state == "PLAYING" && hintSafe.nonEmpty) {
      for (cell <- hintSafe.toSeq.sorted if state == "PLAYING" && !revealed(cell)) {
        reveal(cell._1, cell._2, solve = false)
        moves += 1
      }
      if (state == "PLAYING") runSolver()
    }
    s"Autoplay revealed $moves safe hint(s)." + (state match {
      case "WON" => " The solver finished the game: you WIN."
      case "LOST" => " (a hinted cell was a mine: the solver would be wrong)"
      case _ => " No safe hint left: v1 logic is stuck here, a guess (or v2) is needed."
    })
  }

  def flag(r: Int, c: Int): String = {
    val cell = (r, c)
    if (state != "PLAYING") return s"Game is $state: type n to start a new one."
    if (!board.inGrid(cell)) return s"($r,$c) is outside the ${g.rows}x${g.cols} board."
    if (revealed(cell)) return s"($r,$c) is revealed: no flag there."
    flags = if (flags(cell)) flags - cell else flags + cell
    if (flags(cell)) s"Flag on ($r,$c)." else s"Flag removed from ($r,$c)."
  }

  /** Drives RevealEngine like the Board will: commits each emitted layer on the next edge. */
  private def runRevealEngine(click: (Int, Int)): (Set[(Int, Int)], Int, Int) = {
    val io = dut.io.reveal
    val b = board
    io.clear.poke(false.B)
    for (i <- 0 until p.cells) {
      val cell = cellOf(i)
      val on = b.inGrid(cell)
      val adj = if (on) b.adj(cell) else 0
      val code = (if (on) 1 else 0) | (if (on && revealed(cell)) 2 else 0) | (if (on && flags(cell)) 4 else 0) | (adj << 3)
      if (code != revealPoked(i)) {
        io.view(i).active.poke(on.B)
        io.view(i).revealed.poke((on && revealed(cell)).B)
        io.view(i).flagged.poke((on && flags(cell)).B)
        io.view(i).adj.poke(adj.U)
        revealPoked(i) = code
      }
    }
    pokeConfig(io.start.bits.config)
    io.start.bits.idx.poke(index(click).U)
    io.start.valid.poke(true.B)
    require(io.start.ready.peekBoolean(), "RevealEngine is not ready")
    dut.clock.step()
    io.start.valid.poke(false.B)
    var fresh = Set.empty[(Int, Int)]
    var guard = 0
    while (!io.done.peekBoolean()) {
      val mask = io.revealSet.peek().litValue
      val layer = (0 until p.cells).filter(mask.testBit).map(cellOf).toSet
      fresh ++= layer
      for (cell <- layer) {
        io.view(index(cell)).revealed.poke(true.B)
        revealPoked(index(cell)) |= 2
      }
      dut.clock.step()
      guard += 1
      require(guard <= p.cells + 2, "RevealEngine did not finish")
    }
    val layers = io.layers.peek().litValue.toInt
    val cycles = io.cycles.peek().litValue.toInt
    dut.clock.step()
    (fresh, layers, cycles)
  }

  /** Runs Solver v1 on the masked view (revealed + clue only, never mines or flags). */
  private def runSolver(): Unit = {
    val io = dut.io.solver
    val b = board
    io.clear.poke(false.B)
    for (i <- 0 until p.cells) {
      val cell = cellOf(i)
      val shown = b.inGrid(cell) && revealed(cell)
      val adj = if (shown) b.adj(cell) else 0
      val code = (if (shown) 1 else 0) | (adj << 1)
      if (code != solverPoked(i)) {
        io.board(i).revealed.poke(shown.B)
        io.board(i).adj.poke(adj.U)
        solverPoked(i) = code
      }
    }
    pokeConfig(io.start.bits.config)
    io.start.valid.poke(true.B)
    require(io.start.ready.peekBoolean(), "Solver is not ready")
    dut.clock.step()
    io.start.valid.poke(false.B)
    var guard = 0
    while (!io.done.peekBoolean()) {
      dut.clock.step()
      guard += 1
      require(guard <= p.cells + 2, "Solver did not finish")
    }
    val cycles = io.cycles.peek().litValue.toInt
    if (io.contradiction.peekBoolean()) {
      hintSafe = Set.empty; hintMine = Set.empty
      solverNote = s"Solver: contradiction after $cycles cycle(s) (should never happen in a real game)."
    } else {
      val hints = io.hints.peek() // one peek for the whole vector
      hintSafe = (0 until p.cells).filter(i => hints(i).safe.litToBoolean).map(cellOf).toSet
      hintMine = (0 until p.cells).filter(i => hints(i).mine.litToBoolean).map(cellOf).toSet
      solverNote = s"Solver v1: ${hintSafe.size} safe, ${hintMine.size} mine(s), fixpoint in $cycles cycle(s)."
    }
    dut.clock.step()
  }

  private def pokeConfig(cfg: ActiveConfig): Unit = {
    cfg.preset.poke(g.id.U)
    cfg.cols.poke(g.cols.U)
    cfg.rows.poke(g.rows.U)
    cfg.mines.poke(mineCount.U)
  }

  def render(showHints: Boolean): String = {
    val b = board
    val sb = new StringBuilder
    sb ++= "     " + (0 until g.cols).map(c => f"$c%2d").mkString + "\n"
    for (r <- 0 until g.rows) {
      sb ++= f"  $r%2d "
      for (c <- 0 until g.cols) {
        val cell = (r, c)
        val ch =
          if (exploded.contains(cell)) "X"
          else if (state == "LOST" && mines(cell)) "*"
          else if (state == "WON" && mines(cell)) "F"
          else if (revealed(cell)) b.adj(cell).toString
          else if (flags(cell)) "F"
          else if (showHints && hintSafe(cell)) "s"
          else if (showHints && hintMine(cell)) "m"
          else "."
        sb ++= " " + ch
      }
      sb ++= "\n"
    }
    sb ++= s"  $state | mines left ${if (state == "WON") 0 else mineCount - flags.size}"
    lastReveal.foreach { case (n, l, cy) => sb ++= s" | last reveal: $n cell(s), $l layer(s), $cy cycle(s)" }
    sb ++= s"\n  $solverNote" + (if (showHints) " Hints: s = safe, m = mine." else " (hints hidden)")
    sb.toString
  }
}

/** Interactive (sbt only forwards the keyboard to a forked JVM; do not use -batch):
  *   sbt "set Test / run / fork := true" "set Test / run / connectInput := true" "Test/runMain minesweeper.R2DemoConsole 9 9 10 42"
  * Scripted (works anywhere, including -batch):
  *   sbt "Test/runMain minesweeper.R2DemoConsole 9 9 10 42 --script \"r 4 4; f 0 0; q\""
  * Arguments: cols rows mines seed. Commands: r <row> <col>, f <row> <col>, a, h, n [seed], q.
  * Rows and columns start at 0. See docs/R2_DEMO.md.
  */
object R2DemoConsole {
  val help: String =
    """Commands: r <row> <col> reveal | f <row> <col> flag | a autoplay safe hints | h hints on/off | n [seed] new game | q quit
      |Board: . hidden, F flag, 0-8 revealed, s/m solver hint (safe/mine), * mine, X the mine you hit""".stripMargin

  def main(args: Array[String]): Unit = {
    val (positional, script) = args.indexOf("--script") match {
      case -1 => (args.toSeq, None)
      case k => (args.take(k).toSeq, args.lift(k + 1))
    }
    val nums = positional.map(_.toInt)
    val cols = nums.lift(0).getOrElse(9)
    val rows = nums.lift(1).getOrElse(9)
    val mineCount = nums.lift(2).getOrElse(10)
    val seed = nums.lift(3).getOrElse(1)
    val p = MsParams.forVerification(cols, rows)
    p.validate(GameSettings(0, mineCount, seed)) match {
      case Left(err) =>
        println(s"Illegal settings: $err (mines must be 0..${p.maxMines(p.presets.head)}, seed 1..65535)")
        return
      case Right(_) =>
    }
    val commands: Iterator[String] = script match {
      case Some(s) => s.split(";").iterator.map(_.trim)
      case None => Iterator.continually { print("> "); Console.flush(); StdIn.readLine() }.takeWhile(_ != null)
    }
    println(s"Plan-B demo: ${cols}x$rows, $mineCount mines. Reveal and hints run on the simulated Chisel RevealEngine and Solver.")
    println(help)
    RawTester.test(new R2DemoHarness(p)) { dut =>
      dut.clock.setTimeout(0)
      val game = new R2DemoGame(p, mineCount, dut)
      println(game.newGame(seed))
      var showHints = true
      println(game.render(showHints))
      var running = true
      var linesRead = 0
      while (running && commands.hasNext) {
        val line = commands.next()
        linesRead += 1
        if (script.isDefined) println(s"> $line")
        val words = line.trim.split("\\s+").filter(_.nonEmpty).toList
        val t0 = System.nanoTime()
        val message = words match {
          case List("q") | List("quit") => running = false; "Bye."
          case List("h") => showHints = !showHints; if (showHints) "Hints on." else "Hints off."
          case List("a") => game.autoplay()
          case List("n") => game.newGame(game.seed % 0xffff + 1)
          case List("n", s) if s.forall(_.isDigit) && s.nonEmpty => game.newGame(s.toInt)
          case List("r", r, c) if (r + c).forall(_.isDigit) => game.reveal(r.toInt, c.toInt)
          case List("f", r, c) if (r + c).forall(_.isDigit) => game.flag(r.toInt, c.toInt)
          case Nil => ""
          case _ => help
        }
        val ms = (System.nanoTime() - t0) / 1000000
        if (message.nonEmpty) println(message + (if (words.headOption.exists(Set("r", "a"))) s"  [simulation: $ms ms]" else ""))
        if (running && words.nonEmpty) println(game.render(showHints))
      }
      if (script.isEmpty && linesRead == 0)
        println("\nNo keyboard input reached the program. sbt only forwards it to a forked JVM, without -batch:\n" +
          "  sbt \"set Test / run / fork := true\" \"set Test / run / connectInput := true\" " +
          "\"Test/runMain minesweeper.R2DemoConsole 9 9 10 42\"\nor use --script (see docs/R2_DEMO.md).")
    }
  }
}
