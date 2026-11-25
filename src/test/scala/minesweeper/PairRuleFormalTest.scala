package minesweeper

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.formal._
import org.scalatest.flatspec.AnyFlatSpec

class PairRuleCheck(partner: Int) extends Module {
  private val (rows, cols) = (PairRule.regionRows, PairRule.regionCols)
  private def cells = Vec(rows, Vec(cols, Bool()))
  val mine = IO(Input(cells))
  val active = IO(Input(cells))
  val revealed = IO(Input(cells))
  val knownSafe = IO(Input(cells))
  val knownMine = IO(Input(cells))

  val m = Wire(cells)
  val unk = Wire(cells)
  for (r <- 0 until rows; c <- 0 until cols) {
    m(r)(c) := mine(r)(c) && active(r)(c)
    unk(r)(c) := active(r)(c) && !revealed(r)(c) && !knownSafe(r)(c) && !knownMine(r)(c)
    assume(!revealed(r)(c) || (active(r)(c) && !mine(r)(c)))
    assume(!knownMine(r)(c) || (active(r)(c) && !revealed(r)(c) && mine(r)(c)))
    assume(!knownSafe(r)(c) || (active(r)(c) && !revealed(r)(c) && !mine(r)(c)))
  }

  def constraintAt(cr: Int, cj: Int): (ClueConstraint, Bool) = {
    val near = PairRule.window.map { case (wr, wc) => (cr + wr, cj + wc) }
    val count = PopCount(near.zipWithIndex.collect { case ((r, c), k) if k != 4 => m(r)(c) })
    PairRule.constraint(revealed(cr)(cj) && active(cr)(cj), count,
      near.map { case (r, c) => unk(r)(c) }, near.map { case (r, c) => knownMine(r)(c) })
  }

  val (dr, dc) = PairRule.partners(partner)
  val (bCell, aCell) = (PairRule.clueCells(0), PairRule.clueCells(partner + 1))
  val (b, bBad) = constraintAt(bCell._1, bCell._2)
  val (a, aBad) = constraintAt(aCell._1, aCell._2)
  val d = PairRule.decide(b, a, PairRule.bounds(b, a, dr, dc))
  val (bSafe, bMine, aSafe, aMine) = PairRule.deductions(b, a, dr, dc, d)

  assert(!bBad && !aBad, "a consistent clue judged unsatisfiable")
  assert(!d.bad, "a consistent pair has L > U")
  for (((wr, wc), k) <- PairRule.window.zipWithIndex) {
    val (br, bc) = (bCell._1 + wr, bCell._2 + wc)
    val (ar, ac) = (aCell._1 + wr, aCell._2 + wc)
    assert(!(bSafe(k) && m(br)(bc)), s"centre window bit $k: safe deduction on a mine")
    assert(!(bMine(k) && !m(br)(bc)), s"centre window bit $k: mine deduction on a safe cell")
    assert(!(aSafe(k) && m(ar)(ac)), s"partner window bit $k: safe deduction on a mine")
    assert(!(aMine(k) && !m(ar)(ac)), s"partner window bit $k: mine deduction on a safe cell")
  }
}

class PairRuleFormalTest extends AnyFlatSpec with ChiselScalatestTester with Formal {
  for (((dr, dc), n) <- PairRule.partners.zipWithIndex) {
    s"The pair rule with the partner at ($dr, $dc)" should "only make true deductions" taggedAs FormalTag in {
      verify(new PairRuleCheck(n), Seq(BoundedCheck(1), ResetOption(0)))
    }
  }
}
