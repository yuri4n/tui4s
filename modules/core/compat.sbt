// Binary-compatibility intention for the NEXT release of this module
// (sbt-version-policy, early-semver; see AGENTS.md, "Compatibility").
//   BinaryCompatible: the next release can be a patch bump (0.y.z -> 0.y.z+1).
//   None:             the next release must bump the minor version (0.y -> 0.y+1).
// Set it back to BinaryCompatible after each release.
versionPolicyIntention := Compatibility.None
