package minesweeper.host

import scala.annotation.tailrec
import scala.collection.mutable

/** A protocol transcript from docs/protocol; the format is in docs/protocol/README.md. */
final case class Fixture(name: String, steps: Vector[Fixture.Step]) {
  import Fixture._

  /** Each complete request, with split writes joined, and the replies that follow it. */
  def transactions: Vector[(Send, Vector[Pattern])] = {
    val out = mutable.ArrayBuffer.empty[(Send, mutable.ArrayBuffer[Pattern])]
    var partial = Vector.empty[Byte]
    steps.foreach {
      case Send(b, false) => partial ++= b
      case Send(b, true) =>
        out += ((Send(partial ++ b, terminated = true), mutable.ArrayBuffer.empty[Pattern]))
        partial = Vector.empty
      case Expect(p) => out.last._2 += p
    }
    out.map { case (s, ps) => (s, ps.toVector) }.toVector
  }
}

object Fixture {
  sealed trait Step
  /** Bytes the host writes; terminated adds the LF that completes the request line. */
  final case class Send(bytes: Vector[Byte], terminated: Boolean) extends Step
  final case class Expect(pattern: Pattern) extends Step

  /**
    * An expected reply line. A token written as ~value is implementation-defined:
    * it matches any token from a real device, and the mock device sends value.
    */
  final case class Pattern(text: String) {
    private val parts = text.split(" ", -1).toVector
    require(parts.forall(p => p.nonEmpty && p != "~"), s"malformed pattern '$text'")

    def mockLine: String = parts.map(_.stripPrefix("~")).mkString(" ")

    def matches(line: String): Boolean = {
      val got = line.stripSuffix("\r").split(" ", -1).toVector
      got.length == parts.length && parts.zip(got).forall { case (p, g) =>
        if (p.startsWith("~")) g.nonEmpty else p == g
      }
    }
  }
  object Pattern {
    def parse(text: String): Pattern = Pattern(text)
  }

  /** Printable ASCII plus \r, \\ and \xHH escapes; LF is never written inside a line. */
  private def unescape(s: String): Either[String, Vector[Byte]] = {
    val out = Vector.newBuilder[Byte]
    var i = 0
    var error: Option[String] = None
    while (error.isEmpty && i < s.length) {
      val c = s(i)
      if (c == '\\' && s.startsWith("\\r", i)) { out += '\r'.toByte; i += 2 }
      else if (c == '\\' && s.startsWith("\\\\", i)) { out += '\\'.toByte; i += 2 }
      else if (c == '\\' && s.startsWith("\\x", i) && i + 4 <= s.length &&
        s.substring(i + 2, i + 4).forall(h => "0123456789abcdefABCDEF".contains(h))) {
        out += Integer.parseInt(s.substring(i + 2, i + 4), 16).toByte; i += 4
      }
      else if (c == '\\') error = Some(s"unknown escape at column ${i + 1}")
      else if (c < ' ' || c > '~') error = Some(s"non-printable character at column ${i + 1}")
      else { out += c.toByte; i += 1 }
    }
    error.toLeft(out.result())
  }

  def parse(name: String, text: String): Either[String, Fixture] = {
    val steps = Vector.newBuilder[Step]
    var requestSent = false
    var partialOpen = false
    var error: Option[String] = None
    val lines = text.linesIterator.zipWithIndex
    while (error.isEmpty && lines.hasNext) {
      val (line, i) = lines.next()
      def fail(msg: String): Unit = error = Some(s"$name:${i + 1}: $msg")
      if (line.isEmpty || line.startsWith("#")) ()
      else if (line.startsWith(">| ") || line.startsWith("> ")) {
        val terminated = line.startsWith("> ")
        unescape(line.drop(if (terminated) 2 else 3)) match {
          case Left(e) => fail(e)
          case Right(b) =>
            steps += Send(b, terminated)
            partialOpen = !terminated
            requestSent ||= terminated
        }
      } else if (line.startsWith("< ")) {
        if (!requestSent || partialOpen) fail("reply before a complete request")
        else try steps += Expect(Pattern.parse(line.drop(2)))
        catch { case e: IllegalArgumentException => fail(e.getMessage) }
      } else fail("expected '#', '> ', '>| ' or '< '")
    }
    if (error.isEmpty && partialOpen) error = Some(s"$name: ends inside a partial request")
    error.toLeft(Fixture(name, steps.result()))
  }
}

/** Mock device that answers exactly as a fixture prescribes; no FPGA needed. */
final class ScriptedDevice(fixture: Fixture) extends Transport {
  import Fixture._
  private val steps = fixture.steps
  private var pos = 0
  private val received = mutable.ArrayBuffer.empty[Byte]
  private val replies = mutable.Queue.empty[String]
  private var violation: Option[String] = None

  def send(bytes: Array[Byte]): Unit = { received ++= bytes; advance() }

  def readLine(timeoutMillis: Long): Option[String] =
    if (replies.nonEmpty) Some(replies.dequeue()) else None

  /** The host's first deviation from the script, if any. */
  def error: Option[String] = violation

  /** Every scripted request arrived exactly and every reply was read. */
  def finished: Boolean =
    violation.isEmpty && pos == steps.length && received.isEmpty && replies.isEmpty

  @tailrec private def advance(): Unit =
    if (violation.isEmpty && received.nonEmpty) steps.lift(pos) match {
      case Some(Send(b, _)) if received.startsWith(b) =>
        received.remove(0, b.length)
        pos += 1
        while (pos < steps.length && steps(pos).isInstanceOf[Expect]) {
          replies.enqueue(steps(pos).asInstanceOf[Expect].pattern.mockLine)
          pos += 1
        }
        advance()
      case Some(Send(b, _)) if b.startsWith(received) => () // rest of the line still to come
      case _ => violation = Some(s"${fixture.name}: unexpected bytes from the host at step ${pos + 1}")
    }
}

final case class ReplayReport(linesChecked: Int, snapshotsChecked: Int)

/** Send a fixture's requests to any device and check every reply line and snapshot. */
object Replay {
  def run(fixture: Fixture, device: Transport, timeoutMillis: Long = 2000): Either[String, ReplayReport] = {
    var lines = 0
    var snapshots = 0
    var transaction = Vector.empty[Reply]
    var failure: Option[String] = None
    val it = fixture.steps.iterator.zipWithIndex
    while (failure.isEmpty && it.hasNext) it.next() match {
      case (Fixture.Send(b, terminated), _) =>
        device.send(b.toArray)
        if (terminated) transaction = Vector.empty
      case (Fixture.Expect(p), i) =>
        val where = s"${fixture.name} step ${i + 1}"
        device.readLine(timeoutMillis) match {
          case None => failure = Some(s"$where: no reply, expected '${p.text}'")
          case Some(line) => Reply.parse(line) match {
            case Left(e) => failure = Some(s"$where: $e")
            case Right(_) if !p.matches(line) =>
              failure = Some(s"$where: expected '${p.text}', got '$line'")
            case Right(reply) =>
              lines += 1
              transaction :+= reply
              if (reply.isInstanceOf[Reply.End] && transaction.exists(_.isInstanceOf[Reply.Snap]))
                this.snapshots(transaction) match {
                  case Left(e) => failure = Some(s"$where: $e")
                  case Right(s) => snapshots += s.length
                }
          }
        }
    }
    failure
      .orElse(device.readLine(0).map(l => s"${fixture.name}: unexpected extra reply '$l'"))
      .toLeft(ReplayReport(lines, snapshots))
  }

  /** Assemble every SNAP..END group in a reply stream. */
  def snapshots(replies: Seq[Reply]): Either[String, Vector[Snapshot]] = {
    val starts = replies.indices.filter(i => replies(i).isInstanceOf[Reply.Snap])
    starts.foldLeft[Either[String, Vector[Snapshot]]](Right(Vector.empty)) { (acc, i) =>
      acc.flatMap { done =>
        val end = replies.indexWhere(_.isInstanceOf[Reply.End], i)
        if (end < 0) Left("SNAP without END")
        else Snapshot.assemble(replies.slice(i, end + 1)).map(done :+ _)
      }
    }
  }
}
