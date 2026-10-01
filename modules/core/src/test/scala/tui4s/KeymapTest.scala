package tui4s

import kyo.test.*
import tui4s.fixtures.CounterApp

class KeymapTest extends TuiSuite:
    private val keys = CounterApp.keys

    "apply runs the bound transition" in {
      assert(keys(1, Key.Char('+')) == 2)
      assert(keys(1, Key.Char('-')) == 0)
      assert(keys(1, Key.Char('q')) == Quit)
    }

    "an unbound key or another event returns the same state object" in {
      val state = new StringBuilder("s")
      val map   = Keymap[StringBuilder](Bind('a', "a")(_ => new StringBuilder))
      assert(map(state, Key.Char('x')).asInstanceOf[AnyRef] eq state)
      assert(map(state, Resize(1, 1)).asInstanceOf[AnyRef] eq state)
      assert(map(state, Key.Char('a')).asInstanceOf[AnyRef] ne state)
    }

    "unapply matches the bound keys in a pattern" in {
      def update(n: Int, e: Event): Next[Int] = e match
          case keys(action) => action(n)
          case _            => -1
      assert(update(5, Key.Char('+')) == 6)
      assert(update(5, Key.Char('x')) == -1)
      assert(update(5, Paste("+")) == -1)
    }

    "lookup finds the binding of every key of `or`" in {
      val up  = Bind[Int](Key.Up, "up")(_ - 1).or('k', Key.PageUp)
      val map = Keymap(up)
      assert(map.lookup(Key.Char('k')).map(_.help) == Some("up"))
      assert(map.lookup(Key.PageUp).map(_.help) == Some("up"))
      assert(map.lookup(Key.Down).isEmpty)
      assert(map(3, Key.Char('k')) == 2)
    }

    "++ keeps the left binding for a key bound on both sides" in {
      val left   = Keymap(Bind[Int]('a', "left a")(_ => 1))
      val right  = Keymap(
        Bind[Int]('a', "right a")(_ => 2),
        Bind[Int]('b', "b")(_ => 3),
      )
      val both   = left ++ right
      assert(both(0, Key.Char('a')) == 1)
      assert(both(0, Key.Char('b')) == 3)
      assert(both.helpEntries == Seq(("a", "left a"), ("b", "b")))
      assert((right ++ left)(0, Key.Char('a')) == 2)
      // A right binding keeps only its keys that are free on the left.
      val shared = left ++ Keymap(Bind[Int]('c', "c or a")(_ => 4).or('a'))
      assert(shared(0, Key.Char('a')) == 1 && shared(0, Key.Char('c')) == 4)
      assert(shared.helpEntries == Seq(("a", "left a"), ("c", "c or a")))
      // ... and is dropped when none is free.
      val gone   = left ++ Keymap(Bind[Int]('a', "right a")(_ => 2))
      assert(gone.binds.size == 1)
    }

    "explain says what a key does, or what is bound" in {
      assert(keys.explain(Key.Char('+')) == "'+' -> increment")
      assert(keys.explain(Key.Char('x')) == "'x' -> no binding (bound: + - q)")
      val map = Keymap(
        Bind[Int](Key.Up, "up")(identity),
        Bind[Int](' ', "toggle")(identity),
      )
      assert(map.explain(Key.Up) == "↑ -> up")
      assert(map.explain(Key.Char(' ')) == "space -> toggle")
      assert(
        map.explain(
          Resize(1, 2),
        ) == "Resize(1,2) -> no binding (bound: ↑ space)",
      )
    }

    "a key bound twice in one Keymap is rejected" in {
      val e     = intercept[IllegalArgumentException](
        Keymap[Int](
          Bind[Int]('a', "x")(identity),
          Bind[Int]('a', "y")(identity),
        ),
      )
      assert(e.getMessage.contains("'a'"))
      val viaOr = intercept[IllegalArgumentException](
        Keymap[Int](Bind[Int](Key.Up, "x")(identity).or(Key.Up)),
      )
      assert(viaOr.getMessage.contains("↑"))
    }

    "a key that a terminal never sends is rejected, with the right key" in {
      val tab   = intercept[IllegalArgumentException](
        Keymap[Int](Bind[Int]('\t', "x")(identity)),
      )
      assert(tab.getMessage.contains("bind tab"))
      val enter = intercept[IllegalArgumentException](
        Keymap[Int](Bind[Int]('a', "x")(identity).or('\n')),
      )
      assert(enter.getMessage.contains("bind enter"))
      val ctrl  = intercept[IllegalArgumentException](
        Keymap[Int](Bind[Int](Key.Ctrl('S'), "save")(identity)),
      )
      assert(ctrl.getMessage.contains("bind ctrl+s"))
      val bell  = intercept[IllegalArgumentException](
        Keymap[Int](Bind[Int]('\u0007', "x")(identity)),
      )
      assert(bell.getMessage.contains("control character"))
      assert(
        Keymap[Int](Bind[Int](Key.Ctrl('s'), "save")(identity)).binds.size == 1,
      )
    }

    "help lists the visible bindings in order" in {
      val map = Keymap(
        Bind[Int](Key.Up, "up")(identity).or('k'),
        Bind[Int]('?', "secret")(identity).hide,
        Bind[Int]('q', "quit")(_ => Quit),
      )
      assert(
        help(map) == View.Help(Seq(("↑/k", "up"), ("q", "quit")), Align.Left),
      )
      assert(map(1, Key.Char('?')) == 1) // hidden, still bound
      assertShows(render(help(map), 20, 1), "↑/k up · q quit")
      assertShows(
        render(help(keys).centered, 40, 1),
        "   + increment · - decrement · q quit",
      )
    }

    "Bind infers S from the expected type" in {
      val b: Bind[Int] = Bind('a', "x")(identity)
      assert(b.keys == Seq(Key.Char('a')) && b.help == "x" && !b.hidden)
      assert(b.hide.hidden)
    }
end KeymapTest
