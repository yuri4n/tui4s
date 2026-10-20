#!/usr/bin/env bash
# Runs one task of the tui4s stack with its JDK 25 AOT cache and the start
# flags of the tui4s examples (ADR 0015): run.sh counter|dashboard.
# The program is the program of bench/impl/tui4s (the same jars); build.sh
# trains the cache. Without a cache this script stops: the numbers of this
# stack are the numbers with a cache.
# claude 2026-10-20: created (L5b): the AOT mode of the tui4s stack
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
case "${1:-}" in
  counter) main=CounterApp ;;
  dashboard) main=DashboardApp ;;
  *) echo "usage: run.sh counter|dashboard" >&2; exit 2 ;;
esac
cache="$here/$1.aot"
# TUI4S_AOT_FLAGS: the training of build.sh (-XX:AOTCacheOutput=...).
if [ -n "${TUI4S_AOT_FLAGS:-}" ]; then
  aot=("$TUI4S_AOT_FLAGS")
else
  [ -f "$cache" ] || { echo "run.sh: no AOT cache $cache: run build.sh first" >&2; exit 1; }
  aot=("-XX:AOTCache=$cache" -Xlog:aot=off)
fi
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" --enable-native-access=ALL-UNNAMED \
  --sun-misc-unsafe-memory-access=allow -Xss16m \
  -XX:TieredStopAtLevel=1 -XX:+UseSerialGC "${aot[@]}" \
  -cp "$here/../tui4s/$1/target/universal/stage/lib/*" "$main"
