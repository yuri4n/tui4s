package tui4s

import kyo.test.*
import tui4s.testkit.*

/** A kyo-test suite with helpers for screens. */
abstract class TuiSuite extends Test[Any]:
    /** An app that shows `view` and keeps its state on every event. */
    def still(view: View, options: TuiOptions = TuiOptions()): App[Unit] =
      App[Unit]((), _ => view, (s, _) => s, options = options)

    /** The screen of `view` on a `width` x `height` terminal. */
    def render(view: View, width: Int, height: Int): Screen =
      TuiTest(still(view), width, height).screen

    /** Asserts that `screen` shows `expected` (see `diffScreen`). */
    def assertShows(screen: Screen, expected: String)(using AssertScope): Unit =
        val diff = diffScreen(screen, expected)
        assert(
          diff.isEmpty,
          diff.getOrElse("") + "\n--- actual screen:\n" + screen.text,
        )
end TuiSuite
