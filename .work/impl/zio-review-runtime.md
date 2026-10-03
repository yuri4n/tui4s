# Review of feat/zio: runtime fidelity and resource safety

Reviewer lens: the caller runtime is kept (FiberRefs, executor via AmbientZIOs),
no fiber or thread leaks, cancellation restores the terminal before the outer
effect completes, backpressure without loss, no blocking of Kyo workers, no
deadlock between the two runtimes.

Status: done (read-only review, no build run). 4 findings: 3 major, 1 minor.

Checked and found correct (no finding):
- AmbientZIOs keeps the caller's FiberRefs, clock and executor (ZIO.runtime in
  an inheritable Local; source fibers inherit it through Fiber.initUnscoped).
- ZIO interrupt of Tui.run / Tui.start release waits for the Kyo fiber
  (kyo-zio RC7 ZIOs.run interruptAndAwait), so the synchronous terminal release
  has run before the ZIO effect completes.
- Kyo -> ZIO cancel in getOn (Sync.ensure outcome on interrupt is Present) and
  the `.interruptible` fork for runtimes captured in an acquire.
- No thread blocks across the runtimes: ZIO callbacks (async cb, addObserver)
  only schedule on the other side; no blocking call in modules/zio main code.
- Inbox backpressure: one pending put per source; a put of a stopped source is
  delivered with a dead token and dropped; Kyo's Channel drops a put whose
  waiter was interrupted (TuiHandle.update).
- ZIOApp interrupts leftover root fibers (source ZIOs) after `run` ends.

## Findings

### zio-runtime-1 (major): LiveTuiTest settles inside the uninterruptible acquire

- File: modules/zio/src/main/scala/tui4s/interop/zio/testkit/LiveTuiTest.scala:47-49,
  with modules/zio/src/main/scala/tui4s/interop/zio/internal/ZioLive.scala:168.
- `ZioLive.start` ends with `run.settle.andThen(run)`, and `LiveTuiTest.apply`
  runs it as the acquire of `ZIO.acquireReleaseWith`. A ZIO acquire is
  uninterruptible, and `ZIOs.run` restores that status around its join. `settle`
  waits until no `task` body runs (`busy == 0`).
- Failure: an app that declares a standing `Source.task` in `sources(init)`
  whose ZIO sleeps on a zio-test TestClock (`task(ZIO.sleep(1.minute).as(5))`).
  The acquire waits for the task, the task waits for `TestClock.adjust`, and only
  the test body (which never starts) can adjust: deadlock. With the real clock, a
  task that hangs (a network call) hangs the acquire. In both cases
  `.timeout(...)` or `TestAspect.timeout` cannot stop it (ZIO's timeout waits for
  the interrupt of the loser), although the kit doc says "give the test a
  timeout".
- Fix: return the handle from the acquire right after the first frame, and run
  the first `settle` at the start of `use` (inside `test(...)`, interruptible),
  for example `test(LiveRun(run))` preceded by `AmbientZIOs.run(run.settle)`.

### zio-runtime-2 (major): each waiting `states` pull (and each interrupted `await`) leaves a dead waiter on `ZioRun.done`

- File: modules/zio/src/main/scala/tui4s/interop/zio/internal/ZioRunner.scala:103-105
  (also :69 for `await`).
- When the state has not changed yet, `nextState` runs
  `Async.race(next.safe.get.unit, run.done.safe.get.unit)`. The loser fiber
  parks on `run.done` with `promise.onComplete { removeInterrupt; schedule(this) }`
  (kyo IOTask.scala:304). Kyo's `IOPromise` has no way to remove an `onComplete`
  waiter, and `done` is uninterruptible, so the interrupt of the loser leaves its
  callback (and the IOTask it closes over) in the pending chain of `done` until
  the run ends.
- Failure: a ZIO program that runs `handle.states.runForeach(...)` next to an
  app with `Source.every(16.millis)` keeps about 60 dead waiters per second:
  about 216 000 per hour, all retained until the app quits, then all run at
  once when `done` completes. `handle.await.timeout(100.millis)` in a polling
  loop leaks the same way (one waiter per timed-out await).
- Fix: do not park ZIO-driven Kyo waiters on a promise that lives as long as
  the run. One option: the per-handle watcher fiber follows the core SignalRef
  (exact watcher of SPEC 5.8, it is the only long-lived waiter) and publishes
  `(state, ended)` into a `zio.stream.SubscriptionRef`, and it completes a
  `zio.Promise` with the result. Then `states` =
  `ref.changes.chunks.map(_.last).takeUntil(_._2).map(_._1)` (newest value per
  pull, as `Source.signal` does) and `await` = `promise.await`; ZIO removes the
  waiter of an interrupted `await` or stream, so nothing stays behind.

### zio-runtime-3 (major): the live kit's settle waits for tasks that sleep on the caller's TestClock

- File: modules/zio/src/main/scala/tui4s/interop/zio/internal/ZioLive.scala:128-134
  (used by press, send, typeText and the start at :168).
- `settle` loops until `busy == 0`. Core's `tui4s.testkit.LiveTest.settle`
  (modules/core/src/main/scala/tui4s/testkit/LiveTest.scala:203-213) stops
  waiting for a task that is parked on the virtual clock (it races
  `awaitPendingSleepers(idle + busy)` against the next batch). The facade has no
  such case, but its doc (LiveTuiTest.scala:32-35) offers the caller's TestClock
  as the clock of the sources.
- Failure: under a TestClock, `update` starts `s.withTask(Source.For[S].task(
  ZIO.sleep(5.seconds).as(v))(...))` on 'r'. `t.press('r')` never returns: the
  task waits for `TestClock.adjust`, and the test cannot call it until `press`
  returns. The test hangs until its timeout (or for ever in the acquire, see
  zio-runtime-1). The same app works with core's kit and `advance`.
- Fix: give the facade kit the same rule as core: when the fiber's clock is a
  TestClock, count the parked sleepers (`TestClock.sleeps.map(_.size)`, read on
  the caller's runtime) and stop waiting when `sleepers + busy` are parked, or
  at least add a `drain` (fold and draw, no task wait) and document that `press`
  under a TestClock must not start a task that sleeps.

### zio-runtime-4 (minor): interrupting `TuiHandle.stop` kills the run instead of stopping it

- File: modules/zio/src/main/scala/tui4s/interop/zio/Tui.scala:148
- `stop` runs core `stop`, which puts `Stop` and then waits with
  `fiber.getResult` on the run fiber. In Kyo an interrupted waiter interrupts
  the fiber it waits on (the reason why `await` and `states` wait on `done`).
- Failure: `h.stop.timeout(50.millis)` while the release joins the input
  thread (up to 2 x pollMillis + 50 ms) or while the inbox is full: the timeout
  interrupts the Kyo waiter, the cascade interrupts the run fiber, and the run
  ends with `Panic(Interrupted)`. Then `h.await` dies with `kyo.Interrupted`
  instead of returning the last state, although `stop` says "like Quit".
- Fix: put `Stop` (as core does) and then wait on `run.done` (uninterruptible),
  not on the run fiber: `run.core.update`-style put of `Stop`, then
  `ZioRunner.await`-style `run.done.safe.get.unit`. Keep the release as it is.
