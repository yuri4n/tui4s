#!/usr/bin/env bash
# Runs one task in the current terminal: run.sh counter|dashboard.
# claude 2026-10-20: created: the start script of the bubbletea stack (L5)
set -euo pipefail
case "${1:-}" in
  counter | dashboard) exec "$(dirname "$0")/bin/$1" ;;
  *) echo "usage: run.sh counter|dashboard" >&2; exit 2 ;;
esac
