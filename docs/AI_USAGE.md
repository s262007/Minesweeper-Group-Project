# AI usage

## R1-S0: shared contract and interfaces (15 October 2026)

Codex helped draft the contract, parameter definitions, shared Chisel interfaces and contract tests from the team's project plan and agreed settings. It also edited the wording and corrected the starter CI branch filters from master to main.

The local contract suite passed all 12 tests using Scala 2.13.14, Chisel 3.6.1 and ChiselTest 0.6.2. R2/R3 review is pending. The team will review the rules, defaults, LFSR sequence and interface timing before freezing the contract.

## R1-S0: independent setup work (16 October 2026)

Codex drafted R1 module shells, elaboration/smoke tests, the S0 backlog and board-status record, PR template, build-output ignore rules, Z3 tool-check setup, license and upstream notice. The user authorized license selection. MIT applies to original contributions; starter reuse terms are unresolved. IntelliJ guidance follows the existing temurin-25 setup. Team approvals and R2/R3 implementation remain pending. See CONTRACT_VALIDATION.md for checks actually run.

## R1-S0 review fixes, proposed by R2 (19 October 2026)

Claude Code (Anthropic) reviewed the R1-S0 pull request against plan v1.1 and the course labs, then made the "should fix" changes on a separate branch: migration to Chisel 6.7 / ChiselTest 6.0 (build file and the two test files), removal of the starter adder and Quartus example, a Makefile clean target that only removes build output, CI on JDK 17 for every pull request, and the matching README/contract/S0/validation/notice updates. The first migrated run failed one test (`emitSystemVerilog` no longer accepts `--target-dir`); the fix was to check the returned RTL text. The full suite was then run on JDK 17 and JDK 25 (15/15 each). No contract rule or interface was changed.

## R2-S0: RevealEngine and Solver shells (19 October 2026)

Claude Code wrote the RevealEngine and Solver shells against CONTRACT sections 5.5 and 5.6 (refuse every start, reveal nothing, certify no hints; the Solver rejects more lanes than cells) and R2ShellTest: RTL emission at 4x4, 7x5, 9x9, 16x8 and 30x24 for Solver v1 and v2/lanes 4, the lanes limit, and simulated refusal behaviour. sbt test: 19/19 on JDK 17. The formal smoke harness of R2-S0 is not part of this change.

## R2-S1: RevealEngine frontier flood fill (19 October 2026)

Claude Code wrote RevealEngineTest first, from CONTRACT 5.5, with its own board and BFS written from row/column loops (it does not use MsParams.capacityNeighbors). It covers: a numbered click, a zero cascade with its numbered boundary, a flag wall, empty boards clicked in a corner (layers = max(rows, cols)), cells revealed by an earlier move, a 5x4 preset inside 8x6 storage (padding never touched), starts on ineligible cells, busy/clear handshakes, 20 random layouts on each of the four verification sizes, and 8x8 and 30x16 games at the production 30x24 capacity. Against the shell, all 10 tests failed on start.ready; the implementation then passed all 10. Two deliberately injected bugs (numbered cells expanding; flags ignored) were caught by 4 and 3 tests respectively, then reverted. Full suite: 28/28 on JDK 17. The defensive handling of starts on ineligible cells (complete with zero layers) is our choice and is documented in the source; the contract leaves it to the Core.

## R2-S2: Solver v1, single-clue propagation (19 October 2026)

Claude Code wrote SolverTest first, from CONTRACT 5.6, with a reference v1 fixpoint written in the test from the rule text (coordinate-based neighbours). It covers:
- a corner 1, a two-step chain to the fixpoint (cycles = 3), no-information boards, and the 1-1 wall pattern (v1 must not use the subset rule);
- padding: a 5x4 game in 8x6 storage, where counting padding as unknown would block a deduction;
- inconsistent clues (contradiction, no hints), and busy/clear/restart handshakes;
- 30 random 4x4 games checked against every mine layout consistent with the visible numbers (exhaustive enumeration), and 20 played games per verification size compared exactly with the reference (hints and evaluation count);
- 9x9 and 30x16 games at the 30x24 capacity.

All 10 tests failed against the shell, then passed. Three injected bugs were caught by 7, 1 and 5 tests and then reverted: an unsound safe rule, padding counted as unknown, and stopping after one evaluation.

The 30x24 simulations take minutes with the default simulator (solver 12 min 44 s, reveal 1 min 37 s), so they are tagged `minesweeper.Slow` and can be skipped locally with `-l minesweeper.Slow`. The fast suite runs 35/35 in about 3.5 minutes; the full `sbt test` ran 37/37 in 11 minutes (JDK 17).

The v1 solver is fully parallel. `subsetRule`/`lanes` are accepted but unused until R2-S4. Its area at 720 cells is not measured yet (no synthesis tool chosen).

## R2 plan-B demo console (20 October 2026)

Claude Code wrote R2DemoConsole, a terminal game where RevealEngine and Solver v1 run in simulation. The Scala side places mines with `Lfsr16.placeMines` and handles flags, win and loss. It also wrote R2DemoConsoleTest on 6x6 boards:
- first click safe, cascades equal to the independent BFS, hints equal to the reference v1 and sound;
- flags, win and loss;
- autoplay never steps on a mine (it won 7 of 12 games without guessing).

Findings along the way:
- sbt only forwards keyboard input to a forked JVM without `-batch`, so the live command adds `set Test / run / fork := true` and `set Test / run / connectInput := true`. The console prints this hint if it receives no input.
- Profiling showed that treadle's cost per simulated cycle grows about with the square of the cell count (solver 10 ms at 4x4, 41 ms at 6x6, 233 ms at 9x9), not the pokes and peeks. A one-Bool-per-cell rewrite of the solver gave no real gain and was reverted. The live demo therefore uses 6x6 (1-3 s per move); a recorded 9x9 game is in docs/R2_DEMO_9x9_transcript.txt.

Fast suite: 39/39 in about 4 minutes (JDK 17). No hardware source changed.


## R3-S0 merged into main (22 October 2026)

R3's `feature/R3-S1` was written for Chisel 3.6. Claude Code merged main into it (no conflicts: R3's files are all new) and ported `UartShellTest` to Chisel 6.7: `circt.stage.ChiselStage.emitSystemVerilog`, which returns the RTL text and takes no `--target-dir`, with the same checks as before. No R3 source or other test changed. Fast suite after the merge: 71/71 (JDK 17).

## R3-S1 done by R2: golden model, co-simulation, PlayConsole (24 October 2026)

R3 was unavailable before the MVP, so R2 did this R3 story with Claude Code. Claude Code wrote the golden model (`model/GoldenModel.scala`) and a v1 model solver (`model/ModelSolver.scala`) from CONTRACT 3, 4, 5.2 and 5.6, using R3's `RefGrid` and no `MsParams` helpers. It also wrote `ChiselImpl` (drives `MinesweeperCore`), `CoSimTest` (model and hardware compared after every command) and `PlayConsole` (terminal game on the simulated Core, checked live by the model).

- Model tests: a hand-checked 6x5 board (cascade sets, layer counts, flags, loss, win), placement against `Lfsr16` on all five production presets, NewGame validation order, random-game invariants, solver soundness and closure. 19/19.
- Co-simulation: 5 fast tests (invalid inputs, flags, win and loss, preset switch, random sequences on three capacities) passed against the Core on the first run. Two injected bugs were caught and reverted: a model cascade expanding from numbered cells, and a Core skipping `revealAllMines` on a loss (minesLeft off by one).
- Fast suite: 134/134 in 23 min (JDK 17). The two long co-simulation runs (longer random sequences, 30x24 capacity) passed in 3 h 8 min; they only run with `COSIM_LONG=1`, so CI stays short.

The same assistant wrote R2's RevealEngine and Solver, so R3 should still review the model against the contract. Limits and the contract readings to confirm are in docs/R3_S1.md.

## R2-S3: formal proofs (27 October 2026)

Claude Code wrote the formal harnesses and the CI job; R2 checked each result.
- `FormalSmokeTest` (the R2-S0 smoke harness): z3 proves a true property and reports a counterexample for a false one.
- `SolverFormalTest`: an inductive step for Solver v1 on 3x3, 4x4 and a padded 4x3 build. It starts from any state where the deductions are correct and shows the next step keeps them correct and the hints agree with the mine layout. A bounded unrolling of whole runs was tried first and did not finish on 3x3 (80 min).
- `RevealFormalTest`: Board + RevealEngine, bounded to cells + 4 cycles: no mine or flag revealed, reveals never undone, every reveal ends.
- Two injected bugs (solver mine count, cascade expanding from numbered cells) were each found by z3 and reverted.

The CI `build` job now skips the `minesweeper.Formal` tag; the `formal-tools` job installs z3 and runs the proofs. Sizes, runtimes, assumptions and limits are in docs/R2_S3_FORMAL.md.

## R2: solver area and timing (30 October - 4 November 2026)

R1's Vivado run gave the parallel Solver 26,641 LUTs (42% of the XC7A100T). Claude Code rewrote it to evaluate one row of clues per step in three cycles (read window, count, write), with the same SolverIO and hints. It measured the LUTs and logic levels with Yosys and wrote the cycle-count reference and the test updates; R2 checked each result.
- Yosys (xc7, 30x24): 34,024 to 11,506 LUTs, 20 to 8 logic levels. R1's routed Vivado run of `feature/lut-reduction` (this solver plus R3's Board change): Solver 8,684 LUTs, whole design 50,004 LUTs (79%).
- Tests compare every board with the parallel reference fixpoint and with the cycle count of the row schedule. Fast suite 119/119; merged through PR #13 with a green CI.
- `SolverFormalTest` now also keeps the window and rule registers as invariants. Three injected bugs (row offset, window read, rule test) were each found by z3 and reverted.
- `RevealFormalTest` follows R1's adjacency pass: clicks wait for `adjacencyReady`, and the bound grows by maxRows.

Details: docs/R2_SOLVER_AREA.md and docs/R2_S3_FORMAL.md.

## R2-S4: Solver v2 (pair rule), row by row (4 November 2026)

Claude Code rebuilt the pair rule of CONTRACT 5.6 on the row-by-row v1, off by default (`SolverConfig(subsetRule = true)` turns it on). It also wrote the v2 tests, the pair-rule formal harness, and the Yosys measurements; R2 checked each result.
- After each v1 fixpoint, a pair pass compares every pair of clues at most two cells apart, one centre column per five cycles, and writes through the v1 write path.
- Yosys (xc7, 30x24): 15,368 LUTs with v2 on, against 11,506 for v1; at most 10 logic levels, on 11 flip-flops. The default build is unchanged.
- `SolverTest`: hand-made boards v1 cannot finish, soundness against every consistent 4x4 layout, closure equal to an independent Scala reference, padding and contradiction. Fast suite 128/128.
- `PairRuleFormalTest`: the pair rule proven sound by z3 for all 12 partner offsets. Two injected bugs were found and reverted.

The earlier v2 branch (`r2/s4-solver-v2`, on the parallel v1) is superseded. Details: docs/R2_S4_SOLVER_V2.md.

## R2-S5: README sections (9 November 2026)

Claude Code wrote the README sections "Reveal engine" and "Solver and formal proofs" (rules, schedule diagram, size and speed, tests, what is proven and what is not), from the code and the R2 documents on main, for R2 to review in the PR. The numbers come from R1's routed Vivado build of 4 November 2026, the Yosys runs in docs/R2_SOLVER_AREA.md and docs/R2_S4_SOLVER_V2.md, and the test and proof logs. No source or test changed.

## R3-S4 step 1 done by R2: ModelSolver v2 and Solver co-simulation (9 November 2026)

R2 did this R3 step with Claude Code, as for R3-S1. Claude Code wrote rule 2 in the model (`ModelSolver.pairDecision` and `solveV2`), its tests and `SolverCoSimTest`; R2 checked the results.
- Rule 2 is decided by enumeration: every placement of mines on the union of the two clues' unresolved cells is tried, and a cell is decided when all placements that satisfy both clues agree. This is not the L and U formula used by the hardware; a test checks that both give the same decisions for every pair of parts up to 3 cells.
- `ModelSolverTest`: 12/12, 6 of them new (the contract example, the agreement with L and U, 1-2-1 against a wall, a partial overlap on a board, a contradiction between two clues, and soundness, closure and monotonicity on 200 random boards). One bug injected in the enumeration was caught by 4 tests and reverted.
- `SolverCoSimTest`: the golden model plays games, and after every move the hardware Solver must give the same hints as `ModelSolver`: v1 on 4x4 and 7x5, v2 on 4x4 (half the boards need rule 2), v2 on a 4x4 preset in 6x5 storage, and v2 on 9x9 (slow tag). 4/4, the 9x9 test in 32 min.

The same assistant wrote the hardware Solver; the enumeration avoids sharing its formula, but R3 should still review the model against the contract.

## R3-S3 done by R2: exact solver oracle and property tests (9 November 2026)

R2 did this R3 story with Claude Code, as for R3-S1. Claude Code wrote `SolverOracle`, `SolverOracleTest` and `PropertyTest`; R2 checked the results.
- `SolverOracle` gives the cells that are the same in every mine layout that fits the revealed numbers, by backtracking per frontier component, with the total mine count as an option. It returns `GaveUp` above 2^20 tried values.
- `SolverOracleTest`: 8/8. Hand cases, agreement with a separate check that tries every layout on 400 random boards (some inconsistent), soundness on 635 boards of played games, every ModelSolver v1 and v2 hint contained in the oracle, and the budget. The enumeration test found one bug in the first version (two mine counts merged in a Scala `Map`); it is fixed and kept as a regression test.
- `PropertyTest`: ScalaCheck generators for capacities, games and moves. Twelve properties are checked after every command: on 500 model games, and on 12 hardware games run in lockstep with the model. Three bugs injected in the model were each found and reverted.
- The VGA hint overlay of the plan is not done: the project has no VGA renderer.

The same assistant wrote the hardware Solver and the model. The oracle and the checks are written from the contract and share no code with them, but R3 should still review them. Details: docs/R3_S3.md.
