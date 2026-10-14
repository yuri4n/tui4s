package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui4s.internal.*

/** The incremental re-layout of one container (`Layout.relayout`): the path
  * that the runner takes when a region changes.
  */
// claude 2026-10-12: created (PR #10 review: Codex, Layout.scala:703)
class RelayoutTest extends Test[Any]:

    "a retained table row follows the new column widths" in {
      // row 2 is a region. Its two cells swap their widths: the columns
      // move, but the table keeps its size, so the table's owner does not
      // lay the table out again. Row 1 does not change.
      def row(a: String, b: String) = tr(td(a), td(b))
      val sig                       = Signal.initConst(row("a", "bbbbbbbb"))
      val ui                        = table(row("x", "y"), sig)
      def env(second: UI): Scene    =
        Scene(_ => Some(Content.Single(second)), None, Size(20, 4), _ => 0)
      val laid1                     = Layout.layoutAll(ui, env(row("a", "bbbbbbbb")))
      val region                    = laid1.regionOwner.keys.head
      val owner                     = laid1.regionOwner(region)
      val (laid2, _)                =
        Layout.relayout(laid1, owner, Set.empty, env(row("aaaaaaaa", "b")), ui)
      val secondCells               = laid2.nodes.values
        .filter(n =>
          n.index == 1 && (n.kind match
              case Kind.El(_: UI.Ast.Td) => true
              case _                     => false),
        )
        .toVector
      assert(secondCells.size == 2, s"${secondCells.size} second cells")
      assert(
        secondCells.map(_.rect.x).distinct.size == 1,
        "the second cells are not aligned: " + secondCells
          .map(n => n.path.showLoc + " at x=" + n.rect.x)
          .mkString(", "),
      )
    }
end RelayoutTest
