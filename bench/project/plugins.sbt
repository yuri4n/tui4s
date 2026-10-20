// The plugins of the bench build (bench/build.sbt). This build is not the
// root build: it reads the tui4s artifacts from the local Ivy repository.
// claude 2026-10-20: created (L5)
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.13.0")
