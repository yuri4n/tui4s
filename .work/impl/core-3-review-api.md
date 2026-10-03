# core-3 review: Public API, DX and tests

Reviewer lens: public API, DX, tests. Branch feat/core-3-terminal vs feat/core-2-engine
(worktree .work/worktrees/core, head f5d7cb0). Read-only review; no build was run.
Status: done (see the end of the file).

## api-1 (major) Tui.run / TuiApp / TuiError.NotATerminal docs promise a failure that does not happen

- Files: modules/core/src/main/scala/tui4s/Tui.scala:15-20 and 31-33,
  modules/core/src/main/scala/tui4s/TuiApp.scala:37-42, modules/core/src/main/scala/tui4s/Options.scala:65-67.
- The public Scaladoc says `Tui.run` "Fails with TuiError.NotATerminal when stdin or stdout is not a
  terminal", the object doc says the JVM needs "a terminal on stdin and stdout", and NotATerminal is
  documented as "Standard input or output is not a terminal". Nothing in TerminalIO checks stdin or
  stdout. NotATerminal comes only from `enableRawMode` failing, and crossterm 0.29 (the version inside the
  jatatui 0.31 native library; `strings libnative-arm64-darwin-crossterm.dylib` shows crossterm-0.29.0 and
  "/dev/tty") opens /dev/tty when stdin is not a TTY. The implementer knows this (NativeTerminalTest
  doc, examples/README.md checklist "stdin from a pipe and no terminal").
- Failure scenario: in a normal terminal session, `myapp < /dev/null`, `echo q | myapp`, or an agent
  that runs `sbt "examples/runMain examples.counter.CounterApp" < /dev/null` to "check NotATerminal"
  as the doc suggests: raw mode is entered on /dev/tty, the alternate screen opens, the piped `q` is
  ignored, and the process waits for keys from the keyboard (the agent hangs, the user's terminal is in
  raw mode). `myapp > out.txt`: raw mode on /dev/tty, every frame is written into out.txt, the user sees
  a frozen prompt. In neither case is NotATerminal raised.
- Fix: correct the docs to the real contract: "Fails with NotATerminal when the process has no
  controlling terminal (crossterm uses /dev/tty when stdin is not a terminal). Frames go to stdout; do
  not redirect it." Do not add an isatty(stdout) check: under `sbt run` with
  `outputStrategy := Some(StdoutOutput)` the forked JVM's stdout is a pipe that sbt copies to the
  terminal, so such a check would break the documented way to run the examples.

## api-2 (minor) The run's Kyo log ignores the caller's log level; logFile gets no info/debug lines by default

- Files: modules/core/src/main/scala/tui4s/internal/TuiLog.scala:80-81,
  modules/core/src/main/scala/tui4s/Options.scala:30-33.
- `TuiLog.let` binds `TuiLog(sink, Log.live.level)`: the level of Kyo's process-wide console logger
  (`-Dkyo.Log.defaultLevel`, default warn), not the level of the logger that is active where `Tui.run`
  is called. The public `TuiOptions.logFile` doc says the file gets "the Kyo log of the run" and does not
  say that info and debug lines are dropped.
- Failure scenario: `TuiOptions(logFile = Some(Path.of("app.log")))` and `Log.info("loaded 3 items")` in a
  task: app.log stays empty of it (only warn/error lines). A Kyo user who wraps the run,
  `Log.withConsoleLogger("app", Log.Level.debug)(Tui.run(app))`, also gets no debug/info line in the
  file: TuiLog.let replaces that logger with one at warn.
- Fix: take the level of the active logger, `Log.use(current => Log.let(Log(TuiLog(sink, current.level)))(v))`
  (same default: Log.live is warn), and say in the logFile Scaladoc which levels reach the file and how to
  lower it (`-Dkyo.Log.defaultLevel=debug` for a TuiApp).

## api-3 (minor) NativeAccessDenied tells the user to add a flag for failures the flag cannot fix

- File: modules/core/src/main/scala/tui4s/internal/Crossterm.scala:83-94 (loadError).
- `UnsatisfiedLinkError` (and `NoClassDefFoundError`) map to `NativeAccessDenied`, whose message is
  "start the JVM with --enable-native-access=ALL-UNNAMED". In jatatui's NativeLoader an
  UnsatisfiedLinkError means: no library for this platform on the class path, the extraction to
  java.io.tmpdir failed, or `System.load` of the extracted file failed. Denied native access on JDK 25
  is an `IllegalCallerException`, not an UnsatisfiedLinkError. A NoClassDefFoundError is only the second
  try after any first failure, so a platform-detection RuntimeException gives TerminalIO on the first
  run and NativeAccessDenied on the second.
- Failure scenario: an app in a container with /tmp mounted noexec (or on Alpine/musl, or Windows on ARM),
  started with `--enable-native-access=ALL-UNNAMED` as the README says: TuiApp prints "start the JVM with
  --enable-native-access=ALL-UNNAMED" (plus a cause line). The user or agent adds the flag that is already
  there and gets the same error.
- Fix: map only `IllegalCallerException` to NativeAccessDenied; map UnsatisfiedLinkError and
  NoClassDefFoundError to `TerminalIO` (its cause line shows the real reason). This also needs the SPEC 5.9
  step 1 text changed (it lists UnsatisfiedLinkError); note it as a deviation.

## api-4 (minor) Facade parity: the documented way to add an ecosystem can silently do nothing, and the suite cannot see it

- Files: project/FacadeParity.scala:14-18 and 30-32, examples/src/test/scala/parity/ParitySuite.scala:17-19.
- The Scaladoc tells the ZIO and Cats PRs to add `FacadeParity.ecosystems += "zio" -> "import tui4s.interop.zio.*"`.
  Written bare in build.sbt (as shown), that setting goes to the root project, not to `examples`. Put in a
  `.settings(...)` of examples before `FacadeParity.settings(...)`, it is reset by
  `ecosystems := Seq("kyo" -> ...)`. ParitySuite only asserts that "kyo" is in `Parity.apps`.
- Failure scenario: Z1 adds the line as documented; no `parity.onzio` file is generated, ParitySuite runs
  only the Kyo copy and is green, and the CI check "the Counter text compiles under the ZIO import" never
  ran.
- Fix: document `examples / FacadeParity.ecosystems += ...` (or `.settings(...)` after
  `FacadeParity.settings`), and make the suite assert the full expected set (generate an `expected` list
  from the projects that examples depends on, or have Z1/K1 change the assertion to
  `Set("kyo", "zio", ...)`).

## api-5 (minor) SurfaceCheck's documented reuse path puts the core test stub `zio.ZIO` ahead of the real ZIO

- Files: modules/core/src/test/scala/tui4s/SurfaceCheck.scala:16-17,
  modules/core/src/test/scala/zio/ZIO.scala:1-6.
- SurfaceCheck says the facades reuse it with `core % "test->test"`. Core's test classes include the
  stand-in `package zio; final class ZIO[-R, +E, +A]` (for GuardTest). sbt puts internal dependency
  classes before external jars on the test class path.
- Failure scenario: Z1 adds `dependsOn(core % "compile->compile;test->test")` to run the no-Kyo check on
  `tui4s.interop.zio`. The zio facade test sources then see the stub `zio.ZIO` (a class with no
  companion and no methods) before zio_3's `ZIO`: `ZIO.succeed`, `ZIO.scoped`, `.flatMap` do not compile,
  and at run time the stub class would shadow the real one.
- Fix: move SurfaceCheck out of the core test tree into a source directory that the facade tests add
  (for example `Test / unmanagedSourceDirectories += (core / baseDirectory).value / "src/surface/scala"`
  plus a tasty-query test dependency), or keep the zio stub in a source set that only core uses; and
  change the Scaladoc.

## api-6 (minor) The Kyo-log integration test does not check "after the restore"

- File: modules/core/src/test/scala/tui4s/TerminalIOTest.scala:282-308.
- The leaf is named "Kyo log lines at warn or above are printed after the restore", but it only checks
  that a line with "careful now" reaches stderr at some time (with `eventually`) and that "detail" does
  not. The sibling leaves for captured output and for the trace warning check the index against "flush";
  this one does not.
- Failure scenario: a regression that makes LogSink print important lines at once (for example `finished`
  set before the run, or TuiLog writing warn lines straight to the real stderr) draws "careful now" over
  the UI; this leaf stays green.
- Fix: `assert(calls.indexOf("flush") >= 0 && calls.indexWhere(l => l.startsWith("err: ") && l.contains("careful now")) > calls.indexOf("flush"))`, and drop the `eventually` (the line is printed before `TuiRun.run` returns).

## api-7 (minor) SchedulerDefaults publishes members the SPEC does not have

- File: modules/core/src/main/scala/tui4s/spi/SchedulerDefaults.scala:18 and 30-33.
- SPEC 2.11 / 5.14 define `defaults` and `applyIfUnset()`. The PR adds public `val OptOut` and
  `def envName(key)`; `envName` is used only by SchedulerDefaultsTest. Every public member of a published
  module is a binary-compatibility promise once tui4s-core is released.
- Failure scenario: a later change of the env-name rule (Kyo changes its flag lookup) or a rename of the
  opt-out constant is a binary break of tui4s-core that forces `Compatibility.None` and a minor bump, for
  members no user needs.
- Fix: make `envName` `private[tui4s]` (the test is in package tui4s), and either make `OptOut`
  `private[tui4s]` or add both to the SPEC as API.

## api-8 (minor) Two real-TTY checklist steps cannot be done with the commands the README gives

- File: examples/README.md:70-74 (with build.sbt:74-77).
- The steps "Start an example with `--illegal-native-access=deny` in place of
  `--enable-native-access=ALL-UNNAMED`" and "Start an example without `--enable-native-access`" give no
  command. The only documented command is `sbt "examples/runMain ..."`, and the examples build always
  adds `--enable-native-access=ALL-UNNAMED` to the forked JVM (`run / javaOptions ++= ...`). With both
  flags, the enable flag wins for the unnamed module.
- Failure scenario: the person (or agent) who does the C6 checklist runs
  `sbt -J--illegal-native-access=deny "examples/runMain examples.counter.CounterApp"`: the flag goes to
  sbt's own JVM, the fork still gets `--enable-native-access=ALL-UNNAMED`, the app starts normally, and
  the step is recorded as failed (or as passed for the wrong reason). The JEP 472 warning step has the
  same problem.
- Fix: give the exact commands, for example
  `sbt 'set examples / run / javaOptions := Seq("--illegal-native-access=deny", "--sun-misc-unsafe-memory-access=allow")' "examples/runMain examples.counter.CounterApp"`
  and the same with only `--sun-misc-unsafe-memory-access=allow` for the warning step.

## Checked and found fine (no finding)

- Public surface of C5 matches SPEC 2.9 / 2.11: `Tui.run`, `Tui.start`, `TuiApp` (`main`, `args`,
  `source`, `onExit`), `TuiHandle`, `TuiOptions(captureOutput, logFile, trace)`, `spi.SchedulerDefaults`.
  New internals (`Crossterm`, `NativeCrossterm`, `CrosstermInput`, `EventMapping`, `TerminalIO`, `Hooks`,
  `LogSink`, `TuiLog`, `OutputCapture`, `TuiRun`, the `TuiApp` companion) are `private[tui4s]`. No jatatui
  type in a new public signature. Every new public member has Scaladoc.
- The TuiApp constructor runs `SchedulerDefaults.applyIfUnset()` before the subclass fields; Kyo
  scheduler and Log threads are daemon threads, so `main` returning on success ends the JVM.
- Examples are SPEC 3.1-3.3 text; munit suites cover SPEC 6.3 (plus tabs, failed load, live pause).
- KyoFreeSurfaceTest: the exclusion list is exactly SPEC 2.2; the second leaf proves the probe is not
  empty and that every Kyo-typed member is in the list.
- EventMappingTest covers every KeyCode case, modifiers, kinds, mouse kinds and buttons.

Status: done. 8 findings (1 major, 7 minor).
