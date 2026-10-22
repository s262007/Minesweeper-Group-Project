package minesweeper.host

import java.nio.charset.StandardCharsets.US_ASCII

/**
  * Host side of UART protocol version 2 (CONTRACT section 6): text encoding and
  * structural checks only. The host never computes game state, flood fills or hints.
  */
object Protocol {
  val Version: Int = 2
  val MaxRequestBytes: Int = 64
  val MaxResponseLine: Int = 256
  val MaxU32: Long = 0xffffffffL

  def hex4(v: Int): String = {
    require(v >= 0 && v <= 0xffff, s"$v does not fit four hex digits")
    f"$v%04X"
  }

  def nextSequence(seq: Int): Int = (seq + 1) & 0xffff

  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
  private def isUpperHex(c: Char): Boolean = isDigit(c) || (c >= 'A' && c <= 'F')

  def parseHex4(s: String): Option[Int] =
    if (s.length == 4 && s.forall(isUpperHex)) Some(Integer.parseInt(s, 16)) else None

  /** Decimal without sign, spaces or exponent, at most max. */
  def parseUnsigned(s: String, max: Long): Option[Long] =
    if (s.nonEmpty && s.length <= 10 && s.forall(isDigit)) Some(s.toLong).filter(_ <= max)
    else None

  def parseInt(s: String): Option[Int] = parseUnsigned(s, Int.MaxValue).map(_.toInt)

  /** Only the response minesLeft field may be negative. */
  def parseSigned(s: String): Option[Int] =
    if (s.startsWith("-")) parseInt(s.drop(1)).map(-_) else parseInt(s)

  /** Tokens separated by exactly one space; None for empty fields or stray spaces. */
  def tokens(line: String): Option[Vector[String]] = {
    val t = line.split(" ", -1).toVector
    if (t.exists(_.isEmpty)) None else Some(t)
  }
}

import Protocol._

sealed trait Request {
  def seq: Int
  def line: String
  /** The line plus LF; field widths keep every request below MaxRequestBytes. */
  final def bytes: Array[Byte] = (line + "\n").getBytes(US_ASCII)
}

object Request {
  final case class Hello(seq: Int) extends Request {
    val line: String = s"HELLO ${hex4(seq)}"
  }
  final case class NewGame(seq: Int, seed: Int, preset: Int, mines: Int) extends Request {
    require(preset >= 0 && mines >= 0, "preset and mines are unsigned")
    val line: String = s"NEW ${hex4(seq)} ${hex4(seed)} $preset $mines"
  }
  final case class Reveal(seq: Int, row: Int, col: Int) extends Request {
    require(row >= 0 && col >= 0, "coordinates are zero-based and unsigned")
    val line: String = s"REVEAL ${hex4(seq)} $row $col"
  }
  final case class Flag(seq: Int, row: Int, col: Int) extends Request {
    require(row >= 0 && col >= 0, "coordinates are zero-based and unsigned")
    val line: String = s"FLAG ${hex4(seq)} $row $col"
  }
  final case class Get(seq: Int) extends Request {
    val line: String = s"GET ${hex4(seq)}"
  }

  /** Strict syntactic parse of a line without terminator. Semantic limits belong to the device. */
  def parse(line: String): Option[Request] = tokens(line).flatMap {
    case Vector("HELLO", s) => parseHex4(s).map(Hello)
    case Vector("GET", s) => parseHex4(s).map(Get)
    case Vector("NEW", s, h, p, m) =>
      for (q <- parseHex4(s); seed <- parseHex4(h); preset <- parseInt(p); mines <- parseInt(m))
        yield NewGame(q, seed, preset, mines)
    case Vector("REVEAL", s, r, c) =>
      for (q <- parseHex4(s); row <- parseInt(r); col <- parseInt(c)) yield Reveal(q, row, col)
    case Vector("FLAG", s, r, c) =>
      for (q <- parseHex4(s); row <- parseInt(r); col <- parseInt(c)) yield Flag(q, row, col)
    case _ => None
  }
}

sealed abstract class CellToken(val text: String)
object CellToken {
  case object Hidden extends CellToken("H")
  case object Flagged extends CellToken("F")
  /** Only after a loss. */
  case object Mine extends CellToken("M")
  final case class Number(n: Int) extends CellToken(n.toString) {
    require(n >= 0 && n <= 8, "adjacent counts are 0..8")
  }

  def parse(s: String): Option[CellToken] = s match {
    case "H" => Some(Hidden)
    case "F" => Some(Flagged)
    case "M" => Some(Mine)
    case d if d.length == 1 && d(0) >= '0' && d(0) <= '8' => Some(Number(d(0) - '0'))
    case _ => None
  }
}

sealed abstract class HintMark(val char: Char)
object HintMark {
  case object NoHint extends HintMark('.')
  case object Safe extends HintMark('S')
  case object Mine extends HintMark('M')
  private val all = Seq(NoHint, Safe, Mine)
  def parse(c: Char): Option[HintMark] = all.find(_.char == c)
}

/** Stable states only; transient Generating/Revealing/Solving are never sent. */
sealed abstract class SnapState(val text: String)
object SnapState {
  case object Idle extends SnapState("IDLE")
  case object Playing extends SnapState("PLAYING")
  case object Won extends SnapState("WON")
  case object Lost extends SnapState("LOST")
  case object Error extends SnapState("ERROR")
  private val all = Seq(Idle, Playing, Won, Lost, Error)
  def parse(s: String): Option[SnapState] = all.find(_.text == s)
}

sealed trait Reply
object Reply {
  val ErrCodes: Set[String] = Set("BUSY", "BAD_CMD", "BAD_ARG", "BAD_PRESET", "BAD_MINES",
    "BAD_SEED", "RANGE", "LINE_TOO_LONG", "SEQ", "INTERNAL", "RX_FRAME", "RX_OVERFLOW")

  final case class Info(seq: Int, protocol: Int, contract: String, maxCols: Int, maxRows: Int,
      minMines: Int, solver: String, lanes: Int) extends Reply
  final case class Preset(seq: Int, id: Int, cols: Int, rows: Int, minMines: Int, maxMines: Int)
      extends Reply
  final case class Ack(seq: Int) extends Reply
  final case class Snap(seq: Int, revision: Long, state: SnapState, preset: Int, cols: Int,
      rows: Int, mines: Int, minesLeft: Int, hintsValid: Boolean, generationCycles: Long,
      revealCycles: Long, revealLayers: Long, solverCycles: Long) extends Reply
  final case class Row(seq: Int, row: Int, cells: Vector[CellToken]) extends Reply
  final case class Hint(seq: Int, row: Int, marks: Vector[HintMark]) extends Reply
  final case class End(seq: Int, revision: Long) extends Reply
  /** seq is None for "----". */
  final case class Err(seq: Option[Int], code: String) extends Reply

  private def u32(s: String): Option[Long] = parseUnsigned(s, MaxU32)
  private def version(s: String): Boolean = {
    val parts = s.split("\\.", -1)
    parts.length == 3 && parts.forall(p => parseInt(p).isDefined)
  }
  private def all[A](xs: Seq[Option[A]]): Option[Vector[A]] =
    if (xs.forall(_.isDefined)) Some(xs.flatten.toVector) else None

  /** Parse one reply line; a single trailing CR is tolerated. */
  def parse(raw: String): Either[String, Reply] = {
    val line = raw.stripSuffix("\r")
    if (line.length > MaxResponseLine) Left(s"reply longer than $MaxResponseLine bytes")
    else tokens(line).flatMap(decode).toRight(s"malformed reply '$raw'")
  }

  private def decode(t: Vector[String]): Option[Reply] = t match {
    case Vector("INFO", s, v, c, mc, mr, mm, sv, l) =>
      for {
        q <- parseHex4(s); ver <- parseInt(v) if version(c)
        maxCols <- parseInt(mc); maxRows <- parseInt(mr); minMines <- parseInt(mm)
        if sv == "V1" || sv == "V2"
        lanes <- parseInt(l)
      } yield Info(q, ver, c, maxCols, maxRows, minMines, sv, lanes)
    case Vector("PRESET", s, i, c, r, mn, mx) =>
      for {
        q <- parseHex4(s); id <- parseInt(i); cols <- parseInt(c); rows <- parseInt(r)
        minMines <- parseInt(mn); maxMines <- parseInt(mx)
      } yield Preset(q, id, cols, rows, minMines, maxMines)
    case Vector("ACK", s) => parseHex4(s).map(Ack)
    case Vector("SNAP", s, rev, st, p, c, r, m, ml, hv, g, rc, rl, sc) =>
      for {
        q <- parseHex4(s); revision <- u32(rev); state <- SnapState.parse(st)
        preset <- parseInt(p); cols <- parseInt(c); rows <- parseInt(r); mines <- parseInt(m)
        minesLeft <- parseSigned(ml)
        hintsValid <- hv match { case "0" => Some(false); case "1" => Some(true); case _ => None }
        gen <- u32(g); revealCycles <- u32(rc); layers <- u32(rl); solverCycles <- u32(sc)
      } yield Snap(q, revision, state, preset, cols, rows, mines, minesLeft, hintsValid, gen,
        revealCycles, layers, solverCycles)
    case "ROW" +: s +: r +: cells if cells.nonEmpty =>
      for (q <- parseHex4(s); row <- parseInt(r); tokens <- all(cells.map(CellToken.parse)))
        yield Row(q, row, tokens)
    case Vector("HINT", s, r, mask) =>
      for (q <- parseHex4(s); row <- parseInt(r); marks <- all(mask.map(HintMark.parse)))
        yield Hint(q, row, marks)
    case Vector("END", s, rev) => for (q <- parseHex4(s); revision <- u32(rev)) yield End(q, revision)
    case Vector("ERR", "----", code) if ErrCodes(code) => Some(Err(None, code))
    case Vector("ERR", s, code) if ErrCodes(code) => parseHex4(s).map(q => Err(Some(q), code))
    case _ => None
  }
}

/** A complete, validated board as the host may display it. */
final case class Snapshot(snap: Reply.Snap, cells: Vector[Vector[CellToken]],
    hints: Vector[Vector[HintMark]]) {
  def rows: Int = snap.rows
  def cols: Int = snap.cols
  def cell(row: Int, col: Int): CellToken = cells(row)(col)
  def hint(row: Int, col: Int): HintMark = hints(row)(col)
  def flagCount: Int = cells.map(_.count(_ == CellToken.Flagged)).sum
}

object Snapshot {
  /**
    * Build a snapshot from SNAP through END. Rejects anything a host must not commit
    * to the display: missing or reordered rows, wrong lengths, mismatched sequence or
    * revision, mines before a loss, and hints that are invalid or on revealed cells.
    */
  def assemble(lines: Seq[Reply]): Either[String, Snapshot] = lines.headOption match {
    case Some(snap: Reply.Snap) =>
      val expected = 2 + 2 * snap.rows
      if (snap.rows < 1 || snap.cols < 1) Left("empty grid")
      else if (lines.length != expected) Left(s"expected $expected lines, got ${lines.length}")
      else {
        val rows = lines.slice(1, expected - 1).grouped(2).zipWithIndex.map {
          case (Seq(Reply.Row(rs, r, cs), Reply.Hint(hs, hr, ms)), i)
              if rs == snap.seq && hs == snap.seq && r == i && hr == i &&
                cs.length == snap.cols && ms.length == snap.cols => Right((cs, ms))
          case (_, i) => Left(s"row $i: expected ROW then HINT for row $i with ${snap.cols} " +
            s"entries and sequence ${hex4(snap.seq)}")
        }.toVector
        rows.collectFirst { case Left(e) => e } match {
          case Some(e) => Left(e)
          case None => lines.last match {
            case Reply.End(seq, rev) if seq == snap.seq && rev == snap.revision =>
              val ok = rows.collect { case Right(r) => r }
              check(Snapshot(snap, ok.map(_._1), ok.map(_._2)))
            case other => Left(s"expected END ${hex4(snap.seq)} ${snap.revision}, got $other")
          }
        }
      }
    case other => Left(s"snapshot must start with SNAP, got $other")
  }

  private def check(s: Snapshot): Either[String, Snapshot] = {
    val positions = for (r <- 0 until s.rows; c <- 0 until s.cols) yield (r, c)
    def unrevealed(t: CellToken) = t == CellToken.Hidden || t == CellToken.Flagged
    val leakedMine = positions.find { case (r, c) =>
      s.snap.state != SnapState.Lost && s.cell(r, c) == CellToken.Mine }
    val invalidHint = positions.find { case (r, c) =>
      !s.snap.hintsValid && s.hint(r, c) != HintMark.NoHint }
    val revealedHint = positions.find { case (r, c) =>
      s.hint(r, c) != HintMark.NoHint && !unrevealed(s.cell(r, c)) }
    if (leakedMine.isDefined) Left(s"mine shown at ${leakedMine.get} before a loss")
    else if (invalidHint.isDefined) Left(s"hint at ${invalidHint.get} while hintsValid=0")
    else if (revealedHint.isDefined) Left(s"hint on revealed cell ${revealedHint.get}")
    else if (s.snap.minesLeft != s.snap.mines - s.flagCount)
      Left(s"minesLeft ${s.snap.minesLeft} != ${s.snap.mines} mines - ${s.flagCount} flags")
    else Right(s)
  }
}
