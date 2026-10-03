package tui4s

sealed trait Event derives CanEqual

enum Key extends Event derives CanEqual:
  case Char(c: scala.Char)            // printable; Shift is already applied: Key.Char('A'), Key.Char(' ')
  case Ctrl(c: scala.Char)            // Ctrl + letter, lower case: Key.Ctrl('s')
  case Alt(c: scala.Char)
  case Enter, Esc, Tab, BackTab, Backspace, Delete, Insert
  case Up, Down, Left, Right, Home, End, PageUp, PageDown
  case F(n: Int)
  case Modified(key: Key, mods: Mods) // a named key with modifiers: Shift+Up, Ctrl+Left

  /** Help-bar and trace text: "+", "space", "ctrl+s", "alt+x", "↑", "shift+↑", "tab", "f5". */
  def show: String = this match
    case Key.Char(' ')          => "space"
    case Key.Char(c)            => c.toString
    case Key.Ctrl(c)            => s"ctrl+$c"
    case Key.Alt(c)             => s"alt+$c"
    case Key.Enter              => "enter"
    case Key.Esc                => "esc"
    case Key.Tab                => "tab"
    case Key.BackTab            => "shift+tab"
    case Key.Backspace          => "backspace"
    case Key.Delete             => "del"
    case Key.Insert             => "ins"
    case Key.Up                 => "↑"
    case Key.Down               => "↓"
    case Key.Left               => "←"
    case Key.Right              => "→"
    case Key.Home               => "home"
    case Key.End                => "end"
    case Key.PageUp             => "pgup"
    case Key.PageDown           => "pgdn"
    case Key.F(n)               => s"f$n"
    case Key.Modified(key, mods) =>
      (if mods.ctrl then "ctrl+" else "") + (if mods.alt then "alt+" else "") +
        (if mods.shift then "shift+" else "") + key.show
end Key

final case class Mods(shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false) derives CanEqual

/** `tags`: the `.tag(...)` names of the views under the pointer in the last drawn frame, top-most first. */
final case class Mouse(kind: MouseKind, x: Int, y: Int, mods: Mods, tags: List[String]) extends Event
    derives CanEqual

enum MouseKind derives CanEqual:
  case Down(button: Button)
  case Up(button: Button)
  case Drag(button: Button)
  case Moved, ScrollUp, ScrollDown, ScrollLeft, ScrollRight

enum Button derives CanEqual:
  case Left, Right, Middle

final case class Resize(width: Int, height: Int) extends Event derives CanEqual
final case class Paste(text: String)             extends Event derives CanEqual // needs TuiOptions.paste (default on)
case object FocusGained                           extends Event                  // needs TuiOptions.focusEvents
case object FocusLost                             extends Event
