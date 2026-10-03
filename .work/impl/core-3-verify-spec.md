# core-3 verify (lens: spec)

Skeptic pass over the spec-lens findings. Worktree: .work/worktrees/core.
Verdicts are added as each finding is checked.

## spec-1: confirmed (severity "major" is high; it is a SPEC gap plus a wrong doc)

- TerminalIO.enter (TerminalIO.scala:74-75) is the only TTY check: enableRawMode failure -> NotATerminal.
  That is what SPEC 5.9 step 3 says, so the mechanism follows the SPEC.
- crossterm's unix `tty_fd()` uses stdin when isatty(0), else opens /dev/tty; `window_size` opens
  /dev/tty. NativeTerminalTest.scala:13 itself says "crossterm opens /dev/tty when stdin is not a
  terminal". So `echo x | app` in a terminal enters raw mode on /dev/tty and runs, and `app > out.txt`
  enters raw mode on stdin and draws into out.txt (draw goes through Rust stdout, SPEC 5.10).
- Tui.scala:31-32 says "Fails with NotATerminal when stdin or stdout is not a terminal". The stdout half is
  never checked; the stdin half is only true when there is no controlling TTY. SPEC 8.2's checklist line
  ("with stdin piped (not a TTY) it stops with NotATerminal") was validated without any TTY, and
  examples/README.md:75 narrows it to "stdin from a pipe and no terminal", which is the true behavior.
- The scenario is real. The finding does not contradict the SPEC (an isatty check before raw mode fits
  5.12 "no TTY -> NotATerminal, before raw mode"), and its fallback (narrow the docs) is cheap.

## spec-2: confirmed

- SPEC 2.4: "the build turns that warning into an error for this file". build.sbt:23-33 has only
  `-Wconf:msg=@nowarn annotation ...:s`; modules/core/build.sbt has no scalacOptions; no -Werror or
  -Xfatal-warnings anywhere; `check` alias = fmt check, Test/compile, test, versionPolicyCheck.
- EventMapping.scala:16-17 itself says a new case is "a compile warning here".
- A non-exhaustive match throws MatchError (NonFatal) in CrosstermInput.read (Crossterm.scala:103) on the
  input thread; InputThread.run (Session.scala:240-243) turns it into Failed(TerminalIO(e)), and the run
  fails. The trigger needs a jatatui upgrade with a new case, which the SPEC rule exists to catch.

## spec-3: confirmed

- TuiApp.block (TuiApp.scala:86-107): the tui4s-stop hook interrupts and then waits on `done`, which only
  `fiber.unsafe.onComplete` counts down. SPEC 5.9 TuiApp.main step 3 says "wait up to 2 s for the restore
  latch"; there is no restore latch.
- Kyo RC7 IOTask (scheduler/IOTask.scala:28-31): an interrupted fiber's promise "stays pending until" its
  remainder is released. If the render fiber's worker is blocked inside System.exit (Shutdown.exit ->
  ApplicationShutdownHooks.runHooks joins every hook and ignores InterruptedException), the slice never
  ends, so `done` never counts down and tui4s-stop waits the full 2 s. tui4s-restore restores the screen
  at once, so the screen is restored and the process ends about 2 s later (the prompt comes back at exit,
  not before, so the finding's wording is a little off).
- Same 2 s delay when onExit calls sys.exit: the stop hook is still registered (removed only after
  fiber.get) and the fiber cannot complete. SPEC 5.13 lists System.exit in user code as supported.

## spec-4: confirmed, but much rarer than stated (cosmetic; low severity)

- RenderLoop.closedIsBug (RenderLoop.scala:115-127) turns Closed from inbox.take into
  TerminalIO(IllegalStateException("inbox closed")); no "release in progress" guard exists.
- But "close first" alone does not give that result. The fiber's own release (Runner.runOn:217-218)
  calls Session.close, which is `synchronized` (Session.scala:97) and blocks until the hook's close ends
  (input-thread join up to 2*pollMillis+50 ms, then leave). The JVM starts tui4s-stop together with
  tui4s-restore, so its interrupt normally lands before the fiber completes. Kyo RC7 then settles the
  interrupt: Idle path -> release() -> abandon -> Interrupted; running slice -> Safepoint.stop, honored
  at the next poll ("a map interposes a poll", ArrowEffect.scala:670). Report prints "interrupted".
- The failure result needs the tui4s-stop thread to stall for the whole release (about 1-100 ms) before
  it calls interrupt. That is possible on a heavily loaded machine, not on the normal path. The exit code
  is 1 either way, and the terminal is restored either way; only the printed text differs.
- TuiAppTest.scala:150-178 does run only tui4s-stop (true), so the test does not cover the two-hook race.

## spec-5: confirmed (timing-dependent)

- Kyo RC7 Log.scala:31-40: Log.info/warn -> emitAt -> Log.emit(Event(..., unsafe = TuiLog, ...));
  LogPlatformSpecific.emit enqueues to the global daemon when `kyo.Log.asyncLogging` (default true,
  Log.scala:566); the drain calls LogShared.dispatch -> event.sink.emit -> TuiLog.write later.
- No Log.flush anywhere in the worktree. TerminalIO.leave finishes the sink (TerminalIO.scala:141) and
  TuiRun.run again in Sync.ensure. After finish, LogSink.line (LogSink.scala:51-54) prints important lines
  to err and drops the rest; the file writer was closed by finish. So a late info line is lost and a
  late warn line goes to stderr, not to the logFile.
- Note: a pure fold cannot call Log.info; the trigger is a source/task (or anything on a Kyo fiber) that
  logs just before the run ends. The daemon is usually fast, so the loss needs the drain to lag behind
  the release; the `eventually` in TerminalIOTest.scala:301 shows late delivery does happen.
- TuiLog.scala:35 stamps LocalTime.now() at write time; Kyo's own ConsoleLogger overrides emit to render
  event.timestamp (Log.scala:274-280). True, cosmetic.

## spec-6: confirmed (narrow trigger)

- OutputCapture.installStreams (OutputCapture.scala:45-59) creates the forwarders and records
  originalOut/originalErr only when `out == null`; Capture.stop (lines 96-100) sets System.out/err back to
  those first originals. realErr (line 32-35) is also the first stderr.
- Plain Tui.run twice with System.setOut(fileStream) between: run 2 replaces fileStream with the old
  forwarder (which forwards to the first stdout), and its stop restores the first stdout. The program's
  redirect is lost silently. Needs two runs in one JVM plus a redirect between them.

## spec-7: confirmed

- BurstProbe.scala:25-33: t0 before Headless.start, t1 after h.await (the run's end, release included).
- After the last batch is accepted, InputThread loops into port.poll(25); ScriptedInput.poll
  (ScriptedInput.scala:27-34) waits the full timeout on an empty queue; Session.close joins it
  (Session.scala:105). QuitProbe measures this tail at p50 34.4 ms (bench/results/quit.jsonl).
- BASELINE.md B1: 12.1 us/event = about 121 ms per round. A fixed tail of about 20-34 ms is 2.0-3.4
  us/event, larger than the 2.1 us/event margin of the miss. BASELINE.md says the time "includes the
  start, the first frame and the release", but still records the result as a B1 miss. SPEC 7.3 B1 is
  "runtime overhead per input event"; B7 budgets the quit tail on its own. So the miss is not shown.

## spec-8: refuted

- True that the leaf (TerminalIOTest.scala:282-308) does not compare positions with "flush".
- The stated regression does not pass the leaf: TuiLog has only the sink, and "the real stderr" in this
  test is not `err`. A TuiLog that wrote to OutputCapture.realErr (the JVM stderr) or to a stream other
  than the injected FakeErr would leave err.lines without "careful now", so `eventually` gives false and
  the assert fails. If it wrote to System.err, the capture holds the line until the restore (correct).
- The injected err reaches the sink only through finish. A regression that finishes the sink early is
  caught by the sibling leaf at lines 232-260, which checks position against "flush".
- What is left is only that the leaf name says more than its asserts. That is not a defect that hides a
  real failure.
