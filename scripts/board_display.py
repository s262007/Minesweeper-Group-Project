"""PC display for the FPGA Minesweeper (UART protocol version 2, CONTRACT section 6).

    python scripts/board_display.py --port COM5                    # the Nexys4 DDR board
    python scripts/board_display.py --port socket://localhost:7777 # sbt "Test/runMain minesweeper.SimBoardServer 7777"
    python scripts/board_display.py --port COM5 --text             # terminal version, no window

The window shows the board the FPGA sends: numbers, flags, and the solver's hints (green: safe,
red: mine). Left click reveals a cell, right click flags it. "Auto" reveals safe hints one by one
until none is left. The display never computes the game itself: every picture is a snapshot the
board sent, checked before it is shown (CONTRACT 6.2).

Needs Python 3 and pyserial (pip install pyserial). tkinter comes with Python.
"""

import argparse
import random
import sys
import time

import serial

VERSION = 2
STATES = ("IDLE", "PLAYING", "WON", "LOST", "ERROR")


class ProtocolError(Exception):
    """A reply that breaks CONTRACT 6, or no reply in time."""


class DeviceError(Exception):
    """The board answered ERR <code>."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


class Snapshot:
    """One checked SNAP..END group."""

    def __init__(self, fields, cells, hints):
        (self.revision, self.state, self.preset, self.cols, self.rows, self.mines, self.mines_left,
         self.hints_valid, self.generation_cycles, self.reveal_cycles, self.reveal_layers,
         self.solver_cycles, self.selection_mode, self.selected_preset, self.selected_row,
         self.selected_col, self.hardware_hints_enabled) = fields
        self.cells = cells
        self.hints = hints

    def safe_hints(self):
        return [(r, c) for r in range(self.rows) for c in range(self.cols)
                if self.hints[r][c] == "S" and self.cells[r][c] == "H"]


def _hex4(v):
    return f"{v:04X}"


def _parse_hex_signed(value, bits):
    parsed = int(value, 16)
    sign = 1 << (bits - 1)
    return parsed - (1 << bits) if parsed & sign else parsed


def parse_snapshot(lines, seq):
    """Check and decode the lines from SNAP to END (CONTRACT 6.2)."""
    s = _hex4(seq)
    head = lines[0].split(" ")
    hex_metrics = head[0] == "SNAPX"
    expected_tag = "SNAPX" if hex_metrics else "SNAP"
    if len(head) not in (14, 18, 19) or head[0] != expected_tag or head[1] != s:
        raise ProtocolError(f"bad SNAP line: {lines[0]!r}")
    try:
        if hex_metrics:
            revision = int(head[2], 16)
            mines_left = _parse_hex_signed(head[8], 12)
            counters = [int(x, 16) for x in head[10:14]]
        else:
            revision = int(head[2])
            mines_left = int(head[8])
            counters = [int(x) for x in head[10:14]]
        preset, cols, rows, mines = (int(head[4]), int(head[5]), int(head[6]), int(head[7]))
        hints_valid = int(head[9])
        if len(head) >= 18:
            selection_mode = head[14]
            selected_preset, selected_row, selected_col = map(int, head[15:18])
            hardware_hints_enabled = int(head[18]) if len(head) == 19 else None
        else:
            selection_mode, selected_preset, selected_row, selected_col = "?", 0, 0, 0
            hardware_hints_enabled = None
    except ValueError:
        raise ProtocolError(f"bad number in {lines[0]!r}")
    state = head[3]
    if (state not in STATES or hints_valid not in (0, 1) or
            hardware_hints_enabled not in (None, 0, 1) or selection_mode not in ("?", "S", "G") or
            (selection_mode != "?" and
             (selected_row < 0 or selected_row >= rows or selected_col < 0 or selected_col >= cols or
              selected_preset not in range(5)))):
        raise ProtocolError(f"bad state or hintsValid in {lines[0]!r}")
    if len(lines) != 2 + 2 * rows:
        raise ProtocolError(f"expected {rows} ROW/HINT pairs, got {len(lines) - 2} lines")
    cells, hints = [], []
    for r in range(rows):
        row = lines[1 + 2 * r].split(" ")
        hint = lines[2 + 2 * r].split(" ")
        if (len(row) != 3 + cols or row[:2] != ["ROW", s] or
                not row[2].isdigit() or int(row[2]) != r):
            raise ProtocolError(f"bad ROW {r}: {lines[1 + 2 * r]!r}")
        if (len(hint) != 4 or hint[:2] != ["HINT", s] or
                not hint[2].isdigit() or int(hint[2]) != r or len(hint[3]) != cols):
            raise ProtocolError(f"bad HINT {r}: {lines[2 + 2 * r]!r}")
        tokens = row[3:]
        for c, t in enumerate(tokens):
            if t not in ("H", "F", "M") and not (len(t) == 1 and "0" <= t <= "8"):
                raise ProtocolError(f"bad cell token {t!r} at ({r},{c})")
            if t == "M" and state != "LOST":
                raise ProtocolError("a mine is shown before a loss")
            h = hint[3][c]
            if h not in ".SM" or (h != "." and (not hints_valid or t not in ("H", "F"))):
                raise ProtocolError(f"bad hint {h!r} at ({r},{c})")
        cells.append(tokens)
        hints.append(hint[3])
    end = lines[-1].split(" ")
    expected_end = "ENDX" if hex_metrics else "END"
    try:
        end_revision = int(end[2], 16 if hex_metrics else 10) if len(end) == 3 else None
    except ValueError:
        end_revision = None
    if len(end) != 3 or end[0] != expected_end or end[1] != s or end_revision != revision:
        raise ProtocolError(f"END does not match: {lines[-1]!r}")
    flags = sum(row.count("F") for row in cells)
    if mines_left != mines - flags:
        raise ProtocolError(f"minesLeft {mines_left} is not mines {mines} - flags {flags}")
    return Snapshot([revision, state, preset, cols, rows, mines, mines_left, hints_valid] + counters +
                    [selection_mode, selected_preset, selected_row, selected_col,
                     hardware_hints_enabled], cells, hints)


class Board:
    """One connection to a board: requests, sequence numbers, checks and recovery."""

    def __init__(self, link, progress_timeout=2.0, total_timeout=10.0):
        self.link = link
        self.progress_timeout = progress_timeout
        self.total_timeout = total_timeout
        self.seq = 1
        self.info = None
        self.presets = []
        self.snapshot = None

    def _read_line(self, deadline):
        buf = b""
        while True:
            if time.monotonic() > deadline:
                raise ProtocolError("no complete reply within the total timeout")
            chunk = self.link.readline()
            if not chunk:
                if not buf:
                    raise ProtocolError("no reply line within the progress timeout")
                continue
            buf += chunk
            if buf.endswith(b"\n"):
                line = buf.decode("ascii", errors="replace").rstrip("\n")
                return line[:-1] if line.endswith("\r") else line

    def _transaction(self, text):
        """Send one request (text with '#' for the sequence) and return its reply lines."""
        seq = self.seq
        line = text.replace("#", _hex4(seq))
        self.link.write((line + "\n").encode("ascii"))
        deadline = time.monotonic() + self.total_timeout
        first = self._read_line(deadline)
        word = first.split(" ")
        if word[0] == "ERR":
            self.seq = (seq + 1) & 0xFFFF
            raise DeviceError(word[2] if len(word) == 3 else first)
        if word[0] not in ("ACK", "INFO") or word[1] != _hex4(seq):
            raise ProtocolError(f"unexpected first reply {first!r} to {line!r}")
        lines = [first]
        while True:
            reply = self._read_line(deadline)
            lines.append(reply)
            tag = reply.split(" ")[0]
            if tag == "ERR":
                self.seq = (seq + 1) & 0xFFFF
                raise DeviceError(reply.split(" ")[-1])
            if tag in ("END", "ENDX"):
                break
        self.seq = (seq + 1) & 0xFFFF
        return seq, lines

    def hello(self):
        seq, lines = self._transaction("HELLO #")
        info = lines[0].split(" ")
        if len(info) != 9 or int(info[2]) != VERSION:
            raise ProtocolError(f"protocol version is not {VERSION}: {lines[0]!r}")
        self.info = {"version": info[3], "maxCols": int(info[4]), "maxRows": int(info[5]),
                     "minMines": int(info[6]), "mode": info[7], "lanes": int(info[8])}
        self.presets = []
        for l in lines[1:-1]:
            p = l.split(" ")
            if p[0] != "PRESET" or len(p) != 7:
                raise ProtocolError(f"bad PRESET line {l!r}")
            self.presets.append({"id": int(p[2]), "cols": int(p[3]), "rows": int(p[4]),
                                 "minMines": int(p[5]), "maxMines": int(p[6])})
        return self.info, self.presets

    def _snapshot_request(self, text):
        seq, lines = self._transaction(text)
        self.snapshot = parse_snapshot(lines[1:], seq)
        return self.snapshot

    def get(self):
        return self._snapshot_request("GET #")

    def new_game(self, preset, mines, seed):
        return self._snapshot_request(f"NEW # {_hex4(seed)} {preset} {mines}")

    def reveal(self, row, col):
        return self._snapshot_request(f"REVEAL # {row} {col}")

    def flag(self, row, col):
        return self._snapshot_request(f"FLAG # {row} {col}")

    def recover(self):
        """After a timeout or a bad reply: HELLO, then GET (never repeat a move blindly, CONTRACT 6.3)."""
        if hasattr(self.link, "reset_input_buffer"):
            time.sleep(0.2)
            self.link.reset_input_buffer()
        self.hello()
        return self.get()


def open_link(port, baud, timeout):
    if "://" in port:
        return serial.serial_for_url(port, baudrate=baud, timeout=timeout)
    return serial.Serial(port, baudrate=baud, timeout=timeout)


def render_text(s):
    out = [f"{s.state}  {s.cols}x{s.rows}, {s.mines} mines, mines left {s.mines_left}, revision {s.revision}  "
           f"(cycles: generation {s.generation_cycles}, reveal {s.reveal_cycles}, solver {s.solver_cycles})",
           "    " + "".join(f"{c:3d}" for c in range(s.cols))]
    for r in range(s.rows):
        row = []
        for c in range(s.cols):
            t, h = s.cells[r][c], s.hints[r][c]
            ch = {"H": ".", "F": "F", "M": "*"}.get(t, t)
            if t in ("H", "F") and h == "S":
                ch = "s"
            elif t == "H" and h == "M":
                ch = "m"
            row.append(f"{ch:>3}")
        out.append(f"{r:3d} " + "".join(row))
    return "\n".join(out)


def run_text(board, commands):
    """r <row> <col> | f <row> <col> | a (one safe hint) | auto | n <preset> <mines> [seedhex] | g | q"""
    print(render_text(board.get()))
    for cmd in commands:
        words = cmd.split()
        if not words:
            continue
        try:
            if words[0] == "q":
                return
            elif words[0] == "r":
                board.reveal(int(words[1]), int(words[2]))
            elif words[0] == "f":
                board.flag(int(words[1]), int(words[2]))
            elif words[0] == "a":
                safe = board.snapshot.safe_hints()
                if safe:
                    board.reveal(*safe[0])
                else:
                    print("No safe hint: you have to guess.")
            elif words[0] == "auto":
                while board.snapshot.state == "PLAYING" and board.snapshot.safe_hints():
                    board.reveal(*board.snapshot.safe_hints()[0])
            elif words[0] == "n":
                seed = int(words[3], 16) if len(words) > 3 else random.randint(1, 0xFFFF)
                board.new_game(int(words[1]), int(words[2]), seed)
            elif words[0] == "g":
                board.get()
            else:
                print(run_text.__doc__)
                continue
        except DeviceError as e:
            print(f"Board refused: {e.code}")
            continue
        except (ProtocolError, ValueError, IndexError) as e:
            print(f"Problem: {e}; recovering with HELLO and GET")
            board.recover()
        print(render_text(board.snapshot))


def run_window(board, port_label):
    import tkinter as tk
    from tkinter import ttk

    colours = {"hidden": "#b0b8c0", "open": "#e8ecf0", "safe": "#96d6a0", "mine": "#ec9696", "boom": "#dc3c3c"}
    numbers = {"1": "#195ac8", "2": "#1e8232", "3": "#c82828", "4": "#1e1e82",
               "5": "#821e1e", "6": "#148282", "7": "#141414", "8": "#6e6e6e"}

    root = tk.Tk()
    root.title(f"Minesweeper on the FPGA ({port_label})")
    bar = ttk.Frame(root, padding=6)
    bar.pack(fill="x")
    preset_var = tk.StringVar()
    mines_var = tk.StringVar()
    seed_var = tk.StringVar(value=f"{random.randint(1, 0xFFFF):04X}")
    hints_var = tk.BooleanVar(value=True)
    status_var = tk.StringVar()
    form_dirty = {"value": False}
    names = [f"{p['id']}: {p['cols']}x{p['rows']}" for p in board.presets]

    ttk.Label(bar, text="Grid").pack(side="left")
    preset_box = ttk.Combobox(bar, textvariable=preset_var, values=names, width=10, state="readonly")
    preset_box.pack(side="left", padx=4)
    ttk.Label(bar, text="Mines").pack(side="left")
    mines_entry = ttk.Entry(bar, textvariable=mines_var, width=5)
    mines_entry.pack(side="left", padx=4)
    ttk.Label(bar, text="Seed (hex)").pack(side="left")
    ttk.Entry(bar, textvariable=seed_var, width=6).pack(side="left", padx=4)

    canvas = tk.Canvas(root, background="#fafafa", highlightthickness=0)
    canvas.pack(padx=8, pady=4)
    ttk.Label(root, textvariable=status_var, padding=(8, 2, 8, 8)).pack(fill="x")

    def sync_form_from_fpga():
        if form_dirty["value"]:
            return
        s = board.snapshot
        if s.selection_mode == "S":
            preset_id = s.selected_preset
            mines = (14, 14, 40, 99, 130)[preset_id]
        elif s.selection_mode == "G":
            preset_id = s.preset
            mines = s.mines
        else:
            return
        preset_var.set(names[preset_id])
        mines_var.set(str(mines))

    def cell_size(s):
        return max(18, min(36, 760 // max(s.cols, 1), 560 // max(s.rows, 1)))

    def draw():
        s = board.snapshot
        sync_form_from_fpga()
        if s.hardware_hints_enabled is not None:
            hints_var.set(s.hardware_hints_enabled == 1)
            hints_checkbox.configure(state="disabled", text="Hints (FPGA switch)")
        else:
            hints_checkbox.configure(state="normal", text="Show hints")
        size = cell_size(s)
        canvas.configure(width=s.cols * size, height=s.rows * size)
        canvas.delete("all")
        for r in range(s.rows):
            for c in range(s.cols):
                t, h = s.cells[r][c], s.hints[r][c] if hints_var.get() else "."
                x0, y0 = c * size, r * size
                box = (x0 + 1, y0 + 1, x0 + size - 1, y0 + size - 1)
                if t in ("H", "F"):
                    fill = colours["safe"] if h == "S" else colours["mine"] if h == "M" else colours["hidden"]
                    canvas.create_rectangle(*box, fill=fill, outline="#8c949c")
                    if t == "F":
                        canvas.create_text(x0 + size / 2, y0 + size / 2, text="F", fill="#c82828",
                                           font=("Arial", int(size * 0.5), "bold"))
                else:
                    canvas.create_rectangle(*box, fill=colours["open"], outline="#cdd2d7")
                    if t == "M":
                        canvas.create_oval(x0 + size * 0.28, y0 + size * 0.28, x0 + size * 0.72, y0 + size * 0.72,
                                           fill="#1e1e1e")
                    elif t != "0":
                        canvas.create_text(x0 + size / 2, y0 + size / 2, text=t, fill=numbers[t],
                                           font=("Arial", int(size * 0.5), "bold"))
                if s.selection_mode == "G" and (r, c) == (s.selected_row, s.selected_col):
                    canvas.create_rectangle(*box, outline="#ff9800", width=3)
        cursor_status = (f"FPGA preset selection {s.selected_preset}: "
                         f"{board.presets[s.selected_preset]['cols']}x{board.presets[s.selected_preset]['rows']}"
                         if s.selection_mode == "S" else
                         f"FPGA cursor ({s.selected_row}, {s.selected_col})"
                         if s.selection_mode == "G" else "FPGA cursor unavailable")
        hint_switch_status = ("FPGA hints switch ON" if s.hardware_hints_enabled == 1 else
                              "FPGA hints switch OFF" if s.hardware_hints_enabled == 0 else
                              "FPGA hints switch unavailable")
        status_var.set(f"{s.state}   mines left {s.mines_left}   revision {s.revision}   "
                       f"cycles: generation {s.generation_cycles}, reveal {s.reveal_cycles} "
                       f"({s.reveal_layers} layers), solver {s.solver_cycles}   "
                       f"solver {board.info['mode']}   {cursor_status}   {hint_switch_status}")

    def act(fn, *args):
        try:
            fn(*args)
        except DeviceError as e:
            status_var.set(f"Board refused: {e.code}")
            return False
        except ProtocolError as e:
            status_var.set(f"Problem: {e}; recovering...")
            try:
                board.recover()
            except (ProtocolError, DeviceError) as e2:
                status_var.set(f"No board: {e2}")
                return False
            return False
        draw()
        return True

    def poll_board():
        act(board.get)
        root.after(500, poll_board)

    def cell_at(event):
        s = board.snapshot
        size = cell_size(s)
        r, c = event.y // size, event.x // size
        return (r, c) if 0 <= r < s.rows and 0 <= c < s.cols else None

    def on_left(event):
        rc = cell_at(event)
        if rc:
            act(board.reveal, *rc)

    def on_right(event):
        rc = cell_at(event)
        if rc:
            act(board.flag, *rc)

    def on_new():
        try:
            preset = board.presets[names.index(preset_var.get())]
            if act(board.new_game, preset["id"], int(mines_var.get()), int(seed_var.get(), 16)):
                form_dirty["value"] = False
                draw()
        except ValueError:
            status_var.set("Mines must be a number and the seed four hex digits")

    def on_preset(_=None):
        form_dirty["value"] = True
        p = board.presets[names.index(preset_var.get())]
        mines_var.set(str(max(p["minMines"], round(p["cols"] * p["rows"] * 0.15))))

    def on_mines_edit(_=None):
        form_dirty["value"] = True

    def on_auto():
        if board.snapshot.state == "PLAYING" and board.snapshot.safe_hints():
            act(board.reveal, *board.snapshot.safe_hints()[0])
            root.after(150, on_auto)

    preset_box.bind("<<ComboboxSelected>>", on_preset)
    mines_entry.bind("<KeyRelease>", on_mines_edit)
    canvas.bind("<Button-1>", on_left)
    canvas.bind("<Button-3>", on_right)
    canvas.bind("<Button-2>", on_right)
    ttk.Button(bar, text="New game", command=on_new).pack(side="left", padx=6)
    hints_checkbox = ttk.Checkbutton(bar, text="Show hints", variable=hints_var, command=draw)
    hints_checkbox.pack(side="left", padx=6)
    ttk.Button(bar, text="Auto", command=on_auto).pack(side="left", padx=2)
    ttk.Button(bar, text="Refresh", command=lambda: act(board.get)).pack(side="left", padx=2)

    current = board.snapshot.preset
    preset_var.set(names[[p["id"] for p in board.presets].index(current)])
    mines_var.set(str(board.snapshot.mines))
    draw()
    root.after(500, poll_board)
    return root


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--port", required=True, help="serial port (COM5, /dev/ttyUSB1) or socket://host:port")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--text", action="store_true", help="terminal version, no window")
    ap.add_argument("--script", help="text mode: commands separated by ';' instead of the keyboard")
    ap.add_argument("--screenshot", help="window mode: save a picture of the window after loading, then quit")
    args = ap.parse_args()

    link = open_link(args.port, args.baud, timeout=2.0)
    board = Board(link)
    try:
        board.recover()
    except (ProtocolError, DeviceError) as e:
        sys.exit(f"No answer from a protocol-{VERSION} board on {args.port}: {e}")
    print(f"Connected: protocol {VERSION}, contract {board.info['version']}, {board.info['maxCols']}x"
          f"{board.info['maxRows']} storage, solver {board.info['mode']}, {len(board.presets)} presets.")

    if args.text:
        commands = args.script.split(";") if args.script else iter(lambda: input("> "), "q")
        try:
            run_text(board, commands)
        finally:
            link.close()
        return
    if sys.platform == "win32":
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except (AttributeError, OSError):
            pass
    root = run_window(board, args.port)
    if args.screenshot:
        def grab():
            from PIL import ImageGrab
            root.update()
            x, y = root.winfo_rootx(), root.winfo_rooty()
            ImageGrab.grab((x, y, x + root.winfo_width(), y + root.winfo_height())).save(args.screenshot)
            root.destroy()
        root.after(800, grab)
    root.mainloop()
    link.close()


if __name__ == "__main__":
    main()
