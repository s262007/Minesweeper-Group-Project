# R2-S3: formal proofs

First version 27 October 2026. Updated on 4 November 2026 for the code now on main:
- the row-by-row Solver (three cycles per row step, `docs/R2_SOLVER_AREA.md`);
- R1's Board, which counts the neighbours in a pass after the mines are loaded;
- R3's Board change, which reads the stored cell state without masking it again.

Tools: ChiselTest 6.0 formal (`verify`, `BoundedCheck`), z3 5.1.0 locally (Windows), z3 from the Ubuntu packages in CI. All proofs carry the `minesweeper.Formal` tag.

## What is proven

| Property | Module | Sizes | Method | Local runtime |
|---|---|---|---|---|
| A safe hint is never a mine, a mine hint is always a mine, never both, never on a revealed or inactive cell. Consistent clues are never reported as a contradiction. | Solver v1 | 3x3, 4x4, and 4x3 storage with 4x3 and 3x2 games | Inductive step: holds after any number of steps and runs | 15 s, 1 min 27 s, 38 s |
| A cascade never reveals a mine or a flagged cell. A revealed cell never becomes hidden. Every reveal ends within cells + 1 cycles. | Board + RevealEngine | same three | Bounded: cells + maxRows + 4 cycles from reset (load, the Board's adjacency pass, click, whole cascade), with any clicks and flags in that window | 1 min 49 s, 37 min 47 s, 3 min 45 s |
| The v2 pair rule: no consistent clue judged unsatisfiable, no consistent pair with L > U, every deduction in I, X and Y true. | `PairRule` (Solver v2) | 5x7 pair region, each of the 12 partner offsets | Combinational, one cycle, any layout and any sound knowledge (see R2_S4_SOLVER_V2.md) | 5 s to 1 min 38 s each, 6 min 14 s in all |
| Tool flow: a true property is proven and a false one is reported | `FormalSmokeTest` | - | Bounded, 10 cycles | 4 s, 1 s |

The 4x3 configuration plays a 3x2 game inside 4x3 storage too, so padding on both axes is covered.

## What the prover may choose

- Solver: any preset of the build, any mine layout, any set of revealed cells that are safe and active and show their true count (computed in the harness with its own row and column loops, not the `MsParams` neighbour helpers), any solver state that satisfies the invariant below, and start at any time. Clear and reset stay low.
- Reveal: any preset, any layout loaded once after reset, flag toggles whenever no reveal runs, and clicks only on hidden, unflagged, active, safe cells, as the Core does (it handles mine clicks itself). Clicks wait for `adjacencyReady`, as in the Core. The Board's streamed-adjacency inputs stay idle, as the Core drives them in the default build. Clear stays low.

## Why induction for the solver

The solver evaluates one row of clues per step, in three cycles: read a three-row window into registers, count each clue's mines and unknown neighbours into registers, then write the deductions. The invariant has three parts:
- **Knowledge:** every deduced mine is a mine, every deduced safe cell is not, and all deductions lie in the active grid.
- **Window registers**, in the count and write phases: a sound snapshot. Unknown cells are active and hidden, deduced mines are mines, the other active hidden cells are safe, and each clue is a revealed cell's true count.
- **Rule registers**, in the write phase: true for the unknown window cells around each clue, and no clue judged unsatisfiable.

Start and reset empty the knowledge and enter the read phase, so the invariant holds when a run begins (base case, read from `Solver.scala`). The proof starts the solver in any state allowed by the invariant (`ResetOption(0)`) and shows, in one cycle:
- consistent clues are never judged inconsistent;
- the deductions written keep the knowledge invariant;
- a window read in the read phase is a sound snapshot;
- the rule results computed in the count phase are true;
- the visible hints agree with the layout.

The registers take exactly these values on the next edge and hold them until their next phase (from the RTL), so by induction the hints are sound after any number of steps and runs. This needs the board to be the same from a read to its write; the Core holds it during a run (CONTRACT 5.1).

A bounded unrolling of whole runs was tried first, on the earlier parallel solver. z3 had not finished the 3x3 case after 80 minutes (with the board allowed to change between runs), nor after 15 minutes with a single held board: the counting arguments over many steps are hard for it.

## The proofs can fail (mutation checks, not committed)

- Solver, earlier parallel version: M counted from the known safe cells instead of the known mines. Counterexample at step 0, "consistent clues judged inconsistent". This mutation only matters once some knowledge exists, so the induction really starts from arbitrary states.
- Solver, row-by-row version, each found at step 0 on 3x3:
  - deductions written one row off: "a new deduction is wrong";
  - the window reads deduced safe cells as mines: "the window read is not a sound snapshot";
  - the all-mines rule tests clue == M: "a rule result is wrong".
- RevealEngine: expansion from every frontier cell instead of zero cells only. Counterexample after 7 steps on 3x3 (load, adjacency pass, click, cascade), "cell 3: the cascade revealed a mine". This also shows that clicks really happen within the bound after the adjacency pass.

Each failing run writes its counterexample waveform to `test_run_dir/<test name>/<harness>.vcd`.

## Not proven

- Boards larger than 4x4, and the 30x24 production capacity.
- RevealEngine: only within cells + maxRows + 4 cycles from reset, and only with the default (non-streamed) adjacency of the Board. That the cascade equals the BFS set is checked by the unit tests and the co-simulation, not formally.
- Solver: completeness (that it finds every rule-1 deduction), the cycle bound and the `cycles` counter. The base case, the phase sequence and the `clear` input are argued from the RTL and covered by unit tests.
- Solver v2: only the pair rule itself is proven; its control (pair pass, region selection, pending rows) is checked by simulation. The v1 induction covers the default config only.
- The Core's orchestration, MineGen and the UART path.

## How to run

```text
sbt "testOnly -- -n minesweeper.Formal"
```

z3 must be on the PATH. Without it, skip the proofs with `-l minesweeper.Formal`. In CI, the `build` job skips them and the `formal-tools` job installs z3 and runs them.
