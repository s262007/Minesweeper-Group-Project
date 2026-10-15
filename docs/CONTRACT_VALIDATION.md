# Contract checks

R1-S0, 15 October 2026. Contract version 1.2.0; team review pending.

## Test run

```text
sbt "testOnly minesweeper.ContractTest"

Total number of tests run: 12
Suites: completed 1, aborted 0
Tests: succeeded 12, failed 0, canceled 0, ignored 0, pending 0
All tests passed.
```

The tests ran in an isolated copy of the starter project with sbt 1.12.13, Scala 2.13.14, Chisel 3.6.1, its compiler plugin and ChiselTest 0.6.2. The local runtime was Oracle Java 1.8.0_421; GitHub CI uses JDK 11. build.sbt is unchanged.

The suite checks preset IDs, legal mine counts, invalid settings, widths, fixed storage stride, padding, neighbours, density conversion, LFSR period, mine placement and UART clock settings. Neighbours are checked across all 1601 active cells in the five presets. Mine placement covers 40 preset/count/seed/click cases, each repeated. The interface probe elaborates at 30x24 and 4x4 capacities.

All main and test sources compile. Only ContractTest was run; the game engines and serial link are not implemented yet. The test-only ChiselStage API produces a deprecation warning with Chisel 3.6.1.

## Review

- R2: check start/clear/done/valid, flood-fill timing, masking and solver rules.
- R3: check snapshot fields, UART v2/retries and reference-model comparison points.
- All: confirm reset defaults (9x9, 14 mines, seed 0001), preset IDs and LFSR sequence.
- Choose the board after checking availability, then supply its clock and pins.

Next S0 work: add module stubs, protocol fixtures and formal smoke tests; have everyone clone and build; finish the backlog and repository rules.
