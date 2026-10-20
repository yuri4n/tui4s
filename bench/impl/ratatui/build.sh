#!/usr/bin/env bash
# Builds the ratatui implementations (release mode): build.sh [counter|dashboard].
# claude 2026-10-20: created (L5)
set -euo pipefail
cd "$(dirname "$0")"
case "${1:-all}" in
  counter | dashboard) cargo build --release --locked -q -p "$1" ;;
  all) cargo build --release --locked -q ;;
  *) echo "usage: build.sh [counter|dashboard]" >&2; exit 2 ;;
esac
