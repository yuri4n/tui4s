#!/usr/bin/env bash
# Builds a GraalVM native binary of one example (ADR 0015):
#
#   scripts/native-image.sh <app> [native-image options...]
#
# <app> is a start script of the staged distribution (counter, dashboard, ...).
# The script stages it first (sbt examples/Universal/stage; set SBT="sbt
# --client" to use an sbt server, or SKIP_STAGE=1 to use the stage as it is),
# then runs native-image on its class path and main class, and writes
# examples/target/native/<app>. The metadata of tui4s-runner
# (META-INF/native-image) gives native-image the crossterm library and the
# isatty downcall; no option of yours is needed. Extra options go to
# native-image, for example -Ob (a quick build) or -Os (a small binary).
# Needs GraalVM for JDK 25 (Community Edition is enough) with native-image on
# PATH, or in $GRAALVM_HOME/bin or $JAVA_HOME/bin.
# claude 2026-10-20: created (L5b, ADR 0015)
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
app=${1:-}
[ -n "$app" ] || { echo "usage: scripts/native-image.sh <app> [native-image options...]" >&2; exit 2; }
shift

ni=""
for dir in "${GRAALVM_HOME:-}" "${JAVA_HOME:-}"; do
    if [ -n "$dir" ] && [ -x "$dir/bin/native-image" ]; then ni="$dir/bin/native-image"; break; fi
done
[ -n "$ni" ] || ni=$(command -v native-image || true)
if [ -z "$ni" ]; then
    cat >&2 <<'MSG'
native-image.sh: native-image is not found. Install GraalVM for JDK 25:
  - GraalVM Community Edition: https://github.com/graalvm/graalvm-ce-builds/releases
    (unpack it, then set GRAALVM_HOME to its directory), or
  - SDKMAN: `sdk list java` shows the GraalVM builds ("graalce"), then
    `sdk install java <version>-graalce`.
Then run this script again.
MSG
    exit 1
fi

stage="$root/examples/target/universal/stage"
if [ "${SKIP_STAGE:-}" != "1" ]; then
    (cd "$root" && ${SBT:-sbt} -Dsbt.supershell=false examples/Universal/stage)
fi
script="$stage/bin/$app"
[ -f "$script" ] || { echo "native-image.sh: no start script $script" >&2; exit 1; }
# The main class and the class path of the start script of native-packager.
main=$(sed -n "s/^declare -a app_mainclass=('\(.*\)')$/\1/p" "$script")
cp=$(sed -n 's/^declare -r app_classpath="\(.*\)"$/\1/p' "$script" | sed "s|\$lib_dir|$stage/lib|g")
[ -n "$main" ] && [ -n "$cp" ] || { echo "native-image.sh: no main class or class path in $script" >&2; exit 1; }

out="$root/examples/target/native"
mkdir -p "$out"
# The proxy setup of some machines sets JAVA_TOOL_OPTIONS; the build needs no
# network, and the line "Picked up ..." is noise in its log.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
echo "native-image.sh: $ni ($("$ni" --version | head -n 1)), main $main"
start=$(date +%s)
"$ni" --no-fallback -march=compatibility -cp "$cp" -o "$out/$app" "$@" "$main"
echo "native-image.sh: $out/$app ($(du -h "$out/$app" | cut -f1), $(( $(date +%s) - start )) s)"
