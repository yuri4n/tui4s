# core-3 review: correctness and SPEC conformance

PR: feat/core-3-terminal (C5 + C6 + C7). Reviewer lens: correctness and SPEC
conformance. Read-only review; no build was run.

Status: done. 8 findings (2 major, 6 minor). C6 examples and their tests match SPEC 3.1-3.3 and 6.3; the tasty-query check and the parity generator look correct.

## Findings

### spec-1 (major) NotATerminal: a redirected stdout (or a piped stdin with a controlling TTY) is not detected
- Files: modules/core/src/main/scala/tui4s/internal/TerminalIO.scala:74-75; modules/core/src/main/scala/tui4s/Tui.scala:31-33; Options.scala:66-67.
- The only check is `enableRawMode()` failing. crossterm's raw mode, size query and event source use `/dev/tty` when stdin is not a TTY, and the JNI `execute`/`enqueue` write to Rust's stdout.
- Scenario: `java -jar counter.jar > out.txt` (or `| tee log`) in a terminal: raw mode succeeds on stdin, the alternate screen and every frame go into out.txt, the user sees nothing and must type `q` blind. `echo x | app` also runs (reads keys from /dev/tty). The Tui.run scaladoc says "Fails with NotATerminal when stdin or stdout is not a terminal"; the error text says "stdin/stdout is not a terminal".
- Fix: before step 3, check that stdin and stdout are terminals (`System.console()` non-null and `isTerminal` on JDK 22+, which checks isatty on both fds; or isatty(1) through FFM), and fail with NotATerminal; or narrow the doc and the message to what is really checked.

### spec-2 (major) EventMapping: the exhaustivity warning is not an error, as SPEC 2.4 requires
- Files: build.sbt:23-33 (scalacOptions), modules/core/build.sbt; modules/core/src/main/scala/tui4s/internal/EventMapping.scala:16-17.
- SPEC 2.4: "Scala 3.9 checks Java sealed interfaces for exhaustivity ... and the build turns that warning into an error for this file". No -Wconf / -Werror in any build file; the scaladoc itself says a new case is only "a compile warning".
- Scenario: a jatatui/crossterm upgrade adds a KeyCode (or Event / MouseEventKind) case. `sbt check` passes with a warning. At run time the new key reaches EventMapping on the input thread, the match throws MatchError, InputThread turns it into Failed(TerminalIO(MatchError)) and the app quits with "terminal I/O failed".
- Fix: add a -Wconf rule that makes the match-exhaustivity warning an error for this file (for example `-Wconf:msg=match may not be exhaustive&src=.*EventMapping\.scala:e`) in modules/core/build.sbt.

### spec-3 (minor) TuiApp stop hook waits for the fiber, not for the restore: System.exit in app code hangs 2 s
- File: modules/core/src/main/scala/tui4s/TuiApp.scala:85-107.
- SPEC 5.9 TuiApp.main step 3: the hook interrupts the fiber and waits up to 2 s "for the restore latch". The code waits on `done`, which is counted down only when the whole program fiber (run + onExit) completes.
- Scenario: a TuiApp whose `update` (or a keymap action) calls `sys.exit(0)` (SPEC 5.13 lists System.exit in user code). That call blocks the Kyo worker that runs the render fiber inside Runtime.exit. The JVM runs tui4s-restore (restores the terminal in ~50 ms) and tui4s-stop; tui4s-stop interrupts a fiber that can never resume and waits the full 2 s on `done`. The process ends 2 s after the terminal is restored.
- Fix: count a restore latch down at the end of Session.close (or TerminalIO.leave) and let the stop hook wait for that latch (SPEC wording), not for the fiber's completion.

### spec-4 (minor) SIGTERM: the restore hook closes the inbox under the live loop; the run can end as TerminalIO("inbox closed"); the SIGTERM test runs only one hook
- Files: modules/core/src/main/scala/tui4s/internal/TerminalIO.scala:96-107, Session.scala:97-111, RenderLoop.scala:117-125 (closedIsBug), TuiApp.scala:118-130; test modules/core/src/test/scala/tui4s/TuiAppTest.scala:150-178.
- On SIGTERM the JVM starts every hook at once: tui4s-stop (interrupt) and tui4s-restore (Session.close, which closes the inbox). If the close wins, the render fiber parked in `take` gets Closed, `closedIsBug` fails the run with TerminalIO(IllegalStateException("inbox closed: ...")), and `report` prints "terminal I/O failed" + "cause: ... inbox closed" instead of "interrupted" (SPEC 5.13: SIGTERM = interrupt). The same happens for System.exit from a source fiber.
- The leaf "the SIGTERM hook interrupts the run" says "What the JVM does on SIGTERM: run the main hook" and runs only tui4s-stop, so it does not test the real case (both hooks).
- Fix: mark the session as releasing before the inbox is closed and let the loop treat Closed during a release as an interruption (or let TuiApp report "interrupted" when its hook ran); make the test start both hooks concurrently.

### spec-5 (minor) Kyo Log is asynchronous in RC7: the last log lines of a run miss the logFile
- Files: modules/core/src/main/scala/tui4s/internal/TuiLog.scala:27-43, LogSink.scala:51-70 and 82-97, TerminalIO.scala:141.
- Kyo RC7 `Log.warn/info` (emitAt -> Log.emit) put the event on a global daemon channel (`kyo.Log.asyncLogging` default true); the daemon calls TuiLog later. TerminalIO.leave finishes the sink in the release. A line that the daemon delivers after `finish`: with `logFile`, an info/debug line is dropped and a warn line goes to stderr, not to the file. The TerminalIOTest leaf "Kyo log lines ..." already needs `eventually` after `TuiRun.run` returned, so late delivery happens.
- Scenario: `TuiOptions(logFile = Some(p))`, a fold does `Log.info("saved 3 items")` and returns Quit; the release runs before the daemon drains; the line is not in p. Also TuiLog stamps the time at write, not `event.timestamp`.
- Fix: await `Log.flush` before the sink is finished on the normal path (TuiRun, after the loop, before the release), and keep a file sink appending after `finish` (reopen in append mode) for lines that still come late; override `emit` to use `event.timestamp`.

### spec-6 (minor) OutputCapture restores the stream of the first run, not the one that was current
- File: modules/core/src/main/scala/tui4s/internal/OutputCapture.scala:45-63 and 89-102.
- `installStreams` creates the forwarders (and records originalOut/originalErr) only once (`if out == null`). Later starts reuse them; `stop` sets System.out back to the first original.
- Scenario (Tui.run, no TuiApp): run 1 ends; the program calls `System.setOut(fileStream)`; run 2 starts: System.out = forwarder that writes to the first stdout; run 2 ends: System.out = the first stdout. The program's own redirection is silently undone, and its later prints go to the terminal, not to fileStream. SPEC 5.9 step 5: "restore System.out / System.err" (the streams of this run).
- Fix: when not pinned, record the current System.out/err at each `start` and restore those; or rebuild the forwarders when System.out is not one of them.

### spec-7 (minor) BurstProbe counts the quit tail (an idle input poll + release) as per-event cost; the B1 "miss" is not established
- Files: bench/src/main/scala/tui4s/bench/BurstProbe.scala:25-35; bench/BASELINE.md (B1 row).
- t0 is taken before `Headless.start` and t1 after `h.await`, so the time includes the start, the first frame and the quit: after the last batch the input thread sits in `poll(25)` on an empty ScriptedInput, and the release joins it (QuitProbe on the same machine: p50 34 ms from `q` to the end).
- Scenario: one round = ~121 ms for 10 000 keys (12.1 us/event, recorded as a miss of B1 <= 10 us). Up to ~25-34 ms of that is the quit tail that B7 budgets separately, i.e. up to ~3 us/event; without it the burst is ~9 us/event, inside the budget. SPEC 7.3: a miss "opens an issue", so the baseline points at a non-existent regression.
- Fix: time from the first `read` to the batch that folds the last key (an Observer/`h.states` reaching 10 000, or timestamps in the input port), excluding start and release; re-measure B1.

### spec-8 (minor) The Kyo-log leaf does not check "after the restore"
- File: modules/core/src/test/scala/tui4s/TerminalIOTest.scala:282-308.
- The leaf is named "Kyo log lines at warn or above are printed after the restore" but only checks that the line shows up in err eventually, and that "detail" does not. It never compares the position of the line with "raw-mode-off"/"flush" (the output-capture leaf at 232-260 does).
- Scenario: a regression where TuiLog writes a warn line straight to the real stderr while the UI is up (over the screen) still passes this leaf.
- Fix: assert `calls.indexWhere(_.contains("careful now")) > calls.indexOf("flush")`, as the capture leaf does (call Log.flush in the app first if needed).
