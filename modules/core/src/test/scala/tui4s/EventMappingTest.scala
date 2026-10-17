package tui4s

import kyo.*
import kyo.UI.{Keyboard, KeyboardEvent, Modifiers}
import kyo.test.*
import tui.crossterm.{
  Event as CEvent,
  KeyCode,
  KeyEventKind,
  KeyEventState,
  KeyModifiers,
  MediaKeyCode,
  ModifierKeyCode,
  MouseButton,
  MouseEvent,
  MouseEventKind,
}
import tui4s.internal.{EventMapping, Msg, Size}
import tui4s.testkit.ScriptedInput

/** crossterm records to runner messages, built without the native library:
  * every key code, modifier and kind; the canonical events; the events that the
  * runner drops.
  */
// claude 2026-10-13: changed (T1, RC-22): kyo-ui KeyboardEvent, Msg.Paste,
// Msg.Resize; the canonical events (the lesson of the review thread
// Keymap.scala:159 of the old PR 3); no mouse and no focus events (D-30)
class EventMappingTest extends Test[Any]:
    import EventMappingTest.*

    /** The kyo-ui key that the mapping gives for `e` (`None`: dropped). */
    private def mapped(e: CEvent): Option[KeyboardEvent] =
      EventMapping(e, 1L) match
          case Msg.Key(ev, _) => Some(ev)
          case _              => None

    private def wrongOf(table: Seq[(CEvent, KeyboardEvent)]): Seq[String] =
      table.collect {
        case (e, want) if !mapped(e).contains(want) =>
          s"$e -> ${mapped(e)}, want $want"
      }

    // claude 2026-10-13: changed (T1): kyo-ui Keyboard
    "every named key code, without modifiers" in {
      val table = Seq[(KeyCode, KeyboardEvent)](
        KeyCode.Enter()     -> kb(Keyboard.Enter),
        KeyCode.Esc()       -> kb(Keyboard.Escape),
        KeyCode.Tab()       -> kb(Keyboard.Tab),
        KeyCode.BackTab()   -> kb(Keyboard.Tab, Modifiers(shift = true)),
        KeyCode.Backspace() -> kb(Keyboard.Backspace),
        KeyCode.Delete()    -> kb(Keyboard.Delete),
        KeyCode.Insert()    -> kb(Keyboard.Insert),
        KeyCode.Up()        -> kb(Keyboard.ArrowUp),
        KeyCode.Down()      -> kb(Keyboard.ArrowDown),
        KeyCode.Left()      -> kb(Keyboard.ArrowLeft),
        KeyCode.Right()     -> kb(Keyboard.ArrowRight),
        KeyCode.Home()      -> kb(Keyboard.Home),
        KeyCode.End()       -> kb(Keyboard.End),
        KeyCode.PageUp()    -> kb(Keyboard.PageUp),
        KeyCode.PageDown()  -> kb(Keyboard.PageDown),
        KeyCode.F(1)        -> kb(Keyboard.F1),
        KeyCode.F(12)       -> kb(Keyboard.F12),
      )
      val wrong = wrongOf(table.map((c, want) => (key(c), want)))
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    // claude 2026-10-13: changed (T1): kyo-ui KeyboardEvent; META is meta
    "characters: Shift is in the character, Ctrl gives lower case, Alt, Meta" in {
      val table = Seq[(CEvent, KeyboardEvent)](
        key(KeyCode.Char('a'))                 -> kb(Keyboard.Char('a')),
        key(KeyCode.Char('A'), Shift)          -> kb(Keyboard.Char('A'), shift),
        key(KeyCode.Char(' '))                 -> kb(Keyboard.Space),
        key(KeyCode.Char('+'), Shift)          -> kb(Keyboard.Char('+'), shift),
        key(KeyCode.Char('é'))                 -> kb(Keyboard.Char('é')),
        key(KeyCode.Char('s'), Ctrl)           -> kb(Keyboard.Char('s'), ctrl),
        key(KeyCode.Char('S'), Ctrl | Shift)   ->
          kb(Keyboard.Char('s'), Modifiers(ctrl = true, shift = true)),
        key(KeyCode.Char('c'), Ctrl | Alt)     ->
          kb(Keyboard.Char('c'), Modifiers(ctrl = true, alt = true)),
        key(KeyCode.Char('x'), Alt)            -> kb(Keyboard.Char('x'), alt),
        key(KeyCode.Char('X'), Alt | Shift)    ->
          kb(Keyboard.Char('X'), Modifiers(alt = true, shift = true)),
        key(KeyCode.Char('a'), Super)          -> kb(Keyboard.Char('a')),
        key(KeyCode.Char('a'), Hyper | Meta)   ->
          kb(Keyboard.Char('a'), Modifiers(meta = true)),
        key(KeyCode.BackTab(), Shift)          -> kb(Keyboard.Tab, shift),
        key(KeyCode.Char('q'), kind = Repeat)  -> kb(Keyboard.Char('q')),
        key(KeyCode.Char('q'), state = Keypad) -> kb(Keyboard.Char('q')),
      )
      val wrong = wrongOf(table)
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    // claude 2026-10-13: changed (T1): kyo-ui Modifiers (it was Key.Modified)
    "a named key with modifiers keeps them" in {
      val table = Seq[(CEvent, KeyboardEvent)](
        key(KeyCode.Up(), Shift)                -> kb(Keyboard.ArrowUp, shift),
        key(KeyCode.Left(), Ctrl)               -> kb(Keyboard.ArrowLeft, ctrl),
        key(KeyCode.Enter(), Alt)               -> kb(Keyboard.Enter, alt),
        key(KeyCode.Home(), Ctrl | Alt | Shift) ->
          kb(Keyboard.Home, Modifiers(ctrl = true, alt = true, shift = true)),
        key(KeyCode.F(5), Ctrl)                 -> kb(Keyboard.F5, ctrl),
        key(KeyCode.Tab(), Ctrl)                -> kb(Keyboard.Tab, ctrl),
        key(KeyCode.BackTab(), Ctrl | Shift)    ->
          kb(Keyboard.Tab, Modifiers(ctrl = true, shift = true)),
        key(KeyCode.Down(), Super)              -> kb(Keyboard.ArrowDown),
      )
      val wrong = wrongOf(table)
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    // claude 2026-10-13: created (T1): the canonical events
    // The lesson of the review thread Keymap.scala:159 (old PR 3): a key
    // binding must match what a terminal can send. So the mapping gives ONE
    // form per key: a test that binds `Char('a')` with `shift`, or a
    // `Keyboard.Unknown("BackTab")`, would bind a key that never comes.
    "canonical events: Shift gives the upper-case char, Ctrl the lower-case one, BackTab is Shift-Tab" in {
      val letters = ('a' to 'z') ++ ('A' to 'Z')
      val bad     = letters.flatMap { c =>
        val shifted = mapped(key(KeyCode.Char(c), Shift))
        val ctrled  = mapped(key(KeyCode.Char(c), Ctrl))
        val both    = mapped(key(KeyCode.Char(c), Ctrl | Shift))
        Seq(
          Option.when(
            !shifted.contains(kb(Keyboard.Char(c.toUpper), shift)),
          )(s"Shift-$c -> $shifted"),
          Option.when(!ctrled.contains(kb(Keyboard.Char(c.toLower), ctrl)))(
            s"Ctrl-$c -> $ctrled",
          ),
          Option.when(
            !both.contains(
              kb(Keyboard.Char(c.toLower), Modifiers(ctrl = true, shift = true)),
            ),
          )(s"Ctrl-Shift-$c -> $both"),
        ).flatten
      }
      // Every printable ASCII key with Shift: the char of the terminal, never
      // a lower-case letter with `shift`.
      val ascii   = (33 to 126).map(_.toChar).flatMap { c =>
        mapped(key(KeyCode.Char(c), Shift)).collect {
          case KeyboardEvent(Keyboard.Char(got), m, _)
              if !m.shift || got.isLower =>
            s"Shift-'$c' -> $got $m"
        }
      }
      val tab     = Seq(
        key(KeyCode.BackTab()),
        key(KeyCode.BackTab(), Shift),
        key(KeyCode.Tab(), Shift),
      ).map(mapped).distinct
      assert(bad.isEmpty, bad.mkString("\n"))
      assert(ascii.isEmpty, ascii.mkString("\n"))
      assert(tab == Seq(Some(kb(Keyboard.Tab, shift))), tab.toString)
    }

    // claude 2026-10-13: changed (T1): also F13 and half of a surrogate pair
    "a key release, F13, half of a surrogate pair and the keys that the runner does not model are dropped" in {
      val dropped = Seq[CEvent](
        key(KeyCode.Char('a'), kind = Release),
        key(KeyCode.Enter(), kind = Release),
        key(KeyCode.F(13)),
        key(KeyCode.Char('\ud83d')),
        key(KeyCode.Char('\ude00')),
        key(KeyCode.Null()),
        key(KeyCode.CapsLock()),
        key(KeyCode.ScrollLock()),
        key(KeyCode.NumLock()),
        key(KeyCode.PrintScreen()),
        key(KeyCode.Pause()),
        key(KeyCode.Menu()),
        key(KeyCode.KeypadBegin()),
        key(KeyCode.Media(MediaKeyCode.Play)),
        key(KeyCode.Modifier(ModifierKeyCode.LeftShift)),
      )
      val kept    = dropped.filter(e => EventMapping(e, 1L) != null)
      assert(kept.isEmpty, kept.mkString("\n"))
    }

    // claude 2026-10-13: changed (T1): dropped (D-30); PR 5 mapped them
    "mouse and focus events are dropped (no mouse and no focus events: D-30)" in {
      val events = Seq[CEvent](
        CEvent.Mouse(
          MouseEvent(
            MouseEventKind.Down(MouseButton.Left),
            7,
            3,
            KeyModifiers(0),
          ),
        ),
        CEvent.Mouse(
          MouseEvent(MouseEventKind.ScrollUp(), 7, 3, KeyModifiers(0)),
        ),
        CEvent.FocusGained(),
        CEvent.FocusLost(),
      )
      val kept   = events.filter(e => EventMapping(e, 1L) != null)
      assert(kept.isEmpty, kept.mkString("\n"))
    }

    // claude 2026-10-13: changed (T1): Msg.Paste and Msg.Resize with the
    // sequence number (the focus events are dropped)
    "a paste is Msg.Paste with its text as it is; a resize is Msg.Resize; both get the sequence number" in {
      assert(
        EventMapping(CEvent.Paste("a\nb 😀"), 7L) == Msg.Paste("a\nb 😀", 7L),
      )
      assert(
        EventMapping(CEvent.Resize(120, 40), 8L) == Msg.Resize(
          Size(120, 40),
          8L,
        ),
      )
      assert(
        EventMapping(key(KeyCode.Char('a')), 9L) ==
          Msg.Key(kb(Keyboard.Char('a')), 9L),
      )
    }

    // claude 2026-10-13: changed (T1): the raw crossterm event (D-21 a); the
    // input thread maps it
    "CrosstermInput polls, then reads the crossterm event as it is" in {
      val first = key(KeyCode.Char('a'))
      val drop  = key(KeyCode.Up(), kind = Release)
      val fake  = FakeCrossterm(events = Seq(first, drop))
      val input = tui4s.internal.CrosstermInput(fake)
      val one   = input.poll(10) && input.read() == first
      val two   = input.poll(10) && input.read() == drop
      assert(one && two && !input.poll(10))
    }
end EventMappingTest

object EventMappingTest:
    val Shift: Int = KeyModifiers.SHIFT
    val Ctrl: Int  = KeyModifiers.CONTROL
    val Alt: Int   = KeyModifiers.ALT
    val Super: Int = KeyModifiers.SUPER
    val Hyper: Int = KeyModifiers.HYPER
    val Meta: Int  = KeyModifiers.META

    val Press: KeyEventKind   = KeyEventKind.Press
    val Repeat: KeyEventKind  = KeyEventKind.Repeat
    val Release: KeyEventKind = KeyEventKind.Release
    val Keypad: Int           = KeyEventState.KEYPAD

    val shift: Modifiers = Modifiers(shift = true)
    val ctrl: Modifiers  = Modifiers(ctrl = true)
    val alt: Modifiers   = Modifiers(alt = true)

    /** A crossterm key event. */
    def key(
        code: KeyCode,
        mods: Int = 0,
        kind: KeyEventKind = Press,
        state: Int = 0,
    ): CEvent = ScriptedInput.key(code, mods, kind, state)

    /** The kyo-ui key event of a terminal key (no target). */
    def kb(k: Keyboard, m: Modifiers = Modifiers.none): KeyboardEvent =
      KeyboardEvent(k, m, Absent)
end EventMappingTest
