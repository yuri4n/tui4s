package tui4s.use

import kyo.*
import kyo.UI.{Keyboard, KeyboardEvent, Modifiers}
import kyo.test.*

/** The chords, their labels, and the handler and the key hints of a keymap. */
// claude 2026-10-19: created (L2a)
class KeymapTest extends Test[Any]:

    private def key(k: Keyboard, mods: Modifiers = Modifiers.none) =
      KeyboardEvent(k, mods, Absent)

    "a chord of a character ignores Shift; another chord compares it" in {
      assert(
        Chord
          .char('A')
          .matches(key(Keyboard.Char('A'), Modifiers(shift = true))),
      )
      assert(
        !Chord
          .char('a')
          .matches(key(Keyboard.Char('A'), Modifiers(shift = true))),
      )
      assert(
        Chord.ctrl('Z').matches(key(Keyboard.Char('z'), Modifiers(ctrl = true))),
      )
      assert(!Chord.ctrl('z').matches(key(Keyboard.Char('z'))))
      assert(
        !Chord(Keyboard.Tab).matches(key(Keyboard.Tab, Modifiers(shift = true))),
      )
      assert(
        Chord(Keyboard.Tab, Modifiers(shift = true))
          .matches(key(Keyboard.Tab, Modifiers(shift = true))),
      )
    }

    "the labels of the chords" in {
      assert(Chord.ctrl('z').label == "Ctrl-Z")
      assert(Chord.char('q').label == "q")
      assert(Chord(Keyboard.ArrowUp).label == "↑")
      assert(Chord(Keyboard.PageDown).label == "PgDn")
      assert(Chord(Keyboard.Escape).label == "Esc")
      assert(Chord(Keyboard.Tab, Modifiers(shift = true)).label == "Shift-Tab")
      assert(Chord(Keyboard.F5).label == "F5")
    }

    "the handler runs the first binding that matches; another key does nothing; help lists the bindings with a text" in {
      for
          ran    <- Signal.initRef(Chunk.empty[String])
          keys    = Keymap.of(
                      Binding.of("undo", Chord.ctrl('z'))(
                        ran.updateAndGet(_ :+ "undo"),
                      ),
                      Binding.of("up", Chord(Keyboard.ArrowUp), Chord.char('k'))(
                        ran.updateAndGet(_ :+ "up"),
                      ),
                      Binding.of("", Chord.char('k'))(
                        ran.updateAndGet(_ :+ "hidden"),
                      ),
                    )
          _      <- keys.handler(key(Keyboard.Char('z'), Modifiers(ctrl = true)))
          _      <- keys.handler(key(Keyboard.Char('k')))
          _      <- keys.handler(key(Keyboard.Char('x')))
          _      <- keys.handler(key(Keyboard.ArrowUp))
          result <- ran.current
      yield
          assert(result == Chunk("undo", "up", "up"))
          assert(keys.help == Chunk(("Ctrl-Z", "undo"), ("↑/k", "up")))
    }
end KeymapTest
