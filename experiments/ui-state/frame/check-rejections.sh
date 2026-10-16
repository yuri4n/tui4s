#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p target/rejection-logs
for fixture in EnvOnly EnvUiCallback TrailingFrame InlineLibraryCaller; do
  diagnostic='Frame cannot be derived within the kyo package.'
  if [[ "$fixture" == EnvUiCallback ]]; then
    diagnostic='None of the overloaded alternatives of method onClick'
  fi
  log="target/rejection-logs/$fixture.log"
  if sbt "set Compile / unmanagedSources += baseDirectory.value / \"compile-fail\" / \"$fixture.scala\"" compile > "$log" 2>&1; then
    printf 'FAIL %s: compilation unexpectedly succeeded\n' "$fixture" >&2
    exit 1
  else
    status=$?
    printf '%s\n' "$status" > "target/rejection-logs/$fixture.exit"
  fi
  if ! grep -Fq "$diagnostic" "$log"; then
    printf 'FAIL %s: expected diagnostic missing; see %s\n' "$fixture" "$log" >&2
    exit 1
  fi
  printf 'PASS %s: expected compiler rejection\n' "$fixture"
done
