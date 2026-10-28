# FPGA Minesweeper

Parameterized Minesweeper in Chisel, targeting a Digilent Nexys4 DDR with an Artix-7 XC7A100T-1CSG324C and 100 MHz oscillator. The FPGA will run the game logic, flood-fill reveal engine and constraint solver. Physical pin mapping and hardware integration remain in progress.

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

## Shared contract

See [CONTRACT.md](docs/CONTRACT.md) for board presets, runtime mine counts, memory layout, engine handshakes and UART messages. Parameter definitions and shared interfaces are in `src/main/scala/minesweeper`.

MineGen, Board, the flood-fill reveal path and the v1 constraint solver are wired into the Core in the current local work. The user reports the Core tests and full Scala suite pass; deductions are exposed only after a completed, consistent solve. The `UartGameBridge` speaks the byte-level protocol, while `UartRx` and `UartTx` are still shells and `FpgaTop` is not connected to the Nexys4 DDR serial pins. The board target is selected, but board constraints, final synthesis fit and hardware validation remain open. See the R1 S1-S3 checklists for the current scope and dependencies.


## Planned Nexys4 DDR controls

The planned board interface uses four seven-segment digits only to show mines remaining: a sign digit and three digits for the magnitude, so over-flagging can be shown as a negative count. The four directional pushbuttons move the highlighted cell across the active grid: up/down move between rows, and left/right move between columns. Movement stops at the grid edges. A browser tab on the PC will display the board, highlight the selected cell, and show revealed numbers and solver hints. The tab is planned to communicate with the FPGA over its USB-UART connection using the shared text protocol. A slide switch controls hints directly: hints are enabled while it is on and hidden while it is off. Separate slide switches request flag, reveal and autoplay actions. Flag, reveal and autoplay are triggered only on a switch transition from off to on; return the switch to off before requesting that action again. A flag action toggles the selected cell between flagged and unflagged. A reveal action opens the selected cell, subject to the game rules. The center pushbutton starts or restarts a game.

The `Nexys4Top` connects input conditioning, highlighted-cell selection, game command control, the Core, the four-digit mines-left display, and the UART bridge while keeping board and hint vectors internal. Top-level elaboration and verification, the PC browser interface, functioning FPGA UART RX/TX, and board pin constraints remain to be completed. The game board display belongs to the host/UART work; the seven-segment display shows mines remaining only.

## License

MIT for original project contributions. See [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md) for the starter material and its unresolved reuse terms.

