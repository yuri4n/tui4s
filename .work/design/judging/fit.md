# Judge: ecosystem fit (25%) and testability / agent-friendliness (15%)

Status: COMPLETE (2026-10-01). Judge lens: "fit". Nothing was compiled (panel rule).
Candidates: elm, components, signals, free. All four files were read in full.
Sources checked: `.work/scratch/kyo-src/` (RC7), `.work/scratch/jatatui/src/` (0.31.0),
`.work/scratch/kyo-rc7/zio/ZioBridge.scala`, `.work/scratch/kyo-rc7/CatsBridge.scala`.

## 1. Scores

| Candidate  | fit (25%) | testability (15%) | weighted points (fit x 0.25 + test x 0.15) |
| ---------- | --------- | ----------------- | ------------------------------------------ |
| elm        | 8.0       | 8.5               | 3.28                                       |
| free       | 6.5       | 9.0               | 2.98                                       |
| signals    | 7.5       | 7.0               | 2.93                                       |
| components | 6.0       | 6.5               | 2.48                                       |

Short version: **elm** has the best fit, mostly because of its ZIO and Cats integrations. Its facades
only re-export the Kyo-free shared surface and add effect constructors and a runner. **free** has the
best testability: its `TuiTest` is synchronous, has no effect types, and is the same for all three
ecosystems. **signals** is the most Kyo-native design after components, and its Cats facade is the most
idiomatic. **components** fits Kyo best of all, but its ZIO and Cats facades are second class, and
they have a lost-update hazard.

## 2. What I verified in the sources (and what it changes)

| # | Claim | Who relies on it | Result | Where |
| - | ----- | ---------------- | ------ | ----- |
| V1 | `SignalRef.Unsafe.next(): Fiber.Unsafe[A, Any]` returns the current change promise; every changing write swaps in a new promise, then completes the old one | signals (watchers) | TRUE. It is a public "integrations" API. The register-before-wake protocol in signals 5.3 is sound on the JVM: `getAndSet` writes `currentRef` before `onUpdate` swaps the promise | `kyo-core/kyo/Signal.scala:666-718` |
| V2 | `SignalRef.observe` re-arms a `repairInterval` race; `Signal.map` is a lazy view with no cache | signals | TRUE (`observe(repairInterval)`, `Async.race(nextWith, Async.sleep(repairInterval))`; `map` = `_initRawF`) | `Signal.scala:116-142, 160` |
| V3 | Kyo lifts any plain value into `A < S` implicitly | components (facade trap) | TRUE: `implicit inline def lift[A, S](v: A)(using CanLift[A]): A < S`. So a `ZIO`/`IO` value passed to a Kyo `h: => Any < R` compiles and never runs. Components saw this and closed it with an opaque facade `View`. Signals avoids it with the `IntoAction` typeclass. Elm and free never take a handler of type `Any < S`, so they are not exposed | `kyo-kernel/kyo/kernel/internal/Implicits.scala:18` |
| V4 | `opaque type <[+A, -S]`; `Async <: Sync` | signals (`IntoAction[-R]` given for `Unit < (Async & Abort[Throwable])` must accept `Unit < Sync`) | TRUE: `opaque type Async <: (Sync & Async.Join)`, and `S` is contravariant, so `Unit < Sync <: Unit < (Async & Abort[Throwable])`. A branch that returns `Int < Sync` does not fit, and the `@implicitNotFound` text asks for `.unit`, which is good for agents | `Pending.scala:48`, `kyo-core/kyo/Async.scala:47` |
| V5 | `Abort[-E]` is contravariant | all (core `Abort[TuiError]` fits bridge `Abort[Throwable]`) | TRUE | `kyo-prelude/kyo/Abort.scala:41` |
| V6 | `Var.updateDiscard`, `Var.setDiscard`, `Var.runTuple`, `Emit.value`, `Emit.run`; `Emit[-V]` | components | TRUE | `Var.scala:108,141,194`; `Emit.scala:34,46,90` |
| V7 | `Sync.ensure`, `Sync.acquireReleaseWith` | all (F8 terminal restore) | TRUE (both overloads present) | `Sync.scala:61,78,109` |
| V8 | `Async.sleep` goes through `Clock.sleep`; `Clock.withTimeControl`, `advance`, `awaitPendingSleepers` | elm, components, signals tests | TRUE, **with a catch**: `advance(d)` moves the clock once and completes only the sleepers that are due at that moment. A periodic task sleeps again *after* the jump, so its next deadline is `now + period`. One `advance(1.second)` gives ONE tick of a 250 ms timer, not four. `advance` also waits up to 100 ms of real wall-clock time | `Clock.scala:390-500`, `Async.scala:161` |
| V9 | `Channel.initUnscoped`, `Channel.Unsafe.putFiber/putBatchFiber/offer/drain`, `Fiber.initUnscoped`, `Fiber.Unsafe.onComplete` | all | TRUE | `Channel.scala:338,387,411-421`; `Fiber.scala:178,500` |
| V10 | `Stream.unfold(acc, chunkSize)(f)`, `Stream.range(start, end, step, chunkSize)`; default chunk 4096; `map` with an effectful function maps a whole chunk with `Kyo.foreach` | elm, components, signals, free samples | TRUE. The 4096 trap is real: `map` emits only after the whole chunk is mapped | `Stream.scala:92-93, 924, 982, 1027-1038` |
| V11 | `KyoApp.Base.main` is `final` | elm, free (TuiApp not a KyoApp) | TRUE | `KyoApp.scala:52` |
| V12 | KyoApp's constructor does not start the scheduler, so `TuiApp extends KyoApp { Tui.tuneScheduler() }` can still set the StaticFlags | components, signals | PLAUSIBLE: the constructor only creates a promise and installs INT/TERM handlers (`KyoAppInterrupts`); the scheduler is first touched when `main` runs the registered block. Not proven (class init of `Promise.Unsafe` not traced) | `KyoAppInterrupts.scala:29-42`, `KyoAppRunner*.scala` |
| V13 | `Log.let`, `Random.withSeed`, `Kyo.zip` (up to 10), `Loop.foreach`, `Async.timeout`, `Timeout`, `Duration.fromJava` | various | TRUE | `Log.scala:136`, `Random.scala:192`, `Kyo.scala:88+`, `Loop.scala:626`, `Duration.scala:93` |
| V14 | `Local.init` is inheritable (forked fibers see the ambient ZIO runtime / Dispatcher) | elm, components, signals, free bridges | TRUE (`initNoninheritable` is the separate opt-out) | `Local.scala:132,163` |
| V15 | `AmbientZIOs.run/get` capture the caller runtime in an inheritable `Local` (F12) | all ZIO facades | TRUE | `kyo-rc7/zio/ZioBridge.scala:36-49` |
| V16 | `CatsBridge.runWithDispatcher` uses `Dispatcher.parallel(await = false)`; `runF` is cancel-and-await | all Cats facades | TRUE | `kyo-rc7/CatsBridge.scala:71-113` |
| V17 | "jatatui widgets are immutable values with structural equality" | free (`View = Widget`, level-2 frame skip), signals (leaf memo) | **FALSE for `Gauge` and `LineGauge`**: they have no `equals`/`hashCode`, so they compare by reference. `Paragraph`, `Sparkline`, `Tabs`, `List`, `Block`, `Clear`, `Span`, `Line`, `Text` do have `equals`; `Style` and `Table` are records | `jatatui-widgets/.../gauge/Gauge.java`, `LineGauge.java` |
| V18 | `TestBackend.draw` copies cell fields; `buffer()` returns the live internal buffer | all testkits | TRUE. A testkit must copy into its own immutable `Screen` (elm, components, signals do; free also exposes `def buffer: Buffer`, the live mutable object) | `TestBackend.java:53,133-145` |

Consequences of V8 and V17:

- **V8 breaks two sample tests.** Elm `DashboardSuite`: `tui.advance(1.second)` then `assertEquals(m.samples.size, 4)`.
  Under Kyo's `TimeControl` it gives 1 sample. Components `DashboardTest`: `clock.advance(2.seconds)` and
  "5 log lines arrive offstage" (400 ms stream). It gives 1 line, so `s.contains("(#5)")` fails. Signals uses
  the Kyo idiom (`clock.advance(250.millis).andThen(clock.awaitPendingSleepers(2))`). But it does not fence
  *before* the first advance, so a timer that is not armed yet can miss the jump. A correct `TestTui.advance(d)`
  must step: `awaitPendingSleepers(n)`, then advance to the next deadline, then settle, until `d` is used up.
  This can be fixed in the testkit. But the elm and components examples show that their designers did not
  check the clock semantics.
- **V17 weakens free's claims.** In free, `View` *is* the jatatui widget. A screen with a `gauge(...)` is never
  `equals` to the last view, so the "level 2: no frame" filter never fires while a gauge is visible. The
  Dashboard always shows a gauge on the Metrics tab. This costs only performance, but `t.frames`
  assertions on such screens would surprise an agent. Elm and components keep plain data in their own case-class
  nodes and build the jatatui widget at paint time, so they are not affected. Signals leaves hold jatatui
  widget values, so its view memo misses for gauges. That is minor, because a binding recomputes only when its
  source value changed.

## 3. Candidate by candidate

### 3.1 elm (fit 8.0, testability 8.5)

**Kyo fit.** Kyo appears only where effects enter. These are the `Cmd`/`Sub` constructors
(`Cmd.perform(task: => A < Async)`, `Cmd.attempt(... A < (Async & Abort[E]))(Result[E, A] => Msg)`,
`Sub.stream(id)(Stream[A, Async])`, `Sub.signal(id)(Signal[A])`) and the runner
(`Tui.run: Model < (Async & Abort[TuiError])`, `Tui.start: TuiHandle < (Async & Scope)`). `TuiHandle.models: Signal[Model]`
is a good Kyo integration point for apps that embed a TUI. The engine sketch is idiomatic: `Loop.foreach`,
`map`/`andThen`, one `Sync.Unsafe.defer` per batch, and `Sync.acquireReleaseWith(Session.open)(_.restore())`
around `Scope.run` (F8 correct). There are no for-comprehensions (F1). Minuses: the shared surface uses `Option`/`Seq`/
`java.time.Duration` and not `Maybe`/`Chunk`/`kyo.Duration`, on purpose. The `Cmd` row is fixed
(`Async & Abort[E]`), so there is no `Env`/`Layer` (8.1.7). A Kyo user who wants `Var`/`Signal` as UI state does
not get it: the model is one immutable value.

**ZIO / Cats fit (best of the four).** The facades do not wrap `Program`, `View`, `Next`, `Key`. They re-export
them and add (1) effect constructors, (2) a runner, (3) an app class. The ZIO API is idiomatic:
`Cmd.attempt(zio)(Either[E, A] => Msg)`, `Cmd.exit(zio)(Exit => Msg)`, `Sub.stream(id)(ZStream)`,
`Tui.run: ZIO[Any, TuiError, Model]`, `ZTuiApp extends ZIOAppDefault`. The Cats API is the same with `IO`/`fs2.Stream`.
Every constructor lowers to `Cmd.fromDispatch`/`Sub.fromDispatch`, so the core knows nothing about ZIO
or CE. Runtime fidelity is right: `AmbientZIOs.run/get` (F12), not `ZStreams.get`; `CatsBridge.runWithDispatcher`
with `await = false` (F10, stated). Backpressure reaches the foreign stream:
`put(d, a) = ZIO.async(cb => d.unsafe.put(a, () => cb(ZIO.unit)))`. Key handling is pure, so a key press
never crosses a runtime boundary; only commands and subscriptions do. `Frame` is summoned inside the facade,
so no Kyo type is in any facade signature. Minuses: Cats is `IO`-only in v1 (open question 8.4.2). Neither app
class removes ZIO's console logger or the CE starvation checker (free does). Virtual time in facade
tests is an open gap (8.2.4). The `tui4s.zio` / `tui4s.cats` package shadowing (shared by all four) is
documented.

**Resource safety.** The restore is a synchronous release. On interrupt, the input thread can read one more
event after the restore (8.2.5, stated honestly). Components and free stop and join the input thread first;
elm should copy that order.

**Testability.** These test levels are pure and Kyo-free, so they are the same for Kyo, ZIO and Cats users:
`val Next(m, cmd) = app.update(m, msg)`, `route(app.subscriptions(m), key)`, and
`Screen.render(app.view(m), w, h)`. Focus logic is a one-line test without a runtime
(`route(Todo.subscriptions(inList), Key.Char('q')) == Some(Msg.Quit)`). `TestTui` adds `frames`, `quit`, `model`,
`screen` and virtual time. `traceFile` lets an agent replay a real-terminal session into `update`.
`Msg` enums give exhaustivity errors. Minuses: the Dashboard test is wrong under Kyo's `TimeControl` (V8).
`Next` is a union, and `withCmd` is an extension on every type (`anything.withCmd(...)` compiles). There are
more pieces than in free (`Cmd`, `Sub`, `Keymap`, `Bind`, `Next`, `View`).

### 3.2 components (fit 6.0, testability 6.5)

**Kyo fit (best on the Kyo side alone, about 9/10).** Local state *is* `Var[S]`, outputs *are* `Emit[O]`, commands
*are* `Emit[Tui.Cmd]`, lifetimes *are* per-instance `Scope`s, and async sources are `Stream`/`Signal`/fibers. A handler runs as
`Var.runTuple(state)(Emit.run[O](Emit.run[Tui.Cmd](h)))` + `Abort.run[Throwable]` (all names verified, V6).
"Updating the wrong component's state is a compile error" holds because `Var[V]` is invariant. `View[-R]`
follows the contravariance of `A < S`. `Log.let` keeps Kyo's console logger off the screen. The costs: Kyo
rows in error messages, `Tag[Var[S]]` on every component (generic components need classes with
`using` parameters), the `type Ui = BuildContext ?=> View[Local]` context function, and a closed row (no `Env`).

**ZIO / Cats fit (weakest, about 4/10).** The designers saw the V3 trap. As a result, the facades cannot reuse
the core `View`. Each facade re-declares an opaque `View`, ~60 forwarders, `Component`, `UiState`,
`Sub` and `Tui`. That is a second API to keep in sync by hand. `state.update` reads a `spi.Dispatch` from a ZIO
`FiberRef` / CE `IOLocal`, and the SPI says: "Valid only while one handler runs". Problems:
1. A ZIO user who forks inside a handler (`fetch.flatMap(r => state.set(...)).fork`, a normal ZIO pattern) gives
   the child fiber an inherited reference to a dead `Dispatch`. The owner check compares the component, not
   liveness. So the write is lost silently, or it races the UI fiber.
2. The facade `Component` has no `task` (the Kyo side has one), so ZIO/Cats users have no correct way to run
   one-shot async work that writes state back. They can block the handler (the UI freezes) or abuse `Sub.onMount`
   with a changing key.
3. `Tui.quit: UIO[Unit]` and `state` work only inside a handler. Outside one they fail at run time.
4. Cats is `IO`-only. Every handler pays a bridge hop.
5. There is no facade testkit: `Probe.key` takes a Kyo `Component.Emitting`, and `TestTui` returns Kyo effects.
   So a ZIO/Cats user cannot test without Kyo types.

The bridges themselves are right (AmbientZIOs, Dispatcher `await = false`). **Resource safety** is the best of the four: `Term.exit`
stops and joins the input thread *before* it leaves raw mode, inside `Sync.acquireReleaseWith`.

**Testability.** Good tools: `Probe.key(component, props, state, key): Transition` for one transition, and
`TestTui.tree` / `explainKey` ("why does my key do nothing", answered as text), dev-mode checks for
duplicate keys and focus ids. But every test beyond plain state methods needs the Kyo runtime (`Probe.key`
returns `< (Async & Abort[Throwable])`). Trees with handlers cannot be compared with `==`. The Dashboard test is
wrong under `TimeControl` (V8). The design itself lists the agent traps: the positional/keyed/object
identity rules, `onKeys` row inference (UNVERIFIED), and the confusing error when `: Ui` is missing.

### 3.3 signals (fit 7.5, testability 7.0)

**Kyo fit (very good).** The state type is Kyo's own `SignalRef` with no wrapper, so any Kyo code can share it.
Setup is `Tui => View < (Async & Scope & Abort[Throwable])`, fibers are `Fiber.init` in the TUI `Scope`, streams are
`stream.foreach(...)`, and pipelines are `Kyo.zip(...).map` with no for-comprehensions. The watcher uses the public integration
API `SignalRef.unsafe.next()` correctly (V1). The design also explains why it avoids `observe` (1 s repair timer,
V2). `Log.let` keeps the console logger off the screen. Minuses: `TuiConfig` mixes in
`scala.concurrent.duration.FiniteDuration`. Derived signals (`ref.map`) silently get the 1 s-repair watcher.
`IntoAction[R]` inference from partial-function literals is UNVERIFIED (V4 shows that the subtyping is right).

**ZIO / Cats fit (good, best Cats).** ZIO: handlers return `ZIO` through `given IntoAction[ZIO[Any, Throwable, Unit]]`,
setup is `ZIO[Scope, Throwable, View]` with `forkScoped`, and `TuiZIOApp extends ZIOAppDefault`. Cats: tagless
`Tui.run[F[_]: Async](Tui[F] => Resource[F, View])` and `SignalRef[F, A]` with `Sync[F]`. This is the most idiomatic CE surface
of the four. Facade state writes call the Kyo unsafe API inside `ZIO.succeed` / `Sync[F].delay` (no bridge hop).
Handlers do cross runtimes on every key (fork on the caller runtime, then await). Gaps:
1. The facade setup scope is closed by a core `Scope.ensure`
   (`Scope.ensure(getF(release))` for Cats, `.provide(childScope)` for ZIO). On interrupt, F8 says Scope finalizers
   run detached and are not awaited. For Cats, the release also goes through a Dispatcher that is shutting down with
   `await = false`. So user `Resource` finalizers can run after `Tui.run` returns, be cancelled, or be rejected.
2. ZIO `SubscriptionRef` and fs2 `SignallingRef` cannot be bound directly (8.1.13). Users must learn a parallel
   `SignalRef`.
3. `(using Frame)` shows on `Source.view`, and users must remember `import tui4s.zio.{*, given}` (a missing given
   gives a clear `@implicitNotFound`).

**Resource safety.** The restore is a `Sync.ensure` (good). But the input is a Kyo *fiber* in the TUI `Scope`, and
5.9 says the restore runs first and the scope closes after it. So the input fiber can still `poll`/`read`
stdin in cooked mode after the restore and swallow a keystroke typed into the shell. Stop the input first.

**Testability.** `settle` is exact (a sync message through the inbox, then a pull). `verifyRepaints` proves in every test that
the partial repaint equals a full repaint. `stats` lets tests assert performance properties. Every error
carries the Kyo `Frame` of the binding or handler. The Dashboard test uses the right Kyo clock idiom
(needs one more fence, V8). The facades ship `TestTui` in `UIO`/`F`. Minuses: there is no pure transition test.
Handlers are effects on refs that the setup creates, so "key `+` in state 3 gives 4" needs the engine. The app must be exposed
as a value to be testable. The rules that decide correctness and speed are invisible to the
types: bind at the leaves, one ref per consistent unit (torn frames otherwise), `at` instead of `map`.
These are traps that an agent will not see.

### 3.4 free (fit 6.5, testability 9.0)

**Kyo fit (moderate).** App logic has no effects at all (`init`, `view`, `update(S, Event): Next[S]`, `sources(S)`), so
F1 cannot happen. Kyo shows only in `Source` constructors (`A < (Async & Abort[Throwable])`, `Stream`, `Signal`)
and in `Dispatch.apply(f: S => S): Unit < Async`. The runtime is clean and idiomatic: `Loop(state, drawnAt)`, one
`Sync.defer` fold per batch, `Sync.acquireReleaseWith(TerminalIO.open)(_.close())` with input stopped first,
`Sync.ensure(sup.stopAll)`, and `Fiber.initUnscoped` owned by a supervisor (with a stated reason). `TuiApp` is not a
`KyoApp`, because `KyoApp.Base.main` is final (V11). Minuses for "idiomatic Kyo": users never use
`Var`/`Signal`/`Scope`. There is **no one-shot effect** (W2): "on Enter, call an API" must be modelled as a state flag plus
`source.task(...).key(s.saveId)`. That is unidiomatic in Kyo, ZIO and CE alike, and an agent will forget to bump the
key. `View = jatatui.core.widgets.Widget` puts jatatui 0.x types into the public and binary API of all three published modules (R3),
and V17 shows that their equality is not uniform.

**ZIO / Cats fit (good).** The same `App[S]` is used in all three ecosystems (the Counter body is identical). Sources take
`ZIO`/`ZStream` or `F`/`fs2.Stream`, runners return `IO[TuiError, S]` / `F[S]`, and the app classes are `ZIOAppDefault` /
`IOApp.Simple`. It is **the only candidate that removes ZIO's console logger** (`Runtime.removeDefaultLoggers`)
**and turns off the CE starvation checker**. Both write to stdout/stderr and would corrupt the screen. Bridges
are right (AmbientZIOs, `await = false` stated). Minuses: `(using kyo.Frame, Trace)` sits in the ZIO facade
signatures (auto-filled, but visible; elm avoids it). One `F` per app is checked at run time, not by the compiler
(R10). Foreign source cancellation is signalled, not awaited, at shutdown.

**Testability (best).** `TuiTest(app, w, h).press(...).typeText(...).fold(_.sample(0.42))` runs the real fold, the real
frame rules and the real painter, synchronously on the caller's thread, with no effect runtime. It is the same code
for Kyo, ZIO and Cats users. `t.state`, `t.screen` (a string), `t.frames` (performance as an assertion), `t.sources`
(declared source keys, so "pause stops the poller" is one line), `t.cursor`, and `assertScreen` with a row diff are
the shortest agent loop of the four. The surface is the smallest (seven concepts). Minuses: `t.frames` is wrong in
spirit for screens with a `Gauge` (V17). `TuiTest` exposes the live mutable jatatui `Buffer`. Timing is only
simulated (`fold`); the live level is UNVERIFIED. Source identity by call site (`Frame`) is implicit, and two sources
built in one helper share it. Fold placeholders need the `source` member (R2).

## 4. Fatal flaws (in this lens)

- **components**: the ZIO/Cats facade state handle depends on an ambient `Dispatch` that "is valid only while one handler
  runs". A forked fiber inherits it through `FiberRef`/`IOLocal`, so a later `state.update` is silently lost or races the UI
  fiber. The facades also have no `task`, so there is no correct path for async work that writes state. For the goal
  "ZIO and Cats Effect integrations comfortable for the developer", this is fatal unless the facade gets a typed handle
  (for example `onKey(k)(ui => ...)` with an explicit, liveness-checked handle) plus a facade `task`.
- **elm / signals / free**: no fatal flaw in this lens. The serious but fixable items are: signals' facade
  `Resource`/`Scope` release on the interrupt path, and its input fiber outliving the restore; free's `View = Widget` coupling
  plus the `Gauge` equality hole; elm's and components' sample tests that are wrong under Kyo `TimeControl`.

## 5. Best ideas to graft into the winner

1. **elm**: a Kyo-free shared surface, plus facades that add only effect constructors, a runner and an app class.
   Everything lowers to one low-level port (`Cmd.fromDispatch` / `Sub.fromDispatch`), so the core knows nothing about
   ZIO or CE, and key handling never crosses a runtime boundary.
2. **elm**: `TuiHandle` (`send`, `models: Signal[Model]`, `await`, `stop`) and `Tui.start: ... < (Async & Scope)` to embed
   a TUI in a bigger Kyo program.
3. **elm**: one `Keymap` value drives both routing and the help bar, and a pure `route(subs, key)` makes focus tests one line.
4. **elm**: `TuiOptions.traceFile` (one line per msg, command, sub start/stop, frame) and replay into `update`.
5. **elm / free**: foreign-stream backpressure as `ZIO.async` / `IO.async_` over `Channel.Unsafe.putFiber(...).onComplete`, and
   never `ZStreams.get` (F12).
6. **free**: the synchronous, effect-free `TuiTest` driver for all three ecosystems, `t.frames` as a performance contract,
   `t.sources`, and `assertScreen` with a row diff.
7. **free**: ZIO `TuiApp` with `bootstrap = Runtime.removeDefaultLoggers`, and CE `TuiApp` with the starvation checker off.
   Combine with **components/signals** `Log.let(fileLogger)` around the whole run for Kyo's `Log`.
8. **free / elm**: `TuiApp` does not extend `KyoApp`. Its own `main` sets the scheduler StaticFlags before the first Kyo
   class loads (V11).
9. **components / free**: stop and join the input reader *before* leaving raw mode, inside the synchronous release.
10. **signals**: the Cats facade shape: tagless `F[_]: Async`, `Resource[F, ...]` for setup, and state ops as
    `Sync[F].delay` over the Kyo unsafe API (no bridge hop). Bind the `Resource` release on the facade side (allocate
    before calling the core), not through a core `Scope.ensure`.
11. **signals**: `IntoAction[-R]` with an `@implicitNotFound` that names the fix. This is the clean guard against the V3
    lift trap wherever a handler parameter would otherwise be `Any < S`.
12. **signals**: a deterministic `settle` (a sync promise through the inbox), `TuiConfig.test`, `Clock.withTimeControl` with
    `awaitPendingSleepers` fences *before and after* each step, `Random.withSeed`, and errors that carry the Kyo `Frame` of the
    user code.
13. **signals**: an exact watcher over `SignalRef.unsafe.next()` (register before wake). Use it to implement `Sub.signal` /
    `Source.signal` without `observe`'s 1 s repair timer.
14. **components**: `TestTui.tree` and `explainKey` text introspection, and dev-mode checks (duplicate keys or focus ids, a
    handler slower than one frame budget).
15. **elm**: opaque `Style` / `Color` / `Line` over jatatui records, and view nodes that hold plain data. They hide the name
    clashes and the `Style.fg()` accessor trap, keep jatatui out of the binary API, and keep view equality correct
    (V17 shows that holding raw jatatui widgets breaks it).

## 6. Notes for whoever implements the winner

- `TestTui.advance(d)` must step through deadlines with `awaitPendingSleepers` (V8). A single `TimeControl.advance` is not
  "time passes for d".
- View equality must not depend on jatatui widget `equals` (V17: `Gauge`, `LineGauge` have none).
- On the interrupt path, every foreign-runtime finalizer (ZIO `Scope`, CE `Resource`) must be released by the facade's
  own bracket. A Kyo `Scope.ensure` is not awaited (F8).
- Keep F12/F10 explicit in the facade code: `AmbientZIOs`, and `Dispatcher.parallel(await = false)`.
