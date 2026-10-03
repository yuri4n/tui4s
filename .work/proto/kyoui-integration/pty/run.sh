#!/usr/bin/env bash
# claude 2026-10-03: created: starts one main of the integration assembly jar ($KYOUI_JAR) with the runner's JVM flags.
# Adapted from .work/proto/kyoui-runner/pty/run.sh. JAVA_TOOL_OPTIONS (proxy settings of this container) is unset: the JVM
# would print "Picked up JAVA_TOOL_OPTIONS" into the terminal, and the apps need no network.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS
exec /usr/lib/jvm/java-25-openjdk-amd64/bin/java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
    -Xss16m -Xmx512m $JFLAGS -cp "$KYOUI_JAR" "$@"
