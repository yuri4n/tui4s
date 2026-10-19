# tui4s

A terminal runner for [kyo-ui](https://github.com/getkyo/kyo), in Scala 3.

<!-- claude 2026-10-12: changed these lines: tui4s runs kyo-ui values (C1) -->
A kyo-ui value describes the screen; tui4s lays it out in terminal cells and
paints it with [jatatui](https://github.com/oyvindberg/jatatui), a Java port of
[ratatui](https://ratatui.rs). The core runs on [Kyo](https://getkyo.io).
Integrations for [ZIO](https://zio.dev) and
[Cats Effect](https://typelevel.org/cats-effect/) are planned.

> **Status:** under construction, first usable version. A tui4s app is a
> kyo-ui UI and a `TuiApp` main class: `Tui.run` runs the UI in the real
> terminal, renders the value, follows its signals and routes keys (focus,
> handlers, text editing, paste), and it mounts components in two styles
> (hooks and a `Component` class); a headless kit tests it without a terminal.
> Nothing is published yet.
<!-- claude 2026-10-12: changed the status line (C2) -->
<!-- claude 2026-10-13: changed the status line (C3): keys -->
<!-- claude 2026-10-13: changed the status line (T1): the real terminal -->
<!-- claude 2026-10-13: changed the status line (C4): components and hooks -->
<!-- claude 2026-10-13: changed the status line (T2): TuiApp -->

Components come in two styles. Hooks: a function, for example
`for count <- Signal.initRef(0) yield div(...)`, and `component(key)(body)`
for a node that the runner mounts. A class: `final class Counter extends Component`
with `type State = Int`, `initial`, `view(state)` and `onMount(state)`. See
the Scaladoc of `tui4s.Hooks` and `tui4s.Component`.
<!-- claude 2026-10-13: created these lines (C4): the two component styles -->
<!-- claude 2026-10-19: changed: Signal.initRef, not the deprecated useSignal (P1b, A5 f) -->

## Development setup

With [Nix](https://nixos.org/download/) installed and flakes enabled:

```sh
git clone --recurse-submodules https://github.com/yuri4n/tui4s.git
cd tui4s
nix develop
sbt check examples/Universal/stage
examples/target/universal/stage/bin/counter
```

<!-- claude 2026-10-19: changed: Node.js (L2a) -->
The development shell supplies JDK 25, sbt, Node.js (for the Scala.js tests),
and the tools for the terminal checklist. Run the last command in a real
terminal; `q` exits. For an existing
checkout, run `git submodule update --init --recursive` before building.
The flake defines shells for Linux on x86_64 or aarch64 and Apple Silicon
macOS. On Intel macOS, or without Nix, install JDK 25, sbt and Node.js, then
use the same sbt commands. These shell targets do not imply terminal runtime testing
on every platform.

For automatic activation, install direnv and nix-direnv and enable direnv's
hook in your shell. For zsh, add `eval "$(direnv hook zsh)"` to `~/.zshrc`.
Then run `direnv allow` in this checkout. The committed `.envrc` uses the same
locked flake as `nix develop`; entering this directory activates its tools.
Run `direnv allow` separately in `vendor/pini4s` when developing that library.

`sbt examples/Universal/packageBin` creates a ZIP distribution under
`examples/target/universal/`. It contains a launcher for each example and its
runtime JARs; the receiving machine still needs JDK 25. The existing
`sbt examples/launchers` command and `examples/target/bin/<app>` paths remain
available. See [running and packaging examples](examples/README.md#run-an-example)
and [pini4s's standalone setup and demo](vendor/pini4s/README.md).

## Quick start

<!-- claude 2026-10-13: created this section (T1): the Counter example -->
<!-- claude 2026-10-13: changed this section (T2, OS-48 part): the hooks Counter with its TuiApp main, and its test; the two code blocks are the text of the example files (ReadmeSuite checks them) -->
<!-- claude 2026-10-17: changed the imports of the first code block (scalafix OrganizeImports changed them in Counter.scala) -->
<!-- claude 2026-10-19: changed: the Counter uses Signal.initRef, not the deprecated useSignal (P1b, A5 f) -->
The smallest app: the Counter example
(`examples/src/main/scala/examples/counter/Counter.scala`). The UI is plain
kyo-ui with one piece of state (a `SignalRef` from `Signal.initRef`);
`object CounterApp extends TuiApp` is its main class. `+` and `-` change the count, `q` and Ctrl-C quit:

<!-- from: examples/src/main/scala/examples/counter/Counter.scala#quickstart -->
```scala
import kyo.*
import kyo.UI.*
import tui4s.{Tui, TuiApp}

/** The Counter: a kyo-ui UI with one piece of state (a `SignalRef`), key
  * handlers and two buttons. `+` and `-` change the count, Tab moves between
  * the buttons, Enter and Space press them, `q` quits (and so does Ctrl-C).
  */
object Counter:

    /** The Counter UI; `quit` ends the run. */
    def ui(quit: Unit < Sync): UI < Sync =
      Signal.initRef(0).map(count => view(count, quit))

    /** The Counter view over a count that the caller owns. */
    def view(count: SignalRef[Int], quit: Unit < Sync): UI =
      div.onKeyDown {
        case KeyboardEvent(Keyboard.Char('+'), _, _) =>
          count.updateAndGet(_ + 1)
        case KeyboardEvent(Keyboard.Char('-'), _, _) =>
          count.updateAndGet(_ - 1)
        case KeyboardEvent(Keyboard.Char('q'), _, _) => quit
        case _                                       => ()
      }(
        h1("Counter"),
        span("Count: ", count.map(_.toString)).id("count"),
        div.style(_.row.gap(1.px))(
          button("+").onClick(count.updateAndGet(_ + 1)),
          button("-").onClick(count.updateAndGet(_ - 1)),
        ),
        p("+ / - change it, Tab moves, Enter presses, q quits").style(
          _.color(Style.Color.gray),
        ),
      )
end Counter

/** Runs the Counter in the real terminal. Start it with its start script, not
  * with `sbt run` (sbt also reads the keyboard):
  * {{{
  * sbt examples/launchers
  * examples/target/bin/counter
  * }}}
  */
object CounterApp extends TuiApp:
    def ui: UI < (Async & Scope) = Counter.ui(Tui.quit)
```

Start it in a terminal. The script uses `$JAVA_HOME/bin/java`, or `java` on
`PATH` when `JAVA_HOME` is not set: it must be JDK 25.
<!-- claude 2026-10-13: changed (T1, review R3-6): JAVA_HOME comes first -->

```sh
sbt examples/launchers
examples/target/bin/counter
```

Do not use `sbt run`: sbt also reads the keyboard. The script starts the app
in its own JVM with the flag that a run needs,
`--enable-native-access=ALL-UNNAMED` (the terminal library is native).
`TuiApp` sets the tui4s scheduler values in code before the app starts (fewer
split frames under load, less CPU when idle), and it installs the capture of
`System.out`, `System.err` and `println` while the UI is up. The exit code is
0 after a quit (also Ctrl-C), 1 after a failure (the error is printed after
the terminal is restored), and 143 or 130 for `kill -TERM` or `kill -INT`
(the terminal is restored first). An app with a main of its own (for example
a `KyoApp` that calls `Tui.run`) gives the scheduler values as JVM flags: see
`examples/README.md`, which also lists the other examples (two counters, both
component styles, Todo, Dashboard).

A test runs the UI on `tui4s.testkit.Headless`, with no terminal
(`examples/src/test/scala/examples/counter/CounterSuite.scala`):

<!-- from: examples/src/test/scala/examples/counter/CounterSuite.scala#quickstart-test -->
```scala
import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.testkit.Headless

/** The Counter on the headless test kit: no terminal. */
class CounterSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    test("+ and - change the count") {
      val s = run {
        for
            h <- Headless.start(50, 8)(Counter.ui)
            _ <- h.char('+')
            _ <- h.char('+')
            _ <- h.char('-')
            s <- h.screen
        yield s
      }
      assert(s.contains("Count: 1"), s.text)
    }
```

## State, events, and reusable widgets

Use Kyo directly: `SignalRef` owns mutable state, `Signal.map` derives values,
and kyo-ui binds inputs with `.value(ref)` or `.checked(ref)`. Component events
are typed Scala callbacks; Kyo `Emit` separates event-producing logic from its
handler when needed. An ordinary class of signals and actions can be a shared
store, with Kyo `Env` or `Layer` supplying it to the app.

<!-- claude 2026-10-19: changed this paragraph (L2b): Tabs is in tui4s-widgets, its selection is a Model; the adapter (L2b review) -->
The tabs of the Dashboard (the adapter `examples.widgets.Tabs` over
`tui4s.widgets.Tabs`) own their selection by default. The `Model` of a widget says who owns its value:
`selected = Model.Bound(ref)` lets the widget write the parent's ref, and
`selected = Model.Controlled(signal)` with `onChange = next => ...` lets the
parent accept or reject each request. `tui4s.widgets.Tabs` is a plain Kyo UI
construction effect; `examples.widgets.Tabs` adds the tui4s mount lifetime.

The [state and events guide](examples/README.md#state-and-events-with-kyo)
contains complete examples of native bindings, local and controlled tabs,
typed events, and optional shared stores. The small
[pini4s](https://github.com/yuri4n/pini4s) library adds named references for
components that explicitly share state. Neither tabs nor native bindings
require it.

## Scroll, wrap and ellipsis

<!-- claude 2026-10-19: created this section (L1a; the tab stop, the row wrap, the ellipsis rule, no focus, the features with no example: L1a review): the code block is the text of the region "scroll" of the Todo example (ReadmeSuite checks it) -->
The Todo example (`examples/src/main/scala/examples/todo/Todo.scala`) puts
its list and its input in a viewport that scrolls, and a long title ends with
"…". These are plain kyo-ui styles:

<!-- from: examples/src/main/scala/examples/todo/Todo.scala#scroll -->
```scala
    /** One line of text: a longer text ends with "…" at the edge of its box
      * (CSS `overflow: hidden`, `text-overflow: ellipsis`,
      * `white-space: nowrap`).
      */
    val oneLine: Style =
      Style.overflow(_.hidden).textOverflow(_.ellipsis).whiteSpace(_.noWrap)

    /** A row: the check box keeps its 3 cells, and a long title ends with "…".
      * The list names the selected row by its id.
      */
    def row(r: Row): Ast.Li =
      li.id(s"item-${r.item.id}")
        .style(
          (if r.selected then
               Style.bg(Style.Color.indigo).color(Style.Color.white)
           else Style.empty).gap(1.px),
        )(
          checkbox.checked(r.item.done).style(_.flexShrink(0)),
          span(r.item.text).style(
            if r.item.done then oneLine.strikethrough.color(Style.Color.gray)
            else oneLine,
          ),
        )

    /** The list keeps the focus while the arrows move the selection:
      * `aria-activedescendant` names the selected row (`selected`, its id), so
      * the viewport around the list scrolls to it, and `preventScrollKeys`
      * keeps the arrows from scrolling it too. kyo-ui's `aria` takes a fixed
      * value, so the list is built again when `selected` changes; a toggle or
      * an add changes only its rows (the `foreachKeyed`).
      */
    def list(
        selected: Signal[String],
        rows: Signal[Chunk[Row]],
        onKey: KeyboardEvent => Any < Async,
    ): UI =
      selected.map(id =>
        ul.id("list")
          .tabIndex(0)
          .preventScrollKeys
          .aria("activedescendant", id)
          .onKeyDown(onKey)
          .style(
            _.border(1.px, Style.Color.slate)
              .minHeight(4.px)
              .focus(_.border(1.px, Style.Color.blue)),
          )(
            rows.foreachKeyed(_.item.id.toString)(row),
          ): UI,
      )

    /** The list and the input bar under it, in a viewport that takes the free
      * rows and scrolls (`overflowY: auto`). It is not a tab stop, because it
      * holds focusable elements (its list and its input).
      */
    def body(list: UI, input: UI): UI =
      div.style(_.overflowY(_.auto).flexGrow(1))(list, input)
```

An element with `overflow`, `overflowX` or `overflowY` other than `visible`
is a viewport. tui4s lays out its content at its natural size on that axis,
clips it to the box, and shows the window at the scroll offset. `hidden`
clips and does not scroll. `scroll` and `auto` also scroll. Such a viewport
is a tab stop when its content does not fit and it holds no focusable
element (as in Chrome); `tabIndex(-1)` takes it out. A viewport around a
list of buttons is not a tab stop: the keys on a button scroll it. A focused
viewport draws its track heavy (`┃`); with no indicator, only a border
(heavy when focused) or a `focus` style marks it. When
its content is taller than the box and the box has 3 rows or more, a scroll
indicator (a track with a thumb) takes its right column
(`scrollbarWidth(_.none)` hides it, `scrollbarColor` colours it). In a
column, a viewport can shrink to the free rows: give it, or a box around it,
a height (`height`, `maxHeight`, `height(100.pct)` on the root, `flexGrow`).

| Key on the focused element (or on an element inside the viewport) | Scroll |
| --- | --- |
| ArrowUp, ArrowDown | one row |
| PageUp, PageDown | one viewport height |
| Home, End | to the top, to the bottom |
| ArrowLeft, ArrowRight | one column (a viewport that scrolls horizontally) |

The innermost viewport that can move scrolls. A key does not scroll when the
focused element uses it (a text input moves its caret, a select cycles, the
arrows of a focus group move the focus), or when an element of the focus
chain has kyo-ui's `preventScrollKeys`. When the focus moves, each viewport
around the focused element scrolls the least distance that shows it, as a
browser does; a key or a paste on the focused element keeps it in view too.
A list that keeps the focus and moves a selection names the selected element
with `aria("activedescendant", id)`, and the viewport follows that element.
kyo-ui's `aria` takes a fixed value, so the Todo list is built again when the
selection changes (a `SignalRef` of the selected id); a toggle or an add
changes only its rows. With no focus (`autoFocus = false` before the first
Tab), a key scrolls nothing.

Text: `whiteSpace(_.noWrap)` keeps one line, `pre` keeps the spaces and the
line breaks with no wrap, `preWrap` keeps them and wraps, `normal` and
`preLine` collapse the spaces. A text wraps in a column, and in a row box of
a definite `width`. The row wrap is new: before, a text in a row never
wrapped. A row that shrinks such a text makes it as high as its lines at its
new width. `textOverflow(_.ellipsis)` with `overflow(_.hidden)` on the
element that holds the text puts "…" at the cut. Every measure counts
terminal cells: a wide character (CJK, an emoji) takes two cells, and a cut
never splits it.

The examples do not show `pre`, `preWrap`, `textWrap(_.noWrap)`,
`scrollbarWidth`, `scrollbarColor` or a horizontal scroll yet; the goldens of
`GoldenScreenTest` do.

## Overlays, terminal size and theme colors

<!-- claude 2026-10-19: created this section (L1b): the code blocks are the text of the region "overlays" of the Dashboard example and of the region "colors" of the CounterStyles example (ReadmeSuite checks them) -->
The Dashboard example (`examples/src/main/scala/examples/dashboard/Dashboard.scala`)
opens a help box over the screen with `?`, shows a toast when `p` pauses
the sampler, and puts the tabs and the log side by side in a terminal of 120
columns or more. The boxes are plain kyo-ui styles; the size is a tui4s
signal:

<!-- claude 2026-10-19: changed: the help box lists the keys of a keymap (L2a) -->
<!-- from: examples/src/main/scala/examples/dashboard/Dashboard.scala#overlays -->
```scala
    /** The terminal width from which the tabs and the log are side by side. */
    val wideFrom: Int = 120

    /** The tabs and the log side by side in a terminal of `wideFrom` columns or
      * more, else one above the other. `Tui.size` is the size of the terminal
      * as a signal (kyo-ui has no such signal on the JVM), and a signal of
      * styles restyles the box when the size changes.
      */
    def body(tabs: UI, log: UI, size: Signal[TerminalSize]): UI =
      div(div(tabs).style(_.flexGrow(1)), div(log).style(_.flexGrow(1)))
        .style(
          size.map(t =>
            if t.width >= wideFrom then Style.row.gap(2.px) else Style.column,
          ),
        )

    /** The help: a box in the middle of the screen, over the rest (a
      * `position(_.overlay)` layer that centers it), with a row for each key of
      * `keys`. Its `focusTrap` keeps Tab inside it; its Close button takes the
      * focus when it appears (`focusAuto`) and gives it back when it goes
      * (`focusRestore`). Esc does nothing by itself in a focus trap: the
      * `onKeyDown` of the box closes it.
      */
    def help(s: State, keys: Keymap): UI =
      when(s.help)(
        div.style(_.position(_.overlay).justify(_.center).align(_.center))(
          div
            .id("help")
            .focusTrap(true)
            .onKeyDown {
              case KeyboardEvent(Keyboard.Escape, _, _) => s.help.set(false)
              case _                                    => ()
            }
            .style(
              _.border(1.px, Style.Color.blue)
                .bg(Style.Color.black)
                .padding(0.px, 1.px),
            )(
              h2("Keys"),
              span("←/→  change the tab"),
              UI.fragment(keys.help.map((k, h) => span(f"$k%-4s $h"))*),
              button("Close")
                .id("help-close")
                .focusAuto(true)
                .focusRestore(true)
                .onClick(s.help.set(false))
                .style(_.alignSelf(_.center)),
            ),
        ),
      )

    /** The toast: a box at the bottom right (`position(_.fixed)` with `bottom`
      * and `right`), over the rest, while a message is shown.
      */
    def toast(s: State): UI =
      s.toast.map {
        case None    => UI.empty
        case Some(t) =>
          div.style(
            _.position(_.fixed)
              .bottom(1.px)
              .right(2.px)
              .border(1.px, Style.Color.green)
              .bg(Style.Color.black)
              .padding(0.px, 1.px),
          )(t.text)
      }
```

A positioned element is a layer. `position(_.absolute)` takes the element out
of the flex flow and places it in its containing block: the padding box of
the nearest positioned ancestor, else the screen. `position(_.fixed)` places
it in the screen. kyo-ui's `overlay` is a fixed box over the whole screen,
and its `dropdown` is an absolute box under the right edge of its positioned
ancestor. `top`, `right`, `bottom` and `left` place the box from an edge; two
opposite offsets give its size, else `width` and `height` do, else its
natural size. `position(_.relative)` keeps the element in the flow, moves it
by its offsets, and makes it a containing block. `sticky` stays in the flow
and does not stick.

The flow paints first, then the layers by `zIndex` (0 when none), the equal
ones in document order. Each layer is a stacking context of its own, so its
positioned descendants paint inside its place in the order. In a layer, a
background hides what is under it; a layer with no background shows it. A
layer is clipped only by the viewports of its chain of containing blocks: a
fixed element escapes every viewport. When a frame changes cells under a
layer, the runner paints those rows again in paint order, so nothing under a
layer shows through it.

A layer stays in the Tab order at its place in the document, as in a
browser. A modal is a layer with kyo-ui's `focusTrap`: Tab stays inside it.
Its first focusable element with `focusAuto(true)` takes the focus when it
appears; with `focusRestore(true)` the focus goes back to the element that
had it when the modal closes. Esc does nothing by itself in a focus trap:
the `onKeyDown` of the app closes the modal.

`Tui.size` is the size of the terminal in cells, as a `Signal[TerminalSize]`.
kyo-ui has no signal of the window size on the JVM (its `UIWindow.size` is in
the Scala.js jar only). The construction of the UI, a hook or class
component, and a handler can read it. A resize writes it in the frame of the
resize, so the layout that derives from it comes in the same frame.

`flexWrap(_.wrap)` breaks the items of a row (or of a column of a definite
height) into lines. Each line is as high as its tallest item, and the lines
stack from the top. kyo-ui has no `alignContent`, so the lines stretch, as
in a browser: when the container is higher than its lines, each line gets an
equal part of the free rows. `displayNone` removes an element from the
layout and the paint (its components keep their state).

The CounterStyles example declares its colours once, as kyo-ui custom
properties, and names them in its counters:

<!-- from: examples/src/main/scala/examples/counterstyles/CounterStyles.scala#colors -->
```scala
    /** The custom colours of the app, declared once for the whole tree: a
      * kyo-ui `Stylesheet.vars` block (CSS custom properties on `:root`). The
      * run gets it as its stylesheet (`TuiApp.stylesheet`, or the `stylesheet`
      * of `Headless.start`), and an element names a colour with
      * `Style.Color.variable`.
      */
    val colors: Stylesheet = Stylesheet.vars(
      "frame" -> "#64748b",
      "label" -> "#eab308",
      "count" -> "#22c55e",
    )

    val frame: Style.Color = Style.Color.variable("frame")
    val label: Style.Color = Style.Color.variable("label")
    val count: Style.Color = Style.Color.variable("count")
```

kyo-ui declares a custom property only in a `Stylesheet` (`vars` for the
root, `scopedVars` for a class, an id or a data attribute), so the run takes
the stylesheet: `override def stylesheet` in a `TuiApp`,
`Tui.run(options, sheet)`, or the `stylesheet` of `Headless.start`. A block
of a selector declares over the root in the subtree of the element that it
matches. A value is `#hex`, `rgb()`, `rgba()`, `transparent` or `var(--other,
fallback)`. A `var()` in a value takes its value where the block declares
it, as in CSS. A name with no value gives no colour: a text keeps the colour
of its parent, and a background stays empty. The log of the run has one
warning for that name.

A style property that the terminal does not show (`fontSize`, `hover`,
`shadow`, ...) gives one warning in the log of the run, once for each name:
"tui4s: the style property FontSizeProp is not supported in the terminal
(first seen at ...)". The package Scaladoc of `tui4s` lists the honored and
the ignored properties. In a test, `Headless.awaitLog` gives the lines.

## Forms and inputs

<!-- claude 2026-10-19: created this section (L1c): the code block is the text of the region "forms" of the Forms example (ReadmeSuite checks it) -->
The Forms example (`examples/src/main/scala/examples/forms/Forms.scala`)
puts the native inputs of the terminal in one form. The inputs are plain
kyo-ui elements with their `.value(ref)` and `.checked(ref)` bindings; the
only tui4s name is `maxLength`:

<!-- from: examples/src/main/scala/examples/forms/Forms.scala#forms -->
```scala
import kyo.*
import kyo.UI.*
import tui4s.{Tui, TuiApp, maxLength}

/** A sign-up form with the native inputs of kyo-ui: a text input, a password, a
  * number, a range, two checkboxes, two radios, a select, a dropdown, a
  * textarea of 3 rows, a read-only and a disabled field, and the Submit and
  * Reset buttons. Tab moves between the fields. Enter in a field submits the
  * form (also in the textarea: Alt-Enter or Ctrl-J there gives a new line). The
  * result shows under the form. Esc quits.
  */
object Forms:

    /** One row of the form: a label of 9 cells and its input (a `fragment` puts
      * more inputs side by side).
      */
    def field(label: String, input: UI): UI =
      div.style(_.row.gap(1.px).align(_.start))(
        span(label).style(_.width(9.px)),
        input,
      )

    /** The form; `quit` ends the run. */
    def ui(quit: Unit < Sync): UI < Sync =
      for
          name    <- Signal.initRef("")
          secret  <- Signal.initRef("")
          age     <- Signal.initRef("30")
          volume  <- Signal.initRef(50.0)
          news    <- Signal.initRef(true)
          updates <- Signal.initRef(false)
          plan    <- Signal.initRef("free")
          colour  <- Signal.initRef("blue")
          size    <- Signal.initRef("m")
          message <- Signal.initRef("")
          result  <- Signal.initRef("Fill the form, then press Enter.")
      yield
          def yesNo(on: Boolean, what: String): String =
            if on then what else s"no $what"
          val submit: Unit < Sync                      =
            for
                n <- name.get
                s <- secret.get
                a <- age.get
                v <- volume.get
                l <- news.get
                u <- updates.get
                p <- plan.get
                c <- colour.get
                z <- size.get
                m <- message.get
                _ <- result.set(
                       s"Sent: $n, ${yesNo(s.nonEmpty, "secret")}, age $a, " +
                         s"volume ${v.toInt}, ${yesNo(l, "news")}, " +
                         s"${yesNo(u, "updates")}, $p, $c, size $z, " +
                         s"${m.linesIterator.size} message lines",
                     )
            yield ()
          // Reset gives every field its first value
          val reset: Unit < Sync                       =
            Kyo
              .foreach(Seq(name, secret, message))(_.set(""))
              .andThen(age.set("30"))
              .andThen(volume.set(50.0))
              .andThen(news.set(true))
              .andThen(updates.set(false))
              .andThen(plan.set("free"))
              .andThen(colour.set("blue"))
              .andThen(size.set("m"))
              .andThen(result.set("The form has its first values again."))
          // two radios of one group: the plan is the state; each radio shows
          // it and asks to change it
          def choice(value: String, label: String): UI =
            div.style(_.row.gap(1.px))(
              radio
                .onChange(_ => plan.set(value))
                .checked(plan.map(_ == value)),
              span(label),
            )
          div.onKeyDown {
            case KeyboardEvent(Keyboard.Escape, _, _) => quit
            case _                                    => ()
          }(
            h1("Sign up"),
            form.onSubmit(submit)(
              field("Name", input.value(name).placeholder("Ada").maxLength(20)),
              field("Secret", passwordInput.value(secret)),
              field("Age", numberInput.value(age).min(0).max(130).step(1)),
              field("Volume", rangeInput.value(volume).min(0).max(100).step(5)),
              field(
                "Mail",
                fragment(
                  checkbox.checked(news),
                  span("news"),
                  checkbox.checked(updates),
                  span("updates"),
                ),
              ),
              field(
                "Plan",
                fragment(choice("free", "Free"), choice("pro", "Pro")),
              ),
              field(
                "Colour",
                select.value(colour)(
                  option.value("blue")("Blue"),
                  option.value("green")("Green"),
                ),
              ),
              field(
                "Size",
                dropdown("s" -> "Small", "m" -> "Medium", "l" -> "Large")
                  .value(size),
              ),
              field(
                "Message",
                textarea.value(message).style(_.width(30.px).height(3.px)),
              ),
              field("Account", input.value("ada-01").readOnly(true)),
              field("Team", input.value("core").disabled(true)),
              div.style(_.row.gap(1.px))(
                button("Submit"),
                // kyo-ui has no button type: a button in a form submits it,
                // so Reset stops its click before the form
                button("Reset").onClick(reset).stopPropagation(true),
              ),
            ),
            p(result).id("result"),
          )
end Forms

/** Runs the form in the real terminal: `examples/target/bin/forms`. */
object FormsApp extends TuiApp:
    def ui: UI < (Async & Scope) = Forms.ui(Tui.quit)
```

Each input edits its bound ref before its `onInput` or `onChange` runs.
Tab and Shift-Tab move between the inputs. The keys of each input:

| Input | Keys |
| --- | --- |
| `input`, `emailInput`, `telInput`, `urlInput`, `searchInput` | Type, Backspace, Delete, ArrowLeft, ArrowRight, Home, End, Ctrl-A, Ctrl-E, Ctrl-U, Ctrl-K, Ctrl-W; by grapheme clusters. A paste is one edit; a line break in it becomes a space. Enter runs `onChange` and submits the form. |
| `passwordInput` | As `input`. Each grapheme cluster shows as one "•"; the text never reaches the screen. |
| `numberInput` | Digits, one minus sign first and one decimal point (no `+`, as in HTML). ArrowUp and ArrowDown change the value by `step` (1 when none), clamped to `min` and `max`, and run `onChangeNumeric`. Enter writes the number as kyo-ui shows it ("1." becomes "1") and runs `onChangeNumeric`. |
| `textarea` | Lines that wrap at the spaces, by cells. ArrowUp and ArrowDown move one line and keep the column; PageUp and PageDown move by the height; Home and End go to the ends of the line; Ctrl-A and Ctrl-E to the ends of the text line between two line breaks. Alt-Enter and Ctrl-J insert a line break, and Shift-Enter too when the terminal reports Shift with Enter. A plain Enter submits the form that holds the textarea; outside a form it inserts a line break, as in a browser. A paste keeps its line breaks. A long text scrolls inside the textarea, and the view follows the caret. When the focus leaves the textarea after an edit that changed its text, `onChange` runs with the text (the change event of a browser). |
| `rangeInput` | ArrowLeft and ArrowDown, ArrowRight and ArrowUp move by `step`; PageDown and PageUp by 10 steps; Home and End to `min` and `max` (0 and 100 when none). The value stays on the steps from `min`, as in HTML. |
| `checkbox`, `radio` | Space and Enter toggle a checkbox and check a radio. A group of radios is one ref of the app (the example's `plan`). |
| `select`, `dropdown` | Space, Enter, ArrowDown and ArrowUp go to the next or the previous option. Enter does not submit the form. |
| `button` | Space and Enter press it: a click that submits the form that holds it. |

`readOnly(true)` keeps the focus and the moves of the caret, and stops every
edit. `disabled(true)` takes the input out of the Tab order, stops every key,
and dims it. kyo-ui has no `maxLength` attribute: `maxLength(n)` from
`tui4s` limits the grapheme clusters that the keys and a paste give to a text
input or a textarea, and a paste is cut so that no character is split. A
browser does not read it. kyo-ui has no `rows` attribute either: the height
of a textarea is the `height` of its style (3 rows when none).

The focused text input or textarea shows its caret with the terminal cursor.
The date, time, colour and file inputs show an empty box; the log of the run
has one warning for each of them. A character outside the Basic Multilingual
Plane (an emoji) that is typed arrives wrong (jatatui 0.31.0 cuts it to 16
bits); a paste keeps it.

## Composables (tui4s-use)

<!-- claude 2026-10-19: created this section (L2a): the code blocks are the text of the region "native" of the Native recipes, of the region "composables" of the Dashboard example and of the regions of the Recipes example (ReadmeSuite checks them); changed: the names timeAgo and Keymap.of, the stream status, the %%% line (L2a review) -->
`io.github.yuri4n::tui4s-use` has composables: plain functions over Kyo's
`Signal`, `SignalRef` and `Scope`. The module does not depend on tui4s-core.
It builds for the JVM and for Scala.js, so a composable works in the terminal
and on the web. Add it next to tui4s-core:

```scala
libraryDependencies += "io.github.yuri4n" %% "tui4s-use" % "<version>"
// a Scala.js or a cross-built project:
libraryDependencies += "io.github.yuri4n" %%% "tui4s-use" % "<version>"
```

### You may not need a composable

Kyo does most of the work in one or two lines. A `SignalRef` holds state,
and `Signal.map` derives a value. `Signal.observe` runs a setup for each value
in a new `Scope`: when the value changes, the work for the old value stops.
These five need no library:

<!-- from: examples/src/main/scala/examples/composables/Native.scala#native -->
```scala
    /** A toggle: `updateAndGet` on a `SignalRef`. */
    def toggle(on: SignalRef[Boolean])(using Frame): Unit < Sync =
      on.updateAndGet(!_).unit

    /** A counter that stops at `max`. */
    def increment(count: SignalRef[Int], max: Int)(using Frame): Unit < Sync =
      count.updateAndGet(n => (n + 1).min(max)).unit

    /** An interval: a fiber that the caller's `Scope` ends. */
    def every(period: Duration)(action: => Any < Async)(using
        Frame,
    ): Unit < (Sync & Scope) =
      Fiber.init(Loop.forever(Async.sleep(period).andThen(action))).unit

    /** A timer that pauses: `Signal.observe` starts the ticks while `paused` is
      * false, and interrupts them when it changes.
      */
    def timer(paused: Signal[Boolean], ticks: SignalRef[Int], period: Duration)(
        using Frame,
    ): Unit < (Sync & Scope) =
      Fiber
        .init(
          paused.observe(p =>
            if p then Kyo.unit
            else every(period)(ticks.updateAndGet(_ + 1)),
          ),
        )
        .unit

    /** A derived value: `Signal.map`. */
    def label(count: Signal[Int])(using Frame): Signal[String] =
      count.map(n => s"$n items")
```

### The composables

| Composable | What it gives | Type |
| --- | --- | --- |
| `useNow(every)` | The time of Kyo's `Clock` as a signal. It ticks on each multiple of `every`. | `Signal[Instant] < (Sync & Scope)` |
| `timeAgo(at, now, format)` | "5 minutes ago": the `TimeAgo` ADT and an English formatter that you can replace. It has no lifetime, so it has no `use` name. | `Signal[String]` |
| `debounced(s, quiet)`, `throttled(s, every)` | A signal after a quiet time, or at most once per window. The last value always shows. | `Signal[A] < (Sync & Scope)` |
| `useDebouncedFn(quiet)(f)` | A function that runs `f` after a quiet time. A call stops the wait or the run of the call before it. | `(A => Unit < Sync) < (Sync & Scope)` |
| `useAsync(input)(f)` | The `AsyncState` (`Idle`, `Loading`, `Done`, `Failed`) of the work for the current input. A new input interrupts the old work. | `Signal[AsyncState[E, A]] < (Sync & Scope)` |
| `useStreamFold(s, zero)(f)`, `useStreamText(s)` | A stream folded into a signal, or a text stream joined (the tokens of a chat answer), with the status of the stream (`Running`, `Done`, `Failed`). | `StreamState[B] < (Sync & Scope)` |
| `Keymap.of(bindings*)` | A key map as data: a handler for `onKeyDown` and the key hints. It has no lifetime, so it has no `use` name. | `Keymap` |
| `useSelection(items, key, edge, page)` | The selected item by its key, with the moves. It follows its item when the list changes. | `Selection[A, K] < Sync` |
| `useScrollWindow(items, size, selected)` | The visible slice of a long list (a virtual list). The window moves the least distance. | `Signal[Window[A]] < (Sync & Scope)` |
| `useDialog[Q, A]` | A modal: `open` waits for the answer, `close` gives it, `launch` starts a flow from a key handler and never waits. | `Dialog[Q, A] < (Sync & Scope)` |
| `useHistory(ref, limit)` | Undo and redo over a `SignalRef`. | `History[A] < Sync` |
| `usePersisted(key, initial, storage)` | A `SignalRef` kept in a `Storage`: files on the JVM, `localStorage` in a browser. | `SignalRef[A] < (Sync & Scope)` |
| `useProcess(cmd)` | A program that runs (JVM): its output lines as a stream and its exit code as a signal. The end of the `Scope` kills it. Scala.js gives `ProcessError.Unsupported`. | `RunningProcess < (Sync & Scope & Abort[ProcessError])` |

A composable that starts a fiber or registers a finalizer needs the `Scope`
of its caller. The caller owns it: there is no hidden registry. Call a
composable once, where its state lives: at the start of the app, or in the
body of a component (`Hooks.component`, which tui4s mounts with a `Scope` of
its own). Do not call it in a `render`, `when` or `foreach` closure. Such a
closure runs again on each change, and each run starts a new composable.

A key handler of tui4s runs on the runner, one at a time. A handler that
waits for the answer of a dialog stops all input, so it starts the flow with
`Dialog.launch`. `launch` never waits: when 16 flows wait already, it drops
the flow and logs a warning. `debounced`, `throttled`, `useAsync`, `useScrollWindow` and
`usePersisted` watch a signal with `Signal.observe`. A write that lands while
the watch arms is seen at Kyo's next repair, at most 1 s later.

### Three composables in the Dashboard

The Dashboard keeps its keys in a keymap (the help box lists them). Its
status line says how long ago the last sample came:

<!-- from: examples/src/main/scala/examples/dashboard/Dashboard.scala#composables -->
```scala
    /** The keys of the Dashboard as data: the root handler runs them, and the
      * help box lists them.
      */
    def keys(s: State)(using Frame): Keymap =
      Keymap.of(
        Binding.of("pause or resume the sampler", Chord.char('p'))(pause(s)),
        Binding.of("this help (Esc closes it)", Chord.char('?'))(
          s.help.set(true),
        ),
        Binding.of("quit", Chord.char('q'))(Tui.quit),
      )

    /** How long ago the last sample came ("updated 5 seconds ago"), from a
      * clock that ticks each second; the clock stops with the `Scope` of the
      * app.
      */
    def stamp(s: State)(using Frame): UI < (Sync & Scope) =
      for now <- useNow(1.second)
      yield span(timeAgo(s.updated, now).map(t => s"updated $t"))
        .style(_.color(Style.Color.gray))
```

The Todo example writes its list through `useHistory`: Ctrl-Z undoes a change
of the list, and Ctrl-Y redoes it.

### More composables, one recipe each

<!-- claude 2026-10-19: created this section (L2a review): each block is the text of a region of the Recipes example (ReadmeSuite checks them) -->
Each recipe is a small UI in `examples.composables.Recipes`. Its suite
(`RecipesSuite`) runs each one on the headless test kit.

A search as the user types (`debounced` and `useAsync`):

<!-- from: examples/src/main/scala/examples/composables/Recipes.scala#search -->
```scala
    /** A search as the user types: `debounced` waits until the query is quiet
      * for 200 ms, and `useAsync` runs `find` for it. A new query interrupts
      * the search for the old one.
      */
    def search(find: String => Chunk[String] < Async)(using
        Frame,
    ): UI < (Sync & Scope) =
      for
          query   <- Signal.initRef("")
          quiet   <- debounced(query, 200.millis)
          results <- useAsync(quiet.map(q => Maybe.when(q.nonEmpty)(q)))(find)
      yield div(
        input.value(query).focusAuto(true),
        results.map {
          case AsyncState.Idle       => span("type to search")
          case AsyncState.Loading    => span("searching...")
          case AsyncState.Done(rows) => span(s"found: ${rows.mkString(", ")}")
          case AsyncState.Failed(e)  => span(s"failed: $e")
        },
      )
```

A progress that changes faster than the screen needs (`throttled`):

<!-- from: examples/src/main/scala/examples/composables/Recipes.scala#progress -->
```scala
    /** A progress that changes faster than the screen needs: `throttled` shows
      * it at most once per 100 ms, and the last value always shows.
      */
    def progress(done: Signal[Int])(using Frame): UI < (Sync & Scope) =
      for shown <- throttled(done, 100.millis)
      yield span(shown.map(n => s"$n %"))
```

A note that saves itself after the edits stop (`useDebouncedFn`):

<!-- from: examples/src/main/scala/examples/composables/Recipes.scala#autosave -->
```scala
    /** A note that saves itself: each edit starts the wait again, and `save`
      * runs 300 ms after the last edit, with the last text.
      */
    def autosave(save: String => Unit < Async)(using
        Frame,
    ): UI < (Sync & Scope) =
      for
          note   <- Signal.initRef("")
          status <- Signal.initRef("saved")
          later  <- useDebouncedFn[String](300.millis)(text =>
                      save(text).andThen(status.set("saved")),
                    )
      yield div(
        input
          .value(note)
          .focusAuto(true)
          .onInput(text => status.set("editing").andThen(later(text))),
        span(status),
      )
```

An answer that comes in pieces, with its status (`useStreamText`; `useStreamFold` is the general fold):

<!-- from: examples/src/main/scala/examples/composables/Recipes.scala#answer -->
```scala
    /** An answer that comes in pieces (the tokens of a chat model):
      * `useStreamText` joins them, and its status says when the answer is
      * complete.
      */
    def answer(tokens: Stream[String, Async])(using
        Frame,
    ): UI < (Sync & Scope) =
      for reply <- useStreamText(tokens)
      yield div(
        span(reply.value),
        span(reply.status.map {
          case StreamStatus.Running   => "..."
          case StreamStatus.Done      => "(complete)"
          case StreamStatus.Failed(e) => s"(failed: ${e.getMessage})"
        }),
      )
```

A long list with a selection and a visible window (`useSelection` and `useScrollWindow`):

<!-- from: examples/src/main/scala/examples/composables/Recipes.scala#list -->
```scala
    /** A long list that shows 5 rows: `useSelection` keeps the selected row by
      * its key, and `useScrollWindow` gives the rows to show. The arrows move
      * the selection.
      */
    def list(items: Signal[Chunk[String]])(using Frame): UI < (Sync & Scope) =
      for
          selection <- useSelection(items, identity[String])
          window    <- useScrollWindow(items, Signal.initConst(5), selection.index)
          keys       = Keymap.of(
                         Binding.of("up", Chord(Keyboard.ArrowUp))(selection.moveUp),
                         Binding.of("down", Chord(Keyboard.ArrowDown))(
                           selection.moveDown,
                         ),
                       )
      yield div
        .tabIndex(0)
        .focusAuto(true)
        .onKeyDown(keys.handler)(
          window
            .combineLatest(selection.key)
            .map((w, key) =>
              div(
                UI.fragment(
                  w.slice.map(row =>
                    span(if key.contains(row) then s"> $row" else s"  $row"),
                  )*,
                ),
              ),
            ),
        )
```

A delete that asks first (`useDialog`; a key handler starts the flow with `launch`):

<!-- from: examples/src/main/scala/examples/composables/Recipes.scala#confirm -->
```scala
    /** A delete that asks first: `d` starts a flow (`launch`) that opens the
      * dialog and waits for its answer; `y` or `n` answers.
      */
    def confirm(items: SignalRef[Chunk[String]])(using
        Frame,
    ): UI < (Sync & Scope) =
      for
          ask   <- useDialog[String, Boolean]
          delete = items.get.map(xs =>
                     if xs.isEmpty then Kyo.unit
                     else
                         ask
                           .open(s"Delete ${xs.head}?")
                           .map(yes =>
                             if yes then items.updateAndGet(_.drop(1)).unit
                             else Kyo.unit,
                           ),
                   )
          keys   = Keymap.of(
                     Binding.of("delete", Chord.char('d'))(ask.launch(delete)),
                     Binding.of("yes", Chord.char('y'))(ask.close(true)),
                     Binding.of("no", Chord.char('n'))(ask.close(false)),
                   )
      yield div
        .tabIndex(0)
        .focusAuto(true)
        .onKeyDown(keys.handler)(
          span(items.map(_.mkString(", "))),
          span(ask.request.map(_.fold("")(q => s"$q (y/n)"))),
        )
```

A setting that the next run keeps (`usePersisted`):

<!-- from: examples/src/main/scala/examples/composables/Recipes.scala#setting -->
```scala
    /** A setting that the next run keeps: `usePersisted` reads it from
      * `storage` and writes each change. `t` changes it. An app gives
      * `Storage.forApp("<app>")`; a test gives `Storage.inMemory`.
      */
    def setting(storage: Storage)(using Frame): UI < (Sync & Scope) =
      for
          dark <- usePersisted("dark", false, storage)
          keys  = Keymap.of(
                    Binding.of("theme", Chord.char('t'))(dark.updateAndGet(!_)),
                  )
      yield div
        .tabIndex(0)
        .focusAuto(true)
        .onKeyDown(keys.handler)(
          span(dark.map(d => if d then "theme: dark" else "theme: light")),
        )
```

The output and the exit code of a program (`useProcess`, on the JVM):

<!-- from: examples/src/main/scala/examples/composables/Recipes.scala#process -->
```scala
    /** The output of a program, a line at a time, and its exit code:
      * `useProcess` starts it, and the end of the `Scope` kills it.
      */
    def process(cmd: Chunk[String])(using Frame): UI < (Sync & Scope) =
      Abort.run[ProcessError](useProcess(cmd)).map {
        case Result.Success(run) =>
          useStreamFold(run.lines, Chunk.empty[String])((ls, l) =>
            (ls :+ l).takeRight(5),
          ).map(out =>
            div(
              span(out.value.map(_.mkString(" | "))),
              span(run.exit.map(_.fold("running")(c => s"exit ${c.toInt}"))),
            ),
          )
        case Result.Failure(e)   => span(s"cannot start: $e")
        case Result.Panic(t)     => span(s"cannot start: $t")
      }
```

## Widgets (tui4s-widgets)

<!-- claude 2026-10-19: created this section (L2b): the code blocks are the text of the regions of the gallery example (ReadmeSuite checks them); changed: the web is not checked yet (L2b review) -->
`io.github.yuri4n::tui4s-widgets` has widgets: plain kyo-ui functions for the
JVM and Scala.js. They use the composables of tui4s-use and do not depend on
tui4s-core, so a widget builds for the terminal and for the web. Today only
the terminal runs them; the web runner (L4) will check their sizes (a size
that counts rows or cells is in `em`). Add it next to tui4s-core:

```scala
libraryDependencies += "io.github.yuri4n" %% "tui4s-widgets" % "<version>"
// a Scala.js or a cross-built project:
libraryDependencies += "io.github.yuri4n" %%% "tui4s-widgets" % "<version>"
```

The rules of every widget:

- A `Model` says who owns the value of a widget. `Model.Local(initial)`: the
  widget owns it. `Model.Bound(ref)`: the parent owns a `SignalRef`; the
  widget writes it, then calls `onChange`, as a native `.value(ref)` does.
  `Model.Controlled(signal)`: the parent owns a signal; `onChange` asks, and
  the parent accepts (it writes the source) or rejects. A list owns its
  `Selection` of tui4s-use, or takes the parent's (`ListView.of`).
- A widget that only builds a UI is a `UI`. A widget that allocates state is
  `UI < Sync`, and one that starts a fiber is `UI < (Sync & Scope)`. Build a
  widget once, where its state lives (the start of the app, the body of a
  component), not in a `render` closure.
- The Scaladoc of each widget that takes the focus has its keys. A list keeps
  the focus and names its selected row with `aria-activedescendant`, so the
  runner scrolls that row into view. A layer (a modal, a menu, the palette)
  is a kyo-ui `position` layer with `focusTrap`, `focusAuto` and
  `focusRestore`; Esc closes it, and its keys do not reach the app.
- The colours are custom properties: `--tui4s-accent`, `--tui4s-muted`,
  `--tui4s-border`, `--tui4s-surface`, `--tui4s-selection`,
  `--tui4s-success`, `--tui4s-warning` and `--tui4s-danger`.
  `Colors.defaults` has a value for each one: give it to the run
  (`override def stylesheet = Colors.defaults` in a `TuiApp`), or give your
  own `Stylesheet.vars` with these names. With no value, a widget keeps its
  marks (`›`, `[x]`, bold text) and shows no colour.

The gallery example (`examples/target/bin/gallery`) shows every widget with
live state: the family list at the left (the arrows, or F1 to F5), the
widgets of the family at the right (Tab goes from widget to widget), and the
hints of its keymap at the bottom (Ctrl-P opens the command palette over that
keymap; Ctrl-Q quits). Each family is one function of the gallery.

### Containers

`Panel` (a border style, a title on the border), `SplitPane` (the arrows on its divider resize it), `Accordion` (`Mode.One` or `Mode.Many`) and `Tabs`:

<!-- from: examples/src/main/scala/examples/gallery/Gallery.scala#containers -->
```scala
    /** The containers: a panel, a split pane, an accordion and tabs. */
    def containers(using Frame): UI < Sync =
      for
          split <- SplitPane(
                     Panel(title = "Left")(span("files")),
                     Panel(title = "Right")(span("preview")),
                   )(size = Model.Local(40))
          acc   <- Accordion(
                     Accordion.Section("General", span("Name and email.")),
                     Accordion.Section("Advanced", span("Cache and logs.")),
                   )()
          tabs  <- Tabs("One", "Two", "Three")(
                     panel = i => span(s"The panel of tab ${i + 1}."),
                   ).ui
      yield div.style(_.gap(1.px))(
        Panel(title = "Panel")(span("A border style and a title on it.")),
        labeled("SplitPane (Tab to the divider, then ←/→)", split),
        labeled("Accordion", acc),
        labeled("Tabs", tabs),
      )
```

### Data

The charts (`Sparkline`, `Gauge`, `ProgressBar`, `BarChart`, `Spinner`), `ListView` (`ListView.virtual` builds only the rows of the window of a long list), `DataTable` (the keys 1 to 9 sort), `TreeView` and `Pager` (a search, and a follow mode for a log):

<!-- from: examples/src/main/scala/examples/gallery/Gallery.scala#data -->
```scala
    /** The data widgets: the charts, a list, a table, a tree and a pager. */
    def data(using Frame): UI < (Sync & Scope) =
      for
          fruits  <-
            Signal.initRef(Chunk("apple", "banana", "cherry", "date", "elder"))
          list    <- ListView(fruits, identity[String], height = 3)(span(_))
          table   <- DataTable(
                       Signal.initConst(langs),
                       _.name,
                       DataTable.Column("Name", _.name, DataTable.Width.Fixed(8)),
                       DataTable.Column(
                         "Year",
                         _.year.toString,
                         order = Present(Ordering.by(_.year)),
                       ),
                       DataTable.Column("Kind", _.kind, DataTable.Width.Fill),
                     )(height = 3)
          tree    <- TreeView(
                       Signal.initConst(
                         Chunk(
                           TreeView.Node(
                             "src",
                             Chunk(
                               TreeView.Node("Main.scala"),
                               TreeView.Node("Ui.scala"),
                             ),
                           ),
                           TreeView.Node("README.md"),
                         ),
                       ),
                       identity[String],
                       height = 4,
                     )(span(_))
          log     <- Pager(
                       Signal.initConst(
                         Chunk.from((1 to 40).map(i => s"event $i: served")),
                       ),
                       height = 3,
                     )
          spinner <- Spinner(label = "working")
      yield div.style(_.gap(1.px))(
        div.style(_.row.gap(2.px))(
          Sparkline(Signal.initConst(Chunk(0.1, 0.4, 0.9, 0.6, 0.3))),
          Gauge(Signal.initConst(0.42), width = 10),
          ProgressBar(Signal.initConst(3), Signal.initConst(10), width = 10),
          spinner,
        ),
        BarChart(
          Signal.initConst(Chunk("Mon" -> 3.0, "Tue" -> 7.5, "Wed" -> 5.0)),
          width = 20,
        ),
        labeled("ListView (↑/↓, PgUp/PgDn, Home/End)", list),
        labeled("DataTable (1 to 3 sort)", table),
        labeled("TreeView (→ opens, ← closes)", tree),
        labeled("Pager (f follows, n/N next match)", log),
      )
```

### Input

`SearchBox` (its `query` is debounced), `Autocomplete` (a dropdown layer; `Match.Prefix`, `Contains` or `Fuzzy`), `Checklist`, `RadioGroup`, `NumberStepper` and `DateField`:

<!-- from: examples/src/main/scala/examples/gallery/Gallery.scala#input -->
```scala
    /** The input widgets: a search box, an autocomplete, a checklist, a radio
      * group, a number stepper and a date field.
      */
    def input(using Frame): UI < (Sync & Scope) =
      for
          search <- SearchBox()
          auto   <- Autocomplete(
                      Signal.initConst(langs.map(_.name)),
                      matcher = Autocomplete.Match.Fuzzy,
                      placeholder = "a language",
                    )
          tasks  <- Checklist(
                      Signal.initConst(
                        Chunk("write the widgets", "write the tests"),
                      ),
                      identity[String],
                      height = 2,
                    )(span(_))
          size   <- RadioGroup("Small", "Medium", "Large")()
          count  <- NumberStepper(value = Model.Local(3), min = 0, max = 10)
          date   <- DateField()
      yield div.style(_.gap(1.px))(
        labeled(
          "SearchBox",
          div(search.view, span(search.query.map(q => s"query: $q"))),
        ),
        labeled("Autocomplete (↓, Enter)", auto),
        labeled("Checklist (Space)", tasks),
        labeled("RadioGroup (↑/↓)", size),
        labeled("NumberStepper (↑/↓, PgUp/PgDn)", count),
        labeled("DateField", date),
      )
```

### Feedback

`StatusBar`, `KeyHints` (the hints of a `Keymap`), `CommandPalette` (a search over the bindings of a `Keymap`), `Modal.confirm` and `Modal.prompt` (over `useDialog`; `ask` never waits) and `Toasts`. The app builds the layers once, and its root holds their views:

<!-- from: examples/src/main/scala/examples/gallery/Gallery.scala#feedback -->
```scala
    /** The feedback widgets: a status bar, key hints, the command palette, a
      * confirm, a prompt and toasts.
      */
    def feedback(layers: Layers)(using Frame): UI < Sync =
      for
          answer <- Signal.initRef("no answer yet")
          name   <- Signal.initRef("notes.txt")
      yield div.style(_.gap(1.px))(
        labeled(
          "StatusBar",
          StatusBar(
            Signal.initConst("gallery.scala  120 lines"),
            right = span("UTF-8"),
          ),
        ),
        labeled(
          "KeyHints",
          KeyHints.of(Chunk("↑/k" -> "up", "↓/j" -> "down", "Enter" -> "open")),
        ),
        labeled(
          "CommandPalette",
          div.style(_.row)(
            button("Open the palette (Ctrl-P)").onClick(layers.palette.open),
          ),
        ),
        labeled(
          "Modal.confirm and Modal.prompt",
          div.style(_.row.gap(2.px))(
            button("Delete").onClick(
              layers.confirm.ask("Delete the file?")(yes =>
                answer.set(if yes then "deleted" else "kept"),
              ),
            ),
            span(answer),
            button("Rename").onClick(
              layers.prompt.ask("New name?")(n => n.fold(Kyo.unit)(name.set)),
            ),
            span(name.map(n => s"name: $n")),
          ),
        ),
        labeled(
          "Toasts",
          div.style(_.row)(
            button("Show a toast").onClick(
              layers.toasts.show("Saved", Toasts.Level.Success),
            ),
          ),
        ),
      )
```

### Navigation

`Menu.bar` (a dropdown of a `Keymap` for each menu), `Tooltip` (on focus), `Breadcrumbs`, `Pagination`, `Tags` and `Wizard`:

<!-- from: examples/src/main/scala/examples/gallery/Gallery.scala#navigation -->
```scala
    /** The navigation widgets: a menu bar, a tooltip, breadcrumbs, pagination,
      * tags and a wizard.
      */
    def navigation(using Frame): UI < Sync =
      for
          done  <- Signal.initRef("nothing chosen")
          file   = Keymap.of(
                     Binding.of("New", Chord.ctrl('n'))(done.set("File › New")),
                     Binding.of("Open", Chord.ctrl('o'))(done.set("File › Open")),
                   )
          help   =
            Keymap.of(
              Binding.of("About", Chord(Keyboard.F12))(done.set("Help › About")),
            )
          menu  <- Menu.bar(Menu.Group("File", file), Menu.Group("Help", help))
          tip   <- Tooltip("Saves the file")(button("Save"))
          path  <- Signal.initRef(Chunk("home", "docs", "widgets"))
          crumb <-
            Breadcrumbs(path, onPick = i => path.updateAndGet(_.take(i + 1)))
          page  <- Signal.initRef(0)
          pages <- Pagination(Signal.initConst(12), page = Model.Bound(page))
          tags  <- Tags(tags = Model.Local(Chunk("scala", "kyo")))
          steps <- Wizard(
                     Wizard.Step("Account", span("Pick a user name.")),
                     Wizard.Step("Profile", span("Add a photo.")),
                     Wizard.Step("Done", span("All set.")),
                   )(onFinish = done.set("wizard finished"))
      yield div.style(_.gap(1.px))(
        labeled("Menu (↓ opens, Enter runs)", div(menu, span(done))),
        labeled("Tooltip (on focus)", tip),
        labeled("Breadcrumbs", crumb),
        labeled(
          "Pagination (←/→)",
          div(pages, span(page.map(p => s"page ${p + 1} of 12"))),
        ),
        labeled("Tags (Enter adds, Delete removes)", tags),
        labeled("Wizard", steps),
      )
```

## A real app: the assistant

<!-- claude 2026-10-19: created this section (L3): the code blocks are regions of the assistant module, of its main and of its suite (ReadmeSuite checks them) -->
The assistant is the flagship example: a chat with a language model
(Anthropic, OpenAI, or a scripted fake provider) in the terminal. It is
also the example of the shape of a real app. Its code is in two places:

- `modules/assistant` (sbt `assistantJVM` and `assistantJS`): the domain,
  the services, the store and the UI. The shared part is plain kyo-ui with
  tui4s-use and tui4s-widgets. It does not use tui4s-core, so a web target
  can use it. The `jvm` part has the interpreters of the JVM.
- `examples/src/main/scala/examples/assistant/Assistant.scala`: the main.
  It only wires.

Start it with `examples/target/bin/assistant` (a real provider when a key
is set) or with `examples/target/bin/assistant --fake` (no network, no
key). [examples/README.md](examples/README.md) tells how to give a key.

### The domain is pure

The types are values: `Conversation`, `Message` (with its status
`Streaming`, `Done` or `Failed`), `ChatEvent`, `ChatError` and `Settings`.
The fold of a streamed reply is a pure state machine with laws: the deltas
give the text, a stop ends the message, and an error marks it failed and
keeps its text. `DomainPropertyTest` checks the laws, the title rule and the
text form of a conversation, on the JVM and on Node.js.

<!-- from: modules/assistant/shared/src/main/scala/examples/assistant/Transcript.scala#transcript -->
```scala
    /** The message after `event`. */
    def apply(message: Message, event: ChatEvent): Message =
      message.status match
          case MessageStatus.Streaming =>
            event match
                case ChatEvent.TextDelta(t) =>
                  message.copy(content = message.content + t)
                case ChatEvent.Stop(_, u)   =>
                  message.copy(status = MessageStatus.Done, usage = u)
                case ChatEvent.Error(e)     =>
                  message.copy(status = MessageStatus.Failed(e))
          case _                       => message
```

### The services are traits, and the interpreters are in `make`

A service is a trait: a capability. Its errors are values
(`Abort[ChatError]`):

<!-- from: modules/assistant/shared/src/main/scala/examples/assistant/Services.scala#provider -->
```scala
/** A chat provider: the capability to stream the reply to a request. The stream
  * gives the pieces of the text, then one [[ChatEvent.Stop]] or one
  * [[ChatEvent.Error]]; or it fails with a [[ChatError]] (an HTTP status, no
  * connection). An interrupt of the fiber that runs it stops it, and its
  * connection closes with it.
  *
  * The interpreters: [[FakeProvider]] (shared, scripted), and on the JVM the
  * Anthropic and OpenAI providers ([[SseProvider]] over the HTTP client of
  * kyo-http).
  */
// claude 2026-10-19: created (L3)
trait ChatProvider:
    /** The events of the reply to `request`. */
    def stream(request: ChatRequest)(using
        Frame,
    ): Stream[ChatEvent, Async & Abort[ChatError]]
```

An interpreter is in the `make` of the companion. The log keeps its lines
as a signal (the log pane shows them) and gives each line to the Kyo log of
the run:

<!-- from: modules/assistant/shared/src/main/scala/examples/assistant/Services.scala#applog -->
```scala
    /** A log that keeps the last `limit` lines; each line starts with its time
      * (UTC, `HH:MM:SS`) and its level.
      */
    def make(limit: Int = 500)(using Frame): AppLog < Sync =
      Signal
        .initRef(Chunk.empty[String])
        .map(ref =>
          new AppLog:
              val lines: Signal[Chunk[String]] = ref

              private def add(level: String, message: String): Unit < Sync =
                Clock.now.map(now =>
                  ref
                    .updateAndGet(ls =>
                      (ls :+ s"${now.show.slice(11, 19)} $level $message")
                        .takeRight(limit.max(1)),
                    )
                    .unit,
                )

              def info(message: String)(using Frame): Unit < Sync =
                add("info", message).andThen(Log.info(message))

              def warn(message: String)(using Frame): Unit < Sync =
                add("warn", message).andThen(Log.warn(message)),
        )
```

Each platform has its interpreters. On the JVM: `SseProvider` with the
wire of Anthropic or of OpenAI over the HTTP client of kyo-http
(`KyoHttp`), the keys (`SecretsJvm`), and the files (`FileStorage` of
tui4s-use). The tests have interpreters in memory: `FakeProvider`,
`Secrets.inMemory` and `Storage.inMemory`.

### One store

`Assistant` is the one store of the app. It groups the state, the derived
values and the actions. A reply runs on a fiber of its own. The run state
names the fiber before the fiber can end:

<!-- from: modules/assistant/shared/src/main/scala/examples/assistant/Assistant.scala#start -->
```scala
    /** Starts the fiber of the reply to `request`. The fiber waits for a gate,
      * so the run state names it before it can end.
      */
    private def start(
        id: ConversationId,
        request: ChatRequest,
        s: Settings,
    ): Unit < Sync =
      providers.get(s.provider) match
          case Absent            =>
            fold(id, ChatEvent.Error(ChatError.Auth))
              .andThen(save(id))
              .andThen(log.warn(s"${s.provider.label} has no key"))
          case Present(provider) =>
            for
                gate  <- Latch.init(1)
                gen   <- generation.incrementAndGet
                fiber <- Fiber.initUnscoped(
                           gate.await.andThen(reply(id, gen, request, provider)),
                         )
                _     <- run.set(Run.Busy(id, gen, fiber))
                _     <- streamingRef.set(Streaming.Running(id))
                _     <- log.info(s"${s.provider.label}: a request to ${s.model}")
                _     <- gate.release
            yield ()
```

A cancel interrupts that fiber. The stream and its HTTP connection stop
with it (one resource, one scope):

<!-- from: modules/assistant/shared/src/main/scala/examples/assistant/Assistant.scala#cancel -->
```scala
    /** Stops the reply that streams: its fiber ends, the message is marked
      * stopped (its text stays), and the conversation is saved.
      */
    def cancel: Unit < Async =
      run.getAndSet(Run.Idle).map {
        case Run.Idle               => Kyo.unit
        case Run.Busy(id, _, fiber) =>
          for
              _ <- fiber.interrupt
              _ <- fold(id, ChatEvent.Error(ChatError.Cancelled))
              _ <- streamingRef.set(Streaming.Idle)
              _ <- save(id)
              _ <- log.info("the reply was stopped")
          yield ()
      }
```

### The wiring is Kyo layers

The shared part gives the layers that build on the services of a platform:

<!-- from: modules/assistant/shared/src/main/scala/examples/assistant/Assistant.scala#wiring -->
```scala
    /** The providers: with [[StartMode.Detect]], each provider whose key
      * `Secrets` finds (`live` makes it); always the fake `fake`.
      */
    def providers(
        live: (ProviderChoice, ApiKey) => ChatProvider,
        fake: ChatProvider,
        mode: StartMode,
    )(using Frame): Layer[Providers, Env[Secrets & AppLog] & Sync] =
      Layer.from[Secrets, AppLog, Providers, Sync]((s, log) =>
        mode match
            case StartMode.Detect   => Providers.make(s, live, fake, log)
            case StartMode.FakeOnly =>
              Providers.fakeOnly(fake): Providers < Sync,
      )
```

The main gives the layers of the JVM and runs the UI:

<!-- from: examples/src/main/scala/examples/assistant/Assistant.scala#main -->
```scala
    def ui: UI < (Async & Scope) =
        val mode =
          if args.contains("--fake") then StartMode.FakeOnly
          else StartMode.Detect
        Tui.size.map(size =>
          Memo.run(
            Env.runLayer(
              AssistantJvm.storage,
              AssistantJvm.secrets,
              Wiring.conversations,
              Wiring.log,
              Wiring.providers(AssistantJvm.live, FakeProvider.make(), mode),
            )(
              AssistantUi.run(
                mode,
                Tui.quit,
                size.map(t => ScreenSize(t.width, t.height)),
              ),
            ),
          ),
        )
    end ui
```

### A test wires the interpreters in memory

The headless suite wires the same app with the storage and the keys in
memory and the fake provider:

<!-- from: examples/src/test/scala/examples/assistant/AssistantSuite.scala#test -->
```scala
    /** The app on a `width` x `height` screen: the storage and the keys in
      * memory, the scripted provider `fake` (also as the real providers).
      */
    private def start(
        fake: ChatProvider = FakeProvider.make(delay = 5.millis),
        keys: Map[ProviderChoice, String] = Map.empty,
        width: Int = 100,
        height: Int = 30,
    )(using Frame): Headless < (Async & Scope) =
      Storage.inMemory.map { storage =>
        val (files, secrets) = Wiring.inMemory(storage, keys)
        Headless.start(width, height, stylesheet = Colors.defaults)(quit =>
          Tui.size.map(size =>
            Memo.run(
              Env.runLayer(
                files,
                secrets,
                Wiring.conversations,
                Wiring.log,
                Wiring.providers((_, _) => fake, fake, StartMode.Detect),
              )(
                AssistantUi.run(
                  StartMode.Detect,
                  quit,
                  size.map(t => ScreenSize(t.width, t.height)),
                ),
              ),
            ),
          ),
        )
      }
```

## Modules

Each module is released on its own, with its own version.

| Artifact                         | For                                    |
| -------------------------------- | -------------------------------------- |
| `io.github.yuri4n::tui4s-core`   | Kyo users: the tui4s experience in one dependency. It gives tui4s-runner, tui4s-app and tui4s-testkit. |
| `io.github.yuri4n::tui4s-runner` | The terminal runner for kyo-ui: `Tui.run`, the components (`Hooks`, `Component`), the screen types. The base of the others. |
| `io.github.yuri4n::tui4s-app`    | `TuiApp`, the main class of an app, on tui4s-runner. |
| `io.github.yuri4n::tui4s-testkit` | `tui4s.testkit.Headless`, the headless test kit, on tui4s-runner. |
| `io.github.yuri4n::tui4s-zio`    | ZIO users (planned).                   |
| `io.github.yuri4n::tui4s-cats`   | Cats Effect users (planned).           |
| `io.github.yuri4n::tui4s-use`    | Composables over Kyo signals, for the JVM and Scala.js. It does not need tui4s-core. |
| `io.github.yuri4n::tui4s-widgets` | Widgets for the JVM and Scala.js, on tui4s-use. It does not need tui4s-core. |
<!-- claude 2026-10-19: created the two rows above (L2a); changed: the widgets row (L2b) -->
<!-- claude 2026-10-19: changed: the rows of the split of the core: runner, app, testkit (D25) -->

An app uses tui4s-core:

```scala
libraryDependencies += "io.github.yuri4n" %% "tui4s-core" % "<version>"
```

The terminal runner for kyo-ui alone (for example, a library, or a main of
its own with no `TuiApp`) is tui4s-runner. Add tui4s-testkit in the `Test`
scope for the headless test kit:

```scala
libraryDependencies += "io.github.yuri4n" %% "tui4s-runner"  % "<version>"
libraryDependencies += "io.github.yuri4n" %% "tui4s-testkit" % "<version>" % Test
```

The packages are the same in all the artifacts (`tui4s`, `tui4s.spi`,
`tui4s.testkit`), so the imports do not change.
<!-- claude 2026-10-19: created these lines (D25) -->

## Requirements

- JDK 25 (Kyo 1.0.0-RC7 needs it).
- Scala 3.9.0 or later.

## Contributing

Read [AGENTS.md](AGENTS.md). It has the commands, the code rules, and the
release process for humans and for AI agents.

Run `git submodule update --init --recursive` after cloning to fetch the
optional [pini4s](https://github.com/yuri4n/pini4s) example dependency under
`vendor/pini4s`. The core and the reusable widgets use Kyo directly.
The [API experiments](experiments/ui-state/README.md) record the alternatives
and the upstream API audit behind that choice.

## License

[Apache-2.0](LICENSE)
