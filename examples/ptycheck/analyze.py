"""Checks the terminal output that checklist.exp logged, screen by screen.

A small terminal emulator replays the raw output and takes a snapshot of the
screen at each MARK line, so a check reads what a person would see.
Usage: python3 analyze.py <session.log>. Exit code 1 when a check fails.
"""
import re
import sys
import unicodedata

raw = open(sys.argv[1], "rb").read().decode("utf-8", "replace")
ROWS, COLS = 60, 200


class Screen:
    """Enough of a VT100 for crossterm output: cursor moves, clears, text."""

    def __init__(self):
        self.cells = [[" "] * COLS for _ in range(ROWS)]
        self.r = self.c = 0
        self.alt = False

    def clear(self, mode=2):
        if mode == 2:
            self.cells = [[" "] * COLS for _ in range(ROWS)]
        elif mode == 0:
            self.cells[self.r][self.c:] = [" "] * (COLS - self.c)
            for row in range(self.r + 1, ROWS):
                self.cells[row] = [" "] * COLS

    def put(self, ch):
        if ch == "\r":
            self.c = 0
        elif ch == "\n":
            self.r = min(self.r + 1, ROWS - 1)
        elif ch >= " ":
            wide = unicodedata.east_asian_width(ch) in ("W", "F")
            if self.r < ROWS and self.c < COLS:
                self.cells[self.r][self.c] = ch
                if wide and self.c + 1 < COLS:
                    self.cells[self.r][self.c + 1] = ""
            self.c += 2 if wide else 1

    def feed(self, text):
        for m in re.finditer(r"\x1b\[([0-9;?]*)([ -/]*)([@-~])|\x1b[()][A-Z0-9]|\x1b.|([^\x1b])", text, re.S):
            if m.group(4) is not None:
                self.put(m.group(4))
                continue
            params, final = m.group(1) or "", m.group(3)
            if final == "H":
                parts = [int(x) if x else 1 for x in params.split(";")] if params else [1, 1]
                self.r, self.c = parts[0] - 1, (parts[1] - 1 if len(parts) > 1 else 0)
            elif final == "J":
                self.clear(int(params) if params.isdigit() else 0)
            elif final in "hl" and params == "?1049":
                self.alt = final == "h"
                self.clear(2)

    def text(self):
        return "\n".join("".join(row).rstrip() for row in self.cells)


parts = re.split(r"\n@@MARK ([a-z0-9-]+)@@\n", raw)
screen = Screen()
snap, seg = {}, {}
screen.feed(parts[0])
for i in range(1, len(parts) - 1, 2):
    name, body = parts[i], parts[i + 1]
    seg[name] = body
    screen.feed(body)
    snap[name] = screen.text()  # the screen at the end of this step

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f": {detail}"))


def shows(step, text):
    return text in snap.get(step, "")


def show(step):
    return repr(snap.get(step, "")[-600:])


check("counter: + shows Count: 1", shows("counter-plus1", "Count: 1"), show("counter-plus1"))
check("counter: + shows Count: 2", shows("counter-plus2", "Count: 2"), show("counter-plus2"))
check("counter: - shows Count: 1", shows("counter-minus", "Count: 1"), show("counter-minus"))
check("counter: the help bar comes from the keymap", shows("counter-plus1", "+ increment") and shows("counter-plus1", "q quit"), show("counter-plus1"))
for step in ("counter-q", "ctrlc-start", "term-start"):
    body = seg.get(step, "")
    check(f"{step}: leaves the alternate screen and shows the cursor", "\x1b[?1049l" in body and "\x1b[?25h" in body)
after_q = re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]", "", seg.get("counter-q", "").split("\x1b[?1049l")[-1]).strip()
check("counter: nothing stray is printed after the restore", after_q.startswith("EXIT=0"), repr(after_q[:200]))

check("todo: typed text shows in the field", shows("todo-typed", "buy milk"), show("todo-typed"))
check("todo: enter adds the item", shows("todo-enter", "buy milk"), show("todo-enter"))
check("todo: bracketed paste with an emoji shows", shows("todo-paste", "tea ☕ time") or (shows("todo-paste", "☕") and shows("todo-paste", "time")), show("todo-paste"))
check("todo: tab moves the focus (the help bar shows the list keys)", shows("todo-tab", "toggle") and shows("todo-tab", "delete"), show("todo-tab"))
check("todo: ctrl-s shows saved", "saved" in snap.get("todo-ctrls", "").lower(), show("todo-ctrls"))
check("todo: a restart loads both items", shows("todo2-start", "buy milk") and shows("todo2-start", "☕"), show("todo2-start"))


def gauge(step):
    m = re.search(r"(\d+)%", snap.get(step, ""))
    return m.group(1) if m else None


running = [gauge(f"dash-run-{i}") for i in range(8)]
paused = [gauge(f"dash-paused-{i}") for i in range(6)]
check("dashboard: the gauge changes about 4 times a second", len(set(v for v in running if v)) >= 4, f"gauge values every 250 ms: {running}")
check("dashboard: the log pane fills", "/api" in snap.get("dash-run-7", ""), show("dash-run-7"))
check("dashboard: p stops the gauge", len(set(paused)) == 1 and paused[0] is not None, f"gauge values while paused: {paused}")
logs_after = snap.get("dash-paused-5", "")
check("dashboard: the log pane keeps going while paused", snap.get("dash-paused-0", "") != logs_after, "the screen did not change while paused")
check("dashboard: p again resumes the gauge", gauge("dash-resume") != paused[-1], f"{paused[-1]} -> {gauge('dash-resume')}")
small = snap.get("dash-resize-small", "").splitlines()
check("dashboard: after a resize to 60x20 the frame is 60 columns wide", any(len(l) == 60 and l.startswith("╭") for l in small), repr(small[:3]))
big = snap.get("dash-resize-big", "").splitlines()
check("dashboard: after a resize back to 100x30 the frame is 100 columns wide", any(len(l) == 100 and l.startswith("╭") for l in big), repr(big[:3]))
check("dashboard: right arrow changes the tab", snap.get("dash-right") != snap.get("dash-resize-big"), "no change")

deny = re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]", "", seg.get("deny-start", ""))
check("deny: the message names --enable-native-access", "enable-native-access" in deny, repr(deny[:300]))
check("deny: no alternate screen was entered", "\x1b[?1049h" not in seg.get("deny-start", ""))
pipe = re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]", "", seg.get("pipe-start", ""))
check("pipe: the message says not a terminal", "not a terminal" in pipe, repr(pipe[:300]))

print(f"\nScreen part: {sum(results)} passed, {len(results) - sum(results)} failed")
sys.exit(0 if all(results) else 1)
