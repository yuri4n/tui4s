#!/usr/bin/env bash
# claude 2026-10-20: created (ADR 0020): the release notes of one module from
# CHANGELOG.md, the one source of the release notes
#
# Usage: bash scripts/changelog-section.sh <module> <version> [<file>]
# It prints the text of the subsection "### tui4s-<module>" under the heading
# of <version>, and exits 0. If the heading or the subsection is missing, or
# the subsection has no text, it prints the problem to stderr and exits 1.
# <file> is CHANGELOG.md of the repository by default (the tests give a
# fixture).
#
# The heading of a version is "## [<version>] - <YYYY-MM-DD>" (Keep a
# Changelog). The brackets and the date are optional: "## <version>" also
# matches. "Unreleased" is a version for this script, so a check of the file
# before the release pull request works too. The text drops the lines that are
# only an HTML comment (the marks of the file) and the blank lines at the
# start and at the end.
# The prepare job of .github/workflows/release.yml runs it for each module
# before a tag exists; the publish job gives its text to the GitHub release.
# It works with bash 3.2 and the awk of macOS.
set -euo pipefail

root=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
if [[ $# -lt 2 || $# -gt 3 ]]; then
  echo "usage: scripts/changelog-section.sh <module> <version> [<file>]" >&2
  exit 1
fi
module=$1
version=$2
file=${3:-"$root/CHANGELOG.md"}
[[ -r $file ]] || { echo "changelog-section: cannot read $file" >&2; exit 1; }

status=0
text=$(awk -v ver="$version" -v mod="$module" '
  /^## / {
    inver = 0; inmod = 0
    h = substr($0, 4)
    sub(/ - .*$/, "", h)
    sub(/[ \t]+$/, "", h)
    if (!found_ver && (h == "[" ver "]" || h == ver)) { inver = 1; found_ver = 1 }
    next
  }
  inver && /^### / {
    inmod = 0
    h = substr($0, 5)
    sub(/[ \t]+$/, "", h)
    if (h == "tui4s-" mod) { inmod = 1; found_mod = 1 }
    next
  }
  inmod {
    if ($0 ~ /^[ \t]*<!--.*-->[ \t]*$/) next
    lines[++n] = $0
  }
  END {
    if (!found_ver) exit 3
    if (!found_mod) exit 4
    first = 1
    while (first <= n && lines[first] ~ /^[ \t]*$/) first++
    last = n
    while (last >= first && lines[last] ~ /^[ \t]*$/) last--
    if (first > last) exit 5
    for (i = first; i <= last; i++) print lines[i]
  }
' "$file") || status=$?

case $status in
  0) printf '%s\n' "$text" ;;
  3) echo "changelog-section: $file has no heading '## [$version] - <YYYY-MM-DD>'." \
       "Rename '## [Unreleased]' to the version and the date in the release pull request." >&2 ;;
  4) echo "changelog-section: the heading of $version in $file has no subsection '### tui4s-$module'." >&2 ;;
  5) echo "changelog-section: the subsection '### tui4s-$module' of $version in $file has no text." >&2 ;;
  *) echo "changelog-section: awk failed with exit $status on $file." >&2 ;;
esac
((status == 0)) || exit 1
