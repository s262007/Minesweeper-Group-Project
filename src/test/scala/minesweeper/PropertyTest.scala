package minesweeper

import chiseltest._
import minesweeper.model._
import minesweeper.model.SolverOracle.Exact
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.collection.mutable

/**
  * Generated games and the properties every completed command must keep (R3-S3). The checks
  * read only the public view and the true layout; they share no code with the model's cascade
  * or solver, nor with the hardware.
  */
object GameProperties {
  type Cell = (Int, Int)

  /** Moves of a generated game. Some name a cell; the others are resolved when played. */
  sealed trait Move
  object Move {
    /** Any coordinate inside the storage: may be padding (Range), a flag (no-op) or a mine (loss). */
    final case class RevealAt(row: Int, col: Int) extends Move
    final case class FlagAt(row: Int, col: Int) extends Move
    /** A hidden cell that is not a mine, chosen with the private layout. */
    final case class RevealSafe(pick: Int) extends Move
    /** A hidden cell with a safe hint. */
    final case class RevealHint(pick: Int) extends Move
    /** Any hidden cell, mine or not: a random click. */
    final case class RevealAny(pick: Int) extends Move
    /** A hidden mine. */
    final case class FlagMine(pick: Int) extends Move
  }

  final case class GameScript(preset: Int, mines: Int, seed: Int, moves: Vector[Move])

  def neighbours(c: Cell, rows: Int, cols: Int): Seq[Cell] =
    for (dr <- -1 to 1; dc <- -1 to 1 if (dr, dc) != ((0, 0));
         r = c._1 + dr; cc = c._2 + dc if r >= 0 && r < rows && cc >= 0 && cc < cols) yield (r, cc)

  def count(truth: Set[Cell], c: Cell, rows: Int, cols: Int): Int = neighbours(c, rows, cols).count(truth)

  /**
    * The cells a click on `start` reveals, by breadth-first search from it: a cell with no mine
    * around it opens its neighbours, except revealed and flagged ones. Also returns the number
    * of layers (largest distance from the click, plus one).
    */
  def cascade(rows: Int, cols: Int, truth: Set[Cell], start: Cell, revealed: Set[Cell], flagged: Set[Cell]): (Set[Cell], Int) = {
    val dist = mutable.Map(start -> 0)
    val queue = mutable.Queue(start)
    while (queue.nonEmpty) {
      val c = queue.dequeue()
      if (count(truth, c, rows, cols) == 0)
        for (n <- neighbours(c, rows, cols) if !revealed(n) && !flagged(n) && !dist.contains(n)) {
          dist(n) = dist(c) + 1
          queue.enqueue(n)
        }
    }
    (dist.keySet.toSet, dist.values.max + 1)
  }

  def cellsOf(s: Snapshot, kind: CellKind): Set[Cell] =
    s.cells.indices.filter(i => s.cells(i).active && s.cells(i).kind == kind).map(i => (i / s.maxCols, i % s.maxCols)).toSet

  /** The command a move stands for in this view, or None if no cell fits. */
  def resolve(s: Snapshot, truth: Set[Cell], move: Move): Option[CoSimCmd] = {
    def pickFrom(cells: Seq[Cell], pick: Int) = if (cells.isEmpty) None else Some(cells(pick % cells.size))
    val hidden = cellsOf(s, CellKind.Hidden).toVector.sorted
    move match {
      case Move.RevealAt(r, c) => Some(CoSimCmd.Reveal(r, c))
      case Move.FlagAt(r, c) => Some(CoSimCmd.Flag(r, c))
      // Before the first reveal the layout is empty, and the first click is always safe.
      case Move.RevealSafe(k) => pickFrom(hidden.filterNot(truth), k).map { case (r, c) => CoSimCmd.Reveal(r, c) }
      case Move.RevealHint(k) =>
        val hinted = if (s.hintsValid) hidden.filter { case (r, c) => s.hints(r * s.maxCols + c).safe } else Vector.empty
        pickFrom(hinted, k).map { case (r, c) => CoSimCmd.Reveal(r, c) }
      case Move.RevealAny(k) => pickFrom(hidden, k).map { case (r, c) => CoSimCmd.Reveal(r, c) }
      case Move.FlagMine(k) => pickFrom(hidden.filter(truth), k).map { case (r, c) => CoSimCmd.Flag(r, c) }
    }
  }

  /** Every property broken by one completed command, as messages; empty when all hold. */
  def violations(before: Snapshot, cmd: CoSimCmd, res: CmdResult, after: Snapshot, truth: Set[Cell]): Vector[String] = {
    val out = Vector.newBuilder[String]
    def check(ok: Boolean, what: => String): Unit = if (!ok) out += what
    val (rows, cols, stride) = (after.rows, after.cols, after.maxCols)
    def idx(c: Cell): Int = c._1 * stride + c._2
    val active = for (r <- 0 until rows; c <- 0 until cols) yield (r, c)
    val numbers = cellsOf(after, CellKind.Number)
    val flags = cellsOf(after, CellKind.Flagged)
    val shown = cellsOf(after, CellKind.Mine)
    val numbersBefore = cellsOf(before, CellKind.Number)
    val flagsBefore = cellsOf(before, CellKind.Flagged)

    // A completed command on a real game ends in a stable state, never in Error.
    check(res.status != Status.InternalError && after.state != State.Error, s"internal error (state ${after.state})")
    check(Seq(State.Idle, State.Playing, State.Won, State.Lost).contains(after.state), s"unstable state ${after.state}")
    check(after.cells.indices.forall(i => after.cells(i).active == (i / stride < rows && i % stride < cols)),
      "the active cells are not the active grid")
    check(after.cells.forall(c => c.active || c == PublicCell.padding), "a padding cell is not blank")

    // Revision; a command that changes nothing leaves the whole view as it was.
    check(after.revision == (if (res.changed) (before.revision + 1) & 0xffffffffL else before.revision),
      s"revision ${before.revision} -> ${after.revision} with changed = ${res.changed}")
    if (!res.changed) check(after == before, "a command that changed nothing altered the view")

    check(after.minesLeft == after.mines - flags.size, s"minesLeft ${after.minesLeft}, expected ${after.mines} - ${flags.size} flags")

    // Revealed numbers are safe cells with the right count.
    for (c <- numbers) {
      check(!truth(c), s"mine $c shown as a number")
      check(after.cells(idx(c)).adj == count(truth, c, rows, cols),
        s"$c shows ${after.cells(idx(c)).adj}, it has ${count(truth, c, rows, cols)} mines around it")
    }
    // Mines are shown only after a loss, and then all of them.
    if (after.state == State.Lost) check(shown == truth, s"after the loss the view shows mines $shown, the layout is $truth")
    else check(shown.isEmpty, s"mines shown in state ${after.state}")
    check(after.exploded.isDefined == (after.state == State.Lost), s"exploded = ${after.exploded} in state ${after.state}")

    // Won exactly when every safe cell is revealed.
    val allSafeRevealed = active.forall(c => truth(c) || numbers(c))
    check((after.state == State.Won) == allSafeRevealed, s"state ${after.state} while all safe cells revealed = $allSafeRevealed")

    // Hints: only on unrevealed cells, never both, true for the layout, and certain for the clues.
    if (after.hintsValid) {
      for (i <- after.hints.indices if after.hints(i) != Hint.none) {
        val (h, c, cell) = (after.hints(i), (i / stride, i % stride), after.cells(i))
        check(cell.active && (cell.kind == CellKind.Hidden || cell.kind == CellKind.Flagged), s"hint on $c, which is $cell")
        check(!(h.safe && h.mine), s"hint on $c is both safe and mine")
        check(!h.safe || !truth(c), s"safe hint on mine $c")
        check(!h.mine || truth(c), s"mine hint on safe cell $c")
      }
      val hintSafe = active.filter(c => after.hints(idx(c)).safe).toSet
      val hintMine = active.filter(c => after.hints(idx(c)).mine).toSet
      SolverOracle.certain(RefGrid(rows, cols), numbers.map(c => c -> after.cells(idx(c)).adj).toMap) match {
        case e: Exact => check(hintSafe.subsetOf(e.safe) && hintMine.subsetOf(e.mine),
          s"hints the oracle cannot prove: safe ${hintSafe -- e.safe}, mine ${hintMine -- e.mine}")
        case SolverOracle.Inconsistent(_) => check(ok = false, "the oracle finds the revealed numbers inconsistent")
        case SolverOracle.GaveUp(_, _) => // too large to check here; boards in these tests are small
      }
    } else check(after.hints.forall(_ == Hint.none), "hints shown while hintsValid is low")
    check(after.state != State.Playing || after.hintsValid, "Playing without valid hints")

    val finishedBefore = before.state == State.Won || before.state == State.Lost
    cmd match {
      case CoSimCmd.New(_, _, _) =>
        if (res.status == Status.Ok)
          check(after.state == State.Idle && numbers.isEmpty && flags.isEmpty && !after.hintsValid &&
            after.minesLeft == after.mines && after.exploded.isEmpty && truth.isEmpty, "NewGame did not clear the game")
      case CoSimCmd.Reveal(r, c) =>
        check(numbersBefore.subsetOf(numbers), s"revealed cells hidden again: ${numbersBefore -- numbers}")
        if (finishedBefore) check(!res.changed, "a reveal changed a finished game")
        else if (res.changed && after.state == State.Lost) {
          check(truth((r, c)) && after.exploded.contains(idx((r, c))), s"lost, but ($r,$c) is not the exploded mine")
          check(numbers == numbersBefore, "the losing click revealed numbers")
        } else if (res.changed) {
          val (expected, layers) = cascade(rows, cols, truth, (r, c), numbersBefore, flagsBefore)
          check(numbers -- numbersBefore == expected,
            s"reveal of ($r,$c) opened ${(numbers -- numbersBefore).toSeq.sorted}, the search gives ${expected.toSeq.sorted}")
          check(after.revealLayers == layers, s"revealLayers ${after.revealLayers}, the search gives $layers")
        }
      case CoSimCmd.Flag(r, c) =>
        if (finishedBefore) check(!res.changed, "a flag changed a finished game")
        else if (res.changed) {
          check(numbers == numbersBefore, "a flag changed the revealed cells")
          check(flags == (if (flagsBefore((r, c))) flagsBefore - ((r, c)) else flagsBefore + ((r, c))), s"flag ($r,$c) did not toggle")
          // CONTRACT 7: user flags do not change deductions.
          if (before.hintsValid && after.hintsValid) check(after.hints == before.hints, s"flag ($r,$c) changed the hints")
        }
    }
    out.result()
  }

  /**
    * Plays one script: NewGame, then each move that resolves to a command. `run` applies a
    * command to the implementations, `view` is the view under test; the true layout is the
    * model's. Throws with the history and both views on the first broken property.
    */
  def play(script: GameScript, model: GoldenModel, run: CoSimCmd => CmdResult, view: () => Snapshot): Played = {
    val history = mutable.ArrayBuffer.empty[String]
    def step(cmd: CoSimCmd): Unit = {
      val before = view()
      history += cmd.toString
      val res = run(cmd)
      val after = view()
      val broken = violations(before, cmd, res, after, model.mineCells)
      if (broken.nonEmpty) throw new AssertionError(
        s"""${broken.mkString("\n")}
           |Script: $script
           |History: ${history.mkString(" | ")}
           |Mines: ${model.mineCells.toSeq.sorted.mkString(" ")}
           |Before: ${before.summary}
           |${before.render()}After: ${after.summary}
           |${after.render()}""".stripMargin)
    }
    step(CoSimCmd.New(script.preset, script.mines, script.seed))
    for (move <- script.moves; cmd <- resolve(view(), model.mineCells, move)) step(cmd)
    Played(history.size, view().state)
  }

  /** What one played script covered: the commands run and the final state. */
  final case class Played(commands: Int, end: State)

  /** Totals over the played scripts, for the test report. */
  final class Tally {
    private var games, commands = 0
    private val ends = mutable.Map.empty[State, Int].withDefaultValue(0)
    def add(p: Played): Unit = { games += 1; commands += p.commands; ends(p.end) += 1 }
    override def toString: String =
      s"$games games, $commands commands; ended won ${ends(State.Won)}, lost ${ends(State.Lost)}, " +
        s"playing ${ends(State.Playing)}, idle ${ends(State.Idle)}"
  }

  /** Capacities with rows and columns in 2..9, sometimes with a second, smaller preset. */
  val genParams: Gen[MsParams] = for {
    cols <- Gen.choose(2, 9)
    rows <- Gen.choose(2, 9)
    smaller <- Gen.option(Gen.zip(Gen.choose(2, cols), Gen.choose(2, rows)))
  } yield {
    val extra = smaller.filter(_ != ((cols, rows))).toVector.map { case (c, r) => GridPreset(1, c, r) }
    MsParams(maxCols = cols, maxRows = rows, presets = GridPreset(0, cols, rows) +: extra,
      minMines = 0, defaultPresetId = 0, defaultMines = 0, verification = true)
  }

  def genMove(p: MsParams): Gen[Move] = {
    val pick = Gen.choose(0, 1 << 20)
    val cell = Gen.zip(Gen.choose(0, p.maxRows - 1), Gen.choose(0, p.maxCols - 1))
    Gen.frequency(
      5 -> pick.map(Move.RevealSafe),
      3 -> pick.map(Move.RevealHint),
      1 -> pick.map(Move.RevealAny),
      2 -> pick.map(Move.FlagMine),
      1 -> cell.map { case (r, c) => Move.FlagAt(r, c) },
      1 -> cell.map { case (r, c) => Move.RevealAt(r, c) })
  }

  /**
    * A legal NewGame (mine counts at both ends and between), a first click, and up to `maxMoves`
    * more moves. Moves after the end of the game are kept: they must be no-ops.
    */
  def genScript(p: MsParams, maxMoves: Int): Gen[GameScript] = for {
    g <- Gen.oneOf(p.presets)
    mines <- Gen.frequency(1 -> Gen.const(p.minMines), 1 -> Gen.const(p.maxMines(g)), 4 -> Gen.choose(p.minMines, p.maxMines(g)))
    seed <- Gen.choose(1, 0xffff)
    first <- Gen.choose(0, 1 << 20)
    n <- Gen.choose(0, maxMoves)
    moves <- Gen.listOfN(n, genMove(p))
  } yield GameScript(g.id, mines, seed, Move.RevealSafe(first) +: moves.toVector)
}

class PropertyTest extends AnyFlatSpec with ChiselScalatestTester with ScalaCheckPropertyChecks {
  import GameProperties._

  private val modelGames: Gen[(MsParams, GameScript)] =
    for (p <- genParams; s <- genScript(p, 2 * p.cells)) yield (p, s)

  "The golden model" should "keep the game properties on generated games" in {
    val tally = new Tally
    forAll(modelGames, minSuccessful(500)) { case (p, script) =>
      val model = new GoldenModel(p)
      tally.add(play(script, model, cmd => cmd.run(model), () => model.snapshot))
    }
    info(tally.toString)
  }

  // One build for the hardware run: elaboration is the slow part. Four presets of 2 to 6
  // columns and 2 to 5 rows, so most games leave padding on one or both axes.
  private val hwParams = MsParams(
    maxCols = 6, maxRows = 5,
    presets = Vector(GridPreset(0, 6, 5), GridPreset(1, 4, 4), GridPreset(2, 5, 2), GridPreset(3, 2, 3)),
    minMines = 0, defaultPresetId = 0, defaultMines = 0, verification = true)

  "The hardware" should "keep the game properties and match the model on generated games" in {
    test(new MinesweeperCore(hwParams)) { dut =>
      val hw = new ChiselImpl(dut, hwParams)
      hw.init()
      val model = new GoldenModel(hwParams)
      val ls = new Lockstep(hwParams, "generated games", model, hw)
      ls.check()
      // Each game starts with NewGame on the same build, so it also checks that nothing leaks
      // from the previous game.
      val tally = new Tally
      val start = System.nanoTime()
      var slowest = (0L, 0.0) // clock cycles and seconds of the slowest game
      // The default simulator takes about 0.2 s per clock cycle on a laptop, and a generated game
      // 20 to 140 cycles: 12 short games take 2 to 4 minutes there, about a third of that in CI,
      // within the story's CI budget (under 3 minutes for this file).
      forAll(genScript(hwParams, 7), minSuccessful(12)) { script =>
        val (c0, t0) = (hw.cycles, System.nanoTime())
        tally.add(play(script, model, cmd => ls(cmd), () => hw.snapshot))
        val game = (hw.cycles - c0, (System.nanoTime() - t0) / 1e9)
        if (game._2 > slowest._2) slowest = game
      }
      info(f"$tally; ${hw.cycles} clock cycles in ${(System.nanoTime() - start) / 1e9}%.0f s, " +
        f"slowest game ${slowest._1} cycles in ${slowest._2}%.0f s")
    }
  }
}
