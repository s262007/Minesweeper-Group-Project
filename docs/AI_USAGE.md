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

## R2-S3: formal proofs (27 October 2026)

Claude Code wrote the formal harnesses and the CI job; R2 checked each result.
- `FormalSmokeTest` (the R2-S0 smoke harness): z3 proves a true property and reports a counterexample for a false one.
- `SolverFormalTest`: an inductive step for Solver v1 on 3x3, 4x4 and a padded 4x3 build. It starts from any state where the deductions are correct and shows the next step keeps them correct and the hints agree with the mine layout. A bounded unrolling of whole runs was tried first and did not finish on 3x3 (80 min).
- `RevealFormalTest`: Board + RevealEngine, bounded to cells + 4 cycles: no mine or flag revealed, reveals never undone, every reveal ends.
- Two injected bugs (solver mine count, cascade expanding from numbered cells) were each found by z3 and reverted.

The CI `build` job now skips the `minesweeper.Formal` tag; the `formal-tools` job installs z3 and runs the proofs. Sizes, runtimes, assumptions and limits are in docs/R2_S3_FORMAL.md.

## R2-S4: Solver v2 (29 October 2026)

Claude Code wrote the pair rule in `Solver.scala` (CONTRACT 5.6: subset and partial overlaps, swept over the clues with 1 to cells lanes), the v2 tests and the v2 formal cases; R2 checked each result.
- `SolverTest`: hand-made boards that v1 cannot finish (1-1 and 1-2-1 against a wall, the contract's partial overlap, mines from a partial overlap), the same hints with 1, 4 and all lanes, soundness against every consistent layout on 4x4, and an independent Scala v2 reference on 4x4, 7x5 and 9x9.
- `SolverFormalTest`: the v1 inductive step reused for v2 on 9-cell builds (3x3 with 1 and 9 lanes, a 2x2 game in 3x3 storage). 4x4 had not finished after 40 minutes and was dropped.
- An injected bug (|X| and |Y| swapped in the lower bound) was found by z3 in 11 s and reverted.
- The 9x9 slow test keeps 2 games in the suite (about 40 min); 6 games passed locally in 2 h 7 min.

The Core still builds v1. Details, cycle counts and limits are in docs/R2_S4_SOLVER_V2.md.
