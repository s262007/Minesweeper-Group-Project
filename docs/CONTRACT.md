# Hardware Minesweeper - shared contract

Version **1.2.0** | 15 October 2026 | Owner: **R1**

**Draft for R2 and R3 review.** Freeze this file and the Scala interfaces together after review.

## 1. Scope and ownership

The FPGA has storage for **30 columns x 24 rows**. Players choose one of five grid presets and a mine count when starting a game. The minimum is **14 mines**. The FPGA runs the game and solver; a PC sends commands and displays results over UART.

Reset defaults to **9x9, 14 mines, seed 0001**. Confirm these defaults and the LFSR sequence during review. We will choose the FPGA board after checking availability, then add its clock, pin mapping and timing constraints.

| Files / modules | Owner | Reviewer |
|---|---|---|
| `docs/CONTRACT.md`, `Params.scala`, `Interfaces.scala` | R1, with both teammates' agreement | R2 and R3 |
| `MineGen`, `Board`, `MinesweeperCore`, `UartGameBridge`, `FpgaTop`, emit/build/benchmark scripts, CI | R1 | R2 |
| `RevealEngine`, `Solver`, formal harnesses | R2 | R3 |
| `UartRx`, `UartTx`, host client/display, golden model, model solver, oracle, co-simulation, properties, hardware replay, README/AI log | R3 | R1 |

Each owner writes unit tests for their modules. R3 writes the independent reference and integration tests. `ContractTest.scala` checks the shared settings and interfaces.

Use the existing S0-S6 story IDs, tests-first workflow and one branch/PR per story. Split large stories into child cards. Contract changes need a `contract` PR, both teammates' approvals and the author's sign-off. Everyone then updates to the same revision. Record AI assistance in `docs/AI_USAGE.md`, and review code before merging.

## 2. Build compatibility and package

Use package **`minesweeper`** for new project code. Keep the template's example package `empty` separate. Source locations:

- `src/main/scala/minesweeper/Params.scala`
- `src/main/scala/minesweeper/Interfaces.scala`
- `src/test/scala/minesweeper/ContractTest.scala`

Use the starter's current versions: Scala **2.13.14**, Chisel **3.6.1**, the matching `chisel3-plugin`, and ChiselTest **0.6.2**. Keep a version upgrade separate from the interface work. CI uses JDK 11; local test details are in `CONTRACT_VALIDATION.md`.

The project branch is `main`. Run CI on pushes to `main` and pull requests targeting `main`.

## 3. Dimensions, presets and legal settings

All written grids use **columns x rows**. Coordinates and method arguments use **(row, col)**, both zero-based.

| Preset ID | Columns | Rows | Cells | Minimum mines | Maximum mines |
|---|---:|---:|---:|---:|---:|
| 0 | 8 | 8 | 64 | 14 | 55 |
| 1 | 9 | 9 | 81 | 14 | 72 |
| 2 | 16 | 16 | 256 | 14 | 247 |
| 3 | 30 | 16 | 480 | 14 | 471 |
| 4 | 30 | 24 | 720 | 14 | 711 |

The maximum reserves nine cells so first-click protection works for every click, including an interior click. A corner's smaller protected area does not increase the allowed mine count. In general `maxMines = cols*rows - min(3,cols)*min(3,rows)`.

`MsParams` describes generated storage capacity and supported presets. `GameSettings(presetId, mines, seed)` describes one runtime game. The active dimensions and count are latched together on a valid NewGame. Settings cannot change mid-game. Hardware `ActiveConfig` includes preset ID, columns, rows and mines; internal producers must ensure dimensions match that ID's preset.

Validate, in order: supported preset, mine count within that preset's range, nonzero 16-bit seed. A rejected NewGame leaves the existing game/configuration unchanged. UART parsers reject large/negative/unparseable values before assigning narrow UInt fields; never truncate a request into a legal one.

`fromDensity` is a benchmark helper, not a production input. It accepts finite 0..1 and rounds down `density*activeCells`, then validates the resulting count. It may reject low densities that produce fewer than 14 mines. No silent clamping.

### 3.1 Capacity, indexing and masking

The physical index is always **`row * p.maxCols + col`**. With the agreed capacity, row 1 / col 0 is index 30 even in an 8x8 game. Vector length and mask width stay at 720. UART ROW lines contain only the selected number of columns; their order is compact for display, not the internal storage stride.

Cell `i` is active iff `i < p.cells`, `i/p.maxCols < activeRows` and `i%p.maxCols < activeCols`. A neighbour must be active and differ by at most one row and column, excluding itself. Never connect the right edge of a selected grid to padding or the next row. All mine, reveal, flag and hint writes are masked to active cells. Inactive public/internal fields and hints are zero/Hidden and are excluded from counts, solver scans and win detection.

`capacityNeighbors` may generate static wiring candidates. Filter them with active dimensions in hardware. R3's independent checker calculates neighbours from coordinate loops; it must not use `neighborIdx`/`capacityNeighbors` to establish the correctness of the same wiring.

### 3.2 Widths

Default capacity yields `rowW=5`, `colW=5`, dimension-count widths `rowsW=5`, `colsW=5`, `idxW=10`, `cntW=10`, `presetW=3`, `minesLeftW=11`. Dimension counts must represent 24/30, not only the last coordinate. Counts must represent all 720 cells. `minesLeft` is signed. Seeds/sequences are 16-bit; revision and operation counters are 32-bit. Arithmetic sums/subtractions are widened before comparison or assignment.

Production minimum stays 14. `MsParams.forVerification(cols,rows)` deliberately permits tiny grids and a zero minimum for standalone tests/formal harnesses. It is never a production selectable mode and must not be instantiated in the final `FpgaTop`. Harnesses using a different parameter set are labelled separately from production evidence.

## 4. Game rules and mine placement

Reset selects the documented default game, revision 0, no mines placed yet, no flags/reveals/hints, and Idle. NewGame selects its requested settings, clears the board, counters and all solver knowledge, and returns to Idle. Mines are generated only for the first accepted reveal of a hidden unflagged cell.

First reveal protects that cell plus its in-bounds neighbours. User flags placed before the first reveal remain flags during mine generation; they do not influence where mines are placed. A reveal of a flagged cell is a no-op and does not trigger generation.

A safe nonzero cell reveals only itself. A zero reveals its connected zero region and the bordering numbered cells by frontier expansion. Flags block the expansion. A mine reveal enters Lost; all active mines become visible. Revealing all active safe cells enters Won. Won and Lost persist until NewGame. Reveal/Flag in those terminal states return successful no-ops. A caught internal solver inconsistency enters Error, hides hints and requires NewGame/reset to resume.

Flag toggles only a hidden active cell in Idle or Playing. Flags on revealed cells are no-ops. Over-flagging is allowed: `minesLeft = configuredMines - activeFlagCount` may be negative. A safe hint on a wrongly flagged cell is valid; the player must unflag it before revealing. Player flags never become trusted solver mines.

Invalid active coordinates return Range and leave state unchanged. Repeated safe reveals and flagged-cell reveals are no-ops. An accepted no-op does not increment revision or replace the previous operation counters. Each successful state-changing command increments revision once (modulo 2^32), including NewGame. Errors on request validation do not increment it. The initial reset is the only reset-to-zero of revision. Hint changes occurring within a command belong to that command's single revision.

### 4.1 Exact PRNG and placement sequence

The executable specification is `Lfsr16` in `Params.scala`: right-shifting Galois LFSR, XOR mask **0xB400**. For state `s`, `next=(s >>> 1) XOR (0xB400 if s bit 0 is one else 0)`. Seeds 0001..FFFF are valid; 0000 is rejected. The nonzero-state period is 65535.

For each candidate, use the **current** state before stepping: `compact=(s-1) % activeCells`. Convert it to active row/col using **activeCols**, then to a physical index using **maxCols**. Reject duplicates and protected cells. Step after every candidate, including a rejected one. Stop when exactly the requested count is placed. This mapping is deterministic; uniform mine-layout distribution is not claimed.

Process one candidate per hardware clock. Every active candidate occurs within a full LFSR period, so legal placement needs at most 65535 candidate attempts. Allow a documented two-cycle control overhead in the initial MineGen test bound. Do not assume a 720-cycle generation bound. Zero-mine verification requests may complete without candidate attempts. R3 checks exact mine count, protected cells, no padding and determinism independently in addition to sequence agreement.

## 5. Interfaces and timing

`Interfaces.scala` is the authoritative field/type list. Directions below are from the perspective of the named module. Standard Chisel `clock` and `reset` belong to modules; these bundles do not invent board clocks.

### 5.1 General handshake rule

Transfers occur only on the rising edge where `valid && ready` (`fire`) is true. Producers hold valid and bits stable until transfer. A module accepts at most one operation at a time. `clear` dominates start, drives start.ready low, resets local knowledge/counters and cancels any local work. Normal Core issues clear only at controlled boundaries; physical reset may abort everything. A start request carries a copy of its validated ActiveConfig.

The Core freezes the relevant board view for a Solver run. Reveal reads a board that may acquire its own reveal writes, but no external command or mine/flag change can occur during the operation. `done` on MineGen/Reveal/Solver is one cycle; last results/counters remain stable until clear or the next start. Owners must not depend on another module sampling an unlatched one-cycle public command result.

### 5.2 CoreIO - R1

Inputs: `cmd: Flipped(Decoupled[CmdBundle])`, and backpressure on `result`. CmdBundle has kind, row, col, preset, mines, seed. Only NewGame uses preset/mines/seed; Reveal and Flag use row/col.

Outputs: `result: Decoupled[CommandResult]`, active config, state, busy, settled, public cells, hints/hintsValid, signed minesLeft, exploded physical index, revision, generationCycles, revealCycles, revealLayers and solverCycles. `exploded.valid` is false except for a lost game and persists until NewGame/reset.

One accepted command produces exactly one result, including a no-op or a rejected semantic value presented directly by a testbench. Validation precedes state mutation. Status values are Ok, BadPreset, BadMines, BadSeed, Range, BadCommand and InternalError. `changed` tells whether the externally visible game changed. InternalError is a detected design/invariant failure, not a user-argument error; it may follow partial processing and must never certify hints as sound.

`busy` means the command is executing; `settled` means a stable public view is available. At completion, result.valid rises and busy falls. While result.valid is high, config/state/public cells/hints/metadata/result bits remain stable until result.fire. cmd.ready stays low while executing or holding a result. The UART bridge keeps result.ready low throughout response serialization and asserts it only after its final END line has been accepted into the TX stream. No new request can mutate the snapshot.

Direct Chisel tests set result.ready high when they want immediate completion consumption. Sampling one cycle after busy falls is insufficient if a new command has already been admitted; use the result handshake. After a command with no internal error, the completion state is Idle, Playing, Won or Lost. Error is a recoverable diagnostic state via NewGame/reset. Transient states Generating, Revealing and Solving are not streamed as finished snapshots.

The four operation metrics describe the most recent successful state-changing command. Start those metrics at zero for such a command, then fill only stages actually executed: generationCycles from MineGen, revealCycles/revealLayers from RevealEngine, solverCycles from Solver. A flag command therefore has zero generation/reveal metrics. NewGame leaves all four zero. Rejected requests and successful no-ops preserve the previous metrics. These counters exclude UART transmission and Core orchestration overhead; measure end-to-end command time separately. Hint validity is cleared before any actual board change and restored only after a successful solver run on the new snapshot.

### 5.3 BoardIO - R1

Inputs: active config, clear, Valid loadMines mask, revealSet mask, revealAllMines, Valid flagToggle index. Outputs: private CellView vector, RevealCellView vector, SolverCellView vector, active flag count and revealed-safe count.

CellView has active/mine/revealed/flagged/adj. RevealCellView omits mine truth but includes hidden adj for flood-fill logic. SolverCellView has only revealed/adj: adj must be zero unless revealed and safe, and inactive cells are zero. Never wire raw hidden adjacent counts into the solver.

Writes occur on rising edges. Clear has highest priority. Outside clear, Core must drive at most one write action per cycle (load, nonzero revealSet, revealAllMines or flagToggle). loadMines updates only mine bits and preserves pre-first-click flags. revealSet does not reveal flagged cells; Core deliberately handles an unflagged losing click. revealAllMines exposes all active mines after loss; flags on such mines are cleared so revealed/flagged do not overlap. No other reveal write changes flags. Flag/reveal actions ignore inactive cells defensively; Core rejects their external coordinates. Adjacent counts use only active mines and are 0..8.

### 5.4 MineGenIO - R1

`start` request: config, seed, safeIdx. Inputs also include clear. Outputs: busy, done, a capacity-width mine mask and cycles. SafeIdx must identify an active cell. Output has exactly config.mines set bits on done, none in padding or the protected region. Seed/algorithm follow section 4.1. The mask remains stable until clear/new start.

### 5.5 RevealIO - R2

`start` request: config and physical idx. Other inputs: clear and RevealCellView vector. Outputs: revealSet, busy, done, cycles and layers. Core starts only a hidden, unflagged, active safe cell and handles direct mine clicks itself.

Use a registered frontier/visited set. In the cycle immediately following start.fire, revealSet is the clicked bit. Board commits that first layer on the next rising edge. Each following cycle emits the next nonempty BFS layer from zero cells in the previous layer; numbered cells reveal but do not expand. A private visited mask prevents revisits independently of Board's update timing. Do not use a combinational feedback path through revealSet and Board.

After the final frontier is committed, done pulses, busy falls and revealSet is zero. layers counts nonempty layers, starting at 1 for a valid click. On an empty verification board clicked at a corner, layers=max(activeRows,activeCols), using eight-neighbour connectivity. cycles counts clocks from start.fire to completion, excluding the admission edge and including the edge committing the last layer. Under this protocol cycles equals layers for the required one-ring-per-cycle implementation. Core can report its additional orchestration/generation time separately. Formal/test bounds may allow cells+2 for control checks, but the layer-equality acceptance test remains required.

### 5.6 SolverIO - R2

`start` request: config. Other inputs: clear and masked SolverCellView. Outputs: hints, valid, busy, done, contradiction and cycles. Capture or hold the input snapshot for the full run. New start or clear invalidates previous hints; successful completion sets valid and pulses done. valid and final hints remain until clear/new start. No-progress is successful completion with an empty hint mask, never an endless busy state.

On inconsistent constraints, set contradiction and pulse done, with valid=false and zero hints. Core treats this as InternalError. Every hint must be active, unrevealed and sound; safe/mine bits may not both be true. Hints may refer to flagged cells because flags are not solver inputs. Unused adj fields are zero, preventing deductions from hidden truth.

v1: for each revealed clue, count already deduced mines M and unresolved neighbours U, excluding known safe cells. Residual `k=clue-M`. If k=0, U is safe; if k=|U|, U is mined. Check arithmetic consistency before unsigned subtraction. Iterate until knowledge reaches a fixpoint; a deduced safe cell does not acquire a new clue until actually revealed.

v2: retain v1 and compare normalized constraints. For A subset B, derive B-A with residual b-a. For partial overlap define I=A intersect B, X=A-B, Y=B-A. Bounds are L=max(0,a-|X|,b-|Y|), U=min(|I|,a,b). If L=U=q, derive counts q for I, a-q for X and b-q for Y and apply zero/all tests. Include a genuine non-subset regression. Example: A={i1,i2,x1,x2},a=1; B={i1,i2,y},b=2 implies x1/x2 safe, y mined. Test the kernel and a valid board realization; kernel tests alone are not board integration evidence.

SolverConfig selects v1/v2 and sweep lanes at elaboration; 1 <= lanes <= p.cells. Different lane counts must give the same final fixpoint even if cycle counts differ. Owners derive a bound including pair sweeps, knowledge iterations and FSM overhead; do not reuse the reveal bound for the solver.

### 5.7 UART bytes and game bridge

UartRxIO: rx input; Decoupled 8-bit output; one-cycle framingError and overflow indicators. UartTxIO: Decoupled 8-bit input, idle-high tx output and busy. RX uses synchronization and 16x sampling. FIFO depth defaults to 128 and must hold at least a full 64-byte request. On overflow, signal it and discard/resynchronize the current request rather than silently executing truncated data.

`UartConfig(clockHz=...)` requires an explicit clock; baud defaults to 115200, with nearest integer 16x sample divider and less than 2% nominal baud error. This is a shared timing convention; validate actual RX/TX tolerance with a serial testbench and the selected board. A single clock with enables is preferred to a fabric-generated UART clock.

UartGameBridgeIO has RX/TX byte channels, RX error inputs and a flipped CoreIO. R1 owns packet parsing, command admission and snapshot serialization; R3 owns the byte hardware and host. FpgaTopIO has logical rx/tx/won/lost pins. Board-specific mapping and clock/reset conditioning are deferred.

## 6. UART protocol version 2

Protocol version **2** includes the runtime preset and mine count in NEW. Hosts must check the version before playing. Use ASCII tokens, one space between tokens and LF terminators; accept optional CR before LF. Requests are at most 64 bytes including the terminator; host response lines support at least 256 bytes. Decimal values are unsigned except the response minesLeft field. No signs or exponent notation on inputs. Sequence and seed are each four uppercase hex digits. Parse and check widths before hardware admission.

### 6.1 Requests and replies

| Request | Reply |
|---|---|
| `HELLO ssss` | INFO, one PRESET per supported grid, END; no ACK |
| `NEW ssss hhhh preset mines` | ACK, completed snapshot, END |
| `REVEAL ssss row col` | ACK, completed snapshot, END |
| `FLAG ssss row col` | ACK, completed snapshot, END |
| `GET ssss` | ACK, current stable snapshot, END |

Example: `NEW 0001 0042 3 80` selects 30 columns x 16 rows, 80 mines, seed hexadecimal 0042. `NEW 0002 0001 0 13` is rejected as BAD_MINES; it does not change the running game.

ACK format: `ACK ssss`. It acknowledges admission, not successful completion. Semantic validation normally happens in the bridge before ACK; defensive Core errors can still finish with ERR afterward. Every transaction ends in either END or ERR. No unsolicited snapshots are sent.

HELLO output:

```text
INFO ssss 2 1.2.0 maxCols maxRows minMines V1|V2 lanes
PRESET ssss id cols rows minMines maxMines
END ssss revision
```

There are five PRESET lines for the production build. HELLO discovers capacity/presets/mode and starts a fresh host retry session only when the bridge is idle. It does not change the game or revision. The host follows reconnect with GET rather than blindly repeating an uncertain mutation.

### 6.2 Snapshot format

```text
SNAP ssss revision state preset cols rows mines minesLeft hintsValid generationCycles revealCycles revealLayers solverCycles
ROW ssss row token token ... token
HINT ssss row mask
END ssss revision
```

One ROW and one HINT for each active row, ascending order; send each ROW followed by its HINT. A ROW contains exactly activeCols tokens. A HINT mask contains exactly activeCols characters. Public tokens: H hidden, F flagged, 0..8 revealed number, M mine after Lost. Hints: '.' none, S safe, M solver-mine. Only stable states IDLE, PLAYING, WON, LOST, ERROR are sent. Integer fields are decimal; hintsValid is 0/1. A zero hintsValid suppresses all hint characters.

Core cells are PublicCellView, not private CellView. For Hidden, Flagged, Mine and inactive cells, adj=0. A mine cannot be transmitted as M before Lost. Exposing mine positions on loss is deliberate. Revealed safe numbered cells have kind Number and adj 0..8. No hidden adjacent counts, seed or test-load data is serialized. Seed is known to a player who selected it; the design does not claim adversarial anti-cheating security, only that solver/display paths do not use truth improperly.

Hold Core's result while streaming its reply. For GET/HELLO, the bridge admits no concurrent mutation. Commit a displayed snapshot only after sequence, dimensions, rows, hint lengths and matching END revision validate. Ignore capacity padding entirely. Report computation counters separately from the transmission duration/byte count.

### 6.3 Errors, retries and recovery

`ERR ssss code` completes rejection. Codes: BUSY, BAD_CMD, BAD_ARG, BAD_PRESET, BAD_MINES, BAD_SEED, RANGE, LINE_TOO_LONG, SEQ, INTERNAL, RX_FRAME, RX_OVERFLOW. Use `----` when no sequence can be recovered. Reject seed zero as BAD_SEED. A parsed nonnegative coordinate outside the active grid is RANGE. Malformed/negative/oversized numeric text is BAD_ARG. Invalid settings and request errors do not mutate the game.

Only one host request may be outstanding. Host increments sequences (wrapping after FFFF) after completed transactions. The bridge remembers the latest admitted request's sequence and full parsed identity. Identical retry while executing returns BUSY without a second execution; after completion it replays the same stable reply without repeating the action. Reuse of that cached sequence with different contents returns SEQ. Any new admitted transaction replaces the cache; arbitrary older requests are not replayable. Do not automatically retry a timed-out FLAG with a new sequence.

NewGame while busy returns BUSY and does not abort the current operation. Physical reset may abort, clears the retry state and restores the default game. If execution outcome is uncertain after disconnect/reset, HELLO and GET recover the observed state before further moves. If the board is still busy, wait/retry the recovery read.

An oversized input line is discarded through LF. RX framing/overflow errors discard the partial request and resynchronize at LF. Use configurable host progress and total-operation timeouts; initial budgets are 2 seconds without a complete valid response line and 10 seconds total. Measure the maximum-config solver/generation budget before freezing those limits. This version has structural validation and UART framing, not an application checksum; it does not claim to detect every possible bit corruption.

## 7. Verification, independence and test-only access

`ContractTest` covers agreed settings, inclusive mine bounds, invalid seeds/presets, derived widths, physical stride/padding, all active neighbour sets, density conversion, LFSR period and placement invariants, UART divisor constraints and interface elaboration. It does not prove any unimplemented game module.

R3's plain Scala reference is separate from Chisel control/inference code. Compare after every completed command: config, stable state, every public cell, flag count, hints/valid, revision and applicable counters. Keep private-layout checks in simulation only. Hardware-in-loop compares the same production-valid seed/preset/mine-count/move trace against the model. Test boundary clicks on all five presets and multiple counts including 14 and the legal maximum; test switching from large to small so stale padding cannot leak or prevent winning.

Protocol fixtures cover all opcodes, partial lines, LF/CRLF, parsing overflow, invalid preset/count/coordinates, BUSY, duplicate FLAG, reconnect, reset mid-reply, byte backpressure, frame errors and coherent snapshots. Verify wrong user flags do not change deductions. Verify empty hint results complete, and NewGame clears all previous hints/config-dependent state.

TestLoadRequest belongs only to a simulation/formal wrapper or the R3 adapter. It includes config, seed, private mine/reveal/flag masks and state. Test wrapper admission occurs only when the Core is idle with no pending result, recomputes adjacency, clears stale solver knowledge and enforces consistent masks/counts/state. It must not add a production CoreIO or UART command. FpgaTop elaboration must expose no test-load/truth port. Arbitrary consistent symbolic harnesses are separate from production NEW limits.

R2 records property, dimensions, mode/lanes, assumptions, bound, tools, runtime and commit for each formal result. Prove safe/mine hints against symbolic truth under consistent revealed clues; review assumptions and check start/completion/deduction covers plus targeted mutation failures. Monotonic reveal excludes NewGame/reset; mine-reveal safety excludes explicit losing-click/loss-display behaviour. A small generated 3x3 protected game can have zero mines, so use arbitrary-layout standalone harnesses and nontrivial larger end-to-end cases. No bounded result establishes correctness for every size and all time.

## 8. Benchmark and board selection implications

Use the five active presets with baseline 14 mines and representative legal higher counts; for example floor(25% of cells) and floor(35% of cells), validated before the run. Include solver v1, v2 lane 1 and v2 lane 4, plus small equivalence tests for other lanes. Record actual mine count and seed, computation cycles, bytes and UART latency, soundness failures, no-guess wins/stalls and oracle completeness with timeout denominators.

Active size changes in one bitstream do **not** change its static LUT/FF/BRAM utilization. Compare generated capacities/architectures in separately labelled synthesis experiments; compare active presets for runtime latency and solver outcomes. Keep the maximum 30x24 build as a hardware-fit target, not a verified fit claim. If the available FPGA cannot fit it, discuss an architecture/board change with the team instead of silently removing agreed presets.

## 9. Before freezing

R2 reviews the engine timing and solver rules. R3 reviews the public state, UART protocol and model comparison points. All three compile the same revision before starting dependent modules.

Remaining S0 tasks are module stubs, protocol/model fixtures, formal-tool smoke checks, backlog setup and board availability. The game engines and FPGA build follow in later stories.
