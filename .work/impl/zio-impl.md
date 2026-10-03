# zio: the ZIO facade (Z1 + Z2 + Z3) - implementation notes

Worktree: .work/worktrees/zio, branch feat/zio (from feat/core-3-terminal 666a8e3).
Agent: implementer. Started 2026-10-01.

## Plan

Package `tui4s.interop.zio` (user decision Q1), files under modules/zio/src/main/scala/tui4s/interop/zio/.

### Z1 exports and bridge
- exports.scala: `export tui4s.dsl.*`, `export tui4s.{App, Next, Quit, Event, Key, ...}`, `type Source[S]`.
- internal/AmbientZIOs.scala: inheritable kyo.Local[Maybe[zio.Runtime[Any]]]; run / get / getOn (Sync.ensure
  cancel region); header crediting kyo-zio (Apache-2.0, Copyright 2015 Flavio Brasil and the Kyo contributors).
- modules/zio/build.sbt: tasty-query (Test), core's src/surface/scala dir, munit, Test / fork, zio-test (Test,
  for the TestClock question).
- Tests: AmbientZIOsSuite (values, typed errors, defects, ZIO->Kyo and Kyo->ZIO interruption, caller FiberRef +
  executor, from a forked Kyo fiber, 500-round interrupt race); KyoFreeFacadeSuite (SurfaceCheck on
  tui4s.interop.zio + .testkit); parity: examples/zio.sbt adds `FacadeParity.ecosystems += "zio" -> ...`,
  ParitySuite expects "zio" (one-line edit of a shared file: deviation).

### Z2 sources, runner, app
- Source / Source.For: every, poll, task, stream (ZStream), signal (zio.stream.SubscriptionRef).
  every sleeps with ZIO.sleep on the caller's runtime (SPEC 4.4 "facade sources sleep on the caller's clock";
  the 4.2 table said Kyo Async.sleep: deviation, so TestClock drives every and poll alike).
- Tui.run (SchedulerDefaults + AmbientZIOs.run(core Tui.run)), Tui.start (ZIO.acquireRelease over an unscoped
  real-terminal start composed in the facade: no core edit), private[tui4s] runWith / startWith seams.
- TuiHandle: update/send/states/stats/await/stop with (using Trace); states = current state, then each change
  (exact SignalRef protocol), ends after the final state.
- TuiApp extends ZIOAppDefault, tui4s.App[S]: constructor applies SchedulerDefaults + OutputCapture.install;
  default loggers removed only while the UI is up (scoped around Tui.run in `run`), so ZIOApp still reports a
  failure after the restore (with bootstrap removal a failed run printed nothing).
- Tests: ZioFacadeSuite (SPEC 6.3), backpressure 10 000 elements / inbox 16, stopped source interrupts its ZIO
  (onInterrupt), TestClock drives poll (record the answer), handle, TuiApp seam.

### Z3 examples and live kit
- tui4s.interop.zio.testkit.LiveTuiTest + LiveTest (press/send/typeText/settle/state/screen/stats/sources/quit)
  on RecordingControl via Runner.startOrFail through AmbientZIOs (real clock, fps 0).
- examples/ziocounter, examples/ziodashboard (SPEC 3.4) + munit suites (sync TuiTest + LiveTuiTest).

## Progress
- 20:49 main sources written; `sbt -batch zio/compile` green, 0 warnings (log .work/logs/zio-compile-1.log):
  exports.scala, Source.scala, Tui.scala (Tui + TuiHandle), TuiApp.scala, internal/{AmbientZIOs, ZioSources,
  ZioRunner}. Design points found while writing:
  * ZIO.runtime captures the runtime FLAGS too: a runtime captured inside a ZIO bracket's acquire (Tui.start)
    has interruption off, so `rt.unsafe.fork` would make every ZIO source uninterruptible (a stopped source
    would never stop). getOn forks `ZIO.suspendSucceed(v).interruptible`.
  * Kyo interrupt cascade: a Kyo fiber that waits on another fiber and is interrupted interrupts what it waits
    on. So a ZIO `await` that times out (AmbientZIOs.run(core.await)) would stop the app, and racing
    `core.result` in the states stream would kill the app at the first change. The facade handle waits on its
    own uninterruptible promise (ZioRun.done), completed by one watcher fiber per handle. SignalRef `next`
    promises are already uninterruptible (Signal.scala).
  * Tui.run / Tui.start remove ZIO's default loggers while the UI is up (scoped, on the calling fiber; sources
    inherit it through the captured runtime). TuiApp keeps ZIOAppDefault's bootstrap, so a failure is logged by
    ZIOApp after the restore (SPEC's bootstrap = removeDefaultLoggers made a failed run print nothing).
  * Tui.start fails with TuiError (typed) instead of a defect (core needs a panic only for its Kyo signature).
- 21:04 zio/test: 35 of 35 green after 3 fixes (test bug: App.apply's `init` is by-value; the testkit package
  did not exist yet; the backpressure bound). Recorded: TestClock DRIVES poll and every (SPEC 4.2 UNVERIFIED
  item answered: yes); one adjust(1 s) of a 250 ms poller gave 4 samples in each run seen (the suite accepts
  1..4: re-arming is a race, as Kyo V8). Race: 59-89 of 500 ZIOs had started at the interrupt, 0 leaked.
  Backpressure: the stream ran at most 33-38 elements ahead of the folds with inbox 16 (batch being folded +
  inbox + waiting puts), never the whole stream.
- 21:19 live kit (internal/ZioLive + testkit/LiveTuiTest) + LiveTuiTestSuite (9), examples ziocounter /
  ziodashboard + suites, examples/zio.sbt (parity entry + zio-test at the zio module's ZIO version).
  Fix found by a flaky leaf: the kit's release must interrupt AND await the app (it only signalled).
  zio/test 44/44, examples/test 25/25 (log .work/logs/zio-test-5.log).
  Shared-file changes: ParitySuite (Set("kyo", "zio")), root build.sbt line 110: launcher name = the example's
  package (examples.ziocounter.CounterApp wrote bin/counter over the Kyo Counter that ptycheck runs).
- 21:30 fmt; COMMITS 8641f9e (Z1), a13b265 (Z2), dfe95fd (Z3 kit), c8c762a (Z3 examples). Next: full check.
- 21:30 `sbt -batch check` GREEN (log .work/logs/zio-check-1.log).
- 21:40 real pseudo-terminal check (expect script .work/logs/zio-pty.exp, results zio-pty-3.log): ZIO Counter
  draws, + + - q -> exit 0; stdin /dev/null -> ZIOApp's ERROR log with "TuiError$NotATerminal: stdin/stdout is
  not a terminal", exit 1; ZIO Dashboard: log stream and pause drawn, SIGTERM -> exit 143 (ZIO's hook interrupts
  run, the release restores), q -> exit 0; the tty is in cooked mode after each. Note for pty scripts: jatatui
  writes only changed cells with cursor moves, so match short contiguous text ("/api/items/", "(paused)").
- 21:44 TuiAppSuite (scheduler defaults in the constructor; run without a terminal -> NotATerminal); dropped the
  unused AmbientZIOs.current. COMMIT 0c8c00d. Final `sbt -batch fmt` (no change) + `sbt -batch check` GREEN:
  core 32 kyo-test suites 0 failed, zio 46/46, examples 25/25, versionPolicyCheck ok (zio-check-final.log). DONE.

## Commits on feat/zio
- 8641f9e zio: the facade's exports and the AmbientZIOs bridge (Z1)
- a13b265 zio: sources, Tui.run, Tui.start, TuiHandle and TuiApp (Z2)
- dfe95fd zio: the live test kit tui4s.interop.zio.testkit.LiveTuiTest (Z3)
- c8c762a examples: the ZIO Counter and Dashboard with munit tests (Z3)
- 0c8c00d test(zio): TuiApp sets the scheduler defaults and fails without a terminal

## Deviations from SPEC
- Sources live under modules/zio/src/main/scala/tui4s/interop/zio/ (package tui4s.interop.zio, Q1); SPEC 4.1/4.2
  still name tui4s/zio/ paths.
- `every` sleeps with ZIO.sleep on the caller's runtime (SPEC 4.2 table: Kyo Async.sleep, "no ZIO involved"), so
  a TestClock drives every and poll alike (SPEC 4.4: facade sources sleep on the caller's clock).
- `Source.signal(ref: zio.stream.SubscriptionRef[A])` added (not in SPEC 4.2): current value, then the newest
  value of each pull; an equal value is skipped.
- AmbientZIOs forks `ZIO.suspendSucceed(v).interruptible`: ZIO.runtime captures the runtime flags, and a runtime
  taken in a bracket's acquire (Tui.start, LiveTuiTest) would make every source uninterruptible.
- Tui.start: ZIO[Scope, TuiError, TuiHandle[S]] (typed failure) instead of ZIO[Scope, Nothing, ...] (defect).
- Logging: Tui.run and Tui.start remove ZIO's default loggers while the UI is up (scoped on the calling fiber;
  sources inherit it). TuiApp keeps ZIOAppDefault's bootstrap (SPEC: override val bootstrap =
  Runtime.removeDefaultLoggers, which made a failed run print nothing): a failure is logged by ZIOApp after the
  restore, exit 1.
- TuiHandle: await and states wait on the handle's own uninterruptible promise (one watcher fiber per handle),
  not on the run fiber: in Kyo an interrupted waiter interrupts the fiber it waits on (a timed-out await would
  stop the app). Interrupting await does not stop the app. states = current state, each change, ends after the
  final state (SPEC: ZStream.repeatZIO(core.states.next)).
- LiveTuiTest / LiveTest: methods take (using Trace); typeText, quit, error added; real clock or the caller's
  TestClock; the app's failure fails the test; an interrupted test interrupts and awaits the app.
- Tests: zio-test is a Test dependency (zio module, and examples via examples/zio.sbt at the zio module's ZIO
  version) for TestClock only; no zio-test-sbt runner.
- Shared files: examples/src/test/scala/parity/ParitySuite.scala (Set("kyo", "zio")); root build.sbt line 110:
  the launcher script is named after the example's package (bin/ziocounter, bin/ziodashboard; Kyo names
  unchanged): examples.ziocounter.CounterApp overwrote bin/counter, which ptycheck runs as the Kyo Counter. The
  cats PR needs the same line (catscounter): on merge keep one copy.
- examples/README.md not changed (to avoid a conflict with the cats PR); the run commands are in the Scaladoc of
  the two ZIO examples.

## Open
- TestClock: one adjust of several periods may fire fewer ticks than periods (4 of 4 seen here); docs should say
  "adjust one period at a time after TestClock.sleeps shows the source asleep" (the tests do).
- examples/README.md: add rows for ziocounter / ziodashboard after the cats PR merges.
- The ZIO version literal lives in modules/zio/build.sbt; move it to project/Versions.scala when that shared file
  can change (examples/zio.sbt already reads it from the zio module).
