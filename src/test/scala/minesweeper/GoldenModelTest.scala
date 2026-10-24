package minesweeper

import minesweeper.model._
import org.scalatest.flatspec.AnyFlatSpec

import scala.util.Random

class GoldenModelTest extends AnyFlatSpec {
  // Hand-checked 6x5 board (columns x rows), mines M at (0,4), (2,4), (4,1):
  //      0 1 2 3 4 5
  //  0   0 0 0 1 M 1
  //  1   0 0 0 2 2 2
  //  2   0 0 0 1 M 1
  //  3   1 1 1 1 1 1
  //  4   1 M 1 0 0 0
  private val handP = MsParams.forVerification(6, 5)
  private val handMines = Set((0, 4), (2, 4), (4, 1))
  private val numbers: Map[(Int, Int), Int] = {
    val rows = Vector("0001M1", "000222", "0001M1", "111111", "1M1000")
    (for (r <- 0 until 5; c <- 0 until 6 if rows(r)(c) != 'M') yield (r, c) -> (rows(r)(c) - '0')).toMap
  }
  private val zeroRegion = Set((0, 0), (0, 1), (0, 2), (1, 0), (1, 1), (1, 2), (2, 0), (2, 1), (2, 2))
  private val zeroBorder = Set((0, 3), (1, 3), (2, 3), (3, 0), (3, 1), (3, 2), (3, 3))

  private def loaded(): GoldenModel = {
    val m = new GoldenModel(handP)
    m.testLoad(0, handMines)
    m
  }
  private def idx(p: MsParams, r: Int, c: Int): Int = r * p.maxCols + c
  private def revealedCells(s: Snapshot): Set[(Int, Int)] =
    s.cells.indices.filter(i => s.cells(i).kind == CellKind.Number)
      .map(i => (i / s.maxCols, i % s.maxCols)).toSet

  "GoldenModel placement" should "agree with the contract reference on every production preset" in {
    val p = MsParams()
    for {
      g <- p.presets
      mines <- Seq(14, p.maxMines(g))
      seed <- Seq(1, 0x42, 0xffff)
      (r, c) <- Seq((0, 0), (0, g.cols - 1), (g.rows - 1, 0), (g.rows - 1, g.cols - 1), (g.rows / 2, g.cols / 2), (0, 3))
    } {
      val grid = RefGrid(g.rows, g.cols)
      val (placed, attempts) = GoldenModel.placeMines(grid, mines, seed, (r, c))
      val reference = Lfsr16.placeMines(p, GameSettings(g.id, mines, seed), r, c)
      assert(placed.map { case (rr, cc) => idx(p, rr, cc) } == reference, s"preset ${g.id}, $mines mines, seed $seed, ($r,$c)")
      // Independent checks: exact count, inside the active grid, protected 3x3 area free, deterministic.
      assert(placed.size == mines)
      assert(placed.forall { case (rr, cc) => rr >= 0 && rr < g.rows && cc >= 0 && cc < g.cols })
      val protectedArea = for (dr <- -1 to 1; dc <- -1 to 1) yield (r + dr, c + dc)
      assert(protectedArea.forall(a => !placed(a)))
      assert(GoldenModel.placeMines(grid, mines, seed, (r, c)) == ((placed, attempts)))
      assert(attempts >= mines && attempts <= 65535)
    }
  }

  it should "step the same LFSR as the contract and have period 65535" in {
    var s = 1
    var steps = 0
    do {
      assert(GoldenModel.lfsrNext(s) == Lfsr16.next(s))
      s = GoldenModel.lfsrNext(s)
      steps += 1
    } while (s != 1 && steps <= 70000)
    assert(steps == 65535)
  }

  "GoldenModel" should "reset to the default 9x9 game with 14 mines" in {
    val p = MsParams()
    val s = new GoldenModel(p).snapshot
    assert((s.preset, s.cols, s.rows, s.mines, s.minesLeft) == ((1, 9, 9, 14, 14)))
    assert(s.state == State.Idle && s.revision == 0 && !s.hintsValid && s.exploded.isEmpty)
    assert(s.cells.length == 720 && s.cells.count(_.active) == 81)
    assert(s.cells.forall(c => c.kind == CellKind.Hidden && c.adj == 0))
    assert(s.cells(idx(p, 8, 8)).active && !s.cells(idx(p, 0, 9)).active && !s.cells(idx(p, 9, 0)).active)
    assert(s.hints.forall(_ == Hint.none))
    assert(Seq(s.generationCycles, s.revealCycles, s.revealLayers, s.solverCycles).forall(_ == 0))
  }

  it should "validate NewGame in contract order and change nothing on rejection" in {
    val m = new GoldenModel(MsParams())
    val before = m.snapshot
    assert(m.newGame(5, 0, 0) == CmdResult(Status.BadPreset, changed = false, 0))
    assert(m.newGame(0, 13, 0) == CmdResult(Status.BadMines, changed = false, 0))
    assert(m.newGame(0, 56, 1) == CmdResult(Status.BadMines, changed = false, 0)) // 8x8 maximum is 55
    assert(m.newGame(0, 14, 0) == CmdResult(Status.BadSeed, changed = false, 0))
    assert(m.newGame(0, 14, 0x10000) == CmdResult(Status.BadSeed, changed = false, 0))
    assert(m.snapshot == before)
    assert(m.newGame(0, 55, 0xffff) == CmdResult(Status.Ok, changed = true, 1))
    val s = m.snapshot
    assert((s.preset, s.cols, s.rows, s.mines, s.state, s.revision) == ((0, 8, 8, 55, State.Idle, 1L)))
    assert(m.newGame(4, 711, 1).status == Status.Ok && m.snapshot.cells.count(_.active) == 720)
  }

  it should "cascade a zero click through its region and stop at numbered cells" in {
    val m = loaded()
    assert(m.reveal(0, 0) == CmdResult(Status.Ok, changed = true, 1))
    val s = m.snapshot
    assert(revealedCells(s) == zeroRegion ++ zeroBorder)
    assert(revealedCells(s).forall(c => s.cells(idx(handP, c._1, c._2)).adj == numbers(c)))
    assert(s.revealLayers == 4 && s.revealCycles == 4 && s.generationCycles == 0)
    assert(s.state == State.Playing)
    // A second zero region, reached from (4,4): three layers, (4,2) is the last one.
    m.reveal(4, 4)
    val t = m.snapshot
    assert(revealedCells(t) == zeroRegion ++ zeroBorder ++ Set((4, 3), (4, 4), (4, 5), (3, 4), (3, 5), (4, 2)))
    assert(t.revealLayers == 3)
    // A numbered cell reveals only itself in one layer.
    m.reveal(1, 4)
    assert(revealedCells(m.snapshot) == revealedCells(t) + ((1, 4)) && m.snapshot.revealLayers == 1)
  }

  it should "let a flag block the cascade without revealing the flagged cell" in {
    val m = loaded()
    m.flag(1, 1)
    m.reveal(0, 0)
    val s = m.snapshot
    assert(revealedCells(s) == zeroRegion ++ zeroBorder - ((1, 1)))
    assert(s.cells(idx(handP, 1, 1)).kind == CellKind.Flagged)
    assert(s.revealLayers == 5) // (2,2) now comes one layer later, and (3,3) after it
  }

  it should "lose on a mine, show every mine, clear flags on mines and freeze the game" in {
    val m = loaded()
    m.flag(0, 4) // correct flag
    m.flag(4, 4) // wrong flag
    m.reveal(0, 0)
    val r = m.reveal(2, 4)
    assert(r == CmdResult(Status.Ok, changed = true, 4))
    val s = m.snapshot
    assert(s.state == State.Lost && s.exploded.contains(idx(handP, 2, 4)))
    assert(handMines.forall(c => s.cells(idx(handP, c._1, c._2)).kind == CellKind.Mine))
    assert(s.cells(idx(handP, 4, 4)).kind == CellKind.Flagged)
    assert(s.minesLeft == 2) // the flag on (0,4) became a visible mine
    assert(!s.hintsValid && s.hints.forall(_ == Hint.none))
    assert(Seq(s.generationCycles, s.revealCycles, s.revealLayers, s.solverCycles).forall(_ == 0))
    assert(m.reveal(0, 5) == CmdResult(Status.Ok, changed = false, 4))
    assert(m.flag(0, 5) == CmdResult(Status.Ok, changed = false, 4))
    assert(m.snapshot == s)
  }

  it should "win when the last safe cell is revealed and keep later commands as no-ops" in {
    val m = loaded()
    for ((r, c) <- Seq((0, 0), (4, 4), (1, 4), (0, 5), (1, 5), (2, 5))) m.reveal(r, c)
    assert(m.snapshot.state == State.Playing)
    m.reveal(4, 0)
    val s = m.snapshot
    assert(s.state == State.Won && s.hintsValid)
    assert(revealedCells(s).size == 27)
    assert(s.hints.indices.filter(i => s.hints(i).mine).toSet == handMines.map(c => idx(handP, c._1, c._2)))
    assert(m.reveal(0, 4).changed == false && m.flag(0, 4).changed == false)
  }

  it should "keep flags before the first reveal in Idle, without hints and without moving mines" in {
    val p = MsParams.forVerification(7, 5).copy(presets = Vector(GridPreset(0, 7, 5)))
    val plain = new GoldenModel(p)
    val flagged = new GoldenModel(p)
    for (m <- Seq(plain, flagged)) m.newGame(0, 8, 0x1234)
    assert(flagged.flag(4, 6) == CmdResult(Status.Ok, changed = true, 2))
    assert(flagged.snapshot.state == State.Idle && !flagged.snapshot.hintsValid)
    assert(flagged.snapshot.minesLeft == 7)
    plain.reveal(0, 0)
    flagged.reveal(0, 0)
    assert(plain.mineCells == flagged.mineCells && plain.mineCells.size == 8)
    assert(flagged.snapshot.generationCycles > 0)
    assert(flagged.snapshot.cells(idx(p, 4, 6)).kind == CellKind.Flagged)
  }

  it should "reject coordinates outside the active grid and ignore no-op commands" in {
    val p = MsParams.forVerification(7, 5).copy(presets = Vector(GridPreset(0, 4, 4), GridPreset(1, 7, 5)))
    val m = new GoldenModel(p)
    m.newGame(0, 2, 7)
    assert(m.reveal(0, 4) == CmdResult(Status.Range, changed = false, 1))
    assert(m.flag(4, 0) == CmdResult(Status.Range, changed = false, 1))
    m.reveal(0, 0)
    val s = m.snapshot
    val rev = s.revision
    val first = revealedCells(s).head
    assert(m.reveal(first._1, first._2) == CmdResult(Status.Ok, changed = false, rev))
    assert(m.flag(first._1, first._2) == CmdResult(Status.Ok, changed = false, rev))
    assert(m.snapshot == s) // metrics of the last real command are kept
  }

  it should "allow over-flagging and report negative mines left" in {
    val m = new GoldenModel(MsParams.forVerification(4, 4))
    m.newGame(0, 1, 3)
    for (c <- 0 until 3) m.flag(0, c)
    assert(m.snapshot.minesLeft == -2)
  }

  it should "give hints that do not depend on wrong flags" in {
    val m = loaded()
    m.reveal(0, 0)
    val before = m.snapshot
    m.flag(4, 4) // safe cell, wrongly flagged
    m.flag(0, 4)
    val after = m.snapshot
    assert(after.hintsValid && after.hints == before.hints && after.solverCycles > 0)
    assert(after.revision == before.revision + 2 && after.revealLayers == 0)
  }

  it should "keep its invariants over random games" in {
    val configs = Seq(
      MsParams.forVerification(4, 4),
      MsParams.forVerification(7, 5).copy(presets = Vector(GridPreset(0, 4, 4), GridPreset(1, 7, 5), GridPreset(2, 6, 5))),
      MsParams()
    )
    for (p <- configs; runSeed <- 1 to 15) {
      val rnd = new Random(runSeed)
      val m = new GoldenModel(p)
      var prev = m.snapshot
      for (_ <- 0 until 60) {
        val s0 = m.snapshot
        val res =
          if (s0.state == State.Won || s0.state == State.Lost || rnd.nextInt(12) == 0) {
            val g = p.presets(rnd.nextInt(p.presets.size))
            m.newGame(g.id, p.minMines + rnd.nextInt(p.maxMines(g) - p.minMines + 1), 1 + rnd.nextInt(0xffff))
          } else if (rnd.nextInt(5) == 0) m.flag(rnd.nextInt(s0.rows), rnd.nextInt(s0.cols))
          else m.reveal(rnd.nextInt(s0.rows), rnd.nextInt(s0.cols))
        val s = m.snapshot
        val truth = m.mineCells
        def cellAt(i: Int) = (i / p.maxCols, i % p.maxCols)
        assert(s.revision == prev.revision + (if (res.changed) 1 else 0) && res.revision == s.revision)
        if (!res.changed) assert(s == prev)
        assert(s.minesLeft == s.mines - s.cells.count(_.kind == CellKind.Flagged))
        for (i <- s.cells.indices) {
          val c = s.cells(i)
          if (!c.active) assert(c == PublicCell.padding && s.hints(i) == Hint.none)
          if (c.kind == CellKind.Number) {
            assert(!truth(cellAt(i)), "a cascade revealed a mine")
            assert(c.adj == RefGrid(s.rows, s.cols).adjacentMines(truth, cellAt(i)._1, cellAt(i)._2))
          }
          if (c.kind == CellKind.Mine) assert(s.state == State.Lost && truth(cellAt(i)))
          val h = s.hints(i)
          assert(!(h.safe && h.mine))
          if (h.safe || h.mine) assert(s.hintsValid && c.active && c.kind != CellKind.Number)
          if (h.safe) assert(!truth(cellAt(i)), "unsound safe hint")
          if (h.mine) assert(truth(cellAt(i)), "unsound mine hint")
        }
        val safeRevealed = s.cells.count(_.kind == CellKind.Number)
        if (s.state == State.Won) assert(safeRevealed == s.cols * s.rows - s.mines)
        if (s.state == State.Playing) assert(safeRevealed < s.cols * s.rows - s.mines)
        if (s.hintsValid) assert(s.state == State.Playing || s.state == State.Won)
        prev = s
      }
    }
  }
}
