# Judgement: dx lens (declarative, simple, beautiful API; weight 35%)

Status: COMPLETE (see section 5 for scores). Reading notes first, then the judgement.

## Reading notes (raw, added while reading)

### elm (read in full)

- Counter (Kyo) is ~25 lines: `enum Msg`, `case class Model`, `object Counter extends TuiApp[Model, Msg]`
  with `keys`, `init`, `update`, `view`, `subscriptions`. No `import kyo.*`. The same text compiles for
  ZIO (`ZTuiApp`) and Cats (`IOTuiApp`): one import and one base class differ.
- `Next[Model, Msg] = Model | WithCmd` removes the `(m, Cmd.none)` noise. `m.withCmd(Cmd.quit)` reads well.
- `Keymap(Bind('+', "increment", Msg.Inc), ...)` drives routing AND `View.help(keys)`: one value, two uses.
- Todo: focus routing by `subscriptions(m)` with first-match-wins; `Sub.keys { case key => Msg.Edit(key) }`
  as catch-all after the keymap. `Cmd.attempt(TodoStore.load)(Msg.Loaded(_))`. "save then quit" = two msgs.
- Dashboard: `Sub.poll("load", 250.millis)(sample)(Msg.Sample(_))`, `Sub.stream("logs")(logLines)(Msg.Log(_))`.
  Pause = remove the sub from `subscriptions(m)`. Same code across 3 effect systems, only `sample`/`logLines` differ.
- Costs: string ids for subs (user's job, Elm gotcha); every async value needs a Msg case + update case;
  `Model`/`Msg` must be top-level (cyclic ref if nested in the object); `derives CanEqual` everywhere
  (strictEquality). The Kyo Dashboard has a footgun comment about `chunkSize = 1`.
- Kyo-free shared surface: uses `Option`, `Seq`, `java.time.Duration`. ZIO/Cats facades re-export.
  Open risk: `export` of top-level extension `withCmd` across packages (UNVERIFIED).

### components (read in full)

- Counter (Kyo) is the shortest of all: one `object Counter extends Component[Unit, Int]` with `init`
  and `build`, handlers inline: `.onKeys { case Key.Char('+') => update(_ + 1) ... case Key.Char('q') => Tui.quit }`.
  Needs `import kyo.*` (TuiApp extends KyoApp; `run(...)`).
- Help text is a hand-written string (`" + / - change   q quit "`), not derived from the bindings.
- Todo = 3 components + built-in TextField; parent wires typed outputs: `.onOutput { case TodoList.Out.Toggle(id) => ... }`.
  Repeated ceremony `X(X.Props(...))`: `TodoList(TodoList.Props(s.items, ...))`, `LogPane(LogPane.Props(p.logs, capacity = 500))`.
  Each component has its own `Props`/`State`/`Out` types.
- Kyo rows leak into user helpers: `withSelected(p)(f: Item => Unit < Emit[Out]): Unit < (Var[State] & Emit[Out])`
  with a comment that `Option.fold` breaks the row type. `current.map(st => ...)` vs `state` param of build
  (two ways to read state: build-time value vs latest; a known stale-closure trap that the doc must explain).
- Tab focus is free (unhandled Tab moves focus); focus regions route keys. `IndexedStack` keeps hidden tabs mounted.
- Dashboard: streams passed as `Props(samples: Source[Long], logs: Source[String]) derives CanEqual`; producer
  fiber + Channel + `Fiber.init` wired by hand in `DashboardApp.run`.
- ZIO/Cats facades: opaque `View`, `state.update(...)` returning `UIO`/`IO`, handlers are `ZIO`/`IO`.
  Good catch (worth grafting as a test): Kyo lifts any plain value into `A < S`, so a ZIO passed to a
  Kyo-typed handler `=> Any < R` compiles and NEVER runs. Facades hide the core View for this reason.
  Cost: ~60 forwarders per facade; runtime owner-check on `state`.
- Cats Dashboard is not fully shown ("identical to 4.4").
- Self-declared DX risk: inference of `R2` in `onKeys` PF literals, `Ui = BuildContext ?=> View[Local]`
  (writing `def build(...) =` without `: Ui` gives a confusing error).

### signals (read in full)

- Counter (Kyo) ~15 lines: one `view(tui, count)` with `count.view(n => text(...))` binding and a root
  `.onKey { case Key.Char('+') => count.update(_ + 1) ... }`, plus
  `val app: Tui => View < (Async & Scope) = t => Signal.initRef(0).map(view(t, _))` and `tui(app)`.
  Kyo users see `View < (Async & Scope)` in the app signature (acceptable for Kyo users).
- ZIO Counter: `SignalRef.make(0).map { count => column(...).onKey { ... } }` in `def app(tui: Tui): ZIO[Scope, Throwable, View]`.
  Cats Counter: `Resource.eval(SignalRef[IO].of(0)).map { count => ... }`. Very idiomatic for each ecosystem:
  ZIO users recognise `SubscriptionRef`-like refs, Cats users `Ref[IO].of`. Needs `import tui4s.zio.{*, given}`.
- `IntoAction[-R]` typeclass with `@implicitNotFound` naming the fix: avoids the "ZIO lifted into `A < S` and never
  run" trap via type-class dispatch; forgetting `given` import gives a readable error.
- Todo: one `Model` ref + a `draft: SignalRef[TextField]` + `Focus[Pane]`; handlers like
  `draft.getAndSet(TextField.empty).map(f => model.update(_.add(f.text)))`; `.boxed(" Todos ", active = focus.is(Pane.List))`.
  Tab focus cycling is free. Help text is a hand-written string.
- Dashboard: refs created in setup with `Kyo.zip(Signal.initRef(0), Signal.initRef(Metrics.empty), ...)`, ticker via
  `Tui.every(250.millis)(...)`, stream via `Fiber.init(logSource.foreach(line => logs.update(...)))`. Pausing would need
  a fiber handle or a flag ref (not shown). Structural binding `tab.view { case 0 => ...; case _ => ... }` is nice.
- User code imports jatatui types directly (`import jatatui.core.style.Style`, `Line.styled(..., Style.empty().dim().crossedOut())`):
  Java-flavoured, less beautiful than elm's opaque `Style`/`Line` + String extensions.
- DX knobs the user must learn: "bind at the leaves" (a root binding silently degrades to full repaint),
  `ref.at(_.x)` vs `ref.map(f)` (the second gets a 1 s repair-timer watcher and up to 1 s latency in a race; same
  call site look), "values that change together in one ref" (torn frames otherwise, nothing enforces it).
- Self-declared: inference of `R` in `onKey[R](pf)(using IntoAction[R])` with Kyo opaque `<` UNVERIFIED.

### free (read in full)

- Counter is the shortest of all four (13 non-blank lines) and byte-identical for Kyo, ZIO and Cats (only the import changes):
  `def update(n: Int, event: Event): Next[Int] = event match { case Key.Char('+') => n + 1 ... case Key.Char('q') => Quit; case _ => n }`.
  No Msg enum, no Cmd, no Sub, no effect type in `init`/`view`/`update`/`sources`.
- Todo: `(t.focus, event) match` with model methods (`t.add`, `t.toggle`, `t.typed(e)`); focus and Tab are manual;
  help text hand-written.
- Dashboard: `sources(d)` is a pure function of state:
  `val cpu = source.poll(250.millis)(Random.nextDouble)(_ sample _).key("cpu"); if d.paused then Seq(logs) else Seq(logs, cpu)`.
  Same text for ZIO (`zio.Random`, `ZStream`) and Cats (`IO`, `fs2.Stream`).
- `View = jatatui Widget`: jatatui builders chain (`text(...).centered`), tui4s extensions (`.size`, `.bordered`). Two vocabularies.
- Self-declared gaps: no one-shot command (W2: "on Ctrl-S write the file" needs a flag + request counter + keyed task);
  `: Next[S]` must be written; `_ sample _` inference needs the `source` member (`Source.For[S]` otherwise).

---

## 1. Source checks that the scores depend on

Checked in `.work/scratch/kyo-src/` (RC7) and `.work/scratch/jatatui/src/` (0.31.0). Nothing compiled.

| Claim | Result | Where |
| --- | --- | --- |
| Kyo lifts any non-Kyo value into `A < S`, so a ZIO/IO given to a handler typed `=> Any < R` compiles and never runs (components 4.1) | **CONFIRMED.** `implicit inline def lift[A, S](v: A)(using inline cl: CanLift[A]): A < S`; `CanLift.derived` only refuses `A <:< (Any < Nothing)` and singletons. A `UIO[Unit]` or `IO[Unit]` is liftable. | `kyo-kernel/kyo/kernel/internal/Implicits.scala:18`, `CanLift.scala:79` |
| signals: "plain `Unit` handlers use the Kyo `IntoAction` instance by contravariance" | **WRONG.** `opaque type <[+A, -S] = A \| Pending[A, S] \| Nested[A]`: outside the kernel `Unit` is not a subtype of `Unit < S` (only an implicit conversion exists), and given search does not apply conversions. A handler body `()` gets the `implicitNotFound` error; the user must write `Kyo.unit`. Minor. | `kyo-kernel/kyo/kernel/Pending.scala:48` |
| signals: Kyo handlers like `count.update(_ + 1): Unit < Sync` fit `IntoAction[Unit < (Async & Abort[Throwable])]` | OK: `opaque type Async <: (Sync & Async.Join)`, and `<` is contravariant in `S`. | `kyo-core/kyo/Async.scala:47` |
| signals: `SignalRef.getAndSet`, `Signal.initRef` with a `missingCanEqual` message | OK. No member `update` exists on `SignalRef`, so the tui4s extension `update` is not shadowed. | `kyo-core/kyo/Signal.scala:333-353, 598` |
| components: `Var.updateDiscard`, `Var.setDiscard`, `Var.runTuple` | OK. | `kyo-prelude/kyo/Var.scala:108,141,194` |
| `Random.nextInt(bound)`, `Random.nextDouble` on the companion; `Stream.unfold(acc, chunkSize)`, `Stream.range(..., chunkSize)`, effectful `Stream.map` | OK. | `kyo-core/kyo/Random.scala:221-247`, `kyo-prelude/kyo/Stream.scala:92,982,1027` |
| `Kyo.zip` arity (signals threads refs through it) | up to 10. | `kyo-kernel/kyo/Kyo.scala:161` |
| free/signals: facade constructors take `(using kyo.Frame)`; users never write it | OK: `implicit inline def derive: Frame` is in the `Frame` companion (implicit scope, no import needed). It still shows in IDE signatures. | `kyo-data/kyo/Frame.scala:136` |
| free: `.centered` on `text(...)`, `.yellow`/`.bold` chain on `Span`, `Flex.Center` | OK: `Paragraph.centered()`, `Span implements Stylize<Span>` with `yellow()`, `bold()`, `dim()`, `crossedOut()`; `Flex.Center` is an enum constant. | `jatatui-widgets/.../Paragraph.java:142`, `jatatui-core/.../Stylize.java:79,185`, `Span.java:36` |
| free: no jatatui widget member shadows the extension names `size/fill/percent/atLeast/atMost/bordered/framed/padded/styled/center/tag/key` | OK: the only public `size()` members are on `Terminal` and `TestBackend`, which are not views. | grep over `jatatui-core`, `jatatui-widgets` |
| elm/components/signals: jatatui names used by the samples (`Style.empty()`, `Style.DEFAULT`, `withFg`, `Line.styled`, `Line.raw`, `Tabs.ofStrings`, `withSelected`, `Sparkline.withDataLongs/withMax`, `Gauge.withRatio/withLabel`) | OK. | jatatui sources |

## 2. Size of the sample apps (non-blank, non-comment code lines)

| Candidate | Kyo Counter | Kyo Todo | Kyo Dashboard | ZIO Counter | ZIO Dashboard | Cats Dashboard |
| --- | --- | --- | --- | --- | --- | --- |
| elm | 22 | 96 (incl. ~20 lines of file persistence the others do not have; ~75 without) | 53 | 22 | 52 | 52 |
| components | 18 | 59 (+ library TextField; 3 component types) | 76 | 19 | 70 | not shown ("identical to 4.4") |
| signals | 16 | 59 | 41 | 16 | 38 | 42 |
| free | **13** | 57 | 43 | **13** | 42 | 44 |

Line counts are only one input. The scores below also weigh concepts to learn, traps, type guidance and
how the code reads.

## 3. Per-candidate judgement (dx)

### free: 8 / 10

What is excellent:
- The Counter is the whole app in 13 lines and it is the same text in the three ecosystems
  (`import tui4s.*` / `tui4s.zio.*` / `tui4s.cats.*`). `update` is a plain `match` that returns the new
  state or `Quit`; a newcomer who knows Elm, bubbletea or a `foldLeft` understands it at once.
- No `Msg` type and no second `update` for async values: the Dashboard writes
  `source.poll(250.millis)(Random.nextDouble)(_ sample _).key("cpu")` and the fold is a model method.
  Compared with elm's six `Msg` cases plus six `update` cases, this removes the largest TEA boilerplate.
- "Sources are a function of state" makes pause beautiful: `if d.paused then Seq(logs) else Seq(logs, cpu)`.
- App logic has no effect type at all, so ZIO and Cats users see no Kyo types, and the F1
  for-comprehension trap cannot happen in user code. The only Kyo trace is `(using kyo.Frame)` on facade
  `Source` constructors, which the user never writes.
- The view DSL reads cleanly: `column(text("Count: ", n.toString.bold.yellow).centered.size(1), ...)
  .flex(Flex.Center).bordered(" Counter ")`; size modifiers are order-independent (a stated rule).
- The synchronous `TuiTest(app).press(...).screen` is the same for all three ecosystems.

What costs points:
- No one-shot command (its own W2): "on Ctrl-S save the file" needs a `saving` flag, a request counter and
  a keyed `source.task`. This is the `useEffect` dependency problem in a different form, and real apps
  meet it on day one (elm's Todo shows load/save in four lines with `Cmd.attempt`).
- Keys and help are not linked: help bars are hand-written strings that can drift from `update`
  (`"tab focus · enter add · ↑↓ move · ..."`). elm derives the help bar from the same `Keymap`.
- Focus is manual: `(t.focus, event) match` plus `switchFocus`. Fine for two panes, tedious for forms.
- Type guidance has small holes: `: Next[S]` must be written (an inferred union widens), `_ sample _` only
  infers through the `source` member (`Source.For[MyState]` otherwise), and `update` must end with
  `case _ => state` because `Event` is open.
- `View = jatatui Widget` mixes two vocabularies in one chain (`.centered` / `.withUseUnicode(true)` from
  Java, `.size` / `.bordered` from tui4s). Powerful, but less uniform than a tui4s-owned DSL.

### elm: 7 / 10

What is excellent:
- One `Program[Model, Msg]` text for all three ecosystems; the facade only adds effect constructors, and
  `Frame` is summoned inside the facade, so ZIO/Cats signatures are the cleanest of the four.
- `Next[Model, Msg] = Model | WithCmd` removes Elm's `(m, Cmd.none)` noise: `m.copy(count = m.count + 1)`
  or `m.withCmd(Cmd.quit)`.
- `Keymap(Bind('+', "increment", Msg.Inc), ...)` drives routing AND `View.help(keys)`: one value, no drift.
- `Cmd.attempt(TodoStore.load)(Msg.Loaded(_))`: one-shot effects with typed failures as messages; the Todo
  shows persistence that no other candidate shows.
- The types guide: the compiler lists the missing members of `Program`, and an exhaustive `Msg` match
  flags every unhandled message.

What costs points:
- The classic TEA boilerplate: every async value is a `Msg` case plus an `update` case
  (`Sample(value: Long)`, `Log(line: String)`, `Loaded(...)`, `Saved(...)`); keys go key -> `Msg` -> state.
  Dashboard: 53 lines against free's 43 and signals' 41.
- A silent trap: keys arrive only through `subscriptions` (`override def subscriptions(m) = Sub.keymap(keys)`),
  and `subscriptions` has a default. A first app with `init`/`update`/`view` compiles, runs, and ignores
  every key except Ctrl-C. The "compiler lists the work" claim does not cover this.
- String ids for resource subscriptions (`Sub.poll("load", ...)`, `Sub.stream("logs")`); stale-stream
  gotcha when parameters change but the id does not.
- `View.` prefix on every node (`View.column(View.text(...), View.help(keys))`) is noisier than bare DSL
  functions; `Model`/`Msg` must be top-level types (cyclic reference otherwise); `withCmd` is an extension on
  every type, and union types make some errors harder to read.
- UNVERIFIED: re-export of the top-level `withCmd` extension through `tui4s.zio` / `tui4s.cats`
  (fallback: generated forwarders).

### signals: 6.5 / 10

What is excellent:
- Short and visual: Counter 16 lines, Dashboard 41 (the shortest Kyo Dashboard). Handlers sit next to the
  widget they belong to (`.onKey { case Key.Char('+') => count.update(_ + 1) ... }`).
- Each ecosystem reads idiomatically: ZIO `SignalRef.make(0).map { count => ... }`, Cats
  `Resource.eval(SignalRef[IO].of(0)).map { ... }` with a tagless `F[_]` option. Cats users get the most
  native-looking facade of the four.
- `IntoAction[-R]` with `@implicitNotFound` is the right defence against the verified lift trap: a `UIO`
  handler resolves the ZIO instance, and a missing `given` import prints a message that names the fix.
- Tab focus is automatic; `.boxed(" Todos ", active = focus.is(Pane.List))` is a pleasant way to show focus.
- Structural binding `tab.view { case 0 => overview(metrics); case _ => logsPane(logs) }` is elegant.

What costs points:
- Hidden knobs that change behaviour without changing how the code looks: "bind at the leaves" (a root
  binding silently degrades to full repaints), `ref.at(_.x)` versus `ref.map(f)` (same call shape; the second
  gets a 1 s repair-timer watcher and up to 1 s latency in a write race), and "values that change together
  live in one ref" (torn frames otherwise; nothing enforces it). These are rules a learner must memorize.
- State changes are effects (`count.update`, `draft.getAndSet(...).map(f => model.update(...))`), so the
  logic is less declarative than a pure `update`, and level-1 tests must run effects.
- Refs are threaded by hand: `view(tui, tab, metrics, logs)` and `Kyo.zip(Signal.initRef(0),
  Signal.initRef(Metrics.empty), Signal.initRef(Chunk.empty[String]))`; the signature grows with every new
  piece of state. Kyo users write `val app: Tui => View < (Async & Scope) = ...`.
- Raw jatatui in user code: `import jatatui.core.style.Style`,
  `Line.styled(..., Style.empty().dim().crossedOut())`. Less beautiful than elm's opaque `Style` / String
  extensions or free's `item.title.dim.crossedOut`.
- `import tui4s.zio.{*, given}` is required; inference of `R` in `onKey[R](pf)(using IntoAction[R])` with
  Kyo's opaque `<` is UNVERIFIED; the "plain `Unit` works" claim is wrong (section 1).
- Help text is hand-written.

### components: 5 / 10

What is excellent:
- The Kyo Counter is short and pleasant (`object Counter extends Component[Unit, Int]` + one `build` with
  inline `.onKeys`), and the ZIO/Cats Counter is the same shape with `state.update`.
- Local state per component, automatic Tab focus between `.focusable` regions, focus-region key routing
  (typing `d` in the input never deletes), `IndexedStack`, and `explainKey` / `tree` text introspection.
- The facade design (opaque `View` with ZIO/IO handlers) correctly blocks the verified lift trap.

What costs points:
- The most ceremony beyond Counter. Every component brings `Props`, `State` and `Out` types and the call
  sites repeat themselves: `TodoList(TodoList.Props(s.items, ...))`, `LogPane(LogPane.Props(p.logs, capacity = 500))`,
  `.onOutput { case TodoList.Out.Toggle(id) => update(_.toggle(id)) ... }`. Dashboard: 76 lines, the longest,
  with a hand-wired `Channel.init` + `Fiber.init(produceSamples(...))` + `streamUntilClosed()` + streams
  passed through `Props`.
- Kyo effect rows leak into ordinary code: `withSelected(p)(f: Item => Unit < Emit[Out]): Unit < (Var[State] & Emit[Out])`,
  `Chunk[Sub[Local]]`, `type Ui = BuildContext ?=> View[Local]`. The design's own sample carries a workaround
  comment ("not Option.fold: its first list would fix the type to Unit < Any"). The doc admits that writing
  `def build(...) =` without `: Ui` gives a confusing error, and that `View[R & R2]` inference with PF literals
  is UNVERIFIED (its stated fallback, `onKeys[Local] { ... }`, adds noise to every handler).
- Two ways to read state (the `state` parameter of `build`, captured at build time, and `current` inside a
  handler) are a stale-closure trap that every learner must understand.
- Most concepts to learn (View, Component, Props, State, Out/onOutput, focus regions, IndexedStack,
  LayoutBuilder, BuildContext, Sub, task, Var/Emit), and string focus ids.
- For Kyo users the handler type `=> Any < R2` accepts any non-Kyo value through the verified implicit
  `lift`: a handler body that returns a `Future` or a ZIO compiles and does nothing.
- Facades: about 60 forwarders per module (docs and IDE help can drift), and a runtime owner check on
  `state` instead of a type check. The Cats Dashboard is not shown.

## 4. Do ZIO and Cats users really see no Kyo types?

| Candidate | Verdict |
| --- | --- |
| elm | Yes, and the cleanest: the shared `Program` has no Kyo type, facades summon `Frame` internally, and test levels 1-3 are Kyo-free. |
| free | Yes in user code; the app logic is byte-identical. `(using kyo.Frame)` is visible in IDE help for facade `Source` constructors (it is the source identity). |
| signals | Yes in user code. `(using Frame)` on `Source.view` and the Kyo row inside `IntoAction` can appear in IDE help and in inference errors. Needs the `given` import. |
| components | Yes, through an opaque `View` and duplicated forwarders. Runtime (not compile-time) check for the wrong `state`. |

## 5. Scores (criterion "dx")

| Candidate | Score | One-line reason |
| --- | --- | --- |
| free | **8** | Shortest, purest, byte-identical across Kyo/ZIO/Cats; loses points for no one-shot command, manual focus, unlinked help, `Next`/fold inference crutches. |
| elm | **7** | Familiar, well-typed, keymap-driven help, real `Cmd`; loses points for Msg boilerplate, the silent "keys need `subscriptions`" trap, string sub ids, `View.` noise. |
| signals | **6.5** | Short and idiomatic per ecosystem with a good `IntoAction` gate; loses points for invisible perf/latency knobs, effectful state, ref threading, raw jatatui Java API in user code. |
| components | **5** | Great Counter, but the heaviest ceremony beyond it, Kyo rows and a stale-state duality in user code, the most concepts, and UNVERIFIED inference that the samples depend on. |

## 6. Fatal flaws (dx lens)

No candidate has a flaw that alone makes it fail the DX goal for the three samples. The nearest ones:

- **components** (near-fatal): for anything bigger than Counter, user code must contain Kyo effect rows and
  per-component `Props`/`State`/`Out` plumbing, and the readability of every handler depends on UNVERIFIED
  `View[R & R2]` + partial-function inference. If that inference fails, the stated fallback (`onKeys[Local] {...}`
  on every handler) and longer row errors defeat "simple, even beautiful" for Kyo users.
- **signals** (near-fatal for learnability): correctness-adjacent behaviour is invisible at the call site
  (`ref.map(f).view` vs `ref.at(f).view`: 1 s worst-case latency and an idle 1 s timer; root binding = full
  repaint; multi-ref writes from fibers = torn frames). An agent or a newcomer cannot see these from the types.
- Not fatal but must be fixed by any winner: elm's silent no-keys default (`subscriptions = Sub.none`) and
  free's missing one-shot command.

## 7. Best ideas to graft into the winner (any candidate)

1. **elm: one `Keymap` value for routing AND help** (`Bind('+', "increment", Msg.Inc)` + `View.help(keys)`).
   In free this could be a `Keymap[S => Next[S]]` helper (`keys.lookup(key).fold(state)(_(state))` in
   `update`) plus `help(keys)` in `view`, so help bars cannot drift.
2. **elm: a one-shot command with minimal ceremony** (`Next = Model | WithCmd`, `m.withCmd(Cmd.attempt(task)(toMsg))`).
   For free: `Next[S] = S | Quit.type | WithTask[S]` with `s.withTask(source.task(save(s.items))(_ saved _))`,
   failures folded with `.recover`. This closes free's W2 without bringing back a `Msg` type.
3. **free: no `Msg` ADT, folds `(S, A) => S`, and `sources(state)` as a pure function of state** (pause =
   remove the source). Keep `source.poll(250.millis)(task)(_ sample _).key("cpu")`.
4. **signals: the `IntoAction[-R]` gate with `@implicitNotFound`** for any API that accepts effectful
   handlers or tasks: it stops ZIO/IO values from being silently lifted into Kyo `A < S` (verified
   `Implicits.lift`). Fix its "plain `Unit`" case with an explicit `given IntoAction[Unit]`.
5. **components + signals: automatic Tab focus cycling and a focus-highlighted border**
   (`.bordered(title, focused = ...)` in free already; add an optional `Focus` helper so forms do not need a
   hand-written `(focus, event) match` for every field).
6. **elm: the CI check that no `kyo.` type appears in the shared, Kyo-free surface** (tasty-query or a
   reflection test), and summoning `Frame` inside facades so that ZIO/Cats signatures stay clean.
7. **elm: `ctrlCQuits` checked before routing**, so a buggy update cannot trap the user (free has the
   option; keep it on by default).
8. **free: the member-clash audit** of extension names against jatatui members (VERIFIED by grep here), and
   order-independent size modifiers (`x.size(3).bordered(t)` == `x.bordered(t).size(3)`).
9. **elm: opaque `Style`/`Line`/`Span` wrappers and String extensions** that hide jatatui's name clashes
   (`List`, `Line`, `Block`, `Color`) and the `Style.fg()` record-accessor trap, while free keeps jatatui
   widgets as views for the long tail.
10. **components: `explainKey` / `tree` text introspection** ("why did my key do nothing?") as a testkit
    helper for agents; for a TEA winner, elm's `route(subs, key)` is the equivalent.

Status: COMPLETE.
