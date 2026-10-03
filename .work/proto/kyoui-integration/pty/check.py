"""Checks the screens of logs/pty-session.log (written by checklist.exp), replayed with the runner's VT emulator."""
# claude 2026-10-03: created: screen checks of the integration PTY run. It imports vt.py from the runner prototype
# (.work/proto/kyoui-runner/pty/vt.py), unchanged and not copied. Snapshots go to logs/pty-screens.log.
import os
import re
import sys

here = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(here, "..", "..", "kyoui-runner", "pty"))
import vt  # noqa: E402

logs = os.path.join(here, "..", "logs")
snaps = vt.replay(os.path.join(logs, "pty-session.log"))
by = {name: (seg, alt, in_alt) for name, seg, alt, in_alt in snaps}

with open(os.path.join(logs, "pty-screens.log"), "w") as f:
    for name, seg, alt, in_alt in snaps:
        f.write(f"===== after {name} (bytes written in this step: {len(seg.encode('utf-8'))}, alt screen active: {in_alt})\n{alt}\n")

results = []


# claude 2026-10-03: created check
def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f": {detail}"))


# claude 2026-10-03: created screen
def screen(mark):
    return by.get(mark, ("", "", False))[1]


# claude 2026-10-03: created seg
def seg(mark):
    return by.get(mark, ("", "", False))[0]


# claude 2026-10-03: created nbytes
def nbytes(mark):
    return len(seg(mark).encode("utf-8"))


# claude 2026-10-03: created counts
def counts(mark):
    return re.findall(r"Count: -?\d+", screen(mark))


# claude 2026-10-03: created the checks of one Counter run (prefix "zio" or "cats")
def counter(p, steps):
    check(f"{p}: alt screen entered", "\x1b[?1049h" in seg(f"{p}-start"))
    first = counts(f"{p}-start")
    check(f"{p}: first screen shows three 'Count: 0'", first == ["Count: 0"] * 3, screen(f"{p}-start"))
    for mark, want in steps:
        check(f"{p}: after {mark}: {want}", counts(mark) == want, screen(mark))
        print(f"INFO bytes written for {mark}: {nbytes(mark)}")
    c = seg(f"{p}-ctrlc")
    check(f"{p}: Ctrl-C leaves the alt screen and shows the cursor", "\x1b[?1049l" in c and "\x1b[?25h" in c, repr(c[:300]))
    after = re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]", "", c.split("\x1b[?1049l")[-1]).strip()
    check(f"{p}: nothing stray after the restore (the next output is EXIT=0)", after.startswith("EXIT=0"), repr(after[:300]))
    changing = [m for m, _ in steps if "enter" in m]
    check(f"{p}: partial repaint (each Enter writes < 200 bytes)", all(nbytes(m) < 200 for m in changing), [nbytes(m) for m in changing])


counter("zio", [
    ("zio-enter", ["Count: -1", "Count: 0", "Count: 0"]),
    ("zio-tab", ["Count: -1", "Count: 0", "Count: 0"]),
    ("zio-enter2", ["Count: 0", "Count: 0", "Count: 0"]),
    ("zio-enter3", ["Count: 1", "Count: 0", "Count: 0"]),
    ("zio-tabtab", ["Count: 1", "Count: 0", "Count: 0"]),
    ("zio-enter4", ["Count: 1", "Count: 1", "Count: 0"]),
    ("zio-tab3", ["Count: 1", "Count: 1", "Count: 0"]),
    ("zio-enter5", ["Count: 1", "Count: 1", "Count: 1"]),
])
check("zio: Tab writes something (the focus moves)", nbytes("zio-tab") > 0, nbytes("zio-tab"))

counter("cats", [
    ("cats-enter", ["Count: -1", "Count: 0", "Count: 0"]),
    ("cats-tab", ["Count: -1", "Count: 0", "Count: 0"]),
    ("cats-enter2", ["Count: 0", "Count: 0", "Count: 0"]),
])
print(f"\nScreen part: {sum(results)} passed, {len(results) - sum(results)} failed")
