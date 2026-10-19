package tui4s.widgets

import kyo.*

/** The colours of the widgets: kyo-ui custom properties
  * (`Style.Color.variable`), so one stylesheet gives the colours of all the
  * widgets, in the terminal and on the web. [[Colors.defaults]] has a value for
  * each one: give it to the run (`TuiApp.stylesheet`, `Tui.run(options,
  * sheet)`, `UI.PageHead.css` on the web), or give your own `Stylesheet.vars`
  * with the same names.
  *
  * kyo-ui's `Color.Var` has no fallback value. Without a value, a text keeps
  * the colour of its parent and a background is transparent, so the widgets
  * never use colour alone: a selected row also has a marker ("›") and bold
  * text, a checked item "[x]", a focused element the focus style of the runner.
  * The table of the names is in the package Scaladoc ([[tui4s.widgets]]).
  *
  * {{{
  * object App extends TuiApp:
  *   override def stylesheet = Colors.defaults
  *   def ui = ListView(items, identity)(span(_))
  * }}}
  */
// claude 2026-10-19: created (L2b): the colour variables (D8)
object Colors:
    /** `--tui4s-accent`: the selection, the active tab, a filled bar. */
    val accent: Style.Color = Style.Color.variable("tui4s-accent")

    /** `--tui4s-muted`: secondary text, hints, an inactive tab. */
    val muted: Style.Color = Style.Color.variable("tui4s-muted")

    /** `--tui4s-border`: the borders of panels and layers. */
    val border: Style.Color = Style.Color.variable("tui4s-border")

    /** `--tui4s-surface`: the background of a layer (a modal, a menu). */
    val surface: Style.Color = Style.Color.variable("tui4s-surface")

    /** `--tui4s-selection`: the background of the selected row. */
    val selection: Style.Color = Style.Color.variable("tui4s-selection")

    /** `--tui4s-success`: a valid value, a done step, a success toast. */
    val success: Style.Color = Style.Color.variable("tui4s-success")

    /** `--tui4s-warning`: a warning toast. */
    val warning: Style.Color = Style.Color.variable("tui4s-warning")

    /** `--tui4s-danger`: an error message, an error toast. */
    val danger: Style.Color = Style.Color.variable("tui4s-danger")

    /** The default value of each variable, as a `:root` block. */
    val defaults: Stylesheet = Stylesheet.vars(
      "tui4s-accent"    -> "#eab308",
      "tui4s-muted"     -> "#6b7280",
      "tui4s-border"    -> "#64748b",
      "tui4s-surface"   -> "#111827",
      "tui4s-selection" -> "#1e3a5f",
      "tui4s-success"   -> "#22c55e",
      "tui4s-warning"   -> "#f59e0b",
      "tui4s-danger"    -> "#ef4444",
    )
end Colors

/** The styles that several widgets share. */
// claude 2026-10-19: created (L2b)
private[widgets] object Looks:
    /** A selected row: the selection background, bold accent text. */
    val selected: Style =
      Style.bg(Colors.selection).color(Colors.accent).bold

    /** A row that is not selected. */
    val plain: Style = Style.empty

    /** Secondary text. */
    val muted: Style = Style.color(Colors.muted)

    /** A layer: a border, the surface background, one cell of padding. */
    val layer: Style =
      Style.border(1.px, Colors.border).bg(Colors.surface).padding(0.px, 1.px)

    /** The marker of the selected row ("› ") or two spaces. */
    def marker(on: Boolean): String = if on then "› " else "  "

    /** One line that ends with "…" when it is wider than its box. */
    val oneLine: Style =
      Style.whiteSpace(_.noWrap).overflow(_.hidden).textOverflow(_.ellipsis)

    /** One line that keeps its spaces, and ends with "…" when it is wider than
      * its box.
      */
    val preLine: Style =
      Style.whiteSpace(_.pre).overflow(_.hidden).textOverflow(_.ellipsis)

    /** A fresh id with `prefix`, from Kyo's `Random`: the ids of the rows of
      * two widgets do not collide, and there is no global counter.
      */
    def freshId(prefix: String)(using Frame): String < Sync =
      Random.nextStringAlphanumeric(8).map(s => s"$prefix-$s")
end Looks
