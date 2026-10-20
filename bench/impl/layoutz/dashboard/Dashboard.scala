// claude 2026-10-20: created: the dashboard task (bench/tasks/dashboard.md) in layoutz
import layoutz.*

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import scala.sys.process.*

enum Msg:
  case Tick
  case Move(n: Int)
  case Page(n: Int)
  case ToggleFocus
  case Fail

final case class State(
    ticks: Int = 0,
    history: Vector[Int] = Vector.empty,
    selected: Int = 0,
    offset: Int = 0,
    onLoad: Boolean = false
)

object Dashboard extends LayoutzApp[State, Msg]:
  val Items = 1000
  val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
  // layoutz gives no size and no resize event: the size is read once.
  val (height, width) =
    Seq("sh", "-c", "stty size < /dev/tty").!!.trim
      .split(" ")
      .map(_.toInt) match
      case Array(r, c) => (r, c)
      case _           => (24, 80)
  // The frame keeps the last row free (each line ends with a newline):
  // header, the panels, the status bar.
  val rows: Int = (height - 1 - 2 - 2).max(1)

  /** v(T) of the specification. */
  def value(t: Int): Int = math.round(50 + 40 * math.sin(t / 5.0)).toInt

  def init: (State, Cmd[Msg]) = (State(), Cmd.none)

  def select(s: State, i: Int): State =
    val sel = i.max(0).min(Items - 1)
    s.copy(selected = sel, offset = s.offset.max(sel - rows + 1).min(sel))

  def update(msg: Msg, s: State): (State, Cmd[Msg]) = msg match
    case Msg.Tick =>
      val t = s.ticks + 1
      (
        s.copy(ticks = t, history = (s.history :+ value(t)).takeRight(40)),
        Cmd.none
      )
    case Msg.Move(n) if !s.onLoad => (select(s, s.selected + n), Cmd.none)
    case Msg.Page(n) if !s.onLoad =>
      (select(s, s.selected + n * rows), Cmd.none)
    case Msg.ToggleFocus => (s.copy(onLoad = !s.onLoad), Cmd.none)
    case Msg.Fail        => throw RuntimeException("forced failure (Ctrl-X)")
    case _               => (s, Cmd.none)

  def subscriptions(s: State): Sub[Msg] = Sub.batch(
    Sub.time.everyMs(100, Msg.Tick),
    Sub.onKeyPress {
      case Key.Down      => Some(Msg.Move(1))
      case Key.Up        => Some(Msg.Move(-1))
      case Key.PageDown  => Some(Msg.Page(1))
      case Key.PageUp    => Some(Msg.Page(-1))
      case Key.Tab       => Some(Msg.ToggleFocus)
      case Key.Ctrl('X') => Some(Msg.Fail)
      case _             => None
    }
  )

  def sparkline(values: Seq[Int]): String =
    values.map(v => "▁▂▃▄▅▆▇█".charAt((v * 8 / 101).min(7))).mkString

  def view(s: State): Element =
    val left = width / 2 - 4
    val right = width - width / 2 - 5
    val time = LocalTime.now.format(clock)
    val items = (s.offset until (s.offset + rows).min(Items)).map(i =>
      val marker = if i == s.selected then "> " else "  "
      Text(f"$marker%sItem ${i + 1}%04d".padTo(left, ' '))
    )
    val v = value(s.ticks)
    val bar = "█" * (v * 40 / 100) + "░" * (40 - v * 40 / 100) + f" $v%3d%%"
    val load = Seq[Element](s"Ticks: ${s.ticks}", bar, sparkline(s.history))
      .map(e => Text(e.render.padTo(right, ' ')))
    val focus = if s.onLoad then "load" else "list"
    layout(
      "Dashboard" + " " * (width - 9 - time.length) + time,
      row(
        box(if s.onLoad then "Items" else "*Items*")(items*),
        box(if s.onLoad then "*Load*" else "Load")(
          (load ++ Seq.fill(rows - load.size)(Text(" " * right)))*
        )
      ),
      f"Selected ${s.selected + 1}%04d/$Items | Focus: $focus | Tab focus, q quit"
    )

  def main(args: Array[String]): Unit = run(quitKey = Key.Char('q'))
