# zio facade: verify runtime findings (skeptic pass)

Started 2026-10-01. Verdicts are added as each finding is checked.

## zio-runtime-1: confirmed (one sub-claim is wrong)

Traced:
- `LiveTuiTest.apply` (LiveTuiTest.scala:47-49) runs `AmbientZIOs.run(ZioLive.start(...))` as the acquire of
  `ZIO.acquireReleaseWith`. ZIO 2.1.26 `ReleaseExit.apply` runs the acquire inside `uninterruptibleMask`
  (ZIO.scala:5690), outside `restore`.
- `ZioLive.start` ends with `.map(run => run.settle.andThen(run))` (ZioLive.scala:168).
- kyo-zio RC7 `ZIOs.run` (ZIOs.scala:61-89) is `uninterruptibleMask { restore => ... restore(join) }`. In the acquire,
  `restore` gives back "uninterruptible", so the join cannot be interrupted.
- `settle` (ZioLive.scala:128-134) loops while `c.busy != 0`. `busy` = `Supervisor.busyTasks` (Supervisor.scala:186),
  which counts every live generation of `Kind.Task`, standing or one-shot. The facade `Source.task` is
  `Sources.makeTask` -> `Kind.Task`. So a standing task in `sources(init)` keeps the first settle waiting.
- ZIO `timeoutTo` (ZIO.scala:5645-5665) calls `loser.interruptAs(parentFiberId)`, which is
  `interruptAsFork *> await` (Fiber.scala:179). So `LiveTuiTest(app)(...).timeout(d)` waits for the acquire.
- TestClock: `AmbientZIOs.run` captures the caller's runtime (with the TestClock), and the source's ZIO forks on it.
  `ZIO.sleep` then waits for `TestClock.adjust`, which only the body could call, and the body never starts.

Wrong sub-claim: "TestAspect.timeout cannot end it either". zio-test 2.1.x `TestAspect.timeout` is
`test.either.disconnect.timeout(duration)` (TestAspect.scala:1147), so it reports a timeout and leaves the stuck
fiber behind. munit's own test timeout also ends the test. The hang is of `ZIO#timeout` and of the app fibers.

Not against SPEC: 6.2 says settle "repeats while a one-shot task is running" (the implementation also waits for
standing tasks, which is wider than SPEC), and 2.10/4.4 do not ask for a settle in the acquire.

## zio-runtime-2: confirmed

Traced (kyo-core 1.0.0-RC7 sources from the Coursier cache):
- `nextState` (ZioRunner.scala:103-105) runs `Async.race(next.safe.get.unit, run.done.safe.get.unit)` when the
  state has not changed. `Fiber.internal.Race` (Fiber.scala:888-904) forks one IOTask per racer and interrupts the
  losers when the race completes.
- The racer parks on `done`: `useResult` registers `task.interrupts(v)` (Async.scala:829-834); the boundary parks
  (`parkOn`, IOTask.scala:~100); `run` arms `promise.onComplete { _ => removeInterrupt(promise);
  Scheduler.get.schedule(this) }` on `done` (IOTask.scala:303-306). The closure holds the IOTask.
- `IOPromise.Pending` has no way to remove an `onComplete` node: `removeInterrupt` rebuilds the chain and keeps every
  `onComplete` (IOPromise.scala:337-338, 390). `done` is `initUninterruptible` (`preInterrupt() = false`,
  Fiber.scala:689-691), so the cascade from the interrupted racer does nothing to it.
- After the interrupt, `abandon` clears the task's `curr`, but the node, the closure and the IOTask (its fields,
  the racer body, the captured isolate state) stay reachable from `done` until `done` completes at the end of the
  run. So each pull that waits leaks one node.
- `TuiHandle.await` (Tui.scala:144-145) -> `run.done.safe.get` in a Kyo fiber from `ZIOs.run`. A ZIO timeout runs
  `interruptAndAwait`, the Kyo fiber is abandoned, and its node stays on `done` the same way.
- No stack risk: `flush` is iterative, and nothing calls `removeInterrupt` or `interrupt` on `done` with effect.
  The issue is linear heap growth (roughly a few hundred bytes per waiting pull) for the life of the run.
- SPEC 4.2 only says `states` = `ZStream.repeatZIO(core.states.next)`. It does not ask for the wait on `done`, so
  the finding does not go against SPEC.

## zio-runtime-3: confirmed (lower the severity: minor to moderate)

Traced:
- `ZioLive.settle` (ZioLive.scala:128-134) waits until `busy == 0` (no sleeper rule). `press` / `send` /
  `typeText` call it.
- `update` returns `withTask(Source.For[S].task(ZIO.sleep(5.seconds).as(v))(...))`. The task is `Kind.Task` and
  counts in `busyTasks`. Its ZIO forks on the runtime that `AmbientZIOs.run` captured at start (the Kyo `Local` is
  inherited by the render fiber and then by the task fiber), so under `testEnvironment` it sleeps on the
  TestClock. No batch comes, `await(c.batches)` does not complete, and `press('r')` does not return in sequential
  test code.
- Core's kit has the rule (LiveTest.scala:203-213: `parkedOrNews(idle + busy)`). It came from the core-2 review
  for the same hang under virtual time (core-2-fix.md, "A task parked on the virtual clock counts as idle").
- The facade doc (LiveTuiTest.scala:32-35) and zio-impl.md ("real clock or the caller's TestClock") offer the
  caller's TestClock. zio-test's `ZIOSpecDefault` uses a TestClock by default.

Why the severity is lower: SPEC 4.4 gives the facade kits "the real clock", and SPEC 6.2 says settle waits while
a task runs ("give the leaf a timeout"). So the gap is in TestClock support that the implementation added beyond
SPEC. A test can also fork `press` and call `TestClock.adjust`. "Never returns" is true only for sequential test
code. The minimum fix is a doc line. The sleeper rule (through `TestClock.sleeps`) is optional.

## zio-runtime-4: confirmed

Traced:
- `TuiHandle.stop` (Tui.scala:148) = `AmbientZIOs.run(run.core.stop)`. Nothing makes it uninterruptible.
- Core `stop` (core Tui.scala:105-106) = `inbox.put(Stop)` then `fiber.getResult` on the run fiber. The run fiber
  is a plain `Fiber.initUnscoped` IOTask (RenderLoop.scala:~310), so interrupts can reach it.
- `getResult` -> `useResult` registers `task.interrupts(v)` on the waiting task (Async.scala:829-834). A ZIO
  timeout -> `interruptAndAwait` -> Kyo `interrupt()` -> `abandon` -> `settleInterrupt` -> `flushInterrupt`
  reaches the `interrupts` node -> `runFiber.interrupt(Panic(Interrupted))`. The run fiber is not done yet
  (release, input-thread join), so `preInterrupt` is true and the interrupt lands. An interrupt taken on the slice
  wins over the value (IOTask.scala:125-131), so the result is `Panic(Interrupted)`. The watcher then completes
  `done` with it, and `ZioRunner.await` -> `Abort.get` -> panic -> `Exit.die(Interrupted)`.
- The terminal is still restored: abandon runs the owed releases.
- Only the `getResult` phase cascades. While `put(Stop)` waits on a full inbox, the cascade goes to the channel
  promise, and the stop is only cancelled.
- The `Tui.start` release (Tui.scala:97) runs in ZIO's uninterruptible release, and `ZIOs.run`'s `restore` keeps
  it uninterruptible, so that path is safe, as the finding says.
- Not against SPEC: the facade doc says stop works "like Quit", and await is documented as interrupt-safe.
