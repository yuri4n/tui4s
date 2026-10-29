package examples.workflow

import kyo.*
import kyo.UI.*
import tui4s.use.{Binding, Chord, Keymap}
import tui4s.widgets.{Colors, Gauge, Sparkline}
import tui4s.{TerminalSize, Tui, TuiApp}

/** One operator session: dashboard gauges beside a scripted assistant.
  *
  * The metrics side shows four service cards. Each card has a gauge and a
  * sparkline. Twenty-four probe fibers tick those series. The transcript side
  * shows token chunks, tool events and a status line. Those three fibers run
  * with the probes. The script is local. The app reads no key and calls no
  * provider.
  *
  * The probe box is a list of signal rows. The list is longer than 16 rows, so
  * the runner samples those rows. The gauges stay on their own cards.
  */
// cursor 2026-10-29: created: the heavy operator session
object Workflow:

    /** Live streams, or streams that wait for Ctrl-G. */
    // cursor 2026-10-29: created: the quiet and live paces
    enum Pace derives CanEqual:
        /** The streams run. */
        case Live

        /** The streams wait. The screen stays still. */
        case Quiet

    /** Reads the flags of the start script. */
    // cursor 2026-10-29: created: the flags of the session
    object Settings:
        /** `args` as a pace. `--quiet` waits. Any other flag is an error. */
        // cursor 2026-10-29: created: reject an unknown flag
        def parse(args: Vector[String]): Either[String, Pace] =
          args.foldLeft[Either[String, Pace]](Right(Pace.Live)) { (out, arg) =>
            out.flatMap(_ =>
              if arg == "--quiet" then Right(Pace.Quiet)
              else Left(s"unknown workflow option: $arg"),
            )
          }
    end Settings

    /** The periods of the four kinds of stream. */
    // cursor 2026-10-29: created: one period for each kind of stream
    final case class Periods(
        metric: Duration,
        token: Duration,
        tool: Duration,
        status: Duration,
    )

    /** One line of the transcript. */
    // cursor 2026-10-29: created: a transcript line
    final case class Line(id: Int, text: String) derives CanEqual

    /** One probe: its latest sample and a short history. */
    // cursor 2026-10-29: created: one probe signal pair
    final case class Probe(
        name: String,
        now: SignalRef[Double],
        history: SignalRef[Chunk[Double]],
    )

    /** The signals of one session. */
    // cursor 2026-10-29: created: the session signals
    final case class State(
        probes: Chunk[Probe],
        lines: SignalRef[Chunk[Line]],
        draft: SignalRef[String],
        tool: SignalRef[String],
        status: SignalRef[String],
        pace: SignalRef[Pace],
    )

    /** The local script. It needs no key and no network. */
    // cursor 2026-10-29: created: the scripted reply, tools and statuses
    object Script:
        /** The first sentence of the reply. It fits on one transcript row. */
        val opening: String = "Queue depth is high."

        /** The token chunks, in order. The stream repeats them. */
        val pieces: Chunk[String] = Chunk(
          s"$opening ",
          "I read the sampler. ",
          "Errors stay in limit. ",
          "This is a local script. ",
        )

        /** The tool events, in order. */
        val tools: Chunk[String] = Chunk(
          "lookup metrics",
          "read queue",
          "sample errors",
          "check latency",
        )

        /** The status events, in order. */
        val statuses: Chunk[String] = Chunk("streaming", "tool", "ready")

        /** The one-line reply to a line that the operator sent. */
        // cursor 2026-10-29: created: the reply to one sent line
        def reply(text: String): String = s"Noted: $text."
    end Script

    /** The names of the probes. The first four are the service cards. */
    // cursor 2026-10-29: created: twenty-four probe names
    val probeNames: Chunk[String] = Chunk(
      "ingest",
      "memory",
      "errors",
      "latency",
      "queue",
      "cpu",
      "disk",
      "net",
      "probe-09",
      "probe-10",
      "probe-11",
      "probe-12",
      "probe-13",
      "probe-14",
      "probe-15",
      "probe-16",
      "probe-17",
      "probe-18",
      "probe-19",
      "probe-20",
      "probe-21",
      "probe-22",
      "probe-23",
      "probe-24",
    )

    /** How many service cards the metrics side shows. */
    // cursor 2026-10-29: created: four service cards
    val cardCount: Int = 4

    /** Probe fibers, plus the token, tool and status fibers. */
    // cursor 2026-10-29: created: the count of independent streams
    val streamCount: Int = probeNames.size + 3

    /** Samples kept on one sparkline. */
    // cursor 2026-10-29: created: the sparkline length
    val historyLimit: Int = 16

    /** Transcript lines kept on the screen. */
    // cursor 2026-10-29: created: the transcript window
    val lineLimit: Int = 6

    /** Cells in one transcript line before the next piece starts a new line. */
    // cursor 2026-10-29: created: the transcript line width
    val lineWidth: Int = 24

    /** Cells in one gauge. */
    // cursor 2026-10-29: created: the gauge width
    val gaugeWidth: Int = 10

    /** The width from which the two sides sit side by side. */
    // cursor 2026-10-29: created: the split width
    val wideFrom: Int = 80

    // cursor 2026-10-29: created: the shared probe seed
    private val seed: Double = 0.35

    /** The next sample of a random walk in 0 to 1. */
    // cursor 2026-10-29: created: one step of a probe
    def step(current: Double, random: Double): Double =
      (current + (random - 0.5) * 0.3).max(0.0).min(1.0)

    /** `history` plus `value`, cut to [[historyLimit]]. */
    // cursor 2026-10-29: created: the sparkline window
    def remember(history: Chunk[Double], value: Double): Chunk[Double] =
      (history :+ value).takeRight(historyLimit)

    /** The label of one probe row. */
    // cursor 2026-10-29: created: the text of one probe row
    def probeText(name: String, value: Double): String =
        val pct = (value.max(0.0).min(1.0) * 100).round.toInt
        f"$name $pct%02d%%"

    /** `lines` with `piece` on the last line, or on a new line when that line
      * is a user line or has no room. The result keeps [[lineLimit]] lines.
      */
    // cursor 2026-10-29: created: append one token chunk
    def appendPiece(lines: Chunk[Line], piece: String): Chunk[Line] =
        val next = lines.lastOption match
            case Some(last)
                if !last.text.startsWith("You:") &&
                  last.text.length + piece.length <= lineWidth =>
              lines.dropRight(1) :+ last.copy(text = last.text + piece)
            case _ =>
              val id = lines.lastOption.fold(1)(_.id + 1)
              lines :+ Line(id, piece)
        next.takeRight(lineLimit)
    end appendPiece

    /** `lines` plus the operator line `text`. */
    // cursor 2026-10-29: created: append one sent line
    def say(lines: Chunk[Line], text: String): Chunk[Line] =
        val id = lines.lastOption.fold(1)(_.id + 1)
        (lines :+ Line(id, s"You: $text")).takeRight(lineLimit)

    /** Writes one sample into `probe`. */
    // cursor 2026-10-29: created: store one sample
    def tick(probe: Probe, value: Double)(using Frame): Unit < Sync =
      probe.now
        .set(value)
        .andThen(probe.history.updateAndGet(h => remember(h, value)))
        .unit

    /** Writes one burst: every probe, one token, one tool event, one status. */
    // cursor 2026-10-29: created: one burst of every source
    def burst(s: State)(using Frame): Unit < Sync =
      Kyo
        .foreachDiscard(s.probes.zipWithIndex) { pair =>
          val (probe, index) = pair
          tick(probe, ((index % 9) + 1) / 10.0)
        }
        .andThen(s.lines.updateAndGet(ls => appendPiece(ls, "tick ")))
        .andThen(s.tool.set("lookup metrics"))
        .andThen(s.status.set("burst"))
        .unit

    /** The state. Each probe starts at the same seed. */
    // cursor 2026-10-29: created: allocate the session signals
    def init(pace: Pace)(using Frame): State < Sync =
      for
          probes <- Kyo.foreach(probeNames) { name =>
                      for
                          now     <- Signal.initRef(seed)
                          history <- Signal.initRef(Chunk(seed))
                      yield Probe(name, now, history)
                    }
          lines  <- Signal.initRef(Chunk.empty[Line])
          draft  <- Signal.initRef("")
          tool   <- Signal.initRef("idle")
          status <-
            Signal.initRef(if pace == Pace.Live then "streaming" else "idle")
          shown  <- Signal.initRef(pace)
      yield State(probes, lines, draft, tool, status, shown)

    /** Sends the composer text and appends the scripted reply. */
    // cursor 2026-10-29: created: send one line to the local script
    def send(s: State)(using Frame): Unit < Sync =
      for
          raw <- s.draft.get
          _   <- s.draft.set("")
          text = raw.trim
          _   <-
            if text.isEmpty then Kyo.unit
            else
                s.lines
                  .updateAndGet(ls => say(ls, text))
                  .andThen(
                    s.lines
                      .updateAndGet(ls => appendPiece(ls, Script.reply(text))),
                  )
                  .unit
      yield ()

    /** Starts the streams once. A second call does nothing. */
    // cursor 2026-10-29: created: open the stream gate once
    def arm(
        s: State,
        go: Promise[Unit, Any],
        armed: AtomicRef[Boolean],
    )(using Frame): Unit < Sync =
      armed.getAndSet(true).map { was =>
        if was then ()
        else s.pace.set(Pace.Live).andThen(go.completeUnit.unit)
      }

    /** One probe fiber. It waits for `go`, then takes a random walk. */
    // cursor 2026-10-29: created: one probe stream
    private def probeLoop(
        probe: Probe,
        every: Duration,
        first: Duration,
        go: Promise[Unit, Any],
    )(using Frame): Unit < Async =
      go.get.andThen(Async.sleep(first)).andThen {
        Loop.forever {
          for
              v <- probe.now.get
              r <- Random.nextDouble
              _ <- tick(probe, step(v, r))
              _ <- Async.sleep(every)
          yield ()
        }
      }

    /** The token fiber. It waits for `go`, then repeats the script. */
    // cursor 2026-10-29: created: the token stream
    private def tokenLoop(
        s: State,
        every: Duration,
        go: Promise[Unit, Any],
    )(using Frame): Unit < Async =
      go.get.andThen {
        Loop(0) { i =>
          val piece = Script.pieces(i % Script.pieces.size)
          for
              _ <- Async.sleep(every)
              _ <- s.lines.updateAndGet(ls => appendPiece(ls, piece))
          yield Loop.continue(i + 1)
        }
      }

    /** The tool fiber. It waits for `go`, then repeats the tool events. */
    // cursor 2026-10-29: created: the tool stream
    private def toolLoop(
        s: State,
        every: Duration,
        go: Promise[Unit, Any],
    )(using Frame): Unit < Async =
      go.get.andThen {
        Loop(0) { i =>
          val name = Script.tools(i % Script.tools.size)
          for
              _ <- Async.sleep(every)
              _ <- s.tool.set(name)
          yield Loop.continue(i + 1)
        }
      }

    /** The status fiber. It waits for `go`, then repeats the status events. */
    // cursor 2026-10-29: created: the status stream
    private def statusLoop(
        s: State,
        every: Duration,
        go: Promise[Unit, Any],
    )(using Frame): Unit < Async =
      go.get.andThen {
        Loop(0) { i =>
          val name = Script.statuses(i % Script.statuses.size)
          for
              _ <- Async.sleep(every)
              _ <- s.status.set(name)
          yield Loop.continue(i + 1)
        }
      }

    /** The keys. Esc quits. Ctrl-G runs `arm`. Enter is the form, not a key
      * here.
      */
    // cursor 2026-10-29: created: the session keys
    def keys(arm: Unit < Sync)(using Frame): Keymap =
      Keymap.of(
        Binding.of("quit", Chord(Keyboard.Escape))(Tui.quit),
        Binding.of("run the streams", Chord.ctrl('g'))(arm),
      )

    /** The four service cards. Each card keeps its own gauge and sparkline. */
    // cursor 2026-10-29: created: the service cards
    def cards(s: State): UI =
      div(
        s.probes.take(cardCount).map { probe =>
          div(
            div.style(_.row.gap(1.px))(
              span(probe.name).style(_.width(8.px)),
              Gauge(probe.now, gaugeWidth, Style.Color.green),
            ),
            Sparkline(probe.history, color = Style.Color.blue),
          )
        }*,
      )

    /** The probe rows. This parent is only those rows, so a list longer than 16
      * is one crowded parent. The runner samples the rows.
      */
    // cursor 2026-10-29: created: the sampled probe rows
    def probeStrip(s: State): UI =
      div
        .id("probes")
        .style(_.height(3.px).overflow(_.hidden))(
          s.probes
            .map(probe => span(probe.now.map(v => probeText(probe.name, v))))*,
        )

    /** The metrics side: the cards, then the sampled probe rows. */
    // cursor 2026-10-29: created: the metrics column
    def metrics(s: State): UI =
      div(
        h2("Metrics"),
        cards(s),
        h3("Probes"),
        span("These rows update by sampling."),
        probeStrip(s),
      )

    /** The transcript side: the script, the latest tool, the status and the
      * composer.
      */
    // cursor 2026-10-29: created: the transcript column
    def transcript(s: State)(using Frame): UI =
      div(
        h2("Transcript"),
        when(s.lines.map(_.isEmpty))(
          span("The reply streams from a local script."),
        ),
        div
          .id("transcript")
          .style(
            _.border(1.px, Style.Color.slate)
              .padding(0.px, 1.px)
              .height(8.px)
              .overflowY(_.hidden),
          )(
            ul(s.lines.foreachKeyed(_.id.toString)(line => li(line.text))),
          ),
        span(s.tool.map(t => s"tool $t")),
        span(s.status.map(t => s"status $t")),
        div.style(_.row.gap(1.px))(
          span("Ask"),
          form.onSubmit(send(s))(
            input
              .value(s.draft)
              .id("composer")
              .placeholder("Message")
              .focusAuto(true)
              .style(_.width(24.px)),
          ),
        ),
      )

    /** The two sides side by side at [[wideFrom]] columns, else stacked. */
    // cursor 2026-10-29: created: the split from the terminal width
    def columns(metrics: UI, transcript: UI, size: Signal[TerminalSize]): UI =
      div(
        div(metrics).style(_.flexGrow(1).minWidth(0.px).overflow(_.hidden)),
        div(transcript).style(_.flexGrow(1).minWidth(0.px).overflow(_.hidden)),
      )
        .style(
          size.map(t =>
            if t.width >= wideFrom then Style.row.gap(1.px) else Style.column,
          ),
        )

    /** The screen. `arm` runs the streams. */
    // cursor 2026-10-29: created: the session screen
    def view(s: State, arm: Unit < Sync)(using Frame): UI < Sync =
        val keymap = keys(arm)
        for size <- Tui.size
        yield div.onKeyDown(keymap.handler)(
          h1("Operator session"),
          div.style(_.row.gap(2.px))(
            span(
              s.pace.map {
                case Pace.Live  => "live · scripted · no key"
                case Pace.Quiet => "idle · scripted · no key"
              },
            ),
            span(s"$streamCount sources"),
          ),
          columns(metrics(s), transcript(s), size),
          span(
            keymap.help.map((k, h) => s"$k $h").mkString("  ") + "  Enter sends",
          ),
        )
    end view

    /** The session. The fibers start in this scope and wait for the gate. A
      * live pace opens the gate at once. `--quiet` waits for Ctrl-G.
      */
    // cursor 2026-10-29: created: start the streams in the UI scope
    def app(
        pace: Pace = Pace.Live,
        metricEvery: Duration = 160.millis,
        tokenEvery: Duration = 70.millis,
        toolEvery: Duration = 280.millis,
        statusEvery: Duration = 400.millis,
    )(using Frame): UI < (Async & Scope) =
        val periods = Periods(metricEvery, tokenEvery, toolEvery, statusEvery)
        for
            s     <- init(pace)
            go    <- Promise.init[Unit, Any]
            armed <- AtomicRef.init(false)
            start  = arm(s, go, armed)
            _     <- Kyo.foreach(s.probes.zipWithIndex) { pair =>
                       val (probe, index) = pair
                       Fiber.init(
                         probeLoop(
                           probe,
                           periods.metric,
                           Duration.fromNanos((index + 1).toLong * 5000000L),
                           go,
                         ),
                       )
                     }
            _     <- Fiber.init(tokenLoop(s, periods.token, go))
            _     <- Fiber.init(toolLoop(s, periods.tool, go))
            _     <- Fiber.init(statusLoop(s, periods.status, go))
            _     <- if pace == Pace.Live then start else Kyo.unit
            ui    <- view(s, start)
        yield ui
        end for
    end app
end Workflow

/** Runs the session in the real terminal. The default path is the script. It
  * needs no API key. Start it with its start script, not with `sbt run`:
  * {{{
  * sbt examples/launchers
  * examples/target/bin/workflow
  * examples/target/bin/workflow --quiet
  * }}}
  */
// cursor 2026-10-29: created: the workflow main
object WorkflowApp extends TuiApp:
    /** The colours of the gauges and the sparklines. */
    // cursor 2026-10-29: created: the widget colours
    override def stylesheet: Stylesheet = Colors.defaults

    /** The UI of the parsed flags. An unknown flag fails before the screen. */
    // cursor 2026-10-29: created: parse the flags, then show the session
    def ui: UI < (Async & Scope) =
      Workflow.Settings.parse(args.toVector) match
          case Right(pace) => Workflow.app(pace)
          case Left(error) => Abort.panic(IllegalArgumentException(error))
end WorkflowApp
