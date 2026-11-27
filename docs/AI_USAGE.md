# AI usage

## R1-S0 · Shared contract and interfaces (15 October 2026)

Codex helped draft the contract, the parameter definitions, the shared Chisel interfaces and the contract tests from the team's project plan and agreed settings. It also polished the wording and changed the branch filters of the starter CI from master to main.

The local contract suite passed all 12 tests with Scala 2.13.14, Chisel 3.6.1 and ChiselTest 0.6.2. Review by R2 and R3 was still pending at that point. The team planned to review the rules, the defaults, the LFSR sequence and the interface timing before freezing the contract.

## R1-S0 · Independent setup work (16 October 2026)

Codex drafted the R1 module shells and their elaboration and smoke tests. It also wrote the S0 backlog and the board status record, the pull request template, the ignore rules for build output, the z3 tool check, the licence and the upstream notice. The user chose the licence. MIT applies to original contributions and the reuse terms of the starter remain open. The IntelliJ guidance follows the existing temurin 25 setup. Team approvals and the R2 and R3 implementations were pending at that stage. CONTRACT_VALIDATION.md lists the checks that were actually run.

## R1-S0 · Review fixes proposed by R2 (19 October 2026)

Claude Code (Anthropic) reviewed the R1-S0 pull request against plan v1.1 and the course labs. It then made the changes marked as "should fix" on a separate branch. The build moved to Chisel 6.7 and ChiselTest 6.0 together with the two test files. The starter adder and the Quartus example were removed. A Makefile clean target now removes build output only. CI runs on JDK 17 for every pull request. The README, the contract, the S0 record, the validation record and the notice were updated to match.

The first migrated run failed one test because `emitSystemVerilog` no longer accepts `--target-dir`. The test now checks the returned RTL text instead. The full suite then passed on JDK 17 and on JDK 25 with 15 out of 15 tests each. No contract rule or interface changed.

## R2-S0 · RevealEngine and Solver shells (19 October 2026)

Claude Code wrote the RevealEngine and Solver shells against CONTRACT sections 5.5 and 5.6. They refuse every start, reveal nothing and certify no hints. The Solver also rejects more lanes than cells. R2ShellTest checks RTL emission at 4x4, 7x5, 9x9, 16x8 and 30x24 for Solver v1 and for v2 with 4 lanes. It also checks the lanes limit and the simulated refusal behaviour. `sbt test` passed 19 out of 19 on JDK 17. The formal smoke harness of R2-S0 came later.

## R2-S1 · RevealEngine frontier flood fill (19 October 2026)

Claude Code wrote RevealEngineTest first from CONTRACT 5.5. The test builds its own board and its own breadth first search from row and column loops and does not use `MsParams.capacityNeighbors`. It covers a numbered click and a zero cascade with its numbered border. It also covers a wall of flags and empty boards clicked in a corner, where the number of layers equals the larger of rows and columns. Further cases are cells revealed by an earlier move and a 5x4 preset inside 8x6 storage that never touches the padding. The test also tries starts on ineligible cells and the busy and clear handshakes. Finally it runs 20 random layouts on each of the four verification sizes and 8x8 and 30x16 games at the production capacity of 30x24.

All 10 tests failed against the shell on `start.ready`. The implementation then passed all 10. Two bugs were injected on purpose. In the first one numbered cells expanded and in the second one flags were ignored. They were caught by 4 and 3 tests and then reverted. The full suite passed 28 out of 28 on JDK 17. A start on an ineligible cell completes with zero layers. This defensive choice is ours and the source documents it, since the contract leaves it to the Core.

## R2-S2 · Solver v1 and single clue propagation (19 October 2026)

Claude Code wrote SolverTest first from CONTRACT 5.6. The test contains a reference v1 fixpoint written from the rule text with neighbours computed from coordinates. It checks a 1 in a corner and a chain of two steps to the fixpoint in 3 cycles. It also checks boards without any information and the 1 1 pattern against a wall, where v1 must not use the subset rule. For padding it plays a 5x4 game in 8x6 storage, since counting padding as unknown would block a deduction. It covers inconsistent clues, which must give a contradiction and no hints, and the busy, clear and restart handshakes. It compares 30 random 4x4 games with every mine layout that fits the visible numbers. It also compares 20 played games per verification size exactly with the reference, both hints and number of evaluations. Last come 9x9 and 30x16 games at the 30x24 capacity.

All 10 tests failed against the shell and then passed. Three injected bugs were caught by 7, 1 and 5 tests and then reverted. They were an unsound safe rule, padding counted as unknown and a stop after one evaluation.

With the default simulator the 30x24 simulations take minutes. The solver run took 12 min 44 s and the reveal run 1 min 37 s. They carry the tag `minesweeper.Slow` and can be skipped locally. The fast suite passed 35 out of 35 in about 3.5 minutes and the full `sbt test` passed 37 out of 37 in 11 minutes on JDK 17.

This v1 solver was fully parallel. The options `subsetRule` and `lanes` were accepted but unused until R2-S4. Its area at 720 cells could not be measured yet because no synthesis tool had been chosen.

## R2 · Fallback demo console (20 October 2026)

Claude Code wrote R2DemoConsole, a terminal game in which the RevealEngine and Solver v1 run in simulation. The Scala side places the mines with `Lfsr16.placeMines` and handles flags, wins and losses. R2DemoConsoleTest plays on 6x6 boards. It checks that the first click is safe and that cascades equal the independent search. It also checks that hints equal the reference v1 and are sound, and that flags, wins and losses behave correctly. Its autoplay never stepped on a mine and won 7 of 12 games without guessing.

Two findings came up along the way. First, sbt only forwards keyboard input to a forked JVM without `-batch`. The live command therefore turns on forking and input forwarding, and the console prints this hint when it receives no input. Second, profiling showed that the cost of treadle per simulated cycle grows roughly with the square of the cell count. The solver took 10 ms per cycle at 4x4, 41 ms at 6x6 and 233 ms at 9x9, and the pokes and peeks were not the cause. A rewrite of the solver with one Bool per cell gave no real gain and was reverted. The live demo therefore uses 6x6 with 1 to 3 s per move, and a recorded 9x9 game is in docs/R2_DEMO_9x9_transcript.txt.

The fast suite passed 39 out of 39 in about 4 minutes on JDK 17. No hardware source changed.

## R3-S0 · Merged into main (22 October 2026)

The R3 branch `feature/R3-S1` was written for Chisel 3.6. Claude Code merged main into it without conflicts because all R3 files were new. It then ported `UartShellTest` to Chisel 6.7 with `circt.stage.ChiselStage.emitSystemVerilog`, which returns the RTL text and takes no target directory. The checks stayed the same. No other R3 source or test changed. The fast suite passed 71 out of 71 after the merge on JDK 17.

## R3-S1 done by R2 · Golden model, cosimulation and PlayConsole (24 October 2026)

R3 was unavailable before the MVP, so R2 took over this story with Claude Code. Claude Code wrote the golden model (`model/GoldenModel.scala`) and a v1 model solver (`model/ModelSolver.scala`) from CONTRACT 3, 4, 5.2 and 5.6. They use the R3 `RefGrid` and no helper from `MsParams`. Claude Code also wrote `ChiselImpl`, which drives `MinesweeperCore`, and `CoSimTest`, which compares the model and the hardware after every command. `PlayConsole` is a terminal game on the simulated Core that the model checks live.

The model tests passed 19 out of 19. They cover a 6x5 board checked by hand with its cascade sets, layer counts, flags, loss and win. They also cover placement against `Lfsr16` on all five production presets, the validation order of NewGame, invariants on random games and the soundness and closure of the solver. The five fast cosimulation tests passed against the Core on the first run. They test invalid inputs, flags, a win and a loss, a preset switch and random sequences on three capacities. Two injected bugs were caught and reverted. One was a model cascade that expanded from numbered cells and the other a Core that skipped `revealAllMines` on a loss, which left minesLeft off by one. The fast suite passed 134 out of 134 in 23 minutes on JDK 17. The two long cosimulation runs with longer random sequences and the 30x24 capacity passed in 3 h 8 min. They only run with `COSIM_LONG=1` so that CI stays short.

The same assistant wrote the RevealEngine and the Solver of R2. R3 should therefore still review the model against the contract. The limits and the contract readings to confirm are in docs/R3_S1.md.

## R2-S3 · Formal proofs (27 October 2026)

Claude Code wrote the formal harnesses and the CI job and R2 checked each result. In `FormalSmokeTest` z3 proves a true property and reports a counterexample for a false one. `SolverFormalTest` is an inductive step for Solver v1 on 3x3, 4x4 and a padded 4x3 build. It starts from any state in which the deductions are correct and shows that the next step keeps them correct and that the hints agree with the mine layout. A bounded unrolling of whole runs was tried first and did not finish on 3x3 within 80 minutes. `RevealFormalTest` bounds Board and RevealEngine to cells plus 4 cycles. It shows that no mine or flag is revealed, that a reveal is never undone and that every reveal ends. Two injected bugs were each found by z3 and reverted. They were a wrong mine count in the solver and a cascade expanding from numbered cells.

The CI `build` job now skips the tag `minesweeper.Formal` and the `formal-tools` job installs z3 and runs the proofs. Sizes, runtimes, assumptions and limits are in docs/R2_S3_FORMAL.md.

## R2 · Solver area and timing (30 October to 4 November 2026)

In the Vivado run of R1 the parallel Solver took 26,641 LUTs, which is 42% of the XC7A100T. Claude Code rewrote it to evaluate one row of clues per step in three cycles that read the window, count and write. SolverIO and the hints stayed the same. Claude Code also measured LUTs and logic levels with Yosys and wrote the cycle count reference and the test updates. R2 checked each result.

Yosys for xc7 at 30x24 went from 34,024 to 11,506 LUTs and from 20 to 8 logic levels. The routed Vivado run of R1 on the branch `feature/lut-reduction`, with this solver and a Board change by R3, gave 8,684 LUTs for the Solver and 50,004 LUTs for the whole design, which is 79%. The tests compare every board with the parallel reference fixpoint and with the cycle count of the row schedule. The fast suite passed 119 out of 119 and PR #13 was merged with green CI. `SolverFormalTest` now also keeps the window and rule registers as invariants. Three injected bugs in the row offset, the window read and the rule test were each found by z3 and reverted. `RevealFormalTest` follows the adjacency pass of R1. Clicks wait for `adjacencyReady` and the bound grows by maxRows.

Details are in docs/R2_SOLVER_AREA.md and docs/R2_S3_FORMAL.md.

## R2-S4 · Solver v2 with the pair rule, row by row (4 November 2026)

Claude Code rebuilt the pair rule of CONTRACT 5.6 on the row by row v1. It was off by default and `SolverConfig(subsetRule = true)` turned it on. Claude Code also wrote the v2 tests, the formal harness of the pair rule and the Yosys measurements. R2 checked each result.

After each v1 fixpoint a pair pass compares every pair of clues at most two cells apart. It handles one centre column every five cycles and writes through the v1 write path. Yosys for xc7 at 30x24 gave 15,368 LUTs with v2 on against 11,506 for v1, with at most 10 logic levels on 11 flip flops. The default build did not change. `SolverTest` adds boards made by hand that v1 cannot finish, soundness against every consistent 4x4 layout and closure equal to an independent Scala reference. It also covers padding and contradictions. The fast suite passed 128 out of 128. In `PairRuleFormalTest` z3 proves the pair rule sound for all 12 partner offsets. Two injected bugs were found and reverted.

The earlier v2 branch `r2/s4-solver-v2` built on the parallel v1 is superseded. Details are in docs/R2_S4_SOLVER_V2.md.

## R2-S5 · README sections (9 November 2026)

Claude Code wrote the README sections "Reveal engine" and "Solver and formal proofs" from the code and the R2 documents on main. They describe the rules, the schedule with a diagram, size and speed, the tests and what is proven and what is not. R2 reviewed them in the pull request. The numbers come from the routed Vivado build of R1 on 4 November 2026, from the Yosys runs in docs/R2_SOLVER_AREA.md and docs/R2_S4_SOLVER_V2.md and from the test and proof logs. No source or test changed.

## R3-S4 step 1 done by R2 · ModelSolver v2 and Solver cosimulation (9 November 2026)

R2 took over this R3 step with Claude Code as for R3-S1. Claude Code wrote rule 2 in the model (`ModelSolver.pairDecision` and `solveV2`), its tests and `SolverCoSimTest`. R2 checked the results.

Rule 2 is decided by enumeration. Every placement of mines on the union of the unresolved cells of both clues is tried. A cell is decided when all placements that satisfy both clues agree on it. This is not the L and U formula of the hardware, and a test checks that both give the same decisions for every pair of parts up to 3 cells. `ModelSolverTest` passed 12 out of 12 with 6 new tests. They cover the contract example, the agreement with L and U, the 1 2 1 pattern against a wall, a partial overlap on a board, a contradiction between two clues and soundness, closure and monotonicity on 200 random boards. One bug injected in the enumeration was caught by 4 tests and reverted. In `SolverCoSimTest` the golden model plays games and after every move the hardware Solver must give the same hints as `ModelSolver`. This runs v1 on 4x4 and 7x5 and v2 on 4x4, where half the boards need rule 2. It also runs v2 on a 4x4 preset in 6x5 storage and v2 on 9x9 with the slow tag. All 4 tests passed and the 9x9 test took 32 min.

The same assistant wrote the hardware Solver. The enumeration avoids sharing its formula but R3 should still review the model against the contract.

## R3-S3 done by R2 · Exact solver oracle and property tests (9 November 2026)

R2 took over this R3 story with Claude Code as for R3-S1. Claude Code wrote `SolverOracle`, `SolverOracleTest` and `PropertyTest` and R2 checked the results.

`SolverOracle` returns the cells that are the same in every mine layout that fits the revealed numbers. It backtracks per frontier component and can take the total mine count into account. It returns `GaveUp` above 2^20 tried values. `SolverOracleTest` passed 8 out of 8. It covers cases made by hand and the agreement with a separate check that tries every layout on 400 random boards, some of them inconsistent. It also checks soundness on 635 boards of played games, the inclusion of every ModelSolver v1 and v2 hint in the oracle and the budget. The enumeration test found one bug in the first version, where two mine counts were merged in a Scala `Map`. It is fixed and kept as a regression test. `PropertyTest` adds ScalaCheck generators for capacities, games and moves. Twelve properties are checked after every command on 500 model games and on 12 hardware games run in lockstep with the model. Three bugs injected in the model were each found and reverted. The VGA hint overlay of the plan is not done because the project has no VGA renderer.

The same assistant wrote the hardware Solver and the model. The oracle and the checks come from the contract and share no code with them, but R3 should still review them. Details are in docs/R3_S3.md.

## R3-S4 step 2 done by R2 · AutoPlay and solver statistics (10 November 2026)

R2 took over this R3 step with Claude Code. Claude Code wrote `AutoPlay`, `AutoPlayTest` and the documentation and ran the full measurement. R2 checked the results.

`AutoPlay` plays games with the hints. It reveals every safe hint and otherwise guesses a hidden cell that is not a mine hint, using a seeded random number generator. On each board where it chooses a move it measures the v1 and v2 hints against `SolverOracle`. One command writes `results/solver_stats.csv`. The full run took 32 min. It covers 1000 model games per 9x9 setting and player and 25 hardware games each on 4x4 and 7x5. The hardware games run in lockstep with the model and equal the same games played on the model alone. On Beginner v2 wins without guessing in 79% of games against 58% for v1. v2 finds 98 to 99.7% of the cells that the numbers alone make certain, while v1 finds 53 to 90%. `AutoPlayTest` runs a short version in CI and checks the agreement and the consistency of the measures.

Details are in docs/R3_S4.md.

## R3-S5 done by R2 · Documentation (10 November 2026)

R2 took over this R3 story with Claude Code. Claude Code rewrote the README and wrote `AutoPlayFrames` and `scripts/autoplay_gif.py`. It recorded the animation and checked the README commands on a fresh clone. R2 checked the results.

The README gained an architecture diagram in Mermaid and sections on how to run the project, on verification with the CI test counts, on the solver statistics, on the Nexys4 board and on AI usage. The R2 sections stayed unchanged. Outdated statements were corrected after checking the code and the history. The UART was no longer a shell, a link pointed to a missing document and the CI commands were wrong. The full list is in docs/R3_S5.md. The animation shows a Beginner game played by AutoPlay on the simulated `MinesweeperCore` and checked by the golden model. It takes 30 commands with one guess and ends in a win. R1 was asked to confirm the hardware status and the board summary, and R1 and R3 to add the AI usage of the modules they wrote after S0.

Details are in docs/R3_S5.md.

## R2 · Documentation after PR #22 with Solver v2 by default (15 November 2026)

PR #22 by R1 made v2 the default Solver of the Core (`MsParams.solverV2 = true`). Claude Code updated the documents that still described v2 as off by default. These were the README with its parts table, rules, size table, verification counts, statistics and board section, together with `docs/R2_S4_SOLVER_V2.md` and `docs/R3_S4.md`. R2 checked the changes.

After PR #22 the model settings of AutoPlay built v2. In the rows of the v2 player the measure labelled v1 therefore counted v2 hints. Every AutoPlay setting now builds the v1 Solver (`solverV2 = false`) as when `results/solver_stats.csv` was produced, and a `require` keeps it that way. `AutoPlayTest` passed. The board section recorded that the board build used v2, that no Vivado run of it existed yet and that the option `--board-unmasked-solver-adj` sent hidden adjacency counts to the Solver against CONTRACT 5.3.

## R2 · Induction proof of the complete Solver v2 (16 November 2026)

After PR #22 put v2 on the board, Claude Code wrote `SolverV2FormalTest` and R2 checked the results.

The Solver runs without reset. The harness assumes an invariant over the whole state in one cycle and asserts it in the next, so z3 also checks the control transitions. The invariant covers the knowledge, the v1 window and rules, the control of the pair pass, the pair window, the region, the constraints, the bounds, the decisions and the pending rows. The 3x3 proof passed in 2 h 09 min with the slow tag and was run by hand. The 4x4 proof gave no answer within 4 h 50 min and was stopped, and the padded 4x3 build was not run. Three injected bugs were caught on 3x3. The pending rows were shifted by one column, the safe deduction for X was taken from the mine decision for X, and the second pair write landed one row too high.

The first harness held the board in registers that only kept their own value, and the compiler turned them into constants. The board was empty and the proof was vacuous although it passed. Mutations that should have failed passed as well, which is how probes found the cause. A witness test in CI now checks that the hypothesis allows a nontrivial pair decision.

## R2 for the team · PC display (16 November 2026)

The board display on the PC replaced the VGA display of R3 but had no owner. R2 wrote it with Claude Code. It consists of `scripts/board_display.py`, a tkinter window or terminal client that speaks UART protocol 2 through pyserial, and its tests. It also includes `ModelDevice` and `SimBoardServer`, a protocol board backed by the golden model on a TCP port, and `ModelDeviceTest`. R2 checked the results.

The Python tests passed 4 out of 4. The client writes exactly the request lines of the fixtures, accepts their snapshots and refuses broken ones. `ModelDeviceTest` passed 2 out of 2 and the model board answers all 8 fixture sessions. A game was also played by hand in the terminal and in the window against the model board. A bug turned up in the host code. `Replay.run` never sent the LF that ends a request, so it only worked with the scripted mock. `Fixture.wire` fixes it and the existing protocol tests still pass. The display had not been tried on the FPGA at that point. Details are in docs/R3_PC_DISPLAY.md.

## R2 · Documentation after PRs #27 and #28 (18 November 2026)

Claude Code reviewed PRs #27 and #28 after their merge and wrote this documentation update. R2 checked it. Section 6.4 of the contract describes the hex snapshots `SNAPX` and `ENDX` from `UartReplySerializer` and its tests. The README now states that switch 0 gates the hints sent over the UART and gives the board status of 16 November and what was still missing. A temporary copy of `CoSimTest` with all the new timing options on matched the golden model with 6 out of 6 tests in 22 min. It was not committed.

## R2 · Contract 1.4.0 after PR #30 (21 November 2026)

Claude Code updated sections 6.1, 6.2 and 6.4 of the contract to match PR #30, which brought version 1.4.0 and five board fields on SNAP and SNAPX. The text follows `UartReplySerializer`, its test and `S0Stubs`. R2 checked it. Reading the code also showed that `host/Protocol.scala` still accepted only snapshot lines of 14 tokens, so the Scala host could not parse the 19 tokens sent by the board. The contract recorded this and the next entry fixed it.

## R2 for R3 · The Scala host reads the snapshot fields of the board (21 November 2026)

PR #30 made the hardware append five board fields to every SNAP and SNAPX line, which then has 19 tokens. `host/Protocol.scala` only matched 14, so `Reply.parse`, `Replay` and the Scala host could no longer read a real board. CI did not notice because its devices send 14 tokens. Claude Code changed the parser to accept 14 or 19 tokens with an optional `Reply.BoardFields` and added tests. R2 checked them.

In `ProtocolTest` the hardware line of `UartReplySerializerTest` and a decimal line decode with their board fields, while lines with some, extra or invalid board fields are refused. In `UartBridgeTest` the GET reply of the simulated bridge now goes through `Reply.parse` and `Snapshot.assemble`. The run of `ProtocolTest`, `UartBridgeTest`, `ProtocolFixtureTest` and `ModelDeviceTest` passed 25 out of 25.

## R2 for R3 · Cosimulation of the timing configuration of the board in CI (24 November 2026)

PR #33 turned on timing options by default for the board. `MsParams.forVerification`, which most tests use, turns them off, so CI no longer tested the Core that runs on the board. Claude Code made `CoSimTest` a shared `CoSimSuite` with two classes. `CoSimTest` keeps the earlier runs and `CoSimBoardConfigTest` repeats them with the options of the board through `BoardBuild.params`, plus a check that these options are on. R2 checked it.

Before the change a temporary copy with the options of the board matched the golden model with 6 out of 6 tests in 21 min. After it the two classes passed 13 out of 13 in 29 min on a laptop, running in parallel.

## R2 for R3 · Solver v2 in random cosimulation (25 November 2026)

Most cosimulation runs of whole games used Solver v1 because v2 is slow to simulate. Measured on 4x4, v2 costs about 0.6 s per simulated cycle against 0.08 s for v1, which makes about 90 s per move. Claude Code added a short random sequence with v2 to `CoSimSuite`, with 10 commands on 4x4, so that both `CoSimTest` and `CoSimBoardConfigTest` run it in CI. Longer v2 sequences carry the slow tag, with 40 commands on 4x4 and 30 on 5x6 with two presets. R2 checked them. The two short runs matched the golden model in 9 min 21 s locally with both classes in parallel.
