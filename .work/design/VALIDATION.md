# SPEC.md validation log

Validator: compiles the API skeleton of SPEC.md section 2, the samples of section 3, and the
ZIO / Cats facades of section 4 against Kyo 1.0.0-RC7, jatatui 0.31.0, ZIO 2.1.26, CE 3.7.1, fs2 3.14.0.

Skeleton: `.work/scratch/design-validate/` (scala-cli, `--server=false`, JDK 25, `JDK_JAVA_OPTIONS=-Xss16m`).
Scalac options: `-Yexplicit-nulls -Wunused:all -Wvalue-discard -Wnonunit-statement -deprecation -feature`.

Each compile attempt is appended below: what failed, why, what changed.

## Attempts

### Attempt 1: core skeleton, first compile (22 s wall, 21 errors)

Command: `scala-cli compile --server=false core` (log `out/core-1.log`).

1. **`internal` is ambiguous under `import kyo.*`.** Kyo has a package `kyo.internal`. In any tui4s file that does
   `import kyo.*`, the name `internal` resolves to `kyo.internal` (a wildcard import beats a package member that is
   defined in another file), and with `import tui4s.*` as well it is a hard "ambiguous" error. Fix: library code
   writes `tui4s.internal.X`. SPEC 2.2 / 2.12 amended (rule for library code).
2. **Extension objects for a top-level opaque type.** `object StyleOps` as a separate top-level object in the file
   of `opaque type Style` does NOT see through the alias (`withFg is not a member of tui4s.Style`). Only the
   companion object (and the file's top-level defs) see through a top-level opaque type. Fix: the ops objects are
   nested in the companions (`Style.StyleOps`, `Line.SpanOps`); `dsl` does `export Line.SpanOps.*` and
   `export Style.StyleOps.*`. SPEC 2.7 amended.

### Attempt 2-3: core compiles (22 s); position-less Kyo warnings

- Core compiled with 0 errors. `-Wunused:all` printed 29 warnings "@nowarn annotation does not suppress any
  warnings" without a position: they come from Kyo's inline methods (`Loop`, `Sync.ensure`, ...) inlined into
  tui4s code. Silenced with `-Wconf:msg=@nowarn annotation does not suppress any warnings:s`. Users who compile
  their own Kyo code with `-Wunused:all` see the same noise (SPEC 5.15 amended: the build template carries the
  `-Wconf`).
- Nothing else failed: the overload of a case-class field with an inherited View method (`Gauge.ratio: Double`
  vs `View.ratio(num, den)`, `Styled.style` vs `View.style(s)`) compiles; `export dsl.*` at the top level of
  package `tui4s` works, including the extension forwarders re-exported from `Line.SpanOps` and `Style.StyleOps`
  (three overloads of `bold` etc. for String / Line.Span / Style resolve).
- Core packaged as `out/tui4s-core.jar` (`scala-cli --power package --library`), so that the samples and the
  facades compile against a jar, as the real `examples` / `zio` / `cats` modules will (macros from TASTy).

### Attempt 4: Kyo samples (SPEC 3.1-3.3) + tests against the jar (2 errors, 5 warnings)

1. **`App.apply` default `sources = (_: Any) => Nil` fails when `sources` is omitted**: "Found: Any =>
   Seq[tui4s.Source[Nothing]] Required: S => Seq[tui4s.Source[S]]". The default getter infers its own `S` as
   `Nothing` and `Source` is invariant. Reproduced without Kyo (`out/probes/default-getter.scala`): Scala 3.9 types
   the default getter from its right-hand side, so `(_: S) => Nil` also fails ("Found: S => Seq[Box[Nothing]]").
   Fix: `sources: S => Seq[Source[S]] = (_: S) => Seq.empty[Source[S]]`. SPEC 2.3 amended.
2. **`_ sample _`, `_ log _`, `_ loaded _`, `_ saved _` warn** under Scala 3.9: "Alphanumeric method sample is not
   declared infix; it should not be used as infix operator". Every sample fold written this way warns. Fix in the
   samples: `_.sample(_)`, `_.log(_)`, `_.loaded(_)`, `_.saved(_)` (same inference, no warning). SPEC 1.3 (G-list
   text), 3.2-3.5, 6.3 amended.
3. `t.typeText("call bob").press(...)` as a statement warns under `-Wnonunit-statement` ("unused value of type
   (t : TuiTest[Todo])"): the chained `this.type` result of a chain is not exempt (a single `t.press(...)` is).
   Test code style only; SPEC 6.3 sample split into two statements.
4. The three Kyo samples (Counter, Todo, Dashboard) compiled unchanged otherwise: `bind` with `_ + 1`,
   `_ => Quit` and an eta-expanded private method; `case active(action) => action(t)`; `.or(Key.BackTab)`;
   `t.copy(...).withTask(source.task(Abort.run[IOException](...))(...).id("save"))` infers `S = Todo` and
   `A = Result[IOException, Unit]`; `source.poll(250.millis)(Random.nextDouble)(...)` (a `Double < Sync` fits
   `A < (Async & Abort[Throwable])`); `source.stream(requests)(...)` with a `Stream[String, Async]`;
   `Stream.unfold(1, chunkSize = 1)`; `Result.Success(x)` patterns; `Abort.catching[IOException]`.
5. Name-clash check (`clash/NameClash.scala`, `import kyo.*` + `import tui4s.*`, every exported name used once):
   no ambiguity. `kyo.Span` stays usable next to `Line.Span`.

### Attempt 5: samples compile; first test run (16 of 23 tests fail, all from one skeleton bug)

- Skeleton bug, not a SPEC issue: `catch case NonFatal(t) => fail(...); false` on one line parses as
  `(try ... catch case ... => fail(...)); false` in Scala 3, so `Engine.start` / `planWork` always returned false
  and every event was marked failed. Rewritten as multi-line `catch` blocks. (Worth a line in the contributor
  rules: no `;` after a one-line `catch case`.)
- `munit.compileErrors` DOES report the `NotForeign` macro error (the inline given is expanded during the
  type check that `compileErrors` runs), with the intended message. So the GuardTest of SPEC 6.3 works with
  munit's `compileErrors` as well as kyo-test's `typeCheckFailure` (not tried).

### Attempt 6: 19 of 23 pass; the runtime suite passes completely

Passed: the real loop on a TestBackend (Counter: keys -> frames -> Quit returns 3; Dashboard: `poll(250 ms)` and
the `chunkSize = 1` stream fold on fibers, `p` removes the poller, history 4-6 samples in 1.1 s; interrupt ->
`enter, input-started, input-stopped, input-joined, leave-raw-mode` complete when `interruptAwait` returns, i.e.
the `Sync.acquireReleaseWith(open)(_.close())` + `Sync.ensure(stopAll)` shape of 5.9 works with
`Abort[TuiError]` (enum extending Exception: ConcreteTag OK); `Headless.start` + `TuiHandle` update/send/stats/
stop/await; a failing task -> `TuiError.SourceFailed("bad", boom)`; keyed tasks). `Loop(drawnAt) { ... }` with
`Loop.continue` / `Loop.done` under `Async & Abort[TuiError]` compiled without annotations except on two helper
methods (`Loop.Outcome[Long, S] < ...` result types).

Failures (findings):
1. **Wide graphemes in `Screen`.** jatatui stores a wide grapheme in one cell and " " in its trailing cell(s);
   reading cell by cell gives "✔  call bob" (jatatui's Wcwidth makes ✔ and ☐ two columns wide). `Screen` must skip
   the trailing cells (as `Buffer.diff` does). Fixed in `Screen.of`; SPEC 2.10 amended.
2. **Cell styles are not comparable with `Style` values.** A cell reports `bg = Reset, underlineColor = Reset`
   for colors nobody set, so `styleAt(x, y) == Style.bold.fg(Color.Yellow)` is false for a bold yellow cell.
   Fixed: `Screen` normalizes Reset colors to unset. SPEC 2.10 amended.
3. **The Todo help bar overflows.** `global ++ Keymap(...)` lists the three global binds first
   ("tab/shift+tab focus · ctrl+s save · esc quit · ↑/k up · ..."), so at 80 columns minus the 24-column status
   the item keys ("d delete") are cut off and the SPEC 6.3 test "the help bar shows the keys of the focused pane"
   fails. Fix in the sample: `Keymap(pane binds) ++ global` (pane keys first; `++` keeps left-wins). SPEC 3.2
   amended.
4. The golden screen guessed in the test was wrong (`Flex.Center` centers the two rows vertically); the real
   output is right.

### Attempt 7: all 25 Kyo sample tests pass

`out/samples-5.log`: CounterSuite 8, TodoSuite 4, DashboardSuite 2, GuardSuite 5, RuntimeSuite 6. Also checked:
- Appendix A 15: `javap examples.counter.CounterApp` shows `public static void main(java.lang.String[])`: the
  inherited `final def main` of `TuiApp` gets a static forwarder.
- Appendix A 19: the scheduler flags are `kyo.StaticFlag`s from `kyo-config` (`kyo/Flag.scala`): name = the
  object's full name, read from the system property first, then the env var `name.replace('.', '_').toUpperCase`
  (`KYO_SCHEDULER_CYCLEINTERVALNS`). The three object names of 5.14 exist (`kyo.scheduler.cycleIntervalNs`,
  `kyo.scheduler.blockingMonitorIntervalNs`, `kyo.scheduler.regulator.concurrencyCollectIntervalMs`). VERIFIED.
- Appendix A 10: `summon[CallSite]` and a core source label are the caller's `File.scala:line` when the macro
  comes from the jar.

### Attempt 8: tui4s-zio facade + ZIO samples (1 test-code error, then 7 of 7 pass)

- `export tui4s.{App, Next, Quit, Key, ...}` from a package compiles in Scala 3.9 (checked first in a scratch
  file), so the facade `exports.scala` uses the short form; the alias + `val` fallback of SPEC 4.1 is not needed.
  Patterns `Key.Char('q')`, `Quit`, `Resize(w, h)` work through it.
- The facade (`Source` / `Source.For` every/poll/task/stream, `Tui.run`, `Tui.start`, `TuiHandle`, `TuiApp extends
  ZIOAppDefault, tui4s.App[S]` with `override val bootstrap = Runtime.removeDefaultLoggers`) compiled at once. No
  member clash between `ZIOAppDefault` and `App`.
- The ZIO Counter and Dashboard of SPEC 3.4 compile with only `import tui4s.zio.*` (+ `zio.*`, `ZStream`): no Kyo
  type in user code. The UNVERIFIED ZIO names `ZStream.iterate`, `.schedule`, `Schedule.spaced`,
  `Random.nextDouble` are right (VERIFIED by compile and run).
- Needed in core: `Runner.startUnscoped(app, control): TuiHandle[S] < Sync` (named in SPEC 4.2 but not in 2.x) so
  that `Tui.start` releases with `ZIO.acquireRelease`, not a Kyo `Scope`.
- Needed in the SPI: the facade `task` must say "this is a one-shot task" and `poll`/`every` "periodic" (the
  synchronous test kit completes a task after `emit`; the live kit counts periodic sleepers). SPEC 2.11 has only
  `make` / `makeChunked`; added `Sources.makeTask` and `Sources.makePeriodic`. SPEC 2.11 amended.
- `TuiHandle` methods take `(using Trace)` (ZIO style; SPEC 4.2 listed them without). SPEC 4.2 amended.
- Test-code error: `FiberRef.make` needs a `Scope` in ZIO 2 (SPEC 6.3 test wraps it in `ZIO.scoped`).
- Tests (all pass): the shared Counter under `tui4s.zio` in `TuiTest`; the ZIO Counter on the real loop via
  `Tui.runWith(app, TestBackend, ScriptedInput)` returning `IO[TuiError, Int]` = 2 and "Count: 2" on screen; a
  `ZIO` task source sees the caller's `FiberRef` (42) from the forked Kyo source fiber (F12); `fiber.interrupt`
  returns after `leave-raw-mode` (F11); the ZIO Dashboard (ZStream logs + `poll(250.millis)(Random.nextDouble)`)
  folds 4-6 samples per 1.1 s and `p` stops the poller; a facade source label is the user's `File.scala:line`;
  `Tui.start` in `ZIO.scoped` with `update`/`send`/`stats`/`stop`/`await`.

### Attempt 9: tui4s-cats facade + Cats samples (compiled at once; 7 of 8, then 8 of 8)

- The vendored `CatsBridge` (header + the new F10 change line), `AmbientDispatcher` (inheritable
  `Local[Maybe[Dispatcher[?]]]`), `Source` / `Source.For[F, S]` (every/poll/task/stream), `Tui.run[F]`,
  `Tui.resource`, `TuiHandle[F, S]`, `TuiApp extends IOApp.Simple, tui4s.App[S]` compiled with no error and no
  warning. No member clash between `IOApp.Simple` and `App`.
- The Cats Counter and Dashboard of SPEC 3.5 compile with only `import tui4s.cats.*` (+ `cats.effect.IO`,
  `fs2.Stream`, `scala.concurrent.duration.*`): no Kyo type in user code. The UNVERIFIED fs2 names
  `Stream.iterate(1)(_ + 1).covary[IO].metered(700.millis)` are right (VERIFIED by compile and run).
- Starvation checker (4.3, UNVERIFIED): `IORuntimeConfig` 3.7.1 has a public
  `copy(cpuStarvationCheckInitialDelay: Duration = ...)`, and `CpuStarvationCheck.run` ends with
  `.delayBy(cpuStarvationCheckInitialDelay)`, so `super.runtimeConfig.copy(cpuStarvationCheckInitialDelay =
  Duration.Inf)` means "never starts". VERIFIED (source + a test on a `TuiApp` subclass).
- `Source.For[F[_]: Async, S] private[cats] ()` (SPEC 4.3) is a class with a context bound, so `For.apply` allocates
  (one small object per call); the shared-instance trick of the Kyo/ZIO `For` does not apply. Harmless.
- The one failure was test timing: on a cold JVM the fixed `IO.sleep(300.millis)` before pushing `q` let `q` join
  the same input batch as the `+` keys, so the app quit before the frame for the new count (SPEC 2.13 rule 11:
  "Quit: no frame is drawn"; correct behaviour). The loop tests now wait until the screen shows the value.
- Tests (all pass): the shared Counter under `tui4s.cats`; the Cats Counter on the real loop via
  `Tui.runWith[IO, Int](app, TestBackend, ScriptedInput)` = 2 with "Count: 2" on screen; an `IO` task source runs
  on an `io-compute` thread of the caller's runtime; `fiber.cancel` returns after `leave-raw-mode`
  (cancel-and-await); the Cats Dashboard (fs2 metered stream + IO poll) folds 4-6 samples per 1.1 s and `p` stops
  the poller; a facade source label is the user's `File.scala:line`; `Tui.resource` + `TuiHandle`; the
  `TuiApp` runtime config.

Totals after attempt 9: core skeleton + Kyo samples 25 tests, ZIO 7, Cats 8: **40 of 40 pass**.

### Attempt 10: extra checks (no SPEC break; several notes)

- Run without a TTY: `scala-cli run samples --main-class examples.counter.CounterApp` (with and without
  `--enable-native-access=ALL-UNNAMED`, stdin `/dev/null`) prints "stdin/stdout is not a terminal" and exits 1.
  Without the flag JDK 25 only prints the JEP 472 warning (default `--illegal-native-access=warn`), so the SPEC
  8.2 checklist line "stops with NativeAccessDenied" was wrong; amended.
- F1: `scala-cli clean` + compile of core and samples (with tests) without `JDK_JAVA_OPTIONS=-Xss16m` (JVM default
  1 MB thread stack): both compile. App code in this design does not need `-Xss`.
- NotForeign with a wrong fold (`(s: String, _) => s` on `Source.For[Int].task(zio.ZIO)`): `compileErrors` shows only
  the two fold type errors; the NotForeign message does not appear. The SPEC 2.8 claim "this error comes before
  any fold type error" was wrong; amended. `poll` with a `Future` is rejected with the message.
- Virtual time probe (`samples/tests/TimeControlProbe.test.scala`): the real loop of the Dashboard under
  `Clock.withTimeControl` (fps 0); source fibers follow the controlled clock (`Fiber.initUnscoped` keeps Locals).
  (history, logs) = (1, 0) at start, (2, 1) after one `advance(1 s)`, (6, 2) after four 250 ms steps: V8 holds.
  Also found: with the default `fps = 60` the pacing `Async.sleep` is virtual too, so a change right after a frame
  is never drawn under time control (live kit must force fps 0; SPEC 5.4 amended). A first probe version that
  blocked a worker (`Thread.sleep`) right after `awaitPendingSleepers` saw the render fiber stall; `settle` must
  park, not block.
- Skeleton fix found by the probe: `TuiHandle.states` was `null` until the first batch; it is now published after
  the first frame (SPEC 2.9 amended).

### Final run (after all changes)

`out/core-final.log` (0 warnings), `out/samples-final.log` 28/28, `out/zio-final.log` 7/7, `out/cats-final.log`
8/8: **43 of 43 tests pass**. SPEC.md amended in place ("(validated: ...)" markers) and section 10 added. Scratch
probes copied to `out/probes/` (default-getter inference, export from a package).
