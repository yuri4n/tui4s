#!/bin/bash
# Runs one example main on the exported classpath. Extra JVM flags come from $JFLAGS.
exec "/Users/yuri4n/.sdkman/candidates/java/25.0.4+1.1-tem/bin/java" ${JFLAGS:---enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow} -Duser.home="/Users/yuri4n/Developer/projects/tui4s/.work/scratch/pty/home" -cp "$(cat "/Users/yuri4n/Developer/projects/tui4s/.work/scratch/pty/cp.txt")" "$@"
