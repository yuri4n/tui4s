package tui4s.internal

import kyo.*
import kyo.Style.{Display, Position, Prop}

/** A thing in the UI that the terminal cannot show: the runner logs one warning
  * for each, once in a run ([[Warnings.scan]]).
  */
// claude 2026-10-19: created (L1b)
private[tui4s] enum Warn derives CanEqual:
    /** A case of `Style.Prop` that the runner ignores (its case name, as in the
      * table of the package Scaladoc), or a value of a case that it does not
      * honor (for example `DisplayProp(table)`).
      */
    case Ignored(name: String)

    /** A `Color.Var` with no colour value in the stylesheet of the run. */
    case NoValue(name: String)

    /** The log line of the warning; `at` is the first element that has it. */
    def line(at: Loc): String = this match
        case Ignored(n) =>
          s"tui4s: the style property $n is not supported in the terminal (first seen at ${at.showLoc})"
        case NoValue(n) =>
          s"tui4s: the colour var(--$n) has no value in the stylesheet of the run (first seen at ${at.showLoc})"
end Warn

/** The warnings of the style properties that the terminal cannot show. A
  * property of [[Props.honored]] gives none, except the values that the runner
  * does not honor (`DisplayProp` listItem, table, tableRow, tableCell;
  * `PositionProp` sticky, which stays in the flow and does not stick) and a
  * `Color.Var` with no value. Any other property gives one warning with its
  * case name. The styles of `focus` and `disabled` count too; the styles of
  * `hover` and `active` are ignored as a whole (no mouse, D7).
  *
  * The runner keeps the warnings that it logged in `World.warned`, so each one
  * is logged once in a run, with the first element that has it.
  */
// claude 2026-10-19: created (L1b)
private[tui4s] object Warnings:

    /** The warnings of `style` on an element whose custom properties are
      * `vars`.
      */
    def of(style: Style, vars: CssVars): Vector[Warn] =
      style.props.toVector.flatMap(prop(_, vars))

    /** The warnings of one property. */
    def prop(p: Prop, vars: CssVars): Vector[Warn] = p match
        case Prop.FocusProp(s)                           => of(s, vars)
        case Prop.DisabledProp(s)                        => of(s, vars)
        case Prop.DisplayProp(v)                         =>
          v match
              case Display.listItem | Display.table | Display.tableRow |
                  Display.tableCell =>
                Vector(Warn.Ignored(s"DisplayProp($v)"))
              case _ => Vector.empty
        case Prop.PositionProp(Position.sticky)          =>
          Vector(Warn.Ignored("PositionProp(sticky)"))
        case Prop.BgColor(c)                             => color(c, vars)
        case Prop.TextColor(c)                           => color(c, vars)
        case Prop.BorderColorProp(t, r, b, l)            =>
          Vector(t, r, b, l).flatMap(color(_, vars)).distinct
        case Prop.ScrollbarColorProp(th, tr)             =>
          (color(th, vars) ++ color(tr, vars)).distinct
        case other if Props.honored(other.productPrefix) => Vector.empty
        case other                                       => Vector(Warn.Ignored(other.productPrefix))

    /** The warning of a `Color.Var` with no value. */
    private def color(c: Style.Color, vars: CssVars): Vector[Warn] = c match
        case v: Style.Color.Var if vars.color(v.name) == CssColor.Unset =>
          Vector(Warn.NoValue(v.name))
        case _                                                          => Vector.empty

    /** The new warnings of a frame: the elements of the subtrees of the roots
      * of `damage` (the nodes that the frame laid out or painted again), in
      * document order, without the warnings of `seen`. Gives each new warning
      * with its first element.
      */
    def scan(
        laid: Laid,
        damage: Vector[Damage],
        seen: Set[Warn],
    ): Vector[(Warn, Loc)] =
        val roots                               = Loc.tops(damage.collect { case Damage.Paint(p) => p })
        def walk(p: Loc): Iterator[(Warn, Loc)] =
          laid.nodes.get(p).iterator.flatMap { n =>
            val here = n.kind match
                case Kind.El(e) =>
                  of(e.attrs.uiStyle, n.props.text.vars).iterator.map((_, p))
                case _          => Iterator.empty
            here ++ n.kids.iterator.flatMap(walk)
          }
        roots.iterator
          .flatMap(walk)
          .filterNot((w, _) => seen.contains(w))
          .toVector
          .distinctBy(_._1)
    end scan
end Warnings
