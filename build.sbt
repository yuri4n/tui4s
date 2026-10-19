// tui4s: a terminal runner for kyo-ui, in Scala 3.
// claude 2026-10-12: changed this header: tui4s runs kyo-ui (line 1, core line)
//
// Published modules (each one has its own releases; see AGENTS.md):
//   runner  -> io.github.yuri4n::tui4s-runner   (kyo-ui on Kyo + jatatui)
//   app     -> io.github.yuri4n::tui4s-app      (TuiApp, on the runner)
//   testkit -> io.github.yuri4n::tui4s-testkit  (Headless, on the runner)
//   core    -> io.github.yuri4n::tui4s-core     (runner + app + testkit)
// claude 2026-10-19: changed: the four modules of the split of the core (D25)
//   zio  -> io.github.yuri4n::tui4s-zio   (ZIO integration)
//   cats -> io.github.yuri4n::tui4s-cats  (Cats Effect integration)
//   use     -> io.github.yuri4n::tui4s-use      (composables; JVM and JS)
//   widgets -> io.github.yuri4n::tui4s-widgets  (widgets; JVM and JS)
// claude 2026-10-19: changed: the two cross-built modules (L2a)
// ModuleVersioning and sbt-ci-release set `version` and `publishTo`. Never set
// them here.

// claude 2026-10-12: created: sbt-tpolecat types that build.sbt uses
import org.typelevel.sbt.tpolecat.DevMode
import org.typelevel.scalacoptions.ScalacOptions
// claude 2026-10-19: created: the type of a cross-built module (L2a)
import sbtcrossproject.CrossProject

ThisBuild / organization      := "io.github.yuri4n"
ThisBuild / homepage          := Some(url("https://github.com/yuri4n/tui4s"))
ThisBuild / licenses          := List(License.Apache2)
ThisBuild / developers        := List(
  Developer(
    "yuri4n",
    "yuri4n",
    "oss.juli@pm.me",
    url("https://github.com/yuri4n"),
  ),
)
ThisBuild / scalaVersion      := Versions.scala
ThisBuild / semanticdbEnabled := true

// Scalafix: the main sources use .scalafix.conf (OrganizeImports and
// DisableSyntax), the test sources use .scalafix-test.conf (OrganizeImports
// only). A project/Test lookup finds ThisBuild/Test before the Global default.
// claude 2026-10-17: created (purity plan, phase P0)
ThisBuild / Test / scalafixConfig :=
  Some((ThisBuild / baseDirectory).value / ".scalafix-test.conf")

// Compiler options (D-8). sbt-tpolecat sets them. Its default set for Scala 3
// has -deprecation, -feature, -unchecked, -Wvalue-discard,
// -Wnonunit-statement and the -Wunused:* checks. On a developer machine the
// warnings stay warnings (DevMode). CI sets the environment variable
// SBT_TPOLECAT_CI, which adds -Werror (CiMode). To get the CI behaviour in a
// local sbt session, run `tpolecatCiMode` first.
// claude 2026-10-12: created: the two settings below replace the hand-written
// scalacOptions list; changed: the -Wconf comment (review R1-7, R2-2)
ThisBuild / tpolecatDefaultOptionsMode := DevMode
ThisBuild / tpolecatDevModeOptions ++= Set(
  // Reference types are not nullable. Java results get flexible types.
  ScalacOptions.privateExplicitNulls,
  // Under -Wunused:nowarn (or -Wunused:all, -Wall) each inlined Kyo method
  // that carries @nowarn adds this warning, with no source position. It says
  // nothing about our code. The sbt-tpolecat set has no -Wunused:nowarn, so
  // the rule does nothing today; it stays for the day that check is on.
  ScalacOptions.other(
    "-Wconf:msg=@nowarn annotation does not suppress any warnings:s",
  ),
)
// -Ycheck-all-patmat is a compiler-testing flag ("used for testing the
// algorithm"). It reports a match on Kyo's opaque `Maybe` (`Present`, `Absent`)
// as not exhaustive. That is a false alarm: the two extractors cover every
// value. The sealed types keep their exhaustivity check without this flag.
// claude 2026-10-12: created
ThisBuild / tpolecatExcludeOptions += ScalacOptions.privateCheckAllPatmat

// Unreleased inter-project deps have dynver versions like 0.2.0+3-1a2b3c4d.
ThisBuild / versionPolicyIgnoredInternalDependencyVersions :=
  Some("^\\d+\\.\\d+\\.\\d+\\+\\d+".r)

// One test JVM at a time (D-9). The runner tests settle on the JVM-wide Kyo
// scheduler. A second test JVM on the same CPUs raises the rate of split
// frames (spike S1: 3 JVMs on 2 CPUs gave 0.67 to 1.50 per 10 000). With this
// limit, sbt runs the forked test JVMs of the modules one after the other.
// claude 2026-10-12: created
// claude 2026-10-19: changed: the runner tests (D25)
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

/** The test settings of a module whose suites run the runner: runner, app,
  * testkit and core (D25). They were the settings of modules/core/build.sbt.
  *
  * kyo-test (the Kyo project's own test framework, released with Kyo) runs the
  * suites; kyo-test-prop gives the property tests (PropertyTest, Gen, Shrink);
  * tasty-query reads the TASTy of the kyo-ui jar (KyoUiSurfaceTest) and of the
  * tui4s jars (PublicApiTest).
  *
  * The tests run in a forked JVM. Kyo starts one scheduler per class loader and
  * nothing stops it, so each test run inside a long-lived sbt (a shell, `~`,
  * BSP) would leave one more scheduler and its threads running. A forked JVM
  * exits after the tests. The two long flags stop JDK 25 warnings about
  * sun.misc.Unsafe and about the native crossterm library. -Xss16m is for
  * tasty-query on the kyo-ui jar. UTF-8 output keeps the box-drawing characters
  * of a golden-screen diff readable in the sbt log.
  *
  * The tui4s scheduler values (D-17 c). Kyo reads these properties once, when
  * its scheduler starts. With Kyo's defaults the scheduler cycle runs every 100
  * us; with 10 ms it finds and drains a stalled worker less often, which costs
  * less CPU when the app is idle and gives fewer split frames under load (spike
  * S1: 0 splits with no load, at most 0.75 per 10 000 under load). The minimum
  * interval of the blocking monitor removes a measurement of about 90 ms when
  * the scheduler starts (see `SchedulerDefaults`). The test JVM always sets
  * them. `TuiApp` sets the same values for a real app
  * (`tui4s.spi.SchedulerDefaults`, in its constructor).
  *
  * The load flag of the glitch tests (gate 1 item 2). The frame contract is
  * SOFT: at most 1 split frame per 10 000 events under load. A glitch test
  * sends 500 events and asserts 0 split frames; on a loaded 2-CPU CI runner
  * that gives about a 5 percent chance of 1 split, while a real regression
  * gives many. So CI sets TUI4S_LOAD=1 (.github/workflows/ci.yml) and the test
  * JVM gets -Dtui4s.load=1, which allows 1 split in 500 events. Without the
  * variable (a developer machine) the assertion stays 0.
  *
  * The suites run one at a time inside the forked JVM: the settle of the runner
  * reads the JVM-wide scheduler, so a suite that runs next to another one sees
  * its load. This is sbt's default for a forked JVM; it is set here so that a
  * change is a visible decision. Inside a suite, kyo-test runs the leaves in
  * parallel unless the suite says `config.sequential` (the runner suites do).
  */
// claude 2026-10-19: created (D25): the test settings of modules/core/build.sbt
// (created 2026-10-12, changed 2026-10-13 (T2, C3) and 2026-10-19 (C2)), for
// the four modules of the split
lazy val runnerTestSettings = Seq(
  libraryDependencies ++= Seq(
    "io.getkyo"     %% "kyo-test-runner" % Versions.kyo        % Test,
    "io.getkyo"     %% "kyo-test-prop"   % Versions.kyo        % Test,
    "ch.epfl.scala" %% "tasty-query"     % Versions.tastyQuery % Test,
  ),
  // Use `+=`: `:=` would drop the other frameworks.
  Test / testFrameworks += new TestFramework("kyo.test.runner.SbtFramework"),
  Test / fork               := true,
  Test / javaOptions ++= Seq(
    "-Xmx1g",
    "-Xss16m",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
    "--sun-misc-unsafe-memory-access=allow",
    "--enable-native-access=ALL-UNNAMED",
    "-Dkyo.scheduler.cycleIntervalNs=10000000",
    "-Dkyo.scheduler.blockingMonitorIntervalNs=20000000",
    "-Dkyo.scheduler.blockingMonitorMinIntervalNs=20000000",
    "-Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100",
  ),
  Test / javaOptions ++= {
    if (sys.env.get("TUI4S_LOAD").exists(_.trim == "1")) Seq("-Dtui4s.load=1")
    else Seq.empty
  },
  Test / testForkedParallel := false,
)

/** A published module in `modules/<id>`, versioned from its `<id>-v*` tags. A
  * change in `modules/<id>`, in `watch` (more directories whose code goes into
  * the module), in `build.sbt` or in `project` makes it unreleased.
  */
// claude 2026-10-19: changed: the directories of `watch` (D25 hygiene)
def module(id: String, watch: Seq[String] = Seq.empty): Project =
  Project(id, file(s"modules/$id"))
    .settings(
      ModuleVersioning(
        tagPrefix = s"$id-v",
        // claude 2026-10-09: changed paths: also watch build.sbt and project/
        paths = Seq(s"modules/$id") ++ watch ++ Seq("build.sbt", "project"),
      ),
    )
    .settings(name := s"tui4s-$id")

// claude 2026-10-19: changed: the cross-built modules (L2a); the four modules
// of the split of the core (D25); the assistant (L3); the web app (L4)
lazy val root = project
  .in(file("."))
  .aggregate(
    runner,
    app,
    testkit,
    core,
    zio,
    cats,
    use,
    widgets,
    assistant,
    examples,
    assistantWeb,
  )
  .settings(
    name           := "tui4s",
    publish / skip := true,
  )

// The split of tui4s-core (D25, issue #17). The runner is the renderer, the
// embedder and the component model; app holds TuiApp and testkit holds
// Headless, each on the runner; core is the composite of the three. A test
// suite lives in the lowest module whose main classpath it needs: the suites
// that need only the runner are in modules/runner, and the suites that run
// Headless or TuiApp are in modules/core. They use the test sources of the
// runner (FakeCrossterm, the fixtures, ScriptedInput), hence test->test.
// claude 2026-10-19: created (D25)
lazy val runner  = module("runner").settings(runnerTestSettings)
lazy val app     = module("app").dependsOn(runner).settings(runnerTestSettings)
lazy val testkit =
  module("testkit").dependsOn(runner).settings(runnerTestSettings)
// claude 2026-10-19: changed: the composite watches the code of its three
// modules, so a change there makes it unreleased too (D25 hygiene)
lazy val core    = module(
  "core",
  watch = Seq("modules/runner", "modules/app", "modules/testkit"),
)
  .dependsOn(runner % "compile->compile;test->test", app, testkit)
  .settings(runnerTestSettings)
// claude 2026-10-19: changed: zio and cats depend on the runner (D25)
lazy val zio     = module("zio").dependsOn(runner)
lazy val cats    = module("cats").dependsOn(runner)

/** A published module in `modules/<id>` for the JVM and for Scala.js, with no
  * dependency on the core: plain kyo-ui code (D6). The sources are in
  * `shared/`, `jvm/` and `js/` (CrossType.Full); the sbt ids are `<id>JVM` and
  * `<id>JS`. The artifacts are `tui4s-<id>` and `tui4s-<id>_sjs1`. The tests
  * use kyo-test on both platforms (Node.js runs the JS tests).
  */
// claude 2026-10-19: created (L2a)
def crossModule(id: String): CrossProject =
  CrossProject(id, file(s"modules/$id"))(JVMPlatform, JSPlatform)
    .crossType(CrossType.Full)
    .settings(
      ModuleVersioning(
        tagPrefix = s"$id-v",
        paths = Seq(s"modules/$id", "build.sbt", "project"),
      ),
      name                   := s"tui4s-$id",
      // modules/<id>/compat.sbt sets the intention of the aggregate project
      // <id> (its base directory); both platforms take it from there.
      versionPolicyIntention :=
        (LocalProject(id) / versionPolicyIntention).value,
      // kyo-kernel's POM brings JMH in compile scope (see
      // modules/runner/build.sbt): keep it out of the POM of the module.
      // claude 2026-10-19: changed: modules/runner (D25)
      libraryDependencies ++= Seq(
        ("io.getkyo" %%% "kyo-ui"          % Versions.kyo)
          .excludeAll(ExclusionRule("org.openjdk.jmh")),
        "io.getkyo"  %%% "kyo-test-runner" % Versions.kyo % Test,
        "io.getkyo"  %%% "kyo-test-prop"   % Versions.kyo % Test,
      ),
    )
    .jvmSettings(
      Test / testFrameworks +=
        new TestFramework("kyo.test.runner.SbtFramework"),
      // A forked test JVM, as in the core: Kyo starts one scheduler per
      // class loader, and nothing stops it.
      Test / fork := true,
      Test / javaOptions ++= Seq(
        "-Xmx1g",
        "--sun-misc-unsafe-memory-access=allow",
        "--enable-native-access=ALL-UNNAMED",
      ),
    )
    .jsSettings(
      // kyo-test has another framework class on Scala.js.
      Test / testFrameworks +=
        new TestFramework("kyo.test.runner.JsFramework"),
    )

/** The project `<id>` of a cross-built module: it aggregates `<id>JVM` and
  * `<id>JS`, so that `<id>/test`, `<id>/publishSigned` and the release tag
  * `<id>-v<semver>` (scripts/release.sh, release.yml) act on both. Its base
  * directory `modules/<id>` holds `compat.sbt`.
  */
// claude 2026-10-19: created (L2a)
def crossAggregate(id: String, m: CrossProject): Project =
  Project(id, file(s"modules/$id"))
    .aggregate(m.jvm, m.js)
    .settings(
      ModuleVersioning(
        tagPrefix = s"$id-v",
        paths = Seq(s"modules/$id", "build.sbt", "project"),
      ),
      name           := s"tui4s-$id",
      publish / skip := true,
    )

// claude 2026-10-19: created (L2a): the composables (tui4s.use) and the
// widgets (tui4s.widgets); changed: widgets depends on use on both platforms
// (L2b): the lists take useSelection, the modal useDialog, ...
lazy val useCross     = crossModule("use").settings(
  // kyo-system (Path, Command): the files of usePersisted and the process
  // of useProcess on the JVM. kyo-ui brings it already; the module names it
  // because it calls it. Its POM brings JMH through kyo-kernel too.
  // claude 2026-10-19: changed: JMH is out of the POM (L2a review)
  libraryDependencies += ("io.getkyo" %%% "kyo-system" % Versions.kyo)
    .excludeAll(ExclusionRule("org.openjdk.jmh")),
)
lazy val useJVM       = useCross.jvm
lazy val useJS        = useCross.js
lazy val use          = crossAggregate("use", useCross)
lazy val widgetsCross = crossModule("widgets").dependsOn(useCross)
lazy val widgetsJVM   = widgetsCross.jvm
lazy val widgetsJS    = widgetsCross.js
lazy val widgets      = crossAggregate("widgets", widgetsCross)

/** The flagship example app, the AI assistant (L3, D3): the domain, the
  * services, the store and the UI in `shared/` (plain kyo-ui, tui4s-use and
  * tui4s-widgets: no tui4s-core), the interpreters of the JVM in `jvm/` (the
  * HTTP client of kyo-http, the keys, the files), the interpreters of the
  * browser in `js/` (fetch, the keys in `localStorage`, the storage). The
  * terminal main is `examples.assistant.AssistantApp` in the examples project;
  * the web main is in the project `assistantWeb`. Not published.
  */
// claude 2026-10-19: created (L3); changed: the JS interpreters (L4)
lazy val assistantCross = crossModule("assistant")
  .dependsOn(widgetsCross)
  .settings(publish / skip := true)
  .jvmSettings(
    // kyo-http (the client of the providers). kyo-ui brings it already; the
    // module names it because it calls it. JMH stays out, as in use.
    libraryDependencies += ("io.getkyo" %% "kyo-http" % Versions.kyo)
      .excludeAll(ExclusionRule("org.openjdk.jmh")),
  )
  .jsSettings(
    // The recorded fixtures of the JVM tests, as a Scala object of the JS
    // tests (Node.js reads no resource file): FetchHttpTest serves them from
    // a fake fetch.
    // claude 2026-10-19: created (L4)
    Test / sourceGenerators += Def.task {
      val dir                        = (ThisBuild / baseDirectory).value /
        "modules/assistant/jvm/src/test/resources/fixtures"
      val out                        =
        (Test / sourceManaged).value / "examples/assistant/Fixtures.scala"
      def literal(s: String): String = s.flatMap {
        case '\\'         => "\\\\"
        case '"'          => "\\\""
        case '\n'         => "\\n"
        case '\r'         => "\\r"
        case c if c < ' ' => f"\\u${c.toInt}%04x"
        case c            => c.toString
      }
      val entries                    = IO
        .listFiles(dir)
        .sortBy(_.getName)
        .map(f => s"""    "${f.getName}" -> "${literal(IO.read(f))}",""")
      IO.write(
        out,
        ("package examples.assistant" +: "" +:
          "/** The fixtures of the JVM tests (generated by build.sbt). */" +:
          "object Fixtures:" +:
          "  val texts: Map[String, String] = Map(" +: entries :+ "  )")
          .mkString("", "\n", "\n"),
      )
      Seq(out)
    }.taskValue,
  )
lazy val assistantJVM   = assistantCross.jvm
lazy val assistantJS    = assistantCross.js
lazy val assistant      = crossAggregate("assistant", assistantCross)

// Optional named shared state; widgets use Kyo's native signals and callbacks.
lazy val pini4s = ProjectRef(file("vendor/pini4s"), "root")

// claude 2026-10-13: created: the task key of the start scripts (OS-20; the
// form of the feat/cats branch)
lazy val launchers =
  taskKey[Seq[File]]("Writes a start script for each example in target/bin")

// The sample apps use the terminal runner and optional stores.
// The zio and cats modules are empty in this version.
// claude 2026-10-19: changed: core, the composables and the widgets; no
// `run / connectInput`; the JVM flags of a real run; munit tests; the
// launchers task (T1, L2a); the assistant (L3); the test sources of the
// assistant: its interpreters in memory and its gate (L3 review)
lazy val examples = project
  .in(file("examples"))
  .dependsOn(
    core,
    useJVM,
    widgetsJVM,
    assistantJVM % "compile->compile;test->test",
    pini4s,
  )
  .enablePlugins(JavaAppPackaging)
  .settings(
    name           := "tui4s-examples",
    maintainer     := "yuri4n",
    publish / skip := true,
    // kyo-core of pini4s brings JMH too (see modules/runner/build.sbt): keep
    // it out of the distribution.
    // claude 2026-10-19: created (D); changed: modules/runner (D25)
    excludeDependencies += ExclusionRule("org.openjdk.jmh"),
    // `sbt run` is not a way to use a terminal UI (see the launchers below):
    // the forked JVM gets no terminal on stdin, so the app stops with
    // NotATerminal and prints why.
    run / fork     := true,
    outputStrategy := Some(StdoutOutput),
    // The JVM flags of a real run. Packaged launchers load the same flags.
    // --enable-native-access: crossterm is a JNI library (without the flag
    // JDK 25 prints a warning into the terminal before the UI starts; with
    // --illegal-native-access=deny the app stops with NativeAccessDenied).
    // --sun-misc-unsafe-memory-access: Kyo and Scala use sun.misc.Unsafe.
    // -Xss16m: Kyo's deep stacks, as in the prototype runs.
    // claude 2026-10-13: changed: where the warning goes (seen in a PTY)
    run / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--sun-misc-unsafe-memory-access=allow",
      "-Xss16m",
    ),
    // The tui4s scheduler values (D-17 c), as JVM flags: Kyo reads them once,
    // when its scheduler starts. With them the runner gives fewer split
    // frames under load and uses less CPU when the app is idle (spike S1).
    // A `TuiApp` also sets them in code (`tui4s.spi.SchedulerDefaults`); an
    // app with its own `main` (examples.counterkyo) needs them as here.
    // claude 2026-10-13: changed (T2): TuiApp is here
    // claude 2026-10-19: changed (C2): blockingMonitorMinIntervalNs (no
    // measurement of about 90 ms when the scheduler starts)
    run / javaOptions ++= Seq(
      "-Dkyo.scheduler.cycleIntervalNs=10000000",
      "-Dkyo.scheduler.blockingMonitorIntervalNs=20000000",
      "-Dkyo.scheduler.blockingMonitorMinIntervalNs=20000000",
      "-Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100",
    ),
  )
  .settings(
    // The example tests use munit, as most users do. A forked JVM, as in core:
    // Kyo starts one scheduler per class loader. The test JVM gets the
    // scheduler values too, because the tests run the runner (Headless).
    libraryDependencies += "org.scalameta" %% "munit" % Versions.munit % Test,
    Test / fork                            := true,
    Test / javaOptions ++= Seq(
      "-Xmx1g",
      "--sun-misc-unsafe-memory-access=allow",
      "--enable-native-access=ALL-UNNAMED",
      "-Dkyo.scheduler.cycleIntervalNs=10000000",
      "-Dkyo.scheduler.blockingMonitorIntervalNs=20000000",
      // claude 2026-10-19: created (C2)
      "-Dkyo.scheduler.blockingMonitorMinIntervalNs=20000000",
      "-Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100",
    ),
  )
  // Native-packager owns the portable jar layout and Bash/Windows scripts.
  // No default main: it generates a launcher for each discovered example.
  // Keep package names (counter, dashboard, ...) in the distribution.
  .settings(
    Compile / mainClass     := None,
    Universal / javaOptions := (run / javaOptions).value.map("-J" + _),
    Universal / mappings    := ExamplePackaging.mappings(
      (Universal / mappings).value,
      (Compile / discoveredMainClasses).value,
      executableScriptName.value,
    ),
    // These small forwarders keep the existing developer and PTY paths.
    // They run the packaged scripts outside sbt and preserve app arguments.
    launchers               := ExamplePackaging.launchers(
      (Universal / stage).value,
      target.value,
      scriptClasspath.value,
      (Compile / discoveredMainClasses).value,
    ),
  )

Global / commands ++= Seq(
  ModuleVersioning.releaseModule,
  ModuleVersioning.stageModule,
)

addCommandAlias("fmt", "; scalafmtAll; scalafmtSbt")
addCommandAlias("fmtCheck", "; scalafmtCheckAll; scalafmtSbtCheck")
// claude 2026-10-17: changed: `scalafixAll --check` after the compile
addCommandAlias(
  "check",
  "; scalafmtCheckAll; scalafmtSbtCheck; Test/compile; scalafixAll --check; test; versionPolicyCheck",
)
// claude 2026-10-19: changed: use and widgets (L2a); runner, app and testkit
// (D25)
addCommandAlias(
  "versions",
  "; show runner/version; show app/version; show testkit/version; show core/version; show zio/version; show cats/version; show useJVM/version; show widgetsJVM/version",
)

/** The assistant on the web (L4, D5): a standalone Scala.js app from the shared
  * module of the assistant and its JS interpreters. It is static files (one
  * page and one script), with no server and no link to the terminal app. It is
  * in `examples/web`, not in `modules`: it is an app, as the examples are, and
  * `modules` has the libraries. `scripts/web-build.sh` links it (fullLinkJS)
  * and assembles `dist/assistant`. Not published.
  *
  * The locale database: kyo-ui brings the full CLDR database of
  * scala-java-locales (61 percent of the default bundle of 33 MB); the app
  * shows English only, so the minimal en-US database replaces it (the research
  * of 2026-10-17: 7.9 MB, 0.99 MB with gzip).
  */
// claude 2026-10-19: created (L4)
lazy val assistantWeb = project
  .in(file("examples/web"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(assistantJS)
  .settings(
    name                                                := "tui4s-assistant-web",
    publish / skip                                      := true,
    scalaJSUseMainModuleInitializer                     := true,
    excludeDependencies += ExclusionRule(
      "io.github.cquiroz",
      "locales-full-currencies-db_sjs1_3",
    ),
    libraryDependencies += "io.github.cquiroz"         %%% "locales-minimal-en_us-db" % "1.5.4",
    // The script of the page has no source map (the map holds the paths of
    // the build machine), and it goes to a path with no Scala version, for
    // scripts/web-build.sh.
    // claude 2026-10-19: created (L4 review)
    Compile / fullLinkJS / scalaJSLinkerConfig ~= (_.withSourceMap(false)),
    Compile / fullLinkJS / scalaJSLinkerOutputDirectory := target.value / "web-opt",
  )
