package minesweeper

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class UartTransactionTrackerTest extends AnyFlatSpec with ChiselScalatestTester {
  private val p = MsParams()

  private def initialize(dut: UartTransactionTracker): Unit = {
    dut.io.request.valid.poke(false.B)
    pokeRequest(dut, UartRequestKind.Hello, sequence = 0)
    dut.io.coreBusy.poke(false.B)
    dut.io.complete.poke(false.B)
    dut.io.decision.ready.poke(false.B)
    dut.reset.poke(true.B)
    dut.clock.step(2)
    dut.reset.poke(false.B)
  }

  private def send(
      dut: UartTransactionTracker,
      kind: UartRequestKind.Type,
      sequence: Int,
      row: Int = 0,
      col: Int = 0,
      error: UartDecodeError.Type = UartDecodeError.Ok
  ): Unit = {
    pokeRequest(dut, kind, sequence, row, col, error)
    dut.io.request.valid.poke(true.B)
    dut.io.request.ready.expect(true.B)
    dut.clock.step()
    dut.io.request.valid.poke(false.B)
    dut.io.decision.valid.expect(true.B)
  }

  private def pokeRequest(
      dut: UartTransactionTracker,
      kind: UartRequestKind.Type,
      sequence: Int,
      row: Int = 0,
      col: Int = 0,
      error: UartDecodeError.Type = UartDecodeError.Ok
  ): Unit = {
    dut.io.request.bits.kind.poke(kind)
    dut.io.request.bits.sequence.poke(sequence.U)
    dut.io.request.bits.sequenceValid.poke(true.B)
    dut.io.request.bits.error.poke(error)
    dut.io.request.bits.command.kind.poke(CmdKind.Reveal)
    dut.io.request.bits.command.row.poke(row.U)
    dut.io.request.bits.command.col.poke(col.U)
    dut.io.request.bits.command.preset.poke(0.U)
    dut.io.request.bits.command.mines.poke(0.U)
    dut.io.request.bits.command.seed.poke(0.U)
  }

  private def consume(dut: UartTransactionTracker): Unit = {
    dut.io.decision.ready.poke(true.B)
    dut.clock.step()
    dut.io.decision.ready.poke(false.B)
  }

  private def complete(dut: UartTransactionTracker): Unit = {
    dut.io.complete.poke(true.B)
    dut.clock.step()
    dut.io.complete.poke(false.B)
  }

  "UartTransactionTracker" should "execute once, return BUSY in flight and replay after completion" in {
    test(new UartTransactionTracker(p)) { dut =>
      initialize(dut)
      send(dut, UartRequestKind.Reveal, sequence = 0x10, row = 2, col = 3)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.Execute)
      consume(dut)

      send(dut, UartRequestKind.Reveal, sequence = 0x10, row = 2, col = 3)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.Busy)
      consume(dut)
      send(dut, UartRequestKind.Get, sequence = 0x11)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.Busy)
      consume(dut)

      complete(dut)
      send(dut, UartRequestKind.Reveal, sequence = 0x10, row = 2, col = 3)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.Replay)
      consume(dut)
    }
  }

  it should "reject sequence reuse with different contents and replace the one-entry cache" in {
    test(new UartTransactionTracker(p)) { dut =>
      initialize(dut)
      send(dut, UartRequestKind.Flag, sequence = 7, row = 1, col = 1)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.Execute)
      consume(dut)
      complete(dut)

      send(dut, UartRequestKind.Flag, sequence = 7, row = 1, col = 2)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.SequenceError)
      consume(dut)

      send(dut, UartRequestKind.Flag, sequence = 8, row = 1, col = 2)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.Execute)
      consume(dut)
      complete(dut)

      send(dut, UartRequestKind.Flag, sequence = 7, row = 1, col = 1)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.Execute)
      consume(dut)
    }
  }

  it should "return decoder errors without caching them and honor an externally busy Core" in {
    test(new UartTransactionTracker(p)) { dut =>
      initialize(dut)
      send(dut, UartRequestKind.Get, sequence = 1, error = UartDecodeError.BadArgument)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.DecodeError)
      consume(dut)

      dut.io.coreBusy.poke(true.B)
      send(dut, UartRequestKind.Get, sequence = 1)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.Busy)
      consume(dut)
      dut.io.coreBusy.poke(false.B)

      send(dut, UartRequestKind.Get, sequence = 1)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.Execute)
      consume(dut)
      complete(dut)
    }
  }

  it should "hold a decision stable while downstream is backpressured" in {
    test(new UartTransactionTracker(p)) { dut =>
      initialize(dut)
      send(dut, UartRequestKind.Get, sequence = 0xffff)
      val sequence = dut.io.decision.bits.request.sequence.peek().litValue
      dut.clock.step(3)
      dut.io.decision.valid.expect(true.B)
      dut.io.decision.bits.disposition.expect(UartTransactionDisposition.Execute)
      dut.io.decision.bits.request.sequence.expect(sequence.U)
      consume(dut)
      complete(dut)
    }
  }
}
