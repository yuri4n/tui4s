#!/usr/bin/env bash
# Release ONE module of tui4s to Maven Central by creating and pushing the tag <module>-v<version>.
# The tag push triggers .github/workflows/release.yml, which publishes only that module.
#
# Usage: scripts/release.sh <module> <version> [--dry-run] [--yes]
#   scripts/release.sh core 0.2.0 --dry-run   # validate only, delete the local tag afterwards
#   scripts/release.sh zio 0.1.3              # validate, ask, push the tag
# Env: MODULES (default "core zio cats"), REMOTE (default origin), BRANCH (default main)
set -euo pipefail

die() { echo "release: $*" >&2; exit 1; }

MODULES=${MODULES:-"core zio cats"}
REMOTE=${REMOTE:-origin}
BRANCH=${BRANCH:-main}

[[ $# -ge 2 ]] || die "usage: scripts/release.sh <module> <version> [--dry-run] [--yes]"
module=$1; version=$2; shift 2
dry_run=false; assume_yes=false
for arg in "$@"; do
  case $arg in
    --dry-run) dry_run=true ;;
    --yes|-y)  assume_yes=true ;;
    *) die "unknown option: $arg" ;;
  esac
done

[[ " $MODULES " == *" $module "* ]] || die "module must be one of: $MODULES"
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || die "version must look like 0.2.0 or 0.2.0-RC1"
tag="$module-v$version"

cd "$(git rev-parse --show-toplevel)"
[[ $(git rev-parse --abbrev-ref HEAD) == "$BRANCH" ]] || die "check out $BRANCH first"
[[ -z $(git status --porcelain) ]] || die "working tree is not clean"

if git remote get-url "$REMOTE" >/dev/null 2>&1; then
  git fetch --quiet --tags "$REMOTE" "$BRANCH"
  [[ $(git rev-parse HEAD) == $(git rev-parse "$REMOTE/$BRANCH") ]] || die "HEAD is not $REMOTE/$BRANCH (pull or push first)"
  if git ls-remote --exit-code --tags "$REMOTE" "refs/tags/$tag" >/dev/null; then die "tag $tag already exists on $REMOTE"; fi
elif ! $dry_run; then
  die "remote $REMOTE not found"
fi
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then die "tag $tag already exists locally"; fi

prev=$(git describe --tags --abbrev=0 --match "$module-v[0-9]*" 2>/dev/null || true)
if [[ -n $prev ]]; then
  prev_v=${prev#"$module-v"}
  highest=$(printf '%s\n%s\n' "$prev_v" "$version" | sort -V | tail -n1)
  [[ $prev_v != "$version" && $highest == "$version" ]] || die "$version is not greater than the last release $prev_v"
  echo "==> Last release of $module: $prev_v"
else
  echo "==> First release of $module"
fi

if ! $dry_run && command -v gh >/dev/null 2>&1; then
  ci=$(gh run list --commit "$(git rev-parse HEAD)" --workflow ci.yml --json conclusion --jq '.[0].conclusion' 2>/dev/null || true)
  [[ $ci == success ]] || die "CI is not green for HEAD (conclusion: '${ci:-none}')"
fi

pushed=false
git tag -a "$tag" -m "tui4s-$module $version"
cleanup() { if [[ $pushed != true ]]; then git tag -d "$tag" >/dev/null && echo "==> Deleted local tag $tag"; fi; }
trap cleanup EXIT

echo "==> Validating $tag: guard (assertReleasable), versionCheck, tests, unsigned staging"
RELEASE_VERSION=$version sbt -batch "stageModule $module" "$module/versionCheck" "$module/test" \
  || die "validation failed. If an inter-project dependency is unreleased, release it first (e.g. scripts/release.sh core <next>)."

if $dry_run; then echo "==> Dry run OK: $tag is releasable."; exit 0; fi

if ! $assume_yes; then
  read -r -p "Push $tag to $REMOTE and upload tui4s-$module $version to Maven Central? [y/N] " answer
  [[ $answer == y || $answer == Y ]] || die "aborted"
fi
git push "$REMOTE" "refs/tags/$tag"
pushed=true
echo "==> Pushed $tag. Follow the release: gh run list --workflow release.yml --limit 1"
echo "==> When the run is green, publish the deployment at https://central.sonatype.com/publishing/deployments"
echo "    (nothing is public before that), then publish the draft GitHub release."
