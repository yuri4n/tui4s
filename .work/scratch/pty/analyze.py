"""Checks the raw PTY output between the MARK lines that checklist.exp wrote into session.log."""
import re
import sys

raw = open(sys.argv[1] if len(sys.argv) > 1 else "session.log", "rb").read().decode("utf-8", "replace")
parts = re.split(r"\n@@MARK ([a-z0-9-]+)@@\n", raw)
seg = {parts[i]: parts[i + 1] for i in range(1, len(parts) - 1, 2)}

def visible(s):
    s = re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]", "", s)
    return re.sub(r"\x1b[()][A-Z0-9]", "", s)

results = []
def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f": {detail}"))

def has(segname, text):
    return text in visible(seg.get(segname, ""))

check("counter: alt screen entered", "\x1b[?1049h" in seg.get("counter-start", ""))
check("counter: + draws 1", has("counter-plus1", "1"), repr(visible(seg.get("counter-plus1", ""))[:200]))
check("counter: second + draws 2", has("counter-plus2", "2"), repr(visible(seg.get("counter-plus2", ""))[:200]))
check("counter: - draws 1", has("counter-minus", "1"), repr(visible(seg.get("counter-minus", ""))[:200]))
q = seg.get("counter-q", "")
check("counter: q leaves the alt screen and shows the cursor", "\x1b[?1049l" in q and "\x1b[?25h" in q, repr(q[:300]))
check("counter: q prints nothing stray after the restore",
      visible(q.split("\x1b[?1049l")[-1]).strip().startswith("EXIT=0"), repr(visible(q.split("\x1b[?1049l")[-1])[:200]))
for s in ("ctrlc-start", "term-start"):
    t = seg.get(s, "")
    check(f"{s}: alt screen left and cursor shown", "\x1b[?1049l" in t and "\x1b[?25h" in t)

check("todo: typed text shows", has("todo-typed", "buy milk") or has("todo-typed", "k"), repr(visible(seg.get("todo-typed", ""))[:300]))
check("todo: enter adds the item to the list", has("todo-enter", "buy milk"), repr(visible(seg.get("todo-enter", ""))[:300]))
check("todo: bracketed paste with an emoji shows", has("todo-paste", "☕") and has("todo-paste", "time"), repr(visible(seg.get("todo-paste", ""))[:300]))
check("todo: tab changes the help bar to the list keys", has("todo-tab", "toggle") or has("todo-tab", "delete"), repr(visible(seg.get("todo-tab", ""))[:300]))
check("todo: ctrl-s shows saved", "saved" in visible(seg.get("todo-ctrls", "")).lower(), repr(visible(seg.get("todo-ctrls", ""))[:300]))
check("todo: restart loads both items", has("todo2-start", "buy milk") and has("todo2-start", "☕"), repr(visible(seg.get("todo2-start", ""))[:400]))

run = visible(seg.get("dash-running", ""))
paused = visible(seg.get("dash-paused", ""))
resumed = visible(seg.get("dash-resume", ""))
check("dashboard: the gauge redraws while running (2 s)", run.count("%") >= 4, f"{run.count('%')} % signs")
check("dashboard: the log stream draws while running", "GET" in run or "/api" in run)
check("dashboard: p stops the gauge (no % drawn for 1.5 s)", paused.count("%") == 0, f"{paused.count('%')} % signs: {paused[:300]!r}")
check("dashboard: the log stream continues while paused", "/api" in paused or any(c.isdigit() for c in paused), repr(paused[:300]))
check("dashboard: p again resumes the gauge", resumed.count("%") >= 1, f"{resumed.count('%')}")
small = seg.get("dash-resize-small", "")
big = seg.get("dash-resize-big", "")
check("dashboard: redraws after a resize to 60x20", len(visible(small)) > 100, f"{len(visible(small))} visible chars")
check("dashboard: redraws after a resize back to 100x30", len(visible(big)) > 100, f"{len(visible(big))} visible chars")
check("dashboard: right arrow changes the tab", len(seg.get("dash-right", "")) > 20)
check("deny: message names --enable-native-access", "enable-native-access" in visible(seg.get("deny-start", "")), repr(visible(seg.get("deny-start", ""))[:400]))
check("deny: no raw mode or alt screen was entered", "\x1b[?1049h" not in seg.get("deny-start", ""))
check("pipe: message says not a terminal", "not a terminal" in visible(seg.get("pipe-start", "")), repr(visible(seg.get("pipe-start", ""))[:300]))
print(f"\nOutput part: {sum(results)} passed, {len(results) - sum(results)} failed")
