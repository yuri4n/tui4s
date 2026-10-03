# zio facade review: correctness and SPEC conformance

Lens: SPEC.md sections 4, 5 and 6. Bridge, sources, runner, app class, tests.
Status: done. 6 findings (2 major, 4 minor). Read-only review; no build was run.

## Findings

### zio-spec-1 (major): TuiHandle.states and an interrupted await leak one waiter on `done` each time

- File: modules/zio/src/main/scala/tui4s/interop/zio/internal/ZioRunner.scala:103-105 (and :68-69)
- `nextState` waits with `Async.race(next.safe.get.unit, run.done.safe.get.unit)`. `done` is an
  uninterruptible promise that completes only when the run ends. In Kyo RC7 a parked fiber arms
  `promise.onComplete { removeInterrupt; schedule(this) }` on the promise it waits on
  (IOTask.scala ~304); an interrupt of the waiter does not remove that callback, and an
  uninterruptible promise refuses the interrupt. `Race.success` interrupts the loser.
- Scenario: `Tui.start(app)` with a consumer `h.states.foreach(...)` that keeps up. Each state change
  wakes the race through `next`; the loser fiber that waited on `done` is interrupted, and its
  callback (closure + IOTask + Pending node) stays on `done` until the app ends. A Dashboard with
  ~5 changes/s leaks ~400k entries per day. The same for each interrupted `h.await`
  (`h.await.timeout(1.second)` in a loop: one entry per timeout).
- Fix: do not wait on `done` per change. For example, keep a facade `SignalRef[(S, Boolean)]` (or
  an epoch counter) that the observer sets on each change and the watcher sets to `(final, true)`
  at the end (always a change), and wait only on its `next` promise, which is replaced at each
  change. For `await`, complete a `zio.Promise[TuiError, S]` from the watcher: ZIO removes an
  interrupted joiner.

### zio-spec-2 (major): the live kit's settle hangs on a task that sleeps on a TestClock, and the first settle cannot be interrupted

- Files: modules/zio/src/main/scala/tui4s/interop/zio/internal/ZioLive.scala:128-134 and :165-168;
  modules/zio/src/main/scala/tui4s/interop/zio/testkit/LiveTuiTest.scala:47-49
- `ZioLive.settle` loops while `busy > 0` (a running `task` body) and waits for the task to END. The
  kit documents TestClock use, but under a TestClock a task that sleeps (`.delay`, `ZIO.sleep`,
  `Schedule`, `timeout`) never ends while the test fiber is blocked in `press`/`send`/`typeText`/
  `settle`: the test cannot call `TestClock.adjust`. Core's kit does not have this problem: its
  `settleWith` stops when every running body is parked on the clock (core LiveTest.scala:203-214).
- Worse, `ZioLive.start` does `run.settle` inside the acquire of `ZIO.acquireReleaseWith`, which is
  uninterruptible (and `ZIOs.run` restores that). An app whose first state declares a standing task
  that does not end (a slow or hung load, or any sleeping task under a TestClock) makes
  `LiveTuiTest(app)(...)` hang; `.timeout(...)` and fiber interrupt cannot stop it. This contradicts
  the kit's own doc "Interrupting the test stops the app". Core settles inside `Sync.ensure`, in the
  interruptible part.
- Scenario: `LiveTuiTest(App[String](init = "", ..., sources = s => if s.isEmpty then
  Seq(Source.For[String].task(ZIO.succeed("x").delay(1.second))((_, v) => v)) else Nil))(t => ...)`
  inside `testEnvironment.build`: hangs forever before the test body runs.
- Fix: move the first settle out of the acquire (run it at the start of `use`), and make settle stop
  waiting for tasks that are suspended on the caller's TestClock (for example: stop when no new batch
  arrives and `TestClock.sleeps` is non-empty, or count tasks as settled once they are parked, as core
  does), or document that `settle` under a TestClock does not wait for tasks.

### zio-spec-3 (minor): Tui.start removes ZIO's default loggers before the acquire, also when the start fails

- File: modules/zio/src/main/scala/tui4s/interop/zio/Tui.scala:64-69 (and startWith :86-88)
- `removeDefaultLoggers *> acquire(...)` registers the logger removal on the caller's Scope before the
  app starts. When the acquire fails (`NotATerminal`, `init` threw), no UI ever came up, but the
  removal stays until the caller's scope ends. The same after the app quit: the loggers come back
  only at the scope end, not at the restore that the object's Scaladoc names.
- Scenario: `ZIO.scoped(Tui.start(app).flatMap(_.await).catchAll(e => ZIO.logError(s"no UI: $e") *>
  plainCli))` run without a terminal: the `logError` line and every log of `plainCli` are lost (no
  UI, no capture), and the user sees nothing.
- Fix: remove the loggers only when the acquire succeeds, for example acquire into a child scope and
  close it on failure (`acquire(...).tapErrorCause(_ => restore)`), or restore the loggers in a
  finalizer that the watcher triggers when the run ends.

### zio-spec-4 (minor): two facade tests do not test what their names claim

- File: modules/zio/src/test/scala/tui4s/interop/zio/ZioFacadeSuite.scala:356-385 and :387-413
- "every and signal fold on the caller's runtime" checks only that `every` ticked twice and that
  `signal` delivered 3. Nothing reads a FiberRef, the executor or the clock: the test passes with
  `every` on Kyo's `Async.sleep` or with a ZIO forked on `Runtime.default`.
- "signal folds the newest value; an equal write folds nothing" asserts `folds <= 1001`. The writer
  publishes `i, i` pairs much faster than the source puts into the inbox, so each pull takes many
  elements and `c.last` already collapses the pairs: the bound holds also without `.changes`
  (ZioSources.scala:78). The equal-skip is not tested.
- Fix: in the first test, run under `FiberRef.locally` (or a TestClock, real time not moving it) and
  fold the FiberRef value from the `every` tick. In the second, set a value, wait until it is folded,
  set the same value again, then a new one, and assert that the fold count of the repeated value is 1.

### zio-spec-5 (minor): an interrupted TuiHandle.stop interrupts the app; await then dies with kyo.Interrupted

- File: modules/zio/src/main/scala/tui4s/interop/zio/Tui.scala:148 (core TuiHandle.stop: Tui.scala:105-106 of core)
- The implementer fixed the Kyo interrupt cascade for `await` and `states`, but `stop` still runs
  core `stop` = `inbox.put(Stop).andThen(fiber.getResult)` in a Kyo fiber. A ZIO interrupt of `stop`
  interrupts that Kyo fiber, and the cascade interrupts the app's run fiber that it waits on.
- Scenario: `h.stop.timeout(50.millis)` while the render fiber is still busy with a batch (Stop not
  yet folded): the app ends by interrupt, not "like Quit". Its result becomes
  `Result.Panic(Interrupted)`, so `h.await` dies with a `kyo.Interrupted` defect (a Kyo type in the
  user's Cause) instead of returning the last state.
- Fix: in the facade, put `Stop` (core `update`-like put) and then wait on the handle's own `done`
  promise (as `await` does), not on the run fiber; a timed-out `stop` then leaves the app quitting
  normally.

### zio-spec-6 (minor): with Tui.run from the user's own ZIOApp, zio.Console output from sources is not captured

- File: modules/zio/src/main/scala/tui4s/interop/zio/Tui.scala:26-31 and :50-54
- `zio.Console.printLine` and `ZIO.debug` write to `scala.Console.out` (zio Console.scala:90-112),
  which keeps the stream that `System.out` was when `scala.Console` loaded. Core's capture replaces
  `System.out` only; `TuiApp` binds `scala.Console` early with `OutputCapture.install()`, but a
  program that calls `Tui.run` from its own `ZIOAppDefault` (the use the Scaladoc names) has usually
  printed or logged before (`Console.printLine("starting")`, `ZIO.logInfo`), so `scala.Console` is
  bound to the real stdout. The Scaladoc still says that output goes to the log file or memory.
- Scenario: `def run = Console.printLine("starting") *> Tui.run(app)` and a source that does
  `Console.printLine(s"got $x")`: the line is drawn over the UI (Q2 says capture is on by default).
- Fix: in `quietly` (and `Tui.start`), also replace ZIO's Console service for the run with one that
  writes to `java.lang.System.out` / `err` at call time (`ZIO.withConsoleScoped(...)`), so the
  captured runtime that the sources use prints through the capture; or state the limit in the
  Scaladoc of `Tui.run` and `Tui.start`.

## Checked and found correct (no finding)

- AmbientZIOs: values, typed errors (Abort[E] <-> ZIO E), defects (panic <-> die), ZIO interrupt of
  `run` waits for the Kyo fiber (kyo-zio `ZIOs.run` interruptAndAwait), Kyo interrupt of `get`
  interrupts the ZIO in the `Sync.ensure` finalizer, the fork/record step is one Sync step (no lost
  interrupt), and the `.interruptible` fork for a runtime captured in a bracket acquire.
- Sources: every/poll/task/stream lower to the right `Sources.make*` kind; `put` gives inbox
  backpressure; a stopped source's late value is dropped by its token; zio.Chunk fits the chunked
  fold (Engine folds any Iterable).
- Tui.run: typed `IO[TuiError, S]`, restore before the interrupt completes, scheduler defaults first.
- Tui.start: release is ZIO's bracket and waits on `core.stop`; typed start failure.
- TuiApp: scheduler defaults and capture install in the constructor; ZIOApp's hook interrupts `run`;
  `interruptRootFibers` also stops source ZIO fibers at the end.
- Exports match SPEC 4.1; KyoFreeFacadeSuite visits the package objects of the facade.
