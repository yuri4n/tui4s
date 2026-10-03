# core-3 review: runtime, concurrency and performance

Reviewer lens: runtime, concurrency, performance. Branch feat/core-3-terminal vs feat/core-2-engine.
Status: done. 6 findings (2 major, 4 minor). Checked and not reported: CountingBackend seal vs hook race (single JNI enqueue per draw, Rust stdout is locked), lock order OutputCapture -> LogSink -> real stderr (no cycle), Kyo IOTask interrupt (promise completes only after finalizers, so the 2 s latch is sound except runtime-3), scheduler flags order in TuiApp and in the probes.

## runtime-1 (major) Kyo Log is asynchronous: late TuiLog lines miss the log file
Files: modules/core/src/main/scala/tui4s/internal/TuiLog.scala:81, LogSink.scala:51-55, TerminalIO.scala:141
Kyo RC7 `Log.info/warn` (safe API) calls `Log.emit(Event)`, which on the JVM offers the event to a
daemon fiber (kyo-core Log.scala:566 `asyncLogging` default true; LogPlatformSpecific.emit). TuiLog
gets each line later, on that fiber. `TerminalIO.leave` finishes the sink without `Log.flush`.
Scenario: `logFile = Some(p)`, update does `Log.info("saving")` and returns Quit (or a source logs in
its finalizer during stopAll); the daemon dispatches after `sink.finish` -> LogSink.line sees
`finished != null`: the info line is dropped (file closed), a warn line goes to stderr although a log
file is set. Under load or with many debug lines the whole tail is lost. Also: a late line of run A
goes straight to the real stderr while a later run B has its UI up.
Fix: after the run (TuiRun.run / start, where Async is allowed), `Log.flush` with a short timeout
before finishing a file sink; keep the file open for late lines (append) instead of dropping or
printing them; only the hook path finishes inside leave.

## runtime-2 (major) NotATerminal only when there is no controlling terminal
Files: TerminalIO.scala:74-75, Tui.scala:31, TuiApp.scala:39-40, Options.scala:65-67
crossterm 0.29 `enable_raw_mode` uses `tty_fd()`: stdin if it is a TTY, else it opens /dev/tty. So
`java ... CounterApp > out.txt` or `| tee`: raw mode succeeds on /dev/tty, frames go to the file, the
screen shows nothing, keys are not echoed: the app looks hung until a blind `q`/Ctrl-C. `< /dev/null`
also runs (input from /dev/tty). The manual check "< /dev/null prints not a terminal" only held because
the agent sandbox has no /dev/tty; NativeTerminalTest skips whenever /dev/tty opens.
Note: `sbt examples/runMain` forks with stdout piped (sbt Fork.scala:181-182 StdoutOutput ->
process.run(connectInput=false)), so a strict stdout check would break the documented run command.
Fix: decide the contract in SPEC; at least fix the docs/message ("no controlling terminal"), and
warn (before raw mode, or in the log) when `System.console()` is null / `!isTerminal()`.

## runtime-3 (minor) System.exit in update/onExit of a TuiApp delays exit by 2 s
File: modules/core/src/main/scala/tui4s/TuiApp.scala:102-107
The tui4s-stop hook waits on `done` (fiber completion). If user code calls `sys.exit(n)` on the
program fiber (in onExit to set an exit code, or in update), that worker thread blocks in
Runtime.exit until all hooks end; IOTask.interrupt only marks the task, the slice never ends, the
fiber never completes -> `done.await(2, SECONDS)` always times out: every such exit takes 2 s more.
Fix: wait on a latch counted down by the terminal release (or skip the wait once the release ran).

## runtime-4 (minor) OutputCapture keeps a partial line without bound
File: modules/core/src/main/scala/tui4s/internal/OutputCapture.scala:115-148
Only '\n' ends a line. A library that writes '\r' progress (spinner, download bar) to System.err
while the UI is up grows `partial` for the whole session (MBs over hours), and at the end prints it
as one huge line. The ring caps lines, not bytes.
Fix: treat '\r' as a line end (or keep the last segment only) and cap `partial` (e.g. 8 KiB).

## runtime-5 (minor) B1 "miss" includes the quit join of the input thread
File: bench/src/main/scala/tui4s/bench/BurstProbe.scala:25-35, bench/BASELINE.md:38
The clock stops after `h.await`, i.e. after the synchronous release, which joins the input thread
that sits in `ScriptedInput.poll(25 ms)` once the script is empty. QuitProbe measured that at
p50 34 ms. 34 ms / 10 000 keys = ~3.4 us/event of the recorded 12.1 us: without it B1 is ~8.7 us,
inside the 10 us budget. BASELINE.md records a miss that is a probe artifact.
Fix: stop the clock when `h.states` shows the final state (or subtract the measured quit time),
re-measure B1.

## runtime-6 (minor) SoakProbe blocks a Kyo worker, then counts threads
File: bench/src/main/scala/tui4s/bench/SoakProbe.scala:67-70, Probes.scala:53-59
`heapAfterGc` (3 x System.gc + Thread.sleep(200)) runs inside `h.states.current.map`, on a Kyo
worker. Kyo's BlockingMonitor flags the blocked worker and the scheduler adds a worker
(Scheduler.scala:477-481 floor = blocked + minWorkers); `threadCount` is read right after. The
18/19 variation and the relaxed "<= 4 threads" budget come from the probe itself.
Fix: sample from a plain thread outside Kyo (main thread sleeping 10 s, read stats/state through
short `Probes.block` calls), and keep the SPEC budget "thread count constant".
