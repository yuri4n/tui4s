# core-3 fix log (feat/core-3-terminal)

Started 2026-10-01. Findings: spec-1..7, api-1,2,4,5,8, runtime-1..6.
Status per finding is added below as each one is verified and fixed.

## spec-2 (EventMapping exhaustivity is an error) - FIXED
- modules/core/build.sbt: `-Wconf:msg=match may not be exhaustive&src=.*/EventMapping\.scala:e`.
- Verified: with `KeyCode.Media` and `MouseEventKind.ScrollRight` removed from the matches, `core/compile`
  fails with two E029 "Pattern Match Exhaustivity Error"s (log .work/logs/core-3-fix-wconf.log). No unit
  test is possible for a compiler flag; the check is the build itself.

## spec-1 + api-1 + runtime-2 (NotATerminal contract) - FIXED (stdin checked, stdout documented)
- Verified the gap in a pseudo-terminal (`script -q /dev/null ...` works in this sandbox): before the fix,
  `CounterApp < /dev/null` entered the alternate screen and then failed with "terminal I/O failed / Failed
  to initialize input reader" (macOS kqueue cannot poll /dev/tty; Linux would run on /dev/tty);
  `CounterApp > file` ran with exit 0 and wrote the escape sequences into the file.
- sbt 1.13.0 Fork (javap): connectInput + StdoutOutput -> stdin Redirect.INHERIT, stdout/stderr piped and
  pumped. So a stdout TTY check would break `sbt examples/runMain`; a stdin check does not.
- Fix: Crossterm.stdinIsTerminal (NativeCrossterm: isatty(0) through FFM, internal.Tty; unknown/Windows ->
  true). TerminalIO.enter throws NotATerminal(IOException("stdin is not a terminal")) before raw mode.
  Message string unchanged (SPEC 2.9). Docs of Tui, Tui.run, TuiApp.main, TuiError.NotATerminal: stdin is
  checked; stdout is not (it can be a pipe to the terminal); do not redirect it to a file.
- Tests: TerminalIOTest "stdin is not a terminal: NotATerminal before raw mode" (FakeCrossterm(stdinTty =
  false); calls == Seq("load")). NativeTerminalTest: isatty true on a posix_openpt master, false on -1; the
  real Tui.run leaf now also runs when /dev/tty exists but stdin is not a terminal (the forked test JVM).
- Real pty checks after the fix: normal run exit 0; stdout piped (`| cat`, like sbt) exit 0; `< /dev/null`
  and `echo x |` -> "stdin/stdout is not a terminal / cause: java.io.IOException: stdin is not a terminal",
  exit 1, no escape sequence written. One JEP 472 warning only (the FFM call adds none).
- SPEC 5.9 step 3 and 8.2 already say "stdin is not a TTY -> NotATerminal"; no SPEC edit needed.

## api-8 (README checklist commands) - FIXED
- examples/README.md gives the exact `sbt 'set examples / run / javaOptions := ...' "examples/runMain ..."`
  commands for the deny and the no-flag steps, and `sbt "examples/runMain ..." < /dev/null` for stdin.
  Verified here: deny -> "start the JVM with --enable-native-access=ALL-UNNAMED", exit 1; no flag -> the
  JEP 472 warning, then (no TTY here) NotATerminal. Logs .work/logs/core-3-fix-readme-{deny,warn}.log.

## spec-3 + runtime-3 (stop hook waits 2 s when user code calls System.exit) - FIXED
- TerminalIO gets `restored: () => Unit`, called at the end of leave; TuiRun.run passes it. TuiApp.runMain
  makes a `restored` latch; TuiApp.block counts it down also when the fiber ends; the tui4s-stop hook
  interrupts and waits <= 2 s on that latch (SPEC 5.9 TuiApp step 3: "the restore latch").
- Test TuiAppTest "System.exit in update: the stop hook waits for the restore, not 2 s": a key blocks its
  worker like System.exit (ignores interrupts); both hooks run at once. Without the fix: the stop hook took
  2006 ms (FAIL); with it: < 100 ms (log .work/logs/core-3-fix-hooks{-nofix}.log).

## spec-4 (a Closed inbox under the loop ends as "terminal I/O failed") - FIXED
- RenderLoop.closedIsBug: when the backend is already sealed (CountingBackend.isClosed: the release ran
  from outside), the run ends with Abort.panic(Interrupted(frame, "the release of the terminal")); other
  Closed stays TerminalIO("inbox closed").
- Tests: TerminalIOTest "the shutdown hook restores the terminal while the app runs" now asserts
  Panic(Interrupted) (without the fix: Failure(TerminalIO)); TuiAppTest "the restore hook first, then the
  stop hook" asserts err == Seq("interrupted") (without the fix: "terminal I/O failed / inbox closed").
  The SIGTERM leaf now starts both hooks at once, as the JVM does.

## spec-5 + runtime-1 (Kyo Log is asynchronous: late lines miss the log file) - FIXED
- LogSink: after finish, a line still goes to the log file (append, open/close per late line); without a
  file, as before (important -> real stderr, others dropped).
- TuiRun.run: after Runner.run (success or failure; not on interruption: a Sync.ensure cannot wait),
  TuiLog.flush(2.seconds) = Abort.run[Timeout](Async.timeout(2 s)(Log.flush)), then the result.
- Not done: TuiLog stamping event.timestamp. `Log.Event` is private[kyo] in RC7, so TuiLog cannot override
  `Log.Unsafe.emit(event)`; the stamp stays the write time (cosmetic, ms late at most after the flush).
- Not done (aside of runtime-1): a late important line without a log file goes to the real stderr, also
  while a later run's UI is up. Needs two runs in one JVM plus a late warn line; left as is.
- Tests: OutputCaptureTest "with a file, a line after finish is still appended to the file"; TerminalIOTest
  "every Kyo log line of the run is in the log file when the run returns" (3000 warn lines; without the fix
  only 379 of 3000 reached the file).

## api-2 (the run's log ignores the caller's level) - FIXED
- TuiLog.let: `Log.use(current => Log.let(Log(TuiLog(sink, current.level)))(v))`. Default unchanged (Log.live
  is warn). TuiOptions.logFile Scaladoc says which levels reach the file and how to lower the level.
- Test: TerminalIOTest "the Kyo log of the run has the level of the caller's logger"
  (Log.withConsoleLogger("app", info) around TuiRun.run; fails without the fix).
  Logs .work/logs/core-3-fix-log{-nofix}.log.

## spec-6 (OutputCapture restores the streams of the first run) - FIXED
- installStreams makes the forwarders once; at a later start, when System.out/err is not the forwarder (the
  program set its own stream), the forwarder writes to that stream from now on (`Forward.original` is a
  var) and originalOut/Err become it, so a stop restores the streams of its own start. scala.Console stays
  bound to the same forwarder. New `OutputCapture.realOut` (tests).
- Test: OutputCaptureTest "a capture restores the streams of its own start" (fails without the fix).

## runtime-4 (unbounded partial line with '\r' progress output) - FIXED
- Forward: a '\r' not followed by '\n' starts the line again; a line is cut at MaxLine = 8192 bytes, only
  before the first byte of a character.
- Tests: "a progress line ('\r') keeps only its last update", "a line is cut at MaxLine bytes, never inside
  a character" (both fail without the fix: one 30000-byte line; "progress 1\rprogress 2..." kept whole).
  Log .work/logs/core-3-fix-capture{-nofix,-2}.log. (An own slip: a `git checkout` of the test file during
  the no-fix run dropped the new tests; re-added and re-run green before the commit.)

## api-4 (facade parity: the documented `+=` can do nothing; the suite cannot tell) - FIXED
- project/FacadeParity.scala doc: add the entry to `examples` in a `.settings(...)` AFTER
  `FacadeParity.settings(...)` (it sets the list to Kyo only), and add the name to ParitySuite.
- ParitySuite asserts the exact set of generated apps (`Set("kyo")` now; Z1/K1 add "zio"/"cats"), so a
  name in only one place fails. KyoFree/Parity tests green (log .work/logs/core-3-fix-surface.log).

## api-5 (SurfaceCheck reuse via test->test brings the core zio.ZIO stub) - FIXED
- SurfaceCheck moved to modules/core/src/surface/scala (core: `Test / unmanagedSourceDirectories +=
  baseDirectory.value / "src" / "surface" / "scala"`). Its Scaladoc tells the facades to add that directory
  plus tasty-query (Test), not `core % "test->test"`. No facade test exists yet to prove it (Z1/K1 do).

- spec-6 follow-up (own review): retargeting the installed forwarder could loop when the program's new
  stream wraps the forwarder. Now a forwarder never changes its stream: a new forwarder is made only when
  System.out/err is neither the current forwarder nor its stream; all forwarders are drained at a stop.
  Test "a stream that wraps the installed forwarder does not make a loop" (pinned, as TuiApp). Commit
  after 3d1812a.

## spec-7 + runtime-5 (BurstProbe counts the quit tail) - FIXED
- BurstProbe wraps the Counter: `update` stamps the time of `q`; B1 = (fold of q - start) / keys. The
  release is printed apart (releaseMsMedian). Re-measured (load 4.7-6.1, other work on the machine):
  7.5 us/event median, best 6.8, release 28.7 ms (= 2.9 us/event). BASELINE B1: ok. Log core-3-fix-burst.log.

## runtime-6 (SoakProbe blocks a Kyo worker before counting threads) - FIXED
- Probes.offWorker runs the GC + thread count on one daemon platform thread; the fiber waits on a promise.
  Budget: threads.max - threads.min <= 1 (was 4). 180 s soak (load 2.7-5.5): heap +0.14%, 18 threads in 17
  samples, 17 in one (a thread ended, not added). BASELINE B8 notes updated; the full 600 s soak should be
  re-run on an idle machine before the release. Log core-3-fix-soak.log.

## Final
- `sbt -batch fmt` then `sbt -batch check` GREEN (core 249 kyo-test leaves, examples 15 munit tests,
  versionPolicyCheck ok; log .work/logs/core-3-fix-check-1.log). The fmt changes are commit 64816cf.
- Real pty re-check on the final build: normal run exit 0; stdout piped exit 0; `kill -TERM` -> the JVM
  ends in ~200 ms, alternate screen left, cursor shown, tty back in icanon, exit 143.
- Commits on feat/core-3-terminal: c93862e, 39d6b4b, 519a5a3, 322ad52, 3d1812a, fb94953, 632ec71, cca9f34,
  64816cf. Not pushed.
- Partly done (by design or not possible): stdout is not checked (sbt pipes it; docs say so); TuiLog does
  not stamp event.timestamp (Log.Event is private[kyo]); a late warn line without a log file still goes
  to the real stderr even while a later run's UI is up; the full 600 s soak and the other budgets should
  be measured again on an idle machine (load was 3-6 here).
