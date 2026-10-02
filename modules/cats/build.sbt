// tui4s-cats. Versions shared by modules are in project/Versions.scala.
description := "Cats Effect integration for tui4s."

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect"       % "3.7.1",
  "co.fs2"        %% "fs2-core"          % "3.14.0",
  "org.scalameta" %% "munit"             % Versions.munit % Test,
  "org.typelevel" %% "munit-cats-effect" % "2.2.1"        % Test,
  // Reads the TASTy of the facade: no kyo type in its API (SPEC 6.5).
  "ch.epfl.scala" %% "tasty-query"       % "1.9.0"        % Test,
)

// The Kyo-free API check of core (SurfaceCheck), compiled with these tests.
// Not `core % "test->test"`: the core tests have a stand-in zio.ZIO class.
Test / unmanagedSourceDirectories +=
  (ThisBuild / baseDirectory).value / "modules" / "core" / "src" / "surface" / "scala"

// A forked JVM, as in core: Kyo starts one scheduler per class loader and
// nothing stops it, and SurfaceCheck reads the class path of its own JVM. The
// flags stop JDK 25 warnings about sun.misc.Unsafe and the native crossterm
// library.
Test / fork := true
Test / javaOptions ++= Seq(
  "-Xmx1g",
  "--sun-misc-unsafe-memory-access=allow",
  "--enable-native-access=ALL-UNNAMED",
)
