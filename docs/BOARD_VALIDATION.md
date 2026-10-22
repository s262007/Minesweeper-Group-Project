# Board validation

The Board stores mine, revealed and flagged masks at the configured maximum capacity. It uses `maxCols` as the physical row stride for every preset, masks state and writes to the active rows and columns, and counts adjacent mines from active neighbours only. The private view exposes the full state to the Core, the reveal view omits mine truth while retaining counts for flood fill, and the solver view provides counts only for revealed safe cells.

`BoardTest` covers the five production presets and padding, corner and edge neighbour counts, row-wrap prevention, flag preservation during mine loading, ignored writes to flagged/revealed/out-of-grid cells, loss display and solver masking, clear/reset priority, changing active dimensions, and 120 deterministic mixed updates checked against a coordinate-based reference model. It also exercises the full-capacity flag and safe-reveal counters.

Validation on Microsoft JDK 17:

- Freshly compiled the main and test Scala sources with Scala 2.13.16 and the Chisel 6.7.0 compiler plugin; all 7 `BoardTest` cases passed (about 12 minutes).
- The existing 21 tests across `AddTester`, `ContractTest`, `S0SmokeTest` and `MineGenTest` passed against the new Board sources (about 43 seconds).
- `S0SmokeTest` generated SystemVerilog at verification sizes and the full 30x24 capacity.

The test runner invoked ScalaTest directly with the cached project dependencies because the local sbt launcher could not start the build process in this environment. These results are not a fresh `sbt test` run. No formal proof, synthesis, board timing or game-Core integration is claimed. The Board implementation does not change the shared interfaces or parameter definitions.
