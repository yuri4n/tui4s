#!/bin/sh
# graal-agent.sh <config out dir> [extra JVM flags...]: the counter on the GraalVM JVM with the tracing agent
S=/tmp/claude-0/-home-user-tui4s/6d712dbd-bef7-57d0-b343-fd9d5dc2fafb/scratchpad
G=$S/dl/graalvm/graalvm-jdk-25.0.4+7.1
stage=$S/perf/stage-pristine
out=$1; shift
cp=$(sed -n 's/^declare -r app_classpath="\(.*\)"$/\1/p' "$stage/bin/counter" | sed "s|\$lib_dir|$stage/lib|g")
exec $G/bin/java -agentlib:native-image-agent=config-merge-dir=$out --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Xss16m \
  -Dkyo.scheduler.cycleIntervalNs=10000000 -Dkyo.scheduler.blockingMonitorIntervalNs=20000000 \
  -Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100 "$@" -cp "$cp" examples.counter.CounterApp
