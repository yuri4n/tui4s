#!/usr/bin/env bash
# claude 2026-10-20: changed (ADR 0020): this script is now a front for the
# form of .github/workflows/release.yml. It starts the workflow on main with
# gh, then shows the run. It creates no tag and pushes nothing: the workflow
# checks everything, waits for the approval of the environment maven-central,
# and then tags and publishes each module in the order of the dependencies.
#
# Usage: scripts/release.sh <module|all> <version> [--dry-run]
#   scripts/release.sh all 0.1.0-RC1 --dry-run # check everything; no tag, no publish
#   scripts/release.sh use 0.1.3              # release one module
# <module> is runner, app, testkit, core, use, widgets, or all (all the
# modules, in this order). It needs gh (GitHub CLI), signed in with the right
# to run the workflows of the repository. It works with bash 3.2 (macOS).
set -euo pipefail

die() { echo "release: $*" >&2; exit 1; }

# claude 2026-10-09: source the library from the directory of this script, before any cd
# claude 2026-10-20: changed: the library is release-lib.sh (ADR 0020)
script_dir=$(CDPATH='' cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source-path=SCRIPTDIR source=release-lib.sh
. "$script_dir/release-lib.sh"

# claude 2026-10-20: changed: <module> can be all; --yes is gone (ADR 0020)
[[ $# -ge 2 ]] || die "usage: scripts/release.sh <module|all> <version> [--dry-run]"
module=$1; version=$2; shift 2
dry_run=false
for arg in "$@"; do
  case $arg in
    --dry-run) dry_run=true ;;
    *) die "unknown option: $arg. The approval of the environment maven-central is the gate, so there is no --yes." ;;
  esac
done

release_resolve_modules "$module" >/dev/null || die "module must be all or one of: $(release_all_modules)"
# claude 2026-10-09: changed the version check to version_valid (narrow accepted forms)
version_valid "$version" \
  || die "version must be x.y.z or x.y.z-<alpha|beta|M|RC><n> (no leading zeros, n >= 1), e.g. 0.2.0 or 0.2.0-RC1"
command -v gh >/dev/null 2>&1 || die "gh (GitHub CLI) is not on PATH"

# claude 2026-10-20: created (ADR 0020): start the workflow, then find its run
# (the newest dispatch run that was not there before)
cd "$script_dir/.."
last_run() {
  gh run list --workflow release.yml --event workflow_dispatch --limit 1 \
    --json databaseId --jq '.[0].databaseId // empty' 2>/dev/null || true
}
before=$(last_run)
echo "==> Start the release workflow on main: module=$module version=$version dry_run=$dry_run"
gh workflow run release.yml --ref main -f module="$module" -f version="$version" -f dry_run="$dry_run"

run_id=""
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  sleep 2
  run_id=$(last_run)
  if [[ -n $run_id && $run_id != "$before" ]]; then break; fi
  run_id=""
done
if [[ -z $run_id ]]; then
  echo "==> The run is not in the list yet. Find it with: gh run list --workflow release.yml --limit 1"
  exit 0
fi
echo "==> Run: $(gh run view "$run_id" --json url --jq .url)"
if ! $dry_run; then
  echo "==> After the checks, GitHub asks the required reviewer of the environment maven-central to approve the job publish."
fi
echo "==> Watch it with: gh run watch $run_id"

# claude 2026-10-20: created (ADR 0020): offer to watch the run in a terminal
if [[ -t 0 && -t 1 ]]; then
  read -r -p "Watch the run now? [y/N] " answer || answer=""
  if [[ $answer == y || $answer == Y ]]; then gh run watch "$run_id"; fi
fi
