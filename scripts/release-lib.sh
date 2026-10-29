# shellcheck shell=bash
# claude 2026-10-20: changed: this file was scripts/version-order.sh; it also
# has the module list and the tag lookup, for scripts/release.sh and for the
# jobs of .github/workflows/release.yml (ADR 0020)
# The release rules of tui4s: the version form, the version order, the
# modules and the last tag of a module. scripts/release.sh and
# .github/workflows/release.yml source this file, so the form and the
# workflow use the same rules.
# Source this file. It only defines functions: it does not change shell options
# and it prints nothing. It works with bash 3.2 (macOS).
#
# Accepted form: x.y.z or x.y.z-<qualifier><n>. The qualifier is alpha, beta, M
# or RC. The numbers have no leading zeros, and n starts at 1.
# Examples: 0.2.0, 0.2.0-alpha1, 0.2.0-M2, 0.2.0-RC10.
#
# Order (the same as Maven ComparableVersion for these forms):
#   1. Compare x, then y, then z as numbers.
#   2. For the same x.y.z: alpha < beta < M < RC < no qualifier.
#   3. For the same qualifier, compare n as a number (RC10 > RC2).
# claude 2026-10-09: changed the coursier note (M is below alpha and beta)
# Note: coursier puts M below alpha and beta. For all other pairs of these
# forms, it agrees.

# claude 2026-10-09: created version_valid (accepted release version forms)
# version_valid VERSION: exit 0 if VERSION has an accepted form, else exit 1.
# On success, BASH_REMATCH holds the parts of VERSION.
version_valid() {
  # The regex is in a variable and unquoted on the right side of =~, because
  # bash 3.2 reads a quoted regex as a literal string.
  local re='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|M|RC)([1-9][0-9]*))?$'
  [[ ${1-} =~ $re ]]
}

# claude 2026-10-09: created _version_parts (split a version into 5 numbers)
# _version_parts VERSION: set the array _vp of the caller to
# (x y z rank n). rank: alpha=1, beta=2, M=3, RC=4, no qualifier=5.
# Exit 1 if VERSION has no accepted form.
_version_parts() {
  version_valid "${1-}" || return 1
  local rank
  case ${BASH_REMATCH[5]:-} in
    alpha) rank=1 ;;
    beta) rank=2 ;;
    M) rank=3 ;;
    RC) rank=4 ;;
    *) rank=5 ;;
  esac
  _vp=("${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}" "$rank" "${BASH_REMATCH[6]:-0}")
}

# claude 2026-10-09: created version_gt (Maven order, not sort -V)
# version_gt NEW OLD: exit 0 if NEW > OLD, exit 1 if NEW <= OLD.
# Exit 2 if NEW or OLD has no accepted form.
version_gt() {
  local -a _vp new old
  local i
  _version_parts "${1-}" || return 2
  new=("${_vp[@]}")
  _version_parts "${2-}" || return 2
  old=("${_vp[@]}")
  for i in 0 1 2 3 4; do
    # 10# forces base 10. Without it, bash reads a leading 0 as octal.
    if ((10#${new[i]} > 10#${old[i]})); then return 0; fi
    if ((10#${new[i]} < 10#${old[i]})); then return 1; fi
  done
  return 1
}

# claude 2026-10-20: created release_all_modules (the order of the dependencies);
# cursor 2026-10-29: changed: the published modules are runner, testkit, core (ADR 0024)
# release_all_modules: print the published modules in the order of their
# dependencies: runner, then testkit, then core. A release of "all" goes in
# this order. app, use and widgets are not published: their JVM classes ship
# in core.
release_all_modules() {
  echo "runner testkit core"
}

# cursor 2026-10-29: created release_bundle_sbt (one Central deployment, ADR 0024)
# release_bundle_sbt MODULE...: print the CI_RELEASE command for those
# modules, in the given order. Each module is checked, then publishSigned.
# One sonaRelease after that command uploads every staged module.
# Exit 1 when no module is given.
release_bundle_sbt() {
  local m cmd=";"
  [[ $# -ge 1 ]] || return 1
  for m in "$@"; do
    cmd="$cmd $m/assertReleasable; $m/versionCheck; $m/publishSigned;"
  done
  printf '%s\n' "$cmd"
}

# cursor 2026-10-29: created release_bundle_project (ADR 0024)
# release_bundle_project MODULE...: print the project where sonaRelease runs.
# That project is the first module. The root project is a snapshot, so
# sonaRelease cannot run there. The upload zips the whole target/sona-staging
# directory, so every module staged before it is in the bundle.
# Exit 1 when no module is given.
release_bundle_project() {
  [[ $# -ge 1 ]] || return 1
  printf '%s\n' "$1"
}

# claude 2026-10-20: created release_resolve_modules (the input "module" of the form)
# release_resolve_modules MODULE: print the modules that a release of MODULE
# publishes. MODULE is one module, or "all" for every module in the order of
# release_all_modules. Exit 1 if MODULE is not a module and not "all".
release_resolve_modules() {
  local m
  if [[ ${1-} == all ]]; then
    release_all_modules
    return 0
  fi
  for m in $(release_all_modules); do
    if [[ ${1-} == "$m" ]]; then
      echo "$m"
      return 0
    fi
  done
  return 1
}

# claude 2026-10-20: created release_latest_before (the last release of a module)
# release_latest_before MODULE [VERSION]: print the greatest version of the
# tags MODULE-v* of the git repository in the current directory. With
# VERSION, only the versions below VERSION count (a re-run finds the release
# before its own tag). Print nothing if no tag counts.
# Exit 2 if a tag MODULE-v* has no accepted form: then no order is safe.
release_latest_before() {
  local m=${1-} limit=${2-} tag v best=""
  for tag in $(git tag -l "$m-v*"); do
    v=${tag#"$m-v"}
    version_valid "$v" || return 2
    if [[ -n $limit ]] && ! version_gt "$limit" "$v"; then continue; fi
    if [[ -z $best ]] || version_gt "$v" "$best"; then best=$v; fi
  done
  if [[ -n $best ]]; then echo "$best"; fi
  return 0
}
