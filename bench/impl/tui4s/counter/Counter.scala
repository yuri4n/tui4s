// claude 2026-10-20: created: the counter task (bench/tasks/counter.md) in tui4s
import kyo.*
import kyo.UI.*
import tui4s.{Tui, TuiApp}

/** The counter of the bench: kyo-ui on the tui4s runner. */
object CounterApp extends TuiApp:
  def ui: UI < (Async & Scope) =
    for count <- Signal.initRef(0)
    yield
      def add(n: Int): Unit < Sync = count.updateAndGet(_ + n).unit
      div
        .style(_.height(100.pct))
        .onKeyDown {
          case KeyboardEvent(Keyboard.Char('+') | Keyboard.ArrowUp, _, _) =>
            add(1)
          case KeyboardEvent(Keyboard.Char('-') | Keyboard.ArrowDown, _, _) =>
            add(-1)
          case KeyboardEvent(Keyboard.Char('q'), _, _)           => Tui.quit
          case KeyboardEvent(Keyboard.Char('x'), m, _) if m.ctrl =>
            Sync.defer(throw RuntimeException("forced failure (Ctrl-X)"))
          case _ => ()
        }(
          div("Counter"),
          div.style(_.margin(1.px, 0.px, 0.px, 0.px))(
            span("Count: ", count.map(_.toString))
          ),
          div.style(_.flexGrow(1)),
          div("+/- or Up/Down change the count, q quits")
        )
