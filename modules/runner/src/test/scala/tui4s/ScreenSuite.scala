package tui4s

import jatatui.core.buffer.Buffer
import kyo.*
import kyo.test.*
import tui4s.internal.{Loc, Offset, Render}

/** A kyo-test suite with golden-screen helpers. A golden is the text of the
  * screen, one line per row, without the spaces at the end of a row.
  */
// claude 2026-10-12: created
abstract class ScreenSuite extends Test[Any]:

    /** The screen of `ui` on `w` x `h` cells, rows without trailing spaces,
      * with the focus `focus` and the scroll offsets `scroll`.
      */
    // claude 2026-10-19: changed: the carets (L1c)
    def screen(
        ui: UI,
        w: Int,
        h: Int,
        focus: Option[Loc] = None,
        scroll: Map[Loc, Offset] = Map.empty,
        carets: Map[Loc, Int] = Map.empty,
    ): Vector[String] =
      Render
        .text(ui, w, h, focus, scroll, carets)
        .map(_.replaceAll("\\s+$", ""))

    /** The buffer of `ui` on `w` x `h` cells, for checks of cell styles, with
      * the focus `focus` and the carets `carets`.
      */
    // claude 2026-10-19: changed: the focus and the carets (L1c)
    def buffer(
        ui: UI,
        w: Int,
        h: Int,
        focus: Option[Loc] = None,
        carets: Map[Loc, Int] = Map.empty,
    ): Buffer = Render.buffer(ui, w, h, focus, carets = carets)

    /** Asserts that `ui` on `w` x `h` cells shows `expected`: one line per row,
      * rows at the end that are blank can be left out.
      */
    // claude 2026-10-19: changed: the carets (L1c)
    def assertScreen(
        ui: UI,
        w: Int,
        h: Int,
        expected: String,
        focus: Option[Loc] = None,
        scroll: Map[Loc, Offset] = Map.empty,
        carets: Map[Loc, Int] = Map.empty,
    )(using
        AssertScope,
    ): Unit =
        val actual = screen(ui, w, h, focus, scroll, carets)
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
