# tui4s design candidate: "free" (Three pure functions, one runtime)

Author: designer "free" of the tui4s design panel, 2026-10-01.
Legend: **VERIFIED** = checked in the RC7 / jatatui 0.31 sources or in a probe log named in CONTEXT.md.
**UNVERIFIED** = not checked; the validator should compile it.

Outline:

1. Pitch and core idea
2. Concepts and public API of tui4s-core
3. The three sample apps (Kyo)
4. Counter and Dashboard for ZIO and Cats Effect users
5. Runtime model
6. Testing and agent-friendliness
7. Performance analysis
8. Weaknesses, risks and open questions

---

## 1. Pitch and core idea

### Pitch

A tui4s app is three pure functions (`init`, `view`, `update`) and one more pure function,
`sources(state)`, that says which background streams must run now. A view is an immutable tree whose
leaves are the jatatui widgets themselves. The Kyo runtime owns every mutable object and draws only when
the *view* changed, at most once per frame. The app logic has no effect types, so the same `Counter`
compiles under Kyo, ZIO and Cats Effect: only the import line and `main` change.

### The core idea, in one screen

```scala
import tui4s.*                    // ZIO users: import tui4s.zio.*   Cats users: import tui4s.cats.*

object Counter extends TuiApp[Int]:
  def init: Int = 0

  def view(n: Int): View =
    column(
      text("Count: ", n.toString.bold.yellow).centered.size(1),
      text("+ / - change it · q quits".dim).centered.size(1)
    ).flex(Flex.Center).bordered(" Counter ")

  def update(n: Int, event: Event): Next[Int] = event match
    case Key.Char('+') => n + 1
    case Key.Char('-') => n - 1
    case Key.Char('q') => Quit
    case _             => n
```

Five ideas make this work. Each one is justified against the panel weights
(DX 35%, performance 25%, ecosystem fit 25%, testability 15%).

| # | Idea | Why (weight) |
| --- | --- | --- |
| 1 | **Model-view-update with no message type.** `update` takes the terminal `Event` directly and returns `Next[S] = S \| Quit`. Async sources fold their values into the state with a plain function `(S, A) => S`, so an app needs no `Msg` ADT. | DX: the shortest TEA there is; the counter above is the whole app. Testability: `update` and `view` are pure functions. |
| 2 | **The app logic has no effect types.** `init`, `view`, `update` and `sources` return plain values. Effects live only inside sources (a Kyo `Stream`, a ZIO `ZStream`, an fs2 `Stream`) and inside the runtime. | Ecosystem fit: one `App[S]` trait serves Kyo, ZIO and Cats users; the facades are small. F1: user code never needs a Kyo for-comprehension, so it cannot overflow the compiler stack. |
| 3 | **`View` is `jatatui.core.widgets.Widget`.** Every jatatui widget is already a view; tui4s adds layout nodes (`column`, `row`, `layers`), decorators (`bordered`, `size`, `fill`) and short constructors for the common widgets. Nothing in jatatui is hidden behind a re-wrapped catalogue. | DX: a small surface; the whole jatatui catalogue and its docs stay usable. Perf: no conversion layer, widgets are built straight into the buffer. |
| 4 | **Sources are a function of state.** `sources(s)` returns the background work that must run for state `s`. The runtime starts the new ones, stops the ones that disappeared, and keeps the rest. A source is identified by its call site (Kyo `Frame`) plus an optional `.key(...)`. | DX: no commands, no subscriptions, no manual fiber bookkeeping: "pause" is `if paused then Nil`. Perf: a tick runs only while some state needs it, so an idle app does no work. |
| 5 | **Draw when the view changed, at most once per frame.** Level 1: the new state is the same object (`eq`), so nothing happens. Level 2: the new view equals the last drawn view (jatatui widgets have structural equality) and the size did not change, so no frame is drawn. Bursts of events are folded into one frame (frame cap `fps`). | Perf: an unchanged frame costs ~2 ms and ~1.7 MiB (jatatui.md 5.3); this design never pays it. |

What this candidate borrows, and from where:

- From **TEA / Elm / bubbletea**: one model, `view: S => View`, `update`. LLMs and humans know this shape.
- From **immediate-mode UI (Dear ImGui / egui)**: the view is rebuilt from scratch on each change, with no
  retained widget tree for the user to manage. Only the runtime keeps a little retained state: the jatatui
  `ListState`/`TableState` scroll offsets, keyed by tree position ("immediate-mode view, retained-mode
  widget state").
- From **React `useEffect` / Elm subscriptions**: background work declared as a function of state, diffed
  by key. There is no hook-order rule, because identity comes from the call site, not from call order.
- From **kyo-ui**: an immutable UI value with structural equality.

What this candidate refuses, and why:

- **Fine-grained signals.** jatatui repaints the whole buffer in each frame anyway (render + diff + reset,
  jatatui.md 5.3), so knowing *which* region changed saves almost nothing. A whole-view rebuild costs
  microseconds; the frame costs milliseconds. Signals would add a graph, subscriptions and lifetimes for
  no gain.
- **Components with local state (React, Flutter, the first take at 48a6dd9).** Local state needs identity
  rules (hook order, keys, element trees, `createState`), and these rules are the main source of bugs for
  humans and agents. Here, state is one value: you can print it, compare it and test it.

---

## 2. Concepts and public API of tui4s-core

### 2.1 The concepts (seven, and only seven)

| Concept | Type | One line |
| --- | --- | --- |
| App | `App[S]` | `init`, `view`, `update`, `sources`, `options`. Pure. Shared by Kyo, ZIO and Cats users. |
| State | your `S` | One immutable value (usually a case class). The runtime holds it; nobody else does. |
| Event | `Event` | `Key`, `Mouse`, `Resize`, `Paste`, `FocusGained`, `FocusLost`. A Scala ADT, not the crossterm records. |
| Next | `Next[S] = S \| Quit.type` | What `update` returns: the next state, or `Quit`. |
| View | `View = jatatui Widget` | An immutable description of one frame. jatatui widgets are views as they are. |
| Source | `Source[S]` | Background work (ticks, streams, tasks) that folds values into the state. Declared by `sources(state)`. |
| Runner | `Tui.run`, `TuiApp` | Starts the terminal, the input thread and the sources; runs the loop; restores the terminal; returns the last state. |

Packages of `tui4s-core` (artifact `io.github.yuri4n::tui4s-core`):

| Package | Content | Who uses it |
| --- | --- | --- |
| `tui4s` | `App`, `Next`, `Quit`, `Event` ADT, `View` + the view DSL (exported from `object tui4s.dsl`), `TextField`, `Source`, `Dispatch`, `Options`, `Viewport`, `Tui`, `TuiApp`, `TuiError` | every Kyo user: `import tui4s.*` |
| `tui4s.testkit` | `TuiTest` (headless harness on jatatui `TestBackend`) | tests, for Kyo, ZIO and Cats users |
| `tui4s.internal` | `Painter`, layout nodes, render loop, source supervisor, terminal resource, input thread, crossterm event mapping | not public API (`private[tui4s]` where possible; the facades are in package `tui4s.*`, so they can see it) |

Re-exported jatatui names (so that `import tui4s.*` is enough for most apps): `Style`, `Color`,
`Modifier`, `Line`, `Block`, `BorderType`, `Constraint`, `Flex`. **Not** `Span`: `kyo.Span` exists (kyo-data,
VERIFIED) and `import kyo.*` + `import tui4s.*` would make `Span` ambiguous. The String styling
extensions (2.4) cover nearly all uses of `Span`. How the Java classes are re-exported (an `export`
with a package prefix, or type aliases plus a forwarder object for the static members such as
`Color.YELLOW` and `Flex.Center`) is UNVERIFIED (8.4, item 11); the fallback is one
`import jatatui.core.style.{Color, Style}` line in user code.

### 2.2 App, Next, Quit, Options

```scala
package tui4s

/** A terminal app: three pure functions, plus the background work that a state needs. */
trait App[S]:
  /** The first state. */
  def init: S
  /** The screen for a state. Must be a pure function of `state` (it is called once per state change). */
  def view(state: S): View
  /** The next state after an event, or `Quit`. Return `state` itself (same object) when nothing changed. */
  def update(state: S, event: Event): Next[S]
  /** The background sources that must run while the app is in `state`. Diffed by key after each change. */
  def sources(state: S): Seq[Source[S]] = Nil
  /** Terminal and loop options. */
  def options: Options = Options()

object App:
  /** An app as a value: for apps built from runtime data, and for tests. */
  def apply[S](
      init: S,
      view: S => View,
      update: (S, Event) => Next[S],
      sources: S => Seq[Source[S]] = (_: S) => Nil,
      options: Options = Options()
  ): App[S]

case object Quit
type Next[+S] = S | Quit.type

final case class Options(
    fps: Int = 60,                         // frame cap: at most one frame per 1/fps
    mouse: Boolean = false,                // EnableMouseCapture
    paste: Boolean = true,                 // EnableBracketedPaste: pasted text arrives as one Paste event
    ctrlCQuits: Boolean = true,            // Ctrl-C quits before `update` sees it (raw mode: no SIGINT)
    viewport: Viewport = Viewport.Fullscreen,
    pollMillis: Int = 25                   // input poll timeout: bounds the shutdown latency
) derives CanEqual

enum Viewport derives CanEqual:
  case Fullscreen                          // alternate screen
  case Inline(height: Int)                 // jatatui Viewport.inline(height), no alternate screen
```

No Kyo type appears in `App`, `Next`, `Options` or `Viewport`. This is on purpose: ZIO and Cats users
implement the same trait (section 4).

`Next[S]` is a union, so `update` returns `n + 1` or `Quit` with no wrapper. The runtime tells them
apart with `next.asInstanceOf[AnyRef] eq Quit` (one reference test). Write the result type of `update`
explicitly (`: Next[Int]`): Scala 3 widens an inferred union, and with an explicit type the branches of
the `match` are checked against `Next[Int]`.

### 2.3 Events

```scala
package tui4s

sealed trait Event derives CanEqual

/** Keys. The common cases need no modifier test: `Key.Char('q')`, `Key.Ctrl('c')`, `Key.Up`. */
enum Key extends Event derives CanEqual:
  case Char(c: scala.Char)                 // printable; Shift is already in the char ('A', '+')
  case Ctrl(c: scala.Char)                 // Ctrl + letter, lower case: Key.Ctrl('c')
  case Alt(c: scala.Char)
  case Enter, Esc, Tab, BackTab, Backspace, Delete, Insert
  case Up, Down, Left, Right, Home, End, PageUp, PageDown
  case F(n: Int)
  case Modified(key: Key, mods: Mods)      // a named key with modifiers: Shift+Up, Ctrl+Left, ...

final case class Mods(shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false) derives CanEqual

/** `tags`: the `.tag(...)` names of the views under the pointer in the last frame, top-most first. */
final case class Mouse(kind: MouseKind, x: Int, y: Int, mods: Mods, tags: List[String]) extends Event
enum MouseKind derives CanEqual:
  case Down(button: Button)
  case Up(button: Button)
  case Drag(button: Button)
  case Moved, ScrollUp, ScrollDown, ScrollLeft, ScrollRight
enum Button derives CanEqual:
  case Left, Right, Middle

final case class Resize(width: Int, height: Int) extends Event
final case class Paste(text: String) extends Event
case object FocusGained extends Event
case object FocusLost extends Event
```

How events reach user code:

- The input thread maps each crossterm `tui.crossterm.Event` to this ADT with an exhaustive type-pattern
  match (Java records cannot be extractors, jatatui.md 8.4; Scala 3.9 checks Java sealed interfaces for
  exhaustivity, 8.5, so a new upstream variant is a compile warning that the build makes an error).
- `KeyEventKind.Release` is dropped; `Press` and `Repeat` become `Key` events.
- `KeyCode.Char(c)` with CONTROL becomes `Key.Ctrl(c.toLower)`, with ALT `Key.Alt(c)`, else `Key.Char(c)`
  (SHIFT is ignored for chars: it is already in `c`). `BackTab` ignores SHIFT too (crossterm sends
  Shift+Tab as `BackTab` with SHIFT set). Other named keys with any modifier become `Key.Modified(...)`.
- `Mouse.tags` is filled on the render fiber just before `update` (the hit map belongs to the painter,
  so the input thread never reads it). This gives clickable views without handlers in the view tree:
  `button.tag("ok")` in `view`, `case Mouse(MouseKind.Down(_), _, _, _, "ok" :: _)` in `update`.
- `Resize` reaches `update` too (rarely needed). The runtime redraws on a resize anyway.

### 2.4 View: jatatui widgets plus layout nodes

```scala
package tui4s

/** A view is a jatatui widget. Every jatatui widget value is a view as it is. */
type View = jatatui.core.widgets.Widget

object dsl:                                       // `package tui4s` has `export dsl.*`
  // ---- text (jatatui Paragraph / Line; the result keeps its jatatui type, so `.centered`, `.withWrap(..)` work)
  def text(content: String): Paragraph
  def text(parts: (String | Span)*): Paragraph     // one line built from parts
  def text(lines: Line*): Paragraph                // several lines
  def line(parts: (String | Span)*): Line

  // ---- layout nodes (tui4s classes that implement Widget)
  def column(children: View*): Stack               // Layout.vertical
  def row(children: View*): Stack                  // Layout.horizontal
  def layers(children: View*): View                // painted in order; later ones on top (popups)
  val empty: View                                  // paints nothing; useful as a spacer

  // ---- short constructors for common widgets
  def gauge(ratio: Double, label: String = ""): Gauge            // ratio clamped to 0..1 (withRatio throws outside)
  def sparkline(values: Seq[Long]): Sparkline                    // withDataLongs(values*)
  def tabs(titles: Seq[String], selected: Int): Tabs             // highlight = REVERSED
  def list(items: Seq[String | Line], selected: Int, focused: Boolean = true): View   // ListView: offset retained
  def table(header: Seq[String], rows: Seq[Seq[String]], widths: Seq[Constraint], selected: Int): View
  def input(field: TextField, focused: Boolean = true, placeholder: String = ""): View  // shows the cursor when focused
  def log(lines: Seq[String]): View                // the newest lines that fit, bottom-aligned (a log pane)

  // ---- escape hatches
  def draw(paint: (Rect, Buffer) => Unit): View    // a custom widget (also just a Widget SAM lambda)
  def stateful[St](widget: StatefulWidget[St], fresh: => St)(prepare: St => Unit): View
    // any jatatui StatefulWidget (Scrollbar, a custom one): the runtime keeps one `St` per tree position,
    // `prepare` copies the model into it before each render (e.g. `_.select(...)`)

  // ---- decorators, on every view (and so on every jatatui widget)
  extension (v: View)
    def size(cells: Int): View                     // Constraint.Length along the parent axis
    def fill(weight: Int = 1): View                // Constraint.Fill (the default for children)
    def percent(p: Int): View                      // Constraint.Percentage
    def atLeast(cells: Int): View                  // Constraint.Min
    def atMost(cells: Int): View                   // Constraint.Max
    def bordered(title: String = "", focused: Boolean = false): View   // rounded Block; accent border when focused
    def framed(b: Block): View                     // any jatatui Block around the view
    def padded(horizontal: Int, vertical: Int = 0): View
    def styled(style: Style): View                 // base style for the area
    def center(width: Int, height: Int): View      // a width x height box in the middle, cleared first (popups)
    def tag(name: String): View                    // hit-test name for Mouse.tags
    def key(id: Any): View                         // identity for stateful descendants (see 5.6)

  // ---- styled strings (each returns a jatatui Span; jatatui's own Stylize methods chain after it)
  extension (s: String)
    def bold: Span; def dim: Span; def italic: Span; def underlined: Span; def reversed: Span; def crossedOut: Span
    def fg(c: Color): Span; def bg(c: Color): Span
    def red: Span; def green: Span; def yellow: Span; def blue: Span; def magenta: Span; def cyan: Span; def gray: Span

final class Stack private[tui4s] (/* direction, children, constraints, gap, flex */) extends View:
  def gap(cells: Int): Stack                       // Layout.withSpacing
  def flex(f: Flex): Stack                         // Layout.withFlex
```

Names were chosen against jatatui members, because a member wins over an extension method (a member
that does not fit the arguments gives a compile error, not the extension): `ratio`, `width`, `height`
(members of `Gauge`, `Tabs`, `Line`, `Text`, ...), `max` (`BarChart.max()`), `block` (a public field of
`Paragraph` and `List`, a method of `BarChart`, `Chart`, `Canvas`) and `padding` (a public field of
`Block`) are avoided (VERIFIED by grep over `jatatui-core` and `jatatui-widgets`). `fill`, `percent`,
`atLeast`, `atMost`, `bordered`, `framed`, `padded`, `tag`, `key`, `styled`, `center` are not instance
members of any jatatui widget; `size` is a public field of the fun widget `JatatuiLogo` only (VERIFIED,
same grep). `.centered` on `text(...)` is jatatui's own `Paragraph.centered()`.

Rules of the tree (all enforced by the node classes, not by the user):

- A size modifier can be put before or after a decorator: `x.size(3).bordered("t")` and
  `x.bordered("t").size(3)` are the same node, because every decorator lifts a `Sized` wrapper outside.
- Children without a size get `Constraint.Fill(1)`.
- Every node is an immutable value with structural equality (case classes over jatatui values, which have
  structural equality, jatatui.md 3.0). A `draw { ... }` lambda compares by reference, so a view that holds
  one is "always changed" (correct, only slower; see 7).
- A tui4s node also implements `Widget.render(area, buf)`, so a tui4s view can be rendered by plain
  jatatui code (with fresh widget states in that case).

How jatatui is exposed, widget by widget:

| jatatui | In tui4s | Why |
| --- | --- | --- |
| `Paragraph`, `Gauge`, `Sparkline`, `Tabs` | short constructors that return the jatatui type | stateless values; the `withX` builders stay available (`gauge(r).withUseUnicode(true)`) |
| `BarChart`, `Chart`, `Canvas`, `Calendar`, `LineGauge`, `Clear` | used directly (they are views) | no reason to wrap |
| `Block` | `.bordered(title)` / `.framed(b)`; jatatui's own `withBlock(b)` on the widgets that have it | the common case in one word |
| `List` + `ListState`, `Table` + `TableState` | `list(...)`, `table(...)` nodes | the state is mutable and `render` writes the scroll offset (jatatui.md 3.0), so the runtime keeps it (5.6) |
| `Scrollbar` + `ScrollbarState`, user `StatefulWidget`s | `stateful(widget, fresh)(prepare)` | same mechanism, generic |
| `TextInput` | **not wrapped**; `input(field)` + `TextField` | jatatui's `TextInput` uses UTF-16 indexes (jatatui.md 3.7); `TextField` is grapheme-aware |
| `Layout`, `Constraint` | `column`, `row`, `.size`/`.fill`/... | `Layout.split` is called by the `Stack` node; its cached `Rect[]` is only read, never written (jatatui.md 2.4) |
| `Frame`, `Buffer`, `Terminal`, `Cell` | never exposed, except the `Buffer` given to a `draw` lambda during paint | mutable; owned by the render fiber |

### 2.5 TextField: the one interactive control

```scala
package tui4s

/** A single-line text field. `cursor` counts graphemes (BreakIterator), not UTF-16 chars. */
final case class TextField(text: String, cursor: Int) derives CanEqual:
  /** Applies an editing event: printable keys, Backspace, Delete, Left, Right, Home, End,
    * Ctrl('a'/'e'/'u'/'w'), Paste. Any other event returns `this` (same object). */
  def edit(event: Event): TextField
  def isBlank: Boolean
  def cleared: TextField

object TextField:
  val empty: TextField
  def apply(text: String): TextField                // cursor at the end
```

There is no general "component" abstraction. Reusable interactive parts follow the same pattern as
`TextField`: an immutable value in the model, a pure `edit(event)`, and a view constructor. A list
selection is just an `Int` in the model (jatatui clamps it when it renders).

### 2.6 Sources: how ticks, tasks and streams feed the state

```scala
package tui4s

import kyo.*

/** Background work that folds values into the state. Identity = call site (Kyo Frame) + optional key. */
final class Source[S] private[tui4s] (
    private[tui4s] val id: String,
    private[tui4s] val body: Dispatch[S] => Unit < (Async & Abort[Throwable]),
    private[tui4s] val onError: Maybe[(S, Throwable) => S]
):
  /** Adds a key to the identity: a source with a new key is a new source (the old one is stopped). */
  def key(k: Any): Source[S]
  /** Folds a failure into the state instead of failing the app. */
  def recover(f: (S, Throwable) => S): Source[S]

object Source:
  /** Applies `f` every `period`. */
  def every[S](period: Duration)(f: S => S)(using Frame): Source[S]
  /** Runs `task` every `period` and folds its result. "A fiber that produces a value every 250 ms." */
  def poll[S, A](period: Duration)(task: => A < (Async & Abort[Throwable]))(f: (S, A) => S)(using Frame): Source[S]
  /** Runs `task` once (per appearance of this source) and folds its result. */
  def task[S, A](task: => A < (Async & Abort[Throwable]))(f: (S, A) => S)(using Frame): Source[S]
  /** Folds every element of a Kyo stream; one state change per chunk. */
  def stream[S, A](stream: Stream[A, Async & Abort[Throwable]])(f: (S, A) => S)(using Tag[Emit[Chunk[A]]], Frame): Source[S]
  /** Folds the changes of a Kyo Signal (latest-value semantics). */
  def signal[S, A](signal: Signal[A])(f: (S, A) => S)(using Tag[Emit[Chunk[A]]], Frame): Source[S]
  /** The general form: a fiber body that calls `dispatch` whenever it wants. */
  def apply[S](body: Dispatch[S] => Unit < (Async & Abort[Throwable]))(using Frame): Source[S]

  /** The same constructors with `S` already fixed, so that folds like `_ sample _` infer their types.
    * `TuiApp[S]` has one as `source`; for an app built as a value, use `Source.For[MyState]`. */
  final class For[S] private[tui4s] ():
    def every(period: Duration)(f: S => S)(using Frame): Source[S]
    def poll[A](period: Duration)(task: => A < (Async & Abort[Throwable]))(f: (S, A) => S)(using Frame): Source[S]
    def task[A](task: => A < (Async & Abort[Throwable]))(f: (S, A) => S)(using Frame): Source[S]
    def stream[A](stream: Stream[A, Async & Abort[Throwable]])(f: (S, A) => S)(using Tag[Emit[Chunk[A]]], Frame): Source[S]
    def signal[A](signal: Signal[A])(f: (S, A) => S)(using Tag[Emit[Chunk[A]]], Frame): Source[S]
    def apply(body: Dispatch[S] => Unit < (Async & Abort[Throwable]))(using Frame): Source[S]
  object For:
    def apply[S]: For[S]                           // one shared instance, cast (no allocation)

/** The way into the render loop. Thread-safe; waits (backpressure) when the inbox is full. */
final class Dispatch[S] private[tui4s] (/* the inbox channel */):
  def apply(f: S => S)(using Frame): Unit < Async      // a state change
  def send(event: Event)(using Frame): Unit < Async    // a synthetic event, given to `update`
```

Lifecycle, all done by the runtime on the render fiber (5.4):

1. At start, and after each batch that produced a new state `s`, the runtime calls `app.sources(s)`.
2. It diffs the result against the running set by `id` (`frame.position.show` plus the key, VERIFIED that
   `Frame` has `position`/`show` in kyo-data `Frame.scala`). New ids are started as fibers; ids that
   disappeared are interrupted; the others are left alone (their new `Source` value is ignored).
3. Two sources from the same call site with the same key (a `map` without `.key`) get `#0`, `#1`, ...
   suffixes, so the identity falls back to position in the list.
4. A source body that ends normally (a `task`) stays "done" while its id is still present: it does not
   run again until it disappears and comes back (the `useEffect` rule).
5. A failure goes to `recover` if there is one; otherwise the app stops with `TuiError.SourceFailed`.

Why `(S, A) => S` and not messages: a message type (`enum Msg`) and a second `update` for it doubles the
code in the Dashboard and adds nothing a test cannot get from calling `d.sample(0.5)` directly. A fold is
still data flow in one direction: it runs on the render fiber, in order with the key events.

### 2.7 Tui.run, TuiApp, TuiError: start and quit

```scala
package tui4s

import kyo.*

object Tui:
  /** Runs `app` in the real terminal until `update` returns Quit (or Ctrl-C, see Options).
    * Returns the last state. The terminal is restored before this computation completes, also on
    * failure and on interruption (5.8). Needs a TTY. */
  def run[S](app: App[S])(using Frame): S < (Async & Abort[TuiError])

enum TuiError(message: String, cause: Throwable | Null) extends Exception(message, cause):
  case NotATerminal(error: Throwable)        extends TuiError("stdin/stdout is not a terminal", error)
  case NativeAccessDenied(error: Throwable)  extends TuiError("start the JVM with --enable-native-access=ALL-UNNAMED", error)
  case TerminalIO(error: Throwable)          extends TuiError("terminal I/O failed", error)
  case SourceFailed(source: String, error: Throwable) extends TuiError(s"source $source failed", error)

/** A main class for Kyo users: `object Counter extends TuiApp[Int]: ...`. */
abstract class TuiApp[S] extends App[S]:
  /** Sets the scheduler flags (5.9), preloads the native library, runs `Tui.run(this)`, calls `onExit`
    * AFTER the terminal is restored, prints a failure to stderr, and exits with code 0 or 1. */
  final def main(args: Array[String]): Unit
  protected final def args: Seq[String]
  /** Source constructors with `S` fixed: `source.poll(250.millis)(Random.nextDouble)(_ sample _)`. */
  protected final def source: Source.For[S] = Source.For[S]
  /** Runs after the terminal is restored: print a result, save a file, ... */
  protected def onExit(state: S): Unit < (Async & Abort[Throwable]) = ()
```

Why `source.poll(...)` and not only `Source.poll(...)`: in `Source.poll(d)(task)(_ sample _).key("cpu")` the
state type `S` appears only in the fold and in the result, and the `.key(...)` call cuts the expected type
off, so Scala cannot type the placeholders. With `S` fixed by the member, it can. The ZIO and Cats main
classes have their own `source` member with ZIO and fs2 types (section 4), so the sample code has the same
shape in the three ecosystems.

- **Start**: `TuiApp.main` (or `Tui.run(app)` inside any Kyo program, for example a `KyoApp`).
- **Quit**: `update` returns `Quit`; or Ctrl-C (when `ctrlCQuits`); or the fiber that runs `Tui.run` is
  interrupted (a ZIO interrupt, a CE cancel, SIGTERM through `TuiApp`'s shutdown hook).
- **Result**: the last state. A picker is an app whose `Tui.run(app).map(_.choice)` is the answer.

`TuiApp` is not a `KyoApp`, because `KyoApp.Base.main` is `final` (VERIFIED, `KyoApp.scala`) and the
scheduler flags must be set before the Kyo scheduler initializes (5.9). Users who prefer `KyoApp` write
`run(Tui.run(Counter).unit)` and set the flags with `-D` options.

---

## 3. The three sample apps, for Kyo users

All three are complete programs: imports, model, view, update, sources, main. Each runs with
`sbt "examples/runMain <Object>"` (with the JVM flags of section 5.9). None of them contains a
for-comprehension, a `Var`, a `Scope` or a fiber. The only Kyo code is `Chunk` (as a collection), the log
stream and `Random.nextDouble` in the Dashboard.

### 3.1 Counter

```scala
import tui4s.*

object Counter extends TuiApp[Int]:
  def init: Int = 0

  def view(n: Int): View =
    column(
      text("Count: ", n.toString.bold.yellow).centered.size(1),
      text("+ / - change it · q quits".dim).centered.size(1)
    ).flex(Flex.Center).bordered(" Counter ")

  def update(n: Int, event: Event): Next[Int] = event match
    case Key.Char('+') => n + 1
    case Key.Char('-') => n - 1
    case Key.Char('q') => Quit
    case _             => n
```

What happens at runtime: a `+` makes a new state (`1 ne 0`), the runtime calls `view(1)`, the view is
different from the last one, one frame is drawn. An `x` returns the same `n`: no `view` call, no frame.

### 3.2 Todo

```scala
import kyo.*
import tui4s.*

final case class Item(title: String, done: Boolean = false) derives CanEqual

enum Focus derives CanEqual:
  case Input, Items

final case class Todo(
    field: TextField = TextField.empty,
    items: Chunk[Item] = Chunk.empty,
    selected: Int = 0,
    focus: Focus = Focus.Input
) derives CanEqual:
  def left: Int = items.count(!_.done)

  def add: Todo =
    if field.isBlank then this
    else copy(field = TextField.empty, items = items.append(Item(field.text.trim)), selected = items.size)

  def move(by: Int): Todo =
    copy(selected = (selected + by).max(0).min((items.size - 1).max(0)))

  def toggle: Todo =
    if items.isEmpty then this
    else copy(items = items.updated(selected, items(selected).copy(done = !items(selected).done)))

  def delete: Todo =
    if items.isEmpty then this
    else
      val rest = items.patch(selected, Nil, 1)
      copy(items = rest, selected = selected.min((rest.size - 1).max(0)))

  def switchFocus: Todo =
    copy(focus = if focus == Focus.Input then Focus.Items else Focus.Input)

  def typed(event: Event): Todo =
    val f = field.edit(event)
    if f eq field then this else copy(field = f)      // keep identity when the key did nothing
end Todo

object TodoApp extends TuiApp[Todo]:
  def init: Todo = Todo()

  def view(t: Todo): View =
    val inInput = t.focus == Focus.Input
    column(
      input(t.field, focused = inInput, placeholder = "What needs doing?")
        .bordered(" New ", focused = inInput)
        .size(3),
      list(t.items.map(itemLine), t.selected, focused = !inInput)
        .bordered(s" Todo · ${t.left} left ", focused = !inInput),
      text("tab focus · enter add · ↑↓ move · space toggle · d delete · esc quit".dim).size(1)
    )

  private def itemLine(item: Item): Line =
    if item.done then line("✔ ".green, item.title.dim.crossedOut)
    else line("☐ ", item.title)

  def update(t: Todo, event: Event): Next[Todo] = (t.focus, event) match
    case (_, Key.Tab | Key.BackTab)   => t.switchFocus
    case (_, Key.Esc)                 => Quit
    case (Focus.Input, Key.Enter)     => t.add
    case (Focus.Input, e)             => t.typed(e)
    case (Focus.Items, Key.Up)        => t.move(-1)
    case (Focus.Items, Key.Down)      => t.move(1)
    case (Focus.Items, Key.Char(' ')) => t.toggle
    case (Focus.Items, Key.Char('d')) => t.delete
    case (Focus.Items, Key.Char('q')) => Quit
    case _                            => t
```

Notes:

- Focus is an enum in the model and `update` matches on `(focus, event)`. There is no focus manager: the
  compiler checks the match, and a test can set `focus` directly.
- The text field shows the terminal cursor when it is focused (`input` sets the cursor position during
  paint; jatatui hides the cursor when no position is set, jatatui.md 2.7).
- `list(...)` takes the selection from the model. The scroll offset is not in the model: the runtime keeps
  one jatatui `ListState` for this tree position, so moving up inside a long list moves the highlight,
  not the page (5.6).
- `t.items.map(itemLine)` builds one `Line` per item on each state change. For 100 items that is the
  "+28 KiB" case measured in jatatui.md 5.3 (rebuilding items is cheap; rendering is the cost).

### 3.3 Dashboard

```scala
import kyo.*
import tui4s.*

final case class Dash(
    tab: Int = 0,
    cpu: Double = 0.0,
    history: Chunk[Long] = Chunk.empty,
    logs: Chunk[String] = Chunk.empty,
    paused: Boolean = false
) derives CanEqual:
  def sample(v: Double): Dash = copy(cpu = v, history = history.append((v * 100).toLong).takeRight(200))
  def log(line: String): Dash = copy(logs = logs.append(line).takeRight(500))
  def switchTab(by: Int): Dash = copy(tab = Math.floorMod(tab + by, Dashboard.tabNames.size))

object Dashboard extends TuiApp[Dash]:
  val tabNames: Chunk[String] = Chunk("Metrics", "Requests")

  // A Kyo stream of log lines. NOTE chunkSize = 1: Kyo's unfold/repeatPresent buffer up to chunkSize
  // elements (default 4096) before they emit, which a UI must not do (kyo.md 2.8, VERIFIED in Stream.scala).
  val requests: Stream[String, Async] =
    Stream.unfold(1, chunkSize = 1)(i =>
      Async.sleep(700.millis).andThen(Present((s"GET /api/items/$i 200", i + 1)))
    )

  def init: Dash = Dash()

  // Sources are a function of the state: pausing removes the CPU poller, so its fiber is interrupted;
  // resuming starts a new one. The log stream keeps running in both cases (same key).
  override def sources(d: Dash): Seq[Source[Dash]] =
    val logs = source.stream(requests)(_ log _).key("logs")
    val cpu  = source.poll(250.millis)(Random.nextDouble)(_ sample _).key("cpu")
    if d.paused then Seq(logs) else Seq(logs, cpu)

  def view(d: Dash): View =
    column(
      tabs(tabNames, d.tab).bordered(" Dashboard ").size(3),
      if d.tab == 0 then metrics(d) else log(d.logs).bordered(" Requests "),
      text(s"←/→ tab · p ${if d.paused then "resume" else "pause"} · q quit".dim).size(1)
    )

  private def metrics(d: Dash): View =
    row(
      column(
        gauge(d.cpu, f"${d.cpu * 100}%.0f%%").bordered(" CPU ").size(3),
        sparkline(d.history).bordered(" History ")
      ).percent(60),
      log(d.logs).bordered(" Requests ")
    )

  def update(d: Dash, event: Event): Next[Dash] = event match
    case Key.Left      => d.switchTab(-1)
    case Key.Right     => d.switchTab(1)
    case Key.Char('p') => d.copy(paused = !d.paused)
    case Key.Char('q') => Quit
    case _             => d
```

The CPU source above is the short form. The same thing written as "a fiber that produces a value every
250 ms", with the general constructor:

```scala
val cpu = source { dispatch =>
  Loop.forever(Async.sleep(250.millis).andThen(Random.nextDouble).map(v => dispatch(_.sample(v))))
}.key("cpu")
```

What runs, and when:

| Thing | Runs when | Cost when idle |
| --- | --- | --- |
| input thread | always | one `poll(25 ms)` JNI call at a time, on a platform thread |
| `logs` source fiber | always (key present in every state) | parked in `Async.sleep` |
| `cpu` source fiber | only while `!paused` | parked in `Async.sleep` |
| render loop | parked in `channel.take` until an event or a fold arrives | none |
| frames | 4/s from `cpu` + ~1.4/s from `logs` + one per key that changes the view | none while paused and no logs |

---

## 4. ZIO and Cats Effect users

The app logic (`App[S]`: `init`, `view`, `update`, `sources`) has no effect types, so ZIO and Cats users
implement the same trait. Each facade adds three things only: (1) one import that re-exports the core
API without Kyo types, (2) `Source` constructors that take their own effect and stream types, (3) a
runner (`Tui.run`) and a main class (`TuiApp`) for their runtime.

**The user imports only the facade package** (`import tui4s.zio.*` or `import tui4s.cats.*`), never
`tui4s.*`. Reason: `import tui4s.*` also imports the sub-package `tui4s.zio` as the name `zio`, and a
wildcard import has a higher precedence than the root package, so a following `import zio.*` would mean
`tui4s.zio.*` (see 8, risk R9). The facade package re-exports everything the user needs, so the second
import is never necessary.

### 4.1 ZIO: Counter

```scala
import tui4s.zio.*

object Counter extends TuiApp[Int]:              // tui4s.zio.TuiApp = ZIOAppDefault + App[Int]
  def init: Int = 0

  def view(n: Int): View =
    column(
      text("Count: ", n.toString.bold.yellow).centered.size(1),
      text("+ / - change it · q quits".dim).centered.size(1)
    ).flex(Flex.Center).bordered(" Counter ")

  def update(n: Int, event: Event): Next[Int] = event match
    case Key.Char('+') => n + 1
    case Key.Char('-') => n - 1
    case Key.Char('q') => Quit
    case _             => n
```

The body is byte-for-byte the Kyo Counter. Only the import line differs.

### 4.2 ZIO: Dashboard

```scala
import tui4s.zio.*
import zio.*
import zio.stream.ZStream

final case class Dash(
    tab: Int = 0,
    cpu: Double = 0.0,
    history: Chunk[Long] = Chunk.empty,         // zio.Chunk
    logs: Chunk[String] = Chunk.empty,
    paused: Boolean = false
):
  def sample(v: Double): Dash = copy(cpu = v, history = (history :+ (v * 100).toLong).takeRight(200))
  def log(line: String): Dash = copy(logs = (logs :+ line).takeRight(500))
  def switchTab(by: Int): Dash = copy(tab = Math.floorMod(tab + by, Dashboard.tabNames.size))

object Dashboard extends TuiApp[Dash]:
  val tabNames: Chunk[String] = Chunk("Metrics", "Requests")

  val requests: ZStream[Any, Nothing, String] =
    ZStream.iterate(1)(_ + 1).schedule(Schedule.spaced(700.millis)).map(i => s"GET /api/items/$i 200")

  def init: Dash = Dash()

  override def sources(d: Dash): Seq[Source[Dash]] =
    val logs = source.stream(requests)(_ log _).key("logs")
    val cpu  = source.poll(250.millis)(Random.nextDouble)(_ sample _).key("cpu")   // zio.Random
    if d.paused then Seq(logs) else Seq(logs, cpu)

  def view(d: Dash): View =
    column(
      tabs(tabNames, d.tab).bordered(" Dashboard ").size(3),
      if d.tab == 0 then metrics(d) else log(d.logs).bordered(" Requests "),
      text(s"←/→ tab · p ${if d.paused then "resume" else "pause"} · q quit".dim).size(1)
    )

  private def metrics(d: Dash): View =
    row(
      column(
        gauge(d.cpu, f"${d.cpu * 100}%.0f%%").bordered(" CPU ").size(3),
        sparkline(d.history).bordered(" History ")
      ).percent(60),
      log(d.logs).bordered(" Requests ")
    )

  def update(d: Dash, event: Event): Next[Dash] = event match
    case Key.Left      => d.switchTab(-1)
    case Key.Right     => d.switchTab(1)
    case Key.Char('p') => d.copy(paused = !d.paused)
    case Key.Char('q') => Quit
    case _             => d
```

No Kyo type appears: `Chunk`, `Schedule`, `Random`, `ZStream` and `250.millis` are ZIO's.

### 4.3 The tui4s-zio facade API

```scala
package tui4s.zio                                  // module tui4s-zio; sources use _root_.zio internally

import _root_.zio.{Duration, IO, Runtime, Scope, Trace, ZIO, ZIOAppArgs, ZIOAppDefault}
import _root_.zio.stream.ZStream

// ---- 1. re-exports (type aliases + stable values, so patterns like Key.Char('q') and Quit work)
type App[S] = tui4s.App[S];         val App: tui4s.App.type = tui4s.App
type Next[+S] = tui4s.Next[S];      val Quit: tui4s.Quit.type = tui4s.Quit
type Event = tui4s.Event
type Key = tui4s.Key;               val Key: tui4s.Key.type = tui4s.Key
// ... Mods, Mouse, MouseKind, Button, Resize, Paste, FocusGained, FocusLost, TextField, Options,
//     Viewport, View, TuiError, Style, Color, Modifier, Line, Block, BorderType, Constraint, Flex (same pattern)
type Source[S] = tui4s.Source[S]
export tui4s.dsl.*                                 // text, column, row, gauge, ..., String styling

// ---- 2. sources from ZIO effects and ZStreams
object Source:
  def every[S](period: Duration)(f: S => S)(using kyo.Frame): Source[S]
  def poll[S, A](period: Duration)(task: ZIO[Any, Throwable, A])(f: (S, A) => S)(using kyo.Frame, Trace): Source[S]
  def task[S, A](task: ZIO[Any, Throwable, A])(f: (S, A) => S)(using kyo.Frame, Trace): Source[S]
  def stream[S, A](stream: ZStream[Any, Throwable, A])(f: (S, A) => S)(using kyo.Frame, Trace): Source[S]
  final class For[S]:                              // same four, with S fixed
    ...

// ---- 3. runner and main class
object Tui:
  def run[S](app: App[S])(using Trace): IO[TuiError, S]

abstract class TuiApp[S] extends ZIOAppDefault, tui4s.App[S]:
  protected final def source: Source.For[S] = Source.For[S]
  override val bootstrap = Runtime.removeDefaultLoggers   // ZIO's console logger would draw over the UI
  final def run: ZIO[ZIOAppArgs & Scope, Any, Any] = Tui.run(this).flatMap(onExit)
  protected def onExit(state: S): ZIO[Any, Throwable, Unit] = ZIO.unit
```

`kyo.Frame` is a using-parameter: the macro fills it at the call site and the user never writes it
(it gives the default source id). No Kyo type is written in user code.

How it maps onto the core:

- **`Tui.run(app)`** is `AmbientZIOs.run(tui4s.Tui.run(app))` (prototype `.work/scratch/kyo-rc7/zio/
  ZioBridge.scala`, F12). `AmbientZIOs.run` captures the caller's `zio.Runtime` (`ZIO.runtime`) in an
  inheritable Kyo `Local`; the core's `S < (Async & Abort[TuiError])` becomes `IO[TuiError, S]` through
  `ZIOs.run`. Exceptions thrown by `update`/`view` are Kyo panics and become ZIO defects.
- **Sources** are core `Source.apply` bodies that call `AmbientZIOs.get` (F12: the ZIO runs on the
  caller's runtime, with its FiberRefs and executor; VERIFIED also from a forked Kyo fiber, which is
  where sources run). For a stream: `AmbientZIOs.get(stream.runForeachChunk(c => send(s => c.foldLeft(s)(f))))`
  where `send` is a `ZIO.async` around the core's thread-safe `Dispatch.unsafe.put(g, onAccepted)`
  (backpressure: the stream waits while the inbox is full). `poll` is a Kyo `Loop.forever` of
  `Async.sleep` + `AmbientZIOs.get(task)`. `every` uses no ZIO at all.
- **Interruption**: ZIO interrupts `Tui.run` -> `ZIOs.run` interrupts the Kyo fiber **and waits** (F11)
  -> the terminal release (a `Sync.acquireReleaseWith`, 5.8) has run when the ZIO fiber reports
  interrupted. A stopped source interrupts its ZIO fiber (signalled, not awaited; F11).
- **Logging**: ZIO's default console logger writes to stdout, which corrupts the screen
  (jatatui.md 5.2), so `TuiApp` removes the default loggers. Users add a file logger in `bootstrap`.
- **Scheduler flags** (5.9): `TuiApp`'s constructor sets them, before the first Kyo class initializes.

### 4.4 Cats Effect: Counter

```scala
import tui4s.cats.*

object Counter extends TuiApp[Int]:              // tui4s.cats.TuiApp = IOApp.Simple + App[Int]
  def init: Int = 0

  def view(n: Int): View =
    column(
      text("Count: ", n.toString.bold.yellow).centered.size(1),
      text("+ / - change it · q quits".dim).centered.size(1)
    ).flex(Flex.Center).bordered(" Counter ")

  def update(n: Int, event: Event): Next[Int] = event match
    case Key.Char('+') => n + 1
    case Key.Char('-') => n - 1
    case Key.Char('q') => Quit
    case _             => n
```

### 4.5 Cats Effect: Dashboard

```scala
import tui4s.cats.*
import cats.effect.IO
import fs2.Stream
import scala.concurrent.duration.*

final case class Dash(
    tab: Int = 0,
    cpu: Double = 0.0,
    history: Vector[Long] = Vector.empty,
    logs: Vector[String] = Vector.empty,
    paused: Boolean = false
):
  def sample(v: Double): Dash = copy(cpu = v, history = (history :+ (v * 100).toLong).takeRight(200))
  def log(line: String): Dash = copy(logs = (logs :+ line).takeRight(500))
  def switchTab(by: Int): Dash = copy(tab = Math.floorMod(tab + by, Dashboard.tabNames.size))

object Dashboard extends TuiApp[Dash]:
  val tabNames: Vector[String] = Vector("Metrics", "Requests")

  val requests: Stream[IO, String] =
    Stream.iterate(1)(_ + 1).covary[IO].metered(700.millis).map(i => s"GET /api/items/$i 200")

  val cpuLoad: IO[Double] = IO(math.random())

  def init: Dash = Dash()

  override def sources(d: Dash): Seq[Source[Dash]] =
    val logs = source.stream(requests)(_ log _).key("logs")
    val cpu  = source.poll(250.millis)(cpuLoad)(_ sample _).key("cpu")
    if d.paused then Seq(logs) else Seq(logs, cpu)

  def view(d: Dash): View =
    column(
      tabs(tabNames, d.tab).bordered(" Dashboard ").size(3),
      if d.tab == 0 then metrics(d) else log(d.logs).bordered(" Requests "),
      text(s"←/→ tab · p ${if d.paused then "resume" else "pause"} · q quit".dim).size(1)
    )

  private def metrics(d: Dash): View =
    row(
      column(
        gauge(d.cpu, f"${d.cpu * 100}%.0f%%").bordered(" CPU ").size(3),
        sparkline(d.history).bordered(" History ")
      ).percent(60),
      log(d.logs).bordered(" Requests ")
    )

  def update(d: Dash, event: Event): Next[Dash] = event match
    case Key.Left      => d.switchTab(-1)
    case Key.Right     => d.switchTab(1)
    case Key.Char('p') => d.copy(paused = !d.paused)
    case Key.Char('q') => Quit
    case _             => d
```

### 4.6 The tui4s-cats facade API

```scala
package tui4s.cats                                 // module tui4s-cats; sources use _root_.cats internally

import _root_.cats.effect.{Async, IO, IOApp}
import _root_.cats.effect.unsafe.IORuntimeConfig
import scala.concurrent.duration.FiniteDuration

// ---- 1. re-exports: the same alias file as tui4s-zio
export tui4s.dsl.*
type Source[S] = tui4s.Source[S]
// ... App, Next, Quit, Event, Key, ... (as in 4.3)

// ---- 2. sources from F effects and fs2 streams (any F with Async; IO in practice)
object Source:
  def every[S](period: FiniteDuration)(f: S => S)(using kyo.Frame): Source[S]
  def poll[F[_]: Async, S, A](period: FiniteDuration)(task: F[A])(f: (S, A) => S)(using kyo.Frame): Source[S]
  def task[F[_]: Async, S, A](task: F[A])(f: (S, A) => S)(using kyo.Frame): Source[S]
  def stream[F[_]: Async, S, A](stream: fs2.Stream[F, A])(f: (S, A) => S)(using kyo.Frame): Source[S]
  final class For[F[_], S]:                        // same four, with F and S fixed
    ...

// ---- 3. runner and main class
object Tui:
  def run[F[_]: Async, S](app: App[S]): F[S]      // fails with TuiError

abstract class TuiApp[S] extends IOApp.Simple, tui4s.App[S]:
  protected final def source: Source.For[IO, S] = Source.For[IO, S]
  // CE's CPU-starvation checker prints warnings to stderr, which would draw over the UI: turn it off.
  override protected def runtimeConfig: IORuntimeConfig = super.runtimeConfig.copy(/* starvation check off */)
  final def run: IO[Unit] = Tui.run[IO, S](this).flatMap(onExit)
  protected def onExit(state: S): IO[Unit] = IO.unit
```

How it maps onto the core:

- **`Tui.run[F](app)`** is `CatsBridge.runWithDispatcher[F, S] { d ?=> ambientDispatcher.let(Present(d))(tui4s.Tui.run(app)) }`
  with the vendored bridge `.work/scratch/kyo-rc7/CatsBridge.scala` (F7, F8, 13 tests pass).
  `runWithDispatcher` creates the `Dispatcher` with **`await = false`** (F10: `await = true` can lose a
  cancel in CE 3.7.1). `S < (Async & Abort[TuiError])` already conforms to the bridge's
  `Abort[Throwable] & Async`, because `Abort` is contravariant (`Abort[-E]`, VERIFIED in Abort.scala).
- **Sources** read the `Dispatcher[F]` from the inheritable Kyo `Local` and start their `F` with
  `CatsBridge.getF` (F8: the F runs on the caller's runtime). A stream source runs, on the CE side,
  `stream.chunks.evalMap(c => send(s => c.foldLeft(s)(f))).compile.drain`, where `send` is `F.async_`
  around `Dispatch.unsafe.put(g, onAccepted)`. A stopped source cancels its F (signalled, F8).
- **Cancellation**: CE cancels `Tui.run` (IOApp on SIGINT/SIGTERM, or a `timeout`) -> `runF`'s
  `onCancel` interrupts the Kyo fiber and waits for it (cancel-and-await, F8) -> the terminal release
  (`Sync.acquireReleaseWith`) has run when the cancel completes. VERIFIED in F8 for `Sync.ensure`; the
  same guarantee holds for `Sync.acquireReleaseWith` (kernel `Bracket`, synchronous release; CONTEXT.md).
- **One F per app**: the `Dispatcher` in the `Local` is typed `Dispatcher[F]` by the runner. A source
  built for another F (an `IO` source in a `Tui.run[MyF]` app) is a runtime error with a clear message,
  not a compile error (risk R10).

### 4.7 What changes between the three ecosystems

| Part of the app | Kyo | ZIO | Cats Effect |
| --- | --- | --- | --- |
| import | `tui4s.*` | `tui4s.zio.*` | `tui4s.cats.*` |
| `init`, `view`, `update` | identical | identical | identical |
| `sources` shape | `source.poll / stream / task / every` | same | same |
| effect in a source | `A < (Async & Abort[Throwable])` | `ZIO[Any, Throwable, A]` | `F[A]` |
| stream in a source | `kyo.Stream[A, Async]` | `ZStream[Any, Throwable, A]` | `fs2.Stream[F, A]` |
| main | `TuiApp` (own `main`) | `TuiApp` = `ZIOAppDefault` | `TuiApp` = `IOApp.Simple` |
| run inside a program | `Tui.run(app): S < (Async & Abort[TuiError])` | `Tui.run(app): IO[TuiError, S]` | `Tui.run[F](app): F[S]` |
| tests | `tui4s.testkit.TuiTest` (pure, same for all) | same | same |

---

## 5. Runtime model

### 5.1 Who runs where, and who owns what

```
 tui4s-input (platform daemon thread, owned by the terminal resource)
   loop { if jni.poll(25 ms) then read every queued event (poll(0)), map to tui4s.Event,
          inbox.putBatch (wait while full) }                                           ──┐ Chunk[Event]
                                                                                         │
 source fibers (Kyo, one per active source id, started/stopped by the supervisor)        │ S => S
   e.g. Loop.forever(Async.sleep(250.millis).andThen(task).map(a => dispatch(f(_, a))))  ──┤ (one per tick,
                                                                                         │  task result
                                                                                         ▼  or stream chunk)
                         inbox: Channel[Input[S]]  (MPSC, capacity 1024)
                                                                                         │
 render loop = the fiber that called Tui.run                                             ▼
   take (park) -> drain -> fold events and folds into S -> diff sources -> wait for the frame slot
   -> fold what came meanwhile -> view(S) -> equal to the last drawn view? -> draw (one Sync step)
```

| Object | Owner | Rule |
| --- | --- | --- |
| jatatui `Terminal`, both `Buffer`s, `Frame` | render loop | only touched inside one `Sync.Unsafe.defer` per frame |
| `Painter` (widget states by tree position, hit map, cursor, last view, last size) | render loop | plain mutable fields, no locks |
| `Supervisor` (map: source id -> fiber) | render loop | changed only between batches |
| crossterm reader (`poll`/`read`) | input thread | the only caller of `poll`/`read` |
| crossterm writer (`enqueue`/`execute`/`flush`) | render loop, then the release | the release runs after the input thread has stopped |
| the state `S` | render loop (the `Loop` state) | immutable; sources never see it, they send functions |

All drawing happens on one fiber, as jatatui requires (jatatui.md 5.2). A Kyo fiber can move between
threads, but its steps are sequential, so this is single-owner access. A `Sync` block runs to completion
without parking, so an interrupt cannot land in the middle of a `terminal.draw`.

### 5.2 The inbox

```scala
private[tui4s] type Input[S] = Event | (S => S) | Failed        // Failed(sourceId, error): internal
inbox <- Channel.initUnscoped[Input[S]](1024, Access.MultiProducerSingleConsumer)   // VERIFIED signatures
```

Events and state functions travel in the same channel, so they are applied in arrival order on one
fiber. No wrapper is allocated for a fold: a type test separates an `Event` from a function.

### 5.3 The input thread

The verified production shape (CONTEXT.md) runs input as a Kyo fiber that blocks in `poll(50 ms)`. This
candidate uses the other verified variant of the same probe, a dedicated platform thread (loop.scala
`InputMode.Thread`, same throughput: 7.0 us/event vs 6.7 us/event), for three reasons:

1. A Kyo worker is never parked inside a JNI call, so the blocking monitor never marks a worker as
   blocked and never grows the pool for a TUI that is only waiting for keys.
2. The thread is stopped by the same synchronous release that restores the terminal (5.8), so "input
   stopped, then terminal restored" is a fixed order, also on interrupt.
3. The idle probe gave its lowest number for this mode (17.8% vs 23.3% of a core, loop1.log, noisy
   machine; the difference is mostly noise, the scheduler timers dominate, see 5.9).

```scala
// inside TerminalIO (internal), started by the acquire, stopped by the release
private def inputLoop(jni: CrosstermJni, inbox: Channel[Input[S]], running: AtomicBoolean, pollMs: Int): Unit =
  import AllowUnsafe.embrace.danger
  val timeout = tui.crossterm.Duration(0, pollMs * 1_000_000)          // record Duration(secs, nanos), VERIFIED
  try
    while running.get() do
      if jni.poll(timeout) then
        val batch = Chunk.newBuilder[Input[S]]
        var n = 0
        while n < 512 && (n == 0 || jni.poll(ZeroDuration)) do          // drain what is already queued
          EventMapping(jni.read()).foreach(batch += _)                 // Release -> dropped
          n += 1
        val done = CountDownLatch(1)                                   // backpressure, as in loop.scala
        inbox.unsafe.putBatchFiber(batch.result()).onComplete(_ => done.countDown())
        done.await()
  catch case e: RuntimeException => discard(inbox.unsafe.offer(Failed("input", TuiError.TerminalIO(e))))
```

Costs: one `poll` JNI call per 25 ms while idle (40 wake-ups/s on one platform thread); shutdown waits at
most one poll interval (the probe measured ~56-60 ms with a 50 ms poll; with 25 ms, ~30 ms is expected,
UNVERIFIED).

### 5.4 The render loop

The loop has the shape of the verified probe (take -> drain -> fold -> draw if changed; 10k queued keys
at ~6.7 us/event and 3 frames). Code sketch, idiomatic Kyo (`map`/`andThen`/`Loop`, no for-comprehension):

```scala
private[tui4s] final class RenderLoop[S](app: App[S], inbox: Channel[Input[S]], painter: Painter, sup: Supervisor[S]):
  private val frameNanos = 1_000_000_000L / app.options.fps

  def run(using Frame): S < (Async & Abort[TuiError]) =
    sup.sync(app.sources(app.init))
      .andThen(painter.draw(app.view(app.init)))
      .andThen(now)
      .map(t0 =>
        Loop(app.init, t0) { (state, drawnAt) =>
          nextBatch.map(batch => fold(state, batch)).map {
            case Stop(last)                => Loop.done(last)
            case s if same(s, state)       => Loop.continue(state, drawnAt)       // level 1: nothing changed
            case s                         => changed(s.asInstanceOf[S], drawnAt)
          }
        }
      )

  /** Park until something arrives, then take everything that is queued (coalescing). */
  private def nextBatch(using Frame): Chunk[Input[S]] < (Async & Abort[TuiError]) =
    inbox.take.map(first => inbox.drain.map(rest => Chunk(first).concat(rest))).handle(closedIsBug)

  private def changed(s: S, drawnAt: Long)(using Frame) =
    sup.sync(app.sources(s)).andThen(now).map { t =>
      val wait = drawnAt + frameNanos - t
      if wait <= 0 then drawThenContinue(s)                                   // latency first
      else                                                                    // too early: fold more, draw once
        Async.sleep(Duration.fromNanos(wait))
          .andThen(inbox.drain.handle(closedIsBug))
          .map(more => fold(s, more))
          .map {
            case Stop(last) => Loop.done(last)
            case s2         => sup.sync(app.sources(s2.asInstanceOf[S])).andThen(drawThenContinue(s2.asInstanceOf[S]))
          }
    }

  private def drawThenContinue(s: S)(using Frame) =
    painter.draw(app.view(s)).andThen(now).map(t => Loop.continue(s, t))

  private def now(using Frame): Long < Sync = Sync.defer(java.lang.System.nanoTime())
```

`fold(state, batch)` is a plain `while` loop inside one `Sync.defer` (no Kyo step per event):

1. `Key.Ctrl('c')` with `ctrlCQuits` -> stop.
2. `Resize` -> `painter.resized()` (the next draw happens even if the view is equal).
3. `Mouse` -> `painter.enrich(mouse)` adds the tags under the pointer from the last frame.
4. Any `Event` -> `app.update(s, e)`; `Quit` -> stop with the current state.
5. A function -> `s = f(s)`.
6. `Failed(id, e)` -> the source's `recover` if present, else `Abort.fail(TuiError.SourceFailed(id, e))`.

The result is `S | Stop[S]`. `same(a, b)` is `a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]`.

### 5.5 When a frame is drawn

A frame is drawn only when **all** of these hold:

1. The fold produced a state that is not the same object as before (level 1). Returning `state` from
   `update` for an unhandled key costs one reference test.
2. The new view is not `equals` to the last drawn view, or a `Resize` arrived since the last frame
   (level 2). All tui4s nodes are case classes and jatatui widgets have structural equality, so a state
   change that does not show on screen (a counter that is not displayed, a key that the text field
   ignored but that made a new `Todo`) costs a view build and one `equals`, not a frame.
3. The frame slot is free: at most one frame per `1/fps` (default 60). Events that arrive while the loop
   waits for the slot are folded into the same frame.

```scala
// Painter (internal), on the render fiber
def draw(view: View)(using Frame): Unit < (Sync & Abort[TuiError]) =
  Sync.Unsafe.defer {
    if !resized && view.equals(lastView) then ()                       // level 2: no frame
    else
      try
        discard(terminal.draw(frame => paintRoot(view, frame)))         // jatatui diff -> only changed cells are written
        lastView = view; resized = false; sweepStates()
      catch case e: java.io.IOException => Abort.fail(TuiError.TerminalIO(e))
  }
```

`paintRoot` sets the painter's area and buffer from `frame.area()`/`frame.bufferMut()`, walks the tree,
then calls `frame.setCursorPosition(x, y)` if an `input` node asked for the cursor (VERIFIED Frame API).

### 5.6 Widget state that jatatui forces on us

`List`, `Table` and `Scrollbar` need a mutable state object, and `render` writes its scroll offset
(jatatui.md 3.0). If tui4s built a fresh `ListState` in each frame, the offset would always start at 0
and the list would scroll instead of moving the highlight. So the painter keeps them:

- Each node gets a position id while the tree is walked: `child = parent * 31 + index + 1` (a `Long`,
  no allocation). `.key(id)` replaces the position id of a subtree with a hash of `id`.
- A stateful node asks `painter.state(positionId, fresh)`. The painter keeps a `java.util.HashMap[Long, Slot]`;
  a slot remembers the node class too, so a different widget at the same position gets a fresh state.
- Before the render, the node copies the model into the state (`listState.select(Optional.of(selected))`);
  jatatui clamps the selection and moves the offset.
- After each frame, slots that were not visited are removed (the sweep), so state lives exactly as long
  as the widget is on screen.

This is the only retained state in the framework, and the user never sees it. The cost: one hash
lookup per stateful node per frame.

### 5.7 Sources: the supervisor

```scala
private[tui4s] final class Supervisor[S](inbox: Channel[Input[S]], dispatch: Dispatch[S]):
  private val running = java.util.HashMap[String, Running]()       // id -> fiber, done flag, onError

  /** Called on the render fiber after each state change. */
  def sync(wanted: Seq[Source[S]])(using Frame): Unit < Sync =
    Sync.Unsafe.defer {
      val ids = uniqueIds(wanted)                       // id + "#n" suffix for duplicates
      stopMissing(ids)                                  // fiber.unsafe.interrupt(): signalled, not awaited
      startNew(wanted, ids)                             // Fiber.initUnscoped(src.body(dispatch)) + onComplete -> Failed(...) on error
    }

  def stopAll(using Frame): Unit < Sync                 // in a Sync.ensure around the loop
```

- When the ids are the same as last time (the common case), `sync` is a few hash lookups.
- A source fiber only calls `dispatch`; it never touches the state or the terminal.
- `Fiber.initUnscoped` and not `Fiber.init`: a scoped fiber adds a finalizer to the enclosing `Scope`
  for each start, so an app that starts one `task` per key press would grow that list for its whole life.
  The supervisor owns the fibers itself and stops them all in a `Sync.ensure`.
- Interrupting a source that wraps ZIO or CE work cancels that work (signalled, F8/F11). tui4s does not
  wait for foreign cancellation at shutdown; a late `dispatch` into the finished app is dropped.

### 5.8 Start, quit, errors, interruption, terminal restore

```scala
object Tui:
  def run[S](app: App[S])(using Frame): S < (Async & Abort[TuiError]) =
    Channel.initUnscoped[Input[S]](1024, Access.MultiProducerSingleConsumer).map { inbox =>
      Sync.acquireReleaseWith(TerminalIO.open(app.options, inbox))(_.close()) { term =>   // F8: synchronous release
        val sup = Supervisor[S](inbox, Dispatch(inbox))
        Sync.ensure(sup.stopAll)(RenderLoop(app, inbox, Painter(term), sup).run)
      }
    }
```

`TerminalIO.open` (one `Sync` step): load the native library first (the JEP 472 warning, if any, is
printed before raw mode; if native access is denied, fail with `NativeAccessDenied` while the terminal is
still normal), `enableRawMode()`, `execute(EnterAlternateScreen, Hide, EnableBracketedPaste
[, EnableMouseCapture])`, `Terminal.create(CrosstermBackend(jni))` (or `Terminal.withOptions(...,
TerminalOptions(Viewport.inline(h)))` for `Viewport.Inline`), start the input thread, register an
idempotent JVM shutdown hook. If a step fails, `open` undoes the steps before it and fails with
`NotATerminal`. `Jatatui.init`/`runIo` are not used (global uncaught-exception handler, jatatui.md 4.5).

`close()` (synchronous): stop the input thread (flag, `join` up to 2 x poll), `execute(DisableMouseCapture,
DisableBracketedPaste, Show, LeaveAlternateScreen)`, `disableRawMode()`, `flush()`, remove the hook.
Each step runs even if the one before it failed; `close` never throws (a restore error must not hide
the app's own error); the first restore error is kept and printed by `TuiApp` after the restore.

| How the app ends | What happens, in order |
| --- | --- |
| `update` returns `Quit` (or Ctrl-C) | loop returns the state -> `Sync.ensure` stops sources -> release: input thread joined, terminal restored -> `Tui.run` succeeds with the state |
| `update`/`view` throws | Kyo panic -> same finalizers -> `Tui.run` fails (panic). `TuiApp` prints the stack trace to stderr after the restore, exit code 1 |
| terminal I/O fails | `Abort.fail(TuiError.TerminalIO(e))` -> same finalizers |
| a source fails | `recover` folds it into the state, or `TuiError.SourceFailed` ends the app |
| the fiber is interrupted (ZIO interrupt, CE cancel, `Fiber.interruptAwait`) | `Sync.ensure` and the `acquireReleaseWith` release run **before** the interrupt is reported (F8, F11; the probe verified "terminal restored before `interruptAwait` returns") |
| SIGTERM | `TuiApp`'s shutdown hook interrupts the app fiber and waits up to 2 s; the IOApp/ZIOApp runners do the same through their own hooks |
| SIGINT | in raw mode, Ctrl-C is a key, not a signal; `ctrlCQuits` handles it |
| the JVM exits in another way (`System.exit` in user code) | the idempotent shutdown hook restores the terminal |

Never a `Scope.ensure` for the terminal: on interrupt, Scope finalizers run on a detached fiber that is
not awaited (F8).

### 5.9 Idle CPU and JVM flags

- No frame is drawn and no fiber wakes up while nothing happens, except the input thread's 25 ms poll
  and the sources the state asks for. The Dashboard with `paused = true` and no new logs does no work.
- The Kyo scheduler costs ~13% of a core when idle with default flags (F13). `TuiApp` (all three) sets
  these system properties at the start of `main`/construction, only when the user did not set them, and
  before the scheduler class initializes:
  `kyo.scheduler.cycleIntervalNs=10000000`, `kyo.scheduler.blockingMonitorIntervalNs=20000000`,
  `kyo.scheduler.regulator.concurrencyCollectIntervalMs=100` -> 4-8% measured (F13). The 1 ms clock
  thread (~2-2.5%) is the floor and cannot be configured today (upstream issue to open).
- JVM options a library cannot set, shipped in the examples build and in the docs:
  `--enable-native-access=ALL-UNNAMED` (JEP 472, jatatui.md 1.3), `--sun-misc-unsafe-memory-access=allow`
  (F4), `fork := true` and `connectInput := true` for `sbt run`. `-Xss16m` is needed only to compile
  long Kyo for-comprehensions (F1); the sample apps have none.
- Logging: nothing may write to stdout/stderr while the UI is up. The ZIO and Cats main classes remove
  the console logger and the starvation checker (4.3, 4.6). Kyo's `Log` should be routed to a file by
  `TuiApp` for the duration of the app (open question Q5).

---

## 6. Testing and agent-friendliness

### 6.1 Three levels of test, none needs a terminal

| Level | What | How | Needs |
| --- | --- | --- | --- |
| 1. logic | `update`, model methods, `sources(state)` | call them; they are pure | nothing (plain munit / kyo-test / zio-test / weaver) |
| 2. screen | what a sequence of events draws | `TuiTest(app, w, h)`: the real fold + the real painter on a jatatui `TestBackend` (published, copies cells, jatatui.md 2.9) | jatatui-core only; no native library, no Kyo runtime visible |
| 3. live | sources, timing, the full loop | `TuiTest.live(app, w, h)`: the real `RenderLoop` on a `TestBackend`, scripted input, optional Kyo virtual time | Kyo (`Async & Scope`); UNVERIFIED shape |

```scala
package tui4s.testkit

/** A headless driver. Uses the same fold, the same frame rules (levels 1 and 2) and the same painter as
  * the runtime, but synchronously, on the caller's thread. Same API for Kyo, ZIO and Cats users. */
final class TuiTest[S](app: App[S], width: Int = 80, height: Int = 24):
  def state: S
  def quit: Boolean                     // true once update returned Quit
  def frames: Int                       // frames actually drawn (level-1/level-2 skips are not counted)
  def screen: String                    // the visible text, one line per row, trailing spaces trimmed
  def buffer: jatatui.core.buffer.Buffer   // for style checks: buffer.cellAt(x, y).fg, .modifier
  def sources: Seq[String]              // keys (or call sites) of the sources that sources(state) declares
  def cursor: Option[(Int, Int)]        // where the terminal cursor would be
  def send(events: Event*): this.type   // one batch: folded, then at most one frame
  def press(keys: Key*): this.type      // one batch per key (like a human typing)
  def typeText(text: String): this.type // Key.Char per grapheme
  def fold(f: S => S): this.type        // what a source does: `t.fold(_.sample(0.42))`
  def resize(width: Int, height: Int): this.type
object TuiTest:
  def apply[S](app: App[S], width: Int = 80, height: Int = 24): TuiTest[S]
```

### 6.2 Example tests (munit; the same code works for ZIO and Cats users)

```scala
import kyo.*
import munit.FunSuite
import tui4s.*
import tui4s.testkit.TuiTest

class CounterSuite extends FunSuite:
  test("update is a pure function") {
    assertEquals(Counter.update(1, Key.Char('+')), 2)
    assertEquals(Counter.update(1, Key.Char('q')), Quit)
  }

  test("the screen shows the count") {
    val t = TuiTest(Counter, width = 32, height = 6).press(Key.Char('+'), Key.Char('+'))
    assertEquals(t.state, 2)
    assert(t.screen.contains("Count: 2"), t.screen)      // the message prints the whole screen on failure
  }

  test("keys that change nothing draw no frame") {
    val t      = TuiTest(Counter)
    val before = t.frames
    t.press(Key.Char('x'), Key.Up)
    assertEquals(t.frames, before)                        // performance contract, tested
  }

  test("q quits") {
    assert(TuiTest(Counter).press(Key.Char('q')).quit)
  }

class TodoSuite extends FunSuite:
  test("add, toggle, delete") {
    val t = TuiTest(TodoApp, 48, 12)
      .typeText("buy milk").press(Key.Enter)
      .typeText("call bob").press(Key.Enter)
      .press(Key.Tab, Key.Up, Key.Char(' '))
    assertEquals(t.state.items.map(_.done), Chunk(true, false))
    assert(t.screen.contains("✔ buy milk"), t.screen)
    t.press(Key.Char('d'))
    assertEquals(t.state.items.map(_.title), Chunk("call bob"))
  }

  test("'q' in the text field is text, not quit") {
    val t = TuiTest(TodoApp).typeText("q")
    assert(!t.quit)
    assertEquals(t.state.field.text, "q")
    assert(t.cursor.isDefined)                            // the field has the cursor
  }

class DashboardSuite extends FunSuite:
  test("a sample moves the gauge; pause stops the cpu source") {
    val t = TuiTest(Dashboard, 72, 16).fold(_.sample(0.42))
    assert(t.screen.contains("42%"), t.screen)
    assertEquals(t.sources, Seq("logs", "cpu"))
    t.press(Key.Char('p'))
    assertEquals(t.sources, Seq("logs"))                  // the poller would be interrupted
  }
```

A golden-screen assertion is a plain string compare; `tui4s.testkit` adds `assertScreen(t, expected)`
which prints a row-by-row diff (like jatatui's unpublished `BufferAssertions`, jatatui.md 2.9):

```scala
assertScreen(TuiTest(Counter, 24, 5).press(Key.Char('+')),
  """╭ Counter ─────────────╮
    |│       Count: 1       │
    |│+ / - change it · q qu│
    |│                      │
    |╰──────────────────────╯""")             // illustrative: exact spacing is UNVERIFIED
```

### 6.3 Why this is easy for an AI agent to write and to check

1. **One pattern for every app.** `init` / `view` / `update` / `sources`. There are no hook rules, no
   element identity, no lifecycle methods, no `Var` or `Signal` to thread through. An agent that knows
   Elm or bubbletea already knows tui4s.
2. **The screen is text.** `t.screen` is a string that the agent can print, read and diff. A failed
   assertion shows the whole frame. The agent can check its layout work without a terminal and without
   screenshots.
3. **The compiler checks the parts that agents get wrong.** `Key` is an enum (exhaustive matches);
   `Next[S]` forces `update` to return a state; `-Wnonunit-statement` and `-Wvalue-discard` catch a lost
   `copy(...)`.
4. **Performance is testable.** `t.frames` turns "do not redraw for nothing" into an assertion.
5. **Same code in three ecosystems.** Docs, examples and tests are shared; an agent working in a ZIO
   codebase can copy a Kyo example and change one import.
6. **No Kyo knowledge needed for app logic.** The F1 trap (compiler stack overflow on long Kyo
   for-comprehensions) cannot happen in `update` or `view`, because they are not effectful.
7. **Errors are plain values.** A failing source can be folded into the state with `recover`, so the
   error path is visible in the model and testable with `t.fold(_.failed(...))`.

---

## 7. Performance analysis

Numbers marked VERIFIED come from jatatui.md 5.3 (2014 i7, loaded machine, 120x40) and from the Kyo
event-loop probe (loop1..3.log). The rest are estimates and say so.

### 7.1 Work per input event

| Step | Where | Cost | Source |
| --- | --- | --- | --- |
| `poll` + `read` + map to `tui4s.Event` | input thread | one JNI read, one small object | design |
| hand-over to the render fiber | `putBatchFiber` / `take` + `drain` | ~0.5-0.8 us/event when batched | VERIFIED micro: 469-843 ns/iter |
| `update(s, e)` | render fiber, inside one `Sync.defer` for the whole batch | the user's code: a `copy` or less; no Kyo step per event | design (cheaper than `Var.update`, 617-835 ns/iter VERIFIED) |
| level-1 test | render fiber | one `eq` | design |
| whole pipeline, 10k queued keys, batched + coalesced | probe with a stand-in draw | ~6.7 us/event, 3 frames | VERIFIED (loop1.log) |

### 7.2 Work per frame (only when the state changed)

| Step | Cost | Note |
| --- | --- | --- |
| `sources(s)` + supervisor diff | ~0.1-1 us for a few sources | estimate; hash lookups when the ids are unchanged |
| `view(s)` | a few dozen node objects; Todo with 100 items ~ +28 KiB | VERIFIED order of size: rebuilding 100 `ListItem`s each frame added +28 KiB (jatatui.md 5.3, C vs B) |
| level-2 `equals` with the last view | O(view) when equal, stops at the first difference otherwise; ~1-50 us | estimate |
| tree walk | one `Layout.split` cache hit per `column`/`row`: 2.7-4.5 us each (VERIFIED); one hash lookup per stateful node | Dashboard: ~4 stacks, ~15 us |
| jatatui `Terminal.draw` | ~2.1 ms and ~1.7 MiB for a typical 120x40 frame; ~0.6 ms of that is fixed (diff + reset) | VERIFIED |
| terminal output | one `Print` per changed cell, one JNI `enqueue` per frame | jatatui.md 4.3, 2.8 |

The framework's own cost per frame (sources diff + view build + equality + tree walk) is roughly
20-100 us, so **1-5% of a frame**. The rest is jatatui, and the design attacks that part by not drawing.

### 7.3 Frames that are not drawn

| Situation | Frames | Saved per avoided frame |
| --- | --- | --- |
| a key that `update` ignores (`case _ => s`) | 0 (level 1: no `view` call at all) | ~2.1 ms, ~1.7 MiB |
| a key that changes the state but not the screen | 0 (level 2: one `view` + one `equals`) | ~2 ms, ~1.7 MiB minus the view build |
| a burst of N events | at most `fps` frames per second; 10k queued keys -> 3 frames | VERIFIED in the probe |
| idle (no input, no source) | 0 | everything |
| Dashboard, running | ~4 (cpu) + ~1.4 (logs) frames/s -> ~11 ms/s of CPU (~1.1% of a core) and ~9 MiB/s of short-lived garbage | estimate from the VERIFIED per-frame numbers |
| Dashboard, paused, no new logs | 0 | |

For comparison: a fixed 60 fps loop at 120x40 would cost ~126 ms/s (~12.6% of a core) and ~100 MiB/s of
garbage while showing the same thing (VERIFIED per-frame cost x 60).

### 7.4 Idle cost

- Render loop: parked in `inbox.take` (no CPU).
- Input thread: one `poll(25 ms)` JNI call at a time (40 returns/s).
- Sources: only the ones the state asks for; each one parked in `Async.sleep` or in its stream.
- Kyo scheduler: ~13% of one core with default flags, 4-8% with the flags `TuiApp` sets, floor ~2-2.5%
  from the 1 ms clock thread (VERIFIED, F13). This floor is the largest idle cost and is outside
  tui4s's control.

### 7.5 Cost of the abstraction compared with the other directions

| Mechanism | This design | Typical alternative |
| --- | --- | --- |
| change detection | one `eq`, then one `equals` on the root view | signal graph (subscriptions, propagation per change) or a VDOM diff of element trees |
| widget identity | position ids only for stateful jatatui widgets (hash lookup) | fibers/hooks for every component |
| effects per event | none (a pure `while` loop folds the batch) | `Var.update` / `SignalRef.set` per event (~0.6-0.8 us VERIFIED for `Var`) |
| background work | fibers only while the state needs them | always-on tick fiber |
| Kyo steps per frame | `take`, `drain`, one `Sync` for the draw, `Loop.continue` (2 state values, no tuple) | |

### 7.6 Known hot spots

- **Very long lists.** `list(items)` builds one jatatui `ListItem` per item on each state change. At 10k
  items this is ~10k objects per change (estimate ~1-2 MiB). Fix (not in the samples): a windowed
  `list(count, i => Line)` node that builds only the visible rows at paint time.
- **Views that hold lambdas** (`draw { ... }`, jatatui `Canvas`) are never `equals` to the last view, so
  level 2 never skips their frame. Correct, only slower.
- **Long append-only logs.** `equals` on a 500-line `Chunk` compares the common prefix; a few
  microseconds. Keep logs bounded in the model (`takeRight(500)`), as the sample does.

---

## 8. Weaknesses, risks and open questions

### 8.1 Weaknesses of the model itself

- **W1. One global state scales by discipline, not by mechanism.** Big apps become nested case classes
  and nested `copy` calls; there is no local component state and no lens library. A reusable widget is
  a triple (value in the model, pure `edit`, view constructor), like `TextField`; a form with ten fields
  needs a focus enum and routing in `update`. Components or signals do this with less code in large apps.
- **W2. No one-shot command.** `update` is pure, so "on Ctrl-S, write the file" is: set `saving = true`
  and a request counter in the state, and declare `source.task(save(doc))(_ saved _).key(s.saveId)` while
  `saving`. It is declarative and testable, but it is one more step than bubbletea's `Cmd` or a ZIO effect
  in the handler. (Q2.)
- **W3. Every state change rebuilds the whole view.** Cheap for normal screens (7.2), expensive for a
  10k-row table unless a windowed node exists (7.6).
- **W4. Focus and mouse are minimal.** Focus is the user's enum; mouse support is `tag` + `Mouse.tags`
  (no hover regions, no click-to-focus, no event bubbling). Fine for keyboard-first TUIs; weak for
  mouse-heavy ones.

### 8.2 Risks

- **R1. `Next[S] = S | Quit.type`.** Scala 3 widens an inferred union, so `def update(...) = ...` with no
  result type may infer `Matchable` and fail with an unclear override error; the docs and samples always
  write `: Next[S]` (UNVERIFIED whether the override's expected type is used for inference here). If `S`
  is `Any` or itself contains `Quit.type`, `Quit` is ambiguous. An opaque `Next` would fix both but
  would need an explicit lift (`Next(n + 1)`), which costs beauty.
- **R2. Type inference of folds.** `source.poll(...)(task)(_ sample _)` relies on `S` being fixed by the
  `source` member. With the companion `Source.poll(...)(...)(_ sample _).key("cpu")` the placeholders
  cannot be typed; the user must write `Source.For[Dash]` or typed lambdas. The validator should check
  that `_ log _` infers with `source.stream(...)` (UNVERIFIED).
- **R3. jatatui types are tui4s API.** `View = Widget` and the helpers that return `Paragraph`, `Gauge`,
  `Tabs`, `Sparkline` put jatatui's (0.x, single maintainer, two releases so far) types into tui4s's
  public and binary API. A jatatui breaking change is a tui4s breaking change (`Compatibility.None`).
  This is the price of "no re-wrapped catalogue". The alternative (helpers return `View`) loses
  `.centered`/`.withX` chaining. (Q3.)
- **R4. Position identity for widget state.** If a conditional view appears before a list, the list's
  position id changes and its scroll offset resets (the selection stays visible; the page jumps).
  `.key(...)` fixes it, but the user must know it.
- **R5. Call-site identity for sources.** It is implicit: two sources built inside the same helper
  method share a call site and fall back to list position; a source whose parameters change but whose id
  does not keeps the old fiber running (the `useEffect` dependency problem). The rule "put changing
  parameters in `.key(...)`" must be learned.
- **R6. Structural equality is trusted.** Level 2 skips a frame when `view.equals(lastView)`. A
  user-written `Widget` class with a wrong `equals` (equal objects that paint differently) would suppress a
  frame. jatatui's own widgets and tui4s nodes are safe; `draw` lambdas are reference-equal (safe).
- **R7. Kyo stream chunking.** `Stream.unfold`/`repeatPresent` buffer to 4096 elements by default
  (VERIFIED in Stream.scala). A Kyo user who forgets `chunkSize = 1` gets a log pane that never updates.
  tui4s cannot un-buffer an upstream stream; only docs and examples help.
- **R8. Input on a platform thread** differs from the verified fiber shape. It was measured in the same
  probe (Thread mode), but its stop path depends on `poll` returning within its timeout; if the JNI call
  hangs, `join` times out and a daemon thread leaks (the terminal is restored anyway).
- **R9. Package names `tui4s.zio` / `tui4s.cats`.** `import tui4s.*` imports these sub-packages as `zio`
  and `cats` and shadows the root packages in that file. The facades re-export all of the core so users
  never need `import tui4s.*`, but a user who mixes Kyo and ZIO code in one file will hit it.
  Alternative: `tui4s.interop.zio` or `tui4s.ziotui`. (Q4.)
- **R10. Cats: one `F` per app.** Sources read a `Dispatcher[F]` from a Kyo `Local` set by `Tui.run[F]`;
  a source for another `F` fails at run time, not at compile time.
- **R11. Scheduler flags.** A 10 ms worker cycle lowers idle CPU but may make the scheduler slower to
  react (timer and preemption granularity; frame-pacing sleeps could wake late). UNVERIFIED. The flags
  are silently ignored if user code starts the Kyo scheduler before `TuiApp` sets them.
- **R12. Everything that writes to stdout/stderr corrupts the screen**: user `println`, Kyo `Log`
  defaults, ZIO's console logger, CE's starvation checker, JEP 472 and `sun.misc.Unsafe` warnings. The
  main classes handle the last four; `println` is on the user.
- **R13. Inherited jatatui/crossterm limits**: non-BMP characters typed on the keyboard are truncated
  (`KeyCode.Char(char)`, jatatui.md 4.4; paste works); no Windows arm64 or musl native; one temp dir per
  JVM; JDK flags required.

### 8.3 Open questions for the panel

- **Q1.** Should `update` be allowed `Sync` (for `Clock.now`, random ids)? This candidate says no: purity
  keeps the logic identical for ZIO and Cats and keeps tests trivial. Time and randomness come from
  sources.
- **Q2.** Add a one-shot command (`Next.withTask(...)`, or `update` returning `(S, Seq[Task])`) next to
  state-driven sources? It would fix W2 but add a second way to start work.
- **Q3.** Helpers return jatatui types (chainable, R3 coupling) or `View` (decoupled, less chainable)?
- **Q4.** Facade package names (R9).
- **Q5.** Should `TuiApp` redirect Kyo `Log` (and `System.out`/`err`) to a file while the UI is up?
- **Q6.** kyo-test or munit for tui4s's own tests? The testkit itself is framework-neutral.
- **Q7.** `TuiTest` inside `tui4s-core` (no extra dependency; jatatui `TestBackend` is in jatatui-core) or
  a separate `tui4s-testkit` artifact?
- **Q8.** Suspend (Ctrl-Z) and "run an external program" (leave and re-enter the alternate screen): add
  `Source.exec(...)` that pauses input and restores the terminal around a process?

### 8.4 What the validator should compile first (UNVERIFIED items)

1. `type View = jatatui.core.widgets.Widget` + tui4s nodes as case classes that implement `Widget`;
   extension methods `size/fill/percent/atLeast/atMost/bordered/key/tag` on jatatui widgets (no member
   clash) and `.centered` chaining on `text(...)`.
2. `text(content: String)`, `text(parts: (String | Span)*)`, `text(lines: Line*)` overload resolution for
   `text("a")`, `text("a", "b".bold)`, `text(line1, line2)`.
3. `type Next[+S] = S | Quit.type` with `def update(n: Int, e: Event): Next[Int] = e match ...` and
   munit `assertEquals(Counter.update(1, Key.Char('q')), Quit)`.
4. `enum Key extends Event` with `case Char(c: scala.Char)` and the pattern `(_, Key.Tab | Key.BackTab)`.
5. `source.stream(requests)(_ log _).key("logs")` and `source.poll(250.millis)(Random.nextDouble)(_ sample _)`
   type inference (R2).
6. `Sync.acquireReleaseWith(open)(_.close())(use)` with `use` returning `S < (Async & Abort[TuiError])`
   (needs `ConcreteTag[TuiError]`).
7. The render loop sketch (5.4): `Loop(state, drawnAt)` with branches that return `Loop.continue` or
   `Loop.done` under `Async & Abort[TuiError]`.
8. Facades: `abstract class TuiApp[S] extends ZIOAppDefault, tui4s.App[S]` and
   `extends IOApp.Simple, tui4s.App[S]` (no member clash with `init`, `view`, `update`, `sources`,
   `options`); `override val bootstrap = Runtime.removeDefaultLoggers`; the CE `IORuntimeConfig` field
   that turns the starvation checker off.
9. Re-export file: type aliases + stable `val`s so that `Key.Char('q')`, `Resize(w, h)` and `Quit` work as
   patterns through `tui4s.zio.*`.
10. Setting the scheduler system properties in `TuiApp.main` before `kyo.scheduler` initializes.
11. Re-exporting jatatui Java classes with their static members (`Color.YELLOW`, `Flex.Center`,
    `Constraint.Length(3)`) from package `tui4s`, so that `import tui4s.*` is enough. A Java class's
    statics are not a Scala value, so `val Flex = ...` does not work; `export jatatui.core.layout.Flex`
    (package prefix) may or may not be accepted by Scala 3.9.
