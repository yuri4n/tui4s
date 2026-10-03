<!-- claude 2026-10-03: created the prototype report above the verification section (fix of the blocking item) -->
# Prototype "kyoui-facades": ZIO and Cats facades as a generated mirror of kyo-ui

Status: DONE as a feasibility prototype (2026-10-03). The mirror works for ZIO and for Cats Effect. Open items
O1 to O8 stay (see "Open items"). The most important limits: 9 drag-and-drop setters have no ZIO or Cats form
(O1), and a facade user can reach Kyo DATA types (not Kyo effect types) through the aliased event and style types
(O2).
Folder: `.work/proto/kyoui-facades/`. Sections: "Goal", "What was built", "How to run", "User-facing API",
"Design", "Results", "Measurements", "Findings", "Open items", "Open questions for the user", "What carries into
the PRs", "History". After them: "Independent verification (2026-10-03)" (the skeptic) and "Fixes after
verification (2026-10-03)" (this agent).

Note on this report: the prototype agents (desktop 2026-10-02, cloud 2026-10-03) did not write a report. The
skeptic found no REPORT.md. The fixer agent wrote this report on 2026-10-03 from the code, the logs and its own
runs on this machine. A claim with the mark VERIFIED has output on THIS machine (Linux container, JDK 25) with the
log name. A claim with the mark HISTORY comes from a desktop log (pushed in branch `wip/handoff`) and was not re-run
here.

## Progress log

- 2026-10-03 (fixer): read HANDOFF.md, DECISIONS.md and the skeptic section. Started this report.
- 2026-10-03 (fixer): read all sources (zio, cats, full-zio, gen, shared, shared-test, probes, kyo-smoke). The text "VERIFIED by a hanging test" in `cats/src/CatsBridge.scala` comes from branch `feat/cats` (`CatsBridgeSuite`, test "stress: interrupt right after the IO starts never leaks the IO"). That test is not in this prototype. Next: re-run the tests (3 runs each), then write the sections.
- 2026-10-03 (fixer): ZIO tests 3 of 3 runs green (9 of 9 + 2 of 2, 7.7 to 7.8 s wall), Cats tests 3 of 3 runs green (9 of 9 + 2 of 2, 9.2 to 9.8 s wall). Logs: `logs/fixer-zio-test-{1,2,3}.txt`, `logs/fixer-cats-test-{1,2,3}.txt`. Stress: 0 of 150 failures and 0 wrong items in each run.
- 2026-10-03 (fixer): full-zio test 3 of 3 runs green with `full-zio zio/src shared shared-test` (FullMirrorSuite 2 of 2). kyo-smoke with `shared`: exit 0. Generator: both mains exit 0; the 3 mirrors are byte-identical (`cmp`). Probes `probes.runProbes` and `probes.errors.errorProbe`: exit 0, same lines as the desktop logs. Logs: `logs/fixer-*.txt`. Next: write the sections.
- 2026-10-03 (fixer): wrote all sections below. Appended "Fixes after verification (2026-10-03)" at the end.

## Goal

DECISIONS.md (fifth round) says: ZIO and Cats users must NOT see Kyo types. The facades are a GENERATED mirror of
the kyo-ui DSL with ZIO / `F[_]` types at every effect point. The mirror builds the same kyo-ui AST. Facade users
import only the mirror. Reason: kyo-ui handlers are `Any < Async`, so a ZIO or IO value compiles as a handler and
never runs (the "lift trap").

Questions of this prototype:

1. Can a generator read the kyo-ui API (TASTy of `io.getkyo::kyo-ui:1.0.0-RC7`) and write the mirror?
2. Does the mirror stop the lift trap at compile time, with a clear message?
3. Do handlers run on the runtime of the caller (ZIO FiberRefs and executor, Cats IORuntime and IOLocal)?
4. Can the facade state (a ZIO ref, an fs2 `SignallingRef`) be the same cell that kyo-ui binds (one source of truth)?
5. Do both component styles work (hooks with `useSignal`, a `Component` class with a type member)?
6. How large is a full mirror (all HTML elements of kyo-ui)?

## What was built

| Folder | Content |
| --- | --- |
| `gen/` | The generator. `Generate.scala` (rules, mirror writer, `@main gen.writeMirrors`, `@main gen.writeFullMirror`), `KyoUiApi.scala` (reads kyo-ui TASTy with tasty-query 1.9.0), `Inventory.scala` (`@main gen.inventory`, an API count). |
| `zio/generated/ZioUi.scala` | GENERATED ZIO mirror of a 9-element subset (Div, P, SpanElement, H1, Button, Input, Checkbox, Ul, Li). 487 lines. |
| `cats/generated/CatsUi.scala` | GENERATED Cats mirror of the same subset. 488 lines. |
| `full-zio/generated/ZioUiFull.scala` | GENERATED ZIO mirror of all 52 HTML elements of kyo-ui. 1116 lines. It replaces `zio/generated` (same package). |
| `zio/src/` | Hand-written ZIO runtime of the mirror: `SignalRef` (one-cell ref), `Bridge` (effect points, state), `Hooks` (`useSignal`, `useStream`, `Component`), `AmbientZIOs` (vendored from tui4s-zio: ZIO on the caller's runtime), `Headless` (test runner facade). |
| `cats/src/` | Hand-written Cats runtime: `Bridge` (effect points, `KyoSignallingRef`), `Hooks`, `CatsBridge` (vendored from tui4s-cats, derived from kyo-cats RC5), `AmbientDispatcher` (the caller's `Dispatcher[F]` in a Kyo `Local`), `Headless`. |
| `shared/kyo/tui4sproto/Headless.scala` | A headless kyo-ui runner for tests, in package `kyo`. It is a stand-in for the terminal runner (see Design). |
| `shared-test/SurfaceLeaks.test.scala` | A TASTy check: it lists each public signature of a package that names a type of package `kyo`. |
| `zio/apps/`, `cats/apps/` | Counter (hooks style and `Component` style, two counters) and Todo, in the mirror only. |
| `full-zio/apps/Settings.scala` | A settings form over elements that only the full mirror has (form, label, textarea, range, select, file input, table, img, ...). |
| `zio/test/`, `cats/test/`, `full-zio/test/` | `ZioFacadeSuite` (9 tests), `ZioStressSuite` (2), `CatsFacadeSuite` (9), `CatsStressSuite` (2), `FullMirrorSuite` (2). |
| `probes/`, `kyo-smoke/` | Desktop probes: overload resolution against kyo-ui (`probes.runProbes`), guard messages (`probes.errors.errorProbe`); a Kyo-native Counter on the headless runner (`smoke.Smoke`). |
| `logs/` | Desktop logs from branch `wip/handoff` (HISTORY): `zio-test.txt`, `cats-test.txt`, `generate.txt`, `generate-full.txt`, `full-zio-compile.txt`, `inventory.*`, `probes.txt`, `error-probe.txt`, `into-probe.txt`, `kyo-smoke.txt`. All other logs are from this machine, 2026-10-03: the resume agent (`*-2026-10-03.txt`, `*-before-fix-*`, `*-after-fix-*`, `*-consecutive-*`, `*-final-*`, `*-rules-*`, `generate-drift-check.txt`, `generate-signalref.txt`), the skeptic (`skeptic-*`) and the fixer (`fixer-*`). |

## How to run

Run each command in `.work/proto/kyoui-facades/`. Run only one at a time. The folders `full-zio`, `kyo-smoke` and
the tests are NOT self-contained: give all the input folders on the command line, as below.

| What | Command | Seen on this machine |
| --- | --- | --- |
| ZIO tests | `scala-cli test --jvm system zio shared shared-test` | VERIFIED: exit 0, 3 of 3 runs (`logs/fixer-zio-test-{1,2,3}.txt`) |
| Cats tests | `scala-cli test --jvm system cats shared shared-test` | VERIFIED: exit 0, 3 of 3 runs (`logs/fixer-cats-test-{1,2,3}.txt`) |
| Full mirror test | `scala-cli test --jvm system full-zio zio/src shared shared-test` | VERIFIED: exit 0, 3 of 3 runs (`logs/fixer-full-zio-test-{1,2,3}.txt`) |
| Full mirror compile | `scala-cli compile --jvm system full-zio zio/src shared` | VERIFIED by the skeptic (`logs/skeptic-full-zio-compile-2.txt`). `full-zio` alone FAILS (`Not found: type SignalRef`). Give `zio/src`, not `zio`: `zio/generated` holds the subset mirror in the same package. |
| Kyo-native smoke | `scala-cli run --jvm system kyo-smoke shared --main-class smoke.Smoke` | VERIFIED: exit 0 (`logs/fixer-kyo-smoke.txt`) |
| Regenerate the subset mirrors | `scala-cli run --jvm system gen --main-class gen.writeMirrors -- <outDir>` | VERIFIED: exit 0, output byte-identical to `zio/generated` and `cats/generated` (`logs/fixer-generate.txt`). The code writes to `<outDir>/zio/generated/` and `<outDir>/cats/generated/`, so `.` overwrites the mirrors in place (from the code; I did not run it with `.`). |
| Regenerate the full mirror | `scala-cli run --jvm system gen --main-class gen.writeFullMirror -- <outDir>` | VERIFIED: exit 0, byte-identical (`logs/fixer-generate-full.txt`) |
| API inventory | `scala-cli run --jvm system gen --main-class gen.inventory` | HISTORY only (`logs/inventory.txt`, desktop). Not re-run here. |
| Probes | `scala-cli run --jvm system probes --main-class probes.runProbes` and `... --main-class probes.errors.errorProbe` | VERIFIED: exit 0 (`logs/fixer-probes.txt`, `logs/fixer-error-probe.txt`) |

The main classes are in package `gen` (or `probes`, `smoke`). `--main-class writeMirrors` without the package fails
with `ClassNotFoundException`.

## User-facing API

ZIO, hooks style and `Component` style (`zio/apps/Counter.scala`, an excerpt with the comments removed). There is no Kyo import and no Kyo type:

```scala
import tui4s.interop.zio.ui.*
import zio.*

object Counter:
    def apply(label: String, key: String): ZIO[Scope, Nothing, HtmlNode] =
        for count <- useSignal(0)
        yield div(
            h1(label),
            count.render(n => p(s"Count: $n")),
            button("-").id(s"$key-dec").onClick(count.update(_ - 1)),
            button("+").id(s"$key-inc").onClick(count.update(_ + 1))
        )

object CounterComponent extends Component:
    type State = Int
    def initial = 0
    def view(count: SignalRef[Int]): HtmlNode =
        div(
            h1("Component counter"),
            count.render(n => p(s"Count: $n")),
            button("+").id("c-inc").onClick(count.update(_ + 1)),
            button("reset").id("c-reset").onClick(count.set(0))
        )
```

ZIO Todo, the two-way bound input and the keyed list (`zio/apps/Todo.scala`, an excerpt):

```scala
input.id("new").placeholder("What needs doing?").value(draft)          // draft: SignalRef[String]
    .onKeyDown(e => ZIO.when(e.key == Keyboard.Enter)(add)),
button("Add").id("add").onClick(add),                                  // add: UIO[Unit] reads draft.get
ul(
    todos.signal.foreachKeyed(_.id.toString)(t =>
        li(
            checkbox.id(s"done-${t.id}").checked(t.done).onChange(d => toggle(t.id, d)),
            span(if t.done then s"~${t.title}~" else t.title),
            button("x").id(s"del-${t.id}").onClick(todos.update(_.filterNot(_.id == t.id)))
        )
    )
),
when(todos.signal.map(_.exists(_.done)))(button("Clear done").id("clear").onClick(todos.update(_.filterNot(_.done))))
```

Cats Effect, tagless final (`cats/apps/Counter.scala`, an excerpt with the comments removed). The state is a real `fs2.concurrent.SignallingRef`:

```scala
import cats.effect.*
import fs2.concurrent.SignallingRef
import tui4s.interop.cats.ui.*

object Counter:
    def apply[F[_]: Async](label: String, key: String): Resource[F, HtmlNode] =
        for count <- useSignal[F](0)
        yield div(
            h1(label),
            count.render(n => p(s"Count: $n")),
            button("-").id(s"$key-dec").onClick(count.update(_ - 1)),
            button("+").id(s"$key-inc").onClick(count.update(_ + 1))
        )

final class CounterComponent[F[_]: Async] extends Component[F]:
    type State = Int
    def initial = 0
    def view(count: SignallingRef[F, Int]): HtmlNode = ...
```

A mistake gives a tui4s message at compile time (VERIFIED, `logs/fixer-zio-test-1.txt`, `logs/fixer-cats-test-1.txt`):

```text
button("x").onClick(println("hi"))                 // ZIO and Cats
button("x").onClick(ZIO.attempt(println("hi")))    // ZIO: the ZIO can fail
button("x").onClick(kyo.Sync.defer(println("hi"))) // ZIO and Cats: a Kyo effect
button("x").onClick(IO.pure(1))                    // Cats: not F[Unit]
error: tui4s: `onClick` takes a ZIO[Any, Nothing, Any] (or event => ZIO[Any, Nothing, Any]). Handle the errors of a
       ZIO that can fail (orDie, catchAll); a value of another type would never run.
error: tui4s: `onClick` takes an F[Unit] (or event => F[Unit]) for an F with a cats.effect.Async; use .void for an
       F[A]; a value of another type would never run.
```

Two other mistakes give a normal compiler error (no tui4s text): `val b: kyo.UI.Ast.Button = button("x")` gives
"Found: tui4s.interop.zio.ui.Button, Required: kyo.UI.Ast.Button", and Cats `onClick(Option(()))` gives "No given
instance of type cats.effect.kernel.Async[Option]".

Tests use the headless runner (a stand-in for the terminal runner):

```scala
for
    h <- Headless.mount(TodoApp.app)                // ZIO[Scope, Nothing, Headless]
    _ <- h.input("new", "Buy milk") *> h.click("add")
    t <- h.text                                     // "Todos\n[](Add)[ ]Buy milk(x)\n1 left"
yield t
```

## Design

### The mirror (generated)

- The generator reads the TASTy of kyo-ui RC7 with tasty-query. It finds the element classes, their kyo-ui traits,
  the public setters of each class and trait, and the factories of `kyo.UI`.
- Each element and each trait becomes an OPAQUE type with the kyo-ui subtype relation as its upper bound, for example
  `opaque type Button <: Inline & Interactive & Focusable & HasDisabled & Activatable & Clickable = kyo.UI.Ast.Button`.
  Reason: Scala picks an applicable MEMBER before an extension method (probe P1a to P1c). kyo-ui's member
  `onClick(=> Any < Async)` accepts any value. An opaque type hides all kyo-ui members, so only the mirror's
  extension methods are in scope.
- Each setter becomes ONE extension method on its declaring trait (`extension [E <: Interactive](e: E)`), not one per
  element. It forwards to kyo-ui with a cast back to `E`.
- A small table of rules translates each parameter type (`Rule` in `gen/Generate.scala`): `Plain`, `Data` (a kyo-ui
  data type, aliased), `Action` (`=> Any < Async`), `Handler` (`X => Any < Async`), `Sig` (`kyo.Signal[X]`), `Ref`
  (`kyo.SignalRef[X]`), `Children`, `Pairs`, `Repeated` (varargs), `Pure` (a pure function). The ecosystem object
  (`Generate.Zio`, `Generate.Cats`) decides only the effect points. A setter that no rule covers is left out and
  listed in the generator output.
- For each setter with an effect point, the generator also writes an `inline` overload that calls
  `scala.compiletime.error` with the tui4s message. It catches every argument that is not a ZIO / `F[Unit]`.
- Factories take a tui4s `Here` (an opaque `kyo.Frame`, made at the call site), so kyo-ui keeps the user's source
  position in the node (probe P3a, P3b).
- kyo-ui data types (`MouseEvent`, `KeyboardEvent`, `WheelEvent`, `Keyboard`, `Modifiers`, `Style`, `InputFilter`,
  `Length`, and in the full mirror `Href`, `Target`, `ImgSrc`, `FileAccept`, `ImageExt`) are type ALIASES. See O2.
- The reactive part (`Signal`, `render`, `foreach`, `foreachKeyed`, `when`, `empty`, `Child`) is a hand-written
  template inside the generator, the same for each ecosystem.

### Effect points

- ZIO: `Bridge.action(z)` is `AmbientZIOs.get(z)`. `AmbientZIOs.run` keeps the caller's `zio.Runtime` in an
  inheritable Kyo `Local`; `get` forks the ZIO on that runtime. Thus a handler sees the FiberRefs and the executor of
  the fiber that mounted the UI, not `Runtime.default`.
- Cats: `Bridge.action(fa)` runs `fa` through the ambient `Dispatcher[F]` (a Kyo `Local` that `Headless.mount`
  sets). The Dispatcher comes from the caller's runtime (`Dispatcher.parallel[F](await = false)`).
- kyo-ui runs a handler inline in its dispatch. The ZIO / `F` ends before the dispatch returns.

### State (one source of truth)

- ZIO: `SignalRef[A]` (`zio/src/SignalRef.scala`) is a tui4s type with the methods of a `zio.Ref` and a `changes`
  stream. Its state IS the `kyo.Signal.SignalRef` cell that kyo-ui binds. ZIO seals `Ref` and `SubscriptionRef`
  (skeptic probe P3), so the facade cannot implement them. `modify` is a compare-and-set loop on the cell.
- Cats: `KyoSignallingRef[F, A]` (`cats/src/Bridge.scala`) EXTENDS `fs2.concurrent.SignallingRef[F, A]` (an open
  abstract class) on the same kind of Kyo cell.
- `useStream` makes a read-only Kyo signal that follows a `ZStream` / fs2 `Stream` for the lifetime of the scope.

### Component styles

- Hooks: a component is a `ZIO[Scope, Nothing, HtmlNode]` or a `Resource[F, HtmlNode]`. `useSignal` allocates state.
- Class: `abstract class Component { type State; def initial: State; def view(state: SignalRef[State]): HtmlNode }`
  (Cats: `Component[F]` with `SignallingRef[F, State]`). `mount` allocates the state and calls `view`.

### Headless test runner

- `shared/kyo/tui4sproto/Headless.scala` mounts the UI ONCE with kyo-ui's own `ReactiveUI.normalize` and
  `ReactiveUI.subscribe` (the same calls as `UI.runRender`). It sends events through kyo-ui's own dispatcher
  (`Subscription.handle`). It renders the screen with kyo-ui's `HtmlRenderer` and reduces the HTML to text.
- These kyo-ui APIs are `private[kyo]`, so the file is in package `kyo`. The terminal runner
  (`.work/proto/kyoui-runner/`) does not use them; it has its own dispatcher outside package `kyo`.
- ONE runner fiber dispatches all events, in order, like a terminal runner.

## Results

| # | Question / claim | Status | Evidence on this machine |
| --- | --- | --- | --- |
| 1 | The generator writes the mirrors from the TASTy of kyo-ui RC7 | VERIFIED | `logs/fixer-generate.txt`, `logs/fixer-generate-full.txt`; byte-identical to the files in the folder |
| 2 | The lift trap is real without the mirror | VERIFIED | Tests "the lift trap is real WITHOUT the mirror" (ZIO, Cats); `logs/fixer-probes.txt` P1d, P4a, P4b (raw kyo-ui accepts a ZIO and a Unit with no error) |
| 3 | With the mirror, the trap does not compile, with a tui4s message | VERIFIED | Guard tests; messages in "User-facing API" |
| 4 | Handlers run on the caller's runtime | VERIFIED | `[zio runtime] seen: caller-42 on caller-zio-pool`; `[cats runtime] seen: caller-42 on caller-ce-pool` |
| 5 | One source of truth (the facade ref is the cell that kyo-ui binds) | VERIFIED | Tests "one source of truth ..." (a fiber outside a handler, a stream, `changes` / `discrete` of a ref that kyo-ui writes) |
| 6 | Two-way binding under fast typing: the handler reads the last typed value | VERIFIED | Test "two-way binding under fast typing" (20 rounds); stress suites: 0 of 150 failures, 0 of 150 wrong items, in each of 3 runs per ecosystem |
| 7 | Hooks style and `Component` style both work | VERIFIED | Counter tests (two hooks counters and one `Component` counter on one screen) |
| 8 | The mirror builds the same kyo-ui AST as kyo-ui | VERIFIED, with a limit | Test "the mirror builds the same kyo-ui AST" compares a tree with a `style(f)` and a `Length`, but with no handler and no signal (a handler is a closure, and two closure objects are not equal) |
| 9 | The full mirror covers all HTML elements and a real form runs | VERIFIED | 52 elements; `saved: Ann\|Hi\|50\|true\|Dark` in `logs/fixer-full-zio-test-1.txt` |
| 10 | ZIO / `F[_]` types at EVERY effect point | NOT YET | 9 setters skipped (O1) |
| 11 | Facade users see no Kyo type | PARTLY | No Kyo EFFECT type in the facade signatures (surface tests). Kyo DATA types are reachable through the aliases (O2) |
| 12 | Cats binding setters accept only a `useSignal` ref | NO (run time only) | O3 |

## Measurements

All on this machine (4 CPUs, shared with one other agent), incremental builds (Bloop cache).

| Item | Value | Log |
| --- | --- | --- |
| Subset mirror | 9 elements, 12 traits, 9 factories, 68 of 77 setters, 28 with an effect point, 487 lines (ZIO) / 488 (Cats) | `logs/fixer-generate.txt` |
| Full mirror | 52 elements, 13 traits, 164 of 173 setters, 56 with an effect point, 1116 lines | `logs/fixer-generate-full.txt` |
| Size without trait-level forwarders (HISTORY) | 4563 (element, setter) pairs over 99 element classes, SVG included | `logs/inventory.txt` (desktop) |
| Hand-written runtime | 768 lines in `zio/src` + `cats/src` (of which 344 lines are the vendored `AmbientZIOs` and `CatsBridge`) | `wc -l` |
| ZIO tests, wall time of `scala-cli test` | 7.7 s, 7.7 s, 7.7 s (ZioFacadeSuite 2.8 to 3.0 s, ZioStressSuite 1.8 to 2.0 s) | `logs/fixer-zio-test-{1,2,3}.txt` |
| Cats tests, wall time | 9.8 s, 9.2 s, 9.2 s (CatsFacadeSuite 3.2 to 3.3 s, CatsStressSuite 2.5 to 2.7 s) | `logs/fixer-cats-test-{1,2,3}.txt` |
| Full mirror test, wall time | 7.7 s, 5.8 s, 5.0 s (FullMirrorSuite 2.0 to 3.0 s) | `logs/fixer-full-zio-test-{1,2,3}.txt` |
| Stress (each run) | ZIO and Cats: "input then click add" 0 of 150 failures; "type char by char then Enter" 150 of 150 items, 0 wrong | same logs |
| Surface of the full mirror | 27 entries name a Kyo type; 0 outside the aliased data types | `logs/fixer-full-zio-test-1.txt` |

## Findings

- F1. The lift trap is real. kyo-ui's member `onClick(=> Any < Async)` accepts a ZIO, an IO or a `Unit`, with no
  type error and no warning (P4a, P4b). kyo-ui calls a handler with `handler.get.unit`, so a ZIO value is only
  discarded (skeptic C1, from the kyo-ui source).
- F2. An extension method cannot shadow an applicable member (P1a to P1c). Thus a mirror of extension methods on the
  kyo-ui types themselves does NOT stop the trap. The mirror needs opaque types that hide the kyo-ui members.
- F3. Trait-level forwarders keep the mirror small: 164 generated setters (of 173) for 52 elements (1116 lines), not
  one forwarder per (element, setter) pair.
- F4. ZIO seals `Ref` and `SubscriptionRef` (skeptic probe P3). A ZIO facade needs its own ref type. fs2's
  `SignallingRef` is open, so the Cats facade can give users the type they know.
- F5. Two cells race. The first ZIO version kept a `zio.stream.SubscriptionRef`, a Kyo ref, two pump fibers and a
  registry. A handler could read a stale draft: 2 of 150 failures and 4 wrong items (`logs/zio-test-before-fix-1.txt`,
  this machine). After the change to one cell: 0 failures in every run since. This race is the most likely cause of
  the "ZIO facade FLAKY" baseline in HANDOFF.md (not proven: the baseline runs have no log in this folder).
- F6. `kyo.Signal.SignalRef.Unsafe.compareAndSet` compares references (it calls
  `AtomicReference.compareAndSet`). The CAS loop of `modify` reads `prev` and compares the same reference, so it is
  correct (skeptic C5).
- F7. kyo-ui runs a handler inline in its dispatch, with no fork. When the dispatch returns, the handler is done.
  An error or a panic of a handler is caught by kyo-ui (`safeDispatch`) and logged; bubbling continues (skeptic C9).
- F8. `Dispatcher.parallel(await = true)` of cats-effect 3.7.1 can lose a cancel. The skeptic re-ran
  `.work/scratch/kyo-rc7/dbg/PureDispatcher.scala` here: `await=true` 3 fails in 11 rounds, `await=false` 0 in 1000
  (`logs/skeptic-rerun-pure-dispatcher.txt`). Thus `Headless.mount` uses `await = false`.

## Open items

- O1. Drag and drop has no ZIO or Cats form. The generator skips 9 setters ("no rule"): `dragSource(kyo.Drag.Source)`,
  `dropTarget(kyo.Drag.Target)`, `onDragEnd(kyo.Drag.End => Any < Async)`, `onDragEnter`, `onDragLeave`,
  `onDragOver`, `onDragStart` (`kyo.Drag.Event => Any < Async`), `onDrop(kyo.Drag.Event => kyo.Drag.Decision < Async)`,
  `onSortMove(kyo.Drag.Move => kyo.Drag.Decision < Async)`. The mirror has only the action forms (`onDragEnd(action)`).
  Thus the claim "ZIO / F[_] types at EVERY effect point" is NOT yet true. Fix: aliases for the `kyo.Drag.*` data
  types, a handler rule for a result that is not `Any` (`X => ZIO[Any, Nothing, Decision]`), and rules for the
  `Drag.Source` / `Drag.Target` values (they hold handlers, so they need their own mirror).
- O2. Kyo DATA types are reachable through the aliases. The facade signatures name no Kyo type outside the aliases
  (surface tests). But the aliased types have Kyo members: `MouseEvent.targetId`, `KeyboardEvent.targetId`,
  `WheelEvent.targetId`, `Keyboard.charValue`, `Style.Color.hex`, `Length.resolveOrAuto` give `kyo.Maybe`;
  `Style.props` gives `kyo.Chunk`; `Style.find` / `without` need a `kyo.ConcreteTag`; `Modifiers.derived$Schema` gives
  a `kyo.Schema`. The skeptic's probe P1 compiles `val leak: kyo.Maybe[String] = MouseEvent(...).targetId` with no
  Kyo import. No Kyo EFFECT type leaks. Thus DECISIONS ("must NOT see Kyo types") is met only for effect types.
  Fix: generate mirror data types (for example `targetId: Option[String]`), or extension accessors and a rule that
  forbids the Kyo members. See Q1.
- O3. The Cats binding setters (`value`, `checked`, `render`, `signal`, a text child) take any
  `fs2.concurrent.SignallingRef[F, A]`. A ref that `useSignal` did not make compiles, and then fails at run time:
  `IllegalArgumentException: this SignallingRef was not made by tui4s useSignal` (skeptic probe P2). The ZIO side has
  no such gap (its own `SignalRef` type). Fix: a tui4s subtype (for example `UiSignallingRef[F, A] extends
  SignallingRef[F, A]`) in the setter signatures. See Q3.
- O4. The headless test runner drops a failed event with no message. `dispatch` in
  `shared/kyo/tui4sproto/Headless.scala` wraps the work in `.handle(Abort.run[Nothing])` and completes the promise in
  all cases. Thus a click on a missing id (and a panic in the dispatch) returns normally (skeptic probe P4). No
  current test passes only because of this: each test checks the screen after the event. Fix: complete the promise
  with the result, and fail `send` on a panic. This is test-harness code; the terminal runner has its own dispatch.
- O5. Stale texts in the vendored bridges:
  - `cats/src/AmbientDispatcher.scala`: the error message names `tui4s.interop.cats.Tui.run`, `Tui.resource` and
    `TuiApp`. None of these exists in this prototype (they exist in branch `feat/cats` of the old design). The
    prototype entry point is `Headless.mount`.
  - `zio/src/AmbientZIOs.scala`: the header says "moved ... into `tui4s.interop.zio.internal`". The real package is
    `tui4s.interop.zio.ui.internal`. The doc comment cites "SPEC 4.2, F11, F12" and "the terminal is restored"; these
    belong to the old design.
  - `cats/src/CatsBridge.scala` line 176: "the started effect leaked, VERIFIED by a hanging test". That test is in
    branch `feat/cats` (`modules/cats/src/test/scala/tui4s/interop/cats/internal/CatsBridgeSuite.scala`, "stress:
    interrupt right after the IO starts never leaks the IO (300 rounds, IORuntime)"). It is NOT in this prototype.
    I did not run it on this machine. Status here: UNVERIFIED (vendored text).
- O6. `full-zio` and `kyo-smoke` are not self-contained folders. Use the commands in "How to run".
- O7. ZIO `SignalRef.changes` merges changes that come faster than the consumer (it gives the latest value; a value
  can repeat). This is less than `SubscriptionRef.changes`. An interrupted wait leaves its callback on the Kyo
  promise until the next change (then it is a no-op).
- O8. The facades are checked on the headless runner only. The run on the terminal runner is the work of
  `.work/proto/kyoui-integration/` (not this prototype).

## Open questions for the user

- Q1. Event and style data: accept Kyo data types through the aliases (`kyo.Maybe` in `targetId`), or generate
  mirror data types with Scala types (`Option`)? The second meets DECISIONS fully, but it adds generated code and a
  conversion per event.
- Q2. Drag and drop: give it a ZIO / Cats form now (O1), or leave it out of the terminal backend (a terminal has
  little use for drag and drop)?
- Q3. Cats: keep `fs2.concurrent.SignallingRef` in the setter signatures (a type users know, a run-time check), or a
  tui4s subtype (a compile-time check)?

## What carries into the PRs

- The generator (`gen/`) and its rule table. Run it in the build (or keep the output in the repo with a drift check,
  like the `cmp` above).
- The opaque-type design and the `compiletime.error` guard overloads.
- `SignalRef` (ZIO) and `KyoSignallingRef` (Cats): one cell, no pump fiber.
- `AmbientZIOs` and `CatsBridge` / `AmbientDispatcher` (already in branches `feat/zio` and `feat/cats`), with the
  stale texts of O5 fixed.
- The surface test (`shared-test/SurfaceLeaks.test.scala`) as a CI check, with the alias list.
- The stress suites, as a guard against a second cell.

## History

- 2026-10-02 (desktop, macOS, HISTORY): the first agent built the generator, the subset mirrors, the full mirror, the shared
  headless runner, the ZIO and Cats bridges, the apps and the facade suites (desktop logs `zio-test.txt`, `cats-test.txt` from
  branch `wip/handoff`: 9 of 9 each). The run was stopped at the user's request. That agent wrote no report.
- 2026-10-03 (cloud, before the resume): Cats 9 of 9; ZIO FLAKY (HANDOFF.md).
- 2026-10-03 (cloud, resume agent, logs from 14:19 to 14:34): added the stress suites, found the race (F5), replaced
  the ZIO state by the one-cell `SignalRef` (generator rule changed too), tightened the surface test (Kyo names, not
  member names), added the `Repeated` and `Pure` rules, the full-mirror app and `FullMirrorSuite`. That agent also
  wrote no report.
- 2026-10-03 (skeptic): verification below. Verdict: code green; report missing (blocking).
- 2026-10-03 (fixer, this agent): wrote this report; re-ran the tests; see "Fixes after verification".

<!-- claude 2026-10-03: created this file. The original REPORT.md of the prototype did not exist on this machine. -->
## Independent verification (2026-10-03)

Skeptic agent. Machine: Linux container, 4 CPUs, shared with one other agent. Date: 2026-10-03.

### Step 1: read REPORT.md (FAILED)

- At 14:38 UTC, the file `.work/proto/kyoui-facades/REPORT.md` did not exist.
- The file is also not in branch `origin/wip/handoff` (`git ls-tree -r origin/wip/handoff -- .work/proto/kyoui-facades` shows no REPORT.md).
- A search of the file system for `*.md` files changed after 13:50 UTC found only `proto/kyoui-runner/REPORT.md` and `HANDOFF.md`.
- Thus the prototype agent wrote no report. Its claims are not on disk. I can only check the code, the logs and the claims in code comments.
- I created this file only to hold this section. I did not write a report for the prototype agent.

### Step 2a: ZIO tests, five runs (about 14:40 UTC)

Command: `cd .work/proto/kyoui-facades && scala-cli test --jvm system zio shared shared-test`.
Logs: `logs/skeptic-zio-test-1.txt` to `logs/skeptic-zio-test-5.txt`.

| Run | Exit | ZioFacadeSuite | ZioStressSuite | Wall time |
| --- | --- | --- | --- | --- |
| 1 | 0 | 9 of 9 pass | 2 of 2 pass | 8.0 s |
| 2 | 0 | 9 of 9 pass | 2 of 2 pass | 8.2 s |
| 3 | 0 | 9 of 9 pass | 2 of 2 pass | 7.8 s |
| 4 | 0 | 9 of 9 pass | 2 of 2 pass | 8.0 s |
| 5 | 0 | 9 of 9 pass | 2 of 2 pass | 8.0 s |

Result: CONFIRMED green, 5 of 5. The builds were incremental (Bloop cache). No run recompiled from zero.
Note: only 2 suites ran. `shared-test/SurfaceLeaks.test.scala` printed `[zio surface] ... names kyo.*` lines, but it is not a separate suite in the output. See step 3.

### Step 2b: Cats tests, five runs

Command: `cd .work/proto/kyoui-facades && scala-cli test --jvm system cats shared shared-test`.
Logs: `logs/skeptic-cats-test-1.txt` to `logs/skeptic-cats-test-5.txt`.

| Run | Exit | CatsFacadeSuite | CatsStressSuite | Wall time |
| --- | --- | --- | --- | --- |
| 1 | 0 | 9 of 9 pass | 2 of 2 pass | 9.5 s |
| 2 | 0 | 9 of 9 pass | 2 of 2 pass | 9.6 s |
| 3 | 0 | 9 of 9 pass | 2 of 2 pass | 9.6 s |
| 4 | 0 | 9 of 9 pass | 2 of 2 pass | 9.8 s |
| 5 | 0 | 9 of 9 pass | 2 of 2 pass | 9.4 s |

Result: CONFIRMED green, 5 of 5.

### Step 2c: full-zio compile

| Command | Exit | Log | Result |
| --- | --- | --- | --- |
| `scala-cli compile --jvm system full-zio` | 1 | `logs/skeptic-full-zio-compile-1.txt` | FAIL: `Not found: type SignalRef` (and more). The folder is not self-contained. |
| `scala-cli compile --jvm system full-zio zio/src shared` | 0 | `logs/skeptic-full-zio-compile-2.txt` | PASS |
| `scala-cli test --jvm system full-zio zio/src shared` | 1 | `logs/skeptic-full-zio-test-1.txt` | FAIL: `Not found: surface` (needs `shared-test`) |
| `scala-cli test --jvm system full-zio zio/src shared shared-test` | 0 | `logs/skeptic-full-zio-test-2.txt` | PASS: FullMirrorSuite 2 of 2 |

The other agent used the inputs `full-zio zio/src shared` (see `full-zio/.bsp/scala-cli.json`, argv). No file records this command for a reader. Thus `full-zio` compiles only with `zio/src` and `shared` on the command line. This is minor: the code compiles, but the folder needs a README line or a `//> using file` directive.

### Step 3a: generated mirrors are the output of the generator (CONFIRMED)

- I ran `scala-cli run --jvm system gen --main-class gen.writeMirrors -- <scratch>` and `... gen.writeFullMirror -- <scratch>`. Both exit 0. Logs: `logs/skeptic-generate.txt`, `logs/skeptic-generate-full.txt`.
- `cmp` shows that `zio/generated/ZioUi.scala`, `cats/generated/CatsUi.scala` and `full-zio/generated/ZioUiFull.scala` are byte-identical to the new output. There is no hand edit and no drift.
- Note: `--main-class writeMirrors` fails (`ClassNotFoundException`). The main classes are in package `gen` (`gen.writeMirrors`, `gen.writeFullMirror`, `gen.inventory`).
- Generator summary on this machine: subset mirror 9 elements, 68 of 77 setters, 28 with an effect point. Full mirror 52 elements, 164 of 173 setters, 56 with an effect point.
- The generator SKIPS 9 setters ("no rule"): `dragSource`, `dropTarget`, `onDragEnd`, `onDragEnter`, `onDragLeave`, `onDragOver`, `onDragStart`, `onDrop`, `onSortMove` with `kyo.Drag.*` parameters. These are effect points of kyo-ui with no ZIO or Cats form. Thus "ZIO / F[_] types at EVERY effect point" (DECISIONS fifth round) is not yet true. The mirror has the action-only forms (`onDragEnd(action)`), but not the event forms. Severity: minor (the generator log says it).

### Step 3b: claims in code, tests and logs

Because there is no report, I checked the claims that the code comments, the test names and the logs make.

CONFIRMED (with evidence on this machine):

- C1. The lift trap is real without the mirror. kyo-ui's own `onClick(Any < Async)` accepts a ZIO or IO value and never runs it. Test "the lift trap is real WITHOUT the mirror" passes in all 10 runs (5 ZIO, 5 Cats). The kyo-ui source invokes a handler with `handler.get.unit` (`kyo-ui/kyo/internal/ReactiveUI.scala`, `invoke`), so a ZIO value is only discarded.
- C2. With the mirror imports, the trap does not compile. The guard test prints the intended tui4s message for `println(...)`, `ZIO.attempt(...)` (may fail), `IO.pure(1)` (not Unit) and `kyo.Sync.defer(...)`. A raw `kyo.UI.Ast.Button` type and the raw named setter `onClick(f = ...)` also do not compile. For Cats, `Option(())` fails with "No given instance of Async[Option]" (an error, but not the tui4s message).
- C3. Handlers run on the caller's runtime. ZIO: `seen: caller-42 on caller-zio-pool` (FiberRef and executor of the mount). Cats: `seen: caller-42 on caller-ce-pool` (IOLocal and compute pool of the mount). Both in all runs.
- C4. One source of truth (ZIO `SignalRef`, Cats `KyoSignallingRef`): the state is the `kyo.Signal.SignalRef` cell that kyo-ui binds. Code: `zio/src/SignalRef.scala`, `cats/src/Bridge.scala`. The "one source of truth" tests pass in all runs.
- C5. "`compareAndSet` compares references". The RC7 bytecode of `kyo.Signal$SignalRef$Unsafe.compareAndSet` calls `java.util.concurrent.atomic.AtomicReference.compareAndSet` (identity). It notifies only when `curr != next`. Thus the CAS loop in `modifyNow` is correct.
- C6. `changes` / `discrete` register the wait before they read the value, so no change is lost (a value can repeat). This agrees with `SignalRef.Unsafe`: `set` writes the value first, then swaps and completes the promise.
- C7. The race fix. `logs/zio-test-before-fix-1.txt` (14:19, this machine) shows the ZIO stress suite failing: 2 of 150 and 4 wrong items. After the change to one cell: 0 of 150 and 0 wrong in `zio-test-after-fix-1.txt`, in 10 logs of the other agent, and in my 5 runs (5 x 300 rounds, 0 failures).
- C8. The headless test runner uses kyo-ui's own mount path: `ReactiveUI.normalize(ui, Seq.empty)` then `ReactiveUI.subscribe(root, exchange)`, the same calls as `UI.runRender` (`kyo-ui/kyo/UI.scala` line 312-313). `ReactiveUI`, `HtmlRenderer` and `UIEvent` are `private[kyo]`, so the runner must be in package `kyo` (true in the source).
- C9. kyo-ui runs a handler inline in its dispatch (no fork), so `AmbientZIOs.get` returns only after the ZIO ended. A handler error or panic is caught by `safeDispatch` and logged with `Log.error`; bubbling continues.
- C10. The mirror builds the same kyo-ui AST as kyo-ui (test passes in all runs). Limit: the test compares a tree with no handler and no signal.
- C11. The apps (`zio/apps`, `cats/apps`, `full-zio/apps`) import only the mirror and zio / cats-effect / fs2. They have no `kyo` import and no Kyo type.
- C12. Full mirror: 52 elements = all 52 HTML element classes in `kyo-ui_3-1.0.0-RC7.jar` (`kyo/UI$Ast$*.class`). SVG nodes are not in the mirror.
- C13. Full mirror app: the settings form runs headless and ends with `saved: Ann|Hi|50|true|Dark` (ArrowDown on a select picks the next option; a click on the submit button fires `onSubmit` of the form).

### Step 3c: probes (my scratch copies of the sources; no prototype file changed)

Probe sources: `/tmp/claude-0/.../scratchpad/skeptic-probes/` (copies of `zio/src`, `zio/generated`, `cats/src`, `cats/generated`, `shared` plus one probe file each). Logs: `logs/skeptic-probe-*.txt`.

| Probe | Command | Result |
| --- | --- | --- |
| P1 Kyo type through an alias (ZIO) | `scala-cli run --jvm system . --main-class probe.ZioProbe` | exit 0. `val inferred = ev.targetId` has type `kyo.Maybe[String]`, and a handler `e => ZIO.succeed(e.targetId.getOrElse(...))` compiles with no `kyo` import. Output: `PROBE zio: targetId has type kyo.Maybe, value = btn`. |
| P2 user-made `SignallingRef` (Cats) | `scala-cli run --jvm system . --main-class probe.CatsProbe` | exit 0. `input.value(SignallingRef.of[IO, String](""))` COMPILES and then throws at run time: `IllegalArgumentException: this SignallingRef was not made by tui4s useSignal`. |
| P3 ZIO seals `Ref` and `SubscriptionRef` | `scala-cli compile --jvm system .` | exit 1, as expected: `Cannot extend sealed class Ref` and `Cannot extend sealed trait SubscriptionRef`. The claim in `zio/src/SignalRef.scala` is CONFIRMED. |
| P4 control for the lift-trap test, and a missing id | `scala-cli run --jvm system . --main-class tui4s.interop.zio.ui.ClickProbe` | exit 0. `raw kyo handler ran = true` (a raw-kyo button with a real Kyo handler is reached, so the lift-trap test is not vacuous). `click on a missing id returned normally = true`, with no message. |

Findings from the probes:

- R1 (minor). "The public API of the mirror names no Kyo type, except the aliases" is true for the facade signatures (surface test). But the aliased types have Kyo members: `MouseEvent.targetId`, `KeyboardEvent.targetId`, `WheelEvent.targetId`, `Keyboard.charValue`, `Style.Color.hex` give `kyo.Maybe`; `Style.props` gives `kyo.Chunk`; `Style.find` / `without` need `kyo.ConcreteTag`; `Modifiers.derived$Schema` gives `kyo.Schema`. The generator log lists them ("leak through alias"). Thus a ZIO or Cats user CAN see Kyo data types (not Kyo effect types). This is less than DECISIONS ("ZIO/Cats users must NOT see Kyo types").
- R2 (minor). The Cats binding setters (`value`, `checked`, `render`, text child) take any `fs2.concurrent.SignallingRef`. Only a ref from `useSignal` works; any other ref compiles and fails at run time. The ZIO side has no such gap: it uses its own `SignalRef` type.
- R3 (minor, test harness). `shared/kyo/tui4sproto/Headless.scala` `dispatch` wraps the work in `.handle(Abort.run[Nothing])` and then completes the promise. Thus a click on a missing id and a handler panic are dropped with no message. No current test passes only because of this (each test checks the screen after the event, and P4 shows the lift-trap path is reached), but a wrong id in a future test will not fail at the click.

### Step 3d: cold builds and re-runs of history evidence

- Cold builds (fresh copy with no `.scala-build`, in my scratch folder): ZIO tests exit 0 (9 + 2 pass, `logs/skeptic-zio-test-cold.txt`); Cats tests exit 0 (9 + 2 pass, `logs/skeptic-cats-test-cold.txt`); `full-zio zio/src shared` compile exit 0 (`logs/skeptic-full-zio-compile-cold.txt`). Thus the green results do not come from stale class files.
- `probes` (desktop log `logs/probes.txt`, `logs/error-probe.txt`): re-run here from a scratch copy, exit 0, same output (`logs/skeptic-rerun-probes.txt`, `logs/skeptic-rerun-error-probe.txt`). CONFIRMED: kyo-ui `Button.onClick(ZIO)` picks the member `onClick(=> Any < Async)` and gives no type error (P1c, P1d, P4a); `Here` carries the call-site Frame into the node (P3a, P3b).
- `kyo-smoke` (desktop log `logs/kyo-smoke.txt`): needs `shared` on the command line. Re-run here with `shared`: exit 0, output identical to the desktop log (`logs/skeptic-rerun-kyo-smoke.txt`).
- `logs/into-probe.txt` is a macOS log (`/private/tmp/claude-501/...`). I did not re-run it (the `into` probe source is not in the folder). Status: history, unverified here.
- `cats/src/CatsBridge.scala` says "Reproduced without Kyo: 3 hangs in 10 rounds ... await = false: 0 in 1000" (`Dispatcher.parallel(await = true)` loses a cancel). The only log was from the desktop. I re-ran `.work/scratch/kyo-rc7/dbg/PureDispatcher.scala` from a scratch copy: `await=true: rounds=11 fails=3`, `await=false: rounds=1000 fails=0` (`logs/skeptic-rerun-pure-dispatcher.txt`). CONFIRMED in kind (the program stops at 3 fails, so "3 in 10" and "3 in 11" are the same result).
- `cats/src/CatsBridge.scala` line 176 says the `Sync.ensure` region was "VERIFIED by a hanging test". No such test is in this prototype. Status: UNVERIFIED on this machine (minor; vendored text from earlier work).

### Step 3e: stale texts (minor)

- `cats/src/AmbientDispatcher.scala`: the error message tells the user to "run the app with tui4s.interop.cats.Tui.run, Tui.resource or TuiApp". None of these exists in the prototype (grep finds no `Tui` or `TuiApp`).
- `zio/src/AmbientZIOs.scala` header: "moved ... into `tui4s.interop.zio.internal`". The real package is `tui4s.interop.zio.ui.internal`. The doc also cites "SPEC 4.2, F11, F12" and "the terminal is restored", which belong to the old design.
- `full-zio` and `kyo-smoke` are not self-contained: they compile only with `zio/src shared` (and `shared-test` for the test) or `shared` on the command line. No file in the folder records these commands.

### Verdict

| Item | Status | Severity |
| --- | --- | --- |
| The prototype REPORT.md exists and holds its claims | REFUTED: the file did not exist on this machine and is not in `origin/wip/handoff` | blocking (the deliverable that HANDOFF.md names is missing; I could not check any "VERIFIED" mark, measurement or "User-facing API" code block, because there are none) |
| ZIO tests green (5 runs) | CONFIRMED 5 of 5, plus 1 cold run | - |
| Cats tests green (5 runs) | CONFIRMED 5 of 5, plus 1 cold run | - |
| full-zio compiles | CONFIRMED with `full-zio zio/src shared`; FAILS with `full-zio` alone | minor |
| Generated mirrors = generator output (TASTy of kyo-ui RC7) | CONFIRMED byte-identical | - |
| ZIO / F[_] at every effect point | REFUTED in part: 9 drag / sort setters have no rule | minor |
| Facade users see no Kyo type | REFUTED in part: Kyo data types (`kyo.Maybe`, `kyo.Chunk`, `kyo.ConcreteTag`, `kyo.Schema`) through the aliases; no Kyo effect type | minor |
| Cats binding needs a `useSignal` ref | Compile-time gap: any `SignallingRef` compiles, then throws | minor |
| Headless runner reports a missing id | No: silent | minor (test harness) |
| Lift trap real, guard works, caller runtime, one source of truth, race fixed, same AST, CAS by reference, ZIO seals Ref | CONFIRMED (C1 to C13, P3, P4) | - |

Flaky: no. 10 of 10 incremental runs and 2 of 2 cold runs were green.
Next step for the prototype owner: write the prototype's own report ABOVE this section (what was built, the user-facing code, the open items R1 to R3 and the 9 skipped setters). Do not change the code for this verdict.

<!-- claude 2026-10-03: created the section "Fixes after verification" (fixer agent) -->
## Fixes after verification (2026-10-03)

Fixer agent. Machine: the same Linux container (4 CPUs, shared with one other agent). I changed no code, no test and
no generated file. All the refuted items were in the report (missing or wrong text), not in the code. The skeptic's
section above is unchanged.

### What I fixed

| Item (skeptic) | Severity | What was wrong | Fix |
| --- | --- | --- | --- |
| The prototype REPORT.md exists and holds its claims | blocking | The report was missing. | I wrote the prototype report ABOVE the skeptic's section: goal, what was built, how to run, user-facing API (code blocks), design, results with VERIFIED / HISTORY marks, measurements, findings, open items O1 to O8, questions Q1 to Q3, history. Each VERIFIED mark names a log of this machine. |
| full-zio compiles with `scala-cli compile --jvm system full-zio` | minor | No file recorded the input folders. | "How to run" gives the exact commands: `full-zio zio/src shared` (compile), `full-zio zio/src shared shared-test` (test), `kyo-smoke shared` (smoke), and the package of each main class (`gen.writeMirrors`). Open item O6. |
| ZIO / F[_] types at EVERY effect point | minor | Not true: 9 drag and drop setters have no rule. | Result 10 says "NOT YET". Open item O1 lists the 9 setters and the fix. Question Q2. |
| ZIO/Cats facade users see no Kyo type | minor | Kyo DATA types are reachable through the aliases. | Result 11 says "PARTLY". Open item O2 lists the members (`kyo.Maybe`, `kyo.Chunk`, `kyo.ConcreteTag`, `kyo.Schema`). Question Q1. |
| Cats binding setters only bind refs made by useSignal | minor | Any `SignallingRef` compiles, then throws. | Result 12 says "NO (run time only)". Open item O3 with the fix. Question Q3. |
| Headless runner reports events on a missing id | minor | The dispatch drops the panic. | Open item O4 states the silent drop and the fix. |
| CatsBridge.scala line 176 "VERIFIED by a hanging test" | minor | No such test in the prototype. | Open item O5: the test is in branch `feat/cats` (`CatsBridgeSuite`, "stress: interrupt right after the IO starts never leaks the IO (300 rounds, IORuntime)"). I did not run it here. Status: UNVERIFIED on this machine. |
| Stale texts in the vendored bridges | minor | Wrong names in `AmbientDispatcher` and `AmbientZIOs`. | Open item O5 lists each stale text and the true name. I did not change the code (the task asks for report text for minor items). |

### Re-runs after the fix (this machine, 2026-10-03)

The fix changed only REPORT.md. I re-ran the tests anyway, to check each command that the report gives.

| Command | Runs | Result | Logs |
| --- | --- | --- | --- |
| `scala-cli test --jvm system zio shared shared-test` | 3 | 3 of 3 exit 0: ZioFacadeSuite 9 of 9, ZioStressSuite 2 of 2 (0 of 150 failures, 0 wrong items); 7.8 s, 7.7 s, 7.7 s | `logs/fixer-zio-test-{1,2,3}.txt` |
| `scala-cli test --jvm system cats shared shared-test` | 3 | 3 of 3 exit 0: CatsFacadeSuite 9 of 9, CatsStressSuite 2 of 2 (0 of 150 failures, 0 wrong items); 9.8 s, 9.2 s, 9.2 s | `logs/fixer-cats-test-{1,2,3}.txt` |
| `scala-cli test --jvm system full-zio zio/src shared shared-test` | 3 | 3 of 3 exit 0: FullMirrorSuite 2 of 2; 7.7 s, 5.8 s, 5.0 s | `logs/fixer-full-zio-test-{1,2,3}.txt` |
| `scala-cli run --jvm system kyo-smoke shared --main-class smoke.Smoke` | 1 | exit 0: `Count: 0`, then `Count: 2` after two clicks | `logs/fixer-kyo-smoke.txt` |
| `scala-cli run --jvm system gen --main-class gen.writeMirrors -- <scratch>` and `... gen.writeFullMirror -- <scratch>` | 1 each | exit 0; `cmp` shows the 3 mirrors byte-identical to the files in the folder | `logs/fixer-generate.txt`, `logs/fixer-generate-full.txt` |
| `scala-cli run --jvm system probes --main-class probes.runProbes` and `... probes.errors.errorProbe` | 1 each | exit 0; same P1a to P4b and E1 to E5 lines as the desktop logs | `logs/fixer-probes.txt`, `logs/fixer-error-probe.txt` |

Flaky: no. 9 of 9 test runs were green. All builds were incremental (Bloop cache); the skeptic's cold runs above
cover a build from zero.

### Not done

- I did not fix the minor items in the code (O1 to O5). Reason: the task asks for report text for minor items, and
  the skeptic asked for no code change for this verdict. The fixes are written in "Open items".
- I did not re-run `gen.inventory` (`logs/inventory.txt` stays HISTORY) or the `into` probe (its source is not in the
  folder).
- I did not run the `feat/cats` stress test that the "VERIFIED by a hanging test" text refers to.
