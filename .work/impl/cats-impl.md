# cats: the Cats Effect facade (K1 + K2 + K3) - implementation notes

Worktree: .work/worktrees/cats, branch feat/cats (from feat/core-3-terminal 666a8e3).
Agent: implementer. Started 2026-10-01.

## Plan

Package `tui4s.interop.cats` (SPEC 9 Q1), files in modules/cats/src/main/scala/tui4s/interop/cats/.

### K1 exports and bridge
- exports.scala: `export tui4s.dsl.*`, `export tui4s.{App, Next, ...}`, `type Source[S] = tui4s.Source[S]`.
- internal/CatsBridge.scala: vendored from .work/scratch/kyo-rc7/CatsBridge.scala, header of SPEC 4.3 + F10 line,
  private[tui4s]; the header points to the repo LICENSE (Apache-2.0 text is already there, identical).
- internal/AmbientDispatcher.scala: inheritable Local[Maybe[Dispatcher[?]]].
- Tests: CatsBridgeSuite (13 prototype tests), DispatcherSuite (F10: 100 cancels from a foreign thread through
  Dispatcher.parallel(await = false), no hang), KyoFreeFacadeSuite (core SurfaceCheck via
  Test / unmanagedSourceDirectories + tasty-query).
- modules/cats/build.sbt: tasty-query Test dep, surface source dir, Test / fork, javaOptions.

### K2 sources, runner, app
- Source.scala: object Source (every, poll, task, stream, signal) + Source.For[F[_]: Async, S].
  every: Sources.makePeriodic + Kyo sleep; poll: makePeriodic + getF((fa >>= put) >> sleep).foreverM;
  task: makeTask + getF(fa >>= put); stream: makeChunked + getF(s.chunks.evalMap(put).compile.drain);
  signal: Sources.make + getF(signal.discrete.evalMap(put).compile.drain) (fs2.concurrent.Signal).
- Tui.scala: Tui.run[F] (applyIfUnset + TuiRun under runWithDispatcher), Tui.resource (Resource.make with
  CE's bracket over Runner.startUnscoped), private[tui4s] runWith / resourceWith seams, TuiHandle[F, S].
  Tui.run of the real terminal goes through internal.TuiRun.run(app, NativeCrossterm()) like core Tui.run.
- TuiApp.scala: IOApp.Simple + App[S]; ctor: applyIfUnset + OutputCapture.install (like core TuiApp);
  runtimeConfig with cpuStarvationCheckInitialDelay = Inf; source; run; onExit.
- Tests: CatsFacadeSuite (SPEC 6.3), fs2 backpressure (10 000 elements, inbox 16), stopped source cancels its
  IO (onCancel runs), cancelled Tui.resource use restores before the release returns.

### K3 examples and live kit
- testkit/LiveTuiTest.scala: LiveTuiTest.apply[F, S, A](app, w, h)(test: LiveTest[F, S] => F[A]): F[A],
  trait LiveTest[F, S] (press, send, typeText, settle, state, screen, stats, sources, quit, error). Real clock
  (no virtual time: Cats sources sleep on F's clock). Own Observer + Probe-based settle on core internals.
- examples/catscounter, examples/catsdashboard (SPEC 3.5) + munit-cats-effect tests.
- examples/cats.sbt: FacadeParity "cats" entry, munit-cats-effect Test dep. ParitySuite expected set += "cats".

## Progress
- 20:37 main sources written (exports as `package object cats`, CatsBridge vendored + header, AmbientDispatcher,
  Source, Tui + TuiHandle, TuiApp, testkit/LiveTuiTest). cats/compile: 0 warnings.
- 20:48 tests written: CatsBridgeSuite (13), DispatcherSuite (F10 pure + bridge path), KyoFreeFacadeSuite,
  CatsFacadeSuite, SourceSuite, ResourceSuite, LiveTuiTestSuite; fixtures CounterApp + Apps. Test/compile ok
  after removing 2 unused imports of the prototype test. Next: cats/test.
- 20:50 cats/test: 47/47 green on the first run (log .work/logs/cats-test-1.log). COMMITS 19d5870 (K1),
  7206ad7 (K2), f5b1c34 (live kit).
- 20:56 examples: catscounter, catsdashboard (SPEC 3.5) + CounterSuite/DashboardSuite (sync + live in IO);
  examples/cats.sbt (parity "cats", munit-cats-effect); ParitySuite set += "cats"; root build.sbt launchers
  name scripts by package (catscounter vs counter collided). examples/test 25/25 green.
- 21:02 pty check (scratchpad/pty/cats.exp, the ptycheck pattern): catscounter + - q, Ctrl-C, SIGTERM (143),
  catsdashboard fs2 log line + pause + q: all PASS, cooked mode restored, no stray output after restore.
  No-TTY run: IOApp prints TuiError$NotATerminal (with stack trace), exit 1.
- 21:12 FOUND (probe): Kyo's join cascade. A Kyo fiber that joins another (`fiber.get` / `getResult`)
  registers `task.interrupts(v)` (kyo IOTask "interrupt cascade"): interrupting the JOINER interrupts the
  joined fiber. TuiHandle.await = runF(core.await) and the per-change race of `states` (runF(core.result))
  therefore STOPPED THE APP when the F was cancelled (h.await.timeout(...), a states race). Fix: the cats
  TuiHandle gets a waiter fiber `end` (Fiber.initUnscoped(core.result)) made at acquire and never
  interrupted; await and states wait on it with an onComplete callback (cancel leaves it alone); states
  registers one callback per stream (F.background + Deferred), not one per state. Test added in ResourceSuite.
  Core's own TuiHandle.await has the same property for Kyo users (open issue for core).
- 21:07 NativeTerminalSuite: real crossterm, no TTY on stdin -> Tui.run[IO] and Tui.resource fail with
  NotATerminal; System.out/err unchanged. LiveTest.eventually takes a FiniteDuration, reads F.monotonic.
- 21:25 `sbt -batch check` GREEN (log .work/logs/cats-check-1.log): cats 50 munit tests, examples 25, core
  kyo-test suites, versionPolicyCheck. cats/doc: no warning from our docs (one from CE's IOApp doc).
- 21:30 live kit: start is a bracket acquire; release interrupts the app and waits (test: a cancelled live
  test cancels the app's poll IO). Docs: `import fs2.*` makes `text(...)` ambiguous (fs2.text, verified with
  scala-cli); the starvation checker note for users' own IOApp.
- 21:33 backpressure test measures the lead of the stream (<= 64); with a non-waiting put it was 1169 and
  the test failed (checked once, code restored).
- 21:36 cancel-at-any-time test (30 runs cancelled 0-9 ms after start: never left entered). Final
  `sbt -batch fmt` (no change) + `sbt -batch check` GREEN (log .work/logs/cats-check-2.log): cats 52,
  examples 25, core suites, versionPolicyCheck. Tree clean. DONE.

## Commits on feat/cats (from 666a8e3)
- 19d5870 cats: facade exports and the vendored Kyo <-> Cats Effect bridge (K1)
- 7206ad7 cats: sources, Tui.run[F], Tui.resource and TuiApp (K2)
- f5b1c34 cats: the live test kit tui4s.interop.cats.testkit.LiveTuiTest (K3)
- b931d71 examples: the Cats Effect Counter and Dashboard, facade parity (K3)
- f84f6f8 build: name the example start scripts after the package
- 1b3e80e cats: a cancelled await or states stream does not stop the app
- b9796fd cats: real-library runner tests; eventually takes a FiniteDuration
- b4e66b3 cats: the live kit stops the app also when the test is cancelled
- 3a88465 docs(cats): the fs2.* import clash and the starvation checker in an IOApp
- ec645e8 test(cats): the backpressure test measures how far fs2 runs ahead
- 9f4cc05 test(cats): a cancel at any time of a run leaves no terminal entered

## Deviations from SPEC (also in the structured result)
- Exports are in `package object cats` (file exports.scala), to carry the package Scaladoc; members as 4.1.
- Paths/packages follow Q1: tui4s/interop/cats/{exports,Source,Tui,TuiApp}.scala, internal/{CatsBridge,
  AmbientDispatcher}.scala, testkit/LiveTuiTest.scala. CatsBridge header verbatim + F10 line + a pointer to the
  repo LICENSE (Apache-2.0 text already there). CatsBridgeSuite: package/imports changed, 2 unused imports
  dropped, last test renamed (its Dispatcher has await = false).
- Source.signal(fs2.concurrent.Signal[F, A]) added (General kind; discrete, latest value).
- TuiHandle[F, S] waits for the end on a waiter fiber that nothing interrupts (Kyo join cascade); `states`
  starts with the current state and ends with the last one; cancelling `stop` stops the app anyway.
- Tui.resource (real terminal) composes core internals (LogSink, TuiLog.let, TerminalIO, Runner.startUnscoped):
  core has no unscoped TuiRun.start; no core file changed.
- TuiApp also installs OutputCapture in its constructor (as core TuiApp); a failure is reported by IOApp
  (stack trace, exit 1), not by core's message-only report; no `args`.
- LiveTuiTest[F](app, w, h)(test: LiveTest[F, S] => F[A]) (LiveTuiTest.Of[F] partial application); LiveTest
  adds typeText, eventually(timeout), quit, error; no advance / virtual time (real clock).
- Shared files changed: root build.sbt (launchers: script named after the package, 1 line + comment);
  examples/src/test/scala/parity/ParitySuite.scala (Set("kyo", "cats")).

## Open issues
- Core TuiHandle.await/stop (Kyo API) join the run fiber: interrupting a fiber that waits in `h.await`
  (Async.timeout) interrupts the app (Kyo IOTask interrupt cascade, `task.interrupts(v)` on join).
- `import fs2.*` + facade import: `text(...)` is ambiguous (fs2.text). SPEC 2.12 says no clash; verified with
  scala-cli. Documented in the facade package doc; needs a troubleshooting entry (8.5).
- examples/README.md and examples/ptycheck/checklist.exp do not list catscounter / catsdashboard (shared
  files). A scratch expect script (scratchpad/pty/cats.exp) passed: + - q, Ctrl-C, SIGTERM 143, dashboard
  log + pause, cooked mode, no stray output.
- Tui.resource on the real terminal is verified only up to NotATerminal (no TTY test with a handle).
