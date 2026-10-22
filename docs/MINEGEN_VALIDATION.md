# MineGen — R1/S1 Step 2

22 October 2026. Local work on `r1/s1`; no GitHub changes.

## Implementation

`MineGen.scala` replaces the MineGen definition formerly in S0Stubs.scala. The existing MineGenIO and parameters are unchanged. Board, Core, UART bridge and FPGA top remain shells.

On start.fire, the module captures the validated configuration, seed and first-click coordinates. It clears the previous result and processes one LFSR candidate on each following clock, rejecting protected and duplicate cells. The LFSR advances even on rejection. Each runtime preset uses constant divisors for compact indexing, with conversion to the fixed physical storage stride. This avoids a general runtime divisor; FPGA resource use and timing have not been measured.

`cycles` counts candidate attempts, excluding the admission edge. The final candidate sets done for one cycle and lowers busy. The completed mask and counter remain stable until clear, reset or another accepted request. There is one completion cycle with start.ready low, after which another request can be accepted. Clear cancels work and overrides start. Zero-mine verification requests complete immediately after admission with zero candidate cycles. Internal-request assertions check the validated-input assumptions and the one-period execution bound; these are not formal proofs.

## Test evidence

The new five-test suite was written first. All five tests failed against the original shell because it never accepted a request. After implementation, the complete local suite passed **21 tests across four suites**, with no failures, cancellations, ignored or pending tests. Final run time was 35.538 seconds.

- Production matrix: five presets, minimum and maximum mine counts, seeds 1 and 65535, and five first-click positions (two corners, top/left edges and interior): 100 requests, plus a repeated request after other presets.
- Layouts match the contract's Scala reference sequence. Separate coordinate-based checks establish exact mine count, active-cell placement and safe-zone exclusion without using the neighbour helpers.
- The expected attempt count is checked before and at completion. A small-board test also checks every candidate clock, including protected and duplicate rejections.
- Tests hold a different preset/configuration request during busy and verify that it is accepted only after the previous completion. They also check stable outputs, clear versus simultaneous start, cancellation, reset during work and zero-mine verification behaviour.
- The existing ContractTest, S0SmokeTest and starter AddTester pass. S0SmokeTest emits SystemVerilog for MineGen and the other R1 modules at 4x4, 7x5, 9x9, 16x8 and production 30x24 capacities.

Validation used Microsoft JDK 17.0.20.1, Scala 2.13.16, Chisel/plugin 6.7.0 and ChiselTest 6.0.0 from the cached resolved project dependencies. The agent's sbt startup remains affected by the previously recorded Windows access error. For this step, Scala sources and tests were freshly compiled with the matching compiler plugin into a separate workspace output directory, then run directly through ScalaTest. This is a passing compile/test result, not a claim that `sbt test` launched successfully from this chat. The cached matching firtool 1.62.1 was selected explicitly for RTL checks.

Normal IntelliJ/sbt verification commands remain:

```text
sbt "testOnly minesweeper.MineGenTest"
sbt test
```

No production interfaces, R2 implementation or AI_USAGE.md entries changed. No Board work, Core integration, solver proof, FPGA synthesis or physical-board test is included in this step.
