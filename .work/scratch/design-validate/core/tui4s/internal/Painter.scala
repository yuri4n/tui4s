package tui4s.internal

import java.util.Optional
import jatatui.core.buffer.Buffer
import jatatui.core.layout.Constraint as JConstraint
import jatatui.core.layout.Flex as JFlex
import jatatui.core.layout.HorizontalAlignment
import jatatui.core.layout.Layout
import jatatui.core.layout.Margin
import jatatui.core.layout.Rect
import jatatui.core.style.Style as JStyle
import jatatui.core.terminal.Frame as JFrame
import jatatui.core.text.Line as JLine
import jatatui.core.text.Span as JSpan
import jatatui.widgets.Clear as JClear
import jatatui.widgets.block.Block as JBlock
import jatatui.widgets.block.BorderType
import jatatui.widgets.gauge.Gauge as JGauge
import jatatui.widgets.gauge.LineGauge as JLineGauge
import jatatui.widgets.list.List as JList
import jatatui.widgets.list.ListItem
import jatatui.widgets.list.ListState
import jatatui.widgets.paragraph.Paragraph
import jatatui.widgets.paragraph.Scroll
import jatatui.widgets.paragraph.Wrap
import jatatui.widgets.sparkline.Sparkline as JSparkline
import jatatui.widgets.table.Row
import jatatui.widgets.table.Table as JTable
import jatatui.widgets.table.TableState
import jatatui.widgets.tabs.Tabs as JTabs
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.*
import tui4s.*

/** Paints a View into a jatatui buffer (5.7): layout, widget-state slots, hit map, cursor, theme. Owned by the render
  * fiber; plain mutable fields.
  */
private[tui4s] final class Painter(theme: Theme):
  private final class Slot(val kind: Int, val state: Any):
    var visited = true

  private val slots                          = new java.util.HashMap[Long, Slot]()
  private val hits                           = ArrayBuffer.empty[(Rect, String)]
  private var lastHits: Vector[(Rect, String)] = Vector.empty
  private var cursorAt: (Int, Int) | Null    = null
  def markResized(): Unit = () // the engine forces the next frame; the painter keeps no per-size state

  def cursor: Option[(Int, Int)] = Option(cursorAt)

  /** The tags under (x, y) in the last drawn frame, top-most first. */
  def tagsAt(x: Int, y: Int): List[String] =
    lastHits.reverseIterator
      .collect { case (r, n) if x >= r.x && x < r.x + r.width && y >= r.y && y < r.y + r.height => n }
      .toList

  /** One frame. */
  def paint(view: View, frame: JFrame): Unit =
    hits.clear()
    cursorAt = null
    slots.values().forEach(_.visited = false)
    walk(view, frame.area(), frame.bufferMut(), 1L)
    discard(slots.values().removeIf(!_.visited))
    lastHits = hits.toVector
    val c = cursorAt
    if c != null then frame.setCursorPosition(c._1, c._2)

  private def slot[A](pos: Long, kind: Int)(make: => A): A =
    val s = slots.get(pos)
    if s != null && s.kind == kind then
      s.visited = true
      s.state.asInstanceOf[A]
    else
      val st = make
      discard(slots.put(pos, Slot(kind, st)))
      st

  private def constraintOf(v: View): JConstraint = v match
    case View.Sized(_, c) =>
      c match
        case Constraint.Length(n)   => JConstraint.Length(n)
        case Constraint.Fill(w)     => JConstraint.Fill(w)
        case Constraint.Percent(p)  => JConstraint.Percentage(p)
        case Constraint.Min(n)      => JConstraint.Min(n)
        case Constraint.Max(n)      => JConstraint.Max(n)
        case Constraint.Ratio(a, b) => JConstraint.Ratio(a, b)
    case _ => JConstraint.Fill(1)

  private def flexOf(f: Flex): JFlex = f match
    case Flex.Start        => JFlex.Start
    case Flex.Center       => JFlex.Center
    case Flex.End          => JFlex.End
    case Flex.SpaceBetween => JFlex.SpaceBetween
    case Flex.SpaceAround  => JFlex.SpaceAround

  private def styleOf(s: Style): JStyle = Style.toJ(s)
  private def fgStyle(c: Color): JStyle = JStyle.empty().withFg(Color.toJ(c))

  private def walk(view: View, area: Rect, buf: Buffer, pos: Long): Unit =
    if area.width <= 0 || area.height <= 0 then ()
    else
      view match
        case t: View.Text =>
          var p = Paragraph.of(t.lines.map(Line.toJ).asJava)
          p = t.align match
            case Align.Left   => p.withAlignment(HorizontalAlignment.Left)
            case Align.Center => p.withAlignment(HorizontalAlignment.Center)
            case Align.Right  => p.withAlignment(HorizontalAlignment.Right)
          if t.wraps then p = p.withWrap(Wrap(true))
          if t.scrollY != 0 then p = p.withScroll(Scroll(t.scrollY, 0))
          p.render(area, buf)

        case s: View.Stack =>
          val cs     = s.children.map(constraintOf).asJava
          val layout = (if s.vertical then Layout.vertical(cs) else Layout.horizontal(cs))
            .withSpacing(s.spacing)
            .withFlex(flexOf(s.justify))
          val rects = layout.split(area) // jatatui's shared cache entry: read at once, never stored
          var i     = 0
          while i < s.children.size do
            walk(s.children(i), rects(i), buf, pos * 31 + i + 1)
            i += 1

        case View.Layers(children) =>
          var i = 0
          while i < children.size do
            walk(children(i), area, buf, pos * 31 + i + 1)
            i += 1

        case it: View.Items =>
          val state = slot(pos, 1)(ListState.empty())
          state.select(if it.selected >= 0 then Optional.of(Integer.valueOf(it.selected)) else Optional.empty())
          JList
            .of(it.items.map(i => ListItem.of(Line.lineOf(i))).asJava)
            .withHighlightSymbol("> ")
            .withHighlightStyle(styleOf(if it.focused then theme.highlight else theme.unfocusedHighlight))
            .render(area, buf, state)

        case t: View.Table =>
          val state = slot(pos, 2)(TableState())
          state.select(if t.selected >= 0 then Optional.of(Integer.valueOf(t.selected)) else Optional.empty())
          val rows = t.rows.map(r => Row.ofStrings(r.map(c => Line.lineOf(c).toContentString)*)).asJava
          JTable
            .of(rows, t.widths.map(w => constraintOf(View.Sized(View.Empty, w))).asJava)
            .withHeader(Row.ofStrings(t.header*))
            .withHighlightSymbol("> ")
            .withRowHighlightStyle(styleOf(if t.focused then theme.highlight else theme.unfocusedHighlight))
            .render(area, buf, state)

        case View.Tabs(titles, selected) =>
          JTabs
            .of(titles.map(JLine.from(_)).asJava)
            .withSelected(selected)
            .withHighlightStyle(fgStyle(theme.accent).bold())
            .render(area, buf)

        case View.Gauge(ratio, label, color, line) =>
          val r = if ratio.isNaN then 0.0 else ratio.max(0.0).min(1.0) // withRatio throws outside 0..1
          if line then
            val g = JLineGauge.empty().withRatio(r).withFilledStyle(fgStyle(color))
            (if label.nonEmpty then g.withLabel(label) else g).render(area, buf)
          else
            val g = JGauge.empty().withRatio(r).withGaugeStyle(fgStyle(color))
            (if label.nonEmpty then g.withLabel(label) else g).render(area, buf)

        case View.Sparkline(values, max, color) =>
          val s = JSparkline.empty().withDataLongs(values*).withStyle(fgStyle(color))
          max.fold(s)(m => s.withMax(m)).render(area, buf)

        case View.Input(field, focused, placeholder) =>
          if field.text.isEmpty && placeholder.nonEmpty then
            discard(buf.setStringn(area.x, area.y, placeholder, area.width, JStyle.empty().dim()))
          else discard(buf.setStringn(area.x, area.y, field.text, area.width, JStyle.empty()))
          if focused then
            val before = TextField.graphemes(field.text).take(field.cursor).mkString
            val x      = JSpan.raw(before).width().min(area.width - 1)
            cursorAt = (area.x + x, area.y)

        case View.Log(lines) =>
          val shown = lines.takeRight(area.height)
          var y     = area.y + area.height - shown.size
          shown.foreach { l =>
            discard(buf.setLine(area.x, y, Line.lineOf(l), area.width))
            y += 1
          }

        case View.Clear => JClear().render(area, buf)
        case View.Empty => ()

        case View.Widget(w)            => w.render(area, buf)
        case View.Stateful(fresh, paint) => paint(area, buf, slot(pos, 3)(fresh()))
        case View.Draw(paint)          => paint(area, buf)

        case View.Sized(child, _) => walk(child, area, buf, pos)

        case View.Bordered(child, border, focused) =>
          val kind = border.kind match
            case BorderKind.Plain   => BorderType.Plain
            case BorderKind.Rounded => BorderType.Rounded
            case BorderKind.Double  => BorderType.Double
            case BorderKind.Thick   => BorderType.Thick
          val bstyle = if focused then styleOf(border.style).patch(fgStyle(theme.accent)) else styleOf(border.style)
          var block  = JBlock.bordered().withBorderType(kind).withBorderStyle(bstyle)
          if border.title.nonEmpty then block = block.withTitle(border.title).withTitleStyle(styleOf(border.titleStyle))
          block.render(area, buf)
          walk(child, block.inner(area), buf, pos)

        case View.Padded(child, h, v) => walk(child, area.inner(Margin(h, v)), buf, pos)

        case View.Styled(child, style) =>
          buf.setStyle(area, styleOf(style))
          walk(child, area, buf, pos)

        case View.Popup(child, width, height) =>
          val w    = width.min(area.width)
          val h    = height.min(area.height)
          val rect = Rect(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h)
          JClear().render(rect, buf)
          walk(child, rect, buf, pos)

        case View.Identified(child, name) => walk(child, area, buf, name.hashCode.toLong | (1L << 40))

        case View.Tagged(child, name) =>
          hits += ((area, name))
          walk(child, area, buf, pos)
      end match
  end walk
end Painter
