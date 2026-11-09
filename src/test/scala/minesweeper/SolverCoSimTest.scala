package minesweeper

import chisel3._
import chiseltest._
import minesweeper.model.{CellKind, GoldenModel, ModelSolver, RefGrid, Snapshot, State}
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

/** R3-S4: the hardware Solver, v1 and v2, against ModelSolver on the boards of played games.
  *
  * The golden model plays each game with its own mine placement and cascade. After every
  * reveal that leaves the game running, the revealed cells and their numbers go to the
  * Solver and to ModelSolver, and the hints must be equal. v2 is compared with
  * ModelSolver.solveV2, which decides pairs of clues by enumeration and shares no code with
  * the Solver's PairRule.
  */
class SolverCoSimTest extends AnyFlatSpec with ChiselScalatestTester {
  private type Cell = (Int, Int)

  /** What the solver sees of a game: the active preset and the revealed numbers. */
  private case class Board(preset: Int, cols: Int, rows: Int, mines: Int, clues: Map[Cell, Int]) {
    def grid: RefGrid = RefGrid(rows = rows, cols = cols)
  }

  private def boardOf(s: Snapshot): Board = {
    val clues = s.cells.zipWithIndex.collect {
      case (cell, idx) if cell.active && cell.kind == CellKind.Number => (idx / s.maxCols, idx % s.maxCols) -> cell.adj
    }.toMap
    Board(s.preset, s.cols, s.rows, s.mines, clues)
  }

  /** The boards after each move of `games` model games: reveals of random safe hidden cells. */
  private def playedBoards(p: MsParams, presetId: Int, games: Int, moves: Int, seed: Int): Seq[Board] = {
    val rnd = new Random(seed)
    val g = p.presets.find(_.id == presetId).get
    val maxMines = g.cols * g.rows - math.min(3, g.cols) * math.min(3, g.rows)
    val out = Seq.newBuilder[Board]
    for (_ <- 0 until games) {
      val model = new GoldenModel(p)
      val density = 0.15 + 0.2 * rnd.nextDouble()
      val mines = math.max(p.minMines, math.min(maxMines, math.round(density * g.cols * g.rows).toInt))
      model.newGame(presetId, mines, 1 + rnd.nextInt(0xfffe))
      var move = 0
      var playing = true
      while (playing && move < moves) {
        val s = model.snapshot
        // Before the first reveal mineCells is empty: the first click is always safe.
        val candidates = for {
          r <- 0 until g.rows
          c <- 0 until g.cols
          if s.cells(r * p.maxCols + c).kind == CellKind.Hidden && !model.mineCells((r, c))
        } yield (r, c)
        if (candidates.isEmpty) playing = false
        else {
          val (r, c) = candidates(rnd.nextInt(candidates.size))
          model.reveal(r, c)
          val after = model.snapshot
          if (after.state == State.Playing) out += boardOf(after) else playing = false
          move += 1
        }
      }
    }
    out.result()
  }

  private def needsPairs(b: Board): Boolean = {
    val (v1, v2) = (ModelSolver.solveV1(b.grid, b.clues), ModelSolver.solveV2(b.grid, b.clues))
    v2.safe.size + v2.mine.size > v1.safe.size + v1.mine.size
  }

  /** `withPairs` boards that need rule 2, then `others` that do not. */
  private def pick(boards: Seq[Board], withPairs: Int, others: Int): Seq[Board] = {
    val (pairs, rest) = boards.partition(needsPairs)
    assert(pairs.size >= withPairs, s"only ${pairs.size} of ${boards.size} boards need rule 2")
    pairs.take(withPairs) ++ rest.take(others)
  }

  /** v1 and v2 passes cannot outnumber the cells: the same bound as SolverTest's v2 bound. */
  private def bound(p: MsParams): Int =
    2 * (p.cells + 1) * (3 * p.maxRows + 1) + (p.cells + 1) * p.maxRows * (5 * p.maxCols + 5) + 2

  /** One solver run: the hints, and whether it ended valid without a contradiction. */
  private def solve(dut: Solver, p: MsParams, b: Board): (Set[Cell], Set[Cell], Boolean) = {
    dut.io.clear.poke(false.B)
    for (i <- 0 until p.cells) {
      val clue = b.clues.get((i / p.maxCols, i % p.maxCols))
      dut.io.board(i).revealed.poke(clue.isDefined.B)
      dut.io.board(i).adj.poke(clue.getOrElse(0).U)
    }
    val cfg = dut.io.start.bits.config
    cfg.preset.poke(b.preset.U)
    cfg.cols.poke(b.cols.U)
    cfg.rows.poke(b.rows.U)
    cfg.mines.poke(b.mines.U)
    dut.io.start.valid.poke(true.B)
    dut.io.start.ready.expect(true.B)
    dut.clock.step()
    dut.io.start.valid.poke(false.B)
    var waited = 0
    while (!dut.io.done.peekBoolean()) {
      assert(waited < bound(p), s"no result within ${bound(p)} cycles")
      dut.clock.step()
      waited += 1
    }
    val hints = (0 until p.cells).map(i => ((i / p.maxCols, i % p.maxCols), dut.io.hints(i)))
    val safe = hints.collect { case (c, h) if h.safe.peekBoolean() => c }.toSet
    val mine = hints.collect { case (c, h) if h.mine.peekBoolean() => c }.toSet
    val ok = dut.io.valid.peekBoolean() && !dut.io.contradiction.peekBoolean()
    dut.clock.step()
    (safe, mine, ok)
  }

  private def coSimulate(p: MsParams, cfg: SolverConfig, boards: Seq[Board]): Unit = {
    assert(boards.nonEmpty, "no board to compare")
    test(new Solver(p, cfg)) { dut =>
      // The wait in solve has its own bound; v2 runs can exceed chiseltest's 1000 idle cycles.
      dut.clock.setTimeout(0)
      for (b <- boards) {
        val expected = if (cfg.subsetRule) ModelSolver.solveV2(b.grid, b.clues) else ModelSolver.solveV1(b.grid, b.clues)
        assert(!expected.contradiction, "the model found a contradiction on a real game")
        val (safe, mine, ok) = solve(dut, p, b)
        assert(ok, s"the solver did not end valid on ${b.clues}")
        assert(safe == expected.safe && mine == expected.mine,
          s"clues ${b.clues}: hardware safe $safe mine $mine, model safe ${expected.safe} mine ${expected.mine}")
      }
    }
  }

  private val p4x4 = MsParams.forVerification(4, 4)
  private val p7x5 = MsParams.forVerification(7, 5)
  // A 4x4 game in 6x5 storage: the region of a clue reaches into the padding on both axes.
  private val padded = MsParams(
    maxCols = 6, maxRows = 5,
    presets = Vector(GridPreset(0, 6, 5), GridPreset(1, 4, 4)),
    minMines = 0, defaultPresetId = 0, defaultMines = 0, verification = true
  )

  "The hardware Solver" should "give the hints of ModelSolver v1 on played 4x4 and 7x5 games" in {
    coSimulate(p4x4, SolverConfig(), playedBoards(p4x4, 0, games = 6, moves = 3, seed = 41).take(12))
    coSimulate(p7x5, SolverConfig(), playedBoards(p7x5, 0, games = 4, moves = 3, seed = 42).take(8))
  }

  it should "give the hints of ModelSolver v2 on played 4x4 games, half of them needing rule 2" in {
    coSimulate(p4x4, SolverConfig(subsetRule = true), pick(playedBoards(p4x4, 0, 300, 3, seed = 43), 3, 3))
  }

  it should "give the hints of ModelSolver v2 in a 4x4 preset inside 6x5 storage" in {
    coSimulate(padded, SolverConfig(subsetRule = true), pick(playedBoards(padded, 1, 300, 3, seed = 44), 2, 1))
  }

  it should "give the hints of ModelSolver v2 on played 9x9 games" taggedAs (SlowTag) in {
    val p = MsParams.forVerification(9, 9)
    coSimulate(p, SolverConfig(subsetRule = true), pick(playedBoards(p, 0, 100, 4, seed = 45), 3, 1))
  }
}
