# UART protocol v2 fixtures

Owner: **R3**. These transcripts are shared contract fixtures, so all three members approve them (plan v1.1, section 9). They are written from `docs/CONTRACT.md` 1.2.0 sections 3, 4.1 and 6, **not** generated from the hardware serializer. R1's bridge tests, R3's host and the FPGA replay all check against the same files.

Each file is one session that starts from **physical reset**: 9x9, 14 mines, seed 0001, revision 0.

## Format

| Line | Meaning |
|---|---|
| `# text` | Comment |
| `> text` | Host writes `text` followed by LF |
| `>\| text` | Host writes `text` with no LF (one part of a split request) |
| `< text` | Device replies with this line |

`>` text is printable ASCII and may contain the escapes `\r`, `\\` and `\xHH`.

In a `<` line, a token written as `~value` is **implementation-defined**. Against a real device or the Chisel bridge, it matches any token. The mock device sends `value`. Loose tokens are used only for values the contract leaves to the implementation:

- `generationCycles` (number of LFSR attempts plus control overhead)
- `solverCycles` and the solver's HINT masks
- the solver mode and lane count in INFO
- `hintsValid` where the contract does not settle it (see the questions below)

Replay still checks every reply structurally, including loose ones. Every SNAP..END group must also form a coherent snapshot: rows in ascending order, `cols` tokens per row, matching sequence and END revision, no `M` before a loss, no hints while `hintsValid=0` or on revealed cells, and `minesLeft = mines - flags`.

## Sessions

| File | Covers |
|---|---|
| `hello.txt` | INFO, five PRESET lines, no ACK; HELLO does not change the revision |
| `reset_get.txt` | Default all-hidden 9x9 after reset |
| `errors.txt` | BAD_CMD, BAD_ARG (lowercase hex, extra/missing fields, signs, oversized numbers), RANGE (incl. storage padding), BAD_SEED, BAD_PRESET, BAD_MINES, NEW validation order, no mutation after rejection |
| `line_framing.txt` | CRLF, 64-byte limit (64 accepted, 65 is LINE_TOO_LONG), recovery, split writes |
| `flags_rect.txt` | Zero-based (row, col) on 30 columns x 16 rows, flag toggle, padding rows |
| `overflag.txt` | Negative `minesLeft` |
| `retry.txt` | Identical retry replays without re-toggling; same sequence with different content gives SEQ |
| `reveal.txt` | First-click generation and cascade (seed 007C, 8x8), flags, no-ops, loss display, terminal no-ops |

`ProtocolFixtureTest` checks that the mine positions shown in `reveal.txt` after the loss equal `Lfsr16.placeMines` (CONTRACT 4.1). It also checks every number shown against R3's independent adjacency count.

## Not covered here

These need a cycle-level testbench or hardware rather than a host transcript:

- BUSY
- reset during a reply
- byte backpressure
- RX framing and overflow errors
- baud tolerance
- disconnect/reconnect

They belong to R1's bridge tests (R1-S2) and R3's UART and replay stories (R3-S2/S3).

## Assumptions to confirm before approval

The fixtures make these choices where CONTRACT 1.2.0 is silent. Each one needs a decision, and then either a fixture edit or a contract note.

1. **FLAG and the solver.** Does an accepted FLAG rerun the solver? Section 5.2 zeroes the generation/reveal metrics for a flag but says nothing about `solverCycles` or `hintsValid`, so the fixtures leave both loose.
2. **Retry replay.** An identical retry after completion replays the whole reply, *including* `ACK`.
3. **LINE_TOO_LONG** always uses `----`, even when the start of the line holds a readable sequence.
4. **Unknown opcode.** `JUMP 0001` returns `ERR 0001 BAD_CMD`: the sequence is still read from the second token.
5. **Field count.** Extra or missing fields after a known opcode return BAD_ARG, not BAD_CMD.
6. **After a loss.** `hintsValid` and `solverCycles` are loose. The losing click reports zero generation and reveal metrics, because the Core handles a mine click without RevealEngine.
7. **RX errors.** Whether RX_FRAME and RX_OVERFLOW produce an `ERR ----` line after resynchronising at LF is not covered.
