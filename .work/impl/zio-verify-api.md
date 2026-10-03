# zio facade API review: verification (skeptic pass)

Worktree: .work/worktrees/zio. Verdicts are added as each finding is checked.

## zio-api-1: CONFIRMED

- `ZioSources.task` (ZioSources.scala:48-53) forks the task ZIO with `AmbientZIOs.get` on the
  caller's runtime, so `ZIO.sleep` / `.delay` in it uses the caller's clock. Under ZIOSpecDefault
  that is the TestClock.
- `Supervisor.busyTasks` (core Supervisor.scala:186-191) counts every live generation of kind
  `Task` (standing or one-shot). A task parked on the TestClock stays live.
- `ZioLive.settle` (ZioLive.scala:128-134) loops while `busy != 0`, and
  `await(c.batches)` waits for a new batch that never comes. `press`/`send`/`typeText` call
  `settle`; `ZioLive.start` (line 168) calls it before the test body runs.
- So `t.press(...)` for a key that starts `task(ZIO.sleep(..))` never returns, and a standing
  `task` that sleeps in `sources(init)` blocks `LiveTuiTest(...)` before the body. The only escape
  is to fork the input call (or fork an adjuster before `LiveTuiTest`) - nothing in the scaladoc
  says so, and LiveTuiTest.scala:32-36 advertises TestClock support.
- Core does not have the problem: core `settleWith` (core LiveTest.scala:203-214) ends when every
  busy body is parked on the virtual clock (`awaitPendingSleepers`).
- Nuance: the finding says `.timeout` does not help; an inline `.timeout` runs on the TestClock,
  but `TestAspect.timeout` uses the live clock, so it turns the hang into a failure, not a pass.
  The core claim holds.

## zio-api-2: CONFIRMED (the gap is real; "no other way" is overstated)

- Core `TuiApp` has `protected final def args: Seq[String]` (core TuiApp.scala:35, 56, 65; SPEC 2.9
  line 637). The ZIO `TuiApp` (zio TuiApp.scala:32-47) has no `args`, and `run` is final, so
  `getArgs` cannot be used in `run`. `init` is pure. `def init = Doc.load(args.head)` does not
  compile with `import tui4s.interop.zio.*`.
- Correction: there is one other way. ZIOAppDefault's `bootstrap` is a non-final
  `val bootstrap: ZLayer[ZIOAppArgs, Any, Any]` (zio ZIOAppDefault.scala:43), and the facade does
  not override it, so a user can write
  `override val bootstrap = ZLayer.fromZIO(getArgs.map(a => argv = a))` and read `argv` in `init`.
  That is a workaround, not parity. SPEC 4.2 does not list `args` for the ZIO TuiApp, but it does
  not exclude it either; adding it does not contradict SPEC.

## zio-api-3: CONFIRMED

- Core `tui4s.testkit.LiveTest` is a public trait (core LiveTest.scala:18). The facade trait is
  `tui4s.interop.zio.testkit.LiveTest` (LiveTuiTest.scala:74). SPEC 4.4 tells ZIO users to
  `import tui4s.testkit.*` for TuiTest, and the facade kit needs `tui4s.interop.zio.testkit.*`;
  examples/src/test/scala/examples/ziocounter/CounterSuite.scala:4-6 has both wildcard imports.
- Two wildcard imports at the same level that bind different symbols make an unqualified
  `LiveTest` ambiguous in Scala 3, so `def addTwo(t: LiveTest[Int])` fails to compile there.
  The repo does not hit it today: no example names the type, and LiveTuiTestSuite is inside the
  package. Workaround: a named import (`import tui4s.interop.zio.testkit.LiveTest`).
- The re-export fix fits SPEC. The rename fix does not: SPEC 4.4 names the trait `LiveTest`.

## zio-api-4: REFUTED

- The upstream LICENSE.txt line is "Copyright 2015 Flavio Brasil" (kyo-LICENSE.txt:190). The
  header (AmbientZIOs.scala:6) says "Copyright 2015 Flavio Brasil and the Kyo contributors".
- That exact wording is what SPEC prescribes: SPEC 4.2 ("Copyright 2015 Flavio Brasil and the Kyo
  contributors, Apache-2.0") and SPEC 4.3 (the CatsBridge header, "keep its header verbatim",
  same sentence, "the copyright line is the one in the upstream LICENSE.txt"). The cats worktree
  uses the same text. The suggested fix contradicts the SPEC.
- No 4(c) failure: the upstream notice is retained in full (the header only adds credit), and
  the upstream source file has no per-file notice to retain. At most a wording nit to fix in
  SPEC and both headers together, not in this facade alone.

## zio-api-5: REFUTED

- The premise "TestClock.adjust does not wait for the woken fibers to run" is wrong for this
  setup. zio-test TestClock.run (2.1.24 sources; the TestClock$Test class is byte-identical in
  2.1.26) calls `awaitSuspended(5ms)` first and `awaitSuspended(2ms)` after it completes each
  due promise. With a hand-built testEnvironment the `fibers` annotation is `Left(0)`, so
  `freeze` returns an empty map, and `awaitSuspended` is two real `ClockLive.sleep(waitFor)`
  calls: about 4 ms of real time after each wake, also after the last one, before adjust
  returns.
- With the suggested break (cpu kept while paused), the poller is asleep when
  `TestClock.adjust(1.second)` runs, so it is woken at least once. It runs `Random.nextDouble`
  and `put` (`emit.unsafe.put` -> `inbox.unsafe.putFiber`), which take microseconds, well inside
  the 4 ms. The Fold is then in the inbox before `t.settle` puts its probe, and the probe is
  answered only after that Fold is folded, so `history` is 6 or more and `assertEquals(paused, 5)`
  fails. The check catches the bug in practice. It can give a false pass only if the woken fiber
  is delayed by more than about 4 ms (a very loaded machine), which is a narrow window, not
  "passes almost every time".

## zio-api-6: CONFIRMED (minor doc gap; the general caveat is already public)

- ZIO's live Console writes through `scala.Console.out` (zio Console.scala, `printLine(SConsole.out)`),
  and the default logger is `ZLogger.default.map(println(_))` (RuntimePlatformSpecific.scala:35).
- `scala.Console.out` keeps the stream that `System.out` was when `scala.Console` loaded. core
  OutputCapture.installStreams (OutputCapture.scala:58-76) replaces `System.out` and touches
  `scala.Console.out`, but that cannot rebind a Console that is already loaded.
- In `object Main extends ZIOAppDefault { def run = ZIO.logInfo("starting") *> Tui.run(app) }`
  the log line loads `scala.Console` bound to the real stdout. `Tui.run` (zio Tui.scala:50-54)
  removes the default loggers and starts the capture, but a later `Console.printLine` in a source
  writes to the real stdout, over the UI. `OutputCapture` is `private[tui4s]`, so a user cannot
  install it early; only the facade `TuiApp` constructor does (zio TuiApp.scala:34).
- Nuance: the public `TuiOptions.captureOutput` scaladoc (core Options.scala:26-29, exported by
  the facade) already says println is captured with `Tui.run` "only when nothing used
  `scala.Console` before the first run". The gap is that the facade Tui scaladoc does not repeat
  it and does not say that ZIO's Console and default logger use `scala.Console`.

