import deps.*

lazy val root = project
  .in(file("."))
  .settings(
    inThisBuild(
      Seq(
        semanticdbEnabled := true,
        scalacOptions ++= Seq(
          "-Wunused:all",
          "-Wvalue-discard",
          "-Yexplicit-nulls",
        ),
      ),
    ),
    name                 := "Tui4s",
    version              := "0.1.0-SNAPSHOT",
    scalaVersion         := scala3Version,
    Compile / run / fork := true,
    testFrameworks += new TestFramework("weaver.framework.CatsEffect"),
    libraryDependencies ++= fs2,
    libraryDependencies ++= tuiScala,
    libraryDependencies ++= Seq(
      weaver % Test,
    ),
  )
// .aggregate(tui)

// lazy val tui = project.in(file("tui"))
//   .settings(
//     name         := "Tui4s-Tui",
//     version      := "0.1.0-SNAPSHOT",
//     scalaVersion := "2.13.14",
//     libraryDependencies ++= tuiScala,
//   )
