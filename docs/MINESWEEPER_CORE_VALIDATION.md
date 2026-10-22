# MinesweeperCore validation

MinesweeperCore now owns the runtime configuration and command state machine. Reset selects the documented default configuration with an empty board. Valid NewGame commands are checked in preset, mine-count and seed order, then atomically replace the settings, clear board state and metrics, and increment the revision. Mines are generated only when the first hidden, unflagged cell is revealed; flags entered beforehand survive the mine load.

The Core connects safe reveals to R2's delivered `RevealEngine`. The engine receives the Board's restricted reveal view; its emitted layers commit through the Board's reveal-set input. The Core holds a reveal command while the engine is busy, then updates reveal metrics, win state, revision and the result at completion. Mine hits still expose mines directly, while flags and already-revealed cells remain no-ops. Private mine and hidden adjacent-count data stay out of public cells. Hints remain invalid because the Solver is not integrated into the Core.

The user reports that the focused `CoreTest`, `RevealEngineTest` and `SimulationDemoTest` run passed after the multi-cycle flood-fill integration. This covers reset defaults, NewGame result backpressure, rejected settings and coordinates, over-flagging, lazy generation with pre-existing flags, hidden mine truth, mine loss, safe-cell win, terminal no-ops, metrics and settled/busy transitions, along with reveal expansion and demo behavior. A full `sbt test` run is still needed for regression acceptance.

Earlier full-suite validation used Microsoft JDK 17 with Scala 2.13.16, Chisel 6.7.0 and ChiselTest 6.0.0, but predates the reveal integration. The recent focused result was reported by the user from their JDK 17 terminal; this environment has not independently rerun it.

Solver-backed hints, UART operation, FPGA synthesis/timing, formal proofs and independent co-simulation remain pending.
