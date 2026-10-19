package tui4s.internal

import kyo.UI.{Keyboard, KeyboardEvent}

/** How a visual line of a textarea ends: `Wrap`, the text goes on in the next
  * line (a soft wrap); `Newline`, a line break (the grapheme at the end of the
  * line) ends it; `End`, the text ends.
  */
// claude 2026-10-19: created (L1c): an ADT, not a Boolean
private[tui4s] enum Break derives CanEqual:
    case Wrap, Newline, End

/** One visual line of a textarea: the grapheme clusters `start until end` of
  * its text. A line that ends with `Newline` has the line break at `end`; the
  * next line starts after it.
  */
// claude 2026-10-19: created (L1c)
private[tui4s] final case class AreaLine(start: Int, end: Int, brk: Break):
    /** The last caret of the line: `end`, except before a wrap, where `end` is
      * the first caret of the next line.
      */
    def last: Int = brk match
        case Break.Wrap => end - 1
        case _          => end

/** The text of a textarea: its visual lines and the moves and edits of its
  * caret. Pure functions of the text, the caret (a grapheme index, as in
  * [[TextEdit]]) and the width of the field in cells.
  *
  * The lines follow CSS `white-space: pre-wrap` by cells: a line break ends a
  * line; a line that is wider than the field wraps after its last space that
  * fits (the spaces stay at the end of the line), and a word that is wider than
  * the field is split by cells. A grapheme that is wider than the field takes a
  * line of its own. Each grapheme is in one line, so a caret has one place: the
  * caret at the end of a wrapped line shows at the start of the next line.
  *
  * For example, `lines("ab cd\nxyz", 3)` gives `AreaLine(0, 3, Wrap)` ("ab "),
  * `AreaLine(3, 5, Newline)` ("cd") and `AreaLine(6, 9, End)` ("xyz").
  *
  * The keys of the caret ([[key]]): ArrowUp and ArrowDown move it one visual
  * line and keep its column in cells (or go to the end of a shorter line);
  * ArrowUp on the first line goes to the start, ArrowDown on the last line to
  * the end. PageUp and PageDown move it by the height of the field. Home and
  * End go to the ends of the visual line; Ctrl-A and Ctrl-E to the ends of the
  * logical line (the text between two line breaks). Ctrl-U removes the logical
  * line before the caret, Ctrl-K the logical line after it (at the end of a
  * line it joins the next line). The other keys edit as in a text input
  * ([[TextEdit.key]]). A line break is one grapheme: Backspace after it joins
  * two lines.
  */
// claude 2026-10-19: created (L1c): the multi-line textarea
private[tui4s] object AreaText:

    /** True when the grapheme `g` is a line break. */
    def isBreak(g: String): Boolean = g == "\n" || g == "\r\n" || g == "\r"

    /** The cells of the grapheme `g` in a textarea: a line break takes none. */
    def cells(g: String): Int = if isBreak(g) then 0 else Layout.textWidth(g)

    /** The visual lines of `text` in a field `width` cells wide. */
    def lines(text: String, width: Int): Vector[AreaLine] =
      linesOf(TextEdit.graphemes(text), width)

    /** The visual lines of the graphemes `gs` in a field `width` cells wide (at
      * least 1). Law: the lines cover the graphemes in order, each one once
      * (the line breaks are between the lines); a line that ends with `Wrap` is
      * not empty; the last line ends with `End`.
      */
    def linesOf(gs: Vector[String], width: Int): Vector[AreaLine] =
        val w      = width.max(1)
        val ws     = gs.map(cells)
        // the starts of the logical lines and the breaks that end them
        val breaks = gs.indices.filter(i => isBreak(gs(i))).toVector
        val starts = 0 +: breaks.map(_ + 1)
        val ends   = breaks :+ gs.size
        starts.zip(ends).zipWithIndex.flatMap { case ((s, e), i) =>
          wrap(
            gs,
            ws,
            s,
            e,
            w,
            if i == breaks.size then Break.End else Break.Newline,
          )
        }
    end linesOf

    /** The visual lines of the logical line `s until e`; the last one ends with
      * `last`.
      */
    private def wrap(
        gs: Vector[String],
        ws: Vector[Int],
        s: Int,
        e: Int,
        w: Int,
        last: Break,
    ): Vector[AreaLine] =
        def blank(i: Int): Boolean                              = gs(i).isBlank
        @annotation.tailrec
        def spaces(j: Int): Int                                 = if j < e && blank(j) then spaces(j + 1) else j
        // the end of the line that starts at `i`: its graphemes that fit (at
        // least one); at a space that does not fit, the spaces after it too
        // (they stay at the end of the line); else after the last space that
        // fits; else (one long word) at the first grapheme that does not fit
        def lineEnd(i: Int): Int                                =
            @annotation.tailrec
            def from(j: Int, used: Int, space: Int): Int =
              if j < e && (j == i || used + ws(j) <= w) then
                  from(j + 1, used + ws(j), if blank(j) then j + 1 else space)
              else if j >= e then e
              else if blank(j) then spaces(j)
              else if space > i then space
              else j
            from(i, 0, -1)
        end lineEnd
        @annotation.tailrec
        def go(i: Int, acc: Vector[AreaLine]): Vector[AreaLine] =
            val end = lineEnd(i)
            if end >= e then acc :+ AreaLine(i, e, last)
            else go(end, acc :+ AreaLine(i, end, Break.Wrap))
        if s >= e then Vector(AreaLine(s, e, last)) else go(s, Vector.empty)
    end wrap

    /** The index of the line of `lines` that holds `caret`. */
    def row(lines: Vector[AreaLine], caret: Int): Int =
        val i = lines.indexWhere(l => caret >= l.start && caret <= l.last)
        if i >= 0 then i else (lines.size - 1).max(0)

    /** The column of `caret` in its line `l`: the cells of the graphemes before
      * it.
      */
    def column(gs: Vector[String], l: AreaLine, caret: Int): Int =
      (l.start until caret.min(l.end)).foldLeft(0)((c, i) => c + cells(gs(i)))

    /** The caret of the line `l` at the column `col`: the last caret of the
      * line whose column is at most `col` (the end of a shorter line).
      */
    def caretAt(gs: Vector[String], l: AreaLine, col: Int): Int =
        @annotation.tailrec
        def go(c: Int, used: Int): Int =
          if c < l.last && used + cells(gs(c)) <= col then
              go(c + 1, used + cells(gs(c)))
          else c
        go(l.start, 0)

    /** The (row, column) of `caret` in `text` laid out `width` cells wide. */
    def place(text: String, caret: Int, width: Int): (Int, Int) =
        val gs = TextEdit.graphemes(text)
        val ls = linesOf(gs, width)
        val c  = caret.max(0).min(gs.size)
        val r  = row(ls, c)
        (r, column(gs, ls(r), c))

    /** True when `ev` is a newline key of a textarea that never submits a form
      * (D7): Alt-Enter, Shift-Enter (when the terminal reports Shift with
      * Enter) and Ctrl-J. A plain Enter inserts a line break only outside a
      * form; the runner decides that (see [[tui4s.internal.Dispatch]]).
      */
    def isNewline(ev: KeyboardEvent): Boolean =
        val m = ev.modifiers
        ev.key match
            case Keyboard.Enter   => (m.alt || m.shift) && !m.ctrl && !m.meta
            case Keyboard.Char(c) =>
              m.ctrl && !m.alt && !m.meta && c.toLower == 'j'
            case _                => false

    /** The text and the caret after a line break at the caret, under `rules`. */
    def newline(text: String, caret: Int, rules: Rules): (String, Int) =
        val bounds = TextEdit.boundaries(text)
        TextEdit.insert(
          text,
          bounds,
          caret.max(0).min(bounds.length - 1),
          "\n",
          rules,
        )
    end newline

    /** The text and the caret after a paste of `s` at the caret: one edit that
      * keeps the line breaks (`\r\n` and `\r` become `\n`). A tab becomes a
      * space; other control characters are dropped. The paste is cut by
      * clusters to the room that `rules.max` leaves.
      */
    def paste(
        text: String,
        caret: Int,
        s: String,
        rules: Rules,
    ): (String, Int) =
        val clean  = s
          .replace("\r\n", "\n")
          .nn
          .replace('\r', '\n')
          .nn
          .replace('\t', ' ')
          .nn
          .filterNot(c => c != '\n' && Character.isISOControl(c))
        val bounds = TextEdit.boundaries(text)
        TextEdit.insert(
          text,
          bounds,
          caret.max(0).min(bounds.length - 1),
          clean,
          rules,
        )
    end paste

    /** The text and the caret after the key `ev` in a textarea `width` cells
      * wide and `height` rows high (the port of its view), under `rules`. The
      * line keys are the keys of the object; a plain Enter changes nothing here
      * (the runner inserts the line break or submits the form); every other key
      * edits as [[TextEdit.key]] does.
      */
    def key(
        text: String,
        caret: Int,
        ev: KeyboardEvent,
        width: Int,
        height: Int,
        rules: Rules,
    ): (String, Int) =
        val gs                                     = TextEdit.graphemes(text)
        val n                                      = gs.size
        val at                                     = caret.max(0).min(n)
        val ls                                     = linesOf(gs, width)
        val r                                      = row(ls, at)
        val m                                      = ev.modifiers
        val plain                                  = !m.ctrl && !m.alt && !m.meta
        val ctrl                                   = m.ctrl && !m.alt && !m.meta
        val bounds                                 = TextEdit.boundaries(text)
        // the ends of the logical line of the caret
        val home                                   = (at - 1 to 0 by -1).find(i => isBreak(gs(i))).fold(0)(_ + 1)
        val end                                    = (at until n).find(i => isBreak(gs(i))).getOrElse(n)
        def toRow(target: Int): Int                =
          if target < 0 then 0
          else if target >= ls.size then n
          else caretAt(gs, ls(target), column(gs, ls(r), at))
        def rem(from: Int, to: Int): (String, Int) =
          if from >= to then (text, at)
          else TextEdit.remove(text, bounds, from, to, at, rules)
        val page                                   = height.max(1)
        ev.key match
            case Keyboard.ArrowUp if plain   => (text, toRow(r - 1))
            case Keyboard.ArrowDown if plain => (text, toRow(r + 1))
            case Keyboard.PageUp if plain    =>
              (text, toRow(if r == 0 then -1 else (r - page).max(0)))
            case Keyboard.PageDown if plain  =>
              (
                text,
                toRow(if r == ls.size - 1 then ls.size
                else (r + page).min(ls.size - 1)),
              )
            case Keyboard.Home if plain      => (text, ls(r).start)
            case Keyboard.End if plain       => (text, ls(r).last)
            case Keyboard.Enter              => (text, at)
            case Keyboard.Char(c) if ctrl    =>
              c.toLower match
                  case 'a' => (text, home)
                  case 'e' => (text, end)
                  case 'u' => rem(home, at)
                  case 'k' =>
                    if at == end then rem(at, (at + 1).min(n)) else rem(at, end)
                  case _   => TextEdit.key(text, at, ev, rules)
            case _                           => TextEdit.key(text, at, ev, rules)
        end match
    end key
end AreaText
