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

