#!/usr/bin/env bash
# Builds the bubbletea implementations into bin/: build.sh [counter|dashboard].
# claude 2026-10-20: created: the build script of the bubbletea stack (L5)
set -euo pipefail
cd "$(dirname "$0")"
case "${1:-all}" in
  counter | dashboard) tasks=$1 ;;
  all) tasks="counter dashboard" ;;
  *) echo "usage: build.sh [counter|dashboard]" >&2; exit 2 ;;
esac
for t in $tasks; do go build -mod=readonly -trimpath -o "bin/$t" "./$t"; done
