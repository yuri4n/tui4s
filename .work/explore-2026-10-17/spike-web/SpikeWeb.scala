package examples.spikeweb

import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kyo.*
import kyo.UI.*
import tui4s.Tui
import tui4s.TuiApp
import tui4s.TuiOptions

/** SPIKE (not for main): one kyo-ui UI value over one application state, shown
  * in the terminal (tui4s) and in a browser (kyo-ui server push,
  * `UI.runHandlers` + kyo-http `HttpServer`) in the same JVM at the same time.
  */
final class Store(
    val count: SignalRef[Int],
    val messages: SignalRef[Chunk[String]],
    val draft: SignalRef[String],
):
    def inc(using Frame): Unit < Sync = count.updateAndGet(_ + 1).unit

    def add(using Frame): Unit < Sync =
      draft.get.map { text =>
        if text.trim.isEmpty then ()
        else
            messages.updateAndGet(_ :+ text.trim).andThen(draft.set(""))
      }
end Store

object Store:
    def init(using Frame): Store < Sync =
      for
          count    <- Signal.initRef(0)
          messages <- Signal.initRef(Chunk.empty[String])
          draft    <- Signal.initRef("")
      yield Store(count, messages, draft)
end Store

object SpikeWeb:

    /** The shared view: a pure value over the store. No quit key here, so the
      * browser cannot stop the terminal app.
      */
    def view(store: Store)(using Frame): UI =
      div(
        h1("Spike: one UI, two surfaces"),
        span("Count: ", store.count.map(_.toString)).id("count"),
        form.onSubmit(store.add)(
          div.style(_.row.gap(1.em))(
            span("Message:"),
            input
              .id("draft")
              .placeholder("type, Enter adds")
              .value(store.draft)
              // em: cells in tui4s, font units in a browser
              .style(_.width(30.em)),
          ),
        ),
        button("+1").id("inc").onClick(store.inc),
        span("Messages:"),
        ul.id("messages")(
          store.messages.foreachIndexed((i, m) => li(s"${i + 1}. $m")),
        ),
      )

    /** Appends one line to the spike log (`SPIKE_LOG`), never to stdout. */
    def note(line: String)(using Frame): Unit < Sync =
      Sync.defer {
        sys.env.get("SPIKE_LOG").foreach { file =>
          val start = ManagementFactory.getRuntimeMXBean.nn.getStartTime
          val now   = java.lang.System.currentTimeMillis()
          discard(
            Files.writeString(
              Paths.get(file),
              s"$line jvmStart=$start now=$now sinceJvmStart=${now - start}ms\n",
              StandardCharsets.UTF_8,
              StandardOpenOption.CREATE,
              StandardOpenOption.APPEND,
            ),
          )
        }
      }

    private def discard[A](a: A): Unit = { val _ = a }

    /** The status line after a bind. */
    def bound(
        host: String,
        result: Result[HttpBindException, HttpServer],
    )(using Frame): String < Sync =
      result match
          case Result.Success(server) =>
            note(s"http-ready port=${server.port}")
              .andThen(s"web: http://$host:${server.port}/")
          case other                  =>
            note(s"http-failed $other").andThen(s"web: bind failed: $other")

    /** Mode "fork": binds the HTTP server on a forked fiber and keeps it until
      * the fiber is interrupted (the app scope closes at quit). The status
      * signal shows the result.
      */
    def serve(
        host: String,
        port: Int,
        handlers: Seq[HttpHandler[?, ?, ?]],
        status: SignalRef[String],
    )(using Frame): Unit < Async =
      Scope.run {
        Abort
          .run[HttpBindException](HttpServer.init(port, host)(handlers*))
          .map {
            case Result.Success(server) =>
              note(s"http-ready port=${server.port}")
                .andThen(status.set(s"web: http://$host:${server.port}/"))
                .andThen(server.await)
            case other                  =>
              note(s"http-failed $other")
                .andThen(status.set(s"web: bind failed: $other"))
          }
      }
end SpikeWeb

/** The terminal main. Argument 1: the port (default 18080). Esc quits.
  * `SPIKE_MODE=fork` binds on a forked fiber; the default ("direct") binds in
  * `ui`, under the app scope, before the first frame. `SPIKE_TUI_LOG=<file>`
  * sends the run's log and captured output to that file.
  */
object SpikeWebApp extends TuiApp:
    override def options: TuiOptions =
      TuiOptions(logFile = sys.env.get("SPIKE_TUI_LOG").map(Paths.get(_).nn))

    def ui: UI < (Async & Scope) =
      val host = "127.0.0.1"
      val port = args.headOption.flatMap(_.toIntOption).getOrElse(18080)
      val fork = sys.env.get("SPIKE_MODE").contains("fork")
      for
          _        <- SpikeWeb.note(s"ui-start fork=$fork")
          store    <- Store.init
          page      = SpikeWeb.view(store)
          status   <- Signal.initRef("web: starting")
          // The by-name block of runHandlers returns the SAME value `page` for
          // each connection: the signals live outside it, so every browser
          // session and the terminal share them.
          handlers <- UI.runHandlers("/")(page)
          _        <-
            if fork then
                Fiber.init(SpikeWeb.serve(host, port, handlers, status)).unit
            else
                // HttpServer.init is scoped: the app scope closes it at quit.
                Abort
                  .run[HttpBindException](
                    HttpServer.init(port, host)(handlers*),
                  )
                  .map(r => SpikeWeb.bound(host, r).map(status.set))
      yield div.onKeyDown {
        case KeyboardEvent(Keyboard.Escape, _, _) => Tui.quit
        case _                                    => ()
      }(
        page,
        p(status).id("web"),
        p("Tab moves, Enter presses or adds, Esc quits").style(
          _.color(Style.Color.gray),
        ),
      )
      end for
    end ui
end SpikeWebApp
