#!/bin/sh
# build-plain.sh <name> <classpath file> [extra flags]: native-image with no tui4s-specific flags (the metadata comes from the jar)
S=/tmp/claude-0/-home-user-tui4s/6d712dbd-bef7-57d0-b343-fd9d5dc2fafb/scratchpad
N=$S/native; G=$S/dl/graalvm/graalvm-jdk-25.0.4+7.1
name=$1; cpf=$2; shift 2
mkdir -p $N/out/$name; unset JAVA_TOOL_OPTIONS; export JAVA_HOME=$G; cd $N/out/$name
start=$(date +%s.%N); $G/bin/native-image -cp "$(cat $cpf)" --no-fallback -o $N/out/$name/dashboard "$@" examples.dashboard.DashboardApp; rc=$?
echo "BUILD_RC=$rc wall_s=$(echo "$(date +%s.%N) - $start" | bc)"; exit $rc
