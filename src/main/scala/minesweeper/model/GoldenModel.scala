package minesweeper.model

import minesweeper.MsParams

import scala.collection.mutable

/** Game states of CONTRACT 5.2. A completed command only ever reports the stable ones. */
sealed trait State
object State {
  case object Idle extends State
  case object Generating extends State
  case object Revealing extends State
  case object Solving extends State
  case object Playing extends State
  case object Won extends State
  case object Lost extends State
  case object Error extends State
}

sealed trait Status
object Status {
  case object Ok extends Status
  case object BadPreset extends Status
  case object BadMines extends Status
  case object BadSeed extends Status
  case object Range extends Status
  case object BadCommand extends Status
  case object InternalError extends Status
}

sealed trait CellKind
object CellKind {
  case object Hidden extends CellKind
  case object Flagged extends CellKind
  case object Number extends CellKind
  case object Mine extends CellKind
}

final case class PublicCell(active: Boolean, kind: CellKind, adj: Int)
object PublicCell {
  val padding: PublicCell = PublicCell(active = false, CellKind.Hidden, 0)
}
final case class Hint(safe: Boolean, mine: Boolean)
object Hint {
  val none: Hint = Hint(safe = false, mine = false)
}
final case class CmdResult(status: Status, changed: Boolean, revision: Long)

/**
  * Everything a completed command exposes on CoreIO (CONTRACT 5.2), in plain Scala.
  * cells and hints use the physical order row * maxCols + col over the full capacity.
  */
final case class Snapshot(
    maxCols: Int,
    preset: Int,
    cols: Int,
    rows: Int,
    mines: Int,
    state: State,
    cells: Vector[PublicCell],
    hintsValid: Boolean,
    hints: Vector[Hint],
    minesLeft: Int,
    exploded: Option[Int],
    revision: Long,
    generationCycles: Long,
    revealCycles: Long,
    revealLayers: Long,
    solverCycles: Long
) {
  /** . hidden, F flag, 0-8 revealed, * mine, X exploded; with hints, s/m mark hidden cells. */
  def render(showHints: Boolean = true): String = {
    val sb = new StringBuilder
    sb ++= "   " + (0 until cols).map(c => f"$c%2d").mkString + "\n"
    for (r <- 0 until rows) {
      sb ++= f"$r%2d "
      for (c <- 0 until cols) {
        val idx = r * maxCols + c
        val cell = cells(idx)
        val hint = hints(idx)
        val ch = cell.kind match {
          case CellKind.Mine => if (exploded.contains(idx)) 'X' else '*'
          case CellKind.Number => ('0' + cell.adj).toChar
          case CellKind.Flagged => 'F'
          case CellKind.Hidden if showHints && hintsValid && hint.safe => 's'
          case CellKind.Hidden if showHints && hintsValid && hint.mine => 'm'
          case CellKind.Hidden => '.'
        }
        sb ++= s" $ch"
      }
      sb ++= "\n"
    }
    sb.result()
  }

  def summary: String =
    s"state $state | ${cols}x$rows, $mines mines, mines left $minesLeft | revision $revision | " +
      s"cycles: generation $generationCycles, reveal $revealCycles ($revealLayers layers), solver $solverCycles" +
      (if (hintsValid) "" else " | no hints")
}

object Snapshot {
  /**
    * Mismatches between the reference and an implementation (CONTRACT 7). solverCycles is
    * implementation-defined (it changes with v2 lanes), so only "the solver ran" is compared.
    */
  def differences(expected: Snapshot, actual: Snapshot, maxCellReports: Int = 8): Vector[String] = {
    val out = Vector.newBuilder[String]
    def field[T](name: String, e: T, a: T): Unit =
      if (e != a) out += s"$name: expected $e, got $a"
    field("preset", expected.preset, actual.preset)
    field("cols", expected.cols, actual.cols)
    field("rows", expected.rows, actual.rows)
    field("mines", expected.mines, actual.mines)
    field("state", expected.state, actual.state)
    field("hintsValid", expected.hintsValid, actual.hintsValid)
    field("minesLeft", expected.minesLeft, actual.minesLeft)
    field("exploded", expected.exploded, actual.exploded)
    field("revision", expected.revision, actual.revision)
    field("generationCycles", expected.generationCycles, actual.generationCycles)
    field("revealCycles", expected.revealCycles, actual.revealCycles)
    field("revealLayers", expected.revealLayers, actual.revealLayers)
    field("solver ran (solverCycles > 0)", expected.solverCycles > 0, actual.solverCycles > 0)
    field("cell vector length", expected.cells.length, actual.cells.length)
    val cellDiffs = expected.cells.indices.filter(i =>
      i < actual.cells.length && (expected.cells(i) != actual.cells(i) || expected.hints(i) != actual.hints(i)))
    for (i <- cellDiffs.take(maxCellReports)) {
      val where = s"cell $i (row ${i / expected.maxCols}, col ${i % expected.maxCols})"
      if (expected.cells(i) != actual.cells(i)) out += s"$where: expected ${expected.cells(i)}, got ${actual.cells(i)}"
      if (expected.hints(i) != actual.hints(i)) out += s"$where hint: expected ${expected.hints(i)}, got ${actual.hints(i)}"
    }
    if (cellDiffs.size > maxCellReports) out += s"... ${cellDiffs.size - maxCellReports} more cells differ"
    out.result()
  }
}

/** A Minesweeper implementation driven by commands (lab 7 pattern: model and Chisel in lockstep). */
trait MinesweeperImpl {
  def name: String
  def newGame(preset: Int, mines: Int, seed: Int): CmdResult
  def reveal(row: Int, col: Int): CmdResult
  def flag(row: Int, col: Int): CmdResult
  /** Public view after the last completed command. */
  def snapshot: Snapshot
}

/**
  * Golden model of the game (CONTRACT 3, 4 and 5.2), independent of the Chisel code:
  * geometry from RefGrid, its own LFSR and placement, BFS cascade and ModelSolver hints.
  * Only data is read from MsParams (capacity, presets, limits, defaults), never its helpers.
  */
final class GoldenModel(p: MsParams) extends MinesweeperImpl {
  import GoldenModel._

  val name = "model"
  private val maxCols = p.maxCols
  private val capacity = p.maxCols * p.maxRows

  private var preset = p.defaultPresetId
  private var mineCount = p.defaultMines
  private var seed = 1
  private var grid = gridOf(preset)
  private var generated = false
  private var mines = Set.empty[Cell]
  private val revealed = mutable.Set.empty[Cell]
  private val flagged = mutable.Set.empty[Cell]
  private var state: State = State.Idle
  private var revision = 0L
  private var generationCycles = 0L
  private var revealCycles = 0L
  private var revealLayers = 0L
  private var solverCycles = 0L
  private var exploded: Option[Cell] = None
  private var hints: Option[ModelSolver.Result] = None

  private def gridOf(id: Int): RefGrid = {
    val g = p.presets.find(_.id == id).getOrElse(throw new IllegalArgumentException(s"no preset $id"))
    RefGrid(rows = g.rows, cols = g.cols)
  }

  private def physical(c: Cell): Int = c._1 * maxCols + c._2
  private def adj(c: Cell): Int = grid.adjacentMines(mines, c._1, c._2)
  private def terminal: Boolean = state == State.Won || state == State.Lost || state == State.Error
  private def noOp: CmdResult = CmdResult(Status.Ok, changed = false, revision)
  private def rejected(s: Status): CmdResult = CmdResult(s, changed = false, revision)

  /** Starts a successful state-changing command: one revision, metrics from zero (CONTRACT 5.2). */
  private def beginChange(): Unit = {
    revision = (revision + 1) & 0xffffffffL
    generationCycles = 0
    revealCycles = 0
    revealLayers = 0
    solverCycles = 0
    hints = None
  }

  def newGame(presetId: Int, mines: Int, seed: Int): CmdResult = {
    val g = p.presets.find(_.id == presetId)
    // Validation order of CONTRACT 3: preset, mine range, seed. Nothing changes on rejection.
    if (g.isEmpty) return rejected(Status.BadPreset)
    val maxMines = g.get.cols * g.get.rows - math.min(3, g.get.cols) * math.min(3, g.get.rows)
    if (mines < p.minMines || mines > maxMines) return rejected(Status.BadMines)
    if (seed < 1 || seed > 0xffff) return rejected(Status.BadSeed)
    beginChange()
    preset = presetId
    mineCount = mines
    this.seed = seed
    grid = gridOf(presetId)
    generated = false
    this.mines = Set.empty
    revealed.clear()
    flagged.clear()
    exploded = None
    state = State.Idle
    CmdResult(Status.Ok, changed = true, revision)
  }

  def flag(row: Int, col: Int): CmdResult = {
    val c = (row, col)
    if (terminal) noOp
    else if (!grid.contains(row, col)) rejected(Status.Range)
    else if (revealed(c)) noOp
    else {
      beginChange()
      if (!flagged.remove(c)) flagged += c
      // Before the first reveal there are no clues: the game stays Idle without hints.
      if (generated) solve()
      result()
    }
  }

  def reveal(row: Int, col: Int): CmdResult = {
    val c = (row, col)
    if (terminal) noOp
    else if (!grid.contains(row, col)) rejected(Status.Range)
    else if (revealed(c) || flagged(c)) noOp
    else if (generated && mines(c)) {
      beginChange()
      state = State.Lost
      exploded = Some(c)
      // All mines become visible; flags on mines are cleared (CONTRACT 5.3).
      flagged --= mines
      CmdResult(Status.Ok, changed = true, revision)
    } else {
      beginChange()
      if (!generated) {
        val (placed, attempts) = placeMines(grid, mineCount, seed, c)
        mines = placed
        generationCycles = attempts
        generated = true
      }
      val layers = cascade(c)
      revealCycles = layers
      revealLayers = layers
      state = if (revealed.size == grid.rows * grid.cols - mineCount) State.Won else State.Playing
      solve()
      result()
    }
  }

  /** Frontier BFS (CONTRACT 4, 5.5): returns the number of nonempty layers. */
  private def cascade(start: Cell): Int = {
    var layer = Set(start)
    val visited = mutable.Set(start)
    var layers = 0
    while (layer.nonEmpty) {
      revealed ++= layer
      layers += 1
      val next = for {
        cell <- layer if adj(cell) == 0
        n <- grid.neighbours(cell._1, cell._2)
        if !revealed(n) && !flagged(n) && !visited(n)
      } yield n
      visited ++= next
      layer = next
    }
    layers
  }

  private def solve(): Unit = {
    val clues = revealed.iterator.map(c => c -> adj(c)).toMap
    val r = ModelSolver.solveV1(grid, clues)
    solverCycles = r.rounds
    if (r.contradiction) state = State.Error else hints = Some(r)
  }

  private def result(): CmdResult =
    CmdResult(if (state == State.Error) Status.InternalError else Status.Ok, changed = true, revision)

  def snapshot: Snapshot = {
    val cells = Vector.tabulate(capacity) { idx =>
      val c = (idx / maxCols, idx % maxCols)
      if (!grid.contains(c._1, c._2)) PublicCell.padding
      else if (state == State.Lost && mines(c)) PublicCell(active = true, CellKind.Mine, 0)
      else if (revealed(c)) PublicCell(active = true, CellKind.Number, adj(c))
      else if (flagged(c)) PublicCell(active = true, CellKind.Flagged, 0)
      else PublicCell(active = true, CellKind.Hidden, 0)
    }
    val hintVec = Vector.tabulate(capacity) { idx =>
      val c = (idx / maxCols, idx % maxCols)
      hints.fold(Hint.none)(h => Hint(h.safe(c), h.mine(c)))
    }
    Snapshot(maxCols, preset, grid.cols, grid.rows, mineCount, state, cells, hints.isDefined, hintVec,
      mineCount - flagged.size, exploded.map(physical), revision,
      generationCycles, revealCycles, revealLayers, solverCycles)
  }

  /** Private truth, for simulation-only checks (CONTRACT 7). Empty before the first reveal. */
  def mineCells: Set[Cell] = mines

  /**
    * Simulation-only test load (CONTRACT 7): a game of the current preset with this layout,
    * as if generated, with no revealed cells and no flags. Used by hand-written model tests.
    */
  def testLoad(presetId: Int, layout: Set[Cell]): Unit = {
    val g = gridOf(presetId)
    require(layout.forall { case (r, c) => g.contains(r, c) }, "layout outside the active grid")
    preset = presetId
    grid = g
    mineCount = layout.size
    mines = layout
    generated = true
    revealed.clear()
    flagged.clear()
    exploded = None
    hints = None
    generationCycles = 0
    revealCycles = 0
    revealLayers = 0
    solverCycles = 0
    state = State.Playing
  }
}

object GoldenModel {
  type Cell = (Int, Int)

  /** CONTRACT 4.1, from its text: right-shifting Galois LFSR with XOR mask 0xB400. */
  def lfsrNext(s: Int): Int = (s >>> 1) ^ (if ((s & 1) == 1) 0xb400 else 0)

  /**
    * CONTRACT 4.1 placement. Each candidate uses the current state, then the state steps,
    * including after a rejected candidate. Returns the mines and the number of candidates.
    */
  def placeMines(grid: RefGrid, count: Int, seed: Int, first: Cell): (Set[Cell], Int) = {
    val cells = grid.rows * grid.cols
    val protectedCells = grid.neighbours(first._1, first._2).toSet + first
    val placed = mutable.Set.empty[Cell]
    var state = seed
    var attempts = 0
    while (placed.size < count) {
      require(attempts < 65535, "placement did not finish within one LFSR period")
      val compact = (state - 1) % cells
      val cell = (compact / grid.cols, compact % grid.cols)
      if (!protectedCells(cell)) placed += cell
      state = lfsrNext(state)
      attempts += 1
    }
    (placed.toSet, attempts)
  }
}
