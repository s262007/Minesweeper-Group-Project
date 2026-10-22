package minesweeper

import minesweeper.host._
import minesweeper.model.RefGrid
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

class ProtocolFixtureTest extends AnyFlatSpec {
  private val dir = Paths.get("docs/protocol")
  private val required = Seq("hello", "reset_get", "errors", "line_framing", "flags_rect",
    "overflag", "retry", "reveal")

  private lazy val fixtures: Map[String, Fixture] =
    Files.list(dir).iterator().asScala.filter(_.toString.endsWith(".txt")).map { (f: Path) =>
      val name = f.getFileName.toString.stripSuffix(".txt")
      name -> Fixture.parse(name, Files.readString(f)).fold(e => fail(s"$name: $e"), identity)
    }.toMap

  private def sentText(s: Fixture.Send): String = new String(s.bytes.toArray, US_ASCII)

  "The protocol fixtures" should "include every required session and parse" in {
    for (name <- required) assert(fixtures.contains(name), s"missing docs/protocol/$name.txt")
  }

  it should "contain only structurally valid replies" in {
    for ((name, f) <- fixtures; Fixture.Expect(pattern) <- f.steps)
      assert(Reply.parse(pattern.mockLine).isRight, s"$name: ${pattern.text}")
  }

  it should "send well-formed requests exactly as the host encoder writes them" in {
    for ((name, f) <- fixtures; s @ Fixture.Send(_, true) <- f.steps) {
      val text = sentText(s).stripSuffix("\n").stripSuffix("\r")
      Request.parse(text).foreach(r => assert(r.line == text, s"$name: $text"))
    }
  }

  it should "answer every complete request with ACK, INFO or ERR first" in {
    for ((name, f) <- fixtures) {
      val firstReplies = f.transactions.map { case (send, replies) =>
        val text = sentText(send).stripSuffix("\n").stripSuffix("\r")
        (text, replies.headOption.map(_.mockLine.takeWhile(_ != ' ')))
      }
      for ((text, first) <- firstReplies) {
        val expected = Request.parse(text) match {
          case Some(_: Request.Hello) => Set("INFO", "ERR")
          case Some(_) => Set("ACK", "ERR")
          case None => Set("ERR")
        }
        assert(first.exists(expected), s"$name: '$text' answered by $first")
      }
    }
  }

  it should "replay against the scripted mock device without an FPGA" in {
    for ((name, f) <- fixtures) {
      val device = new ScriptedDevice(f)
      val report = Replay.run(f, device).fold(e => fail(s"$name: $e"), identity)
      assert(device.finished, name)
      assert(report.linesChecked > 0, name)
    }
    val snapshots = fixtures.values.map(f => Replay.run(f, new ScriptedDevice(f)).toOption.get)
      .map(_.snapshotsChecked).sum
    assert(snapshots >= 20)
  }

  private def withPattern(f: Fixture, from: String, to: String): Fixture = {
    val steps = f.steps.map {
      case Fixture.Expect(p) if p.text == from => Fixture.Expect(Fixture.Pattern.parse(to))
      case s => s
    }
    assert(steps != f.steps, s"$from not found")
    f.copy(steps = steps)
  }

  "Replay" should "report a device that sends a different board" in {
    val f = fixtures("reset_get")
    val liar = new ScriptedDevice(withPattern(f, "ROW 0001 3 H H H H H H H H H",
      "ROW 0001 3 H H H 1 H H H H H"))
    assert(Replay.run(f, liar).isLeft)
  }

  it should "report a snapshot that leaks a mine even when the pattern agrees" in {
    val leaky = withPattern(fixtures("reset_get"), "ROW 0001 3 H H H H H H H H H",
      "ROW 0001 3 H H H M H H H H H")
    assert(Replay.run(leaky, new ScriptedDevice(leaky)).isLeft)
  }

  it should "report missing and extra lines" in {
    val f = fixtures("hello")
    val short = f.copy(steps = f.steps.init)
    assert(Replay.run(f, new ScriptedDevice(short)).isLeft)
    assert(Replay.run(short, new ScriptedDevice(f)).isLeft)
  }

  "The reveal fixture" should "agree with the contract LFSR placement and independent counts" in {
    val f = fixtures("reveal")
    val p = MsParams()
    val requests = f.transactions.flatMap { case (s, _) =>
      Request.parse(sentText(s).stripSuffix("\n")) }
    val newGame = requests.collectFirst { case r: Request.NewGame => r }.get
    val firstClick = requests.collectFirst { case r: Request.Reveal => r }.get
    val replies = f.transactions.flatMap(_._2.map(_.mockLine)).map(l => Reply.parse(l).toOption.get)
    val snaps = Replay.snapshots(replies).fold(e => fail(e), identity)
    val first = snaps.find(_.snap.state == SnapState.Playing).get
    val last = snaps.find(_.snap.state == SnapState.Lost).get
    val preset = p.preset(newGame.preset).get
    val grid = RefGrid(preset.rows, preset.cols)
    val shownMines = grid.cells.filter { case (r, c) => last.cell(r, c) == CellToken.Mine }.toSet
    val expected = Lfsr16.placeMines(p, GameSettings(preset.id, newGame.mines, newGame.seed),
      firstClick.row, firstClick.col).map(i => (i / p.maxCols, i % p.maxCols))
    assert(first.snap.revealLayers > 1, "the first click should cascade")
    assert(shownMines == expected)
    for (s <- Seq(first, last); (r, c) <- grid.cells) s.cell(r, c) match {
      case CellToken.Number(n) => assert(n == grid.adjacentMines(expected, r, c), s"($r, $c)")
      case _ =>
    }
  }

  "Host sources" should "not depend on the reference model or hardware code" in {
    val files = Files.list(Paths.get("src/main/scala/minesweeper/host")).iterator().asScala.toSeq
    assert(files.nonEmpty)
    for (f <- files; word <- Seq("minesweeper.model", "chisel3", "Lfsr16", "MsParams"))
      assert(!Files.readString(f).contains(word), s"$f mentions $word")
  }
}
