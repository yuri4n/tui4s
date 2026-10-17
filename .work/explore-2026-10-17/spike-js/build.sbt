// SPIKE: the kyo-ui view of the tui4s spike as a Scala.js single-page app.
ThisBuild / scalaVersion := "3.9.0"

// Size variants: SPIKE_SLIM=1 swaps the full CLDR locale database (a transitive
// dependency of kyo-ui) for the minimal en_US one; SPIKE_GCC=1 runs the Closure
// Compiler in fullLinkJS (off by default since Scala.js 1.16; minify is on).
val slim = sys.env.get("SPIKE_SLIM").contains("1")
val gcc  = sys.env.get("SPIKE_GCC").contains("1")
// SPIKE_NOTZ=1 also drops the time-zone database (scala-java-time-tzdb).
val notz = sys.env.get("SPIKE_NOTZ").contains("1")

lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "spike-js",
    // The shared, platform-free view (also compiled by the JVM tui4s build).
    Compile / unmanagedSourceDirectories += baseDirectory.value / "shared",
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies += "io.getkyo" %%% "kyo-ui" % "1.0.0-RC7",
    excludeDependencies ++= (if (slim)
                               Seq(ExclusionRule("io.github.cquiroz", "locales-full-currencies-db_sjs1_3"))
                             else Nil),
    excludeDependencies ++= (if (notz)
                               Seq(ExclusionRule("io.github.cquiroz", "scala-java-time-tzdb_sjs1_3"))
                             else Nil),
    libraryDependencies ++= (if (slim)
                               Seq("io.github.cquiroz" %%% "locales-minimal-en_us-db" % "1.5.4")
                             else Nil),
    Compile / fullLinkJS / scalaJSLinkerConfig ~= (c => if (gcc) c.withClosureCompiler(true) else c),
    // Close to the tui4s flags (sbt-tpolecat defaults + explicit nulls).
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wvalue-discard",
      "-Wnonunit-statement",
      "-Wunused:all",
      "-Yexplicit-nulls",
    ),
  )
