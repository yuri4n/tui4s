// The bench build: the JVM implementations of the bench tasks (tui4s,
// jatatui-plain, layoutz, tamboui). It is not part of the root build (D10): it
// reads the tui4s artifacts from the local Ivy repository, so no published
// module of the root build changes. bench/run.sh publishes them first and
// writes their version into bench/tui4s.version.
// claude 2026-10-20: created: the bench build of the JVM stacks (L5)

// The version of the tui4s artifacts: the system property `tui4s.version`,
// else the file bench/tui4s.version.
// claude 2026-10-20: created: the version of the tui4s artifacts (L5)
val tui4sVersion: String =
  sys.props
    .get("tui4s.version")
    .orElse {
      val f = file("tui4s.version")
      if (f.exists) Some(IO.read(f).trim) else None
    }
    .getOrElse("unset-run-bench-run.sh-first")

val jatatuiVersion = "0.31.0"
val layoutzVersion = "0.8.0"
val tambouiVersion = "0.6.0"
val gjfVersion = "1.37.0"

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "bench"
ThisBuild / version := "0.1.0"
ThisBuild / resolvers += Resolver.mavenLocal
Global / onChangedBuildSource := ReloadOnSourceChanges

// The settings of one task: its sources are the files in its directory (no
// src/main/scala), it stages its jars (sbt-native-packager), and the JVM flags
// are in the run.sh of the stack.
// claude 2026-10-20: created: the settings of one task (L5)
def task(dir: String, main: String): Seq[Setting[_]] = Seq(
  Compile / scalaSource := baseDirectory.value,
  Compile / javaSource := baseDirectory.value,
  Compile / unmanagedSources / excludeFilter :=
    HiddenFileFilter || "target" || "*.patch",
  Compile / mainClass := Some(main),
  Compile / packageDoc / publishArtifact := false,
  Compile / doc / sources := Nil,
  scalacOptions ++= Seq("-deprecation", "-feature"),
  javacOptions ++= Seq("--release", "25")
)

// A Java task: no Scala library on its class path.
// claude 2026-10-20: created: the settings of a Java task (L5)
val javaOnly: Seq[Setting[_]] = Seq(
  crossPaths := false,
  autoScalaLibrary := false
)

// tui4s-core brings the runner, TuiApp, the composables and the widgets.
// cursor 2026-10-29: changed: one coordinate (ADR 0024)
lazy val tui4sDeps = Seq(
  "io.github.yuri4n" %% "tui4s-core" % tui4sVersion
)

// kyo-kernel's POM has JMH in compile scope; tui4s excludes it too.
val noJmh = ExclusionRule("org.openjdk.jmh")

lazy val tui4sCounter = project
  .in(file("impl/tui4s/counter"))
  .enablePlugins(JavaAppPackaging)
  .settings(task("tui4s/counter", "CounterApp"))
  .settings(libraryDependencies ++= tui4sDeps, excludeDependencies += noJmh)

lazy val tui4sDashboard = project
  .in(file("impl/tui4s/dashboard"))
  .enablePlugins(JavaAppPackaging)
  .settings(task("tui4s/dashboard", "DashboardApp"))
  .settings(libraryDependencies ++= tui4sDeps, excludeDependencies += noJmh)

lazy val jatatuiDeps = Seq(
  "com.olvind.jatatui" % "jatatui-core" % jatatuiVersion,
  "com.olvind.jatatui" % "jatatui-crossterm" % jatatuiVersion,
  "com.olvind.jatatui" % "jatatui-widgets" % jatatuiVersion
)

lazy val jatatuiCounter = project
  .in(file("impl/jatatui-plain/counter"))
  .enablePlugins(JavaAppPackaging)
  .settings(task("jatatui-plain/counter", "Counter"), javaOnly)
  .settings(libraryDependencies ++= jatatuiDeps)

lazy val jatatuiDashboard = project
  .in(file("impl/jatatui-plain/dashboard"))
  .enablePlugins(JavaAppPackaging)
  .settings(task("jatatui-plain/dashboard", "Dashboard"), javaOnly)
  .settings(libraryDependencies ++= jatatuiDeps)

lazy val layoutzCounter = project
  .in(file("impl/layoutz/counter"))
  .enablePlugins(JavaAppPackaging)
  .settings(task("layoutz/counter", "Counter"))
  .settings(
    libraryDependencies += "xyz.matthieucourt" %% "layoutz" % layoutzVersion
  )

lazy val layoutzDashboard = project
  .in(file("impl/layoutz/dashboard"))
  .enablePlugins(JavaAppPackaging)
  .settings(task("layoutz/dashboard", "Dashboard"))
  .settings(
    libraryDependencies += "xyz.matthieucourt" %% "layoutz" % layoutzVersion
  )

lazy val tambouiDeps = Seq(
  "dev.tamboui" % "tamboui-tui" % tambouiVersion,
  "dev.tamboui" % "tamboui-widgets" % tambouiVersion,
  "dev.tamboui" % "tamboui-panama-backend" % tambouiVersion
)

lazy val tambouiCounter = project
  .in(file("impl/tamboui/counter"))
  .enablePlugins(JavaAppPackaging)
  .settings(task("tamboui/counter", "Counter"), javaOnly)
  .settings(libraryDependencies ++= tambouiDeps)

lazy val tambouiDashboard = project
  .in(file("impl/tamboui/dashboard"))
  .enablePlugins(JavaAppPackaging)
  .settings(task("tamboui/dashboard", "Dashboard"), javaOnly)
  .settings(libraryDependencies ++= tambouiDeps)

// google-java-format for the Java implementations: `sbt javafmt` formats them
// in place, `sbt javafmtCheck` fails when one is not formatted.
// claude 2026-10-20: created: the formatter of the Java sources (L5)
lazy val javafmt = project
  .in(file("project/javafmt"))
  .settings(
    javaOnly,
    libraryDependencies += "com.google.googlejavaformat" % "google-java-format" % gjfVersion,
    fork := true,
    run / javaOptions ++= Seq(
      "api",
      "code",
      "file",
      "parser",
      "tree",
      "util"
    ).map(p =>
      s"--add-exports=jdk.compiler/com.sun.tools.javac.$p=ALL-UNNAMED"
    ),
    run / baseDirectory := (ThisBuild / baseDirectory).value
  )

val javaSources =
  "impl/jatatui-plain/counter/Counter.java impl/jatatui-plain/dashboard/Dashboard.java " +
    "impl/tamboui/counter/Counter.java impl/tamboui/dashboard/Dashboard.java"

addCommandAlias(
  "javafmt",
  s"javafmt/runMain com.google.googlejavaformat.java.Main --replace $javaSources"
)
addCommandAlias(
  "javafmtCheck",
  s"javafmt/runMain com.google.googlejavaformat.java.Main --dry-run --set-exit-if-changed $javaSources"
)

lazy val root = project
  .in(file("."))
  .aggregate(
    tui4sCounter,
    tui4sDashboard,
    jatatuiCounter,
    jatatuiDashboard,
    layoutzCounter,
    layoutzDashboard,
    tambouiCounter,
    tambouiDashboard
  )
  .settings(publish / skip := true)
