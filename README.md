# FPGA Minesweeper

Parameterized Minesweeper in Chisel, with UART input and display. The FPGA will run the game logic, flood-fill reveal engine and constraint solver. The FPGA board is still to be selected.

## Build setup

The dependency versions are shared; local IntelliJ and CI use the JDKs listed below:

| Tool | Version |
| --- | --- |
| JDK | Temurin 25 in R1's IntelliJ (verified); Temurin 11 in CI |
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

Run these commands from the repository root. Both Java commands should report version 11. For the existing IntelliJ workflow, select temurin-25 for both Project SDK and sbt JVM, then reload sbt. All tests and starter Verilog generation have also passed with this installed JDK. The Java 11 terminal example above is optional.

## Checks

```text
sbt test
sbt "testOnly minesweeper.ContractTest"
```

The first command runs all tests, including the starter adder example. The second runs only the shared contract tests. Current tests use the default simulator and do not need Verilator. A simulator or FPGA toolchain needed by later work will be documented when selected.

GitHub Actions runs `sbt test` with JDK 11 for pushes to `main` and pull requests targeting `main`. If sbt reports that 2.x requires Java 17, check that you are in this repository and that `project/build.properties` is present. Do not upgrade sbt or Chisel to resolve that error.

## Shared contract

See [CONTRACT.md](docs/CONTRACT.md) for board presets, runtime mine counts, memory layout, engine handshakes and UART messages. Parameter definitions and shared interfaces are in `src/main/scala/minesweeper`.

The starter adder remains in `src/main/scala/empty`. It can be generated with `sbt "runMain empty.AddMain"`. The S0 Minesweeper modules are shells only; the game and serial logic are not implemented yet. See [S0 status](docs/S0.md).


## License

MIT for original project contributions. See [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md) for the starter material and its unresolved reuse terms.

