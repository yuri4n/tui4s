// tui4s-core. Versions shared by modules are in project/Versions.scala.
description := "Declarative terminal UIs for Scala 3 on Kyo, rendered with jatatui."

libraryDependencies ++= Seq(
  "io.getkyo"         %% "kyo-core"          % Versions.kyo,
  "com.olvind.jatatui" % "jatatui-core"      % Versions.jatatui,
  "com.olvind.jatatui" % "jatatui-widgets"   % Versions.jatatui,
  "com.olvind.jatatui" % "jatatui-crossterm" % Versions.jatatui,
  "io.getkyo"         %% "kyo-test-runner"   % Versions.kyo % Test,
  // Reads the TASTy of the shared surface: no kyo type in it (SPEC 6.5).
  "ch.epfl.scala"     %% "tasty-query"       % "1.9.0"      % Test,
)

// kyo-test (the Kyo project's own test framework, released with Kyo).
// Use `+=`: `:=` would drop the other frameworks.
Test / testFrameworks += new TestFramework("kyo.test.runner.SbtFramework")

// Run the tests in a forked JVM. Kyo starts one scheduler per class loader and
// nothing stops it, so each test run inside a long-lived sbt (a shell, `~`,
// BSP) would leave one more scheduler and its threads running. A forked JVM
// exits after the tests. The flags stop JDK 25 warnings about sun.misc.Unsafe
// and about the native crossterm library (NativeTerminalTest loads it).
Test / fork := true
Test / javaOptions ++= Seq(
  "-Xmx1g",
  "--sun-misc-unsafe-memory-access=allow",
  "--enable-native-access=ALL-UNNAMED",
)
