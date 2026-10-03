#!/usr/bin/env bash
# Idle-CPU probe for the Kyo 1.0.0-RC7 scheduler. Run: bash .work/scratch/idle/run.sh
set -euo pipefail
cd "$(dirname "$0")"
source ../../env.sh
OUT=idle-output.txt
: > "$OUT"
scala-cli --power package --server=false -f --assembly idle.scala -o idle.jar >/dev/null 2>build.log
J=(java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -cp idle.jar)
S=kyo.scheduler
# run <label> <mode> [-Dflag=value ...]
run() { local label=$1 mode=$2; shift 2; "${J[@]}" -Dprobe.label="$label" "$@" tui4s.idle.IdleMain "$mode" 2>>stderr.log | tee -a "$OUT"; }
for i in 1 2; do
  echo "== round $i ==" | tee -a "$OUT"
  run "plain JVM (no Kyo)" jvm
  run "kyo defaults" kyo
  run "cycle 1 ms" kyo -D$S.cycleIntervalNs=1000000
  run "cycle 10 ms" kyo -D$S.cycleIntervalNs=10000000
  run "cycle 10 ms + monitor 20 ms" kyo -D$S.cycleIntervalNs=10000000 -D$S.blockingMonitorIntervalNs=20000000
  run "cycle 10ms+mon 20ms+conc 100ms" kyo -D$S.cycleIntervalNs=10000000 -D$S.blockingMonitorIntervalNs=20000000 \
      -D$S.regulator.concurrencyCollectIntervalMs=100
  run "all above + coreWorkers 2" kyo -D$S.cycleIntervalNs=10000000 -D$S.blockingMonitorIntervalNs=20000000 \
      -D$S.regulator.concurrencyCollectIntervalMs=100 -D$S.coreWorkers=2
done
