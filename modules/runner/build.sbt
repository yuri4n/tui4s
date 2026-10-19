// tui4s-runner. Versions shared by modules are in project/Versions.scala.
// claude 2026-10-12: changed the whole file: a runner for kyo-ui (RC-16)
// claude 2026-10-19: changed: the runner of the split of the core (D25); the
// test settings are `runnerTestSettings` in the root build.sbt
description := "The terminal runner for kyo-ui, on Kyo and jatatui."

// kyo-kernel's POM has JMH (and through it commons-math3, asm and
// jopt-simple) in compile scope: 3.0 MB that no run loads, so it is excluded.
// claude 2026-10-19: created (D): the JMH exclusion
val noJmh = ExclusionRule("org.openjdk.jmh")

libraryDependencies ++= Seq(
  ("io.getkyo"        %% "kyo-core"          % Versions.kyo).excludeAll(noJmh),
  // kyo-ui and kyo-core must have the same version. The transitive jars of
  // kyo-ui (kyo-http, kyo-net, kyo-ffi, ...) stay: `UI.Href.Absolute` needs
  // kyo-http (D-18, spike S4).
  ("io.getkyo"        %% "kyo-ui"            % Versions.kyo).excludeAll(noJmh),
  "com.olvind.jatatui" % "jatatui-core"      % Versions.jatatui,
  "com.olvind.jatatui" % "jatatui-crossterm" % Versions.jatatui,
)

// The matches in internal/EventMapping.scala list every case of crossterm's
// sealed Java types. A case that a jatatui upgrade adds must stop the build
// also on a developer machine (DevMode): as a warning it would only show at
// run time, as a MatchError on the input thread.
// claude 2026-10-13: created (T1, RC-22: the rule of PR 5)
Compile / scalacOptions +=
  "-Wconf:msg=match may not be exhaustive&src=.*/EventMapping\\.scala:e"
