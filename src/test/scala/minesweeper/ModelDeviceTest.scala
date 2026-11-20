package minesweeper

import minesweeper.host._
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** The model board (behind SimBoardServer and the PC display's tests) against the contract's fixtures. */
class ModelDeviceTest extends AnyFlatSpec {
  private val fixtures: Seq[Fixture] =
    Files.list(Paths.get("docs/protocol")).iterator().asScala.filter(_.toString.endsWith(".txt")).toSeq
      .sortBy(_.toString).map { (f: Path) =>
        val name = f.getFileName.toString.stripSuffix(".txt")
        Fixture.parse(name, Files.readString(f)).fold(e => fail(s"$name: $e"), identity)
      }

  "The model board" should "answer every protocol fixture session from a physical reset" in {
    assert(fixtures.size >= 8)
    for (f <- fixtures) {
      // Each fixture session starts from a physical reset.
      // These committed fixtures describe the pre-1.4 V1 protocol transcript.
      Replay.run(f, new ModelDevice(legacyProtocolFixtures = true)) match {
        case Left(e) => fail(e)
        case Right(report) => info(s"${f.name}: ${report.linesChecked} lines, ${report.snapshotsChecked} snapshots")
      }
    }
  }

  it should "play whole games by following its safe hints, then keep a won game unchanged" in {
    var won = 0
    for (seed <- 1 to 40) {
      val d = new ModelDevice(MsParams.forVerification(5, 4))
      var seq = 1
      def ask(cmd: String): Vector[String] = {
        d.send((cmd.replace("#", Protocol.hex4(seq)) + "\n").getBytes("US-ASCII"))
        seq += 1
        Iterator.continually(d.readLine(0)).takeWhile(_.isDefined).map(_.get).toVector
      }
      def snapshot(lines: Vector[String]): Snapshot = {
        assert(lines.head.startsWith("ACK"), lines.head)
        Snapshot.assemble(lines.drop(1).map(l => Reply.parse(l).fold(e => fail(e), identity))).fold(e => fail(e), identity)
      }
      snapshot(ask(s"NEW # ${Protocol.hex4(seed)} 0 3"))
      var s = snapshot(ask("REVEAL # 0 0"))
      var stuck = false
      while (s.snap.state == SnapState.Playing && !stuck) {
        val safe = for (r <- 0 until s.rows; c <- 0 until s.cols if s.hint(r, c) == HintMark.Safe) yield (r, c)
        if (safe.isEmpty) stuck = true // would need a guess: try the next seed
        else s = snapshot(ask(s"REVEAL # ${safe.head._1} ${safe.head._2}"))
      }
      // Following safe hints never loses.
      assert(s.snap.state != SnapState.Lost, s"seed $seed: lost while following safe hints")
      if (s.snap.state == SnapState.Won) {
        won += 1
        val after = snapshot(ask("REVEAL # 0 1"))
        assert(after.snap.state == SnapState.Won && after.snap.revision == s.snap.revision)
      }
    }
    info(s"$won of 40 games won by following the hints alone")
    assert(won > 0)
  }
}
