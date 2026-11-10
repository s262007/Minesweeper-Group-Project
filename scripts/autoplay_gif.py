"""Draw the AutoPlay frames as an animated GIF (R3-S5).

    sbt "Test/runMain minesweeper.AutoPlayFrames"     # writes docs/figures/autoplay_frames.txt
    python scripts/autoplay_gif.py                    # writes docs/figures/autoplay.gif

Optional arguments: input frames file, output GIF. Needs Pillow (pip install Pillow).

Frames file: a '#' title line, then blocks separated by blank lines. Each block starts with
"frame <n> | <move> | <reason> | <state> | mines left <k> | solver <c> cycles", followed by one line
per row with one character per cell: '.' hidden, 'F' flag, '0'-'8' number, '*' mine, 'X' exploded
mine, 's' hidden with a safe hint, 'm' hidden with a mine hint.
"""

import re
import sys

from PIL import Image, ImageDraw, ImageFont

CELL = 36
MARGIN = 14
TOP = 64
BOTTOM = 30

BACKGROUND = (250, 250, 250)
HIDDEN = (176, 184, 192)
HIDDEN_EDGE = (140, 148, 156)
OPEN = (232, 236, 240)
SAFE_HINT = (150, 214, 160)
MINE_HINT = (236, 150, 150)
EXPLODED = (220, 60, 60)
LAST_MOVE = (240, 180, 20)
TEXT = (30, 30, 30)
NUMBER_COLOURS = {
    "1": (25, 90, 200), "2": (30, 130, 50), "3": (200, 40, 40), "4": (30, 30, 130),
    "5": (130, 30, 30), "6": (20, 130, 130), "7": (20, 20, 20), "8": (110, 110, 110),
}


def font(size):
    for name in ("DejaVuSans-Bold.ttf", "arialbd.ttf", "Arial Bold.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default(size=size)


def read_frames(path):
    with open(path, encoding="utf-8") as f:
        text = f.read()
    title = ""
    frames = []
    for block in text.strip().split("\n\n"):
        lines = [line for line in block.splitlines() if line.strip()]
        if lines and lines[0].startswith("#"):
            title = lines[0].lstrip("# ").strip()
            lines = lines[1:]
        if lines:
            frames.append((lines[0], lines[1:]))
    return title, frames


def draw(header, rows, title_font, label_font, cell_font, small_font):
    fields = [f.strip() for f in header.split("|")]
    move, reason, state, mines_left = fields[1], fields[2], fields[3], fields[4]
    last = re.search(r"reveal \((\d+),(\d+)\)", move)
    last = (int(last.group(1)), int(last.group(2))) if last else None

    cols, nrows = len(rows[0]), len(rows)
    width = 2 * MARGIN + cols * CELL
    height = TOP + nrows * CELL + BOTTOM + MARGIN
    img = Image.new("RGB", (width, height), BACKGROUND)
    d = ImageDraw.Draw(img)

    d.text((MARGIN, 8), "AutoPlay on the simulated MinesweeperCore", font=title_font, fill=TEXT)
    caption = move if move.startswith("new") else f"{move}: {reason}"
    if move.startswith("new"):
        caption = "new game, 9x9, 10 mines"
    d.text((MARGIN, 30), caption, font=label_font, fill=TEXT)
    status = f"{state}, {mines_left}"
    d.text((width - MARGIN - d.textlength(status, font=label_font), 30), status, font=label_font, fill=TEXT)

    for r, line in enumerate(rows):
        for c, ch in enumerate(line):
            x0, y0 = MARGIN + c * CELL, TOP + r * CELL
            box = (x0 + 1, y0 + 1, x0 + CELL - 1, y0 + CELL - 1)
            if ch in ".smF":
                fill = {"s": SAFE_HINT, "m": MINE_HINT}.get(ch, HIDDEN)
                d.rectangle(box, fill=fill, outline=HIDDEN_EDGE)
                if ch == "F":
                    d.polygon([(x0 + 13, y0 + 9), (x0 + 26, y0 + 15), (x0 + 13, y0 + 21)], fill=EXPLODED)
                    d.line([(x0 + 13, y0 + 9), (x0 + 13, y0 + 28)], fill=TEXT, width=2)
            else:
                d.rectangle(box, fill=EXPLODED if ch == "X" else OPEN, outline=(205, 210, 215))
                if ch in "*X":
                    d.ellipse((x0 + 10, y0 + 10, x0 + CELL - 10, y0 + CELL - 10), fill=TEXT)
                elif ch != "0":
                    w = d.textlength(ch, font=cell_font)
                    d.text((x0 + (CELL - w) / 2, y0 + 6), ch, font=cell_font, fill=NUMBER_COLOURS[ch])
            if last == (r, c):
                d.rectangle((x0 + 1, y0 + 1, x0 + CELL - 1, y0 + CELL - 1), outline=LAST_MOVE, width=3)

    legend_y = TOP + nrows * CELL + 8
    x = MARGIN
    for colour, text in ((SAFE_HINT, "safe hint"), (MINE_HINT, "mine hint"), (LAST_MOVE, "last move")):
        d.rectangle((x, legend_y + 2, x + 14, legend_y + 16), fill=colour if colour != LAST_MOVE else BACKGROUND,
                    outline=colour if colour == LAST_MOVE else HIDDEN_EDGE, width=3 if colour == LAST_MOVE else 1)
        d.text((x + 20, legend_y + 1), text, font=small_font, fill=TEXT)
        x += 28 + d.textlength(text, font=small_font) + 10
    return img


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "docs/figures/autoplay_frames.txt"
    dst = sys.argv[2] if len(sys.argv) > 2 else "docs/figures/autoplay.gif"
    title, frames = read_frames(src)
    fonts = (font(15), font(14), font(20), font(12))
    images = [draw(header, rows, *fonts) for header, rows in frames]
    durations = [900] * len(images)
    durations[-1] = 3000
    images[0].save(dst, save_all=True, append_images=images[1:], duration=durations, loop=0)
    print(f"Wrote {dst}: {len(images)} frames ({title})")


if __name__ == "__main__":
    main()
