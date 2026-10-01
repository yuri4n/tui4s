// tui4s-core. Versions shared with other modules live in project/Versions.scala.
description := "Declarative terminal UIs for Scala 3 on Kyo, rendered with jatatui."

libraryDependencies ++= Seq(
  "io.getkyo"         %% "kyo-core"          % Versions.kyo,
  "com.olvind.jatatui" % "jatatui-core"      % Versions.jatatui,
  "com.olvind.jatatui" % "jatatui-widgets"   % Versions.jatatui,
  "com.olvind.jatatui" % "jatatui-crossterm" % Versions.jatatui,
  "org.scalameta"     %% "munit"             % Versions.munit % Test,
)
