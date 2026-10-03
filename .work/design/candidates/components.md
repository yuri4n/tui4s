# tui4s design candidate: components with local state (React / Flutter style)

Status: COMPLETE (first full draft, all sections written).
Author: design panel, direction "components with local state". Date: 2026-10-01.
Legend: VERIFIED = checked in the RC7 / jatatui 0.31 sources or in a probe log. UNVERIFIED = not
checked (a compile or run is needed). All Scala below is design code: nothing was compiled.

## Outline

1. Pitch and core idea
2. Concepts and public API of tui4s-core
3. The three sample apps for Kyo users
4. Counter and Dashboard for ZIO and Cats Effect users (facades)
5. Runtime model
6. Testing and agent-friendliness
7. Performance analysis
8. Weaknesses, risks and open questions
Appendix A. The user's first take (48a6dd9): what was kept, fixed, dropped
Appendix B. Verification log (checked / UNVERIFIED names)

---

## 1. Pitch and core idea

**Pitch (5 lines).**
An app is a tree of small components. Each component is one `object` with a typed local state `S`:
`init` makes the state, `build` turns props and state into an immutable `View`, handlers on that view
are ordinary Kyo computations over `Var[S]`, and `subscriptions` declare the async sources it listens to.
The runtime keeps each component instance alive by its position in the tree, rebuilds only the
instances whose state or props changed, routes keys along the focus path, and draws only dirty frames.

### 1.1 The core idea in one picture

```
  Component (object, stateless code)          Instance (runtime, one per tree position)
  ───────────────────────────────────         ───────────────────────────────────────────
  init(props)              : S          ──►    state: S          (owned by the runtime)
  build(props, state)      : View[Local] ──►    cached View tree  (rebuilt only when dirty)
  subscriptions(props, s)  : Chunk[Sub] ──►    one fiber per Sub, in the instance's Scope
  handlers in the View     : Any < (Var[S] & Emit[O] & Tui.Base)
                                                run on the UI fiber with Var.runTuple(state)
```

Five rules make the whole design:

1. **A `View` is a value.** It is an immutable tree: layout nodes, jatatui widget leaves, handlers as
   data, and `Mount` nodes for child components. `build` is pure. It registers nothing.
2. **State is local and typed.** A component has exactly one state value `S` (a case class). Handlers
   change it through Kyo's own `Var[S]` effect. There are no positional hooks, so there are no "rules
   of hooks". Updating the wrong component's state is a compile error, because the handler's effect row
   (`Var[Todo.State]`) does not fit the expected row (`Var[TextField.State]`).
3. **Children talk to parents with typed outputs, not callbacks.** A child emits `O` values with Kyo's
   `Emit[O]`. The parent attaches `child(props).onOutput(o => ...)`, which runs with the parent's
   `Var`. This is Flutter's "callback prop" made typed and pure.
4. **Identity is position plus key.** The runtime matches a `Mount` node to an existing instance by
   (parent instance, path in the parent's view, optional key, component class). An instance that is
   not visited in a pass is unmounted: its `Scope` closes, so its subscriptions and tasks stop.
5. **Dirty tracking at two levels.** An event marks only the instances whose state changed as
   "rebuild-dirty". A frame is drawn only if a *painted* instance changed, the focus moved, or the
   terminal resized. Bursts of events are coalesced into one frame (frame budget, default 60 fps cap).

### 1.2 What it looks like (Kyo)

```scala
import tui4s.*
import kyo.*

object Counter extends Component[Unit, Int]:
  def init(props: Unit): Int = 0

  def build(props: Unit, n: Int): Ui =
    Paragraph(s"Count: $n").centered
      .bordered(" Counter ")
      .onKey(Key('+'))(update(_ + 1))
      .onKey(Key('-'))(update(_ - 1))
      .onKey(Key('q'))(Tui.quit)

object CounterApp extends TuiApp:
  run(Tui.run(Counter()))
```

### 1.3 Why this direction (and how it differs from the other candidates)

- **Local reasoning.** A component file contains everything about one part of the screen: its state,
  its keys, its async sources. A big app does not grow one giant `Model` and one giant `update`.
- **Incremental work.** Only dirty instances run `build`. Elm-style designs rebuild the whole view on
  each update. (Painting is still a full frame: jatatui is immediate-mode. See section 7.)
- **Kyo fit.** Local state *is* `Var[S]`, outputs *are* `Emit[O]`, commands *are* `Emit[Tui.Cmd]`,
  lifetimes *are* `Scope`s, async sources *are* `Stream` / `Signal` / fibers. A component is, in effect,
  a handler of `Var[S]` and `Emit[O]` for the handlers in its subtree.
- **It starts from the user's first take** (commit 48a6dd9): Flutter's split of Widget (config),
  Element (mounted instance) and State (per-instance state), `StatelessWidget` / `StatefulWidget`,
  `build`, `BuildContext`, a resource that owns the terminal, and a frame-rate setting. Appendix A
  lists in detail what was kept, what was fixed (the main bug: state was re-created on every frame)
  and what was dropped.

### 1.4 Vocabulary map (Flutter / first take / React / this design)

| Flutter            | first take (48a6dd9)          | React / jatatui-react      | tui4s (this design)                         |
| ------------------ | ----------------------------- | -------------------------- | ------------------------------------------- |
| `Widget` (config)  | `Widget[F]`                   | `Element` (data)           | `View[-R]` (immutable description)          |
| `Element` (mounted)| `Element[F]`                  | `Fiber`                    | `Instance` (runtime-internal)               |
| `StatelessWidget`  | `StatelessWidget[F]`          | function component         | a plain function returning `View`           |
| `StatefulWidget` + `State<T>` | `StatefulWidget[F]` + `State` + `StateT` | component + `useState` | `Component[P, S]` + `Var[S]`        |
| `build(context)`   | `build(BuildContext)`         | render body                | `build(props, state): Ui` (context function)|
| `BuildContext`     | `BuildContext(buffer, area, lastEvent)` | `RenderContext`  | `BuildContext` (focus, size; no buffer, no event) |
| `didUpdateWidget`  | (none)                        | `useEffect` on props       | `propsChanged(before, after, state)`        |
| `Focus`/`FocusNode`| (none)                        | `useFocus` (Ink)           | `.focusable(id)` + focus path routing       |
| `IndexedStack`     | (none)                        | (none)                     | `IndexedStack(i)(children*)`                |
| `setState`         | `Ref.set` after `StateT.run`  | `State.set`                | `update(f)` = `Var.updateDiscard(f)`        |
| callbacks in props | (none)                        | callback props             | typed outputs `Emit[O]` + `.onOutput`       |
| `Stream`/`Timer`   | event signal (fs2)            | `useEffect` + `useFrame`   | `subscriptions`: `Sub.every/stream/signal`  |

---

## 2. Concepts and public API of tui4s-core

### 2.1 Packages and what each one holds

| Package             | Content                                                                                   | Who uses it          |
| ------------------- | ----------------------------------------------------------------------------------------- | -------------------- |
| `tui4s`             | `View`, `Component`, `Sub`, `Key`/`KeyPress`/`Mods`, `Mouse`, `Event`, `Tui`, `TuiApp`, `TuiError`, `BuildContext`, layout and widget builders, `Style`/`Color`/`Line` helpers | every Kyo user (`import tui4s.*`) |
| `tui4s.components`  | built-in stateful components written with the public API: `TextField`, `SelectList`, `LogView`, `ScrollView` | users              |
| `tui4s.testkit`     | `TestTui` (headless harness on jatatui `TestBackend`), `Screen` snapshot and assertions   | tests, agents        |
| `tui4s.spi`         | `ComponentBase`, `Dispatch`, `View.Handler.Raw`, `Leaf`, `PaintSlots`: the contract that `tui4s-zio` and `tui4s-cats` build on | facade modules, advanced users |
| `tui4s.internal`    | `private[tui4s]` runtime: `Runtime`, `Reconciler`, `Painter`, `FocusTable`, `InputThread`, `Term` | nobody outside       |

One import (`import tui4s.*`) is enough for an app. The jatatui classes that users need often
(`Style`, `Color`, `Line`, `Constraint`, `Flex`, `Size`, `Rect`) are visible through tui4s type
aliases plus small helper objects (2.7). For full control a user imports jatatui directly; tui4s
never hides it.

### 2.2 `View`: the immutable description

```scala
package tui4s

import kyo.*
import jatatui.core.buffer.Buffer
import jatatui.core.layout.{Constraint, Direction, Flex, Rect, Size}
import jatatui.widgets.block.{Block, BorderType}

/** An immutable description of a part of the screen.
  *
  * `R` is the effect row that the handlers in this subtree need (outside nested components).
  * A view with no handlers is a `View[Any]`, and `View[Any] <: View[R]` for every `R`.
  */
sealed abstract class View[-R]:
  import View.*

  // ---- identity (React/Flutter "key") and size in the parent Column/Row ----
  final def key(k: String): View[R]                    = Keyed(k, this)
  final def length(cells: Int): View[R]                = Sized(Constraint.Length(cells), this)
  final def fill(weight: Int = 1): View[R]             = Sized(Constraint.Fill(weight), this)
  final def percent(p: Int): View[R]                   = Sized(Constraint.Percentage(p), this)
  final def min(cells: Int): View[R]                   = Sized(Constraint.Min(cells), this)
  final def max(cells: Int): View[R]                   = Sized(Constraint.Max(cells), this)

  // ---- decoration ----
  final def block(b: Block): View[R]                   = Boxed(b, this)
  final def bordered(title: String = "", tpe: BorderType = BorderType.Rounded): View[R] =
    Boxed(Block.bordered().withBorderType(tpe).withTitle(title), this)
  final def style(s: Style): View[R]                   = Styled(s, this)
  final def padding(horizontal: Int, vertical: Int = 0): View[R] = Padded(horizontal, vertical, this)

  // ---- focus ----
  /** Makes this node a focus target. Handlers below it run only while it holds the focus. */
  final def focusable(id: String, autoFocus: Boolean = false): View[R] = Focus(id, autoFocus, this)
  /** Terminal cursor position, relative to this node's area; shown while the focus is inside. */
  final def cursor(x: Int, y: Int): View[R]            = Cursor(x, y, this)

  // ---- keyboard (see 2.8 for the routing rule) ----
  final def onKey[R2](k: KeyPress)(h: => Any < R2): View[R & R2] =
    Handle(Handler.Keys(kp => if kp == k then Present(() => h) else Absent), this)
  final def onKeys[R2](pf: PartialFunction[KeyPress, Any < R2]): View[R & R2] =
    Handle(Handler.Keys(kp => if pf.isDefinedAt(kp) then Present(() => pf(kp)) else Absent), this)
  final def onChar[R2](h: Char => Any < R2): View[R & R2] =
    Handle(Handler.Keys { case Key.Char(c) => Present(() => h(c)); case _ => Absent }, this)
  final def onPaste[R2](h: String => Any < R2): View[R & R2] = Handle(Handler.Paste(h), this)

  // ---- mouse (needs Tui.Config(mouse = true)) ----
  final def onClick[R2](h: Mouse => Any < R2): View[R & R2]       = Handle(Handler.Click(h), this)
  final def onScroll[R2](h: ScrollDir => Any < R2): View[R & R2]  = Handle(Handler.Scroll(h), this)
end View

object View:
  // ---- node types (the runtime matches on these; users build them through the methods above) ----
  case object Empty extends View[Any]
  /** A painting leaf. Open for extension: wrap any drawing code (see 2.6). */
  abstract class Leaf extends View[Any]:
    /** Runs on the UI fiber inside `Terminal.draw`. Must not keep `buf` or `slots` objects. */
    def paint(area: Rect, buf: Buffer, slots: spi.PaintSlots): Unit
  final case class Linear[-R](dir: Direction, children: Chunk[View[R]], gap: Int, margin: Int, flex: Flex)
      extends View[R]:
    def gap(n: Int): Linear[R]        = copy(gap = n)
    def margin(n: Int): Linear[R]     = copy(margin = n)
    def flex(f: Flex): Linear[R]      = copy(flex = f)
  final case class Layers[-R](children: Chunk[View[R]]) extends View[R]                  // later paints on top
  final case class Indexed[-R](selected: Int, children: Chunk[View[R]]) extends View[R]  // all mounted, one painted
  final case class Sized[-R](constraint: Constraint, child: View[R]) extends View[R]
  final case class Keyed[-R](key: String, child: View[R]) extends View[R]
  final case class Boxed[-R](block: Block, child: View[R]) extends View[R]
  final case class Styled[-R](style: Style, child: View[R]) extends View[R]
  final case class Padded[-R](h: Int, v: Int, child: View[R]) extends View[R]
  final case class Focus[-R](id: String, autoFocus: Boolean, child: View[R]) extends View[R]
  final case class Cursor[-R](x: Int, y: Int, child: View[R]) extends View[R]
  final case class Handle[-R](handler: Handler[R], child: View[R]) extends View[R]
  final case class Deferred[-R](build: Size => View[R]) extends View[R]   // Flutter LayoutBuilder
  /** A child component. `out` runs in the PARENT's handler row when the child emits an output. */
  final case class Mount[P, S, O, -R](
      component: spi.ComponentBase[P, S, O],
      props: P,
      out: Maybe[O => Any < R]
  ) extends View[R]:
    def onOutput[R2](f: O => Any < R2): Mount[P, S, O, R2] = Mount(component, props, Present(f))

  /** Handlers are data in the tree. Nothing is registered as a side effect of `build`. */
  enum Handler[-R]:
    case Keys(select: KeyPress => Maybe[() => Any < R])
    case Paste(run: String => Any < R)
    case Click(run: Mouse => Any < R)
    case Scroll(run: ScrollDir => Any < R)
    /** SPI for the ZIO / Cats facades: runs with the owner's raw state cell (see 4.1). */
    case Raw(select: Event => Maybe[spi.Dispatch => Unit < (Async & Abort[Throwable])])

  def widget(w: jatatui.core.widgets.Widget): View[Any]       // escape hatch: any jatatui widget value
  def stateful[St <: AnyRef](w: jatatui.core.widgets.StatefulWidget[St], init: => St)(
      prepare: St => Unit
  ): View[Any]                                                 // runtime-owned widget state (2.9)
end View
```

Notes on typing:

- `View[-R]` is contravariant like Kyo's `A < S` (`S` is contravariant in `<[+A, -S]`, VERIFIED in
  `kyo-kernel/Pending.scala` per kyo.md 2.1). So `Column(a, b)` with `a: View[Var[M]]` and
  `b: View[Any]` infers `View[Var[M]]`, and `.onKey(k)(h)` returns `View[R & R2]`, the same shape as
  Kyo's own `map[B, S2](f: A => B < S2): B < (S & S2)`.
- `onKey` takes the handler by name, so building a view does not even build the handler
  computation. The handler is built when the key arrives.
- The node classes are public case classes so that tests and agents can inspect a built tree
  (`Counter.build((), 3)` is a plain value). Handlers are functions, so trees that contain handlers
  are not comparable with `==`; layout and leaf nodes are.

### 2.3 `Component`: local state, build, subscriptions

```scala
package tui4s

import kyo.*

object Component:
  /** Full form: a component with outputs `O` to its parent. */
  abstract class Emitting[P, S, O](using
      val stateTag: Tag[Var[S]],
      val outTag: Tag[Emit[O]],
      val stateEq: CanEqual[S, S],
      val propsEq: CanEqual[P, P]
  ) extends spi.ComponentBase[P, S, O]:

    /** The handler row of this component: its own state, its outputs, and the runtime commands. */
    type Local = Var[S] & Emit[O] & Tui.Base
    /** What `build` returns. A context function: the `BuildContext` is passed implicitly. */
    type Ui    = BuildContext ?=> View[Local]

    // ---- the three things a user writes ----
    def init(props: P): S
    def build(props: P, state: S): Ui
    def subscriptions(props: P, state: S): Chunk[Sub[Local]] = Chunk.empty

    // ---- optional lifecycle (Flutter didUpdateWidget) ----
    /** Called on the UI fiber when the parent passes new props (by `==`). Default: keep the state. */
    def propsChanged(before: P, after: P, state: S): S = state

    // ---- helpers for handlers (all are plain Kyo effects) ----
    protected inline def update(inline f: S => S)(using inline frame: Frame): Unit < Var[S] =
      Var.updateDiscard[S](f)
    protected inline def set(s: S)(using inline frame: Frame): Unit < Var[S] = Var.setDiscard(s)
    /** The LATEST state inside a handler (the `state` parameter of `build` is the build-time value). */
    protected inline def current(using inline frame: Frame): S < Var[S]     = Var.get[S]
    protected inline def output(o: O)(using inline frame: Frame): Unit < Emit[O] = Emit.value(o)

    /** Runs `work` in a fiber owned by this instance (interrupted on unmount), then `done` on the UI fiber. */
    protected def task[A](work: => A < (Async & Abort[Throwable]))(
        done: Result[Throwable, A] => Any < Local
    )(using Frame): Unit < Emit[Tui.Cmd]

    // ---- build-time queries (they record a dependency, so a change triggers a rebuild) ----
    protected def hasFocus(using ctx: BuildContext): Boolean          = ctx.hasFocus
    protected def isFocused(id: String)(using ctx: BuildContext): Boolean = ctx.isFocused(id)
    protected def terminalSize(using ctx: BuildContext): Size         = ctx.terminalSize

    // ---- mounting ----
    final def apply(props: P): View.Mount[P, S, O, Any]               = View.Mount(this, props, Absent)
    final def apply()(using ev: Unit <:< P): View.Mount[P, S, O, Any] = apply(ev(()))
  end Emitting
end Component

/** A component without outputs: the common case. */
abstract class Component[P, S](using Tag[Var[S]], CanEqual[S, S], CanEqual[P, P])
    extends Component.Emitting[P, S, Nothing]

/** Build-time context (the first take's BuildContext, without buffer and without event). */
final class BuildContext private[tui4s] (/* instance + runtime handles */):
  def hasFocus: Boolean                   // the focused node is inside this instance
  def isFocused(id: String): Boolean
  def terminalSize: Size
```

Why one typed state per component, and not React hooks:

- jatatui-react's hooks are positional slots in a `HashMap<HookKey, Object>` with unchecked casts
  (VERIFIED in `RenderContext.useState` / `HookStore.values`). Call order matters, nothing is typed,
  and the hook rules are not enforced. A case class `S` plus `Var[S]` is typed, has no ordering rule,
  and is testable with `Var.runTuple`.
- jatatui-react had to remove automatic memoization because handlers were registered as a side
  effect of rendering (VERIFIED, comment in `HookStore`: "interacted badly with side-effect handlers").
  Here handlers are data in the returned `View`, so the runtime can skip `build` safely when props,
  state and the recorded `BuildContext` reads are unchanged.
- What hooks give and this design keeps: `useState` = `S`; `useEffect` on mount / on deps =
  `Sub.onMount(key)` (key = deps); `useTimeout`/`useFrame` = `Sub.every`; `useFocus` =
  `.focusable(id)` + `hasFocus`; `useContext` = props or an external `SignalRef` with `Sub.signal`;
  `useRef` = not needed (state is a value; jatatui widget states are runtime-owned, 2.9).

### 2.4 `Sub`: async sources, owned by an instance

```scala
package tui4s

sealed abstract class Sub[-R]:
  def key: String

object Sub:
  /** Runs `h` every `period` (first run after one period). Ticks do not pile up: at most one is pending. */
  def every[R](key: String, period: Duration)(h: => Any < R)(using Frame): Sub[R]
  /** Runs `h` for each element; elements that arrive together are applied in one dispatch. Backpressure. */
  def stream[A, R](key: String, source: => Stream[A, Async & Abort[Throwable]])(h: A => Any < R)(using
      Frame, Tag[Emit[Chunk[A]]]
  ): Sub[R]
  /** Runs `h` with the current value and then each change (latest-value semantics, like streamChanges). */
  def signal[A, R](key: String, source: Signal[A])(h: A => Any < R)(using Frame): Sub[R]
  /** Runs `h` once when this key appears (React useEffect on mount; change the key to run it again). */
  def onMount[R](key: String)(h: => Any < R)(using Frame): Sub[R]
  /** Every terminal event, before focus routing (for global shortcuts or key loggers). Rarely needed. */
  def events[R](key: String)(h: Event => Any < R)(using Frame): Sub[R]
```

Semantics:

- The runtime evaluates `subscriptions(props, state)` after each rebuild of the instance and diffs by
  `key`: a new key starts a fiber in the instance's `Scope`; a missing key interrupts its fiber; an
  existing key keeps running, and its deliveries use the newest `h` (so a handler that reads props sees
  the current props). To restart a source with new parameters, change its key
  (`Sub.every(s"tick-$ms", ms.millis)(...)`), like React's dependency array.
- Every delivery is a message to the UI fiber. `h` runs there with the instance's `Var[S]`, so state
  changes from async sources are serialized with key handlers. There are no locks in user code.
- When the instance unmounts, its `Scope` closes: every fiber of every `Sub` and `task` is interrupted.

### 2.5 `Tui`: commands, run, config, errors, entry point

```scala
package tui4s

object Tui:
  /** Effects that every handler may use. The runtime handles them. */
  type Base = Emit[Cmd] & Async & Abort[Throwable]

  sealed abstract class Cmd derives CanEqual
  object Cmd:
    case object Quit                    extends Cmd
    final case class Focus(id: String)  extends Cmd
    case object FocusNext               extends Cmd
    case object FocusPrev               extends Cmd
    case object Redraw                  extends Cmd   // full repaint (terminal.clear), e.g. after a shell-out
    final case class PrintAbove(lines: Chunk[String]) extends Cmd   // Inline viewport: terminal.insertBefore
    private[tui4s] final case class Task(/* erased work + done, bound to the emitting instance */) extends Cmd

  def quit(using Frame): Unit < Emit[Cmd]                 = Emit.value(Cmd.Quit)
  def focus(id: String)(using Frame): Unit < Emit[Cmd]    = Emit.value(Cmd.Focus(id))
  def focusNext(using Frame): Unit < Emit[Cmd]            = Emit.value(Cmd.FocusNext)
  def focusPrev(using Frame): Unit < Emit[Cmd]            = Emit.value(Cmd.FocusPrev)
  def printAbove(lines: String*)(using Frame): Unit < Emit[Cmd] = Emit.value(Cmd.PrintAbove(Chunk.from(lines)))

  final case class Config(
      maxFps: Int = 60,                         // frame budget: at most one frame per 1000/maxFps ms
      mouse: Boolean = false,                   // EnableMouseCapture
      bracketedPaste: Boolean = true,           // Event.Paste carries emoji and long text whole
      tabMovesFocus: Boolean = true,            // unhandled Tab / BackTab cycle the focus
      ctrlCQuits: Boolean = true,               // Ctrl-C quits before any routing
      pollInterval: Duration = 25.millis,       // input thread poll timeout (bounds the quit latency)
      queueCapacity: Int = 1024,                // UI channel; input and streams get backpressure
      viewport: Viewport = Viewport.Fullscreen, // or Inline(height) for a prompt-style UI
      onHandlerError: ErrorPolicy = ErrorPolicy.Crash,
      tuneScheduler: Boolean = true             // F13 flags, see 5.8
  )
  enum Viewport derives CanEqual:
    case Fullscreen
    case Inline(height: Int)
  enum ErrorPolicy:
    case Crash                                   // restore the terminal, fail Tui.run with TuiError
    case Report(log: TuiError => Unit < Sync)    // keep the old state of that instance and continue

  /** Runs the app until `Tui.quit` (or Ctrl-C). */
  def run(root: View[Base], config: Config = Config())(using Frame): Unit < (Async & Abort[TuiError])

  /** Runs the app until the ROOT component emits its first output, and returns it (a picker, a form). */
  def runFor[P, S, O](root: View.Mount[P, S, O, Any], config: Config = Config())(using
      Frame
  ): Maybe[O] < (Async & Abort[TuiError])

  /** Sets the kyo.scheduler StaticFlags of F13 unless they are already set. Call before Kyo starts. */
  def tuneScheduler(): Unit
end Tui

/** Entry point: a KyoApp that tunes the scheduler first (constructor body, before `main`). */
abstract class TuiApp extends KyoApp:
  Tui.tuneScheduler()

enum TuiError(message: String, cause: Throwable | Null) extends Exception(message, cause):
  case NotATerminal                                        extends TuiError("stdin/stdout is not a TTY", null)
  case NativeAccess(c: Throwable)                          extends TuiError("crossterm native library could not load (add --enable-native-access=ALL-UNNAMED)", c)
  case Io(c: Throwable)                                    extends TuiError("terminal I/O failed", c)
  case HandlerFailed(component: String, c: Throwable)      extends TuiError(s"handler of $component failed", c)
  case BuildFailed(component: String, c: Throwable)        extends TuiError(s"build of $component failed", c)
```

`TuiError` extends `Exception` on purpose: Cats users get it as the failure of an `IO`, and ZIO users
as a typed error (section 4).

### 2.6 Widgets: how jatatui is wrapped and exposed

Three levels, from the most common to the most free:

1. **tui4s builders** (case classes that extend `View.Leaf`, so `==` works and memo is safe). Each one
   maps to one jatatui widget, builds it in `paint`, and clamps inputs that jatatui rejects with an
   exception (`Gauge.withRatio` throws outside 0..1, VERIFIED jatatui.md 3.0).

   | tui4s builder (selected parameters)                                          | jatatui widget                                  |
   | ---------------------------------------------------------------------------- | ----------------------------------------------- |
   | `Text(s: String, style = Style.empty)`                                        | `Buffer.setLine` of one `Line`                  |
   | `Paragraph(text: String, wrap = true, scroll = 0, style = ...)` `.centered`   | `paragraph.Paragraph` + `Wrap`, `Scroll`        |
   | `Gauge(ratio: Double, label = "", gaugeStyle = ...)`                          | `gauge.Gauge.withRatio` (clamped)               |
   | `LineGauge(ratio: Double, label = "")`                                        | `gauge.LineGauge`                               |
   | `Sparkline(data: Chunk[Long], max: Maybe[Long] = Absent)`                     | `sparkline.Sparkline.withDataLongs`             |
   | `Tabs(titles: Chunk[String], selected: Int, highlight = Style.reversed)`      | `tabs.Tabs.ofStrings(...).withSelected`         |
   | `ListView(items: Chunk[Line], selected: Maybe[Int], highlight, symbol = "> ")`| `list.List` + runtime-owned `ListState`         |
   | `ListView.virtual(count: Int, selected: Maybe[Int])(row: Int => Line)`           | same; builds only the visible rows at paint time |
   | `TableView(rows, widths, selected, header)`                                   | `table.Table` + runtime-owned `TableState`      |
   | `BarChart(...)`, `Chart(...)`, `Canvas(paint: Context => Unit)`               | `barchart`, `chart`, `canvas`                   |
   | `Clear` (inside `Popup`)                                                      | `widgets.Clear`                                 |

2. **`View.widget(w)`**: any jatatui `Widget` value (a SAM: a Scala lambda `(area, buf) => ...`
   works, VERIFIED jatatui.md 2.7). For widgets tui4s has not wrapped yet, or custom drawing.

3. **`View.stateful(w, init)(prepare)`** and **`View.Leaf`**: for jatatui `StatefulWidget`s; the
   state object is created once per node identity and owned by the runtime (2.9).

Text input is not jatatui's `TextInput` (it uses UTF-16 indexes, VERIFIED jatatui.md 3.7). The
built-in `tui4s.components.TextField` keeps a grapheme-aware buffer (`java.text.BreakIterator`, width
from jatatui's `Wcwidth`) and paints with `Buffer.setStringn`. It is a normal component (3.2 shows its
outline), so users can copy and change it.

### 2.7 Layout and styles

Layout is Flutter / jatatui-react style: containers, and a size on each child.

```scala
object Column:
  def apply[R](children: View[R]*): View.Linear[R]          // Direction.Vertical, gap 0, Flex.Start
object Row:
  def apply[R](children: View[R]*): View.Linear[R]          // Direction.Horizontal
object Layers:
  def apply[R](children: View[R]*): View[R]                 // overlays; later ones paint on top and win clicks
object IndexedStack:
  def apply[R](selected: Int)(children: View[R]*): View[R]  // every child stays mounted; one is painted
object Popup:
  def apply[R](width: Constraint, height: Constraint)(child: View[R]): View[R]  // centered, Clear + child
object LayoutBuilder:
  def apply[R](f: Size => View[R]): View[R]                 // built at paint time with the real area
```

- A child without a size gets `Constraint.Fill(1)` (jatatui-react's rule). `.length(3)`,
  `.percent(30)`, `.min(10)`, `.fill(2)` attach a jatatui `Constraint`. The container calls
  `Layout.vertical/horizontal(constraints*).withSpacing(gap).withMargin(margin).withFlex(flex)
  .split(area)` (VERIFIED names, jatatui.md 2.4); equal layouts hit jatatui's LRU cache (~2.7 us).
- Styles are jatatui `Style` values (immutable records). tui4s adds type aliases and helper objects,
  because Java statics cannot be re-exported as values:

```scala
type Style = jatatui.core.style.Style
type Color = jatatui.core.style.Color
type Line  = jatatui.core.text.Line
object Style:
  val empty: Style      = jatatui.core.style.Style.empty()
  def fg(c: Color): Style = empty.withFg(c)          // NOT Style.fg(c): that is the record accessor (jatatui.md 8.4)
  def bg(c: Color): Style = empty.withBg(c)
  val bold: Style       = empty.bold()
  val reversed: Style   = empty.withAddModifier(jatatui.core.style.Modifier.REVERSED)
  val crossedOut: Style = empty.crossedOut()
object Color:
  val Yellow: Color = jatatui.core.style.Color.YELLOW   // ... the 16 named colours
  def rgb(r: Int, g: Int, b: Int): Color = jatatui.core.style.Color.Rgb(r, g, b)
object Line:
  def apply(s: String, style: Style = Style.empty): Line = jatatui.core.text.Line.styled(s, style)
type Block      = jatatui.widgets.block.Block
type BorderType = jatatui.widgets.block.BorderType
object Block:
  def bordered(title: String = "", tpe: BorderType = jatatui.widgets.block.BorderType.Rounded): Block =
    jatatui.widgets.block.Block.bordered().withBorderType(tpe).withTitle(title)
```

The result of every helper is the jatatui value, so all jatatui withers stay available
(`Block.bordered().withBorderStyle(...)`, `Style.bold.withBg(...)`).

`Size`, `Rect`, `Constraint` and `Flex` are type aliases only. Their static members (`Constraint.Length`,
`Flex.Center`) need `import jatatui.core.layout.*`; the view methods `.length(n)`, `.percent(p)`,
`.fill(w)`, `.gap(n)`, `.flex(f)` cover the common cases without it. tui4s also provides
`given CanEqual[Unit, Unit]` so that `Component[Unit, S]` works under `-language:strictEquality`.

### 2.8 How key, mouse, paste and resize events reach user code

The input thread converts `tui.crossterm.Event` into the tui4s `Event` ADT (own Scala types, because
Java records cannot be extractors, VERIFIED jatatui.md 8.4):

```scala
enum KeyCode derives CanEqual:
  case Char(c: scala.Char)
  case Enter, Tab, BackTab, Backspace, Delete, Insert, Esc, Up, Down, Left, Right, Home, End, PageUp, PageDown
  case F(n: Int)
  case Other(description: String)               // media keys, modifier keys, Null
final case class Mods(bits: Int) derives CanEqual:
  def ctrl: Boolean; def alt: Boolean; def shift: Boolean
final case class KeyPress(code: KeyCode, mods: Mods = Mods.None) derives CanEqual

object Key:
  def apply(c: scala.Char): KeyPress            // Key('q'); SHIFT is removed from Char keys, so Key('A') matches Shift+a
  def ctrl(c: scala.Char): KeyPress             // Key.ctrl('s')
  val Enter, Tab, BackTab, Esc, Up, Down, Left, Right, Backspace, Delete, Home, End, PageUp, PageDown: KeyPress
  object Char:  def unapply(k: KeyPress): Option[scala.Char]   // a printable char without Ctrl/Alt
  object Ctrl:  def unapply(k: KeyPress): Option[scala.Char]

enum Event derives CanEqual:
  case Key(press: KeyPress)                     // only KeyEventKind.Press and Repeat; Release is dropped
  case Mouse(m: tui4s.Mouse)
  case Paste(text: String)
  case Resize(width: Int, height: Int)
  case FocusGained, FocusLost
```

**Routing rule for keys (one sentence): a handler belongs to the nearest `.focusable` above it; it
runs when that focusable holds the focus (or contains the focused node), and handlers with no
focusable above them are app-level and run for every key that the focused part did not consume.**

Order of one key press:

1. `Ctrl-C` quits (if `ctrlCQuits`).
2. The focus chain, innermost focus region first: in each region, deeper handlers first; the first
   handler that matches consumes the key.
3. App-level handlers (deepest first).
4. If nothing consumed it: `Tab` / `BackTab` move the focus (if `tabMovesFocus`).

Examples: in the Todo app, `TextField`'s `onChar` is below the focusable `"input"`, so it consumes
`d` while the input has focus and the list's `d` (below `"list"`) does not run. In Counter there is no
focusable, so all its handlers are app-level and always run.

- **Paste** follows the same chain as keys (`onPaste`).
- **Mouse**: hit-test on the bounds recorded during the last paint, in paint order with layers
  (jatatui-react's `(z, seq)` rule: `Layers`/`Popup` children win), then bubble up the tree. A click on a
  focusable moves the focus to it. Handlers get a `Mouse` with `x`/`y` and `localX`/`localY`.
- **Resize**: never sent to handlers. The runtime marks the frame dirty and rebuilds the instances whose
  `build` read `terminalSize`. `Terminal.draw` resizes its buffers itself (`autoresize`, VERIFIED in
  `Terminal.java`). A component that needs every event can use `Sub.events`.

### 2.9 How state is held and updated

- **Component state** lives in the runtime's `Instance` record (one per mounted position), as a plain
  field written only by the UI fiber. A handler runs as
  `Var.runTuple(instance.state)(Emit.run[O](Emit.run[Tui.Cmd](handler)))` plus `Abort.run[Throwable]`;
  the runtime stores the new state, compares it with `==` (`CanEqual[S, S]`), marks the instance dirty if
  it changed, sends the outputs to the parent's `onOutput`, and executes the commands.
- **Shared state** has two answers. (a) Lift it into a common ancestor and pass it down in props (the
  Todo app does this for the items). (b) An external store: a `SignalRef[A]` created before `Tui.run`,
  passed in props, read with `Sub.signal`, and written from any handler with `ref.set` /
  `ref.updateAndGet` (`SignalRef` API VERIFIED in `Signal.scala`: `set`, `getAndUpdate`,
  `updateAndGet`, `use`, `get`).
- **jatatui widget state** (`ListState`, `TableState`, `ScrollbarState`) is not user state. The user
  keeps `selected: Int` in `S`; the runtime keeps one mutable jatatui state object per leaf identity in
  `PaintSlots`, calls `select(...)` before `render`, and lets `render` clamp and compute the scroll
  offset. The offset survives between frames, and no mutable jatatui object ever reaches user code.

### 2.10 How async sources feed the app

| Source                              | Write it as                                             | Delivery                         |
| ----------------------------------- | ------------------------------------------------------- | -------------------------------- |
| timer / animation                   | `Sub.every("tick", 250.millis)(update(_.step))`         | at most one pending tick         |
| a Kyo `Stream` (logs, file watch)   | `Sub.stream("logs", logs)(line => update(_.add(line)))` | per chunk, with backpressure     |
| a `Signal` / `SignalRef` (shared)   | `Sub.signal("cfg", props.config)(c => set(...))`        | latest value                     |
| a one-shot request                  | `task(fetch(url))(r => update(_.loaded(r)))`            | once, if still mounted           |
| a fiber the user started elsewhere  | give it a `Channel` or `SignalRef` and use `Sub.stream` (`channel.streamUntilClosed()`) / `Sub.signal` | as above |

All of them become messages on the one UI `Channel`, and all handlers run on the UI fiber.

### 2.11 How the app starts and how it quits

```scala
object Main extends TuiApp:
  run(Tui.run(App()))                               // or Tui.run(App(), Tui.Config(mouse = true))
```

- `Tui.run` loads the native library first (so a JEP 472 warning or a `NativeAccess` error happens
  before raw mode), checks for a TTY, then acquires the terminal in a `Sync.acquireReleaseWith`
  (raw mode, alternate screen, hidden cursor, optional mouse and bracketed paste) and releases it
  synchronously (5.6). It does not use `Jatatui.init/runIo` (global handler and shutdown hook,
  VERIFIED jatatui.md 4.5).
- The app ends when a handler emits `Tui.quit`, on `Ctrl-C`, when the root emits an output under
  `Tui.runFor`, when a handler fails under `ErrorPolicy.Crash`, or when the fiber running `Tui.run` is
  interrupted. In every case the terminal is restored before `Tui.run`'s result is visible.

### 2.12 The SPI that the facades use (`tui4s.spi`)

```scala
package tui4s.spi

/** What the runtime needs from any component, whatever its handler style. */
abstract class ComponentBase[P, S, O]:
  def name: String = getClass.getSimpleName.stripSuffix("$")
  private[tui4s] def initState(props: P): S
  private[tui4s] def buildView(props: P, state: S)(using BuildContext): View[Any]   // rows erased
  private[tui4s] def subscriptionsOf(props: P, state: S): Chunk[Sub[Any]]
  private[tui4s] def onNewProps(before: P, after: P, state: S): S
  private[tui4s] def sameState(a: S, b: S): Boolean
  private[tui4s] def sameProps(a: P, b: P): Boolean
  /** Runs one handler that `buildView` put in the tree, against this instance's state cell. */
  private[tui4s] def runHandler(h: () => Any, d: Dispatch): Unit < (Async & Abort[Throwable])

/** Valid only while one handler runs, on the UI fiber. Unsafe by design; facades wrap it. */
trait Dispatch:
  def owner: ComponentBase[?, ?, ?]
  def state: Any
  def setState(s: Any): Unit
  def emitOutput(o: Any): Unit
  def command(c: tui4s.Tui.Cmd): Unit

/** Runtime-owned mutable objects for one leaf identity (jatatui widget states). */
trait PaintSlots:
  def slot[A <: AnyRef](init: => A): A
```

`Component.Emitting` implements `ComponentBase` with `Var`/`Emit`. The ZIO and Cats facades
implement it with `Handler.Raw` and a `Dispatch` stored in a ZIO `FiberRef` / CE `IOLocal` (4.1, 4.2).
The `private[tui4s]` members are visible to `tui4s.zio` and `tui4s.cats` because those packages are
inside `tui4s`.


## 3. The three sample apps for Kyo users

All three compile against the API of section 2 (UNVERIFIED: nothing was compiled; the validator
should check the inference of `onKeys` partial functions and of `Loop` with one state value).
None of them needs a for-comprehension (F1).

### 3.1 Counter

```scala
package examples.counter

import kyo.*
import tui4s.*

object Counter extends Component[Unit, Int]:
  def init(props: Unit): Int = 0

  def build(props: Unit, n: Int): Ui =
    Column(
      Paragraph(s"Count: $n").centered,
      Text(" + / - change   q quit ", Style.fg(Color.DarkGray)).length(1)
    ).bordered(" Counter ")
      .onKeys {
        case Key.Char('+') => update(_ + 1)
        case Key.Char('-') => update(_ - 1)
        case Key.Char('q') => Tui.quit
      }
end Counter

object CounterApp extends TuiApp:
  run(Tui.run(Counter()))
```

What happens on `+`: the input thread reads the key and puts it on the UI channel; the UI fiber
routes it (no focusable, so the handlers are app-level), runs `update(_ + 1)` with `Var.runTuple(3)`,
gets state `4`, marks the `Counter` instance dirty, rebuilds it, and draws one frame.

### 3.2 Todo

Three components: the root owns the items (shared by input and list), `TodoList` owns its
selection, and the built-in `TextField` owns the text being typed. Children report to the root with
typed outputs.

```scala
package examples.todo

import kyo.*
import tui4s.*
import tui4s.components.TextField

final case class Item(id: Long, title: String, done: Boolean) derives CanEqual

object TodoApp extends Component[Unit, TodoApp.State]:
  final case class State(items: Chunk[Item], nextId: Long) derives CanEqual:
    def add(title: String): State =
      if title.isBlank then this else State(items :+ Item(nextId, title.trim, done = false), nextId + 1)
    def toggle(id: Long): State = copy(items = items.map(i => if i.id == id then i.copy(done = !i.done) else i))
    def delete(id: Long): State = copy(items = items.filterNot(_.id == id))
    def remaining: Int          = items.count(!_.done)

  def init(props: Unit): State = State(Chunk.empty, 1L)

  def build(props: Unit, s: State): Ui =
    Column(
      TextField(TextField.Props(id = "input", placeholder = "What needs doing?", autoFocus = true))
        .onOutput { case TextField.Out.Submitted(text) => update(_.add(text)) }
        .bordered(" New todo ")
        .length(3),
      TodoList(TodoList.Props(s.items, s" Todos (${s.remaining} left) "))
        .onOutput {
          case TodoList.Out.Toggle(id) => update(_.toggle(id))
          case TodoList.Out.Delete(id) => update(_.delete(id))
        },
      Text(" tab focus   enter add   space done   d delete   esc quit ", Style.fg(Color.DarkGray)).length(1)
    ).onKey(Key.Esc)(Tui.quit)
end TodoApp

object TodoList extends Component.Emitting[TodoList.Props, TodoList.State, TodoList.Out]:
  final case class Props(items: Chunk[Item], title: String) derives CanEqual
  final case class State(selected: Int) derives CanEqual
  enum Out derives CanEqual:
    case Toggle(id: Long)
    case Delete(id: Long)

  def init(p: Props): State = State(0)

  /** Keep the selection inside the list when the parent removes items (Flutter didUpdateWidget). */
  override def propsChanged(before: Props, after: Props, s: State): State =
    State(s.selected.min(after.items.size - 1).max(0))

  def build(p: Props, s: State): Ui =
    val rows   = p.items.map(i => Line(s"${if i.done then "[x]" else "[ ]"} ${i.title}", if i.done then Style.crossedOut else Style.empty))
    val border = if hasFocus then Style.fg(Color.Yellow) else Style.empty
    ListView(rows, selected = if p.items.isEmpty then Absent else Present(s.selected))
      .block(Block.bordered().withTitle(p.title).withBorderStyle(border))
      .focusable("list")
      .onKeys {
        case Key.Up        => update(st => st.copy(selected = (st.selected - 1).max(0)))
        case Key.Down      => update(st => st.copy(selected = (st.selected + 1).min(p.items.size - 1).max(0)))
        case Key.Char(' ') => withSelected(p)(i => output(Out.Toggle(i.id)))
        case Key.Char('d') => withSelected(p)(i => output(Out.Delete(i.id)))
      }

  /** Reads the LATEST selection (not the build-time one) and runs `f` on the selected item, if any. */
  private def withSelected(p: Props)(f: Item => Unit < Emit[Out])(using Frame): Unit < (Var[State] & Emit[Out]) =
    current.map { st =>
      p.items.lift(st.selected) match
        case Some(item) => f(item)
        case None       => Kyo.unit      // not Option.fold: its first list would fix the type to Unit < Any
    }
end TodoList

object TodoMain extends TuiApp:
  run(Tui.run(TodoApp()))
```

Notes:

- `tab` is not handled by the app: no handler matches it, so the runtime moves the focus between the
  two focusables (`"input"` then `"list"`, in tree order).
- The focus region rule (2.8) is what makes typing `d` in the input insert a `d`: `TextField`'s
  `onChar` is in region `"input"`; `TodoList`'s `d` is in region `"list"`, which is not on the focus
  chain. `Esc` is app-level and works from both regions.
- Decorations of one element form one unit: `.focusable("list")` and `.onKeys {...}` work in either
  order, and `.length(3)` / `.key(k)` can be anywhere in the chain (the parent looks through the chain
  for the size). Without this rule `.onKeys(...).focusable(id)` and `.focusable(id).onKeys(...)` would
  mean different things, which is a trap for people and agents.
- `TodoList` never mutates the items: it emits `Toggle`/`Delete`, and the parent updates its state.
  The list is a "controlled" view of the items plus its own selection.

The built-in `TextField` (in `tui4s.components`), in outline. It is a normal component; users can copy it.

```scala
package tui4s.components

import kyo.*
import tui4s.*

object TextField extends Component.Emitting[TextField.Props, TextField.State, TextField.Out]:
  final case class Props(id: String, placeholder: String = "", autoFocus: Boolean = false) derives CanEqual
  /** `Graphemes` = an immutable vector of grapheme clusters (BreakIterator) with cached display widths. */
  final case class State(text: Graphemes, cursor: Int, scroll: Int) derives CanEqual:
    def insert(s: String): State = ...    // splits s into graphemes, inserts at cursor
    def backspace: State = ...; def delete: State = ...; def left: State = ...; def right: State = ...
    def home: State = ...; def end: State = ...
  enum Out derives CanEqual:
    case Submitted(text: String)

  def init(p: Props): State = State(Graphemes.empty, 0, 0)

  def build(p: Props, s: State): Ui =
    val body =
      if s.text.isEmpty && !hasFocus then Text(p.placeholder, Style.fg(Color.DarkGray))
      else Text(s.text.render(from = s.scroll))
    body
      .cursor(s.text.widthBetween(s.scroll, s.cursor), 0)
      .focusable(p.id, p.autoFocus)
      .onChar(c => update(_.insert(c.toString)))
      .onPaste(t => update(_.insert(t)))
      .onKeys {
        case Key.Backspace => update(_.backspace)
        case Key.Delete    => update(_.delete)
        case Key.Left      => update(_.left)
        case Key.Right     => update(_.right)
        case Key.Home      => update(_.home)
        case Key.End       => update(_.end)
        case Key.Enter     => current.map(st => output(Out.Submitted(st.text.mkString)).andThen(set(init(p))))
      }
    // horizontal scroll: a LayoutBuilder variant adjusts `scroll` to the real width (omitted here)
end TextField
```

### 3.3 Dashboard

The tick producer is a fiber that the app starts itself and connects through a `Channel`; the log
source is a Kyo `Stream`. The two panels are separate components with their own state and their own
subscriptions. `IndexedStack` keeps both panels mounted, so the hidden panel keeps collecting data, but
only the visible one is painted (and a change in the hidden one does not draw a frame, see 5.3).

```scala
package examples.dashboard

import kyo.*
import tui4s.*

type Source[A] = Stream[A, Async & Abort[Throwable]]

object Dashboard extends Component[Dashboard.Props, Dashboard.State]:
  final case class Props(samples: Source[Long], logs: Source[String]) derives CanEqual
  final case class State(tab: Int) derives CanEqual
  private val titles = Chunk("Metrics", "Logs")

  def init(p: Props): State = State(0)

  def build(p: Props, s: State): Ui =
    Column(
      Tabs(titles, selected = s.tab).bordered(" tui4s dashboard ").length(3),
      IndexedStack(s.tab)(
        MetricsPanel(MetricsPanel.Props(p.samples)),
        LogPane(LogPane.Props(p.logs, capacity = 500))
      ),
      Text(" left/right tabs   up/down scroll logs   q quit ", Style.fg(Color.DarkGray)).length(1)
    ).onKeys {
      case Key.Left      => update(st => st.copy(tab = (st.tab + titles.size - 1) % titles.size))
      case Key.Right     => update(st => st.copy(tab = (st.tab + 1) % titles.size))
      case Key.Char('q') => Tui.quit
    }
end Dashboard

object MetricsPanel extends Component[MetricsPanel.Props, MetricsPanel.State]:
  final case class Props(samples: Source[Long]) derives CanEqual
  final case class State(last: Long, history: Chunk[Long]) derives CanEqual:
    def push(v: Long): State = State(v, (history :+ v).takeRight(200))

  def init(p: Props): State = State(0L, Chunk.empty)

  override def subscriptions(p: Props, s: State): Chunk[Sub[Local]] =
    Chunk(Sub.stream("samples", p.samples)(v => update(_.push(v))))

  def build(p: Props, s: State): Ui =
    Column(
      Gauge(s.last / 100.0, label = s"${s.last}%").bordered(" Load ").length(3),
      Sparkline(s.history, max = Present(100L)).bordered(" Last 200 samples ")
    )
end MetricsPanel

object LogPane extends Component[LogPane.Props, LogPane.State]:
  final case class Props(source: Source[String], capacity: Int) derives CanEqual
  /** `back` = lines scrolled back from the end; 0 follows the tail. */
  final case class State(lines: Chunk[String], back: Int) derives CanEqual:
    def append(l: String, cap: Int): State = copy(lines = (lines :+ l).takeRight(cap))

  def init(p: Props): State = State(Chunk.empty, 0)

  override def subscriptions(p: Props, s: State): Chunk[Sub[Local]] =
    Chunk(Sub.stream("logs", p.source)(line => update(_.append(line, p.capacity))))

  def build(p: Props, s: State): Ui =
    LayoutBuilder { size =>
      val height = (size.height - 2).max(1)                     // inside the border
      val end    = (s.lines.size - s.back).max(0)
      Paragraph(s.lines.slice((end - height).max(0), end).mkString("\n"), wrap = false)
        .bordered(if s.back == 0 then " Logs (following) " else s" Logs (-${s.back}) ")
    }.onKeys {
      case Key.Up   => update(st => st.copy(back = (st.back + 1).min((st.lines.size - 1).max(0))))
      case Key.Down => update(st => st.copy(back = (st.back - 1).max(0)))
    }
end LogPane

// ---- sources ----

/** A fiber that produces a value every 250 ms (a random walk in 0..100). */
def produceSamples(out: Channel[Long])(using Frame): Unit < (Async & Abort[Closed]) =
  Loop(50L) { v =>
    Async.sleep(250.millis)
      .andThen(Random.nextInt(21))
      .map(d => (v + d - 10).max(0L).min(100L))
      .map(next => out.put(next).andThen(Loop.continue(next)))
  }

/** A log stream: one line every 400 ms. chunkSize = 1, or the stream waits for 4096 lines (kyo.md 2.8). */
def logLines(using Frame): Stream[String, Async] =
  Stream.unfold(1, chunkSize = 1) { i =>
    Async.sleep(400.millis).andThen(Clock.now).map(t => Present((s"$t  GET /api/items  200  (#$i)", i + 1)))
  }

object DashboardApp extends TuiApp:
  run {
    Channel.init[Long](64).map { samples =>
      Fiber.init(produceSamples(samples)).andThen(
        Tui.run(Dashboard(Dashboard.Props(samples.streamUntilClosed(), logLines)))
      )
    }
  }
```

Notes:

- `LogPane`'s `Up`/`Down` are app-level (no focusable), but `IndexedStack` makes a hidden child
  *offstage*: it stays mounted, its subscriptions run, but it gets no input and is not painted (Flutter's
  `Offstage`). So the arrows scroll the logs only on the Logs tab.
- `Channel.init` and `Fiber.init` need `Scope`, which `KyoApp.run` provides (VERIFIED: `run` takes
  `A < (Async & Scope & Abort[Any])`). When `Tui.run` returns, the scope closes, the producer fiber is
  interrupted and the channel is closed.
- `samples.streamUntilClosed()` is `Stream[Long, Async]` (VERIFIED signature in `Channel.scala`),
  which is a `Source[Long]` because `Stream`'s effect parameter is contravariant.
- The dashboard is a good test of the design's weak spot: a sample arrives every 250 ms and a log
  line every 400 ms, so about 6.5 frames per second are drawn while the app is otherwise idle. Each frame
  repaints everything (about 2 ms at 120x40, jatatui.md 5.3). That is fine, but section 7 discusses the
  cost and the optional subtree blit.


## 4. Counter and Dashboard for ZIO and Cats Effect users (facades)

### 4.1 The facade idea (same for both modules)

A ZIO or Cats user must not see `A < S`, `Var`, `Emit`, `Chunk`, `Maybe` or `kyo.Duration`. There is
also a real trap to avoid: Kyo lifts any plain value into `A < S`, so a `ZIO` or `IO` value passed to
the core `onKey(k)(h: => Any < R2)` would **compile and never run** (it would be treated as a pure
result). So the facades do not re-use the core `View` type in their public API. They define:

1. **`opaque type View = tui4s.View[Any]`** with their own extension methods (`onKey`, `bordered`,
   `length`, ...) whose handlers are `ZIO[Any, Throwable, Any]` or `IO[Any]`. The core methods are
   hidden by the opaque type. Layout and widget builders are thin forwarders with standard types
   (`Seq`, `Option`, `java.time.Duration` / `zio.Duration` / `FiniteDuration`).
2. **A Ref-like state handle.** Each facade component has `protected val state: UiState[S]` with
   `get`, `set`, `update`, `modify` returning `UIO`/`IO`. It reads the current `spi.Dispatch` from a
   ZIO `FiberRef` / a CE `IOLocal` that the facade sets around the handler, and checks that the dispatch
   belongs to this component (a clear error instead of a `ClassCastException` if a handler uses another
   component's `state`).
3. **Raw handlers.** Facade handlers become `View.Handler.Raw` nodes (2.2). The runtime runs a raw
   handler with the owner instance's `Dispatch`; the facade runs the ZIO / IO inside it through the
   bridge and then the runtime reads the new state from the dispatch, exactly as for Kyo handlers.
4. **The bridges.** ZIO: `AmbientZIOs` (F12): `Tui.run` captures the caller's `zio.Runtime` (with its
   FiberRefs and executor) in a Kyo `Local`; every handler runs with `AmbientZIOs.get` on that runtime.
   Cats: the vendored `CatsBridge` (F7, F8): `Tui.run` uses `runWithDispatcher` (Dispatcher created
   with **`await = false`**, F10); every handler runs with `getF` through that Dispatcher, so the IO
   runs on the caller's `IORuntime`.

```
 facade handler (ZIO / IO)                         core runtime (Kyo, UI fiber)
 ─────────────────────────                         ─────────────────────────────────────────
 .onKey(k)(state.update(_ + 1))  ──builds──►  View.Handle(Handler.Raw(ev => if ev matches k:
                                                 Present(d => AmbientZIOs.get(
                                                   dispatchRef.locally(d)(h))   // ZIO
                                                 // CatsBridge.getF(local.set(d) >> h)   // Cats
                                               )))
 state.update(f): UIO[Unit] = dispatchRef.get.flatMap(d => ZIO.succeed(d.setState(f(d.state))))
```

The `Dispatch` object is touched by the ZIO / IO fiber while the Kyo UI fiber waits for the bridge's
promise, so only one side uses it at a time, and the promise completion orders the writes before the UI
fiber reads the new state.

### 4.2 `tui4s-zio` public API (package `tui4s.zio`)

```scala
package tui4s.zio

import _root_.zio.{Duration, Trace, UIO, ZIO}
import _root_.zio.stream.ZStream

opaque type View = tui4s.View[Any]
object View:
  extension (v: View)
    def key(k: String): View
    def length(n: Int): View;  def fill(w: Int = 1): View;  def percent(p: Int): View
    def min(n: Int): View;     def max(n: Int): View
    def bordered(title: String = "", tpe: BorderType = BorderType.Rounded): View
    def block(b: Block): View; def style(s: Style): View;  def padding(h: Int, v: Int = 0): View
    def focusable(id: String, autoFocus: Boolean = false): View
    def cursor(x: Int, y: Int): View
    def onKey(k: KeyPress)(h: => ZIO[Any, Throwable, Any])(using Trace): View
    def onKeys(pf: PartialFunction[KeyPress, ZIO[Any, Throwable, Any]])(using Trace): View
    def onChar(h: Char => ZIO[Any, Throwable, Any])(using Trace): View
    def onPaste(h: String => ZIO[Any, Throwable, Any])(using Trace): View
    def onClick(h: Mouse => ZIO[Any, Throwable, Any])(using Trace): View

// layout and widgets: the same names as the core, standard types
def Column(children: View*): View;  def Row(children: View*): View;  def Layers(children: View*): View
def IndexedStack(selected: Int)(children: View*): View;  def LayoutBuilder(f: Size => View): View
def Text(s: String, style: Style = Style.empty): View
def Paragraph(text: String, wrap: Boolean = true, scroll: Int = 0, centered: Boolean = false): View
def Gauge(ratio: Double, label: String = ""): View
def Sparkline(data: Seq[Long], max: Option[Long] = None): View
def Tabs(titles: Seq[String], selected: Int): View
def ListView(items: Seq[Line], selected: Option[Int], highlight: Style = Style.reversed): View

final class UiState[S] private[zio] (owner: tui4s.spi.ComponentBase[?, S, ?]):
  def get(using Trace): UIO[S]
  def set(s: S)(using Trace): UIO[Unit]
  def update(f: S => S)(using Trace): UIO[Unit]
  def modify[B](f: S => (B, S))(using Trace): UIO[B]

object Component:
  abstract class Emitting[P, S, O](using CanEqual[S, S], CanEqual[P, P])
      extends tui4s.spi.ComponentBase[P, S, O]:
    type Ui = BuildContext ?=> View
    def init(props: P): S
    def build(props: P, state: S): Ui
    def subscriptions(props: P, state: S): Seq[Sub] = Nil
    def propsChanged(before: P, after: P, state: S): S = state
    protected final val state: UiState[S]
    protected final def output(o: O)(using Trace): UIO[Unit]
    protected def hasFocus(using BuildContext): Boolean
    final def apply(props: P): Mount[O]
    final def apply()(using Unit <:< P): Mount[O]
abstract class Component[P, S](using CanEqual[S, S], CanEqual[P, P]) extends Component.Emitting[P, S, Nothing]

opaque type Mount[+O] <: View = tui4s.View[Any]
extension [O](m: Mount[O]) def onOutput(f: O => ZIO[Any, Throwable, Any])(using Trace): View

opaque type Sub = tui4s.Sub[Any]
object Sub:
  def every(key: String, period: Duration)(h: => ZIO[Any, Throwable, Any])(using Trace): Sub
  def stream[A](key: String, source: => ZStream[Any, Throwable, A])(h: A => ZIO[Any, Throwable, Any])(using Trace): Sub
  def onMount(key: String)(h: => ZIO[Any, Throwable, Any])(using Trace): Sub

object Tui:
  def quit(using Trace): UIO[Unit]
  def focus(id: String)(using Trace): UIO[Unit]
  final case class Config(maxFps: Int = 60, mouse: Boolean = false, tabMovesFocus: Boolean = true,
                          ctrlCQuits: Boolean = true, pollInterval: Duration = Duration.fromMillis(25))
  def run(root: View, config: Config = Config())(using Trace): ZIO[Any, TuiError, Unit]
  def runFor[O](root: Mount[O], config: Config = Config())(using Trace): ZIO[Any, TuiError, Option[O]]

trait TuiApp extends _root_.zio.ZIOAppDefault:
  tui4s.Tui.tuneScheduler()      // trait body: runs at object construction, before main

// re-exported tui4s types (plain Scala, no Kyo): KeyPress, Key, Mods, Mouse, Event, BuildContext,
// TuiError, Style, Color, Line, Block, BorderType, Size (as type aliases + vals for the objects)
```

### 4.3 How the ZIO facade maps onto the core

| facade                                   | core                                                                                  |
| ---------------------------------------- | ------------------------------------------------------------------------------------- |
| `Tui.run(root, cfg)`                     | `AmbientZIOs.run(tui4s.Tui.run(root, cfg.toCore))` -> `ZIO[Any, TuiError, Unit]` (typed error, F11) |
| handler `ZIO[Any, Throwable, Any]`       | `Handler.Raw(... d => AmbientZIOs.get(dispatchRef.locally(d)(h.unit)))`              |
| `state.update(f)`                        | `dispatchRef.get` + owner check + `d.setState(f(d.state))`                            |
| `output(o)` / `Tui.quit`                 | `d.emitOutput(o)` / `d.command(Tui.Cmd.Quit)`                                        |
| `Sub.stream(key, zstream)(h)`            | raw stream sub; the ZStream runs on the caller runtime with `AmbientZIOs.get(zs.runForeachChunk(post))`, where `post` waits for `channel.unsafe.putFiber` (backpressure). `ZStreams.get` is not used, because it would run on `Runtime.default` (F12). |
| `Sub.every(key, d)(h)`                   | core `Sub.every(key, d.toKyo)` with a raw handler                                     |
| fiber interrupt of `Tui.run`             | `ZIOs.run` interrupts the Kyo fiber and waits (F11); the terminal restore is a synchronous release, so it is done when the ZIO interruption completes |
| `zio.Duration`                           | `kyo.Duration.fromJava(d)` (UNVERIFIED name; `fromNanos(d.toNanos)` works in any case) |

### 4.4 Counter and Dashboard with ZIO (no Kyo types)

```scala
package examples.zio

import zio.*
import zio.stream.*
import tui4s.zio.*

object Counter extends Component[Unit, Int]:
  def init(props: Unit): Int = 0

  def build(props: Unit, n: Int): Ui =
    Column(
      Paragraph(s"Count: $n", centered = true),
      Text(" + / - change   q quit ", Style.fg(Color.DarkGray)).length(1)
    ).bordered(" Counter ")
      .onKeys {
        case Key.Char('+') => state.update(_ + 1)
        case Key.Char('-') => state.update(_ - 1)
        case Key.Char('q') => Tui.quit
      }
end Counter

object CounterApp extends TuiApp:
  def run = Tui.run(Counter())
```

```scala
package examples.zio

import zio.*
import zio.stream.*
import tui4s.zio.*

type Source[A] = ZStream[Any, Throwable, A]

object Dashboard extends Component[Dashboard.Props, Dashboard.State]:
  final case class Props(samples: Source[Long], logs: Source[String]) derives CanEqual
  final case class State(tab: Int) derives CanEqual
  private val titles = Vector("Metrics", "Logs")

  def init(p: Props): State = State(0)

  def build(p: Props, s: State): Ui =
    Column(
      Tabs(titles, selected = s.tab).bordered(" tui4s dashboard ").length(3),
      IndexedStack(s.tab)(
        MetricsPanel(MetricsPanel.Props(p.samples)),
        LogPane(LogPane.Props(p.logs, capacity = 500))
      ),
      Text(" left/right tabs   up/down scroll logs   q quit ", Style.fg(Color.DarkGray)).length(1)
    ).onKeys {
      case Key.Left      => state.update(st => st.copy(tab = (st.tab + titles.size - 1) % titles.size))
      case Key.Right     => state.update(st => st.copy(tab = (st.tab + 1) % titles.size))
      case Key.Char('q') => Tui.quit
    }
end Dashboard

object MetricsPanel extends Component[MetricsPanel.Props, MetricsPanel.State]:
  final case class Props(samples: Source[Long]) derives CanEqual
  final case class State(last: Long, history: Vector[Long]) derives CanEqual:
    def push(v: Long): State = State(v, (history :+ v).takeRight(200))

  def init(p: Props): State = State(0L, Vector.empty)

  override def subscriptions(p: Props, s: State): Seq[Sub] =
    Seq(Sub.stream("samples", p.samples)(v => state.update(_.push(v))))

  def build(p: Props, s: State): Ui =
    Column(
      Gauge(s.last / 100.0, label = s"${s.last}%").bordered(" Load ").length(3),
      Sparkline(s.history, max = Some(100L)).bordered(" Last 200 samples ")
    )
end MetricsPanel

object LogPane extends Component[LogPane.Props, LogPane.State]:
  final case class Props(source: Source[String], capacity: Int) derives CanEqual
  final case class State(lines: Vector[String], back: Int) derives CanEqual

  def init(p: Props): State = State(Vector.empty, 0)

  override def subscriptions(p: Props, s: State): Seq[Sub] =
    Seq(Sub.stream("logs", p.source)(line => state.update(st => st.copy(lines = (st.lines :+ line).takeRight(p.capacity)))))

  def build(p: Props, s: State): Ui =
    LayoutBuilder { size =>
      val height = (size.height - 2).max(1)
      val end    = (s.lines.size - s.back).max(0)
      Paragraph(s.lines.slice((end - height).max(0), end).mkString("\n"), wrap = false)
        .bordered(if s.back == 0 then " Logs (following) " else s" Logs (-${s.back}) ")
    }.onKeys {
      case Key.Up   => state.update(st => st.copy(back = (st.back + 1).min((st.lines.size - 1).max(0))))
      case Key.Down => state.update(st => st.copy(back = (st.back - 1).max(0)))
    }
end LogPane

def produceSamples(out: Queue[Long]): UIO[Nothing] =
  Ref.make(50L).flatMap { last =>
    (Random.nextIntBetween(-10, 11)
      .flatMap(d => last.updateAndGet(v => (v + d).max(0L).min(100L)))
      .flatMap(out.offer) *> ZIO.sleep(250.millis)).forever
  }

val logLines: Source[String] =
  ZStream.iterate(1)(_ + 1).schedule(Schedule.spaced(400.millis))
    .mapZIO(i => Clock.instant.map(t => s"$t  GET /api/items  200  (#$i)"))

object DashboardApp extends TuiApp:
  def run =
    for
      samples <- Queue.bounded[Long](64)
      _       <- produceSamples(samples).forkScoped
      _       <- Tui.run(Dashboard(Dashboard.Props(ZStream.fromQueue(samples), logLines)))
    yield ()
```

The ZIO version is line-for-line the Kyo version with `state.update` in place of `update`, `Seq`/
`Vector`/`Option` in place of `Chunk`/`Maybe`, and ZIO sources. The for-comprehension is ZIO's, not
Kyo's, so F1 does not apply.

### 4.5 `tui4s-cats` public API (package `tui4s.cats`)

The same shape as 4.2 with `IO` (the facade is IO-first; see 8.3 for tagless `F[_]`):

```scala
package tui4s.cats

import _root_.cats.effect.{IO, IOApp}
import scala.concurrent.duration.FiniteDuration

opaque type View = tui4s.View[Any]
object View:
  extension (v: View)
    def onKey(k: KeyPress)(h: => IO[Any]): View
    def onKeys(pf: PartialFunction[KeyPress, IO[Any]]): View
    def onChar(h: Char => IO[Any]): View
    // ... the same layout / decoration / focus methods as tui4s.zio.View

final class UiState[S]:
  def get: IO[S]; def set(s: S): IO[Unit]; def update(f: S => S): IO[Unit]; def modify[B](f: S => (B, S)): IO[B]

object Component:
  abstract class Emitting[P, S, O](using CanEqual[S, S], CanEqual[P, P]) extends tui4s.spi.ComponentBase[P, S, O]:
    type Ui = BuildContext ?=> View
    def init(props: P): S
    def build(props: P, state: S): Ui
    def subscriptions(props: P, state: S): Seq[Sub] = Nil
    protected final val state: UiState[S]
    protected final def output(o: O): IO[Unit]
abstract class Component[P, S](using CanEqual[S, S], CanEqual[P, P]) extends Component.Emitting[P, S, Nothing]

object Sub:
  def every(key: String, period: FiniteDuration)(h: => IO[Any]): Sub
  def stream[A](key: String, source: => fs2.Stream[IO, A])(h: A => IO[Any]): Sub
  def onMount(key: String)(h: => IO[Any]): Sub

object Tui:
  val quit: IO[Unit]
  def focus(id: String): IO[Unit]
  def run(root: View, config: Config = Config()): IO[Unit]                // fails with TuiError (a Throwable)
  def runFor[O](root: Mount[O], config: Config = Config()): IO[Option[O]]

trait TuiApp extends IOApp.Simple:
  tui4s.Tui.tuneScheduler()
```

Mapping onto the core:

| facade                         | core                                                                                         |
| ------------------------------ | -------------------------------------------------------------------------------------------- |
| `Tui.run(root, cfg)`           | `CatsBridge.runWithDispatcher { d ?=> dispatcherLocal.let(d)(tui4s.Tui.run(root, cfg.toCore)) }`. The core row is `Abort[TuiError] & Async`, which fits the bridge's `Abort[Throwable] & Async` because `Abort[-E]` is contravariant (VERIFIED `Abort.scala`: `sealed trait Abort[-E]`). `Dispatcher.parallel(await = false)` (F10). |
| handler `IO[Any]`              | `Handler.Raw(... d => dispatcherLocal.use(disp => CatsBridge.getF(dispatchLocal.set(d) >> h)(using disp)))`: the IO runs on the caller's runtime; `IOLocal` is set inside the same IO fiber that runs `h` |
| `state.update(f)`              | `dispatchLocal.get` + owner check + `d.setState(...)`. The `IOLocal` key is created once per JVM inside the first `Tui.run` (an `IOLocal` value is per fiber, so one key can serve concurrent runs) |
| `Sub.stream(key, fs2)(h)`      | raw stream sub whose source fiber runs `CatsBridge.getF(s.chunks.evalMap(post).compile.drain)`; `post` = `IO.async_` on `channel.unsafe.putFiber(...).onComplete` (backpressure) |
| cancel of the `IO` running `Tui.run` | `runF` cancels the Kyo fiber and waits (cancel-and-await, F8); the terminal restore is a synchronous release, so it is done when the cancel returns |

### 4.6 Counter and Dashboard with Cats Effect (no Kyo types)

```scala
package examples.cats

import cats.effect.*
import tui4s.cats.*

object Counter extends Component[Unit, Int]:
  def init(props: Unit): Int = 0

  def build(props: Unit, n: Int): Ui =
    Column(
      Paragraph(s"Count: $n", centered = true),
      Text(" + / - change   q quit ", Style.fg(Color.DarkGray)).length(1)
    ).bordered(" Counter ")
      .onKeys {
        case Key.Char('+') => state.update(_ + 1)
        case Key.Char('-') => state.update(_ - 1)
        case Key.Char('q') => Tui.quit
      }
end Counter

object CounterApp extends TuiApp:
  def run: IO[Unit] = Tui.run(Counter())
```

```scala
package examples.cats

import cats.effect.*
import cats.effect.std.{Queue, Random}
import scala.concurrent.duration.*
import tui4s.cats.*

type Source[A] = fs2.Stream[IO, A]

// Dashboard, MetricsPanel and LogPane: identical to 4.4 (state.update returns IO[Unit] here),
// with `Seq[Sub]` subscriptions built from fs2 streams:
//   override def subscriptions(p: Props, s: State): Seq[Sub] =
//     Seq(Sub.stream("samples", p.samples)(v => state.update(_.push(v))))

def produceSamples(out: Queue[IO, Long]): IO[Nothing] =
  (Random.scalaUtilRandom[IO], Ref.of[IO, Long](50L)).tupled.flatMap { (rnd, last) =>
    (rnd.betweenInt(-10, 11)
      .flatMap(d => last.updateAndGet(v => (v + d).max(0L).min(100L)))
      .flatMap(out.offer) >> IO.sleep(250.millis)).foreverM
  }

val logLines: Source[String] =
  fs2.Stream.iterate(1)(_ + 1).metered[IO](400.millis)
    .evalMap(i => IO.realTimeInstant.map(t => s"$t  GET /api/items  200  (#$i)"))

object DashboardApp extends TuiApp:
  def run: IO[Unit] =
    Queue.bounded[IO, Long](64).flatMap { samples =>
      produceSamples(samples).background.surround(
        Tui.run(Dashboard(Dashboard.Props(fs2.Stream.fromQueueUnterminated(samples), logLines)))
      )
    }
```

(`.tupled` on a tuple of `IO`s needs `cats.syntax.all.*`; the CE / fs2 names here are standard
cats-effect 3 / fs2 3 API, not checked against sources in this panel: UNVERIFIED.)

### 4.7 What the facades cost and what they cannot hide

- **Duplicated surface.** Each facade re-declares the view methods and builders (about 60 forwarders).
  It is mechanical code, and a test in each facade can check that every core builder has a forwarder.
- **Runtime check instead of a type check.** In Kyo, using the wrong component's state is a compile
  error (`Var[A]` vs `Var[B]`). In the facades, `state` is a member, so `OtherComponent.state` is not
  in scope by accident; but a helper that receives a `UiState[S]` from elsewhere could still be called
  in the wrong handler, and that is detected only at run time (owner check).
- **One hop per handler.** Each facade handler crosses into ZIO / CE and back (a fork on the caller
  runtime and a promise join). That is microseconds per key press, but it is more than a Kyo handler
  (which runs inline on the UI fiber). Pure state updates could skip the hop: `onKey(k)(Pure(f))` is a
  possible optimization (open question 8.3).
- **Stack traces** of a failing facade handler cross the bridge; the `TuiError.HandlerFailed` keeps the
  original cause.


## 5. Runtime model

### 5.1 Threads and fibers

```
                ┌──────────────────────────── Sync.acquireReleaseWith (terminal) ─────────────────────────────┐
                │                                                                                             │
 crossterm ──►  │ input thread "tui4s-input" (platform, daemon)                                                │
 (JNI)          │   loop: if stop then exit; if poll(25 ms) then read every queued event (poll(0), max 512)    │
                │         -> convert to tui4s.Event, drop KeyEventKind.Release                                │
                │         -> channel.unsafe.putBatchFiber(Msg.Input(batch)) and WAIT (backpressure)            │
                │                                                                                             │
                │   ┌─────────────── Scope.run ─────────────────────────────────────────────────────────┐     │
                │   │ UI fiber (the fiber that runs Tui.run): owns EVERYTHING mutable                   │     │
                │   │   Terminal + its two Buffers, instance table, focus table, PaintSlots             │     │
                │   │   loop: take -> drain -> apply (dispatch, rebuild, commit) -> paint if dirty      │     │
                │   │                                                                                   │     │
                │   │ per-instance Scope: Sub fibers, task fibers -> only POST messages to the channel  │     │
                │   └───────────────────────────────────────────────────────────────────────────────────┘     │
                └─────────────────────────────────────────────────────────────────────────────────────────────┘
 user fibers (producers) ──► Channel / SignalRef / Stream ──► Sub fibers
```

- **Input on a platform thread, not a Kyo fiber.** `read()` is a JNI call that interrupts cannot stop;
  a dedicated thread does not pin a Kyo worker, and the terminal release can stop and join it
  synchronously. The probe measured this shape ("platform thread, batched, coalescing": ~7.0 us/event,
  VERIFIED `loop2.log`), the same order as the Kyo-fiber input (~6.7 us/event). Its one cost is the
  quit latency (one poll interval), so the poll interval is 25 ms instead of 50 ms.
- **One UI fiber.** Every handler, every `build`, every reconcile and every paint runs on it. Kyo may
  move the fiber between worker threads at a park point; the scheduler hand-off orders the memory, so
  the single-owner rule for jatatui's mutable objects holds.
- **Sub and task fibers only post.** A source fiber never touches state. It sends
  `Msg.Deliver(instance, generation, run)`; the UI fiber runs `run` with the instance's state.

```scala
private[tui4s] enum Msg:
  case Input(events: Chunk[Event])
  case Deliver(target: InstanceRef, generation: Long, run: spi.Dispatch => Unit < (Async & Abort[Throwable]))
  case Tick(target: InstanceRef, generation: Long, sub: String)   // timer; at most one pending per Sub
  case SubFailed(target: InstanceRef, generation: Long, sub: String, cause: Throwable)
```

The channel is `Channel.init[Msg](cfg.queueCapacity, Access.MultiProducerSingleConsumer)` (VERIFIED
signature and the probe's choice). The input thread and stream subs use `put` (backpressure); timers
use `offer` guarded by a per-sub "pending" flag, so a slow UI never accumulates ticks.

### 5.2 The UI loop

The probe's production shape (CONTEXT.md, `loop.scala`), with the component work in `apply`:

```scala
private def loop(rt: Runtime)(using Frame): Unit < (Async & Abort[TuiError]) =
  Loop(0L) { lastPaint =>
    rt.channel.take.map { first =>
      rt.channel.drain.map(rest => rt.applyAll(first +: rest)).map {
        case Step.Quit                    => Loop.done(())
        case Step.Idle                    => Loop.continue(lastPaint)          // nothing visible changed
        case Step.PaintNeeded             =>
          rt.frameWait(lastPaint)                                              // sleep the rest of the budget,
            .andThen(rt.channel.drain).map(rt.applyAll)                        // fold in what arrived meanwhile,
            .map(step => if step == Step.Quit then Loop.done(()) else rt.paint.map(Loop.continue(_)))
      }
    }
  }
```

(`Abort[Closed]` of the channel is mapped to a normal stop; it only happens during shutdown.)

`applyAll(batch)` processes the messages in order. For each one:

1. **Dispatch.** Route the event (2.8) or the delivery to one handler owner. Run the handler:
   `Abort.run[Throwable](Var.runTuple(state)(Emit.run[O](Emit.run[Tui.Cmd](h))))` for a Kyo component
   (for `O = Nothing` the runtime skips `Emit.run[O]`), or the raw handler with a `Dispatch` for a facade
   component. Store the new state. If `!(new == old)`, mark the instance **rebuild-dirty**.
2. **Outputs.** Each output goes to the parent's `onOutput` handler, which runs the same way against the
   parent's state (recursively up the tree).
3. **Commands.** `Quit` ends the batch. `Focus*` changes the focus table. `Task` forks the work in the
   emitting instance's `Scope`. `Redraw` sets a full-repaint flag.
4. **Reconcile, if anything is rebuild-dirty, before the next message.** This is the answer to stale
   closures: a handler can capture build-time values (`p.items`, a draft text), and the next key in the
   same batch must see handlers built from the new state. Reconcile does not paint, so it is cheap.
5. **Commit.** Start / stop `Sub` fibers (diff by key), close the scopes of unmounted instances, and
   sweep their `PaintSlots`.

### 5.3 When a frame is drawn: dirty tracking and coalescing

Two flags:

| flag              | set by                                                               | effect                         |
| ----------------- | -------------------------------------------------------------------- | ------------------------------ |
| rebuild-dirty     | a state change (`!=`), new props (`!=`), a changed `BuildContext` read (focus, size) | `build` runs again for that instance only |
| paint-dirty       | a rebuilt instance that is **on stage** (painted in the last frame, or newly mounted and on stage), a focus move, a resize, `Redraw`, the first frame | one frame is drawn |

- A handler that leaves the state equal draws nothing. A change in an offstage instance (a hidden
  `IndexedStack` child) rebuilds that instance (cheap) and draws nothing.
- **Coalescing.** The frame budget is `1000 / maxFps` ms (16 ms by default). The first change after an
  idle period paints immediately. A change less than one budget after the last paint makes the UI fiber
  sleep for the rest of the budget, drain what arrived meanwhile, apply it, and paint once. 10 000
  queued keys gave 3 frames in the probe (VERIFIED `loop2.log`).
- **Incremental rebuild.** Reconcile walks down from the root but skips any clean instance with equal
  props and no dirty descendant (each instance keeps a count of dirty descendants). A rebuilt instance
  re-matches its child `Mount` nodes: same slot and equal props -> the child is reused without a walk.
- **Identity.** A child's slot = (parent instance, the path of child indexes from the parent's view root
  to the `Mount`, with `.key(k)` replacing the index, component class). Same slot -> same instance and
  same state. A slot that is not visited -> unmount. A different component class at the same position ->
  a different slot -> unmount and mount (jatatui-react's type-change rule, VERIFIED in
  `RenderContext.reconcile`).

### 5.4 Painting

`paint` is one `Sync.Unsafe.defer { terminal.draw(frame => painter.run(frame)) }` (one unsafe region
per frame, kyo.md 4.3). The painter walks the cached trees (it never calls `build`, except for
`LayoutBuilder`, which needs the real area):

- `Linear` -> `Layout.vertical/horizontal(...).split(area)` (cached by jatatui; the returned `Rect[]`
  is read, never stored or written: it is the shared cache array, VERIFIED jatatui.md 2.4).
- `Boxed` -> `block.render(area, buf)` then the child in `block.inner(area)`.
- `Leaf` -> `paint(area, buf, slots)`.
- `Layers` / `Popup` -> children in order, each in a higher paint layer.
- `Indexed` -> only the selected child (the others are offstage).
- Every node with a mouse handler or a focus id records `(rect, layer, seq)` for hit-testing.
- The focused node's `Cursor` sets `frame.setCursorPosition(x, y)`; otherwise jatatui hides the cursor
  (VERIFIED `Terminal.draw`: `cursorPosition.isEmpty() -> hideCursor()`).

### 5.5 Who owns the mutable jatatui objects

| object                                           | owner                          | rule                                                     |
| ------------------------------------------------ | ------------------------------ | -------------------------------------------------------- |
| `Terminal`, its two `Buffer`s, `Frame`           | UI fiber, inside `paint`       | never leaves the draw callback                           |
| `ListState`, `TableState`, `ScrollbarState`      | `PaintSlots` (runtime), per leaf identity | created once; `select(...)` from the user's `selected` before each render; swept when the leaf's instance unmounts |
| `Layout.split` result (`Rect[]`, shared cache)   | jatatui                        | read in place, not stored                                |
| `Buffer.diff` live cells, `CompletedFrame.buffer`| jatatui                        | tui4s never reads them (the testkit reads `TestBackend`'s own copied buffer) |
| `CrosstermJni` poll / read                       | input thread                   | the only reader                                          |
| `CrosstermJni` enqueue / execute / flush         | UI fiber (through `CrosstermBackend`) and the release | never concurrent with each other              |
| widgets, `Text`, `Style`, `Line`                 | nobody (immutable values)      | built freely during paint                                |

### 5.6 Terminal setup, restore, interruption (F8)

```scala
def run(root: View[Tui.Base], cfg: Config)(using Frame): Unit < (Async & Abort[TuiError]) =
  Term.loadNative.andThen(                                         // load CrosstermJni first: JEP 472 warning or
    Sync.acquireReleaseWith(Term.enter(cfg))(Term.exit) { term =>  // NativeAccess error before raw mode
      Scope.run(
        Channel.init[Msg](cfg.queueCapacity, Access.MultiProducerSingleConsumer).map { ch =>
          term.startInput(ch).andThen(Runtime.init(term, ch, root, cfg).map(loop))
        }
      )
    }
  )
```

- `Term.enter`: check that stdin/stdout are TTYs (`System.console`, UNVERIFIED as the right test with
  JDK 22+ where `System.console()` may be non-null for redirected streams), `enableRawMode`,
  `execute(EnterAlternateScreen, Hide, [EnableMouseCapture], [EnableBracketedPaste])`,
  `Terminal.create(CrosstermBackend(jni))` or `Terminal.withOptions(backend,
  TerminalOptions(Viewport.inline(h)))` (VERIFIED names, jatatui.md 2.8, 4.5).
- `Term.exit` (synchronous): (1) set the input thread's stop flag and `join` it (bounded: 2 x poll
  interval + 50 ms), so it cannot poll stdin after raw mode is off; (2) `execute(DisableBracketedPaste,
  DisableMouseCapture, Show, LeaveAlternateScreen)`; (3) `disableRawMode`. Each step runs even if an
  earlier one failed; failures are logged, never printed while the screen is in raw mode.
- **Why `Sync.acquireReleaseWith` and not `Scope`**: on interrupt, `Scope` finalizers are drained on a
  detached fiber that is not awaited, but synchronous releases are done before the fiber's result is
  visible (F8, VERIFIED by the probe: "RESTORE TERMINAL ... (Sync.ensure)" before "interruptAwait
  returned"). So `fiber.interruptAwait`, `ZIOs.run`'s interrupt-and-await and `CatsBridge.runF`'s
  cancel-and-await all return with the terminal already restored.
- Component scopes, sub fibers and task fibers live in the inner `Scope.run`. After an interrupt they
  may be finalized a little later, on a detached fiber (F8); that is harmless, because they only post to
  a channel that is closed by then.
- Ctrl-C in raw mode is a key, not SIGINT (raw mode turns ISIG off), so `ctrlCQuits` handles it. An
  optional `Config.restoreOnJvmExit` adds an idempotent shutdown hook (guarded by an `AtomicBoolean`) for
  apps that call `System.exit` inside a handler; it is removed when `Tui.run` returns.

### 5.7 Errors

| failure                                   | `ErrorPolicy.Crash` (default)                       | `ErrorPolicy.Report(log)`                       |
| ----------------------------------------- | --------------------------------------------------- | ----------------------------------------------- |
| handler fails (`Abort[Throwable]` or panic) | `TuiError.HandlerFailed(name, cause)`; terminal restored; `Tui.run` fails | that run's state change is dropped; `log` is called; the app continues |
| `build` throws                            | `TuiError.BuildFailed(name, cause)`                 | the instance keeps its previous tree            |
| a `Sub` stream fails                      | `HandlerFailed`                                      | the sub stops; `log` is called                  |
| jatatui / crossterm I/O in `paint`        | `TuiError.Io(cause)` (always)                        | same                                            |

Nothing is written to stdout or stderr while the TUI runs. `Tui.run` runs its body under `Log.let(...)`
(VERIFIED `Log.let(log: Log)(f)`) with a logger that writes to `Config.logFile` or keeps the last lines
in memory and prints them to stderr after the restore. Kyo's default console logger would otherwise
draw over the screen.

### 5.8 Idle CPU

When nothing happens: no frames, the UI fiber is parked on `channel.take`, the input thread wakes 40
times a second inside a JNI `poll`, and no timer runs unless a `Sub.every` is mounted. The remaining
cost is the Kyo scheduler itself: ~13% of one core with defaults, 4-8% with the flags of F13
(VERIFIED, `idle-output.txt`), and ~2-2.5% is the hard-coded 1 ms clock thread.

`Tui.tuneScheduler()` sets these system properties when they are absent (they are `StaticFlag`s read
once at class init, F13):

```
kyo.scheduler.cycleIntervalNs=10000000
kyo.scheduler.blockingMonitorIntervalNs=20000000
kyo.scheduler.regulator.concurrencyCollectIntervalMs=100
```

`TuiApp` (all three modules) calls it in its constructor body, before `main`. This works only if no Kyo
scheduler class was initialized before; for `KyoApp` the constructor only registers the `run` block
(VERIFIED `KyoApp.Base`: `main` runs `initCode`), but whether `KyoAppRunnerWithInterrupts` touches the
scheduler at construction is UNVERIFIED. The fallback is documented: put the three `-D` flags in
`javaOptions`. The effect of a 10 ms worker cycle on input latency under load is also UNVERIFIED.

### 5.9 Resize, inline viewport

- `Event.Resize` sets paint-dirty and rebuild-dirty for the instances that read `terminalSize`.
  `Terminal.draw` calls `autoresize()` first, which resizes both buffers (VERIFIED in `Terminal.java`),
  so the next frame is complete.
- `Viewport.Inline(h)` draws below the prompt without the alternate screen (VERIFIED: the Inline
  viewport and `insertBefore` work headless, jatatui.md 3.8). `Tui.printAbove(lines)` (a command) maps to
  `terminal.insertBefore` for "log above the live area" UIs.


## 6. Testing and agent-friendliness

### 6.1 Three levels of tests, none needs a terminal

| level | what it checks | tool | cost |
| ----- | -------------- | ---- | ---- |
| 1. state logic | `State.add`, `push`, `toggle` | plain functions on case classes | trivial |
| 2. one transition | "key `+` in state 3 gives state 4", "Enter emits `Submitted`" | `tui4s.testkit.Probe` | builds one view, runs one handler; no runtime |
| 3. app behaviour and screen | focus, routing, children, subscriptions, rendering | `tui4s.testkit.TestTui` on jatatui `TestBackend` | the real runtime, headless, deterministic |

```scala
package tui4s.testkit

/** Level 2: build the component's view with a stub BuildContext, route one key, run the handler. */
object Probe:
  def key[P, S, O](c: Component.Emitting[P, S, O], props: P, state: S, k: KeyPress,
                   focus: Maybe[String] = Absent)(using Frame): Transition[S, O] < (Async & Abort[Throwable])
final case class Transition[S, O](state: S, outputs: Chunk[O], commands: Chunk[Tui.Cmd], handled: Boolean)

/** Level 3: the real runtime with a TestBackend. No input thread; each call runs on the caller's fiber. */
final class TestTui[O] private[testkit] (/* runtime + TestBackend */):
  def press(keys: KeyPress*)(using Frame): Unit < Async        // route + handlers + reconcile (like one batch)
  def typeText(s: String)(using Frame): Unit < Async            // one Key per char
  def paste(s: String)(using Frame): Unit < Async
  def click(x: Int, y: Int)(using Frame): Unit < Async
  def resize(width: Int, height: Int)(using Frame): Unit < Async
  def settle(using Frame): Unit < Async                         // run pending deliveries (subs, tasks) until idle
  def screen(using Frame): Screen < Sync                        // paint into the TestBackend, return the text
  def focused(using Frame): Maybe[String] < Sync
  def outputs(using Frame): Chunk[O] < Sync
  def quitRequested(using Frame): Boolean < Sync
  def stateOf[S](c: Component.Emitting[?, S, ?])(using Frame): Maybe[S] < Sync   // first mounted instance
  def tree(using Frame): String < Sync                          // instance tree dump (6.3)
  def explainKey(k: KeyPress)(using Frame): String < Sync       // the route a key would take (6.3)

object TestTui:
  def apply[P, S, O](root: View.Mount[P, S, O, Any], width: Int = 80, height: Int = 24,
                     config: Tui.Config = Tui.Config())(using Frame): TestTui[O] < (Sync & Scope)

final case class Screen(lines: Chunk[String]):
  def contains(s: String): Boolean
  def row(y: Int): String
  def styleAt(x: Int, y: Int): Style
  override def toString: String = lines.mkString("\n")
```

`Screen` is read from `TestBackend.buffer()` with `cellAt(x, y).symbol()` per row; `TestBackend.draw`
copies cells, so there is no aliasing (VERIFIED jatatui.md 2.9). Timers and sleeping sources use
`Async.sleep`, which goes through `Clock.sleep` (VERIFIED `Async.scala` line 161), so
`Clock.withTimeControl` (VERIFIED, `advance(duration)`) makes subscription tests deterministic.

### 6.2 Example tests (kyo-test, F6)

```scala
package examples

import kyo.*
import kyo.test.*            // UNVERIFIED package of kyo-test-api; the shape is from the RC7 README (F6)
import tui4s.*
import tui4s.testkit.*
import examples.counter.Counter
import examples.todo.*
import examples.dashboard.*

class CounterTest extends Test[Any]:
  "Counter" - {
    "one transition: + increments" in {
      Probe.key(Counter, (), 3, Key('+')).map(t => assert(t.state == 4 && t.handled))
    }
    "q emits Quit and keeps the state" in {
      Probe.key(Counter, (), 3, Key('q')).map(t => assert(t.state == 3 && t.commands == Chunk(Tui.Cmd.Quit)))
    }
    "screen after + + -" in {
      TestTui(Counter(), width = 30, height = 5).map { t =>
        t.press(Key('+'), Key('+'), Key('-')).andThen(t.screen).map(s => assert(s.contains("Count: 1")))
      }
    }
  }

class TodoTest extends Test[Any]:
  "Todo" - {
    "add two items, toggle the first, delete it" in {
      TestTui(TodoApp(), width = 50, height = 12).map { t =>
        t.typeText("buy milk").andThen(t.press(Key.Enter))
          .andThen(t.typeText("walk dog")).andThen(t.press(Key.Enter))
          .andThen(t.press(Key.Tab, Key(' ')))                 // focus the list, toggle item 0
          .andThen(t.stateOf(TodoApp))
          .map(s => assert(s.map(_.items.map(_.done)) == Present(Chunk(true, false))))
          .andThen(t.press(Key('d')))
          .andThen(t.screen)
          .map(s => assert(!s.contains("buy milk") && s.contains("walk dog") && s.contains("(1 left)")))
      }
    }
    "typing d in the input does not delete" in {
      TestTui(TodoApp(), 50, 12).map { t =>
        t.typeText("x").andThen(t.press(Key.Enter)).andThen(t.typeText("d"))
          .andThen(t.stateOf(TodoApp)).map(s => assert(s.map(_.items.size) == Present(1)))
      }
    }
  }

class DashboardTest extends Test[Any]:
  "Dashboard" - {
    "a sample reaches the gauge; the hidden log pane does not draw" in {
      Clock.withTimeControl { clock =>
        Channel.init[Long](8).map { samples =>
          TestTui(Dashboard(Dashboard.Props(samples.streamUntilClosed(), logLines)), 60, 20).map { t =>
            samples.put(42L).andThen(t.settle).andThen(t.screen).map(s => assert(s.contains("42%")))
              .andThen(clock.advance(2.seconds)).andThen(t.settle)          // 5 log lines arrive offstage
              .andThen(t.press(Key.Right)).andThen(t.screen)
              .map(s => assert(s.contains("(#5)")))
          }
        }
      }
    }
  }
```

### 6.3 What makes the design easy for an AI agent to write and check

1. **One shape for every screen.** A component is an `object` with `init`, `build`, and optionally
   `subscriptions` and `propsChanged`. An agent can copy a sample and change it. There are no hook rules
   to remember and no global message type that grows with the app.
2. **The compiler catches the common mistakes.** Updating the wrong state is a type error that names
   both state types; a missing case in an `Out` match is an exhaustivity warning; a handler that uses an
   effect the runtime does not handle (say `Env[Db]`) does not fit `Local` and fails to compile.
3. **Deterministic, readable checks.** `TestTui` + `Screen` give text an agent can read and compare;
   `Clock.withTimeControl` removes timing flakiness; `Probe.key` checks one transition in one line.
4. **Text introspection for debugging.** `t.tree` prints the instance tree:
   ```
   TodoApp#root  state=State(items=2, nextId=3)
   ├─ TextField#0  [focus region "input"]  state=State(text="", cursor=0)
   └─ TodoList#1   [focus region "list" *focused*]  state=State(selected=1)  subs=-
   ```
   and `t.explainKey(Key('d'))` prints the route:
   `focus=list -> region "list": TodoList onKeys matches 'd' -> consumed`. An agent that wonders "why
   does my key do nothing" gets the answer as text, not by guessing.
5. **Dev-mode checks with clear messages** (on by default in `TestTui`, opt-in at run time): two
   siblings with the same `.key`, two focusables with the same id, a handler that ran longer than one
   frame budget, `onOutput` missing on a component whose `O` is not `Nothing` (outputs dropped).
6. **Familiar names.** `Paragraph`, `Gauge`, `Tabs`, `Sparkline`, `Block`, `Constraint` are ratatui's
   names, so knowledge of ratatui examples transfers. Flutter / React developers recognise
   `Component`, `build`, `key`, `IndexedStack`, `LayoutBuilder`, "lift state up".
7. **The same code in three effect systems.** The ZIO and Cats samples differ from the Kyo samples only
   in `state.update` vs `update` and the source types, so an agent that knows one knows all three.

What is harder for agents: the `Ui` context-function type (writing `def build(...) =` without `: Ui`
gives a confusing error), and the routing rule (focus regions) must be understood once. `explainKey`
exists for the second.


## 7. Performance analysis

Numbers marked VERIFIED come from `jatatui.md` 5.3 (2014 i7, JDK 25, loaded machine) and the Kyo
loop probe (`loop2.log`, `loop3.log`). Numbers marked EST are my estimates; they need a benchmark.

### 7.1 Work per event (one key press that changes one component's state)

| step                                                                  | cost                                    |
| --------------------------------------------------------------------- | --------------------------------------- |
| input thread: JNI read, convert, `putBatchFiber`                      | part of ~7 us/event end to end (VERIFIED, thread input, batched, coalesced) |
| UI fiber: `take` + `drain`                                            | 0.5-0.8 us/event amortized (VERIFIED micro: putBatch / take + drain) |
| routing: focus regions are precomputed arrays; `isDefinedAt` per candidate handler | < 1 us for tens of handlers (EST) |
| handler: `Var.runTuple` + 2 x `Emit.run` + `Abort.run`               | 1-3 us, a few hundred bytes (EST; `Loop + Var.update` alone is 0.6-0.8 us, VERIFIED) |
| state compare `new == old`                                            | O(fields); a big `Chunk` field that did not change is compared element by element unless it is the same reference (the runtime checks `eq` first; a component can override `sameState`) |
| rebuild of the dirty instance                                         | O(nodes of that instance's view). Small components: 1-20 us, 1-20 KiB (EST). A list of 1 000 items builds 1 000 `Line`s (~100 KiB, ~50-100 us, EST) unless it uses `ListView.virtual(count)(i => Line)`, which builds visible rows only at paint time |
| reconcile                                                             | visits only dirty instances and children with new props; slot lookup is one hash lookup per `Mount` |
| focus / routing table refresh                                         | one walk over the cached trees: O(all nodes), no `build` calls. ~10-40 us for 1 000-2 000 nodes (EST). Could be made incremental per instance |

So an event that changes state costs roughly 10 us plus the rebuild of the changed component, and
**no frame** until the batch is done. An event that changes nothing (no handler, or equal state) costs
a few microseconds and draws nothing.

### 7.2 Work per frame

| step                                                       | cost (120x40, typical screen)                         |
| ---------------------------------------------------------- | ----------------------------------------------------- |
| jatatui widgets render (Block + Paragraph + List)          | ~1.2 ms, ~1.7 MiB allocated (VERIFIED)                |
| `Terminal.draw` fixed part: diff + reset                   | ~0.6 ms, ~0 allocation (VERIFIED)                     |
| backend: one JNI `enqueue` of the changed cells + 3 JNI calls | proportional to changed cells (VERIFIED shape, jatatui.md 2.8) |
| tui4s paint walk: match per node, `Layout.split` per container (cache hit 2.7 us, 0.3 KiB, VERIFIED), bounds records | ~20-50 us and a few KiB for a 50-node tree (EST): 2-3% of jatatui's own cost |

The paint walk never calls `build` (except `LayoutBuilder`) and never diffs trees. jatatui's own
buffer diff is the only diff, and it is what keeps terminal output small.

### 7.3 How unchanged frames are avoided

1. No timer drives frames. A frame needs paint-dirty (5.3).
2. A handler that produces an equal state draws nothing (`==` check; `SignalRef` gives the same
   dedup for external stores, VERIFIED doc comment in `Signal.scala`).
3. Changes in offstage subtrees (`IndexedStack`) rebuild but do not draw.
4. Bursts are coalesced to at most `maxFps` frames per second; 10 000 queued keys drew 3 frames in
   the probe (VERIFIED).
5. Idle: zero frames; the only idle costs are the input poll (40 wake-ups/s) and the Kyo scheduler
   (4-8% of a core with the tuned flags, 13% without, VERIFIED F13).

Dashboard at rest: ~6.5 data updates per second -> ~6.5 frames/s x ~2 ms = ~13 ms CPU per second
(~1.3% of a core, EST) on top of the scheduler's idle cost.

### 7.4 The cost of the abstraction itself

- **Retained trees.** Each instance keeps its last `View` tree between frames (that is what lets clean
  instances skip `build`). Memory: proportional to the visible UI (a 1 000-line list retains 1 000
  `Line`s unless lazy). Elm-style designs drop the view after drawing but rebuild all of it on each
  update.
- **Identity bookkeeping.** Instance table, slot ids, focus regions, dirty-descendant counters. All
  are O(changed) per event except the routing-table refresh (O(nodes), 7.1).
- **No signal graph, no VDOM diff.** Dependency tracking is coarse (per instance) and explicit (state,
  props, two `BuildContext` reads). That is cheaper and easier to explain than a fine-grained signal
  graph, but it rebuilds a whole component when any part of its state changes. The answer for a hot
  spot is the same as in React: split the component.
- **Facade hop.** ZIO / Cats handlers cross a bridge (fork on the caller runtime + promise join): EST
  10-30 us per handler. Invisible for key presses; worth a "pure update" fast path for high-rate
  stream subscriptions (open question 8.3).

### 7.5 Optional: subtree blit (phase 2, must be measured)

Painting is a full frame, because jatatui's `Terminal.draw` asks the callback to "fully render the
entire frame" (VERIFIED doc comment) and resets the back buffer. But the previous frame's buffer stays
intact during the next draw callback: `swapBuffers` resets it only after `flush` (VERIFIED
`Terminal.draw` / `swapBuffers`), and it is the buffer of the `CompletedFrame` returned by the previous
`draw` (VERIFIED: `new CompletedFrame(buffers[1 - current], ...)` after the swap). So a clean, on-stage
instance whose area did not change, with nothing painted above it in the last frame and no
`LayoutBuilder` or cursor inside, could copy its rectangle from the previous buffer instead of running
its widgets: ~4 800 cell copies for a full 120x40 screen (EST 30-60 us) instead of ~1.2 ms of widget
rendering. In the dashboard, the tabs and the log pane would be copied and only the gauge and sparkline
rendered. This relies on jatatui's buffer-reuse behaviour, which is not a documented contract, so it
is opt-in (`Config.blit = true`) and needs a benchmark and an equality test against full rendering.


## 8. Weaknesses, risks and open questions

### 8.1 Weaknesses (honest list)

1. **More concepts than Elm.** `View`, `Component`, `Sub`, outputs and `onOutput`, focus regions,
   `IndexedStack`, `LayoutBuilder`, `BuildContext`. Elm needs Model, Msg, update, view. For Counter the
   difference is small; for Todo, each component brings its own `Props` / `State` / `Out` types and the
   parent wires `onOutput`. A one-model Elm Todo is shorter.
2. **Local state dies on unmount.** The classic React/Flutter trap: hide a panel with an `if` and its
   state is gone. The answers (`IndexedStack`, lift state up, external `SignalRef`) work, but a person or
   an agent must know them. Whole-app operations (undo, save and restore the session, time-travel
   debugging) are harder than with one immutable model, because the state is spread over instances.
3. **Positional identity.** Inserting a node before a component in a `Column` changes its slot and
   resets its state, unless it has a `.key`. React has the same rule; it still surprises people. A
   dev-mode warning for "same component class moved to a neighbouring index" is possible but heuristic.
4. **Component identity by object.** Components should be `object`s (or stable `val`s). A component
   class instantiated inside `build` (`new Select[Int](...)`) is a new component value every build;
   the runtime compares components by reference, so the instance would be re-mounted every frame. This
   needs a dev-mode check and documentation. Generic components need a `Tag[Var[S]]`, so they are classes
   with `using` parameters, which makes this trap more likely.
5. **Type inference is the main DX risk.** `View[-R]` intersections, partial functions in `onKeys`, the
   `Ui` context function, `Emit[-V]` contravariance with `O = Nothing` (`Emit[Nothing]` is the top of the
   `Emit` types, so `Emit[Nothing] & Emit[Cmd]` simplifies to `Emit[Cmd]`; the runtime skips
   `Emit.run[O]` for `Nothing`). When inference fails, the error shows Kyo rows, which are long. The
   validator must compile the samples; the fallback is explicit type ascriptions in the docs.
6. **The paint is still a full frame.** Components save `build` work, not paint work. Without the blit
   of 7.5, a big screen costs the same ~2 ms per frame as in any other design.
7. **Runtime complexity.** Reconciler, slot identity, generations for late deliveries, dirty-descendant
   counters, focus regions, offstage rules, `PaintSlots` sweeping: more runtime code (EST 2 000-3 000
   lines) and more places for bugs than an Elm loop. It needs property tests (random trees, random
   key sequences; invariants: no leaked sub fibers, focus always on a mounted focusable or none).
8. **Handlers may block the UI.** Handlers have `Async`, so a slow handler freezes input and drawing.
   `task` is the right tool, and dev mode warns when a handler runs longer than one frame budget, but the
   type does not prevent it. (Restricting handlers to `Sync` would prevent it and would also forbid useful
   things such as `channel.put` or a short `SignalRef` update with `Async`.)
9. **Two rebuild paths for staleness.** "Rebuild between events" fixes stale closures for normal nodes,
   but handlers inside a `LayoutBuilder` are refreshed only at paint time; mouse handlers use the last
   painted geometry. Both are documented, both are edge cases.
10. **String focus ids.** A typo is a silent "never focused". Duplicate ids are detected at run time in
    dev mode only. Typed ids (`val Input = FocusId("input")`) are possible but add ceremony.
11. **Facades.** About 60 forwarders per facade, a runtime owner check instead of a type check, reliance on
    `FiberRef` / `IOLocal`, one bridge hop per handler, and no tagless `F[_]` in v1.

### 8.2 Risks

| risk | likelihood | mitigation |
| ---- | ---------- | ---------- |
| `onKeys` partial-function inference of `R2` fails or gives a huge error | medium | validator compiles the samples; fallback `onKeys[Local] { ... }` with the component's alias |
| crossterm: one thread in `poll/read`, another in `enqueue/flush` at the same time | low (Rust uses separate locks for the event reader and stdout, UNVERIFIED for this build) | stress test in CI with a pseudo-terminal |
| `Tui.tuneScheduler()` runs after the scheduler is initialized (no effect) | medium | document the `-D` fallback; detect and log (not print) when the flags were not applied |
| a 10 ms scheduler cycle adds input latency under load | unknown | measure key-to-frame latency with and without the flags |
| TTY detection on JDK 22+ (`System.console()` is non-null for redirected I/O) | medium | use `System.console().isTerminal()` (JDK 22+ API, UNVERIFIED name) or a crossterm `terminalSize` probe |
| `Sync.acquireReleaseWith` release blocks a Kyo worker while it joins the input thread (<= ~100 ms) | certain, small | bounded join; the Kyo scheduler tolerates blocking (kyo.md 4.5) |
| jatatui 0.x API changes | medium | everything is wrapped; only `View.Leaf` / `View.widget` expose jatatui types on purpose |
| `Tag[Emit[Nothing]]` / `Tag[Var[S]]` derivation in generic code | low-medium | `Component` takes them as `using` parameters at the `object` definition site (concrete types) |
| IOLocal / FiberRef propagation through the bridges | low | the probe's AmbientZIOs shows FiberRefs flow (F12); the IOLocal is set inside the same IO fiber that runs the handler |

### 8.3 Open questions

1. **Consume by default?** A matching handler consumes the key (chosen). Should `onKey(k, passThrough =
   true)` exist for "observe but do not consume"?
2. **Typed focus ids** (`FocusId` values) instead of strings?
3. **Pure fast path for facades.** `state.on(k)(f: S => S)` could build a raw handler that applies `f`
   without a bridge hop. It needs the facade `UiState[S]` to create view modifiers. Worth it?
4. **Tagless `F[_]` for tui4s-cats.** `IOLocal` is IO-only. An `F[_]: Async` facade would pass the
   handle explicitly (`onKey(k)(ui => ui.update(...))`) or use cats-mtl `Local`. Is IO-first acceptable?
5. **Context / providers** (theme, router) as `View.provide(key, value)` + a `BuildContext` read, or
   props only? Providers make memo dependencies wider.
6. **Subtree blit (7.5)**: implement in v1, v2, or never? It is the only way this design saves paint work.
7. **Error boundaries**: `View.catching(fallback: TuiError => View[Any])` per subtree, as in React?
8. **Animation**: one `Sub.every(16.millis)` per animated component, or a shared frame clock (like
   jatatui-react's `useFrame`) so that all animations step in the same frame?
9. **`Tui.run` result**: return the root's final state as well (for "edit, then save on quit")?
   `runFor` covers outputs; the final state needs a second entry point or `Tui.runWithState`.
10. **Scope of `Sub.events`**: keep it (global key loggers, recorders) or remove it to keep one routing
    path?


## Appendix A. The user's first take (48a6dd9): what was kept, fixed, dropped

Read with `git show 48a6dd9:src/main/scala/tui4s/{interfaces,widgets,withTerminal,Demo,FrameF,TerminalF,CrosstermJniF}.scala`
and `elements/elements.scala`. Every "VERIFIED" below was seen in those files.

### A.1 Kept, and why

| first take                                                                 | in this design                                     | why it is kept |
| -------------------------------------------------------------------------- | -------------------------------------------------- | -------------- |
| Flutter's three layers: `Widget` (config), `Element` (mounted), `State`    | `View` (value), `Instance` (runtime), `S` (typed)  | It is the right model for local state with identity; Flutter shows that it scales. The names change only because `Widget` clashes with jatatui's `Widget` |
| `StatelessWidget` vs `StatefulWidget`                                       | plain functions returning `View` vs `Component[P, S]` | Most UI is stateless; the first take's `StatelessWidget.of(buildFunc)` was already a function, so the stateless side becomes just a function |
| `build(buildContext)` and the name `BuildContext`                          | `build(props, state): Ui` with `BuildContext ?=>`   | Same mental model; the context is now implicit and holds only focus and size |
| `State.build(...): StateT[F, TState, Widget[F]]` (state in, state out)     | handlers over Kyo `Var[S]`; `Var.runTuple` is `StateT.run` | The first take wanted state transitions as a state monad; `Var` is Kyo's native one, and testable the same way |
| nested types (`Tabs.Tab`, `TabsState`) inside the widget                   | `Props` / `State` / `Out` nested in the component `object` | Keeps a component self-contained in one place |
| `withTerminal`: a `Resource` that enables raw mode, alternate screen, mouse capture, and restores them | `Term.enter` / `Term.exit` in `Sync.acquireReleaseWith` | Same responsibility. Changed to a synchronous release because a `Scope` finalizer is not awaited on interrupt (F8) |
| `FramesPerUnit` / `60.fps`                                                  | `Config.maxFps = 60`                               | Same knob, but as a cap for coalescing, not a timer that redraws |
| the comment "give [events] to the user as an argument and as a signal"     | events reach handlers as arguments; async values arrive through `Sub.signal` / `Sub.stream` | That was the right instinct; it is now the routing model |
| `F[_]` polymorphism so that Cats users are served                          | Kyo core + `tui4s-cats` / `tui4s-zio` facades      | Same goal (several effect systems), realized with the fixed module split |

### A.2 Fixed (bugs and design problems, VERIFIED in the source)

1. **State was re-created on every frame.** `FrameF.renderWidget` calls `widget.createElement` on every
   draw, `ComponentElement.render` calls `widget.createElement` again, and
   `StatefulElement.of` makes a new `Ref.of(stateWidget.initialState)` each time. So `Tabs` starts from
   `initialState` on every event: the state never survives. **Fix:** instances persist by slot
   identity (5.3); `init` runs once per mount.
2. **The event was an input of `build`.** `BuildContext(buffer, area, lastEvent)` makes `build` both the
   view and the update, and every widget sees every key (the `Tabs` reacts to arrows wherever the focus
   is). **Fix:** handlers are data in the `View`, routed along focus regions (2.8); `build` is pure.
3. **One draw per event, and a read that cannot be stopped.** `withTerminal` does
   `jniF.events().evalMap(e => terminal.draw(app, e))`, and `events` is
   `Stream.repeatEval(poll(by) >> read)`: the result of `poll` is ignored, so `read()` blocks in JNI on
   every iteration and cannot be cancelled. **Fix:** an input thread that reads only after
   `poll(...) == true`, batches the queued events, and a UI loop that coalesces them into dirty frames.
4. **Borders drawn by hand.** `ContainerElement` re-implements jatatui's `Block` with `setCell` loops,
   allocating `(area.top until area.bottom).toList` for each side on every frame. **Fix:** wrap
   `jatatui.widgets.block.Block` (`View.Boxed`).
5. **`Tabs` arithmetic:** `current + 1 % tabsLength` parses as `current + (1 % tabsLength)`, so the index
   never wraps. **Fix:** `(tab + 1) % size` in the Dashboard sample.
6. **Effects around pure construction.** `createElement: F[WElement]` wraps the allocation of a value
   in `F`. **Fix:** views are plain values; effects exist only in handlers and subscriptions.
7. **Path-dependent types** (`type TState`, `widget.State`, `this.State`) are heavy to write and to
   infer. **Fix:** type parameters `Component[P, S]`.
8. **No way to quit, and errors on the console.** `Demo` uses `useForever` and prints errors with
   `Console[IO].errorln` (which draws over the TUI). **Fix:** `Tui.quit`, Ctrl-C, `runFor`, typed
   `TuiError`, nothing on stdout/stderr while the TUI runs (5.7).

### A.3 Dropped

- The Cats-Effect core (`F[_]: Sync / Ref.Make / NonEmptyParallel`): replaced by the Kyo core (fixed
  decision); Cats lives in `tui4s-cats`.
- `BufferF` / `TerminalF` / `CrosstermJniF` / `BackendF` wrappers that suspend every buffer call in
  `F`: painting is one unsafe region per frame (kyo.md 4.3); per-cell effects cost allocation and add
  nothing, because the buffer is owned by one fiber.
- The vendored tui-scala internals (`internal/Wcwidth`, `UnicodeSegmentation`, `reflow`, ...): jatatui
  provides them.
- The `Signal[F, Option[CompletedFrameF[F]]]` result of `withTerminal`: tests use `TestTui` instead.

## Appendix B. Verification log

Checked in the sources (`.work/scratch/kyo-src/` RC7, `.work/scratch/jatatui/src/` 0.31.0) or in the
reports and probe logs named in CONTEXT.md:

| name / fact | where |
| ----------- | ----- |
| `Signal.initRef`, `SignalRef.{get,set,use,getAndUpdate,updateAndGet}`, `Signal.{current,next,observe,streamChanges}`; equal value notifies nobody | `kyo-core/kyo/Signal.scala` |
| `Channel.init(capacity, access)` needs `Scope`; `take`, `drain`, `put`, `putBatch`, `offerDiscard`, `streamUntilClosed`, `unsafe.putBatchFiber`, `unsafe.putFiber` | `kyo-core/kyo/Channel.scala` |
| `Fiber.init` (scoped, interrupt + await on scope close), `Fiber.initUnscoped`, `interrupt`, `interruptAwait`, `get`, `getResult` | `kyo-core/kyo/Fiber.scala` |
| `Sync.ensure`, `Sync.acquireReleaseWith(acquire)(release)(use)(using ConcreteTag[E], Frame)`, `Sync.Unsafe.defer` | `kyo-core/kyo/Sync.scala` |
| `Scope.run`, `Scope.ensure`, `Scope.acquireRelease` | `kyo-core/kyo/Scope.scala` |
| `Async.sleep` goes through `Clock.sleep` (so `Clock.withTimeControl` controls it); `Clock.withTimeControl`, `TimeControl.advance` | `kyo-core/kyo/Async.scala:161`, `Clock.scala` |
| `Var.{get,use,set,setDiscard,update,updateDiscard,run,runTuple}`; `Var[V]` invariant | `kyo-prelude/kyo/Var.scala` |
| `Emit.value`, `Emit.valueWith`; `Emit[-V]` contravariant | `kyo-prelude/kyo/Emit.scala` |
| `Abort[-E]` contravariant (so `Abort[TuiError]` fits `Abort[Throwable]`) | `kyo-prelude/kyo/Abort.scala:41` |
| `Local.init`, `Local.{get,use,let,update}` | `kyo-prelude/kyo/Local.scala` |
| `Loop(...)`, `Loop.continue`, `Loop.done`, `Loop.forever` | `kyo-kernel/kyo/kernel/Loop.scala` |
| `Kyo.unit`, `Kyo.lift` | `kyo-kernel/kyo/Kyo.scala` |
| `Stream.apply`, `Stream.init`, `Stream.unfold(acc, chunkSize)(f)`, `foreach`, `foreachChunk` | `kyo-prelude/kyo/Stream.scala` |
| `Random.nextInt(bound): Int < Sync` | `kyo-core/kyo/Random.scala` |
| `Maybe.fold`, `getOrElse`, `toOption`; `Chunk extends Seq` | `kyo-data/kyo/Maybe.scala`, `Chunk.scala` |
| `Log.let(log)(f)`, `Log.Unsafe` | `kyo-core/kyo/Log.scala` |
| `KyoApp` (`run` registers code, `main` runs it), `KyoApp.Unsafe.runAndBlock` | `kyo-core/kyo/KyoApp.scala` |
| `Isolate` is derived automatically (`inline given`) | `kyo-kernel/kyo/kernel/Isolate.scala:246` |
| jatatui `Frame.{area, renderWidget, renderStatefulWidget, setCursorPosition, bufferMut}` | `jatatui-core/.../terminal/Frame.java` |
| `Terminal.draw` order (autoresize, callback, flush, cursor, swapBuffers, backend.flush) and the returned `CompletedFrame(buffers[1 - current])` | `jatatui-core/.../terminal/Terminal.java` |
| `Gauge.{empty, withRatio, withPercent, withLabel, withGaugeStyle}`, `Sparkline.{empty, withDataLongs, withMax}`, `Tabs.{ofStrings, withSelected, withHighlightStyle, withDivider}` | `jatatui-widgets` sources |
| jatatui-react: positional hooks in `HookStore.values`, auto-memo removed because of handler registration, type-change unmount in `RenderContext.reconcile`, `(z, seq)` hit-testing, focus manager | `jatatui-react` sources |
| first-take bugs A.2 (state re-created, `poll >> read`, draw per event, `1 % n`) | `git show 48a6dd9:...` |

UNVERIFIED (needs the validator or a probe):

- Inference of `R2` in `onKeys(pf)` with a partial-function literal, and of `View[R & R2]` chains.
- `type Ui = BuildContext ?=> View[Local]` as the declared result of an overriding `build`.
- `Tag[Emit[Nothing]]` derivation; `Emit.run` with two `Emit` effects whose types are related by
  contravariance.
- Whether the standard library already has `CanEqual[Unit, Unit]` in strict-equality mode (tui4s
  defines one in any case).
- `Loop(50L) { v => ... Loop.continue(next) }` in `produceSamples` (inference of the done type).
- `Sparkline.withDataLongs(data.toArray*)` from Scala (Java `long...` varargs).
- `kyo.Duration` <-> `zio.Duration` / `FiniteDuration` conversion names.
- The package name of kyo-test's `Test` (`kyo.test` assumed) and its `assert` returning `Unit`.
- Whether `KyoApp`'s construction initializes the Kyo scheduler before `Tui.tuneScheduler()` runs.
- `System.console().isTerminal()` as the TTY test on JDK 22+.
- Concurrent use of crossterm's reader (input thread) and writer (UI fiber).
- That `Clock.withTimeControl` reaches fibers forked inside it (sub fibers in tests) through `Local`
  inheritance.
- cats-effect / fs2 / ZIO names used in section 4 (`IOLocal`, `Queue`, `Stream.fromQueueUnterminated`,
  `metered`, `background.surround`, `ZStream.fromQueue`, `forkScoped`): standard APIs, not checked here.
