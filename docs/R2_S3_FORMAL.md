# R2-S3: formal proofs

27 October 2026. Tools: ChiselTest 6.0 formal (`verify`, `BoundedCheck`), z3 5.1.0 locally (Windows), z3 from the Ubuntu packages in CI. All proofs carry the `minesweeper.Formal` tag.

## What is proven

| Property | Module | Sizes | Method | Local runtime |
|---|---|---|---|---|
| A safe hint is never a mine, a mine hint is always a mine, never both, never on a revealed or inactive cell. Consistent clues are never reported as a contradiction. | Solver v1 | 3x3, 4x4, and 4x3 storage with 4x3 and 3x2 games | Inductive step: holds after any number of evaluations and runs | 19 s, 4 min 11 s, 1 min 5 s |
| The same properties for v2 (rule 1 and the pair rule), at every sweep step. | Solver v2 | 3x3 with 1 and 9 lanes, 3x3 storage with 3x3 and 2x2 games with 1 lane | Inductive step, as for v1 (see R2_S4_SOLVER_V2.md) | 12 min, 22 min, 8 min 20 s |
| A cascade never reveals a mine or a flagged cell. A revealed cell never becomes hidden. Every reveal ends within cells + 1 cycles. | Board + RevealEngine | same three | Bounded: cells + 4 cycles from reset (load, click, whole cascade), with any clicks and flags in that window | 32 s, 20 min 43 s, 4 min 55 s |
| Tool flow: a true property is proven and a false one is reported | `FormalSmokeTest` | - | Bounded, 10 cycles | 3 s, 1 s |

The 4x3 configuration plays a 3x2 game inside 4x3 storage too, so padding on both axes is covered.

## What the prover may choose

- Solver: any preset of the build, any mine layout, any set of revealed cells that are safe and active and show their true count (computed in the harness with its own row and column loops, not the `MsParams` neighbour helpers), any solver state that satisfies the invariant below, and start at any time. Clear and reset stay low.
- Reveal: any preset, any layout loaded once after reset, flag toggles whenever no reveal runs, and clicks only on hidden, unflagged, active, safe cells, as the Core does (it handles mine clicks itself). Clear stays low.

## Why induction for the solver

Invariant: every deduced mine is a mine, every deduced safe cell is not, and all deductions lie in the active grid. Start and reset empty the knowledge, so the invariant holds when a run begins (base case, read from `Solver.scala`). The proof starts the solver in any state allowed by the invariant (`ResetOption(0)`) and shows, in one cycle, that the clues are never judged inconsistent, that this step's deductions keep the invariant, and that the visible hints agree with the layout. By induction the hints are sound after any number of steps and runs. The board does not even need to be held for this step.

A bounded unrolling of whole runs was tried first. z3 had not finished the 3x3 case after 80 minutes (with the board allowed to change between runs), nor after 15 minutes with a single held board: the counting arguments over many steps are hard for it.

## The proofs can fail (mutation checks, not committed)

- Solver: M counted from the known safe cells instead of the known mines. Counterexample at step 0, "consistent clues judged inconsistent". This mutation only matters once some knowledge exists, so the induction really starts from arbitrary states.
- RevealEngine: expansion from every frontier cell instead of zero cells only. Counterexample after 4 steps, "cell 3: the cascade revealed a mine".

Each failing run writes its counterexample waveform to `test_run_dir/<test name>/<harness>.vcd`.

## Not proven

- Boards larger than 4x4, and the 30x24 production capacity.
- RevealEngine: only within cells + 4 cycles from reset. That the cascade equals the BFS set is checked by the unit tests and the co-simulation, not formally.
- Solver: completeness (that it finds every rule-1 deduction), the cycle bound and the `cycles` counter. The base case and the `clear` input are argued from the RTL and covered by unit tests.
- Solver v2 beyond 9 cells: 4x4 with one lane had not finished after 40 minutes.
- The Core's orchestration, MineGen and the UART path.

## How to run

```text
sbt "testOnly -- -n minesweeper.Formal"
```

z3 must be on the PATH. Without it, skip the proofs with `-l minesweeper.Formal`. In CI, the `build` job skips them and the `formal-tools` job installs z3 and runs them.
