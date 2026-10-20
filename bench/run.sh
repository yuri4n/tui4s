#!/usr/bin/env bash
# Runs the bench: publishes the tui4s artifacts locally, writes their version
# into bench/tui4s.version, builds every implementation, and measures.
#
#   bench/run.sh [full|ci|build|measure|report]
#
#   full     (default) everything, 3 perf runs, then the tables of README.md
#            and PERFORMANCE.md
#   ci       the CI smoke: build, conformance, resilience, mutants, change,
#            one short perf run, then the check of the results (harness
#            "check"); no tables (the results are not published)
#   build    publish tui4s locally and build the implementations only
#   measure  measure only (the implementations are built)
#   report   the tables only, from bench/results/$BENCH_MACHINE
#
# Environment: BENCH_MACHINE names the machine in bench/results (default
# local-<n>cpu); BENCH_STACKS limits the stacks (comma-separated); SBT is the
# sbt command (default "sbt --client": one sbt server for the bench build, shut
# down at the end). Needs JDK 25, sbt, cargo, go, python3, node 22 and pnpm,
# patch.
# claude 2026-10-20: created: the run script of the bench (L5)
set -uo pipefail
bench=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$bench/.." && pwd)
mode=${1:-full}
export PYTHONDONTWRITEBYTECODE=1
export BENCH_MACHINE=${BENCH_MACHINE:-local-$(nproc)cpu}
export SBT=${SBT:-sbt --client}
stacks=${BENCH_STACKS:-tui4s,jatatui-plain,tamboui,layoutz,ratatui,bubbletea,textual,ink}
harness="python3 $bench/harness/bench.py"
status=0

step() { echo "bench/run.sh: $*"; }

check_java() {
    local java="${JAVA_HOME:+$JAVA_HOME/bin/}java" major
    major=$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')
    if [ "${major:-0}" -lt 25 ] 2>/dev/null; then
        step "$java is Java ${major:-unknown}; the bench needs JDK 25 (set JAVA_HOME)"
        exit 1
    fi
}

# claude 2026-10-20: changed: the version of each of the six modules, and a
# reload of the bench build after the publish (review fixes)
publish() {
    # One sbt process in the root build: publish the six artifacts and print
    # their version (dynver: like 0.1.0+N-<sha>-SNAPSHOT). The bench build
    # uses one version for all of them, so they must be equal.
    step "publishLocal of the tui4s artifacts (root build)"
    local log="$bench/results/publish.log" version m
    local modules="runner app testkit core useJVM widgetsJVM" prints=()
    for m in $modules; do prints+=("print $m/version"); done
    mkdir -p "$bench/results"
    (cd "$root" && sbt -Dsbt.supershell=false runner/publishLocal app/publishLocal testkit/publishLocal \
        core/publishLocal useJVM/publishLocal widgetsJVM/publishLocal "${prints[@]}") >"$log" 2>&1 || {
        tail -n 40 "$log"; step "publishLocal failed"; exit 1; }
    version=$(grep -E '^[0-9]+\.[0-9]+\.[0-9]+' "$log" | tail -n 6)
    if [ "$(echo "$version" | wc -l)" -ne 6 ] || [ "$(echo "$version" | sort -u | wc -l)" -ne 1 ]; then
        step "the six modules do not have one version: $(echo $version)"; exit 1
    fi
    version=$(echo "$version" | head -n 1)
    echo "$version" >"$bench/tui4s.version"
    step "tui4s version $version"
    # A running sbt server of the bench build read tui4s.version when it
    # loaded: load the build again, so it uses the new version.
    if [ "$SBT" != "sbt" ]; then (cd "$bench" && $SBT reload >/dev/null) || exit 1; jvm=1; fi
}

has() { case ",$stacks," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }

build() {
    local targets=""
    has tui4s && targets="$targets tui4sCounter/stage tui4sDashboard/stage"
    has jatatui-plain && targets="$targets jatatuiCounter/stage jatatuiDashboard/stage"
    has tamboui && targets="$targets tambouiCounter/stage tambouiDashboard/stage"
    has layoutz && targets="$targets layoutzCounter/stage layoutzDashboard/stage"
    if [ -n "$targets" ]; then
        jvm=1
        step "build: the JVM stacks (one sbt server):$targets"
        (cd "$bench" && $SBT -Dsbt.supershell=false $targets) || status=1
    fi
    for s in ratatui bubbletea textual ink; do
        has "$s" || continue
        step "build: $s"
        "$bench/impl/$s/build.sh" || status=1
    done
}

report() {
    python3 "$bench/harness/compact.py" >/dev/null && python3 "$bench/harness/report.py" "$BENCH_MACHINE"
}

jvm=""
finish() {
    # Stop the sbt server of the bench build (sbt --client starts one).
    if [ -n "$jvm" ] && [ "$SBT" != "sbt" ]; then (cd "$bench" && $SBT shutdown >/dev/null 2>&1); fi
}
trap finish EXIT

case "$mode" in
    full | ci | build)
        if has tui4s || has jatatui-plain || has tamboui || has layoutz; then check_java; fi
        if has tui4s; then publish; fi
        build
        ;;
    measure | report) ;;
    *) echo "usage: bench/run.sh [full|ci|build|measure|report]" >&2; exit 2 ;;
esac
[ $status -eq 0 ] || { step "a build failed"; exit 1; }

case "$mode" in
    full | measure)
        jvm=1 # change and mutants build through the sbt server
        $harness conformance --stacks "$stacks" || status=1
        $harness resilience --stacks "$stacks" || status=1
        $harness perf --stacks "$stacks" --runs 3 || status=1
        $harness change --stacks "$stacks" || status=1
        $harness mutants --stacks "$stacks" || status=1
        report || status=1
        ;;
    ci)
        jvm=1
        $harness conformance --stacks "$stacks" || status=1
        $harness resilience --stacks "$stacks" || status=1
        $harness perf --stacks "$stacks" --quick || status=1
        $harness change --stacks "$stacks" || status=1
        $harness mutants --stacks "$stacks" || status=1
        $harness check --stacks "$stacks" || status=1
        ;;
    report) report || status=1 ;;
esac
step "results in $bench/results/$BENCH_MACHINE"
exit $status
