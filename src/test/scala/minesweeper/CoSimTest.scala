package minesweeper

import chiseltest._
import minesweeper.model._
import org.scalatest.flatspec.AnyFlatSpec

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/** Commands for lockstep runs; toString is the history line printed on a mismatch. */
sealed trait CoSimCmd { def run(impl: MinesweeperImpl): CmdResult }
object CoSimCmd {
  final case class New(preset: Int, mines: Int, seed: Int) extends CoSimCmd {
    def run(impl: MinesweeperImpl): CmdResult = impl.newGame(preset, mines, seed)
    override def toString: String = f"NEW preset $preset, $mines mines, seed 0x$seed%04x"
  }
  final case class Reveal(row: Int, col: Int) extends CoSimCmd {
    def run(impl: MinesweeperImpl): CmdResult = impl.reveal(row, col)
    override def toString: String = s"REVEAL $row $col"
  }
  final case class Flag(row: Int, col: Int) extends CoSimCmd {
    def run(impl: MinesweeperImpl): CmdResult = impl.flag(row, col)
    override def toString: String = s"FLAG $row $col"
  }
}

/** Runs the golden model and the hardware side by side and compares them after every command. */
final class Lockstep(val p: MsParams, label: String, val model: GoldenModel, val hw: MinesweeperImpl) {
  private val history = ArrayBuffer.empty[String]

  def apply(cmd: CoSimCmd): CmdResult = {
    history += cmd.toString
    val expected = cmd.run(model)
    val actual = cmd.run(hw)
    val diffs = (if (expected != actual) Vector(s"result: expected $expected, got $actual") else Vector.empty) ++
      Snapshot.differences(model.snapshot, hw.snapshot)
    if (diffs.nonEmpty) throw new AssertionError(report(diffs))
    expected
  }

  def check(): Unit = {
    val diffs = Snapshot.differences(model.snapshot, hw.snapshot)
    if (diffs.nonEmpty) throw new AssertionError(report(diffs))
  }

  private def report(diffs: Vector[String]): String = {
    val truth = model.mineCells.toSeq.sorted.mkString(" ")
    s"""Co-simulation mismatch in $label after ${history.size} commands
       |${diffs.mkString("\n")}
       |History (last 40): ${history.takeRight(40).mkString(" | ")}
       |Mines (model): $truth
       |Model ${model.snapshot.summary}
       |${model.snapshot.render()}
       |Hardware ${hw.snapshot.summary}
       |${hw.snapshot.render()}""".stripMargin
  }
}

class CoSimTest extends AnyFlatSpec with ChiselScalatestTester {
  import CoSimCmd._

  // Verification capacities: several presets per build, rectangular grids, padding on both axes.
  private val square = MsParams.forVerification(4, 4)
  private val wide = MsParams.forVerification(7, 5).copy(
    presets = Vector(GridPreset(0, 4, 4), GridPreset(1, 7, 5), GridPreset(2, 6, 5)))
  private val tall = MsParams.forVerification(5, 6).copy(
    presets = Vector(GridPreset(0, 5, 6), GridPreset(1, 3, 2)))

  private def lockstep(p: MsParams, label: String)(body: Lockstep => Unit): Unit =
    test(new MinesweeperCore(p)) { dut =>
      val hw = new ChiselImpl(dut, p)
      hw.init()
      val ls = new Lockstep(p, label, new GoldenModel(p), hw)
      ls.check()
      body(ls)
    }

  private def terminal(s: Snapshot) = s.state == State.Won || s.state == State.Lost || s.state == State.Error
  private def cellsWhere(s: Snapshot)(f: PublicCell => Boolean): Vector[(Int, Int)] =
    s.cells.indices.filter(i => s.cells(i).active && f(s.cells(i))).map(i => (i / s.maxCols, i % s.maxCols)).toVector

  /** Reveals every safe hidden cell, using the model's private layout, until the game is won. */
  private def playToWin(ls: Lockstep): Unit = {
    var guard = 0
    while (ls.model.snapshot.state == State.Playing && guard < ls.p.cells) {
      val truth = ls.model.mineCells
      val next = cellsWhere(ls.model.snapshot)(c => c.kind != CellKind.Number).find(c => !truth(c)).get
      if (ls.model.snapshot.cells(next._1 * ls.p.maxCols + next._2).kind == CellKind.Flagged) ls(Flag(next._1, next._2))
      ls(Reveal(next._1, next._2))
      guard += 1
    }
    assert(ls.model.snapshot.state == State.Won)
  }

  private def randomNew(p: MsParams, rnd: Random, allowInvalid: Boolean): New = {
    val g = p.presets(rnd.nextInt(p.presets.size))
    val maxMines = p.maxMines(g)
    val mines = rnd.nextInt(4) match {
      case 0 => p.minMines
      case 1 => maxMines
      case _ => p.minMines + rnd.nextInt(maxMines - p.minMines + 1)
    }
    val seed = 1 + rnd.nextInt(0xffff)
    if (!allowInvalid || rnd.nextInt(4) != 0) New(g.id, mines, seed)
    else rnd.nextInt(3) match {
      case 0 if p.presets.size < (1 << p.presetW) => New(p.presets.size, mines, seed)
      case 1 => New(g.id, maxMines + 1, seed)
      case _ => New(g.id, mines, 0)
    }
  }

  private def randomRun(ls: Lockstep, rnd: Random, commands: Int): Unit = {
    val p = ls.p
    for (_ <- 0 until commands) {
      val s = ls.model.snapshot
      val cmd: CoSimCmd =
        if (terminal(s) && rnd.nextInt(4) != 0) randomNew(p, rnd, allowInvalid = false)
        else rnd.nextInt(20) match {
          case 0 => randomNew(p, rnd, allowInvalid = true)
          case 1 if s.rows < p.maxRows => Reveal(s.rows + rnd.nextInt(p.maxRows - s.rows), rnd.nextInt(p.maxCols))
          case 2 if s.cols < p.maxCols => Flag(rnd.nextInt(p.maxRows), s.cols + rnd.nextInt(p.maxCols - s.cols))
          case 3 | 4 | 5 => Flag(rnd.nextInt(s.rows), rnd.nextInt(s.cols))
          case 6 => Reveal(rnd.nextInt(s.rows), rnd.nextInt(s.cols))
          case _ =>
            val hidden = cellsWhere(s)(_.kind == CellKind.Hidden)
            if (hidden.isEmpty) Reveal(0, 0) else { val (r, c) = hidden(rnd.nextInt(hidden.size)); Reveal(r, c) }
        }
      ls(cmd)
    }
  }

  "Co-simulation" should "match the model from reset and on invalid settings and coordinates" in {
    lockstep(wide, "invalid inputs") { ls =>
      ls(New(3, 2, 1)) // no preset 3
      ls(New(0, 8, 1)) // 4x4 holds at most 7 mines
      ls(New(0, 7, 0)) // seed zero
      ls(Reveal(0, 0)) // default 4x4 game with no mines: the first click wins
      ls(New(0, 7, 0x1d))
      ls(Reveal(4, 0)) // row 4 is padding in a 4x4 game
      ls(Flag(0, 6))
      ls(Reveal(3, 3))
      ls(Reveal(3, 3)) // repeated reveal: no-op
    }
  }

  it should "match with flags before the first click, wrong flags and flagged reveals" in {
    lockstep(wide, "flags") { ls =>
      ls(New(1, 8, 0x2a))
      ls(Flag(4, 6))
      ls(Flag(4, 6))
      ls(Flag(2, 3))
      ls(Reveal(2, 3)) // flagged: no generation
      ls(Reveal(0, 0))
      val s = ls.model.snapshot
      val truth = ls.model.mineCells
      for ((r, c) <- cellsWhere(s)(_.kind == CellKind.Hidden).filterNot(truth).take(2)) ls(Flag(r, c)) // wrong flags
      for ((r, c) <- truth.take(2)) ls(Flag(r, c))
      for ((r, c) <- cellsWhere(ls.model.snapshot)(_.kind == CellKind.Number).take(2)) ls(Flag(r, c)) // no-ops
      ls(Flag(2, 3))
    }
  }

  it should "match through a won game, a lost game and the moves after them" in {
    lockstep(wide, "win and loss") { ls =>
      ls(New(1, 10, 0x0bad))
      ls(Reveal(2, 3))
      playToWin(ls)
      ls(Reveal(0, 0))
      ls(Flag(0, 0))
      ls(New(2, 12, 0x4321))
      ls(Reveal(0, 5))
      val truth = ls.model.mineCells.toSeq.sorted
      ls(Flag(truth.head._1, truth.head._2))
      ls(Reveal(truth.last._1, truth.last._2))
      assert(ls.model.snapshot.state == State.Lost)
      ls(Reveal(truth.head._1, truth.head._2))
      ls(New(2, 12, 0x4321))
    }
  }

  it should "match when a large game is followed by a smaller preset" in {
    lockstep(wide, "preset switch") { ls =>
      ls(New(1, wide.maxMines(wide.presets(1)), 0xffff))
      ls(Reveal(4, 6))
      ls(New(1, 5, 0x77))
      ls(Reveal(2, 3))
      ls(New(0, 3, 0x77)) // stale 7x5 cells must not leak into the 4x4 game
      ls(Reveal(3, 0))
      playToWin(ls)
      ls(New(2, 0, 9))
      ls(Reveal(4, 5)) // no mines: one cascade wins
    }
    lockstep(tall, "tall preset switch") { ls =>
      ls(New(0, 6, 0x123))
      ls(Reveal(5, 2))
      ls(New(1, 0, 1)) // 3x2 game, no mine fits outside the protected area
      ls(Reveal(1, 2))
    }
  }

  private val capacities = Seq(square -> "4x4", wide -> "7x5 with 3 presets", tall -> "5x6 with 2 presets")

  it should "match on a random command sequence on every verification capacity" in {
    for ((p, label) <- capacities)
      lockstep(p, s"random $label, seed 1")(ls => randomRun(ls, new Random(1), 40))
  }

  // The two runs below took 3 h 8 min together on a laptop (24 October 2026). CI runs every
  // test, Slow ones included, so they only run on request: COSIM_LONG=1 sbt "testOnly minesweeper.CoSimTest"
  private def longRun(): Unit =
    assume(sys.env.get("COSIM_LONG").contains("1"), "long co-simulation: set COSIM_LONG=1 to run it")

  it should "match on longer random command sequences" taggedAs SlowTag in {
    longRun()
    for ((p, label) <- capacities; seed <- 2 to 5)
      lockstep(p, s"random $label, seed $seed")(ls => randomRun(ls, new Random(seed), 80))
  }

  // A played game at 30x24 capacity needs hours with the default simulator (cost grows with the
  // square of the cell count), so this check stays on settings, stride, padding and range errors.
  it should "match at the production capacity on settings, flags and coordinates of all five presets" taggedAs SlowTag in {
    longRun()
    val p = MsParams()
    lockstep(p, "production capacity") { ls =>
      for (g <- p.presets) {
        ls(New(g.id, p.maxMines(g) + 1, 1))
        ls(New(g.id, 14, 0xffff))
        ls(Flag(g.rows - 1, g.cols - 1)) // far corner: fixed stride, no padding
        ls(Flag(0, 0))
        ls(Flag(0, 0))
        if (g.rows < p.maxRows) ls(Flag(g.rows, 0))
        if (g.cols < p.maxCols) ls(Reveal(0, g.cols))
      }
      ls(New(5, 14, 1)) // no preset 5
      ls(New(0, 13, 1)) // production minimum is 14
      ls(New(0, p.maxMines(p.presets(0)), 0x8000))
    }
  }
}
