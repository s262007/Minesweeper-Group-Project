# FPGA Minesweeper

Parameterized Minesweeper in Chisel, targeting a Digilent Nexys4 DDR Rev. C with an Artix-7 XC7A100T-1CSG324C and 100 MHz oscillator. The FPGA runs the game logic, flood-fill reveal engine and constraint solver. A Nexys4Top bitstream has been generated and programmed; implementation timing still fails the 100 MHz target.

## Build setup

The dependency versions are shared and match the course labs 6-8:

| Tool | Version |
| --- | --- |
| JDK | 17 or later (CI uses Temurin 17; tests also pass on Temurin 25) |
| sbt | 1.12.13, pinned in `project/build.properties` |
| Scala | 2.13.16 |
| Chisel and compiler plugin | 6.7.0 |
| ChiselTest | 6.0.0 |

Install a JDK 17 (or later) and sbt. Scala, Chisel and ChiselTest are downloaded by sbt; separate Scala or Chisel installations are unnecessary. The installed sbt launcher may be newer: the repository pin selects the build version.

On Windows, set `JAVA_HOME` to the JDK directory (the folder containing `bin`), and put its `bin` directory first on PATH. For a PowerShell session:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
sbt test
```

Run these commands from the repository root. Both Java commands should report version 17 or later. In IntelliJ, select the same JDK for both Project SDK and sbt JVM (temurin-17 or temurin-25), then reload sbt. The first run downloads the dependencies and the CIRCT `firtool` binary used for Verilog emission, so it needs internet access.

## Checks

```text
sbt test
sbt "testOnly minesweeper.ContractTest"
```

The first command runs all tests. The second runs only the shared contract tests (`make test` and `make contract` do the same; `make clean` removes build output only). Current tests use the default simulator and do not need Verilator. Vivado 2026.1 is the selected synthesis tool for the Nexys4 DDR target.

GitHub Actions runs `sbt test` with JDK 17 for pushes to `main` and for every pull request. If your sbt launcher is 2.x and complains, check that you are in this repository and that `project/build.properties` is present: the pin selects sbt 1.12.13.

## Play in simulation

```text
sbt "set Test / run / fork := true" "set Test / run / connectInput := true" "Test/runMain minesweeper.PlayConsole 6 6 5 1"
```

This plays a 6x6 game with 5 mines (seed 1) on the simulated `MinesweeperCore`, with solver hints, and the golden model checks every move. Commands, options and the co-simulation are described in [R3_S1.md](docs/R3_S1.md).

`PropertyTest` generates games (ScalaCheck) and checks twelve game properties after every command, on the golden model and on the simulated Core in lockstep with it. `SolverOracle` gives the exact set of certain cells for a board, against which every solver hint is checked. See [R3_S3.md](docs/R3_S3.md).

`sbt "Test/runMain minesweeper.AutoPlay"` plays games with the hints and writes [results/solver_stats.csv](results/solver_stats.csv). On Beginner (9x9, 10 mines, 1000 games), a player following the v2 hints wins without guessing in 79% of games, against 58% with v1. See [R3_S4.md](docs/R3_S4.md).

## Shared contract

See [CONTRACT.md](docs/CONTRACT.md) for board presets, runtime mine counts, memory layout, engine handshakes and UART messages. Parameter definitions and shared interfaces are in `src/main/scala/minesweeper`.

MineGen, Board, the flood-fill reveal path and the v1 constraint solver are wired into the Core in the current local work. The user reports the Core tests and full Scala suite pass; deductions are exposed only after a completed, consistent solve. The `UartGameBridge` speaks the byte-level protocol, while `UartRx` and `UartTx` are still shells and `FpgaTop` is not connected to the Nexys4 DDR serial pins. The board target is selected, but board constraints, final synthesis fit and hardware validation remain open. See the R1 S1-S3 checklists for the current scope and dependencies.

## Reveal engine

`RevealEngine` opens the cells of a click (CONTRACT 5.5). It is a flood fill that reveals one ring of cells per clock cycle:
- a `frontier` register holds the cells revealed in the current cycle; the clicked cell is the first ring;
- the next ring is every neighbour of a zero cell of the frontier that is still eligible: active, hidden, unflagged and not yet visited by this reveal. Numbered cells are revealed but do not expand;
- a private `visited` register stops the engine from visiting a cell twice while the Board commits the previous ring, so `revealSet` depends only on registers: there is no combinational loop through the Board.

A reveal takes one cycle per ring (`cycles` equals `layers`). An empty board clicked in a corner needs max(rows, cols) rings. The neighbour wiring is fixed for the 30x24 capacity, and the eligibility mask removes the padding of smaller presets. In R1's routed Vivado build of 4 November 2026 the engine uses 1,504 LUTs and 1,483 flip-flops.

`RevealEngineTest` (10 tests) covers a numbered cell, a zero region and its numbered border, a wall of flags, the corner case above, cells revealed earlier, a small preset inside the capacity, starts that must be ignored, busy and clear, an independent BFS on 20 random layouts per configuration, and the 30x24 capacity (slow tag). The formal proof is listed under [What is proven](#what-is-proven).

## Solver and formal proofs

`Solver` computes the hints of CONTRACT 5.6: cells that are certainly safe or certainly mines, given the revealed numbers. It sees only the revealed cells and their numbers, never the mine layout.

### Rules

- **v1, one clue at a time.** For a clue, M is the number of deduced mines around it and U the set of its unknown neighbours (hidden and not deduced yet). With k = clue - M: k = 0 makes every cell of U safe, k = |U| makes every cell of U a mine, and k < 0 or k > |U| is a contradiction.
- **v2, pairs of clues, off by default.** Take two clues at most two cells apart, with unknown sets A and B and residuals a and b, and let I = A ∩ B, X = A - B, Y = B - A. The number of mines in I lies between L = max(0, a - |X|, b - |Y|) and U = min(|I|, a, b). L > U is a contradiction. If L = U = q, then I holds q mines, X holds a - q and Y holds b - q, and each part is tested for "no mine" and "all mines". This covers a clue whose cells contain another's, and partial overlaps such as 1-2-1 against a wall. `SolverConfig(subsetRule = true)` turns v2 on.

The rules repeat until nothing changes. On a consistent board they only add true facts, so the order in which the clues are visited does not change the result. Hints are shown only after a run that ends without a contradiction (`valid`).

### Schedule

The rule logic exists for one row of clues, not for every cell: the first, fully parallel version needed 26,641 LUTs in Vivado, 42% of the FPGA.

```mermaid
flowchart LR
    start([start]) --> v1["v1 pass: for each active row,<br/>read a 3-row window, count, write<br/>(3 cycles per row)"]
    v1 -- "new deduction" --> v1
    v1 -- "no change, v1 build" --> done([done, valid])
    v1 -- "no change, v2 build" --> pair["pair pass: for each active row,<br/>read 5 rows (3 cycles), 5 cycles per column,<br/>write (2 cycles)"]
    pair -- "new deduction" --> v1
    pair -- "no change" --> done
    v1 -. "unsatisfiable clue" .-> bad([done, contradiction])
    pair -. "contradiction" .-> bad
```

- **v1 step on row r, 3 cycles:** rows r - 1 to r + 1 go into registers (unknown cells, deduced mines, clues of row r); each clue of row r counts M and U and the rule results are registered; the deductions are written into the knowledge.
- **Pair pass on row r, v2 only:** rows r - 1 to r + 3 are read (3 cycles). Then for each active column c, 5 cycles: select the 5x7 region around (r, c), compute the constraints of the centre clue and its 12 partners, the bounds, the decisions, and add the deductions to a pending buffer. The pending rows are written last (2 cycles).

### Size and speed

| Solver alone | v1 (default) | v2 (`subsetRule = true`) |
|---|---|---|
| LUTs, Vivado (R1's routed build, 4 Nov 2026) | 8,687 | not built yet, about 11,600 estimated |
| LUTs, Yosys (30x24) | 11,506 | 15,368 |
| Flip-flops, Yosys | 1,914 | 3,696 |
| Longest path, Yosys | 8 logic levels | 10 logic levels |

R1's routed build of 4 November 2026 does not meet 100 MHz yet. Its 20 worst setup paths are outside the Solver and the RevealEngine; the slack of these two modules has not been reported yet.

A v1 pass takes 3 cycles per active row. On a model of the schedule with 200 played games per preset, a solve takes at most 216 cycles on 9x9 and 576 on 30x24 (under 6 µs at 100 MHz). With v2, the simulations measured 126 to 239 cycles on 4x4 games, 216 to 480 on 7x5 and up to 1,012 on 9x9. One UART byte at 115,200 baud takes about 87 µs.

### Tests

`SolverTest` has 20 tests, 3 of them with the slow tag:
- hand-made boards: one clue, chained deductions, nothing to deduce, padding, contradiction, the busy and clear handshake; for v2, 1-1 and 1-2-1 against a wall and the contract's partial overlap, which v1 cannot finish;
- soundness: on random 4x4 games, no hint contradicts any mine layout that fits the clues (every layout is enumerated);
- equality with Scala references written in the test, which share no code with the hardware: the v1 fixpoint and its cycle count on 20 played games per configuration, and the v2 closure on 4x4, 7x5 and 9x9 games;
- the 30x24 capacity with 9x9 and 30x16 games (slow).

Slow tests: `sbt "set Test / testOptions := Nil" "testOnly minesweeper.SolverTest minesweeper.RevealEngineTest -- -n minesweeper.Slow"`.

### What is proven

z3 proofs with ChiselTest, on small boards only:

| Property | Module | Sizes | Method |
|---|---|---|---|
| A safe hint is never a mine, a mine hint is always a mine, never both, never on a revealed or inactive cell; consistent clues are never reported as a contradiction | Solver v1 | 3x3, 4x4, 4x3 storage with 4x3 and 3x2 games | induction: true after any number of steps and runs |
| No consistent clue is judged unsatisfiable, no consistent pair has L > U, every deduction in I, X and Y is true | v2 pair rule (`PairRule`) | the 5x7 region, all 12 partner positions | one cycle, any layout and any sound knowledge |
| A cascade never reveals a mine or a flagged cell, a revealed cell never becomes hidden, every reveal ends within cells + 1 cycles | Board + RevealEngine | 3x3, 4x4, 4x3 storage | bounded: cells + maxRows + 4 cycles from reset |

Injected bugs were each found by z3 and then reverted: deductions written one row off, the all-mines test comparing the clue with M, a cascade expanding from numbered cells, |X| and |Y| swapped in L.

Not proven: boards larger than 4x4 and the 30x24 capacity (tests and co-simulation only), that the solver finds every deduction, the v2 control around the pair rule (simulation only), the cycle bounds, MineGen, the Core and the UART path. Details, runtimes and assumptions: [R2_S3_FORMAL.md](docs/R2_S3_FORMAL.md) and [R2_S4_SOLVER_V2.md](docs/R2_S4_SOLVER_V2.md).

Run the proofs with `sbt "testOnly -- -n minesweeper.Formal"` (z3 on the PATH). CI runs them in the `formal-tools` job and everything else in the `build` job.


## Planned Nexys4 DDR controls

The planned board interface uses four seven-segment digits only to show mines remaining: a sign digit and three digits for the magnitude, so over-flagging can be shown as a negative count. The four directional pushbuttons move the highlighted cell across the active grid: up/down move between rows, and left/right move between columns. Movement stops at the grid edges. A browser tab on the PC will display the board, highlight the selected cell, and show revealed numbers and solver hints. The tab is planned to communicate with the FPGA over its USB-UART connection using the shared text protocol. A slide switch controls hints directly: hints are enabled while it is on and hidden while it is off. Separate slide switches request flag, reveal and autoplay actions. Flag, reveal and autoplay are triggered only on a switch transition from off to on; return the switch to off before requesting that action again. A flag action toggles the selected cell between flagged and unflagged. A reveal action opens the selected cell, subject to the game rules. The center pushbutton starts or restarts a game.

The `Nexys4Top` connects input conditioning, highlighted-cell selection, game command control, the Core, the four-digit mines-left display, and the UART bridge while keeping board and hint vectors internal. The current generated bitstream is programmed on the Nexys4 DDR Rev. C. Vivado 2026.1 synthesis reports 55,734 LUTs (87.91%), 6,328 registers (4.99%), 28 LUTRAMs and no block RAM on the `xc7a100tcsg324-1`. The routed timing report fails the 100 MHz target: WNS -19.845 ns, with the critical path in MineGen from its state register to the candidate mask register (29.734 ns path delay, 40 logic levels). The bitstream generation and programming succeeded, and the user reports that the LEDs respond. These results do not establish reliable timing closure or full game play. UART RX/TX remain shells, the hint switch is not connected to behavior, and the PC browser board display and physical game validation remain unfinished. The seven-segment display is intended to show mines remaining only. See `docs/R1_S3.md` for the recorded hardware status and follow-up work.

## License

MIT for original project contributions. See [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md) for the starter material and its unresolved reuse terms.

