"""A minimal VT emulator for PTY logs.

It replays the bytes that the apps wrote into a grid of cells, so a check can read the SCREEN after a partial repaint
(the runner sends only the changed cells, with cursor moves between them). It handles: printable UTF-8 (wide
characters take 2 cells), CR, LF, BS, TAB, CSI cursor moves (H f A B C D E F G d), erase (J K X P @), save and restore
of the cursor (ESC 7/8, CSI s/u), the alternate screen (CSI ?1049h/l, ?47, ?1047), and line scroll at the bottom.
Colours and other modes (SGR, ?25, ?2004, OSC) are read and ignored.

Usage: python3 vt.py <session.log> [rows cols]   -> prints a snapshot of the screen at each @@MARK name@@ line.
"""
import re
import sys
import unicodedata


def width(ch):
    if unicodedata.combining(ch):
        return 0
    return 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1


class Screen:
    def __init__(self, rows=30, cols=100):
        self.rows, self.cols = rows, cols
        self.main = self.blank()
        self.alt = self.blank()
        self.in_alt = False
        self.row = self.col = 0
        self.saved = (0, 0)
        self.saved_main = (0, 0)
        self.alt_entered = 0
        self.alt_left = 0

    def blank(self):
        return [[" "] * self.cols for _ in range(self.rows)]

    @property
    def buf(self):
        return self.alt if self.in_alt else self.main

    def lines(self):
        return ["".join(c for c in r if c is not None).rstrip() for r in self.buf]

    def text(self):
        return "\n".join(self.lines()).rstrip("\n")

    def clamp(self):
        self.row = max(0, min(self.rows - 1, self.row))
        self.col = max(0, min(self.cols - 1, self.col))

    def linefeed(self):
        if self.row == self.rows - 1:
            b = self.buf
            b.pop(0)
            b.append([" "] * self.cols)
        else:
            self.row += 1

    def put(self, ch):
        w = width(ch)
        if w == 0:
            return
        if self.col + w > self.cols:
            self.col = 0
            self.linefeed()
        b = self.buf
        b[self.row][self.col] = ch
        if w == 2 and self.col + 1 < self.cols:
            b[self.row][self.col + 1] = None  # the right half of a wide character
        self.col += w  # col == cols is the "pending wrap" state: the next printable wraps, a CSI move clamps

    def erase_line(self, mode):
        r = self.buf[self.row]
        rng = {0: range(self.col, self.cols), 1: range(0, self.col + 1), 2: range(0, self.cols)}[mode]
        for x in rng:
            if 0 <= x < self.cols:
                r[x] = " "

    def erase_display(self, mode):
        b = self.buf
        if mode in (2, 3):
            for y in range(self.rows):
                b[y] = [" "] * self.cols
        elif mode == 0:
            self.erase_line(0)
            for y in range(self.row + 1, self.rows):
                b[y] = [" "] * self.cols
        elif mode == 1:
            self.erase_line(1)
            for y in range(0, self.row):
                b[y] = [" "] * self.cols

    def csi(self, private, params, final):
        nums = [int(p) if p.isdigit() else None for p in params.split(";")] if params else []

        def n(i, default=1):
            v = nums[i] if i < len(nums) else None
            return default if v is None or v == 0 else v

        if private == "?":
            on = final == "h"
            for p in nums:
                if p in (1049, 1047, 47) and final in "hl":
                    if on and not self.in_alt:
                        self.saved_main = (self.row, self.col)
                        self.in_alt = True
                        self.alt = self.blank()
                        self.alt_entered += 1
                    elif not on and self.in_alt:
                        self.in_alt = False
                        self.row, self.col = self.saved_main
                        self.alt_left += 1
            return
        if final in "Hf":
            self.row, self.col = n(0) - 1, n(1) - 1
        elif final == "A":
            self.row -= n(0)
        elif final == "B":
            self.row += n(0)
        elif final == "C":
            self.col += n(0)
        elif final == "D":
            self.col -= n(0)
        elif final == "E":
            self.row += n(0)
            self.col = 0
        elif final == "F":
            self.row -= n(0)
            self.col = 0
        elif final == "G":
            self.col = n(0) - 1
        elif final == "d":
            self.row = n(0) - 1
        elif final == "J":
            self.erase_display(nums[0] if nums and nums[0] is not None else 0)
        elif final == "K":
            self.erase_line(nums[0] if nums and nums[0] is not None else 0)
        elif final == "X":
            r = self.buf[self.row]
            for x in range(self.col, min(self.cols, self.col + n(0))):
                r[x] = " "
        elif final == "P":
            r = self.buf[self.row]
            k = n(0)
            del r[self.col:self.col + k]
            r.extend([" "] * (self.cols - len(r)))
        elif final == "@":
            r = self.buf[self.row]
            k = n(0)
            for _ in range(k):
                r.insert(self.col, " ")
            del r[self.cols:]
        elif final == "s":
            self.saved = (self.row, self.col)
        elif final == "u":
            self.row, self.col = self.saved
        # m (SGR), r, t, n, c, q ... are ignored
        self.clamp()

    TOKEN = re.compile(
        r"\x1b\[([?>=!]?)([0-9;:]*)[ -/]*([@-~])"  # CSI
        r"|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)"  # OSC
        r"|\x1b[()*+][A-Za-z0-9]"  # charset
        r"|\x1b([78=>MDEc])"  # short ESC
        r"|([\r\n\b\t\x07\x00-\x06\x0e-\x1a\x1c-\x1f])"  # C0
        r"|([^\x1b])",  # printable
        re.S,
    )

    def feed(self, s):
        for m in self.TOKEN.finditer(s):
            if m.group(3) is not None:
                self.csi(m.group(1), m.group(2), m.group(3))
            elif m.group(4) is not None:
                e = m.group(4)
                if e == "7":
                    self.saved = (self.row, self.col)
                elif e == "8":
                    self.row, self.col = self.saved
                elif e == "M":
                    self.row = max(0, self.row - 1)
                elif e == "D":
                    self.linefeed()
                elif e == "E":
                    self.col = 0
                    self.linefeed()
                elif e == "c":
                    self.__init__(self.rows, self.cols)
            elif m.group(5) is not None:
                c = m.group(5)
                if c == "\r":
                    self.col = 0
                elif c == "\n":
                    self.linefeed()
                elif c == "\b":
                    self.col = max(0, self.col - 1)
                elif c == "\t":
                    self.col = min(self.cols - 1, (self.col // 8 + 1) * 8)
            elif m.group(6) is not None:
                if self.col >= self.cols:
                    self.col = 0
                    self.linefeed()
                self.put(m.group(6))
            # OSC and charset selections are ignored


MARK = re.compile(r"\n@@MARK ([A-Za-z0-9_.-]+)@@\n")


def replay(path, rows=30, cols=100):
    """Returns [(mark, raw segment after the mark, alt screen text at the END of the segment, in_alt, Screen)]."""
    raw = open(path, "rb").read().decode("utf-8", "replace")
    parts = MARK.split(raw)
    scr = Screen(rows, cols)
    scr.feed(parts[0])
    out = []
    for i in range(1, len(parts) - 1, 2):
        name, seg = parts[i], parts[i + 1]
        scr.feed(seg)
        out.append((name, seg, "\n".join("".join(c for c in r if c is not None).rstrip() for r in scr.alt), scr.in_alt))
    return out


if __name__ == "__main__":
    rows = int(sys.argv[2]) if len(sys.argv) > 2 else 30
    cols = int(sys.argv[3]) if len(sys.argv) > 3 else 100
    for name, seg, alt, in_alt in replay(sys.argv[1], rows, cols):
        print(f"===== after {name} (bytes in segment: {len(seg.encode('utf-8'))}, alt screen active: {in_alt})")
        print(alt)
