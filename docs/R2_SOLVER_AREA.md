# Solver area and timing: one row per step, three cycles per step

Branch `r2/solver-row-sweep`, experimental, to be agreed with R1 before a PR. First version 30 October 2026 (one row per cycle), timing version 1 November 2026 (three cycles per row).

## Problem

R1's Vivado run on the Nexys4 DDR build (XC7A100T, 63,400 LUTs) gave the Solver 26,641 LUTs, 42% of the device and the largest module. v1 evaluated every clue in parallel, so the rule logic was copied once per cell (720 copies at 30x24).

The whole design also misses 100 MHz: R1's best result is a 22 ns path. The parallel solver had 20 logic levels on its longest path (Yosys, below), so it would miss 10 ns too.

## Change

One row of clues is evaluated per step, in three cycles:
1. **Read:** a window of three rows (r - 1 to r + 1) goes into registers: unknown cells, deduced mines, and the clues of row r. Row multiplexers select it.
2. **Count:** each clue of row r counts its deduced-mine neighbours M and unknown neighbours U, and the rule results (all safe, all mines, unsatisfiable) go into registers.
3. **Write:** the deductions, in rows r - 1 to r + 1, are added to the knowledge.

Passes over the active rows repeat until a pass adds nothing.
- SolverIO, the hint rules and the done/valid/contradiction behaviour are unchanged, so the Core needs no change.
- On a consistent board the rules only add true facts, so the row order does not change the fixpoint. The tests check this on every board against the parallel reference.
- Only `cycles` changes: 3 × passes × active rows. Bound used by the tests: 3 × (cells + 1) × maxRows + 2.
- On a contradiction the knowledge is no longer cleared: the hints are zero because valid is low, as before. This keeps the contradiction signal off the 1,440 knowledge flip-flops.

## Measurements

Yosys 0.70 (`synth_xilinx -family xc7 -flatten`), Solver alone at 30x24 capacity. Logic levels: the most LUT, MUXF7/8 and CARRY4 cells between two flip-flops or ports, counted on the netlist by a script (Yosys's own `ltp` reports false paths through multi-bit wires).

| Version | LUTs | Flip-flops | CARRY4 | Logic levels |
|---|---|---|---|---|
| Parallel (main) | 34,024 | 1,486 | 1,444 | 20 |
| One row per cycle (30 Oct) | 11,740 | 1,492 | 72 | 20 |
| Three cycles per row (this branch) | 11,506 | 1,914 | 100 | 8 |

Yosys counts more LUTs than Vivado: R1's Vivado gave 26,641 for the parallel version, 0.78 times the Yosys count. At the same ratio, this branch would be about 9,000 LUTs in Vivado, about 17,600 LUTs saved (28% of the device). In R1's report the MineGen path had 40 levels for 29.7 ns, about 0.75 ns per level. At that rate 8 levels is about 6 ns, which should leave margin at 10 ns. Both numbers are estimates until R1 runs Vivado.

Cycles per solve, from a Python model of the schedules on 200 random played games per preset (1 to 4 clicks, first click safe):

| Preset | Parallel: mean, max | Three cycles per row: mean, max |
|---|---|---|
| 9x9, 14 mines | 3.9, 8 | 94, 216 |
| 16x16, 40 mines | 4.9, 11 | 211, 528 |
| 30x16, 99 mines | 4.4, 11 | 188, 432 |
| 30x24, 180 mines | 3.8, 10 | 245, 576 |

576 cycles is under 6 µs at 100 MHz; sending one UART byte at 115,200 baud takes about 87 µs.

## Checks

- Fast suite: 119/119 (`sbt "testOnly -- -l minesweeper.Formal"`). The full run passed 114. The other 5 had cycle limits that assumed the parallel solver: `R2DemoConsole` (R2) and `SimulationDemoTest` (R1, limit raised from 64 to 64 plus the solver bound). They passed after the fix, rerun with `SolverTest` and `CoreTest` (24/24).
- Formal v1 proofs, by induction: 3x3 in 22 s, 4x4 in 1 min 38 s, 4x3 storage in 53 s. The harness now also keeps two invariants. The window registers must be a sound snapshot of the board and knowledge. The rule registers must be true for the unknown window cells.
- Mutations found by z3 at step 0 on 3x3, then reverted:
  - the window reads deduced safe cells as mines: "the window read is not a sound snapshot";
  - the all-mines rule tests clue == M: "a rule result is wrong".
- The harness is `SolverFormalTest.scala`; the CI `formal-tools` job runs it with z3 (`docs/R2_S3_FORMAL.md`).

## Consequence for v2

The v2 branch (`r2/s4-solver-v2`) keeps the parallel v1 and adds, per lane, multiplexers over all clue positions and 12 pair-rule units. It was not synthesized, but by this structure it would be larger than the parallel v1 at 30x24. It must not be wired into the Core; v2 will be redone on this row-by-row structure.
