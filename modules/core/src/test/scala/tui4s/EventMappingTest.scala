package tui4s

import kyo.test.*
import tui.crossterm.Event as CEvent
import tui.crossterm.KeyCode
import tui.crossterm.KeyEvent
import tui.crossterm.KeyEventKind
import tui.crossterm.KeyEventState
import tui.crossterm.KeyModifiers
import tui.crossterm.MediaKeyCode
import tui.crossterm.ModifierKeyCode
import tui.crossterm.MouseButton
import tui.crossterm.MouseEvent
import tui.crossterm.MouseEventKind
import tui4s.internal.EventMapping

/** crossterm records to tui4s events (SPEC 2.4), built without the native
  * library: every key code, modifier and kind.
  */
class EventMappingTest extends Test[Any]:
    import EventMappingTest.*

    "every named key code, without modifiers" in {
      val table = Seq[(KeyCode, Key)](
        KeyCode.Enter()     -> Key.Enter,
        KeyCode.Esc()       -> Key.Esc,
        KeyCode.Tab()       -> Key.Tab,
        KeyCode.BackTab()   -> Key.BackTab,
        KeyCode.Backspace() -> Key.Backspace,
        KeyCode.Delete()    -> Key.Delete,
        KeyCode.Insert()    -> Key.Insert,
        KeyCode.Up()        -> Key.Up,
        KeyCode.Down()      -> Key.Down,
        KeyCode.Left()      -> Key.Left,
        KeyCode.Right()     -> Key.Right,
        KeyCode.Home()      -> Key.Home,
        KeyCode.End()       -> Key.End,
        KeyCode.PageUp()    -> Key.PageUp,
        KeyCode.PageDown()  -> Key.PageDown,
        KeyCode.F(1)        -> Key.F(1),
        KeyCode.F(12)       -> Key.F(12),
      )
      val wrong = table.collect {
        case (code, want) if EventMapping(key(code)) != want =>
          s"$code -> ${EventMapping(key(code))}, want $want"
      }
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    "characters: Shift is in the character, Ctrl gives lower case, Alt" in {
      val table = Seq[(CEvent, Event)](
        key(KeyCode.Char('a'))                 -> Key.Char('a'),
        key(KeyCode.Char('A'), Shift)          -> Key.Char('A'),
        key(KeyCode.Char(' '))                 -> Key.Char(' '),
        key(KeyCode.Char('+'), Shift)          -> Key.Char('+'),
        key(KeyCode.Char('é'))                 -> Key.Char('é'),
        key(KeyCode.Char('s'), Ctrl)           -> Key.Ctrl('s'),
        key(KeyCode.Char('S'), Ctrl | Shift)   -> Key.Ctrl('s'),
        key(KeyCode.Char('c'), Ctrl | Alt)     -> Key.Ctrl('c'),
        key(KeyCode.Char('x'), Alt)            -> Key.Alt('x'),
        key(KeyCode.Char('X'), Alt | Shift)    -> Key.Alt('X'),
        key(KeyCode.Char('a'), Super)          -> Key.Char('a'),
        key(KeyCode.Char('a'), Hyper | Meta)   -> Key.Char('a'),
        key(KeyCode.BackTab(), Shift)          -> Key.BackTab,
        key(KeyCode.Char('q'), kind = Repeat)  -> Key.Char('q'),
        key(KeyCode.Char('q'), state = Keypad) -> Key.Char('q'),
      )
      val wrong = table.collect {
        case (e, want) if EventMapping(e) != want =>
          s"$e -> ${EventMapping(e)}, want $want"
      }
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    "a named key with modifiers is Key.Modified" in {
      val table = Seq[(CEvent, Event)](
        key(KeyCode.Up(), Shift)                -> Key.Modified(Key.Up, Mods(shift = true)),
        key(KeyCode.Left(), Ctrl)               -> Key.Modified(Key.Left, Mods(ctrl = true)),
        key(KeyCode.Enter(), Alt)               -> Key.Modified(Key.Enter, Mods(alt = true)),
        key(KeyCode.Home(), Ctrl | Alt | Shift) ->
          Key.Modified(Key.Home, Mods(shift = true, ctrl = true, alt = true)),
        key(KeyCode.F(5), Ctrl)                 -> Key.Modified(Key.F(5), Mods(ctrl = true)),
        key(KeyCode.Tab(), Ctrl)                -> Key.Modified(Key.Tab, Mods(ctrl = true)),
        key(KeyCode.Down(), Super)              -> Key.Down,
      )
      val wrong = table.collect {
        case (e, want) if EventMapping(e) != want =>
          s"$e -> ${EventMapping(e)}, want $want"
      }
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    "a key release and keys that tui4s does not model are dropped" in {
      val dropped = Seq[CEvent](
        key(KeyCode.Char('a'), kind = Release),
        key(KeyCode.Enter(), kind = Release),
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
      val kept    = dropped.filter(e => EventMapping(e) != null)
      assert(kept.isEmpty, kept.mkString("\n"))
    }

    "mouse events: every kind and button, the position and the modifiers" in {
      def mouse(kind: MouseEventKind, mods: Int = 0): CEvent =
        CEvent.Mouse(MouseEvent(kind, 7, 3, KeyModifiers(mods)))
      val table                                              = Seq[(CEvent, Event)](
        mouse(MouseEventKind.Down(MouseButton.Left))               ->
          Mouse(MouseKind.Down(Button.Left), 7, 3, Mods(), Nil),
        mouse(MouseEventKind.Up(MouseButton.Right))                ->
          Mouse(MouseKind.Up(Button.Right), 7, 3, Mods(), Nil),
        mouse(MouseEventKind.Drag(MouseButton.Middle))             ->
          Mouse(MouseKind.Drag(Button.Middle), 7, 3, Mods(), Nil),
        mouse(MouseEventKind.Moved())                              ->
          Mouse(MouseKind.Moved, 7, 3, Mods(), Nil),
        mouse(MouseEventKind.ScrollUp())                           ->
          Mouse(MouseKind.ScrollUp, 7, 3, Mods(), Nil),
        mouse(MouseEventKind.ScrollDown())                         ->
          Mouse(MouseKind.ScrollDown, 7, 3, Mods(), Nil),
        mouse(MouseEventKind.ScrollLeft())                         ->
          Mouse(MouseKind.ScrollLeft, 7, 3, Mods(), Nil),
        mouse(MouseEventKind.ScrollRight())                        ->
          Mouse(MouseKind.ScrollRight, 7, 3, Mods(), Nil),
        mouse(MouseEventKind.Down(MouseButton.Left), Ctrl | Shift) ->
          Mouse(
            MouseKind.Down(Button.Left),
            7,
            3,
            Mods(shift = true, ctrl = true),
            Nil,
          ),
      )
      val wrong                                              = table.collect {
        case (e, want) if EventMapping(e) != want =>
          s"$e -> ${EventMapping(e)}, want $want"
      }
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    "paste, resize and focus keep their names" in {
      assert(EventMapping(CEvent.Paste("a\nb 😀")) == Paste("a\nb 😀"))
      assert(EventMapping(CEvent.Resize(120, 40)) == Resize(120, 40))
      assert(EventMapping(CEvent.FocusGained()) == FocusGained)
      assert(EventMapping(CEvent.FocusLost()) == FocusLost)
    }

    "CrosstermInput polls, then reads and maps" in {
      val fake  = FakeCrossterm(
        events = Seq(key(KeyCode.Char('a')), key(KeyCode.Up(), kind = Release)),
      )
      val input = tui4s.internal.CrosstermInput(fake)
      val first = input.poll(10) && input.read() == Key.Char('a')
      val drop  = input.poll(10) && input.read() == null
      assert(first && drop && !input.poll(10))
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

    /** A crossterm key event. */
    def key(
        code: KeyCode,
        mods: Int = 0,
        kind: KeyEventKind = Press,
        state: Int = 0,
    ): CEvent =
      CEvent.Key(KeyEvent(code, KeyModifiers(mods), kind, KeyEventState(state)))
end EventMappingTest
