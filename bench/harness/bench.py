"""The bench harness: performance, conformance, resilience, the change request and the mutants, in a PTY.

Usage: python3 bench/harness/bench.py <command> [--stacks a,b] [--tasks counter,dashboard] [--runs N] [--quick]
Commands: perf, conformance, resilience, change, mutants, all, machine, check, peek <stack> <task> [keys...]

Results go to bench/results/<machine>/<stack>-<task>.json (one section per command) and
bench/results/<machine>/mutants-<stack>.json. The machine is $BENCH_MACHINE (default: local-<n>cpu). The programs
must be built (bench/run.sh builds them); `change` and `mutants` build the patched versions and rebuild after.

Why Python: the pty, termios and /proc modules of the standard library give the PTY, the line discipline flags
and the process tree with no dependency, and the PTY kit of tui4s (examples/ptycheck/vt.py) already parses a
screen.
"""
# claude 2026-10-20: created (L5): the harness of the bench

import argparse
import datetime
import difflib
import json
import os
import platform
import re
import signal
import statistics
import subprocess
import sys
import time

import compact
import oracle
import stacks as st
from term import BENCH, Session

MACHINE = os.environ.get("BENCH_MACHINE") or f"local-{os.cpu_count()}cpu"
RESULTS = os.path.join(BENCH, "results", MACHINE)
CLK = os.sysconf("SC_CLK_TCK")


def log(*a):
    print("bench:", *a, flush=True)


def run_sh(stack):
    return [os.path.join(st.stack_dir(stack), "run.sh")]


def session(stack, task, rows=30, cols=100):
    return Session(run_sh(stack) + [task], cwd=st.stack_dir(stack), rows=rows, cols=cols)


def result_path(stack, task):
    return os.path.join(RESULTS, f"{stack}-{task}.json")


def update(path, section, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    data = {}
    if os.path.exists(path):
        with open(path) as f:
            data = json.load(f)
    data[section] = value
    data["machine"] = MACHINE
    with open(path, "w") as f:
        json.dump(data, f, indent=1, sort_keys=True)
        f.write("\n")


def load_json(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def ms(ns):
    return None if ns is None else round(ns / 1e6, 2)


def pct(values, q):
    if not values:
        return None
    v = sorted(values)
    return v[min(len(v) - 1, int(round(q * (len(v) - 1))))]


# -- machine


def machine():
    cpu = ""
    try:
        with open("/proc/cpuinfo") as f:
            for line in f:
                if line.startswith("model name"):
                    cpu = line.split(":", 1)[1].strip()
                    break
        with open("/proc/meminfo") as f:
            mem_kb = int(f.readline().split()[1])
    except OSError:
        mem_kb = 0
    info = {
        "machine": MACHINE,
        "cpu": cpu,
        "cpus": os.cpu_count(),
        "memory_gb": round(mem_kb / 1024 / 1024, 1),
        "kernel": platform.release(),
        "date": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "note": os.environ.get("BENCH_MACHINE_NOTE", ""),
    }
    os.makedirs(RESULTS, exist_ok=True)
    old = load_json(os.path.join(RESULTS, "machine.json"))
    if not info["note"]:
        info["note"] = old.get("note", "")
    with open(os.path.join(RESULTS, "machine.json"), "w") as f:
        json.dump(info, f, indent=1)
        f.write("\n")
    return info


# -- perf


def perf_once(stack, task, keys, idle_s):
    s = session(stack, task)
    out = {}
    try:
        if task == "counter":
            first = s.expect(lambda x: "Count: 0" in x.text(), 30)
        else:
            first = s.expect(lambda x: oracle.selected_is(1)(x) and oracle.marked(x) == 1, 30)
        if first is None:
            return {"error": "no first frame: " + oracle.screen_dump(s)}
        out["first_frame_ms"] = ms(first - s.t0)
        time.sleep(0.5)
        lat = []
        for i in range(keys + 1):
            if task == "counter":
                pred, data = oracle.count_on_row3(i + 1), "+"
            else:
                pred, data = oracle.selected_is(i + 2), "down"
            p = s.arm(pred)
            t = s.send(data)
            hit = s.await_armed(p, 2.0)
            if hit is None:
                out["key_error"] = f"key {i + 1}: no effect in 2 s"
                break
            lat.append(hit - t)
            time.sleep(0.02)
        if lat:
            out["first_key_ms"] = ms(lat[0])
            rest = lat[1:]
            out["key_median_ms"] = ms(statistics.median(rest)) if rest else None
            out["key_p90_ms"] = ms(pct(rest, 0.9)) if rest else None
        time.sleep(1.0)
        ticks = []
        if task == "dashboard":
            def watch(ns, sess):
                t, _ = oracle.load_values(sess)
                if t is not None and (not ticks or ticks[-1][1] != t):
                    ticks.append((ns, t))
            s.watch = watch
        c0, w0 = s.cpu_ticks(), time.monotonic()
        time.sleep(idle_s)
        c1, w1 = s.cpu_ticks(), time.monotonic()
        s.watch = None
        out["cpu_pct"] = round(100.0 * (c1 - c0) / CLK / (w1 - w0), 2)
        out["rss_mb"] = round(s.rss_kb() / 1024, 1)
        if len(ticks) > 2:
            gaps = [(b[0] - a[0]) / 1e6 for a, b in zip(ticks, ticks[1:])]
            jumps = [b[1] - a[1] for a, b in zip(ticks, ticks[1:])]
            out["tick_gap_p50_ms"] = round(statistics.median(gaps), 1)
            out["tick_gap_p90_ms"] = round(pct(gaps, 0.9), 1)
            out["tick_gap_max_ms"] = round(max(gaps), 1)
            out["ticks_seen"] = len(ticks)
            out["ticks_skipped_pct"] = round(100.0 * sum(j - 1 for j in jumps if j > 1) / max(1, sum(jumps)), 1)
        code, rep = oracle.quit_and_restore(s)
        out["exit"] = code
    finally:
        s.close()
    return out


def perf(args):
    for stack in args.stacks:
        for task in args.tasks:
            runs = []
            for r in range(args.runs):
                log(f"perf {stack} {task} run {r + 1}/{args.runs}")
                runs.append(perf_once(stack, task, args.keys, args.idle))
                time.sleep(0.5)
            size, what = st.artifact(stack, task)
            summary = {"runs": runs, "artifact_mb": round(size / 1e6, 2), "artifact": what}
            for key in ("first_frame_ms", "first_key_ms", "key_median_ms", "key_p90_ms", "cpu_pct", "rss_mb",
                        "tick_gap_p50_ms", "tick_gap_p90_ms", "ticks_skipped_pct"):
                vals = [x[key] for x in runs if x.get(key) is not None]
                summary[key] = round(statistics.median(vals), 2) if vals else None
            summary["date"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
            update(result_path(stack, task), "perf", summary)
            log(f"  first frame {summary['first_frame_ms']} ms, key {summary['key_median_ms']} ms, "
                f"cpu {summary['cpu_pct']} %, rss {summary['rss_mb']} MB, artifact {summary['artifact_mb']} MB")


# -- conformance


def conformance_once(stack, task):
    s = session(stack, task)
    try:
        if task == "counter":
            steps, _ = oracle.counter_script(s)
            if steps and steps[0]["detail"].startswith("no 'Count"):
                return steps
            q, _, _ = oracle.counter_quit(s)
            steps.append(q)
        else:
            steps, first = oracle.dashboard_script(s)
            if first is None:
                return steps
            code, rep = oracle.quit_and_restore(s)
            steps.append(oracle.step("quit", code == 0 and rep["restored"], f"exit={code} restore={rep}"))
        return steps
    finally:
        s.close()


def conformance(args):
    for stack in args.stacks:
        for task in args.tasks:
            log(f"conformance {stack} {task}")
            steps = conformance_once(stack, task)
            passed = sum(1 for x in steps if x["ok"])
            update(result_path(stack, task), "conformance", {"steps": steps, "passed": passed, "total": len(steps)})
            failed = [x["step"] for x in steps if not x["ok"]]
            log(f"  {passed}/{len(steps)} steps" + (f"; failed: {', '.join(failed)}" if failed else ""))


# -- resilience

ACCEPT = {
    "quit": (0,),
    "ctrl-c": (0, 130, -2),
    "sigterm": (0, 143, -15),
    "sigint": (0, 130, -2),
    "kill-mid-frame": (0, 143, -15),
}
ROWS = ("quit", "ctrl-c", "sigterm", "sigint", "failure", "kill-mid-frame")


def resilience_row(stack, task, row):
    s = session(stack, task)
    try:
        if task == "counter":
            first = s.expect(lambda x: "Count: 0" in x.text(), 30)
            live, check = "+", oracle.count_on_row3(1)
        else:
            first = s.expect(lambda x: oracle.selected_is(1)(x), 30)
            live, check = "down", oracle.selected_is(2)
        if first is None:
            return {"pass": False, "outcome": "no-first-frame", "detail": oracle.screen_dump(s)}
        time.sleep(0.5)
        members = s.tree()
        t0 = time.monotonic()
        if row == "failure":
            outcome, code, rep = oracle.failure_probe(s, live, check)
            return {"pass": oracle.failure_ok(outcome, code, rep), "outcome": outcome, "exit": code, **rep}
        kill = {}
        if row == "quit":
            s.send("q")
        elif row == "ctrl-c":
            s.send("ctrl-c")
        elif row == "sigterm":
            s.signal(signal.SIGTERM)
        elif row == "sigint":
            s.signal(signal.SIGINT)
        elif row == "kill-mid-frame":
            # claude 2026-10-20: changed: the SIGTERM goes out from the reader thread on the first chunk of output
            # after the burst, so it lands while the frames of the burst are written (review fix)
            with s.lock:
                n0 = len(s.raw)

            def on_chunk(ns, sess):
                if not kill and len(sess.raw) > n0:
                    os.kill(sess.pid, signal.SIGTERM)
                    kill["bytes_before_kill"] = len(sess.raw) - n0
                    kill["shown_at_kill"] = shown(sess, task)

            s.watch = on_chunk
            s.send(("+" if task == "counter" else "\x1b[B") * 30)
            deadline = time.monotonic() + 2.0
            while not kill and time.monotonic() < deadline:
                time.sleep(0.002)
            s.watch = None
            if not kill:
                s.signal(signal.SIGTERM)
                kill.update(bytes_before_kill=0, shown_at_kill=shown(s, task))
        code = s.wait(2.5)
        took = round(time.monotonic() - t0, 2)
        if code is None:
            rep = {"restored": False}
            s.close()
            return {"pass": False, "outcome": "hung", "exit": None, "seconds": took}
        rep = s.restore_report(members)
        # The exit code decides only for quit; for the other rows it is a note (counter.md, "Restore contract").
        ok = rep["restored"] and (row != "quit" or code == 0)
        note = "" if code in ACCEPT[row] else f"exit code {code} is not in {ACCEPT[row]}"
        if kill:
            kill["bytes_after_kill"] = len(s.raw) - n0 - kill["bytes_before_kill"]
        return {"pass": ok, "outcome": "ended", "exit": code, "seconds": took, "note": note, **kill, **rep}
    finally:
        s.close()


def shown(s, task):
    """The state on the screen: the count (counter) or the selected item (dashboard), 30 keys after the burst."""
    if task == "counter":
        m = re.search(r"Count: (-?\d+)", s.row(3))
        return f"Count: {m.group(1)}" if m else None
    st_ = oracle.status(s)
    return f"Selected {st_[0]:04d}" if st_ else None


def resilience(args):
    for stack in args.stacks:
        for task in args.tasks:
            rows = {}
            for row in ROWS:
                rows[row] = resilience_row(stack, task, row)
                time.sleep(0.3)
            update(result_path(stack, task), "resilience", rows)
            log(f"resilience {stack} {task}: " + ", ".join(
                f"{r}={'ok' if v['pass'] else 'FAIL'}({v.get('outcome')}, {v.get('exit')})" for r, v in rows.items()))


# -- patches (the change request and the mutants)


def patch(path, reverse=False):
    cmd = ["patch", "-p1", "-s", "-d", BENCH, "--forward", "--no-backup-if-mismatch", "-r", "-", "-i", path]
    if reverse:
        cmd.insert(1, "-R")
    return subprocess.run(cmd, capture_output=True, text=True)


# claude 2026-10-20: changed: keep the diagnostics of the compiler (review fix: javac errors were lost)
COMPILER = re.compile(r"\.(java|scala|rs|go|py|jsx?|tsx?):\d+|^error(\[E\d+\])?:|\[error\]|SyntaxError|TypeError")


def build(stack, task):
    """(ok, the diagnostics of the compiler, else the last lines of the output)."""
    env = dict(os.environ)
    env.setdefault("SBT", "sbt --client")
    r = subprocess.run([os.path.join(st.stack_dir(stack), "build.sh"), task], capture_output=True, text=True,
                       env=env, timeout=900)
    lines = (r.stdout + "\n" + r.stderr).splitlines()
    diags = [l.strip() for l in lines if COMPILER.search(l) and "warn" not in l.lower()]
    return r.returncode == 0, ("\n".join(diags[:12]) or "\n".join(lines[-30:]))


# claude 2026-10-20: changed: count on the sources without comments, by the lexer of the language (review fix: a
# Python string with '#' is code)
def code_lines(stack, path):
    """The lines of a source without comments and blank lines (the rules of compact.py). The indent stays: a line
    that moves into a block is a changed line, as in the patch."""
    with open(path, encoding="utf-8") as f:
        src = f.read()
    code = compact.strip_python(src) if st.STACKS[stack]["language"] == "python" else compact.strip_c_like(src)
    return [l.rstrip() for l in code.splitlines() if l.strip()]


def changed_lines(before, after):
    """The added and removed lines between two lists of code lines."""
    add = rem = 0
    for tag, i1, i2, j1, j2 in difflib.SequenceMatcher(None, before, after, autojunk=False).get_opcodes():
        if tag != "equal":
            rem += i2 - i1
            add += j2 - j1
    return add, rem


def patched_files(path):
    with open(path) as f:
        return [os.path.join(BENCH, l[6:].strip()) for l in f if l.startswith("+++ b/")]


def change(args):
    for stack in args.stacks:
        for task in args.tasks:
            p = os.path.join(st.stack_dir(stack), task, "change.patch")
            if not os.path.exists(p):
                continue
            files = patched_files(p)
            before = {f: code_lines(stack, f) for f in files}
            r = patch(p)
            if r.returncode != 0:
                res = {"applied": False, "detail": r.stdout + r.stderr}
                update(result_path(stack, task), "change", res)
                log(f"change {stack} {task}: patch does not apply")
                continue
            add = rem = 0
            for f in files:
                a, b = changed_lines(before[f], code_lines(stack, f))
                add, rem = add + a, rem + b
            res = {"added": add, "removed": rem, "changed": add + rem}
            try:
                ok, out = build(stack, task)
                res["built"] = ok
                if ok:
                    s = session(stack, task)
                    try:
                        if task == "counter":
                            steps, _ = oracle.counter_script(s, v2=True)
                            q, _, _ = oracle.counter_quit(s)
                            steps.append(q)
                        else:
                            first_step, first = oracle.dashboard_start(s, total=1000)
                            steps = [first_step]
                            if first is not None:
                                steps += oracle.dashboard_v2_steps(s)
                                code, rep = oracle.quit_and_restore(s, "ctrl-c")
                                steps.append(oracle.step("quit (Ctrl-C)", code in (0, 130, -2) and rep["restored"],
                                                         f"exit={code} restore={rep}"))
                    finally:
                        s.close()
                    res["steps"] = steps
                    res["passed"] = sum(1 for x in steps if x["ok"])
                    res["total"] = len(steps)
                else:
                    res["detail"] = out
            finally:
                patch(p, reverse=True)
                build(stack, task)
            update(result_path(stack, task), "change", res)
            log(f"change {stack} {task}: +{add} -{rem}, built={res.get('built')}, "
                f"oracle {res.get('passed')}/{res.get('total')}")


# -- mutants


def mutant_run(stack):
    """The counter key script, then the failure probe, on the built counter.

    Returns (crash, steps, raw): crash is a text when the program died before the script ended, else None; raw is
    the output of the key script run (the stdout-write probe reads it).
    """
    s = session(stack, "counter")
    try:
        steps, first = oracle.counter_script(s, start_timeout=20)
        if first is None:
            code = s.wait(0.1)
            if code is not None and code != 0:
                return f"exit {code} before the first frame", steps, bytes(s.raw)
            return None, steps, bytes(s.raw)
        if s.proc.poll() is not None:
            return f"exit {s.proc.returncode} during the key script", steps, bytes(s.raw)
        q, code, rep = oracle.counter_quit(s)
        steps.append(q)
        raw = bytes(s.raw)
    finally:
        s.close()
    s = session(stack, "counter")
    try:
        if s.expect(lambda x: "Count: 0" in x.text(), 20) is not None:
            time.sleep(0.3)
            outcome, code, rep = oracle.failure_probe(s, "+", oracle.count_on_row3(1))
            steps.append(oracle.step("failure probe", oracle.failure_ok(outcome, code, rep),
                                     f"{outcome} exit={code} restore={rep}"))
    finally:
        s.close()
    return None, steps, raw


# -- the defuse probes (mutants/classes.md, "Outcomes")
# claude 2026-10-20: created: the mechanical rule of "defused" (review fix: no outcome comes from a list that was
# written after the results)
#
# A mutant that passes every step of the oracle is "defused" only when the probe of its class shows that the bug
# has no effect on the user. A class with no probe, or a probe that sees the bug, gives "silent-wrong-behaviour".
# The probes are the same for every stack, and they run only on a mutant that passes the oracle.

MARK = re.compile(rb"count -?\d+")  # what each stdout-write mutant prints ("count 3"); the screen shows "Count: 3"
ALT = re.compile(rb"\x1b\[\?(?:1049|1047|47)([hl])")


def probe_stdout(stack, raw):
    """The text of the write never reaches the screen of the UI (the alternate screen, or the main screen of a
    stack that never enters the alternate screen)."""
    switches = [(m.start(), m.group(1) == b"h") for m in ALT.finditer(raw)]
    never = not any(on for _, on in switches)
    seen = []
    for m in MARK.finditer(raw):
        ui = never
        for pos, on in switches:
            if pos > m.start():
                break
            ui = on
        seen.append((m.start(), ui))
    on_ui = [pos for pos, ui in seen if ui]
    if on_ui:
        return False, f"the text 'count N' reaches the screen of the UI (byte {on_ui[0]} of {len(raw)})"
    if seen:
        return True, "the text 'count N' reaches the terminal only after the UI left the alternate screen"
    return True, "the text 'count N' never reaches the terminal"


def counter_session(stack):
    s = session(stack, "counter")
    if s.expect(lambda x: "Count: 0" in x.text(), 20) is None:
        s.close()
        return None
    time.sleep(0.3)
    return s


def probe_layout(stack, raw):
    """The screen of counter.md at four sizes: 100x30 (the start), then 120x40, 80x24 and 50x12."""
    s = counter_session(stack)
    if s is None:
        return False, "no first frame"
    try:
        for rows, cols in ((30, 100), (40, 120), (24, 80), (12, 50)):
            if (rows, cols) != (30, 100):
                s.resize(rows, cols)
                s.expect(lambda x: x.row(-1).rstrip() == oracle.HELP and x.row(3).rstrip() == "Count: 0", 1.5)
                time.sleep(0.3)
            ok, why = oracle.counter_shape(s, 0, False)
            if not ok:
                return False, f"at {cols}x{rows}: {why}"
        return True, "the screen is right at 100x30, 120x40, 80x24 and 50x12"
    finally:
        s.close()


UNBOUND = ("x", "z", "\x1b[H", "\x1b[3~", "\x1bOP", "\x1b[15~")  # x, z, Home, Delete, F1, F5


def probe_keys(stack, raw):
    """After six keys that are not bound, the program runs and the next '+' has its effect."""
    s = counter_session(stack)
    if s is None:
        return False, "no first frame"
    try:
        for k in UNBOUND:
            s.send(k)
            time.sleep(0.15)
        if s.proc.poll() is not None:
            return False, f"exit {s.proc.returncode} after the keys that are not bound"
        s.send("+")
        if s.expect(oracle.count_on_row3(1), 1.5) is None:
            return False, f"no 'Count: 1' after '+': {oracle.screen_dump(s)}"
        ok, why = oracle.counter_shape(s, 1, False)
        return ok, "after x, z, Home, Delete, F1 and F5, the next '+' gives 'Count: 1'" if ok else why
    finally:
        s.close()


def probe_updates(stack, raw):
    """20 '+' in one write, then 20 writes of '+' with no wait: 'Count: 40'."""
    s = counter_session(stack)
    if s is None:
        return False, "no first frame"
    try:
        s.send("+" * 20)
        if s.expect(oracle.count_on_row3(20), 3.0) is None:
            return False, f"20 '+' in one write: {s.row(3).strip()!r}"
        for _ in range(20):
            s.send("+")
        if s.expect(oracle.count_on_row3(40), 3.0) is None:
            return False, f"20 writes of '+' after 'Count: 20': {s.row(3).strip()!r}"
        return True, "20 '+' in one write and 20 writes with no wait give 'Count: 40'"
    finally:
        s.close()


PROBES = {
    "stdout-write": probe_stdout,
    "no-resize": probe_layout,
    "wrong-unit": probe_layout,
    "off-by-one": probe_layout,
    "non-exhaustive-key": probe_keys,
    "lost-update": probe_updates,
}


def classify(stack, cls, crash, steps, baseline, raw):
    """compile errors are found before; here: runtime-crash, test-or-oracle-catches, defused or silent."""
    import mutants as mu
    failed = {x["step"] for x in steps if not x["ok"]}
    new = sorted(failed - baseline)
    detail = "; ".join(f"{x['step']}: {x['detail'][:120]}" for x in steps if x["step"] in new)[:500]
    if crash:
        return "runtime-crash", crash + ("; " + detail if detail else "")
    if new:
        return "test-or-oracle-catches", detail
    note = mu.notes(stack).get(cls)
    note = f" Note: {note}." if note else ""
    probe = PROBES.get(cls)
    if probe is None:
        return "silent-wrong-behaviour", "every step that passes without the mutant passes with it; the class " \
                                         "has no defuse probe." + note
    harmless, why = probe(stack, raw)
    if harmless:
        return "defused", f"probe: {why}." + note
    return "silent-wrong-behaviour", f"every step of the oracle passes, and the probe sees the bug: {why}." + note


def mutants(args):
    import mutants as mu
    for stack in args.stacks:
        table = {}
        na = mu.not_applicable(stack)
        crash, steps, _ = mutant_run(stack)
        baseline = {x["step"] for x in steps if not x["ok"]}
        log(f"mutant {stack} baseline: failed steps {sorted(baseline) or 'none'}" + (f", crash {crash}" if crash else ""))
        for cls in mu.CLASSES:
            p = os.path.join(BENCH, "mutants", stack, f"{cls}.patch")
            if cls in na:
                table[cls] = {"outcome": "n/a", "detail": na[cls]}
                continue
            if not os.path.exists(p):
                table[cls] = {"outcome": "not-written", "detail": "no patch for this stack"}
                continue
            r = patch(p)
            if r.returncode != 0:
                table[cls] = {"outcome": "patch-error", "detail": (r.stdout + r.stderr)[-300:]}
                log(f"mutant {stack} {cls}: patch-error")
                continue
            try:
                ok, out = build(stack, "counter")
                if not ok:
                    table[cls] = {"outcome": "compile-error", "detail": out[:600]}
                else:
                    c, st_, raw = mutant_run(stack)
                    outcome, detail = classify(stack, cls, c, st_, baseline, raw)
                    table[cls] = {"outcome": outcome, "detail": detail}
            finally:
                patch(p, reverse=True)
            log(f"mutant {stack} {cls}: {table[cls]['outcome']}")
        ok, _ = build(stack, "counter")
        os.makedirs(RESULTS, exist_ok=True)
        with open(os.path.join(RESULTS, f"mutants-{stack}.json"), "w") as f:
            json.dump({"stack": stack, "machine": MACHINE, "baseline_failed": sorted(baseline), "rebuilt": ok,
                       "classes": table}, f, indent=1)
            f.write("\n")


# -- check (the gate of the CI job)
# claude 2026-10-20: created: the check of the results against the known failures and the reference mutants
# (review fix: the CI job failed only on a build failure)

# The failures that the bench knows, with the reason: (stack, task, section, step or row).
EXPECTED = {
    ("layoutz", "counter", "conformance", "start"): "layoutz cannot use the last row",
    ("layoutz", "counter", "conformance", "modes"): "layoutz never enters the alternate screen",
    ("layoutz", "counter", "conformance", "resize 80x24"): "layoutz has no resize event",
    ("layoutz", "dashboard", "conformance", "start"): "layoutz cannot use the last row",
    ("layoutz", "dashboard", "conformance", "modes"): "layoutz never enters the alternate screen",
    ("layoutz", "dashboard", "conformance", "resize 80x24"): "layoutz has no resize event",
    ("layoutz", "counter", "change", "start"): "the start step of the counter fails without the change",
    ("layoutz", "counter", "change", "modes"): "the modes step of the counter fails without the change",
    ("layoutz", "counter", "change", "resize 80x24"): "the resize step of the counter fails without the change",
    ("layoutz", "dashboard", "change", "start"): "the start step of the dashboard fails without the change",
    ("tamboui", "counter", "resilience", "failure"): "TamboUI shows an error screen, and the keys stop working",
    ("tamboui", "dashboard", "resilience", "failure"): "TamboUI shows an error screen, and the keys stop working",
}
REFERENCE = os.environ.get("BENCH_REFERENCE", "container-4vcpu")


def check(args):
    """Exits 1 when a step fails that EXPECTED does not list, a section is missing, or a mutant outcome differs
    from the reference machine."""
    errors = []
    for stack in args.stacks:
        for task in args.tasks:
            data = load_json(result_path(stack, task))
            for section in ("conformance", "resilience", "change"):
                sec = data.get(section)
                if sec is None:
                    if section != "change" or os.path.exists(os.path.join(st.stack_dir(stack), task, "change.patch")):
                        errors.append(f"{stack} {task} {section}: no result")
                    continue
                if section == "resilience":
                    failed = [row for row, v in sec.items() if not v.get("pass")]
                elif sec.get("applied") is False or sec.get("built") is False:
                    failed = ["(the patch does not apply or build)"]
                else:
                    failed = [x["step"] for x in sec.get("steps", []) if not x["ok"]]
                for name in failed:
                    if (stack, task, section, name) not in EXPECTED:
                        errors.append(f"{stack} {task} {section}: {name} fails")
        m = load_json(os.path.join(RESULTS, f"mutants-{stack}.json"))
        if not m:
            errors.append(f"{stack} mutants: no result")
            continue
        ref = {} if REFERENCE == MACHINE else load_json(os.path.join(BENCH, "results", REFERENCE,
                                                                     f"mutants-{stack}.json"))
        if not m.get("rebuilt"):
            errors.append(f"{stack} mutants: the counter was not built again after the mutants")
        for cls, v in m["classes"].items():
            if v["outcome"] == "patch-error":
                errors.append(f"{stack} mutant {cls}: the patch does not apply")
            want = ref.get("classes", {}).get(cls, {}).get("outcome")
            if want is not None and v["outcome"] != want:
                errors.append(f"{stack} mutant {cls}: {v['outcome']}, the reference {REFERENCE} has {want}")
    for e in errors:
        log("check:", e)
    log(f"check: {len(errors)} unexpected results" if errors else "check: all results as expected")
    return 1 if errors else 0


# -- peek (a debugging aid: print the screen after each key)


def peek(argv):
    stack, task, *keys = argv
    s = session(stack, task)
    try:
        time.sleep(3)
        print(s.text())
        for k in keys:
            s.send(k)
            time.sleep(0.5)
            print(f"===== after {k}")
            print(s.text())
    finally:
        s.close()


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "peek":
        return peek(sys.argv[2:])
    ap = argparse.ArgumentParser()
    ap.add_argument("command", choices=["perf", "conformance", "resilience", "change", "mutants", "all", "machine",
                                        "check"])
    ap.add_argument("--stacks", default=",".join(st.ORDER))
    ap.add_argument("--tasks", default=",".join(st.TASKS))
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--keys", type=int, default=50)
    ap.add_argument("--idle", type=float, default=5.0)
    ap.add_argument("--quick", action="store_true", help="one run, 10 keys, 2 s of idle (the CI smoke)")
    args = ap.parse_args()
    args.stacks = [x for x in args.stacks.split(",") if x]
    args.tasks = [x for x in args.tasks.split(",") if x]
    if args.quick:
        args.runs, args.keys, args.idle = 1, 10, 2.0
    if args.command == "check":
        sys.exit(check(args))
    machine()
    if args.command in ("conformance", "all"):
        conformance(args)
    if args.command in ("resilience", "all"):
        resilience(args)
    if args.command in ("perf", "all"):
        perf(args)
    if args.command in ("change", "all"):
        change(args)
    if args.command in ("mutants", "all"):
        mutants(args)


if __name__ == "__main__":
    main()
