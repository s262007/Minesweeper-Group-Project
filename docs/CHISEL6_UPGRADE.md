# R1 build upgrade

21 October 2026. Local R1 validation; this is not a GitHub CI result.

## Selected dependencies

The build now matches the versions supplied by R2: Scala 2.13.16, Chisel and compiler plugin 6.7.0, ChiselTest 6.0.0, and the ScalaTest/ScalaCheck bridge `scalacheck-1-18` 3.2.19.0. The sbt pin remains 1.12.13. Testing libraries are restricted to test scope.

## Changes

- Replaced the legacy Chisel 3 dependencies and removed the obsolete commented build alternative.
- Updated interface elaboration to `circt.stage.ChiselStage.emitCHIRRTL`.
- Updated shell checks to CIRCT SystemVerilog generation. The string-returning API does not accept the old `--target-dir` option, so the test writes the returned RTL into `generated/s0` explicitly.
- Updated the existing starter generation entry point to `emitSystemVerilogFile` so the full local source tree still compiles.
- Updated README, contract build requirements and S0 setup instructions. The older CONTRACT_VALIDATION.md remains a historical record of the previous stack.

The Minesweeper production source files, interface semantics and game parameters were unchanged. No R2 files or branches were changed. AI_USAGE.md was unchanged. No GitHub commit or push was performed.

## Validation

The isolated R1-only check passed all 15 tests. The applied local project then passed `sbt test`: 16 tests across three suites, including the retained starter test. `runMain empty.AddMain` also succeeded. Both commands exited successfully.

The shell test generated SystemVerilog for MineGen, Board, MinesweeperCore and UartGameBridge at 4x4, 7x5, 9x9, 16x8 and 30x24 capacities, plus the production logical FPGA top. ChiselTest simulation checked refused commands, absent hints and idle UART output. These are checks of the existing S0 shells, not implementation of a playable game.

Checks ran on Windows with Temurin 11.0.32.1 and sbt 1.12.13. This matches the existing CI Java major version; the GitHub workflow itself was not run. JDK 17 is the recommended IntelliJ selection for the upgraded Scala stack. The previously used IntelliJ JDK 25 was not validated with this stack.

No formal proof, FPGA synthesis or board test was run. Formal validation remains with the existing R2 harness and tool workflow; the dependency upgrade alone does not establish solver correctness. The ScalaCheck bridge resolved and the test sources compiled, but no new property-based suite was added.

Open the project root as an sbt project in IntelliJ and reload its sbt definition. Select the same supported JDK for the Project SDK and sbt JVM. A separate Scala installation is unnecessary.
