package tui4s.internal

import jatatui.core.backend.TestBackend
import jatatui.core.buffer.Buffer
import kyo.*

/** Renders one static kyo-ui value into a jatatui `TestBackend`, for tests.
  *
  * The scene has no regions and no bound values: a `Reactive` or a `Foreach`
  * shows nothing, a bound input shows its constant. The runner that drives
  * signals comes in a later PR (C2).
  */
// claude 2026-10-12: created
private[tui4s] object Render:

    /** The screen buffer after one frame of `ui` on `w` x `h` cells, with the
      * scroll offsets `scroll` of its viewports.
      */
    // claude 2026-10-19: changed: the scroll offsets (L1a)
    def buffer(
        ui: UI,
        w: Int,
        h: Int,
        focus: Option[Loc] = None,
        scroll: Map[Loc, Offset] = Map.empty,
    ): Buffer =
        val backend = TestBackend(w, h)
        val canvas  = Canvas(backend)
        val env     = Scene(
          _ => None,
          focus,
          Size(w, h),
          _ => 0,
          p => scroll.getOrElse(p, Offset.zero),
        )
        val laid    = Layout.layoutAll(ui, env)
        val damage  =
          Vector(Damage.Clear(Rect(0, 0, w, h), None), Damage.Paint(Loc.screen))
        // claude 2026-10-19: changed (P5): the plan of the pure painter
        val plan    = Paint(laid, env, damage)
        canvas.write(plan)
        discard(canvas.flush(plan))
        backend.buffer()
    end buffer

    /** One string per row of `buffer`: the symbol of each cell. */
    def lines(buffer: Buffer): Vector[String] =
      Vector.tabulate(buffer.area.height) { y =>
        (0 until buffer.area.width)
          .map(x => buffer.cellAt(x, y).symbol())
          .mkString
      }

    /** The rows of the screen after one frame of `ui` on `w` x `h` cells. */
    // claude 2026-10-19: changed: the scroll offsets (L1a)
    def text(
        ui: UI,
        w: Int,
        h: Int,
        focus: Option[Loc] = None,
        scroll: Map[Loc, Offset] = Map.empty,
    ): Vector[String] =
      lines(buffer(ui, w, h, focus, scroll))
end Render
