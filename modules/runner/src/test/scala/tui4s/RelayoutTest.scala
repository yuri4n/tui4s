package tui4s

import kyo.*
import kyo.UI.*
import kyo.test.*
import tui4s.internal.*
import tui4s.oracle.{LayoutPropertyTest, PaintOracle, PaintPropertyTest}

/** The incremental re-layout of one container (`Layout.relayout`): the path
  * that the runner takes when a region changes.
  */
// claude 2026-10-12: created (PR #10 review: Codex, Layout.scala:703)
class RelayoutTest extends Test[Any]:

    // codex 2026-10-28: created: compare every incremental scene with a fresh layout and a full paint.
    private def checkScenes(
        ui: UI,
        initial: Map[Loc, Content],
        steps: Vector[(Loc, Map[Loc, Content])],
    )(using AssertScope): Unit =
        val size                                      = Size(20, 12)
        def scene(contents: Map[Loc, Content]): Scene =
          Scene(contents.get, None, size, _ => 0)
        val first                                     = Layout.layoutAll(ui, scene(initial))
        val drawn                                     = PaintPropertyTest.Now(size)
        discard(
          drawn.draw(Paint(first, scene(initial), PaintOracle.full(size))),
        )
        discard(steps.foldLeft(first) { case (prior, (changed, contents)) =>
          val env            = scene(contents)
          val (next, damage) = Layout.relayout(
            prior,
            prior.regionOwner(changed),
            Set.empty,
            env.copy(changes = Changes.of(Vector(changed))),
            ui,
          )
          val full           = Layout.layoutAll(ui, env)
          assert(next.regionOwner == full.regionOwner)
          assert(next.layers == full.layers)
          assert(
            LayoutPropertyTest.shape(next) == LayoutPropertyTest.shape(full),
          )
          next.nodes.values.foreach { node =>
            node.kind match
                case Kind.Txt(_, _) =>
                  assert(
                    !next.regionOwner.exists((region, owner) =>
                      region.isAtOrUnder(node.path) || owner.isAtOrUnder(
                        node.path,
                      ),
                    ),
                  )
                case _              => ()
          }
          discard(
            drawn.draw(
              Paint(next, env, damage ++ Layout.layerDamage(prior, next)),
            ),
          )
          val fresh          = PaintPropertyTest.Now(size)
          discard(fresh.draw(Paint(full, env, PaintOracle.full(size))))
          assert(drawn.cells == fresh.cells)
          next
        })
    end checkScenes

    // codex 2026-10-28: created: text creation removes flattened regions before later text-only replacements.
    "nested regions can become text, change its size, and become regions again" in {
      val reactive = UI.Ast.Reactive[UI](Signal.initConst[UI](UI.Ast.Text("")))
      val ui       = div.style(_.width(20.px))(reactive, span("kept"))
      val outer    = Loc.root.child(0)
      val inner    = outer.content.child(0)
      val nested   = UI.Ast.Fragment[UI](Chunk[UI](reactive))
      val initial  = Map(
        outer -> Content.Single(nested),
        inner -> Content.Single(span("old").style(_.position(_.relative))),
      )
      val restored = Map(
        outer -> Content.Single(nested),
        inner -> Content.Single(span("restored").style(_.position(_.relative))),
      )
      checkScenes(
        ui,
        initial,
        Vector(
          outer -> Map(outer -> Content.Single(UI.Ast.Text("short"))),
          outer -> Map(
            outer -> Content.Single(
              UI.Ast.Text("a longer text that wraps over several rows"),
            ),
          ),
          outer -> restored,
          inner -> restored.updated(inner, Content.Single(UI.Ast.Text("final"))),
        ),
      )
    }

    // codex 2026-10-28: created: a new wrapper must clear stale ownership before placing reactive text descendants.
    "a new wrapper clears old nested regions before its text changes" in {
      val reactive = UI.Ast.Reactive[UI](Signal.initConst[UI](UI.Ast.Text("")))
      val ui       = div.style(_.width(20.px))(reactive, span("kept"))
      val outer    = Loc.root.child(0)
      val inner    = outer.content.child(0)
      val deepest  = inner.content.child(0)
      val nested   = UI.Ast.Fragment[UI](Chunk[UI](reactive))
      val wrapper  = div(reactive)
      val initial  = Map(
        outer   -> Content.Single(nested),
        inner   -> Content.Single(nested),
        deepest -> Content.Single(span("old").style(_.position(_.relative))),
      )
      val wrapped  = Map(
        outer -> Content.Single(wrapper),
        inner -> Content.Single(UI.Ast.Text("short")),
      )
      val restored = wrapped
        .updated(inner, Content.Single(nested))
        .updated(
          deepest,
          Content.Single(span("restored").style(_.position(_.relative))),
        )
      checkScenes(
        ui,
        initial,
        Vector(
          outer   -> wrapped,
          inner   -> wrapped.updated(
            inner,
            Content.Single(
              UI.Ast.Text("a longer text that wraps over several rows"),
            ),
          ),
          inner   -> restored,
          deepest -> restored.updated(
            deepest,
            Content.Single(UI.Ast.Text("final")),
          ),
        ),
      )
    }

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
