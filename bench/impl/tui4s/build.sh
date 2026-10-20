#!/usr/bin/env bash
# Builds the tui4s implementations (or one task): build.sh [counter|dashboard].
# They are projects of the bench sbt build (bench/build.sbt); the tui4s
# artifacts come from the local Ivy repository (bench/run.sh publishes them).
# Set SBT="sbt --client" to reuse an sbt server.
# claude 2026-10-20: created: the build script of the tui4s stack (L5)
set -euo pipefail
bench=$(cd "$(dirname "$0")/../.." && pwd)
case "${1:-all}" in
  counter) targets="tui4sCounter/stage" ;;
  dashboard) targets="tui4sDashboard/stage" ;;
  all) targets="tui4sCounter/stage; tui4sDashboard/stage" ;;
  *) echo "usage: build.sh [counter|dashboard]" >&2; exit 2 ;;
esac
cd "$bench"
# claude 2026-10-20: changed: one argument with ";" between the commands (sbt --client reads its
# arguments as one command line)
${SBT:-sbt} -Dsbt.supershell=false "$targets"
