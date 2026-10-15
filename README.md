# FPGA Minesweeper

Parameterized Minesweeper in Chisel, with UART input and display. The FPGA will run the game logic, flood-fill reveal engine and constraint solver. The FPGA board is still to be selected.

## Build setup

Use these versions locally and in CI:

| Tool | Version |
| --- | --- |
| JDK | 11 (Temurin in CI) |
| sbt | 1.12.13, pinned in `project/build.properties` |
| Scala | 2.13.14 |
| Chisel and compiler plugin | 3.6.1 |
| ChiselTest | 0.6.2 |

Install a JDK 11 and sbt. Scala, Chisel and ChiselTest are downloaded by sbt; separate Scala or Chisel installations are unnecessary. The installed sbt launcher may be newer: the repository pin selects the build version.

On Windows, set `JAVA_HOME` to the JDK directory (the folder containing `bin`), and put its `bin` directory first on PATH. For a PowerShell session:

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-11'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
java -version
javac -version
sbt test
```

Run these commands from the repository root. Both Java commands should report version 11. When importing into IntelliJ, select JDK 11 for both the project SDK and the sbt JVM; the IDE can otherwise use a different Java version from the terminal.

## Checks

```text
sbt test
sbt "testOnly minesweeper.ContractTest"
```

The first command runs all tests, including the starter adder example. The second runs only the shared contract tests. Current tests use the default simulator and do not need Verilator. A simulator or FPGA toolchain needed by later work will be documented when selected.

GitHub Actions runs `sbt test` with JDK 11 for pushes to `main` and pull requests targeting `main`. If sbt reports that 2.x requires Java 17, check that you are in this repository and that `project/build.properties` is present. Do not upgrade sbt or Chisel to resolve that error.

## Shared contract

See [CONTRACT.md](docs/CONTRACT.md) for board presets, runtime mine counts, memory layout, engine handshakes and UART messages. Parameter definitions and shared interfaces are in `src/main/scala/minesweeper`.

The starter adder remains in `src/main/scala/empty`. It can be generated with `sbt "runMain empty.AddMain"`. The full Minesweeper top level is not implemented yet.

