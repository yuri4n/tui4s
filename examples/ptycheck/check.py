"""Checks the screens of the PTY session log (written by checklist.exp), replayed with the VT emulator vt.py.

Usage: python3 check.py <session log> [screens file]   -> exit 1 when a check fails.
"""
# claude 2026-10-13: changed (T1, RC-31 part): the Counter rows of plan 6.5 (the
# prototype's Counter and Todo checks; the Todo checks come with the Todo example);
# the log path is an argument
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


def restored(mark, name):
    """The quit leaves the alternate screen, shows the cursor, and nothing comes between the restore and the prompt."""
    s = seg(mark)
    check(f"{name}: leaves the alternate screen and shows the cursor", "\x1b[?1049l" in s and "\x1b[?25h" in s, repr(s[-300:]))
    after = ESC.sub("", s.split("\x1b[?1049l")[-1]).strip()
    check(f"{name}: nothing stray after the restore (the next output is EXIT=0)", after.startswith("EXIT=0"), repr(after[:300]))


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

# Stdin is a pipe
p = seg("pipe-start")
check("pipe: NotATerminal, its message, and no alternate screen", "stdin/stdout is not a terminal" in p and "\x1b[?1049h" not in p, repr(p[:600]))

# Native access denied
d = seg("deny-start")
check("native access denied: the message names --enable-native-access=ALL-UNNAMED, no alternate screen",
      "start the JVM with --enable-native-access=ALL-UNNAMED" in d and "\x1b[?1049h" not in d, repr(d[:600]))

print(f"\nScreen part: {sum(results)} passed, {len(results) - sum(results)} failed (screens: {screens})")
sys.exit(0 if all(results) else 1)
