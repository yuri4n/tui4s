// sbt-ci-release brings sbt-dynver and sbt-pgp.
// sbt-version-policy brings sbt-mima-plugin.
// sbt-tpolecat sets the scalac options (build.sbt, "Compiler options").
addSbtPlugin("com.github.sbt"     % "sbt-ci-release"           % "1.12.1")
addSbtPlugin("ch.epfl.scala"      % "sbt-version-policy"       % "3.3.0")
addSbtPlugin("org.scalameta"      % "sbt-scalafmt"             % "2.6.2")
addSbtPlugin("ch.epfl.scala"      % "sbt-scalafix"             % "0.14.9")
// claude 2026-10-12: created (D-8)
addSbtPlugin("org.typelevel"      % "sbt-tpolecat"             % "0.5.7")
addSbtPlugin("com.github.sbt"     % "sbt-native-packager"      % "1.13.0")
// The cross-built modules (tui4s-use, tui4s-widgets) build for the JVM and
// for Scala.js (L2a, D6). kyo-ui_sjs1_3 1.0.0-RC7 is built with Scala.js
// 1.22.0; sbt-scalajs-crossproject 1.4.0 depends on that version.
// claude 2026-10-19: created (L2a)
addSbtPlugin("org.scala-js"       % "sbt-scalajs"              % "1.22.0")
addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.4.0")
