"""Median time (ms from spawn) of each app mark over runs: python3 phases.py <dir> <prefix> [<prefix> ...]."""
import glob
import json
import os
import statistics
import sys

d = sys.argv[1]
labels = ["ctor", "prewarm-jni", "prewarm-tty", "main", "block", "fiber-started", "enter", "jni-loaded", "isatty",
          "alt-screen", "session-open", "ui-build", "ui-built", "run", "mounted", "settled", "frame0-start",
          "frame0-layout", "frame0-paint", "frame0-flush"]
cols = sys.argv[2:]
table = {}
for pre in cols:
    acc = {}
    for js in sorted(glob.glob(os.path.join(d, pre + "-*.json"))):
        res = json.load(open(js))
        t0 = res["t0_ns"]
        seen = set()
        for line in open(js[:-5] + ".marks"):
            ns, _, label = line.split(maxsplit=2)
            label = label.strip()
            if label in seen:
                continue
            seen.add(label)
            acc.setdefault(label, []).append((int(ns) - t0) / 1e6)
        acc.setdefault("PTY frame", []).append(res["frame"])
    table[pre] = acc
print("| mark (ms from spawn, median) | " + " | ".join(cols) + " |")
print("|---" * (len(cols) + 1) + "|")
for lab in labels + ["PTY frame"]:
    cells = []
    for pre in cols:
        v = table[pre].get(lab)
        cells.append(f"{statistics.median(v):.0f}" if v else "")
    if any(cells):
        print(f"| {lab} | " + " | ".join(cells) + " |")
