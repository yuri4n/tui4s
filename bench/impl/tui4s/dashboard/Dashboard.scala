// claude 2026-10-20: created: the dashboard task (bench/tasks/dashboard.md) in tui4s
import kyo.*
import kyo.UI.*
import tui4s.Hooks.{component, useInterval}
import tui4s.use.{useNow, useSelection}
import tui4s.widgets.{Gauge, ListView, Sparkline, StatusBar, Theme}
import tui4s.{Tui, TuiApp}

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The dashboard of the bench: kyo-ui and tui4s-widgets on the tui4s runner.
  * The list and the load panel are the two tab stops: kyo-ui's focus model
  * moves the focus on Tab, and the list takes the arrows only when it has it.
  */
object DashboardApp extends TuiApp:

  override def stylesheet: Stylesheet = Theme.palette(Theme.terminal)

  val items: Chunk[String] = Chunk.from((1 to 1000).map(i => f"Item $i%04d"))

  /** v(T) of the specification. */
  def value(t: Int): Int = math.round(50 + 40 * math.sin(t / 5.0)).toInt

  val clock: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

  def border(on: Boolean): Style =
    Style.border(1.px, if on then Style.Color.yellow else Style.Color.gray)

  def ui: UI < (Async & Scope) =
    for
      ticks <- Signal.initRef(0)
      history <- Signal.initRef(Chunk.empty[Int])
      onLoad <- Signal.initRef(false)
      now <- useNow(1.second)
      _ <- useInterval(100.millis)(
        ticks
          .updateAndGet(_ + 1)
          .map(t => history.updateAndGet(h => (h :+ value(t)).takeRight(40)))
      )
      size <- Tui.size
      start <- size.current
      list = Signal.initConst(items)
      selection <- useSelection(list, identity, page = start.height - 5)
      // The list widget takes a fixed height: a new one for each height.
      listView = size.render(s =>
        val height = (s.height - 5).max(1)
        component(height.toString)(
          ListView.virtual(list, selection, height)(span(_))
        )
      )
    yield div
      .style(_.height(100.pct))
      .onKeyDown {
        case KeyboardEvent(Keyboard.Char('q'), _, _)           => Tui.quit
        case KeyboardEvent(Keyboard.Char('x'), m, _) if m.ctrl =>
          Sync.defer(throw RuntimeException("forced failure (Ctrl-X)"))
        case _ => ()
      }(
        div.style(_.row)(
          div("Dashboard").style(_.flexGrow(1)),
          span(now.map(t => clock.format(t.toJava)))
        ),
        div.style(_.row.flexGrow(1))(
          onLoad.render(on =>
            div.style(border(!on).width(50.pct))(
              div("Items").style(_.bold),
              listView
            )
          ),
          onLoad.render(on =>
            div
              .tabIndex(0)
              .onFocus(onLoad.set(true))
              .onBlur(onLoad.set(false))
              .style(border(on).flexGrow(1))(
                div("Load").style(_.bold),
                span("Ticks: ", ticks.map(_.toString)),
                Gauge(ticks.map(value(_) / 100.0), width = 30),
                Sparkline(history.map(_.map(_.toDouble)), lo = 0, hi = 100)
              )
          )
        ),
        StatusBar(
          selection.index
            .combineLatest(onLoad)
            .map((i, on) =>
              val f = if on then "load" else "list"
              f"Selected ${i + 1}%04d/1000 | Focus: $f | Tab focus, q quit"
            )
        )
      )
