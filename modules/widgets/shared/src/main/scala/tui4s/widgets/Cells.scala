package tui4s.widgets

/** The width of a text in terminal cells, and its cut with "…" at the edge, as
  * the widgets measure it: 2 cells for a wide or fullwidth character (CJK, most
  * emoji), 0 for a combining mark, a joiner, a variation selector or a control
  * character, 1 for the rest. An app that fits a text into a number of cells (a
  * title, a column) uses it, so it measures as the widgets do.
  *
  * {{{
  * Cells.width("中文")              // 4
  * Cells.cut("a long title", 7)     // "a long…"
  * }}}
  */
// claude 2026-10-19: created (L3): the measure of Looks, public for the title
// rule of the assistant
object Cells:
    /** The cells of `s`. */
    def width(s: String): Int = Looks.cells(s)

    /** The cells of the code point `cp`: 0, 1 or 2. */
    def of(cp: Int): Int = Looks.cells(String(Character.toChars(cp)))

    /** `s` when it fits in `max` cells; else its longest start that fits in
      * `max - 1` cells, then "…". A wide character is never cut in half. An
      * empty text for `max` 0 or less.
      */
    def cut(s: String, max: Int): String =
      if max <= 0 then ""
      else if width(s) <= max then s
      else
          @scala.annotation.tailrec
          def go(i: Int, used: Int): Int =
            if i >= s.length then i
            else
                val cp = s.codePointAt(i)
                val w  = of(cp)
                if used + w > max - 1 then i
                else go(i + Character.charCount(cp), used + w)
          s.substring(0, go(0, 0)) + "…"
end Cells
