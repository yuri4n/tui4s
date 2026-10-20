#!/usr/bin/env bash
# Builds the ink implementations: the locked packages (pnpm-lock.yaml), then
# esbuild turns the JSX into dist/<task>/app.js. build.sh [task] (both are built).
# claude 2026-10-20: created (L5)
set -euo pipefail
cd "$(dirname "$0")"
pnpm install --frozen-lockfile --silent
pnpm run --silent build
