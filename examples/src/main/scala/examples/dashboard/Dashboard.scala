package examples.dashboard

// claude 2026-10-19: changed: the gauge and the sparkline of tui4s-widgets
// (L2b); tui4s.Tui (the prototype had tui4s.kyoui.Tui)
import examples.widgets.Tabs
import kyo.*
import kyo.UI.*
import tui4s.use.{Binding, Chord, Keymap, timeAgo, useNow}
import tui4s.widgets.{Colors, Gauge, Sparkline}
import tui4s.{TerminalSize, Tui, TuiApp}

/** The Dashboard in plain kyo-ui: one tab per series (CPU, Memory, Network), a
  * gauge and a sparkline fed by a sampler (every 250 ms), a log pane fed by a
  * stream (a line every 400 ms). ArrowLeft and ArrowRight change the tab; the
  * tabs are buttons in one focus group, so Tab moves to the next tab and Enter
  * or Space selects it; `p` pauses the sampler (the log goes on) and shows a
  * toast; `?` opens the help; `q` quits. The log pane is a viewport that
  * scrolls, with the newest line first: Tab after the last tab reaches it, and
  * the arrows, PageUp, PageDown, Home and End scroll it. In a terminal of 120
  * columns or more, the tabs and the log are side by side. The gauge and the
  * sparkline are in tui4s-widgets; the tabs are the adapter
  * `examples.widgets.Tabs` over `tui4s.widgets.Tabs`. The keys `p`, `?` and `q`
  * are a keymap of tui4s-use (the help box lists them), and the status line
  * says how long ago the last sample came (`useNow`, `timeAgo`).
  */
// claude 2026-10-19: changed: the Scaladoc names the log pane that scrolls
// (L1a), the help, the toast and the two columns (L1b), the keymap and the
// stamp (L2a), the widgets of tui4s-widgets and the tabs adapter (L2b)
object Dashboard:

    /** The series: one tab for each. The tabs need a first label. */
    // claude 2026-10-19: changed: the first series and the others, for the
    // tabs (P1b, A5 g)
    val firstSeries: String      = "CPU"
    val otherSeries: Seq[String] = Seq("Memory", "Network")
    val series: Seq[String]      = firstSeries +: otherSeries

    /** One sample of every series, and the last 40 samples of each. One ref, so
      * one tick is one atomic change.
      */
    final case class Metrics(now: Chunk[Double], history: Chunk[Chunk[Double]])
        derives CanEqual

    object Metrics:
        val empty: Metrics = Metrics(
          Chunk.fill(series.size)(0.0),
          Chunk.fill(series.size)(Chunk.empty),
        )

    /** A toast: a short message that goes away at `until`. */
    // claude 2026-10-19: created (L1b)
    final case class Toast(text: String, until: Instant) derives CanEqual

    // claude 2026-10-19: changed: the help, the toast and the time of a toast
    // (L1b); the time of the last sample (L2a)
    final case class State(
        metrics: SignalRef[Metrics],
        paused: SignalRef[Boolean],
        logs: SignalRef[Chunk[(Int, String)]],
        help: SignalRef[Boolean],
        toast: SignalRef[Option[Toast]],
        toastFor: Duration,
        updated: SignalRef[Instant],
    )

    // claude 2026-10-19: changed: the help and the toast (L1b)
    def init(using Frame): State < Sync = init(2.seconds)

    /** The state, with a toast that stays `toastFor`. */
    // claude 2026-10-19: created (L1b); changed: the time of the last sample
    // (L2a)
    def init(toastFor: Duration)(using Frame): State < Sync =
      for
          metrics <- Signal.initRef(Metrics.empty)
          paused  <- Signal.initRef(false)
          logs    <- Signal.initRef(Chunk.empty[(Int, String)])
          help    <- Signal.initRef(false)
          toast   <- Signal.initRef(Option.empty[Toast])
          updated <- Clock.now.map(Signal.initRef(_))
      yield State(metrics, paused, logs, help, toast, toastFor, updated)

    /** One sampler tick with given values (a test or a benchmark can call it
      * directly); it also keeps the time of the sample.
      */
    // claude 2026-10-19: changed: the time of the sample; there is no bench
    // yet (T2, review R1-6, L2a)
    def tick(s: State, values: Chunk[Double])(using Frame): Unit < Sync =
      s.metrics
        .updateAndGet(m =>
          Metrics(
            values,
            m.history.zip(values).map((h, v) => (h :+ v).takeRight(40)),
          ),
        )
        .andThen(Clock.now.map(s.updated.set))

    /** The sampler: a random walk every `every`, skipped while paused. */
    // claude 2026-10-13: changed (T2): the period is a parameter
    def sampler(s: State, every: Duration = 250.millis)(using
        Frame,
    ): Unit < Async =
      Loop.forever {
        Async.sleep(every).andThen(s.paused.get).map { p =>
          if p then ()
          else
              s.metrics.get.map { m =>
                Kyo
                  .foreach(m.now)(v =>
                    Random.nextDouble
                      .map(r => (v + (r - 0.5) * 0.3).max(0).min(1)),
                  )
                  .map(vs => tick(s, vs))
              }
        }
      }

    /** A log stream: one line every `every`. */
    // claude 2026-10-13: changed (T2): the period is a parameter
    def logStream(every: Duration = 400.millis)(using
        Frame,
    ): Stream[String, Async] =
      Stream[String, Async] {
        Loop(1) { i =>
          Async
            .sleep(every)
            .andThen(
              Emit.valueWith(Chunk(s"event #$i: request served"))(
                Loop.continue(i + 1),
              ),
            )
        }
      }

    // claude 2026-10-19: changed: Gauge and Sparkline of tui4s-widgets (L2b)
    def overview(s: State, t: Int): UI =
      div.style(_.border(1.px, Style.Color.slate).padding(0.px, 1.px))(
        h2(s"${series(t)} usage"),
        Gauge(s.metrics.map(_.now(t)), 30, Style.Color.green),
        Sparkline(s.metrics.map(_.history(t)), color = Style.Color.blue),
      )

    /** The lines that the log keeps. */
    // claude 2026-10-19: created (L1a)
    val logLines: Int = 50

    /** The log pane: its title, and its lines in a viewport 9 rows high (7
      * lines inside the border) that scrolls (`overflowY: scroll`), the newest
      * line first. The viewport is focusable, and it shows a scroll indicator
      * when the lines do not fit; a focused viewport has a heavy border.
      */
    // claude 2026-10-19: changed: a viewport that scrolls (L1a)
    def logPane(s: State): UI =
      div(
        h3("Log"),
        div
          .id("log")
          .style(
            _.border(1.px, Style.Color.slate)
              .padding(0.px, 1.px)
              .height(9.px)
              .overflowY(_.scroll),
          )(
            ul(
              s.logs
                .map(_.reverse)
                .foreachKeyed(_._1.toString)((_, line) => li(line)),
            ),
          ),
      )

    /** `p`: pauses or resumes the sampler, and says so in a toast. */
    // claude 2026-10-19: created (L2a): the action of the key, for the keymap
    def pause(s: State)(using Frame): Unit < Sync =
      s.paused
        .updateAndGet(!_)
        .map(p => notify(s, if p then "sampler paused" else "sampler resumed"))

    // claude 2026-10-19: created the region below (L2a): the README section
    // "Composables (tui4s-use)" shows it
    // region: composables
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
    // endregion: composables

    // claude 2026-10-19: changed: the help (`?`), the toast of `p`, and the
    // layout from the size of the terminal (L1b); the keymap and the stamp
    // (L2a)
    def view(s: State)(using Frame): UI < (Sync & Scope) =
        val keymap = keys(s)
        for
            size    <- Tui.size
            updated <- stamp(s)
        yield div.onKeyDown(keymap.handler)(
          body(
            new Tabs(firstSeries, otherSeries*)(
              heading = h1("Dashboard"),
              panel = overview(s, _),
            ).ui,
            logPane(s),
            size,
          ),
          div.style(_.row.gap(2.px))(
            span(
              s.paused.map(p =>
                if p then "sampler PAUSED (p resumes)"
                else "sampler running (p pauses)  ←/→ tabs  ? help  q quits",
              ),
            )
              .style(_.color(Style.Color.gray)),
            updated,
          ),
          toast(s),
          help(s, keymap),
        )
        end for
    end view

    // claude 2026-10-19: created the region below (L1b), with `when` (L1b
    // review): the README section "Overlays, terminal size and theme colors"
    // shows it
    // region: overlays
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
    // endregion: overlays

    /** Shows `text` in the toast for the time of the state. */
    // claude 2026-10-19: created (L1b)
    def notify(s: State, text: String)(using Frame): Unit < Sync =
      Clock.now.map(now => s.toast.set(Some(Toast(text, now + s.toastFor))))

    /** Takes the toast away when its time is over (it looks every `every`). */
    // claude 2026-10-19: created (L1b)
    def toastClock(s: State, every: Duration = 100.millis)(using
        Frame,
    ): Unit < Async =
      Loop.forever {
        Async
          .sleep(every)
          .andThen(Clock.now)
          .map(now =>
            s.toast.updateAndGet {
              case Some(t) if now >= t.until => None
              case other                     => other
            }.unit,
          )
      }

    /** The app: the state, the sampler and the log, every `sampleEvery` and
      * `logEvery`, and a toast that stays `toastFor` (a test passes short
      * times). The three fibers (the sampler, the log, the clock of the toast)
      * end when the `Scope` of the UI closes, at the end of the run.
      */
    // claude 2026-10-19: changed: a def with the two periods (T2), the time
    // of a toast and its clock (L1b)
    def app(
        sampleEvery: Duration = 250.millis,
        logEvery: Duration = 400.millis,
        toastFor: Duration = 2.seconds,
    ): UI < (Async & Scope) =
      for
          s  <- init(toastFor)
          _  <- Fiber.init(sampler(s, sampleEvery))
          _  <- Fiber.init(toastClock(s))
          _  <- Fiber.init(
                  logStream(logEvery).foreach(line =>
                    // claude 2026-10-19: changed: the log keeps `logLines`
                    // lines, so the pane has some to scroll (L1a)
                    s.logs.updateAndGet(l =>
                      (l :+ (l.lastOption.fold(1)(_._1 + 1), line))
                        .takeRight(logLines),
                    ),
                  ),
                )
          ui <- view(s)
      yield ui
end Dashboard

/** Runs the Dashboard in the real terminal. Start it with its start script, not
  * with `sbt run` (sbt also reads the keyboard):
  * {{{
  * sbt examples/launchers
  * examples/target/bin/dashboard
  * }}}
  */
// claude 2026-10-13: created (T2, RC-33)
object DashboardApp extends TuiApp:
    def ui: UI < (Async & Scope) = Dashboard.app()

    /** The colour variables of tui4s-widgets: the gauge, the sparkline and the
      * tabs take their colours from them (without them, the run logs a warning
      * for each variable).
      */
    // claude 2026-10-19: created (L2b review): the PTY row dashboard found
    // the warnings of the colour variables after the restore
    override def stylesheet: Stylesheet = Colors.defaults
end DashboardApp
