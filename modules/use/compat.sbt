// tui4s-use is not published. The JVM classes ship in tui4s-core (ADR 0024).
// The sources still build for Scala.js. The binary-compatibility check for
// the composables is the check of core. The JVM and JS projects take this
// intention (build.sbt, crossModule).
// claude 2026-10-19: created (L2a); 2026-10-20: changed: CONTRIBUTING.md (ADR 0021); codex 2026-10-23: changed: require binary compatibility for the diagnostic patch release; cursor 2026-10-29: changed: this project is not published (ADR 0024)
versionPolicyIntention := Compatibility.None

// cursor 2026-10-29: changed: no published baseline of tui4s-use (ADR 0024)
versionPolicyPreviousVersions := Nil
