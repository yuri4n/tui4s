# Inventory of the old stack (PRs 3-5, feat/zio, feat/cats)

Status: DONE (2026-10-03). Planning only: no checkout, no build, no push, no GitHub write.
Author: planning agent "inventory of the old stack".
Scope: what the old stack (own View/Keymap/Engine model) gives to the new design. The new design is
tui4s = a terminal backend for kyo-ui (`.work/DECISIONS.md`, fifth round).

Words used here:

- KEEP: take the file with no change, or with only a package or import change.
- ADAPT: take the file or its logic, and change it for kyo-ui.
- DROP: do not take the file. A "lesson" can still carry.
- Slot: the target PR of assumption A1. (a) = runner core, (b) = terminal and app, (c) = examples, ptycheck
  and bench. (zio), (cats) = facade PRs. (gen) = codegen project. (docs) = docs PR.
- ASSUMED: a working assumption that the user accepted with "I like it, go ahead". The user must still confirm
  it. A1 (PR shape a/b/c, PRs 3 and 4 closed or replaced), A2 (upstream issue drafted only), A3 (ZIO state type
  is a facade-owned `SignalRef[A]`), A4 (generated mirrors checked in, codegen sbt project, CI drift check).

Evidence: each fact names a git ref (all refs are `origin/<branch>`), a file, or a report section.
The short names RUNNER, FACADES, INTEGRATION mean `.work/proto/kyoui-runner/REPORT.md`,
`.work/proto/kyoui-facades/REPORT.md`, `.work/proto/kyoui-integration/REPORT.md`.

## 0. Facts about the branches

| Fact | Evidence |
| --- | --- |
| PR 1 `chore/cleanup` -> `main`; PR 2 `docs/docus` -> `chore/cleanup`; PR 3 `feat/core-1-model-view` -> `chore/cleanup`; PR 4 `feat/core-2-engine` -> `feat/core-1-model-view`; PR 5 `feat/core-3-terminal` -> `feat/core-2-engine`. All open, none merged. | `gh api repos/yuri4n/tui4s/pulls?state=all` |
| PR 3: 37 files, +4871 -1, 10 commits. PR 4: 37 files, +5330 -176, 6 commits. PR 5: 56 files, +5041 -71, 18 commits. Together: 111 paths. | `git diff --stat`, `gh api .../pulls/N/commits` |
| `feat/zio` = `feat/core-3-terminal` + 5 commits (26 files, +2554 -4). `feat/cats` = `feat/core-3-terminal` + 11 commits (25 files, +2490 -2). `feat/cats` is NOT on top of `feat/zio`: the two branches are siblings. | `git merge-base --is-ancestor`, `git log --oneline` |
| Each of PRs 3-5 has 1 review, from `chatgpt-codex-connector[bot]` only: PR 3 has 2 inline comments, PR 4 has 3, PR 5 has 3. No human review comment. | `gh api .../pulls/N/reviews`, `.../pulls/N/comments` |
| Three of these bot comments touch code that carries over and are NOT fixed on the branch heads: PR 5 `CountingBackend.scala:33` (P1, a draw races the restore, discussion_r4162064483); PR 5 `Crossterm.scala:103` (P2, `UnsatisfiedLinkError` is reported as `NativeAccessDenied`, discussion_r4162064496); PR 4 `Session.scala:150` (P2, the input thread is not stopped when the acquire fails, discussion_r4160557171). | same; heads `666a8e3`, `5a441a5` are the reviewed commits |
| PRs 3-5 do not change `release.yml`, `scripts/release.sh`, `project/ModuleVersioning.scala`, `project/Versions.scala` or any `compat.sbt`. | `git diff --name-status origin/chore/cleanup..origin/feat/core-3-terminal` |

## 1. Core files of PRs 3, 4 and 5

Totals at `origin/feat/core-3-terminal` (`wc -l` of `git show`):

| Group | Lines |
| --- | --- |
| Main files that carry (KEEP or ADAPT, rows OS-1 to OS-12) | 2556 |
| Test files that carry (ADAPT) | 1795 |
| Main files to DROP (old model and engine) | 4724 |
| Test files to DROP | 3723 |
| `examples/` (all) | 912 |
| `bench/` (all) | 911 |

### 1.1 Corrections to RUNNER "What carries into PRs 3-5"

I checked the RUNNER table against the files. These rows are wrong or not complete:

1. `testkit/Screen.scala` is not "keep". It imports `tui4s.Style` (an opaque type over the jatatui `Style`, in
   `Styling.scala`, PR 3, dropped) and `tui4s.TextField`. It must change its cell-style type.
2. `testkit/ScriptedInput.scala` is not "keep". It is an `InputPort` of the old `tui4s.Event` / `Key`.
3. `testkit/RecordingControl.scala` depends on `TerminalControl`, `TuiOptions` and `Screen`. It must change with them.
4. `internal/Session.scala` holds three parts: the `TerminalControl` trait with `HeadlessControl`, the `Session`
   release, and the `InputThread`. It uses the engine types `Item`, `Probe`, `Stats` and `Failed`, and it builds a
   jatatui `Terminal`. The runner does not use a jatatui `Terminal`: it has its own `Canvas` with a front and a back
   buffer (`kyoui-runner/src/tui4s/kyoui/internal/Paint.scala:21`). So the inline viewport
   (`Viewport.Inline`, built with `Terminal.withOptions`) does not carry for free.
5. `TextField.scala` is not "replaced by the runner". The runner edits an input by UTF-16 index
   (`Dispatch.scala:121-132`: `value.patch(caret, ..., 1)`). The old `TextField` edits by grapheme cluster
   (`java.text.BreakIterator`), and has Ctrl-A, Ctrl-E, Ctrl-U, Ctrl-K, Ctrl-W and the paste rule (line breaks
   become spaces). This logic must carry into the runner's input editing.
6. `Options.scala` is not in the RUNNER table. The old `TuiOptions` has NO Kyo type (`Int`, `Boolean`,
   `java.nio.file.Path`). The prototype `TuiOptions` holds `kyo.Duration` and `Settle`. INTEGRATION R4 asks for
   options without Kyo types: the old file is the precedent. `TuiError` (same file) also carries in part.
7. `OutputCapture.scala` does not fully fix INTEGRATION R3 ("capture `scala.Console.out` too"). The old file has the
   same limit and says so: `scala.Console` keeps the stream that `System.out` was when it first loaded. The old
   `TuiApp` avoids it because it calls `OutputCapture.install()` in its constructor, before user code. A plain
   `Tui.run` keeps the limit (documented in the `captureOutput` Scaladoc). The ZIO and Cats `TuiApp` must also
   install in their constructors (they did on `feat/zio` and `feat/cats`).
8. `CountingBackend.scala`: the class drops (the runner's `Canvas` counts cells), but its `close()` guard must carry.
   The guard stops output after the release starts, because the shutdown hook can release while a frame is drawn.
   The PR 5 bot comment (P1) shows that the guard alone is not enough: the release must wait for a draw in progress.
9. "`FacadeParity` waits for the facade mirror" is wrong. `project/FacadeParity.scala` compiles ONE app text under
   the import of each ecosystem. With a generated mirror, a ZIO app text is different from a Kyo app text
   (`.work/proto/SIDE-BY-SIDE.md`). The premise is false. DROP it; a screen-parity test can replace it (OS-18).
10. Trace: `Tracer`, `MemoryTrace` and `TraceWriter` are generic and carry. The JSON line schema is engine-specific
    and changes to `FrameStats`.
11. Bench: all probes import the old `tui4s.spi.Headless`, `tui4s.Key` and `ScriptedInput`. They are not "same
    metrics, keep": their drivers must be written again. The method (child JVM, `bench/BASELINE.md` format) carries.

The other RUNNER rows are correct: TerminalIO/Crossterm (isatty, `NativeAccessDenied`, shutdown hook, staged undo,
modes), the input thread, `EventMapping` (exhaustive over crossterm events), `LogSink`/`TuiLog`, `TuiApp`
(exit codes 130/143), `SchedulerDefaults` (keep as is), launchers, ptycheck cases (SIGTERM, pipe, native access
denied, resize, paste: all present in `examples/ptycheck/checklist.exp`), the Linux `terminal` CI job.

### 1.2 Per-file table: PR 3 (`chore/cleanup..feat/core-1-model-view`)

Paths below `modules/core/src/main/scala/tui4s/` are written `<m>/`; below `modules/core/src/test/scala/tui4s/`
they are written `<t>/`.

| File | Lines added | Action | Reason and evidence | OS |
| --- | --- | --- | --- | --- |
| `build.sbt` | +3 | KEEP | `-Wconf:msg=@nowarn annotation does not suppress any warnings:s`: Kyo inline methods add this warning; it applies to kyo-ui too. | OS-39 |
| `modules/core/build.sbt` | +12 -1 | ADAPT | kyo-test, `Test / fork`, `-Xmx1g`: keep the fork; framework per D2. | OS-38 |
| `<m>/App.scala` | 124 | DROP | Old model (`init`/`update`/`view`/`sources`). kyo-ui gives the UI value. | OS-13 |
| `<m>/Event.scala` | 149 | DROP | Old public events. Runner uses kyo-ui `KeyboardEvent` and its own messages. | OS-13 |
| `<m>/Focus.scala` | 55 | DROP | Old focus ring. Runner has kyo-ui focus (tabIndex, focusGroup, focusTrap: RUNNER "Investigation notes"). | OS-13 |
| `<m>/Keymap.scala` | 177 | DROP | Old key bindings. kyo-ui handlers replace them. | OS-13 |
| `<m>/Options.scala` | 128 | ADAPT | `TuiOptions` (Kyo-free), `Viewport`, `TuiError` (3 of 5 cases); `Stats` drops (-> `FrameStats`). See 1.1 item 6. | OS-5 |
| `<m>/Source.scala` | 41 | DROP | Old sources. Hooks (`useHold`, `useFold`, `useInterval`) replace them. | OS-14 |
| `<m>/Styling.scala` | 360 | DROP | Old `Color`/`Style`/`Line`. kyo-ui `Style` replaces them. | OS-13 |
| `<m>/TextField.scala` | 156 | ADAPT (logic) | Grapheme editing and Ctrl keys carry into the runner's input editing (1.1 item 5). | OS-11 |
| `<m>/View.scala` | 421 | DROP | Old view tree. | OS-13 |
| `<m>/dsl.scala` | 197 | DROP | Old DSL. | OS-13 |
| `<m>/internal/CountingBackend.scala` | 55 | DROP (lesson) | Class drops; `close()` guard carries (1.1 item 8). | OS-10 |
| `<m>/internal/Engine.scala` | 208 | DROP | Old engine (583 lines, 25 `var`s at PR 5: RUNNER "Result: confirmed" 9). | OS-14 |
| `<m>/internal/Painter.scala` | 500 | DROP | Paints the old view with jatatui widgets through `Terminal.draw`. Runner `Paint.scala` replaces it. | OS-13 |
| `<m>/internal/package.scala` | 7 | KEEP | `discard` helper for `-Wvalue-discard` / `-Wnonunit-statement`. | OS-12 |
| `<m>/package.scala` | 77 | DROP (rewrite) | Package Scaladoc describes the old model. A new one describes the runner. | OS-47 |
| `<m>/testkit/Screen.scala` | 165 | ADAPT | Screen text, `find`, `region`, `diffScreen`, `assertScreen`, wide-character rows. Style type changes (1.1 item 1). | OS-12 |
| `<m>/testkit/TuiTest.scala` | 199 | DROP | Synchronous fold of the old model. | OS-13 |
| `<t>/CounterTest`, `FocusTest`, `FrameContractTest`, `KeyTest`, `KeymapTest`, `NextTest`, `PainterTest`, `StylingTest`, `TodoTest`, `TuiTestTest`, `ViewTest`, `fixtures/CounterApp`, `fixtures/TodoApp` | 1523 | DROP | Tests of the old model. | OS-16 |
| `<t>/NameClashTest.scala`, `StrictEqualityTest.scala`, `TuiSuite.scala` | 133 | DROP (lesson) | Ideas carry as two small new tests: (1) `import kyo.*` + the tui4s import + the test kit import have no ambiguous name; (2) `FrameStats`, `TuiOptions`, `Screen` have `CanEqual`. | OS-16 |
| `<t>/ScreenTest.scala` | 55 | ADAPT | Goes with `Screen`. | OS-15 |
| `<t>/TextFieldTest.scala` | 126 | ADAPT | Goes with the editing logic (OS-11). | OS-15 |

### 1.3 Per-file table: PR 4 (`feat/core-1-model-view..feat/core-2-engine`)

| File | Change | Action | Reason and evidence | OS |
| --- | --- | --- | --- | --- |
| `<m>/Keymap.scala`, `<m>/TextField.scala`, `<m>/dsl.scala` | small M | (as PR 3) | See PR 3 rows. | OS-13, OS-11 |
| `<m>/Source.scala` | +241 -4 | DROP | Sources and `NotForeign` (refuses a `zio.ZIO`, `ZStream`, `IO`, `fs2.Stream`, `Future` in `source.task`). Lesson: the same check can guard tui4s-owned Kyo hooks (`useEffect`, `useInterval` take `Any < Async`). | OS-14 |
| `<m>/Tui.scala` | +80 | ADAPT | `Tui.run`/`Tui.start`, `TuiHandle`, the run seams. New shape: `Tui.run(ui)`. | OS-8 |
| `<m>/internal/Engine.scala` | +463 -88 | DROP | Old engine. | OS-14 |
| `<m>/internal/Items.scala`, `Macros.scala`, `Supervisor.scala`, `Watch.scala` | 42+35+339+48 | DROP | Inbox items, `NotForeign` macro, source supervision, watch. Signals and the runner's region engine replace them. | OS-14 |
| `<m>/internal/RenderLoop.scala` | +385 | DROP (lesson) | Old loop. Lesson: one frame per BATCH of input (the input thread reads up to 512 events per batch). The runner settles and flushes per event; a paste of 1000 keys gives 1000 frames (D7). | OS-14 |
| `<m>/internal/Session.scala` | +225 | ADAPT | Release order, join with timeout, interrupt flag kept, idempotent close, `InputThread` (poll then read, platform thread, backpressure). Fix the bot P2 (input thread leak on a failed acquire). | OS-2 |
| `<m>/internal/Trace.scala` | +326 | ADAPT | Generic writer carries; schema changes. | OS-9 |
| `<m>/spi/Headless.scala` | +35 | ADAPT | Becomes the runner's `Headless` entry (Kyo API, `TestBackend`). | OS-8 |
| `<m>/spi/InputPort.scala` | +18 | ADAPT | Element type changes from `tui4s.Event` (D4). | OS-3 |
| `<m>/spi/Sources.scala` | +72 | DROP | Old sources SPI. | OS-14 |
| `<m>/testkit/LiveTest.scala` | +319 | DROP (lesson) | Live kit under virtual time. Lesson: a test kit must say how it treats a handler that waits on a test clock (OS-31). | OS-14 |
| `<m>/testkit/RecordingControl.scala` | +88 | ADAPT | Records the session steps for the terminal tests. | OS-12 |
| `<m>/testkit/ScriptedInput.scala` | +59 | ADAPT | Scripted input for headless runs and probes; event type changes. | OS-12 |
| `<m>/testkit/TuiTest.scala` | +262 -59 | DROP | Old kit. | OS-13 |
| `<m>/testkit/ViewTree.scala` | +147 | DROP | Old view tree printer. | OS-14 |
| `<t>/DashboardTest`, `EngineTest`, `GuardTest`, `LiveKitTest`, `RuntimeContractTest`, `RuntimeTest`, `SourceTest`, `TraceTest`, `WatcherTest`, `fixtures/DashboardApp`; changes to `CounterTest`, `NextTest`, `TodoTest`, `fixtures/TodoApp` | 1882 new + 147 changed | DROP | Tests of the old engine. | OS-16 |
| `<t>/SessionTest.scala` | +107 | ADAPT | Release order with the input thread; failure after build closes all. | OS-15 |
| `modules/core/src/test/scala/zio/ZIO.scala` | +6 | DROP | Stand-in `zio.ZIO` for the `NotForeign` test. | OS-16 |

### 1.4 Per-file table: PR 5 (`feat/core-2-engine..feat/core-3-terminal`)

| File | Change | Action | Reason and evidence | OS |
| --- | --- | --- | --- | --- |
| `.github/workflows/ci.yml` | +16 | KEEP | Job `terminal`: JDK 25, `expect`, `examples/ptycheck/run.sh`. | OS-41 |
| `.gitignore` | +3 | KEEP | `bench/results/`. | OS-40 |
| `AGENTS.md` | +13 -1 | KEEP (edit) | `bench` row, launchers, ptycheck, "no `sbt run`" rule. `PaintBench` command row changes. | OS-47 |
| `README.md` | +64 | ADAPT | "Quick start" shows the old Counter (`TuiApp[Int]`, `Keymap`, `TuiTest`). JVM flags, launchers, scheduler flags paragraphs keep. | OS-48 |
| `bench/BASELINE.md` | +89 | ADAPT | Method keeps; numbers are old-engine numbers. | OS-21 |
| `bench/.../BurstProbe`, `DashboardProbe`, `IdleProbe`, `LatencyProbe`, `ProbeChild`, `Probes`, `QuitProbe`, `SoakProbe` | 669 | ADAPT | Same metrics; drivers use the old `Headless`, `Key`, `ScriptedInput` (1.1 item 11). | OS-21 |
| `bench/.../EngineBench`, `PaintBench` | 153 | DROP | JMH benches of the old `Engine` and `Painter`. Replace with layout and paint benches of the runner. | OS-21 |
| `build.sbt` | +64 -1 | ADAPT | `bench` project (sbt-jmh), `launchers` task, examples test settings KEEP; `FacadeParity.settings` DROP. | OS-20, OS-40, OS-44 |
| `examples/README.md` | +129 | ADAPT | Run, test, checklist text; rows for the new apps. | OS-50 |
| `examples/ptycheck/analyze.py` | +133 | DROP | Replace with the prototype screen checker (`kyoui-runner/pty/vt.py` + `check.py`: screen-level checks after a partial repaint). | OS-19 |
| `examples/ptycheck/checklist.exp`, `run.sh` | +165 | ADAPT | Cases keep (counter + - q, Ctrl-C, `kill -TERM` 143, todo paste, Tab, Space, Esc, dashboard pause, resize via WINCH, arrows, native access denied, stdin pipe). Screen texts change. | OS-19 |
| `examples/src/main/.../counter`, `dashboard`, `todo` | 279 | ADAPT (rewrite) | Old-model apps. Rewrite on kyo-ui from `kyoui-runner/apps/` (Counter, Todo, Dashboard, both component styles). | OS-18 |
| `examples/src/parity/CounterBody.scala.txt`, `examples/src/test/.../ParitySuite.scala`, `project/FacadeParity.scala` | 147 | DROP | Premise false under a generated mirror (1.1 item 9). | OS-18, OS-44 |
| `examples/src/test/.../CounterSuite`, `DashboardSuite`, `TodoSuite` | 143 | ADAPT (rewrite) | Use `TuiTest`; rewrite on the runner `Headless`. | OS-18 |
| `modules/core/build.sbt` | +20 -2 | ADAPT | Keep fork flags, `--enable-native-access` for tests, and the `-Wconf ... EventMapping\.scala:e` exhaustivity rule. tasty-query and the `src/surface` directory move to the facades. | OS-38, OS-17 |
| `<m>/Options.scala` | +12 -4 | ADAPT | `captureOutput`, `logFile`, `trace` docs. | OS-5 |
| `<m>/Tui.scala` | +38 -1 | ADAPT | Real-terminal `run`/`start` over `TuiRun`. | OS-8 |
| `<m>/TuiApp.scala` | +136 | ADAPT | Main class: scheduler flags, capture install, `block` with the `tui4s-stop` hook, exit codes 0/1/130/143, error printed after the restore. Must take a UI value, not `App[S]`; `source` and `onExit(state)` drop. | OS-6 |
| `<m>/internal/CountingBackend.scala` | +23 -10 | DROP (lesson) | `close()` guard; bot P1. | OS-10 |
| `<m>/internal/Crossterm.scala` | +155 | KEEP (fix) | `Crossterm` trait (testable), `NativeCrossterm`, `Tty.isatty` by FFM, `CrosstermInput`. Fix the bot P2 on `loadError`. | OS-1 |
| `<m>/internal/EventMapping.scala` | +103 | ADAPT | Exhaustive crossterm mapping (key, mouse, paste, resize, focus). Output becomes kyo-ui `KeyboardEvent` plus runner messages. It replaces the prototype `Keys.scala` (which drops mouse, paste and focus). | OS-3 |
| `<m>/internal/LogSink.scala` | +134 | KEEP | Log file or memory ring printed after the restore. | OS-4 |
| `<m>/internal/OutputCapture.scala` | +179 | KEEP | Forwarding `System.out`/`err`; replaces the prototype `OutputGuard` (RUNNER N10). Limit: 1.1 item 7. | OS-4 |
| `<m>/internal/RenderLoop.scala`, `<m>/internal/Session.scala`, `<m>/internal/Trace.scala` | small M | (as PR 4) | See PR 4 rows. | OS-14, OS-2, OS-9 |
| `<m>/internal/TerminalIO.scala` | +188 | KEEP (small change) | Staged enter with undo, `NotATerminal` before raw mode, modes from options, shutdown hook `tui4s-restore`, leave runs every step. Change: inline-viewport branch reads `Terminal` (D5). | OS-1 |
| `<m>/internal/TuiLog.scala` | +90 | KEEP | Kyo `Log.Unsafe` bound with `Log.let` to the run's sink. | OS-4 |
| `<m>/internal/TuiRun.scala` | +56 | ADAPT | Binds `LogSink`, `TuiLog`, `TerminalIO` around the run; takes a UI. | OS-8 |
| `<m>/spi/SchedulerDefaults.scala` | +63 | KEEP | 3 scheduler properties, opt-out `-Dtui4s.schedulerDefaults=false`; touches no Kyo class. RUNNER N5: idle about 14% -> 4% (headless). | OS-7 |
| `modules/core/src/surface/scala/tui4s/SurfaceCheck.scala` | +98 | ADAPT (move) | Core API is kyo-ui by design, so the core check does not apply. The facades need it (prototype `shared-test/SurfaceLeaks.test.scala`, with the alias allow-list). | OS-17 |
| `<t>/EventMappingTest.scala` | +187 | ADAPT | Expected values become kyo-ui events. | OS-15 |
| `<t>/FakeCrossterm.scala` | +141 | KEEP | Fake terminal for the tests. | OS-15 |
| `<t>/KyoFreeSurfaceTest.scala` | +53 | DROP | Core is not Kyo-free any more (1.4 SurfaceCheck row). | OS-17 |
| `<t>/NativeTerminalTest.scala` | +88 | ADAPT | `isatty` true for a PTY, false for a closed fd; app fixture changes. | OS-15 |
| `<t>/OutputCaptureTest.scala` | +187 | KEEP | No old-model reference. | OS-15 |
| `<t>/SchedulerDefaultsTest.scala` | +72 | KEEP | No old-model reference. | OS-15 |
| `<t>/SessionTest.scala` | +45 | ADAPT | See PR 4 row. | OS-15 |
| `<t>/TerminalIOTest.scala` | +507 | ADAPT | 17 cases: order of open/close, modes, NotATerminal, NativeAccessDenied, failing mode command, failing release step, output after restore, log levels, log file, shutdown hook, `Tui.start`. The app fixture changes (39 old-model references). | OS-15 |
| `<t>/TuiAppTest.scala` | +280 | ADAPT | Exit codes, `onExit`, SIGTERM with both hooks, `System.exit` in a handler. Fixture changes. | OS-15 |
| `project/FacadeParity.scala` | +84 | DROP | 1.1 item 9. | OS-44 |
| `project/plugins.sbt` | +5 -4 | KEEP | sbt-jmh 0.4.8. | OS-40 |

## 2. feat/zio and feat/cats under the generated mirror

### 2.1 What survives

| Part | Branch and file | Action | Reason and evidence | OS |
| --- | --- | --- | --- | --- |
| Vendored Kyo-ZIO bridge | `feat/zio`: `internal/AmbientZIOs.scala` (131) + `AmbientZIOsSuite.scala` (229, 9 tests) | KEEP | Byte-identical to `kyoui-facades/zio/src/AmbientZIOs.scala` except the package (`diff`, this session). The suite imports only `kyo` and `zio`. Fix the stale texts of FACADES O5. | OS-22 |
| Vendored Kyo-Cats bridge | `feat/cats`: `internal/CatsBridge.scala` (208), `internal/AmbientDispatcher.scala` (39), `CatsBridgeSuite.scala` (264), `DispatcherSuite.scala` (75) | KEEP | Same: identical except the package. Keep `Dispatcher.parallel(await = false)` (FACADES F8). Fix FACADES O5 / INTEGRATION F3 texts. | OS-23 |
| Facade `TuiApp` | `feat/zio`: `TuiApp.scala` (47); `feat/cats`: `TuiApp.scala` (53) | ADAPT | Keep: constructor calls `SchedulerDefaults.applyIfUnset()` and `OutputCapture.install()`; ZIO turns off its default console loggers while the UI is up; Cats turns off the CPU starvation checker (it prints to stderr). Drop: `extends tui4s.App[S]`, `source`. Add `args` (ZIO finding api-2). | OS-24 |
| Facade run entry | `feat/zio`: `Tui.scala` (149); `feat/cats`: `Tui.scala` (225) | ADAPT | Keep: typed failure (`IO[TuiError, S]`, `F[S]` failing with `TuiError`), `quietly` (logger removal in its own `ZIO.scoped`), `SchedulerDefaults.applyIfUnset()` at run, Cats `Tui.resource` as a CE bracket (never a Kyo `Scope`), the Cats `end` waiter fiber (nothing interrupts it, so a cancelled wait does not stop the app: `cats-impl.md` "Open issues"). Merge with the INTEGRATION glue (`run(app)`, `quit`, `runWithQuit`, `withQuit`). The INTEGRATION ZIO glue returns `UIO[Unit]`: it loses `NotATerminal`; the real module must fail with `TuiError`. | OS-25 |
| Old model re-exports and sources | `feat/zio`: `exports.scala`, `Source.scala`, `internal/ZioRunner.scala`, `internal/ZioSources.scala`; `feat/cats`: `exports.scala`, `Source.scala` | DROP | Re-export the old model. The generated mirror plus `Bridge`, `Hooks`, `SignalRef` / `KyoSignallingRef` replace them. | OS-26 |
| Old live test kits | `feat/zio`: `internal/ZioLive.scala`, `testkit/LiveTuiTest.scala`; `feat/cats`: `testkit/LiveTuiTest.scala` | DROP | The INTEGRATION `TuiHeadless` (over the runner `Headless`) replaces them. | OS-27 |
| Old facade tests | `feat/zio`: `BackpressureSuite`, `TestClockSuite`, `ZioFacadeSuite`, `ZioSuite`, `fixtures`, `LiveTuiTestSuite`; `feat/cats`: `CatsFacadeSuite`, `LiveTuiTestSuite`, `ResourceSuite`, `SourceSuite`, `fixtures/*` | DROP (lessons) | Test the old model. Lessons that carry as new tests: a cancel at any time of a run leaves no terminal entered (`feat/cats` 9f4cc05); a cancelled wait does not stop the app (`ResourceSuite`); a burst of input is bounded (backpressure). | OS-27 |
| Tests that adapt | `feat/zio`: `TuiAppSuite` (scheduler flags set; NotATerminal without a terminal); `feat/cats`: `NativeTerminalSuite` (real crossterm, no TTY: `NotATerminal`, `System.out` unchanged); both `KyoFreeFacadeSuite` | ADAPT | Behaviour still required. `KyoFreeFacadeSuite` becomes the prototype `SurfaceLeaks` check with the alias allow-list (FACADES O2). | OS-28 |
| Facade examples | `feat/zio`: `ziocounter`, `ziodashboard` + suites + `examples/zio.sbt`; `feat/cats`: `catscounter`, `catsdashboard` + suites + `examples/cats.sbt` | ADAPT | Rewrite on the mirror (FACADES `zio/apps`, `cats/apps` have Counter and Todo; no Dashboard yet: INTEGRATION "not tested"). Keep the pattern "one `examples/<m>.sbt` per facade" (no conflict in `build.sbt`); drop its `FacadeParity` line. | OS-29 |
| Facade build | `modules/zio/build.sbt`, `modules/cats/build.sbt` on both branches | ADAPT | Keep `Test / fork` and the JVM flags; tasty-query (test). Remove the `src/surface` directory trick or replace it (OS-17). Align fs2: prototypes use 3.12.2, `modules/cats/build.sbt` has 3.14.0. ZIO version: `val zioVersion` in `modules/zio/build.sbt`, read by `examples/zio.sbt` (D10). | OS-30 |
| Launcher naming | `build.sbt` on both branches | KEEP (one) | Both change the same line: `feat/zio` `main.split('.')(1)`, `feat/cats` `main.split('.').init.last` (commit f84f6f8). Same result for `examples.<pkg>.<Main>`; take the `feat/cats` form. | OS-20 |

### 2.2 The 14 confirmed ZIO findings in the new design

Source: `.work/impl/zio-verify-{api,runtime,spec}.md`. Confirmed: api-1, api-2, api-3, api-6, runtime-1 to
runtime-4, spec-1 to spec-6 (14). Refuted: api-4, api-5. Several findings are one problem seen twice; they are
grouped.

| Findings | Old problem (one line) | Status in the new design | Evidence | Action | OS |
| --- | --- | --- | --- | --- | --- |
| api-1, runtime-3 | Live kit `settle` waits while a task sleeps on the ZIO `TestClock`: `press` never returns. | APPLIES in a new form. kyo-ui runs a handler inline in its dispatch and the bridge waits for the ZIO (FACADES F7; INTEGRATION "How it works" step 3). A handler that sleeps on the caller's `TestClock` blocks the runner fiber, so `TuiHeadless.key` never returns (from the code path; not run). | FACADES F7; glue `TuiHeadless.key = AmbientZIOs.run(k.key(...))` | Document the clock rule in the kit; test with `TestClock`; consider a fork for long handlers (runner decision). | OS-31 |
| api-2 | ZIO `TuiApp` has no `args`. | APPLIES. Easier now: the app is a ZIO value, so its environment can include `ZIOAppArgs`. | zio-verify-api.md | Give `args` (or `ZIOAppArgs`) to the app value. | OS-24 |
| api-3 | Two wildcard imports (`tui4s.testkit.*`, `tui4s.interop.zio.testkit.*`) make `LiveTest` ambiguous. | APPLIES as a rule. The two mirrors define `div`, `button`, `Keyboard` at the top level of their packages (INTEGRATION "Folder content"). A facade user must need ONE import; the facade test kit must re-export (not define again) shared names such as `Screen`. | zio-verify-api.md; INTEGRATION | Name-clash test per facade (OS-16 lesson). | OS-32 |
| api-6, spec-6 | A `scala.Console` bound before `Tui.run` sends ZIO `Console` and the default logger over the UI. | APPLIES unchanged. Reproduced on the new runner (`ZioOutputProbeEarlyMain`, INTEGRATION "What does not work" 3). | zio-verify-api.md; INTEGRATION | Facade `TuiApp` installs the capture in its constructor; `Tui.run` swaps the ZIO `Console` service and loggers for the run, or documents the limit. | OS-33 |
| runtime-1, spec-2 | Live-kit acquire is uninterruptible (kyo-zio `ZIOs.run` `uninterruptibleMask` inside `acquireRelease`), and its first `settle` can wait forever. | NO TRIGGER in the INTEGRATION glue: `TuiHeadless.start` forks the run (`forkScoped`) and waits on a ZIO `Promise`; the finalizer quits and waits at most 5 s. The mechanism (uninterruptible acquire around a Kyo wait) stays. | `kyoui-integration/glue/zio/Tui.scala` | Rule: no unbounded Kyo wait inside a ZIO acquire. Test: a `timeout` around `TuiHeadless.start` ends. | OS-34 |
| runtime-2, spec-1 | A race with a long-lived Kyo promise (`done`) leaves one `onComplete` node per interrupted wait until the run ends. | PARTLY. No `states` stream and no `done` race in the glue. The facade `SignalRef.changes` has the same Kyo property: an interrupted wait keeps its callback until the next change (FACADES O7). Bounded by the next change, not by the run. | zio-verify-runtime.md; FACADES O7 | Rule: never race or time out a wait on a long-lived Kyo promise from ZIO/F; wait on a ZIO `Promise` / CE `Deferred`. Stress test of `changes` with interrupts. | OS-35 |
| runtime-4, spec-5 | An interrupt of `stop` (join of the run fiber) interrupts the run: a clean stop becomes `die(Interrupted)`. | NO TRIGGER in the glue: quit completes a ZIO `Promise`; `awaitEnd` polls `done` every 5 ms; nothing joins the run fiber. The Kyo join cascade stays (`cats-impl.md` "Open issues": also true for Kyo users of the old core). | glue; cats-impl.md | Rule: a facade never joins a Kyo run fiber from an interruptible wait (Cats `end` fiber pattern). Applies also to the Kyo `Headless.awaitEnd` / any future `TuiHandle.await`. | OS-34 |
| spec-3 | `Tui.start` removes the default loggers in the caller's `Scope`; on a failed start, logs of a `catchAll` in that scope are lost. | APPLIES when logger removal comes back (INTEGRATION "Gaps": "decide if the ZIO `TuiApp` removes them"). | zio-verify-spec.md | Remove loggers in an own `ZIO.scoped` around the run only (the old `quietly`). | OS-33 |
| spec-4 | Two weak tests (`every` does not check the runtime; `folds <= 1001` does not isolate the equal-skip). | OBSOLETE: the tests and `every` drop. The lesson is met: the prototype tests check the runtime by value (`seen: caller-42 on caller-zio-pool`, INTEGRATION "What works"). | zio-verify-spec.md | None. | OS-36 |

The Cats facade had no review (task statement). Its implementation notes list four open issues
(`.work/impl/cats-impl.md` "Open issues"): the core join cascade (OS-34), the `import fs2.*` clash with `text(...)`
(the subset Cats mirror `cats/generated/CatsUi.scala` has no `def text`, so no clash today; re-check the full
mirror), the missing README/checklist rows (OS-19), and
`Tui.resource` verified only up to `NotATerminal`.

## 3. Build and CI

`chore/cleanup` is the base. Changes on `feat/core-3-terminal` and on the facade branches are in sections 1 and 2.
This section lists what the new PRs must do.

| Topic | Today | New design | OS |
| --- | --- | --- | --- |
| kyo-ui dependency | Not present. `project/Versions.scala` has `scala`, `kyo`, `jatatui`, `munit`. | kyo-ui is released with Kyo: `"io.getkyo" %% "kyo-ui" % Versions.kyo` in `modules/core/build.sbt`. No new constant in `Versions.scala` is needed. kyo-ui brings kyo-http, kyo-net, kyo-config, kyo-schema-json (RUNNER "Investigation notes"): check the POM size. zio and cats get kyo-ui through core. | OS-37 |
| jatatui-widgets | In `modules/core/build.sbt` since PR 1. The old `Painter` used widgets. | The runner imports no `jatatui.widgets` class (`grep`, this session). Keep (for later `Chart`, `Img`) or drop (D6). | OS-37 |
| Test framework | Core: kyo-test (PR 3, DECISIONS round 4). zio, cats, examples: munit. | Runner prototype tests are munit (`kyoui-runner/project.scala`, munit 1.1.1); all facade tests are munit 1.3.6. Decide (D2). | OS-38 |
| Compiler flags | `-Wunused:all -Wvalue-discard -Wnonunit-statement -Yexplicit-nulls` + PR 3 `-Wconf` for `@nowarn`. | The prototypes compile with `-Wunused:imports` only (`project.scala` of each). The port must pass the strict flags. Real effort, mostly in the runner (2257 lines). | OS-39 |
| Exhaustivity rule | `-Wconf:msg=match may not be exhaustive&src=.*/EventMapping\.scala:e` (PR 5). | Keep; it guards the crossterm mapping. | OS-3 |
| Test JVM | `Test / fork`, `-Xmx1g`, `--sun-misc-unsafe-memory-access=allow`, `--enable-native-access=ALL-UNNAMED` (PR 5, facades). | Keep. The runner needs `-Xss16m` for scala-cli; sbt reads `.jvmopts` (`-Xss16m`). | OS-38 |
| bench project | `bench` with sbt-jmh 0.4.8, depends on core, zio, cats, examples; `bench/results/` ignored. | Keep the project. Probes rewritten (OS-21). | OS-40 |
| Launchers | `examples/launchers` task (PR 5); script name from the package (`feat/cats`). | Keep. | OS-20 |
| CI `sbt` job | `sbt -v check` (= fmtCheck, `Test/compile`, `test`, `versionPolicyCheck`), `sbt -v versions`. | Keep. Add the drift check (OS-42). | OS-41 |
| CI `terminal` job | PR 5: JDK 25, `apt-get install expect`, `examples/ptycheck/run.sh`. | Keep. Add ZIO and Cats rows (OS-19). | OS-41 |
| Codegen (ASSUMED A4) | None. The prototype generator is scala-cli (`kyoui-facades/gen`, tasty-query 1.9.0). | New unpublished sbt project `codegen` (`publish / skip`, not part of a release). It writes the mirrors into `modules/zio/...` and `modules/cats/...` (checked in). CI: run it, then `git diff --exit-code`. The output is inside `modules/<m>`, so `ModuleVersioning` sees a mirror change; a codegen-only change does not mark zio/cats unreleased (correct: only the output ships). | OS-42 |
| `compat.sbt` | All three `Compatibility.None` (PR 1). PRs 3-5 and the facade branches do not change them. | Nothing is released (DECISIONS round 3), so `None` stays. No change. | OS-43 |
| `FacadeParity` | `project/FacadeParity.scala`, used in `build.sbt`, `examples/{zio,cats}.sbt`. | DROP. | OS-44 |
| Release files | `release.yml`, `scripts/release.sh`, `ModuleVersioning.scala` (PR 1). | No change needed by the rework. | OS-45 |
| Surface check location | `modules/core/src/surface/scala` added to the facade tests by `unmanagedSourceDirectories` (PR 5, facade builds). | Core no longer needs it. Put the check in one place that both facades compile (same trick from a neutral directory, or a small unpublished test project). | OS-17 |

## 4. Docs and rules that describe the old model

| File (ref) | Old-model text | Change | OS |
| --- | --- | --- | --- |
| `AGENTS.md` (`chore/cleanup`) | "tui4s is a library for declarative terminal user interfaces ... It wraps jatatui"; "The `zio` and `cats` modules let ZIO and Cats Effect users write tui4s apps without Kyo types". | Say: tui4s is a terminal backend (runner) for kyo-ui; the facades are a generated mirror. Add rules: never edit generated mirror files, run the codegen project; type handler values `: Any < Async` (lift trap, RUNNER O5); the runner calls no `private[kyo]` member (RUNNER N9). | OS-47 |
| `AGENTS.md` (PR 5) | Command row `bench/Jmh/run ... tui4s.bench.PaintBench`. | Change the bench name. Other PR 5 rules stay (no `sbt run`, launchers, ptycheck before a release, run benches alone). | OS-47 |
| `AGENTS.md` (`docs/docus`) | Section "Documentation" (Docus, Vercel, never deploy). | Neutral. Keep. Merge with the PR 5 edit (different sections). | OS-47 |
| `README.md` (PR 5) | "Quick start": `TuiApp[Int]` with `Keymap`, `update`, `view`; test with `TuiTest`. | Rewrite with a kyo-ui Counter (hooks style) and the runner `Headless`. Keep the JVM flags, launchers and scheduler-flags paragraphs. | OS-48 |
| `README.md` (`chore/cleanup`) | Neutral (modules table, requirements). | Add "UI DSL: kyo-ui". | OS-48 |
| `modules/core/.../package.scala` (PR 3) | Package Scaladoc of the old model ("An app is a set of pure functions of one immutable state"). | New Scaladoc. | OS-13 |
| `docs/content/index.md` (`docs/docus`) | "One model, three effect systems"; "tui4s draws a frame only when your state changes". | Say "one UI DSL (kyo-ui)"; "repaints only the regions whose signals changed, and sends only the changed cells". | OS-49 |
| `docs/content/1.getting-started/1.installation.md` | `run / fork := true`, `run / connectInput := true` as the way to run. | Conflicts with the PR 5 rule "do not start a TUI with `sbt run`". Point to a launcher or a packager. Mention kyo-ui. | OS-49 |
| `docs/content/1.getting-started/2.versions.md`, `2.ai/1.agents.md` | Neutral. | No change. | OS-49 |
| `examples/README.md` (PR 5) | Old apps and checklist. | Rewrite rows. | OS-50 |
| `bench/BASELINE.md` (PR 5) | Old-engine numbers (B4 p50 1.56 ms, p99 3.91 ms). | Keep the method; new baseline (RUNNER N4 numbers are a start). | OS-21 |

Rule from `docs/docus` `AGENTS.md`: never deploy the site from an agent.

## 5. The open PRs as GitHub objects

Constraints: never rewrite history on someone else's branch; these branches were made by Claude for the user, but
the user prefers non-destructive moves. Never merge without the user. A PR must be green before review.

Facts that matter:

- PR 4 has base `feat/core-1-model-view`; PR 5 has base `feat/core-2-engine`. GitHub retargets a stacked PR only
  when its base branch is merged AND deleted (HANDOFF "Merge order"). Closing a PR does not retarget anything.
- The review history on PRs 3-5 is one bot review each (8 inline comments in total). Three comments still apply
  (OS-1, OS-2, OS-10). No human review exists.
- Most of the main code that PR 5 adds carries: 9 new files with 1104 lines, of 1291 added main lines
  (`git diff --numstat`). Of all main code at the PR 5 head, 2556 of 7280 lines carry (35%), and nearly all of it
  needs a change. Of PRs 3 and 4, only `Options`, `TextField` (logic), `Screen`, `Session`, `Trace`, `Tui`,
  `Headless`, `InputPort`, `RecordingControl` and `ScriptedInput` carry, all with changes.

| Option | What | For | Against |
| --- | --- | --- | --- |
| A. Close and open new PRs (RECOMMENDED) | Close PRs 5, 4, 3 (top first) with a comment that links the plan and lists the carried bot comments. Make new branches from `chore/cleanup` (for example `feat/kyoui-1-runner`, `feat/kyoui-2-terminal`, `feat/kyoui-3-examples`) and open new stacked PRs (a), (b), (c). Port the carried files with `git checkout origin/feat/core-3-terminal -- <path>` and say the source commit in the message. Keep the old branches unchanged. | No history rewrite. The old PRs and their review stay readable. Each new PR shows only new or carried code. The stack shape matches A1. | Three closed PRs in the list. New PR numbers. Carried bot comments must be copied by hand (3 items). |
| B. Reuse the branch names with new history | Force-push new commits to `feat/core-1-model-view`, `feat/core-2-engine`, `feat/core-3-terminal`; keep PR numbers 3, 4, 5. | Same PR numbers and links. | History rewrite on shared branches (against the user's preference). Old bot comments point to commits that vanish ("outdated"). PR titles and bodies need a full rewrite. |
| C. Keep PR 5 and add commits | Retarget PR 5 to `chore/cleanup`, close PRs 3 and 4, and add commits that delete the old model and add the runner (no force push). | No rewrite. Keeps PR 5 and its comments. | The PR history holds the 34 old commits (10 + 6 + 18, mostly dropped code) plus their removal; `git bisect` and blame are noisy; one very large PR instead of three. Only acceptable with a squash merge. |
| C'. Keep PR 5 and rebase it | Rebase PR 5 on `chore/cleanup` with only the carried parts. | Clean history, same PR number. | It is a history rewrite (force push). Not allowed without the user. |

Recommendation: Option A. For `feat/zio` and `feat/cats` (no PR): leave them unchanged as reference; open the
facade PRs later from new branches on top of the new stack. `wip/handoff`: never merge. PR 2 (`docs/docus`):
not affected by the rework; its content changes (OS-49) go into a later docs PR or a new commit on PR 2 (D9).
Deleting any old branch needs the user (D8).

## 6. Decisions needed

- D1. Package of the runner: `tui4s` (old core, `tui4s.internal`) or `tui4s.kyoui` (prototype). Facade packages:
  `tui4s.interop.zio` (DECISIONS round 4) or `tui4s.interop.zio.ui` (prototype).
- D2. Core test framework: kyo-test (DECISIONS round 4; the carried terminal tests are kyo-test) or munit (the
  runner prototype tests and all facade tests).
- D3. PR objects: option A (recommended), B, C.
- D4. `InputPort` element: the crossterm `Event` (one mapping path for real and scripted input; tests build Java
  records, as `EventMappingTest` does) or a runner message (simpler scripts, but the mapping is not tested by
  headless runs).
- D5. Inline viewport: implement it in the runner `Canvas` (an origin offset and the end-of-run cursor move) or
  drop it for the first release.
- D6. `jatatui-widgets`: keep the dependency or drop it.
- D7. Input bursts: one frame per input batch (old design, max 512 events) or one frame per event (prototype).
  `BurstProbe` measures this.
- D8. Old branches after the close: keep as reference (recommended) or delete.
- D9. Docs PR 2: change its content before the merge, or merge it as is and change content later.
- D10. ZIO version literal: move to `project/Versions.scala` (zio module and examples use it; a change marks all
  modules unreleased) or keep the `examples/zio.sbt` lookup.
- D11. Ctrl-C exit code (INTEGRATION Q2). Old design: the Ctrl-C KEY is a quit (exit 0, `ctrlCQuits`); 130 and 143
  only for `kill -INT` / `kill -TERM` from outside (`TuiApp.scala` Scaladoc). Keep this?
- D12. Replace `FacadeParity` with a screen-parity test (same keys give the same screen for the Kyo, ZIO and Cats
  Counter)?

## 7. Item list (summary of the structured result)

| OS | What | Action | Slot | Size |
| --- | --- | --- | --- | --- |
| OS-1 | TerminalIO + Crossterm (enter/restore, isatty, native load, hook) + bot P2 fix | keep (fix) | b | M (~345 carried, ~40 changed) |
| OS-2 | Session release + InputThread + TerminalControl; bot P2 fix | adapt | b | M (~250, ~80 changed) |
| OS-3 | EventMapping + InputPort -> kyo-ui events; replaces `Keys.scala` | adapt | b | M (~120 + ~190 test) |
| OS-4 | OutputCapture, LogSink, TuiLog (replace `OutputGuard`) | keep | b | S (~400 carried, ~10 changed) |
| OS-5 | TuiOptions/Viewport/TuiError merged with prototype options; Kyo-free (R4) | adapt | a/b | S (~150) |
| OS-6 | Kyo `TuiApp` main class on a UI value | adapt | b | M (~140, ~50 changed) |
| OS-7 | SchedulerDefaults + test | keep | b | S (~135) |
| OS-8 | `Tui.run`/`start`, `TuiRun`, `Headless`, quit as a value (R5) | adapt | a/b | M (~250) |
| OS-9 | Trace writer, new schema | adapt | b | S (~350, ~80 changed) |
| OS-10 | Draw/restore serialisation in `Canvas` (bot P1) | fix | b | S (~40) |
| OS-11 | Grapheme input editing from `TextField` into the runner | adapt | a | S-M (~150 + ~120 test) |
| OS-12 | Test kit: Screen, ScriptedInput, RecordingControl, `discard` | adapt | a/b | M (~320) |
| OS-13 | Old model files of PR 3 | drop | - | L removed (part of 4724) |
| OS-14 | Old engine files of PR 4 (+ lessons: batching, NotForeign, clock rule) | drop | - | L removed |
| OS-15 | Terminal tests to carry (TerminalIO, Session, TuiApp, Native, EventMapping, OutputCapture, SchedulerDefaults, FakeCrossterm, Screen, TextField) | adapt | b | L (~1795, ~30% changed) |
| OS-16 | Old model and engine tests; two lesson tests (name clash, CanEqual) | drop + new | a | S new (~60) |
| OS-17 | Surface check moves to the facades | adapt | zio/cats | S (~100) |
| OS-18 | Examples rewritten on kyo-ui; parity dropped; screen-parity option | adapt | c | M (~600) |
| OS-19 | ptycheck: keep cases, new screen checker, facade rows | adapt | c | M (~400) |
| OS-20 | Launchers task | keep | c | S |
| OS-21 | Bench probes rewritten; JMH benches replaced; new baseline | adapt | c | M (~700) |
| OS-22 | AmbientZIOs + suite | keep | zio | S (~360) |
| OS-23 | CatsBridge + AmbientDispatcher + suites | keep | cats | S (~590) |
| OS-24 | Facade `TuiApp` (ZIO, Cats) | adapt | zio/cats | S (~100 each) |
| OS-25 | Facade run entry (typed error, quietly, resource, `end` fiber, quit glue) | adapt | zio/cats | M (~150 each) |
| OS-26 | Old facade exports and sources | drop | - | removed |
| OS-27 | Old facade live kits and tests (lessons kept as new tests) | drop | - | removed; S new |
| OS-28 | Facade tests that adapt (TuiAppSuite, NativeTerminalSuite, surface) | adapt | zio/cats | S (~150) |
| OS-29 | Facade examples on the mirror; `examples/<m>.sbt` pattern | adapt | zio/cats | M (~300) |
| OS-30 | Facade build files (fork, deps, fs2 version, ZIO version) | adapt | zio/cats | S |
| OS-31 | ZIO api-1/runtime-3: inline handler on TestClock blocks dispatch | fix/doc | zio | S |
| OS-32 | ZIO api-3: one import, no duplicate names | rule + test | zio/cats | S |
| OS-33 | ZIO api-6/spec-6/spec-3: Console and loggers during the run | fix | zio | S-M |
| OS-34 | ZIO runtime-1/spec-2/runtime-4/spec-5: no unbounded acquire, no join cascade | rule + test | zio/cats/a | S |
| OS-35 | ZIO runtime-2/spec-1: no race on a long-lived Kyo promise | rule + test | zio/cats | S |
| OS-36 | ZIO spec-4: obsolete | none | - | - |
| OS-37 | kyo-ui dependency, jatatui-widgets | adapt | a | S |
| OS-38 | Test framework and test JVM settings | decide | a | S |
| OS-39 | Strict compiler flags for the ported runner | adapt | a | M (risk) |
| OS-40 | bench project (sbt-jmh) | keep | c | S |
| OS-41 | CI jobs (`sbt`, `terminal`) | keep | b/c | S |
| OS-42 | Codegen sbt project + drift check (ASSUMED A4) | new | gen | M (~900 gen + build) |
| OS-43 | compat.sbt | keep | - | none |
| OS-44 | FacadeParity | drop | c | removed |
| OS-45 | Release files | keep | - | none |
| OS-46 | Old branches and PR objects (option A) | decide | - | - |
| OS-47 | AGENTS.md | adapt | a + each PR | S |
| OS-48 | README.md | adapt | c | S |
| OS-49 | docs/content | adapt | docs | S |
| OS-50 | examples/README.md | adapt | c | S |
