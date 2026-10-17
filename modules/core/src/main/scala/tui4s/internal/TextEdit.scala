package tui4s.internal

import kyo.UI.{Keyboard, KeyboardEvent}

import java.text.BreakIterator

/** The editing of a single-line text input by grapheme clusters: what a reader
  * sees as one character ("é" written as e + U+0301, "👍🏽", a flag), not a
  * UTF-16 char. So Left, Right, Backspace and Delete never split a character. A
  * caret is a grapheme index: 0 to the number of graphemes of the text.
  *
  * The algorithms come from the old `tui4s.TextField`
  * (origin/feat/core-1-model-view); the events are kyo-ui's
  * [[kyo.UI.KeyboardEvent]]. Each function returns the new text and caret, or
  * the same text and caret when the event changes nothing.
  */
// claude 2026-10-13: created (C3, OS-11, OS-15 part)
private[tui4s] object TextEdit:

    /** The text and the caret after the key `ev`.
      *
      *   - A printable `Char` or `Space` with no Ctrl, Alt or Meta inserts the
      *     character at the caret; a control character is dropped. An `Unknown`
      *     key whose text is printable and not ASCII (a character outside the
      *     BMP, as kyo-ui's `Keyboard.fromString` gives it) inserts that text.
      *     The names of the other keys are ASCII words.
      *   - Backspace and Delete remove one grapheme; ArrowLeft and ArrowRight
      *     move by one grapheme; Home / Ctrl-A and End / Ctrl-E move to the
      *     start and the end.
      *   - Ctrl-U removes the text before the caret, Ctrl-K the text after the
      *     caret, Ctrl-W the word before the caret.
      *
      * With `readOnly`, only the moves work.
      */
    def key(
        text: String,
        caret: Int,
        ev: KeyboardEvent,
        readOnly: Boolean,
    ): (String, Int) =
        val bounds = boundaries(text)
        val size   = bounds.length - 1
        val at     = caret.max(0).min(size)
        val same   = (text, at)
        val m      = ev.modifiers
        val plain  = !m.ctrl && !m.alt && !m.meta
        val ctrl   = m.ctrl && !m.alt && !m.meta
        ev.key match
            case Keyboard.Char(c) if plain                        =>
              if readOnly || Character.isISOControl(c) then same
              else insert(text, bounds, at, c.toString)
            case Keyboard.Space if plain                          =>
              if readOnly then same else insert(text, bounds, at, " ")
            case Keyboard.Unknown(raw) if plain && printable(raw) =>
              if readOnly then same else insert(text, bounds, at, raw)
            case Keyboard.Backspace                               =>
              if readOnly || at == 0 then same
              else remove(text, bounds, at - 1, at)
            case Keyboard.Delete                                  =>
              if readOnly || at >= size then same
              else remove(text, bounds, at, at + 1)
            case Keyboard.ArrowLeft                               => (text, (at - 1).max(0))
            case Keyboard.ArrowRight                              => (text, (at + 1).min(size))
            case Keyboard.Home                                    => (text, 0)
            case Keyboard.End                                     => (text, size)
            case Keyboard.Char(c) if ctrl                         =>
              c.toLower match
                  case 'a' => (text, 0)
                  case 'e' => (text, size)
                  case 'u' =>
                    if readOnly || at == 0 then same
                    else remove(text, bounds, 0, at)
                  case 'k' =>
                    if readOnly || at >= size then same
                    else remove(text, bounds, at, size)
                  case 'w' =>
                    if readOnly || at == 0 then same
                    else remove(text, bounds, wordStart(text, bounds, at), at)
                  case _   => same
            case _                                                => same
        end match
    end key

    /** The text and the caret after a paste of `s` at the caret: one edit. Line
      * breaks and tabs become spaces; other control characters are dropped (see
      * [[singleLine]]).
      */
    def paste(
        text: String,
        caret: Int,
        s: String,
        readOnly: Boolean,
    ): (String, Int) =
        val bounds = boundaries(text)
        val at     = caret.max(0).min(bounds.length - 1)
        val clean  = singleLine(s)
        if readOnly || clean.isEmpty then (text, at)
        else insert(text, bounds, at, clean)
    end paste

    /** The number of graphemes of `s`. */
    def count(s: String): Int = boundaries(s).length - 1

    /** The UTF-16 offsets where grapheme clusters start, plus the length at the
      * end: "ab" -> [0, 1, 2]. The number of graphemes is `length - 1`.
      */
    def boundaries(s: String): Array[Int] =
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
    def graphemes(s: String): Vector[String] =
        val bounds = boundaries(s)
        Vector.tabulate(bounds.length - 1)(i =>
          s.substring(bounds(i), bounds(i + 1)),
        )

    /** `s` for a single-line field: line breaks (`\r\n`, `\n`, `\r`) and tabs
      * become spaces, other control characters are dropped.
      */
    def singleLine(s: String): String =
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

    /** True when the text of an `Unknown` key is a character to insert: not
      * empty, no control character, and not ASCII (the DOM names of the other
      * keys, `Shift`, `F13`, `Unidentified`, are ASCII words).
      */
    private def printable(raw: String): Boolean =
      raw.nonEmpty && raw.exists(_ > '\u007f') &&
        !raw.exists(Character.isISOControl)

    /** Inserts `s` at the grapheme `at`; the caret goes after `s`. */
    private def insert(
        text: String,
        bounds: Array[Int],
        at: Int,
        s: String,
    ): (String, Int) =
        val off     = bounds(at)
        val updated = text.substring(0, off) + s + text.substring(off)
        // a combining mark can join the grapheme before it, so the caret is
        // the number of graphemes up to the end of `s`
        val before  = count(updated.substring(0, off + s.length))
        (updated, before.min(count(updated)))
    end insert

    /** Removes the graphemes `from until to`; the caret goes to `from`. */
    private def remove(
        text: String,
        bounds: Array[Int],
        from: Int,
        to: Int,
    ): (String, Int) =
      (text.substring(0, bounds(from)) + text.substring(bounds(to)), from)

    /** The start of the word before the caret (Ctrl-W): skip white space, then
      * everything up to the previous white space.
      */
    private def wordStart(text: String, bounds: Array[Int], at: Int): Int =
        def blankAt(i: Int): Boolean =
          text.substring(bounds(i), bounds(i + 1)).isBlank
        var i                        = at
        while i > 0 && blankAt(i - 1) do i -= 1
        while i > 0 && !blankAt(i - 1) do i -= 1
        i
end TextEdit
