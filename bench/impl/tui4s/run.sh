#!/usr/bin/env bash
# Runs one task in the current terminal: run.sh counter|dashboard.
# The JVM flags are the flags of a tui4s app (see AGENTS.md of tui4s).
# claude 2026-10-20: created: the start script of the tui4s stack (L5)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
case "${1:-}" in
  counter) main=CounterApp ;;
  dashboard) main=DashboardApp ;;
  *) echo "usage: run.sh counter|dashboard" >&2; exit 2 ;;
esac
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow -Xss16m \
  -cp "$here/$1/target/universal/stage/lib/*" "$main"
