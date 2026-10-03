package tui4s

/** An immutable description of a screen. Nodes are nested in `object View`; build them with the dsl. */
sealed abstract class View:
  // size inside the parent column/row (default fill(1)); order-independent with every decorator:
  // x.size(3).bordered("t") == x.bordered("t").size(3)  (decorators lift a Sized wrapper outside)
  def size(cells: Int): View          = sized(Constraint.Length(cells))
  def fill(weight: Int = 1): View     = sized(Constraint.Fill(weight))
  def percent(p: Int): View           = sized(Constraint.Percent(p))
  def ratio(num: Int, den: Int): View = sized(Constraint.Ratio(num, den))
  def atLeast(cells: Int): View       = sized(Constraint.Min(cells))
  def atMost(cells: Int): View        = sized(Constraint.Max(cells))

  // decoration
  def bordered(title: String = "", focused: Boolean = false): View =
    decorate(View.Bordered(_, Border(title), focused))
  def framed(border: Border): View                 = decorate(View.Bordered(_, border, false))
  def padded(horizontal: Int, vertical: Int = 0): View = decorate(View.Padded(_, horizontal, vertical))
  def style(s: Style): View                        = decorate(View.Styled(_, s))
  def popup(width: Int, height: Int): View         = decorate(View.Popup(_, width, height))

  // identity and interaction
  def id(name: String): View  = decorate(View.Identified(_, name))
  def tag(name: String): View = decorate(View.Tagged(_, name))

  private def sized(c: Constraint): View = this match
    case View.Sized(child, _) => View.Sized(child, c)
    case other                => View.Sized(other, c)

  private def decorate(f: View => View): View = this match
    case View.Sized(child, c) => View.Sized(f(child), c)
    case other                => f(other)
end View

object View:
  final case class Text private[tui4s] (lines: Seq[Line], align: Align, wraps: Boolean, scrollY: Int) extends View:
    def centered: Text              = copy(align = Align.Center)
    def right: Text                 = copy(align = Align.Right)
    def wrap(on: Boolean = true): Text = copy(wraps = on)
    def scroll(y: Int): Text        = copy(scrollY = y)

  final case class Stack private[tui4s] (vertical: Boolean, children: Seq[View], spacing: Int, justify: Flex)
      extends View:
    def gap(cells: Int): Stack = copy(spacing = cells) // Layout.withSpacing
    def flex(f: Flex): Stack   = copy(justify = f)     // Layout.withFlex

  final case class Layers private[tui4s] (children: Seq[View])                                  extends View
  final case class Items private[tui4s] (items: Seq[String | Line], selected: Int, focused: Boolean) extends View
  final case class Table private[tui4s] (
      header: Seq[String],
      rows: Seq[Seq[String | Line]],
      widths: Seq[Constraint],
      selected: Int,
      focused: Boolean
  ) extends View
  final case class Tabs private[tui4s] (titles: Seq[String], selected: Int)                          extends View
  final case class Gauge private[tui4s] (ratio: Double, label: String, color: Color, line: Boolean)   extends View
  final case class Sparkline private[tui4s] (values: Seq[Long], max: Option[Long], color: Color)     extends View
  final case class Input private[tui4s] (field: TextField, focused: Boolean, placeholder: String)    extends View
  final case class Log private[tui4s] (lines: Seq[String | Line])                                    extends View
  case object Clear                                                                                  extends View
  case object Empty                                                                                  extends View
  // escape hatches: equality is the jatatui widget's equals, or reference equality for functions
  final case class Widget private[tui4s] (widget: jatatui.core.widgets.Widget) extends View
  final case class Stateful private[tui4s] (
      fresh: () => Any,
      paint: (jatatui.core.layout.Rect, jatatui.core.buffer.Buffer, Any) => Unit
  ) extends View
  final case class Draw private[tui4s] (paint: (jatatui.core.layout.Rect, jatatui.core.buffer.Buffer) => Unit)
      extends View
  // wrappers built by the modifiers
  final case class Sized private[tui4s] (child: View, constraint: Constraint)                   extends View
  final case class Bordered private[tui4s] (child: View, border: Border, focused: Boolean)      extends View
  final case class Padded private[tui4s] (child: View, horizontal: Int, vertical: Int)          extends View
  final case class Styled private[tui4s] (child: View, style: Style)                            extends View
  final case class Popup private[tui4s] (child: View, width: Int, height: Int)                  extends View
  final case class Identified private[tui4s] (child: View, name: String)                        extends View
  final case class Tagged private[tui4s] (child: View, name: String)                            extends View
end View

enum Flex derives CanEqual:
  case Start, Center, End, SpaceBetween, SpaceAround

enum Align derives CanEqual:
  case Left, Center, Right

enum BorderKind derives CanEqual:
  case Plain, Rounded, Double, Thick

final case class Border(
    title: String = "",
    kind: BorderKind = BorderKind.Rounded,
    style: Style = Style.default,
    titleStyle: Style = Style.default
) derives CanEqual

enum Constraint derives CanEqual:
  case Length(cells: Int)
  case Fill(weight: Int)
  case Percent(p: Int)
  case Min(cells: Int)
  case Max(cells: Int)
  case Ratio(num: Int, den: Int)

final case class Theme(
    accent: Color = Color.Cyan,
    highlight: Style = Style.reversed,
    unfocusedHighlight: Style = Style.dim,
    helpKey: Style = Style.bold,
    helpText: Style = Style.dim
) derives CanEqual
