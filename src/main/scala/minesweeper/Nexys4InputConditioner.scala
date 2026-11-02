package minesweeper

import chisel3._
import chisel3.util.log2Ceil

class Nexys4DebouncedInput(debounceCycles: Int) extends Module {
  require(debounceCycles >= 1, "debounceCycles must be positive")

  val io = IO(new Bundle {
    val raw = Input(Bool())
    val level = Output(Bool())
    val pressed = Output(Bool())
  })

  private val counterWidth = math.max(1, log2Ceil(debounceCycles))
  private val syncFirst = RegNext(io.raw, false.B)
  private val syncSecond = RegNext(syncFirst, false.B)
  private val stableLevel = RegInit(false.B)
  private val stableCycles = RegInit(0.U(counterWidth.W))
  private val previousLevel = RegNext(stableLevel, false.B)

  when(syncSecond === stableLevel) {
    stableCycles := 0.U
  }.elsewhen(stableCycles === (debounceCycles - 1).U) {
    stableLevel := syncSecond
    stableCycles := 0.U
  }.otherwise {
    stableCycles := stableCycles + 1.U
  }

  io.level := stableLevel
  io.pressed := stableLevel && !previousLevel
}

class Nexys4InputConditioner(debounceCycles: Int = 2000000) extends Module {
  require(debounceCycles >= 1, "debounceCycles must be positive")

  val io = IO(new Bundle {
    val buttonUp = Input(Bool())
    val buttonDown = Input(Bool())
    val buttonLeft = Input(Bool())
    val buttonRight = Input(Bool())
    val buttonCenter = Input(Bool())
    val hintSwitch = Input(Bool())
    val flagSwitch = Input(Bool())
    val revealSwitch = Input(Bool())
    val autoplaySwitch = Input(Bool())
    val moveUp = Output(Bool())
    val moveDown = Output(Bool())
    val moveLeft = Output(Bool())
    val moveRight = Output(Bool())
    val restartEvent = Output(Bool())
    val hintsEnabled = Output(Bool())
    val flagEvent = Output(Bool())
    val revealEvent = Output(Bool())
    val autoplayEvent = Output(Bool())
  })

  private val up = Module(new Nexys4DebouncedInput(debounceCycles))
  private val down = Module(new Nexys4DebouncedInput(debounceCycles))
  private val left = Module(new Nexys4DebouncedInput(debounceCycles))
  private val right = Module(new Nexys4DebouncedInput(debounceCycles))
  private val center = Module(new Nexys4DebouncedInput(debounceCycles))
  private val hints = Module(new Nexys4DebouncedInput(debounceCycles))
  private val flag = Module(new Nexys4DebouncedInput(debounceCycles))
  private val reveal = Module(new Nexys4DebouncedInput(debounceCycles))
  private val autoplay = Module(new Nexys4DebouncedInput(debounceCycles))

  up.io.raw := io.buttonUp
  down.io.raw := io.buttonDown
  left.io.raw := io.buttonLeft
  right.io.raw := io.buttonRight
  center.io.raw := io.buttonCenter
  hints.io.raw := io.hintSwitch
  flag.io.raw := io.flagSwitch
  reveal.io.raw := io.revealSwitch
  autoplay.io.raw := io.autoplaySwitch

  io.moveUp := up.io.pressed
  io.moveDown := down.io.pressed
  io.moveLeft := left.io.pressed
  io.moveRight := right.io.pressed
  io.restartEvent := center.io.pressed
  io.hintsEnabled := hints.io.level
  io.flagEvent := flag.io.pressed
  io.revealEvent := reveal.io.pressed
  io.autoplayEvent := autoplay.io.pressed
}
