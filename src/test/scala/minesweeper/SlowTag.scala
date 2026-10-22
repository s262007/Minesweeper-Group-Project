package minesweeper

import org.scalatest.Tag

/** Simulations at the 30x24 production capacity. They pass but take minutes with the
  * default simulator. Skip them locally with: sbt "testOnly -- -l minesweeper.Slow"
  * (CI runs everything unless a separate job is set up for them).
  */
object SlowTag extends Tag("minesweeper.Slow")
