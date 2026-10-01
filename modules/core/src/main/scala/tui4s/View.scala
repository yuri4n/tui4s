package tui4s

import _root_.jatatui.core.buffer.Buffer as JBuffer
import _root_.jatatui.core.layout.Rect as JRect
import _root_.jatatui.core.widgets.Widget as JWidget

/** An immutable description of a screen: a tree of plain data.
  *
  * Build views with the functions of [[dsl]] (`text`, `column`, `list`, ...).
  * The nodes are nested in the companion object; their constructors are
  * private, so the modifiers below are the only way to wrap a view.
  *
  * Two views compare with `==`. The runtime draws a new frame only when the
  * view changed, so equality must be exact: every node holds plain values. The
  * escape hatches (`widget`, `stateful`, `draw`) compare by the jatatui
  * widget's `equals` or by function reference.
  *
  * Sizes and decorations are order-independent:
  * `x.size(3).bordered("t") == x.bordered("t").size(3)`. A decoration lifts the
  * size outside, so the size always applies to the decorated view.
  */
sealed abstract class View derives CanEqual:
    /** Exactly `cells` rows in a column, or columns in a row. */
    def size(cells: Int): View = sized(Constraint.Length(cells))

    /** A share of the free space, by `weight` (the default for every child). */
    def fill(weight: Int = 1): View = sized(Constraint.Fill(weight))

    /** `p` percent of the parent. */
    def percent(p: Int): View = sized(Constraint.Percent(p))

    /** `num / den` of the parent. */
    def ratio(num: Int, den: Int): View = sized(Constraint.Ratio(num, den))

    /** At least `cells`; more when there is free space. */
    def atLeast(cells: Int): View = sized(Constraint.Min(cells))

    /** At most `cells`. */
    def atMost(cells: Int): View = sized(Constraint.Max(cells))

    /** A rounded border with an optional title. A focused border uses the
      * accent color of the theme.
      */
    def bordered(title: String = "", focused: Boolean = false): View =
      decorate(View.Bordered(_, Border(title), focused))

    /** A border described by `border` (kind, styles, title). */
    def framed(border: Border): View =
      decorate(View.Bordered(_, border, focused = false))

    /** Empty space around the view: `horizontal` columns left and right,
      * `vertical` rows above and below.
      */
    def padded(horizontal: Int, vertical: Int = 0): View =
      decorate(View.Padded(_, horizontal.max(0), vertical.max(0)))

    /** The base style of the area, set before the content paints. */
    def style(s: Style): View = decorate(View.Styled(_, s))

    /** The view in a `width` x `height` box in the middle of the area. The box
      * is cleared first. Put popups last in `layers(...)`.
      */
    def popup(width: Int, height: Int): View =
      decorate(View.Popup(_, width.max(0), height.max(0)))

    /** The identity of the widget state that the runtime keeps for this subtree
      * (the scroll offset of a list or a table). Without an id, the identity is
      * the position in the tree, so a view that appears before a list resets
      * its scroll offset; an id prevents that. Use each name once per frame:
      * when two subtrees use the same name, each keeps its own state, matched
      * by paint order.
      */
    def id(name: String): View = decorate(View.Identified(_, name))

    /** While the pointer is over this area, [[Mouse.tags]] contains `name`. */
    def tag(name: String): View = decorate(View.Tagged(_, name))

    private def sized(c: Constraint): View = this match
        case View.Sized(child, _) => View.Sized(child, c)
        case other                => View.Sized(other, c)

    private def decorate(wrap: View => View): View = this match
        case View.Sized(child, c) => View.Sized(wrap(child), c)
        case other                => wrap(other)
end View

/** The view nodes. Build them with the functions of [[dsl]]. */
object View:
    /** Lines of text. Built by `text(...)` and `paragraph(...)`.
      *
      * @param lines
      *   the lines
      * @param align
      *   the horizontal alignment of every line
      * @param wraps
      *   true: long lines wrap at word boundaries; false: they are cut
      * @param scrollY
      *   the number of rows scrolled off the top
      */
    final case class Text private[tui4s] (
        lines: Seq[Line],
        align: Align,
        wraps: Boolean,
        scrollY: Int,
    ) extends View:
        /** The lines centered. */
        def centered: Text = copy(align = Align.Center)

        /** The lines aligned to the right. */
        def right: Text = copy(align = Align.Right)

        /** Long lines wrap (`true`) or are cut (`false`). */
        def wrap(on: Boolean = true): Text = copy(wraps = on)

        /** The text scrolled by `y` rows (0 or more). */
        def scroll(y: Int): Text = copy(scrollY = y.max(0))
    end Text

    /** Children side by side: top to bottom (`column`) or left to right
      * (`row`). Each child takes its size from its modifier (`.size(3)`,
      * `.percent(40)`, ...); the default is `fill(1)`.
      *
      * @param vertical
      *   true for a column, false for a row
      * @param children
      *   the children, in order
      * @param spacing
      *   empty cells between two children
      * @param justify
      *   where the children go when they do not fill the space
      */
    final case class Stack private[tui4s] (
        vertical: Boolean,
        children: Seq[View],
        spacing: Int,
        justify: Flex,
    ) extends View:
        /** `cells` empty cells between two children. */
        def gap(cells: Int): Stack = copy(spacing = cells.max(0))

        /** Where the children go when they do not fill the space. */
        def flex(f: Flex): Stack = copy(justify = f)
    end Stack

    /** Children painted in order on the same area; later ones on top. */
    final case class Layers private[tui4s] (children: Seq[View]) extends View

    /** A selectable list. The runtime keeps its scroll offset (see
      * [[View.id]]).
      *
      * @param items
      *   the rows
      * @param selected
      *   the highlighted row; `-1`: none
      * @param focused
      *   true: the theme's highlight; false: its unfocused highlight
      */
    final case class Items private[tui4s] (
        items: Seq[String | Line],
        selected: Int,
        focused: Boolean,
    ) extends View

    /** A table with a header row. The runtime keeps its scroll offset.
      *
      * @param header
      *   the column titles; empty: no header row
      * @param rows
      *   the rows, one cell per column
      * @param widths
      *   one constraint per column
      * @param selected
      *   the highlighted row; `-1`: none
      * @param focused
      *   true: the theme's highlight; false: its unfocused highlight
      */
    final case class Table private[tui4s] (
        header: Seq[String],
        rows: Seq[Seq[String | Line]],
        widths: Seq[Constraint],
        selected: Int,
        focused: Boolean,
    ) extends View

    /** Tab titles with one selected tab (in the accent color). */
    final case class Tabs private[tui4s] (titles: Seq[String], selected: Int)
        extends View

    /** A progress bar.
      *
      * @param ratio
      *   the filled part, 0 to 1 (clamped; NaN reads as 0)
      * @param label
      *   the text in the bar; empty: the percentage
      * @param color
      *   the color of the filled part
      * @param line
      *   true: a one-row line gauge; false: a block gauge
      */
    final case class Gauge private[tui4s] (
        ratio: Double,
        label: String,
        color: Color,
        line: Boolean,
    ) extends View

    /** A bar chart of values, one column per value. When there are more values
      * than columns, the newest (last) values show.
      *
      * @param values
      *   the values, oldest first
      * @param max
      *   the value of a full bar; `None`: the largest value
      * @param color
      *   the color of the bars
      */
    final case class Sparkline private[tui4s] (
        values: Seq[Long],
        max: Option[Long],
        color: Color,
    ) extends View

    /** A text field. When focused, the terminal cursor shows at the field's
      * cursor; a text longer than the area scrolls to keep the cursor visible.
      *
      * @param field
      *   the value
      * @param focused
      *   true: show the cursor
      * @param placeholder
      *   the dim text shown while the field is empty
      */
    final case class Input private[tui4s] (
        field: TextField,
        focused: Boolean,
        placeholder: String,
    ) extends View

    /** The newest lines that fit, bottom-aligned, cut at the right edge. A line
      * break in a string starts a new row.
      */
    final case class Log private[tui4s] (lines: Seq[String | Line]) extends View

    /** A help bar: "+ increment · - decrement · q quit", in the theme's help
      * styles. When the bar is wider than the area, the entries that do not fit
      * are left out and " …" ends the bar. When not even the first entry fits
      * with " …", the first entry is cut and "…" ends it. So a bar that does
      * not show every entry always ends with "…".
      *
      * @param entries
      *   (keys, help) pairs, in order
      * @param align
      *   the horizontal alignment of the bar
      */
    final case class Help private[tui4s] (
        entries: Seq[(String, String)],
        align: Align,
    ) extends View:
        /** The bar centered. */
        def centered: Help = copy(align = Align.Center)

        /** The bar aligned to the right. */
        def right: Help = copy(align = Align.Right)
    end Help

    /** Erases its area (put it under a popup). */
    case object Clear extends View

    /** Paints nothing. */
    case object Empty extends View

    /** Escape hatch: a jatatui widget, painted as it is. Equality is the
      * widget's `equals` (reference equality for some jatatui widgets, so the
      * frame is redrawn whenever the view is rebuilt).
      */
    final case class Widget private[tui4s] (
        widget: JWidget,
    ) extends View

    /** Escape hatch: a paint function with a state that the runtime keeps while
      * the node is painted at the same tree position. Equality is by function
      * reference.
      *
      * @param fresh
      *   makes the state the first time
      * @param paint
      *   paints the area with the kept state
      * @param kind
      *   the class of the state; a kept state goes only to a node with the same
      *   paint class and the same state class
      */
    final case class Stateful private[tui4s] (
        fresh: () => Any,
        paint: (JRect, JBuffer, Any) => Unit,
        kind: Class[?],
    ) extends View

    /** Escape hatch: a paint function. Equality is by function reference. */
    final case class Draw private[tui4s] (paint: (JRect, JBuffer) => Unit)
        extends View

    /** `child` with its size in the parent column or row. */
    final case class Sized private[tui4s] (child: View, constraint: Constraint)
        extends View

    /** `child` inside a border. */
    final case class Bordered private[tui4s] (
        child: View,
        border: Border,
        focused: Boolean,
    ) extends View

    /** `child` with empty space around it. */
    final case class Padded private[tui4s] (
        child: View,
        horizontal: Int,
        vertical: Int,
    ) extends View

    /** `child` on an area with the base style `style`. */
    final case class Styled private[tui4s] (child: View, style: Style)
        extends View

    /** `child` in a centered, cleared box. */
    final case class Popup private[tui4s] (child: View, width: Int, height: Int)
        extends View

    /** `child` with the widget-state identity `name`. */
    final case class Identified private[tui4s] (child: View, name: String)
        extends View

    /** `child`, reported in [[Mouse.tags]] under the pointer. */
    final case class Tagged private[tui4s] (child: View, name: String)
        extends View
end View

/** Where the children of a [[View.Stack]] go when they do not fill it. */
enum Flex derives CanEqual:
    /** At the start; the free space at the end. */
    case Start

    /** In the middle. */
    case Center

    /** At the end. */
    case End

    /** The free space between the children. */
    case SpaceBetween

    /** The free space around the children. */
    case SpaceAround
end Flex

/** The horizontal alignment of text. */
enum Align derives CanEqual:
    case Left, Center, Right

/** The line style of a border. */
enum BorderKind derives CanEqual:
    case Plain, Rounded, Double, Thick

/** A border.
  *
  * @param title
  *   the title on the top line; empty: no title
  * @param kind
  *   the line style
  * @param style
  *   the style of the border lines
  * @param titleStyle
  *   the style of the title
  */
final case class Border(
    title: String = "",
    kind: BorderKind = BorderKind.Rounded,
    style: Style = Style.default,
    titleStyle: Style = Style.default,
) derives CanEqual

/** The size of a child in a column or a row. */
enum Constraint derives CanEqual:
    /** Exactly `cells`. */
    case Length(cells: Int)

    /** A share of the free space, by weight. */
    case Fill(weight: Int)

    /** `p` percent of the parent. */
    case Percent(p: Int)

    /** At least `cells`. */
    case Min(cells: Int)

    /** At most `cells`. */
    case Max(cells: Int)

    /** `num / den` of the parent. */
    case Ratio(num: Int, den: Int)
end Constraint

/** The colors and styles that the painter uses for focus, selection and help.
  *
  * @param accent
  *   focused borders and the selected tab
  * @param highlight
  *   the selected row of a focused list or table
  * @param unfocusedHighlight
  *   the selected row of a list or table without the focus
  * @param helpKey
  *   the keys in a help bar
  * @param helpText
  *   the help text and separators in a help bar
  */
final case class Theme(
    accent: Color = Color.Cyan,
    highlight: Style = Style.reversed,
    unfocusedHighlight: Style = Style.dim,
    helpKey: Style = Style.bold,
    helpText: Style = Style.dim,
) derives CanEqual
