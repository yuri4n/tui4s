#!/usr/bin/env bash
# Builds the assistant for the web (L4): links the Scala.js app with
# fullLinkJS and assembles dist/assistant (index.html and main.js: static
# files, no secrets, no server). Serve the directory with any static server:
#
#   bash scripts/web-build.sh
#   python3 -m http.server -d dist/assistant 8000   # then open localhost:8000
#
# It needs JDK 25 (JAVA_HOME or java on PATH) and sbt. Run only one sbt in the
# repository: when an sbt server runs, set SBT="sbt --client". It prints the
# size of the script and its size with gzip.
# claude 2026-10-19: created (L4)
set -euo pipefail

CDPATH='' cd "$(dirname "$0")/.."

${SBT:-sbt -batch} assistantWeb/fullLinkJS

# The output directory of fullLinkJS (build.sbt, assistantWeb): no Scala
# version in the path, and no source map.
# claude 2026-10-19: changed (L4 review)
out=examples/web/target/web-opt
dist=dist/assistant
rm -rf "$dist"
mkdir -p "$dist"
cp examples/web/static/index.html "$dist/index.html"
cp "$out/main.js" "$dist/main.js"

bytes=$(wc -c <"$dist/main.js")
gz=$(gzip -9 -c "$dist/main.js" | wc -c)
echo "web-build: $dist/main.js: $bytes bytes ($gz bytes with gzip)"
