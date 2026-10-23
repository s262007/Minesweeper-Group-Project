package minesweeper

import minesweeper.host._
import minesweeper.host.Reply._
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.charset.StandardCharsets.US_ASCII

class ProtocolTest extends AnyFlatSpec {
  "Request encoding" should "follow the contract's zero-based, uppercase-hex text" in {
    assert(Request.Hello(1).line == "HELLO 0001")
    assert(Request.NewGame(1, seed = 0x42, preset = 3, mines = 80).line == "NEW 0001 0042 3 80")
    assert(Request.Reveal(0xaf, row = 2, col = 29).line == "REVEAL 00AF 2 29")
    assert(Request.Flag(0x1234, row = 0, col = 0).line == "FLAG 1234 0 0")
    assert(Request.Get(0xffff).line == "GET FFFF")
    val bytes = Request.Get(7).bytes
    assert(new String(bytes, US_ASCII) == "GET 0007\n")
    assert(bytes.length <= Protocol.MaxRequestBytes)
  }

  it should "wrap the sequence after FFFF" in {
    assert(Protocol.nextSequence(0x0001) == 0x0002)
    assert(Protocol.nextSequence(0xffff) == 0x0000)
  }

  it should "refuse values the text format cannot carry" in {
    intercept[IllegalArgumentException](Request.Get(-1))
    intercept[IllegalArgumentException](Request.Get(0x10000))
    intercept[IllegalArgumentException](Request.Reveal(1, -1, 0))
    intercept[IllegalArgumentException](Request.Flag(1, 0, -1))
    intercept[IllegalArgumentException](Request.NewGame(1, seed = 0x10000, preset = 0, mines = 14))
    intercept[IllegalArgumentException](Request.NewGame(1, seed = 1, preset = -1, mines = 14))
  }

  "Request parsing" should "round-trip every well-formed request" in {
    val requests = Seq(Request.Hello(0), Request.NewGame(0xbeef, 0, 4, 711),
      Request.Reveal(10, 23, 29), Request.Flag(11, 0, 7), Request.Get(0xffff))
    for (r <- requests) assert(Request.parse(r.line).contains(r), r.line)
  }

  it should "reject malformed text instead of guessing" in {
    val bad = Seq("GET 00af", "get 0001", "GET 0001 ", " GET 0001", "GET  0001", "GET 0001 9",
      "GET 001", "REVEAL 0001 1", "REVEAL 0001 +1 2", "REVEAL 0001 -1 2", "REVEAL 0001 1e3 2",
      "REVEAL 0001 x 2", "NEW 0001 042 1 14", "NEW 0001 0042 1", "JUMP 0001", "", "GET 0001\r")
    for (line <- bad) assert(Request.parse(line).isEmpty, line)
  }

  "Reply parsing" should "decode every reply type" in {
    assert(Reply.parse("INFO 0001 2 1.2.0 30 24 14 V1 1") ==
      Right(Info(1, 2, "1.2.0", 30, 24, 14, "V1", 1)))
    assert(Reply.parse("PRESET 0001 3 30 16 14 471") == Right(Preset(1, 3, 30, 16, 14, 471)))
    assert(Reply.parse("ACK 00FF") == Right(Ack(0xff)))
    assert(Reply.parse("SNAP 0002 7 PLAYING 0 8 8 14 -3 1 120 4 4 9") ==
      Right(Snap(2, 7L, SnapState.Playing, 0, 8, 8, 14, -3, hintsValid = true, 120L, 4L, 4L, 9L)))
    assert(Reply.parse("ROW 0002 3 H F 0 8 M") == Right(Row(2, 3,
      Vector(CellToken.Hidden, CellToken.Flagged, CellToken.Number(0), CellToken.Number(8),
        CellToken.Mine))))
    assert(Reply.parse("HINT 0002 3 .SM") ==
      Right(Hint(2, 3, Vector(HintMark.NoHint, HintMark.Safe, HintMark.Mine))))
    assert(Reply.parse("END 0002 4294967295") == Right(End(2, 4294967295L)))
    assert(Reply.parse("ERR 0003 BAD_MINES") == Right(Err(Some(3), "BAD_MINES")))
    assert(Reply.parse("ERR ---- LINE_TOO_LONG") == Right(Err(None, "LINE_TOO_LONG")))
  }

  it should "accept one optional CR before the terminator" in {
    assert(Reply.parse("ACK 0001\r") == Right(Ack(1)))
    assert(Reply.parse("ACK 0001\r\r").isLeft)
  }

  it should "reject structurally invalid lines" in {
    val bad = Seq(
      "ACK ----", "ACK 0001 2", "ACK 00ff", "HELLO 0001",
      "SNAP 0002 7 BOOM 0 8 8 14 14 1 0 0 0 0", // unknown state
      "SNAP 0002 7 SOLVING 0 8 8 14 14 1 0 0 0 0", // transient states are never sent
      "SNAP 0002 7 IDLE 0 8 8 14 14 2 0 0 0 0", // hintsValid is 0/1
      "SNAP 0002 -7 IDLE 0 8 8 14 14 0 0 0 0 0",
      "SNAP 0002 7 IDLE 0 8 8 14 14 0 0 0 0", // missing field
      "END 0002 4294967296", // revision is 32-bit
      "ROW 0002 3", "ROW 0002 3 H 9", "ROW 0002 3 H X", "ROW 0002 3 H  F",
      "HINT 0002 3 .x", "HINT 0002 3", "HINT 0002 3 . S",
      "ERR 0003 OOPS", "ERR 0003", "INFO 0001 2 1.2 30 24 14 V1 1", "INFO 0001 2 1.2.0 30 24 14 V3 1",
      "", "PRESET 0001 3 30 16 14")
    for (line <- bad) assert(Reply.parse(line).isLeft, line)
  }

  // A 3-row x 4-column game: zero-based, row-major, rectangular.
  private def lines(text: String): Seq[Reply] =
    text.trim.linesIterator.map(l => Reply.parse(l.trim).fold(e => fail(s"$l: $e"), identity)).toSeq

  private val good =
    """SNAP 0005 9 PLAYING 0 4 3 14 12 1 0 3 2 5
      |ROW 0005 0 F 1 0 0
      |HINT 0005 0 ....
      |ROW 0005 1 H 2 1 0
      |HINT 0005 1 S...
      |ROW 0005 2 H H F 1
      |HINT 0005 2 MS..
      |END 0005 9""".stripMargin

  "Snapshot assembly" should "accept a coherent rectangular snapshot" in {
    val s = Snapshot.assemble(lines(good)).fold(e => fail(e), identity)
    assert(s.rows == 3 && s.cols == 4)
    assert(s.cell(0, 0) == CellToken.Flagged)
    assert(s.cell(2, 2) == CellToken.Flagged)
    assert(s.cell(1, 1) == CellToken.Number(2))
    assert(s.hint(2, 0) == HintMark.Mine && s.hint(1, 0) == HintMark.Safe)
    assert(s.flagCount == 2)
  }

  private def rejects(text: String): Unit =
    assert(Snapshot.assemble(lines(text)).isLeft, text)

  it should "reject incomplete, reordered or inconsistent snapshots" in {
    val parsed = lines(good)
    val incomplete = parsed.patch(3, Seq.empty, 2) // remove row 1 and its HINT line
    assert(Snapshot.assemble(incomplete).isLeft, "snapshot missing a ROW/HINT pair must be rejected")
    val reordered = Vector(parsed(0), parsed(1), parsed(2), parsed(5), parsed(6),
      parsed(3), parsed(4), parsed(7))
    assert(Snapshot.assemble(reordered).isLeft, "out-of-order ROW/HINT pairs must be rejected")
    rejects(good.replace("ROW 0005 2 H H F 1", "ROW 0005 2 H H F"))
    rejects(good.replace("HINT 0005 2 MS..", "HINT 0005 2 MS."))
    rejects(good.replace("END 0005 9", "END 0005 8"))
    rejects(good.replace("END 0005 9", "END 0006 9"))
    rejects(good.replace("ROW 0005 0 F", "ROW 0004 0 F"))
    rejects(good.linesIterator.toSeq.init.mkString("\n")) // no END
    rejects(good.replace("ROW 0005 1 H 2", "ROW 0005 1 M 2") // mine before loss
      .replace("HINT 0005 1 S...", "HINT 0005 1 ...."))
    rejects(good.replace("HINT 0005 1 S...", "HINT 0005 1 .S..")) // hint on a revealed cell
    rejects(good.replace(" 12 1 0 3", " 13 1 0 3")) // minesLeft != mines - flags
    rejects(good.replace(" 12 1 0 3", " 12 0 0 3")) // hints shown while invalid
  }

  it should "allow a hint on a flagged cell and mines after a loss" in {
    val flaggedHint = good.replace("HINT 0005 0 ....", "HINT 0005 0 S...")
    assert(Snapshot.assemble(lines(flaggedHint)).isRight)
    val lost = good.replace("PLAYING", "LOST").replace("ROW 0005 1 H 2", "ROW 0005 1 M 2")
      .replace("HINT 0005 1 S...", "HINT 0005 1 ....")
    assert(Snapshot.assemble(lines(lost)).isRight)
  }
}
