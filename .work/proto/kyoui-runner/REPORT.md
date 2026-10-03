# Prototype "kyoui-runner": a terminal runner for kyo-ui

Status: DONE (2026-10-03). Plan steps 1-7 are done. Limits: the glitch-free settle is "very likely", not certain
(Findings N2); the PTY run covers Counter and Todo only (as planned), not the Dashboard, resize, SIGTERM or a pipe.
Folder: `.work/proto/kyoui-runner/`. Sections at the end: "User-facing API", "Findings", "Upstream proposals for
kyo-ui", "What carries into PRs 3-5", "Open questions for the user".

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

## Progress log (2026-10-03, Linux container session)

Machine: Linux container, 4 CPUs, 15 GB RAM, JDK 25, scala-cli. Another agent compiles on the same machine at the
same time. All results below are from THIS machine, not from the desktop.

- 2026-10-03: session started. Baseline (measured before this session): `scala-cli test --jvm system kyoui-runner`
  gives 7 of 8 tests pass. The todo test fails at `test/Apps.test.scala:123`: it compares `c.bg.toString` with
  `Rgb[r=99, g=102, b=241]`, but the value prints as `#6366F1`. That is the same colour in a different text form.
- 2026-10-03 step 1: fixed the stale assertion in `test/Apps.test.scala` (todo test). It now compares the cell
  background with `jatatui.core.style.Color.Rgb(0x63, 0x66, 0xf1)` by value (`Color.Rgb` is a Java record with value
  `equals`; its `toString` gives `#6366F1`). kyo-ui's `Style.Color.indigo` is `Hex("#6366f1")`, the same colour.
  Full suite 3 times (`logs/test-1003-{1,2,3}.log`): 8 of 8 pass in each run. Load average was about 1.2 (the other
  agent did not compile at that time). Next: check the suite under CPU load, because the settle reads the scheduler.
- 2026-10-03 step 1, flakiness under load. Method: the test JVM and 2 busy loops are pinned to CPUs 2 and 3
  (`taskset -c 2,3`); `-XX:ActiveProcessorCount=4` keeps 4 Kyo workers; CPUs 0 and 1 stay free for the other agent.
  Before any fix (`logs/test-1003-load2x2-{1,2,3}.log`): 3 of 3 runs FAIL. The glitch test fails in each run (5, 4 and
  2 split frames of 500); the dashboard test fails once and the todo test fails once (same cause: a frame without one
  of the two region updates). The split frames have a settle time of 3 to 18 ms, so the 100 ms limit did not expire:
  the settle stopped too early.
- ROOT CAUSE 1 (our bug, fixed): `Runner.settle` drained the inbox, found nothing, THEN read the scheduler probe. A
  watcher that posted between the drain and the probe was parked again when the probe ran, so the probe said
  "quiescent" and the value stayed in the inbox for a later Background frame. On a contended CPU the OS can stop the
  runner thread between the two reads for a full time slice, so the window is wide. Fix: read the probe first, then
  drain; stop only when the probe was quiescent BEFORE a drain that found nothing.
  After the fix, same load (`logs/test-1003-fix-load2x2-{1,2,3}.log`): 2 of 3 runs pass; 1 split frame in 1500 events
  (settle 7.7 ms). The todo and dashboard tests pass in all 3 runs.
- ROOT CAUSE 2 (in Kyo, cannot be fixed from outside): the probe reads `queue.size()` plus `currentTask` of each worker,
  one worker after the other. A woken task is invisible (a) between `queue.poll()` and `runTask` setting `currentTask`
  (`Worker.run`), (b) while a steal moves the head task to the thief, and (c) when the scan is not atomic: a task on a
  worker that the scan did not read yet can enqueue its successor on a worker that the scan already read, then finish.
  Each window is short, but the OS can stop a thread inside it for a full time slice on a contended CPU.
- Also fixed: the counter and todo tests slept a fixed 50 ms after the quit key, then read `finished`. They now call
  the new `Headless.awaitEnd(10.seconds)`, which returns as soon as the run fiber is done.
- 2026-10-03 step 1, second change: `Settle.Quiescent(max, confirm = 2)`. The settle now ends after `confirm` quiescent
  observations in a row, one scheduler hop apart, each followed by an empty drain. This makes root cause 2 less likely.
  It cannot remove it: only an upstream change can (see "Upstream proposals").
  Glitch probe (`dev/Glitch.scala`, args `3000 confirm`), same load, 3000 events per row, two rounds
  (`logs/glitch-1003-load2x2.log`): confirm=1: 0 and 0 split frames; confirm=2: 0 and 0; confirm=3: 0 and 0. Median
  event-to-flush time in the second round: 96 us (confirm=1), 108 us (2), 133 us (3); p99 about 4 to 5 ms (load).
  So the residual rate with confirm=1 is low (1 split in 1500 suite events, 0 in 6000 probe events). The data do not
  prove that confirm=2 is necessary; it is a cheap margin (one hop, about 10 to 40 us).
- 2026-10-03 step 1 result (with both fixes, `Settle.Quiescent(100.millis, 2)` as default):
  without load, 3 of 3 runs: 8 of 8 pass (`logs/test-1003-noload-confirm2-{1,2,3}.log`, load average 2.8: the other
  agent was busy); under the pinned contention, 6 of 6 runs: 8 of 8 pass (`logs/test-1003-load-confirm2-{1..6}.log`).
  Also removed the unused import warning in `Dispatch.scala`.
- 2026-10-03 step 2 (measurements) preparation: `FrameStats` has two new fields: `endNanos` (`System.nanoTime` at the
  end of the flush) and `subtrees` (paint roots of the frame). `Paint.apply` returns the number of paint roots. New
  bench `dev/Bench.scala` (see the table below).
- FINDING (layout, fixed): a smoke run of the bench added 200 Todo items without deleting. The list grew past the 30
  rows and `Layout.arrange` shrank EVERY flex item in proportion, down to 0 rows: the screen showed only the border of
  the list; the header, the input bar and the rows were gone, so typing changed 0 cells on the screen. CSS does not
  do this: a flex item has `min-height: auto`, so in a column it does not shrink below its content height and the page
  overflows. Fix in `arrange`: in a column, an item without an explicit height does not shrink below its measured
  height; the owner clips the overflow. New test "overflow: a list taller than the screen ..." (40 items): header,
  input and rows 1-20 are visible, item 40 is clipped. Suite: 9 of 9 pass (`logs/test-1003-noload-overflow-1.log`).
  Still a gap: no scrolling (kyo-ui's HTML has a page scroll and `overflow` styles); see "Findings".
- 2026-10-03 step 2 DONE: measurements, see the section "Measurements (step 6 of the plan)" below.

## Measurements (step 6 of the plan, 2026-10-03)

Machine: Linux container, 4 CPUs, 15 GB RAM, JDK 25 (OpenJDK 25), scala-cli `run --jvm system` (JIT, no AOT). The
machine is SHARED: another agent compiled at the same time. The load average was 2.2 to 2.5 during run B and 4.4 to 5.3
during run A. So compare the two runs with care: a difference in p99 can come from the load, not from the flags.

Method (`dev/Bench.scala`): headless runner on a jatatui `TestBackend` of 100x30, one JVM, the apps of `apps/`.
Warm-up first (300 events; 100 loops for the Todo loop), then 1000 measured events per row (Todo grow: 50).
"p50/p99/max" = time from the moment the bench sends the event (a key into the inbox; for the Dashboard, the write of
one sampler tick into the metrics ref) to the end of the flush of that event's frame (`FrameStats.endNanos`).
"cells flushed" = cells sent to the terminal backend. "cell writes" = cells written into the kept buffer.
"region updates" = region values applied (the regions that changed). "subtrees painted" = paint roots.
"late frames" = frames that came after the event's own frame (a split; 0 is the target).
Settle = `Settle.Quiescent(100.millis, confirm = 2)` (the default after step 1).

Run B, with the tui4s Kyo scheduler properties (the values of `SchedulerDefaults` on origin/feat/core-3-terminal:
`cycleIntervalNs=10000000`, `blockingMonitorIntervalNs=20000000`, `regulator.concurrencyCollectIntervalMs=100`),
`logs/bench-1003-tui4sflags.log`:

| scenario | events | p50 us | p99 us | max us | cells flushed: median (min-max) | cell writes | region updates | subtrees painted | settle p50 us | layout+paint+flush p50 us | late frames |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Counter: `+` key | 1000 | 626 | 1238 | 2053 | 1 (1-4) | 6 (6-111) | 1 (1-1) | 1 (1-1) | 328 | 206 | 0 |
| Todo: Space toggles the first item (list of 3, focus on the list) | 1000 | 665 | 1772 | 16514 | 11 (11-11) | 228 (228-228) | 2 (2-2) | 2 (2-2) | 348 | 235 | 0 |
| Todo loop: type `x` into the input | 1000 | 175 | 507 | 9039 | 2 (2-2) | 82 (82-82) | 0 (0-0) | 1 (1-1) | 94 | 45 | 0 |
| Todo loop: Enter adds | 1000 | 748 | 1822 | 12967 | 356 (356-356) | 2468 (2468-2478) | 3 (3-3) | 1 (1-1) | 193 | 503 | 0 |
| Todo loop: Tab to the list | 1000 | 298 | 669 | 12300 | 238 (238-238) | 1044 (1044-1044) | 0 (0-0) | 2 (2-2) | 14 | 222 | 0 |
| Todo loop: Space toggles | 1000 | 341 | 757 | 4545 | 4 (4-4) | 221 (221-221) | 2 (2-2) | 2 (2-2) | 194 | 110 | 0 |
| Todo loop: `d` deletes the first item | 1000 | 687 | 1794 | 13375 | 356 (356-356) | 2450 (2450-2460) | 2 (2-2) | 1 (1-1) | 162 | 483 | 0 |
| Todo loop: Shift-Tab to the input | 1000 | 279 | 633 | 3958 | 236 (236-236) | 908 (908-908) | 0 (0-0) | 2 (2-2) | 14 | 201 | 0 |
| Todo grow: Enter adds, items 4-53 | 50 | 2027 | 21701 | 21701 | 5 (4-366) | 6529 (2493-6678) | 3 (3-3) | 1 (1-1) | 312 | 1227 | 0 |
| Todo grow: Enter adds, items 154-203 | 50 | 5566 | 24426 | 24426 | 4 (4-6) | 6533 (6533-6533) | 3 (3-3) | 1 (1-1) | 435 | 5088 | 0 |
| Dashboard: one sampler tick | 1000 | 328 | 615 | 9240 | 46 (30-69) | 267 (136-267) | 2 (1-2) | 2 (1-2) | 15 | 115 | 0 |

Run A, Kyo default scheduler settings, `logs/bench-1003-default.log` (p50 / p99 in us; the other columns are the same
as in run B): Counter 876 / 5667; Todo toggle (list) 667 / 3222; type 191 / 1758; add 791 / 7969; Tab 310 / 4400;
toggle (loop) 365 / 4416; delete 739 / 7594; Shift-Tab 290 / 3056; grow 4-53: 4011 / 10624; grow 154-203:
5573 / 75329; Dashboard tick 371 / 2750. Late frames: 0 in all rows.

Idle CPU (no events for 10 s; whole JVM process CPU time; the app's runner is mounted and waits):

| idle case | Kyo defaults (run A): % of one core | tui4s scheduler properties (run B): % of one core | frames in 10 s |
| --- | --- | --- | --- |
| no app mounted (JVM + Kyo only) | 14.0 | 4.0 | 0 |
| Counter | 13.1 | 3.6 | 0 |
| Todo | 12.5 | 4.0 | 0 |
| Dashboard, live (sampler every 250 ms, log every 400 ms; NOT idle) | 20.7 | 9.0 | 65 |

Notes on the numbers:
- An idle runner costs nothing that we can measure: Counter and Todo are at the level of "no app mounted". The idle
  cost is Kyo's own threads. With Kyo defaults, one `kyo-scheduler-timer` thread uses about 85 ms of CPU per second
  (the 100 us scheduler cycle) and `kyo-scheduler-clock` about 15 ms per second. The tui4s properties remove most of
  the timer cost; the clock thread (about 20 ms per second) stays.
- The headless bench has no input fiber. `Tui.run` adds a crossterm `poll(20 ms)` loop; step 3 measures the real
  process idle CPU in a PTY.
- About half of a small frame is the settle (Counter: 328 of 626 us). It is the price of glitch-free frames without a
  synchronous change hook in Kyo (see "Upstream proposals").
- Layout+paint+flush of a 1-cell change is about 200 us: the immutable layout index (`Map[Loc, Node]`) and the
  re-layout of the region's owner up the tree cost more than the paint. Not optimised in this prototype.
- A change of the list length (add, delete) repaints the list and everything below it (356 cells flushed, about 2460
  cell writes), because the following siblings move. Focus moves repaint the old and the new focused box (a border
  style change: 236-238 cells).
- "Todo grow" is the overflow case (the list is longer than the screen): the cost grows with the list length (no
  virtualisation: every item is laid out, also the clipped ones): 5.6 ms p50 at 200 items.
- 2026-10-03 step 3 DONE: real PTY run, see the section "Real PTY run (step 7 of the plan)" below.
- 2026-10-03 step 4 DONE: the sections "User-facing API", "Findings", "Upstream proposals for kyo-ui", "What carries
  into PRs 3-5" and "Open questions for the user" are at the end of this file. Final check after all changes: full
  suite 3 times without load, 9 of 9 pass each time (`logs/test-1003-final-{1,2,3}.log`); `pty/check.py` again on the
  PTY log: 20 of 20. Authorship marks (`// claude 2026-10-03: ...`) are on each function or fragment that this session
  created or changed.
- Files changed in this session: `test/Apps.test.scala`, `src/tui4s/kyoui/Tui.scala`,
  `src/tui4s/kyoui/internal/{Runner,Paint,Layout,Dispatch}.scala`, `dev/Glitch.scala`; new: `dev/Bench.scala`,
  `pty/{vt.py,check.py,checklist.exp,run.sh,cpu.sh,run-all.sh}`; logs `logs/*-1003-*.log`, `logs/pty-*.log`.

## Real PTY run (step 7 of the plan, 2026-10-03)

Artifact (no sbt): `scala-cli --power package --jvm system kyoui-runner --main-class apps.CounterMain -o <scratch>/kyoui-apps.jar --assembly --preamble=false -f`
(run from `.work/proto/`; 40 MB; it holds all mains of `apps/`; the jar is NOT kept in `.work/` because of its size).
The apps start with `pty/run.sh <main>`: `java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Xss16m -Xmx512m -cp <jar> <main>`.

Tools (new folder `pty/`, adapted from `.work/scratch/pty/`):
- `pty/checklist.exp`: expect script. It starts bash in a PTY of 100x30 (`TERM=xterm-256color`), saves `stty -a`,
  starts the app, waits for `ESC[?1049h` and the first text, reads `stty -a` of the PTY slave FROM OUTSIDE while the
  app runs (raw mode check), sends the keys, waits for the exit code, saves `stty -a` again and compares the two files
  with `cmp`. It writes `@@MARK name@@` lines into `logs/pty-session.log`.
- `pty/vt.py`: a NEW minimal VT emulator (the desktop `analyze.py` only removed escape codes; that cannot check a
  partial repaint, which sends single cells with cursor moves). It replays the log into a 100x30 grid (cursor moves,
  erase, alternate screen, UTF-8 wide characters, scroll) and takes a snapshot of the screen at each mark.
- `pty/check.py`: checks the snapshots; writes them to `logs/pty-screens.log`. `pty/run-all.sh <jar>` runs both.

Result (`logs/pty-checklist.log`, `logs/pty-check.log`, `logs/pty-screens.log`, `logs/pty-stty-*.log`,
`logs/pty-notes.log`): PTY part 16 of 16 PASS, screen part 20 of 20 PASS.
- Counter: alternate screen entered; raw mode while running (`-icanon -echo -isig`, also `-opost -ixon -icrnl`);
  `+ + -` give the screens `Count: 1`, `Count: 2`, `Count: 1`; each key writes 26 bytes to the terminal (one cell:
  `ESC[2;8H` + the digit + SGR resets); `q` leaves the alternate screen, shows the cursor, exit code 0, and the next
  output is the shell's `EXIT=0` (nothing stray); `stty -a` after = before.
- Counter, second run with the tui4s scheduler properties: same; Ctrl-C quits with exit code 0; `stty -a` after = before.
- Todo (fresh file): typed `buy milk` shows `New: buy milk` (284 bytes for 8 keys); Enter adds `[ ] buy milk`,
  `1 open / 1`, clears the input (243 bytes); Tab focuses the list (752 bytes: the list border changes colour); Space
  gives `[x] buy milk`, `0 open / 1` (118 bytes); Ctrl-S shows `saved 1 items` and the file holds `[x] buy milk`; Esc
  quits (exit code 0, alternate screen left, cursor shown); `stty -a` after = before. A lone Esc byte works as the Esc
  key (crossterm reads it after a short wait).
- First frame: about 0.8-0.9 s after the start command (JVM start included).
- The apps wrote nothing to stdout or stderr during the run (`tui4s.log` in the work folder stayed empty).
- Idle CPU of the REAL Counter process (with the crossterm `poll(20 ms)` input fiber), no keys for 10 s, from
  `/proc/<pid>/stat`: Kyo default scheduler 15.4% and 15.7% of one core (two runs); tui4s scheduler properties 5.4%
  and 6.2%. The headless bench gave 13.1% and 3.6%: the input poll loop adds about 2 points.
- Not checked in the PTY: the Dashboard, resize (SIGWINCH), kill -TERM, a pipe as stdin, bracketed paste. These were
  in the desktop checklist of the old core; they are not in the plan of this prototype (see "Open questions").

## User-facing API

This is the code that an app author writes. Runner internals are not in it. The code below is a verbatim copy of the
files at the end of this session (2026-10-03). The app author imports `kyo.*`, `kyo.UI.*` and, from tui4s, only
`tui4s.kyoui.Tui` (run, quit), `tui4s.kyoui.Hooks.*` (hooks style) or `tui4s.kyoui.Component` (class style).

What tui4s adds to kyo-ui (all of it is in `src/tui4s/kyoui/`):

| Name | Kind | What it does |
| --- | --- | --- |
| `Tui.run(ui: => UI < (Async & Scope))` / `Tui.run(options)(ui)` | `Unit < Async` | Runs the UI in the real terminal until a quit. |
| `Tui.quit` | `Unit < Sync` | Ends the run. Call it from a handler or from a fiber that the UI started. |
| `TuiOptions(ctrlCQuits, autoFocus, settle, pendingTimeout, logFile)` | case class | Options of a run. |
| `Hooks.useSignal(a)` | `SignalRef[A] < Sync` | `Signal.initRef(a)`. |
| `Hooks.useHold`, `useFold`, `useEffect`, `useInterval` | `< (Async & Scope)` | Signals from streams, effects that live as long as the enclosing `Scope`. |
| `Hooks.component(key)(body)` | `UI` | A node that the runner mounts: it runs `body` at mount and closes its `Scope` at unmount. |
| `abstract class Component { type State; def initial; def view(state: SignalRef[State]): UI; def onMount(state) }` | class | The runner allocates the state at mount and keeps it while the same class stays at the same position. |
| `Headless.start(w, h, options)(ui)` | test kit | A run on a jatatui `TestBackend`: `key`, `char`, `typeText`, `resize`, `screen`, `lines`, `cell`, `nextFrame`, `drainFrames`, `awaitEnd`, `quit`. Each key returns the `FrameStats` of its frame. |

### `apps/CounterStyles.scala`

Counter in BOTH styles: hooks (`CounterHooks`, with `useSignal` and `component(...)`) and the type-member `Component` class (`CounterBox`, `CounterComponents`).

```scala
package apps

import kyo.*
import kyo.UI.*
import tui4s.kyoui.Component
import tui4s.kyoui.Hooks.*
import tui4s.kyoui.Tui

/** Style 1: hooks. A component is a function that returns `UI < Sync` (or `< (Async & Scope)`). */
object CounterHooks:

    def counter(label: String): UI < Sync =
        for count <- useSignal(0)
        yield div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
            span(label).style(_.bold),
            span("Count: ", count.map(_.toString)),
            div.style(_.row.gap(1.px))(
                button("+").onClick(count.updateAndGet(_ + 1)),
                button("-").onClick(count.updateAndGet(_ - 1))
            )
        )

    /** Sequenced at the top: each call allocates its own state once. */
    val app: UI < Sync =
        for
            left  <- counter("Left")
            right <- counter("Right")
        yield div.onKeyDown {
            case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
            case _                                       => ()
        }(h1("Hooks"), div.style(_.row.gap(2.px))(left, right))

    /** The same, mounted by the runner: `component(...)` runs the effect when the node mounts. Here the counters live
      * inside a `when`: hiding them drops their state, showing them again starts from 0.
      */
    val dynamic: UI < Sync =
        for shown <- useSignal(true)
        yield div.onKeyDown {
            case KeyboardEvent(Keyboard.Char('h'), _, _) => shown.updateAndGet(!_)
            case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
            case _                                       => ()
        }(
            h1("Hooks, mounted by the runner (h hides/shows)"),
            when(shown)(div.style(_.row.gap(2.px))(component(counter("Left")), component(counter("Right"))))
        )
end CounterHooks

/** Style 2: a component class with a type member for its state. The runner allocates the state at mount. */
final class CounterBox(label: String) extends Component:
    type State = Int
    def initial: Int = 0
    def view(count: SignalRef[Int]): UI =
        div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
            span(label).style(_.bold),
            span("Count: ", count.map(_.toString)),
            div.style(_.row.gap(1.px))(
                button("+").onClick(count.updateAndGet(_ + 1)),
                button("-").onClick(count.updateAndGet(_ - 1))
            )
        )
end CounterBox

object CounterComponents:
    val app: UI =
        div.onKeyDown {
            case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
            case _                                       => ()
        }(h1("Components"), div.style(_.row.gap(2.px))(CounterBox("Left"), CounterBox("Right")))
end CounterComponents

object CounterHooksMain extends KyoApp:
    run(Tui.run(CounterHooks.app))

object CounterHooksDynamicMain extends KyoApp:
    run(Tui.run(CounterHooks.dynamic))

object CounterComponentsMain extends KyoApp:
    run(Tui.run(CounterComponents.app))
```

### `apps/Counter.scala`

Counter in plain kyo-ui (no tui4s helper except `Tui.run` and `Tui.quit`), and two counters as plain functions.

```scala
package apps

import kyo.*
import kyo.UI.*
import tui4s.kyoui.Tui

/** The Counter in plain kyo-ui: only kyo-ui's API, plus `Tui.quit` for the quit key. */
object Counter:

    val app: UI < Sync =
        for count <- Signal.initRef(0)
        yield div.onKeyDown {
            case KeyboardEvent(Keyboard.Char('+'), _, _) => count.updateAndGet(_ + 1)
            case KeyboardEvent(Keyboard.Char('-'), _, _) => count.updateAndGet(_ - 1)
            case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
            case _                                       => ()
        }(
            h1("Counter"),
            span("Count: ", count.map(_.toString)).id("count"),
            div.style(_.row.gap(1.px))(
                button("+").onClick(count.updateAndGet(_ + 1)),
                button("-").onClick(count.updateAndGet(_ - 1))
            ),
            p("+ / - change it, Tab moves, Enter presses, q quits").style(_.color(Style.Color.gray))
        )

    /** One counter as a function: its state is allocated when the function runs. */
    def counter(label: String): UI < Sync =
        for count <- Signal.initRef(0)
        yield div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
            span(label).style(_.bold),
            span("Count: ", count.map(_.toString)),
            div.style(_.row.gap(1.px))(
                button("+").onClick(count.updateAndGet(_ + 1)),
                button("-").onClick(count.updateAndGet(_ - 1))
            )
        )

    /** Two independent counters side by side. */
    val twoCounters: UI < Sync =
        for
            left  <- counter("Left")
            right <- counter("Right")
        yield div.onKeyDown {
            case KeyboardEvent(Keyboard.Char('q'), _, _) => Tui.quit
            case _                                       => ()
        }(
            h1("Two counters"),
            div.style(_.row.gap(2.px))(left, right),
            p("Tab moves between the four buttons, Enter presses, q quits").style(_.color(Style.Color.gray))
        )
end Counter

object CounterMain extends KyoApp:
    run(Tui.run(Counter.app))

object TwoCountersMain extends KyoApp:
    run(Tui.run(Counter.twoCounters))
```

### `apps/Todo.scala`

Todo: text input bound to a `SignalRef`, a form (Enter = submit), a keyed list with keyboard selection, toggle, delete, save to a file.

```scala
package apps

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kyo.*
import kyo.UI.*
import tui4s.kyoui.Tui

/** The Todo app in plain kyo-ui. Components are plain functions of signals and actions. */
object Todo:

    final case class Item(id: Int, text: String, done: Boolean) derives CanEqual
    final case class Row(item: Item, selected: Boolean) derives CanEqual

    // ---- file ----

    def parse(lines: Chunk[String]): Chunk[Item] =
        lines.filter(_.length >= 4).zipWithIndex.map((l, i) => Item(i + 1, l.drop(4), l.startsWith("[x]")))

    def render(items: Chunk[Item]): String =
        items.map(t => s"${if t.done then "[x]" else "[ ]"} ${t.text}").mkString("", "\n", "\n")

    def load(file: String)(using Frame): Chunk[Item] < Sync =
        Abort.run[java.io.IOException](Abort.catching[java.io.IOException] {
            val p = Paths.get(file)
            if Files.exists(p) then Chunk.from(Files.readAllLines(p, StandardCharsets.UTF_8).toArray.map(_.toString)) else Chunk.empty
        }).map(r => parse(r.getOrElse(Chunk.empty)))

    def save(file: String, items: Chunk[Item])(using Frame): Unit < (Sync & Abort[java.io.IOException]) =
        Abort.catching[java.io.IOException](Files.writeString(Paths.get(file), render(items), StandardCharsets.UTF_8)).unit

    // ---- components ----

    def header(count: Signal[String]): UI =
        div.style(_.row.gap(2.px))(h1("Todo"), span(count).style(_.color(Style.Color.gray)))

    def inputBar(draft: SignalRef[String], add: => Any < Async): UI =
        form.onSubmit(add)(
            div.style(_.row.gap(1.px))(
                span("New:"),
                input.id("draft").placeholder("What needs doing? (Enter adds)").value(draft).style(_.width(40.px))
            )
        )

    def row(r: Row): Ast.Li =
        li.style(if r.selected then Style.bg(Style.Color.indigo).color(Style.Color.white) else Style.empty)(
            checkbox.checked(r.item.done),
            span(" " + r.item.text).style(if r.item.done then Style.strikethrough.color(Style.Color.gray) else Style.empty)
        )

    def list(rows: Signal[Chunk[Row]], onKey: KeyboardEvent => Any < Async): UI =
        ul.id("list").tabIndex(0).onKeyDown(onKey)
            .style(_.border(1.px, Style.Color.slate).minHeight(4.px).focus(_.border(1.px, Style.Color.blue)))(
                rows.foreachKeyed(_.item.id.toString)(row)
            )

    def footer(status: Signal[String]): UI =
        div(
            span(status).id("status").style(_.color(Style.Color.green)),
            span("Tab: input/list  ↑↓: move  Space: toggle  d: delete  Ctrl-S: save  Esc: quit").style(_.color(Style.Color.gray))
        )

    // ---- the app ----

    def app(file: String): UI < (Async & Scope) =
        for
            loaded <- load(file)
            items  <- Signal.initRef(loaded)
            draft  <- Signal.initRef("")
            cursor <- Signal.initRef(0)
            status <- Signal.initRef(s"loaded ${loaded.size} items from $file")
            nextId <- AtomicInt.init(loaded.size + 1)
            rows  = items.combineLatest(cursor).map((ts, c) => ts.zipWithIndex.map((t, i) => Row(t, i == c)))
            count = items.map(ts => s"${ts.count(!_.done)} open / ${ts.size}")
            add = draft.get.map { text =>
                if text.trim.isEmpty then ()
                else nextId.getAndIncrement.map(id => items.updateAndGet(_ :+ Item(id, text.trim, false))).andThen(draft.set(""))
            }
            atCursor = (f: (Chunk[Item], Int) => Chunk[Item]) =>
                cursor.get.map(c => items.updateAndGet(ts => if c >= 0 && c < ts.size then f(ts, c) else ts))
            toggle = atCursor((ts, c) => ts.updated(c, ts(c).copy(done = !ts(c).done)))
            delete = atCursor((ts, c) => ts.take(c) ++ ts.drop(c + 1)).map(ts => cursor.updateAndGet(c => c.min(ts.size - 1).max(0)))
            move   = (d: Int) => items.get.map(ts => cursor.updateAndGet(c => (c + d).max(0).min((ts.size - 1).max(0))))
            // The result type is load-bearing: without it the match widens to `Any` and kyo lifts the effects as plain
            // values, so they never run (the "lift trap"; see REPORT.md section 5).
            listKeys = (e: KeyboardEvent) =>
                (e.key match
                    case Keyboard.ArrowUp | Keyboard.Char('k')   => move(-1)
                    case Keyboard.ArrowDown | Keyboard.Char('j') => move(1)
                    case Keyboard.Space                          => toggle
                    case Keyboard.Char('d')                      => delete
                    case _                                       => ()
                ): Any < Async
            saveAll = items.get.map(ts =>
                Abort.run[java.io.IOException](save(file, ts)).map {
                    case Result.Success(_) => status.set(s"saved ${ts.size} items to $file")
                    case other             => status.set(s"save failed: $other")
                }
            )
        yield main.onKeyDown {
            case KeyboardEvent(Keyboard.Char('s'), mods, _) if mods.ctrl => saveAll
            case KeyboardEvent(Keyboard.Escape, _, _)                    => Tui.quit
            case _                                                       => ()
        }(
            header(count),
            inputBar(draft, add),
            list(rows, listKeys),
            footer(status)
        )
end Todo

object TodoMain extends KyoApp:
    run(Tui.run(Todo.app(sys.env.getOrElse("TODO_FILE", "todos.txt"))))
```

### `apps/Dashboard.scala`

Dashboard: tabs, a gauge and a sparkline from a sampler fiber, a log pane from a `Stream`, pause.

```scala
package apps

import kyo.*
import kyo.UI.*
import tui4s.kyoui.Tui

/** The Dashboard in plain kyo-ui: tabs (Left / Right), a gauge and a sparkline fed by a 250 ms sampler, a log pane fed by a
  * stream, `p` pauses the sampler (logs go on), `q` quits.
  */
object Dashboard:

    val series = Seq("CPU", "Memory", "Network")

    /** One sample of every series, and the last 40 samples of each. One ref, so one tick is one atomic change. */
    final case class Metrics(now: Chunk[Double], history: Chunk[Chunk[Double]]) derives CanEqual

    object Metrics:
        val empty: Metrics = Metrics(Chunk.fill(series.size)(0.0), Chunk.fill(series.size)(Chunk.empty))

    final case class State(
        tab: SignalRef[Int],
        metrics: SignalRef[Metrics],
        paused: SignalRef[Boolean],
        logs: SignalRef[Chunk[(Int, String)]]
    )

    def init(using Frame): State < Sync =
        for
            tab     <- Signal.initRef(0)
            metrics <- Signal.initRef(Metrics.empty)
            paused  <- Signal.initRef(false)
            logs    <- Signal.initRef(Chunk.empty[(Int, String)])
        yield State(tab, metrics, paused, logs)

    /** One sampler tick with given values (the bench calls this directly). */
    def tick(s: State, values: Chunk[Double])(using Frame): Unit < Sync =
        s.metrics.updateAndGet(m => Metrics(values, m.history.zip(values).map((h, v) => (h :+ v).takeRight(40)))).unit

    /** The sampler: a random walk every 250 ms, skipped while paused. */
    def sampler(s: State)(using Frame): Unit < Async =
        Loop.forever {
            Async.sleep(250.millis).andThen(s.paused.get).map { p =>
                if p then ()
                else
                    s.metrics.get.map { m =>
                        Kyo.foreach(m.now)(v => Random.nextDouble.map(r => (v + (r - 0.5) * 0.3).max(0).min(1))).map(vs => tick(s, vs))
                    }
            }
        }

    /** A log stream: one line every 400 ms. */
    def logStream(using Frame): Stream[String, Async] =
        Stream[String, Async] {
            Loop(1) { i =>
                Async.sleep(400.millis).andThen(Emit.valueWith(Chunk(s"event #$i: request served"))(Loop.continue(i + 1)))
            }
        }

    def overview(s: State): UI =
        s.tab.render { t =>
            div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
                h2(s"${series(t)} usage"),
                Widgets.gauge(s.metrics.map(_.now(t)), 30, Style.Color.green),
                Widgets.sparkline(s.metrics.map(_.history(t)), Style.Color.blue)
            )
        }

    def logPane(s: State): UI =
        div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px).height(10.px))(
            h3("Log"),
            ul(s.logs.foreachKeyed(_._1.toString)((_, line) => li(line)))
        )

    def view(s: State): UI =
        div.onKeyDown {
            case KeyboardEvent(Keyboard.ArrowLeft, _, _)  => s.tab.updateAndGet(t => (t + series.size - 1) % series.size)
            case KeyboardEvent(Keyboard.ArrowRight, _, _) => s.tab.updateAndGet(t => (t + 1) % series.size)
            case KeyboardEvent(Keyboard.Char('p'), _, _)  => s.paused.updateAndGet(!_)
            case KeyboardEvent(Keyboard.Char('q'), _, _)  => Tui.quit
            case _                                        => ()
        }(
            div.style(_.row.gap(2.px))(h1("Dashboard"), Widgets.tabs(s.tab, series)),
            overview(s),
            logPane(s),
            span(s.paused.map(p => if p then "sampler PAUSED (p resumes)" else "sampler running (p pauses)  ←/→ tabs  q quits"))
                .style(_.color(Style.Color.gray))
        )

    val app: UI < (Async & Scope) =
        for
            s <- init
            _ <- Fiber.init(sampler(s))
            _ <- Fiber.init(logStream.foreach(line => s.logs.updateAndGet(l => (l :+ (l.lastOption.fold(1)(_._1 + 1), line)).takeRight(6))))
        yield view(s)
end Dashboard

object DashboardMain extends KyoApp:
    run(Tui.run(Dashboard.app))
```

### `apps/Widgets.scala`

The terminal-style widgets of the Dashboard, written in plain kyo-ui (spans of block characters), so they also render in a browser.

```scala
package apps

import kyo.*
import kyo.UI.*

/** Terminal-ish widgets written in PLAIN kyo-ui (spans of block characters), so they also render in a browser. */
object Widgets:

    private val blocks = "▁▂▃▄▅▆▇█"

    /** A horizontal gauge of `width` cells for a ratio in [0, 1]. */
    def gaugeText(ratio: Double, width: Int): String =
        val r      = ratio.max(0).min(1)
        val filled = (r * width).round.toInt
        "█" * filled + "░" * (width - filled) + f" ${r * 100}%3.0f%%"

    /** A sparkline: one block character per value, scaled to [lo, hi]. */
    def sparklineText(values: Chunk[Double], lo: Double = 0, hi: Double = 1): String =
        values.map { v =>
            val i = (((v - lo) / (hi - lo).max(1e-9)) * (blocks.length - 1)).round.toInt.max(0).min(blocks.length - 1)
            blocks.charAt(i)
        }.mkString

    def gauge(ratio: Signal[Double], width: Int, color: Style.Color): UI =
        ratio.render(r => span(gaugeText(r, width)).style(_.color(color)))

    def sparkline(values: Signal[Chunk[Double]], color: Style.Color): UI =
        values.render(vs => span(sparklineText(vs)).style(_.color(color)))

    /** Tabs: a row of buttons in one focus group (ArrowLeft / ArrowRight move between them); the selected one is bold. */
    def tabs(selected: SignalRef[Int], labels: Seq[String]): UI =
        selected.render { sel =>
            nav.style(_.gap(1.px))(
                fragment(labels.zipWithIndex.map { (l, i) =>
                    button(l).focusGroup("tabs").onClick(selected.set(i))
                        .style(if i == sel then Style.bold.color(Style.Color.yellow) else Style.color(Style.Color.gray))
                }*)
            )
        }
end Widgets
```

### How the tests use it (`test/Apps.test.scala`, extract)

```scala
for
    h  <- Headless.start(100, 30)(apps.Counter.app)
    _  <- h.char('+')
    s1 <- h.screen
    _ = assert(s1.contains("Count: 1"), s1)
    f  <- h.key(Keyboard.Enter)      // returns the FrameStats of the Enter frame
    q  <- h.char('q')
    fin <- h.awaitEnd(10.seconds)
yield ()
```

## Findings

### Old findings (2026-10-02, desktop; kept; details in the logs above)

- O1. `io.getkyo::kyo-ui:1.0.0-RC7` works on the JVM. A runner outside package `kyo` can read every attribute through
  the public `el.attrs` (only the type name `Attrs` is `private[kyo]`).
- O2. kyo-ui's own reactive machinery (`internal/ReactiveUI`, `ReactiveRegion`, `UIExchange`, `UIEvent`,
  `EventHandler`) is `private[kyo]`. The runner has its own region engine and dispatcher. The dispatcher copies
  kyo-ui's rules (bubbling, `stopPropagation`, bound ref written before `onInput`, Enter/Space activation, Enter in a
  form submits, focus groups and focus traps).
- O3. Reactive nodes: `Reactive(signal)`, `Foreach(signal, key, render)`, and an element with `.value`/`.checked` =
  `Bound.Ref`. `Signal.map(...).observe` compares the SOURCE values, so it does not re-render on the 1 s repair timer.
- O4. Kyo's `Signal` has NO synchronous change hook. A write completes a promise; each watcher resumes as a
  scheduled task, often through one or more races (`observe`, `combineLatest`). So a glitch-free frame needs a settle
  step. Counting scheduler round trips is NOT reliable (glitch probe, 2000 events: 3 to 131 split frames).
- O5. LIFT TRAP in plain kyo-ui: a key handler written as a `val` without an expected type
  (`e => e.key match { case ... => move(-1); case _ => () }`) gets the type `Any`. It compiles where
  `KeyboardEvent => Any < Async` is expected, and its effects never run (no error, no warning). The Todo needs the
  ascription `: Any < Async` (see `apps/Todo.scala`, `listKeys`).
- O6. `Settle.Quiescent` (settle until the Kyo scheduler has no task besides the runner): 0 split frames in 2000
  events on the desktop after warm-up. Limits: a woken watcher that waits longer than `max` for a CPU, or an unrelated
  busy fiber (then the frame flushes at `max`).
- O7. Component state survives a parent re-render (same position and class). Hook components inside a `when` lose
  their state when hidden (as in React).

### New findings (2026-10-03, Linux container, 4 CPUs, shared)

- N1. FLAKINESS, root cause in our code (fixed). The settle loop drained the inbox, found nothing, and then read the
  probe. A watcher that posted between the drain and the probe was parked again when the probe ran; the probe said
  "quiescent" and the frame flushed without that value. Under CPU contention (test JVM and 2 busy loops pinned to 2
  CPUs) 3 of 3 suite runs failed (glitch test 5, 4 and 2 split frames of 500; one dashboard and one todo failure).
  Fix: probe first, then drain; stop only when the probe was quiescent before a drain that found nothing. Also
  `Settle.Quiescent(max, confirm = 2)`: two quiescent observations, one hop apart. Result: 6 of 6 runs pass under the
  same contention, 3 of 3 without it; glitch probe 0 split frames in 18000 events under contention.
- N2. FLAKINESS, root cause in Kyo (cannot be fixed in the runner). The probe reads `queue.size()` and `currentTask`
  of each worker, one after the other. A woken task is invisible between `queue.poll()` and `runTask`, during a
  steal, and when the scan is not atomic (a task on a worker that the scan did not read yet enqueues its successor on
  a worker that the scan already read, then finishes). The OS can stop a thread inside such a window for a full time
  slice. So `Settle.Quiescent` is "very likely", not "certain". The probe also reads the private field
  `Scheduler.currentWorkers` by reflection.
- N3. Two tests waited a fixed 50 ms after the quit key. They now use `Headless.awaitEnd(10.seconds)`, which returns
  as soon as the run fiber is done. The idle test keeps its sleeps: it must wait to show that nothing happens.
- N4. MEASUREMENTS (100x30, headless, see the tables above). Key to flush p50 / p99: Counter 0.63 / 1.24 ms; Todo
  toggle 0.34 / 0.76 ms; Todo add 0.75 / 1.82 ms; typing one character 0.18 / 0.51 ms; Dashboard tick 0.33 / 0.62 ms
  (tui4s scheduler properties, load average about 2.3). One `+` changes 1 cell and the PTY run shows 26 bytes on the
  wire. About half of a small frame is the settle. Late frames: 0 in all runs. The old core (bench B4, macOS, other
  machine) had p50 1.56 ms, p99 3.91 ms; the machines differ, so this is not a direct comparison.
- N5. IDLE CPU is Kyo's, not the runner's. An idle Counter or Todo costs the same as a JVM with Kyo and no app: 12.5
  to 14% of one core with Kyo defaults (one scheduler timer thread uses about 85 ms per second), 3.6 to 4% with the
  tui4s scheduler properties (headless). The real Counter process in a PTY: 15.4 to 15.7% with Kyo defaults, 5.4 to
  6.2% with the properties (the crossterm `poll(20 ms)` loop adds about 2 points).
- N6. PTY: Counter and Todo pass all 36 checks (alternate screen, raw mode while running, screens after each key,
  exit code, cursor shown, nothing stray, `stty -a` after = before, saved file). See "Real PTY run".
- N7. LAYOUT GAPS against kyo-ui's HTML flexbox model (`HtmlRenderer.baseCss`: everything is flex):
  - Overflow (fixed for columns in this session): a list taller than the screen shrank every flex item to 0 rows.
    Now a column item without an explicit height keeps its content height (CSS `min-height: auto`). In a ROW, items
    still shrink in proportion, and the runner does not compute a min-content width (CSS `min-width: auto`).
  - No scrolling: the browser page scrolls; the terminal screen is a fixed box. `overflow`, `overflowX/Y`,
    scrollbars, `scrollMarginTop` and `onScroll` are not supported. Content below the screen is clipped and cannot be
    reached; a focus move to a clipped element does not scroll. A long list is laid out in full (no virtualisation:
    5.6 ms per add at 200 items).
  - Not applied: `flexWrap`, `flexBasis`, `display` (block, inline, grid-like table modes; only `hidden` works),
    `position` (`overlay`, `dropdown`, `absolute`, `fixed`, `sticky`, `relative`) with `top/right/bottom/left`,
    `zIndex` and `translate`. So there are no overlays, popovers or modals.
  - Units: 1 px = 1 em = 1 cell; `%` of the parent; `vh` of the screen; `calc` and `auto` are ignored. A browser
    app with `padding(8.px)` gets 8 cells of padding in the terminal; a terminal app with `gap(1.px)` is nearly
    invisible in a browser. The same UI value cannot look right in both without a convention.
  - Text: no `fontSize`, `fontFamily`, `lineHeight`, `letterSpacing`, `textOverflow` (no ellipsis), `textWrap`,
    `whiteSpace`. Text wraps only in a column parent; in a row it is cut. A word longer than the width is split.
  - Borders: width at most 1 cell; `borderRadius` gives rounded corners; one colour for all sides; `borderCollapse`
    ignored. Tables: one row per cell, no `colspan`/`rowspan`.
  - Colours: 24-bit RGB always (no fallback to 256 or 16 colours); alpha below 0.5 is "no colour"; `opacity` below
    0.7 is DIM. Ignored: `shadow`, the filters (`brightness`, `contrast`, `grayscale`, `sepia`, `invert`,
    `saturate`, `hueRotate`, `blur`), `bgGradient`, `transition`, `animation`, `cursor`, `accentColor`,
    `backgroundClip`, `fontVariantLigatures`, `strokeDashoffset`.
  - Pseudo-classes: `focus` and `disabled` work; `hover` and `active` do not (no mouse).
- N8. ELEMENTS AND ATTRIBUTES that the runner does not support:
  - Cannot support on a plain terminal: `onKeyUp` (terminals report key presses only; the kitty keyboard protocol
    is an exception), `Img` (shown as `[alt]`), `Iframe`, SVG and `Chart` (shown as `[chart]`; sixel/kitty graphics or
    braille plots are possible but large), `PageHead`, `DataIsland`/`jsonLd`, `RawHtml` (tags are removed, the text
    stays), CSS classes and `Stylesheet`, `aria*`, `role`, `data*`, `jsProps`, `enterTransition`/`leaveTransition`.
  - Possible but not done: mouse (`onClick` by mouse, `onHover`/`onUnhover`, `onClickSelf`, `onScroll`/`WheelEvent`;
    crossterm can report mouse events), drag and drop (`dragSource`, `dropTarget`, `onDrag*`, `onDrop`,
    `onSortMove`, `Sortable`), multi-line `Textarea` editing (it is edited as one line; Enter runs `onChange`),
    `NumberInput` stepping and checks, pickers for `DateInput`/`TimeInput`/`ColorInput`/`FileInput`, a drop-down list
    for `Select`/`Dropdown` (they cycle with Enter and the arrows), anchor navigation (`href`), `focusRestore`,
    bracketed paste (a paste arrives as single keys, so a pasted newline can submit a form), focus events from the
    terminal.
- N9. ACCESS TO `private[kyo]` CODE: the runner calls no `private[kyo]` member. It works around four limits: (a) the
  type `Attrs` cannot be named (members are read through `el.attrs`); (b) `Foreach.applyTyped` is private, so the
  runner casts `Foreach[?, ?]` to `Foreach[Any, UI]`; (c) the AST is sealed, so a component node is a `Reactive` whose
  signal is registered in a weak global map (`ComponentSpec.registry`, the only global mutable object); (d) the
  probe reads `Scheduler.currentWorkers` by reflection. kyo-ui's dispatcher and region code are private, so the runner
  copies about 400 lines of their semantics (`Dispatch.scala`, `Mount.scala`).
- N10. Runner limits found while reading the old core (see "What carries into PRs 3-5"): `Tui.run` polls crossterm
  on a Kyo worker (a blocking `poll(20 ms)` inside `Sync.defer`), has no JVM shutdown hook (a SIGTERM restore is
  not tested), no `isatty` check (a pipe as stdin is not tested), no clear message when native access is denied, and
  `OutputGuard` only swaps `System.out`/`System.err`. According to the notes of the old core (`OutputCapture.scala`),
  this misses `scala.Console.out` when `scala.Console` loaded first (NOT tested here), and it prints nothing after the
  restore.

## Upstream proposals for kyo-ui

Each proposal gives the reason and the evidence from this prototype.

1. A synchronous change hook, or a transaction API, on `Signal`.
   - Proposal: a way for a backend to know, synchronously, which signals a set of writes changed. Examples:
     `SignalRef.onChange(f)` that runs `f` inside `set`; or `Signal.transaction { writes }` that returns when all
     derived signals have their new values; or a `version` on each signal plus a public list of its sources.
   - Reason: without it, a backend cannot know when an event's consequences are complete. It must guess (settle).
   - Evidence: the settle is about half of a small frame (Counter: 328 of 626 us). Counting scheduler hops gave 3 to
     131 split frames in 2000 events (O4). The scheduler probe needs reflection on a private field and has races in
     Kyo's worker loop (N2). Under CPU contention our first settle version failed the suite 3 of 3 times (N1).
2. Handlers typed `Unit < Async` (or a check that rejects a pure `Any`) instead of `Any < Async`.
   - Reason: `Any < Async` accepts any value, so a computation that was lifted as a value compiles and never runs.
   - Evidence: the lift trap in the Todo (O5); the same trap makes a ZIO or `F[_]` value compile in a handler and never
     run (the reason for the generated facades, DECISIONS.md fifth round).
3. A public runner SPI.
   - Proposal: make public (or stable under an `spi` package) the region walk (`ReactiveUI.walkStatic`/`subscribe`),
     the event dispatcher (`handle(path, UIEvent)` with its bubbling and default actions), a read-only `Attrs` type,
     and `Foreach.applyTyped`.
   - Reason: kyo-ui's README invites custom backends, but each backend must copy the semantics.
   - Evidence: `Dispatch.scala` (242 lines) and `Mount.scala` (181 lines) copy them; `Foreach` needs a cast (N9).
4. An extension node in the AST for backend-specific nodes, for example `UI.Custom(tag, payload, fallback: UI)`.
   - Reason: the AST is sealed. A component node needs state that the runner allocates at mount.
   - Evidence: `ComponentSpec.registry`, a weak global map keyed by signal identity (N9).
5. In kyo-scheduler: a public, consistent "in-flight tasks" count, or an "idle except the caller" query, and a lower
   idle cost for interactive apps (or a documented profile).
   - Evidence: the probe races in N2; idle cost 12.5 to 15.7% of one core with Kyo defaults against 3.6 to 6.2% with
     the tui4s properties (N5).
6. Terminal-friendly units and documented backend capabilities.
   - Proposal: the CSS units `ch` and `lh` (they map to one terminal cell), and a list of optional capabilities per
     backend (`onKeyUp`, hover, drag and drop, images, `position`).
   - Evidence: N7 (1 px = 1 cell makes browser values too large in a terminal) and N8.

## What carries into PRs 3-5

Source: branch `origin/feat/core-3-terminal` (read with `git show` / `git ls-tree`; not checked out). PR 3 =
core-1-model-view, PR 4 = core-2-engine, PR 5 = core-3-terminal.

Still applies on kyo-ui (the runner should take it over, mostly from PR 5):

| Part of the old core | Files | Why it still applies |
| --- | --- | --- |
| Terminal enter and restore | `internal/TerminalIO.scala`, `internal/Crossterm.scala` (PR 5) | Staged enter with undo, `isatty` check (`NotATerminal` for a pipe), `NativeAccessDenied` message, restore from a JVM shutdown hook (SIGTERM), inline viewport, bracketed paste / mouse / focus modes. The prototype's `Tui.run` has only enter + `Sync.ensure` restore (N10). |
| Input polling | `internal/Session.scala` (input thread, PR 4), `spi/InputPort.scala`, `internal/EventMapping.scala` (PR 5) | A platform input thread with `poll(timeout)` then `read`, joined with a timeout at the release; an exhaustive crossterm mapping (paste, mouse, focus). The prototype polls on a Kyo worker. The runner must map to kyo-ui's `KeyboardEvent` instead of tui4s events. |
| Output capture | `internal/OutputCapture.scala`, `internal/LogSink.scala`, `internal/TuiLog.scala` (PR 5) | Replaces the prototype's `OutputGuard`. |
| Main class | `TuiApp.scala` (PR 5) | Scheduler properties, capture install, exit codes 130/143, error printed after the restore. It must take a `UI < (Async & Scope)` instead of an `App[S]`. |
| Scheduler flags | `spi/SchedulerDefaults.scala` (PR 5) | Measured again here: idle 14% -> 4% (headless), 15.5% -> 5.8% (real process). Keep as is. |
| Test kit | `testkit/Screen.scala` (PR 3), `testkit/ScriptedInput.scala`, `testkit/RecordingControl.scala` (PR 4) | Screen text helpers, scripted input and a recording terminal fit the runner's `Headless`. `TuiTest` (sync fold of a model) and `ViewTree` do not apply. `LiveTest` ideas apply (and its open TestClock finding). |
| Bench | `bench/` probes: `LatencyProbe`, `IdleProbe`, `QuitProbe`, `SoakProbe`, `BurstProbe`, `DashboardProbe`, `Probes`, `ProbeChild`; `bench/BASELINE.md` method (PR 5) | Same metrics; the prototype's `dev/Bench.scala` adds settle, cells and subtrees per frame. `EngineBench` and `PaintBench` measure the old engine and painter: replace them with layout and paint benches of the runner. |
| Real-terminal check | `examples/ptycheck/` (PR 5) | Keep the expect checklist and its extra cases (SIGTERM, pipe, native access denied, resize, paste). Replace `analyze.py` with the prototype's `pty/vt.py` (screen-level checks after a partial repaint). |
| Launchers | `build.sbt` task `examples/launchers` (PR 5) | Start scripts with the JVM flags; still needed (no `sbt run`). |
| Examples | `examples/` (PR 5) | Keep the three apps and their tests, rewritten on kyo-ui (the prototype's `apps/`). `FacadeParity` waits for the facade mirror (prototype B). |
| Trace, CI | `internal/Trace.scala` (PR 4/5), the Linux `terminal` CI job | Keep; the trace lines must carry the runner's `FrameStats` instead of engine steps. |

Replaced by the runner (do not carry):

- PR 3: the own model and view: `App.scala`, `View.scala`, `dsl.scala`, `Styling.scala`, `Keymap.scala`, `Focus.scala`,
  `TextField.scala`, `Event.scala`, `internal/Painter.scala` (jatatui widgets through `Terminal.draw`),
  `testkit/TuiTest.scala` (model fold). kyo-ui gives the UI AST, styles and events; the runner gives layout, paint,
  focus and text editing.
- PR 4: `Source.scala`, `spi/Sources.scala`, `internal/Engine.scala` (583 lines, 25 vars), `internal/Supervisor.scala`,
  `internal/RenderLoop.scala`, `internal/Items.scala`, `internal/Watch.scala`, `internal/Macros.scala`,
  `testkit/ViewTree.scala`. Signals, fibers and the runner's region engine replace sources, the supervisor and the
  engine.
- `CountingBackend` is replaced by the runner's `Canvas` counters (`FrameStats`). `KyoFreeSurfaceTest` /
  `SurfaceCheck` do not apply to the core any more (its API is kyo-ui); they apply to the facades.

## Open questions for the user

1. Settle: keep `Settle.Quiescent(100.millis, confirm = 2)` as the default? It is "very likely" glitch-free, not
   "certain" (N2). The only exact fix is upstream (proposal 1). Do you want me to open the upstream discussion?
2. Overflow: the runner now clips content below the screen. Do you want scrolling (a scroll offset per container
   with `overflow: auto`, and focus moves that scroll), or a virtual list component for long lists?
3. Units: which convention for lengths? Options: 1 px = 1 cell (now); a scale (for example 8 px = 1 cell, so browser
   values work); or the CSS units `ch`/`lh` (needs kyo-ui, proposal 6).
4. Mouse: enable crossterm mouse capture and map clicks, hover and the wheel to kyo-ui's handlers? This breaks text
   selection in the terminal while the app runs.
5. `onKeyUp`: ignore it silently, or log a warning at mount when a UI has `onKeyUp`?
6. Which elements must render something more than a placeholder in the first release: `Img`, SVG / `Chart`,
   `Textarea` (multi-line), `Select` (a drop-down list)?
7. PRs 3-5: may I restructure them as (a) runner core (layout, paint, regions, dispatch, hooks, `Component`),
   (b) terminal and app (the PR 5 parts in the table above, adapted), (c) examples, ptycheck, bench? PR 3 and PR 4
   would then be closed or replaced.
8. The PTY checklist here covers Counter and Todo only. Before a release, do you want the Dashboard, resize,
   SIGTERM, a pipe as stdin and bracketed paste added (as in the old `examples/ptycheck`)?

## Independent verification (2026-10-03)

<!-- claude 2026-10-03: created this section (independent skeptic run; no code was changed) -->

Verifier: an independent skeptic agent. Machine: the same Linux container (4 CPUs, shared with one other agent).
The verifier did NOT change code. Logs of the verifier are in the scratchpad folder
`/tmp/claude-0/-home-user-tui4s/6d712dbd-bef7-57d0-b343-fd9d5dc2fafb/scratchpad/skeptic-runner/` (not kept in `.work/`).

### Progress (written while the work continues)

- 14:49 test run 1: `cd .work/proto && scala-cli test --jvm system kyoui-runner`: PASS, 9 of 9 tests (load average 0.6).
- Code blocks: the five `apps/*.scala` blocks in "User-facing API" are byte-identical to the files (checked with a
  Python diff). The block "How the tests use it (extract)" is NOT a copy of `test/Apps.test.scala` (see below).
- 14:50 test runs 2 and 3: PASS, 9 of 9 tests each (load average 0.5 to 0.6). So 3 of 3 runs pass on this machine.
- 14:51 fresh assembly jar from the CURRENT sources (the jar of the PTY run in the logs was built at 14:38, before the
  last edits at 14:47-14:48). 14:51 PTY run of a COPY of `pty/` (so `run-all.sh` did not delete the original
  `logs/pty-*`): PTY part 16 of 16 PASS, screen part 20 of 20 PASS. Bytes per key: 26 / 26 / 26 (Counter), 284, 243,
  752, 118, 160 (Todo): the same as in the REPORT. First frame 797 to 899 ms. Idle CPU (one run each): 16.39 % (Kyo
  defaults), 5.20 % (tui4s properties). Saved file `[x] buy milk`. `tui4s.log` empty.
- Logs: the pass/fail counts of all 22 `logs/test-1003-*.log` files agree with the progress log (3/3 FAIL before the
  fix under load; 2/3 pass after fix 1; 6/6 and 3/3 pass with `confirm = 2`; 9/9 in `overflow-1` and `final-{1,2,3}`).
  The bench tables (run A and run B) and the idle table agree with `logs/bench-1003-*.log` digit for digit. The glitch
  probe rows agree with `logs/glitch-1003-load2x2.log`.
- kyo-ui and Kyo source (`.work/scratch/kyo-src`; its class layout agrees with the RC7 jar in the coursier cache):
  `Attrs` is `final private[kyo]`; handlers are `Any < Async`; `Foreach.applyTyped` is `private[kyo]`; `UI` is sealed;
  `ReactiveUI`, `ReactiveRegion`, `UIExchange`, `UIEvent`, `EventHandler` are `private[kyo]`; the bound ref is written
  before `onInput`/`onChange` (`autoSet.andThen(invokeWith(...))`); Enter/Space activate button/anchor/checkbox/radio;
  Enter bubbling to a form submits; `map` delegates `observe` to the source loop; `combineLatest.nextWith` races the
  inputs' `next`; the repair interval is 1 s; `set` completes a promise and a parked `IOTask` resumes with
  `promise.onComplete(_ => Scheduler.get.schedule(this))`; `currentWorkers` is a private field; `loadAvg` scans the
  workers one by one; `Worker.load` = `queue.size()` + `currentTask`, and `currentTask` is set only in `runTask`, after
  `queue.poll()`. jatatui `Color.Rgb` is a Java record (value `equals`, own `toString`). `Style.Color.indigo` is
  `Hex("#6366f1")`. All CONFIRMED.
- Files: the changed and new files agree with the list "Files changed in this session" (compared with the baseline
  from `git archive origin/wip/handoff`). Each changed fragment has a `// claude 2026-10-03` mark.
- 14:56-14:57 scratch probe `LateProbe.scala` (in the verifier's scratch folder, compiled with the runner sources; no
  prototype file changed). App: `dev.Glitch.app`. Per event: split = `updates < 4`; late frames by the bench's method
  (`h.drainFrames` right after the event's frame, as in `Bench.timed`); late frames by a second drain 5 ms later.
  Run 1 (500 events): `Hops(1,0)`: 3 splits, bench method 0, after 5 ms 2; `Quiescent(100ms,2)`: 0, 0, 0.
  Run 2 (2000 events): `Hops(1,0)`: 2 splits, bench method 0, after 5 ms 2; `Quiescent(100ms,2)`: 0, 0, 0.
  So the bench's "late frames" column cannot see a split (see the refutation list).
- 14:59 bench re-run (`dev.Bench`, tui4s scheduler properties, load average 0.3 to 0.7): the count columns are
  identical to run B; latencies agree within noise (p50/p99 in us: Counter 595/1243, Todo toggle (loop) 344/760, add
  779/2195, type 179/450, Dashboard tick 472/823); idle 4.0 / 3.8 / 4.0 / 10.1 % of one core. Late frames 0 (see the
  limit of that column below). Log: scratch `bench-tui4sflags.log`.
- 15:00-15:02 suite under pinned CPU contention (2 busy loops on CPUs 2 and 3, test JVM `taskset -c 2,3`,
  `-XX:ActiveProcessorCount=4`): 3 runs, 9 of 9 PASS each. Run 1 recompiled and ended about 4 s after the busy loops
  stopped, so only runs 2 and 3 count as runs under contention (2 of 2 PASS).
- 15:03 scratch probe `LiftProbe.scala` (lift trap, O5): a handler `val` without an expected type, 3 presses of `+`:
  the ref stays 0 and the screen shows `a=0`. The same handler with `: Any < Async`: the ref is 3, the screen shows
  `b=3`. The compile printed no warning (flags of `project.scala`). The Kyo source explains it: `liftPureFunction1`
  makes `a => lift(f(a))`, and `lift` wraps a non-primitive value in `Nested`.
- The upstream PR text (kyo #1649) could not be read: this session has no access to `getkyo/kyo`.

### Result: confirmed

1. Tests are green. 3 of 3 runs without load: 9 of 9 PASS each. 2 of 2 valid runs under pinned CPU contention: 9 of 9
   PASS each. No failure in 5 runs.
2. PTY check is green with a fresh jar from the current sources: 16 of 16 (PTY part) and 20 of 20 (screens). Raw mode
   while running (`-icanon -echo -isig -opost -ixon -icrnl`), `stty -a` after = before, exit codes 0 (q, Ctrl-C, Esc),
   cursor shown, nothing stray, saved file `[x] buy milk`, `tui4s.log` empty, first frame 0.8 to 0.9 s.
3. Partial repaint: one `+` writes 26 bytes (`ESC[2;8H`, the digit, 4 SGR resets). The Todo byte counts are the same
   as in the REPORT.
4. The five `apps/*.scala` code blocks in "User-facing API" are byte-identical to the files. The API table agrees
   with the signatures in `Tui.scala`, `Hooks.scala` and `Component.scala`.
5. All numbers in the bench tables, the idle table and the glitch-probe rows agree with the logs. A re-run of the bench
   on this machine gives the same count columns and latencies within noise.
6. The flakiness story (N1) agrees with the logs: before the fix 3 of 3 runs fail (5, 4 and 2 split frames of 500);
   after fix 1, 1 split frame in 3 runs; with `confirm = 2`, 6 of 6 and 3 of 3 pass. The settle code does what the
   REPORT says (probe first, then drain; `confirm` quiescent observations, one hop apart).
7. N2 (race windows of the scheduler probe) agrees with the Kyo scheduler source.
8. All kyo-ui and Kyo source claims listed above (14:51 to 14:57 items) are CONFIRMED. The lift trap (O5) is
   reproduced on this machine.
9. Old-core facts: all named files exist on `origin/feat/core-3-terminal`; `Engine.scala` has 583 lines and 25 `var`s;
   `SchedulerDefaults` has the three values; `bench/BASELINE.md` B4 is p50 1.56 ms, p99 3.91 ms; the
   `scala.Console` note is in `OutputCapture.scala`. `Dispatch.scala` has 242 lines, `Mount.scala` 181.
10. The list of changed files is complete. Each changed fragment has an authorship mark.

### Result: refuted (all minor; none makes a headline result wrong)

1. The code block "How the tests use it (extract)" is not a copy of `test/Apps.test.scala`. The section says "a
   verbatim copy of the files". The file presses `+ + -` and then checks `Count: 1`; the extract presses one `+`. The
   block is labelled "extract", and it is not app code.
2. The bench column "late frames" cannot see a split frame. `Bench.timed` drains the frame channel at once after the
   event's own frame, and `Headless.press` drops Background frames that come before the next Input frame. The scratch
   probe found 5 split frames (by `updates < 4`) in 2500 events with `Hops(1,0)`; the bench method counted 0 late
   frames; a drain 5 ms later found 4. So "Late frames: 0 in all rows/runs" (Measurements, N4) is not evidence of
   glitch-free frames. The valid evidence is the glitch test, the glitch probe (both check `updates`) and the constant
   "region updates" of the Counter and Todo rows. The Dashboard row has "2 (1-2)"; the verifier did not check if the
   1-update frames are splits or an unchanged gauge text.
3. "The split frames have a settle time of 3 to 18 ms, so the 100 ms limit did not expire": 10 of the 11 logged split
   frames agree, but frame 91 in `test-1003-load2x2-3.log` had a settle of 187.6 ms (the limit expired). Each of the
   three logs has one "settle timed out" line.
4. Run A: "the other columns are the same as in run B" is not exact. Max, settle p50 and layout p50 differ, and some
   cell-write medians differ (Enter adds 2478 against 2468, delete 2460 against 2450, grow 6517 against 6529).
5. Idle CPU of the real process "15.4 % and 15.7 % (two runs); 5.4 % and 6.2 %": the logs hold one run each (15.39 %,
   6.20 %). The second values have no log on this machine (unverified). So "15.5 % -> 5.8 %" in "What carries into
   PRs 3-5" is partly unverified. The verifier's run gave 16.39 % and 5.20 %; the about 3x reduction stays true.
6. "The immutable layout index and the re-layout cost more than the paint": no measurement separates layout from
   paint (`renderNanos` covers layout + paint + flush). Unverified.
7. "kyo-ui's dispatcher already documents TUI semantics": the text is in the client JavaScript of `HtmlRenderer`
   ("TUI/browser parity"), not in the Scala dispatcher. The quote of kyo PR #1649 is unverified (no access).
8. The column list of `baseCss` also has `article` and `aside`. The REPORT omits them. The runner handles them (every
   element that is not in the row list is a column).
9. No test, app or log on this machine uses `useHold`, `useFold`, `useEffect`, `useInterval`, `Component.onMount`,
   `Headless.resize`, focus groups, focus traps or `stopPropagation`. The code exists; the behaviour is unverified.
10. Desktop-only numbers that the REPORT uses as evidence have no log on this machine: "3 to 131 split frames in 2000
    events" for hop counting (O4, proposal 1) and the Kyo timer minimum of about 0.5 ms. On this machine `Hops(1,0)`
    gave 3 splits in 500 and 2 in 2000 events, so "hop counting is not reliable" stays true; the numbers are unverified.

Notes: the PTY logs in `logs/` came from a jar built at 14:38 and from older versions of `checklist.exp`, `check.py`
and `vt.py` (edited at 14:47); the "final check" re-ran only `check.py` on the old session log. The verifier's fresh
run with the current jar and tools gives the same result. The load setup of `glitch-1003-load2x2.log` and of the
`*-load-*` test logs is not written in those logs; the verifier's own contention runs agree with them.
