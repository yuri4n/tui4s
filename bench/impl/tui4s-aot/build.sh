#!/usr/bin/env bash
# Trains the AOT cache of each task of the tui4s stack (or of one task):
# build.sh [counter|dashboard]. It needs the build of bench/impl/tui4s (its
# build.sh, or bench/run.sh build). It starts the task with run.sh and
# -XX:AOTCacheOutput in a pseudo-terminal (python3 pty), sends the keys of the
# task, quits with q, and keeps <task>.aot here. Train again after a change of
# the JDK or of the jars of bench/impl/tui4s.
# claude 2026-10-20: created (L5b): the AOT mode of the tui4s stack
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
case "${1:-all}" in
  counter | dashboard) tasks=$1 ;;
  all) tasks="counter dashboard" ;;
  *) echo "usage: build.sh [counter|dashboard]" >&2; exit 2 ;;
esac
for task in $tasks; do
  lib="$here/../tui4s/$task/target/universal/stage/lib"
  [ -d "$lib" ] || { echo "build.sh: no $lib: build bench/impl/tui4s first" >&2; exit 1; }
  rm -f "$here/$task.aot"
  TUI4S_AOT_FLAGS="-XX:AOTCacheOutput=$here/$task.aot.part" python3 -I - "$here/run.sh" "$task" <<'PY'
import os, pty, select, sys, time
run, task = sys.argv[1], sys.argv[2]
keys = ["+", "+", "+", "-", "\x1b[A", "\x1b[B"] if task == "counter" else ["\x1b[B"] * 3 + ["\x1b[6~", "\t", "\t"]
env = {k: v for k, v in os.environ.items() if k in ("PATH", "JAVA_HOME", "LANG", "TUI4S_AOT_FLAGS")}
env["TERM"] = "xterm-256color"
pid, fd = pty.fork()
if pid == 0:
    os.execve(run, [run, task], env)
seen, end = b"", time.monotonic() + 60
def pump(s):
    global seen
    stop = time.monotonic() + s
    while time.monotonic() < stop:
        if select.select([fd], [], [], 0.05)[0]:
            try: seen += os.read(fd, 65536)
            except OSError: return
while b"\x1b[?1049h" not in seen and time.monotonic() < end:
    pump(0.2)
pump(1.5)
for k in keys + ["q"]:
    os.write(fd, k.encode()); pump(0.3)
for _ in range(600):
    if os.waitpid(pid, os.WNOHANG)[0] == pid: break
    pump(0.1)
PY
  [ -s "$here/$task.aot.part" ] || { echo "build.sh: $task: the JVM wrote no cache" >&2; exit 1; }
  mv "$here/$task.aot.part" "$here/$task.aot"
  echo "build.sh: $task.aot ($(du -h "$here/$task.aot" | cut -f1))"
done
