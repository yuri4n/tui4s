# core-2 verify: runtime lens

Skeptic pass over the runtime findings. Worktree: .work/worktrees/core (HEAD 8b952d8).
Verdicts are added as each finding is checked.

## runtime-2: settle (untilIdle) deadlocks under virtual time when a task body sleeps

Verdict: CONFIRMED (the root is in SPEC 6.2; severity "critical" is high for a test-kit-only hang).

- Kyo RC7 `Clock.withTimeControl`: a `sleep` enqueues a `Task(current + d)` and completes only in `tick()`,
  which only `set`/`advance` call (Clock.scala 445-506). No wall-clock fallback.
- `Launcher.startOne` uses `Fiber.initUnscoped`, which keeps the time-control local, so a `task` body's
  `Async.sleep` is virtual.
- `Supervisor.busyTasks` counts every live `Kind.Task` generation (standing or one-shot). `Engine.completeProbes`
  keeps an `untilIdle` probe while `busyTasks > 0`, and it is called only after a draw decision.
- `LiveRun.settle` always uses `Probe(p, untilIdle = true)`; `press`/`send`/`typeText` call it; `advance`
  calls it after every 1 ms step and at the end.
- Trace (press): key folds -> task planned -> `syncWork` puts it in liveMap -> launched -> fiber parks in a
  virtual sleep -> `completeProbes` sees busyTasks = 1, probe kept -> no more items, no more decisions ->
  `p.safe.get` never returns. `advance` cannot even be called. Trace (advance with a task already parked):
  first step `control.advance(1ms)` + `settle` -> same wait.
- No test covers a sleeping task (LiveKitTest uses only every/poll/stream and non-sleeping tasks).
- SPEC 6.2 says settle "repeats while a one-shot task is running" and "a task that never ends makes settle
  wait", so the press half is SPEC behaviour. But the code's own doc of `advance(d, sleepers)`
  (LiveTest.scala 41-43) says to pass `sleepers` for "a task that sleeps": that documented case hangs at
  the first step. The finding does not contradict a section-9 decision.
- The standing-`task` part (initial settle in LiveRun.apply 187 never returns) follows from the declared
  deviation; it needs a `task` body that never ends (unusual, `Source.apply`/`stream` fit that better).

## runtime-1: Runner.startUnscoped leaks the app fiber if the caller is interrupted before the first frame

Verdict: CONFIRMED (real, but the window is normally short; "major" is arguable).

- RenderLoop.scala 270-276: `Fiber.initUnscoped(runOn(...))`, then the caller parks in `obs.ready.safe.get`.
  Kyo RC7 `Fiber.initUnscoped` is "Deliberately unparented" (Fiber.scala 178-198): interrupting the caller does
  not interrupt the child. Kyo's own comment on `Fiber.use` warns of exactly this window ("in a mapped
  function a window opens where the child runs with nothing to stop it").
- Nothing else owns the fiber in that window: `Runner.start` (290-291, used by `spi.Headless.start`) adds
  `Scope.ensure(h.stop)` only after the handle returns; `LiveRun.apply` (184-186) adds `Sync.ensure(handle.kill)`
  only after it returns; `Tui.startWith` returns the bare handle.
- SPEC 4.2 (line 1464, "validated") specifies `Runner.startUnscoped ... : TuiHandle[S] < Sync` and
  `Runner.start` = it + `Scope.ensure(stop)`. The impl made it `< Async` and waits for the first frame (a
  declared deviation), which turns a few-continuation gap into a parked wait. So the finding does not
  contradict SPEC.
- Limits: the facades' ZIO `acquireRelease` / CE `Resource.make` acquires are uninterruptible, so the facades
  are not affected. The app fiber before its first frame does mostly synchronous work (Session.open, init,
  sources, launch, paint), so the window is the time to the first frame (longer on a cold JVM). The C5 raw-mode
  claim is about code that does not exist yet, but C5 `Tui.start` is specified as this same shape.

## runtime-3: LiveTest.advance can wait forever on a stale sleeper count

Verdict: CONFIRMED (timing-dependent; needs a sleeping source that ends: a finite stream, or a poll whose
task fails and has `.recover`).

- `parked` (LiveTest.scala 130-137) reads `view.sleepers` once and passes it to `awaitPendingSleepers(n)`.
  `view.sleepers` = `sup.sleepers` at the last published decision; a generation leaves `liveMap` only when its
  `Done` is folded (`sup.finished`) or it is stopped.
- Kyo RC7 `awaitPendingSleepers(count)` (Clock.scala 460-472): `count` is fixed; when fewer non-done sleepers
  are queued it arms one waiter that only the next `sleep` enqueue completes.
- `control.advance(step, Duration.Zero)`: `tick()` completes due sleepers and returns after a 0 ms live
  sleep; it does not wait for the woken fibers. The woken fiber's `Done` (put by `onComplete` via `putFiber`)
  races the next `settle` probe. If the probe batch is decided first, the probe completes (a stream/poll is
  not `Kind.Task`, so no untilIdle wait) and the published count still includes the ended source; the next
  `parked` can read it before the render fiber folds the `Done`.
- Then n = (parked others + 1) but only the others are queued; they are already parked and nothing else
  sleeps until time advances, which waits for this: a hang until the leaf timeout.
- SPEC 6.2 computes n as "running" sources (and even only once per advance); the stale count is an
  implementation race, not a SPEC choice. No test covers a source that ends under `advance`.

## runtime-4: Session.close skips the terminal leave when the input-thread join is interrupted

Verdict: CONFIRMED (narrow window, severe result; it breaks the documented "never throws, every step runs").

- `safely` catches `NonFatal`; Scala's `NonFatal` returns false for `InterruptedException`, so a throw from
  `thread.stopAndJoin` -> `Thread.join(timeout)` leaves `close()` before `control.leave(opts)`; `closed` is
  already true, so no later call retries it.
- Kyo RC7 does deliver `Thread.interrupt()` to a worker: BlockingMonitor.process (BlockingMonitor.scala
  258-280) interrupts a blocked worker (CPU time flat for ~2 samples of 2 ms) whose current task has
  `needsInterrupt()`; IOTask.needsInterrupt = `interrupted || !isPending()` (IOTask.scala 203).
- Path (a): the app quits, the bracket release runs in a normal slice and parks in `join` (the input thread
  finishes its `poll(pollMillis)`, 25 ms by default). An interrupt that lands then CASes the status to
  `interrupted` (IOTask.interrupt), the monitor sees the worker blocked and interrupts the thread, `join`
  throws. Window: about pollMillis minus ~4 ms per quit.
- Path (b): the monitor interrupts the render worker while it is blocked in a terminal write (not
  interruptible, so the flag stays set). The stop unwinds the slice and `IOTask.run` -> `release()` ->
  `abandon` runs the owed release on the same thread, inside `task.run`, before `Worker.runTask`'s finally
  clears the flag (Worker.scala 425-431). `join` on a still-alive input thread then throws at once.
- Not affected: a plain interrupt of a parked fiber (abandon sets status Done with the promise pending, so
  needsInterrupt is false and no thread interrupt is sent).

## runtime-5: an input-thread failure is lost when the inbox is full

Verdict: CONFIRMED (minor; needs a full inbox at the moment the port throws).

- Session.scala 190-191: `discard(inbox.unsafe.offer(Failed(...)))`. Kyo RC7 bounded `Channel.Unsafe.offer`
  (Channel.scala 735-739) is `queue.offer(value)`, `Success(false)` when the queue is full; it does not wait.
  The thread then returns from `run()`; nothing else tells the render fiber that input has stopped.
- The inbox can be full: capacity 1024 (Options.scala 42); `TokenEmitter.apply` uses a waiting `put`, so a
  fast chunkSize-1 stream fills the queue during the 16 ms pacing sleep and stays at the limit under
  backpressure.
- The event path in the same method already uses `putBatchFiber` (waits; a close releases it), so the fix
  is local and consistent.

## runtime-6: interrupt windows between a start and its registration

Verdict: CONFIRMED (Kyo documents exactly this window; the Session part is wider than the finding says).

- Kyo RC7 kernel Arrow.scala 105-112: "[[Arrow.apply]] polls before applying, so an interrupt pending when the
  value arrives parks the computation and `f` never runs. ... wrong where `f` records an obligation the value
  just created". `Pending.map` (Pending.scala 65-77) builds such a polling step; Eval.scala 380-385 parks on
  a refused value. An abandoned remainder only runs releases already owed (IOTask.abandon -> Eval.release).
- Launcher.startOne (Supervisor.scala 277-286): `Fiber.initUnscoped(body)` creates and schedules the child;
  `r.fiber = fiber` and `onComplete` are in a later `.map`. A stop pending when the fiber value arrives
  skips the map: `r.fiber` stays null, `stopAll`/`interruptPlanned` (264-265) skip it, the child is
  unparented, and `TokenEmitter.apply` (302-303) swallows `Closed`, so an `every`/`poll` source keeps
  waking (a poll keeps running its task) for the life of the JVM.
- Session.open (Session.scala 106-137) returns `Sync.Unsafe.defer { enter; ...; t.start(); Right(session) }
  .map(r => Abort.get(r))`. Kyo's `Bracket.apply` installs the release only when the acquire's value reaches
  its Ensure arrow ("a computation abandoned before it ran has nothing to release", Bracket.scala 91-95). An
  interrupt that lands at any time during the defer body (enter, Terminal.create, thread start) is honoured
  at the first poll after it, which is that `.map`: the terminal is entered, the input thread runs, and no
  release is owed.

## runtime-7: Engine.fold computes the trace code of every event even when tracing is off

Verdict: CONFIRMED (minor efficiency; the cost per key is far below the B1 budget).

- Engine.scala 169: `val code = EventCode(ev)` is an eager val for every event that is not a quitting
  Ctrl-C. It is read only under `if tracing` (170-172) and in the catch (179).
- EventCode.apply (Trace.scala 180-188): `"key:" + k.show` per key; for `Paste`, UTF-8 bytes plus a Base64
  string of the whole text. So a large paste builds a few MB of unused garbage with the trace off, and the
  Engine doc ("null: no trace (and no trace cost)", 42-43) is not true. A key costs a small string
  (tens of ns), so the B1 argument is weak; the paste case is the real cost.

## runtime-8: RecordingControl.screen reads the live jatatui buffer from the caller's thread

Verdict: CONFIRMED as a latent data race (minor). The stated test failures need a case no current test has.

- RecordingControl.scala 31: `Screen.of(backend.buffer(), None)` on the caller's thread. The render fiber
  writes the same `TestBackend` buffer in `terminal.draw` and resizes it in place via `control.resize`
  (RenderLoop.scala 159, RecordingControl 39-40). This breaks the AGENTS.md one-owner rule that LiveView
  keeps (it copies the screen inside `published`, on the render fiber).
- jatatui 0.31 `Buffer.resize` replaces `content` and then `area` (Buffer.java 252-266); `Screen.of`
  captures `area` once and calls `cellAt` per cell, and `indexOf` throws IndexOutOfBoundsException outside
  the area, so a Resize folded during a read can throw on the test thread.
- Today: RuntimeTest polls `control.screen` while the Dashboard's sources draw (166-168), so torn reads do
  happen, but every poll is a positive `contains` retried by `eventually`, and the reads after `fiber.get`
  see a stopped app. No test resizes a RecordingControl run. So "passes or fails by chance" is not
  reachable with the current suite; it is a hazard for later C5 and facade tests.

## Summary

| id | verdict |
| --- | --- |
| runtime-2 | confirmed (SPEC 6.2 root; severity lower than "critical") |
| runtime-1 | confirmed (window = time to first frame) |
| runtime-3 | confirmed (timing-dependent hang) |
| runtime-4 | confirmed |
| runtime-5 | confirmed (minor) |
| runtime-6 | confirmed (Session window is the whole open body) |
| runtime-7 | confirmed (minor) |
| runtime-8 | confirmed, latent (no current test hits it) |
