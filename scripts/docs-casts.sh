#!/usr/bin/env bash
# Records the terminal casts of the docs site (L5): docs/public/casts/<app>.cast
# in the asciinema v2 format. It writes the start scripts of the examples
# (sbt examples/launchers), then examples/ptycheck/cast.py starts each app in
# a pseudo-terminal of its own (python3 pty), sends the keys of its script and
# keeps the output of the alternate screen. The shell that runs this script
# does not change.
#
#   bash scripts/docs-casts.sh                 # all the casts
#   bash scripts/docs-casts.sh counter todo    # some casts
#
# It needs JDK 25 (JAVA_HOME or java on PATH), sbt and python3. Run only one
# sbt in the repository: when an sbt server runs, set SBT="sbt --client".
# CASTS_SKIP_BUILD=1 uses the start scripts in examples/target/bin as they are.
# The casts are generated files: commit them after a change of an example.
# claude 2026-10-20: created (L5)
set -euo pipefail

CDPATH='' cd "$(dirname "$0")/.."

if [ "${CASTS_SKIP_BUILD:-}" != "1" ]; then
    ${SBT:-sbt -batch} -Dsbt.supershell=false examples/launchers
fi
[ -x examples/target/bin/counter ] || { echo "docs-casts: examples/target/bin/counter is missing"; exit 1; }
# The JVM prints "Picked up JAVA_TOOL_OPTIONS: ..." into the terminal when one
# of these is set (a proxy setup): the apps need no network, so the runs do
# not get them (as in examples/ptycheck/run.sh).
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
PYTHONDONTWRITEBYTECODE=1 python3 examples/ptycheck/cast.py examples/target/bin docs/public/casts "$@"
