# zio facade: verification of spec-review findings

Skeptic pass over zio-spec-1 .. zio-spec-6. Worktree: .work/worktrees/zio.
Verdicts are added as each finding is checked.

## zio-spec-1: CONFIRMED (major)

Traced in Kyo RC7 sources (.work/scratch/kyo-src/kyo-core):
- `Async.useResult` (Async.scala:828) registers `task.interrupts(v)` on the waiter; IOTask.run (IOTask.scala:303-306)
  arms `promise.onComplete { removeInterrupt; schedule(this) }` on the awaited promise when it parks.
- `IOPromise.Pending.onComplete` nodes are removed only by `run`/`interrupt` of the promise; `removeInterrupt`
  rebuilds them (IOPromise.scala:331-342). No other removal path.
- `done` is `Promise.Unsafe.initUninterruptible` (ZioRunner.scala:56): `preInterrupt() = false`, so the cascade from an
  interrupted waiter is refused and `done` stays Pending with the node.
- `Fiber.internal.Race` (Fiber.scala ~900) interrupts every loser on completion. In `nextState` (ZioRunner.scala:103-105)
  a caught-up consumer parks both racers; `next` wins on each change, the `done` racer is interrupted after it parked,
  and its node stays on `done` until the run ends. `ZioRunner.await` (line 68-69) leaks the same way per interrupted wait.
- Nothing in SPEC 4.2 / section 9 contradicts this.

## zio-spec-2: CONFIRMED (the uninterruptible first settle); the TestClock half is per SPEC

- `ZioLive.start` (ZioLive.scala:168) runs `run.settle` inside the start; `LiveTuiTest.apply` (LiveTuiTest.scala:47-49)
  runs that start as the acquire of `ZIO.acquireReleaseWith`, which ZIO runs uninterruptibly.
- `AmbientZIOs.run` -> kyo-zio `ZIOs.run` = `ZIO.uninterruptibleMask(restore => ... restore(join).onInterrupt(...))`
  (kyo-zio ZIOs.scala:61-89): `restore` gives back the acquire's uninterruptible status, so the join cannot be interrupted.
- `settle` (ZioLive.scala:128-134) loops while `busy > 0` (Supervisor.busyTasks counts Kind.Task bodies) and waits on a
  probe for a batch after `c.batches`; with a task that never yields a value no batch comes, so the start never returns.
- So an init `task` that does not end (TestClock `.delay`, or a hung load in real time) hangs LiveTuiTest before the body,
  and an outer `.timeout` (ZIO waits for the interrupted loser) cannot stop it. This contradicts SPEC 6.2's remedy
  ("give the leaf a timeout") and the kit's own doc ("Interrupting the test stops the app").
- Not a defect by itself: settle waiting for a TestClock-sleeping task later in the body. SPEC 6.2 says settle repeats
  while a one-shot task runs, SPEC 4.4 defines the facade kit on the real clock, and the settle Scaladoc says so.
  The body can fork the input and adjust the clock. Core's "parked on the clock" rule is for core's virtual clock.

## zio-spec-3: CONFIRMED (minor)

- `Tui.start` (Tui.scala:67-68) and `startWith` (87-88) run `removeDefaultLoggers *> acquire(...)` in the caller's
  `Scope`. `Runtime.removeDefaultLoggers` is `ZLayer.scoped(FiberRef.currentLoggers.locallyScopedWith(...))`
  (zio 2.1.26 Runtime.scala:295): the change stays on the calling fiber until that scope closes.
- When the acquire fails (NotATerminal, init threw), no UI exists, but a `catchAll` inside the same `ZIO.scoped`
  runs with the default loggers still removed, so its `ZIO.log*` lines are lost. `Tui.run` does not have this
  problem: `quietly` wraps the removal in its own `ZIO.scoped`.
- SPEC 4.2 does not ask `start` to remove loggers at all, so nothing in SPEC requires this behaviour.

## zio-spec-4: CONFIRMED (minor, test quality)

- ZioFacadeSuite.scala:356-385 asserts only `value == 3` and `ticks >= 2`. It reads no FiberRef, executor or clock,
  so `every` on Kyo `Async.sleep` / `Runtime.default` passes it. (TestClockSuite "TestClock drives every" does
  catch that regression, so the gap is in this test's name, not in total coverage of `every`.)
- ZioFacadeSuite.scala:387-413 asserts `folds <= 1001`. zio 2.1.26 `SubscriptionRef.set` publishes every write,
  equal or not, so without `.changes` the most is 2001. The writer starts right after `.fork`, before the app has
  subscribed, and `chunks.collect(c.last)` collapses each pulled chunk. So the bound does not isolate the
  equal-skip of ZioSources.scala:78. Without running it, "stays green" is very likely but not proven. Either way,
  the test does not check the skip reliably.

## zio-spec-5: CONFIRMED (minor)

- Facade `stop` = `AmbientZIOs.run(run.core.stop)` (Tui.scala:148); core `stop` =
  `Abort.run[Closed](inbox.put(Stop)).andThen(fiber.getResult)` (core Tui.scala:105-106). `getResult` ->
  `Async.useResult` registers `task.interrupts(appFiber)` on the waiting Kyo fiber.
- ZIO interrupt -> kyo-zio `ZIOs.run` `interruptAndAwait` -> `fiber.unsafe.interrupt()` -> IOTask.abandon ->
  settleInterrupt -> flushInterrupt -> the `interrupts` node interrupts the app's run fiber
  (`Fiber.initUnscoped(runOn(...))`, an ordinary interruptible IOTask, RenderLoop.scala:310).
- The run fiber then ends with `Panic(Interrupted)`. The watcher stores it in `done`, and `ZioRunner.await` ->
  `Abort.get` -> panic -> `ZIOs.run` maps it to `Exit.die(kyo.Interrupted)`.
- The scope release is uninterruptible, so only a user-level interrupt of `h.stop` (timeout, race) reaches this
  path. That is consistent with the finding's "minor".

## zio-spec-6: CONFIRMED (minor; partly documented in core)

- zio 2.1.26 `ConsoleLive.printLine` writes to `scala.Console.out` (Console.scala:95-96, through `SConsole.withOut`).
  `scala.Console.out` is a DynamicVariable initialised from `System.out` when `scala.Console` loads.
  `OutputCapture.start` only does `System.setOut` (OutputCapture.scala:71-75), so it cannot rebind a
  `scala.Console` that is already loaded.
- With `Console.printLine("starting") *> Tui.run(app)`, `scala.Console` binds to the real stdout. The sources run
  on the captured caller runtime with `ConsoleLive`, so a source's `Console.printLine` draws over the UI.
- Mitigation: core's `TuiOptions.captureOutput` doc (exported to the facade) and the OutputCapture header say that
  "Scala's println" is captured with `Tui.run` only when nothing used `scala.Console` before. But the facade's Tui
  Scaladoc (lines 26-31) and SPEC 4.2 ("the capture catches the rest") do not say that zio.Console is
  `scala.Console`. The facade owns the runtime that the sources use, so it could swap the Console service.
