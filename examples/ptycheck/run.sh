#!/usr/bin/env bash
# Runs the real-terminal checklist of the examples in a pseudo-terminal, with
# no human: it writes the start scripts (sbt examples/launchers, one sbt
# process), starts each example in a pseudo-terminal with expect
# (checklist.exp), sends keys, checks raw mode and `stty -a` from outside,
# then replays the output in a small terminal emulator (vt.py) and checks the
# screens (check.py). It exits with 1 when a row fails.
# Needs expect and python3, and JDK 25 on PATH or in JAVA_HOME.
# Usage, from anywhere: examples/ptycheck/run.sh
# Environment: PTY_SKIP_BUILD=1 uses the scripts in examples/target/bin as
# they are (no sbt); PTY_WORK=<dir> keeps the logs there (default: a new
# directory under $TMPDIR).
# claude 2026-10-13: changed (T1, RC-31 part): the run of the whole kit (the
# prototype's run.sh started one main of an assembly jar; PR 5's run.sh built
# the launchers and ran analyze.py, which vt.py and check.py replace)
set -uo pipefail
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
cd "$root" || exit 1
work=${PTY_WORK:-$(mktemp -d "${TMPDIR:-/tmp}/tui4s-ptycheck.XXXXXX")}
mkdir -p "$work"
echo "ptycheck: work=$work date=$(date -Is) load=$(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)"
if [ "${PTY_SKIP_BUILD:-}" != "1" ]; then
    echo "ptycheck: sbt examples/launchers (log: $work/launchers.log)"
    sbt -Dsbt.supershell=false examples/launchers > "$work/launchers.log" 2>&1 ||
        { echo "ptycheck: sbt examples/launchers failed (see $work/launchers.log)"; exit 1; }
fi
[ -x examples/target/bin/counter ] || { echo "ptycheck: examples/target/bin/counter is missing"; exit 1; }
# The start scripts run "$JAVA_HOME/bin/java" (or java on PATH): it must be
# JDK 25 or later (Kyo 1.0.0-RC7, and the flag
# --sun-misc-unsafe-memory-access).
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
java_major=$("$java_bin" -XshowSettings:properties -version 2>&1 |
    sed -n 's/^ *java.specification.version = //p')
if [ -z "$java_major" ] || [ "$java_major" -lt 25 ] 2>/dev/null; then
    echo "ptycheck: $java_bin is Java ${java_major:-unknown}; the examples need JDK 25 (set JAVA_HOME)"
    exit 1
fi
echo "ptycheck: java $java_bin (Java $java_major)"
# The JVM prints "Picked up JAVA_TOOL_OPTIONS: ..." (and the same for
# JDK_JAVA_OPTIONS) on stderr when one of them is set, for example by the
# proxy setup of a container. That line would land in the terminal under test,
# and the examples need no network: the runs of the apps do not get them. sbt
# above keeps them (it may need the proxy).
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
# check.py imports vt.py: no bytecode files in the source tree.
export PYTHONDONTWRITEBYTECODE=1
status=0
expect "$here/checklist.exp" "$root/examples/target/bin" "$work" "$work/session.log" || status=1
python3 "$here/check.py" "$work/session.log" "$work/screens.txt" || status=1
echo "ptycheck: raw terminal output: $work/session.log; replayed screens: $work/screens.txt"
if [ $status -eq 0 ]; then echo "ptycheck: ALL ROWS PASSED"; else echo "ptycheck: A ROW FAILED"; fi
exit $status
