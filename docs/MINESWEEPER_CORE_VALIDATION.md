# MinesweeperCore validation

MinesweeperCore now owns the runtime configuration and command state machine. Reset selects the documented default configuration with an empty board. Valid NewGame commands are checked in preset, mine-count and seed order, then atomically replace the settings, clear board state and metrics, and increment the revision. Mines are generated only when the first hidden, unflagged cell is revealed; flags entered beforehand survive the mine load.

The Core supports flag toggles, active-coordinate checks, no-op handling, signed mines-left counts, mine loss reporting and winning after all safe cells have been revealed. Each accepted command holds a result until the receiver consumes it. Private mine and hidden adjacent-count data are kept out of public cells. Hints remain invalid because the shared Solver module is not integrated yet.

`CoreTest` was written against the original shell first; its reset, NewGame and command cases failed as expected. After implementation, all 7 Core tests passed. They cover reset defaults, NewGame result backpressure, rejected settings and coordinates, over-flagging, lazy generation with pre-existing flags, hidden mine truth, mine loss, safe-cell win, terminal no-ops, metric retention, and settled/busy transitions. `S0SmokeTest` also passed and generated SystemVerilog at verification dimensions and the 30x24 capacity.

Validation used Microsoft JDK 17 with the project's Scala 2.13.16, Chisel 6.7.0 and ChiselTest 6.0.0 dependencies. Sources were freshly compiled and the selected ScalaTest suites were run directly because the local sbt launcher cannot start its build process in this environment. This was not a fresh `sbt test` run.

Zero-cell flood-fill is not implemented here. The Core currently commits the clicked safe cell only; R2's RevealEngine integration remains Step 5. Solver-backed hints, UART operation, FPGA synthesis/timing, formal proofs and independent co-simulation are also pending.
