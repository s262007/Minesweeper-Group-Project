# R1/S1 validation status

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

`S0SmokeTest` emitted SystemVerilog for verification capacities 4x4, 7x5, 9x9 and 16x8, and the production 30x24 capacity. The simulation walkthrough demonstrates validated NewGame settings, flag on/off, a safe zero-cell click, mine loss, winning after all safe cells are revealed individually, and restart with another preset and mine count. It clearly labels that zero-cell expansion is unavailable until RevealEngine integration.

MineGen, Board and Core have local implementations and unit tests. The Core keeps hidden mine/count data out of public observations, and hints remain invalid while the Solver is absent. The S1 checklist records the current ownership dependencies:

- R2's RevealEngine is not in this checkout, so zero-region flood-fill and its board-write timing tests remain pending.
- R3's independent model and co-simulation adapter are not in this checkout, so independent state comparison remains pending.
- The UART bridge and FPGA top remain shells. No physical UART, FPGA synthesis/timing result, formal solver proof or hardware-fit claim is made.

The test and RTL results are useful partial S1 evidence, but they do not satisfy the full simulated-playable-board acceptance. Do not mark the final S1 checkpoint complete until reveal integration, independent comparison and zero-region demo acceptance are done. Nothing has been pushed.
