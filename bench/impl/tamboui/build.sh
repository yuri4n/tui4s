#!/usr/bin/env bash
# Builds the TamboUI implementations (or one task): build.sh [counter|dashboard].
# They are projects of the bench sbt build (bench/build.sbt); the
# TamboUI jars come from Maven Central.
# Set SBT="sbt --client" to reuse an sbt server.
# claude 2026-10-20: created: the build script of the tamboui stack (L5)
set -euo pipefail
bench=$(cd "$(dirname "$0")/../.." && pwd)
case "${1:-all}" in
  counter) targets="tambouiCounter/stage" ;;
  dashboard) targets="tambouiDashboard/stage" ;;
  all) targets="tambouiCounter/stage tambouiDashboard/stage" ;;
  *) echo "usage: build.sh [counter|dashboard]" >&2; exit 2 ;;
esac
cd "$bench"
${SBT:-sbt} -Dsbt.supershell=false $targets
