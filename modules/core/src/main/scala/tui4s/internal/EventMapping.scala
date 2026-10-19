package tui4s.internal

import kyo.*
import kyo.UI.{Keyboard, KeyboardEvent, Modifiers}
import tui.crossterm.{
  Event as CEvent,
  KeyCode,
  KeyEvent,
  KeyEventKind,
  KeyEventState,
  KeyModifiers,
}

/** crossterm events to runner messages: a key is a kyo-ui
  * [[kyo.UI.KeyboardEvent]] in [[Msg.Key]], as a browser gives it to kyo-ui; a
  * bracketed paste is [[Msg.Paste]]; a new terminal size is [[Msg.Resize]].
  * Runs on the input thread.
  *
  * The rules (from the prototype's `Keys.scala`; each event is canonical, as a
  * terminal can send it):
  *   - Shift is in the character: Shift-a arrives as `Char('A')` with `shift`
  *     (also from a terminal that reports the base key `a` with Shift); a key
  *     binding on `Char('a')` with `shift` never fires.
  *   - A Ctrl letter is the lower-case letter with `ctrl` (Ctrl-S is
  *     `Char('s')`, also with Shift).
  *   - The space bar is `Keyboard.Space`, Esc is `Keyboard.Escape`.
  *   - BackTab (Shift-Tab) arrives as `Tab` with `shift`.
  *   - crossterm's META is `meta`; SUPER and HYPER are not reported.
  *   - A key release, F13 and above, half of a surrogate pair, the lock, media
  *     and modifier keys, and the mouse and focus events are dropped
  *     (`Absent`). The runner has no mouse and no focus events (D-30).
  *
  * A character outside the Basic Multilingual Plane (an emoji) cannot be a
  * kyo-ui `Keyboard.Char` (one UTF-16 char), and crossterm's `KeyCode.Char`
  * holds one UTF-16 char too. The JNI layer of jatatui 0.31.0 cuts a TYPED
  * character outside the plane to its low 16 bits (U+1F600 arrives as U+F600;
  * measured in a pseudo-terminal), so the mapping cannot see it, and such a key
  * arrives as a wrong character. A PASTED one is right: a bracketed paste is
  * one `Paste` event with a Java string, and the text editing inserts it as it
  * is (the text of `Keyboard.Unknown` is the other way in, which the headless
  * kit uses).
  *
  * Java records are not Scala extractors, so the matches use type patterns.
  * Every match lists all cases of the sealed Java interface, so a new crossterm
  * case is a compile error here (`modules/core/build.sbt` makes the
  * exhaustivity warning of this file an error).
  */
// claude 2026-10-13: changed (T1): the Scaladoc says what jatatui's JNI does
// with a typed character outside the BMP
// claude 2026-10-13: changed (T1, RC-22, D-21 a): to kyo-ui KeyboardEvent,
// Msg.Paste, Msg.Resize (it replaces the prototype's Keys.scala and keeps its
// rules); the mouse and the focus events are dropped (D-30)
private[tui4s] object EventMapping:
    /** The runner message for `e`, with the sequence number `seq`; `Absent` for
      * an event that the runner ignores.
      */
    // claude 2026-10-19: changed: Maybe[Msg], not null (purity plan, P1); a
    // null Java field is Absent on the line that reads it
    def apply(e: CEvent, seq: Long): Maybe[Msg] = e match
        case k: CEvent.Key         =>
          Maybe[KeyEvent](k.keyEvent).flatMap(key).map(Msg.Key(_, seq))
        case p: CEvent.Paste       =>
          Maybe[String](p.string).map(Msg.Paste(_, seq))
        case r: CEvent.Resize      =>
          Present(Msg.Resize(Size(r.columns, r.rows), seq))
        case _: CEvent.Mouse       => Absent
        case _: CEvent.FocusGained => Absent
        case _: CEvent.FocusLost   => Absent

    /** The modifier keys of crossterm's bits (`extraShift`: BackTab). SUPER and
      * HYPER are not reported.
      */
    // claude 2026-10-19: changed: Maybe on the Java field (purity plan, P1)
    def mods(m: Maybe[KeyModifiers], extraShift: Boolean = false): Modifiers =
        val bits = m.fold(0)(_.bits)
        Modifiers(
          ctrl = (bits & KeyModifiers.CONTROL) != 0,
          alt = (bits & KeyModifiers.ALT) != 0,
          shift = extraShift || (bits & KeyModifiers.SHIFT) != 0,
          meta = (bits & KeyModifiers.META) != 0,
        )
    end mods

    /** A key press or repeat as kyo-ui's event; `Absent` for a release and for
      * the keys that the runner does not model.
      */
    // claude 2026-10-19: changed: Maybe[KeyboardEvent], not null (purity plan,
    // P1)
    def key(ke: KeyEvent): Maybe[KeyboardEvent] =
        def ev(k: Keyboard, m: Modifiers): Maybe[KeyboardEvent] =
          Present(KeyboardEvent(k, m, Absent))
        val code                                                = Maybe[KeyCode](ke.code)
        if ke.kind == KeyEventKind.Release then Absent
        else
            val mm = Maybe[KeyModifiers](ke.modifiers)
            val m  = mods(mm)
            code.flatMap { kc =>
              kc match
                  case c: KeyCode.Char        =>
                    // A terminal applies Shift to the character; a terminal that
                    // reports the base key (`a` with Shift) gets the same event.
                    val ch =
                      if m.ctrl then Character.toLowerCase(c.c)
                      else if m.shift then Character.toUpperCase(c.c)
                      else c.c
                    if Character.isSurrogate(ch) then Absent
                    else if ch == ' ' then ev(Keyboard.Space, m)
                    else ev(Keyboard.Char(ch), m)
                  case _: KeyCode.BackTab     =>
                    ev(Keyboard.Tab, mods(mm, extraShift = true))
                  case _: KeyCode.Enter       => ev(Keyboard.Enter, m)
                  case _: KeyCode.Esc         => ev(Keyboard.Escape, m)
                  case _: KeyCode.Tab         => ev(Keyboard.Tab, m)
                  case _: KeyCode.Backspace   => ev(Keyboard.Backspace, m)
                  case _: KeyCode.Delete      => ev(Keyboard.Delete, m)
                  case _: KeyCode.Insert      => ev(Keyboard.Insert, m)
                  case _: KeyCode.Up          => ev(Keyboard.ArrowUp, m)
                  case _: KeyCode.Down        => ev(Keyboard.ArrowDown, m)
                  case _: KeyCode.Left        => ev(Keyboard.ArrowLeft, m)
                  case _: KeyCode.Right       => ev(Keyboard.ArrowRight, m)
                  case _: KeyCode.Home        => ev(Keyboard.Home, m)
                  case _: KeyCode.End         => ev(Keyboard.End, m)
                  case _: KeyCode.PageUp      => ev(Keyboard.PageUp, m)
                  case _: KeyCode.PageDown    => ev(Keyboard.PageDown, m)
                  case f: KeyCode.F           =>
                    EventMapping.function(f.num).flatMap(ev(_, m))
                  case _: KeyCode.Null        => Absent
                  case _: KeyCode.CapsLock    => Absent
                  case _: KeyCode.ScrollLock  => Absent
                  case _: KeyCode.NumLock     => Absent
                  case _: KeyCode.PrintScreen => Absent
                  case _: KeyCode.Pause       => Absent
                  case _: KeyCode.Menu        => Absent
                  case _: KeyCode.KeypadBegin => Absent
                  case _: KeyCode.Media       => Absent
                  case _: KeyCode.Modifier    => Absent
              end match
            }
        end if
    end key

    /** The crossterm key event of a terminal for the typed character `c`, as
      * crossterm's parser of the terminal's bytes gives it: an upper-case
      * character has SHIFT (`A` and `É` have it; `a`, `é`, `+` and `!` have
      * none: measured with jatatui 0.31.0 in a pseudo-terminal), a line break
      * (`\r`, and `\n` in the text of a test) is Enter, and a tab is Tab. The
      * headless kit types with it (`Headless.char` and `typeText`, through
      * [[key]]), so a headless test and a real terminal give the same event.
      */
    // claude 2026-10-13: created (T1, review R1-1): one place for the event of
    // a typed character
    def typed(c: Char): KeyEvent =
        val code: KeyCode =
          if c == '\n' || c == '\r' then KeyCode.Enter()
          else if c == '\t' then KeyCode.Tab()
          else KeyCode.Char(c)
        val bits          =
          if Character.isUpperCase(c) then KeyModifiers.SHIFT else 0
        KeyEvent(
          code,
          KeyModifiers(bits),
          KeyEventKind.Press,
          KeyEventState(0),
        )
    end typed

    /** kyo-ui's function key `n` (F1 to F12); kyo-ui has no F13 and above. */
    // claude 2026-10-13: created (T1)
    private def function(n: Int): Maybe[Keyboard] =
      Keyboard.fromString(s"F$n") match
          case Keyboard.Unknown(_) => Absent
          case k                   => Present(k)
end EventMapping
