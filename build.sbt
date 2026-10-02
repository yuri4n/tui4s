// tui4s: declarative terminal UIs for Scala 3.
//
// Published modules (each one has its own releases; see AGENTS.md):
//   core -> io.github.yuri4n::tui4s-core  (Kyo + jatatui)
//   zio  -> io.github.yuri4n::tui4s-zio   (ZIO integration)
//   cats -> io.github.yuri4n::tui4s-cats  (Cats Effect integration)
// ModuleVersioning and sbt-ci-release set `version` and `publishTo`. Never set
// them here.

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
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all",
  "-Wvalue-discard",
  "-Wnonunit-statement",
  "-Yexplicit-nulls",
  // Each inlined Kyo method that carries @nowarn adds this warning, with no
  // source position, under -Wunused:all. It says nothing about our code.
  "-Wconf:msg=@nowarn annotation does not suppress any warnings:s",
)

// Unreleased inter-project deps have dynver versions like 0.2.0+3-1a2b3c4d.
ThisBuild / versionPolicyIgnoredInternalDependencyVersions :=
  Some("^\\d+\\.\\d+\\.\\d+\\+\\d+".r)

/** A published module in `modules/<id>`, versioned from its `<id>-v*` tags. */
def module(id: String): Project =
  Project(id, file(s"modules/$id"))
    .settings(
      ModuleVersioning(
        tagPrefix = s"$id-v",
        paths = Seq(s"modules/$id", "project/Versions.scala"),
      ),
    )
    .settings(name := s"tui4s-$id")

lazy val root = project
  .in(file("."))
  .aggregate(core, zio, cats, examples, bench)
  .settings(
    name           := "tui4s",
    publish / skip := true,
  )

lazy val core = module("core")
lazy val zio  = module("zio").dependsOn(core)
lazy val cats = module("cats").dependsOn(core)

lazy val launchers =
  taskKey[Seq[File]]("Writes a start script for each example in target/bin")

lazy val examples = project
  .in(file("examples"))
  .dependsOn(core, zio, cats)
  .settings(
    name               := "tui4s-examples",
    publish / skip     := true,
    // A terminal app needs the real TTY: fork a JVM, connect stdin and stdout.
    run / fork         := true,
    run / connectInput := true,
    outputStrategy     := Some(StdoutOutput),
    // Without these flags JDK 25 prints warnings over the UI: crossterm is a
    // JNI library, and Kyo and Scala use sun.misc.Unsafe.
    run / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--sun-misc-unsafe-memory-access=allow",
    ),
  )
  .settings(
    // The example tests use munit, as most users do. A forked JVM, as in core:
    // Kyo starts one scheduler per class loader.
    libraryDependencies += "org.scalameta" %% "munit" % Versions.munit % Test,
    Test / fork                            := true,
    Test / javaOptions ++= Seq(
      "-Xmx1g",
      "--sun-misc-unsafe-memory-access=allow",
    ),
  )
  // One Counter text compiled under the import of each ecosystem (SPEC 6.5).
  .settings(
    FacadeParity.settings(file("examples/src/parity/CounterBody.scala.txt")),
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
      IO.write(bin / "classpath", cp)
      mains.map { main =>
        // The package names the script (counter, catscounter): the examples
        // of the facades use the same class names as the Kyo examples.
        val name = main.split('.').init.last
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

// Benchmarks and probes (SPEC 7.4). Not published. Run them alone: other
// builds on the machine make the numbers useless. See bench/BASELINE.md.
lazy val bench = project
  .in(file("bench"))
  .dependsOn(core, zio, cats, examples)
  .enablePlugins(JmhPlugin)
  .settings(
    name           := "tui4s-bench",
    publish / skip := true,
    run / fork     := true,
    run / javaOptions ++= Seq(
      "--enable-native-access=ALL-UNNAMED",
      "--sun-misc-unsafe-memory-access=allow",
    ),
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
