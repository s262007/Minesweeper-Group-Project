package minesweeper

import org.scalatest.flatspec.AnyFlatSpec

import java.io.File
import scala.io.Source

class AutoPlayTest extends AnyFlatSpec {
  "AutoPlay" should "play model and hardware games that agree, with consistent measures, and write the CSV" in {
    val rows = AutoPlay.collect(modelCount = 30, hardwareCount = 2, log = info(_))
    assert(rows.map(r => (r.source, r.setting.label, r.setting.mines, r.player)) == Seq(
      ("model", "9x9", 10, "v1"), ("model", "9x9", 10, "v2"), ("model", "9x9", 14, "v1"), ("model", "9x9", 14, "v2"),
      ("hardware", "4x4", 3, "v1"), ("model", "4x4", 3, "v1"), ("model", "4x4", 3, "v2"),
      ("hardware", "7x5", 6, "v1"), ("model", "7x5", 6, "v1"), ("model", "7x5", 6, "v2")))
    for (row <- rows; (g, i) <- row.games.zipWithIndex) {
      val where = s"${row.source} ${row.setting.label} ${row.player} game $i: $g"
      assert(g.foundV1 <= g.foundV2 && g.foundV2 <= g.certain && g.certain <= g.certainWithTotal, where)
      assert(g.commands >= 2 && g.gaveUp == 0, where)
      assert(g.won || g.guesses > 0, where)
    }
    val f = File.createTempFile("solver_stats", ".csv")
    f.deleteOnExit()
    AutoPlay.write(f.getPath, rows)
    val lines = { val src = Source.fromFile(f, "UTF-8"); try src.getLines().toVector finally src.close() }
    assert(lines.head == AutoPlay.header && lines.size == rows.size + 1)
    val columns = AutoPlay.header.split(",").length
    assert(lines.forall(_.split(",", -1).length == columns))
  }
}
