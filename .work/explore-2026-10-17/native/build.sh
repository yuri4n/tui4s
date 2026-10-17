#!/bin/sh
# build.sh <name> <classpath file> [extra native-image flags...]
S=/tmp/claude-0/-home-user-tui4s/6d712dbd-bef7-57d0-b343-fd9d5dc2fafb/scratchpad
N=$S/native; G=$S/dl/graalvm/graalvm-jdk-25.0.4+7.1
name=$1; cpf=$2; shift 2
mkdir -p $N/out/$name
unset JAVA_TOOL_OPTIONS
export JAVA_HOME=$G
cd $N/out/$name
start=$(date +%s.%N); $G/bin/native-image -cp "$(cat $cpf)" --no-fallback -march=compatibility \
  -H:ConfigurationFileDirectories=${CONFIGDIR:-$N/config} --enable-native-access=ALL-UNNAMED \
  -o $N/out/$name/counter "$@" examples.counter.CounterApp; rc=$?; echo "BUILD_RC=$rc wall_s=$(echo "$(date +%s.%N) - $start" | bc)"; exit $rc
