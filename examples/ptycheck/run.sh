#!/usr/bin/env bash
# Runs the real-terminal checklist of examples/README.md in a pseudo-terminal,
# with no human: it starts each example, sends keys, reads the screen output,
# and checks with `stty` that the terminal is back in cooked mode after each run.
# Needs expect and python3 (both come with macOS). JDK 25 on PATH or in JAVA_HOME.
# Usage, from the repository root: examples/ptycheck/run.sh
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
sbt -batch examples/launchers > /dev/null || { echo "sbt examples/launchers failed"; exit 1; }
work=$(mktemp -d "${TMPDIR:-/tmp}/tui4s-ptycheck.XXXXXX")
mkdir -p "$work/home"
status=0
expect examples/ptycheck/checklist.exp "$PWD/examples/target/bin" "$work/home" "$work/session.log" || status=1
python3 examples/ptycheck/analyze.py "$work/session.log" || status=1
echo "Raw terminal output: $work/session.log"
exit $status
