package minesweeper

import chisel3._
import chiseltest._

import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.{Files, Paths}
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import scala.sys.process._

object R1Benchmark {
  private val seed = 0xace1
  private val sequence = 0x0042

  private final case class Sample(
      presetId: Int,
      cols: Int,
      rows: Int,
      mines: Int,
      seed: Int,
      repeat: Int,
      newGameCycles: Int,
      revealCommandCycles: Int,
      generationCycles: Long,
      revealCycles: Long,
      revealLayers: Long,
      solverCycles: Long,
      outcome: String,
      snapshotBytes: Int,
      responseBytesWithAck: Int,
      simulatorWallMs: Long,
      fingerprint: String
  ) {
    def csv(deterministic: Boolean): String = Seq(
      presetId, cols, rows, mines, f"$seed%04X", repeat, newGameCycles,
      revealCommandCycles, generationCycles, revealCycles, revealLayers, solverCycles,
      outcome, snapshotBytes, responseBytesWithAck, simulatorWallMs, fingerprint,
      if (deterministic) "PASS" else "FAIL"
    ).mkString(",")
  }

  private def stateName(state: BigInt): String = {
    val pairs = Seq(GameState.Idle -> "IDLE", GameState.Generating -> "GENERATING",
      GameState.Revealing -> "REVEALING", GameState.Solving -> "SOLVING",
      GameState.Playing -> "PLAYING", GameState.Won -> "WON", GameState.Lost -> "LOST",
      GameState.Error -> "ERROR")
    pairs.find(_._1.litValue == state).map(_._2).getOrElse(s"UNKNOWN_$state")
  }

  private def gameState(dut: MinesweeperCore): String = stateName(dut.io.state.peek().litValue)

  private def issue(dut: MinesweeperCore, kind: CmdKind.Type, row: Int = 0, col: Int = 0,
      preset: Int = 0, mines: Int = 14, gameSeed: Int = seed): Int = {
    var readyWait = 0
    while (!dut.io.cmd.ready.peek().litToBoolean && readyWait < 1000) {
      dut.clock.step()
      readyWait += 1
    }
    require(readyWait < 1000, s"Core did not accept $kind after $readyWait clocks")

    dut.io.cmd.bits.kind.poke(kind)
    dut.io.cmd.bits.row.poke(row.U)
    dut.io.cmd.bits.col.poke(col.U)
    dut.io.cmd.bits.preset.poke(preset.U)
    dut.io.cmd.bits.mines.poke(mines.U)
    dut.io.cmd.bits.seed.poke(gameSeed.U)
    dut.io.cmd.valid.poke(true.B)
    dut.clock.step()
    dut.io.cmd.valid.poke(false.B)

    var clocks = 1
    while (!dut.io.result.valid.peek().litToBoolean && clocks < 70000) {
      dut.clock.step()
      clocks += 1
    }
    require(clocks < 70000, s"$kind did not complete within 70000 clocks")
    val status = dut.io.result.bits.status.peek().litValue
    require(status == CommandStatus.Ok.litValue, s"$kind returned status encoding $status")
    dut.io.result.ready.poke(true.B)
    dut.clock.step()
    dut.io.result.ready.poke(false.B)
    clocks
  }

  private def decimal(value: Long, digits: Int): String =
    String.format(Locale.ROOT, s"%0${digits}d", Long.box(value))

  private def cellToken(dut: MinesweeperCore, idx: Int): String = {
    val cell = dut.io.cells(idx)
    if (!cell.active.peek().litToBoolean) "H"
    else cell.kind.peek().litValue match {
      case v if v == PublicCellKind.Hidden.litValue => "H"
      case v if v == PublicCellKind.Flagged.litValue => "F"
      case v if v == PublicCellKind.Number.litValue => cell.adj.peek().litValue.toString
      case v if v == PublicCellKind.Mine.litValue => "M"
      case other => throw new IllegalStateException(s"unknown cell kind $other at $idx")
    }
  }

  private def snapshotBytes(dut: MinesweeperCore, p: MsParams): Int = {
    val config = dut.io.config
    val cols = config.cols.peek().litValue.toInt
    val rows = config.rows.peek().litValue.toInt
    val preset = config.preset.peek().litValue.toInt
    val mines = config.mines.peek().litValue.toInt
    val revision = dut.io.revision.peek().litValue.toLong
    val minesLeft = dut.io.minesLeft.peek().litValue.toInt
    val hintsValid = dut.io.hintsValid.peek().litToBoolean
    val generation = dut.io.generationCycles.peek().litValue.toLong
    val reveal = dut.io.revealCycles.peek().litValue.toLong
    val layers = dut.io.revealLayers.peek().litValue.toLong
    val solve = dut.io.solverCycles.peek().litValue.toLong
    val state = gameState(dut)
    val signedMinesLeft = (if (minesLeft < 0) "-" else "0") + decimal(math.abs(minesLeft.toLong), 3)
    val snap = s"SNAP ${f"$sequence%04X"} ${decimal(revision, 10)} $state " +
      s"${decimal(preset, 1)} ${decimal(cols, 2)} ${decimal(rows, 2)} ${decimal(mines, 3)} " +
      s"$signedMinesLeft ${if (hintsValid) 1 else 0} ${decimal(generation, 10)} " +
      s"${decimal(reveal, 10)} ${decimal(layers, 3)} ${decimal(solve, 10)}\n"

    val rowsAndHints = (0 until rows).flatMap { row =>
      val cells = (0 until cols).map(col => cellToken(dut, row * p.maxCols + col))
      val marks = (0 until cols).map { col =>
        val hint = dut.io.hints(row * p.maxCols + col)
        if (!hintsValid) '.'
        else if (hint.safe.peek().litToBoolean) 'S'
        else if (hint.mine.peek().litToBoolean) 'M'
        else '.'
      }.mkString
      Seq(s"ROW ${f"$sequence%04X"} ${decimal(row, 2)} ${cells.mkString(" ")}\n",
        s"HINT ${f"$sequence%04X"} ${decimal(row, 2)} $marks\n")
    }
    val end = s"END ${f"$sequence%04X"} ${decimal(revision, 10)}\n"
    (snap + rowsAndHints.mkString + end).getBytes(US_ASCII).length
  }

  private def fingerprint(dut: MinesweeperCore, p: MsParams): String = {
    val cfg = dut.io.config
    val cols = cfg.cols.peek().litValue.toInt
    val rows = cfg.rows.peek().litValue.toInt
    val cells = (0 until p.cells).map { idx =>
      val cell = dut.io.cells(idx)
      val active = cell.active.peek().litToBoolean
      val kind = cell.kind.peek().litValue.toString
      val adj = cell.adj.peek().litValue.toString
      s"$active:$kind:$adj"
    }.mkString("|")
    val hints = (0 until p.cells).map { idx =>
      val h = dut.io.hints(idx)
      s"${h.safe.peek().litToBoolean}:${h.mine.peek().litToBoolean}"
    }.mkString("|")
    val metrics = Seq(dut.io.generationCycles.peek().litValue,
      dut.io.revealCycles.peek().litValue, dut.io.revealLayers.peek().litValue,
      dut.io.solverCycles.peek().litValue).mkString(":")
    val value = s"${cfg.preset.peek().litValue}:$cols:$rows:" +
      s"${cfg.mines.peek().litValue}:${gameState(dut)}:$metrics:$cells:$hints"
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(US_ASCII))
      .map(b => f"${b & 0xff}%02x").mkString
  }

  private def runSample(dut: MinesweeperCore, p: MsParams, g: GridPreset,
      count: Int, repeat: Int): Sample = {
    val start = System.nanoTime()
    val newGameCycles = issue(dut, CmdKind.NewGame, preset = g.id, mines = count)
    val revealCycles = issue(dut, CmdKind.Reveal, row = 0, col = 0)
    val elapsed = (System.nanoTime() - start) / 1000000L
    val snapBytes = snapshotBytes(dut, p)
    Sample(g.id, g.cols, g.rows, count, seed, repeat, newGameCycles, revealCycles,
      dut.io.generationCycles.peek().litValue.toLong,
      dut.io.revealCycles.peek().litValue.toLong,
      dut.io.revealLayers.peek().litValue.toLong,
      dut.io.solverCycles.peek().litValue.toLong, gameState(dut), snapBytes,
      9 + snapBytes, elapsed, fingerprint(dut, p))
  }

  def main(args: Array[String]): Unit = {
    val quick = args.contains("--quick")
    val onlyPreset = args.sliding(2).collectFirst {
      case Array("--preset", id) => id.toInt
    }
    val onlyMines = args.sliding(2).collectFirst {
      case Array("--mines", n) => n.toInt
    }
    val repeats = args.sliding(2).collectFirst {
      case Array("--repeats", n) => n.toInt
    }.getOrElse(2)
    require(repeats >= 2, "use at least two repeats for the deterministic comparison")

    val p = if (quick) MsParams.forVerification(8, 8) else MsParams()
    if (quick) require(onlyPreset.isEmpty || onlyPreset.contains(0),
      "--quick uses the compact 8x8 preset (id 0); use the full mode for other presets")
    require(!quick || onlyMines.isEmpty,
      "--mines is for production-capacity runs; quick mode always uses 14 mines")
    val presets = if (quick) Seq(p.presets.head) else onlyPreset match {
      case Some(id) => Seq(p.preset(id).getOrElse(throw new IllegalArgumentException(s"bad preset $id")))
      case None => p.presets
    }
    val counts = (g: GridPreset) => {
      val selected = onlyMines match {
        case Some(n) => Seq(n)
        case None if quick => Seq(14)
        case None => Seq(14, (g.cells * 0.25).toInt, (g.cells * 0.35).toInt)
      }
      selected.distinct.map { n =>
        require(n >= p.minMines && n <= p.maxMines(g), s"illegal benchmark count $n for $g")
        n
      }
    }

    val outDir = Paths.get("target", "r1-s4")
    Files.createDirectories(outDir)
    val csvPath = outDir.resolve("benchmarks.csv")
    val metaPath = outDir.resolve("metadata.txt")
    val commit = scala.sys.process.Process(Seq("git", "rev-parse", "HEAD")).!!.trim
    val dirty = scala.sys.process.Process(Seq("git", "status", "--porcelain")).!!.trim.nonEmpty
    val metadata = Seq(
      s"started=${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}",
      s"branch=${scala.sys.process.Process(Seq("git", "branch", "--show-current")).!!.trim}",
      s"head=$commit",
      s"workingTreeDirty=$dirty",
      s"java=${System.getProperty("java.version")}",
      "sbt=1.12.13",
      "scala=2.13.16",
      "chisel=6.7.0",
      "chiseltest=6.0.0",
      "simulator=ChiselTest default simulator",
      s"mode=${if (quick) "quick smoke (compact 8x8 verification capacity, 14 mines)" else if (onlyMines.nonEmpty) s"targeted production-capacity run (${onlyMines.get} mines)" else "full production-capacity sweep (14, floor 25%, floor 35%)"}",
      s"capacity=${p.maxCols}x${p.maxRows}${if (quick) " verification" else " production"}",
      s"repeats=$repeats",
      "responseBytes=exact ASCII byte count derived from the public snapshot and UART v2 line format; ACK is 9 bytes"
    ).mkString("\n") + "\n"
    Files.write(metaPath, metadata.getBytes(US_ASCII))
    val header = "preset,cols,rows,mines,seed_hex,repeat,new_game_command_cycles," +
      "reveal_command_cycles,generation_cycles,reveal_cycles,reveal_layers,solver_cycles," +
      "outcome,snapshot_bytes,response_bytes_including_ack,simulator_wall_ms," +
      "public_snapshot_sha256,repeat_determinism\n"
    Files.write(csvPath, header.getBytes(US_ASCII))

    println(s"R1 S4 benchmark: branch metadata in $metaPath; rows in $csvPath")
    println(s"Each scenario repeats a fixed-seed NewGame + protected first reveal at (0,0); capacity=${p.maxCols}x${p.maxRows}.")
    RawTester.test(new MinesweeperCore(p)) { dut =>
      dut.clock.setTimeout(0)
      dut.io.cmd.valid.poke(false.B)
      dut.io.result.ready.poke(false.B)
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      for (g <- presets; count <- counts(g)) {
        println(s"START preset=${g.id} grid=${g.cols}x${g.rows} mines=$count seed=${f"$seed%04X"}")
        val samples = (1 to repeats).map(repeat => runSample(dut, p, g, count, repeat))
        val deterministic = samples.map(_.fingerprint).distinct.size == 1
        require(deterministic,
          s"public snapshot differed across repeated runs for preset ${g.id}, mines $count")
        val rows = samples.map(_.csv(deterministic)).mkString("\n") + "\n"
        Files.write(csvPath, rows.getBytes(US_ASCII), java.nio.file.StandardOpenOption.APPEND)
        println(s"PASS preset=${g.id} grid=${g.cols}x${g.rows} mines=$count repeats=$repeats " +
          s"snapshotBytes=${samples.head.snapshotBytes} simWallMs=${samples.map(_.simulatorWallMs).sum}")
      }
    }
    println(s"Completed R1 S4 simulation benchmark. Results: $csvPath")
  }
}
