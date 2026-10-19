// The assistant is an example app: it is not published (publish / skip in the
// root build.sbt), so it has no binary-compatibility promise. The helper
// crossModule of build.sbt reads this setting, so it exists.
// claude 2026-10-19: created (L3)
versionPolicyIntention := Compatibility.None
