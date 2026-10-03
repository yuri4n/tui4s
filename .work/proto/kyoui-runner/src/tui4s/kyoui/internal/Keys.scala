package tui4s.kyoui.internal

import kyo.*
import kyo.UI.Keyboard
import kyo.UI.KeyboardEvent
import kyo.UI.Modifiers
import tui.crossterm.Event as CEvent
import tui.crossterm.KeyCode
import tui.crossterm.KeyEventKind
import tui.crossterm.KeyModifiers

/** crossterm events to runner messages, with kyo-ui's own key type. */
object Keys:

    def mods(m: KeyModifiers, extraShift: Boolean = false): Modifiers =
        Modifiers(
            ctrl = (m.bits & KeyModifiers.CONTROL) != 0,
            alt = (m.bits & KeyModifiers.ALT) != 0,
            shift = extraShift || (m.bits & KeyModifiers.SHIFT) != 0,
            meta = (m.bits & KeyModifiers.META) != 0
        )

    /** `None` for events that the runner ignores (a key release, a media key, mouse and focus events). */
    def toMsg(e: CEvent, seq: Long): Option[Msg] = e match
        case k: CEvent.Key =>
            val ke = k.keyEvent
            if ke.kind == KeyEventKind.Release then None
            else
                val m = mods(ke.modifiers)
                val key: Option[(Keyboard, Modifiers)] = ke.code match
                    case c: KeyCode.Char =>
                        val ch = if m.ctrl then Character.toLowerCase(c.c) else c.c
                        Some((if ch == ' ' then Keyboard.Space else Keyboard.Char(ch), m))
                    case _: KeyCode.Enter     => Some((Keyboard.Enter, m))
                    case _: KeyCode.Esc       => Some((Keyboard.Escape, m))
                    case _: KeyCode.Tab       => Some((Keyboard.Tab, m))
                    case _: KeyCode.BackTab   => Some((Keyboard.Tab, mods(ke.modifiers, extraShift = true)))
                    case _: KeyCode.Backspace => Some((Keyboard.Backspace, m))
                    case _: KeyCode.Delete    => Some((Keyboard.Delete, m))
                    case _: KeyCode.Insert    => Some((Keyboard.Insert, m))
                    case _: KeyCode.Up        => Some((Keyboard.ArrowUp, m))
                    case _: KeyCode.Down      => Some((Keyboard.ArrowDown, m))
                    case _: KeyCode.Left      => Some((Keyboard.ArrowLeft, m))
                    case _: KeyCode.Right     => Some((Keyboard.ArrowRight, m))
                    case _: KeyCode.Home      => Some((Keyboard.Home, m))
                    case _: KeyCode.End       => Some((Keyboard.End, m))
                    case _: KeyCode.PageUp    => Some((Keyboard.PageUp, m))
                    case _: KeyCode.PageDown  => Some((Keyboard.PageDown, m))
                    case f: KeyCode.F =>
                        Keyboard.fromString(s"F${f.num}") match
                            case Keyboard.Unknown(_) => None
                            case k                   => Some((k, m))
                    case _ => None
                key.map((k, m) => Msg.Key(KeyboardEvent(k, m, Absent), seq))
            end if
        case r: CEvent.Resize => Some(Msg.Resize(Size(r.columns, r.rows), seq))
        case _                => None
end Keys
