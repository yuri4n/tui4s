#!/usr/bin/env bash
# Copies the web assistant into the docs site (L5): dist/assistant (from
# scripts/web-build.sh) to docs/public/demo/assistant (index.html and
# main.js). The page "The same app on the web" embeds it in a sandboxed
# iframe at index.html?demo: with ?demo, the app reads no key, uses only the
# fake provider (no network) and keeps its storage in memory. The copy is a
# generated file: commit it after a change of the assistant.
#
#   bash scripts/web-build.sh    # dist/assistant (JDK 25 and sbt)
#   bash scripts/docs-demo.sh    # docs/public/demo/assistant
#
# claude 2026-10-20: created (L5); changed: ?demo and the sandbox (L5 review)
set -euo pipefail

CDPATH='' cd "$(dirname "$0")/.."

src=dist/assistant
dst=docs/public/demo/assistant
for f in index.html main.js; do
    [ -s "$src/$f" ] || { echo "docs-demo: $src/$f is missing; run bash scripts/web-build.sh first"; exit 1; }
done
rm -rf "$dst"
mkdir -p "$dst"
cp "$src/index.html" "$src/main.js" "$dst/"

bytes=$(wc -c <"$dst/main.js")
gz=$(gzip -9 -c "$dst/main.js" | wc -c)
echo "docs-demo: $dst/main.js: $bytes bytes ($gz bytes with gzip)"
