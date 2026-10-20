// claude 2026-10-20: created: the counter task (bench/tasks/counter.md) in layoutz
import layoutz.*

import scala.sys.process.*

enum Msg:
  case Add(n: Int)
  case Fail

object Counter extends LayoutzApp[Int, Msg]:
  // layoutz gives no size and no resize event: the height is read once.
  val height: Int =
    Seq("sh", "-c", "stty size < /dev/tty").!!.trim.split(" ").head.toInt

  def init: (Int, Cmd[Msg]) = (0, Cmd.none)

  def update(msg: Msg, count: Int): (Int, Cmd[Msg]) = msg match
    case Msg.Add(n) => (count + n, Cmd.none)
    case Msg.Fail   => throw RuntimeException("forced failure (Ctrl-X)")

  def subscriptions(count: Int): Sub[Msg] = Sub.onKeyPress {
    case Key.Char('+') | Key.Up   => Some(Msg.Add(1))
    case Key.Char('-') | Key.Down => Some(Msg.Add(-1))
    case Key.Ctrl('X')            => Some(Msg.Fail)
    case _                        => None
  }

  // Each line of a frame ends with a newline: a line on the last row would
  // scroll the screen, so the help is on the row above it.
  def view(count: Int): Element =
    val top = Seq[Element]("Counter", "", s"Count: $count")
    val gap = Seq.fill(height - top.size - 2)(Text(""))
    layout((top ++ gap :+ Text("+/- or Up/Down change the count, q quits"))*)

  def main(args: Array[String]): Unit = run(quitKey = Key.Char('q'))
