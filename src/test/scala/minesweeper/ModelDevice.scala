package minesweeper

import minesweeper.host.{Protocol, Request, Transport}
import minesweeper.model.{CellKind, GoldenModel, State, Status}

import java.io.{BufferedInputStream, OutputStream}
import java.net.{ServerSocket, Socket}
import java.nio.charset.StandardCharsets.US_ASCII
import scala.collection.mutable

/**
  * A board that speaks UART protocol version 2 (CONTRACT 6), backed by the golden model instead
  * of the FPGA, for the PC display (scripts/board_display.py) and its tests when no board is
  * plugged in. It is checked against the protocol fixtures in docs/protocol (ModelDeviceTest).
  *
  * Requests are handled one at a time as soon as their LF arrives, so BUSY never occurs and
  * there are no UART framing errors; everything else follows CONTRACT 6: validation order and
  * error codes, the 64-byte line limit, optional CR, split writes, and the retry cache.
  */
final class ModelDevice(
    p: MsParams = MsParams(),
    legacyProtocolFixtures: Boolean = false
) extends Transport {
  private var model = new GoldenModel(p)
  private val pending = mutable.ArrayBuffer.empty[Byte]
  private var discarding = false // an oversized line is discarded through its LF
  private val replies = mutable.Queue.empty[String]
  // The latest admitted (acknowledged) request and its reply, for identical retries.
  private var cached: Option[(Request, Vector[String])] = None

  /** Physical reset: the default game, revision 0, no retry state. */
  def reset(): Unit = {
    model = new GoldenModel(p)
    pending.clear()
    discarding = false
    replies.clear()
    cached = None
  }

  def send(bytes: Array[Byte]): Unit = bytes.foreach { b =>
    if (b == '\n'.toByte) {
      if (discarding) { replies.enqueue("ERR ---- LINE_TOO_LONG"); discarding = false }
      else {
        val raw = new String(pending.toArray, US_ASCII)
        handle(if (raw.endsWith("\r")) raw.dropRight(1) else raw)
      }
      pending.clear()
    } else if (!discarding) {
      pending += b
      // The limit counts the LF: 63 bytes of text at most.
      if (pending.length > Protocol.MaxRequestBytes - 1) { discarding = true; pending.clear() }
    }
  }

  def readLine(timeoutMillis: Long): Option[String] = if (replies.nonEmpty) Some(replies.dequeue()) else None

  private def emit(lines: Seq[String]): Unit = lines.foreach(replies.enqueue)

  private def handle(line: String): Unit = {
    val fields = line.split(" ", -1).toVector
    val seq = fields.lift(1).flatMap(Protocol.parseHex4)
    val seqText = seq.map(Protocol.hex4).getOrElse("----")
    def err(code: String): Unit = emit(Seq(s"ERR $seqText $code"))
    if (!Set("HELLO", "NEW", "REVEAL", "FLAG", "GET").contains(fields.head)) err("BAD_CMD")
    else Request.parse(line) match {
      case None => err("BAD_ARG")
      case Some(r: Request.Hello) =>
        cached = None // HELLO starts a fresh retry session
        emit(hello(r.seq))
      case Some(r) => cached match {
        case Some((c, reply)) if c.seq == r.seq => if (c == r) emit(reply) else err("SEQ")
        case _ => validate(r) match {
          case Some(code) => err(code)
          case None =>
            val reply = execute(r)
            cached = Some((r, reply))
            emit(reply)
        }
      }
    }
  }

  /** Semantic checks before admission, in the order of CONTRACT 3. */
  private def validate(r: Request): Option[String] = r match {
    case Request.NewGame(_, seed, preset, mines) =>
      p.presets.find(_.id == preset) match {
        case None => Some("BAD_PRESET")
        case Some(g) if mines < p.minMines || mines > p.maxMines(g) => Some("BAD_MINES")
        case _ if seed == 0 => Some("BAD_SEED")
        case _ => None
      }
    case Request.Reveal(_, row, col) => inGrid(row, col)
    case Request.Flag(_, row, col) => inGrid(row, col)
    case _ => None
  }

  private def inGrid(row: Int, col: Int): Option[String] = {
    val s = model.snapshot
    if (row < s.rows && col < s.cols) None else Some("RANGE")
  }

  private def execute(r: Request): Vector[String] = {
    val result = r match {
      case Request.NewGame(_, seed, preset, mines) => Some(model.newGame(preset, mines, seed))
      case Request.Reveal(_, row, col) => Some(model.reveal(row, col))
      case Request.Flag(_, row, col) => Some(model.flag(row, col))
      case _ => None // GET
    }
    val s = Protocol.hex4(r.seq)
    if (result.exists(_.status == Status.InternalError)) Vector(s"ACK $s", s"ERR $s INTERNAL")
    else s"ACK $s" +: snapshot(r.seq)
  }

  private def hello(seq: Int): Vector[String] = {
    val s = Protocol.hex4(seq)
    val mode = if (legacyProtocolFixtures) "V1" else if (p.solverV2) "V2" else "V1"
    val version = if (legacyProtocolFixtures) "1.2.0" else MsParams.contractVersion
    (s"INFO $s ${Protocol.Version} $version ${p.maxCols} ${p.maxRows} ${p.minMines} $mode 1" +:
      p.presets.map(g => s"PRESET $s ${g.id} ${g.cols} ${g.rows} ${p.minMines} ${p.maxMines(g)}")) :+
      s"END $s ${model.snapshot.revision}"
  }

  private def snapshot(seq: Int): Vector[String] = {
    val s = model.snapshot
    val q = Protocol.hex4(seq)
    val state = s.state match {
      case State.Idle => "IDLE"
      case State.Playing => "PLAYING"
      case State.Won => "WON"
      case State.Lost => "LOST"
      case _ => "ERROR"
    }
    val head = s"SNAP $q ${s.revision} $state ${s.preset} ${s.cols} ${s.rows} ${s.mines} ${s.minesLeft} " +
      s"${if (s.hintsValid) 1 else 0} ${s.generationCycles} ${s.revealCycles} ${s.revealLayers} ${s.solverCycles}"
    val rows = (0 until s.rows).flatMap { r =>
      val idx = (0 until s.cols).map(c => r * s.maxCols + c)
      val cells = idx.map { i =>
        s.cells(i).kind match {
          case CellKind.Hidden => "H"
          case CellKind.Flagged => "F"
          case CellKind.Mine => "M"
          case CellKind.Number => s.cells(i).adj.toString
        }
      }
      val hints = idx.map { i =>
        if (!s.hintsValid) '.' else if (s.hints(i).safe) 'S' else if (s.hints(i).mine) 'M' else '.'
      }
      Seq(s"ROW $q $r ${cells.mkString(" ")}", s"HINT $q $r ${hints.mkString}")
    }
    (head +: rows.toVector) :+ s"END $q ${s.revision}"
  }
}

/**
  * The model board on a TCP port, so the PC display can be tried without an FPGA:
  * {{{
  * sbt "Test/runMain minesweeper.SimBoardServer 7777"
  * python scripts/board_display.py --port socket://localhost:7777
  * }}}
  * One client at a time. A new connection keeps the game (as replugging the USB cable would);
  * the display recovers it with HELLO and GET.
  */
object SimBoardServer {
  def main(args: Array[String]): Unit = {
    val port = args.headOption.map(_.toInt).getOrElse(7777)
    val device = new ModelDevice()
    val server = new ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress)
    println(s"Simulated board (golden model, production presets) on localhost:$port. Ctrl+C to stop.")
    while (true) {
      val client: Socket = server.accept()
      println(s"Client connected from ${client.getRemoteSocketAddress}")
      try serve(device, new BufferedInputStream(client.getInputStream), client.getOutputStream)
      catch { case e: java.io.IOException => println(s"Connection closed: ${e.getMessage}") }
      finally client.close()
      println("Client disconnected")
    }
  }

  private def serve(device: ModelDevice, in: BufferedInputStream, out: OutputStream): Unit = {
    val buffer = new Array[Byte](256)
    var n = in.read(buffer)
    while (n >= 0) {
      device.send(buffer.take(n))
      var line = device.readLine(0)
      while (line.isDefined) {
        out.write((line.get + "\n").getBytes(US_ASCII))
        line = device.readLine(0)
      }
      out.flush()
      n = in.read(buffer)
    }
  }
}
