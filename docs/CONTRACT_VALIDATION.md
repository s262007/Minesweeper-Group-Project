# R1-S0 validation

This validation record was written while Contract 1.2.0 was pending review. R1
now reports that the team has agreed on it; the test results below remain the
historical results from their stated environments.

## Latest local run: Chisel 6.7 migration (R2 review, 19 October)

The build moved to the course labs' stack: Scala 2.13.16, Chisel/compiler plugin
6.7.0, ChiselTest 6.0.0 (plus the ScalaCheck bridge). Tests now emit with
`circt.stage.ChiselStage`; `emitSystemVerilog` returns the RTL text and no longer
writes `generated/s0`. The starter adder test was removed with the starter
example, so the suite has 15 tests. Same commands, run from the repository root
outside the IDE with two JDKs:

```text
sbt test              (Temurin 17.0.17)
Total number of tests run: 15
Tests: succeeded 15, failed 0, canceled 0, ignored 0, pending 0
All tests passed.

sbt clean test        (Temurin 25.0.4.1)
Total number of tests run: 15
Tests: succeeded 15, failed 0, canceled 0, ignored 0, pending 0
All tests passed.
```

No ChiselStage deprecation warnings remain. No contract rule, parameter or
interface changed in this migration; the 12 contract tests and 3 S0 tests are
the same checks as below.

## Previous run: Chisel 3.6.1 (R1, 16 October)

The complete project was compiled and tested using the Temurin JDK 25.0.4.1
installed for the existing IntelliJ projects, sbt 1.12.13, Scala 2.13.14,
Chisel/compiler plugin 3.6.1 and ChiselTest 0.6.2. No dependency version changed.
The JVM was invoked outside the IDE for this automated check; an IntelliJ UI
run is not claimed.

```text
sbt test
Total number of tests run: 16
Suites: completed 3, aborted 0
Tests: succeeded 16, failed 0, canceled 0, ignored 0, pending 0
All tests passed.
```

The original adder test passes. The 12 contract tests cover presets, mine limits,
invalid settings, widths, storage stride, inactive padding, neighbours, density
rounding, the LFSR period/placement and UART clock settings. Neighbours are
checked across all 1601 active cells in the five presets; placement covers 40
preset/count/seed/click cases, each repeated.

The three new S0 tests emit Verilog for MineGen, Board, MinesweeperCore and
UartGameBridge at 4x4, 7x5, 9x9, 16x8 and 30x24 capacities (columns x rows).
They also emit the production logical FpgaTop, reject verification parameters
for that top, and exercise the core refusal/no-hint and idle serial-output
behaviour. These are shell checks, not game correctness or physical UART tests.
Generated RTL is under generated/s0 and is ignored by Git.

Before these additions, the 13-test suite and starter adder Verilog generation
also passed using both JDK 11 and the same local JDK 25. The earlier 12-test
contract-only run used Java 8. Keep these historical runs separate from the
latest 16-test result. GitHub checks report their own run/commit on the PR.

The ChiselStage deprecation warning and JDK 25 library warnings remain visible;
they did not fail this run. S0 does not migrate the Chisel compiler stack.

## Pending gates

- R2 reviews engine timing/solver rules and supplies its own shells/formal smoke harness.
- R3 reviews UART v2/public observations and supplies its shells, fixtures and mock replay.
- Team agreement on Contract 1.2.0 is reported by R1; keep everyone on the same revision.
- All members build the same merged revision and record their results.
- Z3 executable availability in CI is separate from a formal smoke test or solver proof.
- Target board/device/clock are recorded as Nexys4 DDR, XC7A100T-1CSG324C and 100 MHz, with Vivado 2026.1 selected. Physical board revision, pin mapping, constraints, successful full-top synthesis and programming remain pending.
- Repository admin verifies collaborators, protection and eventual public visibility.
- Clarify starter reuse terms; the new MIT license covers original contributions only.

See S0.md for the backlog and ownership boundaries. No team-wide skeleton,
solver proof, playable FPGA game or board synthesis is claimed by this check.
