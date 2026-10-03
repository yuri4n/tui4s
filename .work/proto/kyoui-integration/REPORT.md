<!-- claude 2026-10-03: created this report (integration of the facade apps with the terminal runner) -->
# Prototype "kyoui-integration": the ZIO and Cats facade apps on the kyo-ui terminal runner

Status: DONE (2026-10-03). Machine: Linux container, 4 CPUs, 15 GB RAM, JDK 25, scala-cli. One other agent
uses the same machine.

## Goal

Prove or disprove that the ZIO and Cats facade apps (`.work/proto/kyoui-facades/{zio,cats}/apps/`) run on the kyo-ui
terminal runner (`.work/proto/kyoui-runner/src/`), with no change to the files of the two prototypes.

<!-- claude 2026-10-03: created the result sections below (they are above the progress log) -->
## Result

- YES. The UNCHANGED ZIO and Cats facade apps (Counter and Todo of `.work/proto/kyoui-facades/{zio,cats}/apps/`) run
  on the kyo-ui terminal runner (`.work/proto/kyoui-runner/src/`). No file of the two prototypes changed (checked:
  no file in the two folders is newer than the start of this session, outside `.scala-build/`).
- The connection is a small glue in this folder: `glue/zio/Tui.scala` (ZIO) and `glue/cats/Tui.scala` (Cats). A user
  starts an app with ONE call: `Tui.run(app)`.
- Headless, 100x30 (the runner's jatatui `TestBackend`): 12 munit tests (6 ZIO, 6 Cats). The final 3 runs:
  12 of 12 PASS in each run (`logs/test-final-c-{1,2,3}.log`). Two earlier sets of 3 runs also passed.
- Real PTY (expect, 100x30): the ZIO Counter. The keys work, the terminal is in raw mode while the app runs, Ctrl-C
  quits with exit code 0, `stty -a` after = before, one Enter writes 26 to 27 bytes (one region, one or two cells). Extra: the
  Cats Counter gives the same result. PTY part 10 of 10, screen part 22 of 22 (`logs/pty-check*.log`).
- I found 3 problems. All 3 are in the RUNNER; the facades only make 2 of them easier to see:
  1. An INTERRUPTED run leaks about 1.75 MB (its region watchers do not stop). The glue test kit works around it.
  2. The FIRST key after the start can show its result about 1 s late (rare: 0 to 4 in 500 starts without load, up to
     13 in 300 under CPU load). A plain Kyo app has it too (1 in 600 starts under load).
  3. The output capture misses `scala.Console.out`: after one `println` before `Tui.run`, a ZIO log or a `println` in a
     handler goes to the terminal and breaks the screen.
- Two gaps of the glue: a quit action for facade users (the runner's `Tui.quit` is a Kyo `Local` that ZIO and Cats
  handlers cannot see), and run options without Kyo types (`TuiOptions` holds `kyo.Duration`).

## How to run

Run each command in `.work/proto/kyoui-integration/`. Run only one at a time. Use `--jvm system`.

| What | Command | Seen on this machine |
| --- | --- | --- |
| Compile | `scala-cli compile --jvm system .` | exit 0 (`logs/compile-1.log`) |
| Tests (12 tests; the 2 stress suites are ignored) | `scala-cli test --jvm system .` | exit 0, 3 of 3 runs (`logs/test-final-c-{1,2,3}.log`) |
| Split probe | `scala-cli test --jvm system . --java-prop probe.rounds=500 --test-only integration.SplitProbeSuite` | `logs/split-probe-*.log` |
| Leak probe | `scala-cli test --jvm system . --java-prop probe.leak=150 --test-only integration.LeakProbeSuite` | `logs/leak-probe-2.log` |
| Assembly jar (all mains) | `scala-cli --power package --jvm system . --main-class integration.ZioCounterMain -o <scratch>/kyoui-integration.jar --assembly --preamble=false -f` | exit 0, 61 MB (`logs/package-final.log`). Do not keep the jar in `.work/`. |
| PTY check | `PTY_WORK=<empty dir> pty/run-all.sh <jar>` | 10 of 10 and 22 of 22 (`logs/pty-checklist.log`, `logs/pty-check.log`) |
| Output probe (PTY) | `KYOUI_JAR=<jar> PTY_WORK=<empty dir> expect pty/output-probe.exp; PTY_WORK=<same dir> python3 pty/probe-check.py` | `logs/pty-probe-check.log` |
| Start an app by hand | `KYOUI_JAR=<jar> pty/run.sh integration.ZioTodoMain` (or `ZioCounterMain`, `CatsCounterMain`, `CatsTodoMain`) | not run by hand here (the PTY check starts the two Counter mains) |

## Folder content

| Path | What it is |
| --- | --- |
| `project.scala` | The build. `//> using files` adds the source folders of the two prototypes: `../kyoui-runner/src`, `../kyoui-facades/shared`, `../kyoui-facades/{zio,cats}/{generated,src,apps}`. Dependencies: the union of both prototypes (kyo-ui, kyo-core, kyo-zio RC7; zio 2.1.26; cats-effect 3.7.1; fs2 3.12.2; jatatui 0.31.0; munit 1.3.6 for tests). |
| `glue/zio/Tui.scala` | ZIO entry point `Tui.run`, `Tui.quit`, and the test kit `TuiHeadless`. Package `tui4s.interop.zio.ui`. |
| `glue/cats/Tui.scala` | Cats entry point `Tui.run`, `Tui.runWithQuit`, and the test kit `TuiHeadless[F]`. Package `tui4s.interop.cats.ui`. |
| `mains/Mains.scala` | `ZioCounterMain`, `ZioTodoMain` (`ZIOAppDefault`), `CatsCounterMain`, `CatsTodoMain` (`IOApp.Simple`). |
| `test/ZioOnTerminal.test.scala`, `test/CatsOnTerminal.test.scala` | The 12 headless tests. |
| `stress/SplitProbe.test.scala`, `stress/LeakProbe.test.scala` | Stress probes, ignored unless `-Dprobe.rounds` / `-Dprobe.leak` is set. |
| `probes/OutputProbe.scala` | Three mains that write output from a handler (for `pty/output-probe.exp`). |
| `pty/` | `run.sh`, `checklist.exp`, `check.py`, `run-all.sh`, `output-probe.exp`, `probe-check.py`. Adapted from `.work/proto/kyoui-runner/pty/`. `check.py` imports the runner's `vt.py` from its folder (not copied). |
| `logs/` | All logs of this session (this machine). |

No name clash and no dependency clash: the packages differ (`tui4s.kyoui`, `tui4s.interop.zio.ui`,
`tui4s.interop.cats.ui`, `apps.zio`, `apps.cats`, `kyo.tui4sproto`, `integration`). The two mirrors both define `div`,
`button`, `Keyboard` and more at the top level of their packages, so one file must import only one mirror. The glue
files must be in the mirror packages, because the conversion `unsafeToKyo(ui: UI): kyo.UI` is `private[ui]`.

## User-facing entry point

### What a ZIO user writes (`mains/Mains.scala`, verbatim)

```scala
package integration

import cats.effect.IO
import cats.effect.IOApp
import zio.ZIOAppDefault

// The user-facing entry points: a ZIO or Cats user starts a facade app on the terminal with ONE call, `Tui.run(app)`.
// The apps are the facade apps of .work/proto/kyoui-facades/{zio,cats}/apps, unchanged. Ctrl-C quits (they have no quit key).

// claude 2026-10-03: created the ZIO mains (ZIOAppDefault + tui4s.interop.zio.ui.Tui.run)
object ZioCounterMain extends ZIOAppDefault:
    def run = tui4s.interop.zio.ui.Tui.run(apps.zio.CounterApp.app)

object ZioTodoMain extends ZIOAppDefault:
    def run = tui4s.interop.zio.ui.Tui.run(apps.zio.TodoApp.app)

// claude 2026-10-03: created the Cats mains (IOApp.Simple + tui4s.interop.cats.ui.Tui.run)
object CatsCounterMain extends IOApp.Simple:
    def run: IO[Unit] = tui4s.interop.cats.ui.Tui.run(apps.cats.CounterApp.app)

object CatsTodoMain extends IOApp.Simple:
    def run: IO[Unit] = tui4s.interop.cats.ui.Tui.run(apps.cats.TodoApp[IO])
```

A quit key in a ZIO app (from `test/ZioOnTerminal.test.scala`, verbatim):

```scala
    test("glue: Tui.quit in a ZIO handler ends the terminal run") {
        val app =
            for n <- useSignal(0)
            yield div(n.render(v => p(s"n=$v")), button("+").id("inc").onClick(n.update(_ + 1)))
                .onKeyDown(e => if e.key == Keyboard.Char('q') then Tui.quit else ZIO.unit)
        run(
            for
                h   <- TuiHeadless.start(app)
                _   <- h.key(Keyboard.Enter)
                s   <- h.screen
                _   <- check(s.contains("n=1"), s)
                _   <- h.char('q')
                end <- h.awaitEnd(10.seconds)
                _   <- check(end, "Tui.quit ends the run")
            yield ()
        )
    }
```

### What a Cats user writes

The Cats mains are in the same file (above): `def run: IO[Unit] = tui4s.interop.cats.ui.Tui.run(apps.cats.CounterApp.app)`.
`Tui.run[F]` works for any `F[_]` with a `cats.effect.Async`. A Cats handler cannot find the run by itself (there is
no fiber-local value for a generic `F`), so a quit key needs `Tui.runWithQuit[F](quit => app)` (from
`test/CatsOnTerminal.test.scala`, verbatim):

```scala
    test("glue: the quit action of runWithQuit / startWithQuit ends the terminal run") {
        def app(quit: IO[Unit]): Resource[IO, UI] =
            useSignal[IO](0).map(n =>
                div(n.render(v => p(s"n=$v")), button("+").id("inc").onClick(n.update(_ + 1)))
                    .onKeyDown(e => if e.key == Keyboard.Char('q') then quit else IO.unit)
            )
        run(TuiHeadless.startWithQuit[IO](app).use { h =>
            for
                _   <- h.key(Keyboard.Enter)
                s   <- h.screen
                _   <- check(s.contains("n=1"), s)
                _   <- h.char('q')
                end <- h.awaitEnd(10.seconds)
                _   <- check(end, "quit ends the run")
            yield ()
        })
    }
```

### How a user tests an app (`test/ZioOnTerminal.test.scala`, verbatim)

```scala
    test("ZIO Counter (facade app, unchanged): Tab / Shift-Tab move, Enter / Space press, 3 independent counters, Ctrl-C quits") {
        run(
            for
                h  <- TuiHeadless.start(CounterApp.app, 100, 30)
                s0 <- h.screen
                _  <- check(counts(s0) == List("Count: 0", "Count: 0", "Count: 0"), s0)
                _  <- check(s0.contains("Left") && s0.contains("Right") && s0.contains("Component counter"), s0)
                f1 <- h.key(Keyboard.Enter) // auto focus: the first button, "-" of Left (id a-dec)
                _  <- check(f1.focus == "a-dec", s"focus after start: ${f1.focus}")
                _  <- check(f1.updates == 1 && f1.flushed > 0 && f1.flushed <= 4, s"one region, a few cells: $f1")
                s1 <- h.screen
                _  <- check(counts(s1) == List("Count: -1", "Count: 0", "Count: 0"), s1)
                ft <- h.key(Keyboard.Tab)
                _  <- check(ft.focus == "a-inc" && ft.updates == 0, s"Tab: $ft")
                _  <- h.key(Keyboard.Enter) *> h.key(Keyboard.Enter) *> h.key(Keyboard.Enter)
                fb <- h.key(Keyboard.Tab)
                _  <- check(fb.focus == "b-dec", s"Tab: $fb")
                _  <- h.key(Keyboard.Space)
                _  <- focusOn(h, "c-inc")
                _  <- h.key(Keyboard.Enter) *> h.key(Keyboard.Enter)
                s2 <- h.screen
                _  <- check(counts(s2) == List("Count: 2", "Count: -1", "Count: 2"), s2)
                fr <- h.key(Keyboard.Tab)
                _  <- check(fr.focus == "c-reset", s"Tab: $fr")
                _  <- h.key(Keyboard.Enter)
                s3 <- h.screen
                _  <- check(counts(s3) == List("Count: 2", "Count: -1", "Count: 0"), s3)
                fz <- h.char('z')
                _  <- check((fz.repainted, fz.flushed, fz.updates) == (0, 0, 0), s"an unknown key changes nothing: $fz")
                fs <- h.key(Keyboard.Tab, shift = true)
                _  <- check(fs.focus == "c-inc", s"Shift-Tab: $fs")
                _  <- h.key(Keyboard.Char('c'), ctrl = true)
                end <- h.awaitEnd(10.seconds)
                _  <- check(end, "Ctrl-C ends the run")
            yield ()
        )
    }
```

The Cats form is `TuiHeadless.start(CounterApp.app, 100, 30).use { h => ... }` with the same methods in `F`.
`FrameStats` is a runner type (`tui4s.kyoui`), not a Kyo type.

### The glue itself (what the real zio and cats modules must hold)

ZIO (`glue/zio/Tui.scala`, verbatim excerpts):

```scala
    def run(app: ZIO[Scope, Nothing, UI]): UIO[Unit] =
        ZIO.scoped {
            for
                quit <- Promise.make[Nothing, Unit]
                _ <- quitRef.locally(Some(quit)) {
                    for
                        ui <- app
                        _  <- AmbientZIOs.run(KTui.run(withQuit(quit, unsafeToKyo(ui))))
                    yield ()
                }
            yield ()
        }

    val quit: UIO[Unit] =
        quitRef.get.flatMap {
            case Some(p) => p.succeed(()).unit
            case None    => ZIO.unit
        }

    private[ui] def withQuit(quit: Promise[Nothing, Unit], ui: _root_.kyo.UI)(using Frame): _root_.kyo.UI < (Async & KScope) =
        KFiber.init(AmbientZIOs.get(quit.await).andThen(KTui.quit)).andThen(ui)
```

Cats (`glue/cats/Tui.scala`, verbatim excerpts):

```scala
    def runWithQuit[F[_]](app: F[Unit] => Resource[F, UI])(using F: CEAsync[F]): F[Unit] =
        (for
            d    <- Dispatcher.parallel[F](await = false)
            quit <- Resource.eval(Deferred[F, Unit])
            ui   <- app(quit.complete(()).void)
        yield (d, quit, ui)).use { (d, quit, ui) =>
            CatsBridge.runF(AmbientDispatcher.let(d)(KTui.run(withQuit(d, quit, unsafeToKyo(ui)))))
        }

    private[ui] def withQuit[F[_]](d: Dispatcher[F], quit: Deferred[F, Unit], ui: _root_.kyo.UI)(using
        Frame
    ): _root_.kyo.UI < (Async & KScope) =
        KFiber.init(CatsBridge.getF(quit.get)(using d).andThen(KTui.quit)).andThen(ui)
```

How it works, in steps:

1. The glue builds the facade value in the user's effect system (`ZIO[Scope, Nothing, UI]` or `Resource[F, UI]`). The
   app's own resources (`useSignal`, `useStream` fibers) live in that scope.
2. The glue converts the facade `UI` to `kyo.UI` (`unsafeToKyo`; an opaque type, so no copy).
3. The glue calls the runner's `tui4s.kyoui.Tui.run(ui)` inside the facade bridge: `AmbientZIOs.run` (ZIO) or
   `CatsBridge.runF` with `AmbientDispatcher.let(dispatcher)` (Cats). The runner fiber and its handlers inherit that
   Kyo `Local`, so each ZIO handler runs on the caller's ZIO runtime and each `F` handler runs through the caller's
   `Dispatcher`. The runner runs a handler inline in its own fiber (`Dispatch.safe`), and the bridge waits for the end
   of the ZIO or `F`, so the runner's settle step starts after the handler's writes (see the first-key race below for
   the one known exception).
4. Quit: the runner's `Tui.quit` reads a Kyo `Local` that only Kyo fibers of the run can see. A ZIO or `F` handler
   runs on its own fiber system, so the glue starts one Kyo fiber during the UI construction (it inherits the
   `Local`). The fiber waits for a ZIO `Promise` / a Cats `Deferred` and then calls the runner's quit.

## What works (evidence from this machine)

| Claim | Evidence |
| --- | --- |
| The unchanged facade apps compile with the runner in one project | `logs/compile-1.log` (exit 0, no warning) |
| Counter: Enter / Space press the focused button, Tab and Shift-Tab move the focus in document order, 3 independent counters (2 hooks style, 1 `Component` style), Ctrl-C ends the run | Counter tests, ZIO and Cats; the test checks the focused id (`a-dec`, `a-inc`, `b-dec`, `c-reset`, `c-inc`) in the `FrameStats` of each key |
| Partial repaint: one Enter = 1 region update, 1 to 4 cells flushed; an unknown key = 0 cells | Counter tests; PTY: 26 to 27 bytes per Enter |
| Todo: typing goes into the auto-focused input (two-way binding: the facade ref IS the cell that the runner writes); Enter runs the input's `onKeyDown` (a ZIO / `F[Unit]`) that reads the draft and adds; the draft clears on the screen; the Add button adds; Space on a checkbox runs `onChange`; `when(...)` shows `Clear done`; the `x` button deletes; `foreachKeyed` keeps the order | Todo tests, ZIO and Cats; 15 items typed key by key, all in order |
| `useStream` (a ZStream / an fs2 Stream that runs outside any handler) updates the screen (Background frames) | useStream tests, ZIO and Cats |
| ZIO handlers run with the FiberRefs and the executor of the fiber that started the app; Cats handlers run on the caller's IORuntime and see the IOLocal of the start | runtime tests (`seen: caller-42 on caller-zio-pool`; `on caller-ce-pool`, `seen: caller-42`) |
| The quit glue works (`Tui.quit` for ZIO, `runWithQuit` for Cats) | quit tests |
| Real terminal: alternate screen, raw mode (`-icanon -echo -isig`) while running, Ctrl-C exit code 0, cursor shown, nothing stray after the restore, `stty -a` after = before | PTY run, ZIO Counter (and Cats Counter as an extra); in the last run the first frame came 990 ms (ZIO) and 1168 ms (Cats) after the start command, JVM start included |
| Handler output goes to `tui4s.log`, not to the screen (when nothing printed before `Tui.run`) | output probe, 7 kinds of output |
| The checks can fail (no vacuous test) | negative control: 3 of 3 control tests failed as they must (`logs/negative-control-*.log`) |

## What does not work, and limits

1. LEAK of an interrupted run (RUNNER). `logs/leak-probe-{1,2}.log`: 150 starts of the plain Kyo runner that end by an
   interrupt keep 257 MB (1754 KB per run); with a quit first, 2 MB. In `logs/split-probe-noload-2.log`, 500 ZIO starts
   and an unknown number of Cats starts (all ended by an interrupt; the glue did not quit first at that time) filled
   the 1 GB heap (`OutOfMemoryError`). Cause (from the source): `Mount.watch` starts each region
   watcher with `Fiber.initUnscoped`, and only `mount.unmountAll`, the last step of `Runner.run`, stops them; an
   interrupt skips it. The watchers wake up every second (the repair timer of `Signal.observe`). I did not prove the
   exact reference path that keeps the whole runner. Glue workaround: `TuiHeadless` quits and waits before its scope
   ends (then 0 to 3 MB per 150 runs). `Tui.run` has no workaround: a cancel of the ZIO or `F` that runs it interrupts
   the runner in the same way, so it leaks too (from the code path; not measured).
2. FIRST-KEY RACE (RUNNER, shown more often by the facades). `stress/SplitProbe.test.scala`: start an app, press
   Enter at once, read the screen. Sometimes the model is 1 but the key's frame has 0 region updates and shows `n=0`.
   The screen catches up 200 to 1500 ms later (never lost in 1500 ms). Counts:

   | Case | No load (500 + 500 starts) | CPU contention (300 + 300 starts) |
   | --- | --- | --- |
   | ZIO facade, first key | 2 + 2 | 13 + 3 |
   | Cats facade, first key | 0 + 0 | 1 + 2 |
   | Plain Kyo runner, first key | 0 (500) | 0 + 1 |
   | Plain Kyo, handler with one scheduler hop | not run | 0 (300) |
   | Steady presses (one app, many keys), ZIO and Cats | 0 + 0 each | 0 + 0 each |

   The delay of about 1 s agrees with the 1 s repair interval of kyo's `Signal.observe`, so the most likely cause is a
   missed wakeup while a region watcher subscribes for the first time; the settle then sees an idle scheduler and
   flushes. This is not proven. Effect on the tests: in contention run 1 (`logs/test-contention-1.log`) the Cats quit
   test failed once for this reason (it reads the screen right after the first key). Without the pinned contention,
   all 9 runs of the three sets of final runs passed.
3. OUTPUT CAPTURE (RUNNER). `OutputGuard` swaps only `System.out` and `System.err`. When `scala.Console` loads before
   the run (one `println` before `Tui.run` is enough), `ZIO.logInfo`, `zio.Console.printLine` and Scala `println` in a
   handler go to the terminal and break the screen (`logs/pty-probe-check.log`, `ZioOutputProbeEarlyMain`). This
   confirms finding N10 of the runner report.
4. LOOK. A button in a column takes the full width of the column (`[ +    ...    ]`, 100 cells): the facade apps put
   their buttons straight into a `div`. I did not check what a browser shows for the same UI.
5. The facade apps have no quit key. Only Ctrl-C ends them (exit code 0). The old core's `TuiApp` has the exit codes
   130 and 143 (runner report, "What carries into PRs 3-5").
6. NOT TESTED: the ZIO and Cats Todo in a real PTY (headless only); resize; SIGTERM and SIGINT from outside; a pipe as
   stdin; idle CPU and latency of the facade apps (not measured); the full ZIO mirror app (`full-zio`; it was not in
   the task); a Dashboard (there is no facade Dashboard).

## What must change in the prototypes (I did not change them)

Runner (`.work/proto/kyoui-runner/src/`):

- R1 (`internal/Runner.scala` `run`, `internal/Mount.scala` `watch`): stop the region watchers also when the run is
  interrupted. For example, run the loop inside `Sync.ensure` that calls `mount.unmountAll` on the last world (keep it
  in an `AtomicRef`), or start each watcher with `Fiber.init` under a `Scope` that the run owns. Reason: the leak probe.
- R2 (`internal/Runner.scala` settle, `internal/Mount.scala`): do not flush a frame while a region watcher has not
  subscribed yet. For example, keep a new region in `pending` (the runner already has `pending` for component mounts)
  until its watcher posts its first value. Reason: the split probe. Upstream, proposal 1 of the runner report (a
  synchronous change hook on `Signal`) would remove the problem.
- R3 (`Tui.scala` `OutputGuard`): capture `scala.Console.out` and `scala.Console.err` too. The runner report (N10 and
  "What carries into PRs 3-5") names the old core's `internal/OutputCapture.scala` for this. Test it with
  `probes/OutputProbe.scala` (`ZioOutputProbeEarlyMain`).
- R4 (`Tui.scala` `TuiOptions`): the options hold Kyo types (`kyo.Duration` in `pendingTimeout`, `Settle`). A facade
  user cannot give options without Kyo types. The runner needs an option set that a facade can mirror.
- R5 (`Tui.scala` `Tui.quit`, `internal/Dispatch.scala` `Tui4sQuit`): the quit action is a Kyo `Local`. A facade
  handler cannot see it. The glue needs one extra Kyo fiber per run. Better: the runner gives the quit action as a
  value (for example `Tui.run(options)(quit => ui)`) or a run handle.

Facades (`.work/proto/kyoui-facades/`):

- F1 (`zio/src/Headless.scala`, `cats/src/Headless.scala`, `shared/kyo/tui4sproto/Headless.scala`): these are the HTML
  stand-in for the runner (in package `kyo`). With the real runner, the test kit of the facades is `TuiHeadless` (this
  folder). The integration compiles `shared` only because these two files need it.
- F2: move `glue/zio/Tui.scala` into `zio/src` and `glue/cats/Tui.scala` into `cats/src` (hand-written runtime, not
  generated). They must stay in the mirror packages (`unsafeToKyo` is `private[ui]`). The generated mirrors need no
  change.
- F3 (`cats/src/AmbientDispatcher.scala`): the error message names `tui4s.interop.cats.Tui.run`, `Tui.resource` and
  `TuiApp`. The real names are now `tui4s.interop.cats.ui.Tui.run` / `Tui.runWithQuit` and `TuiHeadless.start`
  (facade report O5).

## Gaps for the real modules

- Module graph: `tui4s-core` = the runner (Kyo, kyo-ui, jatatui). `tui4s-zio` and `tui4s-cats` = the generated mirror +
  the hand-written runtime + the glue of this folder; they depend on `tui4s-core`, because the glue calls
  `tui4s.kyoui.Tui.run` and `tui4s.kyoui.Headless.start`.
- The three runner fixes R1 to R3 before a release; R4 and R5 before the facade API is stable.
- A facade options type with ZIO / Scala types (for example `scala.concurrent.duration.FiniteDuration`), mapped to the
  runner's options (needs R4).
- Main classes: a ZIO `TuiApp` (a `ZIOAppDefault` that calls `Tui.run`) and a Cats `TuiApp` (an `IOApp`), with the
  old core's scheduler properties, exit codes, signal handling and output capture (runner report, "What carries into
  PRs 3-5"). ZIO's default loggers write to the console; decide if the ZIO `TuiApp` removes them during the run.
- Quit for Cats (see Q1) and the Ctrl-C exit code (see Q2).
- The test kit (`TuiHeadless`) goes into each facade module. It returns the runner's `FrameStats`. Its key type is
  the mirror's `Keyboard`, an alias of a kyo-ui data type (facade open item O2 still applies).
- The PTY checklist of the real modules needs rows for the ZIO and Cats apps (Counter and Todo), and the old extra
  cases (resize, SIGTERM, pipe, paste).
- The facade open items O1 to O8 of the facade report stay open; this work closes O8 (the facades now run on the
  terminal runner).

## Open questions for the user

1. Cats quit: keep the explicit `runWithQuit(quit => app)`, add an `IOLocal`-based `Tui.quit` for `IO` only, or keep
   one JVM-wide "current run" (a process has one terminal)?
2. Ctrl-C: keep exit code 0 (raw mode turns Ctrl-C into a key), or give 130 (the usual code for SIGINT)?
3. Until the runner fix R2 exists: should `TuiHeadless.start` wait a short time after the first frame (this hides the
   first-key race in tests), or stay strict (a test that reads the screen right after the first key can fail, rarely)?
   I kept it strict.
4. Facade options: a hand-written options type per facade, or generated from the runner's `TuiOptions`?

## Progress log

- 2026-10-03: read HANDOFF.md, DECISIONS.md, both prototype reports and their sources. Created this folder.
- 2026-10-03: wrote project.scala (reuses both prototypes through `//> using files`), the glue (`glue/zio/Tui.scala`, `glue/cats/Tui.scala`) and the mains (`mains/Mains.scala`). Next: compile.
- 2026-10-03: first compile PASS (exit 0, 9 s, no warning; `logs/compile-1.log`). The `//> using files` directive accepts directories, so `scala-cli compile --jvm system .` in this folder builds the runner, the shared headless, both mirrors, both runtimes and the four facade apps with the glue. No name or dependency clash: the packages differ (`tui4s.kyoui`, `tui4s.interop.zio.ui`, `tui4s.interop.cats.ui`, `apps.zio`, `apps.cats`, `kyo.tui4sproto`).
- 2026-10-03: exploration run (a temporary test that printed the screens; `logs/explore-1.log`; the test file is
  deleted). The UNCHANGED ZIO Counter and ZIO Todo run on the terminal runner through the glue `TuiHeadless.start`:
  Enter on the first button gives `Count: -1` (2 cells flushed); Tab moves the focus to `a-inc`; typing `Buy milk`
  into the auto-focused input shows the text; Enter adds `[ ]Buy milk` and `1 left` and clears the input; Tab, Tab and
  Space on the checkbox give `[x]~Buy milk~`, `0 left` and the `Clear done` button. One look issue: a button in a
  column takes the full width (`[ +      ...      ]`, 100 cells). CORRECTION (written later, same day): this entry
  first said that a focused empty input gets 0 rows. That was WRONG. My `grep -v '^\s*$'` removed the blank line of
  the input from the printed output. The raw log (`logs/explore-1.log`, lines 78-83) has the blank input row. A focused
  empty input shows no placeholder (`Paint.scala` line 252: the placeholder shows only when the input is not focused);
  this is by design in the runner.
- 2026-10-03: `test/ZioOnTerminal.test.scala` written: 5 tests (Counter, Todo, 15 items typed key by key, glue
  `Tui.quit`, handlers on the caller's runtime). First run: 5 of 5 PASS (`logs/zio-test-dev-1.log`). Next: the Cats
  suite and a negative control (a test that must fail, to show that the checks can fail).
- 2026-10-03: `test/CatsOnTerminal.test.scala` written: 5 tests (the same cases as the ZIO suite, with
  `TuiHeadless.startWithQuit` for the quit case and an `IOLocal` + own compute pool for the runtime case). Negative
  control: a temporary suite with a wrong count and a wrong focus id (ZIO) and a wrong count (Cats). All 3 control
  tests FAIL as they must (`logs/negative-control-zio.log`, `logs/negative-control-cats.log`); in the same run the ZIO
  and Cats suites passed 5 of 5 each. The control file is deleted. Next: 3 runs of the final suites.
- 2026-10-03 15:14 UTC (the entries above have no clock time; the times below are the end times of the named logs): 3 runs of
  `scala-cli test --jvm system .` (`logs/test-final-{1,2,3}.log`): each run exit 0, CatsOnTerminalSuite 5 of 5,
  ZioOnTerminalSuite 5 of 5 (suite times 1.6 to 1.8 s and 0.49 to 0.54 s; wall 6.0 to 6.6 s, incremental build).
  Load average 0.9 (the other agent was not compiling). Next: the PTY run of the ZIO Counter.
- 2026-10-03 15:16 UTC: assembly jar (`scala-cli --power package --jvm system . --main-class integration.ZioCounterMain
  -o <scratchpad>/kyoui-integration.jar --assembly --preamble=false -f`, 61 MB, not kept in `.work/`; `logs/package-1.log`).
  PTY run with `pty/run-all.sh <jar>` (expect, 100x30, `TERM=xterm-256color`): PTY part 10 of 10 PASS, screen part
  22 of 22 PASS (`logs/pty-checklist.log`, `logs/pty-check.log`, `logs/pty-screens.log`, `logs/pty-session.log`,
  `logs/pty-stty-*.log`). The first run had a wrong label for the first-frame time ("after the alternate screen"; the
  timer starts at the start command). I fixed the label and ran the PTY check again; the logs are from the second run.
- 2026-10-03 15:23 UTC: EXTRA output probe (`probes/OutputProbe.scala`, `pty/output-probe.exp`, `pty/probe-check.py`,
  `logs/pty-probe-session.log`, `logs/pty-probe-check.log`). A handler writes one line with each kind of output, in a
  real PTY. Result: with the plain mains, all 7 kinds (`ZIO.logInfo`, `zio.Console.printLine`, Scala `println`,
  `System.out.println`; Cats `IO.println`, Scala `println`, `System.out.println`) go to `tui4s.log`; none reaches the
  terminal. With ONE `println` BEFORE `Tui.run` (`ZioOutputProbeEarlyMain`), `ZIO.logInfo`, `zio.Console.printLine`
  and Scala `println` from the handler go to the TERMINAL while the UI runs and corrupt the screen (raw mode, so the
  lines make a staircase); only `System.out.println` still goes to the file. Cause (from the runner source):
  `OutputGuard` swaps only `System.out` / `System.err`; `scala.Console.out` keeps the stream that it had when
  `scala.Console` was loaded. This confirms finding N10 of the runner report on this machine.
- 2026-10-03 15:24 UTC: added one `useStream` test per suite (a ZStream / an fs2 Stream ticks 3 times; the screen must
  show `tick: 3`; no key, so these are Background frames). 3 runs again (`logs/test-final-b-{1,2,3}.log`): each run
  exit 0, CatsOnTerminalSuite 6 of 6, ZioOnTerminalSuite 6 of 6; load average 0.7 to 1.1. The older 5-test runs stay
  in `logs/test-final-{1,2,3}.log`.
- 2026-10-03 15:25 UTC: EXTRA: 2 runs under CPU contention (2 busy loops and the test JVM pinned to CPUs 2 and 3,
  `-XX:ActiveProcessorCount=4`, the method of the runner report; `logs/test-contention-{1,2}.log`). Run 1: ZIO 6 of 6,
  Cats 5 of 6: the Cats quit test FAILED: right after the first Enter, the screen showed `n=0`, not `n=1`. Run 2: 6 of 6
  and 6 of 6. Next: a stress probe to find out if this is a split frame (the runner's settle limit N2) or a lost update.
- 2026-10-03 15:38 UTC: split probe (`stress/SplitProbe.test.scala`, ignored unless `-Dprobe.rounds` is set). Run 1,
  200 rounds, no load (`logs/split-probe-noload-1.log`): ZIO first key 1 split, ZIO steady 1 split, Cats 0 and 0.
  Run 2, 500 rounds, no load (`logs/split-probe-noload-2.log`): ZIO first key 4 splits in 500. In all 4, the model
  (the facade ref) was 1, the frame of the key had 0 region updates, and the screen still showed `n=0` 200 ms later.
  ZIO steady 0 in 500. Then the Cats part HUNG: the JVM threw `OutOfMemoryError: Java heap space` (heap 1 GB; GC
  threads used about 100 s of CPU; `logs/split-probe-noload-2-jstack.txt`). I stopped the JVM after 11 minutes. So
  about 1000 starts and stops of a runner in one JVM fill 1 GB. Next: a leak probe to find what keeps the memory.
- 2026-10-03 15:47 UTC: leak probe (`stress/LeakProbe.test.scala`, ignored unless `-Dprobe.leak` is set;
  `logs/leak-probe-1.log`). 300 starts of the PLAIN Kyo runner (`tui4s.kyoui.Headless`, no facade) that end when the
  Scope ends (the runner fiber is interrupted): used heap after GC 14 MB -> 528 MB, about 1.75 MB per run. 300 starts
  that send a quit first and wait for the end: 528 MB -> 530 MB (about 6 KB per run: no leak). The ZIO glue phase then
  ran out of heap (OutOfMemoryError; the command hit my 500 s limit, exit 124). So the leak is in the RUNNER, not in
  the facades: an interrupted run does not stop its region watchers. From the runner source: `Mount.watch` starts each
  watcher with `Fiber.initUnscoped`, and only `mount.unmountAll`, the last step of `Runner.run`, stops them; an
  interrupt skips that step. A leaked watcher wakes up every second (the 1 s repair timer of `Signal.observe`) and
  keeps the inbox alive; the memory per run (about 1.75 MB) agrees with the whole runner being kept (the frames channel
  of 65536 places, the inbox, the canvas buffers). I did not prove the exact reference path.
- 2026-10-03 15:49 UTC: glue change: `TuiHeadless` (ZIO and Cats) now sends a quit and waits for the end of the run
  BEFORE its Scope / Resource interrupts the runner fiber. Leak probe again, 150 rounds per phase
  (`logs/leak-probe-2.log`): Kyo runner with quit +2 MB; ZIO glue (Scope ends, the glue quits first) +0 MB; ZIO glue
  with Ctrl-C +0 MB; Cats glue (Resource ends) +3 MB; Kyo runner INTERRUPTED +257 MB (1754 KB per run, the same as
  before). So the glue avoids the leak; the runner still has it (see "What must change").
- 2026-10-03 15:50 UTC: split probe with a 1.5 s check and a Kyo control case, 500 rounds each, no load
  (`logs/split-probe-noload-3.log`, `logs/split-probe-noload-4.log`). ZIO first key: 2 of 500 and 2 of 500 splits; in
  each, the model was 1, the key's frame had 0 region updates, the screen did NOT catch up in 200 ms but DID catch up
  in 1500 ms. ZIO steady: 0 of 500 (twice). Cats first key and steady: 0 of 500 (twice). Plain Kyo runner first key:
  0 of 500. So nothing is lost for good: the value comes about 1 s late, which is the repair interval of kyo's
  `Signal.observe` (it re-reads the signal every 1 s to repair a missed wakeup). Next: the same probe under CPU
  contention.
- 2026-10-03 15:51 UTC: the same split probe under CPU contention, 300 rounds (`logs/split-probe-contention-1.log`):
  ZIO first key 13 of 300 splits, Cats first key 1 of 300, plain Kyo first key 0 of 300, all steady cases 0 of 300.
  Each split caught up in 200 to 1500 ms (none lost). This explains the Cats quit-test failure of contention run 1 (it
  reads the screen right after the FIRST key). Next: a Kyo control whose handler suspends before it writes.
- 2026-10-03 15:53 UTC: split probe under contention again, with a second Kyo control (the handler makes one scheduler
  hop before it writes) (`logs/split-probe-contention-2.log`): ZIO first key 3 of 300, Cats first key 2 of 300, plain
  Kyo first key 1 of 300, Kyo with a hop 0 of 300, steady cases 0. So the first-key race is in the RUNNER (it also
  hits a plain Kyo app); the facades only hit it more often. My idea that "a handler that suspends" causes it is NOT
  supported by the hop control.
- 2026-10-03 15:53 UTC: final 3 runs after the glue change (`logs/test-final-c-{1,2,3}.log`): each run exit 0;
  CatsOnTerminalSuite 6 of 6, ZioOnTerminalSuite 6 of 6; the two stress suites are ignored (as designed). Load average
  2.6 to 2.8 (the other agent was busy). Next: the final sections of this report.
- 2026-10-03 15:54 UTC: new jar from the final sources (`logs/package-final.log`) and the PTY check again: PTY part 10
  of 10, screen part 22 of 22; first frame 990 ms (ZIO) and 1168 ms (Cats) after the start command. These are the
  PTY logs in `logs/` now.
- 2026-10-03: notes on tools. scala-cli `//> using exclude stress` also dropped the stress file when I passed it on
  the command line (no test ran; `logs/exclude-check.log`), so the stress suites use munit's `munitIgnore` and a
  system property instead. The log of the attempt with `using exclude` was overwritten by the 200-round run
  (`logs/split-probe-noload-1.log`).
- 2026-10-03: wrote the result sections above. Added an authorship mark to each test case. One more test run after
  these comment-only edits (`logs/test-after-marks.log`): exit 0, 6 of 6 and 6 of 6.
