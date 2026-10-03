# core-2 review: Public API, DX and tests (lens: api)

Reviewer: api lens. Branch feat/core-2-engine (worktree .work/worktrees/core). Started 2026-10-01.
Status: done. 10 findings (4 major, 6 minor). Checked: SPEC 2.8-2.11 surface present and Kyo-free where shared; no jatatui type in public tui4s/testkit signatures; C3 and C4 test rows of SPEC 8.2 present.

## Findings

### api-1 (major) The live kit hides a failure of the app
- File: modules/core/src/main/scala/tui4s/testkit/LiveTest.scala:189 (and the trait at 17-61)
- `LiveRun.apply` ends with `handle.stop.andThen(Abort.get(result))`. `TuiHandle.stop` discards the run's
  result (`fiber.getResult ... .unit`), and `LiveTest` has no `error` (TuiTest has one).
- Scenario: `update` throws on 'x'. `TuiTest.live(app)(t => t.press('+', 'x', '+').andThen(t.screen).map(s =>
  assert(s.contains("Count: 1"))))` passes; the AppFailed is never visible. An agent sees `quit == true`
  and no reason.
- Fix: after `test`, read the run result; when the run ended with a TuiError that the kit's own stop did not
  cause, fail `live` with it. Add `def error(using Frame): Option[TuiError] < Sync` to LiveTest.

### api-2 (major) settle waits for any running task: hangs under virtual time
- Files: LiveTest.scala:123 (Probe untilIdle = true), :145 (advance step), :187 (settle before `test`);
  internal/Engine.scala:438-447; internal/Supervisor.scala:173 (`busyTasks` counts standing tasks too)
- A task that sleeps on the controlled clock ends only when virtual time moves, and only `advance` moves it.
  (a) Deviation: a standing `task` counts. An app whose initial sources declare
  `source.task(Async.sleep(2.seconds).andThen(1))(...)` hangs in the settle before the test body.
  (b) Also under SPEC rules: an `every(1.second)` fold returns `s.withTask(source.task(Async.sleep(300.millis)...))`;
  `t.advance(2.seconds)` hangs at the 1000 ms step, because the per-step settle waits for the task and the
  task waits for the next step.
- Fix: follow SPEC 6.2 (one-shot tasks only); the per-step settle in `advance` and the settle before `test`
  use untilIdle = false (or treat a task parked on the controlled clock as idle). Add a live test with a
  sleeping task.

### api-3 (major) An interrupt before the first frame leaks the run (Headless.start, startWith, live)
- File: modules/core/src/main/scala/tui4s/internal/RenderLoop.scala:270-277 and 290-291
- `startUnscoped` starts the run with `Fiber.initUnscoped` (unparented, Fiber.scala:187-192), then parks in
  `obs.ready.safe.get`. `Runner.start` registers `Scope.ensure(h.stop)` only after the handle comes back;
  `LiveRun.apply` registers `Sync.ensure(handle.kill)` only after it too.
- Scenario: `Async.timeout(5.millis)(Scope.run(Headless.start(app, backend, input).map(_.await)))` with a
  first frame slower than 5 ms. The timeout interrupts the wait; nobody holds the fiber; the app and its
  input thread run on. With C5 `Tui.start` on this seam, raw mode and the alternate screen stay on. The
  documented contract "the end of the scope stops it" fails.
- Fix: guard the wait: if the caller is interrupted before the handle is returned, interrupt the run fiber
  and await it (for example `Sync.ensure` on a flag set when the handle is returned), or register the stop
  before the wait.

### api-4 (major) A handle whose run ended before its first frame has a null states signal
- Files: modules/core/src/main/scala/tui4s/Tui.scala:56-62; internal/RenderLoop.scala:275-276
- `states` returns a NEW `SignalRef(null)` on each call when no frame was published. `current` gives `null`
  typed as `S`; `next` never completes.
- Scenario: C5 `Tui.start` without a TTY (NotATerminal in `Session.open`), or `init` throws. Kyo:
  `h.states.current.map(_.count)` fails with an NPE instead of the TuiError. ZIO facade (SPEC 4.2:
  `ZStream.repeatZIO(core.states.next)`): `h.states.take(1).runCollect` hangs forever.
- Fix: when the run ends before its first frame, fail `startUnscoped` with the run's TuiError
  (`TuiHandle[S] < (Async & Abort[TuiError])`, same for `Headless.start` / `Tui.start`), so no handle
  without a state exists. At least, create the fallback signal once.

### api-5 (minor) LiveTest.typeText does not map keys as a terminal sends them
- File: modules/core/src/main/scala/tui4s/testkit/LiveTest.scala:116-117
- `put(e)` without `Key.sent`. `TuiTest.typeText` maps '\n' to Enter and '\t' to Tab (TuiTest.scala:242-245);
  `LiveTest.press`, `LiveTest.send` and `ScriptedInput.push` map too. The doc says "(as TuiTest.typeText)".
- Scenario: `t.typeText("milk\n")` on the Todo: TuiTest adds the item (Enter); the live kit sends
  `Key.Char('\n')`, which the field takes as text, so no item. The same test passes sync and fails live.
- Fix: `put(e match { case k: Key => Key.sent(k); case o => o })`, or share one helper with TuiTest.input.

### api-6 (minor) emit turns a ClassCastException inside user fold code into "does not fit the fold"
- Files: modules/core/src/main/scala/tui4s/testkit/TuiTest.scala:192-204; internal/Engine.scala:217-222
- Every CCE from the fold is rethrown while `rethrowClassCast` is on, also a CCE thrown deep in the user's
  fold (a bad cast in model code). SPEC 6.2: user code that throws is recorded as AppFailed, not thrown.
- Scenario: a fold that does `json.asInstanceOf[Map[String, Int]]` on a bad payload. `t.emit("feed", ok)`
  throws `AssertionError("emit(feed): ... does not fit the fold of source feed")`; the value DID fit;
  `t.error` stays empty.
- Fix: treat the CCE as "does not fit" only when it is about the value (for example the CCE message names
  `value.getClass.getName` as the source class), else record `AppFailed("fold of " + label, c)`.

### api-7 (minor) Trace event codes drop the mouse modifiers; replay changes the event
- File: modules/core/src/main/scala/tui4s/internal/Trace.scala:186 (encode), :324 (decode uses Mods())
- Scenario: an app with `mouse = true` handles ctrl+click as "add to selection". The trace writes
  `mouse:down:left:3,4`; `TuiTest.replay` sends a plain click, so the replayed state differs.
- Fix: append the mods when not empty (`mouse:down:left:3,4:ctrl+shift`) and decode them; extend the
  round-trip test in TraceTest.

### api-8 (minor) TuiTest.replay ignores the size in the trace's start line
- File: modules/core/src/main/scala/tui4s/testkit/TuiTest.scala:313-333
- The start line has `"size":"120x40"`; replay uses 80x24 unless the caller passes the size. `Mouse.tags`
  come from the replayed layout, so clicks hit other views.
- Scenario: record a session at 120x40 that clicks a list row tagged "row-7"; `TuiTest.replay(app, file)`
  lays the view out at 80x24, the click lands on another tag, the state differs.
- Fix: use the start line's size when the caller gives none (sentinel defaults, for example -1), or say in
  the Scaladoc to pass it.

### api-9 (minor) GuardTest does not cover three of the five foreign types
- Files: modules/core/src/test/scala/tui4s/GuardTest.scala; internal/Macros.scala:9-16
- Only `zio.ZIO` (stand-in) and `Future` are tested. The entries for `cats.effect.IO`, `fs2.Stream` and
  `zio.stream.ZStream` (SPEC App. A item 10) have no stand-in, so a wrong name in the table goes unseen
  until the facade PRs.
- Fix: add stand-ins `cats.effect.IO`, `fs2.Stream`, `zio.stream.ZStream` in src/test (like zio/ZIO.scala)
  and one typeCheckFailure each.

### api-10 (minor) The TerminalControl seam cannot do the inline-viewport restore of SPEC 5.9
- File: modules/core/src/main/scala/tui4s/internal/Session.scala:38-39, 89
- SPEC 5.9 close step 3: for `Viewport.Inline`, move the cursor below the viewport and print a newline.
  `leave(opts)` gets only the options. The jatatui Terminal (which holds the viewport area) is made by
  Session, after `enter`, and the control never sees it.
- Scenario: C5 crossterm control with `Viewport.Inline(5)`: after quit, the shell prompt is drawn inside
  the last frame; C5 must change the seam that the facades' runWith already passes.
- Fix: pass the last viewport area (or the Terminal) to `leave`, for example
  `leave(opts, viewport: Option[Rect])`, filled from `terminal.getFrame().area()` in `Session.close`.

