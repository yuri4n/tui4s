package tui4s

import _root_.java.text.BreakIterator
import _root_.tui4s.internal.discard

/** A single-line text editor value. Keep it in the app state and show it with
  * `input(field)`.
  *
  * `cursor` counts grapheme clusters (what a reader sees as one character: "é"
  * written as e + U+0301, "👍🏽", a flag), not UTF-16 chars. So Left, Right and
  * Backspace never split a character.
  *
  * {{{
  * def typed(event: Event): Todo =
  *   val f = field.edit(event)
  *   if f eq field then this else copy(field = f)
  * }}}
  *
  * @param text
  *   the text
  * @param cursor
  *   the cursor position, in grapheme clusters from the start (0 to the number
  *   of graphemes)
  */
final case class TextField private (text: String, cursor: Int) derives CanEqual:

    /** The value after `event`.
      *
      *   - A printable `Key.Char` inserts the character at the cursor.
      *   - `Paste` inserts the text; line breaks and tabs become spaces, other
      *     control characters are dropped.
      *   - Backspace and Delete remove one grapheme; Left and Right move by one
      *     grapheme; Home / Ctrl-A and End / Ctrl-E move to the start and the
      *     end.
      *   - Ctrl-U removes the text before the cursor, Ctrl-K the text after the
      *     cursor, Ctrl-W the word before the cursor.
      *
      * Any other event, and a key that changes nothing (Left at the start),
      * returns `this`: the same object, so that the runtime skips the frame.
      */
    def edit(event: Event): TextField =
        val bounds = TextField.boundaries(text)
        val size   = bounds.length - 1
        event match
            case Key.Char(c)              =>
              if Character.isISOControl(c) then this
              else insert(bounds, c.toString)
            case Paste(s)                 =>
              val clean = TextField.singleLine(s)
              if clean.isEmpty then this else insert(bounds, clean)
            case Key.Backspace            =>
              if cursor == 0 then this else remove(bounds, cursor - 1, cursor)
            case Key.Delete               =>
              if cursor >= size then this
              else remove(bounds, cursor, cursor + 1)
            case Key.Left                 =>
              if cursor == 0 then this else new TextField(text, cursor - 1)
            case Key.Right                =>
              if cursor >= size then this else new TextField(text, cursor + 1)
            case Key.Home | Key.Ctrl('a') =>
              if cursor == 0 then this else new TextField(text, 0)
            case Key.End | Key.Ctrl('e')  =>
              if cursor >= size then this else new TextField(text, size)
            case Key.Ctrl('u')            =>
              if cursor == 0 then this else remove(bounds, 0, cursor)
            case Key.Ctrl('k')            =>
              if cursor >= size then this else remove(bounds, cursor, size)
            case Key.Ctrl('w')            =>
              if cursor == 0 then this
              else remove(bounds, wordStart(bounds), cursor)
            case _                        => this
        end match
    end edit

    /** True when the text is empty or only white space. */
    def isBlank: Boolean = text.isBlank

    /** The empty field. */
    def cleared: TextField = if text.isEmpty then this else TextField.empty

    private def insert(bounds: Array[Int], s: String): TextField =
        val at      = bounds(cursor)
        val updated = text.substring(0, at) + s + text.substring(at)
        val before  = TextField.boundaries(updated.substring(0, at + s.length))
        val total   = TextField.boundaries(updated).length - 1
        new TextField(updated, (before.length - 1).min(total))

    /** Removes the graphemes `from until to` and puts the cursor at `from`. */
    private def remove(bounds: Array[Int], from: Int, to: Int): TextField =
        val updated = text.substring(0, bounds(from)) +
          text.substring(bounds(to))
        new TextField(updated, from)

    /** The start of the word before the cursor (Ctrl-W): skip white space, then
      * everything up to the previous white space.
      */
    private def wordStart(bounds: Array[Int]): Int =
        def blankAt(i: Int): Boolean =
          text.substring(bounds(i), bounds(i + 1)).isBlank
        var i                        = cursor
        while i > 0 && blankAt(i - 1) do i -= 1
        while i > 0 && !blankAt(i - 1) do i -= 1
        i
end TextField

/** Builds text fields. */
object TextField:
    /** The empty field. */
    val empty: TextField = new TextField("", 0)

    /** A field with `text`, the cursor at the end. */
    def apply(text: String): TextField =
      new TextField(text, boundaries(text).length - 1)

    /** The UTF-16 offsets where grapheme clusters start, plus the length at the
      * end: "ab" -> [0, 1, 2]. The number of graphemes is `length - 1`.
      */
    private[tui4s] def boundaries(s: String): Array[Int] =
        val b = Array.newBuilder[Int]
        b += 0
        if s.nonEmpty then
            val it  = BreakIterator.getCharacterInstance()
            it.setText(s)
            var end = it.next()
            while end != BreakIterator.DONE do
                b += end
                end = it.next()
        b.result()
    end boundaries

    /** The grapheme clusters of `s`, in order. */
    private[tui4s] def graphemes(s: String): Vector[String] =
        val bounds = boundaries(s)
        Vector.tabulate(bounds.length - 1)(i =>
          s.substring(bounds(i), bounds(i + 1)),
        )

    /** `s` for a single-line field: line breaks and tabs become spaces, other
      * control characters are dropped.
      */
    private def singleLine(s: String): String =
        val out = new java.lang.StringBuilder(s.length)
        var i   = 0
        while i < s.length do
            val c = s.charAt(i)
            if c == '\r' && i + 1 < s.length && s.charAt(i + 1) == '\n' then
                discard(out.append(' '))
                i += 1
            else if c == '\r' || c == '\n' || c == '\t' then
                discard(out.append(' '))
            else if !Character.isISOControl(c) then discard(out.append(c))
            i += 1
        end while
        out.toString
    end singleLine
end TextField
