// The ZIO facade's settings of the examples project (sbt loads the .sbt files
// of a project's base directory after the project's own settings in the root
// build.sbt). The Cats Effect facade has its own file.

// Facade parity (SPEC 6.5): the Counter body also compiles under the ZIO
// import. It must come after `FacadeParity.settings(...)`, which sets the list
// to Kyo only: this file does.
FacadeParity.ecosystems += "zio" -> "import tui4s.interop.zio.*"
