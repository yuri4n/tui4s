#!/usr/bin/env bash
# Table test for scripts/version-order.sh. It works with bash 3.2 (macOS).
# Usage: bash scripts/version-order-test.sh
# It prints each failed case and exits with 1 if a case fails.
# VERSION_ORDER_LIB=<file> tests a different copy of the library.
set -u

# claude 2026-10-09: created the setup (CDPATH='' as in release.sh)
here=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
lib=${VERSION_ORDER_LIB:-"$here/version-order.sh"}
failures=0
cases=0

# claude 2026-10-09: created fail (count and print a failed case)
fail() {
  failures=$((failures + 1))
  echo "FAIL: $*"
}

# claude 2026-10-09: created the source check (no output, no option change)
opts_before=$(set +o)
# shellcheck source-path=SCRIPTDIR source=version-order.sh
out=$(. "$lib" 2>&1)
# shellcheck source-path=SCRIPTDIR source=version-order.sh
. "$lib"
opts_after=$(set +o)
cases=$((cases + 1))
[[ -z $out ]] || fail "sourcing the library printed: $out"
cases=$((cases + 1))
[[ $opts_before == "$opts_after" ]] || fail "sourcing the library changed the shell options"

# claude 2026-10-09: created gt (one row of the order table)
# gt EXPECTED NEW OLD: EXPECTED is yes if NEW > OLD, else no.
gt() {
  local got=no
  cases=$((cases + 1))
  if version_gt "$2" "$3"; then got=yes; fi
  [[ $got == "$1" ]] || fail "version_gt $2 $3: expected $1, got $got"
}

# claude 2026-10-09: created valid (one row of the form table)
# valid EXPECTED VERSION: EXPECTED is yes if VERSION has an accepted form.
valid() {
  local got=no
  cases=$((cases + 1))
  if version_valid "$2"; then got=yes; fi
  [[ $got == "$1" ]] || fail "version_valid '$2': expected $1, got $got"
}

# claude 2026-10-09: created the order table
# The two Codex review cases on PR #1 (sort -V got both wrong).
gt yes 0.2.0 0.2.0-RC1
gt no 0.2.0-RC1 0.2.0
# The numbers of a qualifier are numbers, not text.
gt yes 0.2.0-RC10 0.2.0-RC2
gt no 0.2.0-RC2 0.2.0-RC10
gt no 0.2.0-M2 0.2.0-M10
gt yes 0.2.0-alpha2 0.2.0-alpha1
# alpha < beta < M < RC < final.
gt no 0.2.0-M1 0.2.0-RC1
gt yes 0.2.0-RC1 0.2.0-M1
gt yes 0.2.0-beta1 0.2.0-alpha1
gt no 0.2.0-alpha1 0.2.0-beta1
gt yes 0.2.0-M1 0.2.0-beta1
gt no 0.2.0-beta1 0.2.0-M1
gt yes 0.2.0 0.2.0-RC9
gt yes 0.2.0 0.2.0-alpha1
gt yes 0.2.0-RC1 0.2.0-alpha9
# Equal versions are not greater.
gt no 0.2.0 0.2.0
gt no 0.2.0-RC1 0.2.0-RC1
gt no 0.2.0-alpha1 0.2.0-alpha1
# Major, minor and patch are numbers and come first.
gt yes 0.10.0 0.9.0
gt no 0.9.0 0.10.0
gt yes 0.2.10 0.2.9
gt yes 0.2.1 0.2.0
gt yes 0.2.1-alpha1 0.2.0
gt no 0.2.0 0.2.1-alpha1
gt yes 1.0.0-alpha1 0.99.99
gt no 0.99.99 1.0.0-alpha1
gt yes 1.0.0 0.99.99

# claude 2026-10-09: created the invalid-input check (version_gt exits 2)
# gt_invalid NEW OLD: version_gt must exit 2.
gt_invalid() {
  local status=0
  cases=$((cases + 1))
  version_gt "$1" "$2" || status=$?
  [[ $status == 2 ]] || fail "version_gt $1 $2: expected exit 2, got $status"
}
gt_invalid 0.2 0.1.0
gt_invalid 0.2.0 0.2

# claude 2026-10-09: created the form table
valid yes 0.0.0
valid yes 0.2.0
valid yes 10.20.30
valid yes 0.2.0-alpha1
valid yes 0.2.0-beta3
valid yes 0.2.0-M1
valid yes 0.2.0-RC1
valid yes 0.2.0-RC10
valid no 01.0.0
valid no 0.01.0
valid no 0.0.01
valid no 0.2.0-RC0
valid no 0.2.0-RC01
valid no 0.2.0-RC
valid no 0.2.0-SNAPSHOT
valid no 0.2.0-RC1-SNAPSHOT
valid no 0.2.0-rc1
valid no 0.2.0-m1
valid no 0.2.0-RC1.1
valid no 0.2.0+1
valid no 0.2
valid no v0.2.0
valid no ""
valid no "0.2.0 "

# claude 2026-10-09: created the summary
if ((failures > 0)); then
  echo "version-order-test: $failures of $cases cases failed"
  exit 1
fi
echo "version-order-test: all $cases cases passed"
