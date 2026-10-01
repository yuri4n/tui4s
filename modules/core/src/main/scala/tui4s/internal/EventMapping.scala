package tui4s.internal

import tui.crossterm.Event as CEvent
import tui.crossterm.KeyCode
import tui.crossterm.KeyEvent
import tui.crossterm.KeyEventKind
import tui.crossterm.KeyModifiers
import tui.crossterm.MouseButton
import tui.crossterm.MouseEvent
import tui.crossterm.MouseEventKind
import tui4s.*

/** crossterm events to tui4s events (SPEC 2.4). Runs on the input thread.
  *
  * Java records are not Scala extractors, so the matches use type patterns.
  * Every match lists all cases of the sealed Java interface, so a new crossterm
  * case is a compile warning here.
  */
private[tui4s] object EventMapping:
    /** The tui4s event for `e`; `null` for an event that tui4s ignores (a key
      * release, a media key, a modifier key alone).
      */
    def apply(e: CEvent): Event | Null = e match
        case k: CEvent.Key         => key(k.keyEvent)
        case m: CEvent.Mouse       => mouse(m.mouseEvent)
        case p: CEvent.Paste       => Paste(p.string)
        case r: CEvent.Resize      => Resize(r.columns, r.rows)
        case _: CEvent.FocusGained => FocusGained
        case _: CEvent.FocusLost   => FocusLost

    /** The modifier keys of crossterm's bits. SUPER, HYPER and META are not
      * reported.
      */
    def mods(m: KeyModifiers): Mods =
      Mods(
        shift = (m.bits & KeyModifiers.SHIFT) != 0,
        ctrl = (m.bits & KeyModifiers.CONTROL) != 0,
        alt = (m.bits & KeyModifiers.ALT) != 0,
      )

    /** A key press or repeat; `null` for a release and for keys that tui4s does
      * not model.
      */
    def key(ke: KeyEvent): Key | Null =
      if ke.kind == KeyEventKind.Release then null
      else
          val m                  = mods(ke.modifiers)
          def named(k: Key): Key =
            if m.ctrl || m.alt || m.shift then Key.Modified(k, m) else k
          ke.code match
              case c: KeyCode.Char        =>
                // Shift is already applied to the character.
                if m.ctrl then Key.Ctrl(Character.toLowerCase(c.c))
                else if m.alt then Key.Alt(c.c)
                else Key.Char(c.c)
              case _: KeyCode.BackTab     => Key.BackTab
              case _: KeyCode.Enter       => named(Key.Enter)
              case _: KeyCode.Esc         => named(Key.Esc)
              case _: KeyCode.Tab         => named(Key.Tab)
              case _: KeyCode.Backspace   => named(Key.Backspace)
              case _: KeyCode.Delete      => named(Key.Delete)
              case _: KeyCode.Insert      => named(Key.Insert)
              case _: KeyCode.Up          => named(Key.Up)
              case _: KeyCode.Down        => named(Key.Down)
              case _: KeyCode.Left        => named(Key.Left)
              case _: KeyCode.Right       => named(Key.Right)
              case _: KeyCode.Home        => named(Key.Home)
              case _: KeyCode.End         => named(Key.End)
              case _: KeyCode.PageUp      => named(Key.PageUp)
              case _: KeyCode.PageDown    => named(Key.PageDown)
              case f: KeyCode.F           => named(Key.F(f.num))
              case _: KeyCode.Null        => null
              case _: KeyCode.CapsLock    => null
              case _: KeyCode.ScrollLock  => null
              case _: KeyCode.NumLock     => null
              case _: KeyCode.PrintScreen => null
              case _: KeyCode.Pause       => null
              case _: KeyCode.Menu        => null
              case _: KeyCode.KeypadBegin => null
              case _: KeyCode.Media       => null
              case _: KeyCode.Modifier    => null
          end match

    /** A mouse event; the tags are filled on the render fiber. */
    def mouse(me: MouseEvent): Mouse =
        val kind = me.kind match
            case d: MouseEventKind.Down        => MouseKind.Down(button(d.mouseButton))
            case u: MouseEventKind.Up          => MouseKind.Up(button(u.mouseButton))
            case d: MouseEventKind.Drag        => MouseKind.Drag(button(d.mouseButton))
            case _: MouseEventKind.Moved       => MouseKind.Moved
            case _: MouseEventKind.ScrollUp    => MouseKind.ScrollUp
            case _: MouseEventKind.ScrollDown  => MouseKind.ScrollDown
            case _: MouseEventKind.ScrollLeft  => MouseKind.ScrollLeft
            case _: MouseEventKind.ScrollRight => MouseKind.ScrollRight
        Mouse(kind, me.column, me.row, mods(me.modifiers), Nil)
    end mouse

    private def button(b: MouseButton): Button = b match
        case MouseButton.Left   => Button.Left
        case MouseButton.Right  => Button.Right
        case MouseButton.Middle => Button.Middle
end EventMapping
