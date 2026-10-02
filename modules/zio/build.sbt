// tui4s-zio. Versions shared by modules are in project/Versions.scala.
description := "ZIO integration for tui4s."

// examples/zio.sbt reads the version of dev.zio::zio from this list.
val zioVersion = "2.1.26"

libraryDependencies ++= Seq(
  "io.getkyo"     %% "kyo-zio"     % Versions.kyo,
  "dev.zio"       %% "zio"         % zioVersion,
  "dev.zio"       %% "zio-streams" % zioVersion,
  "org.scalameta" %% "munit"       % Versions.munit % Test,
  // TestClock only: does zio-test's clock drive the facade's sources? The
  // tests run with munit; zio-test-sbt (the ZIO test runner) is not added.
  "dev.zio"       %% "zio-test"    % zioVersion     % Test,
  // Reads the TASTy of the facade: no kyo type in its API (SPEC 6.5).
  "ch.epfl.scala" %% "tasty-query" % "1.9.0"        % Test,
)

// The Kyo-free signature check of core (SurfaceCheck), compiled from its own
// directory: `core % "test->test"` would bring core's stand-in zio.ZIO class.
Test / unmanagedSourceDirectories +=
  (ThisBuild / baseDirectory).value / "modules" / "core" / "src" / "surface" /
    "scala"

// A forked JVM, as in core: Kyo starts one scheduler per class loader and
// nothing stops it. The flags stop JDK 25 warnings about sun.misc.Unsafe.
Test / fork := true
Test / javaOptions ++= Seq(
  "-Xmx1g",
  "--sun-misc-unsafe-memory-access=allow",
  "--enable-native-access=ALL-UNNAMED",
)
