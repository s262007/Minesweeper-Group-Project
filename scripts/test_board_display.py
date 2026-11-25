"""Tests of the PC display's protocol client against the contract fixtures (docs/protocol).

    python -m unittest scripts/test_board_display.py

A fake serial link answers each request with the device lines of a fixture session; the client
must write exactly the fixture's request lines and accept every snapshot. Requests the client
never writes (malformed lines, split writes, identical retries) are skipped.
"""

import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import board_display as bd

FIXTURES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "protocol")


def load(name):
    """[(request line, [reply lines])] for the complete requests of a fixture."""
    out = []
    with open(os.path.join(FIXTURES, name + ".txt"), encoding="ascii") as f:
        for line in f.read().splitlines():
            if line.startswith("> "):
                out.append([line[2:], []])
            elif line.startswith(">| "):
                out.append([None, []])
            elif line.startswith("< "):
                out[-1][1].append(" ".join(t.lstrip("~") for t in line[2:].split(" ")))
    return out


class FakeLink:
    def __init__(self):
        self.expected = None
        self.replies = []
        self.written = []

    def write(self, data):
        line = data.decode("ascii")
        self.written.append(line)
        assert line == self.expected + "\n", f"client wrote {line!r}, fixture expects {self.expected!r}"

    def readline(self):
        return (self.replies.pop(0) + "\n").encode("ascii") if self.replies else b""


def call(board, request):
    """Make the client send the fixture's request through its own API; None if it never would."""
    w = request.split(" ")
    if "\\" in request or len(w) < 2 or len(w[1]) != 4 or w[1] != w[1].upper():
        return None
    try:
        board.seq = int(w[1], 16)
        if w[0] == "HELLO" and len(w) == 2:
            return lambda: board.hello()
        if w[0] == "GET" and len(w) == 2:
            return lambda: board.get()
        if w[0] == "NEW" and len(w) == 5:
            return lambda: board.new_game(int(w[3]), int(w[4]), int(w[2], 16))
        if w[0] in ("REVEAL", "FLAG") and len(w) == 4 and w[2].isdigit() and w[3].isdigit():
            fn = board.reveal if w[0] == "REVEAL" else board.flag
            return lambda: fn(int(w[2]), int(w[3]))
    except ValueError:
        return None
    return None


class ClientAgainstFixtures(unittest.TestCase):
    def replay(self, name):
        link = FakeLink()
        board = bd.Board(link, progress_timeout=0.1, total_timeout=1.0)
        checked = 0
        for request, replies in load(name):
            if request is None:
                continue
            fn = call(board, request)
            if fn is None:
                continue
            link.expected, link.replies = request, list(replies)
            if replies[0].startswith("ERR"):
                with self.assertRaises(bd.DeviceError):
                    fn()
            else:
                result = fn()
                if isinstance(result, bd.Snapshot):
                    self.assertEqual(result.revision, int(replies[-1].split(" ")[2]))
            self.assertEqual(link.replies, [], f"{name}: {request}: lines left unread")
            checked += 1
        return checked

    def test_every_fixture(self):
        names = sorted(f[:-4] for f in os.listdir(FIXTURES) if f.endswith(".txt"))
        self.assertGreaterEqual(len(names), 8)
        total = 0
        for name in names:
            total += self.replay(name)
        self.assertGreater(total, 40)

    def test_hello_reads_the_presets(self):
        link = FakeLink()
        board = bd.Board(link)
        request, replies = load("hello")[0]
        link.expected, link.replies = request, list(replies)
        board.seq = 1
        info, presets = board.hello()
        self.assertEqual((info["maxCols"], info["maxRows"]), (30, 24))
        self.assertEqual([(p["cols"], p["rows"]) for p in presets], [(8, 8), (9, 9), (16, 16), (30, 16), (30, 24)])

    def test_hello_accepts_hex_metrics_end_marker(self):
        link = FakeLink()
        board = bd.Board(link)
        request, replies = load("hello")[0]
        link.expected = request
        link.replies = list(replies[:-1]) + ["ENDX 0001 00000000"]
        board.seq = 1
        info, presets = board.hello()
        self.assertEqual(info["version"], "1.2.0")
        self.assertEqual(len(presets), 5)
        self.assertEqual(link.replies, [])


class SnapshotChecks(unittest.TestCase):
    GOOD = ["SNAP 0002 2 PLAYING 0 3 2 1 1 1 5 2 2 1",
            "ROW 0002 0 0 1 H", "HINT 0002 0 ..M",
            "ROW 0002 1 0 1 H", "HINT 0002 1 ..S",
            "END 0002 2"]

    def test_accepts_a_coherent_snapshot(self):
        s = bd.parse_snapshot(self.GOOD, 2)
        self.assertEqual((s.cols, s.rows, s.state, s.mines_left), (3, 2, "PLAYING", 1))
        self.assertEqual(s.safe_hints(), [(1, 2)])

    def test_accepts_hex_metrics_snapshot_and_signed_mines_left(self):
        lines = ["SNAPX 0002 00000002 PLAYING 0 02 02 001 FFF 0 00000005 00000002 002 00000001",
                 "ROW 0002 00 F F", "HINT 0002 00 ..",
                 "ROW 0002 01 H H", "HINT 0002 01 ..",
                 "ENDX 0002 00000002"]
        s = bd.parse_snapshot(lines, 2)
        self.assertEqual((s.revision, s.mines_left), (2, -1))
        self.assertEqual((s.generation_cycles, s.reveal_cycles, s.reveal_layers,
                          s.solver_cycles), (5, 2, 2, 1))

    def bad(self, i, line):
        lines = list(self.GOOD)
        lines[i] = line
        with self.assertRaises(bd.ProtocolError):
            bd.parse_snapshot(lines, 2)

    def test_rejects_broken_snapshots(self):
        self.bad(1, "ROW 0002 0 0 1 M")
        self.bad(5, "END 0002 3")
        self.bad(0, "SNAP 0002 2 PLAYING 0 3 2 1 0 1 5 2 2 1")
        self.bad(2, "HINT 0002 0 S.M")
        self.bad(3, "ROW 0002 1 0 1")
        self.bad(0, "SNAP 0003 2 PLAYING 0 3 2 1 1 1 5 2 2 1")


if __name__ == "__main__":
    unittest.main()
