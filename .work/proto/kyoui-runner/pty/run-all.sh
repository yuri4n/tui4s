#!/usr/bin/env bash
# claude 2026-10-03: created: runs the PTY checklist, then the screen checks. Usage: run-all.sh <assembly jar>
here=$(cd "$(dirname "$0")" && pwd)
export KYOUI_JAR=$(readlink -f "$1")
export PTY_WORK=${PTY_WORK:-$(mktemp -d)}
logs="$here/../logs"
rm -f "$logs"/pty-session.log "$logs"/pty-notes.log "$logs"/pty-stty-*.log
echo "jar=$KYOUI_JAR work=$PTY_WORK date=$(date -Is) load=$(cut -d' ' -f1-3 /proc/loadavg)" | tee "$logs/pty-checklist.log"
expect "$here/checklist.exp" 2>&1 | tee -a "$logs/pty-checklist.log"
python3 "$here/check.py" 2>&1 | tee "$logs/pty-check.log"
if [ -s "$PTY_WORK/tui4s.log" ]; then cp "$PTY_WORK/tui4s.log" "$logs/pty-app-output.log"; echo "app stdout/stderr: logs/pty-app-output.log"; else echo "app stdout/stderr file: empty or absent"; fi
