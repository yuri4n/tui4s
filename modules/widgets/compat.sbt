// Binary-compatibility intention for the NEXT release of this module
// (sbt-version-policy, early-semver; see CONTRIBUTING.md, "Compatibility and
// versioning").
//   BinaryCompatible: the next release can be a patch bump (0.y.z -> 0.y.z+1).
//   None:             the next release must bump the minor (0.y -> 0.y+1).
// Set it back to BinaryCompatible after each release. The JVM and the JS
// artifacts take this intention (build.sbt, crossModule).
// claude 2026-10-19: created (L2a): the module is not released yet; 2026-10-20: changed: CONTRIBUTING.md (ADR 0021); 2026-10-20: changed: BinaryCompatible after the release of 0.1.0-RC1 (After a release, step 3)
versionPolicyIntention := Compatibility.BinaryCompatible
