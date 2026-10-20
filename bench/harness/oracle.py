"""The screen oracles of the tasks: the key scripts of bench/tasks/counter.md and bench/tasks/dashboard.md.

A script drives a running Session and returns its steps as dicts {"step", "ok", "detail"}. It never raises for a
wrong screen: a failed step is a result. The first step also gives the time of the first frame.
"""
# claude 2026-10-20: created (L5): the oracles of the bench harness

import math
import re
import signal
import time

HELP = "+/- or Up/Down change the count, q quits"
WORDS = (
    "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
    "seventeen eighteen nineteen twenty"
).split()


def words(n):
    if abs(n) > 20:
        return str(n)
    return ("minus " if n < 0 else "") + WORDS[abs(n)]


def step(name, ok, detail=""):
    return {"step": name, "ok": bool(ok), "detail": detail}


def screen_dump(s, rows=None):
    lines = s.lines()
    if rows:
        lines = [lines[r] for r in rows if r < len(lines)]
    return " | ".join(l.strip() for l in lines if l.strip())[:300]


# -- counter


def count_on_row3(n):
    return lambda s: s.row(3).rstrip() == f"Count: {n}"


def counter_shape(s, n, v2):
    """The rows of the spec and no other text (counter.md, "Screen")."""
    lines = s.lines()
    want = {0: "Counter", 2: f"Count: {n}", len(lines) - 1: HELP}
    if v2:
        want[3] = f"In words: {words(n)}"
    bad = []
    for i, line in enumerate(lines):
        exp = want.get(i, "")
        if line.rstrip() != exp:
            bad.append(f"row {i + 1}: {line.strip()[:60]!r} (want {exp!r})")
    return not bad, "; ".join(bad[:4])


def counter_script(s, v2=False, start_timeout=30.0, step_timeout=1.5):
    """The key script of counter.md. Returns (steps, first_frame_ns or None)."""
    steps = []
    ns = s.expect(lambda x: "Count: 0" in x.text(), start_timeout)
    if ns is None:
        steps.append(step("start", False, "no 'Count: 0' in %.0f s: %s" % (start_timeout, screen_dump(s))))
        return steps, None
    time.sleep(0.3)
    ok, why = counter_shape(s, 0, v2)
    steps.append(step("start", ok, why))
    strict = ok  # a layout that fails at the start is reported once; the next steps check the effects
    count = 0

    def key(name, data, delta, settle=0.0):
        nonlocal count
        count += delta
        s.send(data)
        hit = s.expect(count_on_row3(count), step_timeout)
        if settle:
            time.sleep(settle)
        good, why2 = counter_shape(s, count, v2) if strict else (True, "")
        steps.append(step(name, hit is not None and good, why2 if hit is not None else
                          f"no 'Count: {count}' on row 3 in {step_timeout} s: {screen_dump(s)}"))

    for i in range(3):
        key(f"plus {i + 1}", "+", 1)
    key("minus", "-", -1)
    key("up", "up", 1)
    key("down", "down", -1)
    key("burst +++++", "+++++", 5)
    s.send("x")
    time.sleep(0.3)
    ok, why = counter_shape(s, count, v2) if strict else (count_on_row3(count)(s), screen_dump(s))
    steps.append(step("unbound x", ok, why))
    s.resize(24, 80)
    hit = s.expect(lambda x: x.row(24).rstrip() == HELP and x.row(3).rstrip() == f"Count: {count}", step_timeout)
    steps.append(step("resize 80x24", hit is not None, "" if hit else screen_dump(s)))
    return steps, ns


def counter_quit(s):
    members = s.tree()
    s.send("q")
    code = s.wait(2.5)
    rep = s.restore_report(members)
    ok = code == 0 and rep["restored"]
    return step("quit", ok, f"exit={code} restore={rep}"), code, rep


# -- dashboard

STATUS = re.compile(r"Selected (\d{4})/(\d{4}) \| Focus: (\w+)")
MARKED = re.compile(r"[>›] Item (\d{4})")
ITEM = re.compile(r"Item (\d{4})")
TICKS = re.compile(r"Ticks: (\d+)")
PCT = re.compile(r"(\d{1,3})%")
CLOCK = re.compile(r"(\d\d:\d\d:\d\d)\s*$")


def value(t):
    return int(math.floor(50 + 40 * math.sin(t / 5) + 0.5))


def status(s):
    """The status bar: on the last row (the spec), else the lowest row that has it."""
    for line in reversed(s.lines()):
        m = STATUS.search(line)
        if m:
            return int(m.group(1)), int(m.group(2)), m.group(3)
    return None


def marked(s):
    for line in s.lines():
        m = MARKED.search(line)
        if m:
            return int(m.group(1))
    return None


def visible_items(s):
    return sum(1 for line in s.lines() if ITEM.search(line))


def load_values(s):
    """(T, P) read from the load panel (the right half of the screen)."""
    t = p = None
    for line in s.lines():
        right = line[len(line) // 2 :] if len(line) > 60 else line
        m = TICKS.search(line)
        if m:
            t = int(m.group(1))
        elif t is not None and p is None:
            m2 = PCT.search(right)
            if m2:
                p = int(m2.group(1))
    return t, p


def selected_is(n, total=1000, focus=None):
    def pred(s):
        st = status(s)
        return st is not None and st[0] == n and st[1] == total and (focus is None or st[2] == focus)

    return pred


def dashboard_start(s, start_timeout=30.0, total=1000):
    """The first screen of dashboard.md (step 1). Returns (step, first_frame_ns or None)."""
    ns = s.expect(lambda x: selected_is(1, total)(x) and marked(x) == 1, start_timeout)
    if ns is None:
        return step("start", False, "no first screen in %.0f s: %s" % (start_timeout, screen_dump(s))), None
    time.sleep(0.3)
    top = " ".join(s.lines()[:4])
    on_last = STATUS.search(s.row(-1)) is not None
    ok = s.row(1).startswith("Dashboard") and "Items" in top and "Load" in top and status(s)[2] == "list" and on_last
    why = "" if ok else ("status bar not on the last row; " if not on_last else "") + screen_dump(s, range(0, 4))
    return step("start", ok, why), ns


def dashboard_script(s, start_timeout=30.0, step_timeout=1.5):
    first, ns = dashboard_start(s, start_timeout)
    steps = [first]
    if ns is None:
        return steps, None

    def expect_sel(name, n, focus="list", data=None):
        if data is not None:
            s.send(data)
        hit = s.expect(lambda x: selected_is(n, focus=focus)(x) and marked(x) == n, step_timeout)
        steps.append(step(name, hit is not None, "" if hit else f"status={status(s)} marked={marked(s)}"))

    for i in range(3):
        expect_sel(f"down {i + 1}", i + 2, data="down")
    page = visible_items(s)
    expect_sel(f"pgdn (page {page})", min(1000, 4 + page), data="pgdn")
    expect_sel("pgup", 4, data="pgup")
    expect_sel("up", 3, data="up")
    s.send("tab")
    hit = s.expect(selected_is(3, focus="load"), step_timeout)
    steps.append(step("tab to load", hit is not None, "" if hit else f"status={status(s)}"))
    s.send("down")
    time.sleep(0.3)
    st = status(s)
    steps.append(step("down on load", st is not None and st[0] == 3, f"status={st}"))
    s.send("tab")
    hit = s.expect(selected_is(3, focus="list"), step_timeout)
    steps.append(step("tab to list", hit is not None, "" if hit else f"status={status(s)}"))

    # The ticks: T grows by 10 to 30 in 2 s, and each settled frame shows P = v(T).
    samples, prev, bad = [], None, []
    t_end = time.monotonic() + 2.0
    first_t = load_values(s)[0]
    while time.monotonic() < t_end:
        snap = s.text()
        if snap == prev:
            t, p = load_values(s)
            if t is not None and p is not None:
                samples.append((t, p))
                if p != value(t):
                    bad.append((t, p, value(t)))
        prev = snap
        time.sleep(0.02)
    last_t = load_values(s)[0]
    grew = (last_t or 0) - (first_t or 0)
    ok = 10 <= grew <= 30 and not bad and samples
    steps.append(step("ticks", ok, f"T {first_t}->{last_t} (+{grew}), samples={len(samples)}, wrong={bad[:3]}"))

    m = CLOCK.search(s.row(1))
    c0 = m.group(1) if m else None
    hit = s.expect(lambda x: (lambda mm: mm is not None and mm.group(1) != c0)(CLOCK.search(x.row(1))), 1.5)
    steps.append(step("clock", c0 is not None and hit is not None, f"clock={c0}"))

    s.resize(24, 80)
    hit = s.expect(lambda x: STATUS.search(x.row(24)) is not None and selected_is(3)(x), step_timeout)
    steps.append(step("resize 80x24", hit is not None, "" if hit else screen_dump(s, [0, 23])))
    return steps, ns


def dashboard_v2_steps(s, step_timeout=1.5):
    """The checks of the change request of dashboard.md (after the start)."""
    steps = []
    s.send("tab")
    hit = s.expect(lambda x: status(x) is not None and status(x)[2] == "filter", step_timeout)
    steps.append(step("tab to filter", hit is not None, f"status={status(s)}"))
    s.send("99")
    hit = s.expect(lambda x: selected_is(1, 19, "filter")(x) and marked(x) == 99, step_timeout)
    steps.append(step("filter 99", hit is not None, f"status={status(s)} marked={marked(s)}"))
    s.send("backspace")
    hit = s.expect(lambda x: selected_is(1, 271, "filter")(x), step_timeout)
    steps.append(step("backspace", hit is not None, f"status={status(s)}"))
    return steps


def quit_and_restore(s, key="q"):
    members = s.tree()
    s.send(key)
    code = s.wait(2.5)
    rep = s.restore_report(members)
    return code, rep


def failure_probe(s, live_key="+", check=None):
    """Ctrl-X: the program ends, or the runtime contains the failure (counter.md, "Failure")."""
    members = s.tree()
    s.send("ctrl-x")
    code = s.wait(2.0)
    if code is not None:
        return "ended", code, s.restore_report(members)
    outcome = "contained"
    if check is not None:
        before = s.text()
        s.send(live_key)
        if s.expect(check, 1.5) is None:
            outcome = "error-screen"
        del before
    s.send("q")
    code = s.wait(2.5)
    if code is None:
        s.signal(signal.SIGKILL)
        s.wait(2)
        return outcome + "+hung", None, s.restore_report(members)
    return outcome, code, s.restore_report(members)
