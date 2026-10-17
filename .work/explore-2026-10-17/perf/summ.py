"""Summarizes bench JSON lines: mode x app -> first frame median/min/max, alt-screen median, RSS (VmHWM) median."""
import collections
import json
import statistics
import sys

rows = collections.defaultdict(list)
bad = collections.Counter()
for path in sys.argv[1:]:
    for line in open(path):
        d = json.loads(line)
        if d.get("error") or d.get("timeout") or d.get("frame") is None or d.get("exit_code") != 0:
            bad[(d.get("mode"), d.get("app"))] += 1
            continue
        rows[(d["mode"], d["app"])].append(d)


def kb(s):
    return int(s.split()[0]) if s else None


def med(xs):
    return statistics.median(xs)


order = ["launcher", "launcher-novc", "direct", "xshare-off", "c1", "serialgc", "noverify", "noprobe", "jnipath", "prewarm-native",
         "prewarm-all", "best-noaot", "aot", "aot-other-app", "best", "best+serialgc", "best+c1", "best+c1+serialgc", "flags-only-noaot", "flags-only+aot", "code+flags-noaot", "plain-jatatui"]
print(f"| mode | app | N | first frame median ms | min | max | alt-screen median ms | RSS (VmHWM) median MB | max RSS (wait4) median MB |")
print("|---|---|---|---|---|---|---|---|---|")
for m in order:
    for a in ("counter", "dashboard", "plain"):
        rs = rows.get((m, a))
        if not rs:
            continue
        fr = [r["frame"] for r in rs]
        alt = [r["alt_enter"] for r in rs]
        hwm = [kb(r["hwm_before_quit"]) for r in rs if r.get("hwm_before_quit")]
        mx = [r["maxrss_kb"] for r in rs]
        print(f"| {m} | {a} | {len(rs)} | {med(fr):.0f} | {min(fr):.0f} | {max(fr):.0f} | {med(alt):.0f} | "
              f"{med(hwm) / 1024:.0f} | {med(mx) / 1024:.0f} |")
if bad:
    print("failed runs:", dict(bad))
