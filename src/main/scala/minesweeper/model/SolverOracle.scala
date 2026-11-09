package minesweeper.model

import scala.collection.mutable

/**
  * Exact reference for the solver hints (R3-S3), written from CONTRACT 4 and 5.6 only. A cell
  * is certain when it has the same value, safe or mine, in every mine layout that fits the
  * revealed numbers. ModelSolver and the hardware Solver apply local rules, so each hint they
  * give must be certain here, but they can miss certain cells.
  *
  * Unknown cells (active and not revealed; flags are player notes, not facts) next to at least
  * one clue form the frontier. Clues that share an unknown cell join their cells into one
  * component, and each component is searched on its own by backtracking. Unknown cells next to
  * no clue are the interior: the clues say nothing about them, so they are only decided when
  * the total number of mines is given.
  *
  * The search counts the cell values it tries, over all components. Above `budget` (2^20 by
  * default) it stops and returns GaveUp, never a partial answer.
  */
object SolverOracle {
  type Cell = (Int, Int)

  val defaultBudget: Long = 1L << 20

  sealed trait Outcome

  /** The certain cells, with the frontier and interior sizes and the number of values tried. */
  final case class Exact(safe: Set[Cell], mine: Set[Cell], frontier: Int, interior: Int, nodes: Long) extends Outcome

  /** No layout fits the clues (and the total, when it is given). */
  final case class Inconsistent(nodes: Long) extends Outcome

  /** The search went over the budget; nothing is claimed. */
  final case class GaveUp(budget: Long, largestComponent: Int) extends Outcome

  /**
    * The layouts of one component, grouped by their number of mines k: for each k, the cells
    * mined in every layout with k mines and the cells mined in at least one.
    */
  private final case class Layouts(cells: Vector[Cell], byCount: Map[Int, (Set[Cell], Set[Cell])])

  private final class OverBudget extends scala.util.control.ControlThrowable

  /**
    * @param grid       active grid
    * @param clues      revealed cells and their numbers
    * @param totalMines the game's mine count, if the answer may use it
    */
  def certain(grid: RefGrid, clues: Map[Cell, Int], totalMines: Option[Int] = None,
              budget: Long = defaultBudget): Outcome = {
    require(clues.keys.forall { case (r, c) => grid.contains(r, c) }, "a clue is outside the grid")
    // Each clue as its unknown neighbours and the number of mines among them.
    val constraints = clues.toVector.sortBy(_._1).map { case ((r, c), number) =>
      (grid.neighbours(r, c).filterNot(clues.contains), number)
    }
    if (constraints.exists { case (cells, k) => k < 0 || k > cells.size }) return Inconsistent(0)

    val frontier = constraints.flatMap(_._1).distinct.sorted
    val frontierSet = frontier.toSet
    val interior = grid.cells.filterNot(c => clues.contains(c) || frontierSet(c))
    val groups = componentsOf(frontier, constraints)

    val counter = new Counter(budget)
    val components = Vector.newBuilder[Layouts]
    try {
      for (cells <- groups) {
        val inside = cells.toSet
        val own = constraints.filter { case (cs, _) => cs.nonEmpty && inside(cs.head) }
        val layouts = search(cells, own, counter)
        if (layouts.byCount.isEmpty) return Inconsistent(counter.nodes)
        components += layouts
      }
    } catch {
      case _: OverBudget => return GaveUp(budget, groups.map(_.size).maxOption.getOrElse(0))
    }
    val all = components.result()

    totalMines match {
      case None =>
        val (safe, mine) = decide(all, (_, _) => true)
        Exact(safe, mine, frontier.size, interior.size, counter.nodes)
      case Some(total) =>
        // A frontier count m is possible when the interior can hold the rest: 0 <= total - m <= |interior|.
        def fits(m: Int): Boolean = total - m >= 0 && total - m <= interior.size
        val possible = sums(all.map(_.byCount.keySet)).filter(fits)
        if (possible.isEmpty) return Inconsistent(counter.nodes)
        val others = all.indices.map(i => sums(all.patch(i, Nil, 1).map(_.byCount.keySet)))
        val (safe, mine) = decide(all, (i, k) => others(i).exists(s => fits(k + s)))
        val interiorSafe = interior.nonEmpty && possible.forall(_ == total)
        val interiorMine = interior.nonEmpty && possible.forall(m => total - m == interior.size)
        Exact(safe ++ (if (interiorSafe) interior else Nil), mine ++ (if (interiorMine) interior else Nil),
          frontier.size, interior.size, counter.nodes)
    }
  }

  private final class Counter(budget: Long) {
    var nodes = 0L
    def tick(): Unit = { nodes += 1; if (nodes > budget) throw new OverBudget }
  }

  /**
    * Backtracking over the component's cells in order. A value is tried only if every clue of the
    * cell can still be met: no more mines than its number, and enough open cells for the rest.
    * At the last cell every clue is met exactly.
    */
  private def search(cells: Vector[Cell], constraints: Vector[(Vector[Cell], Int)], counter: Counter): Layouts = {
    val n = cells.size
    val local = cells.zipWithIndex.toMap
    val cluesOfCell = Array.fill(n)(mutable.ArrayBuffer.empty[Int])
    for (((cs, _), j) <- constraints.zipWithIndex; c <- cs) cluesOfCell(local(c)) += j
    val need = constraints.map(_._2).toArray // mines still to place, per clue
    val open = constraints.map(_._1.size).toArray // cells with no value yet, per clue
    val layout = new Array[Boolean](n)
    val inAll = mutable.Map.empty[Int, Array[Boolean]]
    val inAny = mutable.Map.empty[Int, Array[Boolean]]

    def allowed(i: Int, mine: Boolean): Boolean = cluesOfCell(i).forall { j =>
      val left = need(j) - (if (mine) 1 else 0)
      left >= 0 && left <= open(j) - 1
    }
    def assign(i: Int, mine: Boolean, sign: Int): Unit = cluesOfCell(i).foreach { j =>
      open(j) -= sign
      if (mine) need(j) -= sign
    }
    def go(i: Int, mines: Int): Unit =
      if (i == n) {
        val all = inAll.getOrElseUpdate(mines, Array.fill(n)(true))
        val any = inAny.getOrElseUpdate(mines, new Array[Boolean](n))
        for (c <- 0 until n) {
          all(c) = all(c) && layout(c)
          any(c) = any(c) || layout(c)
        }
      } else {
        for (mine <- Seq(false, true)) {
          counter.tick()
          if (allowed(i, mine)) {
            layout(i) = mine
            assign(i, mine, 1)
            go(i + 1, if (mine) mines + 1 else mines)
            assign(i, mine, -1)
          }
        }
      }

    go(0, 0)
    def chosen(flags: Array[Boolean]): Set[Cell] = cells.indices.filter(flags(_)).map(cells).toSet
    Layouts(cells, inAll.keys.map(k => k -> ((chosen(inAll(k)), chosen(inAny(k))))).toMap)
  }

  /** Cells safe (or mined) in every layout whose mine count is allowed for its component. */
  private def decide(components: Vector[Layouts], allowed: (Int, Int) => Boolean): (Set[Cell], Set[Cell]) = {
    var safe = Set.empty[Cell]
    var mine = Set.empty[Cell]
    for ((comp, i) <- components.zipWithIndex) {
      // toSeq first: collect on a Map that yields pairs would build a Map and merge equal keys.
      val kept = comp.byCount.toSeq.collect { case (k, sets) if allowed(i, k) => sets }
      mine ++= kept.map(_._1).reduce(_ intersect _)
      val sometimesMined = kept.map(_._2).reduce(_ union _)
      safe ++= comp.cells.filterNot(sometimesMined)
    }
    (safe, mine)
  }

  /** Every sum that takes one value from each set. */
  private def sums(sets: Seq[Set[Int]]): Set[Int] =
    sets.foldLeft(Set(0))((acc, ks) => for (a <- acc; k <- ks) yield a + k)

  /**
    * Frontier cells grouped so that the cells of one clue are in the same group. Within a group,
    * cells are ordered by a breadth-first walk over shared clues, so a clue's cells come close
    * together and its count is checked early.
    */
  private def componentsOf(frontier: Vector[Cell], constraints: Vector[(Vector[Cell], Int)]): Vector[Vector[Cell]] = {
    val linked = mutable.Map.empty[Cell, mutable.LinkedHashSet[Cell]]
    for ((cells, _) <- constraints; a <- cells; b <- cells if a != b)
      linked.getOrElseUpdate(a, mutable.LinkedHashSet.empty) += b
    val seen = mutable.Set.empty[Cell]
    val out = Vector.newBuilder[Vector[Cell]]
    for (start <- frontier if !seen(start)) {
      val order = Vector.newBuilder[Cell]
      val queue = mutable.Queue(start)
      seen += start
      while (queue.nonEmpty) {
        val c = queue.dequeue()
        order += c
        for (n <- linked.getOrElse(c, Nil).toVector.sorted if !seen(n)) {
          seen += n
          queue.enqueue(n)
        }
      }
      out += order.result()
    }
    out.result()
  }
}
