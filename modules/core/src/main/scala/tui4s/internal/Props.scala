package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import jatatui.core.style.{Color as JColor, Modifier, Style as JStyle}
import kyo.*
import kyo.Style.{
  Alignment,
  BorderStyle,
  FlexDirection,
  FontStyle,
  FontWeight,
  Justification,
  ListStyle,
  Overflow,
  Prop,
  ScrollbarWidth,
  TextAlign,
  TextDecoration,
  TextOverflow,
  TextTransform,
  TextWrap,
  WhiteSpace,
}
import kyo.UI.Ast.*

private[tui4s] enum Dir:
    case Row, Column

/** What the wrap does with a word that is wider than its line (CSS
  * `overflow-wrap`): `Split` splits it by cells (`break-word`, the default of
  * the runner); `Keep` keeps it whole, so it overflows its line (`normal`).
  */
// claude 2026-10-19: created (L1a): an ADT, not a Boolean
private[tui4s] enum WordBreak derives CanEqual:
    case Split, Keep

/** Text attributes. Children inherit them, like CSS `color`, `font-weight`,
  * `text-decoration`, `text-align`, `white-space` and `overflow-wrap`.
  * `whiteSpace` is none when no element sets it: the runner keeps the spaces
  * and the line breaks of a text, as `pre-wrap` does.
  */
// claude 2026-10-19: changed: white-space and overflow-wrap (L1a)
private[tui4s] final case class TextAttrs(
    fg: Option[JColor],
    bold: Boolean,
    italic: Boolean,
    underline: Boolean,
    strike: Boolean,
    dim: Boolean,
    reverse: Boolean,
    transform: TextTransform,
    align: TextAlign,
    whiteSpace: Option[WhiteSpace] = None,
    breaks: WordBreak = WordBreak.Split,
):
    def style: JStyle =
        val mods = List(
          bold      -> Modifier.BOLD,
          italic    -> Modifier.ITALIC,
          underline -> Modifier.UNDERLINED,
          strike    -> Modifier.CROSSED_OUT,
          dim       -> Modifier.DIM,
          reverse   -> Modifier.REVERSED,
        ).collect { case (true, m) => m }.foldLeft(Modifier.EMPTY)(_.insert(_))
        val base = JStyle.empty().withAddModifier(mods)
        fg.fold(base)(base.withFg)
    end style

    def apply(text: String): String = transform match
        case TextTransform.uppercase  => text.toUpperCase
        case TextTransform.lowercase  => text.toLowerCase
        case TextTransform.capitalize =>
          text.split(" ", -1).map(_.capitalize).mkString(" ")
        case TextTransform.none       => text
end TextAttrs

private[tui4s] object TextAttrs:
    val default: TextAttrs =
      TextAttrs(
        None,
        false,
        false,
        false,
        false,
        false,
        false,
        TextTransform.none,
        TextAlign.left,
      )
end TextAttrs

/** The box-drawing characters of a border. */
private[tui4s] final case class Glyphs(
    tl: String,
    tr: String,
    bl: String,
    br: String,
    h: String,
    v: String,
)

private[tui4s] object Glyphs:
    val solid: Glyphs   = Glyphs("┌", "┐", "└", "┘", "─", "│")
    val rounded: Glyphs = Glyphs("╭", "╮", "╰", "╯", "─", "│")
    val heavy: Glyphs   = Glyphs("┏", "┓", "┗", "┛", "━", "┃")
    val dashed: Glyphs  = Glyphs("┌", "┐", "└", "┘", "╌", "╎")
    val dotted: Glyphs  = Glyphs("┌", "┐", "└", "┘", "┈", "┊")

private[tui4s] final case class Border(
    sides: Sides,
    glyphs: Glyphs,
    fg: Option[JColor],
)

/** The resolved layout and paint properties of one element in the terminal.
  *
  * The overflow and text-flow properties (the audit of 2026-10-19, kyo-ui
  * 1.0.0-RC7; the DOM runner `CssStyleRenderer` gives the CSS):
  *   - `overflow`, `overflowX`, `overflowY` (`Overflow`: visible, hidden,
  *     scroll, auto; CSS `overflow`, `overflow-x`, `overflow-y`): an element
  *     with an axis that is not `visible` is a viewport
  *     ([[Layout.arrangeKids]], [[Scroll]]). `hidden` clips, `scroll` and
  *     `auto` also scroll (the keys, the follow-focus rule). A viewport that
  *     can scroll now and holds no focusable element is a tab stop
  *     ([[Layout.isFocusable]]). `visible` keeps the old rule: each box clips
  *     its content to its content box. Each axis keeps its own value (CSS
  *     computes `visible` next to a non-visible axis to `auto`; the runner does
  *     not). A viewport whose content is taller than its port, with at least 3
  *     rows, shows a scroll indicator in its right column
  *     (`scrollbarWidth(none)` hides it, `scrollbarColor` gives its colours).
  *   - `whiteSpace` (`WhiteSpace`: normal, noWrap, pre, preWrap, preLine; CSS
  *     `white-space`, inherited): see [[TextFlow]]. `normal` and `noWrap`
  *     collapse each run of white space (line breaks too) to one space;
  *     `preLine` keeps the line breaks; `pre` and `preWrap` keep both. `noWrap`
  *     and `pre` never wrap. With no `whiteSpace`, a text keeps its spaces and
  *     line breaks (the old rule). A text wraps in a column, and in a row whose
  *     element has a definite `width` (CSS wraps a text in any box of a
  *     definite width). The row wrap changes a default: the old layout never
  *     wrapped a text in a row.
  *   - `textWrap` (`TextWrap`: wrap, noWrap, ellipsis, balance, pretty; CSS
  *     `overflow-wrap: break-word`, `overflow-wrap: normal`,
  *     `overflow-wrap: normal; text-overflow: ellipsis`, `text-wrap: balance`,
  *     `text-wrap: pretty`, inherited): `wrap` (the default) splits a word
  *     wider than its line by cells; `noWrap` keeps it whole (it overflows);
  *     `ellipsis` keeps it whole and sets `textOverflow: ellipsis`. `balance`
  *     and `pretty` wrap as `wrap` does (a greedy wrap).
  *   - `textOverflow` (`TextOverflow`: clip, ellipsis; CSS `text-overflow`, not
  *     inherited): `ellipsis` on an element with `overflowX: hidden` paints "…"
  *     (1 cell) at the cut of each line of its texts that is wider than the
  *     text box; the cut is by cells and never splits a wide glyph. `clip` (the
  *     default) clips by cells. Only the texts of that element itself get the
  *     "…" (its direct text children). CSS also cuts a text inside a nested
  *     inline span, and with `overflow` scroll or auto; the runner does not.
  *   - The text measure counts cells: `Layout.textWidth` is jatatui's
  *     `Span.width` (2 cells for a wide character or an emoji), the wrap splits
  *     a long word by cells, the painter cuts a text into `Glyph`s, a text
  *     input scrolls and puts its caret by cells, the label of a select or a
  *     dropdown fits by cells (`Paint.fit`), and the ellipsis never splits a
  *     wide glyph.
  */
// claude 2026-10-19: changed: the audit of the overflow and text-flow
// properties (L1a; the tab stop, the row wrap, the direct texts: L1a review)
private[tui4s] final case class Props(
    dir: Dir,
    /** `rowReverse` or `columnReverse`: the items go from the end. */
    reverse: Boolean, /* claude 2026-10-12: created (PR #10 review: Codex,
     * Props.scala:303) */
    gap: Int,
    padding: Sides,
    margin: Sides,
    border: Option[Border],
    width: Option[Length],
    height: Option[Length],
    minW: Option[Length],
    maxW: Option[Length],
    minH: Option[Length],
    maxH: Option[Length],
    grow: Double,
    shrink: Double,
    align: Alignment,
    alignSelf: Option[Alignment],
    justify: Justification,
    bg: Option[JColor],
    text: TextAttrs,
    hidden: Boolean,
    listStyle: Option[ListStyle],
    decoration: Sides,
    /** The overflow of the horizontal and of the vertical axis (`overflow`,
      * `overflowX`, `overflowY`). An element with an axis that is not `visible`
      * is a viewport (see [[Layout]]).
      */
    // claude 2026-10-19: created (L1a): the overflow of each axis
    overflowX: Overflow = Overflow.visible,
    overflowY: Overflow = Overflow.visible,
    /** `scrollbarWidth`: `none` hides the scroll indicator (the viewport still
      * scrolls); `auto` and `thin` give one column.
      */
    // claude 2026-10-19: created the two fields below (L1a): the indicator
    scrollbarWidth: ScrollbarWidth = ScrollbarWidth.auto,
    /** `scrollbarColor(thumb, track)`: the colours of the indicator. */
    scrollbarColor: Option[(Option[JColor], Option[JColor])] = None,
    /** `textOverflow` (not inherited, as in CSS): `ellipsis` marks the cut of a
      * line that overflows an element with `overflow: hidden`.
      */
    // claude 2026-10-19: created (L1a)
    textOverflow: TextOverflow = TextOverflow.clip,
):
    def insets: Sides = border.fold(Sides.zero)(_.sides) + padding + decoration

    /** True when an axis is not `visible`: the element is a viewport. */
    // claude 2026-10-19: created (L1a)
    def clips: Boolean =
      overflowX != Overflow.visible || overflowY != Overflow.visible
end Props

private[tui4s] object Props:

    /** The props of a text node or of the screen: no box, inherited text. */
    def neutral(text: TextAttrs): Props =
      Props(
        Dir.Column,
        false, // claude 2026-10-12: created (reverse)
        0,
        Sides.zero,
        Sides.zero,
        None,
        None,
        None,
        None,
        None,
        None,
        None,
        0,
        1,
        Alignment.stretch,
        None,
        Justification.start,
        None,
        text,
        false,
        None,
        Sides.zero,
      )

    /** One cell per pixel: `padding(1.px)` is one cell. `Pct` is relative to
      * the parent size, `Auto` and `Calc` are absent.
      */
    def cells(l: Length, parent: Int, screen: Int): Option[Int] = l match
        case Length.Px(v)   => Some(math.round(v).toInt)
        case Length.Em(v)   => Some(math.round(v).toInt)
        case Length.Pct(v)  => Some((v * parent / 100).toInt)
        case Length.Vh(v)   => Some((v * screen / 100).toInt)
        case Length.Calc(_) => None
        case Length.Auto    => None

    private def side(l: Length): Int = cells(l, 0, 0).getOrElse(0).max(0)

    def color(c: kyo.Style.Color): Option[JColor] = c match
        case h: kyo.Style.Color.Hex      => hex(h.value)
        case r: kyo.Style.Color.Rgb      => Some(JColor.Rgb(r.r, r.g, r.b))
        case r: kyo.Style.Color.Rgba     =>
          if r.a < 0.5 then None else Some(JColor.Rgb(r.r, r.g, r.b))
        case kyo.Style.Color.Transparent => None
        case _                           => None

    private def hex(v: String): Option[JColor] =
        val d                    = v.stripPrefix("#")
        def byte(s: String): Int = Integer.parseInt(s, 16)
        d.length match
            case 3 | 4 =>
              Some(
                JColor.Rgb(
                  byte(d.substring(0, 1) * 2),
                  byte(d.substring(1, 2) * 2),
                  byte(d.substring(2, 3) * 2),
                ),
              )
            case 6 | 8 =>
              Some(
                JColor.Rgb(
                  byte(d.substring(0, 2)),
                  byte(d.substring(2, 4)),
                  byte(d.substring(4, 6)),
                ),
              )
            case _     => None
        end match
    end hex

    def defaultDir(el: Element): Dir = el match
        case _: Nav | _: Li | _: SpanElement | _: Button | _: Anchor | _: Tr =>
          Dir.Row
        case _                                                               => Dir.Column

    def isDisabled(el: Element): Boolean = el match
        case hd: HasDisabled => hd.disabled.getOrElse(false)
        case _               => false

    def isHidden(el: Element): Boolean =
      el.attrs.hidden.getOrElse(false) || el.attrs.uiStyle.props.exists {
        case Prop.HiddenProp => true
        case _               => false
      } || (el match
          case _: HiddenInput => true
          case _              => false)

    /** Focusable in the terminal from the element alone: kyo-ui `Focusable`
      * elements, anchors, dropdowns, and any element with `tabIndex >= 0`. A
      * viewport can be a tab stop too, but only its layout tells
      * ([[Layout.isFocusable]]).
      */
    // claude 2026-10-19: changed: a viewport is a tab stop by its layout, not
    // by its style (L1a review)
    def isFocusable(el: Element): Boolean =
      !isDisabled(el) && !isHidden(el) && (el.attrs.tabIndex match
          case Present(n) => n >= 0
          case Absent     =>
            el match
                case _: Focusable | _: Anchor | _: Dropdown => true
                case _                                      => false)

    /** True when `o` lets the user scroll the axis: `scroll` or `auto`. */
    // claude 2026-10-19: created (L1a)
    def scrolls(o: Overflow): Boolean = o match
        case Overflow.scroll | Overflow.auto => true
        case _                               => false

    /** Elements that show the focus themselves (reverse video); other
      * focusables rely on `Style.focus(...)` or a heavy border.
      */
    def nativeFocus(el: Element): Boolean = el match
        case _: Button | _: Checkbox | _: Radio | _: Select | _: Anchor |
            _: Dropdown =>
          true
        case _ => false

    def resolve(
        el: Element,
        inherited: TextAttrs,
        focused: Boolean,
        listStyle: Option[ListStyle],
        index: Int,
    ): Props =
        val base0         = neutral(typeText(el, inherited)).copy(
          dir = defaultDir(el),
          align = defaultDir(el) match
              case Dir.Row    => Alignment.center
              case Dir.Column => Alignment.stretch,
        )
        val base          = el match
            case _: Button =>
              base0.copy(decoration = Sides.lr(2, 2), shrink = 0)
            case _: Li     =>
              listStyle match
                  case Some(ListStyle.disc)    =>
                    base0.copy(decoration = Sides.lr(2, 0))
                  case Some(ListStyle.decimal) =>
                    // claude 2026-10-19: changed: the marker in cells (L1a)
                    base0.copy(decoration =
                      Sides.lr(Layout.textWidth(s"${index + 1}. "), 0),
                    )
                  case _                       => base0
            case _         => base0
        val withStyle     = applyStyle(base, el.attrs.uiStyle)
        val pseudo        = el.attrs.uiStyle.props.foldLeft(withStyle) {
          case (p, Prop.FocusProp(s)) if focused           => applyStyle(p, s)
          case (p, Prop.DisabledProp(s)) if isDisabled(el) => applyStyle(p, s)
          case (p, _)                                      => p
        }
        val hasFocusStyle = el.attrs.uiStyle.props.exists {
          case _: Prop.FocusProp => true
          case _                 => false
        }
        val focusDefault  =
          if !focused || hasFocusStyle then pseudo
          else if nativeFocus(el) then
              pseudo.copy(text = pseudo.text.copy(reverse = true, bold = true))
          else
              pseudo.border.fold(pseudo)(b =>
                pseudo.copy(border = Some(b.copy(glyphs = Glyphs.heavy))),
              )
        if isDisabled(el) then
            focusDefault.copy(text = focusDefault.text.copy(dim = true))
        else focusDefault
    end resolve

    private def typeText(el: Element, t: TextAttrs): TextAttrs = el match
        case _: H1     => t.copy(bold = true, underline = true)
        case _: H2     => t.copy(bold = true)
        case _: H3     => t.copy(bold = true, italic = true)
        case _: H4     => t.copy(bold = true)
        case _: H5     => t.copy(bold = true)
        case _: H6     => t.copy(bold = true)
        case _: Th     => t.copy(bold = true)
        case _: Anchor => t.copy(underline = true)
        case _         => t

    def applyStyle(p: Props, s: kyo.Style): Props =
      s.props.foldLeft(p) { (p, prop) =>
        prop match
            case Prop.BgColor(c)                   => p.copy(bg = color(c))
            case Prop.TextColor(c)                 => p.copy(text = p.text.copy(fg = color(c)))
            case Prop.Padding(t, r, b, l)          =>
              p.copy(padding = Sides(side(t), side(r), side(b), side(l)))
            case Prop.Margin(t, r, b, l)           =>
              p.copy(margin = Sides(side(t), side(r), side(b), side(l)))
            case Prop.Gap(v)                       => p.copy(gap = side(v))
            case Prop.FlexDirectionProp(d)         =>
              /* claude 2026-10-12: changed (PR #10 review: Codex,
               * Props.scala:303): the */
              // reverse variants keep their flag
              d match
                  case FlexDirection.row           =>
                    p.copy(dir = Dir.Row, reverse = false)
                  case FlexDirection.rowReverse    =>
                    p.copy(dir = Dir.Row, reverse = true)
                  case FlexDirection.column        =>
                    p.copy(dir = Dir.Column, reverse = false)
                  case FlexDirection.columnReverse =>
                    p.copy(dir = Dir.Column, reverse = true)
            case Prop.Align(a)                     => p.copy(align = a)
            case Prop.AlignSelf(a)                 => p.copy(alignSelf = Some(a))
            case Prop.Justify(j)                   => p.copy(justify = j)
            case Prop.Width(v)                     => p.copy(width = Some(v))
            case Prop.Height(v)                    => p.copy(height = Some(v))
            case Prop.MinWidth(v)                  => p.copy(minW = Some(v))
            case Prop.MaxWidth(v)                  => p.copy(maxW = Some(v))
            case Prop.MinHeight(v)                 => p.copy(minH = Some(v))
            case Prop.MaxHeight(v)                 => p.copy(maxH = Some(v))
            case Prop.FlexGrowProp(v)              => p.copy(grow = v)
            case Prop.FlexShrinkProp(v)            => p.copy(shrink = v)
            case Prop.FontWeightProp(w)            =>
              val bold = w match
                  case FontWeight.bold | FontWeight.w600 | FontWeight.w700 |
                      FontWeight.w800 | FontWeight.w900 =>
                    true
                  case _ => false
              p.copy(text = p.text.copy(bold = bold))
            case Prop.FontStyleProp(f)             =>
              p.copy(text = p.text.copy(italic = f == FontStyle.italic))
            case Prop.TextDecorationProp(d)        =>
              p.copy(text =
                p.text.copy(
                  underline = d == TextDecoration.underline,
                  strike = d == TextDecoration.strikethrough,
                ),
              )
            case Prop.TextTransformProp(t)         =>
              p.copy(text = p.text.copy(transform = t))
            case Prop.TextAlignProp(a)             => p.copy(text = p.text.copy(align = a))
            case Prop.OpacityProp(v)               =>
              p.copy(text = p.text.copy(dim = v < 0.7))
            case Prop.ListStyleProp(l)             => p.copy(listStyle = Some(l))
            case Prop.HiddenProp                   => p.copy(hidden = true)
            // claude 2026-10-19: created (L1a): the overflow of each axis
            case Prop.OverflowProp(v)              =>
              p.copy(overflowX = v, overflowY = v)
            case Prop.OverflowXProp(v)             => p.copy(overflowX = v)
            case Prop.OverflowYProp(v)             => p.copy(overflowY = v)
            // claude 2026-10-19: created (L1a): the look of the indicator
            case Prop.ScrollbarWidthProp(v)        => p.copy(scrollbarWidth = v)
            case Prop.ScrollbarColorProp(th, tr)   =>
              p.copy(scrollbarColor = Some((color(th), color(tr))))
            // claude 2026-10-19: created (L1a): the text flow, as the DOM
            // runner maps it to CSS (`textWrap` is `overflow-wrap`)
            case Prop.WhiteSpaceProp(v)            =>
              p.copy(text = p.text.copy(whiteSpace = Some(v)))
            case Prop.TextOverflowProp(v)          => p.copy(textOverflow = v)
            case Prop.TextWrapProp(v)              =>
              v match
                  case TextWrap.noWrap   =>
                    p.copy(text = p.text.copy(breaks = WordBreak.Keep))
                  case TextWrap.ellipsis =>
                    p.copy(
                      text = p.text.copy(breaks = WordBreak.Keep),
                      textOverflow = TextOverflow.ellipsis,
                    )
                  case _                 =>
                    p.copy(text = p.text.copy(breaks = WordBreak.Split))
            case Prop.BorderWidthProp(t, r, b, l)  =>
              val sides = Sides(
                side(t).min(1),
                side(r).min(1),
                side(b).min(1),
                side(l).min(1),
              )
              val prev  =
                p.border.getOrElse(Border(Sides.zero, Glyphs.solid, None))
              p.copy(border = if sides.nonZero then
                  Some(prev.copy(sides = sides))
              else None)
            case Prop.BorderStyleProp(st)          =>
              st match
                  case BorderStyle.none   => p.copy(border = None)
                  case BorderStyle.solid  =>
                    p.copy(border =
                      p.border.map(b =>
                        b.copy(glyphs = if b.glyphs == Glyphs.rounded then
                            b.glyphs
                        else Glyphs.solid),
                      ),
                    )
                  case BorderStyle.dashed =>
                    p.copy(border =
                      p.border.map(_.copy(glyphs = Glyphs.dashed)),
                    )
                  case BorderStyle.dotted =>
                    p.copy(border =
                      p.border.map(_.copy(glyphs = Glyphs.dotted)),
                    )
            case Prop.BorderColorProp(c, _, _, _)  =>
              p.copy(border = p.border.map(_.copy(fg = color(c))))
            case Prop.BorderRadiusProp(a, _, _, _) =>
              if side(a) > 0 then
                  p.copy(border =
                    p.border.map(b =>
                      if b.glyphs == Glyphs.solid then
                          b.copy(glyphs = Glyphs.rounded)
                      else b,
                    ),
                  )
              else p
            case _                                 => p
      }
end Props
