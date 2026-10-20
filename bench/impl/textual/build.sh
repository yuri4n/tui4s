#!/usr/bin/env bash
# Builds the Textual implementations: a virtual environment with the locked
# packages (requirements.lock), and a byte-compile check. build.sh [task]
# claude 2026-10-20: created: the build script of the textual stack (L5)
set -euo pipefail
cd "$(dirname "$0")"
[ -x .venv/bin/python ] || python3 -m venv .venv
.venv/bin/pip install -q --disable-pip-version-check -r requirements.lock
case "${1:-all}" in
  counter | dashboard) .venv/bin/python -m py_compile "$1/app.py" ;;
  all) .venv/bin/python -m py_compile counter/app.py dashboard/app.py ;;
  *) echo "usage: build.sh [counter|dashboard]" >&2; exit 2 ;;
esac
