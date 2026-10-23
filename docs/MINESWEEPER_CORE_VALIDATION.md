# MinesweeperCore validation

MinesweeperCore now owns the runtime configuration and command state machine. Reset selects the documented default configuration with an empty board. Valid NewGame commands are checked in preset, mine-count and seed order, then atomically replace the settings, clear board state and metrics, and increment the revision. Mines are generated only when the first hidden, unflagged cell is revealed; flags entered beforehand survive the mine load.

The Core connects safe reveals to R2's delivered `RevealEngine`. The engine receives the Board's restricted reveal view; its emitted layers commit through the Board's reveal-set input. The Core holds a reveal command while the engine is busy, then runs R2's v1 `Solver` against the Board's masked solver view. A successful fixpoint publishes sound/mine hints and the command result; a solver contradiction hides hints, returns `InternalError` and enters `Error`. Private mine and hidden adjacent-count data stay out of public cells.

Flag toggles on hidden cells invalidate prior hints. Before mine generation, a flag completes immediately without running the solver and leaves an unstarted game in `Idle`. After generation, flags trigger a fresh solver run. Mine generation starts only on the first eligible reveal. When generation finishes, the public state becomes `Playing` during the first-click flood fill; after reveal and solver completion the Core enters `Playing` or `Won` as appropriate. Loss still exposes mines and invalidates hints.

The user reports that after the solver/state update, `sbt "testOnly minesweeper.CoreTest"` passed all 10 tests and the subsequent full `sbt test` passed. The focused `ProtocolTest` also passed after correcting the line-ending-sensitive malformed fixture. These are user-reported local results; the final full-run test count was not included.

Earlier full-suite validation predates the latest changes. The current focused and full-suite results are reported by the user from their JDK 17 terminal; this environment has not independently rerun them.

UART byte hardware, FPGA synthesis/timing, formal proofs and independent full-game co-simulation remain pending. R3 owns independent Core/state comparison.
