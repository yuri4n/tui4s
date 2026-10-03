# core-2 review: runtime, concurrency and performance

Reviewer lens: runtime, concurrency and performance.
Branch: feat/core-2-engine (worktree .work/worktrees/core).
Status: done (8 findings: 1 critical, 3 major, 4 minor).

## Findings

### runtime-1 (major) Runner.startUnscoped leaks the app fiber when the caller is interrupted before the first frame
- File: modules/core/src/main/scala/tui4s/internal/RenderLoop.scala:270-276 (also Runner.start 290-291, LiveRun.apply testkit/LiveTest.scala:184-186)
- The app fiber is started with Fiber.initUnscoped, then the caller parks on `obs.ready.safe.get`. Nothing owns the
  app fiber until the handle is returned: `Scope.ensure(h.stop)` (Runner.start, spi.Headless.start) and
  `Sync.ensure(handle.kill)` (LiveRun) are registered only after startUnscoped returns.
- Scenario: `Scope.run(Headless.start(app, backend, input))` (or the C5 `Tui.start`) inside an `Async.timeout`, or a
  kyo-test leaf timeout / liveBlocking timeout, that fires while `init`/`sources(init)`/the first paint run. The caller
  is interrupted in `ready.get`; the app fiber keeps running forever (sources tick, input thread polls), and on the
  real terminal (C5) raw mode and the alternate screen are never left.
- Fix: make the wait own the fiber: e.g. `Sync.ensure(Sync.Unsafe.defer(if !handedOut then fiber.unsafe.interrupt()))
  (obs.ready.safe.get)` or, better, interrupt-and-await on that path (Async finalizer) so the restore is done when the
  interrupt is reported; for Runner.start register `Scope.ensure` (or a bracket) before waiting.

### runtime-2 (critical) settle (untilIdle) deadlocks under virtual time when a task body sleeps
- File: modules/core/src/main/scala/tui4s/internal/Engine.scala:438-447; testkit/LiveTest.scala:119-147, 186-187
- `completeProbes` keeps an untilIdle probe while `sup.busyTasks > 0`. Every LiveTest input method, every 1 ms step of
  `advance` and the settle before `test` use untilIdle. Time moves only in `advance`, between settles.
- Scenario: a debounced search, `withTask(source.task(Async.sleep(300.millis).andThen(search(q)))(...).id("search"))`
  (or a standing `task` that waits on Clock / Async.timeout). `t.typeText("abc")` puts the key, the task starts and
  parks in a virtual sleep, the probe is never completed because the task never ends and no one advances the clock:
  the test hangs until its timeout (deterministic). `advance(1.second)` hangs the same way at its first step, and an app that starts
  such a task from `init`/`sources(init)` hangs in the settle that LiveRun runs before `test`. The same for a toast
  (`withTask(source.task(Async.sleep(2.seconds))((s, _) => s.copy(toast = None)))`), and, because of the declared
  deviation (standing tasks count too), for any app that declares a long-running standing `task` (a connection or a
  file watcher): `TuiTest.live` never reaches `test`. Root: SPEC 6.2 makes settle wait for one-shot tasks while time
  moves only in `advance`; the implementation widens it to standing tasks.
- Fix: a task that is parked on a virtual sleeper must count as idle. For example: settle completes the probe when
  `busyTasks == 0` OR the time control has at least `periodic + busyTasks` pending sleepers (loop: plain probe, then
  `race(probe-on-next-Done, awaitPendingSleepers(n))`); or use plain (non-idle) probes inside `advance` steps and
  document that settle waits only for tasks that do not sleep.

### runtime-3 (major) LiveTest.advance waits forever for a sleeper that ended (stale sleeper count)
- File: modules/core/src/main/scala/tui4s/testkit/LiveTest.scala:129-147 (count from Supervisor.sleepers, internal/Supervisor.scala:181-187)
- `parked` reads `view.sleepers` (Periodic + Chunked generations at the last published decision) once, then
  `control.awaitPendingSleepers(n)`. Kyo's awaitPendingSleepers only re-checks when a NEW sleep is enqueued
  (single `armWaiter`). `control.advance(step, Duration.Zero)` does not wait for the woken fibers, so the settle
  probe of that step can be folded before their values and their `Done`.
- Timing-dependent (more likely under CPU load), so it shows as a flaky hang.
- Scenario: a finite stream source with sleeps (`Stream.unfold(0, chunkSize = 1)(i => Async.sleep(100.millis).andThen(
  if i < 3 then Present((i, i + 1)) else Absent))`), or a `poll` whose task fails at a tick and has `.recover`. At the
  step where its last sleep fires, if the settle probe is folded before the woken fiber's last value and `Done`, the
  published count still includes it. At the next step `parked` waits for n sleepers, the source never sleeps again,
  the other periodic sources are already parked and enqueue nothing: `advance` hangs until the test timeout.
- Fix: do not wait on a stale count. E.g. keep a live AtomicInteger of running Periodic/Chunked fibers in the Launcher
  (decremented in the fiber's onComplete, before Done is put) and loop: read it, race `awaitPendingSleepers(n)` with a
  promise that the decrement completes, re-read on wake-up.

### runtime-4 (major) Session.close skips the terminal leave when the join is interrupted
- File: modules/core/src/main/scala/tui4s/internal/Session.scala:78-93 (stopAndJoin at 199-202)
- `safely` catches `NonFatal` only. `InterruptedException` is not NonFatal, so an interrupt during
  `thread.stopAndJoin` (Thread.join) leaves `close()` and the last step `control.leave(opts)` never runs; `closed` is
  already true, so no later call (the C5 shutdown hook) can retry it. The scaladoc promises "never throws; every step
  runs".
- Scenario: the app quits normally, its release blocks the worker in `join` (up to one poll, 25 ms; up to 100 ms). In
  that window the run fiber is interrupted (the C5 SIGTERM hook or a ZIO/CE cancel racing the quit). Kyo's BlockingMonitor sees the interrupted task's worker blocked and calls
  `Thread.interrupt()` on it (BlockingMonitor.process, IOTask.needsInterrupt = interrupted). `join` throws
  InterruptedException; raw mode and the alternate screen stay on (C5), "leave-raw-mode" is missing (RecordingControl).
  The same happens when the worker thread already carries an interrupt flag set by the monitor while the render fiber
  was blocked in a terminal write (C5): the release then runs on that thread before the worker clears the flag.
- Fix: in `safely` (or around the join) also catch `InterruptedException`, remember it, run the remaining steps, and
  re-assert `Thread.currentThread().interrupt()` at the end.

### runtime-5 (minor) An input-thread failure is lost when the inbox is full; the app then runs with no input
- File: modules/core/src/main/scala/tui4s/internal/Session.scala:190-191
- `inbox.unsafe.offer(Failed(...))` returns false (or Closed) when the inbox is full, and the result is discarded. The
  thread then ends.
- Scenario: a source floods the inbox (a chunkSize-1 log stream, a busy `Source.apply`); during the 16 ms pacing sleep
  the inbox reaches 1024 items. At that moment `port.read()` throws (C5: crossterm read error). `Failed` is dropped,
  the input thread is gone, and the app keeps running and drawing but never sees a key again: no `q`, no Ctrl-C
  (ctrlCQuits is an input event). The user must kill the process; `Tui.run` never fails with TerminalIO.
- Fix: `inbox.unsafe.putFiber(Failed(...))` (waits for room; a close releases it), as the thread already does for
  events.

### runtime-6 (minor) Interrupt windows between a start and its registration leak a fiber / the input thread
- Files: modules/core/src/main/scala/tui4s/internal/Supervisor.scala:274-286 (Launcher.startOne);
  internal/Session.scala:106-137 with RenderLoop.scala:217 (Session.open as the bracket acquire)
- startOne: `Fiber.initUnscoped(body)` returns, then a separate `.map { Sync.Unsafe.defer { r.fiber = fiber; onComplete } }`.
  Kyo's Eval honours a stop at that continuation (Eval.scala:194/269). If the render fiber is interrupted there,
  `r.fiber` stays null, `stopAll` cannot interrupt it, and because `TokenEmitter.apply` swallows `Closed`, an `every` /
  `poll` source loops forever after the app ended (a fiber that wakes each period for the life of the JVM).
- Session.open: the input thread is started and the terminal entered inside the defer, but the bracket installs its
  release only when the acquire's VALUE arrives, after `.map(r => Abort.get(r))`. An interrupt at that continuation
  leaves the input thread running and (C5) the terminal in raw mode.
- Scenario: an external interrupt (facade cancel, interruptAwait, TuiHandle.kill) that lands right after a source start
  or right after the session opened. Narrow, but the result is permanent.
- Fix: start and register in one unsafe block (create the fiber and set `r.fiber` / `onComplete` in the same
  `Sync.Unsafe.defer`), and make the acquire's value the defer's own result (return an Either and fail after the
  bracket, or let the release accept a failed open). Optionally stop a source body on `Closed` instead of swallowing it.

### runtime-7 (minor) The fold computes the trace code of every event even when tracing is off
- File: modules/core/src/main/scala/tui4s/internal/Engine.scala:169 (EventCode in internal/Trace.scala:180-188)
- `val code = EventCode(ev)` runs for every input event; it is used only for the trace and for the failure message.
  The class doc says "null: no trace (and no trace cost)".
- Scenario: trace off, a 1 MiB bracketed paste: the render fiber base64-encodes it (UTF-8 bytes + a ~1.4 MiB string,
  about 3 MiB of garbage) for nothing; each key also builds "key:" + Key.show. This is pure per-event overhead against
  the B1 budget (<= 10 us/event).
- Fix: compute the code only `if tracing`, and build it lazily in the `catch` for `fail("update on " + ...)`.

### runtime-8 (minor) RecordingControl.screen reads the live jatatui buffer from another thread
- File: modules/core/src/main/scala/tui4s/testkit/RecordingControl.scala:31 (used by RuntimeTest `eventually(control.screen...)`)
- `Screen.of(backend.buffer(), None)` walks the TestBackend buffer on the caller's thread while the render fiber
  writes it (`TestBackend.draw` sets cells in place; `resize` changes the buffer area in place). This breaks the
  one-owner rule for jatatui buffers that the live kit (LiveView) follows.
- Scenario: a run-seam test (core, and the facade tests of SPEC 6.3 that check "Count: 2" on screen) polls
  `control.screen.contains(...)` while frames are drawn: it can read a torn frame (half old, half new) and pass or
  fail by chance; if a Resize is folded during the poll, `cellAt` runs with the old area on a resized buffer and throws.
- Fix: snapshot on the render fiber: let RecordingControl return a backend wrapper whose `flush()` (the end of each
  `Terminal.draw`) stores an immutable `Screen` in a volatile field, and have `screen` return that copy.
