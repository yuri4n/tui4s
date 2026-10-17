scalaVersion := "3.9.0"
name := "pini4s-scoped-spike"

Compile / unmanagedSourceDirectories += baseDirectory.value / "../../../vendor/pini4s/src/main/scala"
Test / unmanagedSourceDirectories += baseDirectory.value / "../../../vendor/pini4s/src/test/scala"

libraryDependencies ++= Seq(
  "io.getkyo" %% "kyo-core" % "1.0.0-RC7",
  "io.getkyo" %% "kyo-test-runner" % "1.0.0-RC7" % Test,
)

scalacOptions ++= Seq(
  "-deprecation", "-feature", "-unchecked", "-Werror",
  "-Wvalue-discard", "-Wnonunit-statement", "-Wunused:all",
  "-Yexplicit-nulls",
  "-Wconf:msg=@nowarn annotation does not suppress any warnings:s",
)

Test / testFrameworks += new TestFramework("kyo.test.runner.SbtFramework")
Test / fork := true
Test / javaOptions ++= Seq("-Xmx1g", "-Xss16m", "--sun-misc-unsafe-memory-access=allow")
