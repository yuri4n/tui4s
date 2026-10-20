"""Writes the tables of bench/README.md and bench/PERFORMANCE.md from the JSON results.

Each table goes between `<!-- report:begin NAME -->` and `<!-- report:end NAME -->` in its page; the text around
the markers is not changed. Usage: python3 bench/harness/report.py [machine]  (default: $BENCH_MACHINE, else the
only directory in bench/results besides static/).
"""
# claude 2026-10-20: created (L5): the tables of the bench pages

import json
import os
import re
import sys

import stacks as st
from term import BENCH

NAMES = {"tui4s": "tui4s", "jatatui-plain": "jatatui-plain", "tamboui": "TamboUI", "layoutz": "layoutz",
         "ratatui": "ratatui", "bubbletea": "bubbletea", "textual": "Textual", "ink": "ink"}


def load(path):
    if not os.path.exists(path):
        return {}
    with open(path) as f:
        return json.load(f)


def table(head, rows):
    out = ["| " + " | ".join(head) + " |", "| " + " | ".join("---" if i == 0 else "---:" for i in range(len(head))) + " |"]
    out += ["| " + " | ".join("" if c is None else str(c) for c in r) + " |" for r in rows]
    return "\n".join(out)


def fmt(v, unit="", digits=1):
    if v is None:
        return "-"
    if isinstance(v, float):
        v = round(v, digits)
        if digits == 0:
            v = int(v)
    return f"{v}{unit}"


def concepts():
    path = os.path.join(BENCH, "tasks", "concepts.md")
    if not os.path.exists(path):
        return {}
    text = open(path).read()
    out = {}
    for name, key in [(v, k) for k, v in NAMES.items()]:
        m = re.search(rf"^\| {re.escape(name)} \| (\d+) \| (\d+) \|", text, re.M)
        if m:
            out[key] = (int(m.group(1)), int(m.group(2)))
    return out


def blocks(machine):
    res = os.path.join(BENCH, "results", machine)
    info = load(os.path.join(res, "machine.json"))
    comp = load(os.path.join(BENCH, "results", "static", "compactness.json"))
    con = concepts()
    data = {(s, t): load(os.path.join(res, f"{s}-{t}.json")) for s in st.ORDER for t in st.TASKS}
    out = {}

    out["machine"] = (f"Machine `{machine}`: {info.get('cpu', '?')}, {info.get('cpus', '?')} CPUs, "
                      f"{info.get('memory_gb', '?')} GB, Linux {info.get('kernel', '?')}, measured "
                      f"{info.get('date', '?')}. {info.get('note', '')}").strip()

    rows = []
    for s in st.ORDER:
        r = [NAMES[s]]
        for t in st.TASKS:
            c = comp.get(f"{s}-{t}", {})
            r += [c.get("lines"), c.get("tokens"), c.get("gzip_bytes")]
        rows.append(r)
    out["compactness"] = table(["Stack", "counter lines", "tokens", "gzip B", "dashboard lines", "tokens",
                                "gzip B"], rows)

    rows = []
    for s in st.ORDER:
        r = [NAMES[s]]
        for t in st.TASKS:
            ch = data[(s, t)].get("change", {})
            ok = f"{ch.get('passed')}/{ch.get('total')}" if ch.get("total") else ("build failed" if ch.get("built") is False else "-")
            r += [con.get(s, (None, None))[st.TASKS.index(t)], ch.get("changed"), ok]
        rows.append(r)
    out["expressiveness"] = table(["Stack", "counter concepts", "change lines", "change oracle",
                                   "dashboard concepts", "change lines", "change oracle"], rows)

    rows = []
    for s in st.ORDER:
        r = [NAMES[s]]
        for t in st.TASKS:
            c = data[(s, t)].get("conformance", {})
            r.append(f"{c.get('passed')}/{c.get('total')}" if c else "-")
            failed = [x["step"] for x in c.get("steps", []) if not x["ok"]]
            r.append(", ".join(failed) if failed else "")
        rows.append(r)
    out["conformance"] = table(["Stack", "counter steps", "counter failed", "dashboard steps",
                                "dashboard failed"], rows)

    rows = []
    cols = ["quit", "ctrl-c", "sigterm", "sigint", "failure", "kill-mid-frame"]
    for s in st.ORDER:
        for t in st.TASKS:
            rz = data[(s, t)].get("resilience", {})
            if not rz:
                continue
            cells = []
            for c in cols:
                v = rz.get(c, {})
                mark = "ok" if v.get("pass") else "FAIL"
                extra = v.get("outcome") if c == "failure" else f"exit {v.get('exit')}"
                if v.get("alt_screen") == "never-entered":
                    extra += ", no alt screen"
                cells.append(f"{mark} ({extra})")
            rows.append([f"{NAMES[s]} {t}"] + cells)
    out["resilience"] = table(["Stack, task"] + cols, rows)

    rows = []
    kinds = ["compile-error", "runtime-crash", "test-or-oracle-catches", "defused", "silent-wrong-behaviour", "n/a"]
    for s in st.ORDER:
        m = load(os.path.join(res, f"mutants-{s}.json"))
        if not m:
            continue
        counts = {k: 0 for k in kinds}
        for v in m["classes"].values():
            counts[v["outcome"]] = counts.get(v["outcome"], 0) + 1
        rows.append([NAMES[s]] + [counts.get(k, 0) for k in kinds] + [sum(counts.values()) - counts["n/a"]])
    out["mutants"] = table(["Stack"] + kinds + ["mutants run"], rows)

    rows = []
    classes = None
    for s in st.ORDER:
        m = load(os.path.join(res, f"mutants-{s}.json"))
        if not m:
            continue
        classes = classes or list(m["classes"])
        short = {"compile-error": "compile", "runtime-crash": "crash", "test-or-oracle-catches": "oracle",
                 "defused": "defused", "silent-wrong-behaviour": "SILENT", "n/a": "n/a"}
        rows.append([NAMES[s]] + [short.get(m["classes"][c]["outcome"], m["classes"][c]["outcome"]) for c in classes])
    if classes:
        out["mutant-matrix"] = table(["Stack"] + classes, rows)

    for t in st.TASKS:
        rows = []
        for s in st.ORDER:
            p = data[(s, t)].get("perf", {})
            r = [NAMES[s], fmt(p.get("first_frame_ms"), "", 0), fmt(p.get("first_key_ms")), fmt(p.get("key_median_ms")),
                 fmt(p.get("key_p90_ms")), fmt(p.get("cpu_pct")), fmt(p.get("rss_mb"), "", 0), fmt(p.get("artifact_mb"))]
            if t == "dashboard":
                r += [fmt(p.get("tick_gap_p50_ms")), fmt(p.get("tick_gap_p90_ms")), fmt(p.get("ticks_skipped_pct"))]
            rows.append(r)
        cpu = "CPU idle %" if t == "counter" else "CPU at 10 Hz %"
        head = ["Stack", "first frame ms", "first key ms", "key median ms", "key p90 ms", cpu, "RSS MB", "artifact MB"]
        if t == "dashboard":
            head += ["tick gap p50 ms", "tick gap p90 ms", "ticks skipped %"]
        for mode in ("tui4s AOT cache (JDK 25)", "tui4s native image (GraalVM)"):
            rows.append([mode] + ["measured in L5b"] + [""] * (len(head) - 2))
        out[f"perf-{t}"] = table(head, rows)
    return out


def fill(path, parts):
    text = open(path).read()
    for name, body in parts.items():
        pat = re.compile(rf"(<!-- report:begin {re.escape(name)} -->\n).*?(<!-- report:end {re.escape(name)} -->)", re.S)
        text = pat.sub(lambda m: m.group(1) + body + "\n" + m.group(2), text)
    with open(path, "w") as f:
        f.write(text)


def main():
    machine = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("BENCH_MACHINE")
    if not machine:
        dirs = [d for d in os.listdir(os.path.join(BENCH, "results")) if d != "static"]
        machine = sorted(dirs)[0]
    parts = blocks(machine)
    fill(os.path.join(BENCH, "README.md"), parts)
    fill(os.path.join(BENCH, "PERFORMANCE.md"), parts)
    print(f"report: wrote the tables of {machine} into bench/README.md and bench/PERFORMANCE.md")


if __name__ == "__main__":
    main()
