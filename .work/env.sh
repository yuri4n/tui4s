# Source this before running sbt / node in this project:  source .work/env.sh
# Kyo 1.0.0-RC7 needs JDK 25 at compile time (its macros load Kyo classes) and at runtime.
export JAVA_HOME="$HOME/.sdkman/candidates/java/25.0.4+1.1-tem"
export PATH="$JAVA_HOME/bin:$HOME/.sdkman/candidates/sbt/current/bin:$HOME/.sdkman/candidates/scalacli/current/bin:$HOME/.local/share/mise/installs/node/24/bin:$HOME/.local/share/mise/installs/pnpm/10:$PATH"
# Keep sbt memory bounded: this Mac has 8 CPUs / 16 GB and is shared with other work.
# IPv6 is broken on this network, so force IPv4 for dependency downloads.
export SBT_OPTS="-Xmx3g -Xss16m -XX:+UseG1GC -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv4Addresses=true"
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true -Djava.net.preferIPv4Addresses=true"
