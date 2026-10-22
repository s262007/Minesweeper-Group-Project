# R2 demo console (plan B for the MVP talk)

A terminal Minesweeper where the two R2 modules do the work in simulation:

- **RevealEngine** (R2-S1) computes every cascade, one ring per clock cycle.
- **Solver v1** (R2-S2) computes the safe/mine hints after every move.

The Scala side only does what `MinesweeperCore` (R1-S1) will do later:
- mine placement, with the contract's exact sequence `Lfsr16.placeMines` and the first click protected;
- flags, win and loss.

This is **not** the MVP `PlayConsole` (R3-S1), which will drive the full core and be co-simulated against the golden model. It shows real Chisel hardware playing while the core is being integrated.

## Run it live (6x6, about 1-3 s per move)

sbt only forwards the keyboard to a forked JVM, so keep the two `set` commands and do not use `-batch`. The command works in PowerShell, cmd and bash:

```text
sbt "set Test / run / fork := true" "set Test / run / connectInput := true" "Test/runMain minesweeper.R2DemoConsole 6 6 5 2"
```

With seed 2, `r 2 2` then `a` wins the game through the solver's hints alone.

Arguments: `cols rows mines seed`. The first run compiles the circuit, which takes a few seconds.

| Command | Effect |
| --- | --- |
| `r <row> <col>` | reveal (rows and columns start at 0) |
| `f <row> <col>` | flag / unflag |
| `a` | autoplay: reveal every cell the solver proved safe, repeat until none is left |
| `h` | show / hide hints |
| `n [seed]` | new game |
| `q` | quit |

Board: `.` hidden, `F` flag, `0`-`8` revealed, `s`/`m` solver hint (safe/mine), `*` mine, `X` the mine you hit. Each move prints the RevealEngine result (cells, layers = clock cycles) and the Solver result (hints, cycles to the fixpoint).

### Why it is slower on 9x9

Each move is a cycle-accurate simulation of the circuits in treadle, ChiselTest's default simulator. Its cost per simulated cycle grows roughly with the square of the cell count:

| Board | Solver | RevealEngine |
| --- | --- | --- |
| 4x4 | 10 ms | 1.2 ms |
| 6x6 | 41 ms | 5.3 ms |
| 9x9 | 233 ms | 18 ms |

These are measured simulation times per cycle on a laptop. A 9x9 move takes about 10-13 s of simulation. On the FPGA, one cycle is 10 ns at 100 MHz. That is why the live demo uses 6x6.

## Backup: recorded 9x9 game

[R2_DEMO_9x9_transcript.txt](R2_DEMO_9x9_transcript.txt) is the unedited output of:

```powershell
sbt 'Test/runMain minesweeper.R2DemoConsole 9 9 10 1 --script "r 4 4; a; q"'
```

The first click reveals 64 cells in 9 cycles. The solver then proves all 10 mines and 7 safe cells, and autoplay wins without guessing. Scripted mode also works with `-batch`.

## Suggested 2-minute demo

1. `r 2 2`. The first click is always safe; point out the cascade size and that layers = clock cycles.
2. Read the `s`/`m` hints: they were proven by the hardware solver, which only sees revealed numbers (never mines or flags).
3. `a`. Autoplay follows the safe hints. It either wins, or stops where single-clue logic is stuck: that motivates solver v2 (R2-S4).
4. `n`, `r 2 2`, then step on an `m` cell to show the loss display.

## How it is checked

`R2DemoConsoleTest` (6x6, about 1 minute):
- the first click never loses, and cascades equal the independent BFS of RevealEngineTest;
- hints equal the reference v1 fixpoint and are never wrong;
- flags block reveals, the game is won by opening every safe cell and lost on a mine;
- autoplay never steps on a mine over 12 seeded games (it won 7 of 12 without guessing).
