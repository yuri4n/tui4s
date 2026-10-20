#!/usr/bin/env bash
# Runs one task in the current terminal: run.sh counter|dashboard.
# claude 2026-10-20: created: the start script of the ink stack (L5)
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
case "${1:-}" in
  counter | dashboard) exec node "$here/dist/$1/app.js" ;;
  *) echo "usage: run.sh counter|dashboard" >&2; exit 2 ;;
esac
