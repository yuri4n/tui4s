// tui4s: a terminal runner for kyo-ui, in Scala 3.
// claude 2026-10-12: changed this header: tui4s runs kyo-ui (line 1, core line)
//
// Published modules (each one has its own releases; see AGENTS.md):
//   core -> io.github.yuri4n::tui4s-core  (kyo-ui on Kyo + jatatui)
//   zio  -> io.github.yuri4n::tui4s-zio   (ZIO integration)
//   cats -> io.github.yuri4n::tui4s-cats  (Cats Effect integration)
// ModuleVersioning and sbt-ci-release set `version` and `publishTo`. Never set
// them here.

// claude 2026-10-12: created: sbt-tpolecat types that build.sbt uses
import org.typelevel.sbt.tpolecat.DevMode
import org.typelevel.scalacoptions.ScalacOptions

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

// One test JVM at a time (D-9). The core tests settle on the JVM-wide Kyo
// scheduler. A second test JVM on the same CPUs raises the rate of split
// frames (spike S1: 3 JVMs on 2 CPUs gave 0.67 to 1.50 per 10 000). With this
// limit, sbt runs the forked test JVMs of the modules one after the other.
// claude 2026-10-12: created
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

/** A published module in `modules/<id>`, versioned from its `<id>-v*` tags. */
def module(id: String): Project =
  Project(id, file(s"modules/$id"))
    .settings(
      ModuleVersioning(
        tagPrefix = s"$id-v",
        // claude 2026-10-09: changed paths: also watch build.sbt and project/
        paths = Seq(s"modules/$id", "build.sbt", "project"),
      ),
    )
    .settings(name := s"tui4s-$id")

lazy val root = project
  .in(file("."))
  .aggregate(core, zio, cats, examples)
  .settings(
    name           := "tui4s",
    publish / skip := true,
  )

lazy val core = module("core")
lazy val zio  = module("zio").dependsOn(core)
lazy val cats = module("cats").dependsOn(core)

// claude 2026-10-13: created: the task key of the start scripts (OS-20; the
// form of the feat/cats branch)
lazy val launchers =
  taskKey[Seq[File]]("Writes a start script for each example in target/bin")

// The sample apps (not published). They depend on core only: the zio and cats
// modules are empty in this version.
// claude 2026-10-13: changed (T1): core only; no `run / connectInput`; the
// JVM flags of a real run; munit tests; the launchers task
lazy val examples = project
  .in(file("examples"))
  .dependsOn(core)
  .settings(
    name           := "tui4s-examples",
    publish / skip := true,
    // `sbt run` is not a way to use a terminal UI (see the launchers below):
    // the forked JVM gets no terminal on stdin, so the app stops with
    // NotATerminal and prints why.
    run / fork     := true,
    outputStrategy := Some(StdoutOutput),
    // The JVM flags of a real run. The launchers write them into each script.
    // --enable-native-access: crossterm is a JNI library (without the flag
    // JDK 25 prints a warning over the UI; with --illegal-native-access=deny
    // the app stops with NativeAccessDenied). --sun-misc-unsafe-memory-access:
    // Kyo and Scala use sun.misc.Unsafe. -Xss16m: Kyo's deep stacks, as in
    // the prototype runs.
    run / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--sun-misc-unsafe-memory-access=allow",
      "-Xss16m",
    ),
    // The tui4s scheduler values (D-17 c), as JVM flags: Kyo reads them once,
    // when its scheduler starts. With them the runner gives fewer split
    // frames under load and uses less CPU when the app is idle (spike S1).
    // `TuiApp` (a later pull request) sets them in code
    // (`tui4s.spi.SchedulerDefaults`); an app with its own `main` gives them
    // as here.
    run / javaOptions ++= Seq(
      "-Dkyo.scheduler.cycleIntervalNs=10000000",
      "-Dkyo.scheduler.blockingMonitorIntervalNs=20000000",
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
      "-Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100",
    ),
  )
  // `sbt run` is not a good way to use a terminal UI: sbt also reads the
  // keyboard, so some keys never reach the app (and `sbt -batch` gives the app
  // a pipe as stdin). `examples/launchers` writes target/bin/<app>, a script
  // that starts the app in its own JVM, and target/bin/classpath.
  .settings(
    launchers := {
      val cp    = (Runtime / fullClasspath).value.files
        .mkString(java.io.File.pathSeparator)
      val flags = (run / javaOptions).value.mkString(" ")
      val bin   = target.value / "bin"
      val mains = (Compile / discoveredMainClasses).value
        .filter(_.startsWith("examples."))
      // The package names the script (examples.counter.CounterKyoApp ->
      // counter), so one package has one main.
      // claude 2026-10-13: created: two mains in one package stop the task
      val names = mains.map(main => main -> main.split('.').init.last)
      names.groupBy(_._2).foreach { case (name, ms) =>
        if (ms.size > 1)
          sys.error(
            s"examples/launchers: ${ms.map(_._1).mkString(", ")} get the same script name $name",
          )
      }
      IO.write(bin / "classpath", cp)
      names.map { case (main, name) =>
        val file = bin / name
        IO.write(
          file,
          s"""#!/bin/sh
             |# Starts $main in its own JVM. Written by `sbt examples/launchers`.
             |# Extra JVM options: JAVA_OPTS. Program arguments: the arguments.
             |exec "$${JAVA_HOME:+$$JAVA_HOME/bin/}java" $flags $$JAVA_OPTS \\
             |  -cp "$cp" $main "$$@"
             |""".stripMargin,
        )
        file.setExecutable(true)
        file
      }
    },
  )

Global / commands ++= Seq(
  ModuleVersioning.releaseModule,
  ModuleVersioning.stageModule,
)

addCommandAlias("fmt", "; scalafmtAll; scalafmtSbt")
addCommandAlias("fmtCheck", "; scalafmtCheckAll; scalafmtSbtCheck")
addCommandAlias(
  "check",
  "; scalafmtCheckAll; scalafmtSbtCheck; Test/compile; test; versionPolicyCheck",
)
addCommandAlias(
  "versions",
  "; show core/version; show zio/version; show cats/version",
)
