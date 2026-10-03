# core-3 verify: runtime lens

Skeptic pass over the runtime findings. Worktree: `.work/worktrees/core` at f5d7cb0.
Status: done (2026-10-01).

## runtime-1: confirmed (race; the wording "never" is too strong)

- Kyo RC7 `Log.info/warn` (static tier) -> `Log.enqueue` -> `log.emitAt` -> `Log.emit(Event(level, TuiLog, ...))`.
  `LogPlatformSpecific.emit`: when `Log.asyncLogging()` (StaticFlag default `true`, Log.scala:566) it offers
  the event to the daemon channel (capacity 4096). The drain fiber calls `event.sink.emit(event)`; the default
  `Log.Unsafe.emit` routes to `TuiLog.info/warn` -> `LogSink.line`. So TuiLog gets the line on the drain fiber.
- Nothing in the worktree calls `Log.flush` or sets `kyo.Log.asyncLogging` (grep). TuiRun.run/start only wrap
  `Runner.run` with `TuiLog.let`; `TerminalIO.leave` step 5 calls `sink.finish(err)`.
- After `finish`, `LogSink.line` drops a non-important line and prints an important one to the real stderr.
  With `logFile`, a late info line is lost and a late warn line goes to stderr, not to the file.
- Evidence that late delivery happens: TerminalIOTest "Kyo log lines at warn or above are printed after the
  restore" needs `eventually` after `TuiRun.run` returns.
- Limit: it is a race, not a certainty. The release joins the input thread first (tens of ms), so a single
  line usually arrives in time. A backlog (debug logging, a file flush per line) makes the loss likely.
  Default level is warn (`Log.live.level`), so the info example needs `-Dkyo.Log.defaultLevel=info`.

## runtime-2: confirmed (a contract and docs gap; the code follows the SPEC 5.9 mechanism)

- The only check is `crossterm.enableRawMode()` failing (TerminalIO.scala:74). No `System.console`,
  `isatty` or `/dev/tty` check exists in main code (grep).
- The native library is crossterm 0.29.0 with the mio source (paths in libnative-arm64-darwin-crossterm.dylib;
  the string `/dev/tty` is in it). crossterm `tty_fd()` uses stdin when `isatty(0)`, else opens `/dev/tty`;
  raw mode and the mio event source both use it. NativeTerminalTest's own comment says the same and skips
  when `/dev/tty` opens.
- So `> out.txt` (stdin is a TTY) and `< /dev/null` (with a controlling TTY) do not fail. Frames go to fd 1.
- Tui.run doc ("Fails with NotATerminal when stdin or stdout is not a terminal") and the message
  "stdin/stdout is not a terminal" promise more than the code does. SPEC 5.9 step 3 has the same wrong gloss;
  no user decision in section 9 covers it, so the finding does not contradict the SPEC.
- Note: `examples` uses `outputStrategy := Some(StdoutOutput)`, so sbt pipes the child's stdout. A strict
  stdout-is-a-TTY failure would break the run command; the fix must be a warning or doc change.

## runtime-3: confirmed

- TuiApp.block: the `tui4s-stop` hook calls `fiber.unsafe.interrupt()` then `done.await(2, SECONDS)`; `done`
  counts down only in `fiber.unsafe.onComplete`. The hook is removed only in `Sync.ensure` after `fiber.get`,
  so it is still registered while `onExit` runs.
- Kyo RC7 IOTask.interrupt (IOTask.scala:163-188) overrides IOPromise.interrupt "to take the interrupt without
  completing" (IOPromise.scala:198-205). For a running task it CASes the status and calls
  `Safepoint.stop(thread, this)`, which only sets a slot (kyo-kernel Safepoint.scala:213); no
  `Thread.interrupt`, no completion.
- `System.exit` on the program fiber's worker blocks in `Shutdown.exit -> ApplicationShutdownHooks.runHooks`,
  which joins every hook and retries on InterruptedException. The worker never reaches a safepoint, so the
  fiber never completes and the hook always waits the full 2 s. Same for `sys.exit` in `update` (the program
  fiber waits on the blocked render work).
- SPEC 5.9 TuiApp step 3 says "wait up to 2 s for the restore latch", not for fiber completion, so the fix
  matches the SPEC. `main` exits non-zero only for failures, so `sys.exit` in `onExit` is the natural way
  to set a custom code.

## runtime-4: confirmed (minor; needs a library that writes '\r' progress to System.out/err)

- OutputCapture.Forward.write: during a capture every byte that is not '\n' goes into `partial`
  (ByteArrayOutputStream, no limit). Only '\n' calls `emit`; a '\r' is stripped only at the end of a line.
- LogSink.keep limits the ring by line count (capacity 200), not by bytes. With a log file the whole
  progress text is written as one line only at the stop.
- `Capture.stop` -> `drain` emits the whole buffer as one important line, printed after the restore.
- `System.console()` still reports a terminal while the capture runs (the capture does not change fds), so
  a library that checks it keeps writing '\r' updates. Default `captureOutput = true` (SPEC 9 Q2), so the
  scenario is on the default path. The finding does not contradict a user decision.

## runtime-5: confirmed (the method; the "within budget" number is an estimate, re-measure to settle)

- BurstProbe stops the clock after `h.await` = `fiber.get` (Tui.scala:100). The run's fiber completes only
  after the synchronous release: `Session.close` sets `running = false`, closes the inbox and joins the
  input thread for up to `2 x pollMillis + 50` (Session.scala:97-110). After the last batch, InputThread
  waits in `ScriptedInput.poll(25)` = `LinkedBlockingQueue.poll(25 ms)`; nothing interrupts it.
- The SPEC 7.1 reference "~6.7 us/event" is `processing` in `.work/scratch/kyo-rc7/loop2.log:53`
  (first wake-up to Quit applied); `shutdown` is reported apart. The same log shows the platform-thread input
  mode with `shutdown= 60.20 ms` at poll 50 ms. So BurstProbe measures start + burst + shutdown, which is
  not the quantity of the reference or of B1 ("runtime overhead per input event").
- Size: the join waits the rest of one 25 ms poll, plus the release and wake-ups (QuitProbe p50 34 ms covers
  a full poll). In the burst the input thread's last poll starts before the render fiber has folded the
  last ~1 300 events, so the wait is likely 15-25 ms, not 25-35 ms. Without it B1 is about 9.5-10.6
  us/event: near the budget, not clearly inside it. The finding is right that the recorded miss is not a
  clean per-event number.

## runtime-6: confirmed in part (the perturbation and the SPEC deviation are in the code; the cause of the one 19 is not proven)

- Traced: `heapAfterGc` (3 x `System.gc()` + `Thread.sleep(200)`, Probes.scala) runs inside
  `h.states.current.map`, after `Async.sleep(10.seconds)`, so on a Kyo worker; `Probes.threadCount` is read
  right after it (SoakProbe.scala:67-73). The worker is blocked for >= 600 ms each sample.
- Traced: kyo-scheduler RC7 BlockingMonitor flags a worker whose user CPU time stops advancing (SchedulerDefaults
  sets the scan to 20 ms), and the scheduler then routes tasks and drains its queue to other workers. Worker
  threads come from `Executors.newCachedThreadPool` (WorkerExecutors.scala), so extra mounted workers can
  create a thread that lingers 60 s.
- Traced: SoakProbe's pass check is `threads.max - threads.min <= 4` (line 102), not SPEC 7.3 B8 "thread count
  constant". A leak of 1-4 threads in 600 s passes.
- Not proven: the cited floor `blocked + minWorkers` (Scheduler.scala:481) only matters when the regulator
  has the pool below 5 workers (coreWorkers 8, minWorkers 4 on this Mac). A per-sample +1 from the probe
  would likely persist (the cached thread is reused within 60 s), but BASELINE shows 19 for only one sample.
  Re-running with sampling on a platform thread decides whether the 19 goes away.

## Summary

| id | verdict |
| -- | ------- |
| runtime-1 | confirmed (race, likely under backlog) |
| runtime-2 | confirmed (contract/docs gap) |
| runtime-3 | confirmed |
| runtime-4 | confirmed (minor) |
| runtime-5 | confirmed (method; post-fix number needs a re-measure) |
| runtime-6 | confirmed in part (perturbation + SPEC deviation; causation of the 19 unproven) |
