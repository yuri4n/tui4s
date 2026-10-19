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

The development shell supplies JDK 25, sbt, and the tools for the terminal
checklist. Run the last command in a real terminal; `q` exits. For an existing
checkout, run `git submodule update --init --recursive` before building.
The flake defines shells for Linux on x86_64 or aarch64 and Apple Silicon
macOS. On Intel macOS, or without Nix, install JDK 25 and sbt, then use the
same sbt commands. These shell targets do not imply terminal runtime testing
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

The Dashboard's reusable `examples.widgets.Tabs` owns its selection by default.
Supply `selected = Some(signal)` and `onChange = next => ...` when the parent
owns it. The parent decides whether to accept a request.
`Widgets.tabs(ref, "One", "Two")` connects a writable signal directly. `TabsView` provides the same
widget as a plain Kyo UI construction effect; `Tabs` adds tui4s mount lifetime.

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
      * `position(_.overlay)` layer that centers it). Its `focusTrap` keeps Tab
      * inside it; its Close button takes the focus when it appears
      * (`focusAuto`) and gives it back when it goes (`focusRestore`). Esc does
      * nothing by itself in a focus trap: the `onKeyDown` of the box closes it.
      */
    def help(s: State): UI =
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
              span("p    pause or resume the sampler"),
              span("?    this help (Esc closes it)"),
              span("q    quit"),
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
  * form (also in the textarea: Alt-Enter or Ctrl-J there gives a new line).
  * The result shows under the form. Esc quits.
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

## Modules

Each module is released on its own, with its own version.

| Artifact                         | For                                    |
| -------------------------------- | -------------------------------------- |
| `io.github.yuri4n::tui4s-core`   | Kyo users: run a kyo-ui value in the terminal. The base of the others. |
| `io.github.yuri4n::tui4s-zio`    | ZIO users (planned).                   |
| `io.github.yuri4n::tui4s-cats`   | Cats Effect users (planned).           |

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
