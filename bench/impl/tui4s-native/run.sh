#!/usr/bin/env bash
# Runs one task of the tui4s stack as a GraalVM native image (build.sh makes
# it): run.sh counter|dashboard.
# claude 2026-10-20: created (L5b): the native mode of the tui4s stack
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
case "${1:-}" in
  counter | dashboard) ;;
  *) echo "usage: run.sh counter|dashboard" >&2; exit 2 ;;
esac
[ -x "$here/out/$1" ] || { echo "run.sh: no binary $here/out/$1: run build.sh first" >&2; exit 1; }
exec "$here/out/$1"
