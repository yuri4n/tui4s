package tui4s.internal

import kyo.UI.{Keyboard, KeyboardEvent}

import java.text.BreakIterator

/** Whether a field takes edits: `Edit`, or `ReadOnly` (kyo-ui's `readOnly`: the
  * caret moves, the text stays).
  */
// claude 2026-10-19: created (L1c): an ADT, not a Boolean
private[tui4s] enum FieldAccess derives CanEqual:
    case Edit, ReadOnly

/** The rules of a field for its edits: its access, the most grapheme clusters
  * that it holds (`max`; `Int.MaxValue` when there is no limit), and the texts
  * that it can hold (`valid`; a number field holds a number). An insert is cut
  * to the clusters that fit; an edit whose result is not `valid` changes
  * nothing.
  */
// claude 2026-10-19: created (L1c)
private[tui4s] final case class Rules(
    access: FieldAccess,
    max: Int,
    valid: String => Boolean,
)

private[tui4s] object Rules:
    /** A field with no rule: it takes every edit. */
    val free: Rules = Rules(FieldAccess.Edit, Int.MaxValue, _ => true)

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
      * The edits follow `rules` ([[Rules]]): with `FieldAccess.ReadOnly` only
      * the moves work.
      */
    // claude 2026-10-19: changed: the rules of the field, not a Boolean (L1c)
    def key(
        text: String,
        caret: Int,
        ev: KeyboardEvent,
        rules: Rules,
    ): (String, Int) =
        val bounds                                 = boundaries(text)
        val size                                   = bounds.length - 1
        val at                                     = caret.max(0).min(size)
        val same                                   = (text, at)
        val m                                      = ev.modifiers
        val plain                                  = !m.ctrl && !m.alt && !m.meta
        val ctrl                                   = m.ctrl && !m.alt && !m.meta
        def ins(s: String): (String, Int)          =
          insert(text, bounds, at, s, rules)
        def rem(from: Int, to: Int): (String, Int) =
          remove(text, bounds, from, to, at, rules)
        ev.key match
            case Keyboard.Char(c) if plain                        =>
              if Character.isISOControl(c) then same else ins(c.toString)
            case Keyboard.Space if plain                          => ins(" ")
            case Keyboard.Unknown(raw) if plain && printable(raw) => ins(raw)
            case Keyboard.Backspace                               =>
              if at == 0 then same else rem(at - 1, at)
            case Keyboard.Delete                                  =>
              if at >= size then same else rem(at, at + 1)
            case Keyboard.ArrowLeft                               => (text, (at - 1).max(0))
            case Keyboard.ArrowRight                              => (text, (at + 1).min(size))
            case Keyboard.Home                                    => (text, 0)
            case Keyboard.End                                     => (text, size)
            case Keyboard.Char(c) if ctrl                         =>
              c.toLower match
                  case 'a' => (text, 0)
                  case 'e' => (text, size)
                  case 'u' => if at == 0 then same else rem(0, at)
                  case 'k' => if at >= size then same else rem(at, size)
                  case 'w' =>
                    if at == 0 then same
                    else rem(wordStart(text, bounds, at), at)
                  case _   => same
            case _                                                => same
        end match
    end key

    /** The text and the caret after a paste of `s` at the caret: one edit. Line
      * breaks and tabs become spaces; other control characters are dropped (see
      * [[singleLine]]). The edit follows `rules`: the paste is cut by clusters
      * to the room that `rules.max` leaves.
      */
    // claude 2026-10-19: changed: the rules of the field, not a Boolean (L1c)
    def paste(
        text: String,
        caret: Int,
        s: String,
        rules: Rules,
    ): (String, Int) =
        val bounds = boundaries(text)
        val at     = caret.max(0).min(bounds.length - 1)
        insert(text, bounds, at, singleLine(s), rules)
    end paste

    /** The number of graphemes of `s`. */
    def count(s: String): Int = boundaries(s).length - 1

    /** The UTF-16 offsets where grapheme clusters start, plus the length at the
      * end: "ab" -> [0, 1, 2]. The number of graphemes is `length - 1`.
      */
    // claude 2026-10-19: changed: the boundaries from an Iterator (purity
    // plan, P1)
    def boundaries(s: String): Array[Int] =
      if s.isEmpty then Array(0)
      else
          val it   = BreakIterator.getCharacterInstance()
          it.setText(s)
          val ends = Iterator
            .continually(it.next())
            .takeWhile(_ != BreakIterator.DONE)
          (Iterator.single(0) ++ ends).toArray

    /** The grapheme clusters of `s`, in order. */
    def graphemes(s: String): Vector[String] =
        val bounds = boundaries(s)
        Vector.tabulate(bounds.length - 1)(i =>
          s.substring(bounds(i), bounds(i + 1)),
        )

    /** `s` for a single-line field: line breaks (`\r\n`, `\n`, `\r`) and tabs
      * become spaces, other control characters are dropped.
      */
    // claude 2026-10-19: changed: replace and filterNot (purity plan, P1)
    def singleLine(s: String): String =
      s.replace("\r\n", " ")
        .nn
        .replace('\r', ' ')
        .nn
        .replace('\n', ' ')
        .nn
        .replace('\t', ' ')
        .nn
        .filterNot(Character.isISOControl)

    /** True when the text of an `Unknown` key is a character to insert: not
      * empty, no control character, and not ASCII (the DOM names of the other
      * keys, `Shift`, `F13`, `Unidentified`, are ASCII words).
      */
    def printable(raw: String): Boolean =
      raw.nonEmpty && raw.exists(_ > '\u007f') &&
        !raw.exists(Character.isISOControl)

    /** Inserts `s` at the grapheme `at`; the caret goes after `s`. Under
      * `rules`: nothing in a read-only field; `s` is cut to its first clusters
      * that fit in `rules.max` (the cut is by clusters, so no character is
      * split); nothing when the result is not `rules.valid`.
      */
    // claude 2026-10-19: changed: the rules of the field (L1c)
    def insert(
        text: String,
        bounds: Array[Int],
        at: Int,
        s: String,
        rules: Rules,
    ): (String, Int) =
        val room = rules.max.toLong - (bounds.length - 1)
        val cut  =
          if room <= 0 then ""
          else if room >= Int.MaxValue then s
          else graphemes(s).take(room.toInt).mkString
        if rules.access == FieldAccess.ReadOnly || cut.isEmpty then (text, at)
        else
            val off     = bounds(at)
            val updated = text.substring(0, off) + cut + text.substring(off)
            // a combining mark can join the grapheme before it, so the caret
            // is the number of graphemes up to the end of `cut`
            val before  = count(updated.substring(0, off + cut.length))
            if !rules.valid(updated) then (text, at)
            else (updated, before.min(count(updated)))
        end if
    end insert

    /** Removes the graphemes `from until to`; the caret goes to `from`. Under
      * `rules`: nothing (the caret stays at `caret`) in a read-only field, or
      * when the result is not `rules.valid`.
      */
    // claude 2026-10-19: changed: the rules of the field (L1c)
    def remove(
        text: String,
        bounds: Array[Int],
        from: Int,
        to: Int,
        caret: Int,
        rules: Rules,
    ): (String, Int) =
        val updated =
          text.substring(0, bounds(from)) + text.substring(bounds(to))
        if rules.access == FieldAccess.ReadOnly || !rules.valid(updated) then
            (text, caret)
        else (updated, from)
    end remove

    /** The start of the word before the caret (Ctrl-W): skip white space, then
      * everything up to the previous white space.
      */
    // claude 2026-10-19: changed: a tailrec walk back (purity plan, P1)
    def wordStart(text: String, bounds: Array[Int], at: Int): Int =
        def blankAt(i: Int): Boolean          =
          text.substring(bounds(i), bounds(i + 1)).isBlank
        // Goes back from `i` while the grapheme on the left is blank (for
        // `blank = true`) or not blank (for `blank = false`).
        @annotation.tailrec
        def back(i: Int, blank: Boolean): Int =
          if i > 0 && blankAt(i - 1) == blank then back(i - 1, blank) else i
        back(back(at, blank = true), blank = false)
    end wordStart
end TextEdit
