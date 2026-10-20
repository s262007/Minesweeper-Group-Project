package minesweeper

import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

/** Checks the plan-B demo game against the same references as the engine tests.
  * 6x6 keeps the simulation fast (treadle's cost per cycle grows about with cells squared).
  */
class R2DemoConsoleTest extends AnyFlatSpec with ChiselScalatestTester {
  private val n = 6
  private val mineCount = 5
  private val first = (2, 2)
  private val p = MsParams.forVerification(n, n)

  "R2 demo game" should "never lose on the first click and cascade like the reference BFS" in {
    test(new R2DemoHarness(p)) { dut =>
      val game = new R2DemoGame(p, mineCount, dut)
      for (seed <- Seq(1, 42, 1234)) {
        game.newGame(seed)
        game.reveal(first._1, first._2)
        assert(game.state != "LOST", s"seed $seed: the first click hit a mine")
        assert(!game.mines.exists(m => math.abs(m._1 - first._1) <= 1 && math.abs(m._2 - first._2) <= 1),
          "a mine inside the first-click safe zone")
        assert(game.mines.size == mineCount)
        val expected = RevealBoard(game.g, game.mines).expectedLayers(first).flatten.toSet
        assert(game.revealed == expected, s"seed $seed: cascade differs from the BFS")
      }
    }
  }

  it should "show only sound hints, equal to the reference v1 fixpoint" in {
    test(new R2DemoHarness(p)) { dut =>
      val game = new R2DemoGame(p, mineCount, dut)
      var checked = 0
      for (seed <- Seq(3, 42, 777, 2024)) {
        game.newGame(seed)
        game.reveal(first._1, first._2)
        if (game.state == "PLAYING") {
          val b = RevealBoard(game.g, game.mines, revealed = game.revealed)
          val Some((safe, mine, _)) = ReferenceV1.solve(b, b.revealed, b.adj)
          assert(game.hintSafe == safe && game.hintMine == mine)
          assert(!game.hintSafe.exists(game.mines) && game.hintMine.subsetOf(game.mines))
          checked += 1
        }
      }
      assert(checked > 0)
    }
  }

  it should "lose on a mine, refuse revealing a flag, and win when all safe cells are open" in {
    test(new R2DemoHarness(p)) { dut =>
      val game = new R2DemoGame(p, mineCount, dut)
      game.newGame(42)
      game.reveal(first._1, first._2)
      // A flag blocks a reveal.
      val someMine = game.mines.head
      game.flag(someMine._1, someMine._2)
      assert(game.reveal(someMine._1, someMine._2).contains("flagged"))
      assert(game.state == "PLAYING")
      // Opening every remaining safe cell wins.
      for (r <- 0 until n; c <- 0 until n if !game.mines((r, c)) && !game.revealed((r, c)))
        game.reveal(r, c)
      assert(game.state == "WON")
      // Same seed and first click give the same mines; flag then unflag, then the click loses.
      game.newGame(42)
      game.reveal(first._1, first._2)
      game.flag(someMine._1, someMine._2)
      game.flag(someMine._1, someMine._2)
      game.reveal(someMine._1, someMine._2)
      assert(game.state == "LOST" && game.exploded.contains(someMine))
    }
  }

  it should "never step on a mine when autoplaying the solver's safe hints" in {
    test(new R2DemoHarness(p)) { dut =>
      val game = new R2DemoGame(p, mineCount, dut)
      var wins = 0
      for (seed <- 1 to 12) {
        game.newGame(seed)
        game.reveal(first._1, first._2)
        game.autoplay()
        assert(game.state != "LOST", s"seed $seed: autoplay revealed a mine")
        if (game.state == "PLAYING") assert(game.hintSafe.isEmpty, "autoplay stopped with safe hints left")
        if (game.state == "WON") wins += 1
      }
      info(s"autoplay won $wins of 12 games without guessing")
    }
  }
}
