# Prototype "kyoui-runner": a terminal runner for kyo-ui

Status: IN PROGRESS (started 2026-10-02). Folder: `.work/proto/kyoui-runner/`.

## Plan (written at the start; updated as work continues)

1. Investigate kyo-ui RC7 (UI.scala, internal/ReactiveUI, ReactiveRegion, HtmlRenderer, UIDiff, Style):
   how runners find reactive nodes and subscribe; what is backend-neutral; what is HTML-only.
2. Check that `io.getkyo::kyo-ui:1.0.0-RC7` resolves for the JVM with scala-cli.
3. Build the runner: `TuiRunner.run(ui: UI < Async)`: raw mode + alternate screen, restore in Sync.ensure;
   layout of the AST on a jatatui Buffer; element mapping; focus (Tab / Shift-Tab, tabIndex);
   Enter/Space = click; typing into a focused input updates its SignalRef; onKeyDown gets kyo-ui KeyboardEvent.
4. Fine-grained repaint: one region per reactive node; repaint only that region in a kept buffer;
   flush only changed cells; glitch-free (one event -> all work -> one flush).
5. Apps in plain kyo-ui: Counter (+ two counters), Todo, Dashboard. Hooks (useSignal, ...) and a
   type-member Component class; Counter in both styles.
6. Headless tests (TestBackend) + measurements (100x30, median of >= 1000 events).
7. Real PTY run with expect (Counter, Todo; stty check).

## Progress log

- 2026-10-02: folder and plan created.

## Investigation notes (step 1, written before the code)

- `io.getkyo::kyo-ui:1.0.0-RC7` resolves for the JVM with scala-cli (deps: kyo-core, kyo-http -> kyo-net, kyo-config,
  kyo-schema-json). VERIFIED: a probe compiled and ran.
- `Ast.Attrs` is `private[kyo]`, but its members are readable from outside package `kyo` through the public
  `el.attrs` (`el.attrs.onClick`, `.identifier`, `.tabIndex`, ...). Only naming the type `Attrs` is impossible.
  VERIFIED (probe). So the runner does not need to live in package `kyo`.
- kyo-ui's own machinery (`internal/ReactiveUI`, `ReactiveRegion`, `UIExchange`, `UIEvent`, `EventHandler`) is all
  `private[kyo]`. Backend-neutral parts: `ReactiveUI.normalize`/`subscribe` (one observer fiber per region, calls
  `UIExchange.onChange(region, path, ..., previous, ui)`), and the dispatcher `handle(path, UIEvent)` (bubbling,
  stopPropagation, two-way binding writes the SignalRef BEFORE onInput/onChange, Enter/Space activation of
  button/anchor/checkbox/radio/select, Enter in a form = submit). HTML-specific parts: `ReactiveRegion` ids (comment
  anchors), `ParentContext.HtmlTable`, `HtmlRenderer`, `UIDiff` (byte-level), the client JS (focus trap, focus groups,
  tab order, scroll keys).
- How runners find reactive nodes (`ReactiveUI.walkStatic`): a `Reactive(signal: Signal[UI])` child, a
  `Foreach(signal: Signal[Chunk[A]], key, render)` child (mapped to a `Fragment` of keyed children), and any element
  whose `.value`/`.checked` is `Bound.Ref(ref)` (the element becomes a region over `ref.map(_ => elem)`). Signal-typed
  setters (`.style(Signal)`, `.hidden(Signal)`, `.disabled(Signal)`, `.checked(Signal)`) are just `Reactive` wrappers.
  Each region is observed with `Signal.observe` in its own fiber; each value runs in a fresh `Scope` that owns the
  nested regions (next value tears them down).
- `Signal.map(...).observe` delegates to the SOURCE's observe loop and compares source values, so a region over
  `ref.map(f)` does not re-render on the 1 s repair timer (no idle churn). `combineLatest`/`zip` use the generic
  repairing loop (compares the tuple).
- Kyo `SignalRef.set` completes a promise; observers resume as scheduled tasks (`IOTask`: `promise.onComplete(_ =>
  Scheduler.schedule(this))`). There is NO synchronous change hook on `Signal`, so a backend cannot know, at the end
  of a handler, which regions will change. Consequence: glitch-free frames need a settle step (see the runner
  design). Upstream proposal candidate.
- kyo-ui's dispatcher already documents "TUI semantics" (Enter toggles a checkbox, Enter checks a radio); the
  original kyo-ui PR (#1649) says "Web-only (no TUI backend) ... scoping choices".
- Layout model of kyo-ui (from `HtmlRenderer.baseCss`): everything is flexbox. Column: div, section, main, header,
  footer, form, p, ul, ol, pre, code, h1-h6, label. Row (align-items center): nav, li, span, button, a. Reactive
  boundaries are `display: contents` (transparent for layout). Lists have no markers by default.
- Client-side focus rules (HtmlRenderer JS): Tab order = positive tabIndex ascending, then document order;
  `focusGroup(id)`: ArrowLeft/ArrowRight cycle among peers of the group; `focusTrap`: Tab wraps inside the trap.

## Runner design (decided)

- Package `tui4s.kyoui` (outside `kyo`). Own region engine (not kyo-ui's private `ReactiveUI`), own dispatcher that
  mirrors kyo-ui's semantics, because the terminal needs its own paths, focus and layout anyway.
- One runner fiber owns the world (immutable `World` threaded through a `Loop`) and the only mutable objects: the
  kept jatatui `Buffer` (back) and the copy of what the terminal shows (front).
- Regions: `Reactive` -> one content UI; `Foreach` -> keyed items, reconciled by item value (only new/changed items
  are re-rendered); `Bound.Ref` element -> the element's value. On mount the runner reads `signal.current`
  synchronously and starts one watcher fiber (`signal.observe(v => inbox.put(Content(path, gen, v)))`).
- Settle (glitch-free in practice): after an event, apply all queued content messages, then do scheduler hops
  until N consecutive hops bring nothing; only then lay out, paint and flush once.
- Layout: flexbox in cells (1 px = 1 cell). Regions are transparent: their items are flex items of the owning
  element. A region change re-arranges only the owning container; if the siblings keep their rects, only the
  region's items are repainted; if the container's natural size changes, the parent is re-arranged (and so on up).

## Progress log (continued)

- 2026-10-02: runner core compiles and runs headless (Counter, Todo, Dashboard, hooks, Component). Component state
  survives a parent re-render (same position + class); hooks mounted by `component(...)` lose their state when a `when`
  hides them (React-like).
- FINDING (glitch-free): settling by counting empty scheduler round trips is NOT reliable. Glitch probe (`dev/Glitch.scala`:
  one key writes refs `a` and `b`; four regions: `a`, `b`, `a.combineLatest(b)`, `a.map(2*_)`; a frame is "split" when it
  does not carry all 4 updates), 2000 presses each, machine shared with another compiling agent:
  hops=1: 19 split; hops=2: 7; hops=3: 3..131; hops=6: 26; hops=3 + 250 us quiet window: 0; hops=3 + 1 ms window: 0.
  Cause: `combineLatest.nextWith` = `Async.race` of the inputs' `next`, and `observe` races `nextWith` with a repair
  timer, so one write reaches the runner after 4-5 scheduler hops, and hops run in parallel on other workers.
  Default chosen: 3 hops AND 250 us without news. Kyo's timer cannot sleep less than ~0.5 ms (measured: timeout(50us)
  median 478 us, timeout(1ms) median 1365 us), so the quiet window is measured with nanoTime while hopping.
- FINDING (lift trap in plain kyo-ui, reproduced in the Todo): a key handler written as a `val` without an expected
  type, `listKeys = (e: KeyboardEvent) => e.key match { case ArrowUp => move(-1) ... case _ => () }`, infers the result
  type `Any` (LUB of `Int < Sync` and `Unit`). It still compiles where `KeyboardEvent => Any < Async` is expected, but
  the effects are lifted as plain values and NEVER RUN (no error, no warning). Ascribing `: Any < Async` fixes it.
  kyo-ui typing handlers as `Any < Async` (not `Unit < Async`) makes this easy to hit.
- FINDING (settle): counting scheduler round trips is not reliable; a time window is better but not a guarantee. New
  default `Settle.Quiescent(100.millis)`: settle until Kyo's scheduler has no queued or running task besides the runner
  (reads `Scheduler.get.loadAvg()`/`busyWorkers()`, plus `currentWorkers` by reflection). A write ENQUEUES every parked
  watcher synchronously, so "scheduler idle" means "all consequences posted". Glitch probe, 2000 events: 0 split frames
  (after warm-up). It fails only when a woken watcher waits > 100 ms for a CPU (seen with machine load average ~100:
  this Mac was shared with another agent's builds) or when an unrelated fiber keeps a worker busy; then the frame
  flushes and the late value gets its own frame.
