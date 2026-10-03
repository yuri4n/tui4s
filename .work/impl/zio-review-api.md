# zio facade review: public API and DX lens

Status: complete (6 findings: 1 major, 5 minor).

Checked and found good: the facade signatures name no Kyo type (manual read and KyoFreeFacadeSuite);
`import tui4s.interop.zio.*` next to `import zio.*` has no clash for the exported names; SPEC 8.3 tests
Z1-Z3 are all present (AmbientZIOsSuite, parity, ZioFacadeSuite with the three SPEC 6.3 tests, 10 000
element backpressure, stopped source interrupts its ZIO, TestClock drives poll/every, live kit, example
suites); the examples follow SPEC 3.4; the AmbientZIOs header lists its changes (Apache-2.0 4(b)).
Worktree: .work/worktrees/zio (feat/zio vs feat/core-3-terminal). Read-only review.

## zio-api-1 (major): the live kit hangs under zio-test's TestClock when a task sleeps

- File: modules/zio/src/main/scala/tui4s/interop/zio/internal/ZioLive.scala:128 (settle), :168 (start settles);
  docs at modules/zio/src/main/scala/tui4s/interop/zio/testkit/LiveTuiTest.scala:32-36, :91-95.
- `settle` loops until `busyTasks == 0`. `busyTasks` counts every running `task` body, standing or
  one-shot (core Supervisor.busyTasks). `start`, `press`, `send` and `typeText` all settle. The kit
  advertises "the caller's TestClock" (zio-test's default clock in every ZIOSpecDefault test).
- Failure: in a zio-test spec, `LiveTuiTest(app)(t => t.press('l') *> t.state)` where 'l' returns
  `s.withTask(source.task(load.delay(100.millis))(...))`. The task sleeps on the TestClock, so it never
  ends; `press` never returns; the test cannot call `TestClock.adjust`. The test hangs (zio-test only
  warns after 60 s). The same happens before the test body runs when `sources(init)` holds a task that
  sleeps or retries with a Schedule. A common case: a "saved" flash message cleared by
  `withTask(source.task(ZIO.sleep(2.seconds))(...))`. The advice of the `settle` scaladoc ("give the test
  a timeout") does not help: an inline `.timeout` also runs on the TestClock and never fires. The core kit
  does not have this trap: its settle does not wait for a body that waits on the virtual clock.
- Fix: say it in the scaladoc of `LiveTuiTest` and `settle` (under a TestClock, a task that sleeps keeps
  settle waiting; fork the input and adjust, or use the live clock in that task), and give the kit a way
  out: an option `LiveTuiTest(app, waitForTasks = false)` or input methods that do not settle.

## zio-api-2 (minor): the ZIO TuiApp gives no access to the command-line arguments

- File: modules/zio/src/main/scala/tui4s/interop/zio/TuiApp.scala:40.
- Kyo `tui4s.TuiApp` has `protected final def args: Seq[String]`. The ZIO `TuiApp` has none, and its
  `run` is final, so the user cannot call `getArgs` either. `init` is pure, so a ZIO user cannot start
  the app from an argument (a file to open, a host to watch).
- Failure: `object Viewer extends TuiApp[Doc]: def init = Doc.load(args.head)` compiles on Kyo, and has
  no equivalent on ZIO; the user must drop TuiApp and write a ZIOAppDefault with `Tui.run`.
- Fix: in `run`, `getArgs.flatMap(a => ZIO.succeed(arguments = a.toSeq)) *> Tui.run(this)...` with a
  `@volatile private var arguments` and `protected final def args: Seq[String]`, as core.

## zio-api-3 (minor): `LiveTest` is ambiguous with the two testkit imports that the docs ask for

- File: modules/zio/src/main/scala/tui4s/interop/zio/testkit/LiveTuiTest.scala:74.
- The facade trait is `tui4s.interop.zio.testkit.LiveTest`; core has `tui4s.testkit.LiveTest` (Kyo
  typed). A ZIO user needs both wildcard imports: `tui4s.testkit.*` for `TuiTest`, `Screen`,
  `diffScreen` (LiveTest.screen returns `tui4s.testkit.Screen`), and the facade kit for `LiveTuiTest`.
  The example suites do exactly this (examples/.../ziocounter/CounterSuite.scala:4-6).
- Failure: a helper `def addTwo(t: LiveTest[Int]): UIO[Unit] = t.press('+', '+')` in such a file fails:
  "Reference to LiveTest is ambiguous. It is both imported by ... and imported subsequently by ...".
- Fix: let `import tui4s.interop.zio.testkit.*` be the only testkit import (re-export
  `tui4s.testkit.{TuiTest, Screen, diffScreen, assertScreen, ...}` from it, and say so in the scaladoc),
  or name the facade trait differently (for example `ZioLiveTest`).

## zio-api-4 (minor): the attribution header misquotes the upstream copyright line

- File: modules/zio/src/main/scala/tui4s/interop/zio/internal/AmbientZIOs.scala:6-9.
- The header says "Copyright 2015 Flavio Brasil and the Kyo contributors" and "the copyright line is
  the one in the upstream LICENSE.txt". The Kyo LICENSE.txt (.work/scratch/kyo-rc7/kyo-LICENSE.txt:190)
  says only "Copyright 2015 Flavio Brasil".
- Failure: Apache-2.0 4(c) asks to retain the upstream notices; the header claims to quote a line that
  it changes. A license audit finds a notice that does not match the source.
- Fix: quote it exactly ("Copyright 2015 Flavio Brasil", from LICENSE.txt), and keep "and the Kyo
  contributors" only as a separate description if wanted.

## zio-api-5 (minor): the Dashboard's "none while paused" check cannot fail

- File: examples/src/test/scala/examples/ziodashboard/DashboardSuite.scala:71-82.
- After `press('p')`, the test does `TestClock.adjust(1.second)` then `t.settle` and reads `history`.
  `TestClock.adjust` only completes the sleepers' promises; it does not wait for the woken fiber to
  run. `settle` waits only for items put before it. So a poller that still ran after the pause would put
  its sample after the probe, and `paused == 5` still holds.
- Failure: break the pause (make `sources` ignore `d.paused`): the live test still passes (a race),
  so the test does not show that the ZIO poller stops on the TestClock.
- Fix: after the adjust, wait (real time) until a new log line is folded (the fibers that the adjust
  woke have run), give a short real-time grace (for example 200 ms), settle, then read `history`.
  Note: `TestClock.sleeps` keeps the entry of an interrupted sleeper (zio-test's TestClock.sleep
  registers it and nothing removes it on interrupt), so "one sleeper left" cannot show that the poller
  stopped.

## zio-api-6 (minor): `Tui.run` does not say that ZIO's Console and `println` can escape the capture

- File: modules/zio/src/main/scala/tui4s/interop/zio/Tui.scala:26-31.
- The scaladoc says that with `captureOutput` "System.out and System.err go to the log". ZIO's
  `Console.printLine` and the default logger write through `scala.Console.out`, which keeps the stream
  that `System.out` was when `scala.Console` first loaded (core OutputCapture.scala:15-19). `TuiApp`
  installs the forwarders in its constructor, but a program with its own `ZIOAppDefault` cannot (the
  installer is `private[tui4s]`).
- Failure: `def run = ZIO.logInfo("starting") *> Tui.run(app)` (the log loads `scala.Console` bound to
  the real stdout), and a source does `Console.printLine("loaded")`: the line is drawn over the UI.
- Fix: say it in the `Tui.run` scaladoc (use `TuiApp`, or print nothing through `Console`/`println` before
  the first run), or expose a facade call that installs the forwarders early (for example
  `Tui.installOutputCapture: UIO[Unit]` for the first line of `run`).

