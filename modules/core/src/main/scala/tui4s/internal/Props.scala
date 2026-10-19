package tui4s.internal

// claude 2026-10-13: changed (C3, gate 1 item 1): private[tui4s] on every top-level definition of this file (the seal of tui4s.internal)

import jatatui.core.style.{Color as JColor, Modifier, Style as JStyle}
import kyo.*
import kyo.Style.{
  Alignment,
  BorderStyle,
  FlexDirection,
  FlexWrap,
  FontStyle,
  FontWeight,
  Justification,
  ListStyle,
  Overflow,
  Position,
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

/** How an element is placed (CSS `position`), from kyo-ui's `Position`:
  * `Static` (`flow`) is in the flex flow; `Relative` and `Sticky` are in the
  * flow too, and `Relative` moves by its offsets; `Absolute` (also `dropdown`)
  * and `Fixed` (also `overlay`) are out of the flow. Every value but `Static`
  * is positioned: a containing block for its absolute descendants, painted
  * after the flow ([[Paint]]).
  */
// claude 2026-10-19: created (L1b): the positions of kyo-ui
private[tui4s] enum Pos derives CanEqual:
    case Static, Relative, Sticky, Absolute, Fixed

    /** True when the element is out of the flex flow: absolute or fixed. */
    def outOfFlow: Boolean = this match
        case Absolute | Fixed => true
        case _                => false

    /** True when the element is positioned: not static. */
    def positioned: Boolean = this != Static
end Pos

/** The offsets of a positioned element (CSS `top`, `right`, `bottom`, `left`).
  * None is `auto`.
  */
// claude 2026-10-19: created (L1b)
private[tui4s] final case class Inset(
    top: Option[Length],
    right: Option[Length],
    bottom: Option[Length],
    left: Option[Length],
)

private[tui4s] object Inset:
    val auto: Inset = Inset(None, None, None, None)

/** Text attributes. Children inherit them, like CSS `color`, `font-weight`,
  * `text-decoration`, `text-align`, `white-space`, `overflow-wrap` and the
  * custom properties (`vars`, see [[CssVars]]). `whiteSpace` is none when no
  * element sets it: the runner keeps the spaces and the line breaks of a text,
  * as `pre-wrap` does.
  */
// claude 2026-10-19: changed: white-space and overflow-wrap (L1a), the
// custom properties (L1b)
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
    // claude 2026-10-19: created (L1b): the custom properties in scope (they
    // inherit, as in CSS)
    vars: CssVars = CssVars.empty,
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
  *
  * The position, display, wrap and colour-variable properties, and the size of
  * the window (the audit of 2026-10-19, kyo-ui 1.0.0-RC7; the CSS is the CSS of
  * `CssStyleRenderer`):
  *   - `position` (`Position`: flow, overlay, relative, dropdown, sticky,
  *     absolute, fixed). The CSS: `flow` is `position: static`; `overlay` is
  *     `position: fixed; top: 0; left: 0; width: 100%; height: 100%`;
  *     `relative`, `sticky`, `absolute` and `fixed` are the CSS keywords of the
  *     same name; `dropdown` is `position: absolute; top: 100%; right: 0;
  *     z-index: 50`. The old runner ignored it. Now ([[Pos]], the layers of
  *     [[Layout]]): `absolute` and `fixed` leave the flex flow and take their
  *     box from their containing block (the nearest positioned ancestor, else
  *     the screen; the screen for `fixed`); `relative` stays in the flow and
  *     moves by its offsets; `sticky` stays in the flow and does not stick.
  *     Each positioned element is a layer: it paints after the flow, in the
  *     order of [[Paint]].
  *   - `top`, `right`, `bottom`, `left` (`Length`; CSS `top`, `right`,
  *     `bottom`, `left`). kyo-ui has no `inset`. The old runner ignored them.
  *     Now ([[Inset]], [[Offsets]]): the offsets of a positioned element; a
  *     percentage is of the width or the height of the containing block.
  *   - `zIndex` (`Int`; CSS `z-index`). The old runner ignored it. Now: the
  *     paint order of the layers (0 when none).
  *   - `display` (`Display`: block, inline, inlineBlock, flex, inlineFlex,
  *     listItem, table, tableRow, tableCell; CSS `display`). kyo-ui has no
  *     `none` value and no `grid` value. `displayNone` (and `hidden(true)`) is
  *     `HiddenProp`, CSS `display: none`. The old runner ignored `display`. It
  *     honored `HiddenProp`: the element has no layout and no paint
  *     (`Layout.flatten` drops it), and the regions in it stay mounted
  *     (`Mount.walk` walks it), so their state stays. Now: `HiddenProp` as
  *     before; `flex` and `inlineFlex` keep the flex layout of kyo-ui's base
  *     CSS. `block`, `inline` and `inlineBlock` are accepted and change
  *     nothing: the element keeps its flex layout (an `inline` span stays a row
  *     of text), where a browser uses block or inline flow. They give no
  *     warning. `listItem`, `table`, `tableRow` and `tableCell` change nothing
  *     and warn (see [[Warnings]]).
  *   - `flexWrap` (`FlexWrap`: wrap, noWrap; CSS `flex-wrap`). kyo-ui has no
  *     `wrapReverse` and no `alignContent`. The old runner ignored it. Now:
  *     `wrap` breaks the items into lines ([[Layout.lines]]). Each line is as
  *     high as its tallest item, and the lines stack from the cross start.
  *     kyo-ui's DOM sets no `align-content`, so the browser uses `normal`,
  *     which acts as `stretch` when the container wraps. The runner does the
  *     same: when the container has more cross space than its lines, each line
  *     gets an equal part of the free space.
  *   - `Color.Var(name)` (`Color.variable(name)`; CSS `var(--name)`). The case
  *     has no fallback value. kyo-ui declares a custom property only in a
  *     `Stylesheet`: `Stylesheet.vars(name -> value)` is a `:root` block, and
  *     `Stylesheet.scopedVars(selector, name -> value)` is a block for a
  *     selector. No `Style.Prop` declares a custom property. A page gets its
  *     stylesheet through `UI.PageHead.css` or `UI.runStylesheet`, not through
  *     the element tree. The old runner gave no colour for it. Now: the run
  *     takes a stylesheet (`Tui.run(options, sheet)`, `TuiApp.stylesheet`,
  *     `Headless.start(..., stylesheet)`), and [[CssVars]] resolves the
  *     variable from the blocks of the element and of its ancestors.
  *   - The size of the window: `UIWindow.size` (`Signal[(Int, Int)]`, in CSS
  *     pixels) is in the Scala.js jar of kyo-ui only. A private `SignalRef`
  *     holds it, and the DOM `resize` event writes it. The JVM jar has no
  *     signal of the window size, and a runner cannot write the Scala.js one.
  *     The old runner gave the UI no size. Now: [[tui4s.Tui.size]], a
  *     `Signal[TerminalSize]` that the runner writes on a resize.
  *
  * The input elements and their attributes (the audit of 2026-10-19, kyo-ui
  * 1.0.0-RC7; the DOM runner is `HtmlRenderer`, `ReactiveUI` and the client
  * script). The DOM writes these attributes: `type` (from the element),
  * `placeholder`, `value`, `checked`, `disabled`, `readonly`, `min`, `max`,
  * `step`, and the `name` of a radio. kyo-ui has no `rows`, `cols`,
  * `maxLength`, `required` and `form` attribute, and no `autofocus`:
  * `focusAuto` seeds the focus of any interactive element (L1b). A field is in
  * a form when a `Form` holds it. The decisions:
  *   - `Input`, `EmailInput`, `TelInput`, `UrlInput`, `SearchInput` (the
  *     `ConstrainedInput` fields: `value`, `placeholder`, `readOnly`,
  *     `disabled`, `onInput`, `onChange`, `inputFilter`, `inputMask`). The old
  *     runner: one underlined line; the keys edit by grapheme clusters
  *     ([[TextEdit]]); the placeholder is dim and italic; Enter runs
  *     `onChange`. Keep. Add: the maximum length of `tui4s.maxLength` (kyo-ui
  *     has no attribute for it). `inputFilter` and `inputMask` stay ignored.
  *   - `PasswordInput`. The old runner: one "*" for each grapheme cluster.
  *     Implement: one "•" for each cluster; the text never goes into the paint
  *     plan.
  *   - `NumberInput` (`min`, `max`, `step`, `onChangeNumeric`; its `value` is a
  *     `String`). The old runner: a free text. Implement: the keys accept
  *     digits, one minus sign and one decimal point (`NumberText`; HTML has no
  *     `+`); ArrowUp and ArrowDown change the value by `step` (1 when none),
  *     clamped to `min` and `max`, and a step that changes nothing runs no
  *     callback. The DOM writes the text of each edit to the bound ref, and on
  *     a step or a commit the number as kyo-ui formats it (`NumberFormat`: "3",
  *     "2.5") and calls `onChangeNumeric`. The runner does the same.
  *   - `Textarea`. The old runner: one line, as a text input. Implement: lines
  *     that wrap by cells, a caret that moves by lines, a viewport of its own
  *     (`AreaText`). Its height is the definite `height` of its style, else 3
  *     rows (kyo-ui has no `rows`). Its `onChange` runs on Enter in a form, and
  *     at its blur after an edit that changed the text (the DOM's change
  *     event).
  *   - `Checkbox`, `Radio` (`checked`, `onChange`; the `name` of a radio). The
  *     old runner: Space and Enter toggle a checkbox and check a radio. Keep.
  *     The `name` of a radio makes no group: the DOM leaves the group to the
  *     browser, and the runner leaves it to the refs of the app.
  *   - `Select` (`value`, `onChange`; `Opt` with `value` and `selected`). The
  *     old runner: Space, Enter, ArrowDown and ArrowUp cycle the options. Keep.
  *   - `Dropdown` (`options`, `value`, `onChange`). The old runner: it shows
  *     the label of its value, it takes the focus, and no key changes it.
  *     Implement: the keys of a select (the DOM opens a list; the terminal has
  *     no list).
  *   - `RangeInput` (`value` is a `Double`, `min`, `max`, `step`, `onChange`).
  *     The old runner: a track and a thumb, and no key. Keep the look.
  *     Implement: ArrowLeft, ArrowRight, ArrowDown and ArrowUp move it by
  *     `step`, PageUp and PageDown by 10 steps, Home and End to `min` and
  *     `max`. The value stays on the grid of the steps from `min`, as HTML
  *     keeps the value of a range on its steps.
  *   - `DateInput`, `TimeInput`, `ColorInput`, `FileInput`. The old runner: an
  *     empty box that takes the focus and no key. Keep, and warn once in a run
  *     ([[Warn.UnsupportedElement]]).
  *   - `HiddenInput`: no layout and no paint (old and new).
  *   - `disabled` (every `HasDisabled` element: the inputs and `Button`): no
  *     focus, no key and no paste, and a dim text. Keep: the DOM does not focus
  *     a disabled element either. `readOnly` (the text inputs and the
  *     textarea): the focus and the moves of the caret, no edit, no paste and
  *     no `onChange`. Keep, and the number keys and the newline keys obey it
  *     too.
  */
// claude 2026-10-19: changed: the audit of the input elements (L1c)
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
    /** `position`: see [[Pos]]. */
    // claude 2026-10-19: created the three fields below (L1b): the position,
    // the offsets and the z-index
    position: Pos = Pos.Static,
    /** `top`, `right`, `bottom`, `left`. */
    inset: Inset = Inset.auto,
    /** `zIndex`: the paint order of a positioned element (none is 0). */
    z: Option[Int] = None,
    /** `flexWrap`: `wrap` breaks the items into lines ([[Layout.lines]]). */
    // claude 2026-10-19: created (L1b)
    wrap: FlexWrap = FlexWrap.noWrap,
):
    def insets: Sides = border.fold(Sides.zero)(_.sides) + padding + decoration

    /** True when the items break into lines: `flexWrap(wrap)`. */
    // claude 2026-10-19: created (L1b)
    def wraps: Boolean = wrap == FlexWrap.wrap

    /** True when an axis is not `visible`: the element is a viewport. */
    // claude 2026-10-19: created (L1a)
    def clips: Boolean =
      overflowX != Overflow.visible || overflowY != Overflow.visible
end Props

private[tui4s] object Props:

    /** The cases of kyo-ui's `Style.Prop` that the runner honors, by case name.
      * The runner ignores the other cases. The package Scaladoc of `tui4s`
      * lists the two sets, and `StyleTableTest` compares them with the surface
      * list of kyo-ui, so they do not drift.
      */
    // claude 2026-10-19: created (L1b): the honored properties
    val honored: Set[String] = Set(
      "Align",
      "AlignSelf",
      "BgColor",
      "BorderColorProp",
      "BorderRadiusProp",
      "BorderStyleProp",
      "BorderWidthProp",
      "Bottom",
      "DisabledProp",
      "DisplayProp",
      "FlexDirectionProp",
      "FlexGrowProp",
      "FlexShrinkProp",
      "FlexWrapProp",
      "FocusProp",
      "FontStyleProp",
      "FontWeightProp",
      "Gap",
      "Height",
      "HiddenProp",
      "Justify",
      "Left",
      "ListStyleProp",
      "Margin",
      "MaxHeight",
      "MaxWidth",
      "MinHeight",
      "MinWidth",
      "OpacityProp",
      "OverflowProp",
      "OverflowXProp",
      "OverflowYProp",
      "Padding",
      "PositionProp",
      "Right",
      "ScrollbarColorProp",
      "ScrollbarWidthProp",
      "TextAlignProp",
      "TextColor",
      "TextDecorationProp",
      "TextOverflowProp",
      "TextTransformProp",
      "TextWrapProp",
      "Top",
      "WhiteSpaceProp",
      "Width",
      "ZIndexProp",
    )

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

    /** The terminal colour of `c`; a `Color.Var` takes its value from the
      * custom properties `vars` ([[CssVars]]), and has no colour when they do
      * not give one.
      */
    // claude 2026-10-19: changed: Color.Var (L1b)
    def color(c: kyo.Style.Color, vars: CssVars): Option[JColor] = c match
        case h: kyo.Style.Color.Hex      => hex(h.value)
        case r: kyo.Style.Color.Rgb      => Some(JColor.Rgb(r.r, r.g, r.b))
        case r: kyo.Style.Color.Rgba     =>
          if r.a < 0.5 then None else Some(JColor.Rgb(r.r, r.g, r.b))
        case kyo.Style.Color.Transparent => None
        case v: kyo.Style.Color.Var      =>
          vars.color(v.name) match
              case CssColor.Of(c) => c
              case CssColor.Unset => None

    /** The text colour of `c` for an element whose inherited text is `t`. A
      * `Color.Var` with no value keeps the inherited colour `t.fg`. CSS does
      * the same: an unset `var()` on an inherited property inherits.
      *
      * For example, in `div.style(_.color(Style.Color.red))(span("x")
      * .style(_.color(Style.Color.variable("none"))))`, the text `x` is red.
      */
    // claude 2026-10-19: created (L1b review): an unset var() inherits
    private def textColor(c: kyo.Style.Color, t: TextAttrs): Option[JColor] =
      c match
          case v: kyo.Style.Color.Var =>
            t.vars.color(v.name) match
                case CssColor.Of(x) => x
                case CssColor.Unset => t.fg
          case _                      => color(c, t.vars)

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
        // claude 2026-10-19: changed: the custom properties of the element
        // (L1b)
        val text          = inherited.copy(vars = inherited.vars.enter(el))
        val base0         = neutral(typeText(el, text)).copy(
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

    /** An offset: `auto` is none. */
    // claude 2026-10-19: created (L1b)
    private def offset(l: Length): Option[Length] = l match
        case Length.Auto => None
        case other       => Some(other)

    /** `p` after `position(v)`, as the CSS of kyo-ui's DOM runner: `overlay` is
      * fixed with `top: 0; left: 0; width: 100%; height: 100%`, and `dropdown`
      * is absolute with `top: 100%; right: 0; z-index: 50`.
      */
    // claude 2026-10-19: created (L1b)
    private def position(p: Props, v: Position): Props = v match
        case Position.flow     => p.copy(position = Pos.Static)
        case Position.relative => p.copy(position = Pos.Relative)
        case Position.sticky   => p.copy(position = Pos.Sticky)
        case Position.absolute => p.copy(position = Pos.Absolute)
        case Position.fixed    => p.copy(position = Pos.Fixed)
        case Position.overlay  =>
          p.copy(
            position = Pos.Fixed,
            inset = p.inset
              .copy(top = Some(Length.Px(0)), left = Some(Length.Px(0))),
            width = Some(Length.Pct(100)),
            height = Some(Length.Pct(100)),
          )
        case Position.dropdown =>
          p.copy(
            position = Pos.Absolute,
            inset = p.inset
              .copy(top = Some(Length.Pct(100)), right = Some(Length.Px(0))),
            z = Some(50),
          )

    def applyStyle(p: Props, s: kyo.Style): Props =
      s.props.foldLeft(p) { (p, prop) =>
        prop match
            case Prop.BgColor(c)                   => p.copy(bg = color(c, p.text.vars))
            case Prop.TextColor(c)                 =>
              p.copy(text = p.text.copy(fg = textColor(c, p.text)))
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
              p.copy(scrollbarColor =
                Some((color(th, p.text.vars), color(tr, p.text.vars))),
              )
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
            // claude 2026-10-19: created (L1b): the position, as the DOM
            // runner maps it to CSS (`overlay` and `dropdown` set offsets)
            case Prop.PositionProp(v)              => position(p, v)
            case Prop.Top(v)                       =>
              p.copy(inset = p.inset.copy(top = offset(v)))
            case Prop.Right(v)                     =>
              p.copy(inset = p.inset.copy(right = offset(v)))
            case Prop.Bottom(v)                    =>
              p.copy(inset = p.inset.copy(bottom = offset(v)))
            case Prop.Left(v)                      =>
              p.copy(inset = p.inset.copy(left = offset(v)))
            case Prop.ZIndexProp(v)                => p.copy(z = Some(v))
            // claude 2026-10-19: created (L1b): the flex lines; the display
            // values that the runner honors keep its flex layout (the others
            // warn, see Warnings)
            case Prop.FlexWrapProp(v)              => p.copy(wrap = v)
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
              p.copy(border = p.border.map(_.copy(fg = color(c, p.text.vars))))
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
