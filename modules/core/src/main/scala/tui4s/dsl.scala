package tui4s

import _root_.jatatui.core.buffer.Buffer as JBuffer
import _root_.jatatui.core.layout.Rect as JRect
import _root_.jatatui.core.widgets.Widget as JWidget
import _root_.scala.reflect.ClassTag

/** The view constructors and all extension methods. Package `tui4s` exports
  * this object, so `import tui4s.*` brings everything in.
  */
object dsl:
    // ---- text

    /** One line of text from strings and spans:
      * `text("Count: ", n.toString.bold)`. Long lines are cut. A line break or
      * a tab in a string becomes a space.
      */
    def text(parts: (String | Line.Span)*): View.Text =
      View.Text(Seq(Line(parts*)), Align.Left, wraps = false, scrollY = 0)

    /** Several lines: each argument starts a new line, and so does a line break
      * ('\n' or "\r\n") in a string (one at the end adds no line). Long lines
      * wrap at word boundaries (`.wrap(false)` cuts them).
      */
    def paragraph(lines: (String | Line)*): View.Text =
      View.Text(
        lines.flatMap(Line.linesOf),
        Align.Left,
        wraps = true,
        scrollY = 0,
      )

    /** A line of strings and spans, for `paragraph`, `list` and `logPane`. A
      * line break or a tab in a string becomes a space.
      */
    def line(parts: (String | Line.Span)*): Line = Line(parts*)

    // ---- layout

    /** Children from top to bottom. */
    def column(children: View*): View.Stack =
      View.Stack(vertical = true, children, 0, Flex.Start)

    /** Children from left to right. */
    def row(children: View*): View.Stack =
      View.Stack(vertical = false, children, 0, Flex.Start)

    /** Children painted in order on the same area; later ones on top (for
      * popups).
      */
    def layers(children: View*): View = View.Layers(children)

    /** Paints nothing. */
    val empty: View = View.Empty

    /** Erases its area (put it under a popup). */
    val clear: View = View.Clear

    // ---- widgets

    /** A selectable list, one line per item. The runtime keeps the scroll
      * offset, so moving the selection moves the highlight, not the page.
      *
      * @param selected
      *   the highlighted item; `-1`: none
      * @param focused
      *   false: a dimmer highlight
      */
    def list(
        items: Seq[String | Line],
        selected: Int,
        focused: Boolean = true,
    ): View =
      View.Items(items, selected, focused)

    /** A table with a header row, one line per cell.
      *
      * @param header
      *   the column titles; empty: no header row
      * @param widths
      *   one constraint per column; empty: equal columns
      * @param selected
      *   the highlighted row; `-1`: none
      */
    def table(
        header: Seq[String],
        rows: Seq[Seq[String | Line]],
        widths: Seq[Constraint],
        selected: Int = -1,
        focused: Boolean = true,
    ): View = View.Table(header, rows, widths, selected, focused)

    /** Tab titles; the selected one in the accent color. */
    def tabs(titles: Seq[String], selected: Int): View =
      View.Tabs(titles, selected)

    /** A block progress bar. `ratio` is clamped to 0 to 1. An empty label shows
      * the percentage.
      */
    def gauge(
        ratio: Double,
        label: String = "",
        color: Color = Color.Cyan,
    ): View =
      View.Gauge(clamp(ratio), label, color, line = false)

    /** A one-row progress line. `ratio` is clamped to 0 to 1. */
    def lineGauge(
        ratio: Double,
        label: String = "",
        color: Color = Color.Cyan,
    ): View =
      View.Gauge(clamp(ratio), label, color, line = true)

    /** A bar chart of `values` (oldest first; the newest show when they do not
      * all fit).
      *
      * @param max
      *   the value of a full bar; `None`: the largest value
      */
    def sparkline(
        values: Seq[Long],
        max: Option[Long] = None,
        color: Color = Color.Green,
    ): View =
      View.Sparkline(values, max, color)

    /** A text field. When focused, the terminal cursor shows at the field's
      * cursor.
      *
      * @param placeholder
      *   the dim text shown while the field is empty
      */
    def input(
        field: TextField,
        focused: Boolean = true,
        placeholder: String = "",
    ): View =
      View.Input(field, focused, placeholder)

    /** The newest lines that fit, bottom-aligned. A line break in a string
      * starts a new row, as in `paragraph`.
      */
    def logPane(lines: Seq[String | Line]): View = View.Log(lines)

    /** The help bar of a keymap: "+ increment · - decrement · q quit". Hidden
      * bindings are left out.
      */
    def help(keys: Keymap[?]): View.Help =
      View.Help(keys.helpEntries, Align.Left)

    // ---- escape hatches (jatatui types on purpose)

    /** A jatatui widget, painted as it is. */
    def widget(w: JWidget): View = View.Widget(w)

    /** A paint function with a state of its own (for example a jatatui
      * `ScrollbarState`). The runtime makes the state with `fresh` the first
      * time and keeps it while this node is painted at the same tree position,
      * with a paint function of the same class and a state of the same class. A
      * generic helper that calls `stateful` needs a `ClassTag` for its state
      * type too: `def wrap[St: ClassTag](...)`.
      */
    def stateful[St: ClassTag](fresh: => St)(
        paint: (JRect, JBuffer, St) => Unit,
    ): View =
      // No wrapper lambda: the class of `paint` and the class of the state
      // tell two stateful nodes at one position apart, so a kept state never
      // reaches a paint for another state type (a generic helper has one
      // lambda class for every state type).
      View.Stateful(
        () => fresh,
        paint.asInstanceOf[(JRect, JBuffer, Any) => Unit],
        summon[ClassTag[St]].runtimeClass,
      )

    /** A paint function on the area and the buffer. */
    def draw(paint: (JRect, JBuffer) => Unit): View = View.Draw(paint)

    // ---- transitions

    extension [S](state: S)
        /** `state`, plus one-shot tasks that start after this transition, in
          * order. Tasks returned together with a quit do not start.
          */
        def withTask(tasks: Source[S]*): Next[S] =
          Next.Then(state, tasks, quit = false)

    // ---- styling: each returns a Line.Span (or a Style); the same names
    // exist on Line.Span and Style, so calls chain: "x".bold.red

    export Line.SpanOps.*
    export Style.StyleOps.*

    private def clamp(ratio: Double): Double =
      if ratio.isNaN then 0.0 else ratio.max(0.0).min(1.0)
end dsl
