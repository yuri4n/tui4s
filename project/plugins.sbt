// sbt-ci-release brings sbt-dynver and sbt-pgp.
// sbt-version-policy brings sbt-mima-plugin.
// sbt-tpolecat sets the scalac options (build.sbt, "Compiler options").
addSbtPlugin("com.github.sbt" % "sbt-ci-release"      % "1.12.1")
addSbtPlugin("ch.epfl.scala"  % "sbt-version-policy"  % "3.3.0")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"        % "2.6.2")
addSbtPlugin("ch.epfl.scala"  % "sbt-scalafix"        % "0.14.9")
// claude 2026-10-12: created (D-8)
addSbtPlugin("org.typelevel"  % "sbt-tpolecat"        % "0.5.7")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.13.0")
