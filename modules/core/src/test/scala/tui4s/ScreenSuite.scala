package tui4s

import jatatui.core.buffer.Buffer
import kyo.*
import kyo.test.*
import tui4s.internal.Render

/** A kyo-test suite with golden-screen helpers. A golden is the text of the
  * screen, one line per row, without the spaces at the end of a row.
  */
// claude 2026-10-12: created
abstract class ScreenSuite extends Test[Any]:

    /** The screen of `ui` on `w` x `h` cells, rows without trailing spaces. */
    def screen(ui: UI, w: Int, h: Int): Vector[String] =
      Render.text(ui, w, h).map(_.replaceAll("\\s+$", ""))

    /** The buffer of `ui` on `w` x `h` cells, for checks of cell styles. */
    def buffer(ui: UI, w: Int, h: Int): Buffer = Render.buffer(ui, w, h)

    /** Asserts that `ui` on `w` x `h` cells shows `expected`: one line per row,
      * rows at the end that are blank can be left out.
      */
    def assertScreen(ui: UI, w: Int, h: Int, expected: String)(using
        AssertScope,
    ): Unit =
        val actual = screen(ui, w, h)
        val want   =
          expected.split("\n", -1).toVector.map(_.replaceAll("\\s+$", ""))
        val padded = want ++ Vector.fill((h - want.size).max(0))("")
        assert(
          actual == padded,
          "screen differs\n--- expected:\n" + padded.mkString("\n") +
            "\n--- actual:\n" + actual.mkString("\n"),
        )
    end assertScreen
end ScreenSuite
