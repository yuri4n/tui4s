"""Checks the screens of the PTY session log (written by checklist.exp), replayed with the VT emulator vt.py.

Usage: python3 check.py <session log> [screens file]   -> exit 1 when a check fails.
"""
# claude 2026-10-13: changed (T1, RC-31 part): the Counter rows of plan 6.5 (the
# prototype's Counter and Todo checks; the Todo checks come with the Todo example);
# the log path is an argument
# claude 2026-10-13: changed (T2, RC-31 rest, OS-19): the rows of all the examples (kill -TERM and kill -INT,
# printing, counterkyo, counterstyles, todo, dashboard with the bold tab), the INFO line of the class-load count
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vt  # noqa: E402

session = sys.argv[1]
screens = sys.argv[2] if len(sys.argv) > 2 else os.path.splitext(session)[0] + "-screens.txt"
snaps = vt.replay(session)
by = {name: (seg, alt, in_alt) for name, seg, alt, in_alt in snaps}

with open(screens, "w") as f:
    for name, seg, alt, in_alt in snaps:
        f.write(f"===== after {name} (bytes written in this step: {len(seg.encode('utf-8'))}, alt screen active: {in_alt})\n{alt}\n")

results = []
ESC = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()][A-Za-z0-9]|\x1b[78=>]")


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f": {detail}"))


def screen(mark):
    return by.get(mark, ("", "", False))[1]


def seg(mark):
    return by.get(mark, ("", "", False))[0]


def nbytes(mark):
    return len(seg(mark).encode("utf-8"))


def row_of(mark, text):
    """The row of the first line of the screen that has `text`, or -1."""
    return next((i for i, l in enumerate(screen(mark).splitlines()) if text in l), -1)


def widest(mark):
    """The last column + 1 that has a character on the screen."""
    return max((len(l) for l in screen(mark).splitlines()), default=0)


# claude 2026-10-13: changed (T2): the exit code of the end (143 and 130 for the kill rows)
def restored(mark, name, code=0):
    """The end leaves the alternate screen, shows the cursor, and nothing comes between the restore and the prompt."""
    s = seg(mark)
    check(f"{name}: leaves the alternate screen and shows the cursor", "\x1b[?1049l" in s and "\x1b[?25h" in s, repr(s[-300:]))
    after = ESC.sub("", s.split("\x1b[?1049l")[-1]).strip()
    check(f"{name}: nothing stray after the restore (the next output is EXIT={code})", after.startswith(f"EXIT={code}"), repr(after[:300]))


# claude 2026-10-13: created (T2)
def after_restore(mark):
    """The text that comes after the restore of `mark`, without escape sequences."""
    return ESC.sub("", seg(mark).split("\x1b[?1049l")[-1]).strip()


# claude 2026-10-13: created (T2)
def inside_alt(marks):
    """The text that the app wrote while the alternate screen was up, in the segments `marks`, without escapes."""
    raw = "".join(seg(m) for m in marks)
    inner = raw.split("\x1b[?1049h", 1)[-1].split("\x1b[?1049l", 1)[0]
    return ESC.sub("", inner)


# claude 2026-10-13: created (T2)
def line_with(mark, text):
    """The first line of the screen that has `text`, or ''."""
    return next((l for l in screen(mark).splitlines() if text in l), "")


# claude 2026-10-13: created (T2)
def counts(mark):
    """The counts of the first screen line that has any, from left to right."""
    for l in screen(mark).splitlines():
        found = [int(n) for n in re.findall(r"Count: (-?[0-9]+)", l)]
        if found:
            return found
    return []


# claude 2026-10-13: created (T2)
def overview(mark):
    """The gauge and the sparkline of the Dashboard: the two lines under the line with 'usage'."""
    rows = screen(mark).splitlines()
    i = next((k for k, l in enumerate(rows) if "usage" in l), -1)
    return rows[i + 1:i + 3] if i >= 0 else []


# claude 2026-10-13: created (T2)
def log_pane(mark):
    """The lines of the Dashboard's log pane: the lines with 'event #'."""
    return [l for l in screen(mark).splitlines() if "event #" in l]


# claude 2026-10-13: created (T2): vt.py reads and ignores SGR; this screen also keeps the bold attribute of each
# cell of the alternate screen, so a check can see which tab is bold
class StyledScreen(vt.Screen):
    def __init__(self, rows=30, cols=100):
        super().__init__(rows, cols)
        self.bold = False
        self.bold_cells = [[False] * cols for _ in range(rows)]

    def csi(self, private, params, final):
        if private == "" and final == "m":
            nums = [int(p) if p.isdigit() else 0 for p in params.split(";")] if params else [0]
            i = 0
            while i < len(nums):
                n = nums[i]
                if n in (38, 48, 58):  # a colour: 5;n or 2;r;g;b follow
                    i += 3 if i + 1 < len(nums) and nums[i + 1] == 5 else 5
                    continue
                if n == 0 or n == 22:
                    self.bold = False
                elif n == 1:
                    self.bold = True
                i += 1
            return
        was_alt = self.in_alt
        super().csi(private, params, final)
        if self.in_alt and not was_alt:
            self.bold_cells = [[False] * self.cols for _ in range(self.rows)]

    def put(self, ch):
        w = vt.width(ch)
        super().put(ch)
        if w > 0 and self.in_alt and 0 <= self.col - w < self.cols:
            self.bold_cells[self.row][self.col - w] = self.bold


def styled_replay(path):
    """{mark: (alt screen lines with every cell, bold flags)} at the END of each segment, as vt.replay."""
    raw = open(path, "rb").read().decode("utf-8", "replace")
    parts = vt.MARK.split(raw)
    scr = StyledScreen()
    scr.feed(parts[0])
    out = {}
    for i in range(1, len(parts) - 1, 2):
        scr.feed(parts[i + 1])
        out[parts[i]] = (["".join(c if c is not None else "" for c in r) for r in scr.alt], [list(r) for r in scr.bold_cells])
    return out


styled = styled_replay(session)


def bold_text(mark, text, row_with):
    """True when every cell of `text` is bold, on the first line that has `row_with` (None when not found)."""
    lines, flags = styled.get(mark, ([], []))
    for y, l in enumerate(lines):
        if row_with in l and text in l:
            x = l.index(text)
            return all(flags[y][x:x + len(text)])
    return None


# Counter: + + - then q
check("counter: alt screen entered", "\x1b[?1049h" in seg("counter-start"))
check("counter: first screen shows 'Count: 0'", "Count: 0" in screen("counter-start"), screen("counter-start"))
check("counter: + shows 'Count: 1'", "Count: 1" in screen("counter-plus1"), screen("counter-plus1"))
check("counter: second + shows 'Count: 2'", "Count: 2" in screen("counter-plus2"), screen("counter-plus2"))
check("counter: - shows 'Count: 1'", "Count: 1" in screen("counter-minus"), screen("counter-minus"))
keys = ("counter-plus1", "counter-plus2", "counter-minus")
for m in keys:
    print(f"INFO bytes written for {m}: {nbytes(m)}")
check("counter: partial repaint (one key writes < 200 bytes)", all(0 < nbytes(m) < 200 for m in keys), [nbytes(m) for m in keys])
restored("counter-q", "counter q")

# Ctrl-C
check("counter ctrl-c: + shows 'Count: 1'", "Count: 1" in screen("ctrlc-plus"), screen("ctrlc-plus"))
restored("ctrlc-key", "counter ctrl-c")

# Resize: 100x30 -> 40x20 -> 100x30
help_start, help_end = "+ / - change it", "q quits"
big = screen("resize-start")
small = screen("resize-small")
again = screen("resize-big")
check("resize: at 100 columns the help is one line", row_of("resize-start", help_start) >= 0 and row_of("resize-start", help_start) == row_of("resize-start", help_end), big)
check("resize: at 40 columns the screen is laid out again inside 40 columns (the help wraps)",
      "Count: 0" in small and widest("resize-small") <= 40 and 0 <= row_of("resize-small", help_start) < row_of("resize-small", help_end), small)
check("resize: back at 100 columns the help is one line again",
      "Count: 0" in again and row_of("resize-big", help_start) >= 0 and row_of("resize-big", help_start) == row_of("resize-big", help_end), again)

# claude 2026-10-13: created (T2): the rows of T2, to the pipe row
# kill -TERM and kill -INT from outside
# claude 2026-10-19: changed: kill -HUP (129)
for name, sig, code in (("term", "TERM", 143), ("int", "INT", 130), ("hup", "HUP", 129)):
    check(f"counter kill -{sig}: first screen shows 'Count: 0'", "Count: 0" in screen(f"{name}-start"), screen(f"{name}-start"))
    restored(f"{name}-kill", f"counter kill -{sig}", code)

# Printing: the printed lines never reach the screen; they come after the restore (or go to the log file)
# claude 2026-10-13: changed (T2, review R1-1): the println before the run (IR output probe, R3): it comes before the
# alternate screen, and the later prints are captured all the same (no R3 warning)
R3_WARNING = "scala.Console was in use before the run"
shown = inside_alt(("print-start", "print-plus", "print-q"))
early = ESC.sub("", seg("print-start").split("\x1b[?1049h", 1)[0])
check("printing: the println before the run comes before the alternate screen",
      "printing: before the run" in early and "\x1b[?1049h" in seg("print-start"), repr(early[-300:]))
check("printing: + shows 'Count: 1'", "Count: 1" in screen("print-plus"), screen("print-plus"))
check("printing: no printed line on the screen while the UI is up", "printing:" not in shown, repr(shown[:600]))
lines = after_restore("print-q").splitlines()[:3]
check("printing: after the restore come the two printed lines, then EXIT=0",
      lines == ["printing: the UI is built", "printing: + gives 1", "EXIT=0"], lines)
printed = "".join(seg(m) for m in ("print-start", "print-plus", "print-q"))
check("printing: no R3 warning (the TuiApp constructor installed the capture)", R3_WARNING not in printed,
      repr(printed[-600:]))
check("printing --log: + shows 'Count: 1'", "Count: 1" in screen("printlog-plus"), screen("printlog-plus"))
logged = "".join(seg(m) for m in ("printlog-start", "printlog-plus", "printlog-q"))
log_early, _, log_rest = logged.partition("\x1b[?1049h")
check("printing --log: the println before the run comes before the alternate screen",
      "printing: before the run" in ESC.sub("", log_early) and log_rest != "", repr(log_early[-300:]))
check("printing --log: no printed line in the terminal while the UI is up or after it (they go to the file)",
      "printing:" not in log_rest and R3_WARNING not in log_rest, repr(log_rest[:600]))
restored("printlog-q", "printing --log")

# Printing --fail-on-f: a failure inside the running UI
# claude 2026-10-19: created: exit code 1, the failure after the restore and never on the screen
check("printing --fail-on-f: first screen shows 'Count: 0'", "Count: 0" in screen("printfail-start"), screen("printfail-start"))
shown = inside_alt(("printfail-start", "printfail-f"))
check("printing --fail-on-f: no printed line and no failure on the screen while the UI is up",
      "printing:" not in shown and "Exception" not in shown, repr(shown[:600]))
fail_seg = seg("printfail-f")
check("printing --fail-on-f: leaves the alternate screen and shows the cursor",
      "\x1b[?1049l" in fail_seg and "\x1b[?25h" in fail_seg, repr(fail_seg[-300:]))
fail_lines = after_restore("printfail-f").splitlines()
failure = "java.lang.IllegalStateException: printing: f failed the UI (--fail-on-f)"
check("printing --fail-on-f: after the restore come the printed line and the failure, then EXIT=1",
      fail_lines[:2] == ["printing: the UI is built", failure] and "EXIT=1" in fail_lines
      and fail_lines.index("EXIT=1") > 1, fail_lines[:8])

# The Counter with its own main (KyoApp)
check("counterkyo: + shows 'Count: 1'", "Count: 1" in screen("kyoapp-plus"), screen("kyoapp-plus"))
restored("kyoapp-q", "counterkyo q")

# CounterStyles: Hooks Left, Hooks Right, Components Left, Components Right
for mark, want, what in (("styles-start", [0, 0, 0, 0], "four counters at 0"),
                         ("styles-hook", [1, 0, 0, 0], "Enter on the + of a hook counter: 1"),
                         ("styles-comp", [1, 0, 1, 0], "Enter on the + of a Component counter: 1"),
                         ("styles-hide", [1, 0], "h hides the hook counters"),
                         ("styles-show", [0, 0, 1, 0], "h shows them again at 0; the Component counter keeps 1")):
    check(f"counterstyles: {what} (counts {want})", counts(mark) == want, screen(mark))
restored("styles-q", "counterstyles q")

# Todo
check("todo: first screen has the title and '0 open / 0'", "Todo" in screen("todo-start") and "0 open / 0" in screen("todo-start"), screen("todo-start"))
check("todo: the typed text shows in the input ('New: ... buy milk')", "buy milk" in line_with("todo-typed", "New:"), screen("todo-typed"))
check("todo: Enter adds '[ ] buy milk', '1 open / 1', and empties the input",
      "[ ] buy milk" in screen("todo-enter") and "1 open / 1" in screen("todo-enter") and "buy milk" not in line_with("todo-enter", "New:"),
      screen("todo-enter"))
check("todo: a bracketed paste of one line: the input shows 'tea time', nothing is added",
      "tea time" in line_with("todo-paste", "New:") and "1 open / 1" in screen("todo-paste"), screen("todo-paste"))
check("todo: a paste with a line break: the line break is a space ('tea time and cake'), nothing is added",
      "tea time and cake" in line_with("todo-paste2", "New:") and "1 open / 1" in screen("todo-paste2"), screen("todo-paste2"))
check("todo: Enter adds '[ ] tea time and cake', '2 open / 2'",
      "[ ] tea time and cake" in screen("todo-enter2") and "2 open / 2" in screen("todo-enter2"), screen("todo-enter2"))
check("todo: Tab moves the focus to the list (its border changes: bytes are written)", nbytes("todo-tab") > 0, nbytes("todo-tab"))
check("todo: Space toggles to '[x] buy milk', '1 open / 2'",
      "[x] buy milk" in screen("todo-space") and "1 open / 2" in screen("todo-space"), screen("todo-space"))
check("todo: Ctrl-S shows 'saved 2 items to todo-pty.txt'", "saved 2 items to todo-pty.txt" in screen("todo-ctrls"), screen("todo-ctrls"))
for m in ("todo-typed", "todo-enter", "todo-paste", "todo-paste2", "todo-tab", "todo-space", "todo-ctrls"):
    print(f"INFO bytes written for {m}: {nbytes(m)}")
restored("todo-esc", "todo esc")

# Dashboard
check("dashboard: first screen has the title, 'CPU usage' and 'sampler running'",
      all(t in screen("dash-start") for t in ("Dashboard", "CPU usage", "sampler running")), screen("dash-start"))
check("dashboard: live: the gauge or the sparkline changes between two screens 1 s apart",
      overview("dash-live1") != [] and overview("dash-live1") != overview("dash-live2"), (overview("dash-live1"), overview("dash-live2")))
check("dashboard: p pauses ('sampler PAUSED')", "sampler PAUSED" in screen("dash-pause"), screen("dash-pause"))
check("dashboard: paused: the gauge and the sparkline are the same in two screens 1 s apart",
      overview("dash-paused1") != [] and overview("dash-paused1") == overview("dash-paused2"), (overview("dash-paused1"), overview("dash-paused2")))
check("dashboard: paused: the log goes on", log_pane("dash-paused1") != log_pane("dash-paused2"), (log_pane("dash-paused1"), log_pane("dash-paused2")))
check("dashboard: p resumes ('sampler running')", "sampler running" in screen("dash-resume"), screen("dash-resume"))
check("dashboard: ArrowRight shows 'Memory usage', and the bold tab moves to Memory",
      "Memory usage" in screen("dash-right") and bold_text("dash-right", "Memory", "Network") is True and bold_text("dash-right", "CPU", "Network") is False,
      screen("dash-right"))
check("dashboard: ArrowLeft shows 'CPU usage', and CPU is bold again",
      "CPU usage" in screen("dash-left") and bold_text("dash-left", "CPU", "Network") is True and bold_text("dash-left", "Memory", "Network") is False,
      screen("dash-left"))
check("dashboard: Tab moves the focus only (still 'CPU usage')", "CPU usage" in screen("dash-tab"), screen("dash-tab"))
check("dashboard: Space on the focused tab selects it ('Memory usage')", "Memory usage" in screen("dash-space"), screen("dash-space"))
check("dashboard: Tab, then Enter on the focused tab selects it ('Network usage')", "Network usage" in screen("dash-enter"), screen("dash-enter"))
# The boxes span the width of the screen; the spans of the header and of the help line are cut at the edge (they do
# not wrap: a gap of the layout, see examples/README.md "Known limits").
def box_width(mark):
    """The width of the first box of the screen (its top border line), or -1."""
    top = line_with(mark, "┌")
    return len(top.strip()) if top else -1


check("dashboard: at 40x20 the screen is laid out again inside 40 columns (the boxes are 40 wide)",
      "Network usage" in screen("dash-small") and widest("dash-small") <= 40 and box_width("dash-small") == 40,
      screen("dash-small"))
check("dashboard: back at 100x30 the boxes are 100 wide again, and the help line is whole",
      "Network usage" in screen("dash-big") and box_width("dash-big") == 100 and "q quits" in screen("dash-big"),
      screen("dash-big"))
restored("dash-q", "dashboard q")

# Forms: the first screen, then Esc
# claude 2026-10-19: created (L1c)
fs = screen("forms-start")
check("forms: first screen has the title, the name field, the number 30, the checked news box, the Free radio and Submit",
      all(t in fs for t in ("Sign up", "Name", "[x] news", "(•) Free", "[ Submit ]")) and "30" in line_with("forms-start", "Age"),
      fs)
restored("forms-esc", "forms esc")

# INFO: the classes that the start script of the Counter loads (-Xlog:class+load), no PASS or FAIL
classload = os.path.join(os.path.dirname(os.path.abspath(session)), "classload.log")
if os.path.exists(classload):
    loaded = [l for l in open(classload, encoding="utf-8", errors="replace") if "source:" in l]
    def n(pred):
        return sum(1 for l in loaded if pred(l))
    jdk = n(lambda l: "source: jrt:/" in l or "shared objects file" in l)
    def jar(name):
        return n(lambda l: f"/{name}" in l)
    print(f"INFO class-load (examples/target/bin/counter, start, first frame, q): {len(loaded)} classes;"
          f" JDK {jdk}; Scala {jar('scala3-library') + jar('scala-library')}; Kyo {jar('kyo-')}"
          f" (kyo-ui {jar('kyo-ui')}, kyo-http {jar('kyo-http')}, kyo-net {jar('kyo-net')}); jatatui {jar('jatatui-')};"
          f" tui4s core {n(lambda l: 'modules/core/target' in l or 'tui4s-core' in l)};"
          f" examples {n(lambda l: 'examples/target' in l or 'tui4s-examples' in l)} ({classload})")
else:
    print(f"INFO class-load: no {classload}")

# Stdin is a pipe
p = seg("pipe-start")
check("pipe: NotATerminal, its message, and no alternate screen", "stdin/stdout is not a terminal" in p and "\x1b[?1049h" not in p, repr(p[:600]))

# Native access denied
d = seg("deny-start")
check("native access denied: the message names --enable-native-access=ALL-UNNAMED, no alternate screen",
      "start the JVM with --enable-native-access=ALL-UNNAMED" in d and "\x1b[?1049h" not in d, repr(d[:600]))

print(f"\nScreen part: {sum(results)} passed, {len(results) - sum(results)} failed (screens: {screens})")
sys.exit(0 if all(results) else 1)
