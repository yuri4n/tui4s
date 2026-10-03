# core-3 verify (lens api)

Skeptic pass on the api review findings. Worktree: .work/worktrees/core.

Head f5d7cb0. Read-only; no build was run.

## api-1: confirmed (doc defect; "major" is high, minor is fair)

- TerminalIO.enter (TerminalIO.scala:69-81) has no isatty check. NotATerminal comes only from
  `crossterm.enableRawMode()` failing (line 75).
- The jatatui 0.31 jar bundles crossterm 0.29.0 (`strings libnative-arm64-darwin-crossterm.dylib`:
  `crossterm-0.29.0/src/...`, `event/source/unix/mio.rs`, `/dev/tty`). crossterm's unix `tty_fd()` uses
  stdin when it is a TTY, else opens `/dev/tty`; both `enable_raw_mode` and the mio event source use it.
  So `app < /dev/null` in a terminal session enters raw mode on /dev/tty and reads keys from it, and
  `app > out.txt` writes the frames into the file. No NotATerminal in either case.
- Wrong docs: Tui.scala:31-33 ("when stdin or stdout is not a terminal"), Options.scala:65 ("Standard
  input or output is not a terminal"). The object doc (Tui.scala:18-19) and TuiApp.main ("without a
  terminal") are close to right. NativeTerminalTest.scala:9-16 and examples/README.md:75 ("and no
  terminal") show the real rule.
- No SPEC conflict: the fix changes only Scaladoc. The SPEC message string "stdin/stdout is not a
  terminal" (2.9) and the mapping in 5.9 step 3 stay the same.

## api-2: confirmed (minor)

- TuiLog.scala:81 binds `TuiLog(sink, Log.live.level)`. In kyo-core RC7 Log.scala:121-123,
  `live = Log(AsyncUnsafe(ConsoleLogger("kyo.logs", defaultLevel())))` and defaultLevel is warn.
- Part 1 (Log.info is not written to logFile by default) works like plain Kyo, where Log.live also drops
  info. It is also a declared deviation (core-3-impl.md: "TuiLog level = Kyo's process level"). The
  defect here is the logFile Scaladoc (Options.scala:30-33), which does not say which levels reach the file.
- Part 2 is a real behavior gap: `Log.withConsoleLogger("app", Log.Level.debug)(Tui.run(app))` (the
  method exists, Log.scala:214) is replaced by a warn-level TuiLog, so the caller's debug level is lost.
  `Log.use(current => Log.let(Log(TuiLog(sink, current.level)))(v))` keeps the default (warn), and
  SPEC 5.10 gives no level.

## api-3: refuted (the code follows SPEC 5.9 step 1; propose a SPEC change)

- The technical point is right. NativeLoader.java:12-36 throws UnsatisfiedLinkError when no library is
  on the class path, when the extraction fails, or when System.load fails (noexec tmpdir, musl). It
  propagates out of `<clinit>` as itself, not wrapped (JLS 12.4.2: an Error is not wrapped). With
  `--illegal-native-access=deny`, JDK 25 throws IllegalCallerException instead, which arrives inside an
  ExceptionInInitializerError.
- But SPEC 5.9 step 1 says in so many words "`IllegalCallerException` or `UnsatisfiedLinkError` ->
  `TuiError.NativeAccessDenied`", and Crossterm.scala:89-91 does exactly that. The finding itself
  says that the SPEC must change. By the rules of this pass, that makes it a SPEC amendment and not a
  defect of this PR (same as core-2 api-7).
- The scenario is weaker than it says. TuiApp.describe (TuiApp.scala:123-126) prints
  "  cause: java.lang.UnsatisfiedLinkError: <real reason>", so the real reason shows. Windows on ARM is a
  RuntimeException from getPlatform: it is TerminalIO on the first run, not NativeAccessDenied.
  NoClassDefFoundError comes only from a second load in the same JVM: each Tui.run makes a new
  NativeCrossterm, and TuiApp runs once.
- Worth raising with the user as a SPEC 5.9 change: map only IllegalCallerException to
  NativeAccessDenied.

## api-4: confirmed (minor), with a correction

- A correction to the scenario: written bare in build.sbt "as the doc shows", the line goes to `root`.
  No scope (root, ThisBuild, Global) defines `ecosystems`, so sbt stops at load with "Reference to
  undefined setting". That is loud, not silent.
- The silent path is real. FacadeParity.settings (FacadeParity.scala:32) does
  `ecosystems := Seq("kyo" -> ...)`, so a `+=` that comes earlier in the examples settings is reset
  with no message. ParitySuite.scala:17-19 checks only `contains("kyo")`, and line 21 makes a test only
  for the generated entries. A missing zio or cats entry gives a green suite. Nothing else checks that
  parity/onzio exists.
- Z1 and K1 must add these entries (SPEC 8.3 and 8.4: "facade parity for Counter"). Fix: scope the doc
  snippet as `examples / FacadeParity.ecosystems += ...` (or say "after FacadeParity.settings"), and use
  an exact-set assertion when the facades land.

## api-5: confirmed (minor)

- The SurfaceCheck Scaladoc (lines 16-17) tells the facades to use `core % "test->test"`. Core's test
  tree has `package zio; final class ZIO[-R, +E, +A]` with no companion (src/test/scala/zio/ZIO.scala).
  GuardTest needs it.
- With test->test, `Test / internalDependencyClasspath` adds core's test-classes. sbt's
  dependencyClasspath puts internal entries before the external ones, so for the class name `zio.ZIO`
  the compiler takes the first entry: the stub's ZIO.class/ZIO.tasty. The facade tests then cannot
  compile `ZIO.succeed` etc. (no companion). At run time, the stub class comes first too.
- The failure is loud (a compile error), as the finding says. It is still a trap in the documented
  reuse path that Z1 and K1 will take (SPEC 8.3 and 8.4: the no-Kyo check for the facade packages).

## api-6: refuted (the gap is real, but no listed regression gets past the suite)

- The leaf (TerminalIOTest.scala:282-308) checks only that "careful now" is present and "detail" is
  absent. It does not check the order against "flush", as the finding says.
- Regression 1, "LogSink.finished set before the run": captured output goes through the same
  `LogSink.line(_, important = true)` path. The sibling leaf "output while the UI is up is printed after
  the restore" asserts `hello > flush`, so it fails. OutputCaptureTest also checks that the ring keeps
  lines until finish.
- Regression 2, "TuiLog writes important lines straight to stderr":
  - To System.err: the line is captured into the sink, so it still comes after the restore. Nothing
    breaks.
  - To OutputCapture.realErr only: FakeErr never sees it, `seen` is false, and this leaf fails.
  - To the sink and to the real stderr: the proposed index assertion cannot see that either.
- TuiLog cannot write early to the injected `err`, because it has no `err`. The change would be a
  cheap tidy-up (and would remove the `eventually`), but it does not fix a defect (same as core-2 api-9).

## api-7: refuted (additive; the failure is a future change that the guard reports)

- True: SPEC 2.11 lists only `defaults` and `applyIfUnset()`. SchedulerDefaults.scala:18 (`OptOut`)
  and :33 (`envName`) are public, and the declared deviations do not list them. Only
  SchedulerDefaultsTest uses envName.
- But extra members are additive. Earlier passes did not count such additions as SPEC contradictions
  (core-1 api-7). The scenario is a future rename or change, which versionPolicyCheck reports on the PR
  and which costs only an early-semver minor bump. That is the guard working (same reasoning as core-1
  api-8).
- Free tidy-up before the first core-v tag (there are no core-v* tags yet): make envName and OptOut
  `private[tui4s]`.

## api-8: confirmed (minor, doc)

- build.sbt:74-77 always adds `--enable-native-access=ALL-UNNAMED` to the forked run. The only
  documented commands are `sbt "examples/runMain ..."` (examples/README.md:15-19). So the steps at lines
  70-74 cannot be done with the commands given, and `sbt -J...` only changes sbt's own JVM.
- When both flags are present, ALL-UNNAMED has native access, so the deny step cannot show
  NativeAccessDenied. In that case the step fails where the tester can see it, which is not a false
  pass. The JEP 472 warning step can pass wrongly if the tester checks only that the app starts.
- Fix: give the `set examples / run / javaOptions := ...` commands, as the finding suggests.

## Summary

Status: done. Confirmed: api-1, api-2, api-4, api-5, api-8. Refuted: api-3 (SPEC 5.9 mandates the
mapping; raise as a SPEC change), api-6, api-7. Uncertain: none.
