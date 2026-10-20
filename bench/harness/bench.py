"""The bench harness: performance, conformance, resilience, the change request and the mutants, in a PTY.

Usage: python3 bench/harness/bench.py <command> [--stacks a,b] [--tasks counter,dashboard] [--runs N] [--quick]
Commands: perf, conformance, resilience, change, mutants, all, machine, peek <stack> <task> [keys...]

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
import json
import os
import platform
import signal
import statistics
import subprocess
import sys
import time

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
            ok = rep["restored"] and not outcome.endswith("hung") and (outcome != "ended" or code != 0)
            return {"pass": ok, "outcome": outcome, "exit": code, **rep}
        if row == "quit":
            s.send("q")
        elif row == "ctrl-c":
            s.send("ctrl-c")
        elif row == "sigterm":
            s.signal(signal.SIGTERM)
        elif row == "sigint":
            s.signal(signal.SIGINT)
        elif row == "kill-mid-frame":
            s.send(("+" if task == "counter" else "\x1b[B") * 30)
            s.signal(signal.SIGTERM)
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
        return {"pass": ok, "outcome": "ended", "exit": code, "seconds": took, "note": note, **rep}
    finally:
        s.close()


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


def build(stack, task):
    env = dict(os.environ)
    env.setdefault("SBT", "sbt --client")
    r = subprocess.run([os.path.join(st.stack_dir(stack), "build.sh"), task], capture_output=True, text=True,
                       env=env, timeout=900)
    out = r.stdout + r.stderr
    errors = [l for l in out.splitlines() if "error" in l.lower() and "WARNING" not in l]
    return r.returncode == 0, ("\n".join(errors[:12]) or out[-2000:])


def changed_lines(path):
    """The added and removed lines of a patch, without blank lines and comment lines."""
    add = rem = 0
    with open(path) as f:
        for line in f:
            if line.startswith(("+++", "---")):
                continue
            if line[:1] in "+-":
                body = line[1:].strip()
                if not body or body.startswith(("//", "#", "/*", "*")):
                    continue
                if line[0] == "+":
                    add += 1
                else:
                    rem += 1
    return add, rem


def change(args):
    for stack in args.stacks:
        for task in args.tasks:
            p = os.path.join(st.stack_dir(stack), task, "change.patch")
            if not os.path.exists(p):
                continue
            add, rem = changed_lines(p)
            res = {"added": add, "removed": rem, "changed": add + rem}
            r = patch(p)
            if r.returncode != 0:
                res.update(applied=False, detail=r.stdout + r.stderr)
                update(result_path(stack, task), "change", res)
                log(f"change {stack} {task}: patch does not apply")
                continue
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

    Returns (crash, steps): crash is a text when the program died before the script ended, else None.
    """
    s = session(stack, "counter")
    try:
        steps, first = oracle.counter_script(s, start_timeout=20)
        if first is None:
            code = s.wait(0.1)
            if code is not None and code != 0:
                return f"exit {code} before the first frame", steps
            return None, steps
        if s.proc.poll() is not None:
            return f"exit {s.proc.returncode} during the key script", steps
        q, code, rep = oracle.counter_quit(s)
        steps.append(q)
    finally:
        s.close()
    s = session(stack, "counter")
    try:
        if s.expect(lambda x: "Count: 0" in x.text(), 20) is not None:
            time.sleep(0.3)
            outcome, code, rep = oracle.failure_probe(s, "+", oracle.count_on_row3(1))
            steps.append(oracle.step("failure probe", rep["restored"] and not outcome.endswith("hung"),
                                     f"{outcome} exit={code} restore={rep}"))
    finally:
        s.close()
    return None, steps


def classify(stack, cls, crash, steps, baseline):
    """compile errors are found before; here: runtime-crash, test-or-oracle-catches, defused or silent."""
    import mutants as mu
    failed = {x["step"] for x in steps if not x["ok"]}
    new = sorted(failed - baseline)
    detail = "; ".join(f"{x['step']}: {x['detail'][:120]}" for x in steps if x["step"] in new)[:500]
    if crash:
        return "runtime-crash", crash + ("; " + detail if detail else "")
    if new:
        return "test-or-oracle-catches", detail
    if cls in mu.defused(stack):
        return "defused", mu.defused(stack)[cls]
    return "silent-wrong-behaviour", "every step that passes without the mutant passes with it"


def mutants(args):
    import mutants as mu
    for stack in args.stacks:
        table = {}
        na = mu.not_applicable(stack)
        crash, steps = mutant_run(stack)
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
                    table[cls] = {"outcome": "compile-error", "detail": out[-400:]}
                else:
                    c, st_ = mutant_run(stack)
                    outcome, detail = classify(stack, cls, c, st_, baseline)
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
    ap.add_argument("command", choices=["perf", "conformance", "resilience", "change", "mutants", "all", "machine"])
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
