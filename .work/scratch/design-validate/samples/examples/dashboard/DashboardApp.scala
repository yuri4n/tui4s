package examples.dashboard

import kyo.*
import tui4s.*

final case class Dash(
    tab: Int = 0,
    cpu: Double = 0.0,
    history: Vector[Long] = Vector.empty,
    logs: Vector[String] = Vector.empty,
    paused: Boolean = false
) derives CanEqual:
  def sample(v: Double): Dash = copy(cpu = v, history = (history :+ (v * 100).toLong).takeRight(200))
  def log(line: String): Dash  = copy(logs = (logs :+ line).takeRight(500))
  def switchTab(by: Int): Dash = copy(tab = Math.floorMod(tab + by, Dash.tabs.size))
  def togglePause: Dash        = copy(paused = !paused)
object Dash:
  val tabs: Vector[String] = Vector("Metrics", "Requests")

object DashboardApp extends TuiApp[Dash]:
  /** Any Kyo stream. chunkSize = 1: with the default (4096) unfold buffers lines before they emit. */
  val requests: Stream[String, Async] =
    Stream.unfold(1, chunkSize = 1)(i =>
      Async.sleep(700.millis).andThen(Present((s"GET /api/items/$i 200", i + 1)))
    )

  val keys: Keymap[Dash] = Keymap(
    bind(Key.Left, "prev tab")(_.switchTab(-1)),
    bind(Key.Right, "next tab")(_.switchTab(1)),
    bind('p', "pause")(_.togglePause),
    bind('q', "quit")(_ => Quit)
  )

  def init: Dash = Dash()

  def update(d: Dash, event: Event): Next[Dash] = keys(d, event)

  /** A function of the state: pausing removes the poller, so its fiber stops; the log stream keeps running. */
  override def sources(d: Dash): Seq[Source[Dash]] =
    val logs = source.stream(requests)(_.log(_)).id("logs")
    val cpu  = source.poll(250.millis)(Random.nextDouble)(_.sample(_)).id("cpu")
    if d.paused then Seq(logs) else Seq(logs, cpu)

  def view(d: Dash): View =
    column(
      tabs(Dash.tabs, d.tab).bordered(" Dashboard ").size(3),
      if d.tab == 0 then metrics(d) else logPane(d.logs).bordered(" Requests "),
      help(keys).size(1)
    )

  private def metrics(d: Dash): View =
    row(
      column(
        gauge(d.cpu, f"${d.cpu * 100}%.0f%%").bordered(if d.paused then " CPU (paused) " else " CPU ").size(3),
        sparkline(d.history, max = Some(100L)).bordered(" History ")
      ).percent(60),
      logPane(d.logs).bordered(" Requests ")
    )
end DashboardApp
