"""Checks the screens of logs/pty-session.log (written by checklist.exp), replayed with the VT emulator vt.py."""
# Screen checks of the kyoui-runner PTY run; snapshots go to logs/pty-screens.log
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vt  # noqa: E402

logs = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "logs")
snaps = vt.replay(os.path.join(logs, "pty-session.log"))
by = {name: (seg, alt, in_alt) for name, seg, alt, in_alt in snaps}

with open(os.path.join(logs, "pty-screens.log"), "w") as f:
    for name, seg, alt, in_alt in snaps:
        f.write(f"===== after {name} (bytes written in this step: {len(seg.encode('utf-8'))}, alt screen active: {in_alt})\n{alt}\n")

results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f": {detail}"))


def screen(mark):
    return by.get(mark, ("", "", False))[1]


def seg(mark):
    return by.get(mark, ("", "", False))[0]


def nbytes(mark):
    return len(seg(mark).encode("utf-8"))


def line_with(mark, text):
    return next((l for l in screen(mark).splitlines() if text in l), None)


# Counter
check("counter: alt screen entered", "\x1b[?1049h" in seg("counter-start"))
check("counter: first screen shows 'Count: 0'", "Count: 0" in screen("counter-start"), screen("counter-start"))
check("counter: + shows 'Count: 1'", "Count: 1" in screen("counter-plus1"), screen("counter-plus1"))
check("counter: second + shows 'Count: 2'", "Count: 2" in screen("counter-plus2"), screen("counter-plus2"))
check("counter: - shows 'Count: 1'", "Count: 1" in screen("counter-minus"), screen("counter-minus"))
q = seg("counter-q")
check("counter: q leaves the alt screen and shows the cursor", "\x1b[?1049l" in q and "\x1b[?25h" in q, repr(q[:300]))
after = re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]", "", q.split("\x1b[?1049l")[-1]).strip()
check("counter: nothing stray after the restore (the next output is EXIT=0)", after.startswith("EXIT=0"), repr(after[:200]))
for m in ("counter-plus1", "counter-plus2", "counter-minus"):
    print(f"INFO bytes written for {m}: {nbytes(m)}")
check("counter: partial repaint (one key writes < 200 bytes)", all(nbytes(m) < 200 for m in ("counter-plus1", "counter-plus2", "counter-minus")),
      [nbytes(m) for m in ("counter-plus1", "counter-plus2", "counter-minus")])

check("counter (flags): first screen", "Count: 0" in screen("counterflags-start"), screen("counterflags-start"))
check("counter (flags): + shows 'Count: 1'", "Count: 1" in screen("counterflags-plus"), screen("counterflags-plus"))
c = seg("counterflags-ctrlc")
check("counter (flags): Ctrl-C leaves the alt screen and shows the cursor", "\x1b[?1049l" in c and "\x1b[?25h" in c, repr(c[:300]))

# Todo
check("todo: alt screen entered", "\x1b[?1049h" in seg("todo-start"))
check("todo: first screen has the title and '0 open / 0'", "Todo" in screen("todo-start") and "0 open / 0" in screen("todo-start"), screen("todo-start"))
check("todo: typed text shows in the input ('New: buy milk')", line_with("todo-typed", "New:") is not None and "buy milk" in line_with("todo-typed", "New:"), screen("todo-typed"))
check("todo: Enter adds '[ ] buy milk' and the count is '1 open / 1'", "[ ] buy milk" in screen("todo-enter") and "1 open / 1" in screen("todo-enter"), screen("todo-enter"))
check("todo: Enter clears the input", "buy milk" not in (line_with("todo-enter", "New:") or ""), line_with("todo-enter", "New:"))
check("todo: Tab writes something (the list's focus border)", nbytes("todo-tab") > 0, nbytes("todo-tab"))
check("todo: Space toggles to '[x] buy milk', '0 open / 1'", "[x] buy milk" in screen("todo-space") and "0 open / 1" in screen("todo-space"), screen("todo-space"))
check("todo: Ctrl-S shows 'saved 1 items'", "saved 1 items" in screen("todo-ctrls"), screen("todo-ctrls"))
e = seg("todo-esc")
check("todo: Esc leaves the alt screen and shows the cursor", "\x1b[?1049l" in e and "\x1b[?25h" in e, repr(e[:300]))
for m in ("todo-typed", "todo-enter", "todo-tab", "todo-space", "todo-ctrls"):
    print(f"INFO bytes written for {m}: {nbytes(m)}")
print(f"\nScreen part: {sum(results)} passed, {len(results) - sum(results)} failed")
