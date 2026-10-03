# tui4s design specification (core, zio, cats)

Status: written by the panel synthesizer on 2026-10-01, section by section. Input: `CONTEXT.md`, the four
candidates in `candidates/`, and the three judgements in `judging/`. The validator compiled the API skeleton,
the samples and both facades on 2026-10-01 and amended this file in place: each change is marked
"(validated: <reason>)". Section 10 has the result; the attempt-by-attempt log is `VALIDATION.md`.

Legend: **VERIFIED** = checked in the RC7 Kyo sources, the jatatui 0.31 sources, or a probe log named in
`CONTEXT.md` / `.work/reports/`. **UNVERIFIED** = an inference; the validator or the first PR must check it.
**[src: x]** = the candidate or judge that the idea comes from.

How to read: section 1 says what was decided and why. Sections 2 to 7 are the contract that the
implementation follows. Section 8 is the order of work. Section 9 lists the few decisions that the user
must make. An implementer does not need to read the candidates.

---

## 1. Decision summary

### 1.1 Winner and scores

The winner is **free** ("three pure functions, one runtime"). The other candidates give the grafts in 1.3.

| Candidate | dx (35%) | perf (25%) | fit (25%) | testability (15%) | weighted |
| --- | --- | --- | --- | --- | --- |
| **free** | 8 | 7 | 6.5 | 9 | **7.53** |
| elm | 7 | 6.5 | 8 | 8.5 | 7.35 |
| signals | 6.5 | 7 | 7.5 | 7 | 6.95 |
| components | 5 | 6 | 6 | 6.5 | 5.73 |

Why free stays the winner:

- No judge found a fatal flaw in free. Its two "must fix" items (no one-shot command; `View = jatatui Widget`
  breaks view equality for `Gauge`) have clean fixes that come from elm (grafts G1, G2).
- elm is close (7.35), but its lead in fit comes from parts that graft onto free without its costs (the
  Kyo-free shared surface, facades that add only constructors). Its own costs (a `Msg` ADT and a second
  `update` case for every async value, keys that need `subscriptions`, the lossy `Async.timeout` frame wait)
  do not come along.
- components has a near-fatal DX flaw (Kyo effect rows and Props/State/Out plumbing in user code) and a
  fatal fit flaw (the facade `state` handle reads a `Dispatch` that a forked fiber can outlive). signals has a
  near-fatal learnability flaw (behaviour that matters is invisible at the call site: `map` vs `at`, root
  bindings, torn frames). We keep only their isolated runtime and testing ideas.

The design in one screen (the complete Kyo Counter, section 3.1):

```scala
import tui4s.*

object CounterApp extends TuiApp[Int]:
  val keys: Keymap[Int] = Keymap(
    bind('+', "increment")(_ + 1),
    bind('-', "decrement")(_ - 1),
    bind('q', "quit")(_ => Quit)
  )

  def init: Int = 0
  def update(n: Int, event: Event): Next[Int] = keys(n, event)
  def view(n: Int): View =
    column(
      text("Count: ", n.toString.bold.yellow).centered.size(1),
      help(keys).centered.size(1)
    ).flex(Flex.Center).bordered(" Counter ")
```

The same text compiles for ZIO users (`import tui4s.interop.zio.*`) and Cats Effect users (`import tui4s.interop.cats.*`).

### 1.2 The model in seven rules

1. **An app is pure functions of one immutable state `S`.** `init: S`, `view(S): View`,
   `update(S, Event): Next[S]`, `sources(S): Seq[Source[S]]`. No effect type appears in any of them, so the
   same app text serves Kyo, ZIO and Cats users, and the F1 compiler trap cannot occur in app logic. [src: free]
2. **No message type.** `update` gets the terminal `Event`. Async values fold into the state with
   `(S, A) => Next[S]`. [src: free]
3. **`Next[S] = S | Quit.type | Next.Then[S]`.** A branch returns the new state, `Quit`, `Quit(state)`, or
   `state.withTask(task*)` to start one-shot work. [src: free + elm `withCmd`, adapted]
4. **Standing work is a function of state.** `sources(state)` is diffed by identity after each change:
   new sources start, missing ones stop. Pause = leave the source out. [src: free, elm]
5. **The view is tui4s-owned immutable data.** Case-class nodes hold plain values and build jatatui widgets
   only at paint time, so view equality is exact for every widget. jatatui types appear only in three
   escape hatches. [src: elm]
6. **Draw only when the view changed, at most once per frame.** Level 1: same state object, no work.
   Level 2: equal view and no resize, no frame. Bursts fold into one frame. [src: free, elm]
7. **One fiber owns every mutable jatatui object**, input runs on a platform thread, and the terminal is
   restored by a synchronous release before `Tui.run` completes, also on interruption (F8). [src: free,
   components]

### 1.3 Grafts (what was added to free, and why)

| # | Graft | From | Where | Raises |
| --- | --- | --- | --- | --- |
| G1 | View = tui4s case-class nodes; opaque `Style`/`Color`/`Line`/`Line.Span`; jatatui only in `widget`/`stateful`/`draw` | elm | 2.6, 5.7 | perf (view equality works for `Gauge`/`LineGauge`), fit (jatatui 0.x out of the binary API), dx (no `List`/`Line`/`Block`/`Color` clashes, no `Style.fg()` trap) |
| G2 | One-shot tasks: `state.withTask(source.task(io)(fold))`; `Quit(state)`; folds return `Next[S]` | elm `withCmd`, dx judge | 2.3, 2.8 | dx (closes free W2: "on Ctrl-S save" is one line), fit |
| G3 | One `Keymap` drives `update` and the help bar (`keys(state, event)`, `help(keys)`) | elm | 2.5 | dx (help cannot drift from bindings), testability |
| G4 | `Focus[A]` helper, focus-dependent keymaps, focused border | components, signals | 2.5, 3.2 | dx (typing `d` in the input never deletes) |
| G5 | Kyo-free shared surface, facades add only constructors + runner + app class, a CI check that no `kyo.` type leaks, `CallSite` instead of `kyo.Frame` in facade signatures | elm | 2.12, 4, 6.5 | fit, dx |
| G6 | `NotForeign[A]` compile-time guard against the Kyo lift trap (a ZIO/IO/Future given to `source.task` compiles and never runs) | signals `IntoAction`, components | 2.8 | dx, fit |
| G7 | Generation tokens: a value from a stopped source or a replaced task is dropped; values fold with the latest declared fold | components, elm | 5.8 | perf (runtime correctness) |
| G8 | Release order: close the inbox, stop and join the input thread, then leave raw mode, all in the synchronous release | components, free | 5.9 | perf (no key read after restore) |
| G9 | Output hygiene: `Log.let` around the run, `System.out`/`err` capture while the UI is up, ZIO `removeDefaultLoggers`, CE starvation checker off | components, signals, free | 5.10 | perf, fit |
| G10 | `Tui.start` + `TuiHandle` to embed a TUI in a larger program | elm | 2.9 | fit |
| G11 | Trace file (JSON lines) + replay; `t.lastTrace`, `t.viewTree`, `Keymap.explain` | elm, components | 5.16, 2.10 | testability (agents) |
| G12 | Synchronous effect-free `TuiTest` for all three ecosystems + `emit`/`fail`/`started`; a live level with a deterministic `settle` and correct virtual-time stepping | free, signals, fit judge (V8) | 2.10, 6 | testability |
| G13 | `Stats` counters (frames, view builds, skips, dropped folds) as performance contracts | signals, elm, free | 2.10, 7.2 | testability, perf |
| G14 | Exact register-before-read watcher for `Source.signal` on a `SignalRef` (no 1 s repair timer) | signals | 5.8 | perf |
| G15 | Scheduler flag defaults set in the `TuiApp` constructor (all three modules), only when the user did not set them | all, perf judge check 1 | 5.14 | perf (idle CPU 13% -> 4-8%) |
| G16 | Dev checks: duplicate key binding fails at construction; slow `update`/`view` (> one frame) is reported in the trace | components | 2.5, 5.16 | testability |

### 1.4 Rejected (and why)

| Idea | From | Why not |
| --- | --- | --- |
| `Msg` ADT, separate `Cmd`/`Sub`, keys routed through `subscriptions` | elm | Every async value needs a `Msg` case plus an `update` case (Dashboard 53 vs 43 lines). The `subscriptions = Sub.none` default silently ignores keys. |
| Frame wait as `Async.timeout(inbox.take)` | elm | Lossy in RC7: a take that completes just before the timer interrupt drops the message (`Async.scala:196-212`, `Channel.scala:865-890`). We sleep the remainder, then drain. |
| Scoped `Fiber.init` per command/source | elm, components, signals | Each start adds a finalizer to an unbounded `Scope` queue for the app's life (`Fiber.scala:129-150`, `Scope.scala:289-307`). We use `Fiber.initUnscoped` + a supervisor. |
| Local component state (`Var`/`Emit` rows, Props/State/Out) | components | Kyo rows and ceremony in user code; a stale-state duality; facades need ~60 forwarders and an ambient `Dispatch` that forked fibers outlive. |
| `SignalRef` as the app state | signals | Correctness and speed depend on rules the types do not show (`at` vs `map`, bind at the leaves, one ref per unit). State changes become effects. |
| Input on a Kyo fiber that blocks in JNI `poll` | signals default | Parks a Kyo worker in native code 20 times a second; the blocking monitor reacts; higher idle CPU in the probe. |
| `View = jatatui Widget` | free | `Gauge`, `LineGauge`, `Scrollbar`, `Canvas`, `Calendar` have no `equals` (V17), so level 2 never skips; jatatui 0.x types become tui4s binary API. |
| `IntoAction[-R]` type class for effectful handlers | signals | This design has no effectful handlers. The useful part (stop a foreign effect from being lifted) becomes `NotForeign[A]` on `task`/`poll`. |
| Required string ids for every source | elm | Call-site identity is the default; `.id(name)` is optional. |
| Retained presenter (own canvas, partial repaint) as the default | signals | A port of jatatui's diff and cursor logic is a large correctness surface. Deferred, opt-in, after measurement (1.5). |
| `TuiApp extends KyoApp` | components, signals | `KyoApp.Base.main` is final (V11) and is not needed; our `main` controls the order of scheduler flags, run, restore, exit code. |
| Restore inside `Scope.ensure` | none adopted | F8: Scope finalizers run detached on interruption. Only `Sync.acquireReleaseWith` / `Sync.ensure`. |

### 1.5 Deferred to phase 2 (designed for, not in v1)

Each item enters only when the benchmark of section 7 shows the need, with `Stats` and a "same screen as a
full repaint" check in every test that uses it.

- `cached(dep)(view)`: copy the cells that a subtree painted for an equal `(dep, area)` [src: elm `lazily`,
  components blit; premise VERIFIED: `Terminal.swapBuffers` keeps the last frame intact during the next draw].
- A retained presenter with partial repaint and `verifyRepaints` [src: signals; fix its z-closure first so
  that lower-z nodes under a closed popup repaint].
- `virtualList(count, selected, version)(row)`: builds only the visible rows [src: components, elm].
- `Tui.printAbove(lines)` for `Viewport.Inline` (jatatui `insertBefore`), and `Source.exec` (leave the
  alternate screen, run a process, come back).
- Facade live test kits with ZIO `TestClock` / CE `TestControl`.

### 1.6 Expected effect of the grafts

Synthesizer estimate, not a judge score: dx 8.5 (help from keymaps, one-shot tasks, focus helper, one view
vocabulary), perf 7.5 (exact view equality, generation tokens, release order, output hygiene), fit 7.5 (no
jatatui in the binary API, Kyo-free surface with a CI check, `TuiHandle`, clean facade signatures),
testability 9 (emit/fail, trace, live level with exact settle). Weighted: about **8.1**.

---
## 2. Package layout and the public API of tui4s-core

### 2.1 Module and dependencies

`modules/core`, artifact `io.github.yuri4n::tui4s-core`. Compile dependencies (already in
`modules/core/build.sbt`): `io.getkyo::kyo-core` 1.0.0-RC7, `com.olvind.jatatui:jatatui-core`,
`jatatui-widgets`, `jatatui-crossterm` 0.31.0 (brings `crossterm`). Test dependency: `io.getkyo::kyo-test-runner`
1.0.0-RC7 with `Test / testFrameworks += new TestFramework("kyo.test.runner.SbtFramework")` (6.4). No other
runtime dependency. Never `com.olvind.tui`.

### 2.2 Packages

| Package | Content | Users |
| --- | --- | --- |
| `tui4s` | `App`, `Next`, `Quit`, events (`Event`, `Key`, `Mods`, `Mouse`, `MouseKind`, `Button`, `Resize`, `Paste`, `FocusGained`, `FocusLost`), `Keymap`, `Bind`, `Focus`, `TextField`, `View` (nodes nested in `object View`), `Style`, `Color`, `Line` (+ `Line.Span`), `Flex`, `Align`, `Border`, `BorderKind`, `Constraint`, `Theme`, `Source`, `Dispatch`, `NotForeign`, `CallSite`, `Tui`, `TuiApp`, `TuiHandle`, `TuiOptions`, `Viewport`, `TuiError`, `Stats`. The object `dsl` holds the view constructors and all extension methods; the package has `export dsl.*`. | every Kyo app: `import tui4s.*` is the only tui4s import it needs |
| `tui4s.testkit` | `TuiTest`, `LiveTest`, `Screen`, `diffScreen`, `assertScreen`, `ScriptedInput` (validated: needed by `spi.Headless` users and the facade tests, 2.10) | tests of all three ecosystems |
| `tui4s.spi` | `Sources.make`, `Emitter`, `SchedulerDefaults`, `Headless`, `InputPort` | the facades, tests, benchmarks. Public, documented as "for integrations" |
| `tui4s.internal` | `Engine`, `RenderLoop`, `Painter`, `Supervisor`, `TerminalIO`, `InputThread`, `CrosstermInput`, `EventMapping`, `OutputCapture`, `TuiLog`, `TraceWriter`, macros | not API (`private[tui4s]` where possible). (validated: Kyo has a package `kyo.internal`; in any file with `import kyo.*` the bare name `internal` means `kyo.internal`, or is ambiguous with `import tui4s.*`. Library code writes `tui4s.internal.X`.) |

Kyo-free shared surface (G5): everything in `tui4s` and `tui4s.testkit` except the Kyo-typed members listed
here: the `Source` / `Source.For` constructors, `Dispatch`, `Tui`, `TuiApp`, `TuiHandle`, `TuiTest.live`,
`TuiTest.liveBlocking`, `LiveTest`. The facades re-export the shared surface unchanged (section 4). A CI test
fails when a `kyo.` type appears in a shared signature (6.5).

### 2.3 App, Next, Quit

```scala
package tui4s

/** A terminal app: pure functions of one immutable state `S`. Kyo, ZIO and Cats users implement this trait. */
trait App[S]:
  /** The first state. */
  def init: S
  /** The screen for a state. Pure. Called only when the state changed or after a resize. */
  def view(state: S): View
  /** The next state after an event. Pure. Return `state` itself (the same object) when nothing changed. */
  def update(state: S, event: Event): Next[S]
  /** The standing background work for this state. Pure. Diffed by identity after each change (2.13). */
  def sources(state: S): Seq[Source[S]] = Nil
  /** Terminal and loop options. Read once, at start. */
  def options: TuiOptions = TuiOptions()

  /** A key binding with `S` fixed, so that `_ + 1` and `_.toggle` infer. */
  protected final def bind(key: Key | Char, help: String)(action: S => Next[S]): Bind[S] =
    Bind[S](key, help)(action)

object App:
  /** An app as a value: for apps built from runtime data, and for tests. */
  def apply[S](
      init: S,
      view: S => View,
      update: (S, Event) => Next[S],
      sources: S => Seq[Source[S]] = (_: S) => Seq.empty[Source[S]], // (validated: see the note below)
      options: TuiOptions = TuiOptions()
  ): App[S]

/** What `update` and every fold return. */
type Next[S] = S | Quit.type | Next.Then[S]

/** Stops the app. `Quit` keeps the current state; `Quit(last)` sets it. `Tui.run` returns that state. */
case object Quit:
  def apply[S](last: S): Next[S] = Next.Then(last, Nil, quit = true)

object Next:
  /** A state plus the effects of the transition. Built by `withTask` and `Quit(last)`. */
  final case class Then[S] private[tui4s] (state: S, tasks: Seq[Source[S]], quit: Boolean)

// in object dsl (exported by package tui4s):
extension [S](state: S)
  /** `state`, plus one-shot tasks that start after this transition (2.13 rule 9). */
  def withTask(tasks: Source[S]*): Next[S] = Next.Then(state, tasks, quit = false)
```

Notes:

- `Next` is invariant (`Then[S]` holds `Source[S]`, which is invariant). `n + 1`, `Quit`, `Quit(s)` and
  `s.withTask(...)` all conform to `Next[S]` with no wrapper; a plain state allocates nothing.
- Write the result type of `update` (`: Next[Int]`). An inferred union widens.
- The runtime splits a `Next` with one reference test (`eq Quit`) and one type test (`Next.Then`). A state
  type must not itself be `Quit.type` or a `Next.Then` (absurd in practice; documented).
- `init` is a plain `S`. Work that must run at start is a source that the initial state declares (the Todo
  sample loads its file this way).
- (validated: the default of `App.apply`'s `sources` must be typed `Seq.empty[Source[S]]`. Scala 3.9 types a
  default getter from its right-hand side, so `(_: Any) => Nil` and even `(_: S) => Nil` fail as soon as a
  caller omits `sources`: "Found: S => Seq[Source[Nothing]], Required: S => Seq[Source[S]]" (`Source` is
  invariant). Reproduced without Kyo.)

### 2.4 Events

```scala
package tui4s

sealed trait Event derives CanEqual

enum Key extends Event derives CanEqual:
  case Char(c: scala.Char)              // printable; Shift is already applied: Key.Char('A'), Key.Char(' ')
  case Ctrl(c: scala.Char)              // Ctrl + letter, lower case: Key.Ctrl('s')
  case Alt(c: scala.Char)
  case Enter, Esc, Tab, BackTab, Backspace, Delete, Insert
  case Up, Down, Left, Right, Home, End, PageUp, PageDown
  case F(n: Int)
  case Modified(key: Key, mods: Mods)   // a named key with modifiers: Shift+Up, Ctrl+Left
  /** Help-bar and trace text: "+", "space", "ctrl+s", "alt+x", "↑", "shift+↑", "tab", "f5". */
  def show: String

final case class Mods(shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false) derives CanEqual

/** `tags`: the `.tag(...)` names of the views under the pointer in the last drawn frame, top-most first. */
final case class Mouse(kind: MouseKind, x: Int, y: Int, mods: Mods, tags: List[String]) extends Event derives CanEqual
enum MouseKind derives CanEqual:
  case Down(button: Button)
  case Up(button: Button)
  case Drag(button: Button)
  case Moved, ScrollUp, ScrollDown, ScrollLeft, ScrollRight
enum Button derives CanEqual:
  case Left, Right, Middle

final case class Resize(width: Int, height: Int) extends Event derives CanEqual
final case class Paste(text: String) extends Event derives CanEqual   // needs TuiOptions.paste (default on)
case object FocusGained extends Event                                  // needs TuiOptions.focusEvents
case object FocusLost extends Event
```

Mapping from crossterm (input thread, `EventMapping`; type patterns, because Java records are not
extractors, VERIFIED jatatui.md 8.4; Scala 3.9 checks Java sealed interfaces for exhaustivity, 8.5, and the
build turns that warning into an error for this file):

| crossterm | tui4s |
| --- | --- |
| `KeyEvent` with `KeyEventKind.Release` | dropped |
| `KeyCode.Char(c)` + CONTROL | `Key.Ctrl(c.toLower)` |
| `KeyCode.Char(c)` + ALT | `Key.Alt(c)` |
| `KeyCode.Char(c)` (SHIFT ignored, it is in `c`) | `Key.Char(c)` |
| `BackTab` (SHIFT ignored) | `Key.BackTab` |
| other named key, no modifier / with modifiers | `Key.Up` ... / `Key.Modified(Key.Up, Mods(...))` |
| `F(n)` | `Key.F(n)` |
| `Null`, `CapsLock`, `ScrollLock`, `NumLock`, `PrintScreen`, `Pause`, `Menu`, `KeypadBegin`, `Media`, `Modifier` | dropped |
| `Mouse(MouseEvent(kind, column, row, mods))` | `Mouse(kind, column, row, mods, Nil)`; tags filled on the render fiber |
| `Paste(s)`, `Resize(c, r)`, `FocusGained`, `FocusLost` | same names |

Known limit (VERIFIED jatatui.md 4.4): crossterm truncates non-BMP characters typed on the keyboard; pasted
text (bracketed paste) arrives intact.

### 2.5 Keymap, Bind, Focus, TextField

```scala
package tui4s

/** One binding: its keys, its help text, its transition. */
final class Bind[S] private (val keys: Seq[Key], val help: String, val action: S => Next[S], val hidden: Boolean):
  /** More keys for the same action: `bind(Key.Up, "up")(_.move(-1)).or('k')`. Help shows "↑/k". */
  def or(more: (Key | Char)*): Bind[S]
  /** Bound, but not shown by `help`. */
  def hide: Bind[S]
object Bind:
  /** For apps built as values: `Bind[Dash]('p', "pause")(_.togglePause)`. In a TuiApp use `bind(...)`. */
  def apply[S](key: Key | Char, help: String)(action: S => Next[S]): Bind[S]

/** Key bindings. The same value routes keys in `update` and renders the help bar (G3). */
final class Keymap[S] private (val binds: Seq[Bind[S]]):
  /** The bound transition for `event`, or `state` itself when no binding matches. */
  def apply(state: S, event: Event): Next[S]
  /** Extractor for `update`: `case keys(action) => action(state)`. Matches key events that have a binding. */
  def unapply(event: Event): Option[S => Next[S]]
  def lookup(event: Event): Option[Bind[S]]
  /** Both keymaps. A key bound in both: the left one wins (an intended override). */
  def ++(other: Keymap[S]): Keymap[S]
  /** For tests and agents: "'d' -> delete", or "'x' -> no binding (bound: + - q)". */
  def explain(event: Event): String
object Keymap:
  /** Throws IllegalArgumentException when two binds of the list share a key (dev check G16). */
  def apply[S](binds: Bind[S]*): Keymap[S]

/** Which part of a fixed set has the focus. Immutable, kept in the app state (G4). Structural equality. */
final class Focus[A] private (val order: Vector[A], val index: Int):
  def current: A
  def is(a: A): Boolean
  def next: Focus[A]          // wraps around
  def prev: Focus[A]
  def to(a: A): Focus[A]      // `this` when `a` is not in the order
object Focus:
  def apply[A](first: A, rest: A*): Focus[A]

/** A single-line text editor value. `cursor` counts grapheme clusters (java.text.BreakIterator), not UTF-16
  * chars (jatatui's TextInput uses UTF-16 indexes, jatatui.md 3.7, so tui4s does not wrap it). */
final case class TextField private (text: String, cursor: Int) derives CanEqual:
  /** Printable keys insert; Backspace, Delete, Left, Right, Home, End, Ctrl('a'/'e'/'u'/'k'/'w') edit;
    * Paste inserts. Any other event returns `this` (the same object, so level 1 skips). */
  def edit(event: Event): TextField
  def isBlank: Boolean
  def cleared: TextField
object TextField:
  val empty: TextField
  def apply(text: String): TextField   // cursor at the end
```

`Keymap` keeps a lazily built `HashMap[Key, Bind]`, so a lookup is one hash probe. Build keymaps once
(`val`s); a keymap per state is a choice between precomputed values (`if inInput then inputKeys else
listKeys`), not a `++` per event.

### 2.6 View, styles and text (G1)

A view is an immutable tree of tui4s case classes that hold plain data. The painter builds jatatui widgets
from them at paint time (building is cheap; rendering is the cost, jatatui.md 5.3). Two views compare with
`==`, so the level-2 frame skip is exact for every node, including gauges.

```scala
package tui4s

/** An immutable description of a screen. Nodes are nested in `object View`; build them with the dsl. */
sealed abstract class View:
  // size inside the parent column/row (default fill(1)); order-independent with every decorator:
  // x.size(3).bordered("t") == x.bordered("t").size(3)  (decorators lift a Sized wrapper outside)
  def size(cells: Int): View            // Constraint.Length
  def fill(weight: Int = 1): View       // Constraint.Fill
  def percent(p: Int): View             // Constraint.Percent
  def ratio(num: Int, den: Int): View   // Constraint.Ratio
  def atLeast(cells: Int): View         // Constraint.Min
  def atMost(cells: Int): View          // Constraint.Max
  // decoration
  def bordered(title: String = "", focused: Boolean = false): View   // rounded border; theme accent when focused
  def framed(border: Border): View
  def padded(horizontal: Int, vertical: Int = 0): View
  def style(s: Style): View             // base style of the area, set before the content paints
  def popup(width: Int, height: Int): View   // a width x height box in the middle of the area, cleared first
  // identity and interaction
  def id(name: String): View            // identity of runtime-kept widget state (scroll offsets), 5.7
  def tag(name: String): View           // reported in Mouse.tags while the pointer is over this area

object View:
  final case class Text private[tui4s] (lines: Seq[Line], align: Align, wraps: Boolean, scrollY: Int) extends View:
    def centered: Text
    def right: Text
    def wrap(on: Boolean = true): Text
    def scroll(y: Int): Text
  final case class Stack private[tui4s] (vertical: Boolean, children: Seq[View], spacing: Int, justify: Flex) extends View:
    def gap(cells: Int): Stack          // Layout.withSpacing
    def flex(f: Flex): Stack            // Layout.withFlex
  final case class Layers private[tui4s] (children: Seq[View]) extends View
  final case class Items private[tui4s] (items: Seq[String | Line], selected: Int, focused: Boolean) extends View
  final case class Table private[tui4s] (header: Seq[String], rows: Seq[Seq[String | Line]], widths: Seq[Constraint],
                                         selected: Int, focused: Boolean) extends View
  final case class Tabs private[tui4s] (titles: Seq[String], selected: Int) extends View
  final case class Gauge private[tui4s] (ratio: Double, label: String, color: Color, line: Boolean) extends View
  final case class Sparkline private[tui4s] (values: Seq[Long], max: Option[Long], color: Color) extends View
  final case class Input private[tui4s] (field: TextField, focused: Boolean, placeholder: String) extends View
  final case class Log private[tui4s] (lines: Seq[String | Line]) extends View
  case object Clear extends View
  case object Empty extends View
  // escape hatches: equality is the jatatui widget's equals, or reference equality for functions
  final case class Widget private[tui4s] (widget: jatatui.core.widgets.Widget) extends View
  final case class Stateful private[tui4s] (fresh: () => Any, paint: (jatatui.core.layout.Rect, jatatui.core.buffer.Buffer, Any) => Unit) extends View
  final case class Draw private[tui4s] (paint: (jatatui.core.layout.Rect, jatatui.core.buffer.Buffer) => Unit) extends View
  // wrappers built by the modifiers
  final case class Sized private[tui4s] (child: View, constraint: Constraint) extends View
  final case class Bordered private[tui4s] (child: View, border: Border, focused: Boolean) extends View
  final case class Padded private[tui4s] (child: View, horizontal: Int, vertical: Int) extends View
  final case class Styled private[tui4s] (child: View, style: Style) extends View
  final case class Popup private[tui4s] (child: View, width: Int, height: Int) extends View
  final case class Identified private[tui4s] (child: View, name: String) extends View
  final case class Tagged private[tui4s] (child: View, name: String) extends View

// ---- styles and text: opaque types over jatatui's immutable records (no conversion, structural equality)
opaque type Color = jatatui.core.style.Color
object Color:
  val Reset, Black, Red, Green, Yellow, Blue, Magenta, Cyan, Gray, DarkGray, White: Color
  val LightRed, LightGreen, LightYellow, LightBlue, LightMagenta, LightCyan: Color
  def rgb(r: Int, g: Int, b: Int): Color
  def indexed(i: Int): Color

opaque type Style = jatatui.core.style.Style
object Style:
  val default: Style
  val bold, dim, italic, underlined, reversed, crossedOut: Style
  def fg(c: Color): Style
  def bg(c: Color): Style

opaque type Line = jatatui.core.text.Line
object Line:
  /** A styled piece of a line. Nested, because `kyo.Span` exists and `import kyo.*` is common. */
  opaque type Span = jatatui.core.text.Span
  def apply(parts: (String | Span)*): Line
  val empty: Line

enum Flex derives CanEqual:  case Start, Center, End, SpaceBetween, SpaceAround
enum Align derives CanEqual: case Left, Center, Right
enum BorderKind derives CanEqual: case Plain, Rounded, Double, Thick
final case class Border(title: String = "", kind: BorderKind = BorderKind.Rounded,
                        style: Style = Style.default, titleStyle: Style = Style.default) derives CanEqual
enum Constraint derives CanEqual:
  case Length(cells: Int); case Fill(weight: Int); case Percent(p: Int)
  case Min(cells: Int);    case Max(cells: Int);   case Ratio(num: Int, den: Int)
final case class Theme(accent: Color = Color.Cyan, highlight: Style = Style.reversed,
                       unfocusedHighlight: Style = Style.dim, helpKey: Style = Style.bold,
                       helpText: Style = Style.dim) derives CanEqual
```

How jatatui is wrapped (VERIFIED method names: jatatui.md 2-3 and the candidates' source checks):

| Node | Painted with | Note |
| --- | --- | --- |
| `Text` | `Paragraph.of(Text)` + `withAlignment` / `withWrap(Wrap(true))` / `withScroll` | String parts become `Span.raw` at paint time |
| `Stack` | `Layout.vertical/horizontal(constraints*)` + `withSpacing` + `withFlex`, `split(area)` | the returned `Rect[]` is jatatui's shared cache entry: read at once, never stored or written |
| `Layers` | children in order on the same area | later children win hit tests |
| `Items` | `List.of(items)` + highlight style/symbol, `renderStatefulWidget` with a kept `ListState` | `select(Optional.of(i))` before render; jatatui clamps and moves the offset |
| `Table` | `Table` + `TableState` (kept) | same as `Items` |
| `Tabs` | `Tabs.of(lines).withSelected(i)`, highlight = theme accent | |
| `Gauge` | `Gauge.empty().withRatio(clamped).withLabel(...)`, or `LineGauge` when `line` | ratio clamped to 0..1 (`withRatio` throws outside) |
| `Sparkline` | `Sparkline.empty().withDataLongs(values*)` + `withMax` | |
| `Input` | tui4s grapheme-aware renderer; `frame.setCursorPosition(x, y)` when focused | jatatui hides the cursor when no position is set |
| `Log` | the newest lines that fit, bottom-aligned, no wrap | |
| `Bordered` / `framed` | `Block.bordered().withBorderType(...).withTitle(...)`, child in `block.inner(area)` | |
| `Popup` | `Clear` on the centered rect, then the child | |
| `Widget`, `Stateful`, `Draw` | the user's code, on the render fiber | the only places where jatatui types reach user code |

Names are not jatatui names on purpose: no `List`, `Block`, `Paragraph` in user code, and no `Style.fg()`
record-accessor trap (the `fg` extension calls `withFg`).

### 2.7 The dsl object (exported by `tui4s`)

```scala
package tui4s

object dsl:
  // ---- text
  def text(parts: (String | Line.Span)*): View.Text         // one line
  def paragraph(lines: (String | Line)*): View.Text         // several lines; wraps (`.wrap(false)` clips)
  def line(parts: (String | Line.Span)*): Line
  // ---- layout
  def column(children: View*): View.Stack
  def row(children: View*): View.Stack
  def layers(children: View*): View                          // painted in order; later ones on top (popups)
  val empty: View
  val clear: View                                            // erases its area (under popups)
  // ---- widgets
  def list(items: Seq[String | Line], selected: Int, focused: Boolean = true): View
  def table(header: Seq[String], rows: Seq[Seq[String | Line]], widths: Seq[Constraint],
            selected: Int = -1, focused: Boolean = true): View
  def tabs(titles: Seq[String], selected: Int): View
  def gauge(ratio: Double, label: String = "", color: Color = Color.Cyan): View
  def lineGauge(ratio: Double, label: String = "", color: Color = Color.Cyan): View
  def sparkline(values: Seq[Long], max: Option[Long] = None, color: Color = Color.Green): View
  def input(field: TextField, focused: Boolean = true, placeholder: String = ""): View
  def logPane(lines: Seq[String | Line]): View               // newest lines that fit, bottom-aligned
  def help(keys: Keymap[?]): View.Text                       // "+ increment · - decrement · q quit"
  // ---- escape hatches (jatatui types on purpose)
  def widget(w: jatatui.core.widgets.Widget): View
  def stateful[St](fresh: => St)(paint: (jatatui.core.layout.Rect, jatatui.core.buffer.Buffer, St) => Unit): View
  def draw(paint: (jatatui.core.layout.Rect, jatatui.core.buffer.Buffer) => Unit): View
  // ---- transitions
  extension [S](state: S) def withTask(tasks: Source[S]*): Next[S]
  // ---- styling: each returns a Line.Span; the same names exist on Line.Span, so calls chain
  extension (s: String)
    def bold: Line.Span;  def dim: Line.Span;  def italic: Line.Span;  def underlined: Line.Span
    def reversed: Line.Span;  def crossedOut: Line.Span
    def fg(c: Color): Line.Span;  def bg(c: Color): Line.Span;  def styled(st: Style): Line.Span
    def red: Line.Span;  def green: Line.Span;  def yellow: Line.Span;  def blue: Line.Span
    def magenta: Line.Span;  def cyan: Line.Span;  def gray: Line.Span;  def white: Line.Span
  extension (s: Line.Span)   // the same list
  extension (s: Style)
    def fg(c: Color): Style;  def bg(c: Color): Style        // withFg / withBg, never the accessor fg()
    def bold: Style;  def dim: Style;  def italic: Style;  def underlined: Style
    def reversed: Style;  def crossedOut: Style
    def ++(other: Style): Style                              // patch
```

The extensions on `Style` and `Line.Span` are defined in small objects nested in the companion objects of
those opaque types (`Style.StyleOps`, `Line.SpanOps`), where the alias is transparent, and `dsl` re-exports
them with `export Line.SpanOps.*` and `export Style.StyleOps.*`. (validated: a separate top-level object in the
file of a top-level opaque type does NOT see through the alias ("withFg is not a member of tui4s.Style"); only
the companion and the file's top-level definitions do. The three overloads per name (`bold` on `String`,
`Line.Span`, `Style`) export and resolve without ambiguity, also through `export dsl.*` and the facades'
`export tui4s.dsl.*`.) No extension name is a
member of `String`, `Style` (outside its file) or `Line.Span`, and tui4s nodes are tui4s classes, so a
member never shadows an extension (the trap free audited for jatatui widgets does not exist here).

### 2.8 Sources, tasks, Dispatch, NotForeign, CallSite

```scala
package tui4s

import kyo.*

/** Background work that folds values into the state. Used two ways: declared by `sources(state)`
  * (standing, diffed by identity), or started once by `state.withTask(...)` (one-shot).
  * Identity: the call site, or `.id(name)`. Equality and hashCode: by label. */
final class Source[S] private[tui4s] (/* label, body, fold, recover: internal */):
  /** The identity shown in tests and traces: "DashboardApp.scala:41", or the id. */
  def label: String
  /** Sets the identity. A new id is a new source: the old one stops. Put changing parameters in it.
    * For a task: a task with an id interrupts the running task with the same id (latest wins). */
  def id(name: String): Source[S]
  /** Folds a failure into the state instead of ending the app. */
  def recover(f: (S, Throwable) => Next[S]): Source[S]

object Source:
  /** Applies `f` every `period` (first after one period). */
  def every[S](period: Duration)(f: S => Next[S])(using Frame): Source[S]
  /** Runs `task` now, then again `period` after each result; folds each result. */
  def poll[S, A](period: Duration)(task: => A < (Async & Abort[Throwable]))(using NotForeign[A])(
      f: (S, A) => Next[S])(using Frame): Source[S]
  /** Runs `task` once and folds its result. */
  def task[S, A](task: => A < (Async & Abort[Throwable]))(using NotForeign[A])(
      f: (S, A) => Next[S])(using Frame): Source[S]
  /** Folds every element of a Kyo stream; one state change per chunk. The stream is created at start. */
  def stream[S, A](stream: => Stream[A, Async & Abort[Throwable]])(f: (S, A) => Next[S])(
      using Tag[Emit[Chunk[A]]], Frame): Source[S]
  /** Folds the changes of a Kyo Signal (latest value). Exact for a SignalRef (5.8). */
  def signal[S, A](signal: Signal[A])(f: (S, A) => Next[S])(using Frame): Source[S]
  /** The general form: a fiber body that sends state changes whenever it wants. */
  def apply[S](body: Dispatch[S] => Unit < (Async & Abort[Throwable]))(using Frame): Source[S]

  /** The same constructors with `S` fixed, so folds like `_.sample(_)` infer. `TuiApp[S]` has one as
    * `source`; for an app built as a value write `Source.For[MyState]`. */
  final class For[S] private[tui4s] ():
    def every(period: Duration)(f: S => Next[S])(using Frame): Source[S]
    def poll[A](period: Duration)(task: => A < (Async & Abort[Throwable]))(using NotForeign[A])(
        f: (S, A) => Next[S])(using Frame): Source[S]
    def task[A](task: => A < (Async & Abort[Throwable]))(using NotForeign[A])(f: (S, A) => Next[S])(
        using Frame): Source[S]
    def stream[A](stream: => Stream[A, Async & Abort[Throwable]])(f: (S, A) => Next[S])(
        using Tag[Emit[Chunk[A]]], Frame): Source[S]
    def signal[A](signal: Signal[A])(f: (S, A) => Next[S])(using Frame): Source[S]
    def apply(body: Dispatch[S] => Unit < (Async & Abort[Throwable]))(using Frame): Source[S]
  object For:
    def apply[S]: For[S]                // one shared instance, cast; no allocation

/** How a general source sends state changes into the loop. Thread-safe. */
final class Dispatch[S] private[tui4s]:
  /** Waits while the inbox is full (backpressure). Dropped when this source stopped or the app ended. */
  def apply(f: S => Next[S])(using Frame): Unit < Async

/** Compile-time guard (G6). Kyo lifts any plain value into `A < S` (VERIFIED Implicits.scala:18), so a
  * zio.ZIO, cats.effect.IO, fs2.Stream, zio.stream.ZStream or scala.concurrent.Future given to `task` or
  * `poll` would compile and never run. The macro rejects these types with a message that names the fix:
  * "source.task got a zio.ZIO[...]. A Kyo app runs Kyo computations only. In a ZIO app import tui4s.interop.zio.*;
  * in a Kyo app convert it first (kyo-zio ZIOs.get)". (validated: the inline given expands only when the rest
  * of the call type-checks. With a wrong fold the user sees the fold error, which names `A = zio.ZIO[...]`,
  * and not this message; with a right fold this message appears, also in munit `compileErrors`.) */
sealed trait NotForeign[A]
object NotForeign:
  /** (validated: the macro expansion is inlined into user code, where a sealed trait cannot be instantiated and
    * a private member is not reachable, so the one instance is public.) */
  object Evidence extends NotForeign[Any]
  inline given [A]: NotForeign[A]                 // ${ tui4s.internal.Macros.notForeign[A] }

/** The source position of a call. Used as the identity of facade sources, so facade signatures show no Kyo
  * type. Filled at the call site from Kyo's Frame macro (summonInline); users never write it. */
final class CallSite private[tui4s] (val file: String, val line: Int):
  def show: String
object CallSite:
  inline given here: CallSite
```

Kyo `Duration` here is `kyo.Duration` (`250.millis` with `import kyo.*`). The 4096 chunk trap (VERIFIED
`Stream.scala`): `Stream.unfold` and `Stream.repeatPresent` buffer up to `chunkSize` elements before they
emit; streams for a UI must use `chunkSize = 1`. tui4s cannot fix an upstream stream; the docs and the
Dashboard sample say it.

### 2.9 Runner, app class, handle, options, errors

```scala
package tui4s

import kyo.*

object Tui:
  /** Runs `app` in the real terminal until it quits. Returns the last state. The terminal is restored before
    * this computation completes, also on failure and on interruption (5.9). Needs a TTY. */
  def run[S](app: App[S])(using Frame): S < (Async & Abort[TuiError])
  /** Starts `app` on its own fiber and returns a handle (G10). The end of the scope stops the app. On an
    * interrupted scope the restore can finish shortly after the scope (F8); `handle.stop` / `await` wait
    * for it. */
  def start[S](app: App[S])(using Frame): TuiHandle[S] < (Async & Scope)

final class TuiHandle[S] private[tui4s]:
  def update(f: S => Next[S])(using Frame): Unit < Async      // like a fold, from outside; dropped after the end
  def send(event: Event)(using Frame): Unit < Async           // a synthetic input event
  def states: Signal[S]                                       // the state after each batch (equal states deduplicated)
                                                              // (validated: published from the first frame on, so it starts at `init`)
  def stats(using Frame): Stats < Sync                        // (validated: a lock-free snapshot of the render fiber's counters)
  def await(using Frame): S < (Async & Abort[TuiError])       // the result of the run
  def stop(using Frame): Unit < Async                         // like Quit; returns when the terminal is restored

/** A main class for Kyo users: `object CounterApp extends TuiApp[Int]`. Not a KyoApp (KyoApp.Base.main is
  * final, VERIFIED KyoApp.scala:52). */
abstract class TuiApp[S] extends App[S]:
  // The constructor body calls tui4s.spi.SchedulerDefaults.applyIfUnset() before anything else (5.14).
  /** Runs Tui.run(this); after the restore runs onExit; prints a failure to stderr; exit code 0 or 1. */
  final def main(args: Array[String]): Unit
  protected final def args: Seq[String]
  /** Source constructors with `S` fixed. */
  protected final def source: Source.For[S]
  /** Runs after the terminal is restored: print a result, write a file. */
  protected def onExit(state: S): Unit < (Async & Abort[Throwable]) = ()

final case class TuiOptions(
    fps: Int = 60,                                // at most one frame per 1/fps; <= 0: no cap (live tests)
    mouse: Boolean = false,                       // EnableMouseCapture
    paste: Boolean = true,                        // EnableBracketedPaste: a paste is one Paste event
    focusEvents: Boolean = false,                 // EnableFocusChange: FocusGained / FocusLost
    ctrlCQuits: Boolean = true,                   // Ctrl-C quits before update sees it (raw mode: no SIGINT)
    viewport: Viewport = Viewport.Fullscreen,
    pollMillis: Int = 25,                         // input poll timeout; bounds the quit latency
    inboxCapacity: Int = 1024,
    theme: Theme = Theme(),
    captureOutput: Boolean = true,                // System.out / System.err go to the log while the UI is up
    logFile: Option[java.nio.file.Path] = None,   // Kyo Log + captured output; None: memory ring, printed after restore
    trace: Option[java.nio.file.Path] = None      // JSON lines, one per batch (5.16)
) derives CanEqual

enum Viewport derives CanEqual:
  case Fullscreen                                 // alternate screen
  case Inline(height: Int)                        // jatatui Viewport.inline(height), below the prompt

enum TuiError(message: String, cause: Throwable | Null) extends Exception(message, cause):
  case NotATerminal(error: Throwable)                 extends TuiError("stdin/stdout is not a terminal", error)
  case NativeAccessDenied(error: Throwable)           extends TuiError("start the JVM with --enable-native-access=ALL-UNNAMED", error)
  case TerminalIO(error: Throwable)                   extends TuiError("terminal I/O failed", error)
  case AppFailed(where: String, error: Throwable)     extends TuiError(s"$where threw", error)       // "update on Key.Char(x)", "view", "fold of cpu"
  case SourceFailed(source: String, error: Throwable) extends TuiError(s"source $source failed", error)

/** Counters of one run; the performance contracts of 7.2 assert on them. */
final case class Stats(
    frames: Long, viewBuilds: Long, skippedSame: Long, skippedEqualView: Long,
    batches: Long, events: Long, folds: Long, droppedFolds: Long,
    sources: Int, tasks: Int, lastFrameMicros: Long, lastChangedCells: Int
) derives CanEqual
```

`TuiError` extends `Exception`, so the facades use it as their error type unchanged.

### 2.10 Test kit (`tui4s.testkit`)

```scala
package tui4s.testkit

/** A headless driver. The same fold, frame rules and painter as the runtime, synchronously on the caller's
  * thread, on a jatatui TestBackend. No fibers: sources and tasks are recorded, not run (use emit / fail).
  * The same API for Kyo, ZIO and Cats users. */
final class TuiTest[S] private:
  def state: S
  def quit: Boolean                          // true after Quit, Ctrl-C or an error
  def error: Option[TuiError]                // AppFailed / SourceFailed, recorded instead of thrown
  def frames: Long                           // = stats.frames (1 after construction: the first frame)
  def stats: Stats
  def screen: Screen                         // immutable copy of the last drawn frame
  def view: View                             // the view of the current state
  def viewTree: String                       // that view as an indented tree (for agents)
  def sources: Seq[String]                   // labels of the standing sources declared now
  def started: Seq[String]                   // labels of the one-shot tasks started so far, in order
  def cursor: Option[(Int, Int)]
  def lastTrace: String                      // the trace line of the last batch (5.16): why a key did or did not draw
  def trace: Seq[String]
  // input
  def press(keys: (Key | Char)*): this.type  // one batch per key, like a person typing
  def send(events: Event*): this.type        // all events in one batch
  def typeText(text: String): this.type      // one Key.Char per grapheme, one batch each
  def paste(text: String): this.type
  def click(x: Int, y: Int, button: Button = Button.Left): this.type
  def resize(width: Int, height: Int): this.type
  // background work
  def emit(label: String, value: Any): this.type     // a value from source/task `label`, folded by its CURRENT fold
  def fail(label: String, error: Throwable): this.type
  def fold(f: S => Next[S]): this.type               // any change, as a general source would send it

object TuiTest:
  def apply[S](app: App[S], width: Int = 80, height: Int = 24): TuiTest[S]
  /** Feeds the input events recorded in a trace file (5.16) back into `update`. Folds are not replayed. */
  def replay[S](app: App[S], trace: java.nio.file.Path, width: Int = 80, height: Int = 24): TuiTest[S]
  /** The real loop (fibers, supervisor; frame cap off) on a TestBackend, under Clock.withTimeControl and
    * Random.withSeed(0). Kyo-typed. */
  def live[S, A](app: App[S], width: Int = 80, height: Int = 24)(
      test: LiveTest[S] => A < (Async & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable])
  /** `live` for blocking frameworks (munit, JUnit); KyoApp.Unsafe.runAndBlock underneath. */
  def liveBlocking[S, A](app: App[S], width: Int = 80, height: Int = 24, timeout: Duration = 30.seconds)(
      test: LiveTest[S] => A < (Async & Abort[Throwable]))(using Frame): A

trait LiveTest[S]:
  def press(keys: (Key | Char)*)(using Frame): Unit < Async   // one batch per key, then settle
  def send(events: Event*)(using Frame): Unit < Async
  def typeText(text: String)(using Frame): Unit < Async
  /** Returns when every item put before the call is folded and drawn, and no one-shot task is running. */
  def settle(using Frame): Unit < Async
  /** Virtual time (6.2): fires every sleeper due within `d`, in deadline order, settling after each step.
    * `sleepers`: how many sleepers are parked when the app is idle; default = running every/poll/stream sources. */
  def advance(d: Duration, sleepers: Int = -1)(using Frame): Unit < Async
  def state(using Frame): S < Sync
  def screen(using Frame): Screen < Sync
  def stats(using Frame): Stats < Sync
  def sources(using Frame): Seq[String] < Sync
  def quit(using Frame): Boolean < Sync

/** One frame as text plus styles. Rows have trailing spaces trimmed. */
final case class Screen(rows: Vector[String], styles: Vector[Vector[Style]], cursor: Option[(Int, Int)]) derives CanEqual:
  def text: String                                   // rows joined with '\n'
  def row(y: Int): String
  def contains(s: String): Boolean
  def find(s: String): Option[(Int, Int)]
  def region(x: Int, y: Int, width: Int, height: Int): Vector[String]
  def styleAt(x: Int, y: Int): Style
  override def toString: String = text

/** None when equal; otherwise a row-by-row diff. Framework-neutral. */
def diffScreen(actual: Screen, expected: String): Option[String]
/** Throws java.lang.AssertionError with the diff (munit, zio-test, weaver). In kyo-test write
  * `assert(diffScreen(t.screen, expected).isEmpty)`: kyo-test counts only its own assertions. */
def assertScreen(actual: Screen, expected: String): Unit
```

`TuiTest` copies cells out of `TestBackend.buffer()` into `Screen` (the backend buffer is live and mutable,
VERIFIED `TestBackend.java`); it never exposes a jatatui `Buffer`.

(validated) Two rules for that copy, both found by the sample tests:

- **Wide graphemes.** jatatui keeps a wide grapheme in one cell and " " in its trailing cell(s) (its Wcwidth
  makes `✔` and `☐` two columns wide). `Screen` skips the trailing cells, as `Buffer.diff` does; otherwise the
  Todo row reads "✔  call bob" and `contains("✔ call bob")` fails.
- **Styles.** A drawn cell reports `bg = Reset` and `underlineColor = Reset` for colors nobody set, so a raw
  cell style never equals `Style.bold.fg(Color.Yellow)`. `Screen` reads `Reset` colors as unset, so
  `styleAt(x, y) == Style.bold.fg(Color.Yellow)` holds for a bold yellow cell.

(validated) The kit also needs a scripted `InputPort` for `spi.Headless` and the facade seams of 6.3:
`final class ScriptedInput(events: Seq[Event]) extends spi.InputPort` with `push(e)`, `ScriptedInput(keys*)`,
`ScriptedInput.empty`; `poll` really waits, like the JNI call. Test style: a chained `t.typeText(..).press(..)`
used as a statement warns under `-Wnonunit-statement` (a single `t.press(..)` does not), so tests write one call
per statement or use the result.

### 2.11 SPI for the facades (`tui4s.spi`)

```scala
package tui4s.spi

import kyo.*

/** The one port that every facade source lowers to. The core knows nothing about ZIO or Cats Effect. */
object Sources:
  /** A source whose body emits values; each value folds with the CURRENT fold of this identity (G7). */
  def make[S, A](label: String)(body: Emitter[A] => Unit < (Async & Abort[Throwable]))(
      fold: (S, A) => Next[S])(using Frame): Source[S]
  /** The same for chunks: one state change per emitted chunk. */
  def makeChunked[S, A](label: String)(body: Emitter[Seq[A]] => Unit < (Async & Abort[Throwable]))(
      fold: (S, A) => Next[S])(using Frame): Source[S]
  /** (validated: the facades must say what kind of body a source has. A one-shot `task` is completed by
    * `TuiTest.emit`, and the live kit counts periodic sleepers; `make` alone cannot tell.) */
  def makeTask[S, A](label: String)(body: Emitter[A] => Unit < (Async & Abort[Throwable]))(
      fold: (S, A) => Next[S])(using Frame): Source[S]
  def makePeriodic[S, A](label: String)(body: Emitter[A] => Unit < (Async & Abort[Throwable]))(
      fold: (S, A) => Next[S])(using Frame): Source[S]

abstract class Emitter[-A]:
  def apply(value: A)(using Frame): Unit < Async            // waits while the inbox is full
  def unsafe: Emitter.Unsafe[A]
object Emitter:
  abstract class Unsafe[-A]:
    /** Never blocks. `accepted` runs once the value is in the inbox, or when it is dropped (source stopped,
      * app ended). Used as ZIO.async / F.async_ callbacks: inbox backpressure reaches the foreign stream. */
    def put(value: A, accepted: () => Unit)(using AllowUnsafe): Unit

/** 5.14. Touches no Kyo class, so calling it never starts the Kyo scheduler. */
object SchedulerDefaults:
  val defaults: Seq[(String, String)]
  def applyIfUnset(): Unit

/** Input for the runtime and for headless runs. Called only on the input thread. */
trait InputPort:
  def poll(timeoutMillis: Int): Boolean    // true when an event is ready
  def read(): Event | Null                 // null: an event tui4s ignores (key release, media key)

/** The real loop on any jatatui Backend (TestBackend, a counting wrapper) and any InputPort: facade tests,
  * benchmarks. No raw mode, no alternate screen. */
object Headless:
  def run[S](app: App[S], backend: jatatui.core.backend.Backend, input: InputPort)(using Frame): S < (Async & Abort[TuiError])
  def start[S](app: App[S], backend: jatatui.core.backend.Backend, input: InputPort)(using Frame): TuiHandle[S] < (Async & Scope)
```

### 2.12 Names, imports and the Kyo-free rule

- **No clash with `import kyo.*`.** Kyo RC7 has top-level `Span`, `Gauge`, `Counter`, `Signal`, `Stream`,
  `Duration`, `Frame`, `Render`, `Check`, `Batch`, `Poll`, `Pipe`, `Sink`, `Field`, `Record` and more (VERIFIED:
  grep of `kyo-core`, `kyo-prelude`, `kyo-data`, `kyo-kernel`). tui4s exports none of these names: the span
  type is `Line.Span`, widget nodes are nested (`View.Gauge`), sample objects are `CounterApp`,
  `TodoApp`, `DashboardApp` (a user `object Counter` referenced from another file that does `import kyo.*`
  would resolve to `kyo.Counter`).
- **No clash with `import zio.*` / `cats.effect.*` / `fs2.*`.** tui4s exports no `Task`, `Chunk`, `Stream`,
  `Duration`, `Random`, `Clock`, `Ref`, `Resource`, `Scope`. The one-shot concept is the method `withTask` and
  the constructor `source.task`, never a type named `Task`.
- **Facade users import only the facade package** (`import tui4s.interop.zio.*` or `import tui4s.interop.cats.*`), never
  `import tui4s.*`: a wildcard import of `tui4s` also imports the sub-packages `zio` and `cats` (when those
  modules are on the classpath) and would shadow the root packages. Library code in `tui4s.*` packages
  writes `_root_.zio` / `_root_.cats`.
- **Kyo-free shared surface.** No `kyo.` type in any signature of the shared surface (2.2). The shared
  surface uses `Option`, `Seq`, `Vector`, `java.nio.file.Path`; Kyo-typed APIs use `Maybe`, `Chunk`,
  `kyo.Duration` as Kyo style asks.
- (validated) **No clash, checked.** A file with `import kyo.*` and `import tui4s.*` that uses every exported
  name once (`App`, `Quit`, `Next`, `Key`, ..., `Line.Span`, every dsl function and extension) compiles; `kyo.Span`
  stays usable. The ZIO and Cats samples compile with `import tui4s.interop.zio.*` / `tui4s.interop.cats.*` next to
  `import zio.*` / `cats.effect.IO` + `fs2.Stream`.
- (validated) **Library code and `kyo.internal`.** Inside tui4s, a file with `import kyo.*` must write
  `tui4s.internal.X`, never `internal.X` (Kyo has a package `kyo.internal`).

### 2.13 Semantics (rules an app, a test and an agent can rely on)

1. **One order.** Input events and folds are applied in inbox arrival order, on one fiber.
2. **Every event reaches `update`**, except Ctrl-C when `ctrlCQuits` (it quits first, even inside a text
   field, so a bug in `update` cannot trap the user).
3. **Level 1.** A batch whose final state is the same object as before (`eq`; boxed primitives and strings
   compare with `==`) causes no `view` call, no source diff and no frame.
4. **Level 2.** A new state whose view `==` the last drawn view, with no resize since, causes no frame.
5. **Frame cap.** At most one frame per `1/fps`. The first change after an idle period draws at once; later
   changes in a burst fold into the next frame.
6. **Sources.** `sources(state)` is called at start and after each batch whose final state is a new object.
   The result is diffed by label: new labels start, missing labels stop (fiber interrupted, signalled, not
   awaited), the others keep running and fold with the newest declared fold. Duplicate labels in one list
   get `#1`, `#2` suffixes.
7. **Standing tasks.** A `task` declared in `sources` that ended stays done while its label is declared; it
   runs again only after it disappears and comes back (the `useEffect` rule).
8. **Failures.** A failing source or task folds through `recover`; without `recover` the app ends with
   `TuiError.SourceFailed(label, e)`.
9. **One-shot tasks.** `state.withTask(t*)` starts the tasks after the batch that returned them, in order. A
   task without `.id` always runs. A task with `.id(name)` interrupts the running task with the same id;
   the older task's late value is dropped. A task fold may return `Quit` or `withTask` again. Tasks returned
   together with a quit do not start.
10. **Stopped means silent.** A value from a source after it stopped, or from a replaced task, is dropped
    (counted in `Stats.droppedFolds`).
11. **Quit.** No later item is folded and no frame is drawn. Sources and tasks are interrupted, the terminal
    is restored, and `Tui.run` returns the last state.
12. **User code throws.** An exception from `update`, `view`, `sources`, a fold or a keymap action ends the app
    with `TuiError.AppFailed(where, e)`, after the restore. Fatal errors are not caught.
13. **Mouse.** `Mouse.tags` lists the tags under the pointer in the last drawn frame, top-most first.
14. **Resize.** `update` gets `Resize`; the next frame is drawn even when the view is equal.
15. **Widget state** (list and table scroll offsets) lives while its node is painted at the same tree
    position (or under the same `.id`) in every frame; it is swept otherwise.
16. **Purity.** `init`, `view`, `update`, `sources`, folds and keymap actions run on the render fiber and
    must be pure and fast. A batch slower than one frame budget is marked `slow` in the trace.

---
## 3. The sample apps in final form

(validated: the folds are written `_.sample(_)`, not `_ sample _`: Scala 3.9 warns "Alphanumeric method sample
is not declared infix" for every infix fold, with the same inference otherwise.)

Each app is one complete file. Run: `sbt "examples/runMain examples.counter.CounterApp"` (the examples
build already forks, connects stdin and passes `--enable-native-access=ALL-UNNAMED` and
`--sun-misc-unsafe-memory-access=allow`). No sample contains a for-comprehension, a `Var`, a `Scope` or a
fiber. The model case classes are byte-identical across Kyo, ZIO and Cats (they use `Vector`, not `Chunk`).

### 3.1 Counter (Kyo)

```scala
package examples.counter

import tui4s.*

object CounterApp extends TuiApp[Int]:
  val keys: Keymap[Int] = Keymap(
    bind('+', "increment")(_ + 1),
    bind('-', "decrement")(_ - 1),
    bind('q', "quit")(_ => Quit)
  )

  def init: Int = 0

  def update(n: Int, event: Event): Next[Int] = keys(n, event)

  def view(n: Int): View =
    column(
      text("Count: ", n.toString.bold.yellow).centered.size(1),
      help(keys).centered.size(1)
    ).flex(Flex.Center).bordered(" Counter ")
```

At run time: `+` makes a new state, `view` runs, the view differs, one frame is drawn. `x` returns the same
`n` (level 1): no `view` call, no frame. The help line reads `+ increment · - decrement · q quit` and cannot
drift from the bindings. A plain `match` is equally valid when an app has no help bar:

```scala
  def update(n: Int, event: Event): Next[Int] = event match
    case Key.Char('+') => n + 1
    case Key.Char('-') => n - 1
    case Key.Char('q') => Quit
    case _             => n
```

### 3.2 Todo (Kyo)

Text input, a list with a selection, `space` toggles, `d` deletes, `tab` moves the focus. It also shows the
two kinds of work: a load at start (a standing `task` that the initial state declares) and a save on
Ctrl-S (a one-shot `withTask`).

```scala
package examples.todo

import java.io.IOException
import java.nio.file.{Files, Path}
import kyo.*
import scala.jdk.CollectionConverters.*
import tui4s.*

enum Pane derives CanEqual:
  case Input, Items

final case class Item(title: String, done: Boolean = false) derives CanEqual

final case class Todo(
    field: TextField = TextField.empty,
    items: Vector[Item] = Vector.empty,
    selected: Int = 0,
    focus: Focus[Pane] = Focus(Pane.Input, Pane.Items),
    ready: Boolean = false,
    status: String = "loading…"
) derives CanEqual:
  def left: Int = items.count(!_.done)

  def add: Todo =
    if field.isBlank then this
    else copy(field = TextField.empty, items = items :+ Item(field.text.trim), selected = items.size)

  def move(by: Int): Todo = copy(selected = (selected + by).max(0).min((items.size - 1).max(0)))

  def toggle: Todo =
    if items.isEmpty then this
    else copy(items = items.updated(selected, items(selected).copy(done = !items(selected).done)))

  def delete: Todo =
    if items.isEmpty then this
    else
      val rest = items.patch(selected, Nil, 1)
      copy(items = rest, selected = selected.min((rest.size - 1).max(0)))

  def nextPane: Todo = copy(focus = focus.next)

  def typed(event: Event): Todo =
    val f = field.edit(event)
    if f eq field then this else copy(field = f)      // same object when the key did nothing: no frame

  def loaded(r: Result[IOException, Vector[Item]]): Todo = r match
    case Result.Success(items) => copy(items = items, ready = true, status = s"${items.size} loaded")
    case failed                => copy(ready = true, status = s"could not load: $failed")

  def saved(r: Result[IOException, Unit]): Todo = r match
    case Result.Success(_) => copy(status = "saved")
    case failed            => copy(status = s"could not save: $failed")
end Todo

/** Plain Kyo code. It knows nothing about tui4s. */
object TodoStore:
  val file: Path = Path.of(java.lang.System.getProperty("user.home"), ".tui4s-todo.txt")

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

object TodoApp extends TuiApp[Todo]:
  val global: Keymap[Todo] = Keymap(
    bind(Key.Tab, "focus")(_.nextPane).or(Key.BackTab),
    bind(Key.Ctrl('s'), "save")(save),
    bind(Key.Esc, "quit")(_ => Quit)
  )
  // The pane keys first, so the help bar lists them first. (validated: with `global ++ Keymap(...)` the global
  // binds filled the 56-column help bar and "d delete" was cut off at 80 columns.)
  val inputKeys: Keymap[Todo] = Keymap(bind(Key.Enter, "add")(_.add)) ++ global
  val itemKeys: Keymap[Todo] = Keymap(
    bind(Key.Up, "up")(_.move(-1)).or('k'),
    bind(Key.Down, "down")(_.move(1)).or('j'),
    bind(' ', "toggle")(_.toggle),
    bind('d', "delete")(_.delete),
    bind('q', "quit")(_ => Quit)
  ) ++ global
  def keys(t: Todo): Keymap[Todo] = if t.focus.is(Pane.Input) then inputKeys else itemKeys

  def init: Todo = Todo()

  /** Load once at start: the initial state declares the task; its fold sets `ready`, which removes it. */
  override def sources(t: Todo): Seq[Source[Todo]] =
    if t.ready then Nil else Seq(source.task(Abort.run[IOException](TodoStore.load))(_.loaded(_)).id("load"))

  /** One-shot: the state says "saving…", the task writes the file and folds the result. With an id, a
    * second Ctrl-S replaces a save that is still running (latest wins). */
  private def save(t: Todo): Next[Todo] =
    t.copy(status = "saving…")
      .withTask(source.task(Abort.run[IOException](TodoStore.save(t.items)))(_.saved(_)).id("save"))

  def update(t: Todo, event: Event): Next[Todo] =
    val active = keys(t)
    event match
      case active(action)              => action(t)
      case e if t.focus.is(Pane.Input) => t.typed(e)      // the rest is text: 'q' and 'd' type letters here
      case _                           => t

  def view(t: Todo): View =
    val inInput = t.focus.is(Pane.Input)
    column(
      input(t.field, focused = inInput, placeholder = "What needs doing?")
        .bordered(" New ", focused = inInput)
        .size(3),
      list(t.items.map(itemLine), t.selected, focused = !inInput)
        .bordered(s" Todo · ${t.left} left ", focused = !inInput),
      row(help(keys(t)), text(t.status.dim).right.size(24)).size(1)
    )

  private def itemLine(item: Item): Line =
    if item.done then line("✔ ".green, item.title.dim.crossedOut)
    else line("☐ ", item.title)
end TodoApp
```

What the sample shows:

- **Focus routing** is a choice of keymap (G4). Typing `d` in the input inserts a `d`; in the list it
  deletes. The help bar shows exactly the keys that work now. Ctrl-C quits from anywhere (2.13 rule 2).
- **Load at start** is declarative: no `init` effect. A test checks it with
  `TuiTest(TodoApp).sources == Seq("load")` and `t.emit("load", Result.succeed(Vector.empty[Item]))` (6.3).
- **Save** is one line (G2). "Save, then quit" is the same with a fold that returns `Quit`:
  `t.withTask(source.task(Abort.run[IOException](TodoStore.save(t.items)))((_, _) => Quit))`.
- The scroll offset of the list is not in the model; the painter keeps a jatatui `ListState` for this tree
  position (5.7), so moving inside a long list moves the highlight, not the page.

### 3.3 Dashboard (Kyo)

Tabs (left/right), a gauge and a sparkline fed by a fiber that produces a value every 250 ms, and a log pane
fed by a stream.

```scala
package examples.dashboard

import kyo.*
import tui4s.*

final case class Dash(
    tab: Int = 0,
    cpu: Double = 0.0,
    history: Vector[Long] = Vector.empty,
    logs: Vector[String] = Vector.empty,
    paused: Boolean = false
) derives CanEqual:
  def sample(v: Double): Dash = copy(cpu = v, history = (history :+ (v * 100).toLong).takeRight(200))
  def log(line: String): Dash  = copy(logs = (logs :+ line).takeRight(500))
  def switchTab(by: Int): Dash = copy(tab = Math.floorMod(tab + by, Dash.tabs.size))
  def togglePause: Dash        = copy(paused = !paused)
object Dash:
  val tabs: Vector[String] = Vector("Metrics", "Requests")

object DashboardApp extends TuiApp[Dash]:
  /** Any Kyo stream. chunkSize = 1: with the default (4096) unfold buffers lines before they emit. */
  val requests: Stream[String, Async] =
    Stream.unfold(1, chunkSize = 1)(i =>
      Async.sleep(700.millis).andThen(Present((s"GET /api/items/$i 200", i + 1)))
    )

  val keys: Keymap[Dash] = Keymap(
    bind(Key.Left, "prev tab")(_.switchTab(-1)),
    bind(Key.Right, "next tab")(_.switchTab(1)),
    bind('p', "pause")(_.togglePause),
    bind('q', "quit")(_ => Quit)
  )

  def init: Dash = Dash()

  def update(d: Dash, event: Event): Next[Dash] = keys(d, event)

  /** A function of the state: pausing removes the poller, so its fiber stops; the log stream keeps running. */
  override def sources(d: Dash): Seq[Source[Dash]] =
    val logs = source.stream(requests)(_.log(_)).id("logs")
    val cpu  = source.poll(250.millis)(Random.nextDouble)(_.sample(_)).id("cpu")
    if d.paused then Seq(logs) else Seq(logs, cpu)

  def view(d: Dash): View =
    column(
      tabs(Dash.tabs, d.tab).bordered(" Dashboard ").size(3),
      if d.tab == 0 then metrics(d) else logPane(d.logs).bordered(" Requests "),
      help(keys).size(1)
    )

  private def metrics(d: Dash): View =
    row(
      column(
        gauge(d.cpu, f"${d.cpu * 100}%.0f%%").bordered(if d.paused then " CPU (paused) " else " CPU ").size(3),
        sparkline(d.history, max = Some(100L)).bordered(" History ")
      ).percent(60),
      logPane(d.logs).bordered(" Requests ")
    )
end DashboardApp
```

The poller written as "a fiber that produces a value every 250 ms" with the general constructor:

```scala
val cpu = source { dispatch =>
  Loop.forever(Random.nextDouble.map(v => dispatch(_.sample(v))).andThen(Async.sleep(250.millis)))
}.id("cpu")
```

What runs, and when:

| Thing | Runs when | Cost when idle |
| --- | --- | --- |
| input thread | always | one JNI `poll(25 ms)` at a time on a platform thread |
| `logs` fiber | always (declared in every state) | parked in `Async.sleep` |
| `cpu` fiber | only while `!paused` | parked in `Async.sleep` |
| render fiber | when the inbox has items | parked in `take` |
| frames | ~4/s from `cpu` + ~1.4/s from `logs` + one per key that changes the view | none while paused with no new logs on the screen |

### 3.4 ZIO users: Counter and Dashboard

```scala
package examples.ziocounter          // not examples.zio.counter: a package named zio would shadow the root zio

import tui4s.interop.zio.*

object CounterApp extends TuiApp[Int]:   // tui4s.interop.zio.TuiApp = ZIOAppDefault + App[Int]
  val keys: Keymap[Int] = Keymap(
    bind('+', "increment")(_ + 1),
    bind('-', "decrement")(_ - 1),
    bind('q', "quit")(_ => Quit)
  )

  def init: Int = 0

  def update(n: Int, event: Event): Next[Int] = keys(n, event)

  def view(n: Int): View =
    column(
      text("Count: ", n.toString.bold.yellow).centered.size(1),
      help(keys).centered.size(1)
    ).flex(Flex.Center).bordered(" Counter ")
```

```scala
package examples.ziodashboard

import tui4s.interop.zio.*
import zio.*
import zio.stream.ZStream

final case class Dash(
    tab: Int = 0,
    cpu: Double = 0.0,
    history: Vector[Long] = Vector.empty,
    logs: Vector[String] = Vector.empty,
    paused: Boolean = false
) derives CanEqual:
  def sample(v: Double): Dash = copy(cpu = v, history = (history :+ (v * 100).toLong).takeRight(200))
  def log(line: String): Dash  = copy(logs = (logs :+ line).takeRight(500))
  def switchTab(by: Int): Dash = copy(tab = Math.floorMod(tab + by, Dash.tabs.size))
  def togglePause: Dash        = copy(paused = !paused)
object Dash:
  val tabs: Vector[String] = Vector("Metrics", "Requests")

object DashboardApp extends TuiApp[Dash]:
  val requests: ZStream[Any, Nothing, String] =
    ZStream.iterate(1)(_ + 1).schedule(Schedule.spaced(700.millis)).map(i => s"GET /api/items/$i 200")

  val keys: Keymap[Dash] = Keymap(
    bind(Key.Left, "prev tab")(_.switchTab(-1)),
    bind(Key.Right, "next tab")(_.switchTab(1)),
    bind('p', "pause")(_.togglePause),
    bind('q', "quit")(_ => Quit)
  )

  def init: Dash = Dash()

  def update(d: Dash, event: Event): Next[Dash] = keys(d, event)

  override def sources(d: Dash): Seq[Source[Dash]] =
    val logs = source.stream(requests)(_.log(_)).id("logs")
    val cpu  = source.poll(250.millis)(Random.nextDouble)(_.sample(_)).id("cpu")   // zio.Random
    if d.paused then Seq(logs) else Seq(logs, cpu)

  def view(d: Dash): View =
    column(
      tabs(Dash.tabs, d.tab).bordered(" Dashboard ").size(3),
      if d.tab == 0 then metrics(d) else logPane(d.logs).bordered(" Requests "),
      help(keys).size(1)
    )

  private def metrics(d: Dash): View =
    row(
      column(
        gauge(d.cpu, f"${d.cpu * 100}%.0f%%").bordered(if d.paused then " CPU (paused) " else " CPU ").size(3),
        sparkline(d.history, max = Some(100L)).bordered(" History ")
      ).percent(60),
      logPane(d.logs).bordered(" Requests ")
    )
end DashboardApp
```

No Kyo type appears: `ZStream`, `Schedule`, `Random` and `700.millis` are ZIO's. (validated: both ZIO samples
compile with only these imports, and the Dashboard runs headless with the facade; the ZIO 2.1.26 names
`ZStream.iterate`, `.schedule`, `Schedule.spaced`, `Random.nextDouble` are right: VERIFIED.)

### 3.5 Cats Effect users: Counter and Dashboard

```scala
package examples.catscounter

import tui4s.interop.cats.*

object CounterApp extends TuiApp[Int]:   // tui4s.interop.cats.TuiApp = IOApp.Simple + App[Int]
  val keys: Keymap[Int] = Keymap(
    bind('+', "increment")(_ + 1),
    bind('-', "decrement")(_ - 1),
    bind('q', "quit")(_ => Quit)
  )

  def init: Int = 0

  def update(n: Int, event: Event): Next[Int] = keys(n, event)

  def view(n: Int): View =
    column(
      text("Count: ", n.toString.bold.yellow).centered.size(1),
      help(keys).centered.size(1)
    ).flex(Flex.Center).bordered(" Counter ")
```

```scala
package examples.catsdashboard

import cats.effect.IO
import fs2.Stream
import scala.concurrent.duration.*
import tui4s.interop.cats.*

final case class Dash(
    tab: Int = 0,
    cpu: Double = 0.0,
    history: Vector[Long] = Vector.empty,
    logs: Vector[String] = Vector.empty,
    paused: Boolean = false
) derives CanEqual:
  def sample(v: Double): Dash = copy(cpu = v, history = (history :+ (v * 100).toLong).takeRight(200))
  def log(line: String): Dash  = copy(logs = (logs :+ line).takeRight(500))
  def switchTab(by: Int): Dash = copy(tab = Math.floorMod(tab + by, Dash.tabs.size))
  def togglePause: Dash        = copy(paused = !paused)
object Dash:
  val tabs: Vector[String] = Vector("Metrics", "Requests")

object DashboardApp extends TuiApp[Dash]:
  val requests: Stream[IO, String] =
    Stream.iterate(1)(_ + 1).covary[IO].metered(700.millis).map(i => s"GET /api/items/$i 200")

  val cpuLoad: IO[Double] = IO(math.random())

  val keys: Keymap[Dash] = Keymap(
    bind(Key.Left, "prev tab")(_.switchTab(-1)),
    bind(Key.Right, "next tab")(_.switchTab(1)),
    bind('p', "pause")(_.togglePause),
    bind('q', "quit")(_ => Quit)
  )

  def init: Dash = Dash()

  def update(d: Dash, event: Event): Next[Dash] = keys(d, event)

  override def sources(d: Dash): Seq[Source[Dash]] =
    val logs = source.stream(requests)(_.log(_)).id("logs")
    val cpu  = source.poll(250.millis)(cpuLoad)(_.sample(_)).id("cpu")
    if d.paused then Seq(logs) else Seq(logs, cpu)

  def view(d: Dash): View =
    column(
      tabs(Dash.tabs, d.tab).bordered(" Dashboard ").size(3),
      if d.tab == 0 then metrics(d) else logPane(d.logs).bordered(" Requests "),
      help(keys).size(1)
    )

  private def metrics(d: Dash): View =
    row(
      column(
        gauge(d.cpu, f"${d.cpu * 100}%.0f%%").bordered(if d.paused then " CPU (paused) " else " CPU ").size(3),
        sparkline(d.history, max = Some(100L)).bordered(" History ")
      ).percent(60),
      logPane(d.logs).bordered(" Requests ")
    )
end DashboardApp
```

(validated: both Cats samples compile with only these imports and the Dashboard runs headless; the fs2 3.14
names `Stream.iterate`, `covary`, `metered` are right: VERIFIED.)

### 3.6 What changes between the three ecosystems

| Part of the app | Kyo | ZIO | Cats Effect |
| --- | --- | --- | --- |
| import | `tui4s.*` | `tui4s.interop.zio.*` | `tui4s.interop.cats.*` |
| model, `init`, `update`, `view`, keymaps | identical | identical | identical |
| `sources` shape | `source.poll / task / stream / every` | same | same |
| effect in a source or task | `A < (Async & Abort[Throwable])` | `ZIO[Any, Throwable, A]` | `F[A]` with `Async[F]` |
| stream in a source | `kyo.Stream[A, Async]` | `ZStream[Any, Throwable, A]` | `fs2.Stream[F, A]` |
| durations | `kyo.Duration` | `zio.Duration` (= `java.time.Duration`) | `FiniteDuration` |
| main class | `TuiApp` (own `main`) | `TuiApp` = `ZIOAppDefault` | `TuiApp` = `IOApp.Simple` |
| run inside a program | `Tui.run(app): S < (Async & Abort[TuiError])` | `Tui.run(app): IO[TuiError, S]` | `Tui.run[F](app): F[S]` |
| tests | `tui4s.testkit.TuiTest` (synchronous, the same for all three) | same | same |

---
## 4. The facades: tui4s-zio and tui4s-cats

### 4.1 Principle

The app logic (`App[S]`, `Next`, events, keymaps, the view) has no effect type, so the facades do not wrap it.
Each facade adds exactly three things [src: elm, free]:

1. `Source` constructors that take its own effect and stream types,
2. a runner `Tui.run` (and `Tui.start` / `Tui.resource`) that returns its own effect type,
3. a main class `TuiApp`.

Every facade source lowers to the one core port `tui4s.spi.Sources.make` / `makeChunked` / `makeTask` /
`makePeriodic` (2.11, validated). Key
handling is pure and never crosses a runtime boundary; only sources and tasks run foreign effects, each with
one bridge call for its whole life. The core module has no dependency on ZIO or Cats Effect.

**Re-exports.** Each facade has one file (`modules/<m>/src/main/scala/tui4s/<m>/exports.scala`) that makes
the shared surface (2.2) visible under the facade package:

```scala
package tui4s.interop.zio                                 // tui4s.interop.cats: the same file

export tui4s.dsl.*                                // view constructors, styling extensions, withTask
type App[S] = tui4s.App[S];              val App: tui4s.App.type = tui4s.App
type Next[S] = tui4s.Next[S];            val Next: tui4s.Next.type = tui4s.Next
val Quit: tui4s.Quit.type = tui4s.Quit
type Event = tui4s.Event
type Key = tui4s.Key;                    val Key: tui4s.Key.type = tui4s.Key
type Mods = tui4s.Mods;                  val Mods: tui4s.Mods.type = tui4s.Mods
// ... the same two lines for Mouse, MouseKind, Button, Resize, Paste, FocusGained, FocusLost, Keymap, Bind,
//     Focus, TextField, View, Line, Style, Color, Flex, Align, Border, BorderKind, Constraint, Theme,
//     TuiOptions, Viewport, TuiError, Stats, CallSite
type Source[S] = tui4s.Source[S]                  // the companion is the facade's own `object Source`
```

(validated: Scala 3.9 accepts an export from a package, so the facades use the short form
`export tui4s.{App, Next, Quit, Event, Key, Mods, Mouse, MouseKind, Button, Resize, Paste, FocusGained, FocusLost}`,
`export tui4s.{Keymap, Bind, Focus, TextField, View, Line, Style, Color, Flex, Align, Border, BorderKind, Constraint}`,
`export tui4s.{Theme, TuiOptions, Viewport, TuiError, Stats, CallSite}` plus `type Source[S] = tui4s.Source[S]`;
`Source` itself is not exported, because the facade has its own `object Source`. The alias form above is not
needed.) Patterns such as `Key.Char('q')`, `Quit` and
`Resize(w, h)` work through stable `val`s. A compile test (6.5, "facade parity") compiles the one Counter
text against the three imports.

**Lift trap.** Facade users never see a Kyo-typed parameter: the facade `Source` object shadows the core
companion, and the facade `TuiApp` has its own `source`. So a ZIO or IO value can never be lifted into a
Kyo `A < S` by accident [src: components V3, signals]. Kyo users are guarded by `NotForeign` (2.8).

**Clean signatures.** Facade sources take `(using CallSite)` (a tui4s type, filled at the call site) for
their identity, and summon `kyo.Frame` inside their own code; no Kyo type appears in any facade signature
[src: elm].

### 4.2 tui4s-zio API

Dependencies (already in `modules/zio/build.sbt`): `io.getkyo::kyo-zio` 1.0.0-RC7, `dev.zio::zio` and
`zio-streams` 2.1.26.

```scala
package tui4s.interop.zio

import _root_.zio.{Duration, IO, Runtime, Scope, Trace, UIO, ZIO, ZIOAppArgs, ZIOAppDefault, ZLayer}
import _root_.zio.stream.ZStream

object Source:
  def every[S](period: Duration)(f: S => Next[S])(using CallSite): Source[S]
  /** Runs `task` now, then again `period` after each result (ZIO clock), and folds each result. */
  def poll[S, A](period: Duration)(task: ZIO[Any, Throwable, A])(f: (S, A) => Next[S])(using CallSite, Trace): Source[S]
  def task[S, A](task: ZIO[Any, Throwable, A])(f: (S, A) => Next[S])(using CallSite, Trace): Source[S]
  /** One state change per chunk. Inbox backpressure reaches the stream. */
  def stream[S, A](stream: ZStream[Any, Throwable, A])(f: (S, A) => Next[S])(using CallSite, Trace): Source[S]
  final class For[S] private[zio] ():
    def every(period: Duration)(f: S => Next[S])(using CallSite): Source[S]
    def poll[A](period: Duration)(task: ZIO[Any, Throwable, A])(f: (S, A) => Next[S])(using CallSite, Trace): Source[S]
    def task[A](task: ZIO[Any, Throwable, A])(f: (S, A) => Next[S])(using CallSite, Trace): Source[S]
    def stream[A](stream: ZStream[Any, Throwable, A])(f: (S, A) => Next[S])(using CallSite, Trace): Source[S]
  object For:
    def apply[S]: For[S]

object Tui:
  /** Runs the app in the real terminal on the caller's runtime. Interruption restores the terminal before
    * the interrupt completes. */
  def run[S](app: App[S])(using Trace): IO[TuiError, S]
  /** The app on its own fiber; the scope's release stops it and waits for the restore. */
  def start[S](app: App[S])(using Trace): ZIO[Scope, Nothing, TuiHandle[S]]

final class TuiHandle[S] private[zio]:          // (validated: each method takes (using Trace), ZIO style)
  def update(f: S => Next[S])(using Trace): UIO[Unit]
  def send(event: Event)(using Trace): UIO[Unit]
  def states(using Trace): ZStream[Any, Nothing, S] // ZStream.repeatZIO(core.states.next); no Tag[S] is needed
  def stats(using Trace): UIO[Stats]
  def await(using Trace): IO[TuiError, S]
  def stop(using Trace): UIO[Unit]

abstract class TuiApp[S] extends ZIOAppDefault, tui4s.App[S]:
  // constructor body, first statement: tui4s.spi.SchedulerDefaults.applyIfUnset()
  /** ZIO's default console logger writes to stdout and would draw over the UI. */
  override val bootstrap: ZLayer[ZIOAppArgs, Any, Any] = Runtime.removeDefaultLoggers
  protected final def source: Source.For[S] = Source.For[S]
  final def run: ZIO[ZIOAppArgs & Scope, Any, Any] = Tui.run(this).flatMap(onExit)
  protected def onExit(state: S): ZIO[Any, Throwable, Unit] = ZIO.unit
```

How it maps onto the core:

| Facade call | Implementation | Notes |
| --- | --- | --- |
| `Tui.run(app)` | `ZIO.succeed(SchedulerDefaults.applyIfUnset()) *> AmbientZIOs.run(tui4s.Tui.run(app))` | `AmbientZIOs.run` captures the caller's `zio.Runtime` (`ZIO.runtime`) in an inheritable Kyo `Local` (F12). ZIO interruption interrupts the Kyo fiber **and waits** (F11), so the synchronous release has run when the ZIO fiber reports interrupted. `update`/`view` throws arrive as `TuiError.AppFailed` (a typed failure, not a defect). |
| `Source.task(z)(f)` | `Sources.make(label)(emit => AmbientZIOs.get(z.flatMap(a => put(emit, a))))(f)` | the ZIO runs on the caller's runtime with its FiberRefs and executor, also from the forked source fiber (F12, VERIFIED FiberRef 7 seen from a forked Kyo fiber) |
| `Source.poll(p)(z)(f)` | `Sources.make(label)(emit => AmbientZIOs.get((z.flatMap(a => put(emit, a)) *> ZIO.sleep(p)).forever))(f)` | one bridge call per source life; `ZIO.sleep` uses the caller's clock, so zio-test's `TestClock` drives it (UNVERIFIED) |
| `Source.stream(s)(f)` | `Sources.makeChunked(label)(emit => AmbientZIOs.get(s.runForeachChunk(c => put(emit, c))))(f)` | never `ZStreams.get`: it runs on `Runtime.default` (F12) |
| `Source.every(p)(f)` | `Sources.makePeriodic(callSite.show)(emit => Loop.forever(Async.sleep(kyo.Duration.fromJava(p)).andThen(emit(()))))((s, _) => f(s))` | no ZIO involved. (validated: not the core `Source.every`, whose label would be the facade's own source position, not the user's call site) |
| `put(emit, a)` | `ZIO.async[Any, Nothing, Unit](cb => emit.unsafe.put(a, () => cb(ZIO.unit)))` | inbox backpressure reaches the ZIO / ZStream |
| a source stops | Kyo interrupt of the source fiber -> the ZIO fiber is interrupted (signalled, not awaited, F11) | its late value is dropped (2.13 rule 10) |
| `Tui.start(app)` | `ZIO.acquireRelease(AmbientZIOs.run(Runner.startUnscoped(app)))(h => AmbientZIOs.run(h.stop).orDie)` (validated: core needs `private[tui4s] Runner.startUnscoped(app, control): TuiHandle[S] < Sync`; `Runner.start` = it + `Scope.ensure(stop)`) | the release is ZIO's own bracket, so it is awaited; never a core `Scope.ensure` (F8) [src: fit judge] |

**AmbientZIOs** (vendored from the verified prototype `.work/scratch/kyo-rc7/zio/ZioBridge.scala`, about 50
lines, into `modules/zio/src/main/scala/tui4s/zio/internal/AmbientZIOs.scala`): an inheritable
`kyo.Local[Maybe[zio.Runtime[Any]]]`; `run(v)` = `ZIO.runtime[Any].flatMap(rt => ZIOs.run(local.let(Present(rt))(v)))`;
`get(z)` reads the Local and forks `z` on that runtime (`rt.unsafe.fork`), falling back to
`Runtime.default` outside `run`; the cancel goes through a `Sync.ensure` region (defensive, F9). It uses
kyo-zio RC7 (`ZIOs.run`, `ZIOs.toResult`). Its header says that `getOn` is adapted from kyo-zio's `ZIOs.get`,
Copyright 2015 Flavio Brasil and the Kyo contributors, Apache-2.0, with the list of changes (caller runtime,
`Sync.ensure` cancel region).

**Logging.** `TuiApp` removes ZIO's default loggers. A user who overrides `bootstrap` keeps
`Runtime.removeDefaultLoggers` or adds a file logger. A user who calls `Tui.run` from their own `ZIOApp` gets
a scaladoc note: wrap it in `ZIO.scoped(Runtime.removeDefaultLoggers.build *> Tui.run(app))` or log to a
file. tui4s's `System.out`/`err` capture (5.10) catches the rest while the UI is up.

### 4.3 tui4s-cats API

Dependencies (already in `modules/cats/build.sbt`): `org.typelevel::cats-effect` 3.7.1, `co.fs2::fs2-core`
3.14.0; no kyo-cats (RC5 is binary-incompatible with RC7, F2).

```scala
package tui4s.interop.cats

import _root_.cats.effect.{IO, IOApp, Resource}
import _root_.cats.effect.kernel.Async
import _root_.cats.effect.unsafe.IORuntimeConfig
import scala.concurrent.duration.FiniteDuration

object Source:
  def every[S](period: FiniteDuration)(f: S => Next[S])(using CallSite): Source[S]
  def poll[F[_]: Async, S, A](period: FiniteDuration)(task: F[A])(f: (S, A) => Next[S])(using CallSite): Source[S]
  def task[F[_]: Async, S, A](task: F[A])(f: (S, A) => Next[S])(using CallSite): Source[S]
  def stream[F[_]: Async, S, A](stream: fs2.Stream[F, A])(f: (S, A) => Next[S])(using CallSite): Source[S]
  final class For[F[_]: Async, S] private[cats] ():  // (validated: holds its Async[F], so For.apply allocates; harmless)
    def every(period: FiniteDuration)(f: S => Next[S])(using CallSite): Source[S]
    def poll[A](period: FiniteDuration)(task: F[A])(f: (S, A) => Next[S])(using CallSite): Source[S]
    def task[A](task: F[A])(f: (S, A) => Next[S])(using CallSite): Source[S]
    def stream[A](stream: fs2.Stream[F, A])(f: (S, A) => Next[S])(using CallSite): Source[S]
  object For:
    def apply[F[_]: Async, S]: For[F, S]

object Tui:
  /** Fails with TuiError. Cancel restores the terminal before the cancel completes (cancel-and-await). */
  def run[F[_]: Async, S](app: App[S]): F[S]
  /** The app as a resource; the release stops it and waits for the restore (CE's own bracket). */
  def resource[F[_]: Async, S](app: App[S]): Resource[F, TuiHandle[F, S]]

final class TuiHandle[F[_], S] private[cats]:
  def update(f: S => Next[S]): F[Unit]
  def send(event: Event): F[Unit]
  def states: fs2.Stream[F, S]
  def stats: F[Stats]
  def await: F[S]
  def stop: F[Unit]

abstract class TuiApp[S] extends IOApp.Simple, tui4s.App[S]:
  // constructor body, first statement: tui4s.spi.SchedulerDefaults.applyIfUnset()
  /** CE's CPU-starvation checker prints to stderr and would draw over the UI: off. */
  override protected def runtimeConfig: IORuntimeConfig = /* super.runtimeConfig with the starvation check off */
  protected final def source: Source.For[IO, S] = Source.For[IO, S]
  final def run: IO[Unit] = Tui.run[IO, S](this).flatMap(onExit)
  protected def onExit(state: S): IO[Unit] = IO.unit
```

How it maps onto the core:

| Facade call | Implementation | Notes |
| --- | --- | --- |
| `Tui.run[F](app)` | `Async[F].delay(applyIfUnset()) *> CatsBridge.runWithDispatcher[F, S] { d ?=> AmbientDispatcher.let(Present(d))(tui4s.Tui.run(app)) }` | `runWithDispatcher` creates `Dispatcher.parallel[F](await = false)`: F10, `await = true` can lose a cancel in CE 3.7.1 (3 hangs in 10 rounds; 0 in 1000 with `false`). `Abort[TuiError]` fits the bridge's `Abort[Throwable]` (`Abort[-E]`, VERIFIED). Cancel -> Kyo interrupt, **cancel-and-await** (F8). |
| `Source.task(fa)(f)` | `Sources.make(label)(emit => AmbientDispatcher.use[F](d => CatsBridge.getF(fa.flatMap(a => put(emit, a)))(using d)))(f)` | the F runs on the caller's runtime (F8 test "runWithDispatcher + getF") |
| `Source.poll(p)(fa)(f)` | `getF((fa.flatMap(a => put(emit, a)) >> Async[F].sleep(p)).foreverM)` | one bridge call per source life; F's own clock |
| `Source.stream(s)(f)` | `Sources.makeChunked(label)(emit => ... getF(s.chunks.evalMap(c => put(emit, c.toVector)).compile.drain))(f)` | |
| `put(emit, a)` | `Async[F].async_[Unit](cb => emit.unsafe.put(a, () => cb(Right(()))))` | backpressure reaches fs2 |
| a source stops | Kyo interrupt -> the F is cancelled (signalled, not awaited, F8) | |
| `Tui.resource(app)` | `Resource.make(runF(Runner.startUnscoped(app)))(h => runF(h.stop))` with the ambient Dispatcher | the release is CE's own bracket, never a core `Scope.ensure` [src: signals + fit judge] |

`AmbientDispatcher` is an inheritable `kyo.Local[Maybe[Dispatcher[?]]]` (the AmbientZIOs pattern). Rule:
**one `F` per app**. A source built for another `F` than the runner's is a run-time error, not a compile
error (documented; `TuiApp` fixes `F = IO`, so it cannot happen there).

**The vendored bridge.** Copy `.work/scratch/kyo-rc7/CatsBridge.scala` (13 munit tests pass, F8) to
`modules/cats/src/main/scala/tui4s/cats/internal/CatsBridge.scala`, package `tui4s.interop.cats.internal`,
`private[tui4s]`. Keep its header verbatim; it carries the Apache-2.0 attribution required by section 4 of
the license (F5):

```
Derived from kyo-cats 1.0.0-RC5, file kyo-cats/shared/src/main/scala/kyo/Cats.scala
(https://github.com/getkyo/kyo/blob/v1.0.0-RC5/kyo-cats/shared/src/main/scala/kyo/Cats.scala).
Original work: Copyright 2015 Flavio Brasil and the Kyo contributors, licensed under the Apache License,
Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0). The upstream file has no per-file header; the
copyright line is the one in the upstream LICENSE.txt. Kyo has no NOTICE file.

Changes from the original (Apache-2.0 section 4(b) "prominent notices"):
 - moved out of package `kyo`;
 - `get` takes the IORuntime as a using-parameter instead of the global runtime;
 - `get` maps a failed IO to a typed `Abort[Throwable]` failure, and a self-cancelled IO to `Interrupted`;
 - `run` starts the Kyo fiber inside `IO.uncancelable` and registers cancel-and-await with `onCancel`;
 - added F[_]-polymorphic versions (`runF`, `getF` via `Dispatcher[F]`, `runWithDispatcher`);
 - `runWithDispatcher` uses Dispatcher.parallel(await = false) (cats-effect 3.7.1 can lose a cancel otherwise).

Copyright 2026 the tui4s authors. Licensed under the Apache License, Version 2.0.
```

(The last change line is new: it records F10 in the header.) tui4s is Apache-2.0 itself, so the repository
`LICENSE` already gives recipients the license text; Kyo has no NOTICE file to carry (F5). The 13 tests move
to `modules/cats/src/test/scala/tui4s/cats/internal/CatsBridgeSuite.scala`.

**Starvation checker.** `override protected def runtimeConfig = super.runtimeConfig.copy(cpuStarvationCheckInitialDelay
= Duration.Inf)`. (validated: CE 3.7.1 `IORuntimeConfig` has a public `copy` with this `Duration` field, and
`CpuStarvationCheck.run` ends with `.delayBy(cpuStarvationCheckInitialDelay)`, so `Inf` means "never starts";
VERIFIED in the source and by a test on a `TuiApp` subclass. No system-property fallback is needed.)

### 4.4 Facade test kits

ZIO and Cats users test with the same synchronous `tui4s.testkit.TuiTest` (`import tui4s.testkit.*`; this
import does not shadow `zio` or `cats`). For the live level, each facade ships a small kit with its own
effect type and the real clock (phase 2 of the plan, step Z3 / K3):

```scala
package tui4s.interop.zio.testkit                          // tui4s.interop.cats.testkit: the same with F[_]: Async

object LiveTuiTest:
  def apply[S, A](app: App[S], width: Int = 80, height: Int = 24)(test: LiveTest[S] => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A]
trait LiveTest[S]:
  def press(keys: (Key | Char)*): UIO[Unit]       // one batch per key, then settle
  def send(events: Event*): UIO[Unit]
  def settle: UIO[Unit]
  def state: UIO[S]
  def screen: UIO[Screen]
  def stats: UIO[Stats]
  def sources: UIO[Seq[String]]
```

It runs `spi.Headless` on a `TestBackend` through the facade bridge. Facade sources sleep on the caller's
clock, so a zio-test `TestClock.adjust` should drive `poll` (UNVERIFIED); Cats `TestControl` with Kyo
threads is not supported (open in 1.5).

### 4.5 What the facades cost, and what they cannot hide

- ZIO and Cats apps also run the Kyo scheduler (a second runtime): its idle cost (4-8% of a core with the
  defaults of 5.14) is paid even though the user did not choose Kyo.
- `CallSite` (and ZIO's `Trace`) appear in IDE signatures; users never write them.
- The ZIO facade depends on kyo-zio RC7; the Cats facade carries its own bridge. Both are pinned to the Kyo
  version in `project/Versions.scala`.
- One `F` per app in Cats (above).

---
## 5. Runtime specification

### 5.1 Who runs where, and who owns what

```
 tui4s-input  (platform daemon thread; started by the acquire, stopped and joined by the release)
   loop { if poll(25 ms): read every queued event (max 512, poll(0) between), map to Event,
          inbox.putBatchFiber + wait (backpressure) }                                   ──┐ Event
 source / task fibers (Kyo, Fiber.initUnscoped, owned by the Supervisor)                  │
   emit(value) = inbox.put(Fold(token, value))  (waits while full)                       ──┤ Fold
   on completion: inbox.putFiber(Done(token, result))                                    ──┤ Done
 TuiHandle / LiveTest: Fold(handleToken, f), Event, Probe                                ──┤
                                                                                          ▼
                       inbox: Channel[Item], MPSC, capacity TuiOptions.inboxCapacity (1024)
                                                                                          │
 render fiber = the fiber that called Tui.run                                             ▼
   take (park) -> drain -> fold the batch (one Sync step, plain while loop) -> diff sources, start
   tasks -> frame slot (sleep the remainder, drain, fold) -> view -> equal to the last view? -> draw
```

| Object | Owner | Rule |
| --- | --- | --- |
| jatatui `Terminal`, its two `Buffer`s, `Frame` | render fiber | touched only inside one `Sync.Unsafe.defer` per frame; `Frame` never escapes the draw callback |
| `Painter` (widget-state slots, hit map, cursor, last view, resize flag) | render fiber | plain mutable fields, no locks |
| `Engine` (state, counters, pending tasks) and `Supervisor` maps | render fiber | changed only between and inside batches |
| `Rect[]` from `Layout.split` | read-only during one paint | jatatui's shared cache entry: never stored, never written (jatatui.md 2.4) |
| crossterm reader (`poll` / `read`) | input thread | the only caller |
| crossterm writer (`enqueue` / `execute` / `flush`) | render fiber, then the release | the release runs after the input thread stopped and after the loop returned |
| `System.out` / `System.err` replacement | acquire and release | JVM-global; replaced only while the UI is up |
| the state `S` | render fiber | immutable; sources never see it, they send values that the render fiber folds |

All drawing happens on one fiber, as jatatui requires (jatatui.md 5.2). A Kyo fiber can move between worker
threads, but its steps are sequential and the scheduler hand-off orders memory, so access is single-owner.
A `Sync` block runs to completion without parking, so an interrupt never lands inside `terminal.draw`.

### 5.2 Inbox items

```scala
private[tui4s] type Item = Event | Fold | Done | Probe | Failed
private[tui4s] final class Fold(val token: Long, val value: Any)    // a value from a source/task, or a function (Dispatch, TuiHandle)
private[tui4s] final class Done(val token: Long, val result: Result[Throwable, Unit])
private[tui4s] final class Probe(val promise: Promise.Unsafe[Stats, Any])   // settle / stop / stats; completed after the batch
private[tui4s] final class Failed(val error: TuiError)                // the input thread failed
```

Input events need no wrapper (a type test separates them). Values cost one small `Fold` object.

### 5.3 The input thread

The probe's `InputMode.Thread` shape (VERIFIED `loop.scala`, ~7.0 us/event batched): a dedicated platform
thread, so no Kyo worker is ever parked inside JNI (the blocking monitor never reacts), and the synchronous
release can stop and join it [src: elm, components, free]. `read()` is called only after `poll` returned
true, so the thread never blocks in JNI after `stop()` for longer than one poll interval.

```scala
private[tui4s] final class InputThread(port: InputPort, inbox: Channel[Item], pollMillis: Int)
    extends java.lang.Thread("tui4s-input"):
  setDaemon(true)
  @volatile private var running = true

  override def run(): Unit =
    import AllowUnsafe.embrace.danger
    try
      while running do
        if port.poll(pollMillis) then
          val batch = scala.collection.mutable.ArrayBuffer.empty[Item]
          var n = 0
          while running && n < 512 && (n == 0 || port.poll(0)) do     // drain what is already queued
            val e = port.read()
            if e != null then batch += e                               // key Release etc. map to null
            n += 1
          if batch.nonEmpty then
            val accepted = java.util.concurrent.CountDownLatch(1)      // backpressure, as in loop.scala
            inbox.unsafe.putBatchFiber(batch.toSeq).onComplete(_ => accepted.countDown())
            accepted.await()                                           // also released when the release closes the inbox
    catch case scala.util.control.NonFatal(e) =>
      discard(inbox.unsafe.offer(Failed(TuiError.TerminalIO(e))))

  def stopAndJoin(timeoutMillis: Long): Boolean =
    running = false
    join(timeoutMillis)
    !isAlive
```

`CrosstermInput(jni)` implements `InputPort`: `poll(ms)` is `jni.poll(tui.crossterm.Duration(0, ms * 1_000_000))`
(the record takes seconds and nanos, VERIFIED), `read()` is `EventMapping(jni.read())` (2.4). Costs: one JNI
`poll` per 25 ms while idle; quit waits at most one poll interval (~30 ms expected with 25 ms, UNVERIFIED;
the probe measured ~60 ms with 50 ms).

### 5.4 The render loop

Idiomatic Kyo (`map`, `andThen`, `Loop`, no for-comprehension, F1). The shape is the verified probe (take ->
drain -> fold -> draw if changed; 10k queued keys at ~6.7 us/event and 3 frames, `loop1.log`).

```scala
private[tui4s] final class RenderLoop[S](engine: Engine[S], inbox: Channel[Item], frameNanos: Long):

  def run(using Frame): S < (Async & Abort[TuiError]) =
    engine.start.andThen(                                        // sources(init), first frame
      Loop(engine.now()) { drawnAt =>
        takeBatch.map(engine.fold).map {                         // one Sync.Unsafe.defer, plain while loop (5.5)
          case Step.Quit     => Loop.done(engine.state)
          case Step.Same     => Loop.continue(drawnAt)           // level 1: nothing to do
          case Step.WorkOnly => engine.syncWork.andThen(Loop.continue(drawnAt))   // same state, new tasks
          case Step.Changed  => afterChange(drawnAt)
        }
      }
    )

  /** Park until something arrives, then take everything that is queued (coalescing). */
  private def takeBatch(using Frame): Chunk[Item] < (Async & Abort[TuiError]) =
    inbox.take.map(first => inbox.drain.map(rest => Chunk(first).concat(rest))).handle(closedIsBug)

  private def afterChange(drawnAt: Long)(using Frame) =
    engine.syncWork.andThen {                                    // sources(state) diff once per batch + new tasks
      val wait = drawnAt + frameNanos - engine.now()
      if wait <= 0 then draw(drawnAt)                            // first change after idle: no added latency
      else
        Async.sleep(Duration.fromNanos(wait))                    // sleep the remainder of the frame budget,
          .andThen(inbox.drain.handle(closedIsBug))              // then drain. NEVER Async.timeout around take:
          .map(engine.fold)                                      // it can drop a delivered item in RC7 (1.4)
          .map {
            case Step.Quit => Loop.done(engine.state)
            case _         => engine.syncWork.andThen(draw(drawnAt))   // the earlier change still needs its frame
          }
    }

  /** Builds the view, applies level 2, draws at most once. Returns the new drawnAt (unchanged when skipped). */
  private def draw(drawnAt: Long)(using Frame) =
    engine.draw(drawnAt).map(t => Loop.continue(t))
```

`engine.now()` is `System.nanoTime()`, not Kyo's `Clock`; live tests set `fps = 0` (`frameNanos = 0`), so
`wait <= 0` always. (validated: the pacing sleep itself is an `Async.sleep`, which IS virtual under
`Clock.withTimeControl`: with `fps > 0`, a change within one frame budget of the last frame is never drawn
until virtual time moves (the Counter with its default `fps = 60` drew no second frame under time control).
So `TuiTest.live` must force `fps = 0` whatever the app's options say, and a Kyo user who runs `Tui.run` or
`spi.Headless` inside their own `Clock.withTimeControl` must do the same.)

### 5.5 Folding a batch

`Engine.fold(batch)` runs the whole batch in one `Sync.Unsafe.defer` as a plain `while` loop: no Kyo step and
no effect handler per event [src: free]. For each item, in order, until a quit:

1. `Key.Ctrl('c')` with `ctrlCQuits` -> quit with the current state.
2. `Resize` -> `painter.markResized()`, then step 4.
3. `Mouse` -> `tags = painter.tagsAt(x, y)` from the last drawn frame, then step 4.
4. Any `Event` -> `next = app.update(s, e)` (NonFatal -> `TuiError.AppFailed("update on " + e.show, t)`).
5. `Fold(token, v)` -> `sup.live(token)`: absent -> drop (`droppedFolds += 1`); present -> `next = running.fold(s, v)`
   with the running entry's **current** fold (G7).
6. `Done(token, Success)` -> `sup.finished(token)`. `Done(token, Failure | Panic)` -> `recover(s, e)` if the
   source has one, else abort with `TuiError.SourceFailed(label, e)`. An `Interrupted` result of a source we
   stopped has no live token and is ignored.
7. `Probe(p)` -> remembered; completed after this batch's draw decision (so `settle` sees the screen).
8. `Failed(err)` -> abort with `err`.

Applying `next`: `Quit` -> stop with `s`; `Next.Then(s2, tasks, quit)` -> `s = s2`, then stop if `quit`, else
append `tasks` to the pending list; a plain state -> `s = next`. After the loop the step is `Quit`, `Changed`
(final `s` is not `same` as the state before the batch, or a resize happened), `WorkOnly` (same state, pending
tasks) or `Same`. `same(a, b)` is `a eq b`, or `a == b` when `a` is a boxed primitive or a `String` (an
`Int` above 127 re-boxes; this keeps level 1 cheap and exact).

`Engine.syncWork` (one `Sync` step): if the state changed, `sup.sync(app.sources(s))` (NonFatal ->
`AppFailed("sources")`); then `sup.start(pendingTasks)`. A batch slower than one frame budget (measured
around the fold) writes a `slow` trace line (G16).

### 5.6 When a frame is drawn

A frame is drawn only when all of these hold:

1. The batch produced a new state (level 1) or a resize.
2. `view(s)` (NonFatal -> `AppFailed("view")`) is not `==` to the last drawn view, or a resize arrived since
   (level 2). View nodes are case classes of plain data, so the equality is exact for every node (G1). An
   escape hatch compares with the jatatui widget's `equals` (reference equality for `Gauge`, `LineGauge`,
   `Scrollbar`, `Canvas`, `Calendar`, VERIFIED V17) or by reference for functions: correct, only slower.
3. The frame slot is free: at most one frame per `1/fps` (5.4).

A draw is one `terminal.draw(frame => painter.paint(view, frame))` inside one `Sync.Unsafe.defer`.
jatatui's `Terminal.draw` autoresizes, calls the callback, diffs the buffers, sends one JNI `enqueue` with the
changed cells, shows or hides the cursor, swaps the buffers and flushes (4 JNI calls per frame, jatatui.md
2.8). jatatui's checked `IOException` and crossterm's `RuntimeException("IO error: ...")` become
`TuiError.TerminalIO`. The backend is wrapped in a `CountingBackend` that counts the `BufferUpdate`s it
forwards (`Stats.lastChangedCells`).

Quit draws no final frame.

### 5.7 The painter, widget state, hit map, cursor

- **Walk.** `paint(node, area, buf, position)` recursively. Containers call `Layout.split(area)` once per
  frame and read the shared array at once. Wrappers adjust the area (`Bordered`: `block.inner(area)`;
  `Padded`; `Popup`: centered rect, `Clear` first) or the style (`Styled`: `buf.setStyle(area, style)` first).
  Sizes come from `Sized` children (default `Constraint.Fill(1)`).
- **Position ids.** Root = 1; child = `parent * 31 + index + 1` (a `Long`, no allocation). `.id(name)` replaces
  the position of its subtree with a hash of the name, so a list keeps its scroll offset when a conditional
  view appears before it.
- **Widget-state slots.** `Items`, `Table` and `Stateful` ask `slots.get(position, nodeKind)`; a slot of another
  node kind at that position is replaced by a fresh state. Before render, the node copies the model into
  the state (`listState.select(Optional.of(selected))`); jatatui's render clamps the selection and moves the
  offset, which persists to the next frame. After each frame, slots not visited are removed (the
  jatatui-react sweep rule). One hash lookup per stateful node per frame.
- **Hit map.** `Tagged` nodes record `(area, name)` in paint order; `tagsAt(x, y)` returns the names whose
  area contains the point, last painted first (popups in `layers` win). Rebuilt each frame.
- **Cursor.** A focused `Input` calls `frame.setCursorPosition(x, y)`; otherwise jatatui hides the cursor.
- **Theme.** Focused borders use `theme.accent`; `Items`/`Table` highlights use `theme.highlight` when
  focused and `theme.unfocusedHighlight` otherwise; `help` uses `helpKey` / `helpText`. (validated: `help(keys)`
  returns a plain `View.Text` built in the dsl, which has no theme; the skeleton styles keys bold and text dim.
  To honour `Theme.helpKey` / `helpText`, `help` must return a dedicated node (`View.Help(binds)`, painted with
  the theme) that still offers `.centered` / `.right`. Open for C2.)

### 5.8 The supervisor: sources, tasks, generation tokens

```scala
private[tui4s] final class Supervisor[S](inbox: Channel[Item]):
  private val standing = java.util.LinkedHashMap[String, Running[S]]()  // label -> running standing source
  private val keyed    = java.util.HashMap[String, Long]()               // task id -> token (latest wins)
  private val live     = java.util.HashMap[Long, Running[S]]()           // token -> entry that may still deliver
  private var nextToken = 1L

  def sync(wanted: Seq[Source[S]])(using Frame): Unit < Sync     // on the render fiber, after a changed batch
  def start(tasks: Seq[Source[S]])(using Frame): Unit < Sync
  def live(token: Long): Running[S] | Null
  def finished(token: Long): Unit
  def stopAll(using Frame): Unit < Sync                          // in a Sync.ensure around the loop
```

- **sync**: label the wanted list (duplicates get `#1`, `#2`); interrupt the standing entries whose label is
  missing and remove their tokens; for kept labels, replace the entry's `source` with the newly declared one
  (newest fold and recover, 2.13 rule 6); start the new labels. When labels are unchanged (the common case)
  this is a few hash lookups.
- **start one source**: `token = nextToken++`; `Fiber.initUnscoped(source.body(emitter(token)))`;
  `fiber.unsafe.onComplete(r => inbox.unsafe.putFiber(Done(token, r)))`. **Not** `Fiber.init`: a scoped fiber
  adds a finalizer to the enclosing `Scope` that stays until the app ends (VERIFIED `Fiber.scala:129-150`,
  `Scope.scala:289-307`), so a task per key press would grow it without bound [src: free].
- **Ordering.** A body's emit is a `put` that returns only when the value is in the inbox; `Done` is put
  after the body returned. The channel is FIFO, so `Done` always follows the last value.
- **Tasks**: a task with an explicit `.id(name)` interrupts `keyed(name)` (if running) and takes its place; its
  old token is removed, so the old task's late value is dropped (G7). Tasks without an id get a fresh token
  each.
- **Standing `task` sources** keep their entry (marked done) after `Done`, so they do not restart while
  declared; their token is removed.
- **Stopping** is `fiber.unsafe.interrupt()`: signalled, not awaited. A source that wraps ZIO or CE work
  cancels that work (F8, F11). A late value is dropped; a late `Done` finds no token.
- **stopAll** interrupts every fiber and clears the maps. It runs in a `Sync.ensure` around the loop, before
  the terminal release.

**Built-in bodies** (core constructors; facades use `spi.Sources.make` the same way):

| Constructor | Body |
| --- | --- |
| `every(p)(f)` | `Loop.forever(Async.sleep(p).andThen(emit(())))`, fold `(s, _) => f(s)` |
| `poll(p)(task)(f)` | `Loop.forever(task.map(emit).andThen(Async.sleep(p)))` |
| `task(t)(f)` | `t.map(emit)` |
| `stream(s)(f)` | `s.foreachChunk(c => emit(c))`, one fold of the whole chunk (`Next` folded element by element; a `Quit` inside the chunk stops) |
| `signal(sig)(f)` | a `SignalRef`: the exact watcher below; any other `Signal`: `sig.current`, then `Loop.forever(sig.next)` (validated: `streamChanges` needs a `Tag[Emit[Chunk[A]]]`, which `signal(...)(using Frame)` does not have for a generic `A`) |
| `apply(body)` | `body(dispatch)`; `Dispatch.apply(f)` emits `f`, fold `(s, f) => f(s)` |

**Exact watcher** for a `SignalRef` (G14, protocol VERIFIED against `SignalRef.Unsafe`, `Signal.scala:649-720`:
a changing write sets the value first, then swaps in a new promise and completes the old one; an equal write
completes nothing):

```scala
def watch[A](ref: SignalRef[A], emit: Emitter[A])(using Frame): Unit < Async =
  Loop(Maybe.empty[A]) { last =>
    Sync.Unsafe.defer {
      val next = ref.unsafe.next()    // 1. register for the next change FIRST
      (next, ref.unsafe.get())        // 2. then read the current value
    }.map { (next, now) =>
      val sent = if last.contains(now) then Kyo.unit else emit(now)   // skip a repeat
      sent.andThen(next.safe.get).andThen(Loop.continue(Present(now)))
    }
  }
```

A write after step 1 completes `next`, so another round follows; a write before step 1 is read by step 2.
No value is lost and no timer runs (Kyo's `observe` re-arms a 1 s repair timer, V2). For other `Signal`s, the
watcher reads `current`, then waits on `next`: a write in between shows at the next write (documented).
(validated: the exact watcher compiles as written: `ref.unsafe.next()`, `ref.unsafe.get()`, `next.safe.get`.)

### 5.9 Start, terminal acquire and release (F8)

```scala
object Tui:
  def run[S](app: App[S])(using Frame): S < (Async & Abort[TuiError]) =
    val opts = app.options
    TuiLog.let(opts) {                                                       // Log.let (5.10)
      Channel.initUnscoped[Item](opts.inboxCapacity, Access.MultiProducerSingleConsumer).map { inbox =>
        Sync.acquireReleaseWith(TerminalIO.open(opts, inbox))(_.close()) { term =>   // synchronous release
          Supervisor.init[S](inbox).map { sup =>
            val frameNanos = if opts.fps <= 0 then 0L else 1_000_000_000L / opts.fps
            Sync.ensure(sup.stopAll)(RenderLoop(Engine(app, sup, Painter(term.terminal, opts)), inbox, frameNanos).run)
          }
        }
      }
    }
```

`TerminalIO.open` (one `Sync` step; each done step registers its undo; a failing step undoes the earlier
ones and fails):

1. Load the native library (initialize `CrosstermJni`). A JEP 472 warning, if any, prints now, before raw mode
   (jatatui.md 1.3). `IllegalCallerException` or `UnsatisfiedLinkError` -> `TuiError.NativeAccessDenied`.
2. Start the output capture if `captureOutput` (5.10).
3. `jni.enableRawMode()`. Failure (stdin is not a TTY: "IO error: ...") -> `TuiError.NotATerminal`.
4. `jni.execute(EnterAlternateScreen` (Fullscreen only)`, Hide` [, `EnableBracketedPaste`] [, `EnableMouseCapture`]
   [, `EnableFocusChange`]`)`.
5. `Terminal.create(CountingBackend(CrosstermBackend(jni)))`, or `Terminal.withOptions(..., TerminalOptions(Viewport.inline(h)))`.
6. Start the input thread with `CrosstermInput(jni)`.
7. Register an idempotent JVM shutdown hook that runs `close()` (covers `System.exit` in user code).

`Jatatui.init` / `runIo` are never used: they replace the JVM-global uncaught-exception handler and add a
shutdown hook (jatatui.md 4.5).

`close()` (synchronous, idempotent through an `AtomicBoolean`, never throws; every step runs even if an
earlier one failed; the first error is kept and reported after the restore) [G8]:

1. Stop the input thread (`running = false`) and **close the inbox** (`inbox.unsafe.close()`): a pending
   `putBatchFiber` completes with `Closed`, which releases the thread's latch (fixes the hang that free's
   design left when the inbox was full at quit).
2. Join the input thread for at most `2 x pollMillis + 50 ms`. If it is still alive (a hung JNI `poll`), go on;
   the trace records it (a daemon thread is left; the terminal is restored anyway).
3. `jni.execute([DisableFocusChange], [DisableMouseCapture], [DisableBracketedPaste], Show, LeaveAlternateScreen)`;
   for `Viewport.Inline`: move the cursor below the viewport and print a newline.
4. `jni.disableRawMode()`, `jni.flush()`.
5. Stop the output capture: restore `System.out` / `System.err`, flush the sink; without a `logFile`, print the
   memory ring (captured output and Kyo log lines at warn or above) to the real stderr.
6. Remove the shutdown hook (unless `close` runs inside it).

Why this order: the input thread cannot read stdin after raw mode is off (no keystroke typed into the shell
is swallowed), and everything happens before the fiber's result is visible. On interruption, `Scope`
finalizers run on a detached fiber that nobody awaits, but `Sync.ensure` / `Sync.acquireReleaseWith` releases
finish before the result (F8, VERIFIED in `Scope.scala` and by the bridge tests; `Sync.acquireReleaseWith`
uses the kernel `Bracket` with a synchronous release, `Sync.scala:78-107`). So `fiber.interruptAwait`, ZIO's
interrupt-and-await (F11) and CE's cancel-and-await (F8) all return with the terminal restored. **Never a
`Scope.ensure` for the terminal.**

**`TuiApp.main`** (core):

1. `SchedulerDefaults.applyIfUnset()` (also done in the constructor; idempotent).
2. Start `Tui.run(this).map(s => onExit(s).andThen(s))` on an unscoped fiber (unsafe API).
3. Add a shutdown hook (SIGTERM, `kill -INT`): interrupt that fiber, wait up to 2 s for the restore latch.
4. Block until the fiber ends: `KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Tui.run(this).map(...))`, which
   returns a `Result[Throwable, S]`. (validated: compiles; run without a TTY it prints "stdin/stdout is not a
   terminal" and exits 1, and `javap` shows the static `main` forwarder on `object CounterApp`.)
5. Remove the hook. Success: return (exit 0). `TuiError`: print its message and cause to stderr, exit 1.
   Panic: print the stack trace, exit 1. Printing happens only after the restore.

Ctrl-C in raw mode is a key, not SIGINT (raw mode turns ISIG off); `ctrlCQuits` handles it. The ZIO and Cats
app classes rely on their runtimes' own shutdown hooks (interrupt / cancel the main fiber), which reach the
synchronous release through the bridges' interrupt-and-await.

### 5.10 Output hygiene (G9)

Nothing may write to the terminal while the UI is up, except the painter.

- **Kyo `Log`**: `Tui.run` runs under `Log.let(TuiLog(opts))` (VERIFIED `Log.let(log: Log)(f)`, `Log.scala:136`).
  `TuiLog` is a `Log.Unsafe` that writes to `logFile`, or keeps the last 200 lines in memory; lines at
  warn or above are printed to stderr after the restore.
- **`System.out` / `System.err`** (`captureOutput`, default on): replaced while the UI is up by print streams
  into the same sink; restored by the release. This catches `println` in user code and libraries, and
  JVM-level writes such as Kyo's default console logger if something bypasses the `Log` local. crossterm
  draws through Rust's own stdout (`CrosstermJni.enqueue/flush` are native, jatatui.md 4.1), so the capture
  does not touch drawing. It is JVM-global: other threads' output is captured too, which is the intent.
- **ZIO**: `TuiApp` uses `Runtime.removeDefaultLoggers` (4.2). **Cats Effect**: `TuiApp` turns the CPU
  starvation checker off (4.3).
- JDK warnings: the build template passes `--enable-native-access=ALL-UNNAMED` and
  `--sun-misc-unsafe-memory-access=allow` (F4); the native library is loaded before raw mode anyway.

### 5.11 Resize and the inline viewport

crossterm sends `Resize(columns, rows)`. The fold marks the painter resized and gives the event to `update`.
The next draw happens even if the view is equal; `Terminal.draw` calls `autoresize()`, which resizes and
clears both buffers (VERIFIED `Terminal.java`). A burst of resizes (dragging a window corner) is one batch
and one frame per budget. `Viewport.Inline(h)` uses jatatui's inline viewport; `Terminal.create` and
`autoresize` then query the cursor position (JNI `cursorPosition`, which writes `ESC[6n` and reads the reply)
while the input thread polls stdin. crossterm routes that reply through its internal reader, not `read()`
(UNVERIFIED for jatatui's JNI build); the first core PR that enables `Inline` must test it on a real TTY.

### 5.12 Errors

| Source | What happens |
| --- | --- |
| `update`, `view`, `sources`, a fold, a keymap action throws (NonFatal) | `TuiError.AppFailed(where, e)` -> release -> `Tui.run` fails |
| a source or task fails (`Abort[Throwable]` or panic) | its `recover` folds it; else `TuiError.SourceFailed(label, e)` -> release -> fail |
| jatatui `IOException`, crossterm `RuntimeException("IO error ...")` while drawing | `TuiError.TerminalIO` |
| the input thread fails | `Failed(TerminalIO)` through the inbox -> release -> fail |
| no TTY / native access denied | `NotATerminal` / `NativeAccessDenied`, before raw mode |
| a fatal error (`IsFatal`) | not caught; the synchronous release still runs |
| the release itself fails | `close` never throws; the first restore error is printed after the restore by the app classes |

### 5.13 Quit and interruption

| How the app ends | Order of events |
| --- | --- |
| `update` / a fold returns `Quit` (or Ctrl-C) | loop returns the state -> `Sync.ensure`: sources stopped -> release: inbox closed, input joined, terminal restored -> `Tui.run` succeeds |
| user code throws | same finalizers -> `Tui.run` fails with `AppFailed` |
| the running fiber is interrupted (`interruptAwait`, ZIO interrupt, CE cancel, `TuiHandle.stop`) | the same synchronous finalizers run **before** the interrupt is reported (F8, F11) |
| SIGTERM / `kill -INT` | `TuiApp` hook interrupts the fiber and waits up to 2 s; ZIOApp / IOApp hooks do the same through their runtimes |
| `System.exit` in user code | the idempotent shutdown hook from step 7 of `open` restores the terminal |

### 5.14 Idle CPU and the scheduler flags (F13)

When nothing happens: the render fiber is parked in `take`, the input thread returns from `poll` 40 times
a second, and only the sources the state declares are parked in their sleeps. No frame is drawn. The rest is
the Kyo scheduler's own cost: ~13% of one core with default flags; 4-8% with the flags below; floor
~2-2.5% from the hard-coded 1 ms clock thread (VERIFIED, `.work/scratch/idle/idle-output.txt`).

```scala
package tui4s.spi

object SchedulerDefaults:            // no Kyo import: loading this object never starts the scheduler
  val defaults: Seq[(String, String)] = Seq(
    "kyo.scheduler.cycleIntervalNs"                        -> "10000000",  // 100 us -> 10 ms
    "kyo.scheduler.blockingMonitorIntervalNs"              -> "20000000",  // 2 ms   -> 20 ms
    "kyo.scheduler.regulator.concurrencyCollectIntervalMs" -> "100"        // 10 ms  -> 100 ms
  )
  def applyIfUnset(): Unit =
    if java.lang.System.getProperty("tui4s.schedulerDefaults") != "false" then
      defaults.foreach { (key, value) =>
        val env = key.toUpperCase.replace('.', '_')          // KYO_SCHEDULER_CYCLEINTERVALNS
        if java.lang.System.getProperty(key) == null && java.lang.System.getenv(env) == null then
          discard(java.lang.System.setProperty(key, value))
      }
```

- The keys are the fully qualified names of the `StaticFlag` objects (VERIFIED: `kyo-scheduler`
  `Flags.scala`, `BlockingMonitorFlags.scala`, `regulator/Flags.scala`). They are read once, when the flag
  object initializes.
- **Never override the user.** A key is set only when neither the system property nor the environment
  variable is set (the `KYO_...` form is Kyo's documented env alternative, e.g. `KYO_LOG_DEFAULTLEVEL` in
  `Log.scala`; the scheduler flags read it the same way: validated, `kyo-config` `Flag.scala`).
  `-Dtui4s.schedulerDefaults=false` turns the whole thing off.
- **Where it runs.** First statement of the constructor of all three `TuiApp` classes (a superclass
  constructor runs before the user object's field initializers, so before any user `val` touches Kyo; the
  facades' `ZIOAppDefault` / `IOApp` constructors do not load Kyo), again in core `TuiApp.main`, and at the
  start of the facades' `Tui.run` (helps users who call `Tui.run` from their own app, if Kyo is not loaded yet).
  Core `Tui.run` cannot do it: it is already a Kyo computation.
- **Kyo users with their own `KyoApp`** pass the three `-D` flags (the docs and the sbt template show them).
- `coreWorkers` is not changed: it would cap CPU-heavy tasks. The effect of a 10 ms worker cycle on wake-up
  latency under load is UNVERIFIED; the latency probe of 7.3 measures it.

### 5.15 JVM flags and build template

Shipped in the `examples` build (already present) and in the docs' sbt template:
`run / fork := true`, `run / connectInput := true`, `outputStrategy := Some(StdoutOutput)`,
`run / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")`.
A packaged app adds `Enable-Native-Access: ALL-UNNAMED` to its jar manifest. `-Xss16m` (repo `.jvmopts`) is
needed only to compile long Kyo for-comprehensions (F1); app logic has none. (validated: the core skeleton, the
three Kyo samples and their tests compile from clean with the default 1 MB thread stack, no `-Xss`.)
(validated: with `-Wunused:all`, every inlined Kyo method that carries `@nowarn` adds a position-less warning
"@nowarn annotation does not suppress any warnings" (29 in the core skeleton). The build and the docs' template
add `-Wconf:msg=@nowarn annotation does not suppress any warnings:s`.)

### 5.16 The trace file (G11)

With `TuiOptions.trace = Some(path)` the render fiber appends one JSON line per batch (buffered, flushed every
100 ms and at the release) [src: elm]:

```
{"t":0.000,"kind":"start","size":"120x40","sources":["logs","cpu"]}
{"t":12.481,"kind":"batch","in":["key:+","key:+"],"folds":["cpu"],"dropped":0,"state":"changed","start":[],"stop":[],"tasks":[],"frame":"drawn","frameMicros":1840,"cells":12,"batchMicros":2105}
{"t":13.002,"kind":"batch","in":["key:x"],"state":"same","frame":"skip-same"}
{"t":13.900,"kind":"batch","in":[],"folds":["logs"],"state":"changed","frame":"skip-equal-view"}
{"t":14.200,"kind":"slow","where":"update on key:x","micros":23000}
{"t":20.310,"kind":"quit","reason":"update"}
```

Event codes: `key:<Key.show>`, `paste:<base64>`, `resize:<w>x<h>`, `mouse:<kind>:<button>:<x>,<y>`,
`focus:gained|lost`. `TuiTest.replay` feeds the `in` events back, batch by batch; folds are functions and are
not replayed. `TuiTest.lastTrace` returns the line of the last batch in the same format, so an agent can ask
"why did my key do nothing?" and read `"state":"same","frame":"skip-same"`.

---
## 6. Testing specification

### 6.1 Levels (none needs a terminal, except level 4)

| Level | What it checks | How | Needs |
| --- | --- | --- | --- |
| 1. logic | `update`, model methods, keymaps (`keys(s, e)`, `keys.explain(e)`), `sources(s).map(_.label)` | plain calls on values | nothing |
| 2. screen and frames | what a sequence of events, values and failures draws; frame counts; declared sources; started tasks | `TuiTest(app, w, h)`: the real fold, frame rules and painter on a jatatui `TestBackend`, synchronous | jatatui-core; no Kyo runtime visible; the same for Kyo, ZIO, Cats |
| 3. live | sources and tasks really running, timing, interruption, the restore order | `TuiTest.live` / `liveBlocking`: the real `RenderLoop` + `Supervisor` + fibers on a `TestBackend`, scripted input, Kyo virtual time | Kyo (`Async`) |
| 4. real terminal | raw mode, alternate screen, the input thread on crossterm | the examples on a real TTY (manual checklist, 8.4); optional CI job under `script` (pty) | a TTY |

Most tests should be level 1 and 2: deterministic, fast, and readable by agents.

### 6.2 Test-kit semantics (API in 2.10)

- `TuiTest(app, w, h)` runs `init`, records `sources(init)`, and draws the first frame: `frames == 1`.
- `press` is one batch per key; a `Char` means `Key.Char(c)`. `typeText` splits graphemes: a one-char grapheme is
  `Key.Char`, a longer one (emoji) is a `Paste` (crossterm cannot deliver non-BMP keys, 2.4). `send` puts all its
  events in one batch.
- Frame rules are the runtime's (levels 1 and 2); there is no frame cap, so every changed batch can draw.
- `sources` lists the labels the current state declares, after the diff, in order, with `#n` suffixes.
  `started` lists one-shot task labels in start order.
- `emit(label, value)` folds `value` with the **current** fold of the standing source or running task
  `label` (a task is then done). A value of the wrong type fails with `AssertionError("emit(cpu): 0.42
  (Double) does not fit the fold of source cpu")`; an unknown label fails with the list of known labels.
- `fail(label, e)` goes through `recover`, or ends the app: `quit == true`, `error == Some(SourceFailed(...))`.
  User code that throws is recorded the same way (`AppFailed`), not thrown into the test.
- `LiveTest.settle` puts a `Probe` into the inbox and waits until the render fiber completes it (after the
  fold and draw of everything put before it); it repeats while a one-shot task is running. A task that never
  ends makes `settle` wait: give the leaf a timeout. [src: signals]
- `LiveTest.advance(d, sleepers)` under `Clock.withTimeControl` (VERIFIED API: `advance(duration,
  wallClockDelay)`, `awaitPendingSleepers(count)`, `Clock.scala:358-425`). One `TimeControl.advance(d)` fires
  only the sleepers due at that instant; a periodic source re-arms after the jump, so one call gives one
  tick, not `d / period` ticks (V8). The kit therefore steps:

  ```
  n = sleepers >= 0 ? sleepers : number of running every / poll / stream sources
  repeat until d is used up:
    awaitPendingSleepers(n)                 // every periodic source is parked again
    advance(min(remaining, 1 ms), wallClockDelay = 0)
    settle
  awaitPendingSleepers(n); settle
  ```

  So `advance(1.second)` on the Dashboard fires the poller at 0, 250, 500, 750 and 1000 ms (5 samples) and the
  log stream at 700 ms. Streams with an unusual number of parked sleepers pass `sleepers` explicitly. The live
  harness also runs under `Random.withSeed(0)` and `fps = 0`.

  (validated: a probe ran the real loop of the Dashboard under `Clock.withTimeControl` (`fps = 0`): the source
  fibers inherit the controlled clock. (history, logs) = (1, 0) at start; after ONE `advance(1.second, 0)`:
  (2, 1), one tick per sleeper as V8 says; after four `advance(250.millis, 0)` steps: (6, 2). `TimeControl.tick`
  completes every due sleeper, each re-arms from the new `current`. The kit's `settle` is not built yet; the
  probe waited in real time. Note: a probe fiber that blocked a worker thread right after
  `awaitPendingSleepers` saw the render fiber stall; `settle` must park, never block.)

### 6.3 Example tests

Core tests live in `modules/core/src/test/scala/tui4s/` and use test-local copies of the three samples
(`fixtures/`), because `examples` depends on core, not the reverse.

**Core (kyo-test).**

```scala
package tui4s

import kyo.*
import kyo.test.*
import tui4s.fixtures.*
import tui4s.testkit.*

class CounterTest extends Test[Any]:
  "update is a pure function" in {
    assert(CounterApp.update(1, Key.Char('+')) == 2)
    assert(CounterApp.update(1, Key.Char('q')) == Quit)
    assert(CounterApp.update(1, Key.Char('x')) == 1)
  }
  "the screen shows the count and the help bar" in {
    val t = TuiTest(CounterApp, 40, 6).press('+', '+')
    assert(t.state == 2)
    assert(t.screen.contains("Count: 2"))
    assert(t.screen.contains("+ increment · - decrement · q quit"))
  }
  "a key that changes nothing builds no view and draws no frame" in {
    val t      = TuiTest(CounterApp)
    val before = t.stats
    t.press('x', Key.Up)
    assert(t.stats.viewBuilds == before.viewBuilds && t.stats.frames == before.frames)
    assert(t.lastTrace.contains("\"frame\":\"skip-same\""))
  }
  "q quits; Ctrl-C quits" in {
    assert(TuiTest(CounterApp).press('q').quit)
    assert(TuiTest(CounterApp).press(Key.Ctrl('c')).quit)
  }
  "a golden screen" in {
    val t = TuiTest(CounterApp, 40, 5).press('+')
    assert(diffScreen(t.screen, Golden("counter-1.txt")).isEmpty)   // Golden reads src/test/resources/screens/
  }
end CounterTest

class TodoTest extends Test[Any]:
  "loads at start, then add, toggle, delete" in {
    val t = TuiTest(TodoApp, 48, 12)
    assert(t.sources == Seq("load"))
    t.emit("load", Result.succeed(Vector(Item("buy milk"))))
    assert(t.sources.isEmpty && t.state.items.size == 1)
    t.typeText("call bob")                         // (validated: a chained statement warns, 2.10)
    t.press(Key.Enter, Key.Tab, ' ')
    assert(t.state.items.map(_.done) == Vector(false, true))
    assert(t.screen.contains("✔ call bob"))
    t.press('d')
    assert(t.state.items.map(_.title) == Vector("buy milk"))
  }
  "'d' and 'q' in the text field are text" in {
    val t = TuiTest(TodoApp).emit("load", Result.succeed(Vector.empty[Item])).typeText("dq")
    assert(!t.quit && t.state.field.text == "dq" && t.cursor.isDefined)
  }
  "Ctrl-S starts one save task and folds its result" in {
    val t = TuiTest(TodoApp).emit("load", Result.succeed(Vector.empty[Item])).press(Key.Ctrl('s'))
    assert(t.started == Seq("save") && t.state.status == "saving…")
    t.emit("save", Result.succeed(()))
    assert(t.state.status == "saved")
  }
  "the help bar shows the keys of the focused pane" in {
    val t = TuiTest(TodoApp).emit("load", Result.succeed(Vector.empty[Item]))
    assert(t.screen.contains("enter add") && !t.screen.contains("d delete"))
    t.press(Key.Tab)
    assert(t.screen.contains("d delete"))
    assert(TodoApp.itemKeys.explain(Key.Char('d')) == "'d' -> delete")
  }
end TodoTest

class DashboardTest extends Test[Any]:
  "a sample moves the gauge; pause stops the poller" in {
    val t = TuiTest(DashboardApp, 72, 16).emit("cpu", 0.42)
    assert(t.screen.contains("42%"))
    assert(t.sources == Seq("logs", "cpu"))
    t.press('p')
    assert(t.sources == Seq("logs") && t.screen.contains("CPU (paused)"))
  }
  "live: the poller samples every 250 ms and stops when paused".timeout(20.seconds) in {
    TuiTest.live(DashboardApp, 72, 16) { t =>
      t.advance(1.second)
        .andThen(t.state).map(d => assert(d.history.size == 5 && d.logs.size == 1))
        .andThen(t.press('p'))
        .andThen(t.advance(1.second))
        .andThen(t.state).map(d => assert(d.history.size == 5))
    }
  }
end DashboardTest

class GuardTest extends Test[Any]:
  "a ZIO value given to source.task does not compile" in {
    // (validated: the same check works with munit `compileErrors(...)`; kyo-test's typeCheckFailure not tried)
    typeCheckFailure("""tui4s.Source.For[Int].task(new zio.ZIO[Any, Nothing, Int])((s, _) => s)""")(
      "A Kyo app runs Kyo computations only")
  }
  "Keymap rejects a key bound twice" in {
    val e = intercept[IllegalArgumentException](
      Keymap[Int](Bind[Int]('a', "x")(identity), Bind[Int]('a', "y")(identity)))
    assert(e.getMessage.contains("'a'"))
  }
end GuardTest
```

(Core has no ZIO dependency, so its test sources define a stand-in `package zio; final class ZIO[-R, +E, +A]`;
the macro matches by full type name.)

**Core runtime contracts (kyo-test, level 3).** `RestoreOrderTest`: run `Tui.runWith(app, RecordingControl,
ScriptedInput)` (a `private[tui4s]` seam over the terminal control of 5.9), interrupt it with
`fiber.interruptAwait`, and assert that the log is
`enter, input-started, ..., input-stopped, input-joined, leave-raw-mode` and that it was complete when
`interruptAwait` returned. `SupervisorTest`: a stopped source's late value is dropped; a keyed task replaces
the older one; 10 000 one-shot tasks leave empty maps. The frame contracts are in 7.2.

**Examples (munit, the users' style).**

```scala
package examples.dashboard

import munit.FunSuite
import tui4s.*
import tui4s.testkit.*

class DashboardSuite extends FunSuite:
  test("pause removes the poller") {
    val t = TuiTest(DashboardApp, 72, 16).press('p')
    assertEquals(t.sources, Seq("logs"))
  }
  test("live: five samples in one second") {
    val n = TuiTest.liveBlocking(DashboardApp, 72, 16)(t => t.advance(1.second).andThen(t.state).map(_.history.size))
    assertEquals(n, 5)
  }
```

The ZIO and Cats examples have the same synchronous tests, with `import tui4s.interop.zio.*` / `tui4s.interop.cats.*`.

**tui4s-zio (munit).**

```scala
package tui4s.interop.zio

import munit.FunSuite
import tui4s.testkit.*
import _root_.zio.{FiberRef, Runtime, Unsafe, ZIO, durationInt}

class ZioFacadeSuite extends FunSuite:
  private def run[A](z: ZIO[Any, Any, A]): A = Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(z).getOrThrowFiberFailure())

  test("the shared Counter behaves the same under tui4s.interop.zio") {
    assertEquals(TuiTest(fixtures.CounterApp).press('+', '+').state, 2)
  }
  test("a task source runs on the caller's runtime (FiberRef visible, F12)") {
    val seen = run(ZIO.scoped(FiberRef.make(0).flatMap { ref =>  // (validated: FiberRef.make needs a Scope)
      ref.locally(42)(Tui.runWith(fixtures.readRefOnce(ref), RecordingControl(), ScriptedInput.empty))
    }))
    assertEquals(seen, 42)
  }
  test("interrupting Tui.run returns only after the terminal is restored") {
    val log = RecordingControl()
    run(Tui.runWith(fixtures.CounterApp, log, ScriptedInput.never).fork.flatMap(f => ZIO.sleep(50.millis) *> f.interrupt))
    assert(log.events.lastOption.contains("leave-raw-mode"), log.events)
  }
```

`Tui.runWith(app, control, input)` is the facade's `private[tui4s]` seam over `Tui.run`; the AmbientZIOs
probe tests (`zio/ZioBridge.scala` sections 1-7: values, typed errors, defects, interruption both ways, caller
FiberRef from a forked Kyo fiber, 500-round interrupt race) are ported as `AmbientZIOsSuite`.

**tui4s-cats (munit-cats-effect).**

```scala
package tui4s.interop.cats

import _root_.cats.effect.IO
import munit.CatsEffectSuite
import scala.concurrent.duration.*

class CatsFacadeSuite extends CatsEffectSuite:
  test("an IO task source runs on the caller's IORuntime") {
    Tui.runWith[IO, String](fixtures.threadNameOnce, RecordingControl(), ScriptedInput.empty)
      .map(name => assert(name.startsWith("io-compute"), name))
  }
  test("cancel returns only after the terminal is restored (cancel-and-await, F8)") {
    val log = RecordingControl()
    Tui.runWith[IO, Int](fixtures.CounterApp, log, ScriptedInput.never).start
      .flatMap(f => IO.sleep(50.millis) *> f.cancel)
      .map(_ => assertEquals(log.events.lastOption, Some("leave-raw-mode")))
  }
  test("Dispatcher(await = false): 100 cancels from a foreign thread never hang (F10)") { /* port of dbg/PureDispatcher */ }
```

The 13 bridge tests (`CatsBridge.test.scala`) move unchanged as `CatsBridgeSuite`.

### 6.4 Test framework per module

| Module | Framework | Why |
| --- | --- | --- |
| core | **kyo-test** (`io.getkyo::kyo-test-runner` 1.0.0-RC7, `Test[Any]` suites) | Most core tests are Kyo computations (loop, supervisor, `Clock.withTimeControl`, interruption); a kyo-test leaf *is* a Kyo value, with no `runAndBlock` boilerplate. Per-leaf `.timeout` guards event-loop tests against hangs. `typeCheckFailure` pins compile-time contracts (`NotForeign`, duplicate ids). It is the Kyo project's own framework, released in lockstep with Kyo (F6), so one version in `Versions.scala` covers both. Caveats: a leaf with no kyo-test assertion fails, so use `assert(diffScreen(...).isEmpty)` rather than `assertScreen`; it is an RC. Fallback if the runner misbehaves under sbt 1.13: munit + `KyoApp.Unsafe.runAndBlock` (proven in the probes). |
| zio | **munit** (already in the build) | The tests check the bridge and the facade, not ZIO features; `Runtime.default.unsafe.run` is enough. zio-test would add a dependency, and its `TestClock` does not drive Kyo sleeps. ZIO users test their apps with any framework: the test kit is framework-neutral. |
| cats | **munit + munit-cats-effect** (already in the build) | The vendored bridge already has 13 passing munit tests (F8); `CatsEffectSuite` runs IO tests on a test runtime. |
| examples | **munit** | Shows users the common style; the synchronous `TuiTest` and `liveBlocking` work in any blocking framework. |

### 6.5 Agent-friendliness and CI checks

What an agent gets:

1. **One pattern**: `init` / `view` / `update` / `sources` plus keymaps. No hook rules, no element identity, no
   message type to grow.
2. **The screen is text**: `t.screen` prints the frame; a failed `assert` shows it; `diffScreen` shows a
   row diff.
3. **"Why did my key do nothing?"**: `t.lastTrace` (`"state":"same","frame":"skip-same"`),
   `keys.explain(key)`, `t.viewTree`.
4. **Performance as assertions**: `t.stats` (frames, view builds, skips, dropped folds).
5. **The compiler checks the usual mistakes**: `Key` is an enum; `Next[S]` forces a state; `NotForeign` rejects
   a lifted foreign effect; `-Wnonunit-statement` / `-Wvalue-discard` catch a lost `copy(...)`.
6. **Real-terminal debugging**: `TuiOptions.trace` writes JSON lines the agent can read; `TuiTest.replay`
   reproduces the key part of a session.

CI checks added by the plan (8):

- **Kyo-free shared surface** (core test, G5): with `tasty-query` (test dependency), load the TASTy of the
  shared-surface classes (2.2) and fail if any public signature references a symbol in package `kyo`
  (Java reflection cannot see `A < S`: the opaque `<` erases to `Object`). The facades run the same check on
  their whole public API.
- **Facade parity** (examples): one Counter body compiled three times (Kyo, ZIO, Cats imports) from a shared
  source text (an sbt source generator copies `CounterBody.scala.txt` with the three import lines).
- **Dev checks** in tests: `Keymap` duplicates fail at construction; a `slow` trace line fails the frame
  contract tests.

---
## 7. Performance budget and how to measure it

### 7.1 Reference numbers (VERIFIED)

| Fact | Value | Source |
| --- | --- | --- |
| typical 120x40 frame (Block + wrapped Paragraph + 100-item List) | ~2.1 ms, ~1.7 MiB allocated (2014 i7, loaded machine) | jatatui.md 5.3 |
| `Terminal.draw` fixed cost (diff + reset), even with nothing changed | ~0.6 ms | jatatui.md 5.3 |
| `Layout.split` cache hit | 2.7-4.5 us | jatatui.md 5.3 |
| Kyo loop, 10k queued keys, batched input + coalesced frames | ~6.7 us/event, 3 frames | `loop1.log` |
| the same, drawing after every event | ~14 us/event | `loop1.log` |
| `Channel` putBatch + drain | 0.5-0.8 us/element | kyo-rc7 probe |
| Kyo scheduler idle, one fiber asleep | ~13% of a core (defaults), 4-8% (flags of 5.14), floor ~2-2.5% | F13 |

Where the time goes in this design: the framework's own work per changed frame (sources diff, view build,
view equality, tree walk) is about 20-100 us, 1-5% of a frame; the rest is jatatui. The design attacks the
jatatui part by not drawing (levels 1 and 2, coalescing). A fixed 60 fps loop at 120x40 would cost ~126 ms
of CPU and ~100 MiB of garbage per second for the same screen.

### 7.2 Contracts (deterministic, in CI)

Core kyo-test suite `FrameContractTest`, on `TuiTest` (level 2) or `spi.Headless` with a `TestBackend`
(level 3). They assert on `Stats`, so they do not depend on machine speed.

| # | Contract |
| --- | --- |
| C1 | An unbound key: 0 view builds, 0 frames (level 1). |
| C2 | A state change whose view is equal: 1 view build, 0 frames (level 2). |
| C3 | A view with a `gauge` whose ratio did not change: 0 frames; a ratio change: 1 frame (G1, V17 fixed). |
| C4 | 10 000 keys queued at once (Headless, fps 60, inbox 1024): `events == 10000`, final state `== 10000` (no loss), `frames <= 2 + elapsedMillis / 16`. |
| C5 | A value from a stopped source and from a replaced keyed task: not applied, `droppedFolds` counts it. |
| C6 | 10 000 one-shot tasks that each complete: supervisor maps empty afterwards (no growth, 5.8). |
| C7 | An app with no sources, idle for 1 s of real time after start: 0 batches, 0 frames. |
| C8 | Restore order on interrupt (6.3 `RestoreOrderTest`). |
| C9 | No batch in C1-C7 writes a `slow` trace line. |

### 7.3 Budgets (timed, measured per release, not in CI)

Measured on the development Mac (8 CPUs, 16 GB, JDK 25), with the flags of 5.14 and 5.15. Each result
records the machine, JDK and flags.

| # | Metric | Budget | Probe |
| --- | --- | --- | --- |
| B1 | runtime overhead per input event in a 10k-key burst (Counter, Headless + `CountingBackend(TestBackend)`) | <= 10 us/event amortized | `BurstProbe` |
| B2 | fold + `view` + view equality for a Dashboard change with an equal view | <= 50 us | JMH `EngineBench` |
| B3 | full paint at 120x40 on `TestBackend`: Counter / Todo (100 items) / Dashboard | <= 0.5 / 2.0 / 1.5 ms; <= 2 MiB allocated per frame | JMH `PaintBench` (`-prof gc`) |
| B4 | key-to-flush latency, idle app (timestamp in `InputPort.read` -> `Backend.flush`) | p50 <= 1 ms, p99 <= 3 ms plus one paint | `LatencyProbe` |
| B5 | idle CPU, Counter, no sources | <= Kyo-alone idle with the same flags + 0.5 points of one core; <= 8% absolute | `IdleProbe` |
| B6 | Dashboard running (4 samples/s + ~1.4 log lines/s) | <= 2% of a core above idle; <= 15 MiB/s allocated | `DashboardProbe` |
| B7 | quit latency (`Quit` -> `Tui.run` returns), input thread on a port that honours poll timeouts | <= 2 x pollMillis + 10 ms (60 ms) | `QuitProbe` |
| B8 | soak: 10 min of Dashboard plus one task per 100 ms | heap after full GC within +-5%; `Stats.sources/tasks` bounded; thread count constant | `SoakProbe` |

A budget miss is a bug or a reason to start a phase-2 item (1.5); the investigation uses `Stats`, the trace
file and JFR.

### 7.4 The `bench` project

A new unpublished sbt project (same pattern as `examples`):

```scala
lazy val bench = project
  .in(file("bench"))
  .dependsOn(core, zio, cats)
  .enablePlugins(JmhPlugin)                         // sbt-jmh in project/plugins.sbt (version: latest 0.4.x, UNVERIFIED)
  .settings(
    name           := "tui4s-bench",
    publish / skip := true,
    run / fork     := true,
    run / javaOptions ++= Seq("--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
  )
```

- **JMH** (`bench/src/main/scala/tui4s/bench/*Bench.scala`): `EngineBench` (fold, view build, equality) and
  `PaintBench` (paint of the three sample screens into a `TestBackend` terminal at 120x40).
- **Probes** (mains in `tui4s.bench`): built on `spi.Headless`, a `CountingBackend`, a scripted `InputPort`
  whose `poll` really waits (so it behaves like the JNI call), `ThreadMXBean` CPU and allocation counters
  (`com.sun.management.ThreadMXBean#getThreadAllocatedBytes`), modelled on the probes that already exist
  (`.work/scratch/kyo-rc7/loop.scala`, `.work/scratch/idle/idle.scala` and `run.sh`). `IdleProbe` forks one
  JVM per flag set, like `run.sh`, and also measures a Kyo-only JVM as the baseline.
- **Output**: a table on stdout and one JSON line per run in `bench/results/<probe>.jsonl` (git-ignored). The
  numbers of each release go to `bench/BASELINE.md`.
- **Commands**: `sbt "bench/Jmh/run -i 5 -wi 5 -f 1 tui4s.bench.PaintBench"`,
  `sbt "bench/runMain tui4s.bench.BurstProbe"`, `... IdleProbe`, `... LatencyProbe`, `... SoakProbe 600`.
- **Rule** (user AGENTS.md): run benchmarks alone, with no other build or agent on the machine; a load
  average far above the CPU count makes the numbers useless.
- A real-TTY check of idle CPU (`ps -o %cpu` on the running Counter example) goes into `BASELINE.md` once per
  release, because the probes use a scripted input port, not crossterm.

---
## 8. Implementation plan

### 8.1 Rules for the work

- Branches: a stacked chain on top of `chore/cleanup` (PR 1): `feat/core-1-model` -> `feat/core-2-view` -> ... ;
  the facade branches start from the core PR they need. Each step is one pull request with its tests.
- Before each push: `sbt fmt` and `sbt check` (AGENTS.md). Each PR gets the review workflow (find ->
  adversarial verify -> fix) from `.work/HANDOFF.md`.
- One sbt process per checkout. Parallel facade work happens only in separate git worktrees (separate
  `target/`), and at most 2 builds run at once (user AGENTS.md).
- `project/Versions.scala`: add `kyoTest = kyo` only if two modules use kyo-test; otherwise the core build uses
  `Versions.kyo` for `kyo-test-runner`. Do not touch `compat.sbt` intentions before the first release.
  Publishing stays deferred (DECISIONS.md).
- Step 0 (already planned in the workflow): the validator compiles the API skeleton of section 2 against Kyo
  RC7 and jatatui 0.31 (Appendix A) and amends this spec.

### 8.2 Core (tui4s-core)

| PR | Content | Tests that must pass |
| --- | --- | --- |
| **C1 model** | `App`, `Next`, `Quit`, `Next.Then`, `withTask`, events + `Key.show`, `Keymap`, `Bind`, `Focus`, `TextField` (grapheme-aware), `dsl` skeleton; kyo-test wiring in `modules/core/build.sbt` | Keymap lookup / `++` precedence / `explain` / duplicate rejection / help text; `TextField` editing with ASCII, combining marks, emoji, Ctrl-a/e/u/k/w, Paste, "unhandled key returns the same object"; `Focus` cycle; `Next` splitting; `Key.show` table |
| **C2 view and painter** | `View` + nodes + modifiers (order-independent sizing), `Style` / `Color` / `Line` / `Line.Span` + extensions, all dsl constructors, `Painter` (walk, layout, widget-state slots, sweep, hit map, cursor, theme), `CountingBackend`, `Screen`, `diffScreen`, `assertScreen`, a first `TuiTest` (events, frames; no sources) | golden screens for every node (text align/wrap/scroll, stack gap/flex, layers + popup, list offset kept across frames, table, tabs, gauge clamp, sparkline, input cursor + placeholder, log bottom alignment, help truncation); `x.size(3).bordered(t) == x.bordered(t).size(3)`; C1, C2, C3 |
| **C3 sources and engine** | `Source`, `Source.For`, `Dispatch`, `NotForeign` (macro), `CallSite`, `spi.Sources` / `Emitter`, `Supervisor` with a recording starter, `Engine.fold` (every rule of 2.13), the complete `TuiTest` (`sources`, `started`, `emit`, `fail`, `fold`, `error`, `lastTrace`, `viewTree`, trace line format) | source diff, `#n` suffixes, current-fold rule, keyed tasks (latest wins), `recover`, `Quit` from a fold, tasks with quit not started, `AppFailed` recorded, `GuardTest`, the three sample test suites of 6.3 (sync parts) |
| **C4 loop and live kit** | `Item`s, `RenderLoop` (pacing by sleep + drain), the real `Supervisor` (`Fiber.initUnscoped`, tokens, `stopAll`), exact `SignalRef` watcher, `TraceWriter`, `InputThread` over `InputPort`, `spi.Headless`, `TuiTest.live` / `liveBlocking` / `LiveTest` (`settle`, stepped `advance`), the `Tui.runWith` seam + `RecordingControl` | C4-C9; `RestoreOrderTest`; live Dashboard test (5 samples per second); watcher: 10 000 writes from 4 fibers, the last value always arrives; `slow` trace line |
| **C5 terminal and entry points** | `TerminalIO` (open / close in the order of 5.9), `CrosstermInput`, `EventMapping`, `OutputCapture`, `TuiLog` (`Log.let`), shutdown hook, `Tui.run`, `Tui.start` + `TuiHandle`, `TuiApp` (constructor flags, `main`), `SchedulerDefaults` | `EventMapping` table built from crossterm records (no JNI): every key code / modifier / kind; `SchedulerDefaults` sets only unset keys, respects env vars and the opt-out; `OutputCapture` restores the streams also on failure; `TuiHandle` on Headless (`update`, `send`, `states`, `stop`) |
| **C6 Kyo examples** | `examples/` Counter, Todo, Dashboard (section 3) + munit tests (6.3) | the example tests; the **real-TTY checklist** below, run by a human or an agent with a terminal |
| **C7 CI checks and bench** | tasty-query shared-surface test; facade-parity source generator (used by Z1/K1); `bench` project with JMH + probes; first `bench/BASELINE.md` | the checks themselves; all budgets of 7.3 measured once and recorded (a miss opens an issue, it does not block the PR) |
| C8+ (phase 2) | `cached(dep)`, retained presenter, `virtualList`, `printAbove`, `Source.exec` (1.5) | each with `Stats` contracts and a "same screen as a full repaint" check |

Real-TTY checklist (C6, repeated before each release): Counter `+ - q`, Ctrl-C quits, the shell prompt is
intact and no stray output appears; Todo typing, emoji paste, Tab focus, toggle, delete, Ctrl-S writes
`~/.tui4s-todo.txt` and a restart loads it; Dashboard tabs, the gauge moves 4 times a second, `p` stops the
samples while logs continue, a window resize leaves no artefacts; `kill -TERM <pid>` restores the terminal;
without `--enable-native-access` JDK 25 prints its JEP 472 warning before raw mode and the app still starts
(validated: JDK 25 defaults to `--illegal-native-access=warn`; `NativeAccessDenied` happens only with
`--illegal-native-access=deny`, which the checklist should also try); with stdin piped (not a TTY) it stops with
`NotATerminal` (validated: "stdin/stdout is not a terminal", exit code 1); tested in macOS Terminal and iTerm2.

### 8.3 tui4s-zio (starts after C3 for Z1, after C5 for Z2)

| PR | Content | Tests |
| --- | --- | --- |
| **Z1 exports and bridge** | `exports.scala` (4.1), `AmbientZIOs` with its attribution header, facade parity for Counter, the no-Kyo signature check for `tui4s.interop.zio` | `AmbientZIOsSuite` (the probe's cases: values, typed errors, defects, interruption both ways, caller FiberRef from a forked Kyo fiber, 500-round interrupt race); parity |
| **Z2 sources, runner, app** | `Source` / `Source.For` (every, poll, task, stream), `Tui.run` + `runWith` seam, `Tui.start` + `TuiHandle`, `TuiApp` (`removeDefaultLoggers`, scheduler flags) | `ZioFacadeSuite` (6.3); a 10 000-element `ZStream` through an inbox of 16 folds every element (backpressure, no loss); a stopped source interrupts its ZIO (`onInterrupt` runs); `TestClock` drives `poll` (records the answer to the UNVERIFIED item in 4.2) |
| **Z3 examples and live kit** | `examples/ziocounter`, `examples/ziodashboard` + tests; `tui4s.interop.zio.testkit.LiveTuiTest` | example tests; live kit on Headless |

### 8.4 tui4s-cats (starts after C3 for K1, after C5 for K2; parallel to zio in another worktree)

| PR | Content | Tests |
| --- | --- | --- |
| **K1 exports and bridge** | `exports.scala`, vendored `CatsBridge` with the header of 4.3, the no-Kyo signature check | the 13 `CatsBridgeSuite` tests; the F10 regression (100 cancels from a foreign thread through `Dispatcher.parallel(await = false)`, no hang) |
| **K2 sources, runner, app** | `Source` / `Source.For[F, S]`, `Tui.run[F]` + `runWith`, `Tui.resource`, `TuiApp` (starvation checker off, scheduler flags) | `CatsFacadeSuite` (6.3); fs2 backpressure (10 000 elements, inbox 16); a stopped source cancels its IO (`onCancel` runs); a cancelled `Tui.resource` use restores before the release returns |
| **K3 examples and live kit** | `examples/catscounter`, `examples/catsdashboard` + tests; `tui4s.interop.cats.testkit.LiveTuiTest` | example tests |

### 8.5 Documentation (after C6, Z3, K3)

On the existing `docs/docus` branch: getting started (one page per ecosystem, the JVM flags of 5.15), concepts
(state, `update`, `view`, sources, tasks, keymaps, focus), testing (levels, `TuiTest`, live kit), performance
(frame rules, `Stats`, the trace file, the scheduler flags), troubleshooting (the import rule of 2.12, the
4096 chunk trap, `NotForeign` messages, `kyo.Counter`-style name clashes). Code samples are the files of
section 3, included from `examples/` so they cannot drift.

---
## 9. Open questions for the user

**Decided by the user on 2026-10-01** (these override the table below and any older text):

- Q1: the facade packages are **`tui4s.interop.zio`** and **`tui4s.interop.cats`** (renamed everywhere in this
  spec). `import tui4s.*` can no longer shadow the real `zio` / `cats` packages. Library code still writes
  `_root_.zio` / `_root_.cats` where a name could be ambiguous.
- Q2: output capture is **on by default** (`captureOutput = true`, opt-out in `TuiOptions`).
- Q3: `TuiApp` **sets the scheduler flags** when they are unset (property and env var), opt-out
  `-Dtui4s.schedulerDefaults=false`.
- Q4, Q5, Q6: the proposed defaults (kyo-test for core, munit for the rest; a `bench` project with sbt-jmh;
  the test kit inside core).

Each has a proposed default. The implementation follows the default unless the user decides otherwise.

| # | Question | Proposed default | Why |
| --- | --- | --- | --- |
| Q1 | Facade package names: keep `tui4s.interop.zio` / `tui4s.interop.cats`, or rename (for example `tui4s.ziotui`) to remove the shadowing trap of `import tui4s.*`? | **Keep** `tui4s.interop.zio` / `tui4s.interop.cats`, with the rule "facade users import only the facade package" (2.12) | Natural names; the trap needs both `import tui4s.*` and a facade module on the classpath, which facade users do not do. |
| Q2 | Redirect `System.out` / `System.err` while the UI is up (JVM-global), printing the captured lines after the restore? | **On by default** (`captureOutput = true`), opt-out in `TuiOptions` | Any stray `println` from user code or a library corrupts the screen; capture makes the default safe. |
| Q3 | Should `TuiApp` set the Kyo scheduler flags (idle CPU ~13% -> 4-8% of a core) when the user did not set them? | **Yes**, only for unset keys (property and env var), opt-out `-Dtui4s.schedulerDefaults=false` | The idle cost of a TUI that waits for keys is visible to users (laptop fans); the flags never override an explicit user choice. |
| Q4 | Test framework for core: kyo-test or munit? | **kyo-test** for core; munit for zio, cats and examples (6.4) | Core tests are Kyo computations; kyo-test is released with Kyo. Fallback to munit if the RC runner misbehaves. |
| Q5 | Add an unpublished `bench` project with the sbt-jmh plugin? | **Yes** (7.4) | The performance criterion (25%) needs numbers per release; JMH is the standard for the micro part. |
| Q6 | Keep the test kit inside `tui4s-core` (`tui4s.testkit`) or publish a separate `tui4s-testkit` artifact? | **Inside core** | It needs only jatatui-core's `TestBackend`, which core already depends on; one artifact fewer to version and release. |

---

## Appendix A. What the validator compiles first

These are the cheapest ways to falsify the API of section 2. Each item names the fallback if it fails.

1. `type Next[S] = S | Quit.type | Next.Then[S]` (invariant), `case object Quit { def apply[S](last: S): Next[S] }`,
   `def update(n: Int, e: Event): Next[Int] = keys(n, e)`, a `match` that mixes `n + 1`, `Quit`, `Quit(n)`,
   and kyo-test / munit equality on the union. Fallback: none expected.
   (validated: compiles and passes with munit; `Quit(v)` from a fold returns `v` from the run.)
2. `protected final def bind(key: Key | Char, help: String)(action: S => Next[S])` in `App[S]` with `_ + 1`,
   `_ => Quit` and an eta-expanded private method; `Bind[Int]('a', "x")(identity)`; does `Bind('a', "x")(identity)`
   infer `S` from an expected `Bind[Int]`? Fallback: always write the type argument for apps built as values.
   (validated: all compile; `val b: Bind[Int] = Bind('a', "x")(identity)` infers `S`.)
3. `Keymap.unapply` used as `case active(action) => action(t)` with a local `val active`. (validated: works.)
4. `View` nodes nested in `object View` as `final case class X private[tui4s] (...)`; `text(...).centered.size(1)`;
   `column(...).flex(Flex.Center).bordered(" t ")`; `.or(Key.BackTab)` on `Bind`. (validated: all compile; a case
   field may overload an inherited View method: `Gauge.ratio: Double` next to `View.ratio(num, den)`.)
5. Opaque `Line` with nested opaque `Line.Span`; String and Span extensions with the same names, defined
   where the alias is transparent and re-exported through `dsl`; `n.toString.bold.yellow`,
   `line("✔ ".green, title.dim.crossedOut)`, `text("Count: ", span)` with `(String | Line.Span)*`. (validated: works
   when the ops objects are nested in the companions, 2.7.)
6. `extension (s: Style) def fg(c: Color): Style` outside the opaque scope next to `object Style { val bold ... }`.
   (validated: FAILED as a separate top-level object (no see-through); works as `Style.StyleOps` nested in the
   companion; `Style.bold.fg(Color.Red)` then resolves to the extension, never to the record accessor.)
7. `export dsl.*` at the top level of package `tui4s`; `export tui4s.dsl.*` in `tui4s.interop.zio` (extension
   forwarders, including `withTask`); `export tui4s.{App, Key, Quit, ...}` from a package, else the alias +
   stable `val` file of 4.1; the patterns `Key.Char('q')`, `Quit`, `Resize(w, h)` through the facade.
   (validated: all work; the export from a package is accepted, so the alias file is not needed.)
8. `t.copy(...).withTask(source.task(Abort.run[IOException](save(t.items)))(_.saved(_)).id("save"))` infers
   `S = Todo` and `A = Result[IOException, Unit]`. (validated: infers.)
9. `Source.For[S].task[A](task: => A < (Async & Abort[Throwable]))(using NotForeign[A])(f: (S, A) => Next[S])(using Frame)`:
   the explicit fold list after a using clause; `poll(250.millis)(Random.nextDouble)(_.sample(_))`;
   `stream(requests)(_.log(_))` with a `Stream[String, Async]` argument. (validated: all infer; a
   `Double < Sync` fits `A < (Async & Abort[Throwable])`; the infix form `_ sample _` warns, 3.)
10. The `NotForeign` macro (accepts `Int`, `Result[E, A]`, case classes; rejects `zio.ZIO`, `cats.effect.IO`,
    `fs2.Stream`, `zio.stream.ZStream`, `scala.concurrent.Future` by full name) and `CallSite` as an inline given
    over `summonInline[kyo.Frame]` (the position must be the user's call site). (validated: rejects `zio.ZIO` and
    `Future` with the message, also through munit `compileErrors`, but only when the fold type-checks (2.8); needs
    a public `NotForeign.Evidence`; `CallSite` and core labels are the user's `File.scala:line` when the macros
    come from the jar.)
11. `Sync.acquireReleaseWith(open)(_.close())(use)` with `use: S < (Async & Abort[TuiError])` (a `ConcreteTag`
    for an enum that extends `Exception`), and `Sync.ensure(stopAll)(loop)`; then the F8 timing test with this
    exact shape (restore done when `interruptAwait` returns). (validated: compiles with no annotation, ConcreteTag
    found; the log `enter, input-started, input-stopped, input-joined, leave-raw-mode` is complete when
    `interruptAwait` returns (Kyo), when `fiber.interrupt` returns (ZIO, F11) and when `fiber.cancel` returns
    (CE, F8).)
12. `RenderLoop` with `Loop(drawnAt) { ... }` returning `Loop.continue` / `Loop.done` under
    `Async & Abort[TuiError]`, with `Abort[Closed]` from `take` / `drain` handled. (validated: compiles; the two
    helpers that return a `Loop` step need the result type `Loop.Outcome[Long, S] < (...)` written out.)
13. Supervisor calls: `Fiber.initUnscoped` (Isolate derivation for `Async & Abort[Throwable]`),
    `fiber.unsafe.onComplete`, `fiber.unsafe.interrupt()`, `inbox.unsafe.putFiber`, `putBatchFiber`, `offer`,
    `close()`. (validated: all compile and run; `Fiber.initUnscoped` keeps the Kyo Locals, which the facades'
    ambient runtime and `Clock.withTimeControl` both rely on; `Fiber.Unsafe.init` is detached and would not.)
14. `abstract class TuiApp[S] extends ZIOAppDefault, tui4s.App[S]` and `extends IOApp.Simple, tui4s.App[S]`: no
    member clash (`init`, `view`, `update`, `sources`, `options`, `bind`, `source`, `run`, `onExit`);
    `override val bootstrap = Runtime.removeDefaultLoggers`; the `IORuntimeConfig` starvation field. (validated: no
    clash in either; `copy(cpuStarvationCheckInitialDelay = Duration.Inf)` VERIFIED, 4.3.)
15. `object CounterApp extends TuiApp[Int]` gets a static `main` forwarder from the inherited `final def main`.
    (validated: `javap` shows `public static void main(java.lang.String[])`; `scala-cli run` finds it.)
16. `Clock.withTimeControl` + `awaitPendingSleepers(n)` + `advance(step, Duration.Zero)` stepping (6.2).
    (validated: the API compiles; the real loop's sources follow virtual time; V8 confirmed; the live kit itself
    (`TuiTest.live`, `LiveTest.settle`) is NOT built in the skeleton, see 6.2 and 10.)
17. The exact watcher: `ref.unsafe.next()`, `ref.unsafe.get()`, `p.safe.get` on a `SignalRef`. (validated: compiles;
    not exercised by a test.)
18. Sample-level names: Kyo `Result.Success(x)` pattern, `Result.succeed`, `Abort.catching[IOException]`,
    `Stream.unfold(acc, chunkSize = 1)`; ZIO `ZStream.iterate`, `Schedule.spaced`, `Random.nextDouble`;
    fs2 `Stream.iterate(...).covary[IO].metered`. (validated: all right, by compile and headless runs.)
19. `StaticFlag`: the env var form of a scheduler flag (read the `StaticFlag` source in the Kyo jar).
    (validated: `kyo.StaticFlag` is in `kyo-config`; the property is read first, then the env var
    `name.replace('.', '_').toUpperCase`, e.g. `KYO_SCHEDULER_CYCLEINTERVALNS`; the three names of 5.14 exist.
    VERIFIED, so 5.14's "UNVERIFIED" note on the env form is resolved.)

## Appendix B. Known risks

| # | Risk | Mitigation |
| --- | --- | --- |
| R1 | Concurrent JNI use: the input thread calls `poll`/`read` while the render fiber calls `enqueue`/`flush`. crossterm's Rust side has separate reader and writer state (UNVERIFIED for this build); the inline viewport's cursor query is the riskiest case. | Real-TTY test in C5/C6. Fallback: one "terminal thread" that does both input and output, fed with finished frames by the render fiber. |
| R2 | jatatui (0.x, one maintainer) and Kyo (RC) are pre-1.0. | jatatui types appear only in three escape hatches; Kyo is pinned in `Versions.scala`; a Kyo bump is a minor tui4s release (`Compatibility.None`). |
| R3 | Position identity: a conditional view placed before a list resets its scroll offset. | `.id(name)`; documented next to `list`. |
| R4 | Call-site identity: sources built in one helper share a label (position fallback `#n`); a source whose parameters change but whose label does not keeps the old fiber. | Rule "put changing parameters in `.id(...)`" in the docs and the `Source` scaladoc; the trace shows starts and stops. |
| R5 | The scheduler flags are ignored if Kyo's scheduler started before `TuiApp` (idle stays ~13%). | Set in the earliest constructor; `-D` flags documented for `KyoApp` users; `IdleProbe` checks it. |
| R6 | Output capture is JVM-global. | Opt-out option; streams restored by the synchronous release, also on failure. |
| R7 | Kyo stream chunking (4096) hides elements of `unfold` / `repeatPresent` streams. | Docs, samples, a troubleshooting entry; nothing tui4s can fix upstream. |
| R8 | kyo-test is an RC. | Fallback to munit for core (6.4). |
| R9 | Union-type errors are less friendly; `withTask` is an extension on every type. | Samples always write `: Next[S]`; the extension only type-checks where a `Next[S]` is expected in practice; docs show the common errors. |
| R10 | Every visible change paints the whole frame (~2 ms at 120x40; more on very large terminals). | Levels 1 and 2 avoid most frames; phase-2 `cached` / retained presenter when a benchmark shows the need. |
| R11 | `import tui4s.*` with a facade module on the classpath shadows `zio` / `cats`. | The import rule (2.12); library code uses `_root_`. |
| R12 | crossterm truncates non-BMP keys typed on the keyboard. | Bracketed paste on by default; documented. |
| R13 | Cats: one `F` per app is a run-time rule. | `TuiApp` fixes `IO`; documented for `Tui.run[F]`. |
| R14 | Live-test `advance` steps in 1 ms increments (cost and resolution). | Most source tests use the synchronous `emit`; `advance` takes an explicit `sleepers` count when the default is wrong. |

---
## 10. Validation

Done on 2026-10-01 by the validator (one compile at a time, `scala-cli --server=false`, JDK 25.0.4, Scala 3.9.0,
Kyo 1.0.0-RC7, jatatui 0.31.0, ZIO 2.1.26 + kyo-zio RC7, cats-effect 3.7.1, fs2 3.14.0; scalac options
`-Yexplicit-nulls -Wunused:all -Wvalue-discard -Wnonunit-statement -deprecation -feature`). Attempt-by-attempt
log: `.work/design/VALIDATION.md`. The SPEC text before validation: `.work/scratch/design-validate/out/SPEC.before-validation.md`.

### 10.1 Result

**The design holds.** Every public signature of section 2 compiles. The three Kyo samples and the ZIO and Cats
Counter and Dashboard samples compile with the imports of section 3, and ZIO / Cats user code needs no Kyo type.
The update path and the render path run headless on a jatatui `TestBackend`, both in the synchronous `TuiTest`
and in the real loop (input thread, inbox, supervisor, source fibers, synchronous release). No F1 stack overflow:
the skeleton and the samples compile from clean without `-Xss`.

| Step | What | Result |
| --- | --- | --- |
| 1 | tui4s-core skeleton: all of 2.3-2.11, real `Engine` / `Painter` / `Supervisor` / `RenderLoop` / `InputThread`, `spi.Headless`, `Tui.run` over crossterm (compiled; run only to the `NotATerminal` path) | compiles, 0 warnings |
| 2 | Kyo Counter / Todo / Dashboard (3.1-3.3) + tests | 28 tests pass: the 6.3 sync tests (Counter, Todo, Dashboard, Guard), real-loop tests (Counter keys -> frames -> Quit; Dashboard poll + stream + pause; restore order on interrupt; `TuiHandle`; `SourceFailed`; keyed tasks), a name-clash check with `import kyo.*`, a virtual-time probe |
| 3 | tui4s-zio facade + ZIO Counter / Dashboard (3.4) | 7 tests pass: Counter headless via `Tui.runWith` (`IO[TuiError, Int]`), caller `FiberRef` seen by a ZIO task source (F12), interrupt-and-await restore (F11), ZIO Dashboard, call-site labels, `Tui.start` |
| 4 | tui4s-cats facade + Cats Counter / Dashboard (3.5) | 8 tests pass: Counter headless via `Tui.runWith[IO]`, task on `io-compute`, cancel-and-await restore (F8), Cats Dashboard, call-site labels, `Tui.resource`, starvation checker off |

Total: **43 of 43 tests pass** (`out/samples-final.log`, `out/zio-final.log`, `out/cats-final.log`).

### 10.2 What the validation changed in this SPEC (each marked "(validated: ...)" in place)

1. 2.3: `App.apply`'s default `sources` must be `(_: S) => Seq.empty[Source[S]]` (a `Nil` default fails when omitted).
2. 2.7: the extension objects live inside the opaque types' companions (`Style.StyleOps`, `Line.SpanOps`); a
   separate top-level object does not see through a top-level opaque type.
3. 2.2 / 2.12: library code writes `tui4s.internal.X` (`kyo.internal` shadows `internal` under `import kyo.*`).
4. 2.8: `NotForeign` needs a public `NotForeign.Evidence`; its error shows only when the fold type-checks.
5. 2.9: `TuiHandle.states` starts at `init`; `stats` is a snapshot.
6. 2.10: `Screen` skips the trailing cells of wide graphemes and reads `Reset` colors as unset; the test kit has
   `ScriptedInput`; chained `TuiTest` calls as a statement warn under `-Wnonunit-statement`.
7. 2.11: SPI adds `Sources.makeTask` and `Sources.makePeriodic` (the kind of a facade source matters).
8. 3.x / 6.3 / App. A: folds written `_.sample(_)` (infix `_ sample _` warns in Scala 3.9); Todo keymaps put
   the pane keys first (`Keymap(...) ++ global`), else the help bar cuts "d delete"; ZIO test wraps
   `FiberRef.make` in `ZIO.scoped`.
9. 3.4 / 3.5 / 4.3 / 5.14 / App. A 18-19: the UNVERIFIED ZIO, fs2, `IORuntimeConfig` and `StaticFlag` env-var
   items are now VERIFIED.
10. 4.1: the facades use `export tui4s.{...}` from a package (accepted by Scala 3.9); the alias file is not needed.
11. 4.2: `TuiHandle` methods take `(using Trace)`; facade `every` uses `Sources.makePeriodic` with the user's
    call site; core needs `Runner.startUnscoped`.
12. 5.4: the pacing `Async.sleep` is virtual under `Clock.withTimeControl`, so the live kit must force `fps = 0`.
13. 5.7: `help(keys)` cannot honour `Theme.helpKey` / `helpText` as a plain `View.Text` (open for C2).
14. 5.8: the non-`SignalRef` watcher uses `current` + `next` (no `Tag` for `streamChanges`).
15. 5.9 / 5.15: `TuiApp.main` = `KyoApp.Unsafe.runAndBlock(Duration.Infinity)(...)`; `-Wconf` for Kyo's
    position-less `@nowarn` warnings; `-Xss` not needed for app code.
16. 6.2: V8 confirmed by a probe (one `advance` = one tick per sleeper; the real loop follows virtual time).
17. 8.2 checklist: on JDK 25 a missing `--enable-native-access` only warns; `NativeAccessDenied` needs
    `--illegal-native-access=deny`.

### 10.3 Not validated (still open for the implementation PRs)

- The live test kit: `TuiTest.live`, `liveBlocking`, `LiveTest.settle` / `advance` bodies are `???` in the
  skeleton (only the virtual-time behaviour under them was probed); `TuiTest.replay`; the trace file writer
  (only the in-memory `lastTrace` line format exists).
- Output hygiene (5.10: `Log.let` / `TuiLog`, `System.out` capture), shutdown hooks, the `slow` trace line.
- The real terminal: `CrosstermControl` and `EventMapping` compile but ran only to `NotATerminal` (no TTY here);
  risk R1 (concurrent JNI reader and writer) and the inline viewport still need the real-TTY checklist.
- The `Table` node, mouse hit-tags, `Popup`, `Stateful` slots and the `SignalRef` watcher compile but have no test.
- kyo-test as the core framework (the skeleton uses munit); the tasty-query Kyo-free CI check; the facade
  live kits (4.4); the performance budgets of 7.3.

### 10.4 The skeleton

`.work/scratch/design-validate/` (scala-cli projects; the core is packaged to `out/tui4s-core.jar` so that the
samples and facades compile against a jar, as the real modules will):

| Path | Content |
| --- | --- |
| `core/` | tui4s-core skeleton: `tui4s/{App,Event,Keymap,Styling,View,dsl,Source,Tui}.scala`, `tui4s/spi/`, `tui4s/testkit/`, `tui4s/internal/{Engine,Painter,Supervisor,Runner,Runtime,Items,Macros}.scala` |
| `samples/` | Kyo samples (`examples/{counter,todo,dashboard}`), `clash/NameClash.scala`, `tests/` (munit) |
| `zio/` | tui4s-zio facade (`tui4s/zio/{exports,Facade}.scala`, vendored `internal/AmbientZIOs.scala`), ZIO samples, tests |
| `cats/` | tui4s-cats facade (`tui4s/cats/{exports,Facade}.scala`, vendored `internal/CatsBridge.scala`, `internal/AmbientDispatcher.scala`), Cats samples, tests |
| `out/` | compile and test logs, `tui4s-core.jar`, the pre-validation SPEC copy |

Re-run (one at a time): `source .work/env.sh && export JDK_JAVA_OPTIONS=-Xss16m && cd .work/scratch/design-validate`,
then `scala-cli --power package --server=false core --library -f -o out/tui4s-core.jar`, then
`scala-cli test --server=false samples`, `... zio`, `... cats`.
