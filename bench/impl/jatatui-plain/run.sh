#!/usr/bin/env bash
# Runs one task in the current terminal: run.sh counter|dashboard.
# claude 2026-10-20: created (L5)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
case "${1:-}" in
  counter) main=Counter ;;
  dashboard) main=Dashboard ;;
  *) echo "usage: run.sh counter|dashboard" >&2; exit 2 ;;
esac
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" --enable-native-access=ALL-UNNAMED \
  -cp "$here/$1/target/universal/stage/lib/*" "$main"
