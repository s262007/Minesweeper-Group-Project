# Solver area: one row per cycle

30 October 2026. Branch `r2/solver-row-sweep`, experimental, to be agreed with R1 before a PR.

## Problem

R1's Vivado run on the Nexys4 DDR build (XC7A100T, 63,400 LUTs) gave the Solver 26,641 LUTs, 42% of the device and the largest module. v1 evaluated every clue in parallel, so the rule logic was copied once per cell (720 copies at 30x24).

## Change

One row of clues is evaluated per cycle, through a window of three rows that row multiplexers select. The deductions (rows r - 1 to r + 1) are added at once and the next cycle takes row r + 1. Passes over the active rows repeat until a pass adds nothing.
- SolverIO, the hint rules and the done/valid/contradiction behaviour are unchanged, so the Core needs no change.
- On a consistent board the rules only add true facts, so the row order does not change the fixpoint. The tests check this on every board against the parallel reference.
- Only `cycles` changes: passes × active rows. Bound used by the tests: (cells + 1) × maxRows + 2.

## Measurements

Yosys 0.70 (`synth_xilinx -family xc7 -flatten`), Solver alone at 30x24 capacity:

| Version | LUTs | Flip-flops | CARRY4 |
|---|---|---|---|
| Parallel (main) | 34,024 | 1,486 | 1,444 |
| One row per cycle | 11,740 | 1,492 | 72 |

Yosys counts more LUTs than Vivado: R1's Vivado gave 26,641 for the parallel version, 0.78 times the Yosys count. At the same ratio, the row version would be about 9,200 LUTs in Vivado. That is about 17,000 LUTs saved, 27% of the device. The Vivado number is still to be measured by R1.

Cycles per solve, from a Python model of both schedules on 200 random played games per preset (1 to 4 clicks, first click safe):

| Preset | Parallel: mean, max | One row per cycle: mean, max |
|---|---|---|
| 9x9, 14 mines | 3.9, 8 | 31.3, 72 |
| 16x16, 40 mines | 4.9, 11 | 70.3, 176 |
| 30x16, 99 mines | 4.4, 11 | 62.8, 144 |
| 30x24, 180 mines | 3.8, 10 | 81.7, 192 |

192 cycles is under 2 µs at 100 MHz; sending one UART byte at 115,200 baud takes about 87 µs.

## Checks

- Fast suite: 109/109 (`sbt "testOnly -- -l minesweeper.Formal"`).
- Formal v1 proofs (harness from `r2/s3-formal`, run locally): 3x3 in 16 s, 4x4 in 2 min 34 s, 4x3 storage in 1 min 12 s. A mutation that writes the deductions one row off was found by z3 at step 0 ("a new deduction is wrong").

## Consequence for v2

The v2 branch (`r2/s4-solver-v2`) keeps the parallel v1 and adds, per lane, multiplexers over all clue positions and 12 pair-rule units. It was not synthesized, but by this structure it would be larger than the parallel v1 at 30x24. It must not be wired into the Core; v2 will be redone on this row-by-row structure.
