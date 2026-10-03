# core-2 review: correctness and SPEC conformance

Reviewer lens: correctness and SPEC conformance (C3 + C4).
Status: done. 7 findings (2 major, 5 minor).

## spec-1 (major): settle never returns while a task body sleeps in virtual time

- File: modules/core/src/main/scala/tui4s/testkit/LiveTest.scala:119-127 (settle, untilIdle = true),
  internal/Engine.scala:438-447 (completeProbes waits for busyTasks == 0), LiveTest.scala:186-188 (the
  first settle before `test`).
- Scenario: an app starts a timed one-shot task on a key, for example a toast that hides after 2 s:
  `s.copy(toast = Some("saved")).withTask(source.task(Async.sleep(2.seconds))((s, _: Unit) => s.copy(toast = None)))`.
  `TuiTest.live(app) { t => t.press('x').andThen(t.advance(2.seconds)) ... }`: `press` puts the key, then
  `settle` with `untilIdle = true`. The task fiber parks in `Async.sleep` on the controlled clock. No batch
  comes, time never moves, `busyTasks` stays 1, the probe is never completed, `press` never returns and
  `advance` is never reached. The leaf hangs until its timeout. `advance` alone has the same deadlock: its
  per-step `settle` waits for the sleeping task, so the 1 ms steps never reach the task's deadline, although
  SPEC 2.10 says advance "fires every sleeper due within d". The declared deviation (standing `task`
  sources are waited for too) widens it: a standing `source.task(Async.sleep(500.millis).andThen(load))`
  hangs the first `settle` in `LiveRun.apply`, before the test body runs.
- Fix: do not wait for a task body that can only progress when virtual time moves. For example, complete an
  `untilIdle` probe also when the TimeControl has at least `sleepers + busyTasks` pending sleepers (every
  running body is parked on the clock), or let `advance` use plain probes (fold and draw only) for its
  per-step settle and let the input methods use the task wait only when no task is parked on the clock.
  Add a live test with a task that sleeps.

## spec-2 (major): a live test passes although the app failed

- File: modules/core/src/main/scala/tui4s/testkit/LiveTest.scala:186-190; Tui.scala:71-72 (`stop` discards
  `fiber.getResult`).
- Scenario: in `TuiTest.live(DashboardApp)`, the `'p'` binding throws (or a source without `recover`
  fails). The run ends with `AppFailed("update on key:p", e)` / `SourceFailed`. The test does
  `t.advance(1.second) ... t.press('p').andThen(t.advance(1.second)).andThen(t.state).map(d => assert(d.history.size == 5))`.
  `history` stays 5 because the app is dead, the assertion passes, and `LiveRun.apply` calls `handle.stop`,
  which does `fiber.getResult` and drops it with `.unit`. The live run succeeds; the crash is invisible.
  `LiveTest` has no `error` accessor either (the sync `TuiTest` has one), so the test cannot even check it.
- Fix: after `test`, read the run result; when it is `Failure(e: TuiError)` (not a stop), fail the live
  run with `e` (or with an AssertionError that names it). Also add `error: Option[TuiError] < Sync` to
  `LiveTest`, as `TuiTest.error`.

## spec-3 (minor): the state of a quitting batch is never published (LiveTest.state, TuiHandle.states)

- File: modules/core/src/main/scala/tui4s/internal/RenderLoop.scala:51 and 141 (`Loop.done` without
  `observer.published`), RenderLoop.scala:325-327 (HandleObserver.ended), testkit/LiveTest.scala:86-89
  (LiveView.ended).
- Scenario: `update` returns `Quit(n * 10)` or `Next.Then(s.copy(status = "bye"), Nil, quit = true)`. The
  engine sets `state` to the new value and the loop ends with `Loop.done(engine.state)`, but no observer
  sees that state: `LiveView.ended` and `HandleObserver.ended` copy only the stats. So in a live test
  `t.press('q').andThen(t.state)` returns the state before the quit, and `handle.states.current` never
  holds the state that `handle.await` returns. SPEC 2.9 says `states` is "the state after each batch",
  and LiveTest.state is documented as "the state after the last batch".
- Fix: in `ended`, publish `engine.state` (LiveView: `state = engine.state`; HandleObserver: set the
  signal).

## spec-4 (minor): `sources` is in first-insertion order, not in the order the state declares

- File: modules/core/src/main/scala/tui4s/internal/Supervisor.scala:79-96 (sync keeps kept entries at their
  old position, appends new labels) and 128-132 (`declared` iterates the LinkedHashMap).
- Scenario: `sources(d) = (if d.paused then Nil else Seq(cpu)) ++ Seq(logs)`. At start the state declares
  `[cpu, logs]` and `t.sources == Seq("cpu", "logs")`. Press `p` (declares `[logs]`), press `p` again
  (declares `[cpu, logs]`): `cpu` is new, so it is appended after the kept `logs`, and
  `t.sources == Seq("logs", "cpu")`. The same happens for a plain reorder (`[a, b]` then `[b, a]`: still
  `[a, b]`). SPEC 6.2: "`sources` lists the labels the current state declares, after the diff, in order".
  `LiveTest.sources` uses the same `declared` (the trace "start" line is not affected: at start the
  insertion order is the declared order).
- Fix: keep the declared order of the last `sync` (for example a `Vector[String]` of the labelled list, or
  re-insert the kept entries in `labelled` order when the order changed) and return it from `declared`.

## spec-5 (minor): an interrupt during `Launcher.launch` leaks a source fiber that stopAll cannot stop

- File: modules/core/src/main/scala/tui4s/internal/Supervisor.scala:274-286 (startOne),
  289-294 (stopAll), 300-303 (TokenEmitter swallows Closed).
- Scenario: the render fiber is interrupted (`fiber.interruptAwait`, `TuiHandle.kill`, a ZIO interrupt or a
  CE cancel through the facades) while it runs `launch` after a batch that declared a new `every` source:
  `Fiber.initUnscoped(body)` has returned (the fiber runs), but the continuation that sets `r.fiber` and
  registers `onComplete` never runs. `stopAll` then marks `r` stopped and calls `interruptPlanned`, which
  skips it because `r.fiber == null`. The unscoped fiber runs on after `Tui.run` returned: its
  `Loop.forever(Async.sleep(p).andThen(emit(())))` puts into the closed inbox, `TokenEmitter` swallows
  `Closed`, and the loop never ends (a timer and a fiber per leak; a facade source never cancels its ZIO/CE
  work, against SPEC 5.8 "Stopping ... cancels that work"). Kyo RC7 delivers interrupts between steps of a
  running slice (Safepoint stops), so the window is real, if narrow.
- Fix: make the start and the `r.fiber` assignment one protected step (run `launch` under
  `Async.uninterruptible`, or start the body with `Fiber.initUnscoped` inside an uninterruptible region),
  and/or end a body whose emit finds the inbox closed (let `TokenEmitter.apply` interrupt or abort the
  body on `Closed` instead of returning unit), so a lost fiber stops at its next emit.

## spec-6 (minor): an input failure can be lost when the inbox is full

- File: modules/core/src/main/scala/tui4s/internal/Session.scala:188-191.
- Scenario: the inbox holds 1024 items (a fast stream source or a large paste burst) when `port.poll`
  throws (the TTY goes away). `inbox.unsafe.offer(Failed(...))` returns `Success(false)`, the `Failed`
  item is dropped, and the thread ends. The app keeps running with no input thread: `q` and Ctrl-C can no
  longer reach it, and `Tui.run` never returns, instead of failing with `TuiError.TerminalIO` (SPEC 5.12:
  "the input thread fails -> Failed(TerminalIO) through the inbox -> release -> fail"). The SPEC 5.3 code
  has the same `offer`, so this is a SPEC gap that the implementation copied.
- Fix: put the failure with `inbox.unsafe.putFiber(Failed(...))` (it waits for room without blocking the
  thread, and the release's close completes it), or use a dedicated failure flag that the render fiber
  checks after each batch.

## spec-7 (minor): LiveTest.typeText skips the terminal key mapping, unlike TuiTest.typeText

- File: modules/core/src/main/scala/tui4s/testkit/LiveTest.scala:116-117 (`put(e)` without `Key.sent`);
  compare TuiTest.scala:239-250 (`input` maps keys with `Key.sent`) and LiveTest.scala:103-105 (`press`
  maps them).
- Scenario: `TuiTest.typed("milk\n")` gives `Key.Char('\n')` for the one-char grapheme "\n". In the sync
  kit, `typeText` sends it through `Key.sent`, so `update` gets `Key.Enter` and the Todo adds "milk".
  In the live kit, `t.typeText("milk\n")` puts `Key.Char('\n')` as it is: the Todo keymap (bound to
  `Key.Enter`) does not match, `TextField.edit` drops the control character, and no item is added. The
  same for "\t" (Tab moves focus in TuiTest, nothing in LiveTest) and ESC. LiveTest documents typeText
  "as `TuiTest.typeText`", and SPEC 2.10 gives both the same meaning.
- Fix: map the typed events with `Key.sent` in `LiveRun.typeText` (as `press` and `send` do).

