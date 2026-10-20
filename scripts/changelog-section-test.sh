#!/usr/bin/env bash
# claude 2026-10-20: created (ADR 0020): the table test of
# scripts/changelog-section.sh, on a fixture and on CHANGELOG.md
#
# Usage: bash scripts/changelog-section-test.sh (CI runs it).
# It prints each failed case and exits with 1 if a case fails. It works with
# bash 3.2 and the awk of macOS.
set -u

here=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
script="$here/changelog-section.sh"
failures=0
cases=0

fail() {
  failures=$((failures + 1))
  echo "FAIL: $*"
}

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
fixture="$work/CHANGELOG.md"
cat >"$fixture" <<'MD'
# Changelog

Text before the first version.

## [Unreleased]

### tui4s-runner

- Added: next.

## [0.2.0] - 2026-11-01

<!-- a mark of the version -->

### tui4s-runner

- Fixed: a.
  <!-- a mark of an item -->
- Fixed: b.

### tui4s-use

### tui4s-widgets
- Added: w.
## 0.1.0-RC1 - 2026-10-21
### tui4s-runner
- Added: first.
### tui4s-runner-extra
- Not the runner.
MD

# ok MODULE VERSION EXPECTED: the script exits 0 and prints EXPECTED.
ok() {
  local got status=0
  cases=$((cases + 1))
  got=$(bash "$script" "$1" "$2" "$fixture" 2>/dev/null) || status=$?
  if ((status != 0)); then
    fail "$1 $2: expected exit 0, got $status"
  elif [[ $got != "$3" ]]; then
    fail "$1 $2: expected '$3', got '$got'"
  fi
}

# ko MODULE VERSION WORDS: the script exits 1, prints nothing to stdout and
# prints WORDS (a part of the message) to stderr.
ko() {
  local out err status=0
  cases=$((cases + 1))
  out=$(bash "$script" "$1" "$2" "$fixture" 2>"$work/err") || status=$?
  err=$(cat "$work/err")
  if ((status != 1)); then
    fail "$1 $2: expected exit 1, got $status"
  elif [[ -n $out ]]; then
    fail "$1 $2: expected no output, got '$out'"
  elif [[ $err != *"$3"* ]]; then
    fail "$1 $2: expected '$3' in the message, got '$err'"
  fi
}

nl=$'\n'
ok runner 0.2.0 "- Fixed: a.$nl- Fixed: b."
ok widgets 0.2.0 "- Added: w."
ok runner 0.1.0-RC1 "- Added: first."
ok runner-extra 0.1.0-RC1 "- Not the runner."
ok runner Unreleased "- Added: next."
ko use 0.2.0 "has no text"
ko core 0.2.0 "no subsection '### tui4s-core'"
ko app 0.1.0-RC1 "no subsection '### tui4s-app'"
ko runner 0.3.0 "no heading '## [0.3.0]"
ko runner 0.2 "no heading"
ko runner 0.1.0 "no heading"
ko runner "" "no heading"

# The usage and a missing file.
cases=$((cases + 1))
bash "$script" runner >/dev/null 2>&1 && fail "one argument: expected exit 1"
cases=$((cases + 1))
bash "$script" runner 0.2.0 "$work/missing.md" >/dev/null 2>&1 && fail "a missing file: expected exit 1"

# claude 2026-10-20: changed (review fix): the check of the first heading of a
# file. It checks only the subsections '### tui4s-<module>' that are under the
# first '## ' heading: each one must have text. A first heading with no
# subsection is good (an empty '## [Unreleased]' after a release). A pull
# request adds notes only for the modules that it changes, and a release pull
# request keeps only the modules of the release. Thus the cases of all six
# modules and of a missing subsection are only on the fixtures.

# first_heading FILE: the version of the first '## ' heading of FILE
# ('Unreleased' for '## [Unreleased]'), or nothing.
first_heading() {
  grep -m 1 -E '^## ' "$1" | sed -E 's/^## \[?([^] ]+)\]?.*$/\1/'
}

# first_modules FILE: the module of each subsection '### tui4s-<module>' under
# the first '## ' heading of FILE, one on each line.
first_modules() {
  awk '/^## / { n++; next }
    n == 1 && /^### tui4s-/ { m = substr($0, 11); sub(/[ \t]+$/, "", m); print m }' "$1"
}

# first_problems FILE: one line for each problem of the first heading of FILE
# (no heading, or a subsection with no text); nothing when it is good.
first_problems() {
  local heading m
  heading=$(first_heading "$1")
  if [[ -z $heading ]]; then
    echo "no '## ' heading"
    return
  fi
  for m in $(first_modules "$1"); do
    bash "$script" "$m" "$heading" "$1" >/dev/null 2>&1 ||
      echo "no text for tui4s-$m under '$heading'"
  done
}

# heading NAME WORDS: the first heading of $work/NAME.md (from stdin) has no
# problem when WORDS is empty; else WORDS is a part of its problems.
heading() {
  local file="$work/$1.md" got
  cat >"$file"
  cases=$((cases + 1))
  got=$(first_problems "$file")
  if [[ -z $2 && -n $got ]]; then
    fail "first heading, $1: expected no problem, got '$got'"
  elif [[ -n $2 && $got != *"$2"* ]]; then
    fail "first heading, $1: expected '$2', got '$got'"
  fi
}

heading all-six "" <<'MD'
## [Unreleased]
### tui4s-runner
- r.
### tui4s-app
- a.
### tui4s-testkit
- t.
### tui4s-core
- c.
### tui4s-use
- u.
### tui4s-widgets
- w.
MD
heading one-module-unreleased "" <<'MD'
# Changelog

## [Unreleased]

### tui4s-runner

- Fixed: a key.

## [0.1.0] - 2026-10-30

### tui4s-runner

- Added: first.
MD
heading one-module-release "" <<'MD'
## [0.1.1] - 2026-11-02

### tui4s-use

- Fixed: a composable.

## [0.1.0] - 2026-10-30

### tui4s-runner

- Added: first.
MD
heading empty-unreleased "" <<'MD'
## [Unreleased]

## [0.1.0] - 2026-10-30

### tui4s-runner

- Added: first.
MD
heading empty-subsection "no text for tui4s-app under 'Unreleased'" <<'MD'
## [Unreleased]

### tui4s-app

<!-- a mark only -->

### tui4s-use

- Added: u.
MD
heading no-heading "no '## ' heading" <<'MD'
# Changelog

Nothing yet.
MD

# CHANGELOG.md of the repository: each subsection under its first heading has
# text (Unreleased until the release pull request renames it).
changelog="$here/../CHANGELOG.md"
cases=$((cases + 1))
got=$(first_problems "$changelog")
[[ -z $got ]] || fail "CHANGELOG.md: $got"

if ((failures > 0)); then
  echo "changelog-section-test: $failures of $cases cases failed"
  exit 1
fi
echo "changelog-section-test: all $cases cases passed" \
  "(CHANGELOG.md: $(first_modules "$changelog" | wc -l | tr -d ' ') subsections with text under '$(first_heading "$changelog")')"
