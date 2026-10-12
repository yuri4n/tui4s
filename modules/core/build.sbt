// tui4s-core. Versions shared by modules are in project/Versions.scala.
// claude 2026-10-12: changed the whole file: a runner for kyo-ui (RC-16)
description := "A terminal runner for kyo-ui, on Kyo and jatatui."

libraryDependencies ++= Seq(
  "io.getkyo"         %% "kyo-core"          % Versions.kyo,
  // kyo-ui and kyo-core must have the same version. The transitive jars of
  // kyo-ui (kyo-http, kyo-net, kyo-ffi, ...) stay: `UI.Href.Absolute` needs
  // kyo-http (D-18, spike S4).
  "io.getkyo"         %% "kyo-ui"            % Versions.kyo,
  "com.olvind.jatatui" % "jatatui-core"      % Versions.jatatui,
  "com.olvind.jatatui" % "jatatui-crossterm" % Versions.jatatui,
  "io.getkyo"         %% "kyo-test-runner"   % Versions.kyo        % Test,
  // The surface guard reads the TASTy of the kyo-ui jar (KyoUiSurfaceTest).
  "ch.epfl.scala"     %% "tasty-query"       % Versions.tastyQuery % Test,
)

// kyo-test (the Kyo project's own test framework, released with Kyo).
// Use `+=`: `:=` would drop the other frameworks.
Test / testFrameworks += new TestFramework("kyo.test.runner.SbtFramework")

// Run the tests in a forked JVM. Kyo starts one scheduler per class loader and
// nothing stops it, so each test run inside a long-lived sbt (a shell, `~`,
// BSP) would leave one more scheduler and its threads running. A forked JVM
// exits after the tests. The two long flags stop JDK 25 warnings about
// sun.misc.Unsafe and about the native crossterm library. -Xss16m is for
// tasty-query on the kyo-ui jar.
Test / fork := true
Test / javaOptions ++= Seq(
  "-Xmx1g",
  "-Xss16m",
  "--sun-misc-unsafe-memory-access=allow",
  "--enable-native-access=ALL-UNNAMED",
)
