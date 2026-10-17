"""Runs the startup matrix (mode x app), N rounds, in a shuffled order per round; appends JSON lines.

Usage: python3 bench.py <out.jsonl> <rounds> [mode,mode,...]
"""
import json
import os
import random
import subprocess
import sys

P = os.path.dirname(os.path.abspath(__file__))
PR = f"{P}/stage-pristine"
EX = f"{P}/stage-exp"
MAIN = {"counter": "examples.counter.CounterApp", "dashboard": "examples.dashboard.DashboardApp"}
NOPROBE = "-Dkyo.scheduler.blockingMonitorMinIntervalNs=12800000"
JNI = f"-Djava.library.path={P}/jnidir"
BEST = [NOPROBE, "-Dtui4s.prewarm.native=true", JNI]
OTHER = {"counter": "dashboard", "dashboard": "counter"}


def direct(stage, app, *flags):
    return [f"{P}/direct.sh", stage, MAIN[app], *flags]


MODES = {
    "launcher": lambda app: [f"{PR}/bin/{app}", "--"],
    "direct": lambda app: direct(PR, app),
    "xshare-off": lambda app: direct(PR, app, "-Xshare:off"),
    "c1": lambda app: direct(PR, app, "-XX:TieredStopAtLevel=1"),
    "serialgc": lambda app: direct(PR, app, "-XX:+UseSerialGC"),
    "noverify": lambda app: direct(PR, app, "-XX:+UnlockDiagnosticVMOptions", "-XX:-BytecodeVerificationRemote"),
    "noprobe": lambda app: direct(PR, app, NOPROBE),
    "jnipath": lambda app: direct(PR, app, JNI),
    "prewarm-native": lambda app: direct(EX, app, "-Dtui4s.prewarm.native=true"),
    "prewarm-all": lambda app: direct(EX, app, "-Dtui4s.prewarm.native=true",
                                      f"-Dtui4s.prewarm.list={P}/prewarm-{app[0]}.txt"),
    "best-noaot": lambda app: direct(EX, app, *BEST),
    "aot": lambda app: direct(PR, app, "-XX:AOTMode=on", f"-XX:AOTCache={P}/aot/{app}.aot"),
    "aot-other-app": lambda app: direct(PR, app, "-XX:AOTMode=on", f"-XX:AOTCache={P}/aot/{OTHER[app]}.aot"),
    "best": lambda app: direct(EX, app, *BEST, "-XX:AOTMode=on", f"-XX:AOTCache={P}/aot-best/{app}.aot"),
    "best+serialgc": lambda app: direct(EX, app, *BEST, "-XX:+UseSerialGC", "-XX:AOTMode=on",
                                        f"-XX:AOTCache={P}/aot-best/{app}.aot"),
    "best+c1": lambda app: direct(EX, app, *BEST, "-XX:TieredStopAtLevel=1", "-XX:AOTMode=on",
                                  f"-XX:AOTCache={P}/aot-best/{app}.aot"),
    "best+c1+serialgc": lambda app: direct(EX, app, *BEST, "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC",
                                           "-XX:AOTMode=on", f"-XX:AOTCache={P}/aot-best/{app}.aot"),
    "flags-only-noaot": lambda app: direct(PR, app, "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", NOPROBE, JNI),
    "flags-only+aot": lambda app: direct(PR, app, "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", NOPROBE, JNI,
                                         "-XX:AOTMode=on", f"-XX:AOTCache={P}/aot/{app}.aot"),
    "launcher-novc": lambda app: [f"{P}/stage-novc/bin/{app}", "--"],
    "code+flags-noaot": lambda app: direct(EX, app, *BEST, f"-Dtui4s.prewarm.list={P}/prewarm-{app[0]}.txt",
                                           "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"),
    "plain-jatatui": lambda app: [f"{P}/plain/run.sh"],
}


def main():
    out, rounds = sys.argv[1], int(sys.argv[2])
    modes = sys.argv[3].split(",") if len(sys.argv) > 3 else list(MODES)
    combos = [(m, a) for m in modes for a in ("counter", "dashboard") if not (m == "plain-jatatui" and a == "dashboard")]
    rng = random.Random(42)
    with open(out, "a") as f:
        for r in range(rounds):
            order = combos[:]
            rng.shuffle(order)
            for m, a in order:
                cmd = MODES[m](a)
                load = open("/proc/loadavg").read().split()[0]
                p = subprocess.run([sys.executable, f"{P}/ptytime.py", "--expect", "q quits", "--hold-ms", "300",
                                    "--", *cmd], capture_output=True, text=True)
                try:
                    res = json.loads(p.stdout.strip().splitlines()[-1])
                except Exception:
                    res = {"error": p.stdout[-500:] + p.stderr[-500:]}
                res.update({"mode": m, "app": "plain" if m == "plain-jatatui" else a, "round": r, "load1": load})
                f.write(json.dumps(res) + "\n")
                f.flush()


if __name__ == "__main__":
    main()
