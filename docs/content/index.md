---
seo:
  title: tui4s - a terminal runner for kyo-ui
  description: Run a kyo-ui value in the terminal, in Scala 3, and test it with no terminal. A ZIO and a Cats Effect integration are not in this version (issues #18 and #19).
  ogImage: /og-image.png
---

<!-- claude 2026-10-20: created (L5): the landing page leads with the Counter example; changed: ZIO and Cats Effect are issues, not modules (ADR 0014); changed: the social preview /og-image.png in the front matter (ADR 0022) -->

::u-page-hero
#title
kyo-ui in the terminal.

#description
tui4s runs a [kyo-ui](https://github.com/getkyo/kyo) value in the terminal, in Scala 3. kyo-ui describes the screen. tui4s lays it out in cells, follows its signals, sends the keys to it and paints it with [jatatui](https://github.com/oyvindberg/jatatui).

#links
  :::u-button
  ---
  color: neutral
  size: xl
  to: /getting-started/installation
  trailing-icon: i-lucide-arrow-right
  ---
  Get started
  :::

  :::u-button
  ---
  color: neutral
  icon: i-simple-icons-github
  size: xl
  to: https://github.com/yuri4n/tui4s
  variant: outline
  ---
  Source on GitHub
  :::
::

::u-page-section
---
title: The smallest app
description: This is the complete Counter example. The UI is plain kyo-ui, and `TuiApp` is its main class.
---
<!-- from: examples/src/main/scala/examples/counter/Counter.scala#quickstart -->
```scala [Counter.scala]
import kyo.*
import kyo.UI.*
import tui4s.{Tui, TuiApp, initSig}

/** The Counter: a kyo-ui UI with one piece of state (a `SignalRef`), key
  * handlers and two buttons. `+` and `-` change the count, Tab moves between
  * the buttons, Enter and Space press them, `q` quits (and so does Ctrl-C).
  */
object Counter:

    /** The Counter UI; `quit` ends the run. */
    def ui(quit: Unit < Sync): UI < Sync =
      initSig(0).map(count => view(count, quit))

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

:::cast{src="/casts/counter.cast" max-width="36rem"}
:::
::

::u-page-section
#title
What tui4s gives

#features
  :::u-page-feature
  ---
  icon: i-lucide-layout-template
  to: /guide/components
  ---
  #title
  kyo-ui is the DSL

  #description
  tui4s has no UI DSL of its own. A kyo-ui value describes the screen, and its signals drive the frames.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-flask-conical
  to: /guide/testing
  ---
  #title
  Tests with no terminal

  #description
  `Headless` runs the app, sends keys and reads the screen in a plain unit test.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-blocks
  to: /guide/widgets
  ---
  #title
  Composables and widgets

  #description
  `tui4s-use` and `tui4s-widgets` are plain kyo-ui code for the JVM and Scala.js.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-globe
  to: /guide/web
  ---
  #title
  The same app on the web

  #description
  The assistant example runs in the terminal and in a browser, from one shared code base.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-shield-check
  to: /guide/terminal
  ---
  #title
  The terminal comes back

  #description
  Each way that a run ends restores the terminal: a quit, a failure, a signal or `System.exit`.
  :::

  :::u-page-feature
  ---
  icon: i-lucide-bot
  to: /ai/agents
  ---
  #title
  Ready for AI agents

  #description
  Each page is also Markdown, with `llms.txt` and an MCP server. A test checks each Scala block.
  :::
::
