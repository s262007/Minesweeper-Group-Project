# R2-S4: Solver v2 (pair rule), row by row

4 November 2026. Branch `r2/s4-solver-v2-rows`. Files: `src/main/scala/minesweeper/Solver.scala`, `SolverTest.scala` and `PairRuleFormalTest.scala` in `src/test/scala/minesweeper`.

v2 is built into the Solver but **off by default**: `SolverConfig(subsetRule = true)` turns it on. With the default config the Solver is exactly the v1 on main (same Yosys numbers below), so the FPGA build does not change until the team decides to enable v2.

An earlier v2 (`r2/s4-solver-v2`) put the pair rule on top of the fully parallel v1, with lane multiplexers over all clue positions. It would not have fit the FPGA at 30x24 (`docs/R2_SOLVER_AREA.md`), so this version is rebuilt on the row-by-row v1.

## What v2 adds

v1 looks at one clue at a time. v2 also compares two clues that share hidden cells (CONTRACT 5.6):
- A and B: the unknown neighbours of the two clues; a and b: their residual mine counts.
- I = A ∩ B, X = A − B, Y = B − A.
- L = max(0, a − |X|, b − |Y|) and U = min(|I|, a, b) bound the mines in I.
- L > U: the clues contradict each other.
- L = U = q: I holds q mines, X holds a − q and Y holds b − q. Each part is tested for "zero mines" (all safe) and "all mines".

This covers the subset case (A ⊆ B gives |X| = 0) and partial overlaps, such as the contract's example.

## How it runs

v1 passes run until one adds nothing. Then a **pair pass** goes over the active rows. For row r:
1. **Read, 3 cycles:** rows r − 1 to r + 3 go into registers, through the same row multiplexers as v1 (the row register points at r, r + 1, then r + 2).
2. **Per active column c, 5 cycles:**
   - select the 5x7 region around (r, c);
   - compute the 13 constraints: the centre clue and its 12 partners in half of its 5x5 window, so each pair of clues at most two cells apart is compared once;
   - compute the bounds;
   - compute the decisions;
   - add the region's deductions to a 5-row pending buffer.
3. **Write, 2 cycles:** the pending rows go into the knowledge through the v1 write path (rows r − 1 to r + 1, then r + 1 to r + 3).

All columns of a row use the same snapshot, read in step 1, so the knowledge only changes at the write. A pair pass that adds something goes back to v1 passes; one that adds nothing is the fixpoint. The rules only add true facts on a consistent board, so the order of rows and columns does not change the fixpoint. The tests check it against an independent Scala closure that applies every pair at once.

The pair rule itself is in `object PairRule`, as functions that both the Solver and the formal harness call. `cfg.lanes` is not used by this version: there is one centre clue per column step.

## Size and timing (Yosys, Solver alone, 30x24)

| Config | LUTs | Flip-flops | Logic levels |
|---|---|---|---|
| v1 (default, as on main) | 11,506 | 1,914 | 8 |
| v2 (`subsetRule = true`) | 15,368 | 3,696 | 10 at most, on 11 flip-flops; 8 or less elsewhere |

R1's Vivado run gave the v1 Solver 8,684 LUTs, 0.755 times the Yosys count. At the same ratio v2 would be about 11,600 LUTs, about 2,900 more than v1, so about 52,900 for the whole design (83% of the XC7A100T). This is an estimate until R1 runs Vivado with v2 on.

The paths with 9 or 10 levels end in the "clue unsatisfiable" flags (`consBadR`, `ruleBad`); they contain two CARRY4 stages, which are fast. To get there, three changes were needed (v2 only):
- the active rows and columns are registered at start, which removes the comparisons from the read path;
- the end of a v1 pass is decided one cycle later, from registers only;
- the unsatisfiable-clue flags are registered on their own and collected one cycle later.

## Cycles

A v1 pass takes 3 cycles per active row, plus one for the end-of-pass decision. A pair pass takes 5 cycles per active row and per active column, plus 5 per row. Measured on 4 played 4x4 games, 2 of them needing pairs: 126 to 239 cycles per solve, under 2.5 µs at 100 MHz. At 30x16 a pair pass is 16 × 155 = 2,480 cycles, about 25 µs. That is still far below one UART byte (87 µs at 115,200 baud).

## Tests (`SolverTest`, "Solver v2")

| Test | What it checks | Local time |
|---|---|---|
| 1-1 against a wall | the subset case: v2 adds (0,2) to the two safe cells v1 finds | 2 min |
| 1-2-1 against a wall | v1 finds nothing; v2 finds both mines and the safe cell | 44 s |
| Partial overlap, the contract's example | v1 finds nothing; v2 finds (0,1) safe and (2,1) a mine. The partner is two rows below the centre, so the read of rows r + 2 and r + 3 is exercised | 2 min |
| Partial overlap proving mines | three mines and one safe cell from L = U = 1 | 50 s |
| Every consistent layout, 4x4 | 8 played games, 4 of them needing pairs: no hint contradicts any layout that fits the clues, and some game goes beyond v1 | 11 min |
| Reference closure, 4x4 | 4 played games, 2 needing pairs, equal the Scala closure | 5 min |
| Padding | 5x4 games in 8x6 storage, needing pairs: the region reaches past the active rows and columns | 8 min |
| Contradiction | inconsistent clues give contradiction, valid low, no hints | 5 s |
| Reference closure, 7x5 (slow tag, not in CI) | 4 played games, 2 needing pairs: 448, 480, 216 and 216 cycles | 7 to 15 min |
| Reference closure, 9x9 (slow tag, not in CI) | 2 played games: 1,012 and 478 cycles | 25 min |

The reference (`ReferenceV2` in `SolverTest.scala`) applies rule 1 to every clue and the pair rule to every pair of clues at most two cells apart, all on the same knowledge, until nothing changes. It shares no code with the hardware.

Co-simulation with R3's model (`SolverCoSimTest`, added 9 November 2026): the golden model plays games, and after every move the Solver must give the same hints as `ModelSolver`. `ModelSolver.solveV2` decides a pair by trying every placement of mines on the two clues' unresolved cells, not with L and U, so it checks the bounds as well as the hardware. All four tests pass:

| Test | Boards | Local time |
|---|---|---|
| v1 against `solveV1` | 4x4 and 7x5 played games | 15 s |
| v2 against `solveV2` | 4x4 played games, 3 of the 6 boards needing pairs | 3 min 48 s |
| v2, padding | a 4x4 preset in 6x5 storage, 2 of the 3 boards needing pairs | 3 min 15 s |
| v2, 9x9 (slow tag, not in CI) | 9x9 played games, 3 of the 4 boards needing pairs | 32 min |

Whole fast suite on this branch: 128/128 in 39 min (`sbt "testOnly -- -l minesweeper.Formal"`). The v2 tests add about 30 min of the default simulator, since the v2 circuit is larger and a solve takes more cycles.

The slow tests are run by hand: `sbt "set Test / testOptions := Nil" "testOnly minesweeper.SolverTest -- -n minesweeper.Slow"`. All three pass (v1 at 30x24 storage, v2 closure on 7x5 and 9x9). A v2 solve on 9x9 can take more than 1,000 cycles, which is chiseltest's default limit without a poke, so the test helper turns that limit off (`setTimeout(0)`); its own cycle bound still applies.

## Formal proofs (`PairRuleFormalTest`)

For each of the 12 partner offsets, z3 checks the pair rule on a 5x7 region with any mine layout, any active and revealed cells (revealed cells are safe), and any sound knowledge (deduced mines are mines, deduced safe cells are not). The clues show their true counts. The harness calls the same `PairRule` functions as the Solver. Proven: no consistent clue is judged unsatisfiable, no consistent pair has L > U, and every deduction (in I, X and Y) is true. The 12 proofs take 5 s to 1 min 38 s each, 6 min 14 s in all.

Mutation checks, not committed:
- |X| and |Y| swapped in L: found at step 0, "a consistent pair has L > U".
- X tested for "all mines" against |Y| instead of |X|: found at step 0, "mine deduction on a safe cell".

The control around the rule (reading, column selection, pending buffer, writes, passes) is checked by the simulation tests, not formally. The v1 induction proof (`SolverFormalTest`) covers the default config only.

## Not covered

- Completeness beyond what the reference closure checks: reasoning with three or more clues at once, and the total mine count.
- Vivado area and timing with v2 on (R1).
- `cfg.lanes`: accepted but unused by this version.
