#!/usr/bin/env bash
# Builds the GraalVM native image of each task of the tui4s stack (or of one
# task): build.sh [counter|dashboard]. It needs the build of bench/impl/tui4s
# (its jars) and native-image of GraalVM for JDK 25 (in $GRAALVM_HOME/bin,
# $JAVA_HOME/bin or on PATH). The metadata of tui4s-runner
# (META-INF/native-image) is in its jar: no option is needed. -Os: the size of
# a release binary. The binaries go to out/<task>.
# claude 2026-10-20: created (L5b): the native mode of the tui4s stack
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
case "${1:-all}" in
  counter | dashboard) tasks=$1 ;;
  all) tasks="counter dashboard" ;;
  *) echo "usage: build.sh [counter|dashboard]" >&2; exit 2 ;;
esac
ni=""
for dir in "${GRAALVM_HOME:-}" "${JAVA_HOME:-}"; do
  if [ -n "$dir" ] && [ -x "$dir/bin/native-image" ]; then ni="$dir/bin/native-image"; break; fi
done
[ -n "$ni" ] || ni=$(command -v native-image || true)
[ -n "$ni" ] || { echo "build.sh: native-image not found (GraalVM for JDK 25)" >&2; exit 1; }
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
mkdir -p "$here/out"
for task in $tasks; do
  lib="$here/../tui4s/$task/target/universal/stage/lib"
  [ -d "$lib" ] || { echo "build.sh: no $lib: build bench/impl/tui4s first" >&2; exit 1; }
  case $task in counter) main=CounterApp ;; dashboard) main=DashboardApp ;; esac
  cp=$(printf '%s:' "$lib"/*.jar)
  "$ni" --no-fallback -march=compatibility -Os -cp "${cp%:}" -o "$here/out/$task" "$main"
done
