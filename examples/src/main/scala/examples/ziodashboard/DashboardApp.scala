package examples.ziodashboard

import tui4s.interop.zio.*
import zio.*
import zio.stream.ZStream

final case class Dash(
    tab: Int = 0,
    cpu: Double = 0.0,
    history: Vector[Long] = Vector.empty,
    logs: Vector[String] = Vector.empty,
    paused: Boolean = false,
) derives CanEqual:
    def sample(v: Double): Dash  =
      copy(cpu = v, history = (history :+ (v * 100).toLong).takeRight(200))
    def log(line: String): Dash  = copy(logs = (logs :+ line).takeRight(500))
    def switchTab(by: Int): Dash =
      copy(tab = Math.floorMod(tab + by, Dash.tabs.size))
    def togglePause: Dash        = copy(paused = !paused)
end Dash

object Dash:
    val tabs: Vector[String] = Vector("Metrics", "Requests")

/** The Dashboard for ZIO users (SPEC 3.4): tabs, a gauge and a sparkline fed by
  * a ZIO poller every 250 ms, and a log pane fed by a `ZStream`. No Kyo type:
  * `ZStream`, `Schedule`, `Random` and `700.millis` are ZIO's.
  *
  * Run it: `sbt examples/launchers`, then `examples/target/bin/ziodashboard`.
  */
object DashboardApp extends TuiApp[Dash]:
    val requests: ZStream[Any, Nothing, String] =
      ZStream
        .iterate(1)(_ + 1)
        .schedule(Schedule.spaced(700.millis))
        .map(i => s"GET /api/items/$i 200")

    val keys: Keymap[Dash] = Keymap(
      bind(Key.Left, "prev tab")(_.switchTab(-1)),
      bind(Key.Right, "next tab")(_.switchTab(1)),
      bind('p', "pause")(_.togglePause),
      bind('q', "quit")(_ => Quit),
    )

    def init: Dash = Dash()

    def update(d: Dash, event: Event): Next[Dash] = keys(d, event)

    /** A function of the state: pausing removes the poller, so its ZIO is
      * interrupted; the log stream keeps running.
      */
    override def sources(d: Dash): Seq[Source[Dash]] =
        val logs = source.stream(requests)(_.log(_)).id("logs")
        val cpu  =
          source.poll(250.millis)(Random.nextDouble)(_.sample(_)).id("cpu")
        if d.paused then Seq(logs) else Seq(logs, cpu)

    def view(d: Dash): View =
      column(
        tabs(Dash.tabs, d.tab).bordered(" Dashboard ").size(3),
        if d.tab == 0 then metrics(d)
        else logPane(d.logs).bordered(" Requests "),
        help(keys).size(1),
      )

    private def metrics(d: Dash): View =
      row(
        column(
          gauge(d.cpu, f"${d.cpu * 100}%.0f%%")
            .bordered(if d.paused then " CPU (paused) " else " CPU ")
            .size(3),
          sparkline(d.history, max = Some(100L)).bordered(" History "),
        ).percent(60),
        logPane(d.logs).bordered(" Requests "),
      )
end DashboardApp
