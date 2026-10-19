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

<!-- claude 2026-10-19: created this section (L1a): the code block is the text of the region "scroll" of the Todo example (ReadmeSuite checks it) -->
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
      * `aria-activedescendant` names the selected row, so the viewport around
      * the list scrolls to it, and `preventScrollKeys` keeps the arrows from
      * scrolling it too.
      */
    def list(
        rows: Signal[Chunk[Row]],
        onKey: KeyboardEvent => Any < Async,
    ): UI =
      rows
        .map(_.find(_.selected).fold("")(r => s"item-${r.item.id}"))
        .map(selected =>
          ul.id("list")
            .tabIndex(0)
            .preventScrollKeys
            .aria("activedescendant", selected)
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
      * rows and scrolls (`overflowY: auto`; `tabIndex(-1)`: it is not a tab
      * stop, its list and input are).
      */
    def body(list: UI, input: UI): UI =
      div.tabIndex(-1).style(_.overflowY(_.auto).flexGrow(1))(list, input)
```

An element with `overflow`, `overflowX` or `overflowY` other than `visible`
is a viewport. tui4s lays out its content at its natural size on that axis,
clips it to the box, and shows the window at the scroll offset. `hidden`
clips and does not scroll. `scroll` and `auto` also scroll, and such a
viewport is focusable (Tab reaches it; `tabIndex(-1)` takes it out). When
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

Text: `whiteSpace(_.noWrap)` keeps one line, `pre` keeps the spaces and the
line breaks with no wrap, `preWrap` keeps them and wraps, `normal` and
`preLine` collapse the spaces. A text wraps in a column, and in a row box of
a definite `width`. `textOverflow(_.ellipsis)` with `overflow(_.hidden)` puts
"…" at the cut. Every measure counts terminal cells: a wide character (CJK,
an emoji) takes two cells, and a cut never splits it.

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
