# R2-S4: Solver v2 (pair rule)

29 October 2026. Files: `src/main/scala/minesweeper/Solver.scala`, `SolverTest.scala` and `SolverFormalTest.scala` in `src/test/scala/minesweeper`.

## What v2 adds

v1 looks at one clue at a time. v2 also compares two clues that share hidden cells (CONTRACT 5.6):
- A and B: the unknown neighbours of the two clues; a and b: their residual mine counts.
- I = A ∩ B, X = A − B, Y = B − A.
- L = max(0, a − |X|, b − |Y|) and U = min(|I|, a, b) bound the mines in I.
- L > U: the clues contradict each other.
- L = U = q: I holds q mines, X holds a − q and Y holds b − q. Each part is tested for "zero mines" (all safe) and "all mines".

This covers the subset case (A ⊆ B gives |X| = 0) and partial overlaps, such as the contract's example.

## How it runs

- `SolverConfig(subsetRule = true, lanes = n)` selects v2 with n lanes, 1 ≤ n ≤ cells. The default is still v1, and v1 elaborates exactly as before.
- v1 runs to its fixpoint, then a pair sweep starts. Lane l handles the clues l, l + n, l + 2n, ... one per cycle, so a sweep takes ceil(cells / n) cycles.
- Each clue is paired with the 12 clues in half of its 5x5 window, so each pair is seen once. Clues farther apart share no cell, so nothing is lost.
- The deductions of a sweep step are added at once. A sweep that found something goes back to v1. A sweep that found nothing is the fixpoint: done pulses with valid set.
- The rules only add knowledge, so every lane count reaches the same fixpoint. Only the cycle count changes.

Cycle bound, used as the timeout in the tests: (cells + 1) × (ceil(cells / n) + 2). Each round is v1 until no progress, one fixpoint cycle, and one sweep. Every round except the last adds at least one cell, so there are at most cells + 1 rounds. The v1 cycles with progress add at most cells in total.

The Core still builds v1 (`new Solver(p)` in `MinesweeperCore.scala`, an R1 file). Switching to v2 means passing a `SolverConfig` there; this is to be agreed with R1.

## Cycles against lanes

Measured by the lanes test (`SolverTest`, it prints the totals): 8 played 5x4 boards (20 cells), 4 of them needing the pair rule. The hints were the same for every lane count.

| Lanes | Cycles per sweep | Total cycles, 8 boards |
|---|---|---|
| 1 | 20 | 263 |
| 4 | 5 | 83 |
| 20 (all) | 1 | 35 |

Each lane has its own 12 pair-rule units, so more lanes cost more hardware. The area is not measured yet.

## Tests (`SolverTest`, "Solver v2")

| Test | What it checks | Local time |
|---|---|---|
| 1-1 against a wall | the subset case: v2 adds (0,2) to the two safe cells v1 finds | 6 s |
| 1-2-1 against a wall | v1 finds nothing; v2 finds both mines and the safe cell | 2 s |
| Partial overlap, the contract's example | v1 finds nothing; v2 finds (0,1) safe and (2,1) a mine | 9 s |
| Partial overlap proving mines | three mines and one safe cell from L = U = 1 | 4 s |
| 1, 4 and all lanes | the same hints on 8 boards of 5x4 (4 of them need the pair rule) | 6 min 42 s |
| Every consistent layout, 4x4 | 20 played games: no hint contradicts any layout that fits the clues, and at least one game goes beyond v1 | 1 min 17 s |
| Reference closure | 6 games on 4x4 and 3 on 7x5 equal an independent Scala v2 | 2 min 26 s |
| Contradiction | inconsistent clues give contradiction, valid low, no hints | 1 s |
| Reference closure on 9x9 (slow tag) | 2 games in the suite. 6 games with the same seed passed locally in 2 h 7 min; the first 2 are the same boards | about 40 min |

The Scala reference (`ReferenceV2` in `SolverTest.scala`) applies rule 1 and the pair rule to every pair of clues until nothing changes. It shares no code with the hardware.

## Formal proofs

Same inductive step as v1 (docs/R2_S3_FORMAL.md): from any state where the deductions are correct, including any sweep step, the next step keeps them correct, consistent clues are never judged inconsistent, and the hints agree with the mine layout.

| Size | Lanes | Local runtime |
|---|---|---|
| 3x3 | 1 | 12 min |
| 3x3 | 9 (one sweep step) | 22 min |
| 3x3 storage with 3x3 and 2x2 games (padding) | 1 | 8 min 20 s |

4x4 with one lane had not finished after 40 minutes, so v2 is proven on 9 cells only.

Mutation check, not committed: with |X| and |Y| swapped in L, z3 found a counterexample on 3x3 with one lane in 11 s ("consistent clues judged inconsistent", step 0). The replay of the counterexample in treadle also printed "Constraint #assume_12 was violated". The counterexample itself comes from z3; the replay only produces the waveform.

## Not covered

- Completeness: the hardware may miss a deduction the reference finds. Only the tests compare the two (they matched on every board tried).
- Reasoning with three or more clues at once, and the total mine count.
- Formal proofs beyond 9 cells.
- Area and timing on the FPGA: no board is chosen yet.
