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
