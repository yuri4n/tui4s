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
// tasty-query on the kyo-ui jar. UTF-8 output keeps the box-drawing
// characters of a golden-screen diff readable in the sbt log.
Test / fork := true
Test / javaOptions ++= Seq(
  "-Xmx1g",
  "-Xss16m",
  "-Dstdout.encoding=UTF-8",
  "-Dstderr.encoding=UTF-8",
  "--sun-misc-unsafe-memory-access=allow",
  "--enable-native-access=ALL-UNNAMED",
)

// The tui4s scheduler values (D-17 c). Kyo reads these properties once, when
// its scheduler starts. With Kyo's defaults the scheduler cycle runs every
// 100 us; with 10 ms it finds and drains a stalled worker less often, which
// costs less CPU when the app is idle and gives fewer split frames under
// load (spike S1: 0 splits with no load, at most 0.75 per 10 000 under load).
// The test JVM always sets them. `TuiApp` (a later pull request) brings
// `tui4s.spi.SchedulerDefaults`, which sets the same values for a real app.
// claude 2026-10-12: created
Test / javaOptions ++= Seq(
  "-Dkyo.scheduler.cycleIntervalNs=10000000",
  "-Dkyo.scheduler.blockingMonitorIntervalNs=20000000",
  "-Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100",
)

// The load flag of the glitch tests (gate 1 item 2). The frame contract is
// SOFT: at most 1 split frame per 10 000 events under load. A glitch test
// sends 500 events and asserts 0 split frames; on a loaded 2-CPU CI runner
// that gives about a 5 percent chance of 1 split, while a real regression
// gives many. So CI sets TUI4S_LOAD=1 (.github/workflows/ci.yml) and the test
// JVM gets -Dtui4s.load=1, which allows 1 split in 500 events. Without the
// variable (a developer machine) the assertion stays 0.
// claude 2026-10-13: created (C3)
Test / javaOptions ++= {
  if (sys.env.get("TUI4S_LOAD").exists(_.trim == "1")) Seq("-Dtui4s.load=1")
  else Seq.empty
}

// The suites run one at a time inside the forked JVM: the settle of the
// runner reads the JVM-wide scheduler, so a suite that runs next to another
// one sees its load. This is sbt's default for a forked JVM; it is set here
// so that a change is a visible decision. Inside a suite, kyo-test runs the
// leaves in parallel unless the suite says `config.sequential` (the runner
// suites do).
// claude 2026-10-12: created
Test / testForkedParallel := false
