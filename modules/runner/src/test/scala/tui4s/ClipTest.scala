package tui4s

import jatatui.core.backend.TestBackend
import jatatui.core.style.Style as JStyle
import kyo.*
import kyo.test.*
import tui4s.internal.*

/** The clip of a text is by cells: the left edge of a clip skips cells, not
  * UTF-16 chars, so a wide character, an emoji or a combining mark before the
  * edge does not move the rest of the text. A wide character that the left edge
  * cuts shows its hidden half: a space with no style, as in a paint without the
  * clip.
  */
// claude 2026-10-19: created (P5): the negative control of the clip fix
class ClipTest extends Test[Any]:

    /** The symbols of a row of `w` cells after the text `text` at x = 0 is
      * painted inside `clip`.
      */
    private def row(text: String, clip: Rect, w: Int): Vector[String] =
        val screen  = Rect(0, 0, w, 1)
        val backend = TestBackend(w, 1)
        val canvas  = Canvas(backend)
        val plan    = Plan(
          Size(w, 1),
          Paint.text(0, 0, text, JStyle.empty(), clip),
          Plan.rowSpans(Vector(screen), screen),
          0,
        )
        canvas.write(plan)
        discard(canvas.flush(plan))
        Vector.tabulate(w)(x => backend.buffer().cellAt(x, 0).symbol())
    end row

    /** Asserts that `text` inside `clip` gives the symbols `want`. */
    private def shows(text: String, clip: Rect, want: String*)(using
        AssertScope,
    ): Unit =
        val got = row(text, clip, want.size)
        assert(got == want.toVector, s"got ${got.mkString("[", "|", "]")}")

    "a wide character before the left edge of the clip keeps the cells of the text" in {
      shows("中文x", Rect(0, 0, 6, 1), "中", " ", "文", " ", "x", " ")
      shows("中文x", Rect(1, 0, 5, 1), " ", " ", "文", " ", "x", " ")
    }

    "an emoji (a surrogate pair) before the left edge keeps the cells of the text" in {
      shows("👍ab", Rect(1, 0, 5, 1), " ", " ", "a", "b", " ", " ")
    }

    "a combining mark before the left edge keeps the cells of the text" in {
      shows("e\u0301ab", Rect(2, 0, 4, 1), " ", " ", "b", " ", " ", " ")
    }
end ClipTest
