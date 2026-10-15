package minesweeper

import chisel3.util.log2Ceil

/** Grid sizes are columns x rows; coordinates are (row, col). */
final case class GridPreset(id: Int, cols: Int, rows: Int) {
  require(id >= 0, "preset id must be nonnegative")
  require(cols >= 2 && rows >= 2, "a grid needs at least two columns and rows")
  val cells: Int = cols * rows
}

/** Player-selected settings, latched atomically on a successful NewGame. */
final case class GameSettings(presetId: Int, mines: Int, seed: Int = 1)

sealed trait ConfigError
object ConfigError {
  case object BadPreset extends ConfigError
  case object BadMines extends ConfigError
  case object BadSeed extends ConfigError
}

/**
  * Storage capacity and supported presets. Active dimensions are runtime settings.
  * verification allows small test boards; keep it disabled in FpgaTop.
  */
final case class MsParams(
    maxCols: Int = 30,
    maxRows: Int = 24,
    presets: Vector[GridPreset] = MsParams.standardPresets,
    minMines: Int = 14,
    defaultPresetId: Int = 1,
    defaultMines: Int = 14,
    verification: Boolean = false
) {
  require(maxCols >= 2 && maxCols <= 64 && maxRows >= 2 && maxRows <= 64,
    "capacity dimensions must each be in 2..64")
  require(presets.nonEmpty, "at least one preset is required")
  require(presets.map(_.id) == presets.indices.toVector,
    "preset ids must be unique, contiguous and start at zero")
  require(presets.map(g => (g.cols, g.rows)).distinct.size == presets.size,
    "preset dimensions must be unique")
  require(presets.forall(g => g.cols <= maxCols && g.rows <= maxRows),
    "every advertised preset must fit the generated capacity")
  require(if (verification) minMines >= 0 else minMines == 14,
    "production games require a minimum of 14 mines")

  val cells: Int = maxCols * maxRows
  private def bitsForInclusive(n: Int): Int = math.max(1, log2Ceil(n + 1))
  val rowW: Int = bitsForInclusive(maxRows - 1)
  val colW: Int = bitsForInclusive(maxCols - 1)
  val rowsW: Int = bitsForInclusive(maxRows)
  val colsW: Int = bitsForInclusive(maxCols)
  val idxW: Int = bitsForInclusive(cells - 1)
  val cntW: Int = bitsForInclusive(cells)
  val presetW: Int = bitsForInclusive(presets.size - 1)
  val minesLeftW: Int = cntW + 1
  val seedW: Int = 16
  val cycleW: Int = 32
  val revisionW: Int = 32
  val sequenceW: Int = 16
  val defaultSettings: GameSettings = GameSettings(defaultPresetId, defaultMines, 1)

  def preset(id: Int): Option[GridPreset] = presets.find(_.id == id)
  def maxMines(g: GridPreset): Int =
    g.cells - math.min(3, g.cols) * math.min(3, g.rows)

  def validate(s: GameSettings): Either[ConfigError, GridPreset] = preset(s.presetId) match {
    case None => Left(ConfigError.BadPreset)
    case Some(g) if s.mines < minMines || s.mines > maxMines(g) => Left(ConfigError.BadMines)
    case Some(_) if s.seed < 1 || s.seed > 0xffff => Left(ConfigError.BadSeed)
    case Some(g) => Right(g)
  }

  require(presets.forall(g => minMines <= maxMines(g)),
    "minimum mines must leave space for first-click protection in every preset")
  require(validate(defaultSettings).isRight, "default game settings must be legal")

  /** A storage index always uses maxCols, even for an 8x8 or 9x9 game. */
  def index(row: Int, col: Int): Int = {
    require(row >= 0 && row < maxRows && col >= 0 && col < maxCols,
      "coordinate outside generated capacity")
    row * maxCols + col
  }

  def isActive(idx: Int, g: GridPreset): Boolean =
    idx >= 0 && idx < cells && idx / maxCols < g.rows && idx % maxCols < g.cols

  def activeIndices(g: GridPreset): Vector[Int] = {
    require(g.rows <= maxRows && g.cols <= maxCols, "grid outside generated capacity")
    (for (row <- 0 until g.rows; col <- 0 until g.cols) yield index(row, col)).toVector
  }

  def activeMask(g: GridPreset): BigInt =
    activeIndices(g).foldLeft(BigInt(0))((mask, idx) => mask.setBit(idx))

  /** Static local wiring candidates; filter against the active grid in hardware. */
  def capacityNeighbors(idx: Int): Vector[Int] = {
    require(idx >= 0 && idx < cells, "index outside generated capacity")
    val row = idx / maxCols
    val col = idx % maxCols
    (for {
      dr <- -1 to 1
      dc <- -1 to 1
      if dr != 0 || dc != 0
      rr = row + dr
      cc = col + dc
      if rr >= 0 && rr < maxRows && cc >= 0 && cc < maxCols
    } yield index(rr, cc)).toVector
  }

  def neighborIdx(idx: Int, g: GridPreset): Vector[Int] = {
    require(isActive(idx, g), "neighbours require an active cell")
    capacityNeighbors(idx).filter(isActive(_, g))
  }

  def safeZone(idx: Int, g: GridPreset): Set[Int] = neighborIdx(idx, g).toSet + idx

  /** Convert benchmark densities; UART NEW takes an integer mine count. */
  def fromDensity(presetId: Int, density: Double, seed: Int = 1): GameSettings = {
    require(!density.isNaN && !density.isInfinity && density >= 0 && density <= 1,
      "density must be finite and in 0..1")
    val g = preset(presetId).getOrElse(throw new IllegalArgumentException("unsupported preset"))
    val s = GameSettings(presetId, math.floor(density * g.cells).toInt, seed)
    require(validate(s).isRight, "density produces an illegal mine count or seed")
    s
  }
}

object MsParams {
  val contractVersion: String = "1.2.0"
  val standardPresets: Vector[GridPreset] = Vector(
    GridPreset(0, 8, 8),
    GridPreset(1, 9, 9),
    GridPreset(2, 16, 16),
    GridPreset(3, 30, 16),
    GridPreset(4, 30, 24)
  )

  def forVerification(cols: Int, rows: Int): MsParams = MsParams(
    maxCols = cols, maxRows = rows,
    presets = Vector(GridPreset(0, cols, rows)), minMines = 0,
    defaultPresetId = 0, defaultMines = 0, verification = true
  )
}

final case class SolverConfig(subsetRule: Boolean = false, lanes: Int = 1) {
  require(lanes >= 1, "solver needs at least one lane")
}

/** Supply clockHz once the FPGA board is chosen. */
final case class UartConfig(
    clockHz: Long,
    baud: Int = 115200,
    rxFifoDepth: Int = 128
) {
  require(clockHz > 0 && baud > 0, "clock and baud must be positive")
  require(rxFifoDepth >= 64, "RX FIFO must hold at least one maximum request line")
  val oversample: Int = 16
  private val divisor = math.round(clockHz.toDouble / (baud.toDouble * oversample))
  require(divisor >= 1 && divisor <= Int.MaxValue, "clock cannot support the requested sampling")
  val sampleDivisor: Int = divisor.toInt
  val actualBaud: Double = clockHz.toDouble / (sampleDivisor.toDouble * oversample)
  val relativeBaudError: Double = math.abs(actualBaud - baud) / baud
  require(relativeBaudError < 0.02, "UART sampling baud error must be below 2 percent")
}

/**
  * Reference sequence for MineGen tests.
  * Right-shifting Galois LFSR, XOR mask 0xB400, nonzero-state period 65535.
  */
object Lfsr16 {
  val period: Int = 65535
  def next(state: Int): Int = {
    require(state >= 1 && state <= 0xffff, "LFSR state must be a nonzero 16-bit value")
    (state >>> 1) ^ (if ((state & 1) != 0) 0xb400 else 0)
  }

  def placeMines(p: MsParams, s: GameSettings, firstRow: Int, firstCol: Int): Set[Int] = {
    val g = p.validate(s).fold(e => throw new IllegalArgumentException(e.toString), identity)
    require(firstRow >= 0 && firstRow < g.rows && firstCol >= 0 && firstCol < g.cols,
      "first click must be inside the active grid")
    val excluded = p.safeZone(p.index(firstRow, firstCol), g)
    val mines = scala.collection.mutable.Set.empty[Int]
    var state = s.seed
    var attempts = 0
    while (mines.size < s.mines && attempts < period) {
      val compact = (state - 1) % g.cells
      val idx = p.index(compact / g.cols, compact % g.cols)
      if (!excluded(idx)) mines += idx
      state = next(state)
      attempts += 1
    }
    require(mines.size == s.mines, "legal placement must finish within one LFSR period")
    mines.toSet
  }
}
