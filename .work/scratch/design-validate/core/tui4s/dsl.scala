package tui4s

/** View constructors and all extension methods. Exported by package `tui4s` (`import tui4s.*`). */
object dsl:
  // ---- text
  def text(parts: (String | Line.Span)*): View.Text =
    View.Text(Seq(Line(parts*)), Align.Left, wraps = false, scrollY = 0) // one line
  def paragraph(lines: (String | Line)*): View.Text =
    View.Text(lines.map(l => Line.fromJ(Line.lineOf(l))), Align.Left, wraps = true, scrollY = 0)
  def line(parts: (String | Line.Span)*): Line = Line(parts*)

  // ---- layout
  def column(children: View*): View.Stack = View.Stack(vertical = true, children, 0, Flex.Start)
  def row(children: View*): View.Stack    = View.Stack(vertical = false, children, 0, Flex.Start)
  def layers(children: View*): View       = View.Layers(children) // painted in order; later ones on top
  val empty: View                         = View.Empty
  val clear: View                         = View.Clear            // erases its area (under popups)

  // ---- widgets
  def list(items: Seq[String | Line], selected: Int, focused: Boolean = true): View =
    View.Items(items, selected, focused)
  def table(
      header: Seq[String],
      rows: Seq[Seq[String | Line]],
      widths: Seq[Constraint],
      selected: Int = -1,
      focused: Boolean = true
  ): View = View.Table(header, rows, widths, selected, focused)
  def tabs(titles: Seq[String], selected: Int): View = View.Tabs(titles, selected)
  def gauge(ratio: Double, label: String = "", color: Color = Color.Cyan): View =
    View.Gauge(ratio, label, color, line = false)
  def lineGauge(ratio: Double, label: String = "", color: Color = Color.Cyan): View =
    View.Gauge(ratio, label, color, line = true)
  def sparkline(values: Seq[Long], max: Option[Long] = None, color: Color = Color.Green): View =
    View.Sparkline(values, max, color)
  def input(field: TextField, focused: Boolean = true, placeholder: String = ""): View =
    View.Input(field, focused, placeholder)
  def logPane(lines: Seq[String | Line]): View = View.Log(lines) // newest lines that fit, bottom-aligned

  /** "+ increment · - decrement · q quit" */
  def help(keys: Keymap[?]): View.Text =
    val parts = keys.binds.filterNot(_.hidden).zipWithIndex.flatMap { (b, i) =>
      val sep: Seq[String | Line.Span] = if i == 0 then Nil else Seq(" · ")
      sep ++ Seq[String | Line.Span](
        Line.styled(b.keys.map(_.show).mkString("/"), Style.toJ(Style.bold)),
        Line.styled(" " + b.help, Style.toJ(Style.dim))
      )
    }
    View.Text(Seq(Line(parts*)), Align.Left, wraps = false, scrollY = 0)
  end help

  // ---- escape hatches (jatatui types on purpose)
  def widget(w: jatatui.core.widgets.Widget): View = View.Widget(w)
  def stateful[St](fresh: => St)(paint: (jatatui.core.layout.Rect, jatatui.core.buffer.Buffer, St) => Unit): View =
    View.Stateful(() => fresh, (r, b, s) => paint(r, b, s.asInstanceOf[St]))
  def draw(paint: (jatatui.core.layout.Rect, jatatui.core.buffer.Buffer) => Unit): View = View.Draw(paint)

  // ---- transitions
  extension [S](state: S)
    /** `state`, plus one-shot tasks that start after this transition (2.13 rule 9). */
    def withTask(tasks: Source[S]*): Next[S] = Next.Then(state, tasks, quit = false)

  // ---- styling: each returns a Line.Span; the same names exist on Line.Span, so calls chain
  export Line.SpanOps.*
  export Style.StyleOps.*
end dsl

export dsl.*
