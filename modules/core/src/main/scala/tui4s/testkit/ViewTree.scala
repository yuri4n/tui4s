package tui4s.testkit

import tui4s.*
import tui4s.internal.Json
import tui4s.internal.discard

/** A view as an indented tree of text, one node per line (for agents and test
  * failures). A size modifier shows in brackets before its node:
  *
  * {{{
  * bordered " Counter "
  *   column flex=center
  *     [length 1] text center "Count: 0"
  *     [length 1] help center "+ increment · - decrement · q quit"
  * }}}
  */
private[tui4s] object ViewTree:
    /** The tree of `view`. */
    def apply(view: View): String =
        val b = new StringBuilder
        node(view, 0, "", b)
        b.toString

    private def node(
        v: View,
        depth: Int,
        prefix: String,
        b: StringBuilder,
    ): Unit =
        def line(text: String): Unit =
          discard(
            b.append("  " * depth).append(prefix).append(text).append('\n'),
          )
        def child(c: View): Unit     = node(c, depth + 1, "", b)
        v match
            case View.Sized(c, k)                               =>
              node(c, depth, prefix + "[" + size(k) + "] ", b)
            case View.Text(lines, align, wraps, scroll)         =>
              line(
                "text" + alignment(align) + (if wraps then " wrap" else "") +
                  (if scroll > 0 then s" scroll=$scroll" else "") + " " +
                  Json.str(lines.map(plain).mkString("\n")),
              )
            case View.Stack(vertical, cs, gap, flex)            =>
              line(
                (if vertical then "column" else "row") +
                  (if gap > 0 then s" gap=$gap" else "") +
                  (if flex != Flex.Start then " flex=" + lower(flex) else ""),
              )
              cs.foreach(child)
            case View.Layers(cs)                                =>
              line("layers")
              cs.foreach(child)
            case View.Items(items, selected, focused)           =>
              line(
                s"list items=${items.size} selected=$selected" +
                  (if focused then " focused" else ""),
              )
              items.foreach(i => node(View.Empty, depth + 1, "- " + str(i), b))
            case View.Table(header, rows, _, selected, focused) =>
              line(
                s"table header=${Json.arr(header)} rows=${rows.size} " +
                  s"selected=$selected" + (if focused then " focused" else ""),
              )
            case View.Tabs(titles, selected)                    =>
              line(s"tabs selected=$selected ${Json.arr(titles)}")
            case View.Gauge(ratio, label, _, isLine)            =>
              line(
                (if isLine then "line-gauge " else "gauge ") + ratio + " " +
                  Json.str(label),
              )
            case View.Sparkline(values, max, _)                 =>
              line(
                s"sparkline values=${values.size}" +
                  max.fold("")(m => s" max=$m") +
                  values.lastOption.fold("")(l => s" last=$l"),
              )
            case View.Input(field, focused, placeholder)        =>
              line(
                "input " + Json.str(field.text) + s" cursor=${field.cursor}" +
                  (if focused then " focused" else "") +
                  (if placeholder.nonEmpty then
                       " placeholder=" + Json.str(placeholder)
                   else ""),
              )
            case View.Log(lines)                                =>
              line(
                s"log lines=${lines.size}" +
                  lines.lastOption.fold("")(l => " last=" + Json.str(str(l))),
              )
            case View.Help(entries, align)                      =>
              line(
                "help" + alignment(align) + " " +
                  Json.str(entries.map((k, h) => s"$k $h").mkString(" · ")),
              )
            case View.Clear                                     => line("clear")
            case View.Empty                                     =>
              line(if prefix.startsWith("- ") then "" else "empty")
            case View.Widget(w)                                 =>
              line("widget " + w.getClass.getSimpleName)
            case View.Stateful(_, _, kind)                      =>
              line("stateful " + kind.getSimpleName)
            case View.Draw(_)                                   => line("draw")
            case View.Bordered(c, border, focused)              =>
              line(
                "bordered " + Json.str(border.title) +
                  (if focused then " focused" else ""),
              )
              child(c)
            case View.Padded(c, h, vert)                        =>
              line(s"padded horizontal=$h vertical=$vert")
              child(c)
            case View.Styled(c, _)                              =>
              line("styled")
              child(c)
            case View.Popup(c, w, h)                            =>
              line(s"popup ${w}x$h")
              child(c)
            case View.Identified(c, name)                       =>
              line("id " + Json.str(name))
              child(c)
            case View.Tagged(c, name)                           =>
              line("tag " + Json.str(name))
              child(c)
        end match
    end node

    private def plain(l: Line): String = Line.toJ(l).toContentString

    private def str(item: String | Line): String = item match
        case s: String => s
        case l         => plain(l.asInstanceOf[Line])

    private def lower(a: Any): String = a.toString.toLowerCase

    private def alignment(a: Align): String =
      if a == Align.Left then "" else " " + lower(a)

    private def size(c: Constraint): String = c match
        case Constraint.Length(n)   => s"length $n"
        case Constraint.Min(n)      => s"min $n"
        case Constraint.Max(n)      => s"max $n"
        case Constraint.Percent(n)  => s"percent $n"
        case Constraint.Fill(n)     => s"fill $n"
        case Constraint.Ratio(n, d) => s"ratio $n/$d"

end ViewTree
