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
import jatatui.core.text.Text as JText
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
import jatatui.widgets.table.TableCell
import jatatui.widgets.table.TableState
import jatatui.widgets.tabs.Tabs as JTabs
import scala.collection.mutable.ArrayBuffer
import tui4s.*

/** Paints a [[View]] into a jatatui buffer (SPEC 5.7).
  *
  *   - Walk: containers split their area with `Layout` once per frame and read
  *     the returned (shared, cached) array at once; wrappers change the area or
  *     the style.
  *   - Positions: the root is 1, a child is `parent * 31 + index + 1`; `.id`
  *     replaces the position of its subtree with a value from the name.
  *   - Widget-state slots: lists, tables and stateful nodes keep their state
  *     per position. After each frame the slots that were not painted are
  *     removed (the sweep).
  *   - Hit map: tagged areas in paint order, for `Mouse.tags`.
  *   - Cursor: a focused input sets it; otherwise the terminal hides it.
  *
  * Owned by the render fiber (or the test thread): plain mutable fields, no
  * locks.
  */
private[tui4s] final class Painter(theme: Theme):
    import Painter.*

    private final class Slot(val tag: AnyRef, val state: Any):
        var visited: Boolean = true

    private val slots      = new java.util.HashMap[Long, Slot]()
    private val hits       = ArrayBuffer.empty[Hit]
    private var lastHits   = Array.empty[Hit]
    private var cursorX    = -1
    private var cursorY    = -1
    private var lastCursor = Option.empty[(Int, Int)]

    private val accent    = JStyle.empty().withFg(Color.toJ(theme.accent))
    private val highlight = Style.toJ(theme.highlight)
    private val dimmed    = Style.toJ(theme.unfocusedHighlight)
    private val helpKey   = Style.toJ(theme.helpKey)
    private val helpText  = Style.toJ(theme.helpText)

    /** The cursor of the last painted frame, if a focused input set it. */
    def cursor: Option[(Int, Int)] = lastCursor

    /** The number of kept widget states (lists, tables, stateful nodes). */
    def slotCount: Int = slots.size

    /** The tags under (x, y) in the last painted frame, top-most first. */
    def tagsAt(x: Int, y: Int): List[String] =
        var tags = List.empty[String]
        var i    = 0
        while i < lastHits.length do
            val h = lastHits(i)
            if h.contains(x, y) then tags = h.name :: tags
            i += 1
        tags
    end tagsAt

    /** Paints one frame and sets the frame's cursor. */
    def paint(view: View, frame: JFrame): Unit =
        paint(view, frame.area(), frame.bufferMut())
        lastCursor.foreach((x, y) => frame.setCursorPosition(x, y))

    /** Paints `view` into `area` of `buf`. */
    def paint(view: View, area: Rect, buf: Buffer): Unit =
        hits.clear()
        cursorX = -1
        cursorY = -1
        slots.values().forEach(_.visited = false)
        walk(view, area, buf, 1L)
        discard(slots.values().removeIf(!_.visited))
        lastHits = hits.toArray
        lastCursor = if cursorX >= 0 then Some((cursorX, cursorY)) else None
    end paint

    /** The kept state for `pos`, or a new one from `make`. A slot of another
      * kind at `pos` is replaced.
      */
    private def slot[A](pos: Long, tag: AnyRef)(make: => A): A =
        val s = slots.get(pos)
        if s != null && (s.tag eq tag) then
            s.visited = true
            s.state.asInstanceOf[A]
        else
            val state = make
            discard(slots.put(pos, Slot(tag, state)))
            state
    end slot

    private def walk(view: View, area: Rect, buf: Buffer, pos: Long): Unit =
      if area.width > 0 && area.height > 0 then
          view match
              case t: View.Text                     => text(t, area, buf)
              case s: View.Stack                    => stack(s, area, buf, pos)
              case View.Layers(children)            =>
                var i  = 0
                val it = children.iterator
                while it.hasNext do
                    walk(it.next(), area, buf, childPosition(pos, i))
                    i += 1
              case l: View.Items                    => items(l, area, buf, pos)
              case t: View.Table                    => table(t, area, buf, pos)
              case t: View.Tabs                     => tabs(t, area, buf)
              case g: View.Gauge                    => gauge(g, area, buf)
              case s: View.Sparkline                => sparkline(s, area, buf)
              case i: View.Input                    => input(i, area, buf)
              case l: View.Log                      => log(l, area, buf)
              case h: View.Help                     => help(h, area, buf)
              case View.Clear                       => JClear.INSTANCE.render(area, buf)
              case View.Empty                       => ()
              case View.Widget(w)                   => w.render(area, buf)
              case View.Stateful(fresh, paint)      =>
                // The class of the paint function tells two stateful
                // nodes at one position apart.
                paint(area, buf, slot(pos, paint.getClass)(fresh()))
              case View.Draw(paint)                 => paint(area, buf)
              case View.Sized(child, _)             => walk(child, area, buf, pos)
              case b: View.Bordered                 => bordered(b, area, buf, pos)
              case View.Padded(child, h, v)         =>
                walk(child, area.inner(Margin(h, v)), buf, pos)
              case View.Styled(child, style)        =>
                buf.setStyle(area, Style.toJ(style))
                walk(child, area, buf, pos)
              case View.Popup(child, width, height) =>
                val w    = width.min(area.width)
                val h    = height.min(area.height)
                val x    = area.x + (area.width - w) / 2
                val y    = area.y + (area.height - h) / 2
                val rect = Rect(x, y, w, h)
                JClear.INSTANCE.render(rect, buf)
                walk(child, rect, buf, pos)
              case View.Identified(child, name)     =>
                walk(child, area, buf, idPosition(name))
              case View.Tagged(child, name)         =>
                hits += Hit(area, name)
                walk(child, area, buf, pos)

    private def text(t: View.Text, area: Rect, buf: Buffer): Unit =
        val lines = new java.util.ArrayList[JLine](t.lines.size)
        t.lines.foreach(l => discard(lines.add(Line.toJ(l))))
        var p     = Paragraph.of(lines).withAlignment(alignment(t.align))
        if t.wraps then p = p.withWrap(Wrap(true))
        if t.scrollY > 0 then p = p.withScroll(Scroll(t.scrollY, 0))
        p.render(area, buf)

    private def stack(s: View.Stack, area: Rect, buf: Buffer, pos: Long): Unit =
      if s.children.nonEmpty then
          val cs     = new java.util.ArrayList[JConstraint](s.children.size)
          s.children.foreach(c => discard(cs.add(constraintOf(c))))
          val layout =
            if s.vertical then Layout.vertical(cs) else Layout.horizontal(cs)
          // jatatui's shared cache entry: read at once, never kept.
          val rects  = layout
            .withSpacing(s.spacing)
            .withFlex(flexOf(s.justify))
            .split(area)
          var i      = 0
          val it     = s.children.iterator
          while it.hasNext do
              walk(it.next(), rects(i), buf, childPosition(pos, i))
              i += 1

    private def items(l: View.Items, area: Rect, buf: Buffer, pos: Long): Unit =
        val state = slot(pos, ListTag)(ListState.empty())
        state.select(selection(l.selected))
        val rows  = new java.util.ArrayList[ListItem](l.items.size)
        l.items.foreach(i => discard(rows.add(ListItem.of(Line.lineOf(i)))))
        JList
          .of(rows)
          .withHighlightSymbol(HighlightSymbol)
          .withHighlightStyle(if l.focused then highlight else dimmed)
          .render(area, buf, state)
    end items

    private def table(t: View.Table, area: Rect, buf: Buffer, pos: Long): Unit =
        val state  = slot(pos, TableTag)(TableState())
        state.select(selection(t.selected))
        val rows   = new java.util.ArrayList[Row](t.rows.size)
        t.rows.foreach { r =>
          val cells = new java.util.ArrayList[TableCell](r.size)
          r.foreach(c =>
            discard(cells.add(TableCell.of(JText.from(Line.lineOf(c))))),
          )
          discard(rows.add(Row.of(cells)))
        }
        val widths = new java.util.ArrayList[JConstraint](t.widths.size)
        t.widths.foreach(w => discard(widths.add(constraint(w))))
        val header = new java.util.ArrayList[TableCell](t.header.size)
        t.header.foreach(h => discard(header.add(TableCell.of(h))))
        JTable
          .of(rows, widths)
          .withHeader(Row.of(header).withStyle(HeaderStyle))
          .withHighlightSymbol(HighlightSymbol)
          .withRowHighlightStyle(if t.focused then highlight else dimmed)
          .render(area, buf, state)
    end table

    private def tabs(t: View.Tabs, area: Rect, buf: Buffer): Unit =
        val titles = new java.util.ArrayList[JLine](t.titles.size)
        t.titles.foreach(s => discard(titles.add(JLine.raw(s))))
        var w      = JTabs.of(titles).withHighlightStyle(accent.bold())
        if t.titles.nonEmpty then
            w = w.withSelected(t.selected.max(0).min(t.titles.size - 1))
        w.render(area, buf)

    private def gauge(g: View.Gauge, area: Rect, buf: Buffer): Unit =
        // withRatio throws outside 0..1; the dsl clamps too.
        val ratio = if g.ratio.isNaN then 0.0 else g.ratio.max(0.0).min(1.0)
        val style = JStyle.empty().withFg(Color.toJ(g.color))
        if g.line then
            val w = JLineGauge.empty().withRatio(ratio).withFilledStyle(style)
            (if g.label.nonEmpty then w.withLabel(g.label) else w)
              .render(area, buf)
        else
            val w = JGauge.empty().withRatio(ratio).withGaugeStyle(style)
            (if g.label.nonEmpty then w.withLabel(g.label) else w)
              .render(area, buf)
        end if
    end gauge

    private def sparkline(s: View.Sparkline, area: Rect, buf: Buffer): Unit =
        // The newest values that fit, oldest on the left.
        val shown = s.values.takeRight(area.width)
        val w     = JSparkline
          .empty()
          .withDataLongs(shown*)
          .withStyle(JStyle.empty().withFg(Color.toJ(s.color)))
        s.max.fold(w)(m => w.withMax(m)).render(area, buf)
    end sparkline

    /** A grapheme-aware single-line field. A text wider than the area scrolls
      * so that the cursor cell stays visible.
      */
    private def input(in: View.Input, area: Rect, buf: Buffer): Unit =
        val field = in.field
        if field.text.isEmpty then
            if in.placeholder.nonEmpty then
                discard(
                  buf.setStringn(
                    area.x,
                    area.y,
                    in.placeholder,
                    area.width,
                    PlaceholderStyle,
                  ),
                )
            end if
            if in.focused then setCursor(area.x, area.y)
        else
            val graphemes = TextField.graphemes(field.text)
            val widths    = graphemes.map(width)
            var start     = 0
            var before    = 0 // the width of the visible text before the cursor
            var i         = 0
            while i < field.cursor do
                before += widths(i)
                i += 1
            while before > area.width - 1 && start < field.cursor do
                before -= widths(start)
                start += 1
            val visible   = graphemes.iterator.drop(start).mkString
            discard(
              buf.setStringn(
                area.x,
                area.y,
                visible,
                area.width,
                JStyle.empty(),
              ),
            )
            if in.focused then setCursor(area.x + before, area.y)
        end if
    end input

    private def setCursor(x: Int, y: Int): Unit =
        cursorX = x
        cursorY = y

    private def log(l: View.Log, area: Rect, buf: Buffer): Unit =
        val shown = l.lines.takeRight(area.height)
        var y     = area.y + area.height - shown.size
        shown.foreach { line =>
          discard(buf.setLine(area.x, y, Line.lineOf(line), area.width))
          y += 1
        }

    /** Whole entries that fit; " …" when some do not. When not even the first
      * entry fits, the first entry is cut at the edge.
      */
    private def help(h: View.Help, area: Rect, buf: Buffer): Unit =
        val n      = h.entries.size
        val sizes  = h.entries.iterator.map((k, t) => width(k) + 1 + width(t))
        // ends(i): the width of the first i entries with their separators.
        val ends   = sizes.zipWithIndex
          .scanLeft(0) { case (sum, (w, i)) =>
            sum + (if i == 0 then 0 else SeparatorWidth) + w
          }
          .toArray
        val max    = area.width
        val shown  =
          if ends(n) <= max then n
          else
              (n - 1 to 1 by -1)
                .find(i => ends(i) + EllipsisWidth <= max)
                .getOrElse(1)
        val spans  = new java.util.ArrayList[JSpan](shown * 3 + 1)
        h.entries.iterator.take(shown).zipWithIndex.foreach {
          case ((k, t), i) =>
            if i > 0 then discard(spans.add(JSpan.styled(Separator, helpText)))
            discard(spans.add(JSpan.styled(k, helpKey)))
            discard(spans.add(JSpan.styled(" " + t, helpText)))
        }
        val cut    = shown < n && ends(shown) + EllipsisWidth <= max
        if cut then discard(spans.add(JSpan.styled(Ellipsis, helpText)))
        val used   = (ends(shown) + (if cut then EllipsisWidth else 0)).min(max)
        val offset = h.align match
            case Align.Left   => 0
            case Align.Center => (max - used) / 2
            case Align.Right  => max - used
        discard(
          buf.setLine(
            area.x + offset,
            area.y,
            JLine.fromSpans(spans),
            max - offset,
          ),
        )
    end help

    private def bordered(
        b: View.Bordered,
        area: Rect,
        buf: Buffer,
        pos: Long,
    ): Unit =
        val border = b.border
        val kind   = border.kind match
            case BorderKind.Plain   => BorderType.Plain
            case BorderKind.Rounded => BorderType.Rounded
            case BorderKind.Double  => BorderType.Double
            case BorderKind.Thick   => BorderType.Thick
        val lines  = Style.toJ(border.style)
        var block  = JBlock
          .bordered()
          .withBorderType(kind)
          .withBorderStyle(if b.focused then lines.patch(accent) else lines)
        if border.title.nonEmpty then
            val title = Style.toJ(border.titleStyle)
            block = block
              .withTitle(border.title)
              .withTitleStyle(if b.focused then accent.patch(title) else title)
        block.render(area, buf)
        walk(b.child, block.inner(area), buf, pos)
    end bordered
end Painter

private[tui4s] object Painter:
    /** A tagged area of the last frame. */
    private final class Hit(val area: Rect, val name: String):
        def contains(x: Int, y: Int): Boolean =
          x >= area.x && x < area.x + area.width &&
            y >= area.y && y < area.y + area.height

    private object ListTag
    private object TableTag

    private val HighlightSymbol  = "> "
    private val Separator        = " · "
    private val SeparatorWidth   = 3
    private val Ellipsis         = " …"
    private val EllipsisWidth    = 2
    private val HeaderStyle      = JStyle.empty().bold()
    private val PlaceholderStyle = JStyle.empty().dim()

    private def childPosition(parent: Long, index: Int): Long =
      parent * 31 + index + 1

    /** A position for `.id(name)`: negative, so it does not meet the positive
      * tree positions of a shallow tree.
      */
    private def idPosition(name: String): Long =
      -(name.hashCode.toLong & 0xffffffffL) - 2

    private def width(s: String): Int = JSpan.raw(s).width()

    private def selection(i: Int): Optional[Integer] =
      if i >= 0 then Optional.of(Integer.valueOf(i)) else Optional.empty()

    private def alignment(a: Align): HorizontalAlignment = a match
        case Align.Left   => HorizontalAlignment.Left
        case Align.Center => HorizontalAlignment.Center
        case Align.Right  => HorizontalAlignment.Right

    private def flexOf(f: Flex): JFlex = f match
        case Flex.Start        => JFlex.Start
        case Flex.Center       => JFlex.Center
        case Flex.End          => JFlex.End
        case Flex.SpaceBetween => JFlex.SpaceBetween
        case Flex.SpaceAround  => JFlex.SpaceAround

    private def constraint(c: Constraint): JConstraint = c match
        case Constraint.Length(n)   => JConstraint.Length(n)
        case Constraint.Fill(w)     => JConstraint.Fill(w)
        case Constraint.Percent(p)  => JConstraint.Percentage(p)
        case Constraint.Min(n)      => JConstraint.Min(n)
        case Constraint.Max(n)      => JConstraint.Max(n)
        case Constraint.Ratio(a, b) => JConstraint.Ratio(a, b)

    /** The size of a child: its `Sized` wrapper, else `Fill(1)`. */
    private def constraintOf(v: View): JConstraint = v match
        case View.Sized(_, c) => constraint(c)
        case _                => JConstraint.Fill(1)
end Painter
