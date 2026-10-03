# Judge: performance and runtime correctness (weight 25%)

Status: COMPLETE (2026-10-01). Notes first, then source checks, comparison, verdicts, fatal flaws, grafts, scores.

Lens: work and allocations per event and per frame, draw only when the model changed,
coalescing of event bursts, input latency, idle CPU, ownership of the mutable jatatui objects
(one draw fiber), backpressure, interruption and terminal restore (F8), resize, error handling,
no scheduler thread blocked by the JNI read.

## Reading notes: elm (TEA)

- Input: dedicated daemon platform thread, `poll(50 ms)` then `read()` up to 512 queued events,
  `Channel.Unsafe.putBatchFiber(batch).onComplete(latch.countDown)` + `latch.await()` (elm 5.1).
  No Kyo worker is ever blocked in JNI. Backpressure reaches the input thread.
- Loop: one fiber, `take` (park) -> `drain` -> `foldBatch` in one `Sync.Unsafe.defer` -> subs
  reconcile -> draw once if dirty and frame due (elm 5.2). Frame budget is a deadline on `take`
  (`Async.timeout`), first change after idle draws at once (elm 5.3).
- Dirty: model `eq`/`equals` once per batch, then a second filter on `view(model) == lastView`
  (elm 5.3, 7.3). Resize forces a redraw.
- Ownership table (elm 5.4): Terminal, Buffers, widget states in a runtime `StateStore`, all on the
  loop fiber; escape hatches run inside render.
- Restore: `Sync.acquireReleaseWith(Session.open)(_.restore())` around `Scope.run` (elm 5.7).
- Idle: `SchedulerDefaults.applyIfUnset()` before the first Kyo class; `TuiApp` does not extend
  `KyoApp` (elm 5.8).
- Per-event cost: routing rebuilt from `subscriptions(newModel)` after EVERY message that changed
  the model (elm 5.2 `step`), not once per batch. In a 10k key burst that is 10k calls to
  `subscriptions`. Todo's `activeKeys(m)` builds `global ++ inputKeys` each call, so the "lazily
  built HashMap cached in the keymap" (elm 7.1) is rebuilt for each new Keymap instance: the
  "0 allocations" routing row in 7.1 is optimistic. Small cost, not a flaw.

## Reading notes: components (React / Flutter style)

- Input: platform thread, `poll(25 ms)`, read up to 512, `putBatchFiber` and WAIT (components 5.1).
  Same good shape as elm; quit latency 25 ms.
- Loop (components 5.2): `take` -> `drain` -> `applyAll` -> on `PaintNeeded`: `frameWait(lastPaint)`
  (sleep the rest of the 16 ms budget) -> `drain` -> `applyAll` -> `paint`. First change after idle
  paints at once.
- Per message: handler = `Abort.run(Var.runTuple(state)(Emit.run(Emit.run(h))))` (EST 1-3 us), state
  `==`, then reconcile of the dirty instance AND a routing-table refresh that walks all cached
  nodes, O(nodes), EST 10-40 us for 1-2k nodes, BEFORE the next message (components 5.2 step 4,
  7.1). Per-event cost is the highest of the four, but still bounded and frames are coalesced.
- Dirty: per-instance `==`; paint only if an ON-STAGE instance changed, focus moved, resize,
  `Redraw` (components 5.3). `IndexedStack` offstage changes rebuild but do not paint.
- Ownership table (components 5.5) is complete, including "CrosstermJni enqueue/execute/flush:
  UI fiber and the release, never concurrent".
- Restore: `Sync.acquireReleaseWith(Term.enter)(Term.exit)`; `Term.exit` stops AND JOINS the input
  thread (bounded 2 x poll + 50 ms) before it leaves raw mode (components 5.6). This closes the
  "one key read after restore" race that elm admits (elm 8.2.5).
- `Tui.run` runs under `Log.let(...)` with a file / in-memory logger, so Kyo's console logger cannot
  draw over the screen (components 5.7). Good idea, missing in elm.
- Late deliveries carry `(instance, generation)`, so a value from an unmounted instance is dropped
  (components 5.1). Ticks: `offer` guarded by a per-sub pending flag (no tick pile-up).
- RISK: handlers have `Async` in `Tui.Base` and run INLINE on the UI fiber
  (`Tui.Base = Emit[Cmd] & Async & Abort[Throwable]`, components 2.5). A slow handler freezes
  input handling and drawing (the author admits it, 8.1.8). Facade handlers ALWAYS do a bridge hop
  (fork on the ZIO/CE runtime + promise join) while the UI fiber waits (components 4.1, 4.7, EST
  10-30 us per handler); a stream subscription in a facade pays this per chunk.
- `TuiApp extends KyoApp` and calls `Tui.tuneScheduler()` in its constructor body (components 2.5).
  Needs a check: does `KyoApp`'s superclass construction already initialize the scheduler?

## Reading notes: signals (fine-grained reactive)

- Input default is `InputMode.FiberPoll`: a Kyo fiber that calls JNI `poll(50 ms)` (signals 2.3
  `TuiConfig.input`, 5.1). That parks a Kyo WORKER thread inside native code 20 times a second.
  The platform thread is only an option. This goes against the lens rule "no scheduler thread
  blocked by the JNI read" (checked against kyo-scheduler below).
- Loop (signals 5.2): `take` -> `drain` -> `dispatch` (handlers in order) -> `reconcile` (pull every
  mounted binding, recompute the changed ones, remount + damage) -> `present` paced to maxFps.
- Retained presenter (signals 5.5): own `canvas` + `shown` buffers, repaint only damaged rects,
  row diff ported from `Buffer.diff`, `backend.draw` of the changed cells. `Terminal.draw` is NOT
  used for the fullscreen viewport. Biggest per-frame win of the four (EST 10-20x for small
  changes, signals 7.2), and the biggest correctness surface (own diff port, own cursor, own
  resize; partial repaint rests on invariants jatatui does not enforce, signals 8.1.4-5).
  `verifyRepaints` in every test is the mitigation.
- Wake: one watcher fiber per distinct root `SignalRef`, parked on `ref.unsafe.next()`, register
  before wake, `wakePending` CAS so a burst gives one `Wake` (signals 5.3). Hidden panes have no
  watcher and cost zero (signals 3.3, 7.2).
- One spurious wake per handled event (signals 8.1.7): the watcher of a ref the handler wrote
  wakes the loop again after the pull.
- Restore: `Sync.ensure(engine.terminal.restore)` INSIDE `Scope.run` (signals 5.2 sketch). On a
  normal quit the restore runs first, THEN the scope interrupts the input fiber (signals 5.9). The
  input fiber can be inside `poll(50 ms)` or about to `read()` after raw mode is off; no stop flag
  or join is described. Same class of race as elm 8.2.5, wider window, no mitigation.
- Torn frames: background fibers that write two refs can be seen between the writes (8.1.3).
- `Log.let(engine.log)` around the whole app (signals 5.2): good.
- Handlers `Unit < Async` run inline on the render fiber (8.1.8), same risk as components.

## Reading notes: free (three pure functions)

- Input: platform thread, `poll(25 ms)`, up to 512 queued events, `putBatchFiber` + latch
  (free 5.3). Started by the acquire, stopped and JOINED by the synchronous release BEFORE the
  restore steps (free 5.8 `close()`). Same correct order as components.
- Loop (free 5.4): `take` -> `drain` -> `fold` of the WHOLE batch in one `Sync.defer` `while` loop
  (no Kyo step per event, no effect handler per event) -> level 1 `eq` -> `sources(s)` diff once
  per batch -> frame slot wait (`Async.sleep` remainder) -> `drain` + fold -> `view(s)` -> level 2
  `view.equals(lastView)` -> `terminal.draw`. Cheapest per-event path of the four.
- Sources fold one state change per CHUNK (free 2.6), so a burst from a stream is one fold.
- Ownership table (free 5.1) complete; "crossterm writer: render loop, then the release; the
  release runs after the input thread has stopped".
- Restore: `Sync.acquireReleaseWith(TerminalIO.open)(_.close())` + `Sync.ensure(sup.stopAll)`
  (free 5.8). Source fibers are `Fiber.initUnscoped`, interrupted by signal, not awaited (5.7).
- Gap: Kyo `Log` is NOT routed away from the console (free 5.9 last bullet, open Q5). ZIO console
  logger and CE starvation checker are handled (4.3, 4.6).
- Level 1 is `eq` only (no `equals` on the state); for `S = Int` above 127, an unchanged
  `case _ => n` re-boxes and fails `eq`, so the view is rebuilt and level 2 catches it. Correct,
  slightly more work. Not a flaw.
- Full frame through `Terminal.draw` on each visible change (free 7.2), so ~2 ms per changed frame
  at 120x40; Dashboard ~1.1% of a core (EST, free 7.3).

## Source checks (Kyo RC7, jatatui 0.31)

1. `KyoApp` construction does not start the scheduler (checked `kyo-core/kyo/KyoApp.scala`,
   `internal/KyoAppRunner.scala`, `KyoAppInterrupts.scala`): the constructor only builds an
   `AtomicRef.Unsafe`, an `IOPromise` and OS signal handlers; `IOPromise` touches `Scheduler.get`
   only inside methods (`IOPromise.scala:259`). So `Tui.tuneScheduler()` in a `TuiApp extends KyoApp`
   constructor body (components, signals) should run in time. The elm/free choice (no `KyoApp`) is
   not needed for this reason, but it is not wrong. NOTE: `KyoAppInterrupts` installs SIGINT/SIGTERM
   handlers that race the app with an interrupt (`Async.raceFirst`), so on SIGTERM the `Sync.ensure`
   restore of components/signals runs. Good.
2. `SignalRef.Unsafe` (`kyo-core/kyo/Signal.scala:649-720`): `getAndSet`/`getAndUpdate` write the value
   first, then `onUpdate` swaps in a new uninterruptible promise and completes the old one; equal
   writes complete nothing; `next()` returns the current promise. The signals watcher protocol
   ("register with `next()`, then wake") is therefore lossless on the JVM. CONFIRMED. The repairing
   `observe` with a 1 s `repairInterval` race is also CONFIRMED (`Signal.scala:103-142`).
3. A fiber parked on an uninterruptible promise can still be interrupted promptly: after the slice the
   task status is `Idle`, and `IOTask.interrupt` on `Idle` schedules the task to release
   (`IOTask.scala:163-190, 298-310`). So signals' watcher fibers stop on unmount. No leak beyond one
   stale `onComplete` callback per unmount until the ref's next write.
4. **elm frame-wait race (CONFIRMED in source).** `Async.timeout` forks the body with
   `Fiber.internal.initUnscoped(v)` and, when the sleep fires, calls `task.unsafe.interrupt(error)`
   (`Async.scala:196-212`). `Channel` puts a value back only when `takePromise.complete(value)` FAILS
   (`Channel.scala:865-890`). If the take promise completes with a message and the timer interrupts the
   forked task before it resumes, the task ends with `Timeout` and the message is dropped. elm 5.2
   `next` does exactly this: `Async.timeout(...)(inbox.take.map(Present(_)))` while a frame is due.
   During a burst (the only time this path runs) a key, a `SubValue` or a `Failed(e)` can be lost.
   Easy fix (sleep the remainder, then `drain`, as components / signals / free do), but the design as
   written is not lossless.

5. **Scoped `Fiber.init` grows the Scope for the app's life (CONFIRMED).** `Fiber.init` registers a
   `Scope.acquireRelease` finalizer in the enclosing scope (`Fiber.scala:129-150`), and the scope keeps
   finalizers in an unbounded queue with no removal (`Scope.scala:289-307`). So one finalizer (holding the
   finished fiber and its result) per command stays until the app ends: elm `Cmd.perform`/`attempt`
   ("Fiber.init inside the app's Scope", elm 5.1), components `task` on the root instance (5.1, "forks the
   work in the emitting instance's Scope"), signals `tui.spawn` ("forked in the TUI scope", 2.3). free
   avoids it on purpose with `Fiber.initUnscoped` + its own supervisor map (free 5.7). Slow leak, easy fix.
6. **jatatui widget equality is NOT universal (CONFIRMED).** `Gauge`, `LineGauge`, `Scrollbar`, `Calendar`,
   `Canvas` (and `TextInput`) are plain classes with no `equals`/`hashCode`
   (`jatatui-widgets/.../gauge/Gauge.java`, `LineGauge.java`, ...); `Paragraph`, `List`, `Block`,
   `Sparkline`, `Tabs`, `BarChart`, `Chart`, `Line`, `Span`, `Text` override `equals`; `Table`, `Style`
   are records. Consequence: free's level-2 filter (`view.equals(lastView)`, free 5.5) is reference
   equality for any view that contains a gauge, so the Dashboard metrics view is "always changed"
   (free's own sample, 3.3). free's claim "a state change that does not show on screen costs one equals,
   not a frame" is false for these widgets. elm and components hold plain Scala data in their own case
   classes and build jatatui widgets at paint time, so their view/state equality works. signals' leaf
   memo (`nv != b.child.view`, 5.4) has the same weakness, but its projection dedup runs first, so it
   costs little there.
7. `Terminal.draw` (`Terminal.java:218-250`): autoresize, callback, full-buffer diff, hide/show cursor,
   `swapBuffers` resets the OLD previous buffer, so the last frame's buffer is intact during the next
   callback. CONFIRMED components' blit premise (7.5) and signals' "0.6 ms fixed cost" premise.
   `Backend.draw(Iterable<BufferUpdate>)`, `clear()`, `flush()` exist, so signals' own presenter is
   implementable on the public API.
8. Kyo `BlockingMonitor` (`kyo-scheduler/.../BlockingMonitor.scala:1-60`, `Worker.scala:243-293`) marks a
   worker "blocked" when its user CPU time stops (a thread inside JNI `poll` qualifies), drains its queue
   to other workers and dispatches `Thread.interrupt()` to it on fiber interrupt. signals' default
   `InputMode.FiberPoll` triggers this 20 times a second; the probe measured higher idle CPU for that
   mode (probe: BlockingPoll ~21-23% vs Thread 17.8% of a core, default flags, noisy).
9. `Sync.acquireReleaseWith` uses the kernel `Bracket` with a synchronous release
   (`Sync.scala:78-107`), so elm, components and free get the F8 guarantee; signals uses `Sync.ensure`
   (same guarantee).
10. `CrosstermJni` has no Java-side locks (`crossterm/tui/crossterm/CrosstermJni.java`); concurrent
    reader/writer safety rests on the Rust side for all four candidates (shared risk, not scored).

## Comparison by sub-criterion

| Sub-criterion | elm | components | signals | free |
| --- | --- | --- | --- | --- |
| Work per event | update + routing rebuilt from `subscriptions(m)` per changed msg; fold in one `Sync.Unsafe.defer` | handler with `Var.runTuple` + 2 `Emit.run` + `Abort.run`, then reconcile + O(nodes) routing refresh per msg (highest) | Kyo handler + CAS + promise swap per write; pull once per batch; one spurious wake | pure `update` in a `while` loop for the whole batch; `sources` diffed once per batch (lowest) |
| Work per frame | full `Terminal.draw` (~2 ms at 120x40) | full `Terminal.draw`; build skipped for clean instances; blit only phase 2 | partial repaint + row diff, no `Terminal.draw` (EST 0.05-1 ms) (best) | full `Terminal.draw` |
| Draw only when changed | model `eq`/`equals`, then view `==` (own case classes: works for all widgets) | per-instance state `==`, on-stage only | source dedup + projection dedup + leaf memo | `eq`, then view `equals`; DEFEATED by `Gauge`/`LineGauge`/`Scrollbar`/`Canvas` (no `equals`) |
| Coalescing / pacing | take+drain; deadline via `Async.timeout(take)` (LOSSY, check 4) | take+drain; sleep remainder + drain | take+drain; sleep remainder + drain; latest-value state | take+drain; sleep remainder + drain; per-chunk folds |
| Idle | thread input; subs only as model asks | thread input; subs per mounted instance (offstage still runs) | FiberPoll default blocks a worker 20x/s (worse); hidden panes 0 work (better) | thread input; sources only as state asks |
| One draw fiber / ownership | complete table | complete table | complete table (owns canvas too) | complete table |
| Backpressure | bounded 4096; put; ticks `offerDiscard`; `maxSendsPerBatch` | bounded 1024; put; ticks pending flag | writes never block; inbox only wakes (offer + CAS flag) | bounded 1024; put; per-chunk |
| UI loop can be blocked by user code | no (update pure; effects on fibers) | YES (`Async` handlers inline; facades always hop and wait) | YES (same; `tui.spawn` is opt-in) | no (update pure; sources on fibers) |
| Interrupt / restore (F8) | `Sync.acquireReleaseWith` outside `Scope.run`; input thread NOT joined on interrupt (admitted race) | release stops + JOINS input thread, then restores (best) | `Sync.ensure` INSIDE `Scope.run`: restore first, input fiber stopped later on EVERY quit | release stops + JOINS input thread, then restores (best) |
| Resize | `forceRedraw` + `autoresize` | paint-dirty + `autoresize` | own relayout + `backend.clear`; no autoresize safety net | `resized` flag + `autoresize` |
| Errors / stdout hygiene | typed `TuiError`, `Failed` path; no `Log.let` | ErrorPolicy, `Log.let` | ErrorPolicy, `Log.let`, Frame in errors | panic -> restore -> fail; ZIO/CE loggers off; Kyo `Log` NOT routed (open Q5) |
| Fiber bookkeeping | scoped `Fiber.init` per Cmd (grows) | scoped per instance (root grows) | `tui.spawn` scoped (grows); watchers unscoped | `initUnscoped` + supervisor (best) |

## Verdict per candidate

### elm: 6.5 / 10

Strong, simple runtime: platform-thread input with backpressure (elm 5.1), one loop fiber that owns
every jatatui object (5.4), two filters (model, then view equality over tui4s-owned case classes, which
works even for `Gauge`, 5.3/7.3), `update` cannot block the loop (effects are `Cmd`/`Sub` fibers),
`maxSendsPerBatch` guards `Cmd.send` loops, ticks are `offerDiscard`, `Sync.acquireReleaseWith` restore
(5.7). Deductions:
- **Lossy frame-deadline wait (confirmed, check 4).** `next` uses
  `Async.timeout(Duration.fromNanos(wait))(inbox.take.map(Present(_)))` (elm 5.2). In RC7 a message whose
  take promise completed just before the timer interrupt is dropped. This path runs exactly during bursts
  (held keys, fast streams), so keystrokes, `SubValue`s or a `Failed(e)` can vanish silently. Fix: the
  probe's "sleep the remainder, then drain" (as components / signals / free).
- Input thread is not joined in `restore()`; on interrupt one event can be read after restore (elm 8.2.5).
- Every `Cmd` fiber is a scoped `Fiber.init` in the app scope: the finalizer queue grows per command
  (check 5).
- Full-frame paint for every visible change; `View.lazily` is phase 2. No `Log.let` around the app.
- Routing is rebuilt from `subscriptions(newModel)` after each changed message (cheap, but per event).

### components: 6.0 / 10

Best restore order (release stops and joins the input thread before leaving raw mode, 5.6), `Log.let`
(5.7), generation-tagged deliveries drop late values from unmounted instances (5.1), per-sub pending flag
for ticks, offstage `IndexedStack` children do not paint (5.3), incremental build, and a sound blit idea
(7.5, premise confirmed in `Terminal.java`). Deductions:
- **Handlers run inline on the UI fiber with `Async` in their row** (`Tui.Base`, 2.5; admitted 8.1.8). A
  slow handler freezes input and drawing. Facade handlers ALWAYS cross the bridge and the UI fiber waits
  for the ZIO/CE fiber (4.1, 4.7); a ZIO user who writes `onKey(...)(http.post(...))` freezes the UI.
- Highest per-event cost: 3-4 effect handlers per handler run plus reconcile and an O(all nodes) routing
  refresh before the next message (5.2 step 4, 7.1).
- Paint is still a full frame; build savings are microseconds against a ~2 ms paint (the author agrees,
  8.1.6). Blit is opt-in phase 2.
- Largest runtime (reconciler, slots, dirty-descendant counters, focus regions; EST 2-3k lines, 8.1.7):
  more places for lifecycle bugs (leaked sub fibers, stale focus).
- `task` fibers are scoped to the instance scope; on the root instance they accumulate (check 5).

### signals: 7.0 / 10

The only design that attacks the dominant cost (jatatui paint + `Terminal.draw` fixed 0.6 ms): retained
canvas, partial repaint, per-row diff, `backend.draw` of changed cells (5.5); hidden panes are unmounted
and cost zero (3.3, 7.2); exact, timer-free, lossless watchers (5.3; protocol confirmed in
`Signal.scala`, check 2); producers never block and state coalesces to the latest value; `Log.let` and
`Frame` in every error (5.2, 5.8); `verifyRepaints` makes every test prove partial == full repaint.
Deductions:
- **Default input blocks a Kyo worker in JNI** (`InputMode.FiberPoll`, 2.3/5.1). This breaks the lens rule
  and triggers the blocking monitor 20 times a second (check 8); the probe's idle numbers for this mode
  are worse than the platform thread, which can eat the frame savings at 120x40.
- **Restore before the input fiber stops, on every quit** (`Sync.ensure` inside `Scope.run`, 5.2/5.9):
  for up to `pollEvery` (50 ms) the input fiber can `poll`/`read` with raw mode off; no stop flag before
  `read` is described.
- **Partial-repaint z-closure only goes UP** ("add every node with a higher z whose rect intersects", 5.4
  step 1). When an overlay subtree shrinks or disappears (a `when(...)` popup in `layers`), its rect is
  reset and repainted empty, but the lower-z content under it is not repainted: blank or stale cells until
  something below changes. `verifyRepaints` would catch it in tests that close a popup; the fix is to add
  intersecting lower-z nodes to D.
- Own presenter = own cursor handling and own resize path with no `autoresize` safety net (5.7); torn
  frames from background fibers writing two refs (8.1.3); handlers with `Async` inline on the render fiber
  (8.1.8); one spurious wake per handled event (8.1.7); `tui.spawn` scoped growth (check 5).
- Most perf numbers are estimates (7.1-7.2); the presenter port needs a benchmark.

### free: 7.0 / 10

The cleanest runtime of the four: platform-thread input started by the acquire and stopped + joined by
the synchronous release before the terminal is restored (5.3, 5.8); the whole batch folds in one
`Sync.defer` `while` loop with no effect handler per event (5.4, 7.1); `sources` are diffed once per batch
and streams fold once per CHUNK (2.6); sources are `Fiber.initUnscoped` under a supervisor map, so nothing
accumulates in a scope (5.7); `update` is pure, so nothing can block the loop; ZIO default loggers and the
CE starvation checker are switched off (4.3, 4.6). Deductions:
- **Level-2 frame filter is defeated by jatatui reference equality** (check 6): `View` IS the jatatui
  `Widget`, and `gauge(...)` returns a jatatui `Gauge` with no `equals`. Any view with a `Gauge`,
  `LineGauge`, `Scrollbar`, `Calendar` or `Canvas` is "always changed", so its own Dashboard draws a full
  frame for every state change on the metrics tab. Output stays correct; the "never pays an unchanged
  frame" promise (1, idea 5) does not hold. Fix: wrap those widgets in tui4s case classes (as elm does).
- Full-frame paint for every visible change; no retained or blit path is planned.
- Kyo `Log` is not routed away from the console (5.9, open Q5).
- Level 1 is `eq` only; harmless but an equal-but-new model always rebuilds the view.
- `Channel.initUnscoped` is never closed: with a full inbox at quit, the input thread stays in
  `latch.await()` and the bounded join times out (daemon thread leak, rare).

## Fatal flaws (lens: performance and runtime correctness)

No candidate has a design-level fatal flaw under this lens. One defect is fatal AS WRITTEN and must not be
grafted:
- elm: the frame-deadline wait `Async.timeout(...)(inbox.take)` (elm 5.2) can drop an already-delivered
  message in Kyo RC7 (check 4). A lossy event loop fails runtime correctness. Not inherent to TEA: replace
  it with "sleep the remainder, then drain".

## Best ideas to graft into the winner

1. free + components: the synchronous release stops and JOINS the input thread before it leaves raw mode
   (free 5.8 `close()`, components 5.6 `Term.exit`). Input on a platform daemon thread with
   `putBatchFiber` + latch (elm/components/free), never a Kyo worker in JNI.
2. free: fold the whole batch in one `Sync.defer` `while` loop; diff subscriptions/sources once per batch;
   fold stream elements per chunk.
3. free: start command/source fibers with `Fiber.initUnscoped` and own them in a supervisor map (stop
   them in a `Sync.ensure`), not scoped `Fiber.init` (check 5).
4. components / signals / free: frame pacing = first change after idle draws at once; otherwise sleep the
   rest of the budget, then `drain`, then draw once. Never `Async.timeout` around `take`.
5. elm: two filters, model then view equality, over tui4s-owned case-class view nodes that build jatatui
   widgets at paint time (equality then works for `Gauge` & co, check 6); `maxSendsPerBatch`;
   `offerDiscard` ticks (or components' per-sub pending flag).
6. signals: the retained presenter as an opt-in `Presenter.Retained` with `verifyRepaints` on in every
   test and `FullFrame` as the reference, after fixing the z-closure (include lower-z intersecting nodes).
   Cheaper first step: components' blit of clean subtrees from the previous buffer (premise confirmed in
   `Terminal.java`).
7. signals + components: `Log.let(fileOrMemoryLog)` around the whole app; free: ZIO
   `Runtime.removeDefaultLoggers` and the CE starvation checker off.
8. components: generation-tagged deliveries, so values from removed subscriptions or unmounted parts are
   dropped; elm's "current toMsg of that id" rule.
9. signals: exact register-before-wake watcher on `SignalRef.unsafe.next()` + `wakePending` CAS for any
   `Sub.signal`-style source (avoids the 1 s repairing `observe`).
10. signals `FrameStats` / elm+free `frames` counter: performance contracts as test assertions
    ("an unbound key draws no frame", "one key = one dirty row").
11. components: dev-mode warning when a handler or update runs longer than one frame budget.
12. components / free / elm: a windowed list (`ListView.virtual(count)(row)`) that builds only visible rows.
13. All: scheduler `StaticFlag` defaults before the first Kyo scheduler class. `KyoApp` construction does
    not start the scheduler (check 1), so a `TuiApp extends KyoApp` can also do it in its constructor.

## Scores (criterion "perf")

| Candidate | Score |
| --- | --- |
| elm | 6.5 |
| components | 6.0 |
| signals | 7.0 |
| free | 7.0 |

Status: COMPLETE.
