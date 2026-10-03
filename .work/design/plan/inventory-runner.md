# Inventory: the kyo-ui runner as the real tui4s-core module

<!-- claude 2026-10-03: created this file (planning task "inventory-runner"; no build, no checkout, no commit) -->

Status: DONE (2026-10-03). This is a plan. Nothing was built, run, checked out, committed or pushed.
Scope: the runner prototype (`.work/proto/kyoui-runner/`) becomes `modules/core` (artifact `tui4s-core`).
The facades (zio, cats) and the generator are in other inventories. This file names only what the core must
give them.

Sources read (evidence for each fact is given in the text as `file:line`, a report section, or a git ref):

- `/home/user/tui4s/AGENTS.md`, `.work/DECISIONS.md` (rounds 1-5), `.work/HANDOFF.md`.
- `.work/proto/kyoui-runner/REPORT.md` (all 1027 lines) and all files in `src/`, `test/`, `dev/`, `pty/`.
- `.work/proto/kyoui-integration/REPORT.md` (R1-R5, F1-F3, "Gaps for the real modules").
- `.work/proto/kyoui-facades/REPORT.md` (sections "Design", "Open items", "What carries into the PRs").
- Git refs (read with `git show` / `git ls-tree`, not checked out): `origin/chore/cleanup` (PR 1),
  `origin/feat/core-3-terminal` (PR 5, which holds PRs 3 and 4 below it).
- kyo source in `.work/scratch/kyo-src/` (the runner skeptic confirmed that its class layout agrees with the RC7
  jars; runner REPORT, "Independent verification").
- POMs and jars of Kyo RC7 and jatatui 0.31.0 in the coursier cache (`/root/.cache/coursier/v1/...`).

Abbreviations: "proto" = `.work/proto/kyoui-runner/`. "PR 5" = `origin/feat/core-3-terminal`. "RR" = the runner
REPORT. "IR" = the integration REPORT. "FR" = the facade REPORT. "ASSUMED" = a working assumption that the user
must confirm (section 9).

---

## 0. Summary (facts)

1. The runner main code is 2257 lines in 13 files under `proto/src/tui4s/kyoui/` (wc -l). Largest: `Layout.scala`
   461, `Paint.scala` 273, `Props.scala` 258, `Runner.scala` 253, `Dispatch.scala` 242, `Tui.scala` 237.
2. The runner calls no `private[kyo]` member (RR N9). It works around 4 limits: the `Attrs` type name, the
   `Foreach` cast, the sealed AST (a global weak registry), and reflection on a private field of the Kyo scheduler.
   It also copies about 420 lines of kyo-ui dispatch and region semantics (`Dispatch.scala` 242, `Mount.scala` 181).
3. Glitch-free frames depend on a settle step that reads the Kyo scheduler (`SchedulerProbe.scala`). It is "very
   likely", not "certain" (RR N2). Only an upstream change can make it exact (RR "Upstream proposals" 1).
4. Three runner bugs are open (IR "What must change"): R1 an interrupted run leaks about 1.75 MB; R2 the first key
   can show its result about 1 s late; R3 output capture misses `scala.Console.out`.
5. Two API gaps block a stable facade API (IR R4, R5): the options hold Kyo types; quit is a Kyo `Local`.
6. PR 5 already has the hardened terminal parts that the prototype lacks (RR N10, RR "What carries into PRs 3-5"):
   `TerminalIO`, `Crossterm` (isatty, native-access message), `Session` (platform input thread, shutdown hook),
   `EventMapping`, `OutputCapture`, `LogSink`, `TuiLog`, `TuiApp`, `SchedulerDefaults`, `Trace`. They are about
   1650 main lines and about 1750 test lines on PR 5.
7. kyo-ui adds 8 jars and about 15.2 MB to the runtime classpath of the core (section 3.1). The classpath grows from
   about 20 MB (PR 5) to about 35 MB. The prototype assembly jar is 40 MB (RR "Real PTY run"). Exclusion of
   kyo-http is not safe: the AST type `UI.Href.Absolute` holds a `kyo.HttpUrl` (kyo-ui `UI.scala:499`).
8. The repo build uses `-Wunused:all -Wvalue-discard -Wnonunit-statement -Yexplicit-nulls` (PR 5 `build.sbt`). The
   prototype compiled with `-deprecation -feature -Wunused:imports` only (proto `project.scala:11`). About 71 lines
   of the prototype call Java APIs (grep), so the strict flags need about 150-250 changed lines (estimate).
9. DECISIONS (round 4) says kyo-test for the core. The prototype tests are munit (9 tests, 208 lines).
10. The prototype has 9 headless tests, a glitch probe, a bench, and a PTY kit (`pty/vt.py`, `check.py`,
    `checklist.exp`). All pass on this machine (RR "Independent verification", "Result: confirmed").
11. I propose 36 inventory items (section 7, RC-1 to RC-36) in three PRs: (a) runner core, (b) terminal and app,
    (c) examples, PTY check, bench (ASSUMED A1). 12 decisions are open (section 8).

---

## 1. Public API

### 1.1 The user-facing surface of the prototype

Source: RR "User-facing API" table, verified against the code by the skeptic (RR "Result: confirmed" item 4).

| Name | Where (proto) | Kind | Kyo types in the signature | Change for the real module |
| --- | --- | --- | --- | --- |
| `Tui.run(ui)`, `Tui.run(options)(ui)` | `Tui.scala:72-74` | `Unit < Async` | `UI < (Async & Scope)`, `Frame` | Add an error channel `Abort[TuiError]` (from PR 5 `Options.scala`, `TuiError`). Add a quit value (R5). Real terminal comes from PR 5 parts (RC-20, RC-21). |
| `Tui.quit` | `Tui.scala:96`; `Dispatch.scala:237-242` (`Tui4sQuit`, a Kyo `Local`) | `Unit < Sync` | yes | Keep for Kyo users. Add quit as a value for facades (R5, RC-2). |
| `TuiOptions(ctrlCQuits, autoFocus, settle, pendingTimeout, logFile)` | `Tui.scala:15-26` | case class | `kyo.Duration` in `pendingTimeout` and in `Settle` | Make it Kyo-free (R4, RC-3). Merge the PR 5 fields (`mouse`, `paste`, `focusEvents`, `viewport`, `pollMillis`, `inboxCapacity`, `captureOutput`, `logFile: Option[Path]`, `trace`). |
| `Settle.Quiescent(max, confirm = 2)`, `Settle.Hops(hops, window)` | `Tui.scala:29-42` | enum | `kyo.Duration` | Kyo-free durations (R4). Consider moving it to `tui4s.spi` (a tuning knob, not a daily option). |
| `FrameStats`, `FrameCause` | `Tui.scala:44-67` | case class, enum | no | Keep. Facades return it unchanged (IR "How a user tests an app"). |
| `Hooks.useSignal`, `useHold`, `useFold`, `useEffect`, `useInterval`, `component(key)(body)` | `Hooks.scala:20-58` | effects, `UI` | yes (Kyo API by design) | Keep. Only `useSignal` and `component` have tests (RR skeptic "refuted" item 9). |
| `abstract class Component { type State; initial; view; onMount }` + implicit `componentToChild` | `Component.scala:52-74` | class | yes (Kyo API by design) | Keep. `onMount` has no test (same source). |
| `Headless.start(w, h, options)(ui)`: `key`, `char`, `typeText`, `resize`, `screen`, `lines`, `cell`, `nextFrame`, `drainFrames`, `awaitEnd`, `quit`, `finished`, `backend` | `Tui.scala:128-215` | test kit | `Keyboard` (a kyo-ui data type), Kyo effects | Move to `tui4s.testkit`. Quit and wait before the scope ends (the IR glue workaround for R1). Hide `backend: TestBackend` (a jatatui type in the public API). Return the PR 5 `Screen`. |
| `OutputGuard.during(file)` | `Tui.scala:220-237` | object (public) | yes | Replace with PR 5 `OutputCapture` (internal). Not public. |

Internal (stays internal): `Runner`, `Mount`, `Dispatch`, `Layout`, `Paint` (with `Canvas`), `Props`, `Geometry`,
`Keys`, `World`/`Msg`, `SchedulerProbe`, `ComponentSpec` (`Component.scala:8-37`).

### 1.2 Proposed package layout in `modules/core`

Recommendation (decision D2): the root package `tui4s` holds the user API, because tui4s now IS the kyo-ui
terminal backend (DECISIONS round 5). The old reason for `_root_` imports (an `export dsl.*` cycle, HANDOFF "Facts
the next session must keep") goes away with the old `dsl.scala`.

| Package | Content | Source |
| --- | --- | --- |
| `tui4s` | `Tui` (run, start, quit), `TuiHandle` (if D4 keeps it), `TuiOptions`, `Viewport`, `TuiError`, `TuiApp`, `Hooks`, `Component`, `FrameStats`, `FrameCause` | proto `Tui.scala`, `Hooks.scala`, `Component.scala`; PR 5 `Options.scala`, `TuiApp.scala` |
| `tui4s.testkit` | `Headless` (the run on a test terminal), `Screen`, `diffScreen`, `CellStyle` (if D11) | proto `Tui.scala:128-215`; PR 5 `testkit/Screen.scala` |
| `tui4s.spi` | `SchedulerDefaults` (the facade `TuiApp`s call it), `Settle` (tuning), the run seam for facades if it must be public | PR 5 `spi/SchedulerDefaults.scala` |
| `tui4s.internal` | runner: `Runner`, `Mount`, `Dispatch`, `Layout`, `Paint`, `Props`, `Geometry`, `World`, `SchedulerProbe`, `ComponentSpec`; terminal: `TerminalIO`, `Crossterm`, `Session`, `InputThread`, `EventMapping`, `OutputCapture`, `LogSink`, `TuiLog`, `Trace`, `discard` | proto `internal/`; PR 5 `internal/` |

Option B: keep `tui4s.kyoui`. Good: the name says "kyo-ui backend"; the side-by-side page already shows it. Bad:
one more segment in each import; no second front-end is planned. Recommendation: option A (`tui4s`).

Name check: kyo has no type named `Component`, `Hooks`, `Tui`, `TuiOptions`, `Settle`, `FrameStats`, `Headless`,
`Screen` or `TuiApp` (grep in `.work/scratch/kyo-src`, no match). One trap for docs: `kyo.UI.*` exports `main` (the
`<main>` element, kyo-ui `UI.scala:114`), and `KyoApp` and `TuiApp` have a `main` method.

### 1.3 What must change for the facades

- R4, Kyo-free options (IR "What must change", R4). `TuiOptions.pendingTimeout` and `Settle.*` hold `kyo.Duration`
  (`Tui.scala:23`, `:38`, `:41`). Make every option a Scala or JDK type, so the facades can re-export the same case
  class (no mirror, no mapping). Duration type: decision D3. Recommendation: `java.time.Duration` (ZIO's
  `zio.Duration` is an alias of it; Kyo converts it with `Duration.fromJava`; Cats users convert from
  `FiniteDuration` with `.toJava`).
- R5, quit as a value (IR R5). Today a facade glue must start one extra Kyo fiber per run to bridge a ZIO `Promise`
  or a Cats `Deferred` to `Tui.quit` (IR "The glue itself", `withQuit`). Proposal (decision D4): `Tui.run(options)
  (quit => ui)`, where `quit: Unit < Sync` is the run's own quit action (the runner already has it:
  `Runner.scala:21` `quitAction`). Keep `Tui.quit` (the `Local`) for Kyo users. Keep a handle for tests
  (`Headless`).
- Facade seams: the facade glue lives in `tui4s.interop.zio.ui` / `tui4s.interop.cats.ui` (IR "Folder content").
  These packages are inside `tui4s`, so `private[tui4s]` seams of the core are visible to them. Note:
  `private[tui4s]` is public in the bytecode, so MiMa (sbt-version-policy) checks it like public API (RC-36).
- Test kit for facades: the facades' `TuiHeadless` calls `Headless.start` and returns `FrameStats` (IR "Gaps for
  the real modules"). `Headless.key` takes `kyo.UI.Keyboard`; the facade mirror aliases it (FR O2).
- Errors: a facade needs one error type for "not a terminal" and "native access denied". PR 5 `TuiError` extends
  `Exception`, so ZIO and Cats use it unchanged (PR 5 `Options.scala`, `enum TuiError`).

---

## 2. Hardening list

### 2.1 Runner bugs from the integration (R1-R3)

| Id | Problem | Evidence | Fix options | Recommendation |
| --- | --- | --- | --- | --- |
| R1 | An interrupted run does not stop its fibers. About 1754 KB per run stays on the heap; about 1000 runs fill 1 GB. | IR "What does not work" 1; `logs/leak-probe-2.log`. Code: `Mount.scala:15-16` (`watch` = `Fiber.initUnscoped`), `:128` (Component effects), `:147` (hook bodies), all unscoped; only `unmountAll` (`Runner.scala:37`, `Mount.scala:179-180`) stops them, and an interrupt skips it. | (a) start every watcher and effect with `Fiber.init` under a `Scope` that the run owns; (b) keep the last `World` in an `AtomicRef` and call `unmountAll` in `Sync.ensure`. | (a): the scope closes on success, failure and interrupt, with no extra state. Add a deterministic test (count live watcher fibers after an interrupted run) and keep the IR leak probe as an ignored stress suite. |
| R2 | The FIRST key after the start can show its result about 1 s late (0-4 in 500 starts without load; up to 13 in 300 with load). Steady keys: 0. | IR "What does not work" 2; `logs/split-probe-*.log`. | See below. | See below. |
| R3 | After one `println` before `Tui.run`, `println`, `ZIO.logInfo` and `zio.Console.printLine` from a handler reach the terminal and break the screen. | IR "What does not work" 3; `logs/pty-probe-check.log`. Code: `Tui.scala:220-237` swaps only `System.out`/`System.err`. PR 5 `OutputCapture.scala` doc comment: `scala.Console.out` keeps the stream it saw when it loaded. | (a) carry PR 5 `OutputCapture` (forwarders installed for good by `TuiApp` before user code); (b) also check at start that `scala.Console.out` is a tui4s forwarder, and write a warning to the log and after the restore when it is not. | (a)+(b). A full fix for `Tui.run` inside a foreign `main` (a user's own `KyoApp`, `ZIOAppDefault`) is not possible from outside: the JVM gives no way to change the stream that `scala.Console` keeps per thread. Document "use `TuiApp`, or start nothing that prints before `Tui.run`". |

R2, more analysis (new in this inventory; it is a hypothesis, not proven):

- Kyo documents the window. `Signal.observe` reads `current`, runs `f`, then registers `nextWith`. "A write that
  lands in the narrow window between reading `current` and registering `nextWith` is missed by the immediate wakeup
  and reconciled when the repair timer next fires" (kyo-core `Signal.scala:101-107`). The default repair interval
  is 1 s (`Signal.scala:275`). This agrees with the measured delay of 200-1500 ms (IR R2).
- The runner does not settle before the FIRST frame. `Runner.run` mounts and flushes at once
  (`Runner.scala:34-35`: `walk` then `frame(..., Initial, ...)`). Each later step settles (`Runner.scala:49`). So the
  watchers that the mount forked (`Mount.scala:16`) can still be between `current` and `nextWith` when the first key
  arrives. `Headless.start` returns right after the Initial frame (`Tui.scala:213`), and a test sends a key at once.
  In a real terminal, keys typed during the start wait in the inbox and meet the same window.
- Fix options: (a) settle after the initial mount, before the Initial frame (cheap; uses the existing settle);
  (b) a verify pass: after each settle, re-read `current` of the regions that were mounted in this step or the
  step before, and apply a difference (closes the window also when the probe misses a task, N2); (c) watchers with
  a shorter repair interval through the public `observe(repairInterval)(f)` (`Signal.scala:130`), for example
  250 ms (bounds the delay, but each region wakes 4 times per second when idle: more idle CPU); (d) IR's idea: keep
  a new region in `pending` until its watcher posts a first value (narrower, but `f` runs BEFORE `nextWith`
  registers, so the window stays).
- Recommendation (decision D6): (a) + (b). Check with the IR split probe (500 + 500 starts, with and without load).

### 2.2 Runner findings that need code (RR N1-N10, O1-O7)

| Finding | State | Code needed in the module |
| --- | --- | --- |
| O1 kyo-ui works on the JVM; attrs readable through `el.attrs` | fact | none |
| O2 kyo-ui machinery is `private[kyo]`; the runner copies it | fact | guard test against drift (RC-12); upstream SPI (proposal 3) |
| O3 reactive nodes (`Reactive`, `Foreach`, `Bound.Ref`) | fact | none |
| O4 no synchronous change hook in `Signal` | fact | settle stays (RC-11); upstream proposal 1 |
| O5 lift trap (`Any < Async` handler typed `Any`) | fact | docs page; a test that shows the trap; upstream proposal 2 |
| O6 `Settle.Quiescent` limits: a busy unrelated fiber holds each frame for `max` (100 ms) | open | document; log a counter of "settle timed out" in `FrameStats`/trace (today only a debug print to stderr, `Runner.scala:145-146`, which must go: no stderr while the UI runs) |
| O7 Component state survives a parent re-render; hook components in a `when` lose state | fact | tests exist (`Apps.test.scala` "hooks", "Component class") |
| N1 settle order (probe, then drain) | fixed | keep the fixed code (`Runner.scala:126-153`) |
| N2 probe races in Kyo's worker loop | open, cannot be fixed here | RC-9 (b) verify pass reduces the effect; upstream proposals 1 and 5 |
| N3 fixed sleeps in tests | fixed | keep `awaitEnd` |
| N4, N5 measurements, idle CPU | facts | bench carries them (RC-32); keep `SchedulerDefaults` (RC-25) |
| N6 PTY Counter and Todo pass | fact | PTY kit carries (RC-31) |
| N7 layout gaps | open | section 2.5, RC-13 |
| N8 unsupported elements and attributes | open | section 2.6, RC-14 |
| N9 access to kyo internals | open | section 2.4, RC-12 |
| N10 runner limits found in the old core: input poll on a Kyo worker, no shutdown hook, no isatty check, no native-access message, `OutputGuard` misses `scala.Console` | open | all closed by the PR 5 parts (RC-20 to RC-24) |

Also from the skeptic (RR "Result: refuted"): item 2, the bench column "late frames" cannot see a split (the
bench drains at once and `Headless.press` drops earlier Background frames, `Tui.scala:159-168`). The bench must
count splits by `FrameStats.updates` or drain again after a delay (RC-32).

### 2.3 The settle design and its fragility across Kyo versions

How it works (RR "Runner design", N1, N2; code `Runner.scala:109-156`, `SchedulerProbe.scala:15-41`):

- After an event, the runner applies all queued region values. Then it reads the probe, drains the inbox, and ends
  only after `confirm` (default 2) quiescent probe reads in a row, one scheduler hop apart, each before an empty
  drain. A hop is `Fiber.initUnscoped(Kyo.unit).map(_.get)` (`Runner.scala:110`). The settle gives up after `max`
  (default 100 ms, wall clock with `System.nanoTime`).
- The probe: `totalLoad = round(scheduler.loadAvg() * currentWorkers)`; quiescent when `busyWorkers() <= 1` and
  `totalLoad <= 1` (`SchedulerProbe.scala:25-40`).

What depends on Kyo internals (each one can change in any Kyo release; Kyo is still at RC):

| Dependency | Where we use it | Kyo source | What breaks if Kyo changes it |
| --- | --- | --- | --- |
| Private field `currentWorkers`, read by reflection (`getDeclaredFields.find(_.getName.endsWith("currentWorkers"))`, `setAccessible(true)`) | `SchedulerProbe.scala:17-22, 27` | `kyo-scheduler/kyo/scheduler/Scheduler.scala:117` (`@volatile private var currentWorkers`) | A rename makes `totalLoad` return -1. The probe then SILENTLY falls back to `busyWorkers() <= 1` (`SchedulerProbe.scala:12, 40`): more split frames, no error. A JPMS module descriptor in a later Kyo can make `setAccessible` throw: same silent fallback. |
| `Scheduler.loadAvg()` = mean of `Worker.load` | `SchedulerProbe.scala:27` | `Scheduler.scala:423`; `Worker.scala:178` (`load` = queue size + `currentTask`), `Worker.scala:138, 325, 414-427` (`currentTask` is set only in `runTask`, after `queue.poll()`) | A change in what `load` counts (for example no `currentTask`) changes "quiescent" in silence. |
| `Scheduler.busyWorkers()`, `Scheduler.status()` (debug only) | `SchedulerProbe.scala:34, 40` | public API | Low risk. |
| A write wakes every parked watcher synchronously (`IOTask`: `promise.onComplete(_ => Scheduler.get.schedule(this))`) | the reason why "scheduler idle" means "all consequences posted" (`SchedulerProbe.scala:5-10`) | RR "Independent verification" (confirmed in the Kyo source) | If Kyo defers the enqueue (for example a batched wakeup), the probe can read "idle" before the watchers are queued. |
| `Signal.observe` semantics (`map` delegates to the source loop; `combineLatest` races; 1 s repair) | region watchers (`Mount.scala:16`) | `Signal.scala:96-135, 275` | Changes the number of hops per write and the R2 window. |

Hardening (RC-11):
1. Make the fallback loud: at the first run, check that the reflection works; when not, write one warning to the
   log and count it in the trace.
2. A canary test that fails when `totalLoad()` is -1 (so a Kyo upgrade that renames the field fails CI, not users).
3. Keep the glitch test (500 events, `Apps.test.scala:188`) as the behaviour canary.
4. Remove the stderr debug print (`Runner.scala:145-146`); send "settle timed out" to the trace and the log.
5. Keep `Settle.Hops` as an escape hatch, but in `tui4s.spi`.
6. Test runs: the probe sees the whole JVM. Parallel test suites keep the scheduler busy, so each frame waits up
   to `max`. Core tests must run one suite at a time (RC-19).

### 2.4 Every access to kyo-ui or Kyo internals, and how the runner reaches it

| # | What | How the runner reaches it | Evidence (tui4s side) | Evidence (kyo side) |
| --- | --- | --- | --- | --- |
| K1 | Element attributes (handlers, id, tabIndex, focus rules, stopPropagation) | public member `el.attrs`; the type name `Attrs` cannot be written | `Dispatch.scala:58, 67-71, 92-93, 102, 202-203, 211, 223`; `Runner.scala:171, 236` | `UI.scala:644` (`def attrs: Attrs`, public); `UI.scala:1135` (`final private[kyo] case class Attrs`) |
| K2 | `Foreach` with its own element type | cast `Foreach[?, ?]` to `Foreach[Any, UI]` | `Mount.scala:72-74`; values cast back in `Runner.scala:84, 90` | `UI.scala:1211` (`case class Foreach[A, C]`), `UI.scala:1218` (`private[kyo] def applyTyped`) |
| K3 | A node type for components (the AST is sealed) | a `Reactive` over a fake `Signal.initRaw` signal, registered in a global `WeakHashMap` (the only global mutable object) | `Component.scala:17-33` | `UI.scala:41` (`sealed abstract class UI`), `UI.scala:1206` (`Reactive`); `Signal.scala:451` (`initRaw`, public `inline`) |
| K4 | Component state outside a runner (fallback for other kyo-ui runners) | `Signal.SignalRef.Unsafe.init` with `AllowUnsafe.embrace.danger` | `Component.scala:63-66` | `Signal.scala:725-732` (public, documented as "low-level API for integrations") |
| K5 | Scheduler load | reflection on a private field + public `loadAvg`/`busyWorkers` | `SchedulerProbe.scala:15-40` | `Scheduler.scala:117, 423` |
| K6 | Dispatch semantics (bubbling, stopPropagation, bound ref written before `onInput`, Enter/Space activation, Enter in a form submits, focus group, focus trap) | COPIED, not called (the originals are `private[kyo]`) | `Dispatch.scala` (242 lines) | `internal/ReactiveUI.scala:8, 20`; `internal/EventHandler.scala:15`; `internal/UIEvent.scala:8, 14, 23`; focus rules in the client JS of `internal/HtmlRenderer.scala` (RR skeptic "refuted" item 7) |
| K7 | Region walk (which nodes are reactive, keyed reconcile) | COPIED | `Mount.scala` (181 lines) | `internal/ReactiveUI.scala` (`walkStatic`, `subscribe`), `internal/ReactiveRegion.scala:9`, `internal/UIExchange.scala:15` |
| K8 | Implicit child conversion for `Component` | public `HtmlChildVal.lift` | `Component.scala:73` | `UI.scala:966-968` (public `implicit def lift`) |
| K9 | Style properties, AST element classes | public pattern matches on `kyo.Style.Prop` cases and `kyo.UI.Ast.*` classes, with wildcard defaults | `Props.scala:142-253`; `Layout.scala:110-158`; `Paint.scala:224-233` | `Style.scala:1125` (`enum Prop`, public) |

Guard (RC-12): K9 and K1 have wildcard cases, so a kyo-ui upgrade that adds an element, an attribute or a style
property compiles and is ignored in silence. Proposal: a test that reads the kyo-ui TASTy with tasty-query (already
a test dependency of the core on PR 5, `modules/core/build.sbt`) and compares the list of `UI.Ast` element classes,
`Attrs` fields and `Style.Prop` cases with a checked-in list of "handled" and "ignored on purpose" names. A new name
fails CI. The same test pins K2 (`applyTyped` still private: the cast is still needed) and K5 (the field name).

### 2.5 Layout gaps against kyo-ui's HTML flexbox model (RR N7; IR "What does not work" 4)

| Gap | Effect | First-release need (my view; decision D9) |
| --- | --- | --- |
| No scrolling: no `overflow`, scroll offsets, scrollbars, `onScroll`; focus on a clipped element does not scroll | content below the screen cannot be reached | YES for focus-follows-scroll in a column with `overflow` (a Todo list longer than the screen is common). Size L (300-500 lines). |
| No virtualisation: every item of a long list is laid out (5.6 ms per add at 200 items) | slow long lists | later (after scrolling) |
| Row items shrink in proportion; no min-content width (`min-width: auto`) | text cut in narrow rows | yes, small (with the overflow fix pattern of `Layout.scala:261`) |
| Not applied: `flexWrap`, `flexBasis`, `display` modes (only `hidden`), `position`, `top/right/bottom/left`, `zIndex`, `translate` | no overlays, popovers, modals | later; modals need `position` |
| Units: 1 px = 1 em = 1 cell; `%`; `vh`; `calc` and `auto` ignored | browser values look too big in a terminal | decide the convention (runner open question 3) |
| Text: no `fontSize`, `lineHeight`, `textOverflow`, `whiteSpace`, `textWrap`; wrap only in a column | no ellipsis | ellipsis is cheap: yes |
| Borders: 1 cell max; one colour; `borderCollapse` ignored; tables one row per cell, no `colspan` | simple tables only | later |
| Colours: 24-bit always (no 256/16 fallback); `opacity < 0.7` = DIM; filters, gradients, shadows ignored | wrong colours on 256-colour terminals | 256-colour fallback when `COLORTERM` is not `truecolor`: yes, small |
| Pseudo-classes: `hover`, `active` not applied | none without a mouse | with mouse (D9) |
| A button in a column takes the full width (IR item 4) | looks odd | check `HtmlRenderer.baseCss` first (is it the browser behaviour too?) |

### 2.6 Unsupported elements and attributes (RR N8)

- Impossible on a plain terminal: `onKeyUp` (no key-release events, except the kitty protocol), `Img` (shown as
  `[alt]`, `Paint.scala:224-225`), `Iframe`, SVG and `Chart` (shown as `[chart]`, `Paint.scala:226-227`),
  `PageHead`, `DataIsland`/`jsonLd`, `RawHtml` (tags removed, `Layout.scala:110`), CSS classes, `Stylesheet`,
  `aria*`, `role`, `data*`, `jsProps`, enter/leave transitions.
- Possible, not done: mouse (click, hover, wheel), drag and drop, multi-line `Textarea` (edited as one line,
  `Layout.scala:148`), `NumberInput` steps, pickers (`DateInput`, `TimeInput`, `ColorInput`, `FileInput`), a
  drop-down list for `Select`/`Dropdown` (they cycle, `Dispatch.scala:172-193`), `href` navigation,
  `focusRestore`, bracketed paste (a paste arrives as single keys, so a pasted newline submits a form), terminal
  focus events.
- Proposal (RC-14): (1) at mount, write ONE log line per unsupported attribute kind that the UI uses (runner open
  question 5); (2) bracketed paste: PR 5 already enables it (`TerminalIO.enterCommands`) and maps `Paste` events
  (`EventMapping.scala`); the runner inserts the text into the focused input as one edit with no submit; (3) mouse
  and the rest wait for decision D9.

---

## 3. Conversion: scala-cli project to sbt module

### 3.1 Dependencies

Proto `project.scala:2-8`: `kyo-ui`, `kyo-core`, `jatatui-core`, `jatatui-widgets`, `jatatui-crossterm`,
`crossterm` (all RC7 / 0.31.0), munit (test). PR 5 `modules/core/build.sbt`: `kyo-core`, `jatatui-core`,
`jatatui-widgets`, `jatatui-crossterm`, `kyo-test-runner` (test), `tasty-query` 1.9.0 (test).

Proposed `modules/core/build.sbt`:
- `io.getkyo %% kyo-core % Versions.kyo`, `io.getkyo %% kyo-ui % Versions.kyo` (kyo-ui must match kyo; no new
  entry in `project/Versions.scala`).
- `jatatui-core`, `jatatui-crossterm` (it brings `crossterm` and `jatatui-core`, POM checked).
- DROP `jatatui-widgets`: the runner imports nothing from it (grep of `proto/src`: only `jatatui.core.*`,
  `jatatui.crossterm.CrosstermBackend`, `tui.crossterm.*`).
- Test: `kyo-test-runner`, `tasty-query` (for RC-12).
- Keep from PR 5: `Test / fork := true`, the JVM flags, the `-Wconf` that makes a non-exhaustive `EventMapping`
  match an error.

Size (jar sizes from the coursier cache):

| Group | Jars | Size |
| --- | --- | --- |
| kyo-core closure (already on PR 5) | kyo-core 3.25, kyo-prelude 1.79, kyo-data 1.32, kyo-kernel 0.98, kyo-scheduler 0.37, kyo-stats-registry 0.13; pprint/fansi/sourcecode about 0.38 | about 8.2 MB |
| ADDED by kyo-ui | kyo-ui 5.69, kyo-http 2.79, kyo-schema 2.39, kyo-net 1.76, kyo-system 1.68, kyo-ffi 0.34, kyo-schema-json 0.33, kyo-config 0.20 | about 15.2 MB |
| jatatui (without widgets) | crossterm 1.61 (native libraries inside), jatatui-core 0.19, jatatui-crossterm 0.02 | about 1.8 MB |
| Scala library | scala-library 3.9.0 | 9.8 MB |
| Total runtime classpath of tui4s-core | | about 35 MB (PR 5: about 20 MB). Proto assembly jar: 40 MB (RR "Real PTY run"). |

Is it acceptable for a terminal library? Facts: kyo-ui IS the API of tui4s now (DECISIONS round 5), so kyo-ui is not
optional. kyo-http and kyo-schema are not optional either: `UI.Href.Absolute(url: HttpUrl)` (kyo-ui `UI.scala:499,
510`) and `Modifiers derives Schema` (FR O2) put their types into the AST and the event types. An sbt `exclude`
would compile and then risk `NoClassDefFoundError` at run time. Options (decision D7): (1) accept (recommended);
check with `-verbose:class` that a terminal run loads no kyo-http/kyo-net class, and see if the first frame (0.8-0.9
s, RR "Real PTY run") has a cost from them; (2) exclude (not safe, see above); (3) ask upstream to split kyo-ui into
an AST module and a runner module (add it to the upstream draft, RC-34).

Binary compatibility: kyo-ui and kyo are RCs. Each Kyo bump changes the core's bytecode deps, and
`project/Versions.scala` marks all modules unreleased (AGENTS.md "Dependencies and versions"). Core stays
`Compatibility.None` (PR 5 `modules/core/compat.sbt`) until a first release.

### 3.2 Scalac flags of the build (PR 5 `build.sbt`)

`-deprecation -feature -Wunused:all -Wvalue-discard -Wnonunit-statement -Yexplicit-nulls` and a `-Wconf` that hides
Kyo's inlined `@nowarn` noise. Also scalafmt (`maxColumn = 80`, `indent.significant = 4`; the prototype lines are up
to about 150 columns) and scalafix `OrganizeImports` (`removeUnused = true`).

Estimate (not compiled; numbers from grep):
- `-Yexplicit-nulls`: 71 prototype lines call Java APIs (`Paint.scala` 26, `Tui.scala` 20, `Props.scala` 13,
  `Layout.scala` 6, `Runner.scala` 3, `SchedulerProbe.scala` 2, `Component.scala` 1). The PR 5 code that compiles
  under the same flags uses `.nn` on Java results (for example `TerminalIO.scala` `Runtime.getRuntime.nn`,
  `SchedulerDefaults.scala` `toUpperCase.nn`). Expect 30-70 `.nn` or `| Null` edits. `SchedulerProbe.scala:35`
  already filters nulls.
- `-Wnonunit-statement` / `-Wvalue-discard`: jatatui setters return values (fluent API); `Paint.scala` and `Canvas`
  use them as statements. PR 5 has `internal/package.scala` `discard` for this. Expect 20-60 sites, most in
  `Paint.scala`.
- `-Wunused:all`: the prototype checked only imports. Expect 10-30 unused params, locals or privates.
- scalafmt: automatic, but every line of the prototype changes (review noise). Format in a separate commit.
- Total: about 150-250 changed lines, mechanical. Size M. Risk: low, but do it FIRST in PR (a), before behaviour
  changes, so that reviews can tell format from logic.

### 3.3 Test framework

DECISIONS round 4: "kyo-test for core, munit elsewhere". PR 5 core tests use kyo-test (`TuiSuite extends
Test[Any]`, `"name" in { ... }`, effects returned directly, `config.sequential` in `LiveKitTest`). The prototype
suite is munit with `KyoApp.Unsafe.runAndBlock` wrappers (`Apps.test.scala:8-15`).

Conversion: drop the `run { ... }` wrapper, return the Kyo computation, `assertEquals` -> `assert(a == b)`,
`munitTimeout` -> `.timeout(...)`. About 210 lines; size S. Important: run core suites one at a time
(`config.sequential` and `Test / parallelExecution := false`), because the settle reads the process-wide scheduler
(section 2.3, point 6). Risk: `Headless.awaitEnd` sleeps with `Async.sleep(5.millis)` (`Tui.scala:141-152`) and
`pendingTimeout` uses `Async.timeout` (`Runner.scala:121`): under `Clock.withTimeControl` they wait for virtual
time. The old core had the same trap (HANDOFF "Pending ZIO findings": settle hangs on TestClock). Document: do not
use virtual time with `Headless`, or give it a real-clock option.

Examples keep munit (PR 5 `build.sbt`, project `examples`), as most users do. The decision stands; this inventory
does not reopen it (D8 asks only for confirmation).

### 3.4 What of PR 5 merges in, and how each part attaches to the runner

Line counts are from `git show origin/feat/core-3-terminal:<path> | wc -l`.

| PR 5 part | Lines | Replaces in the proto | How it attaches | Change size |
| --- | --- | --- | --- | --- |
| `internal/TerminalIO.scala` (staged `enter` with undo, `leave` that runs every step, shutdown `Hooks`, enter/leave command lists for alt screen, paste, mouse, focus) | 188 | `Tui.scala:74-107` (`enter`, `restore`, `Sync.ensure`) | `enter(opts)` returns a jatatui `Backend`; the runner's `Canvas(backend)` draws on it (`Paint.scala:21`). `leave` is the last step of the release. | M. New: `Viewport.Inline(h)` needs a row offset in `Canvas` (PR 5 used jatatui `Terminal` for it, `Session.scala`; the runner draws on the `Backend` directly). |
| `internal/Crossterm.scala` (`Crossterm` trait, `NativeCrossterm`, `Tty.isatty`, `loadError` -> `NativeAccessDenied`, `CrosstermInput`) | 155 | `new CrosstermJni()` (`Tui.scala:75`) | Unchanged except `CrosstermInput` maps with the new `EventMapping`. | S |
| `internal/Session.scala` (`TerminalControl`, `HeadlessControl`, `Session` release, `InputThread` on a platform daemon thread, `closeInbox`) | 249 | the input fiber that polls on a Kyo worker (`Tui.scala:109-124`, RR N10) | The input thread puts batches of runner `Msg` into the runner inbox. `Session` holds the `Canvas` (not a jatatui `Terminal[CountingBackend]`). `closeInbox` loses the `Probe` case (old engine). | M |
| `internal/EventMapping.scala` (exhaustive match over crossterm's sealed Java types; `-Wconf` error) | 103 | `internal/Keys.scala` (58 lines, wildcard `case _ => None`) | Output: kyo-ui `KeyboardEvent` (`Keyboard`, `Modifiers`), `Msg.Resize`, a new `Msg.Paste`; mouse and focus events dropped unless options enable them. Keep the BackTab -> Shift-Tab and Ctrl-letter rules of `Keys.scala:32-37`. | S-M |
| `internal/OutputCapture.scala` (forwarders; `install()` for good; `MaxLine`) | 179 | `OutputGuard` (`Tui.scala:220-237`) | Started by `TerminalIO.enter` when `captureOutput`; `TuiApp` calls `install()` in its constructor. Fixes R3 for `TuiApp` users. | S |
| `internal/LogSink.scala`, `internal/TuiLog.scala` (the run's Kyo logger, bound with `Log.let`) | 134 + 90 | `logFile: String` option | `Log.let` around the run, so the handler errors that `Dispatch.safe` logs (`Dispatch.scala:23-28`) go to the file or to the ring that is printed after the restore. | S |
| `TuiApp.scala` (scheduler defaults, capture install, shutdown hook, exit codes 1/130/143, error after restore) | 136 | `object ... extends KyoApp: run(Tui.run(app))` in each main | New shape: `abstract class TuiApp { def ui: UI < (Async & Scope); def options: TuiOptions; protected def args; protected def onExit }`. Keep `block` and `report`. Ctrl-C exit code: D10. | M |
| `spi/SchedulerDefaults.scala` | 63 | nothing (proto measured the same values, RR N5) | Unchanged. | none |
| `internal/Trace.scala` (`Tracer`, `MemoryTrace`, `TraceWriter`) | 354 | nothing | Keep the writers. The per-batch engine JSON goes; write one JSON line per frame from `FrameStats` (seq, cause, updates, repainted, flushed, handle/settle/render ns, focus, subtrees) plus "settle timed out". | M |
| `testkit/Screen.scala` (PR 3) | 165 | `Headless.lines`/`screen`/`cell` | `Headless.screen` returns `Screen`. `Screen.styleAt` returns `tui4s.Style` from the dropped `Styling.scala`: replace (D11). | S |
| `testkit/ScriptedInput.scala` (PR 4) | 59 | nothing | Only for tests of the input-thread path (`InputPort` -> `EventMapping` -> inbox). `Headless` writes to the inbox directly and does not need it. | S |
| `testkit/RecordingControl.scala` (PR 4) | 88 | nothing | Tests of the release order (enter, input-started, input-stopped, input-joined, leave). | S |
| `spi/InputPort.scala` | 18 | nothing | Keep (the input thread reads it); `Event` -> runner `Msg`. | S |
| Tests: `TerminalIOTest` 507, `TuiAppTest` 280, `EventMappingTest` 187, `OutputCaptureTest` 187, `TraceTest` 169, `SessionTest` 152, `FakeCrossterm` 141, `NativeTerminalTest` 88, `SchedulerDefaultsTest` 72, `GuardTest` 58, `ScreenTest` 55 | 1896 | none | Adapt to `Msg` and the runner. Most assert terminal steps, not the engine. | L |
| Bench probes `LatencyProbe` 60, `IdleProbe` 47, `QuitProbe` 52, `SoakProbe` 110, `BurstProbe` 70, `DashboardProbe` 31, `Probes` 215, `ProbeChild` 84; `bench/BASELINE.md` 89 | 758 | `dev/Bench.scala` (231) | App side re-pointed to kyo-ui apps and `Headless`; `EngineBench` 82 and `PaintBench` 71 measure the old engine: replace with JMH benches of `Layout` and `Paint`. | M-L |
| `examples/ptycheck/` (`checklist.exp` 149, `analyze.py` 133, `run.sh` 16) | 298 | `pty/` (proto) | Merge: keep PR 5's extra cases (SIGTERM -> 143, Ctrl-C, pipe, native access denied, WINCH resize, bracketed paste, Dashboard); replace `analyze.py` with the proto's `vt.py` + `check.py` (screen checks after a partial repaint). | M |
| `build.sbt` task `examples/launchers`, CI job `terminal` | - | `pty/run.sh` | Keep. | none |

Do not carry (replaced by the runner; RR "Replaced by the runner"): PR 3 `App`, `View`, `dsl`, `Styling`, `Keymap`,
`Focus`, `TextField`, `Event`, `internal/Painter`, `testkit/TuiTest`; PR 4 `Source`, `spi/Sources`,
`internal/Engine` (583 lines, 25 vars), `Supervisor`, `RenderLoop`, `Items`, `Watch`, `Macros`, `testkit/ViewTree`,
`testkit/LiveTest`; PR 5 `internal/CountingBackend` (the `Canvas` counters replace it), `internal/TuiRun`,
`spi/Headless` (the runner `Headless` replaces it), `src/surface/SurfaceCheck` and `KyoFreeSurfaceTest` (they move
to the facades: the core API is Kyo by design), `project/FacadeParity.scala` and `examples/src/parity` (they wait
for the facade mirror).

---

## 4. Tests to carry

| What | Source | Target | Notes |
| --- | --- | --- | --- |
| 9 headless tests: counter; two counters; hooks; Component class; todo; overflow; dashboard; idle; glitch-free (500 events) | `proto/test/Apps.test.scala` (208 lines) | `modules/core/src/test/scala/tui4s/` (kyo-test), fixtures from `proto/apps/*` copied to `src/test/.../fixtures/` | The fixtures duplicate the examples on purpose (PR 5 did the same: `src/test/scala/tui4s/fixtures/`), so core tests do not depend on the examples project. |
| New regression tests | none (new) | same | R1 (no live watcher after an interrupted run), R2 (first key right after start, 200 starts, deterministic check of `updates`), unverified paths: `useHold`, `useFold`, `useEffect`, `useInterval`, `Component.onMount`, `Headless.resize`, focus group, focus trap, `stopPropagation` (RR skeptic "refuted" item 9), settle canary (`totalLoad >= 0`), kyo-ui surface guard (RC-12). |
| Glitch probe (settle, confirm, events as args) | `proto/dev/Glitch.scala` (59) | `bench/` probe `GlitchProbe` | The test version stays in the suite. |
| Split probe and leak probe (plain Kyo parts) | `kyoui-integration/stress/SplitProbe.test.scala`, `LeakProbe.test.scala` | core test sources, ignored unless a system property is set (as in IR) | Facade parts go to the facade modules. |
| PTY kit | `proto/pty/vt.py` (246), `check.py` (80), `checklist.exp` (180), `run.sh`, `run-all.sh`, `cpu.sh` | `examples/ptycheck/` | Merged with PR 5's cases (section 3.4). Add Dashboard, resize, SIGTERM, pipe, paste (runner open question 8). Later: the ZIO and Cats rows (IR "Gaps"). |
| Bench | `proto/dev/Bench.scala` (231), PR 5 `bench/` | `bench/` (sbt-jmh, not published) | Fix the "late frames" column (RR skeptic "refuted" 2). Keep the method of RR "Measurements" (100x30, warm-up, 1000 events, p50/p99, cells flushed, settle share). New `BASELINE.md` section. |
| Smoke mains | `proto/dev/Smoke3.scala`, `Smoke4.scala` | drop | dev only |

---

## 5. Upstream proposals, ranked by value for tui4s

Source: RR "Upstream proposals for kyo-ui" 1-6. Rank = value for tui4s (correctness first, then code we can
delete, then user safety). The draft issue (ASSUMED A2) leads with rank 1 and offers rank 3 as the small fallback.

| Rank | Proposal (RR #) | Value for tui4s | tui4s code it deletes | Upstream effort (my view) |
| --- | --- | --- | --- | --- |
| 1 | Synchronous change hook or transaction API on `Signal` (RR 1) | Exact glitch-free frames (closes N2); likely closes R2 (no missed-wakeup window); halves small-frame latency (settle is 328 of 626 us for a Counter key, RR "Measurements"); no dependency on scheduler internals. | `SchedulerProbe.scala` (41), the settle loop and `hop` (`Runner.scala:109-156`, about 48), `Settle` (`Tui.scala:29-42`) and its option; possibly the watcher fibers (`Mount.watch`) and with them the R1 class of leak and the 1 s repair wakeups. About 100-150 lines and the most fragile part. | Large (a core `Signal` design change). |
| 2 | Public runner SPI: region walk, dispatcher, read-only `Attrs`, `Foreach.applyTyped` (RR 3) | No drift: kyo-ui changes its rules, tui4s follows for free. | Most of `Dispatch.scala` (242) and part of `Mount.scala` (181): about 300-400 lines; the cast `Mount.scala:74`; the guard test RC-12 shrinks. | Medium (visibility and a stable `spi` package). |
| 3 | kyo-scheduler: public, consistent in-flight count or "idle except the caller" query; an idle profile for interactive apps (RR 5) | Removes the reflection (K5) and the non-atomic scan (N2 root cause 2). Lower idle CPU without our system properties. | `SchedulerProbe.scala:15-27` (reflection); `spi/SchedulerDefaults.scala` (63) and its test if Kyo ships a profile. | Small-medium. Moot for the settle if rank 1 lands. |
| 4 | Handlers typed `Unit < Async`, or a check that rejects a pure `Any` (RR 2) | User safety: stops the silent lift trap (O5) for Kyo users. | No core code. The facades' guard overloads may get simpler (FR F1). Docs lose a warning. | Breaking upstream API change. |
| 5 | An AST extension node, for example `UI.Custom(tag, payload, fallback)` (RR 4) | Removes the only global mutable object. | `ComponentSpec.registry` and the fake signal (`Component.scala:17-33`), about 20 lines. | Small. |
| 6 | Terminal units `ch`/`lh`, and a per-backend capability list (RR 6) | Same UI value looks right in a browser and a terminal; clear docs. | Nothing; changes the unit rules in `Props.scala`/`Layout.scala`. | Small. |
| 7 (new) | Split kyo-ui into an AST module and runner modules (kyo-http, kyo-net only in the runners) | About 15 MB less on the classpath of every tui4s user (section 3.1). | Nothing (a dependency change). | Medium (module split; `Href.Absolute` holds `HttpUrl`). |

The kyo PR #1649 text was not readable from this session (RR skeptic "refuted" item 7). The draft must not claim
what that PR says.

---

## 6. Proposed PR split (ASSUMED A1) and order

Non-destructive moves: new branches from `origin/chore/cleanup` (PR 1). No force-push and no history rewrite on
`feat/core-1-model-view`, `feat/core-2-engine`, `feat/core-3-terminal`, `feat/zio`, `feat/cats`: they stay as
reference. Closing PRs 3, 4 and 5 is a GitHub action for the user (or for Claude after the user says so).

| PR | Branch (base) | Content (items) | Gate |
| --- | --- | --- | --- |
| (a) runner core | `feat/core-runner` (`chore/cleanup`) | RC-1 to RC-19, RC-29, RC-30 (core parts), RC-36. No real terminal yet: everything is tested on `Headless`. | `sbt check` green |
| (b) terminal and app | `feat/core-terminal` (`feat/core-runner`) | RC-20 to RC-28 (and RC-10, the R3 fix) | `sbt check` green + Linux `terminal` CI job with the merged PTY kit for the core apps |
| (c) examples, PTY check, bench | `feat/core-examples` (`feat/core-terminal`) | RC-31 to RC-33 | `sbt check` green + `terminal` job |

Work order inside (a): RC-16 build, RC-18 flags and format (separate commits, no logic), RC-19 tests, then RC-1 to
RC-7 API, then RC-8/RC-9 fixes with their tests, then RC-11/RC-12 guards. Parallel work (user limit: at most 2
agents that build): (a) blocks (b) and (c). The carried PR 5 tests of (b) and the PTY merge of (c) can be prepared
while (a) is in review, but each build waits for its base.

---

## 7. Inventory items

Columns: action = keep / adapt / drop / new / fix / decide. Size: S < 150 lines, M 150-500, L > 500 (main + test
lines touched).

| Id | What | Source | Action | Target | Size | Risk | Depends on |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RC-1 | Package layout: user API in `tui4s`, runner in `tui4s.internal`, test kit in `tui4s.testkit`, tuning in `tui4s.spi` | proto `src/tui4s/kyoui/**`; section 1.2 | decide | `modules/core/src/main/scala/tui4s/{.,internal,testkit,spi}` | S (moves) | Low. Side-by-side page and docs show `tui4s.kyoui`. | D2 |
| RC-2 | `Tui` entry: `run`, `start`/handle, `quit` (Local) + quit as a value (R5), error channel `Abort[TuiError]` | proto `Tui.scala:70-125`; PR 5 `Tui.scala`, `TuiHandle`; IR R5 | adapt | `tui4s/Tui.scala` | M (~200) | Medium: the facade glue depends on this shape. | RC-1, RC-3, D4 |
| RC-3 | Kyo-free `TuiOptions` (R4), merged with the PR 5 option set; `Viewport`; `TuiError` | proto `Tui.scala:15-42`; PR 5 `Options.scala` | adapt | `tui4s/TuiOptions.scala` | S (~140) | Low. | D3 |
| RC-4 | `Hooks` (`useSignal`, `useHold`, `useFold`, `useEffect`, `useInterval`, `component`) | proto `Hooks.scala` (59) | keep | `tui4s/Hooks.scala` | S (~60 + ~150 test) | Medium: four hooks have no test (RR skeptic "refuted" 9). | RC-19 |
| RC-5 | `Component` class and `ComponentSpec` registry | proto `Component.scala` (74) | keep | `tui4s/Component.scala`, `tui4s/internal/ComponentSpec.scala` | S (~80) | Low; global weak map (K3). | RC-1 |
| RC-6 | `FrameStats`, `FrameCause` public; `Settle` to `tui4s.spi` | proto `Tui.scala:29-67` | adapt | `tui4s/FrameStats.scala`, `tui4s/spi/Settle.scala` | S (~60) | Low. | RC-3 |
| RC-7 | Test kit `Headless`: quit and wait before the scope ends; `awaitEnd`; returns `Screen`; no public `TestBackend` | proto `Tui.scala:128-215`; IR "glue change" 15:49; PR 3 `testkit/Screen.scala` | adapt | `tui4s/testkit/Headless.scala`, `Screen.scala` | M (~350) | Medium: virtual-time trap (section 3.3). | RC-2, RC-28, D11 |
| RC-8 | R1: stop watchers, component effects and hook bodies on interrupt (run-owned `Scope`) | IR R1; `Mount.scala:15-16, 128, 147`; `Runner.scala:30-39` | fix | `tui4s/internal/Mount.scala`, `Runner.scala` | S (~50 + ~100 test) | Medium: scope order at the end of a run (unmount before the canvas goes). | RC-19 |
| RC-9 | R2: settle after the initial mount; verify pass for young regions | IR R2; kyo `Signal.scala:101-107, 130, 275`; `Runner.scala:34-35, 49` | fix | `tui4s/internal/Runner.scala` | S (~60 + stress probe) | Medium: the cause is a hypothesis; prove it with the split probe before and after. | RC-8, D6 |
| RC-10 | R3: `OutputCapture` forwarders + `TuiApp.install` + start-time warning when `scala.Console.out` is not ours | IR R3; PR 5 `OutputCapture.scala`; `Tui.scala:220-237` | fix | `tui4s/internal/OutputCapture.scala`, `tui4s/TuiApp.scala` | S (~40 + test) | Medium: no full fix for a foreign `main` (documented limit). | RC-23, RC-24 |
| RC-11 | Settle hardening: loud reflection fallback, canary test, no stderr debug print, timeouts to trace/log, `Settle.Hops` in spi, sequential test runs | section 2.3; `SchedulerProbe.scala`; `Runner.scala:109-156` | adapt | `tui4s/internal/SchedulerProbe.scala`, `Runner.scala` | S (~80) | High (inherent): Kyo internals can change in any RC. | RC-6, D5 |
| RC-12 | kyo-ui surface guard: tasty-query test of `UI.Ast` classes, `Attrs` fields, `Style.Prop` cases against a checked-in handled/ignored list; pins K2 and K5 | section 2.4 | new | `modules/core/src/test/scala/tui4s/KyoUiSurfaceTest.scala` + `src/test/resources/kyo-ui-surface.txt` | M (~150) | Low; tasty-query must read Scala 3.9 TASTy (PR 5 uses 1.9.0 already). | RC-16 |
| RC-13 | Layout gaps for the first release: scroll with focus-follows in `overflow` columns, row min-content width, ellipsis, 256-colour fallback, unit convention | RR N7; section 2.5 | decide | `tui4s/internal/Layout.scala`, `Paint.scala`, `Props.scala` | L (scroll 300-500; rest ~150) | Medium: layout is the largest file (461) and has the fewest tests. | D9 |
| RC-14 | Unsupported elements/attributes: one log line per kind at mount; bracketed paste as one edit; mouse later | RR N8; section 2.6 | new | `tui4s/internal/Mount.scala`, `Dispatch.scala` | M (~150) | Low. | RC-22, D9 |
| RC-15 | Tests for unverified paths: focus group, focus trap, `stopPropagation`, resize, `onMount`, the four hooks | RR skeptic "refuted" 9 | new | `modules/core/src/test/scala/tui4s/` | M (~250) | Low; may find bugs. | RC-19 |
| RC-16 | `modules/core/build.sbt`: add kyo-ui; drop jatatui-widgets; test deps kyo-test-runner and tasty-query; fork and JVM flags; EventMapping `-Wconf` | proto `project.scala`; PR 5 `modules/core/build.sbt` | adapt | `modules/core/build.sbt` | S (~40) | Low. | none |
| RC-17 | Accept the kyo-ui transitive deps (about +15.2 MB); check loaded classes with `-verbose:class`; add the module-split request upstream | section 3.1 | decide | `modules/core/build.sbt`, docs | S | Low now; the size is a user-visible cost. | D7 |
| RC-18 | Strict flags (`-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`, `-Yexplicit-nulls`), scalafmt (80 columns), scalafix; format in its own commit | PR 5 `build.sbt`, `.scalafmt.conf`, `.scalafix.conf` | adapt | all core sources | M (~150-250 changed lines) | Low; mechanical. | RC-16 |
| RC-19 | Test framework: munit -> kyo-test; suites run one at a time; fixtures copied from `apps/` | DECISIONS round 4; `proto/test/Apps.test.scala` | adapt | `modules/core/src/test/scala/tui4s/` | S-M (~210 + ~390 fixtures) | Medium: process-wide settle makes parallel suites slow (section 2.3). | RC-16, D8 |
| RC-20 | `TerminalIO` + `Crossterm` (`NativeCrossterm`, `Tty.isatty`, `loadError`, shutdown `Hooks`): enter/restore of the real terminal; `Canvas` row offset for `Viewport.Inline` | PR 5 `internal/TerminalIO.scala` (188), `internal/Crossterm.scala` (155) | adapt | `tui4s/internal/TerminalIO.scala`, `Crossterm.scala`, `Paint.scala` | M (~350 + ~60 new) | Medium: inline viewport is new code in `Canvas`. | RC-2, PR (a) |
| RC-21 | `Session` + `InputThread` + `TerminalControl`/`HeadlessControl`: platform input thread, idempotent release, inbox of runner `Msg` | PR 5 `internal/Session.scala` (249), `spi/InputPort.scala` (18); proto `Tui.scala:109-124` | adapt | `tui4s/internal/Session.scala` | M (~270) | Medium: release order with the run-owned scope of RC-8. | RC-8, RC-20 |
| RC-22 | `EventMapping` to kyo-ui `KeyboardEvent`, `Msg.Resize`, `Msg.Paste`; exhaustive; replaces `Keys.scala` | PR 5 `internal/EventMapping.scala` (103); proto `internal/Keys.scala` (58) | adapt | `tui4s/internal/EventMapping.scala` | S-M (~130) | Low. | RC-21 |
| RC-23 | `OutputCapture`, `LogSink`, `TuiLog` (`Log.let` around the run); replaces `OutputGuard` | PR 5 `internal/OutputCapture.scala` (179), `LogSink.scala` (134), `TuiLog.scala` (90) | keep | `tui4s/internal/` | S (~400 carried, ~30 changed) | Low. | RC-20 |
| RC-24 | `TuiApp` for kyo-ui (`def ui`, `options`, `args`, `onExit`; scheduler defaults; capture install; shutdown hook; exit codes) | PR 5 `TuiApp.scala` (136) | adapt | `tui4s/TuiApp.scala` | M (~150) | Medium: exit code of Ctrl-C (D10). | RC-2, RC-23, RC-25 |
| RC-25 | `SchedulerDefaults` (idle 14% -> 4% headless; 15.5% -> about 5.8% real process, RR N5) | PR 5 `spi/SchedulerDefaults.scala` (63) | keep | `tui4s/spi/SchedulerDefaults.scala` | S (0 changes) | Low. | none |
| RC-26 | `Trace`: keep writers; one JSON line per frame from `FrameStats` + "settle timed out" | PR 5 `internal/Trace.scala` (354) | adapt | `tui4s/internal/Trace.scala` | M (~250) | Low. | RC-6, RC-11 |
| RC-27 | Carried PR 5 tests and doubles: `TerminalIOTest`, `TuiAppTest`, `EventMappingTest`, `OutputCaptureTest`, `TraceTest`, `SessionTest`, `NativeTerminalTest`, `SchedulerDefaultsTest`, `GuardTest`, `ScreenTest`, `FakeCrossterm`, `RecordingControl`, `ScriptedInput` | PR 5 `modules/core/src/test/scala/tui4s/*`, `testkit/*` (about 1900 + 147 lines) | adapt | `modules/core/src/test/scala/tui4s/`, `tui4s/testkit/` | L (~2100) | Medium: volume. | RC-20 to RC-26 |
| RC-28 | `Screen` style type: replace `tui4s.Style` (dropped `Styling.scala`) | PR 3 `testkit/Screen.scala:7-9, 88-100` | adapt | `tui4s/testkit/Screen.scala` | S (~60) | Low. | D11 |
| RC-29 | Drop the old model and engine files of PRs 3-5 (list in section 3.4, "Do not carry") | `origin/feat/core-3-terminal` | drop | none (not carried) | n/a | Low; they stay on the old branches. | D1 |
| RC-30 | Glitch probe, split probe, leak probe as ignored stress suites (core) and bench probes | `proto/dev/Glitch.scala`; IR `stress/*.test.scala` | adapt | `modules/core/src/test/scala/tui4s/stress/`, `bench/` | S (~200) | Low. | RC-19 |
| RC-31 | PTY kit merge: proto `vt.py` + `check.py` + `checklist.exp` with PR 5 cases (SIGTERM, Ctrl-C, pipe, native access denied, WINCH, paste, Dashboard); drop `analyze.py`; CI `terminal` job | proto `pty/`; PR 5 `examples/ptycheck/` | adapt | `examples/ptycheck/` | M (~650) | Medium: PTY timing on CI runners. | RC-33 |
| RC-32 | Bench: proto `dev/Bench.scala` + PR 5 probes on kyo-ui apps; JMH benches of `Layout`/`Paint`; split-aware "late frames"; new `BASELINE.md` section | proto `dev/Bench.scala` (231); PR 5 `bench/` (~760 + 153 JMH) | adapt | `bench/` | M-L (~900) | Low; numbers need an idle machine. | RC-33 |
| RC-33 | Examples: Counter (+ two counters), CounterStyles (hooks + `Component`), Todo, Dashboard, Widgets; munit suites; `launchers`; README | proto `apps/*` (388 lines); PR 5 `examples/` | adapt | `examples/src/main/scala/examples/**`, `examples/src/test/...` | M (~650) | Low. | RC-24 |
| RC-34 | Upstream issue DRAFT (ranked proposals of section 5 + module split); never posted without the user | RR "Upstream proposals"; section 5 | new | `.work/design/plan/upstream-kyo-ui-draft.md` | S (text) | Low. | D12 |
| RC-35 | PR restructure without history rewrite: new branches (a)/(b)/(c) from `chore/cleanup`; PRs 3-5 closed by the user | RR open question 7; section 6 | decide | GitHub | S | Low; the user must close PRs. | D1 |
| RC-36 | Binary-compat surface: no jatatui or Kyo-internal types in the public API; `private[tui4s]` facade seams are bytecode-public (MiMa); core `compat.sbt` stays `Compatibility.None` until the first release | AGENTS.md "Compatibility"; PR 5 `modules/core/compat.sbt` | adapt | `modules/core/compat.sbt`, public API | S | Medium: a jatatui or Kyo bump would break facade binaries if leaked. | RC-1, RC-7 |

---

## 8. Decisions needed (options, tradeoffs, recommendation)

- D1 (ASSUMED A1, confirm): Restructure PRs 3-5 as (a) runner core, (b) terminal and app, (c) examples, PTY check,
  bench, on NEW branches from `chore/cleanup`; close PRs 3, 4, 5. Alternative: push the new work onto the old
  branches (keeps PR numbers, but the diffs mix the dropped model with the new runner, or need a force-push).
  Recommendation: new branches; the user closes the old PRs.
- D2: Package of the user API: `tui4s` (recommended: shorter, tui4s IS the kyo-ui backend) or `tui4s.kyoui`
  (explicit, matches the side-by-side page).
- D3: Duration type in Kyo-free options: `java.time.Duration` (recommended: equal to `zio.Duration`; JDK type;
  Kyo has `Duration.fromJava`), `scala.concurrent.duration.FiniteDuration` (Cats-native), or plain `Long`
  milliseconds (PR 5 style `pollMillis`; no type, no conversion, less clear).
- D4: Quit for facades: `Tui.run(options)(quit => ui)` (recommended: no extra fiber, works for ZIO, Cats and
  Kyo; keep `Tui.quit` for Kyo users), a run handle only (needs the UI to exist before the handle), or one
  JVM-wide "current run" (simple, but global state; IR Q1 option 3).
- D5: Settle default `Settle.Quiescent(100 ms, confirm = 2)` (RR open question 1). Recommendation: keep; it is
  "very likely" glitch-free and measured; the exact fix is upstream (rank 1).
- D6: R2 fix: settle after the initial mount + verify pass for young regions (recommended), a shorter repair
  interval (simple, more idle CPU), or `pending` until the first value (does not close the window).
- D7: kyo-ui transitive deps (+15.2 MB): accept (recommended), exclude (not safe: `HttpUrl` in the AST, `Schema` on
  `Modifiers`), or wait for an upstream split.
- D8: Confirm kyo-test for the core (DECISIONS round 4) now that the prototype tests are munit. Cost of the
  conversion: about 210 lines, size S. Recommendation: keep kyo-test.
- D9: Scope of the first release: scrolling or a virtual list (RR Q2); the unit convention (RR Q3: 1 px = 1 cell,
  a scale, or `ch`/`lh`); mouse (RR Q4; mouse capture stops text selection in the terminal); `onKeyUp` warning
  (RR Q5); real rendering for `Img`, `Chart`, `Textarea`, `Select` (RR Q6); the extra PTY cases (RR Q8).
  Recommendation: scroll with focus-follows, keep 1 px = 1 cell for now, no mouse, a log warning for
  unsupported attributes, placeholders for `Img`/`Chart`, all PTY cases.
- D10: Ctrl-C exit code: 0 (today; raw mode makes Ctrl-C a key) or 130 (the usual code for SIGINT) (IR Q2).
  Recommendation: 0 for Ctrl-C as a key; 130 and 143 only for real signals from outside (PR 5 `TuiApp` behaviour).
- D11: `Screen` cell styles in the test kit: a small tui4s `CellStyle` with Scala types (recommended: no jatatui
  type in the public API, RC-36) or jatatui `Style` (no new type, but ties the API to jatatui).
- D12 (ASSUMED A2, confirm): Draft the upstream issue (RC-34) in `.work/`; the user reads it and posts it (or tells
  Claude to post it).

---

## 9. Working assumptions (the user said "I like it, go ahead"; each one is ASSUMED until the user confirms)

- A1 ASSUMED: the core rework is (a) runner core, (b) terminal and app parts from PR 5, (c) examples, PTY check
  and bench; PRs 3 and 4 are closed or replaced. This inventory also proposes to replace PR 5 (D1).
- A2 ASSUMED: a kyo-ui upstream issue is DRAFTED and never posted without the user (RC-34, D12).
- A3 ASSUMED: the ZIO facade state type is a facade-owned `SignalRef[A]`, not a `zio.Ref` (FR "State"). Effect on
  the core: none; the facade ref wraps the same `kyo.Signal.SignalRef` cell that the runner binds (FR F4-F6). The
  core must keep writing the bound ref BEFORE `onInput` (`Dispatch.scala:143-146`) so that one cell stays the
  source of truth.
- A4 ASSUMED: generated mirrors are checked in, with a codegen sbt project and a CI drift check. Effect on the
  core: the generator reads the kyo-ui TASTy, not the core; the core's public API (`Tui`, `TuiOptions`, `Headless`,
  `FrameStats`) is mirrored by hand in the facades, so it must stay small and Kyo-free where the facades touch it
  (R4, R5).
