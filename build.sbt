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
// scalacOptions list
ThisBuild / tpolecatDefaultOptionsMode := DevMode
ThisBuild / tpolecatDevModeOptions ++= Set(
  // Reference types are not nullable. Java results get flexible types.
  ScalacOptions.privateExplicitNulls,
  // Each inlined Kyo method that carries @nowarn adds this warning, with no
  // source position, under -Wunused. It says nothing about our code.
  ScalacOptions.other(
    "-Wconf:msg=@nowarn annotation does not suppress any warnings:s",
  ),
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
