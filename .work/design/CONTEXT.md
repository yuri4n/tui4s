# Design panel: shared context (read this first)

Date: 2026-10-01. Repo: /Users/yuri4n/Developer/projects/tui4s (branch chore/cleanup, PR 1 open).
All paths below are relative to the repo root.

## The goal (the user's words)

> A kyo core that wraps the underlying scala library, using state or other methods to write
> declaratively, simply, even beautifully, while performant as possible.
> ZIO and Cats effect integrations as separate modules. [...] independently deployable, but in a
> way that is comfortable with the developer or the agent.

Scoring weights for the panel, from those words:

| Criterion | Weight |
| --- | --- |
| Declarative, simple, beautiful API (DX) | 35% |
| Performance and runtime correctness | 25% |
| Kyo / ZIO / Cats Effect ecosystem fit | 25% |
| Testability and agent-friendliness | 15% |

## Fixed decisions (do not re-open)

- Backend: **jatatui 0.31.0** (`com.olvind.jatatui`: crossterm, jatatui-core, jatatui-widgets,
  jatatui-crossterm; MIT). The Java port of ratatui, successor of tui-scala. Never put
  `com.olvind.tui` on the classpath (same `tui.crossterm` package).
- **Kyo 1.0.0-RC7**, **Scala 3.9.0**, **JDK 25**. Compile with `-Yexplicit-nulls`,
  `-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`.
- Modules: `tui4s-core` (Kyo + jatatui), `tui4s-zio` (ZIO 2.1.26, kyo-zio RC7),
  `tui4s-cats` (cats-effect 3.7.1, fs2 3.14.0, vendored port of the kyo-cats bridge). Each
  module is released on its own. ZIO and Cats users must be able to write a tui4s app
  **without Kyo types in their code**.
- groupId `io.github.yuri4n`, package root `tui4s`. License Apache-2.0.
- The user's own first take (Flutter-style Widget / Element / State on Cats Effect) is ONE
  candidate, not a requirement. See it with:
  `git show 48a6dd9:src/main/scala/tui4s/interfaces.scala`,
  `git show 48a6dd9:src/main/scala/tui4s/elements/elements.scala`,
  `git show 48a6dd9:src/main/scala/tui4s/widgets.scala`,
  `git show 48a6dd9:src/main/scala/tui4s/withTerminal.scala`,
  `git show 48a6dd9:src/main/scala/tui4s/Demo.scala`.

## Verified facts you must design around

Sources: `.work/reports/kyo.md` (Kyo API, RC5 to RC7 changes), `.work/reports/kyo-rc7.md`
(findings F1..F12), `.work/reports/jatatui.md` (section 0 is the summary; sections 2-5 the
API, threading and perf; 7 jatatui-react prior art; 8 Scala interop; 9 risks).

jatatui:
- `Buffer` and `Cell` are mutable. `Buffer.diff` returns live cells. `Layout.split` returns the
  shared cached `Rect[]`. Widget states (`ListState`, `TableState`, `ScrollbarState`) are mutable
  and `render` writes them. So: **all drawing on one fiber**, and the library owns these objects.
- Widgets, text and styles are immutable values with structural equality.
- A typical 120x40 frame costs ~2 ms and ~1.7 MiB allocation, **even when nothing changed**
  (`Terminal.draw` fixed overhead ~0.6 ms). So **render only when the model changed**, and
  coalesce bursts of events into one frame.
- `TestBackend` is published and copies cells: tests can render without a terminal.
- `Jatatui.init` / `runIo` must not be used (global uncaught-exception handler, shutdown hook).
  tui4s does raw mode / alternate screen acquire and release itself.
- crossterm `read()` is a blocking JNI call that interrupts cannot stop. The input fiber must
  loop `poll(short timeout)` then `read()` (drain every event that is already queued).
- Java records cannot be used as Scala extractors. Class-name clashes with Scala: `List`,
  `Line`, `Block`, `Map`, `Color`. `Style.fg()` is a record accessor (use `withFg`).

Kyo RC7:
- Effects: `Sync` (was IO), `Async`, `Abort`, `Env`, `Var`, `Emit`, `Stream`, `Channel`,
  `Signal` / `SignalRef`, `Scope` (was Resource), `Fiber.init` / `Fiber.initUnscoped`, `Loop`,
  `Clock`, `KyoApp`, `KyoApp.Unsafe.runAndBlock`, `Local`, `Memo`.
- House style: `map` rather than `flatMap`; `andThen`, `unit`, `.handle(...)` pipelines.
- There is no `Sync.blocking`.
- `Stream.repeatPresent` defaults to chunkSize 4096: use 1 (or a Channel) for UI events.
- **F1**: Kyo's inline for-comprehensions overflow the compiler stack. The build uses
  `-Xss16m`. Keep user-facing code shapes that do not need giant for-comprehensions.
- **F8**: on interrupt, `Scope` finalizers run on a detached fiber that is NOT awaited. Only
  `Sync.ensure` / `Sync.acquireReleaseWith` finalizers are done when the fiber's result is
  visible. So **terminal restore must be a `Sync.ensure`**, not a `Scope.ensure`.
- **F12** (ZIO): `ZIOs.get` ignores the caller's runtime. tui4s-zio uses the `AmbientZIOs`
  pattern (prototype `.work/scratch/kyo-rc7/zio/ZioBridge.scala`).
- **F7/F8/F10** (Cats): bridge prototype `.work/scratch/kyo-rc7/CatsBridge.scala` (13 tests
  pass). Any CE `Dispatcher` must use `await = false`.
- kyo-test RC7 exists (`io.getkyo::kyo-test-runner`, `Test[S]` suites) next to munit.

Event-loop probe (`.work/scratch/kyo-rc7/loop.scala`, logs `loop1.log`..`loop3.log`): the
production shape that worked is

    input fiber : Loop { poll(50 ms); if ready, read() every queued event; channel.putBatch }
    tick fiber  : Loop { sleep(tick); channel.offer(Tick) }   // drop if full
    render loop : take (park) -> drain (coalesce) -> update model -> draw if changed
    shutdown    : Quit -> loop returns -> Scope closes; terminal restore in Sync.ensure

  10k queued key events: ~6.7 us/event with batched input + coalesced render (3 frames);
  ~14 us/event when drawing after every event. Interrupt -> terminal restored before
  `interruptAwait` returns.

Idle CPU of the Kyo scheduler (probe `.work/scratch/idle/`, output `idle-output.txt`):
- Plain JVM idle: 0.02% of one core. **Kyo runtime idle (one fiber asleep): ~13% of one core**
  with defaults. Cause: the scheduler's timer thread runs its worker cycle every 100 us
  (`-Dkyo.scheduler.cycleIntervalNs=100000`), the blocking monitor every 2 ms, the concurrency
  regulator every 10 ms, and a clock thread ticks every 1 ms (hard-coded).
- With `-Dkyo.scheduler.cycleIntervalNs=10000000 -Dkyo.scheduler.blockingMonitorIntervalNs=20000000
  -Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100` idle drops to 4-8% (noisy; two
  rounds; `-Dkyo.scheduler.coreWorkers=2` on top: 4-6%). The 1 ms clock thread (~2-2.5% of a
  core) is the floor and cannot be configured.
- These are `StaticFlag`s: JVM system properties read once, when the scheduler class
  initializes. A tui4s entry point could set defaults with `System.setProperty` before the
  first Kyo class loads, unless the user already set them.

## Prior art you can read

- jatatui-react (immediate-mode element tree, hooks, dirty redraw): `.work/scratch/jatatui/repo-jatatui-react_DESIGN.md`,
  sources in `.work/scratch/jatatui/src/jatatui-react/`.
- kyo-ui (reactive Signal-based UI for the browser): `.work/scratch/kyo-src/kyo-ui/kyo/`.
- Kyo sources (RC7): `.work/scratch/kyo-src/<module>/` (kyo-core, kyo-prelude, kyo-kernel,
  kyo-data, kyo-scheduler, kyo-zio, ...). jatatui sources: `.work/scratch/jatatui/src/`.
- Probes that compile: `.work/scratch/jatatui/probe.scala`, `probe-widgets.scala` (jatatui from
  Scala 3.9), `.work/scratch/kyo-rc7/loop.scala` (Kyo event loop).

## The three sample apps every candidate must show

1. **Counter**: `+`/`-` change a number, `q` quits.
2. **Todo**: a text input to add items, a list with a selection (up/down), `space` toggles
   done, `d` deletes, `tab` moves focus between the input and the list.
3. **Dashboard**: tabs (left/right), a gauge and a sparkline fed by async ticks (a fiber that
   produces a value every 250 ms), and a log pane fed by a stream.

## Rules for every agent in the panel

- Save early: create your output file at the start and add to it as you go. A stop must not
  lose your work.
- Designers and judges: do not compile or run builds. Read sources and jars instead. Only the
  validator compiles, with `scala-cli --server=false` and `export JDK_JAVA_OPTIONS=-Xss16m`
  after `source .work/env.sh`.
- Do not run sbt in the repo, do not edit files outside `.work/`, do not touch git.
