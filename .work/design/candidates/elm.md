# tui4s design candidate: The Elm Architecture (TEA) on Kyo

Status: COMPLETE (2026-10-01). Designer: TEA direction. All 8 sections written.

## Outline

1. Pitch and core idea
2. Concepts and public API of tui4s-core
3. The three sample apps for Kyo users (Counter, Todo, Dashboard)
4. Counter and Dashboard for ZIO users and for Cats Effect users (facades)
5. Runtime model
6. Testing and agent-friendliness
7. Performance analysis
8. Weaknesses, risks and open questions

Legend: **VERIFIED** = the name/signature was checked in the RC7 Kyo sources
(`.work/scratch/kyo-src/`) or the jatatui 0.31.0 sources (`.work/scratch/jatatui/src/`), or it is a
measured fact from `.work/reports/*`. **UNVERIFIED** = my inference, not checked. Nothing here was
compiled (panel rule).

---

## 1. Pitch and core idea

**Pitch.** A tui4s app is two data types and three pure functions: `update(model, msg): Next[Model, Msg]`,
`view(model): View[Msg]`, `subscriptions(model): Sub[Msg]`. Side effects are data (`Cmd`, `Sub`) that
carry Kyo computations; the runtime runs them on fibers and feeds the results back as messages. One fiber
owns every mutable jatatui object and draws only when the model changed. The same `Program` code runs on
Kyo, ZIO and Cats Effect: only the effect constructors at the edges change.

### 1.1 The core idea in six rules

1. **Purity by type, not by discipline.** `update` and `view` return plain values (no `<` in their
   types), so they cannot run effects. All effects are *descriptions*: `Cmd[Msg]` (one-shot work, for
   example "load this file, then send `Loaded`") and `Sub[Msg]` (standing sources: keys, timers, streams).
   The runtime interprets them. This is Elm's guarantee, which Scala cannot give for arbitrary code, but
   which the *types* of this API give: the only way to cause an effect is to return a `Cmd` or a `Sub`.
2. **"Just the model" is the common case.** `Next[Model, Msg]` is the union `Model | Next.WithCmd[Model, Msg]`.
   A branch that only changes state returns the model (`m.copy(count = m.count + 1)`); a branch that also
   needs work returns `m.withCmd(cmd)`. No `(m, Cmd.none)` noise as in Elm or bubbletea.
3. **Subscriptions are a function of the model.** The runtime calls `subscriptions(model)` after each
   update, and *diffs* the result by id: new timers and streams start as fibers, removed ones are
   interrupted. Keys are subscriptions too, so "which keys work now" (focus, modes) is plain data that
   depends on the model, and the same `Keymap` value drives both key routing and the help bar.
4. **A Kyo-free shared surface.** `Program`, `Next`, `View`, `Key`, `Style`, `Keymap`, the `Cmd`/`Sub`
   *types* and the test kit contain no Kyo types (they use `Option`, `Seq`, `java.time.Duration`). Only the
   *constructors* that take an effect (`Cmd.perform(task: A < Async)`, `Sub.stream(s: Stream[A, Async])`) and
   the runner `Tui.run` are Kyo-typed. `tui4s-zio` and `tui4s-cats` re-export the shared surface and add
   constructors that take `ZIO` / `ZStream` and `IO` / `fs2.Stream`. A Counter app is the *same source
   text* in all three modules except for one import and the main class.
5. **The runtime is the probe that already worked.** Input on its own thread (`poll(50 ms)` then `read()`
   every queued event, `putBatch`), resource-subscription fibers, and one update-render loop (`take` -> `drain` ->
   fold every message through `update` -> draw once if the model changed). Terminal restore is a
   `Sync.ensure` (F8). Measured on the probe: ~6.7 us per event with batching and coalescing.
6. **Model equality is the dirty flag.** `update` returns the same instance when nothing changed (an unbound
   key), and a changed model is compared with the last drawn one (`eq` first, then `==`). No signal graph,
   no virtual DOM diff: the only diff is jatatui's buffer diff, which already exists.

### 1.2 Why TEA fits this problem (and Kyo) well

- jatatui's widgets, text and styles are **immutable values with structural equality** (VERIFIED, report
  section 3.0); a TEA `view` builds exactly such values every frame, and building them is cheap (+28 KiB per
  frame for 100 rebuilt list items, report 5.3). Rendering is the cost, and TEA makes "render only when the
  model changed" trivial.
- jatatui's mutable objects (`Buffer`, `Cell`, `ListState`, `TableState`, `ScrollbarState`) must stay on one
  fiber. In TEA the user never sees them: the view is data, the runtime owns the objects.
- Kyo gives the runtime what Elm's runtime gets from the browser: fibers with interruption for commands and
  subscriptions, `Channel` with batch put/drain for coalescing, `Sync.ensure` for a restore that is done
  before the app's result is visible, `Local` for the ZIO runtime / Cats `Dispatcher` that facade commands
  need.
- An AI agent (or a human) can write and check a TEA app in small, independent pieces: `update` is a pure
  function with a `match`, `view` is a pure function, and tests are `assertEquals` on values plus
  snapshot strings of a headless screen (section 6).

---

## 2. Concepts and public API of tui4s-core

### 2.1 Modules and packages

```
tui4s-core  deps: kyo-core 1.0.0-RC7, jatatui-core, jatatui-widgets, jatatui-crossterm, crossterm (0.31.0)
  tui4s            Program, Next, Cmd, Sub, Tui (runner), TuiApp (main), TuiOptions, TuiError, Dispatch,
                   TuiHandle. Top-level `export`s of tui4s.view.* and tui4s.input.* so that
                   `import tui4s.*` is the only import an app needs.
  tui4s.view       View (immutable tree), layout modifiers, Style, Color, Span, Line, Text, Border
  tui4s.input      Key, Mods, Mouse, MouseKind, InputEvent, Bind, Keymap, TextField
  tui4s.testkit    TestTui (headless runtime on jatatui TestBackend), Screen, scripted input
  tui4s.runtime    SPI for facades and tests: TerminalPort, InputPort, Dispatch.Unsafe
  tui4s.internal   Loop, Renderer, SubDiff, StateStore, Convert (not public API)
tui4s-zio   package tui4s.zio   re-exports the shared surface; Cmd/Sub constructors for ZIO/ZStream;
                                Tui.run: ZIO; ZTuiApp (a ZIOAppDefault)
tui4s-cats  package tui4s.cats  re-exports the shared surface; Cmd/Sub constructors for IO/fs2.Stream;
                                Tui.run[F]: F[Model]; IOTuiApp (an IOApp.Simple)
```

The **shared surface** is everything an app writes in `init`, `update`, `view` and `subscriptions`
signatures: `Program`, `Next`, the *types* `Cmd[Msg]` and `Sub[Msg]`, `View`, `Key`, `Keymap`, `Style`,
`Line`, `TextField`, `TuiOptions`, the test kit. It has **no Kyo types**: it uses `Option` (not `Maybe`),
`Seq`/`Vector` (not `Chunk`), `java.time.Duration` (which *is* `zio.Duration`). This breaks the Kyo house
style (`Maybe`, `Chunk`) on purpose, because the same `Program` trait is the API of all three modules.
The Kyo-typed parts are only: the effect-taking constructors in the `Cmd`/`Sub` companions, `Tui.run`,
`TuiHandle`, and `Dispatch`. A CI check should fail when a `kyo.` type appears in a shared-surface signature
(UNVERIFIED tooling: tasty-query or a reflection test over the public API).

### 2.2 Program, Next, TuiApp

```scala
package tui4s

/** The whole app. The same trait is used by Kyo, ZIO and Cats Effect users. */
trait Program[Model, Msg]:
  /** The first model, and optionally the first commands (for example "load the saved todos"). */
  def init: Next[Model, Msg]

  /** Pure. Return the model alone, or `model.withCmd(cmd)`. Return the SAME instance for "no change". */
  def update(model: Model, msg: Msg): Next[Model, Msg]

  /** Pure. Called only when the model changed (or after a resize). */
  def view(model: Model): View[Msg]

  /** Pure. Called after each update that changed the model. The runtime diffs the result. */
  def subscriptions(model: Model): Sub[Msg] = Sub.none

  /** Terminal and runtime options (mouse capture, frame budget, Ctrl-C policy, ...). */
  def options: TuiOptions = TuiOptions.default
end Program

/** What `init` and `update` return: a model, or a model with commands. */
type Next[+Model, +Msg] = Model | Next.WithCmd[Model, Msg]

object Next:
  final class WithCmd[+Model, +Msg] private[tui4s] (val model: Model, val cmd: Cmd[Msg]):
    override def toString = s"Next($model, $cmd)"
    // equals/hashCode structural on (model, cmd)

  def apply[Model, Msg](model: Model, cmd: Cmd[Msg]): Next[Model, Msg] =
    if cmd eq Cmd.none then model else WithCmd(model, cmd)

  /** `val Next(model, cmd) = app.update(m, msg)`: the pattern tests use. */
  def unapply[Model, Msg](next: Next[Model, Msg]): (Model, Cmd[Msg]) =
    next match
      case w: WithCmd[Model, Msg] @unchecked => (w.model, w.cmd)
      case m                                 => (m.asInstanceOf[Model], Cmd.none)
end Next

// Top level of package tui4s, so `import tui4s.*` brings it in. (Inside `object Next` it would not be
// found: the receiver type is the user's Model, whose implicit scope does not include Next.)
extension [Model](model: Model)
  infix def withCmd[Msg](cmd: Cmd[Msg]): Next[Model, Msg] = Next(model, cmd)
  def withCmds[Msg](cmds: Cmd[Msg]*): Next[Model, Msg]     = Next(model, Cmd.batch(cmds*))

/** Entry point for Kyo users: `object Counter extends TuiApp[Model, Msg]`. */
abstract class TuiApp[Model, Msg] extends Program[Model, Msg]:
  final def main(args: Array[String]): Unit = Tui.main(this, args)
```

Notes:
- `Next` is a union, so `def init = Model(0)` and `case Msg.Inc => m.copy(...)` type-check against
  `Next[Model, Msg]` with no wrapper and no allocation. The runtime tells the two arms apart with one
  `isInstanceOf[WithCmd]` check. Limitation: a `Model` must not itself be a `Next.WithCmd` (absurd in
  practice). I chose a union over the Kyo-style `Model < Emit[Cmd[Msg]]` because (a) a plain value proves
  purity even more strongly than a pure effect row, (b) it costs no handler per message, and (c) it is
  the same type in the ZIO and Cats modules. See section 8 for the trade-off.
- `TuiApp` deliberately does **not** extend `KyoApp`: its `main` must set the scheduler `StaticFlag`
  defaults with `System.setProperty` *before* the first Kyo scheduler class loads, and `KyoApp`'s own
  constructor runs before any subclass code (section 5.8). Kyo users who prefer `KyoApp` call
  `Tui.run(program)` inside `run { ... }` and pass the flags with `-D` (section 2.9).
- Dependencies (a store, an HTTP client) come in as constructor parameters of a `final class MyApp(store:
  Store) extends Program[...]`. There is no `Env` in the effect rows of commands (trade-off, section 8).

### 2.3 Cmd: one-shot effects as data

```scala
package tui4s

/** A description of work for the runtime. Immutable. Nothing runs until the runtime interprets it. */
sealed abstract class Cmd[+Msg]:
  def map[B](f: Msg => B): Cmd[B]                  // for nested components (Elm's Cmd.map)
  def ++[M >: Msg](that: Cmd[M]): Cmd[M]
  def named(label: String): Cmd[Msg]               // shows in test failures and the debug log
  def labels: List[String]                         // inspection for tests ("which commands did update ask for?")

object Cmd:
  // ---- Kyo-free (shared surface) ----
  val none: Cmd[Nothing]
  val quit: Cmd[Nothing]                           // stop after this batch; Tui.run returns the model
  def send[Msg](msg: Msg): Cmd[Msg]                // dispatch msg right after this update, same batch
  def batch[Msg](cmds: Cmd[Msg]*): Cmd[Msg]
  def cancel(key: String): Cmd[Nothing]            // interrupt the running task started with .keyed(key)
  extension [Msg](cmd: Cmd[Msg])
    def keyed(key: String): Cmd[Msg]               // "latest wins": a new task with this key interrupts the old one

  // ---- Kyo constructors (tui4s-core only) ----
  /** Run `task` on a new fiber; send `toMsg(result)`. A panic in `task` fails the app (terminal restored first). */
  def perform[A, Msg](task: => A < Async)(toMsg: A => Msg)(using Frame): Cmd[Msg]

  /** Like perform, but typed failures (and panics) become a message: nothing can crash the app. */
  def attempt[E, A, Msg](task: => A < (Async & Abort[E]))(toMsg: Result[E, A] => Msg)(
      using ConcreteTag[E], Frame): Cmd[Msg]

  /** Fire and forget (for example: write a log file). Failures are logged, not sent. */
  def exec(task: => Any < (Async & Abort[Throwable]))(using Frame): Cmd[Nothing]

  /** Send `msg` after `delay` (one-shot timer). */
  def delay[Msg](delay: Duration)(msg: Msg)(using Frame): Cmd[Msg]

  /** Low-level port (used by tui4s-zio and tui4s-cats): run `body` with a handle that sends messages. */
  def fromDispatch[Msg](body: Dispatch[Msg] => Any < (Async & Abort[Throwable]))(using Frame): Cmd[Msg]
end Cmd
```

Internal representation (`tui4s.internal`): `None`, `Quit`, `Send(msg)`, `Batch(Chunk[Cmd])`,
`Cancel(key)`, and `Task(label: Maybe[String], key: Maybe[String], run: Dispatch[Msg] => Any < (Async &
Abort[Throwable]))`. Every Kyo constructor lowers to `Task`: `perform(t)(f)` is
`Task(d => t.map(a => d(f(a))))`; `attempt(t)(f)` is `Task(d => Abort.run[E](t).map(r => d(f(r))))`
(`Abort.run[E]` needs `ConcreteTag[E]`, VERIFIED signature). `map` composes into the dispatch
(`d.contramap(f)`), so mapping a command costs one closure, not a fiber.

### 2.4 Sub: standing sources, diffed by id

```scala
package tui4s

sealed abstract class Sub[+Msg]:
  def map[B](f: Msg => B): Sub[B]
  def ++[M >: Msg](that: Sub[M]): Sub[M]

object Sub:
  val none: Sub[Nothing]
  def batch[Msg](subs: Sub[Msg]*): Sub[Msg]

  // ---- Routing subscriptions (Kyo-free). No fiber: consulted for each input event, in batch
  //      order; the FIRST one that matches wins. Put focused bindings before global ones. ----
  def keys[Msg](pf: PartialFunction[Key, Msg]): Sub[Msg]
  def keymap[Msg](keymap: Keymap[Msg]): Sub[Msg]
  def mouse[Msg](pf: PartialFunction[Mouse, Msg]): Sub[Msg]
  def resize[Msg](f: (Int, Int) => Msg): Sub[Msg]             // (columns, rows)
  def paste[Msg](f: String => Msg): Sub[Msg]                  // needs bracketed paste (on by default)
  def focus[Msg](gained: Msg, lost: Msg): Sub[Msg]            // needs TuiOptions.focusEvents
  def events[Msg](pf: PartialFunction[InputEvent, Msg]): Sub[Msg]  // raw escape hatch

  // ---- Resource subscriptions. One fiber each, started/stopped by diffing ids. ----
  /** Kyo-free: id = ("every", interval, msg), so it needs no explicit id. */
  def every[Msg](interval: java.time.Duration)(msg: Msg): Sub[Msg]

  // Kyo constructors (tui4s-core only)
  def every[Msg](interval: Duration)(msg: Msg)(using Frame): Sub[Msg]
  def poll[A, Msg](id: String, interval: Duration)(task: => A < Async)(toMsg: A => Msg)(using Frame): Sub[Msg]
  def stream[A, Msg](id: String)(stream: => Stream[A, Async])(toMsg: A => Msg)(
      using Tag[Emit[Chunk[A]]], Frame): Sub[Msg]
  def signal[A, Msg](id: String)(signal: Signal[A])(toMsg: A => Msg)(
      using Tag[Emit[Chunk[A]]], Frame): Sub[Msg]                // latest-value semantics (streamChanges)
  def fromDispatch[A, Msg](id: String)(body: Dispatch[A] => Any < (Async & Abort[Throwable]))(
      toMsg: A => Msg)(using Frame): Sub[Msg]                      // low-level port for facades
end Sub
```

Semantics, in one paragraph an agent can rely on: *after each update that changed the model, the runtime
calls `subscriptions(model)`. Routing subscriptions take effect for the very next input event (even in the
same batch). Resource subscriptions are compared by id at the end of the batch: an id that is new starts a
fiber, an id that is gone has its fiber interrupted, an id that stays keeps its fiber. A value from a
resource fiber is turned into a message with the `toMsg` of the **current** subscription with that id, so
`toMsg` may capture the model without going stale. Values from a subscription that was removed are dropped.*

Implementation (VERIFIED names): `every` is `Loop.forever(Async.sleep(d).andThen(inbox.offerDiscard(...)))`
(a tick is dropped when the inbox is full; it is only a wake-up); `poll` is the same loop with
`task.map(...)` and `inbox.put` (backpressure); `stream` is `stream.foreach(a => inbox.put(SubValue(id, a)))`;
`signal` is `signal.streamChanges.foreach(...)`. Each fiber is started with `Fiber.init` inside the app's
`Scope`, so it is interrupted at the latest when the app ends.

### 2.5 Dispatch and TuiHandle (the Kyo integration points)

```scala
package tui4s

/** Sends messages into a running app. Thread-safe; any fiber or thread. */
abstract class Dispatch[-Msg]:
  def apply(msg: Msg)(using Frame): Unit < Async            // Channel.put: waits when the inbox is full
  def offer(msg: Msg)(using Frame): Boolean < Sync          // Channel.offer: false when full
  def contramap[B](f: B => Msg): Dispatch[B]
  def unsafe: Dispatch.Unsafe[Msg]

object Dispatch:
  abstract class Unsafe[-Msg]:                              // for the ZIO and Cats facades
    def offer(msg: Msg)(using AllowUnsafe): Boolean        // Channel.Unsafe.offer       (VERIFIED)
    def put(msg: Msg, done: () => Unit)(using AllowUnsafe): Unit
                                                           // Channel.Unsafe.putFiber(v).onComplete (VERIFIED names)

/** A running app, for embedding a TUI in a bigger Kyo program (a server, a REPL, a test). */
final class TuiHandle[Model, Msg]:
  def send(msg: Msg)(using Frame): Unit < Async
  def models: Signal[Model]                                 // set once per batch; SignalRef dedups equal models
  def await(using Frame): Model < (Async & Abort[TuiError])
  def stop(using Frame): Unit < Async                       // same as Cmd.quit, from outside
```

### 2.6 View: layout, widgets, styles

`View[+Msg]` is an immutable tree of `final case class` nodes (structural equality, so a view can be
compared, logged and snapshot in tests). `Msg` appears only because of mouse handlers (`onClick`). The
renderer walks the tree once per drawn frame and calls jatatui.

```scala
package tui4s.view

sealed abstract class View[+Msg]

object View:
  // ---- containers ----
  def column[Msg](children: View[Msg]*): View[Msg]       // Layout.vertical(constraints*)
  def row[Msg](children: View[Msg]*): View[Msg]          // Layout.horizontal(constraints*)
  def layers[Msg](children: View[Msg]*): View[Msg]       // same area, painted in order (popups)
  def empty: View[Nothing]

  // ---- text ----
  def text(s: String): View[Nothing]                     // Paragraph.of(String), no wrap
  def text(line: Line): View[Nothing]
  def paragraph(text: Text, wrap: Boolean = true, scroll: Int = 0): View[Nothing]
  def log(lines: Seq[String | Line]): View[Nothing]      // newest lines that fit, bottom-aligned (log panes)

  // ---- widgets ----
  def list(items: Seq[Line], selected: Option[Int],
           highlight: Style = Style.reversed, symbol: String = "> "): View[Nothing]
  def table(header: Seq[String], rows: Seq[Seq[String]], widths: Seq[Size],
            selected: Option[Int] = None): View[Nothing]
  def tabs(titles: Seq[String], selected: Int): View[Nothing]
  def gauge(ratio: Double, label: String = ""): View[Nothing]      // clamps to [0, 1]; never throws
  def lineGauge(ratio: Double, label: String = ""): View[Nothing]
  def sparkline(data: Seq[Long], max: Option[Long] = None): View[Nothing]
  def scrollbar(position: Int, length: Int): View[Nothing]
  def textField(field: TextField, focused: Boolean, placeholder: String = ""): View[Nothing]
  def help[Msg](keymap: Keymap[Msg]): View[Nothing]                // "+ increment • q quit"
  def clear: View[Nothing]                                         // jatatui Clear, under popups

  // ---- escape hatches (jatatui types visible on purpose) ----
  def widget(w: => jatatui.core.widgets.Widget): View[Nothing]
  def stateful[S](id: String, init: => S)(w: => jatatui.core.widgets.StatefulWidget[S]): View[Nothing]
  def draw(f: (jatatui.core.layout.Rect, jatatui.core.buffer.Buffer) => Unit): View[Nothing]

  // ---- memo (phase 2) ----
  def lazily[A, Msg](dep: A)(f: A => View[Msg]): View[Msg]         // see 7.4
end View

/** Size of a child inside its parent row/column. Default: fill(1). */
enum Size derives CanEqual:
  case Length(n: Int); case Fill(weight: Int); case Percent(p: Int)
  case Min(n: Int); case Max(n: Int); case Ratio(num: Int, den: Int)

extension [Msg](v: View[Msg])
  // sizing (read by the parent container)
  def length(n: Int): View[Msg];  def fill(weight: Int = 1): View[Msg];  def percent(p: Int): View[Msg]
  def min(n: Int): View[Msg];     def max(n: Int): View[Msg];            def ratio(n: Int, d: Int): View[Msg]
  // container options (no-op on leaves)
  def spacing(n: Int): View[Msg];  def margin(h: Int, v: Int): View[Msg];  def flex(f: Flex): View[Msg]
  // decoration
  def bordered: View[Msg]                               // Block.bordered()
  def titled(title: String | Line): View[Msg]           // Block.bordered().withTitle(...)
  def border(b: Border): View[Msg]                      // full Block options (type, style, padding, titles)
  def style(s: Style): View[Msg]
  def centered: View[Msg]                               // text alignment
  def center(width: Size, height: Size): View[Msg]      // Rect.centered(Constraint, Constraint) for popups
  // identity and interaction
  def id(key: String): View[Msg]                        // stable key for runtime-owned widget state
  def onClick[M >: Msg](msg: M): View[M]                // hit-tested against the last drawn frame
```

**How jatatui widgets are wrapped.** Each leaf node holds plain Scala data and builds the jatatui widget at
render time (widget construction is cheap; rendering is the cost, report 5.3). Mapping (VERIFIED method
names in the 0.31.0 sources/report): `text` -> `Paragraph.of(Line)`; `paragraph` -> `Paragraph.of(Text)
.withWrap(Wrap(trim)).withScroll(Scroll(y, 0))`; `list` -> `jatatui.widgets.list.List.of(items)
.withHighlightStyle(...).withHighlightSymbol(...)` rendered with `frame.renderStatefulWidget(list, area,
state)`; `tabs` -> `Tabs.of(lines).withSelected(i)`; `gauge` -> `Gauge.empty().withRatio(clamped)
.withLabel(label)`; `sparkline` -> `Sparkline.empty().withDataLongs(data*).withMax(max)` (or `withoutMax`);
`bordered`/`titled` -> `Block.bordered().withTitle(...)`, children laid out in `block.inner(area)`;
`clear` -> `Clear.INSTANCE`. `textField` is tui4s's own grapheme-aware renderer (jatatui's `TextInput`
uses UTF-16 indexes, report 3.7) and calls `frame.setCursorPosition(x, y)` when focused. Widgets that are
not wrapped yet (BarChart, Chart, Canvas, Calendar) are reachable through `View.widget(...)` from day one.

**Who owns widget state.** The model holds only what the *app* decides (`selected: Int`). The jatatui
`ListState`/`TableState`/`ScrollbarState` objects (mutable, written by `render`: offset, clamped
selection) live in the runtime's `StateStore`, keyed by `.id(...)` or by the node's path in the tree
(child indexes + node kind). Before each render the runtime calls `state.select(Optional.of(i))` from the
model; `render` updates the offset, which persists to the next frame, so a list scrolls like ratatui users
expect. Entries not visited in a frame are dropped (the jatatui-react "sweep" rule, report 7.2). User code
never sees these objects.

**Styles and text.** Opaque types over jatatui's immutable records, so there is no conversion cost and
equality is structural:

```scala
package tui4s.view

opaque type Color = jatatui.core.style.Color
object Color:
  val Reset, Black, Red, Green, Yellow, Blue, Magenta, Cyan, Gray, DarkGray, White: Color  // Color.RED, ...
  def rgb(r: Int, g: Int, b: Int): Color = jatatui.core.style.Color.Rgb(r, g, b)
  def indexed(i: Int): Color             = jatatui.core.style.Color.Indexed(i)

opaque type Style = jatatui.core.style.Style
object Style:
  val default: Style = jatatui.core.style.Style.DEFAULT
  val bold, dim, italic, underlined, reversed, crossedOut: Style
  def fg(c: Color): Style = default.withFg(c)

// Top level of the same file (the opaque alias is transparent here). Not inside `object Style`:
// there, the extension `bold` would clash with `val bold` (UNVERIFIED but likely "already defined").
extension (s: Style)
  def fg(c: Color): Style       = s.withFg(c)      // NOT s.fg(...): fg() is the record accessor (report 2.5)
  def bg(c: Color): Style       = s.withBg(c)
  def bold: Style               = s.bold()
  def ++(other: Style): Style   = s.patch(other)
  def toJatatui: jatatui.core.style.Style = s

opaque type Span = jatatui.core.text.Span
opaque type Line = jatatui.core.text.Line
opaque type Text = jatatui.core.text.Text
object Line:
  def apply(parts: (String | Span)*): Line    // Line.fromSpans(...); a String becomes Span.raw
object Text:
  def apply(lines: (String | Line)*): Text
extension (s: String)
  def styled(st: Style): Span;  def fg(c: Color): Span;  def bg(c: Color): Span
  def bold: Span;  def dim: Span;  def italic: Span;  def reversed: Span;  def crossedOut: Span
extension (sp: Span)
  def fg(c: Color): Span;  def bold: Span;  def dim: Span    // Span implements jatatui Stylize (VERIFIED)
```

The opaque types hide jatatui's name clashes (`List`, `Line`, `Block`, `Color`) and the `Style.fg()` trap
from user code. `Style.reversed` etc. map to jatatui's shorthands (`reversed()`, VERIFIED list in report 2.5).

### 2.7 Input: keys, mouse, keymaps, text fields

```scala
package tui4s.input

enum Key derives CanEqual:
  case Char(c: scala.Char)          // printable; Shift is already applied (Key.Char('A'))
  case Ctrl(c: scala.Char)          // Ctrl + letter/digit, lower case: Key.Ctrl('c')
  case Alt(c: scala.Char)
  case Enter, Esc, Backspace, Delete, Insert, Tab, BackTab
  case Up, Down, Left, Right, Home, End, PageUp, PageDown
  case F(n: Int)
  case WithMods(key: Key, mods: Mods)   // a named key with modifiers, e.g. Ctrl+Up

opaque type Mods = Int                  // bits of tui.crossterm.KeyModifiers (SHIFT=1, CONTROL=2, ALT=4, ...)
object Mods:
  extension (m: Mods) def shift: Boolean; def ctrl: Boolean; def alt: Boolean

final case class Mouse(kind: MouseKind, column: Int, row: Int, mods: Mods) derives CanEqual
enum MouseKind derives CanEqual:
  case Down(b: Button); case Up(b: Button); case Drag(b: Button)
  case Moved, ScrollUp, ScrollDown, ScrollLeft, ScrollRight
enum Button derives CanEqual:
  case Left, Right, Middle

enum InputEvent derives CanEqual:
  case Pressed(key: Key); case Clicked(mouse: Mouse); case Pasted(text: String)
  case Resized(columns: Int, rows: Int); case FocusGained, FocusLost

/** A key binding with help text. The same value routes keys AND renders the help bar. */
final case class Bind[+Msg](keys: Seq[Key], help: String, msg: Msg, hidden: Boolean = false)
object Bind:
  def apply[Msg](key: Key, help: String, msg: Msg): Bind[Msg]
  def apply[Msg](c: Char, help: String, msg: Msg): Bind[Msg]          // = Bind(Key.Char(c), ...)

final case class Keymap[+Msg](binds: Seq[Bind[Msg]]):
  def ++[M >: Msg](that: Keymap[M]): Keymap[M]
  def lookup(key: Key): Option[Msg]
object Keymap:
  def apply[Msg](binds: Bind[Msg]*): Keymap[Msg]

/** A pure, grapheme-aware single-line editor (a TEA "component": its own model and update). */
final case class TextField(value: String, cursor: Int) derives CanEqual:  // cursor in grapheme clusters
  def handle(key: Key): TextField     // Char inserts; Backspace, Delete, Left, Right, Home, End,
                                      // Ctrl('a'/'e'/'u'/'k'/'w') edit; any other key: returns `this`
  def insert(s: String): TextField    // for Sub.paste
  def isBlank: Boolean
object TextField:
  val empty: TextField = TextField("", 0)
```

Conversion from crossterm (VERIFIED types, report 4.2): `tui.crossterm.Event.Key(KeyEvent(code,
modifiers, kind, state))` is matched with **type patterns** (Java records are not extractors, report 8.4).
Only `KeyEventKind.Press` and `Repeat` are delivered (Release is dropped). `KeyCode.Char` + CONTROL becomes
`Key.Ctrl(c.toLower)`, + ALT becomes `Key.Alt(c)`, otherwise `Key.Char(c)`. The match over the sealed Java
interfaces is exhaustive-checked by Scala 3.9 (report 8.5), so a new upstream variant is a compile error
in tui4s (with `-Wconf:id=E029:e`), not a silent drop.

### 2.8 Runner, options, errors

```scala
package tui4s

object Tui:
  /** Runs the app in the real terminal. Returns the final model. Restores the terminal in every case. */
  def run[Model, Msg](program: Program[Model, Msg])(using Frame): Model < (Async & Abort[TuiError])

  /** Starts the app and gives a handle (send messages, observe models, stop). */
  def start[Model, Msg](program: Program[Model, Msg])(using Frame): TuiHandle[Model, Msg] < (Async & Scope)

  /** JVM entry used by TuiApp: scheduler defaults, run, exit code, shutdown hook. */
  def main[Model, Msg](program: Program[Model, Msg], args: Array[String]): Unit

  /** Same loop on other ports (TestBackend, a recorded event script). Used by the test kit. */
  def runOn[Model, Msg](program: Program[Model, Msg], terminal: runtime.TerminalPort,
                        input: runtime.InputPort)(using Frame): Model < (Async & Abort[TuiError])

final case class TuiOptions(
    viewport: Viewport = Viewport.Fullscreen,          // or Viewport.Inline(height)
    mouse: Boolean = false,                            // EnableMouseCapture
    bracketedPaste: Boolean = true,                    // EnableBracketedPaste
    focusEvents: Boolean = false,                      // EnableFocusChange
    ctrlCQuits: Boolean = true,                        // raw mode turns Ctrl-C into a key; quit by default
    frameBudget: java.time.Duration = java.time.Duration.ofMillis(16),   // max ~60 frames/s
    inputPoll: java.time.Duration = java.time.Duration.ofMillis(50),     // interrupt latency of the input fiber
    inboxCapacity: Int = 4096,
    maxSendsPerBatch: Int = 10_000,                    // guard against Cmd.send loops
    schedulerDefaults: Boolean = true,                 // low-idle Kyo scheduler flags (5.8)
    traceFile: Option[java.nio.file.Path] = None       // one line per msg / cmd / sub change / frame (6.3)
)
object TuiOptions:
  val default: TuiOptions = TuiOptions()

sealed abstract class TuiError(message: String, cause: Throwable | Null) extends Exception(message, cause)
object TuiError:
  final class NotATerminal(detail: String)   extends TuiError(detail, null)    // stdin/stdout not a TTY
  final class NativeAccess(detail: String)   extends TuiError(detail, null)    // JEP 472 flag missing
  final class Io(cause: Throwable)           extends TuiError("terminal I/O failed", cause)
  final class AppFailed(cause: Throwable)    extends TuiError("update/view/command failed", cause)
```

`TuiError extends Exception` so that the ZIO and Cats facades can use it as their error type unchanged.
jatatui's checked `IOException` (invisible to Scala, report 8.3) and crossterm's `RuntimeException("IO
error: ...")` become `TuiError.Io`; a throw from `update`, `view`, `subscriptions` or a `Cmd.perform` task
becomes `TuiError.AppFailed`.

### 2.9 How the app starts and how it quits

- **Start (Kyo, simplest):** `object Counter extends TuiApp[Model, Msg]` and run it. `Tui.main` sets the
  scheduler defaults, starts `Tui.run(this)` on an unscoped fiber, installs a guarded JVM shutdown hook
  (SIGTERM: interrupt the fiber and wait, at most 1 s, for the restore latch), blocks, and prints a failure
  only after the terminal is restored (UNVERIFIED shape; built on `Fiber.initUnscoped` and
  `fiber.block(timeout)`, VERIFIED names).
- **Start (Kyo, embedded):** `object Main extends KyoApp: run { Tui.run(Counter).map(m => Console.printLine(s"final: $m")) }`.
  Here the scheduler flags must come from `-D...` (KyoApp loads Kyo first).
- **Quit from the app:** `m.withCmd(Cmd.quit)`. The runtime stops after the current batch, does not draw
  again, restores the terminal and `Tui.run` returns the final model.
- **Quit by Ctrl-C:** on by default (`ctrlCQuits`), checked before routing, so a buggy keymap cannot trap
  the user. Turn it off to bind Ctrl-C yourself.
- **Quit from outside:** interrupt the fiber (`fiber.interruptAwait`), `handle.stop`, ZIO interruption or
  CE cancellation. The terminal is restored before `interruptAwait` returns (F8, `Sync.ensure`).
- **Quit by failure:** any `TuiError`; the terminal is restored first, then the error surfaces.

### 2.10 Where state lives and how events reach user code

```
key/mouse/resize/paste --(input thread)--> InputEvent --(routing subs of the CURRENT model, first match)--> Msg
timer/stream/signal ----(sub fibers)-----> value     --(current toMsg of that sub id)------------------> Msg
Cmd task result --------(cmd fibers)-----> Msg
                                             Msg --> update(model, msg) --> Next = model' [+ Cmd]
                                                       |                       |
                                    subscriptions(model') (diff by id)   Cmd interpreted (fibers, send, quit)
                                                       |
                         model' != lastDrawn ? view(model') != lastView ? --> render --> Terminal.draw
```

- **The model** is one immutable value held by the loop fiber (a private field of the `Engine`, 5.2).
  Only `update` produces a new one. User code never holds a `Var`, `AtomicRef` or `SignalRef`; outside
  code observes models through `TuiHandle.models` (a `SignalRef` set once per batch) and changes them only
  by sending messages.
- **View-local widget state** (list/table scroll offsets) is owned by the runtime's `StateStore` (2.6).
- **Async sources** (commands, timers, streams, Kyo `Signal`s, or anything through `Dispatch`) never touch
  the model: they send messages through the bounded inbox.

---

## 3. The three sample apps for Kyo users

Each app is one file. Model and Msg are top-level types of the file's package (an `object X extends
TuiApp[X.Model, X.Msg]` would be a cyclic reference, UNVERIFIED but likely). Compile flags as in the build:
`-language:strictEquality` (hence `derives CanEqual`), `-Wvalue-discard`, `-Wnonunit-statement`.

### 3.1 Counter

```scala
package examples.counter

import tui4s.*

enum Msg derives CanEqual:
  case Inc, Dec, Quit

final case class Model(count: Int) derives CanEqual

object Counter extends TuiApp[Model, Msg]:

  val keys: Keymap[Msg] = Keymap(
    Bind('+', "increment", Msg.Inc),
    Bind('-', "decrement", Msg.Dec),
    Bind('q', "quit", Msg.Quit)
  )

  def init: Next[Model, Msg] = Model(0)

  def update(m: Model, msg: Msg): Next[Model, Msg] = msg match
    case Msg.Inc  => m.copy(count = m.count + 1)
    case Msg.Dec  => m.copy(count = m.count - 1)
    case Msg.Quit => m.withCmd(Cmd.quit)

  def view(m: Model): View[Msg] =
    View.column(
      View.text(Line("Count: ", m.count.toString.bold)).centered,
      View.help(keys).length(1)
    ).titled("Counter")

  override def subscriptions(m: Model): Sub[Msg] = Sub.keymap(keys)
```

There is no `import kyo.*`: an app without effects has no Kyo types at all. This file compiles unchanged
against `tui4s.zio.*` or `tui4s.cats.*` if only the import and `TuiApp` change (section 4).

### 3.2 Todo

```scala
package examples.todo

import java.io.IOException
import java.nio.file.{Files, Path}
import kyo.*
import scala.jdk.CollectionConverters.*
import tui4s.*

enum Focus derives CanEqual:
  case Input, Items

final case class Item(title: String, done: Boolean) derives CanEqual

final case class Model(
    input: TextField,
    items: Vector[Item],
    selected: Int,
    focus: Focus,
    status: String
) derives CanEqual:
  def modifySelected(f: Item => Item): Model =
    if items.isDefinedAt(selected) then copy(items = items.updated(selected, f(items(selected)))) else this
  def deleteSelected: Model =
    if !items.isDefinedAt(selected) then this
    else
      val rest = items.patch(selected, Nil, 1)
      copy(items = rest, selected = selected.min(rest.size - 1).max(0))
end Model

enum Msg derives CanEqual:
  case Edit(key: Key)
  case Paste(text: String)
  case Add, Up, Down, Toggle, Delete, SwitchFocus, Quit
  case Loaded(result: Result[IOException, Vector[Item]])
  case Saved(result: Result[IOException, Unit])

/** Plain Kyo code. Nothing here knows about tui4s. */
object TodoStore:
  val file: Path = Path.of(java.lang.System.getProperty("user.home"), ".tui4s-todos.txt")

  def load(using Frame): Vector[Item] < (Sync & Abort[IOException]) =
    Abort.catching[IOException](Sync.defer {
      if !Files.exists(file) then Vector.empty
      else Files.readAllLines(file).asScala.toVector.map(l => Item(l.drop(4), done = l.startsWith("[x]")))
    })

  def save(items: Vector[Item])(using Frame): Unit < (Sync & Abort[IOException]) =
    Abort.catching[IOException](Sync.defer {
      Files.write(file, items.map(i => (if i.done then "[x] " else "[ ] ") + i.title).asJava)
    }).unit
end TodoStore

object Todo extends TuiApp[Model, Msg]:

  val global = Keymap(Bind(Key.Tab, "switch focus", Msg.SwitchFocus), Bind(Key.Esc, "save & quit", Msg.Quit))
  val inputKeys = Keymap(Bind(Key.Enter, "add", Msg.Add))
  val itemKeys = Keymap(
    Bind(Seq(Key.Up, Key.Char('k')), "up", Msg.Up),
    Bind(Seq(Key.Down, Key.Char('j')), "down", Msg.Down),
    Bind(' ', "toggle", Msg.Toggle),
    Bind('d', "delete", Msg.Delete),
    Bind('q', "save & quit", Msg.Quit)
  )

  def activeKeys(m: Model): Keymap[Msg] =
    if m.focus == Focus.Input then global ++ inputKeys else global ++ itemKeys

  def init: Next[Model, Msg] =
    Model(input = TextField.empty, items = Vector.empty, selected = 0, focus = Focus.Input, status = "loading…")
      .withCmd(Cmd.attempt(TodoStore.load)(Msg.Loaded(_)))

  def update(m: Model, msg: Msg): Next[Model, Msg] = msg match
    case Msg.Edit(key) => m.copy(input = m.input.handle(key))
    case Msg.Paste(s)  => m.copy(input = m.input.insert(s))
    case Msg.Add =>
      if m.input.isBlank then m
      else m.copy(input = TextField.empty, items = m.items :+ Item(m.input.value.trim, done = false))
    case Msg.Up          => m.copy(selected = (m.selected - 1).max(0))
    case Msg.Down        => m.copy(selected = (m.selected + 1).min(m.items.size - 1).max(0))
    case Msg.Toggle      => m.modifySelected(i => i.copy(done = !i.done))
    case Msg.Delete      => m.deleteSelected
    case Msg.SwitchFocus => m.copy(focus = if m.focus == Focus.Input then Focus.Items else Focus.Input)
    case Msg.Quit =>
      m.copy(status = "saving…").withCmd(Cmd.attempt(TodoStore.save(m.items))(Msg.Saved(_)))
    case Msg.Loaded(Result.Success(items)) => m.copy(items = items, status = s"${items.size} loaded")
    case Msg.Loaded(failed)                => m.copy(status = s"could not load: $failed")
    case Msg.Saved(_)                      => m.withCmd(Cmd.quit)   // Tui.run returns the model with its status

  override def subscriptions(m: Model): Sub[Msg] = m.focus match
    case Focus.Input =>
      Sub.batch(
        Sub.keymap(activeKeys(m)),               // first: Tab, Esc, Enter
        Sub.paste(Msg.Paste(_)),
        Sub.keys { case key => Msg.Edit(key) }   // everything else goes to the text field
      )
    case Focus.Items => Sub.keymap(activeKeys(m))

  def view(m: Model): View[Msg] =
    View.column(
      View.textField(m.input, focused = m.focus == Focus.Input, placeholder = "What needs to be done?")
        .titled("New todo")
        .length(3),
      View.list(m.items.map(itemLine), selected = Option.when(m.items.nonEmpty)(m.selected))
        .titled(s"Todos ${m.items.count(_.done)}/${m.items.size}")
        .id("todos"),
      View.row(View.help(activeKeys(m)), View.text(m.status).length(m.status.length + 1)).length(1)
    )

  private def itemLine(i: Item): Line =
    if i.done then Line("[x] ".fg(Color.Green), i.title.dim.crossedOut)
    else Line("[ ] ", i.title)
end Todo
```

What the sample shows: focus is plain model data; which keys work is `subscriptions(model)`, so typing `q`
in the input adds a "q" and in the list it quits; the help bar is computed from the same keymaps; a Kyo
computation (`TodoStore.load`, typed `Abort[IOException]`) becomes a message with `Cmd.attempt`, so a
failed load cannot crash the app; "save, then quit" is two messages, not a callback.

### 3.3 Dashboard

```scala
package examples.dashboard

import kyo.*
import tui4s.*

enum Msg derives CanEqual:
  case PrevTab, NextTab, TogglePause, Quit
  case Sample(value: Long)
  case Log(line: String)

final case class Model(tab: Int, paused: Boolean, samples: Vector[Long], logs: Vector[String])
    derives CanEqual:
  def latest: Long = samples.lastOption.getOrElse(0L)

object Dashboard extends TuiApp[Model, Msg]:

  val tabs = Vector("Overview", "Logs")

  val keys = Keymap(
    Bind(Key.Left, "prev tab", Msg.PrevTab),
    Bind(Key.Right, "next tab", Msg.NextTab),
    Bind('p', "pause sampling", Msg.TogglePause),
    Bind('q', "quit", Msg.Quit)
  )

  /** One sample in 0..99. Any Kyo computation works here (a metric read, an HTTP call). */
  val sample: Long < Sync = Random.nextInt(100).map(_.toLong)

  /** Endless log lines, one every 700 ms. chunkSize = 1: with the default 4096 the effectful `map`
    * would emit nothing until 4096 elements were mapped (rechunk gotcha, kyo.md 2.8). */
  val logLines: Stream[String, Async] =
    Stream.range(0, Int.MaxValue, chunkSize = 1)
      .map(i => Async.sleep(700.millis).andThen(Clock.now).map(now => s"$now  event #$i"))

  def init: Next[Model, Msg] = Model(tab = 0, paused = false, samples = Vector.empty, logs = Vector.empty)

  def update(m: Model, msg: Msg): Next[Model, Msg] = msg match
    case Msg.PrevTab     => m.copy(tab = (m.tab + tabs.size - 1) % tabs.size)
    case Msg.NextTab     => m.copy(tab = (m.tab + 1) % tabs.size)
    case Msg.TogglePause => m.copy(paused = !m.paused)
    case Msg.Sample(v)   => m.copy(samples = (m.samples :+ v).takeRight(200))
    case Msg.Log(line)   => m.copy(logs = (m.logs :+ line).takeRight(1000))
    case Msg.Quit        => m.withCmd(Cmd.quit)

  override def subscriptions(m: Model): Sub[Msg] =
    Sub.batch(
      Sub.keymap(keys),
      // A function of the model: pausing removes this subscription and the runtime interrupts its fiber.
      if m.paused then Sub.none else Sub.poll("load", 250.millis)(sample)(Msg.Sample(_)),
      Sub.stream("logs")(logLines)(Msg.Log(_))
    )

  def view(m: Model): View[Msg] =
    View.column(
      View.tabs(tabs, selected = m.tab).bordered.length(3),
      if m.tab == 0 then overview(m) else View.log(m.logs).titled(s"Logs (${m.logs.size})"),
      View.help(keys).length(1)
    )

  private def overview(m: Model): View[Msg] =
    View.column(
      View.gauge(m.latest / 100.0, label = s"${m.latest}%")
        .titled(if m.paused then "Load (paused)" else "Load")
        .length(3),
      View.sparkline(m.samples, max = Some(100L)).titled("Last 50 s")
    )
end Dashboard
```

What the sample shows: the "fiber that produces a value every 250 ms" is `Sub.poll`, which the runtime
runs as a fiber in the app's `Scope`; the log pane is fed by a plain Kyo `Stream`; `p` makes the poll
subscription disappear from `subscriptions(m)`, the runtime interrupts the fiber, and with the Overview tab
hidden *and* paused, only the 700 ms log stream wakes the app. Detail: a log line that arrives while
the Overview tab is shown still changes the model; model equality alone would draw a frame that looks the same
(~1-2 ms; jatatui's diff then writes 0 cells), unless the second, view-level check (5.3, 7.3) sees that
the view did not change. Here it did not (the Overview view does not mention the logs), so no frame.

---

## 4. ZIO and Cats Effect users: facades and samples

### 4.1 The principle

The shared surface (2.1) has no Kyo types, so the facades do not wrap `Program`, `View`, `Next`, `Key`
or `Keymap`: they re-export them. A facade adds exactly three things: (1) `Cmd` and `Sub` constructors
that take its effect type, (2) a `Tui.run` that returns its effect type, (3) an app base class. Each
constructor lowers to the core's `Cmd.fromDispatch` / `Sub.fromDispatch` (2.3, 2.4), so the core runtime
does not know that ZIO or Cats exist.

Re-export mechanics: `export tui4s.view.*`, `export tui4s.input.*`, type aliases `type Cmd[+Msg] =
tui4s.Cmd[Msg]` / `type Sub[+Msg] = tui4s.Sub[Msg]` next to facade objects named `Cmd` / `Sub`, and
forwarders for the top-level `withCmd` extension. (UNVERIFIED: whether `export` can re-export top-level
definitions of another package; if not, the facade declares forwarding aliases, generated mechanically.)
Inside the facade sources, `_root_.zio` and `_root_.cats` are used, because the package names
`tui4s.zio` / `tui4s.cats` shadow the root packages for code in package `tui4s` (the same reason the
vendored Cats bridge already imports `_root_.cats`).

### 4.2 tui4s-zio API

```scala
package tui4s.zio

import _root_.zio.{Duration as ZDuration, Exit, Trace, UIO, ZIO, ZIOAppDefault}
import _root_.zio.stream.ZStream

export tui4s.view.*                      // View, Size, Style, Color, Span, Line, Text, Border, ...
export tui4s.input.*                     // Key, Mods, Mouse, InputEvent, Bind, Keymap, TextField
export tui4s.{Program, Next, TuiOptions, TuiError, Viewport}
type Cmd[+Msg] = tui4s.Cmd[Msg]
type Sub[+Msg] = tui4s.Sub[Msg]

object Cmd:
  val none: Cmd[Nothing]                   = tui4s.Cmd.none
  val quit: Cmd[Nothing]                   = tui4s.Cmd.quit
  def send[Msg](msg: Msg): Cmd[Msg]        = tui4s.Cmd.send(msg)
  def batch[Msg](cmds: Cmd[Msg]*): Cmd[Msg] = tui4s.Cmd.batch(cmds*)
  def cancel(key: String): Cmd[Nothing]    = tui4s.Cmd.cancel(key)

  /** Runs on the CALLER's ZIO runtime (FiberRefs, executor, logger). Typed errors become a message. */
  def attempt[E, A, Msg](zio: => ZIO[Any, E, A])(toMsg: Either[E, A] => Msg)(using Trace): Cmd[Msg]
  /** Same, with the full Exit (defects and interruption too). */
  def exit[E, A, Msg](zio: => ZIO[Any, E, A])(toMsg: Exit[E, A] => Msg)(using Trace): Cmd[Msg]
  /** A defect fails the app (terminal restored first). */
  def perform[A, Msg](zio: => UIO[A])(toMsg: A => Msg)(using Trace): Cmd[Msg]
  def exec(zio: => ZIO[Any, Any, Any])(using Trace): Cmd[Nothing]
  def delay[Msg](d: ZDuration)(msg: Msg): Cmd[Msg]

object Sub:
  // shared, forwarded: none, batch, keys, keymap, mouse, resize, paste, focus, events
  def every[Msg](interval: ZDuration)(msg: Msg): Sub[Msg]       // zio.Duration IS java.time.Duration
  def poll[A, Msg](id: String, interval: ZDuration)(zio: => UIO[A])(toMsg: A => Msg)(using Trace): Sub[Msg]
  def stream[A, Msg](id: String)(stream: => ZStream[Any, Nothing, A])(toMsg: A => Msg)(using Trace): Sub[Msg]

object Tui:
  def run[Model, Msg](program: Program[Model, Msg])(using Trace): ZIO[Any, TuiError, Model]

abstract class ZTuiApp[Model, Msg] extends ZIOAppDefault with Program[Model, Msg]:
  final def run: ZIO[Any, Any, Any] = Tui.run(this)
```

**How it maps onto the core** (bridge = the verified `AmbientZIOs` prototype, F12, ~40 lines, vendored
into tui4s-zio on top of kyo-zio RC7):

| facade | core | notes |
| --- | --- | --- |
| `Tui.run(p)` | `ZIO.succeed(SchedulerDefaults.applyIfUnset()) *> AmbientZIOs.run(core.Tui.run(p))` | `AmbientZIOs.run` captures `ZIO.runtime` into an inheritable `kyo.Local`; ZIO interruption interrupts the Kyo fiber **and waits** (F11), so the `Sync.ensure` restore is done when the ZIO fiber reports interrupted |
| `Cmd.attempt(z)(f)` | `tui4s.Cmd.fromDispatch(d => AmbientZIOs.get(z.either).map(e => d(f(e))))` | the command fiber is forked inside the app, the `Local` is inherited, so `get` forks the ZIO on the caller's runtime (F12: FiberRef 7 seen from a forked Kyo fiber) |
| `Cmd.exit(z)(f)` | same with `z.exit` | |
| `Sub.poll(id, d)(z)(f)` | `tui4s.Sub.fromDispatch(id)(d => AmbientZIOs.get((z.flatMap(a => put(d, a)) *> ZIO.sleep(interval)).forever))(f)` | one bridge call for the subscription's whole life; Kyo interrupt -> ZIO interrupt (F11) |
| `Sub.stream(id)(s)(f)` | `tui4s.Sub.fromDispatch(id)(d => AmbientZIOs.get(s.runForeach(a => put(d, a))))(f)` | `put(d, a) = ZIO.async(cb => d.unsafe.put(a, () => cb(ZIO.unit)))`: backpressure from the inbox reaches the ZStream; not `ZStreams.get` (it would run on `Runtime.default`, F12) |

No Kyo type appears in any facade signature (`Frame` is summoned inside the facade's own code, so it
points at tui4s sources; that only affects Kyo's debug traces).

### 4.3 Counter and Dashboard for ZIO users

```scala
package examples.ziocounter

import tui4s.zio.*

enum Msg derives CanEqual:
  case Inc, Dec, Quit

final case class Model(count: Int) derives CanEqual

object Counter extends ZTuiApp[Model, Msg]:

  val keys: Keymap[Msg] = Keymap(
    Bind('+', "increment", Msg.Inc),
    Bind('-', "decrement", Msg.Dec),
    Bind('q', "quit", Msg.Quit)
  )

  def init: Next[Model, Msg] = Model(0)

  def update(m: Model, msg: Msg): Next[Model, Msg] = msg match
    case Msg.Inc  => m.copy(count = m.count + 1)
    case Msg.Dec  => m.copy(count = m.count - 1)
    case Msg.Quit => m.withCmd(Cmd.quit)

  def view(m: Model): View[Msg] =
    View.column(
      View.text(Line("Count: ", m.count.toString.bold)).centered,
      View.help(keys).length(1)
    ).titled("Counter")

  override def subscriptions(m: Model): Sub[Msg] = Sub.keymap(keys)
```

Identical to 3.1 except `import tui4s.zio.*` and `ZTuiApp`. (Packages are named `ziocounter`, not
`examples.zio.counter`, so that no package named `zio` can shadow the root `zio`.)

```scala
package examples.ziodashboard

import zio.{Clock, Random, Schedule, UIO, durationInt}
import zio.stream.{UStream, ZStream}
import tui4s.zio.*

enum Msg derives CanEqual:
  case PrevTab, NextTab, TogglePause, Quit
  case Sample(value: Long)
  case Log(line: String)

final case class Model(tab: Int, paused: Boolean, samples: Vector[Long], logs: Vector[String])
    derives CanEqual:
  def latest: Long = samples.lastOption.getOrElse(0L)

object Dashboard extends ZTuiApp[Model, Msg]:

  val tabs = Vector("Overview", "Logs")

  val keys = Keymap(
    Bind(Key.Left, "prev tab", Msg.PrevTab),
    Bind(Key.Right, "next tab", Msg.NextTab),
    Bind('p', "pause sampling", Msg.TogglePause),
    Bind('q', "quit", Msg.Quit)
  )

  val sample: UIO[Long] = Random.nextLongBounded(100)

  val logLines: UStream[String] =
    ZStream.iterate(0L)(_ + 1)
      .schedule(Schedule.spaced(700.millis))
      .mapZIO(i => Clock.instant.map(now => s"$now  event #$i"))

  def init: Next[Model, Msg] = Model(tab = 0, paused = false, samples = Vector.empty, logs = Vector.empty)

  def update(m: Model, msg: Msg): Next[Model, Msg] = msg match
    case Msg.PrevTab     => m.copy(tab = (m.tab + tabs.size - 1) % tabs.size)
    case Msg.NextTab     => m.copy(tab = (m.tab + 1) % tabs.size)
    case Msg.TogglePause => m.copy(paused = !m.paused)
    case Msg.Sample(v)   => m.copy(samples = (m.samples :+ v).takeRight(200))
    case Msg.Log(line)   => m.copy(logs = (m.logs :+ line).takeRight(1000))
    case Msg.Quit        => m.withCmd(Cmd.quit)

  override def subscriptions(m: Model): Sub[Msg] =
    Sub.batch(
      Sub.keymap(keys),
      if m.paused then Sub.none else Sub.poll("load", 250.millis)(sample)(Msg.Sample(_)),
      Sub.stream("logs")(logLines)(Msg.Log(_))
    )

  def view(m: Model): View[Msg] =
    View.column(
      View.tabs(tabs, selected = m.tab).bordered.length(3),
      if m.tab == 0 then overview(m) else View.log(m.logs).titled(s"Logs (${m.logs.size})"),
      View.help(keys).length(1)
    )

  private def overview(m: Model): View[Msg] =
    View.column(
      View.gauge(m.latest / 100.0, label = s"${m.latest}%")
        .titled(if m.paused then "Load (paused)" else "Load")
        .length(3),
      View.sparkline(m.samples, max = Some(100L)).titled("Last 50 s")
    )
end Dashboard
```

`init`, `update`, `view` are character-for-character the Kyo version; only `sample`, `logLines` and the
imports differ. (ZIO API names `Random.nextLongBounded`, `ZStream.iterate`, `.schedule`, `Clock.instant`
are from memory of ZIO 2.1: UNVERIFIED here, no ZIO sources in the scratch folder.)

### 4.4 tui4s-cats API

```scala
package tui4s.cats

import _root_.cats.effect.{IO, IOApp}
import _root_.fs2.Stream as Fs2Stream
import scala.concurrent.duration.FiniteDuration

export tui4s.view.*
export tui4s.input.*
export tui4s.{Program, Next, TuiOptions, TuiError, Viewport}
type Cmd[+Msg] = tui4s.Cmd[Msg]
type Sub[+Msg] = tui4s.Sub[Msg]

object Cmd:
  // shared, forwarded: none, quit, send, batch, cancel
  def attempt[A, Msg](io: => IO[A])(toMsg: Either[Throwable, A] => Msg): Cmd[Msg]
  def perform[A, Msg](io: => IO[A])(toMsg: A => Msg): Cmd[Msg]          // an IO error fails the app
  def exec(io: => IO[Unit]): Cmd[Nothing]
  def delay[Msg](d: FiniteDuration)(msg: Msg): Cmd[Msg]

object Sub:
  // shared, forwarded: none, batch, keys, keymap, mouse, resize, paste, focus, events
  def every[Msg](interval: FiniteDuration)(msg: Msg): Sub[Msg]
  def poll[A, Msg](id: String, interval: FiniteDuration)(io: => IO[A])(toMsg: A => Msg): Sub[Msg]
  def stream[A, Msg](id: String)(stream: => Fs2Stream[IO, A])(toMsg: A => Msg): Sub[Msg]

object Tui:
  def run[Model, Msg](program: Program[Model, Msg]): IO[Model]

abstract class IOTuiApp[Model, Msg] extends IOApp.Simple with Program[Model, Msg]:
  final def run: IO[Unit] = Tui.run(this).void
```

**How it maps onto the core** (bridge = the vendored, tested `CatsBridge` prototype, F7/F8, 13 tests):

| facade | core | notes |
| --- | --- | --- |
| `Tui.run(p)` | `IO.delay(SchedulerDefaults.applyIfUnset()) *> CatsBridge.runWithDispatcher[IO, Model](d ?=> ambient.let(Present(d))(core.Tui.run(p)))` | `runWithDispatcher` creates `Dispatcher.parallel[IO](await = false)` (F10: `await = true` can lose a cancel); `core.Tui.run`'s `Abort[TuiError]` fits `Abort[Throwable]` because `Abort[-E]` is contravariant (VERIFIED); IO cancel -> Kyo interrupt, **cancel-and-await**, so the `Sync.ensure` restore has run when `cancel` returns (F8) |
| `Cmd.attempt(io)(f)` | `tui4s.Cmd.fromDispatch(d => withDispatcher(disp => CatsBridge.getF(io.attempt)(using disp).map(e => d(f(e)))))` | `ambient` is an inheritable `kyo.Local[Maybe[Dispatcher[IO]]]` (the AmbientZIOs pattern); the IO runs on the caller's `IORuntime`, not the global one |
| `Sub.poll(id, d)(io)(f)` | `tui4s.Sub.fromDispatch(id)(d => withDispatcher(disp => CatsBridge.getF((io.flatMap(a => put(d, a)) >> IO.sleep(interval)).foreverM)(using disp)))(f)` | one bridge call per subscription life; Kyo interrupt cancels the IO (F8) |
| `Sub.stream(id)(s)(f)` | `tui4s.Sub.fromDispatch(id)(d => withDispatcher(disp => CatsBridge.getF(s.evalMap(a => put(d, a)).compile.drain)(using disp)))(f)` | `put(d, a) = IO.async_[Unit](cb => d.unsafe.put(a, () => cb(Right(()))))`: inbox backpressure reaches fs2 |

### 4.5 Counter and Dashboard for Cats Effect users

```scala
package examples.catscounter

import tui4s.cats.*

enum Msg derives CanEqual:
  case Inc, Dec, Quit

final case class Model(count: Int) derives CanEqual

object Counter extends IOTuiApp[Model, Msg]:

  val keys: Keymap[Msg] = Keymap(
    Bind('+', "increment", Msg.Inc),
    Bind('-', "decrement", Msg.Dec),
    Bind('q', "quit", Msg.Quit)
  )

  def init: Next[Model, Msg] = Model(0)

  def update(m: Model, msg: Msg): Next[Model, Msg] = msg match
    case Msg.Inc  => m.copy(count = m.count + 1)
    case Msg.Dec  => m.copy(count = m.count - 1)
    case Msg.Quit => m.withCmd(Cmd.quit)

  def view(m: Model): View[Msg] =
    View.column(
      View.text(Line("Count: ", m.count.toString.bold)).centered,
      View.help(keys).length(1)
    ).titled("Counter")

  override def subscriptions(m: Model): Sub[Msg] = Sub.keymap(keys)
```

```scala
package examples.catsdashboard

import cats.effect.IO
import fs2.Stream
import scala.concurrent.duration.*
import tui4s.cats.*

enum Msg derives CanEqual:
  case PrevTab, NextTab, TogglePause, Quit
  case Sample(value: Long)
  case Log(line: String)

final case class Model(tab: Int, paused: Boolean, samples: Vector[Long], logs: Vector[String])
    derives CanEqual:
  def latest: Long = samples.lastOption.getOrElse(0L)

object Dashboard extends IOTuiApp[Model, Msg]:

  val tabs = Vector("Overview", "Logs")

  val keys = Keymap(
    Bind(Key.Left, "prev tab", Msg.PrevTab),
    Bind(Key.Right, "next tab", Msg.NextTab),
    Bind('p', "pause sampling", Msg.TogglePause),
    Bind('q', "quit", Msg.Quit)
  )

  val sample: IO[Long] = IO(scala.util.Random.between(0L, 100L))

  val logLines: Stream[IO, String] =
    Stream.awakeEvery[IO](700.millis).zipWithIndex
      .evalMap((_, i) => IO.realTimeInstant.map(now => s"$now  event #$i"))

  def init: Next[Model, Msg] = Model(tab = 0, paused = false, samples = Vector.empty, logs = Vector.empty)

  def update(m: Model, msg: Msg): Next[Model, Msg] = msg match
    case Msg.PrevTab     => m.copy(tab = (m.tab + tabs.size - 1) % tabs.size)
    case Msg.NextTab     => m.copy(tab = (m.tab + 1) % tabs.size)
    case Msg.TogglePause => m.copy(paused = !m.paused)
    case Msg.Sample(v)   => m.copy(samples = (m.samples :+ v).takeRight(200))
    case Msg.Log(line)   => m.copy(logs = (m.logs :+ line).takeRight(1000))
    case Msg.Quit        => m.withCmd(Cmd.quit)

  override def subscriptions(m: Model): Sub[Msg] =
    Sub.batch(
      Sub.keymap(keys),
      if m.paused then Sub.none else Sub.poll("load", 250.millis)(sample)(Msg.Sample(_)),
      Sub.stream("logs")(logLines)(Msg.Log(_))
    )

  def view(m: Model): View[Msg] =
    View.column(
      View.tabs(tabs, selected = m.tab).bordered.length(3),
      if m.tab == 0 then overview(m) else View.log(m.logs).titled(s"Logs (${m.logs.size})"),
      View.help(keys).length(1)
    )

  private def overview(m: Model): View[Msg] =
    View.column(
      View.gauge(m.latest / 100.0, label = s"${m.latest}%")
        .titled(if m.paused then "Load (paused)" else "Load")
        .length(3),
      View.sparkline(m.samples, max = Some(100L)).titled("Last 50 s")
    )
end Dashboard
```

Note the name `Stream` here is `fs2.Stream`: `tui4s.cats.*` exports no `Stream`, so there is no clash.
(fs2/CE names `Stream.awakeEvery`, `zipWithIndex`, `IO.realTimeInstant` are from memory: UNVERIFIED here.)

Polymorphic `F[_]` (tagless final) is not in v1 of the facade: `Cmd` would need to know `F`, and a
`Local[Dispatcher[?]]` loses the type. Possible later shape: `Tui.runF[F[_]: Async]` plus a
`CmdF[F]` constructor object obtained from it (open question 8.4).

---

## 5. Runtime model

### 5.1 Fibers, threads and the inbox

```
                         +-------------------------------------------------------------+
  tui4s-input thread     |  Channel[Inbox[Msg]]  (bounded, inboxCapacity = 4096, MPSC)   |
  poll(50 ms) -> read() --putBatch-->  Input(InputEvent)                                  |
  (daemon, platform)     |                                                              |
  Sub fibers (Kyo) ------put/offer-->  SubValue(id, value)      +---------------------+ |
  Cmd fibers (Kyo) ------put------->  Message(msg) | Failed(e) |  update-render loop | |
  TuiHandle.send ------->put------->  Message(msg) | Stop      |  (ONE Kyo fiber)    |<-+
                         +-------------------------------------+---------------------+
                                                                 owns: Model, Terminal,
                                                                 Buffers, widget states,
                                                                 routing table, sub table
```

```scala
private[tui4s] enum Inbox[+Msg]:
  case Input(event: InputEvent)
  case Message(msg: Msg)
  case SubValue(id: SubId, value: Any)       // mapped with the CURRENT toMsg of that id (2.4)
  case TaskEnded(key: String, token: Long)   // bookkeeping for Cmd.keyed
  case Failed(error: Throwable)              // a Cmd.perform / Sub fiber panicked
  case Stop                                  // TuiHandle.stop
```

- **Input** runs on a dedicated daemon platform thread, not on a Kyo worker. It loops
  `if running then poll(50 ms)`, then `read()` every event that is already queued (up to 512, `poll(0)`
  between reads), converts them (Release dropped), and hands the batch over with
  `Channel.Unsafe.putBatchFiber(batch).onComplete(latch.countDown)` + `latch.await()` (backpressure). This
  is the probe's `InputMode.Thread` (VERIFIED shape, `loop.scala`): it keeps the blocking JNI call off Kyo
  workers (no blocking-monitor compensation, no pinned carrier) and had the lowest idle CPU of the three
  input modes in the probe (17.8% vs 23.3% for a blocking poll on a Kyo worker, default scheduler flags,
  noisy machine). Its cost is shutdown: release sets `running = false` and joins for at most `4 x poll`
  (probe: ~60 ms at quit). `read()` is only called after `poll` returned true, so an interrupt is never
  stuck in JNI (kyo.md 4.5).
- **Resource subscriptions** and **commands** are Kyo fibers started with `Fiber.init` inside the app's
  `Scope` (structured: interrupted at the latest when the app ends). Ticks use `offerDiscard` (a tick is a
  wake-up; dropping one when the inbox is full is harmless); values and command results use `put`
  (backpressure, nothing lost).
- **The update-render loop** is the app's own fiber. It is the only code that calls `update`, `view`,
  `subscriptions`, and the only code that touches jatatui's `Terminal`, `Buffer`, `Frame` and widget
  states. A Kyo fiber can move between worker threads, but it runs one step at a time, and the scheduler's
  hand-off gives happens-before between steps, so the single-owner rule of jatatui (report 5.2) holds.

### 5.2 The loop

The loop keeps its state in a private, single-fiber mutable `Engine` object (model, routing table,
subscription table, `lastDrawn`, `lastDrawAt`, `forceRedraw`). The pure folding of a batch is one
`Sync.Unsafe.defer` block of plain Scala (Kyo's own advice for hot paths, kyo.md 4.3); effects (start/stop
fibers, draw) are Kyo steps between batches. Sketch (VERIFIED Kyo names; the shape is the probe's
`renderLoop`, with the frame budget moved into the wait):

```scala
private[tui4s] final class Engine[Model, Msg](
    program: Program[Model, Msg],
    inbox: Channel[Inbox[Msg]],
    screen: Screen,                 // Terminal + StateStore + hit regions; owned by this fiber
    subs: SubTable[Msg],            // id -> (fiber, current toMsg)
    tasks: TaskTable[Msg]           // key -> running keyed task
):
  // single-fiber state; only the loop fiber reads or writes it
  private var model: Model           = ...
  private var routing: Routing[Msg]  = ...
  private var lastDrawn: Any         = Engine.NeverDrawn
  private var lastDrawAt: Long       = 0L
  private var forceRedraw: Boolean   = true
  private var quit: Boolean          = false
  private val spawn                  = ArrayBuffer.empty[Cmd.Task[Msg]]

  def run(using Frame): Model < (Async & Abort[TuiError | Closed]) =
    Loop.foreach {
      next.map { first =>
        inbox.drain.map { rest =>
          Sync.Unsafe.defer(foldBatch(first, rest))           // update* + routing; plain Scala
            .andThen(if quit then Loop.done(model) else afterBatch.andThen(Loop.continue))
        }
      }
    }

  /** Park until a message arrives; when a frame is due, park at most until its deadline. */
  private def next(using Frame): Maybe[Inbox[Msg]] < (Async & Abort[Closed]) =
    if !dirty then inbox.take.map(Present(_))
    else
      val wait = lastDrawAt + budgetNanos - java.lang.System.nanoTime()
      if wait <= 0 then Absent
      else Abort.recover[Timeout](_ => Absent)(Async.timeout(Duration.fromNanos(wait))(inbox.take.map(Present(_))))

  private def afterBatch(using Frame): Unit < (Async & Abort[TuiError]) =
    subs.reconcile(program.subscriptions(model))              // start new ids, interrupt removed ids
      .andThen(tasks.start(spawn))                            // Fiber.init per Task; keyed: interrupt old
      .andThen(if dirty && frameDue then screen.draw(program.view(model)).andThen(markDrawn) else Kyo.unit)
```

`foldBatch` applies each `Inbox` item in order:

1. `Input(Pressed(Ctrl('c')))` with `ctrlCQuits` -> `quit = true`, stop.
2. `Input(Resized(...))` -> `forceRedraw = true`, then route like any other event.
3. `Input(e)` -> the **current** routing table (first match wins) -> `Maybe[Msg]`; no match -> ignored.
4. `SubValue(id, v)` -> the current `toMsg` of `id`, or dropped if `id` is gone.
5. `Message(msg)` -> `step(msg)`.
6. `Failed(e)` -> abort the loop with `TuiError.AppFailed(e)`.

`step(msg)`: `val next = program.update(model, msg)`; split it (`isInstanceOf[Next.WithCmd]`); interpret
the `Cmd` tree: `None` nothing; `Batch` each; `Send(m)` appended to a local FIFO that `foldBatch` drains
before it returns (bounded by `maxSendsPerBatch`, so `Send` loops fail loudly instead of spinning);
`Quit` sets `quit`; `Cancel(k)` and `Task` go to `spawn`. If the new model is not `eq` to the old one, the
routing table is rebuilt from `program.subscriptions(newModel)` **immediately**, so that the next event in
the same batch (for example the key after a Tab that moved focus) is routed by the new focus.

### 5.3 When a frame is drawn

- `dirty = forceRedraw || !(model eq lastDrawn) && !model.equals(lastDrawn)`, evaluated once per batch
  (not per message). Case-class `equals` starts with `eq` and models built with `copy` share unchanged
  fields, so the comparison is mostly reference checks.
- **Coalescing**: a batch is everything in the inbox when the loop wakes (`take` + `drain`). 10,000 queued
  key events became 3 frames in the probe (VERIFIED, `loop2.log`).
- **Frame budget** (`frameBudget`, 16 ms): the first change after idle is drawn at once (no added
  latency). During a burst (held key, paste, a fast stream), the loop waits for messages *until the frame
  deadline* (`Async.timeout` on `take`) and draws when the deadline passes, so frames are capped at ~60/s
  and each frame shows everything received so far.
- **Second check, on the view**: when the model changed, the loop builds `view(model)` and compares it
  with the last drawn view (`View` nodes are case classes; `eq` first). Equal view and no resize: no
  render, no `Terminal.draw`. This catches "the model changed but nothing visible did" (a log line while
  the Logs tab is hidden, 3.3). Cost: one view build plus one structural `equals` (7.3).
- **Never drawn**: an unbound key (routing finds nothing), an update that returns the same instance, an
  update that returns an equal model, a tick whose message does not change the model, a change that
  produces an equal view. Each costs about one `update` call and one or two `equals`.
- **What a draw is**: `program.view(model)` (pure, builds case classes), then one
  `terminal.draw(frame => renderer.render(view, frame))` inside one `Sync.Unsafe.defer`. jatatui's
  `Terminal.draw` autoresizes, calls the callback, diffs the buffers, sends one JNI `enqueue` with the
  changed cells, shows or hides the cursor, swaps buffers and flushes (report 2.8: 4 JNI calls per frame).

### 5.4 Who owns the mutable jatatui objects

| Object | Owner | Lifetime |
| --- | --- | --- |
| `CrosstermJni` | input thread: `poll`/`read`; loop fiber: `enqueue`/`execute`/`flush` (via the backend) | app |
| `Terminal[CrosstermBackend]`, its two `Buffer`s, `Frame` | `Screen`, loop fiber only; `Frame` never escapes the draw callback | app |
| `ListState`, `TableState`, `ScrollbarState` | `StateStore` in `Screen`, keyed by `.id` or tree path; loop fiber only | while the node is drawn each frame; swept otherwise |
| `Rect[]` from `Layout.split` (jatatui's shared cache entry) | read-only inside `render`; never stored, never written | one render |
| hit regions for `onClick` | `Screen`, rebuilt each frame in paint order | until the next frame |
| `Model`, routing and subscription tables | `Engine`, loop fiber only | app |

User code receives none of these. The escape hatches (`View.widget`, `View.stateful`, `View.draw`) run
inside `render` on the loop fiber, so even they cannot touch a buffer from another fiber.

Mouse clicks are hit-tested against the regions of the **last drawn** frame, which is what the user saw;
the topmost region (last painted, so popups in `View.layers` win) gets the click (report 7.2 idea).

### 5.5 Resize

crossterm delivers `Event.Resize(columns, rows)`. The loop sets `forceRedraw` (the model did not change,
but the screen must be redrawn) and routes the event to `Sub.resize` if the app subscribed. On the next
draw, `Terminal.draw` calls `autoresize()`, which queries the backend size and resizes and clears the
buffers (report 2.8). Many resize events in a burst (dragging a window corner) coalesce like any other
burst: one frame per budget.

### 5.6 Errors

| Source | What happens |
| --- | --- |
| `update`, `view`, `subscriptions` throw | panic in the loop fiber -> `TuiError.AppFailed(cause)` -> restore -> `Tui.run` fails |
| `Cmd.perform` / `Sub.poll` / `Sub.stream` task panics or aborts | the wrapper (`Abort.run` around the task) sends `Failed(e)` -> loop aborts -> as above |
| `Cmd.attempt` task fails | becomes a message: `toMsg(Result.Failure(e))` (Kyo), `Left(e)` (ZIO, Cats). The app decides |
| `Cmd.exec` task fails | written to the tui4s log file, if one is configured; never to stdout/stderr |
| jatatui `IOException`, crossterm `RuntimeException("IO error")` | `Abort.catching` -> `TuiError.Io` -> restore -> fail |
| JEP 472: native access not enabled | checked before raw mode: `TuiError.NativeAccess("start the JVM with --enable-native-access=ALL-UNNAMED")` (UNVERIFIED check API) |
| stdin/stdout not a TTY | `TuiError.NotATerminal` before raw mode |

Nothing is printed while the TUI is up (it would corrupt the screen). `Tui.main` prints a failure only
after the terminal is restored.

### 5.7 Interruption and terminal restore (F8)

```scala
def run[Model, Msg](program: Program[Model, Msg])(using Frame): Model < (Async & Abort[TuiError]) =
  Sync.acquireReleaseWith(Session.open(program.options))(_.restore()) { session =>   // synchronous release
    Scope.run {                                                                       // owns fibers + inbox
      Channel.init[Inbox[Msg]](program.options.inboxCapacity, Access.MultiProducerSingleConsumer)
        .map(inbox => session.startInput(inbox).andThen(Engine.start(program, session, inbox)))
    }
  }
```

- `Session.open` (one `Sync.defer`): load `CrosstermJni` first (its JEP 472 warning is printed before raw
  mode, report 1.3), check the TTY, `enableRawMode()`, `execute(EnterAlternateScreen, Hide, [mouse],
  [bracketed paste], [focus])`, `Terminal.create(new CrosstermBackend(jni))`. If a step fails, the steps
  already done are undone inside `open` (the release only runs for a successful acquire).
- `session.restore()`: idempotent (`AtomicBoolean`), every step in its own `try`, in reverse order:
  stop the input thread flag, `execute(Disable..., LeaveAlternateScreen, Show)`, `disableRawMode()`.
- **Why `Sync.acquireReleaseWith` and not `Scope.acquireRelease`**: on interruption, `Scope` finalizers
  run on a detached fiber that nobody awaits, but a fiber's result follows its synchronous releases
  (`Sync.ensure`, `Sync.acquireReleaseWith`) (F8, VERIFIED in RC7 source and by test). So when
  `interruptAwait` returns, or the ZIO fiber reports interrupted (`ZIOs.run` waits, F11), or the CE
  `cancel` returns (`runF` cancel-and-await, F8), the terminal is already restored. The probe showed
  exactly this ("terminal restored before `interruptAwait` returns").
- On a normal quit the order is: loop returns -> `Scope.run` closes (interrupts sub/cmd fibers, joins the
  input thread) -> restore. On interruption the Scope drain is detached, so restore can run while the
  input thread is still inside `poll`; the thread checks `running` before each `read()`, so at most one
  already-polled event is read and dropped after restore (a small race, noted in 8.2).
- `Tui.main` also installs a guarded JVM shutdown hook (SIGTERM, `System.exit` elsewhere): it interrupts
  the app fiber and waits up to 1 s for the restore latch. In raw mode Ctrl-C is a key, not SIGINT.

### 5.8 Idle CPU

What runs when nothing happens: the loop fiber is parked in `inbox.take` (no CPU); the input thread wakes
20 times per second inside a JNI `poll` (negligible); timers exist only for subscriptions the model asks
for (the paused Dashboard on the Logs tab has one 700 ms stream). The rest is the Kyo scheduler's own
idle cost, measured at **~13% of one core with defaults** (F13) and **4-8% with tuned flags**, floor
~2-2.5% (the hard-coded 1 ms clock thread).

`SchedulerDefaults.applyIfUnset()` (called by `Tui.main`, by the ZIO `Tui.run` and by the Cats `Tui.run`
before their first Kyo call, and only for properties the user did not set):

```scala
private val defaults = Seq(
  "kyo.scheduler.cycleIntervalNs"                     -> "10000000",   // 100 us -> 10 ms
  "kyo.scheduler.blockingMonitorIntervalNs"           -> "20000000",   // 2 ms   -> 20 ms
  "kyo.scheduler.regulator.concurrencyCollectIntervalMs" -> "100"      // 10 ms  -> 100 ms
)
def applyIfUnset(): Unit =
  defaults.foreach((k, v) => if java.lang.System.getProperty(k) == null then java.lang.System.setProperty(k, v))
```

These are `StaticFlag`s read once when the scheduler class initializes (F13), so this only works if no
Kyo scheduler class was loaded earlier in the JVM. `TuiApp` therefore does not extend `KyoApp`.
UNVERIFIED: that nothing on the `Tui.main` path (or in a ZIO/CE app before `Tui.run`) loads the scheduler
earlier; the fallback is to document the three `-D` flags (and `--enable-native-access=ALL-UNNAMED`,
`--sun-misc-unsafe-memory-access=allow`, F4) in the sbt template's `javaOptions`. I did not set
`coreWorkers=2`: it would cap CPU-heavy commands. UNVERIFIED: the effect of a 10 ms cycle on preemption
latency of busy fibers (irrelevant for a TUI, worth a probe).

---

## 6. Testing, and why the design is easy for an AI agent

### 6.1 Four levels of tests, none needs a terminal

| Level | What | Needs Kyo? | Needs runtime? |
| --- | --- | --- | --- |
| 1. update | `Next(model, cmd) = app.update(m, msg)` and `assertEquals` | no | no |
| 2. routing | `route(app.subscriptions(m), key)`: which message does a key give in this state | no | no |
| 3. view | `Screen.render(app.view(m), w, h)`: text snapshot from jatatui `TestBackend` | no | no |
| 4. scripted run | `TestTui.run(app, w, h)(tui => ...)`: the real loop on `TestBackend`, virtual time | yes (Kyo); ZIO/IO in the facades | yes, headless |

Levels 1-3 are plain function calls on values. They are the same for Kyo, ZIO and Cats users, because
they only touch the shared surface.

```scala
package tui4s.testkit

/** Pure: renders a view into a jatatui TestBackend (Terminal.create(TestBackend(w, h)).draw(...)). */
final case class Screen(lines: Vector[String], cursor: Option[(Int, Int)]):
  def text: String                         // lines joined with '\n'
  def contains(s: String): Boolean
  def find(s: String): Option[(Int, Int)]
  def styleAt(x: Int, y: Int): Style       // from the TestBackend cell (fg, bg, modifiers)
object Screen:
  def render[Msg](view: View[Msg], width: Int, height: Int): Screen

/** Which message would this input give, with these subscriptions? (first match wins, like the runtime) */
def route[Msg](sub: Sub[Msg], key: Key): Option[Msg]
def route[Msg](sub: Sub[Msg], event: InputEvent): Option[Msg]

/** The real Engine on TestTerminalPort (TestBackend) + a scripted InputPort, inside Clock.withTimeControl. */
final class TestTui[Model, Msg]:
  def press(keys: Key*)(using Frame): Unit < Async        // enqueue Input events, then settle
  def typeText(s: String)(using Frame): Unit < Async      // one Key.Char per grapheme
  def paste(s: String)(using Frame): Unit < Async
  def click(column: Int, row: Int)(using Frame): Unit < Async
  def resize(columns: Int, rows: Int)(using Frame): Unit < Async
  def send(msg: Msg)(using Frame): Unit < Async
  def advance(d: Duration)(using Frame): Unit < Async     // TimeControl.advance; timers fire; then settle
  def model(using Frame): Model < Sync
  def screen(using Frame): Screen < Sync                  // the last DRAWN frame
  def frames(using Frame): Int < Sync                     // frames drawn so far (performance assertions)
  def quit(using Frame): Boolean < Sync                   // did the app ask to quit?
object TestTui:
  def run[Model, Msg, A](program: Program[Model, Msg], width: Int = 80, height: Int = 24)(
      test: TestTui[Model, Msg] => A < (Async & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable])
  def unsafeRun[A](v: A < (Async & Abort[Throwable]))(using Frame): A   // KyoApp.Unsafe.runAndBlock for munit
```

`settle` = wait until the inbox is empty, no command fiber is running, and the loop is parked (an
internal quiescence counter; subscription fibers that sleep on virtual time do not count). `TestTui`
sets `frameBudget = 0`, so every batch that changes the model draws a frame: deterministic frame counts.
Virtual time works because `Async.sleep` is `Clock.sleep(d).map(_.get)` (VERIFIED, `Async.scala`), and
the clock is a `Local` that `Clock.withTimeControl` replaces (VERIFIED: `withTimeControl`,
`TimeControl.advance`). `Clock.now` then starts at `Instant.Epoch`, so log lines in snapshots are stable.

### 6.2 Example tests (munit)

```scala
package examples.counter

import tui4s.*
import tui4s.testkit.*

class CounterSuite extends munit.FunSuite:

  test("+ and - change the count; nothing else happens") {
    val Next(m1, cmd) = Counter.update(Model(0), Msg.Inc)
    assertEquals(m1, Model(1))
    assertEquals(cmd, Cmd.none)
    assertEquals(Counter.update(m1, Msg.Dec), Model(0))     // no command: Next IS the model
  }

  test("q asks to quit") {
    val Next(_, cmd) = Counter.update(Model(3), Msg.Quit)
    assertEquals(cmd, Cmd.quit)
  }

  test("every bound key is documented and routed") {
    val subs = Counter.subscriptions(Model(0))
    assertEquals(route(subs, Key.Char('+')), Some(Msg.Inc))
    assertEquals(route(subs, Key.Char('x')), None)
    assert(Counter.keys.binds.forall(_.help.nonEmpty))
  }

  test("view snapshot") {
    val screen = Screen.render(Counter.view(Model(42)), width = 40, height = 5)
    assertEquals(
      screen.text,
      """┌Counter───────────────────────────────┐
        |│              Count: 42               │
        |│                                      │
        |│+ increment • - decrement • q quit    │
        |└──────────────────────────────────────┘""".stripMargin
    )
    assert(screen.styleAt(22, 1).isBold)                    // the '4' of "42": col 1 + 14 + 7
  }
end CounterSuite
```

(The expected text follows ratatui's layout rules: title right after the corner, centered text at
`(38 - 9) / 2 = 14`. UNVERIFIED until the validator renders it; `isBold` is a small `Style` query
extension, `hasModifier(Modifier.BOLD)` underneath, VERIFIED in report 2.5.)

```scala
package examples.dashboard

import kyo.*
import tui4s.*
import tui4s.testkit.*

class DashboardSuite extends munit.FunSuite:

  test("a sample every 250 ms; p pauses sampling and stops the fiber") {
    TestTui.unsafeRun {
      TestTui.run(Dashboard, width = 60, height = 20) { tui =>
        tui.advance(1.second)
          .andThen(tui.model).map(m => assertEquals(m.samples.size, 4))
          .andThen(tui.press(Key.Char('p')))
          .andThen(tui.advance(1.second))
          .andThen(tui.model).map(m => assertEquals(m.samples.size, 4))
          .andThen(tui.screen).map(s => assert(s.contains("Load (paused)")))
      }
    }
  }

  test("an unbound key draws no frame") {
    TestTui.unsafeRun {
      TestTui.run(Dashboard) { tui =>
        tui.frames.map { before =>
          tui.press(Key.Char('z')).andThen(tui.frames).map(after => assertEquals(after, before))
        }
      }
    }
  }
end DashboardSuite
```

The Todo app's focus rule is a level-2 test, with no runtime and no file system:

```scala
test("q types into the input, but quits from the list") {
  val Next(start, _) = Todo.init                        // the load command is not run
  assertEquals(route(Todo.subscriptions(start), Key.Char('q')), Some(Msg.Edit(Key.Char('q'))))
  val inList = start.copy(focus = Focus.Items)
  assertEquals(route(Todo.subscriptions(inList), Key.Char('q')), Some(Msg.Quit))
}
```

ZIO and Cats users get the same `TestTui` with their effect type (`tui4s.zio.testkit.TestTui.run(app)(tui
=> ...): ZIO[...]`, the IO equivalent in tui4s-cats); virtual time there is the core's Kyo clock, so
`advance` drives `Sub.every` but not `ZIO.sleep` inside a ZStream (they would use `TestClock` /
`TestControl` for that: an honest gap, 8.2).

### 6.3 What makes it easy for an agent to write and to check

1. **The compiler lists the work.** `object X extends TuiApp[Model, Msg]` without members fails with the
   list of missing members (`init`, `update`, `view`). A `Msg` enum plus a `match` in `update` gets an
   exhaustivity warning for every unhandled message (make it an error with `-Werror`).
2. **Small, local, pure pieces.** An agent can write and test `update` without knowing anything about
   terminals, fibers or jatatui; then `view` with `Screen.render`; then wire `subscriptions`. Each step has
   its own fast test. No hook rules, no call-order rules, no global registries.
3. **Everything is data with a readable `toString`.** Messages, models, views (`final case class` nodes),
   keymaps and command labels print well, so a failed assertion explains itself, and an agent can print
   `app.view(model)` as a tree to see what it built.
4. **Text snapshots an agent can read.** `Screen.text` is the screen as plain lines, from jatatui's own
   `TestBackend` (VERIFIED published, copies cells). Works in CI and in sandboxes without a TTY.
5. **One shape for all three effect systems.** An agent that learned the Counter learns the ZIO and Cats
   versions for free (the files differ in one import and one class name).
6. **A trace for real-terminal sessions.** `TuiOptions.traceFile: Option[Path]` (2.8) writes one
   line per message, command label, subscription start/stop and frame (duration, changed cells). An agent
   that cannot see the user's terminal can read what happened, and replay the recorded messages into
   `update` to reproduce a bug deterministically (Elm's "time travel", here from a file).
7. **Keys are checkable.** "Is every key documented?", "Does `q` quit in this state?" are one-line tests.

---

## 7. Performance analysis

Measured baselines (VERIFIED, from the reports; 2014 i7, JDK 25, loaded machine): a typical 120x40 frame
(Block + wrapped Paragraph + 100-item List) costs **~2.1 ms and ~1.7 MiB** allocated, of which widgets
~1.2 ms and `Terminal.draw`'s fixed diff + reset ~0.6 ms; an unchanged frame costs the same and writes 0
cells; `Layout.split` cache hit ~2.7 us; Kyo event loop probe **~6.7 us/event** with batched input and
coalesced frames, ~14 us/event when "drawing" (a stand-in) after every event; `Channel` putBatch + drain
~0.5-0.8 us per element.

### 7.1 Work and allocations per input event (key that changes the model)

| Step | Work | Allocations |
| --- | --- | --- |
| input thread | JNI `poll` + `read`, crossterm builds `Event.Key(KeyEvent(...))` | ~4-5 Java records (crossterm) |
| convert | type-pattern match -> `Key` | 0-1 (`Key.Char(c)`; named keys are singletons) |
| hand-over | `Inbox.Input(e)`, batch `Chunk`, one `putBatchFiber` per **batch** | 1 per event + ~2 per batch |
| loop wake-up | `take` (one park/unpark per batch) + `drain` | 1 `Chunk` per batch |
| routing | walk the routing table; `Keymap.lookup` uses a lazily built `HashMap[Key, Msg]` cached in the (immutable, usually `val`) keymap | 0 |
| update | user code: usually one `copy` | 1+ (user) |
| `Next` split | one `isInstanceOf` | 0 (plain model) |
| subscriptions | only when the model changed: builds a few `Sub` nodes; routing rebuilt at once, resource diff at batch end (`HashMap` by id, a handful of entries) | a few small objects |
| dirty check | once per batch: `eq`, then `equals` (shared fields are `eq`) | 0 |

So the runtime's own cost per event is a few hundred nanoseconds of plain Scala plus a share of one
wake-up per batch; the probe's 6.7 us/event is an upper bound (it also updated a `Var` per event and
counted with atomics). In a burst, N events cost N x (routing + update) but **one** frame per 16 ms.

### 7.2 Work and allocations per frame

| Step | Work | Allocations |
| --- | --- | --- |
| `view(model)` | build `final case class` nodes; data is passed by reference (`m.samples`, `m.logs` are not copied) | ~1 small object per node (tens of nodes: a few KiB) |
| view equality (7.3) | structural `equals` against the last drawn view | 0 |
| layout | one `Layout.vertical/horizontal(...).split(area)` per container: cache hit ~2.7 us | ~0.3 KiB per container |
| widget build | jatatui widget values from node data (`List.of(items)`, `Sparkline.withDataLongs(...)`) | O(items) wrapper objects |
| render | jatatui widgets into the back buffer | dominant: ~1.2 ms, ~1.7 MiB at 120x40 (styling, text segmentation) |
| `Terminal.draw` | buffer diff (0 alloc), one JNI `enqueue` of changed cells, flush, swap | ~0.6 ms fixed at 120x40 |

Estimates for the samples (UNVERIFIED, scaled from the measurements): Counter ~0.3-0.5 ms per frame;
Todo with 100 items ~1-1.5 ms; Dashboard overview ~1 ms. Dashboard steady state: 4 samples/s -> 4
frames/s (log lines on the hidden tab draw nothing, 7.3) -> ~4-6 ms CPU per second, well under 1% of a
core for drawing, plus the scheduler's idle cost (5.8), which is larger.

### 7.3 How unchanged frames are avoided (four filters)

1. **No message, no work.** The loop is parked in `take`; there is no render tick. Timers exist only for
   subscriptions the current model asks for.
2. **Model filter.** Same instance or an equal model after a batch: no view, no render.
3. **View filter.** Model changed but the new view equals the last drawn view: no render, no
   `Terminal.draw` (saves the ~0.6 ms fixed cost too, which a buffer diff never can). `View` equality is
   structural; nodes holding big sequences compare them by reference first, so the usual cost is small.
   The worst case is a large `Seq` rebuilt every update with equal content (O(n) compare per frame,
   still microseconds for thousands of elements).
4. **Coalescing and the frame budget.** All messages queued at wake-up are folded before one frame;
   during a burst, frames are capped at one per `frameBudget` and the last frame always shows the
   latest model (10,000 queued keys -> 3 frames in the probe).

Resize bypasses filters 2-3 (`forceRedraw`).

### 7.4 The cost of the abstraction itself

- **No virtual DOM diff and no signal graph.** The only diff is jatatui's cell diff, which every design
  pays. TEA adds: one view build per changed model (microseconds), one or two `equals`, a subscription
  diff by id (a handful of `HashMap` operations per changed model), one fiber per command.
- **Where TEA costs more than a fine-grained reactive design:** any visible change re-renders the whole
  screen. A big terminal (300x100 = 30,000 cells) with a ticking clock in one corner pays a full render
  (~8-10 ms, UNVERIFIED extrapolation, ~250 ns/cell for widgets) per tick, where a signal-per-region
  design would repaint one region. Mitigation, designed in and planned for phase 2:
  `View.lazily(dep)(f)` caches the cells a subtree rendered for `(dep, area)` and copies them into the
  back buffer when both are equal, skipping widget code, text segmentation and the `setStyle` allocations
  (the copy is a field copy per cell; UNVERIFIED ~20-40 ns/cell). The ~0.6 ms diff + reset stays.
- **Big lists.** `View.list(items)` converts `items` into jatatui `ListItem`s every frame (O(n), even if
  only 30 rows are visible). Mitigation (internal, no API change): the renderer keeps jatatui's offset
  in the `StateStore` and passes only the visible window (plus margin) to jatatui, adjusting the
  selection index. UNVERIFIED that jatatui's offset logic behaves identically on a slice; needs a probe.
- **Allocation profile.** At 60 fps a 120x40 screen would allocate ~100 MiB/s inside jatatui (report 5.3);
  TEA only reaches that during sustained bursts, and the frame budget is configurable (33 ms -> half).
- **Per-command fibers.** `Cmd.perform` costs one Kyo fiber (cheap; sub-microsecond creation, UNVERIFIED)
  plus closures. Thousands of concurrent commands are fine; a `Cmd.send` loop is bounded by
  `maxSendsPerBatch`.

---

## 8. Weaknesses, risks and open questions

### 8.1 Weaknesses of the TEA approach itself

1. **Composition boilerplate.** Nested components need wrapper messages (`Msg.Editor(e: EditorMsg)`),
   `cmd.map(Msg.Editor(_))`, `sub.map(...)` and a delegating `case` in `update`. This is Elm's best-known
   cost; big apps get big `Msg` enums and long `update` functions. `TextField` shows the pattern, but there
   is no `Component` abstraction yet (open question 8.4.6).
2. **Ceremony for small async work.** "Load, then show" needs a result message and a `case` for it. A
   hooks design (`useEffect`) is shorter for one-off effects.
3. **All state is global.** Every cursor, every toggle lives in the model. The single exception, scroll
   offsets of lists and tables, lives in the runtime's `StateStore`. That is hidden state: the model
   cannot say "scroll to top" except through the selection. Making the model own offsets would need a
   feedback message from `render` (ugly) or tui4s computing offsets itself (duplicates jatatui logic).
4. **Whole-screen re-render** on any visible change (7.4). Fine at 120x40 (~2 ms); costly on very big
   terminals with a small, fast-changing part, until `View.lazily` (phase 2) exists.
5. **`Next` is a union and `withCmd` is an extension on every type.** The runtime separates the two arms
   with a type test (a `Model` must never be a `Next.WithCmd`); type errors that involve `Next` print a
   union, which is less friendly; and `anything.withCmd(...)` compiles. The Kyo-native alternative
   (`update: ... => Model < Emit[Cmd[Msg]]`) avoids the union but costs an `Emit` handler per message and
   is not shareable with the ZIO/Cats facades.
6. **The Kyo-free shared surface breaks Kyo house style** (`Option`, `Seq`, `java.time.Duration` instead of
   `Maybe`, `Chunk`, `kyo.Duration`). Kyo users pay a small style cost so that one `Program` serves all
   three modules.
7. **Commands have a fixed effect row** (`Async & Abort[E]`). No `Env`, `Var`, `Emit` or user effects:
   dependencies come in through the app's constructor. A Kyo user who structures everything around
   `Env`/`Layer` must handle those effects inside each command (or close over services). A
   `Program[Model, Msg, S]` with an `S` handler would fix it at a large cost in type complexity.
8. **Subscription identity is the user's job.** Changing a stream's parameters without changing its id
   keeps the old stream (the classic Elm gotcha). The "current `toMsg`" rule removes stale closures, not
   stale streams. `Sub.every` derives its id from `(interval, msg)`, so two equal subscriptions merge
   (intended, but it can surprise).
9. **First match wins** for routing subscriptions (Elm delivers to all). I chose it because keymaps need
   precedence (focused before global), but it differs from Elm and must be documented.
10. **Quit interrupts in-flight commands** (the app's `Scope` closes). "Save, then quit" is two messages
   (Todo sample). A `Cmd.quitAfter(cmd)` could be added.

### 8.2 Runtime and integration risks

1. **Concurrent JNI use (UNVERIFIED).** The input thread calls `poll`/`read` while the loop fiber calls
   `enqueue`/`flush`. crossterm keeps a global event reader and a global stdout writer with separate locks
   (report 5.1 says reader contention is UNVERIFIED). jatatui-react does both on one thread. Fallback: the
   probe's `BlockingPoll` mode on a Kyo fiber, or a single "terminal thread" that does input and output.
   Needs a real-TTY probe; `Viewport.Inline` (`cursorPosition` writes a query and reads the reply) is the
   riskiest case.
2. **Scheduler flags by `System.setProperty`** only work if no Kyo scheduler class loaded before (5.8).
   In a ZIO or CE app that already used Kyo, or under some test runners, the defaults silently do nothing.
   The `-D` flags in the build template are the reliable path; idle CPU stays ~13% of a core otherwise.
3. **ZIO and Cats users pay Kyo's idle cost** (a second scheduler next to ZIO's/CE's pools), 4-13% of
   one core, for a runtime they did not choose. Bridging also adds a hop per command (small).
4. **Virtual time in facade tests.** `TestTui.advance` drives Kyo's clock. In tui4s-zio, effects run on
   the caller's ZIO runtime (AmbientZIOs), so ZIO's `TestClock` should drive `ZIO.sleep` inside commands
   and streams, and the facade's `Sub.every`/`poll` should then be built on `ZIO.sleep`, not
   `Async.sleep` (UNVERIFIED). For Cats, mixing `TestControl` with real Kyo threads is unclear (open).
5. **One input event can be read after restore** when the app is interrupted (5.7): the input thread may
   be between `poll` and `read`. It is dropped, but the next program in the same terminal loses one key.
6. **jatatui and Kyo are both pre-1.0** (jatatui 0.31, single maintainer; Kyo RC7). The opaque types and
   the `View` ADT insulate users from jatatui changes, except through the escape hatches
   (`View.widget`, `View.stateful`, `View.draw`), which expose jatatui types on purpose.
7. **Input fidelity**: crossterm truncates non-BMP characters in `KeyCode.Char` (report 4.4), so typed
   emoji arrive broken; only bracketed paste delivers them intact. `TextField` must be tui4s's own
   grapheme-aware code (more code to write and test).
8. **Package-name shadowing**: inside code that does `import tui4s.*`, the name `zio` means `tui4s.zio`
   (wildcard imports beat root packages). Facade users must import only `tui4s.zio.*`; library code uses
   `_root_.zio`. A rename (`tui4s.ziotui`?) would remove the trap at some cost in beauty.
9. **Model equality cost** on huge models that are rebuilt every update (for example a 1,000,000-line
   log in a `Vector` appended per message: O(n) `equals` per batch). Escape: an option to compare by
   reference only (draw whenever the instance changes), or keep huge buffers outside the compared fields.
10. **Bursty frame cost under fast streams**: 1,000 messages/s that each change visible state are capped
   at 60 frames/s, i.e. up to ~120 ms of rendering per second (~12% of a core) at 120x40.

### 8.3 Things a user may expect but this design does not have (yet)

- Printing above an inline UI (bubbletea's `tea.Println`): `Viewport.Inline` exists in jatatui with
  `insertBefore`; a `Cmd.printAbove(lines)` is easy to add, not designed in detail.
- Focus rings for forms (Tab order over many fields), mouse drag and per-region scroll handlers,
  animations (`Sub.frames(fps)` is easy, not designed).
- A "debug overlay" (frames/s, last messages) toggled by a key.

### 8.4 Open questions for the panel

1. Should `Program` also accept the Kyo-native form `update: (Model, Msg) => Model < Emit[Cmd[Msg]]`
   (an adapter for Kyo users who want to emit commands from helpers)? Two forms means more to learn.
2. Tagless-final `F[_]` in tui4s-cats: `Tui.runF[F[_]: Async]` with a `CmdF[F]` object obtained from the
   runner, or IO only?
3. Facade package names: `tui4s.zio` / `tui4s.cats` (natural, shadowing trap) or other names.
4. Scroll offsets: runtime-owned (current) or model-owned?
5. Input on a platform thread (current, lowest idle CPU in the probe, ~60 ms quit) or on a Kyo fiber
   (8 ms quit, a Kyo worker blocked in JNI 20x/s)? Depends on 8.2.1.
6. A small `Component[Model, Msg, Out]` helper (init/update/view/subscriptions plus `map`) for reusable
   widgets like `TextField`, a list with filtering, a confirm dialog?
7. Defaults: `ctrlCQuits = true`, `mouse = false`, `bracketedPaste = true`, `frameBudget = 16 ms`?

### 8.5 What the validator should compile first (cheap ways to falsify this design)

1. `type Next[+M, +Msg] = M | Next.WithCmd[M, Msg]` with `def init = Model(0)` overriding
   `def init: Next[Model, Msg]`, the `match` in `update` mixing `m.copy(...)` and `m.withCmd(...)`, and
   `val Next(m, cmd) = update(...)` (irrefutable tuple-returning `unapply`, UNVERIFIED in 3.9).
2. The top-level `withCmd` extension and the `Style` opaque type with top-level extensions next to
   `object Style` vals (clash check).
3. `Bind('+', "increment", Msg.Inc)` overload resolution against the case-class `apply`.
4. `export tui4s.view.*` from another package, and re-exporting top-level extensions in `tui4s.zio`.
5. `object Counter extends TuiApp[Model, Msg]` gets a static `main` forwarder from the inherited `main`.
6. `Sync.acquireReleaseWith(open)(_.restore())(use)` with `use` returning `Model < (Async & Abort[TuiError])`
   inside `Scope.run`, and the restore timing under `interruptAwait` (repeat the F8 test with this shape).
7. `Stream.range(0, Int.MaxValue, chunkSize = 1).map(effectful)` emits per element (Dashboard log source).
