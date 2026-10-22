# AI usage

## R1-S0: shared contract and interfaces (15 October 2026)

Codex helped draft the contract, parameter definitions, shared Chisel interfaces and contract tests from the team's project plan and agreed settings. It also edited the wording and corrected the starter CI branch filters from master to main.

The local contract suite passed all 12 tests using Scala 2.13.14, Chisel 3.6.1 and ChiselTest 0.6.2. R2/R3 review is pending. The team will review the rules, defaults, LFSR sequence and interface timing before freezing the contract.

## R1-S0: independent setup work (16 October 2026)

Codex drafted R1 module shells, elaboration/smoke tests, the S0 backlog and board-status record, PR template, build-output ignore rules, Z3 tool-check setup, license and upstream notice. The user authorized license selection. MIT applies to original contributions; starter reuse terms are unresolved. IntelliJ guidance follows the existing temurin-25 setup. Team approvals and R2/R3 implementation remain pending. See CONTRACT_VALIDATION.md for checks actually run.

## R1-S0 review fixes, proposed by R2 (19 October 2026)

Claude Code (Anthropic) reviewed the R1-S0 pull request against plan v1.1 and the course labs, then made the "should fix" changes on a separate branch: migration to Chisel 6.7 / ChiselTest 6.0 (build file and the two test files), removal of the starter adder and Quartus example, a Makefile clean target that only removes build output, CI on JDK 17 for every pull request, and the matching README/contract/S0/validation/notice updates. The first migrated run failed one test (`emitSystemVerilog` no longer accepts `--target-dir`); the fix was to check the returned RTL text. The full suite was then run on JDK 17 and JDK 25 (15/15 each). No contract rule or interface was changed.


## R3-S0: protocol fixtures, host codec, mock replay, reference geometry and UART shells (22 October 2026)

Claude Code (Anthropic) wrote the tests first, from CONTRACT 1.2.0 sections 3.1, 4.1, 5.7 and 6: RefGridTest, ProtocolTest, ProtocolFixtureTest and UartShellTest. Before any implementation existed they failed to compile. It then wrote the code:
- `model/RefGrid`: independent neighbours from explicit row/column bounds;
- `host/Protocol`: request encoder, strict reply parser and snapshot checker;
- `host/Fixture`: transcript format, ScriptedDevice mock and Replay runner;
- `UartRx`/`UartTx` shells.

The eight fixtures in `docs/protocol/` were drafted with a throwaway script and then reviewed. The script re-implemented the contract's LFSR and flood fill from the text alone, so it shares no project code. A test checks that the loss display in `reveal.txt` agrees with `Lfsr16.placeMines` and with independent adjacency counts.

Three injected bugs were caught and then reverted: a right edge that wraps (5 tests), a missing mine-leak check (1 fixture test, plus 1 unit test once that test was isolated) and a missing extra-line check (1 test). Full `sbt test`: 47/47 on JDK 25. Seven protocol assumptions are listed in `docs/protocol/README.md` for team approval.
