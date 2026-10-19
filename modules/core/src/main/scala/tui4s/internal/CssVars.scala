package tui4s.internal

import jatatui.core.style.Color as JColor
import kyo.*
import kyo.UI.Ast.Element

/** What a `var(--name)` gives: `Of` a colour (none for `transparent`), or
  * `Unset` when no block declares the name, or when its value is not a colour
  * that the runner reads.
  */
// claude 2026-10-19: created (L1b)
private[tui4s] enum CssColor derives CanEqual:
    case Of(color: Option[JColor])
    case Unset

/** The custom properties (CSS variables) in scope for an element, from the
  * kyo-ui `Stylesheet` of the run: its `:root` blocks (`Stylesheet.vars`) and
  * its blocks for a selector (`Stylesheet.scopedVars`).
  *
  * For example, with `Stylesheet.vars("accent" -> "#22c55e")`, the colour
  * `Style.Color.variable("accent")` of any element is that green. With
  * `Stylesheet.scopedVars(Selector.cls("alert"), "accent" -> "#ef4444")` it is
  * red on an element with `cssClass("alert")` and in its subtree.
  *
  * The resolution, as CSS gives it for these cases: custom properties inherit.
  * The values of an element are the values of its parent, with the blocks whose
  * selector matches the element declared over them, in the order of the
  * stylesheet (a later block wins). The screen has the `:root` blocks. A value
  * is read as a colour: `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`,
  * `rgb(r, g, b)`, `rgba(r, g, b, a)` (no colour below an alpha of 0.5, as
  * [[Props.color]] does), `transparent` (no colour), or `var(--other)` and
  * `var(--other, <fallback>)` (at most 8 steps deep). kyo-ui's `Color.Var` has
  * no fallback value of its own: a name with no value is `CssColor.Unset`, and
  * the colour is not set (the element inherits its text colour, and has no
  * background).
  *
  * The selectors that the runner matches are one simple selector each: `.class`
  * (kyo-ui's `cssClass`), `#id` (`id`), `[data-name]` and `[data-name="value"]`
  * (kyo-ui's `data`). `:root`, `html` and `body` are the root. Another selector
  * (a descendant, a child, a pseudo class, a tag) matches no element;
  * [[CssVars.of]] names it in its problems. The other entries of a stylesheet
  * (rules, media queries, fonts, keyframes) are problems too: the runner
  * ignores them.
  *
  * @param values
  *   the values in scope, by name (without `--`)
  * @param scoped
  *   the blocks for a selector, in the order of the stylesheet
  */
// claude 2026-10-19: created (L1b)
private[tui4s] final case class CssVars(
    values: Map[String, String],
    scoped: Vector[CssVars.Scoped],
):
    /** The custom properties in scope for `el`, a child of the element that has
      * these.
      */
    def enter(el: Element): CssVars =
      if scoped.isEmpty then this
      else
          val here = scoped.filter(_.selector.matches(el))
          if here.isEmpty then this
          else copy(values = here.foldLeft(values)(_ ++ _.pairs))

    /** The colour of `var(--name)` here. */
    def color(name: String): CssColor = CssVars.parse(values, name, 8)
end CssVars

private[tui4s] object CssVars:
    val empty: CssVars = CssVars(Map.empty, Vector.empty)

    /** A simple selector of a block of custom properties. */
    enum Sel derives CanEqual:
        case Cls(name: String)
        case Id(name: String)
        case Data(name: String, value: Option[String])
        case Root
        case Unsupported(css: String)

        /** True when the element `el` has the class, the id or the data
          * attribute of the selector.
          */
        def matches(el: Element): Boolean = this match
            case Cls(n)         => el.attrs.cssClasses.contains(n)
            case Id(n)          => el.attrs.identifier.contains(n)
            case Data(n, v)     =>
              v.fold(el.attrs.dataAttrs.contains(n))(x =>
                el.attrs.dataAttrs.get(n).contains(x),
              )
            case Root           => false
            case Unsupported(_) => false
    end Sel

    object Sel:
        private val Name  = "[A-Za-z_][A-Za-z0-9_-]*"
        private val ClsR  = s"\\.($Name)".r
        private val IdR   = s"#($Name)".r
        private val DataV = s"""\\[data-($Name)="([^"]*)"\\]""".r
        private val DataR = s"\\[data-($Name)\\]".r

        /** The selector of the CSS text of a kyo-ui `Selector`. */
        def of(css: String): Sel = css.trim match
            case ClsR(n)                   => Cls(n.nn)
            case IdR(n)                    => Id(n.nn)
            case DataV(n, v)               => Data(n.nn, Some(v.nn))
            case DataR(n)                  => Data(n.nn, None)
            case ":root" | "html" | "body" => Root
            case other                     => Unsupported(other)
    end Sel

    /** A block of custom properties for a selector. */
    final case class Scoped(selector: Sel, pairs: Map[String, String])

    /** The custom properties of `sheet`, and its problems: one text for each
      * entry or selector that the runner ignores (in the order of the
      * stylesheet, each text once).
      */
    def of(sheet: Stylesheet): (CssVars, Vector[String]) =
        val init             = (CssVars.empty, Vector.empty[String])
        val (vars, problems) = sheet.entries.foldLeft(init) {
          case ((v, ps), Stylesheet.Entry.Vars(pairs))                 =>
            (v.copy(values = v.values ++ pairs), ps)
          case ((v, ps), Stylesheet.Entry.ScopedVars(selector, pairs)) =>
            Sel.of(selector.css) match
                case Sel.Root           =>
                  (v.copy(values = v.values ++ pairs), ps)
                case Sel.Unsupported(c) =>
                  (v, ps :+ s"the selector $c of Stylesheet.scopedVars")
                case s                  =>
                  (v.copy(scoped = v.scoped :+ Scoped(s, pairs.toMap)), ps)
          case ((v, ps), Stylesheet.Entry.Rule(selector, _))           =>
            (v, ps :+ s"the rule ${selector.css} of the stylesheet")
          case ((v, ps), Stylesheet.Entry.Media(q, _))                 =>
            (v, ps :+ s"the media query ${q.condition} of the stylesheet")
          case ((v, ps), Stylesheet.Entry.Font(f))                     =>
            (v, ps :+ s"the font face ${f.family} of the stylesheet")
          case ((v, ps), Stylesheet.Entry.Keyframes(name, _))          =>
            (v, ps :+ s"the keyframes $name of the stylesheet")
        }
        (vars, problems.distinct)
    end of

    private val VarR = """var\(\s*--([^,\s)]+)\s*(?:,\s*(.+?))?\s*\)""".r
    private val RgbR =
      """rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*([0-9.]+)\s*)?\)""".r

    /** The colour of the custom property `name` in `values`, at most `depth`
      * steps of `var()` deep.
      */
    def parse(values: Map[String, String], name: String, depth: Int): CssColor =
      values.get(name).fold(CssColor.Unset)(v => value(values, v.trim, depth))

    /** A colour channel of `rgb()`: 0 to 255. */
    private def channel(d: String): Int = d.toIntOption.getOrElse(255).min(255)

    /** The colour of the CSS value `v`. */
    private def value(
        values: Map[String, String],
        v: String,
        depth: Int,
    ): CssColor =
      v match
          case _ if depth <= 0            => CssColor.Unset
          case "transparent"              => CssColor.Of(None)
          case VarR(n, fallback)          =>
            parse(values, n.nn, depth - 1) match
                case CssColor.Unset =>
                  Option(fallback).fold(CssColor.Unset)(f =>
                    value(values, f.trim, depth - 1),
                  )
                case c              => c
          case RgbR(r, g, b, a)           =>
            val alpha = Option(a).flatMap(_.toDoubleOption).getOrElse(1.0)
            CssColor.Of(
              Option.when(alpha >= 0.5)(
                JColor.Rgb(channel(r.nn), channel(g.nn), channel(b.nn)),
              ),
            )
          case hex if hex.startsWith("#") =>
            Style.Color.hex(hex) match
                case Present(c) => CssColor.Of(Props.color(c, CssVars.empty))
                case Absent     => CssColor.Unset
          case _                          => CssColor.Unset
end CssVars
