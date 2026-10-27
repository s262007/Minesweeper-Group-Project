package minesweeper

import chisel3._
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec

/** A counter that stops at 5. */
class SaturatingCounter extends Module {
  val io = IO(new Bundle {
    val inc = Input(Bool())
    val value = Output(UInt(3.W))
  })
  val count = RegInit(0.U(3.W))
  when(io.inc && count =/= 5.U) { count := count + 1.U }
  io.value := count
}

/** Claims the counter never exceeds `limit`; the prover drives `inc` freely. */
class CounterBound(limit: Int) extends Module {
  val inc = IO(Input(Bool()))
  val dut = Module(new SaturatingCounter)
  dut.io.inc := inc
  when(!reset.asBool) { assert(dut.io.value <= limit.U, "counter above its limit") }
}

/** R2-S0: z3 runs, proves a true property and finds a counterexample for a false one.
  * A tool check alone (z3 -version) does not show that a failing property is reported.
  */
class FormalSmokeTest extends AnyFlatSpec with ChiselScalatestTester with Formal {
  "The formal tool flow" should "prove a true bounded property" taggedAs FormalTag in {
    verify(new CounterBound(5), Seq(BoundedCheck(10)))
  }

  it should "report a counterexample for a false property" taggedAs FormalTag in {
    assertThrows[FailedBoundedCheckException] {
      verify(new CounterBound(4), Seq(BoundedCheck(10)))
    }
  }
}
