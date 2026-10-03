# tui4s design candidate: fine-grained reactive signals ("signals")

Status: COMPLETE (all 8 sections, consistency pass done). Nothing compiled; see 8.4 for UNVERIFIED items.
Author: design panel, direction "fine-grained reactive signals (SolidJS / kyo-ui style)". Date: 2026-10-01.
Legend: **VERIFIED** = read in the RC7 / jatatui 0.31 sources or in a probe log. **UNVERIFIED** = not checked
(needs the validator). Nothing in this file was compiled.

## Outline

1. Pitch and core idea
2. Concepts and public API of tui4s-core
3. The three sample apps (Kyo)
4. Counter and Dashboard for ZIO and Cats Effect users (facades)
5. Runtime model
6. Testing and agent-friendliness
7. Performance analysis
8. Weaknesses, risks and open questions

---

## 1. Pitch and core idea

**Pitch (5 lines).**
State is plain Kyo `SignalRef`s. A view is an immutable tree that you build **once**; its dynamic parts are
*bindings* `ref.view(a => widget)`. The runtime mounts the tree into a retained frame buffer. When a signal
changes, only the bindings that read it are recomputed, only their rectangles are repainted, and only the
rows that they touch are diffed and sent to the terminal. No signal changed = no work, no frame.

**Core idea in one picture.**

```
  SignalRef[Int] count ─┐        (Kyo SignalRef: atomic value + change promise; written by handlers,
  SignalRef[Metrics] m ─┼─wake─┐  tick fibers, streams, ZIO/CE fibers ... any fiber)
  Tui.size ─────────────┘      │
                               v
   static View tree (built once)                     mounted tree (render fiber owns it)
   column(                                           ┌──────────────────────────────────┐
     count.view(n => text(s"Count: $n")) ──bind──>   │ Bind#1 rect=(1,1,38,1) last=41   │──> repaint rect only
       .boxed(" Counter ").len(3),                   │ Boxed (static, painted once)     │
     text("q quit").dim                              │ Leaf  (static, painted once)     │
   )                                                 └──────────────────────────────────┘
                                                               │ dirty rows {1}
                                                               v
                                    canvas Buffer ──row diff──> shown Buffer ──> Backend.draw(k cells)
```

What makes it "fine-grained":

1. **Components run once.** `view` functions are ordinary Scala functions that run at mount time. There
   is no re-render of the whole tree (that is the Elm / React model). Only the body of a *binding*
   (`ref.view(f)`, the SolidJS "reactive expression") runs again, and only when the value it reads is
   different (Kyo signals deduplicate equal writes; tui4s compares again after projection).
2. **Retained terminal surface.** jatatui's `Terminal.draw` resets the back buffer every frame (VERIFIED:
   `Terminal.swapBuffers` calls `reset()`), so ratatui-style apps must repaint everything (~2.1 ms and
   ~1.7 MiB per 120x40 frame, even when nothing changed). tui4s keeps its own retained `canvas` buffer,
   repaints only the rectangles of changed bindings, and diffs only the dirty rows.
3. **Kyo-native state.** The state type is Kyo's own `SignalRef[A]`: any Kyo code (fibers, streams,
   `observe`, `streamChanges`) can read and write it. tui4s adds no store, no message type, no reducer.
   ZIO and Cats users get thin `SignalRef` facades over the same object (section 4).
4. **Pull on wake, not push per expression.** Kyo signals are not a dependency graph (`Signal.map` is a
   lazy view with no cache, VERIFIED in `Signal.scala`). So tui4s does not try to propagate values through
   a graph. Per distinct source `SignalRef` it parks **one** watcher fiber whose only job is to wake the
   render fiber. The render fiber then *pulls*: it reads the current value of every mounted binding
   (a few ns each), and recomputes the ones that differ. This is deterministic (one consistent read
   after all handlers of a batch), glitch-free inside a frame, and makes headless tests exact.
5. **Structure is data too.** `tab.view { case 0 => overview; case _ => logs }` is a *structural*
   binding: when it changes, the old subtree is unmounted (its watchers stop, so invisible content costs
   nothing), the new subtree is mounted and laid out in the same rectangle.

What the user writes (the whole Counter app, Kyo):

```scala
object Counter extends TuiApp:
    def view(tui: Tui, count: SignalRef[Int]): View =
        column(
            count.view(n => text(s"Count: $n").bold).boxed(" Counter ").len(3),
            text(" + / -  change    q  quit").dim
        ).onKey {
            case Key.Char('+') => count.update(_ + 1)
            case Key.Char('-') => count.update(_ - 1)
            case Key.Char('q') => tui.quit
        }

    val app: Tui => View < (Async & Scope) = t => Signal.initRef(0).map(view(t, _))

    tui(app)
end Counter
```

Prior art used: kyo-ui (`signal.render(f)` regions, one observer per region, per-value `Scope`,
`resolveReactives` for static snapshots; VERIFIED in `kyo-ui/kyo/UI.scala` and
`internal/ReactiveUI.scala`), SolidJS (run-once components, reactive expressions), jatatui-react (fiber
identity by position + key, paint-order hit testing, `Renderer` / runner / `TestHarness` split).
What is different from kyo-ui: kyo-ui patches a retained DOM; a terminal has no retained tree, so tui4s
retains the *cells* (canvas buffer) and the *mounted tree* itself, and it batches all region updates into
one frame on one fiber (kyo-ui runs one observer fiber per region that renders independently).

---

## 2. Concepts and public API of tui4s-core

### 2.1 Concepts (seven nouns)

| Concept | Type | Role |
| --- | --- | --- |
| State | Kyo `SignalRef[A]` (no wrapper) | One value that can change. Any fiber can write it. Equal writes are no-ops (Kyo dedup). |
| View | `tui4s.View` (immutable, sealed) | A description of the screen. Built once by plain functions. |
| Source | `tui4s.Source[A]` | What a binding reads: root signal(s) + a pure projection. Made by `ref.at(_.x)`, `focus.is(id)`, `tui.size`, or by the ZIO / Cats facade signals (they extend it). Kyo-free type. |
| Binding | `signal.view(a => View)` / `source.view(...)` | The only dynamic node. Recomputed when the value it reads changes. |
| Handler | `.onKey { case Key.Char('q') => ... }` | A partial function from an event to an *action* (`Unit < Async` for Kyo). Runs on the render fiber, in event order. |
| Focus | `tui4s.Focus[A]` (wraps a `SignalRef[A]`) | Which focusable subtree gets keys first. Tab / BackTab cycle it when no handler takes them. |
| Tui | `tui4s.Tui` (handle) | The running app: `quit`, `size`, `spawn`, `log`, `stats`. Given to the app function. |

Rules of the model (they are also the rules an agent must follow):

1. View functions and binding bodies are **pure** (`A => View`): no effects, no mutation.
2. All effects live in handlers, in fibers that the app starts in its setup, or in `tui.spawn`.
3. State is immutable values in `SignalRef`s (`derives CanEqual`). Put things that change together in one
   ref (one write = one consistent frame); put things that change independently in separate refs (each
   gets its own repaint).
4. Put bindings as close to the leaves as you can. A binding at the root is correct but it repaints its
   whole rectangle on every change (it degrades to the Elm model).

### 2.2 Packages and modules

| Package | Module | Contents |
| --- | --- | --- |
| `tui4s` | tui4s-core | `View`, `Split`, the DSL functions, `Source`, `Key`, `Mouse`, `Event`, `Focus`, `TextField`, `IntoAction`, `Tui`, `TuiApp`, `TuiConfig`, `TuiError`, signal syntax (`view`, `at`, `source`, `update`, `modify`) |
| `tui4s.testkit` | tui4s-core | `TestTui` (headless runtime on jatatui `TestBackend`), `Screen` assertions |
| `tui4s.internal` | tui4s-core | mount tree, reconciler, presenter, input fiber, watcher registry (not public API) |
| `tui4s.zio` | tui4s-zio | `SignalRef`, `Signal`, `Focus`, `Tui`, `TuiZIOApp`, `TestTui`, `given IntoAction[ZIO[...]]`, re-export of the Kyo-free DSL |
| `tui4s.cats` | tui4s-cats | `SignalRef[F, A]`, `Signal[F, A]`, `Focus[F, A]`, `Tui[F]`, `TuiIOApp`, `TestTui`, `given IntoAction[F[Unit]]`, re-export of the Kyo-free DSL |

The DSL is **Kyo-free on purpose**: widget parameters use `Seq`, `Option`, `Int`, `Double`, `String` and
jatatui value types (`Style`, `Color`, `Line`, `Constraint`, `BorderType`), never `Chunk`, `Maybe` or
`Duration`. Only the signal syntax (on Kyo `Signal`) and the runtime (`Tui.run`) carry Kyo types. That is
what lets the facades re-export the same DSL to ZIO and Cats users. Reactive *arguments* of the DSL
(`boxed(active = ...)`, `when(...)`) take the Kyo-free `Source[A]`, which the facade signals extend. The
only Kyo type left in a signature a facade user calls is `(using Frame)` on `Source.view`; Kyo's `Frame` is
an inline given that is materialized at the call site, so it never appears in user code (and it gives
error messages the user's file and line). The DSL lives in `object tui4s.Dsl`, which holds the DSL
functions *and* type aliases / forwarders for the shared types (`type View = tui4s.View`,
`val Key = tui4s.Key`, ...). Package `tui4s` and the facade packages re-export it with one
`export tui4s.Dsl.*` (exporting object members; whether `export` of package members works is UNVERIFIED,
so the design does not rely on it).

jatatui value types are used directly (they are immutable with structural equality, VERIFIED in the
jatatui report section 3.0). Users import them from jatatui (`import jatatui.core.style.{Color, Style}`);
most apps need only `Color`, because the View extensions (`.fg`, `.bold`, `.boxed`) cover the common
cases. Whether tui4s can re-export them with `export` (Java classes, static members) is UNVERIFIED.

### 2.3 Public API (signatures)

```scala
package tui4s

import jatatui.core.buffer.Buffer
import jatatui.core.layout.{Constraint, Flex, HorizontalAlignment, Rect, Size}
import jatatui.core.style.{Color, Style}
import jatatui.core.text.Line
import jatatui.core.widgets.Widget
import jatatui.widgets.block.BorderType
import kyo.*
import scala.concurrent.duration.FiniteDuration

// ---------------------------------------------------------------- View: an immutable description
sealed trait View:
    // size inside the parent column/row (default: Fill(1))
    def len(cells: Int): View
    def fill(weight: Int = 1): View
    def pct(percent: Int): View
    def min(cells: Int): View
    def max(cells: Int): View
    def ratio(num: Int, den: Int): View
    def size(c: Constraint): View
    // decoration and style (style = base style of the subtree, applied before children paint)
    def boxed(title: String = "", border: BorderType = BorderType.Rounded, style: Style = Style.empty()): View
    def boxed(title: String, active: Source[Boolean]): View // border is highlighted while `active`
    def style(s: Style): View
    def fg(c: Color): View
    def bg(c: Color): View
    def bold: View
    def dim: View
    def reversed: View
    def margin(horizontal: Int, vertical: Int): View
    // events (R = the action type; see IntoAction)
    def onKey[R](handler: PartialFunction[Key, R])(using IntoAction[R]): View
    def onMouse[R](handler: PartialFunction[Mouse, R])(using IntoAction[R]): View
    def onClick[R](action: => R)(using IntoAction[R]): View
    def onPaste[R](handler: String => R)(using IntoAction[R]): View
    def focusable[A](focus: Focus[A], id: A): View
    // identity: keeps runtime-owned widget state (ListState offset...) across a remount
    def key(k: String): View
end View

/** A column or a row; extra layout options only make sense here, so they live here. */
final class Split private[tui4s] (/* direction, children, gap, flex, margin */) extends View:
    def gap(cells: Int): Split
    def flex(f: Flex): Split

object View:
    val empty: View
    /** Any immutable jatatui widget, painted into the node's rectangle. */
    def widget(w: Widget): View
    /** Escape hatch for widgets with mutable state (TableState, ScrollbarState...). `init` runs once per
      * mount; the runtime owns `S` and only the render fiber touches it. */
    def stateful[S](init: => S)(paint: (Rect, Buffer, S) => Unit): View
    /** Same as `signal.view(render)`. */
    def bind[A](signal: Signal[A])(render: A => View)(using Frame): View
    /** Static snapshot: resolve every binding to its current value and paint once (tests, docs). */
    def snapshot(view: View, width: Int, height: Int)(using Frame): Chunk[String] < Sync

// ---------------------------------------------------------------- layout and widgets (package-level DSL)
def column(children: View*): Split
def row(children: View*): Split
def layers(children: View*): View                                  // later children paint on top
def centered(width: Constraint, height: Constraint)(child: View): View   // popups (uses Rect.centered)
def when(cond: Source[Boolean])(child: => View)(using Frame): View

def text(content: String): View
def text(line: Line): View
def paragraph(content: String, wrap: Boolean = true, align: HorizontalAlignment = HorizontalAlignment.Left,
              scrollY: Int = 0): View
def gauge(ratio: Double, label: String = "", color: Color = Color.CYAN): View      // ratio clamped to 0..1
def sparkline(data: Seq[Long], max: Option[Long] = None, color: Color = Color.GREEN): View
def tabs(titles: Seq[String], selected: Int, highlight: Style = Style.empty().reversed()): View
def items(rows: Seq[String | Line], selected: Option[Int] = None,
          highlight: Style = Style.empty().reversed(), symbol: String = "› "): View  // jatatui List + ListState
def input(field: TextField, placeholder: String = ""): View        // places the cursor when focused
def logView(lines: Seq[String], follow: Boolean = true): View      // Paragraph scrolled to the end

// ---------------------------------------------------------------- Source: what a binding reads
/** A readable, watchable value. Kyo-free type name, so ZIO / Cats facades can extend it.
  * Internally: the root signals to watch + a pure read function + CanEqual for the projected value.
  * `metrics.at(_.load).view(gauge(_))` recomputes only when `load` changes, and the watcher stays on the
  * root `SignalRef` (exact wake, no repair timer, see 5.3). */
abstract class Source[A] private[tui4s] ():
    def view(render: A => View)(using Frame): View            // Frame: Kyo's call-site position (implicit)
    def at[B](select: A => B)(using CanEqual[B, B]): Source[B]
    def zip[B](other: Source[B]): Source[(A, B)]
    def signal(using Frame): Signal[A]                         // Kyo view of the same value (Signal.initRaw)

// ---------------------------------------------------------------- reactive syntax over Kyo signals
extension [A](signal: Signal[A])
    def source: Source[A]                                      // SignalRef: exact watcher; other Signal: observe
    def view(render: A => View)(using Frame): View             // = signal.source.view(render)
    def at[B](select: A => B)(using CanEqual[B, B]): Source[B]

extension [A, B](signals: (Signal[A], Signal[B]))
    def view(render: (A, B) => View)(using Frame): View        // = a.source.zip(b.source).view(render.tupled)

extension [A](ref: SignalRef[A])
    def update(f: A => A)(using Frame): Unit < Sync           // = ref.getAndUpdate(f).unit
    def modify[B](f: A => (B, A))(using Frame): B < Sync      // CAS loop on ref.unsafe.compareAndSet

// ---------------------------------------------------------------- events
enum Key derives CanEqual:
    case Char(c: scala.Char)          // printable; Shift is already applied by the terminal
    case Ctrl(c: scala.Char)
    case Alt(c: scala.Char)
    case Enter, Esc, Tab, BackTab, Backspace, Delete, Insert
    case Up, Down, Left, Right, Home, End, PageUp, PageDown
    case F(n: Int)
    case Unknown(description: String)

final case class Mouse(kind: Mouse.Kind, x: Int, y: Int) derives CanEqual  // x, y relative to the node's rect
object Mouse:
    enum Button derives CanEqual:
        case Left, Right, Middle
    enum Kind derives CanEqual:
        case Down(button: Button)
        case Up(button: Button)
        case Drag(button: Button)
        case Moved, ScrollUp, ScrollDown, ScrollLeft, ScrollRight

/** Evidence that a handler result `R` can run as a UI action. Contravariant, so one instance covers every
  * subtype: `Unit < Sync`, `Unit < Async`, plain `Unit` all use the Kyo instance. */
@scala.annotation.implicitNotFound(
    "A tui4s handler must return Unit < Async (Kyo), ZIO[Any, Throwable, Unit] (import tui4s.zio.given) " +
        "or F[Unit] (import tui4s.cats.given). Found: ${R}. Use .unit to drop a result.")
trait IntoAction[-R]:
    def action(r: => R)(using Frame): Unit < (Async & Abort[Throwable])
object IntoAction:
    given kyoAction: IntoAction[Unit < (Async & Abort[Throwable])] = KyoAction  // identity

// ---------------------------------------------------------------- focus and text input
final class Focus[A] private[tui4s] (val current: SignalRef[A], val order: Chunk[A]):
    def is(id: A): Source[Boolean]
    def set(id: A)(using Frame): Unit < Sync
    def next(using Frame): Unit < Sync
    def prev(using Frame): Unit < Sync
object Focus:
    def init[A](first: A, rest: A*)(using CanEqual[A, A], Frame): Focus[A] < Sync

/** Immutable single-line editor model. Cursor counts grapheme clusters (not UTF-16 chars). */
final case class TextField(text: String, cursor: Int) derives CanEqual:
    def edit(key: Key): TextField     // Char, Backspace, Delete, Left, Right, Home, End; other keys: no change
    def insert(s: String): TextField  // paste
object TextField:
    val empty: TextField
    def edits(key: Key): Boolean      // true for the keys `edit` consumes (use it as a guard)

// ---------------------------------------------------------------- runtime
trait Tui:
    def quit(using Frame): Unit < Sync
    def size: Source[Size]
    def spawn(task: => Unit < (Async & Abort[Throwable]))(using Frame): Unit < Sync  // forked in the TUI scope
    def log(message: => String)(using Frame): Unit < Sync                           // to TuiConfig.logFile, never stdout
    def stats: Source[FrameStats]
    def unsafe: Tui.Unsafe                       // for the facades: quit()/wake() from any thread

final case class FrameStats(frames: Long, lastFrameMicros: Long, lastDirtyRows: Int, lastChangedCells: Int,
                            lastRecomputed: Int, bindings: Int, watchers: Int) derives CanEqual

object Tui:
    /** Load natives, run `app` (setup) in the TUI scope, enter raw mode + alternate screen, mount, loop.
      * Returns when `quit` is called (or Ctrl-C with TuiConfig.quitOnCtrlC). The terminal is restored in a
      * Sync.ensure, also on failure and on interruption. */
    def run(app: Tui => View < (Async & Scope & Abort[Throwable]), config: TuiConfig = TuiConfig())(using
        Frame
    ): Unit < (Async & Abort[TuiError])
    /** Sugar: a fiber in the current Scope that runs `action` every `period`. */
    def every(period: Duration)(action: => Unit < (Async & Abort[Throwable]))(using Frame): Unit < (Async & Scope)
    /** Default the Kyo scheduler flags for an interactive app (only if not set; see section 5.10). */
    def tuneScheduler(): Unit
    abstract class Unsafe:
        def quit()(using AllowUnsafe): Unit
        def wake()(using AllowUnsafe): Unit

abstract class TuiApp extends KyoApp:
    Tui.tuneScheduler()                          // constructor: before the first fiber starts
    protected def config: TuiConfig = TuiConfig()
    protected def tui(app: Tui => View < (Async & Scope & Abort[Throwable]))(using Frame): Unit =
        run(Tui.run(app, config))                 // KyoApp.run

final case class TuiConfig(
    maxFps: Int = 60,
    mouse: Boolean = false,
    bracketedPaste: Boolean = true,
    quitOnCtrlC: Boolean = true,
    pollEvery: FiniteDuration = FiniteDuration(50, "ms"),
    onError: ErrorPolicy = ErrorPolicy.Stop,
    logFile: Option[java.nio.file.Path] = None,
    tuneScheduler: Boolean = true,
    verifyRepaints: Boolean = false,      // debug: compare partial repaint with a full repaint every frame
    presenter: Presenter = Presenter.Retained,
    input: InputMode = InputMode.FiberPoll
)
object TuiConfig:
    val test: TuiConfig = TuiConfig(maxFps = Int.MaxValue, verifyRepaints = true, tuneScheduler = false)
enum InputMode derives CanEqual:
    case FiberPoll       // Kyo fiber: poll(pollEvery) then read (default; validated by the loop probe)
    case PlatformThread  // daemon thread: lower idle CPU in the probe, ~60 ms slower shutdown
enum ErrorPolicy derives CanEqual:
    case Stop, LogAndContinue
enum Presenter derives CanEqual:
    case Retained   // tui4s canvas + row diff (default)
    case FullFrame  // jatatui Terminal.draw of the whole mounted tree every frame (reference / Inline viewport)

enum TuiError(message: String, cause: Throwable | Null) extends Exception(message, cause):
    case NoTerminal(cause: Throwable)                             extends TuiError("stdin/stdout is not a TTY", cause)
    case NativeAccess(cause: Throwable)                           extends TuiError("run with --enable-native-access=ALL-UNNAMED", cause)
    case Io(cause: Throwable)                                     extends TuiError("terminal I/O failed", cause)
    case SetupFailed(cause: Throwable)                            extends TuiError("the app setup failed", cause)
    case HandlerFailed(event: String, at: String, cause: Throwable) extends TuiError(s"handler for $event at $at failed", cause)
    case RenderFailed(at: String, cause: Throwable)              extends TuiError(s"binding at $at failed", cause)
```

### 2.4 How each concern is expressed

**jatatui widgets.** Each DSL function builds an immutable *leaf* that holds a jatatui widget value and,
for stateful widgets, the *kind* of runtime-owned state it needs. For example `items(...)` holds a
`jatatui.widgets.list.List` plus the selected index; the mounted leaf owns one `ListState` for its whole
life, calls `state.select(...)` and `list.render(area, buf, state)` (VERIFIED names), so the scroll offset
that jatatui writes back survives every repaint. `View.widget(w)` accepts any jatatui `Widget` (or a Scala
lambda, SAM, VERIFIED in the probe); `View.stateful` accepts any `StatefulWidget` with its state. The
user never sees `Buffer`, `Cell`, `Terminal` or a widget state except inside `View.stateful`.
Parameters that jatatui rejects with exceptions (`Gauge.withRatio` outside 0..1, VERIFIED) are clamped.

**Layout.** `column` / `row` map to `Layout.vertical` / `Layout.horizontal` with the children's
constraints (`.len(3)` = `Constraint.Length(3)`, default `Fill(1)`). `Split.gap/flex` map to
`withSpacing/withFlex`. Layout runs at mount, at resize and at remount of a structural binding, never per
frame; it uses jatatui's cached `Layout.split` (bounded LRU, VERIFIED) and only *reads* the shared
`Rect[]` it returns. A binding's rectangle does not depend on its value; for a value-dependent layout,
bind the container: `cols.view(n => row(Seq.fill(n)(cell)*))` (structural binding).

**Styles.** `.style(s)` and the shorthands set the *base style* of a subtree (jatatui semantics: the
container's style is applied to its area before the children paint). The runtime stores the composed base
style per mounted node, so a partial repaint of an inner region re-applies exactly the same styles as a
full repaint would (section 5.4). `.boxed` maps to `Block.bordered().withBorderType(...).withTitle(...)`.

**Events.** The input fiber turns crossterm events into the Scala ADTs above (Press and Repeat keys only).
The render fiber routes each event: keys go first to the handlers on the path from the **focused** node to
the root, then to the root; the first handler whose partial function is defined at the key wins. Unhandled
Tab / BackTab move the focus of the nearest `Focus` group; unhandled Ctrl-C quits (configurable). Mouse
events go to the top-most node under the pointer (reverse paint order) and bubble up. Paste goes to the
focused path. Resize updates `tui.size` and relayouts.

**State.** `Signal.initRef(a)` (Kyo) creates state; `ref.set`, `ref.update(f)` (tui4s syntax),
`getAndUpdate`, `compareAndSet` write it. Reading in a handler: `ref.get`. Views read it only through
bindings. Derived values: `ref.at(_.field)` (preferred, see below) or any Kyo combinator (`map`,
`combineLatest`), which also works.

**Async sources.** Fibers that the app starts in its setup effect run in the TUI scope (`Fiber.init`),
so they stop when the TUI stops. They just write signals; the watcher of that signal wakes the render
fiber. A tick: `Tui.every(250.millis)(metrics.update(_.push(...)))`. A stream:
`Fiber.init(lines.foreach(l => logs.update(_.append(l).takeRight(500))))`. A long task from a handler:
`tui.spawn(task)`. No special message type is needed.

**Start and quit.** `Tui.run(app)` (or `object X extends TuiApp: tui(app)`): the app function gets the
`Tui` handle and returns the root `View` in `Async & Scope`, so it can create refs and start fibers first.
`tui.quit` makes the loop return after the current batch; the scope closes (fibers stop), then the terminal
is restored, then `run` returns `()`.

**Why `ref.at(...)` exists.** tui4s wakes the render fiber with one watcher per *source*. For a
`SignalRef` the watcher is exact and costs nothing while idle (it parks on the ref's change promise, see
5.3). A derived Kyo `Signal` (`ref.map(f)`, `combineLatest`) is opaque (closures over its sources), so
tui4s can only watch it through Kyo's `observe`, which is correct but re-arms a one-second repair timer
(VERIFIED: `Signal.observe` races `nextWith` with `Async.sleep(repairInterval)`, default 1 s). `at` keeps
the root `SignalRef` visible to tui4s and applies the projection itself, so it is both exact and free.

---

## 3. The three sample apps (Kyo users)

All three files compile against the API of section 2 (UNVERIFIED: nothing here was compiled). No
for-comprehension is used (F1); setup code is `map` / `andThen` / `Kyo.zip`.

### 3.1 Counter

```scala
package examples.kyo

import kyo.*
import tui4s.*

object Counter extends TuiApp:

    def view(tui: Tui, count: SignalRef[Int]): View =
        column(
            count.view(n => text(s"Count: $n").bold).boxed(" Counter ").len(3),
            text(" + / -  change    q  quit").dim.len(1)
        ).onKey {
            case Key.Char('+') => count.update(_ + 1)
            case Key.Char('-') => count.update(_ - 1)
            case Key.Char('q') => tui.quit
        }

    val app: Tui => View < (Async & Scope) = t => Signal.initRef(0).map(view(t, _))

    tui(app)
end Counter
```

What runs when `+` is pressed: the root handler runs `count.update` on the render fiber; the pull finds
binding #1 changed (41 -> 42); it builds one `text` leaf, sees it differs from the last one, repaints the
inner rectangle of the box (1 row), diffs that row, and sends the 1-2 changed cells. The box border and
the help line are never repainted after the first frame.

### 3.2 Todo

```scala
package examples.kyo

import jatatui.core.style.Style
import jatatui.core.text.Line
import kyo.*
import tui4s.*

object Todo extends TuiApp:

    final case class Item(title: String, done: Boolean) derives CanEqual

    /** Items and selection change together, so they share one ref: one write = one consistent frame. */
    final case class Model(items: Chunk[Item], selected: Int) derives CanEqual:
        def add(title: String): Model =
            if title.isBlank then this
            else Model(items.append(Item(title.trim, done = false)), items.size)
        def move(delta: Int): Model =
            if items.isEmpty then this
            else copy(selected = (selected + delta).max(0).min(items.size - 1))
        def toggle: Model =
            if items.isEmpty then this
            else copy(items = items.updated(selected, items(selected).copy(done = !items(selected).done)))
        def delete: Model =
            if items.isEmpty then this
            else
                val rest = items.take(selected).concat(items.drop(selected + 1))
                Model(rest, selected.min(rest.size - 1).max(0))
    end Model
    object Model:
        val empty: Model = Model(Chunk.empty, 0)

    enum Pane derives CanEqual:
        case Input, List

    private def line(item: Item): Line =
        if item.done then Line.styled(s"[x] ${item.title}", Style.empty().dim().crossedOut())
        else Line.raw(s"[ ] ${item.title}")

    def view(tui: Tui, model: SignalRef[Model], draft: SignalRef[TextField], focus: Focus[Pane]): View =
        column(
            draft.view(f => input(f, placeholder = "What needs to be done?"))
                .boxed(" New todo ", active = focus.is(Pane.Input))
                .focusable(focus, Pane.Input)
                .onKey {
                    case Key.Enter               => draft.getAndSet(TextField.empty).map(f => model.update(_.add(f.text)))
                    case k if TextField.edits(k) => draft.update(_.edit(k))
                }
                .onPaste(s => draft.update(_.insert(s)))
                .len(3),
            model.view(m => items(m.items.map(line), selected = Option.when(m.items.nonEmpty)(m.selected)))
                .boxed(" Todos ", active = focus.is(Pane.List))
                .focusable(focus, Pane.List)
                .onKey {
                    case Key.Up | Key.Char('k')   => model.update(_.move(-1))
                    case Key.Down | Key.Char('j') => model.update(_.move(+1))
                    case Key.Char(' ')            => model.update(_.toggle)
                    case Key.Char('d')            => model.update(_.delete)
                    case Key.Char('q')            => tui.quit
                },
            text(" tab focus · enter add · space toggle · d delete · q quit (in list) · esc quit").dim.len(1)
        ).onKey { case Key.Esc => tui.quit }

    val app: Tui => View < (Async & Scope) = t =>
        Kyo.zip(Signal.initRef(Model.empty), Signal.initRef(TextField.empty), Focus.init(Pane.Input, Pane.List))
            .map((model, draft, focus) => view(t, model, draft, focus))

    tui(app)
end Todo
```

Notes:
- `q` typed in the input is consumed by the input's handler (`TextField.edits(Key.Char('q'))` is true), so
  it does not quit; in the list it quits. This falls out of "focused path first" routing, no flags needed.
- Tab is not handled anywhere, so the runtime moves `focus` (Input -> List -> Input). `focus.is(...)` is
  a binding on the border only: the border repaints, the input / list regions do not recompute.
- Typing repaints only the 1-row input region; moving the selection repaints only the list region. The
  `ListState` that holds the scroll offset belongs to the mounted `items` leaf, so scrolling is stable.
- `Line.styled`, `Line.raw`, `Style.dim()`, `Style.crossedOut()` are jatatui names (VERIFIED in the
  jatatui report); `Chunk.append/updated/concat/take/drop` and `Kyo.zip` (3-ary) are VERIFIED in RC7 source.

### 3.3 Dashboard

```scala
package examples.kyo

import kyo.*
import tui4s.*

object Dashboard extends TuiApp:

    val Titles: Seq[String] = Seq("Overview", "Logs")

    final case class Metrics(load: Double, history: Chunk[Long]) derives CanEqual:
        def push(x: Double): Metrics = Metrics(x, history.append((x * 100).toLong).takeRight(200))
    object Metrics:
        val empty: Metrics = Metrics(0.0, Chunk.empty)

    /** Stands in for any external source of log lines: a Kyo stream, one line every 700 ms. */
    val logSource: Stream[String, Async] =
        Stream.unfold(0, chunkSize = 1)(i => Async.sleep(700.millis).andThen(Present((s"[$i] GET /api/items 200", i + 1))))

    def overview(metrics: SignalRef[Metrics]): View =
        column(
            metrics.at(_.load).view(l => gauge(l, label = f"${l * 100}%3.0f%%")).boxed(" CPU load ").len(3),
            metrics.at(_.history).view(h => sparkline(h, max = Some(100L))).boxed(" Load history ")
        )

    def logsPane(logs: SignalRef[Chunk[String]]): View =
        logs.view(lines => logView(lines)).boxed(" Logs (stream) ")

    def view(tui: Tui, tab: SignalRef[Int], metrics: SignalRef[Metrics], logs: SignalRef[Chunk[String]]): View =
        column(
            tab.view(i => tabs(Titles, selected = i)).boxed(" tui4s dashboard ").len(3),
            tab.view {
                case 0 => overview(metrics)
                case _ => logsPane(logs)
            },
            text(" ←/→ switch tab · q quit").dim.len(1)
        ).onKey {
            case Key.Left | Key.Char('h')  => tab.update(i => (i + Titles.size - 1) % Titles.size)
            case Key.Right | Key.Char('l') => tab.update(i => (i + 1) % Titles.size)
            case Key.Char('q')             => tui.quit
        }

    def setup(tui: Tui): View < (Async & Scope) =
        Kyo.zip(Signal.initRef(0), Signal.initRef(Metrics.empty), Signal.initRef(Chunk.empty[String])).map {
            (tab, metrics, logs) =>
                Tui.every(250.millis)(Random.nextDouble.map(x => metrics.update(_.push(x))))
                    .andThen(Fiber.init(logSource.foreach(line => logs.update(_.append(line).takeRight(500)))))
                    .andThen(view(tui, tab, metrics, logs))
        }

    tui(setup)
end Dashboard
```

What happens at run time:
- Every 250 ms the tick fiber writes `metrics`. Its watcher wakes the render fiber. While the Overview tab
  is shown, the pull recomputes the two `at` slices (`load` and `history` both change) and repaints the
  gauge row and the sparkline area; the tab bar, the borders and the help line stay untouched.
- While the Logs tab is shown, the gauge and the sparkline are **not mounted**: no binding reads
  `metrics`, its watcher is stopped (ref count 0), and a tick causes no wake and no work at all. The tick
  fiber still updates `metrics`, so the Overview is current when it is mounted again.
- The log fiber appends a line every 700 ms; only the log region repaints, and only while it is mounted.
- `tab.view { ... }` is a structural binding: on `→` the Overview subtree is unmounted (2 bindings, 1
  watcher stopped), the Logs subtree is mounted and laid out in the same rectangle, and that rectangle is
  repainted once. The header `tab.view(i => tabs(...))` shares the same watcher on `tab`.
- `Tui.every`, `Fiber.init` run in the TUI scope: on quit they are interrupted before the terminal is
  restored. `Stream.unfold(acc, chunkSize)(f)`, `Random.nextDouble`, `Stream#foreach` are VERIFIED in RC7.

---

## 4. ZIO and Cats Effect users (no Kyo types in user code)

### 4.1 Principle

The View tree, the widgets, `Key`, `Mouse`, `TextField`, `TuiConfig` are data and are shared as they are.
Three things are effect-specific, and each facade replaces exactly these:

| Concern | Kyo (core) | ZIO facade | Cats facade |
| --- | --- | --- | --- |
| State | `kyo.SignalRef[A]` | `tui4s.zio.SignalRef[A]` (wraps the same Kyo object) | `tui4s.cats.SignalRef[F, A]` (same) |
| Handler result | `Unit < Async` | `ZIO[Any, Throwable, Unit]` | `F[Unit]` with `Async[F]` |
| Entry point / setup | `Tui.run(Tui => View < (Async & Scope))` | `Tui.run(Tui => ZIO[Scope, Throwable, View])` | `Tui.run[F](Tui[F] => Resource[F, View])` |

State reads and writes in the facades do **not** go through a bridge: a `SignalRef` write is an atomic
compare-and-set plus a promise completion, so the facade calls the Kyo *unsafe* API inside
`ZIO.succeed` / `Sync[F].delay` (VERIFIED names: `SignalRef.Unsafe.init/get/set/getAndUpdate/compareAndSet`,
`.safe`). Cost per write = the same as in Kyo, no fiber hop. Only *handlers* and *setup* cross runtimes,
through the bridges that the earlier probes validated (F11, F12 for ZIO; F7, F8, F10 for Cats).

### 4.2 tui4s-zio API

```scala
package tui4s.zio

// Inside this package the name `zio` means tui4s.zio, so the implementation imports _root_.zio.
import _root_.zio.{IO, Scope, Trace, UIO, ZIO, ZIOAppDefault}
import _root_.zio.stream.UStream
import jatatui.core.layout.Size

export tui4s.Dsl.*   // DSL functions + aliases (View, Split, Source, Key, Mouse, TextField, TuiConfig, ...)

/** Read-only signal. Extends the core Source, so it can be passed to boxed(active = ...), when(...). */
sealed abstract class Signal[A] extends tui4s.Source[A]:
    def get(using Trace): UIO[A]
    def changes(using Trace): UStream[A]          // latest-value semantics, like SubscriptionRef.changes

final class SignalRef[A] private[zio] (private[tui4s] val underlying: kyo.SignalRef[A]) extends Signal[A]:
    def set(a: A)(using Trace): UIO[Unit]
    def update(f: A => A)(using Trace): UIO[Unit]
    def getAndSet(a: A)(using Trace): UIO[A]
    def modify[B](f: A => (B, A))(using Trace): UIO[B]      // ZIO Ref convention: (result, newState)
object SignalRef:
    def make[A](initial: A)(using CanEqual[A, A], Trace): UIO[SignalRef[A]]

final class Focus[A] private[zio] (private[tui4s] val underlying: tui4s.Focus[A]):
    def current: Signal[A]
    def is(id: A): Signal[Boolean]
    def set(id: A)(using Trace): UIO[Unit]
    def next(using Trace): UIO[Unit]
    def prev(using Trace): UIO[Unit]
object Focus:
    def make[A](first: A, rest: A*)(using CanEqual[A, A], Trace): UIO[Focus[A]]

def when(cond: Signal[Boolean])(child: => View): View

trait Tui:
    def quit(using Trace): UIO[Unit]
    def size: Signal[Size]
    def log(message: => String)(using Trace): UIO[Unit]
    def stats: Signal[FrameStats]

object Tui:
    def run(app: Tui => ZIO[Scope, Throwable, View], config: TuiConfig = TuiConfig())(using Trace): IO[TuiError, Unit]

trait TuiZIOApp extends ZIOAppDefault:
    tui4s.Tui.tuneScheduler()                     // before the first Kyo scheduler class loads
    def config: TuiConfig = TuiConfig()
    def app(tui: Tui): ZIO[Scope, Throwable, View]
    final def run: ZIO[Any, Throwable, Unit] = Tui.run(app, config)

given zioAction: tui4s.IntoAction[ZIO[Any, Throwable, Unit]]   // imported with `import tui4s.zio.given`

object TestTui:                                    // headless, same engine as core TestTui (section 6)
    def run[A](width: Int, height: Int)(app: Tui => ZIO[Scope, Throwable, View])(
        test: TestTui => ZIO[Any, Throwable, A]): ZIO[Any, Throwable, A]
```

How it maps onto the core:

| ZIO call | Implementation |
| --- | --- |
| `SignalRef.make(a)` | `ZIO.succeed(SignalRef.Unsafe.init(a).safe)` with `AllowUnsafe.embrace.danger` inside the facade |
| `ref.get / set / update` | `ZIO.succeed(underlying.unsafe.get() / set(a) / getAndUpdate(f))` |
| `ref.changes` | `ZStreams.run(underlying.streamChanges)` (kyo-zio, F11), started on the caller runtime |
| `ref.view(f)` (inherited from `Source`) | core `Bind`; the watcher is on `underlying` (exact, section 5.3) |
| handler `ZIO[Any, Throwable, Unit]` | `zioAction` = `AmbientZIOs.get(zio)`: forked on the **caller's** runtime (FiberRefs, executor; F12), awaited by the render fiber, so key order is kept; ZIO failure -> `Abort[Throwable]` -> error policy; Kyo interrupt -> ZIO interrupt (F11) |
| `Tui.run(app)` | `ZIO.runtime.flatMap(rt => AmbientZIOs.run(core.Tui.run(core => AmbientZIOs.get(app(ZioTui(core)).provide(childScope)))))` inside `ZIO.scoped`; the ZIO child `Scope` of the setup is closed by a core `Scope.ensure` when the TUI scope closes |
| `tui.quit` | `ZIO.succeed(core.unsafe.quit())`: sets the quit flag and offers a wake; safe from any thread |
| interrupting the ZIO fiber that runs `Tui.run` | `ZIOs.run` interrupts the Kyo fiber and waits for it (F11); the terminal restore is a `Sync.ensure`, so it is done when the interrupt returns (F8) |

### 4.3 ZIO Counter

```scala
package examples.zio

import tui4s.zio.{*, given}
import zio.*

object Counter extends TuiZIOApp:
    def app(tui: Tui): ZIO[Scope, Throwable, View] =
        SignalRef.make(0).map { count =>
            column(
                count.view(n => text(s"Count: $n").bold).boxed(" Counter ").len(3),
                text(" + / -  change    q  quit").dim.len(1)
            ).onKey {
                case Key.Char('+') => count.update(_ + 1)
                case Key.Char('-') => count.update(_ - 1)
                case Key.Char('q') => tui.quit
            }
        }
end Counter
```

The handler bodies are `UIO[Unit]`, so `onKey[R]` infers `R = UIO[Unit]` and finds `zioAction`
(`IntoAction` is contravariant and `UIO[Unit] <: ZIO[Any, Throwable, Unit]`). The core Kyo instance does
not match (`UIO[Unit]` is not a `Unit < Async`), so there is no ambiguity and no silent discard of the
effect. If the user forgets `given` in the import, the compiler prints the `@implicitNotFound` text of
`IntoAction`, which names the fix. (Inference of `R` from a partial-function literal is the same pattern as
`collect[B](pf: PartialFunction[A, B])`; the exact behaviour with Kyo's opaque `<` is UNVERIFIED.)

### 4.4 ZIO Dashboard

```scala
package examples.zio

import tui4s.zio.{*, given}
import zio.*
import zio.stream.*

object Dashboard extends TuiZIOApp:

    val Titles: Seq[String] = Seq("Overview", "Logs")

    final case class Metrics(load: Double, history: Chunk[Long]) derives CanEqual:
        def push(x: Double): Metrics = Metrics(x, (history :+ (x * 100).toLong).takeRight(200))
    object Metrics:
        val empty: Metrics = Metrics(0.0, Chunk.empty)

    val logSource: UStream[String] =
        ZStream.iterate(0)(_ + 1).schedule(Schedule.spaced(700.millis)).map(i => s"[$i] GET /api/items 200")

    def overview(metrics: SignalRef[Metrics]): View =
        column(
            metrics.at(_.load).view(l => gauge(l, label = f"${l * 100}%3.0f%%")).boxed(" CPU load ").len(3),
            metrics.at(_.history).view(h => sparkline(h, max = Some(100L))).boxed(" Load history ")
        )

    def view(tui: Tui, tab: SignalRef[Int], metrics: SignalRef[Metrics], logs: SignalRef[Chunk[String]]): View =
        column(
            tab.view(i => tabs(Titles, selected = i)).boxed(" tui4s dashboard ").len(3),
            tab.view {
                case 0 => overview(metrics)
                case _ => logs.view(lines => logView(lines)).boxed(" Logs (stream) ")
            },
            text(" ←/→ switch tab · q quit").dim.len(1)
        ).onKey {
            case Key.Left      => tab.update(i => (i + Titles.size - 1) % Titles.size)
            case Key.Right     => tab.update(i => (i + 1) % Titles.size)
            case Key.Char('q') => tui.quit
        }

    def app(tui: Tui): ZIO[Scope, Throwable, View] =
        SignalRef.make(0).zip(SignalRef.make(Metrics.empty)).zip(SignalRef.make(Chunk.empty[String])).flatMap {
            case (tab, metrics, logs) =>
                val ticker = Random.nextDouble.flatMap(x => metrics.update(_.push(x))).repeat(Schedule.spaced(250.millis))
                val feed   = logSource.runForeach(line => logs.update(l => (l :+ line).takeRight(500)))
                ticker.forkScoped *> feed.forkScoped.as(view(tui, tab, metrics, logs))
        }
end Dashboard
```

The tick and the stream are plain ZIO fibers in the setup `Scope`. They write the facade `SignalRef`
(an atomic write on a ZIO thread); the core watcher (a Kyo fiber parked on the ref's change promise) wakes
the render fiber. No ZIO fiber is involved in rendering. `Chunk` here is `zio.Chunk` (a `Seq`, so it fits
`sparkline(data: Seq[Long])`). With `-language:strictEquality`, `CanEqual[Chunk[String], Chunk[String]]`
comes from the standard `CanEqual.canEqualSeqs` (CanEqual is contravariant) — UNVERIFIED for zio.Chunk.

### 4.5 tui4s-cats API

```scala
package tui4s.cats

import _root_.cats.effect.kernel.{Async, Resource, Sync}
import _root_.cats.effect.{IO, IOApp}
import fs2.Stream
import jatatui.core.layout.Size

export tui4s.Dsl.*   // DSL functions + aliases (View, Split, Source, Key, Mouse, TextField, TuiConfig, ...)

sealed abstract class Signal[F[_], A] extends tui4s.Source[A]:
    def get: F[A]
    def discrete: Stream[F, A]                    // like fs2 SignallingRef#discrete (latest value)

final class SignalRef[F[_], A] private[cats] (private[tui4s] val underlying: kyo.SignalRef[A])(using F: Sync[F])
    extends Signal[F, A]:
    def set(a: A): F[Unit]
    def update(f: A => A): F[Unit]
    def getAndSet(a: A): F[A]
    def modify[B](f: A => (A, B)): F[B]           // cats Ref convention: (newState, result)
object SignalRef:
    def apply[F[_]: Sync]: Make[F]                // SignalRef[IO].of(0), like Ref[IO].of(0)
    final class Make[F[_]: Sync]:
        def of[A](initial: A)(using CanEqual[A, A]): F[SignalRef[F, A]]

final class Focus[F[_], A] private[cats] (private[tui4s] val underlying: tui4s.Focus[A])(using Sync[F]):
    def current: Signal[F, A]
    def is(id: A): Signal[F, Boolean]
    def set(id: A): F[Unit]
    def next: F[Unit]
    def prev: F[Unit]
object Focus:
    def apply[F[_]: Sync]: MakeFocus[F]           // Focus[IO].of(Pane.Input, Pane.List)

def when[F[_]](cond: Signal[F, Boolean])(child: => View): View

trait Tui[F[_]]:
    def quit: F[Unit]
    def size: Signal[F, Size]
    def log(message: => String): F[Unit]
    def stats: Signal[F, FrameStats]

object Tui:
    def run[F[_]: Async](app: Tui[F] => Resource[F, View], config: TuiConfig = TuiConfig()): F[Unit]

trait TuiIOApp extends IOApp.Simple:
    tui4s.Tui.tuneScheduler()
    def config: TuiConfig = TuiConfig()
    def app(tui: Tui[IO]): Resource[IO, View]
    final def run: IO[Unit] = Tui.run[IO](app, config)

given catsAction[F[_]](using Async[F]): tui4s.IntoAction[F[Unit]]   // `import tui4s.cats.given`

object TestTui:
    def run[F[_]: Async, A](width: Int, height: Int)(app: Tui[F] => Resource[F, View])(test: TestTui[F] => F[A]): F[A]
```

How it maps onto the core (bridge = the vendored port `CatsBridge` of kyo-cats RC5, F5/F7):

| Cats call | Implementation |
| --- | --- |
| `SignalRef[F].of(a)`, `get`, `set`, `update`, `modify` | `Sync[F].delay(...)` around the Kyo unsafe API (no bridge, no Dispatcher) |
| `discrete` | `Stream.eval(get) ++ Stream.repeatEval(F.async(...))` over the ref's change promise (register-before-read, as in 5.3) |
| `Tui.run[F](app)` | `CatsBridge.runWithDispatcher[F, Unit] { ... }`: a `Dispatcher.parallel[F](await = false)` (F10: `await = true` can lose a cancel) is put in a Kyo `Local` (`AmbientDispatcher`), then `core.Tui.run(core => getF(app(CatsTui(core)).allocated).map((view, release) => Scope.ensure(getF(release)).andThen(view)))` |
| handler `F[Unit]` | `catsAction` = `CatsBridge.getF(fu)(using AmbientDispatcher.current[F])`: the F runs on the caller's runtime (F8 test "runWithDispatcher + getF"), awaited by the render fiber; F error -> `Abort[Throwable]`; Kyo interrupt -> F cancel |
| cancel of the `F[Unit]` returned by `Tui.run` | `runF` interrupts the Kyo fiber and waits (cancel-and-await, F8); terminal restore is a `Sync.ensure` -> done when the cancel returns |
| `tui.quit` | `Sync[F].delay(core.unsafe.quit())` |

The `given [F[_]](using Async[F]): IntoAction[F[Unit]]` only applies to type constructors with an
`Async` instance (effect types), so it cannot capture a `List[Unit]` by accident. It does not match Kyo's
`Unit < S` (Scala's higher-kinded unification fixes the *last* type argument, which is `S`, not `Unit`).

### 4.6 Cats Effect Counter

```scala
package examples.cats

import cats.effect.*
import tui4s.cats.{*, given}

object Counter extends TuiIOApp:
    def app(tui: Tui[IO]): Resource[IO, View] =
        Resource.eval(SignalRef[IO].of(0)).map { count =>
            column(
                count.view(n => text(s"Count: $n").bold).boxed(" Counter ").len(3),
                text(" + / -  change    q  quit").dim.len(1)
            ).onKey {
                case Key.Char('+') => count.update(_ + 1)
                case Key.Char('-') => count.update(_ - 1)
                case Key.Char('q') => tui.quit
            }
        }
end Counter
```

### 4.7 Cats Effect Dashboard (fs2)

```scala
package examples.cats

import cats.effect.*
import cats.syntax.all.*
import fs2.Stream
import scala.concurrent.duration.*
import tui4s.cats.{*, given}

object Dashboard extends TuiIOApp:

    val Titles: Seq[String] = Seq("Overview", "Logs")

    final case class Metrics(load: Double, history: Vector[Long]) derives CanEqual:
        def push(x: Double): Metrics = Metrics(x, (history :+ (x * 100).toLong).takeRight(200))
    object Metrics:
        val empty: Metrics = Metrics(0.0, Vector.empty)

    val logSource: Stream[IO, String] =
        Stream.iterate(0)(_ + 1).covary[IO].metered(700.millis).map(i => s"[$i] GET /api/items 200")

    def overview(metrics: SignalRef[IO, Metrics]): View =
        column(
            metrics.at(_.load).view(l => gauge(l, label = f"${l * 100}%3.0f%%")).boxed(" CPU load ").len(3),
            metrics.at(_.history).view(h => sparkline(h, max = Some(100L))).boxed(" Load history ")
        )

    def view(tui: Tui[IO], tab: SignalRef[IO, Int], metrics: SignalRef[IO, Metrics],
             logs: SignalRef[IO, Vector[String]]): View =
        column(
            tab.view(i => tabs(Titles, selected = i)).boxed(" tui4s dashboard ").len(3),
            tab.view {
                case 0 => overview(metrics)
                case _ => logs.view(lines => logView(lines)).boxed(" Logs (stream) ")
            },
            text(" ←/→ switch tab · q quit").dim.len(1)
        ).onKey {
            case Key.Left      => tab.update(i => (i + Titles.size - 1) % Titles.size)
            case Key.Right     => tab.update(i => (i + 1) % Titles.size)
            case Key.Char('q') => tui.quit
        }

    def app(tui: Tui[IO]): Resource[IO, View] =
        Resource.eval((SignalRef[IO].of(0), SignalRef[IO].of(Metrics.empty), SignalRef[IO].of(Vector.empty[String])).tupled)
            .flatMap { (tab, metrics, logs) =>
                val ticker = Stream.awakeEvery[IO](250.millis)
                    .evalMap(_ => IO(scala.util.Random.nextDouble()).flatMap(x => metrics.update(_.push(x))))
                val feed = logSource.evalMap(line => logs.update(l => (l :+ line).takeRight(500)))
                ticker.merge(feed).compile.drain.background.as(view(tui, tab, metrics, logs))
            }
end Dashboard
```

Tagless-final users write the same code with `F[_]: Async` instead of `IO` and call
`tui4s.cats.Tui.run[F](app)`; nothing else changes.

### 4.8 Package names

`tui4s.zio` and `tui4s.cats` follow the module names. Known hazard: a user who writes `import tui4s.*`
*and* later refers to `zio.X` or `cats.X` gets the tui4s sub-package (a wildcard import shadows a
top-level package of another compilation unit). The facades avoid it by re-exporting the whole DSL, so
ZIO / Cats users never need `import tui4s.*`; the docs say so. Alternative if the panel prefers safety
over symmetry: `tui4s.interop.zio` / `tui4s.interop.cats` (caliban style). See 8.3.

---

## 5. Runtime model

### 5.1 Fibers and who does what

```
             ┌──────────────── TUI Scope (Kyo Scope.run) ─────────────────────────────────────────┐
 crossterm   │ input fiber      Loop { poll(50 ms) ; if ready: read every queued event (<= 512),   │
 JNI ───────>│                  convert to tui4s Event (Press/Repeat only) ; inbox.putBatch }      │
             │ watcher fibers   one per distinct root SignalRef in use (ref-counted), see 5.3      │
             │                  park on the ref's change promise ; on change: wakePending -> inbox  │
             │ user fibers      started by the setup (Fiber.init, Tui.every) or tui.spawn          │
             │                  they only write signals                                            │
             │ render fiber     the fiber that called Tui.run:                                     │
             │                  inbox.take -> drain -> dispatch events to handlers (in order)      │
             │                  -> pull bindings -> recompute + repaint damaged regions             │
             │                  -> present dirty rows (paced to maxFps) -> loop                    │
             └──────────────────────────────────────────────────────────────────────────────────────┘
 inbox = Channel[Msg](1024, Access.MultiProducerSingleConsumer)
 Msg   = Input(Chunk[Event]) | Wake | Failed(Throwable, origin) | Quit | Sync(Promise) (test settle)
```

This is the event-loop shape that the probe validated (`.work/scratch/kyo-rc7/loop.scala`: batched input
+ coalesced render, ~6.7 us per event for 10k queued keys, terminal restored before `interruptAwait`
returns), plus two things: watchers as an extra wake source, and the reconcile step between dispatch and
draw.

### 5.2 Start sequence (`Tui.run`)

1. `Tui.tuneScheduler()` (no effect if the scheduler is already initialized, see 5.10).
2. Load `CrosstermJni` and query the size **before** raw mode, so a JEP 472 warning (jatatui report 1.3)
   lands on the normal screen, not inside the TUI. No TTY -> `TuiError.NoTerminal`.
3. Open the TUI `Scope`. Run the app function under `Log.let(tuiLog)` (Kyo's default `Log.live` is a
   console logger, VERIFIED in `Log.scala`; console output corrupts the screen). The app creates refs,
   starts its fibers, returns the root `View`. Failure -> `TuiError.SetupFailed`, the terminal was never
   touched.
4. `Sync.ensure(restore)`: enable raw mode, enter the alternate screen, hide the cursor, optional mouse /
   bracketed paste. Start the input fiber (`Fiber.init`, scoped).
5. Mount the root (5.4), paint everything, present a full first frame, enter the loop.

```scala
// internal sketch (tui4s.internal.Engine), Kyo style: map/andThen, no for-comprehension
def run(app: Tui => View < (Async & Scope & Abort[Throwable]), config: TuiConfig)(using Frame)
    : Unit < (Async & Abort[TuiError]) =
    Natives.load.andThen(
        Scope.run(
            Engine.init(config).map { engine =>
                Log.let(engine.log) {                              // whole app: never Kyo's console logger
                    setup(app, engine).map { root =>
                        Sync.ensure(engine.terminal.restore) {     // F8: synchronous release, never Scope.ensure
                            engine.terminal.enter
                                .andThen(Fiber.init(engine.inputLoop))
                                .andThen(engine.mount(root))
                                .andThen(engine.loop)
                        }
                    }
                }
            }
        )
    )

def loop(using Frame): Unit < (Async & Abort[TuiError]) =
    Loop(0L) { lastPresentNanos =>
        inbox.take.map(first => inbox.drain.map(rest => dispatch(Chunk(first).concat(rest)))).map {
            case Step.Quit     => Loop.done(())
            case Step.Continue => reconcile.andThen(present(lastPresentNanos)).map(Loop.continue)
        }
    }
```

### 5.3 Watchers: how a write anywhere wakes the render fiber

A watcher is a tiny Kyo fiber per **distinct root signal** that a mounted binding reads (identity map,
ref-counted; mount = +1, unmount = -1, 0 = interrupt). Its only output is a wake-up. It never reads the
value and never touches jatatui.

Exact watcher for a `SignalRef` (the normal case; `ref.view`, `ref.at(...)`, facade refs, `focus`,
`tui.size`):

```scala
// RC7 API (VERIFIED): SignalRef#unsafe.next(): Fiber.Unsafe[A, Any] returns the CURRENT change promise;
// every changing write swaps in a new promise and completes the old one (SignalRef.Unsafe.onUpdate).
def watch(ref: SignalRef[?])(using Frame): Unit < Async =
    def await(p: Fiber.Unsafe[?, Any]): Unit < Async =
        p.safe.get.andThen(Sync.Unsafe.defer {
            val next = ref.unsafe.next()   // 1. register for the next change FIRST
            engine.wake()                  // 2. then wake the render fiber (CAS wakePending false->true, offer Wake)
            next
        }).map(await)
    Sync.Unsafe.defer(ref.unsafe.next()).map(await)
```

Why it never loses the last value: a write that happens after step 1 completes `next`, so there will be
another round. A write that happens before step 1 happens-before step 2, so the render fiber, which pulls
*after* it takes the `Wake`, reads that write (or a newer one). Kyo's own `observe` used to do exactly
this ("register-before-read") and dropped it only because of a Scala Native miscompile (VERIFIED comment
in `Signal.scala`, `SignalRef` section); tui4s is JVM-only. Idle cost: a parked fiber, no timer.

`wakePending` (an `AtomicBoolean`) keeps a burst of writes from flooding the inbox: only the false->true
transition offers a `Wake`; the render fiber sets it back to false *before* it pulls, so a write after the
pull produces a new wake. `offer` (not `put`) is used, so a full inbox never blocks a watcher; a full
inbox already guarantees another loop iteration and therefore another pull.

Fallback for any other Kyo `Signal` (`ref.map(f)`, `combineLatest`, a signal from another library):
`signal.observe(_ => Sync.defer(engine.wake()))`. Kyo's repairing `observe` is lossless with a worst-case
latency of `repairInterval` (1 s) when a write races its read/register window, and it re-arms a 1 s timer
while idle (VERIFIED in `Signal.observe`). Documented; `at` avoids it.

### 5.4 Mount, reconcile, repaint

**Mounted tree** (render fiber only): one node per View node, with `rect`, composed `base` style, paint
order `z`, children, an optional `retained` widget state (`ListState`, `TableState`, the `S` of
`View.stateful`), and for bindings `source`, `render`, `last` value and the call-site `Frame`.

**Mount(view, rect)**: `Split` -> `Layout.split` (cached) and mount children in the returned rects;
`Boxed` -> paint nothing yet, child rect = `block.inner(rect)`; `Bind` -> read the source, `render`, mount
the child in the same rect, ref++ the watchers of its roots; handlers and focusables are recorded with
their rect for routing; leaves get their `retained` state (or inherit it, see remount).

**Reconcile (the pull)**, once per loop iteration, after all handlers of the batch ran:

```
wakePending := false
for each binding b in pre-order (skip b if an ancestor binding was recomputed in this pass):
    v := b.source.read()                    // SignalRef: unsafe get + projection; no allocation for SignalRef
    if v != b.last:                         // CanEqual of the projected value
        b.last := v
        nv := b.render(v)                   // user code, pure; exception -> 5.8
        if nv != b.child.view:              // memo: View leaves are case classes over jatatui values
            remount(b, nv); damage(b)       // structural equality, so "same text" = no repaint
for each boxed(active = s) whose s changed: damage(that box)       // border + subtree repaint, no recompute
```

**Remount** keeps runtime-owned state where it can: the new subtree is matched against the old one by
path (child index, or `.key(k)`) and leaf kind; a matching leaf inherits `retained` (so the `ListState`
offset survives a focus or theme change of an enclosing binding). Unmatched old nodes are dropped and
their watchers ref--.

**Repaint** of the damaged set D:
1. Closure: add every node with a higher `z` whose rect intersects a node in D (overlays from `layers`).
   In a tree of rows and columns, siblings never overlap, so D does not grow.
2. In `z` order, for each node: reset the cells of its rect in the canvas, apply its composed base style
   (`buf.setStyle(rect, base)`), paint its subtree (each leaf: `widget.render(rect, canvas)` or the
   stateful variant with its retained state). Mark the rows of the rect dirty.

Step 2 is what a full repaint would do for those cells, because the only thing an ancestor paints *inside*
a child's rect is its style (a `Block` paints its border and titles outside `inner`). `verifyRepaints`
checks this claim at run time (5.5).

### 5.5 When a frame is drawn, coalescing, presenting

- A frame is presented **only** if the reconcile damaged something (or after a resize). A wake that
  changed nothing visible (equal value, equal View) costs a pull and no frame.
- Coalescing: one loop iteration handles *all* queued messages (take + drain), then pulls once. Ten keys
  typed in one 16 ms window give one frame.
- Pacing: at most `maxFps` (default 60). If the last present was less than 1/maxFps ago, the render fiber
  sleeps the remainder, drains again, reconciles again, then presents once (the probe's shape).
- **Present (`Presenter.Retained`)**: for each dirty row, diff `shown` against `canvas` with a port of
  `Buffer.diff` restricted to one row (same wide-grapheme and VS16 rules, VERIFIED source of
  `Buffer.diff`), collect `BufferUpdate`s into a reused `ArrayList`, call `backend.draw(updates)` (one JNI
  `enqueue` in `CrosstermBackend`), update the cursor only if it changed (a focused `input` asks for it
  during paint), `backend.flush()`, then copy the dirty rows from `canvas` to `shown` (cell fields, no
  allocation). jatatui's `Terminal` is not used for the fullscreen viewport: `Terminal.draw` diffs and
  resets the *whole* buffer every frame (~0.6 ms fixed at 120x40, jatatui report 5.3).
- **`Presenter.FullFrame`**: paint the whole mounted tree inside `terminal.draw(frame => ...)`. Same
  output, ratatui cost. Used for the Inline viewport (needs `Terminal`'s viewport logic) and as the
  reference in tests.
- **`verifyRepaints`** (on by default in `TestTui`, off in production): after each present, paint the
  whole tree from scratch into a scratch `Buffer` and compare it with `canvas` (`Buffer.equals` is
  structural, VERIFIED). A difference fails with the binding positions and the first differing cells.

### 5.6 Ownership of the mutable jatatui objects

| Object | Owner | Rule |
| --- | --- | --- |
| `canvas`, `shown` (`Buffer`, `Cell`) | render fiber | never exposed; `BufferUpdate` live refs are consumed inside `backend.draw` |
| `ListState`, `TableState`, `ScrollbarState`, `View.stateful` state | the mounted leaf | created at mount, touched only inside paint (render fiber) |
| `Rect[]` from `Layout.split` | jatatui's global cache | read only; the immutable `Rect` records are copied into nodes, the array is never kept |
| `Backend` (`CrosstermBackend` / `TestBackend`) | render fiber | `draw`, cursor, `flush`, `clear` |
| `CrosstermJni` reader | input fiber | `poll` / `read` only; never `read` without a `poll` that said ready |
| mounted tree, handler table, watcher registry | render fiber | no locks needed |
| `SignalRef`s | anyone | atomic; the only shared mutable state |

Kyo fibers are not pinned to threads, but the render fiber runs its steps one after the other, and each
hand-off between scheduler threads is a happens-before edge, so single-fiber ownership is enough.

### 5.7 Resize

`Event.Resize(w, h)` is handled first in its batch (only the last one counts): `tui.size` is set (a
`SignalRef`, so bindings on it recompute in the same pull), the root is laid out again top-down (bindings
are *not* recomputed unless their value changed; retained states stay), `canvas` and `shown` are
re-created at the new size, `backend.clear()`, everything is repainted, and the next present sends every
non-blank cell (shown = blank = what the cleared screen shows). A burst of resizes while the window is
dragged collapses into one relayout per frame.

### 5.8 Errors

| Failure | Detection | `ErrorPolicy.Stop` (default) | `ErrorPolicy.LogAndContinue` |
| --- | --- | --- | --- |
| handler fails / throws / panics | `Abort.run[Throwable]` around each action | loop ends, terminal restored, `Abort.fail(HandlerFailed(event, position, cause))` | logged; next event |
| binding `render` throws | try around `render` | `RenderFailed(position of the binding)` | logged; the region shows `! <position>: <message>`; `last` keeps the previous value |
| widget precondition | DSL clamps (`gauge`), `View.widget` is user code | as above | as above |
| setup fails | before raw mode | `SetupFailed` (normal screen, readable) | same |
| `tui.spawn` / setup fiber fails | `Msg.Failed` to the inbox | stop with the cause | logged |
| terminal I/O (`IOException`, crossterm `RuntimeException("IO error ...")`) | presenter / input fiber | `TuiError.Io` | same (cannot continue) |

Every error message carries the Kyo `Frame` (file:line) of the binding or handler, which is what an agent
needs to fix the right line.

### 5.9 Quit, interruption, terminal restore

- `tui.quit` sets a flag and wakes the loop; the loop finishes the current batch and returns. The order is
  then: `Sync.ensure` restore runs (show cursor, disable mouse / paste, leave the alternate screen,
  disable raw mode, flush; each step guarded so one failure does not skip the others; idempotent via an
  `AtomicBoolean`), then the TUI `Scope` closes (input fiber, watchers, user fibers are interrupted and
  awaited by `Fiber.init`'s release).
- Interruption of the fiber that runs `Tui.run` (from Kyo, from ZIO through `ZIOs.run`, from Cats through
  `runF`): the restore is a `Sync.ensure`, so it has run when the interrupter gets the result (F8; the
  loop probe shows "terminal restored before `interruptAwait` returns"). `Scope` finalizers are drained on
  a detached fiber after an interrupt (F8), so user fibers may run a few ms after the restore; they only
  write signals.
- The input fiber can sit in `poll(50 ms)` (a JNI call that an interrupt cannot stop); it notices the
  interrupt at the next poll boundary: quit latency <= `pollEvery`.
- `System.exit` / SIGTERM while the TUI runs: a JVM shutdown hook, installed for the TUI's lifetime and
  removed after, calls the same idempotent restore. Ctrl-C is a key in raw mode (no SIGINT); the default
  handler quits.

### 5.10 Idle CPU

With no input and no ticks: the input fiber does 20 polls per second (`pollEvery` = 50 ms), the render
fiber is parked on `inbox.take`, exact watchers are parked on promises (no timers), no frame is drawn.
The rest is the Kyo scheduler itself (F13): ~13% of a core with defaults, 4-8% with
`kyo.scheduler.cycleIntervalNs=10000000`, `blockingMonitorIntervalNs=20000000`,
`regulator.concurrencyCollectIntervalMs=100`; the floor is Kyo's 1 ms clock thread. These are
`StaticFlag`s read once, so `Tui.tuneScheduler()` sets them with `System.setProperty` *only if unset*,
and only takes effect if it runs before the scheduler class initializes: `TuiApp`, `TuiZIOApp` and
`TuiIOApp` call it in their constructors (UNVERIFIED that no Kyo class starts the scheduler earlier; the
documented fallback is `javaOptions += "-Dkyo.scheduler.cycleIntervalNs=10000000"` etc.). The loop probe
also measured a platform-thread input reader as cheaper at idle (17.8% vs 23.3% of a core, loaded
machine) but slower to shut down (~60 ms); it is a `TuiConfig` option, not the default.

---

## 6. Testing (no terminal) and agent-friendliness

### 6.1 Three levels

| Level | What | Needs |
| --- | --- | --- |
| 1. Model and handlers | Pure functions (`Model.add`, `TextField.edit`) and handler effects (`count.update(_ + 1)`) are ordinary Kyo values: run them, read the ref. | nothing from tui4s |
| 2. One view, one state | `View.snapshot(view, w, h)`: reads every binding's *current* value (like kyo-ui `resolveReactives`), paints once into a `Buffer`, returns the rows. No fibers, no loop. | jatatui-core only |
| 3. The real engine | `TestTui.run(w, h)(app)(test)`: the production engine (watchers, routing, focus, reconcile, retained presenter) on jatatui's `TestBackend` (VERIFIED: published, copies cells), with a scripted event queue instead of crossterm. | jatatui-core only, no native library |

The test kit lives in tui4s-core (`tui4s.testkit`), because it only needs `TestBackend` from jatatui-core.
The facades ship `TestTui` variants with `UIO` / `F[_]` signatures (section 4).

### 6.2 `TestTui` API

```scala
package tui4s.testkit

object TestTui:
    def run[A](width: Int, height: Int, config: TuiConfig = TuiConfig.test)(
        app: Tui => View < (Async & Scope & Abort[Throwable])
    )(test: TestTui => A < (Async & Abort[Throwable]))(using Frame): A < (Async & Abort[Throwable])
    // TuiConfig.test = TuiConfig(maxFps = Int.MaxValue /* no pacing */, verifyRepaints = true,
    //                            onError = ErrorPolicy.Stop, tuneScheduler = false)

trait TestTui:
    def press(keys: Key*)(using Frame): Unit < Async       // one input batch, then settle
    def typeText(text: String)(using Frame): Unit < Async   // Key.Char per char, one batch, then settle
    def paste(text: String)(using Frame): Unit < Async
    def click(x: Int, y: Int)(using Frame): Unit < Async
    def resize(width: Int, height: Int)(using Frame): Unit < Async
    def settle(using Frame): Unit < Async                   // see below
    def lines(using Frame): Chunk[String] < Sync            // rows of the TestBackend screen
    def screen(using Frame): Screen < Sync                  // immutable copy: symbols + styles per cell
    def stats(using Frame): FrameStats < Sync               // frames, dirty rows, changed cells, recomputed, watchers
    def quitRequested(using Frame): Boolean < Sync

final case class Screen(rows: Chunk[String], styles: Chunk[Chunk[Style]]) derives CanEqual:
    def contains(text: String): Boolean
    def row(y: Int): String
    def styleAt(x: Int, y: Int): Style
    def region(x: Int, y: Int, width: Int, height: Int): Chunk[String]
    def render: String                                      // TestBackend.bufferView format, for snapshots
```

**`settle` is deterministic.** It puts a `Msg.Sync(promise)` into the inbox and waits for it. The render
fiber completes the promise after it has handled every earlier message, pulled every binding and
presented. Because the pull *reads* current values (it does not wait for watcher notifications), every
write that happened before `settle` is on the screen after it: no sleeps, no polling, no flaky timing.
Background fibers (ticks, streams) are made deterministic with Kyo's controlled clock
(`Clock.withTimeControl`, `advance`, `awaitPendingSleepers`, VERIFIED in RC7 `Clock.scala`) and a seeded
`Random.withSeed` (VERIFIED). `verifyRepaints = true` makes every test also prove that the fine-grained
repaint equals a full repaint (5.5).

To be testable, an app exposes its app function as a value (`val app: Tui => View < (Async & Scope)`, or
a method like Dashboard's `setup`) and passes it to `tui(...)`; the samples in section 3 do this.

### 6.3 Example tests (kyo-test, F6)

```scala
package examples.kyo

import kyo.*
import kyo.test.*
import tui4s.*
import tui4s.testkit.TestTui

class CounterTest extends Test[Any]:
    "counter" - {
        "+ + - shows 1 and repaints only the number" in {
            TestTui.run(30, 5)(Counter.app) { t =>
                t.press(Key.Char('+'), Key.Char('+'))
                    .andThen(t.press(Key.Char('-')))
                    .andThen(Kyo.zip(t.lines, t.stats))
                    .map { (lines, stats) =>
                        assert(lines(1).startsWith("│Count: 1"))
                        assert(stats.lastDirtyRows == 1)       // fine-grained: the border and help line were not repainted
                        assert(stats.lastRecomputed == 1)
                    }
            }
        }
        "q quits" in {
            TestTui.run(30, 5)(Counter.app)(t => t.press(Key.Char('q')).andThen(t.quitRequested).map(q => assert(q)))
        }
    }
end CounterTest

class TodoTest extends Test[Any]:
    "model is pure" in {
        val m = Todo.Model.empty.add("milk").add("eggs").move(-1).toggle
        assert(m.items.map(i => (i.title, i.done)) == Chunk(("milk", true), ("eggs", false)))
    }
    "add, focus, toggle, delete" in {
        TestTui.run(40, 10)(Todo.app) { t =>
            t.typeText("milk").andThen(t.press(Key.Enter))
                .andThen(t.typeText("eggs")).andThen(t.press(Key.Enter))
                .andThen(t.press(Key.Tab, Key.Char(' ')))           // focus the list, toggle "eggs" (selected = last added)
                .andThen(t.screen)
                .map { s =>
                    assert(s.contains("[ ] milk"))
                    assert(s.contains("[x] eggs"))
                }
                .andThen(t.press(Key.Char('d')))
                .andThen(t.lines)
                .map(lines => assert(!lines.exists(_.contains("eggs"))))
        }
    }
    "q in the input is text, not quit" in {
        TestTui.run(40, 10)(Todo.app)(t =>
            t.typeText("q").andThen(Kyo.zip(t.quitRequested, t.lines)).map { (quit, lines) =>
                assert(!quit)
                assert(lines.exists(_.contains("q")))
            }
        )
    }
end TodoTest

class DashboardTest extends Test[Any]:
    "ticks move the gauge; hidden panes cost nothing" in {
        Random.withSeed(7) {
            Clock.withTimeControl { clock =>
                TestTui.run(60, 20)(Dashboard.setup) { t =>
                    t.settle.andThen(t.lines).map(before =>
                        clock.advance(250.millis).andThen(clock.awaitPendingSleepers(2)).andThen(t.settle)
                            .andThen(Kyo.zip(t.lines, t.stats)).map { (after, stats) =>
                                assert(before != after)                   // gauge and sparkline changed
                                assert(stats.lastRecomputed == 2)         // load slice + history slice
                            }
                    ).andThen(t.press(Key.Right))                         // Logs tab: overview unmounted
                        .andThen(t.stats).map { s =>
                            assert(s.watchers == 2)                       // tab + logs; no watcher on metrics
                        }
                }
            }
        }
    }
end DashboardTest
```

A test for the ZIO facade has the same shape with `tui4s.zio.TestTui` and ZIO effects
(`t.press(Key.Char('+')) *> t.lines`), for Cats with `F`.

### 6.4 Why this design is easy for an AI agent to write and check

1. **Few concepts, one way each.** State = `SignalRef`; dynamic UI = `x.view(...)`; input = `.onKey { case ... }`;
   effects = handlers or fibers in setup. No message enum, no `update` function to keep in sync with a
   view, no hook order rules, no lifecycle methods.
2. **Types catch the common mistakes.** A handler that returns a value or the wrong effect does not compile,
   and the error text (`@implicitNotFound` on `IntoAction`) names the fix. Forgetting `derives CanEqual` on
   state is a compile error with Kyo's own message (`Signal.initRef`'s `missingCanEqual`, VERIFIED).
   Binding bodies are `A => View` (pure), so an effect inside a view does not type-check.
3. **Local reasoning.** Each binding says which state it reads, next to the widget it draws. An agent can
   edit one region without reading the rest of the app.
4. **Text-first verification.** `t.lines` / `t.screen.contains(...)` are plain strings; `Screen.render`
   is a snapshot format; `verifyRepaints` catches engine bugs in every test; `stats` lets a test assert
   *performance properties* ("one key = one dirty row", "hidden tabs have no watchers").
5. **Errors point at code.** Every binding and handler carries its Kyo `Frame`, so a `RenderFailed` or
   `HandlerFailed` says `Todo.scala:57`, not an engine stack.
6. **Deterministic tests.** `settle` is exact (pull-based); time is controlled with Kyo's `TimeControl`.
7. **Same samples for three ecosystems.** The ZIO and Cats code is the Kyo code with different state
   constructors and imports; an agent that knows one knows all three.

---

## 7. Performance analysis

Inputs (all measured earlier, 2014 i7, JDK 25, loaded machine; jatatui report 5.3 and the loop probe):
full typical 120x40 frame ~2.1 ms / ~1.7 MiB; `Terminal.draw` fixed cost 0.6 ms (diff 0.31 ms + reset
0.35 ms); `Buffer.reset` ~72 ns/cell; `Buffer.diff` ~64 ns/cell; `Buffer.setStyle` ~53 ns and ~64 B per
cell; full-screen `Block` 229 us / 333 KiB; `setString` 100 chars 12.6 us; `Layout.split` cache hit
2.7 us, miss ~170 us; loop probe: ~6.7 us per event (batched input, coalesced render), channel
take+drain ~0.5-0.8 us per item. Numbers below that are not in that list are **estimates** derived from
these per-cell costs (marked "est."); the validator should measure them.

### 7.1 Work per event (key press)

Counter, 120x40 terminal; the binding's rectangle is the inner row of the box (118 x 1 cells).

| Step | Work | Allocation |
| --- | --- | --- |
| input fiber: `poll` + `read` + convert | JNI calls; est. 5-20 us (not measured) | one small record + `Event` |
| inbox `putBatch` / `take` / `drain` | ~1-2 us | `Chunk` |
| handler `count.update(_ + 1)` | CAS, `!=`, swap + complete the change promise; est. < 1 us | one `Promise` |
| pull | 1 binding: unsafe get + `!=`; est. < 0.1 us | none |
| recompute | `text(...)` leaf (a jatatui `Paragraph` of one `Line`); est. ~1 us | < 1 KiB |
| memo | leaf `equals`; ~0.1 us | none |
| repaint | reset 118 cells (8.5 us) + paragraph paint (est. 10-15 us incl. its own style pass) | est. 10-15 KiB |
| present | row diff 120 cells (7.7 us) + `backend.draw` of 1-2 changed cells + `flush` (2 JNI calls) | one `ArrayList` reused, 1-2 `Print` commands |
| watcher wake | the watcher of `count` wakes once more; the next iteration pulls, finds nothing new, draws nothing; est. 3-7 us | small |
| **total** | **est. 50-100 us** | **est. 15-30 KiB** |

For comparison, a redraw-everything loop pays the full frame per event: for this small Counter screen
~0.6 ms fixed (`Terminal.draw`) + ~0.3 ms of widgets, ~0.4 MiB; for the "typical" 120x40 screen
~2.1 ms / ~1.7 MiB. A key burst is coalesced in both designs (one frame per loop iteration, <= 60 fps).

### 7.2 Work per frame by app

| App and change | Damaged region (120x40) | tui4s (est.) | Full frame (measured class) | Gain |
| --- | --- | --- | --- | --- |
| Counter `+` | 1 row x 118 | 0.05-0.1 ms, ~20 KiB | ~0.9 ms | ~10-20x |
| Todo: typing a character | input row 118 x 1 | 0.05-0.1 ms | ~2 ms (list of 40 + boxes) | ~20x |
| Todo: moving the selection | list region 118 x 34 (whole `List` widget) | reset 0.29 ms + list paint est. 0.5 ms + diff 34 rows 0.26 ms = ~1 ms | ~2 ms | ~2x |
| Dashboard tick (Overview) | gauge 118 x 1 + sparkline 118 x ~31 | reset 0.27 ms + paint est. 0.2-0.3 ms + diff 0.25 ms = ~0.7 ms | ~1.5-2 ms | ~2-3x |
| Dashboard tick (Logs tab shown) | none (metrics not mounted) | **0** (no watcher, no wake) | Elm: model changed -> full frame ~2 ms, 4x/s | infinite |
| A 10 fps spinner in a corner, rest static | 1 cell | ~30 us x 10/s = 0.03% of a core | ~2 ms x 10/s = 2% of a core + 17 MiB/s garbage | ~60x |
| Nothing changed (idle, or equal write) | none | **0 frames** | 0 with a dirty check; ~2 ms per tick without one | — |
| Terminal resize | everything | full repaint + relayout, ~2-3 ms once per frame of resize events | same | 1x |

Reading: the design wins most where real TUIs spend their time — small, frequently changing regions
(status lines, counters, input fields, gauges, clocks, spinners, a log tail) next to large static ones,
and tabs/panes that are not visible. It wins little when one widget covers most of the screen and that
widget changes (a full-screen list): a jatatui widget is the smallest unit tui4s can repaint, so moving
the selection in a 34-row list repaints and diffs 34 rows although 2 rows differ.

### 7.3 How unchanged frames are avoided (three filters)

1. **Kyo dedup at the source**: `SignalRef.set` of an equal value completes no promise (VERIFIED,
   `SignalRef.Unsafe.getAndSet` compares with `!=`): no watcher wakes.
2. **Projection dedup at the binding**: `ref.at(_.load)` compares the projected value; a change of another
   field recomputes nothing.
3. **View memo at the region**: if `render(v)` returns a View equal to the mounted one (jatatui widgets
   have structural equality), nothing is repainted. If nothing was damaged, nothing is presented.
   And in the presenter, unchanged cells inside a repainted region are not sent (row diff).

### 7.4 Cost of the abstraction itself

| Item | Cost | Notes |
| --- | --- | --- |
| Signal graph | none | Kyo signals are not a graph; there is no propagation, no topological sort, no memo table. |
| Pull per wake | B bindings x est. 30-100 ns | 100 bindings < 10 us. Linear; a dependency index (root -> bindings) is an easy later optimization. |
| Watchers | one parked fiber + promise per distinct root (< 1 KiB); per changing write: a promise completion + one fiber wake + one CAS | Shared by all bindings on that root. Unmounted roots have no watcher. Fallback watchers (derived Kyo signals): + one 1 s timer each. |
| Tree diff | none | No virtual DOM. A recompute remounts only the binding's subtree. |
| Mount / remount | per node: one object; per split: `Layout.split` (2.7 us hit, ~170 us miss); per new root: `Fiber.initUnscoped` | Tab switch in Dashboard est. 0.3-1 ms incl. repaint, once per switch. |
| Memo `equals` | O(size of the leaf), e.g. a 1000-item list est. 10-50 us | Only after the source value changed; always cheaper than painting it. |
| Retained buffers | 2 x W x H cells (`canvas`, `shown`) | The same two buffers jatatui's `Terminal` keeps; `Terminal` itself is not created for the fullscreen viewport. |
| `verifyRepaints` | one full repaint + `Buffer.equals` per frame | Tests / debug only. |
| Facade handlers (ZIO/Cats) | per handled event: fork on the caller runtime + await (est. 5-20 us) | State writes in facades cost the same as in Kyo (no bridge). |

### 7.5 Throughput and latency

- Key bursts: per-event cost is the handler + loop share (~5-10 us, probe 6.7 us/event), so ~100k
  events/s; frames are capped at 60/s; nothing is dropped (input uses `putBatch` with backpressure).
- Latency key -> screen: one loop iteration (+ up to one frame interval when the previous frame was less
  than 16 ms ago). No polling delay on the render side; the input poll returns as soon as an event is
  ready (`poll(50 ms)` waits only when there is nothing).
- Async writes (ticks, streams): watcher wake (a few us) + one iteration.

---

## 8. Weaknesses, risks and open questions

### 8.1 Weaknesses of the design (honest list)

1. **It is not SolidJS.** Kyo signals have no dependency tracking, no cached memos and no transactions
   (`Signal.map` is a lazy view; VERIFIED). tui4s is fine-grained at the level of a *binding*, by pulling,
   not at the level of an expression. A projection used by two bindings is computed twice.
2. **Region = one jatatui widget.** A widget is the smallest thing tui4s can repaint. When one big widget
   covers most of the screen and changes (moving the selection in a full-height list), tui4s repaints and
   diffs the whole widget: the gain over a full frame drops to ~2x (7.2). Per-row granularity would need
   tui4s-owned list/table widgets (Q6).
3. **Torn frames from background fibers that write several refs.** Handlers are atomic with respect to
   frames (they run on the render fiber before the pull). A background fiber that does `a.set(..)` then
   `b.set(..)` can be observed between the two writes for one frame. The rule "values that change together
   live in one ref" avoids it (Dashboard `Metrics`), but nothing enforces it. No `batch` API in v1 (Q2).
4. **tui4s owns a presenter.** For the fullscreen viewport tui4s does not call `Terminal.draw`; it ports
   the row part of `Buffer.diff` (wide glyphs, VS16 trailing cells) and does its own cursor handling. Any
   divergence is a rendering bug. Mitigations: a line-by-line port, `verifyRepaints` in every test, a
   property test that compares `Presenter.Retained` with `Presenter.FullFrame` on `TestBackend` for random
   view trees and event scripts. The Inline viewport is `FullFrame`-only in v1 (Q5).
5. **Partial repaint rests on invariants that jatatui does not enforce.** (a) A widget paints only inside
   its rect; (b) an ancestor contributes only a base style inside a child's rect; (c) overlays are found
   by the z-order closure. A user `View.widget((area, buf) => ...)` that writes outside `area` corrupts
   another region silently in production (`verifyRepaints` finds it in tests).
6. **Two watcher qualities behind one API.** `ref.view` / `ref.at(...)` / facade refs get the exact,
   timer-free watcher. A derived Kyo `Signal` (`ref.map(f)`, `combineLatest`) falls back to Kyo's
   repairing `observe`: one 1 s timer each while idle, and up to 1 s latency in a rare write race. The
   call sites look the same; only `stats.watchers` and the docs show the difference.
7. **One spurious wake per handled event.** The watcher of a ref that a handler wrote wakes the loop once
   more after the pull; that iteration finds nothing and draws nothing (est. 3-7 us).
8. **Handlers run on the render fiber.** This gives exact event order (typing is never reordered) and
   torn-free frames, but a slow handler (a network call) freezes the UI. Long work must go through
   `tui.spawn`. ZIO / Cats handlers also pay a cross-runtime fork + await per event (est. 5-20 us).
9. **Structural bindings remount.** A binding high in the tree (`focus.view(f => wholePane)`) remounts and
   repaints the whole pane on every change; lambdas in the produced subtree make the View memo useless.
   Retained widget state is transferred by path + leaf kind, which can pick the wrong leaf when the
   structure changes (use `.key(...)`).
10. **Change detection is equality.** State must be immutable values with correct `equals`. A mutable
   collection mutated in place inside a ref never re-renders, silently. Under `-language:strictEquality`
   every state type needs `derives CanEqual` (good for Kyo, friction for some users).
11. **Built-in focus model.** "Focused path first, Tab cycles the group" is opinionated. Modal key
   schemes (vim-like) must handle keys at the root and may need to switch the default Tab behaviour off.
12. **The pull is linear in the number of mounted bindings** (B x est. 30-100 ns per wake). Fine for
   hundreds; a screen with tens of thousands of bindings needs the root -> bindings index (Q1).
13. **Interop with native ecosystem state is one hop away.** ZIO `SubscriptionRef` and fs2
   `SignallingRef` cannot be bound directly (their reads are effects, the pull needs synchronous reads);
   they need an adapter fiber that copies into a tui4s `SignalRef`.
14. **Kyo types are not 100% invisible to facade users.** `Source.view` has `(using Frame)`; users never
   write it, but IDE signature help shows a Kyo type. Error messages from the core may mention Kyo.
15. **"Where to put `.view`" is a performance knob.** A root-level binding is correct but turns the app
   into an Elm-style full repaint, silently. Mitigation: `stats` and a debug overlay that flashes
   repainted regions; docs with the rule "bind at the leaves".

### 8.2 Implementation risks

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Lost or duplicated wakes in the watcher protocol | stale screen until the next event | register-before-wake algorithm (5.3) + a stress test: N fibers write random values, `settle`, compare the screen with the final values |
| Watcher ref-count leaks on remount | idle cost grows with every tab switch | test: switch tabs 1000x, assert `stats.watchers` is constant |
| `SignalRef.unsafe.next()` changes before Kyo 1.0 | watcher breaks | one internal file; ask upstream for a public lossless primitive (Q7) |
| Scheduler flags set too late (`StaticFlag` read at class init) | idle CPU stays at ~13% | set in app base-class constructors; document `-D` flags; verify with the idle probe |
| jatatui changes `Buffer.diff` rules (wide glyphs) | rendering artifacts | pin jatatui; upgrade checklist includes re-porting; the Retained-vs-FullFrame property test |
| `IntoAction[R]` inference with Kyo's opaque `<` and PF literals (UNVERIFIED) | handler syntax needs type ascriptions | validator compiles the samples; fallback: `Keymap` values (`.onKey(Keymap { ... })`) or per-ecosystem method names |
| Input fiber in a blocking JNI `poll` on a Kyo worker | worker starvation | validated shape (probe); option: dedicated platform thread |
| Export of the DSL through `object Dsl` + aliases | facade users miss a name | compile check of the facade samples; explicit alias list |

### 8.3 Open questions for the panel

- **Q1** Pull everything per wake (simple, exact; chosen) or keep a root -> bindings index and pull only
  the bindings of the roots that fired (faster at scale, more state to keep right)?
- **Q2** Add `tui.batch(effect)` (holds the pull until the effect ends) for multi-ref background updates,
  or keep the "one ref per consistent unit" rule only?
- **Q3** Handlers on the render fiber (exact order; chosen) or each handler on its own fiber with a
  per-focus queue (UI never blocks, ordering is weaker)?
- **Q4** Facade packages: `tui4s.zio` / `tui4s.cats` (symmetric; hazard 4.8) or `tui4s.interop.zio` /
  `tui4s.interop.cats` (safe)?
- **Q5** Inline viewport: support it in the retained presenter (viewport offset, `insertBefore`) or
  always use `Presenter.FullFrame` there?
- **Q6** Ship tui4s-owned `items` / `table` widgets that paint row by row (selection move = 2 rows), or
  accept whole-widget repaint and stay with jatatui's `List` / `Table`?
- **Q7** Ask Kyo upstream for (a) a public lossless "next change after this one" primitive on
  `SignalRef` (or a version counter), (b) an idle-parking clock thread (F13).
- **Q8** Wrap state in a tui4s type (`State[A]` opaque over `SignalRef`) to get `update` / `at` as real
  methods and hide `unsafe`, at the cost of Kyo ecosystem fit? This design says no (extensions only).
- **Q9** Should `Tui.run`'s setup run before raw mode (chosen: errors are readable, but the app cannot
  measure the alternate screen size until mount) or after?
- **Q10** Is a global default "Ctrl-C quits" acceptable for all apps, or should it be opt-in?

### 8.4 What I could not verify (summary of UNVERIFIED items)

- Nothing in this file was compiled. In particular: inference of `R` in `onKey[R](pf)(using IntoAction[R])`
  for Kyo, ZIO and Cats bodies; the contravariant given resolution; the parameter untupling in
  `Kyo.zip(...).map((a, b, c) => ...)`; `export tui4s.Dsl.*` with type aliases for the facades.
- That `TuiApp` / `TuiZIOApp` / `TuiIOApp` constructors run before the Kyo scheduler initializes.
- Per-step costs marked "est." in section 7 (input JNI conversion, sparkline/list paint at 120x40,
  `backend.draw` of a few cells, facade handler hop).
- `CanEqual` for `zio.Chunk` under strictEquality via `canEqualSeqs`.
- That crossterm's reader (input fiber) and writer (render fiber) can be used from two threads at once
  (every crossterm app with a reader thread does this; not checked in the jatatui sources).
