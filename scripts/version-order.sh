# shellcheck shell=bash
# Version form and version order for scripts/release.sh.
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
