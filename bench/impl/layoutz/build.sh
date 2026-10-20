#!/usr/bin/env bash
# Builds the layoutz implementations (or one task): build.sh [counter|dashboard].
# They are projects of the bench sbt build (bench/build.sbt); the
# layoutz jar comes from Maven Central.
# Set SBT="sbt --client" to reuse an sbt server.
# claude 2026-10-20: created: the build script of the layoutz stack (L5)
set -euo pipefail
bench=$(cd "$(dirname "$0")/../.." && pwd)
case "${1:-all}" in
  counter) targets="layoutzCounter/stage" ;;
  dashboard) targets="layoutzDashboard/stage" ;;
  all) targets="layoutzCounter/stage layoutzDashboard/stage" ;;
  *) echo "usage: build.sh [counter|dashboard]" >&2; exit 2 ;;
esac
cd "$bench"
${SBT:-sbt} -Dsbt.supershell=false $targets
