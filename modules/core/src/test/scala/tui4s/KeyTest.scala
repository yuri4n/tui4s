package tui4s

import kyo.test.*

class KeyTest extends Test[Any]:
    "Key.show is the help and trace text" in {
      val table = Seq(
        Key.Char('+')                                  -> "+",
        Key.Char('A')                                  -> "A",
        Key.Char(' ')                                  -> "space",
        Key.Ctrl('s')                                  -> "ctrl+s",
        Key.Ctrl(' ')                                  -> "ctrl+space",
        Key.Alt('x')                                   -> "alt+x",
        Key.Enter                                      -> "enter",
        Key.Esc                                        -> "esc",
        Key.Tab                                        -> "tab",
        Key.BackTab                                    -> "shift+tab",
        Key.Backspace                                  -> "backspace",
        Key.Delete                                     -> "del",
        Key.Insert                                     -> "ins",
        Key.Up                                         -> "↑",
        Key.Down                                       -> "↓",
        Key.Left                                       -> "←",
        Key.Right                                      -> "→",
        Key.Home                                       -> "home",
        Key.End                                        -> "end",
        Key.PageUp                                     -> "pgup",
        Key.PageDown                                   -> "pgdn",
        Key.F(5)                                       -> "f5",
        Key.Modified(Key.Up, Mods(shift = true))       -> "shift+↑",
        Key.Modified(Key.Left, Mods(ctrl = true))      -> "ctrl+←",
        Key.Modified(Key.Home, Mods(true, true, true)) -> "ctrl+alt+shift+home",
      )
      val wrong = table.collect { case (k, s) if k.show != s => (k, k.show, s) }
      assert(wrong.isEmpty, wrong.mkString("\n"))
    }

    "keys and events compare by value" in {
      assert(Key.Char('a') == Key.Char('a'))
      assert(Key.Char('a') != Key.Char('b'))
      assert(Key.Modified(Key.Up, Mods(shift = true)) != Key.Up)
      assert(Resize(80, 24) == Resize(80, 24))
      assert(Paste("x") != Paste("y"))
    }
end KeyTest
