// tui4s-app is not published. Its classes ship in tui4s-core (ADR 0024).
// The binary-compatibility check for TuiApp is the check of core.
// claude 2026-10-20: changed: CONTRIBUTING.md (ADR 0021); codex 2026-10-23: changed: require binary compatibility for the diagnostic patch release; cursor 2026-10-29: changed: this project is not published (ADR 0024)
versionPolicyIntention := Compatibility.None

// cursor 2026-10-29: changed: no published baseline of tui4s-app (ADR 0024)
versionPolicyPreviousVersions := Nil
