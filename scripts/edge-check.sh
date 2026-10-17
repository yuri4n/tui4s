#!/usr/bin/env bash
# Checks the scalafix suppressions of the purity rules (AGENTS.md, "Purity").
#
# Impure syntax is permitted only in an edge file or on a line with a reason:
#   1. Each edge file starts with `// scalafix:off DisableSyntax`, then one
#      `// EDGE: <the foreign API it wraps>` line.
#   2. No other Scala file has `scalafix:off` or a @SuppressWarnings for
#      scalafix: outside the edge files, a suppression is one line.
#   3. Each `scalafix:ok` names the phase that removes the site, or KEEP:
#      `// scalafix:ok DisableSyntax.<id>; <phase>: <reason>`, where <phase>
#      is P1, P1b or P2 to P6 (the purity plan) or KEEP (it stays).
#
# Usage: bash scripts/edge-check.sh (CI runs it; see AGENTS.md).
# claude 2026-10-17: created (purity plan, phase P0)
set -euo pipefail

cd "$(dirname "$0")/.."

edge=(
  modules/core/src/main/scala/tui4s/internal/Crossterm.scala
  modules/core/src/main/scala/tui4s/internal/LogSink.scala
  modules/core/src/main/scala/tui4s/internal/OutputCapture.scala
  modules/core/src/main/scala/tui4s/internal/SchedulerProbe.scala
  modules/core/src/main/scala/tui4s/internal/Session.scala
  modules/core/src/main/scala/tui4s/internal/TerminalIO.scala
  modules/core/src/main/scala/tui4s/internal/TuiLog.scala
)

is_edge() {
  local f
  for f in "${edge[@]}"; do
    [[ "$f" == "$1" ]] && return 0
  done
  return 1
}

# The Scala files of the repository: tracked, and new ones that git does not
# ignore (the submodules are not included).
mapfile -d '' files < <(git ls-files -z --cached --others --exclude-standard -- '*.scala')

failures=0
fail() {
  echo "edge-check: $*" >&2
  failures=$((failures + 1))
}

for f in "${edge[@]}"; do
  if [[ ! -f "$f" ]]; then
    fail "$f: the edge file is missing (update the list in $0)"
  elif [[ "$(sed -n 1p "$f")" != "// scalafix:off DisableSyntax" ]] ||
    ! sed -n 2p "$f" | grep -qE '^// EDGE: [^ ]'; then
    fail "$f: line 1 must be '// scalafix:off DisableSyntax' and line 2 '// EDGE: <foreign API>'"
  fi
done

for f in "${files[@]}"; do
  is_edge "$f" && continue
  while IFS= read -r hit; do
    fail "$f:$hit: a file suppression outside an edge file; use one '// scalafix:ok' line"
  done < <(grep -nE 'scalafix:off|SuppressWarnings.*scalafix' "$f" || true)
done

for f in "${files[@]}"; do
  while IFS= read -r hit; do
    fail "$f:$hit: no phase; write '// scalafix:ok DisableSyntax.<id>; <P1|P1b|P2..P6|KEEP>: <reason>'"
  done < <(grep -n 'scalafix:ok' "$f" |
    grep -vE 'scalafix:ok [^;]+; (P1b|P[1-6]|KEEP): [^ ]' || true)
done

if ((failures > 0)); then
  echo "edge-check: $failures problem(s)" >&2
  exit 1
fi
echo "edge-check: ${#edge[@]} edge files, $(cat "${files[@]}" | grep -c 'scalafix:ok' || true) line suppressions, all with a phase"
