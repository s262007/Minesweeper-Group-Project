package minesweeper

import org.scalatest.Tag

/** Formal proofs: they need the z3 executable on the PATH. CI runs them in the formal job.
  * Skip them locally with: sbt "testOnly -- -l minesweeper.Formal"
  */
object FormalTag extends Tag("minesweeper.Formal")
