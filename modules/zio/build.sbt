// tui4s-zio. Versions shared by modules are in project/Versions.scala.
description := "ZIO integration for tui4s."

libraryDependencies ++= Seq(
  "io.getkyo"     %% "kyo-zio"     % Versions.kyo,
  "dev.zio"       %% "zio"         % "2.1.26",
  "dev.zio"       %% "zio-streams" % "2.1.26",
  "org.scalameta" %% "munit"       % Versions.munit % Test,
)
