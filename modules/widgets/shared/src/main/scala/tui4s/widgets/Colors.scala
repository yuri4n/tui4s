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

    /** The terminal cells of `s`: 2 for a wide or fullwidth character (CJK,
      * most emoji), 0 for a combining mark, a joiner, a variation selector or
      * a control character, 1 for the rest. A portable approximation of the
      * East Asian Width of Unicode (the module does not depend on the measure
      * of tui4s-core).
      */
    // claude 2026-10-19: created (L2b review): widths in cells, not chars
    def cells(s: String): Int =
        @scala.annotation.tailrec
        def go(i: Int, acc: Int): Int =
          if i >= s.length then acc
          else
              val cp = s.codePointAt(i)
              go(i + Character.charCount(cp), acc + cellsOf(cp))
        go(0, 0)
    end cells

    private def cellsOf(cp: Int): Int =
      if cp < 0x20 || (cp >= 0x7f && cp < 0xa0) || in(zero, cp) then 0
      else if in(wide, cp) then 2
      else 1

    private def in(ranges: Chunk[(Int, Int)], cp: Int): Boolean =
      ranges.exists((lo, hi) => cp >= lo && cp <= hi)

    /** Combining marks, joiners and variation selectors: no cell. */
    private val zero: Chunk[(Int, Int)] = Chunk(
      (0x0300, 0x036f),
      (0x0483, 0x0489),
      (0x0591, 0x05bd),
      (0x0610, 0x061a),
      (0x064b, 0x065f),
      (0x1ab0, 0x1aff),
      (0x1dc0, 0x1dff),
      (0x200b, 0x200f),
      (0x20d0, 0x20ff),
      (0xfe00, 0xfe0f),
      (0xfe20, 0xfe2f),
      (0xe0100, 0xe01ef),
    )

    /** Wide and fullwidth characters: two cells. */
    private val wide: Chunk[(Int, Int)] = Chunk(
      (0x1100, 0x115f),
      (0x231a, 0x231b),
      (0x2329, 0x232a),
      (0x23e9, 0x23ec),
      (0x23f0, 0x23f3),
      (0x25fd, 0x25fe),
      (0x2614, 0x2615),
      (0x2648, 0x2653),
      (0x26a1, 0x26a1),
      (0x26aa, 0x26ab),
      (0x26bd, 0x26be),
      (0x26c4, 0x26c5),
      (0x26d4, 0x26d4),
      (0x26ea, 0x26ea),
      (0x26f2, 0x26f5),
      (0x26fa, 0x26fd),
      (0x2705, 0x2705),
      (0x270a, 0x270b),
      (0x2728, 0x2728),
      (0x274c, 0x274e),
      (0x2753, 0x2757),
      (0x2795, 0x2797),
      (0x27b0, 0x27bf),
      (0x2b1b, 0x2b1c),
      (0x2b50, 0x2b55),
      (0x2e80, 0x303e),
      (0x3041, 0x33ff),
      (0x3400, 0x4dbf),
      (0x4e00, 0x9fff),
      (0xa000, 0xa4cf),
      (0xa960, 0xa97f),
      (0xac00, 0xd7a3),
      (0xf900, 0xfaff),
      (0xfe10, 0xfe19),
      (0xfe30, 0xfe6f),
      (0xff00, 0xff60),
      (0xffe0, 0xffe6),
      (0x1f004, 0x1f004),
      (0x1f0cf, 0x1f0cf),
      (0x1f18e, 0x1f19a),
      (0x1f200, 0x1f251),
      (0x1f300, 0x1f64f),
      (0x1f680, 0x1f6ff),
      (0x1f7e0, 0x1f7eb),
      (0x1f900, 0x1f9ff),
      (0x1fa70, 0x1faff),
      (0x20000, 0x2fffd),
      (0x30000, 0x3fffd),
    )
end Looks
