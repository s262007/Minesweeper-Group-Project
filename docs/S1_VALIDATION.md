# R1/S1 validation status

The full test results below are historical and predate the Core flood-fill integration. After that integration, the user reports the focused `CoreTest`, `RevealEngineTest` and `SimulationDemoTest` suites passed with JDK 17. A fresh full `sbt test` run is still needed before recording S1 acceptance.

The complete local test set passed on 22 October 2026: **36 tests across 7 suites**, with no failures. The run used Microsoft JDK 17.0.20.1, Scala 2.13.16, Chisel 6.7.0 and ChiselTest 6.0.0. The suite totals were:

| Suite | Tests |
| --- | ---: |
| CoreTest | 7 |
| SimulationDemoTest | 1 |
| S0SmokeTest | 3 |
| ContractTest | 12 |
| MineGenTest | 5 |
| BoardTest | 7 |
| AddTester | 1 |
| **Total** | **36** |

The Scala sources were freshly compiled, then ScalaTest was run directly with the project dependencies and JDK 17. The local sbt launcher cannot start its build process in this environment, so this is not a fresh `sbt test` invocation. The complete direct test run took 4 minutes 52 seconds.

The historical `S0SmokeTest` emitted SystemVerilog for verification capacities 4x4, 7x5, 9x9 and 16x8, and the production 30x24 capacity. Its original simulation walkthrough predates flood-fill integration; the updated `SimulationDemoTest` now exercises the integrated reveal path (focused pass reported above).

MineGen, Board, Core, R2's RevealEngine and R2's v1 Solver have local implementations and unit tests. The user reports that the latest Core solver wiring and full Scala suite pass. The Core keeps hidden mine/count data out of public observations. The S1 checklist records the current ownership dependencies:

- R3's independent geometry reference (`RefGrid`) is present and tested. The broader independent Core/state comparison adapter is not in this checkout, so that comparison remains pending.
- The byte-channel UART bridge is implemented, but `UartRx`/`UartTx` remain shells and `FpgaTop` is not connected to physical serial pins. No physical UART, FPGA synthesis/timing result, formal solver proof or hardware-fit claim is made.

After the solver/state update, the user reports that `sbt "testOnly minesweeper.CoreTest"`, the focused `ProtocolTest`, and the full `sbt test` regression all passed on Microsoft JDK 17. The full test count was not included in the latest report, so this note does not claim a count. Independent Core/state comparison remains outstanding until R3's adapter is available. Nothing has been pushed from this worktree.
