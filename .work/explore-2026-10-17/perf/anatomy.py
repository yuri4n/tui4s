"""Startup anatomy: python t0 + app marks + -Xlog:class+load (tn) on one CLOCK_MONOTONIC time line.

Usage: python3 anatomy.py <dir> <prefix>   (reads <prefix>.json, <prefix>.marks, <prefix>.cl, <prefix>.st)
"""
import collections
import json
import os
import re
import sys

d, pre = sys.argv[1], sys.argv[2]
res = json.load(open(os.path.join(d, pre + ".json")))
t0 = res["t0_ns"]


def ms(ns):
    return (ns - t0) / 1e6


marks = []
mp = os.path.join(d, pre + ".marks")
if os.path.exists(mp):
    for line in open(mp):
        ns, thread, label = line.split(maxsplit=2)
        marks.append((int(ns), label.strip(), thread))

st = []
sp = os.path.join(d, pre + ".st")
if os.path.exists(sp):
    for line in open(sp):
        m = re.match(r"\[(\d+)ns\] (.*), ([0-9.]+) secs", line)
        if m:
            st.append((int(m.group(1)), m.group(2), float(m.group(3))))

LINE = re.compile(r"\[(\d+)ns\] (\S+) source: (.*)$")
jar_group = [
    ("scala-library", "Scala"), ("scala3-library", "Scala"),
    ("kyo-ui", "kyo-ui"), ("kyo-http", "kyo-http/net/ffi"), ("kyo-net", "kyo-http/net/ffi"),
    ("kyo-ffi", "kyo-http/net/ffi"), ("kyo-schema", "kyo-schema"),
    ("kyo-", "Kyo (core/kernel/data/prelude/scheduler/...)"),
    ("jatatui", "jatatui+crossterm"), ("tui4s-core", "tui4s"), ("tui4s-examples", "app (examples)"),
    ("fansi", "other jars"), ("pprint", "other jars"), ("sourcecode", "other jars"), ("jmh", "other jars"),
    ("commons-math", "other jars"), ("asm", "other jars"), ("jopt", "other jars"),
]


def group_of_source(src, name, known):
    if src.startswith("shared objects file") or src.startswith("jrt:/"):
        return "JDK"
    if src.startswith("file:"):
        for k, g in jar_group:
            if k in src:
                return g
        return "other jars"
    # a hidden class (lambda) or a class defined at run time: the group of its host
    if src.startswith("__"):
        host = name.split("$$Lambda")[0].split("/")[0]
        if host.startswith(("java.", "jdk.", "sun.", "com.sun.")):
            return "JDK"
        return known.get(host, "JDK" if name.startswith(("java.", "jdk.", "sun.")) else "hidden (unknown host)")
    host = src.strip()
    if host.startswith(("java.", "jdk.", "sun.", "com.sun.")):
        return "JDK"
    return known.get(host, "hidden (unknown host)")


classes = []
known = {}
for line in open(os.path.join(d, pre + ".cl")):
    m = LINE.match(line.strip())
    if not m:
        continue
    ns, name, src = int(m.group(1)), m.group(2), m.group(3)
    g = group_of_source(src, name, known)
    known[name] = g
    classes.append((ns, name, g, src))

# phases between consecutive marks (plus JVM start and python frame)
points = [(t0, "spawn")]
for ns, name, secs in st:
    if name == "Create VM":
        points.append((ns, "Create VM done"))
for ns, label, _ in marks:
    points.append((ns, label))
if res.get("frame") is not None:
    points.append((t0 + int(res["frame"] * 1e6), "PTY: frame seen"))
points.sort()

groups = ["JDK", "Scala", "Kyo (core/kernel/data/prelude/scheduler/...)", "kyo-ui", "kyo-http/net/ffi", "kyo-schema",
          "jatatui+crossterm", "tui4s", "app (examples)", "other jars", "hidden (unknown host)"]
short = {"Kyo (core/kernel/data/prelude/scheduler/...)": "Kyo", "jatatui+crossterm": "jatatui", "app (examples)": "app",
         "hidden (unknown host)": "hid?", "kyo-http/net/ffi": "http/net/ffi", "kyo-schema": "schema", "other jars": "other"}
hdr = f"{'phase (from -> to)':44s} {'at ms':>8s} {'dur ms':>8s} " + " ".join(f"{short.get(g, g):>6s}" for g in groups)
print(hdr)
ci = 0
for i in range(1, len(points)):
    a, b = points[i - 1], points[i]
    cnt = collections.Counter()
    while ci < len(classes) and classes[ci][0] <= b[0]:
        if classes[ci][0] > a[0] or i == 1:
            cnt[classes[ci][2]] += 1
        ci += 1
    print(f"{(a[1] + ' -> ' + b[1])[:44]:44s} {ms(b[0]):8.1f} {(b[0] - a[0]) / 1e6:8.1f} "
          + " ".join(f"{cnt.get(g, 0):6d}" for g in groups))
tail = collections.Counter(c[2] for c in classes[ci:])
print(f"{'after the frame (until exit)':44s} {'':8s} {'':8s} " + " ".join(f"{tail.get(g, 0):6d}" for g in groups))
tot = collections.Counter(c[2] for c in classes)
print(f"{'TOTAL':44s} {'':8s} {'':8s} " + " ".join(f"{tot.get(g, 0):6d}" for g in groups), " all:", len(classes))
first_frame_ns = t0 + int(res["frame"] * 1e6) if res.get("frame") else None
if first_frame_ns:
    before = collections.Counter(c[2] for c in classes if c[0] <= first_frame_ns)
    print("classes before the first frame:", sum(before.values()), dict(before))
jdk_shared = sum(1 for c in classes if c[3].startswith("shared objects file"))
print("JDK classes from the CDS archive:", jdk_shared, " JDK classes parsed from jrt:", sum(1 for c in classes if c[3].startswith("jrt:")))
print("JVM startuptime:", "; ".join(f"{n} {s * 1000:.1f} ms" for _, n, s in st if n in ("Create VM", "Initialize module system", "Genesis")))
print("PTY:", {k: res.get(k) for k in ("alt_enter", "first_text", "frame", "quit_sent", "alt_leave", "exit", "maxrss_kb", "hwm_before_quit")})
