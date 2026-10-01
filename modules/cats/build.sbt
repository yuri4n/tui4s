// tui4s-cats. Versions shared by modules are in project/Versions.scala.
description := "Cats Effect integration for tui4s."

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-effect"       % "3.7.1",
  "co.fs2"        %% "fs2-core"          % "3.14.0",
  "org.scalameta" %% "munit"             % Versions.munit % Test,
  "org.typelevel" %% "munit-cats-effect" % "2.2.1"        % Test,
)
