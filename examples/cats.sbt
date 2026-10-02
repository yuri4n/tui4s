// Settings of the `examples` project for the Cats Effect facade. sbt adds the
// .sbt files of a project's directory after the settings in the root
// build.sbt, so the `+=` below comes after FacadeParity.settings(...).

// Facade parity (SPEC 6.5): the Counter body also compiles under the Cats
// Effect import. ParitySuite expects "cats".
FacadeParity.ecosystems += "cats" -> "import tui4s.interop.cats.*"

// The Cats Effect example tests are IO tests, as Cats Effect users write them.
libraryDependencies += "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test
