package tui4s

/** An input event from the terminal: a [[Key]], a [[Mouse]] event, a
  * [[Resize]], a [[Paste]], [[FocusGained]] or [[FocusLost]].
  */
sealed trait Event derives CanEqual

/** A key press.
  *
  * Printable characters arrive as `Key.Char` with Shift already applied
  * (`Key.Char('A')`, `Key.Char(' ')`). Ctrl with a letter is `Key.Ctrl` in
  * lower case (`Key.Ctrl('s')`). A named key with modifiers is
  * `Key.Modified(Key.Up, Mods(shift = true))`.
  */
enum Key extends Event derives CanEqual:
    /** A printable character, Shift already applied. */
    case Char(c: scala.Char)

    /** Ctrl and a letter, lower case: `Key.Ctrl('s')`. */
    case Ctrl(c: scala.Char)

    /** Alt and a character. */
    case Alt(c: scala.Char)

    /** A named key. */
    case Enter, Esc, Tab, BackTab, Backspace, Delete, Insert

    /** A cursor key. */
    case Up, Down, Left, Right, Home, End, PageUp, PageDown

    /** A function key: `Key.F(5)`. */
    case F(n: Int)

    /** A named key with modifiers: Shift+Up, Ctrl+Left. */
    case Modified(key: Key, mods: Mods)

    /** The text for help bars and traces: "+", "space", "ctrl+s", "alt+x", "↑",
      * "shift+↑", "tab", "f5".
      */
    def show: String = this match
        case Key.Char(c)             => Key.charName(c)
        case Key.Ctrl(c)             => "ctrl+" + Key.charName(c)
        case Key.Alt(c)              => "alt+" + Key.charName(c)
        case Key.Enter               => "enter"
        case Key.Esc                 => "esc"
        case Key.Tab                 => "tab"
        case Key.BackTab             => "shift+tab"
        case Key.Backspace           => "backspace"
        case Key.Delete              => "del"
        case Key.Insert              => "ins"
        case Key.Up                  => "↑"
        case Key.Down                => "↓"
        case Key.Left                => "←"
        case Key.Right               => "→"
        case Key.Home                => "home"
        case Key.End                 => "end"
        case Key.PageUp              => "pgup"
        case Key.PageDown            => "pgdn"
        case Key.F(n)                => s"f$n"
        case Key.Modified(key, mods) =>
          (if mods.ctrl then "ctrl+" else "") +
            (if mods.alt then "alt+" else "") +
            (if mods.shift then "shift+" else "") + key.show

    /** The key in messages: `'d'` for a character, otherwise [[show]]. */
    private[tui4s] def quoted: String = this match
        case Key.Char(' ') => "space"
        case Key.Char(c)   => s"'$c'"
        case other         => other.show
end Key

/** Helpers for [[Key]]. */
object Key:
    private def charName(c: scala.Char): String =
      if c == ' ' then "space" else c.toString

/** Modifier keys held with a named key or a mouse event. */
final case class Mods(
    shift: Boolean = false,
    ctrl: Boolean = false,
    alt: Boolean = false,
) derives CanEqual

/** A mouse event. The runtime reports mouse events only when `TuiOptions.mouse`
  * is on.
  *
  * @param kind
  *   what happened
  * @param x
  *   the column, from 0
  * @param y
  *   the row, from 0
  * @param mods
  *   the modifier keys held
  * @param tags
  *   the `.tag(...)` names of the views under the pointer in the last drawn
  *   frame, top-most first
  */
final case class Mouse(
    kind: MouseKind,
    x: Int,
    y: Int,
    mods: Mods,
    tags: List[String],
) extends Event derives CanEqual

/** What a [[Mouse]] event reports. */
enum MouseKind derives CanEqual:
    /** A button went down. */
    case Down(button: Button)

    /** A button went up. */
    case Up(button: Button)

    /** The pointer moved with a button down. */
    case Drag(button: Button)

    /** The pointer moved, or the wheel turned. */
    case Moved, ScrollUp, ScrollDown, ScrollLeft, ScrollRight
end MouseKind

/** A mouse button. */
enum Button derives CanEqual:
    case Left, Right, Middle

/** The terminal has a new size, in columns and rows. */
final case class Resize(width: Int, height: Int) extends Event derives CanEqual

/** Pasted text, as one event (bracketed paste, `TuiOptions.paste`). */
final case class Paste(text: String) extends Event derives CanEqual

/** The terminal window got the focus (`TuiOptions.focusEvents`). */
case object FocusGained extends Event

/** The terminal window lost the focus (`TuiOptions.focusEvents`). */
case object FocusLost extends Event
