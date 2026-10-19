// scalafix:off DisableSyntax
// EDGE: the jatatui Buffer and Backend (the kernels Canvas.write and Canvas.diff)
package tui4s.internal

import jatatui.core.backend.{Backend, TestBackend}
import jatatui.core.buffer.{Buffer, BufferUpdate, Cell}
import jatatui.core.layout.{Position, Rect as JRect}
import kyo.*
import tui4s.{Screen, TuiError}

import java.util.concurrent.locks.LockSupport
import scala.jdk.CollectionConverters.*

/** The one seam between the pure painter and jatatui: it owns the two buffers
  * of a run and every call to the backend. jatatui's `Buffer` and `Cell` are
  * mutable Java objects that are not thread-safe; only this class touches them.
  *
  * For example, one frame of the runner:
  * {{{
  * val plan = Paint(laid, scene, damage) // pure
  * canvas.write(plan)                    // the kept cells
  * canvas.flush(plan)                    // the changed cells to the terminal
  * }}}
  *
  * The kept buffer (`back`) holds what paint wrote; the shown buffer (`front`)
  * holds what the terminal shows. The two kernels are the only code with a loop
  * over cells: [[write]] (the ops of a plan into `back`) and [[diff]] (the
  * cells of `back` that differ from `front`). Each one states its law.
  *
  * Threads: the runner fiber calls `write`, `diff`, `flush`, `resize` and
  * `size`; one fiber draws (AGENTS.md). Another thread can call `read` and
  * `cells` (the headless kit) and `close` (the release of the terminal, also
  * from the JVM shutdown hook). The backend's monitor orders them: `diff`,
  * `flush`, `resize`, `read`, `cells` and `close` take it; `write` and `size`
  * do not (they touch only `back`). So:
  *   - `read` and `cells` never see a half of a frame: `diff` changes `front`
  *     under the monitor, and a flush draws under the same hold;
  *   - `close` waits for a draw in progress, and after `close` no call touches
  *     the backend: `flush` and `resize` check `closed` under the monitor. The
  *     release restores the terminal only after the last frame.
  */
// claude 2026-10-19: created (P5): the one Canvas seam
private[tui4s] final class Canvas private (
    back: Buffer,
    front: Buffer,
    val backend: Backend,
    flushSeamNanos: Long,
):
    // set by `close`; read and written only under the backend's monitor
    private var closed = false

    // claude 2026-10-19: created (L1c): the terminal cursor that the backend
    // shows (Unknown before the first flush and after a resize); read and
    // written only under the backend's monitor
    private var shown: Canvas.Shown = Canvas.Shown.Unknown

    def size: Size = Size(back.area.width, back.area.height)

    /** Kernel 1: writes the ops of `plan` into the kept cells, in order.
      *
      * Law: after `write(p)`, each kept cell holds the value that the ops of
      * `p` give it, applied in order over its value before (a cell that no op
      * names keeps its value). A plan whose first op clears the whole screen
      * gives the same kept cells from any canvas, and then `diff` and `read`
      * give exactly the cells of the plan.
      *
      * Mutation: the cells of `back` (symbol and style). The ops lie inside the
      * screen (the painter clips them); a cell outside is skipped.
      */
    def write(plan: Plan): Unit =
        val w     = back.area.width
        val h     = back.area.height
        val cells = back.content()
        plan.ops.foreach {
          case Op.Clear(r, bg)      =>
            eachCell(r) { c =>
              c.reset()
              bg.foreach(c.setBg)
            }
          case Op.Fill(r, bg)       => eachCell(r)(c => discard(c.setBg(bg)))
          case Op.Put(x, y, s, st)  =>
            if x >= 0 && x < w && y >= 0 && y < h then
                discard(cells(y * w + x).setSymbol(s).setStyle(st))
          case Op.Run(x, y, gs, st) =>
            if y >= 0 && y < h then
                var cx = x
                gs.foreach { g =>
                  if cx >= 0 && cx + g.width <= w then
                      discard(
                        cells(y * w + cx).setSymbol(g.symbol).setStyle(st),
                      )
                      var hx = cx + 1
                      while hx < cx + g.width do
                          cells(y * w + hx).reset()
                          hx += 1
                  end if
                  cx += g.width
                }
        }
    end write

    /** Runs `f` on each kept cell of `r` inside the screen (for `write`). */
    private def eachCell(r: Rect)(f: Cell => Unit): Unit =
        val w     = back.area.width
        val cells = back.content()
        val x1    = r.right.min(w)
        val y1    = r.bottom.min(back.area.height)
        var y     = r.y.max(0)
        while y < y1 do
            var x = r.x.max(0)
            while x < x1 do
                f(cells(y * w + x))
                x += 1
            y += 1
    end eachCell

    /** Kernel 2: the cells inside `spans` where the kept cells differ from the
      * shown cells, each cell once, in the order of the spans. It copies them
      * to the shown cells, so a second `diff` with no `write` between gives
      * nothing. Under the backend's monitor (`read` reads the shown cells).
      *
      * Law: `diff(spans)` is the set of the cells of `spans` where `back` and
      * `front` differ; after it, `front` equals `back` inside `spans`. With the
      * spans of a plan (every op inside them), `front` then equals `back`
      * everywhere: write(a), diff, write(b), diff gives the cells where the
      * frames of a and b differ, and write(a), diff, write(a), diff gives none.
      *
      * Mutation: the cells of `front` (copies of the cells of `back`).
      */
    def diff(spans: Vector[RowSpan]): Vector[BufferUpdate] =
      backend.synchronized {
        val w   = back.area.width
        val h   = back.area.height
        val b   = back.content()
        val f   = front.content()
        val out = Vector.newBuilder[BufferUpdate]
        spans.foreach { s =>
          if s.y >= 0 && s.y < h then
              var x   = s.x.max(0)
              val end = s.right.min(w)
              while x < end do
                  val i = s.y * w + x
                  if b(i) != f(i) then
                      val copy = b(i).copy()
                      f(i) = copy.copy()
                      out += BufferUpdate(x, s.y, copy)
                  x += 1
        }
        out.result()
      }
    end diff

    /** Sends the changed cells of `plan` to the terminal ([[diff]], then one
      * draw), then the terminal cursor of the plan (`Plan.cursor`: moved to its
      * cell and shown, or hidden), then a flush of the backend, in one hold of
      * the backend's monitor. The draw moves the cursor of the terminal, so the
      * cursor is placed again after each draw. Returns the number of cells sent
      * (`FrameStats.flushed`): 0 when no cell changed; when no cell changed and
      * the cursor did not change either, nothing goes to the backend. After
      * `close` it is 0: nothing is drawn and the shown cells do not change. A
      * write that fails is [[tui4s.TuiError.TerminalIO]].
      *
      * With the test seam (`Knobs.flushSeam`), the draw goes one row of cells
      * at a time, with a pause (a park, not a spin) of the seam between them.
      */
    // claude 2026-10-19: changed: the terminal cursor of the plan (L1c)
    def flush(plan: Plan): Result[TuiError, Int] =
      backend.synchronized {
        if closed then Result.succeed(0)
        else
            val updates = diff(plan.spans)
            val cursor  = Canvas.Shown.of(plan.cursor)
            if updates.isEmpty && shown == cursor then Result.succeed(0)
            else
                terminalIO {
                  if updates.nonEmpty then draw(updates)
                  plan.cursor match
                      case Some(c) =>
                        backend.setCursorPosition(Position(c.x, c.y))
                        backend.showCursor()
                      case None    => backend.hideCursor()
                  backend.flush()
                  shown = cursor
                }.map(_ => updates.size)
            end if
      }

    // claude 2026-10-19: changed: the flush is the caller's (L1c)
    private def draw(updates: Vector[BufferUpdate]): Unit =
      if flushSeamNanos <= 0L then backend.draw(updates.asJava)
      else
          updates.grouped(back.area.width.max(1)).foreach { chunk =>
            backend.draw(chunk.asJava)
            LockSupport.parkNanos(flushSeamNanos)
          }

    /** A new size: both buffers become empty at `s`, and the terminal is
      * cleared. A `TestBackend` (the headless kit) has no terminal that resizes
      * it, so the canvas resizes it here. Under the backend's monitor; after
      * `close` the backend is not touched.
      */
    def resize(s: Size): Result[TuiError, Unit] =
      backend.synchronized {
        val area = JRect.of(0, 0, s.w, s.h)
        back.resize(area)
        back.reset()
        front.resize(area)
        front.reset()
        // claude 2026-10-19: created (L1c): the next flush places the cursor
        shown = Canvas.Shown.Unknown
        if closed then Result.unit
        else
            terminalIO {
              backend match
                  case tb: TestBackend => tb.resize(s.w, s.h)
                  case _               => ()
              backend.clear()
            }
        end if
      }

    /** The shown cells as an immutable screen (the cells of the last flush),
      * with the cursor of a `TestBackend`. Under the backend's monitor, so it
      * is never a half of a frame.
      */
    // claude 2026-10-19: changed: tui4s.Screen, not tui4s.testkit.Screen (D25)
    def read(): Screen =
      backend.synchronized {
        val cursor = backend match
            case tb: TestBackend if tb.cursorShown() =>
              val p = tb.getCursorPosition()
              Some((p.x, p.y))
            case _                                   => None
        Screen.of(front, cursor)
      }

    /** The symbols of the shown cells, one string per row and one place per
      * cell, with the trailing spaces (`Headless.lines`). Under the backend's
      * monitor, as [[read]].
      */
    // claude 2026-10-19: created (P4): the rows of Headless.lines
    def cells(): Vector[String] =
      backend.synchronized {
        val w = front.area.width
        Vector.tabulate(front.area.height)(y =>
          (0 until w).map(x => front.cellAt(x, y).symbol()).mkString,
        )
      }

    /** Stops all output, for the release of the terminal: after it, `flush` and
      * `resize` do not touch the backend. It takes the backend's monitor, which
      * a flush holds while it draws: so it waits for a draw in progress, and
      * the release (also from the JVM shutdown hook, on another thread)
      * restores the terminal only after the last frame has ended. Idempotent.
      */
    def close(): Unit = backend.synchronized { closed = true }

    /** `write`, a write to the terminal: an `IOException` becomes
      * [[tui4s.TuiError.TerminalIO]].
      */
    private def terminalIO(write: => Unit): Result[TuiError, Unit] =
      try
          write
          Result.unit
      catch case e: java.io.IOException => Result.fail(TuiError.TerminalIO(e))
end Canvas

// claude 2026-10-19: created (P5)
private[tui4s] object Canvas:
    /** @param flushSeam
      *   the test seam `Knobs.flushSeam` (zero in a real run)
      */
    def apply(backend: Backend, flushSeam: Duration = Duration.Zero): Canvas =
        val s = backend.size()
        new Canvas(
          Buffer.empty(JRect.of(0, 0, s.width, s.height)),
          Buffer.empty(JRect.of(0, 0, s.width, s.height)),
          backend,
          flushSeam.toNanos,
        )
    end apply

    /** The terminal cursor that the backend shows: `Unknown` (before the first
      * flush, after a resize), `Hidden`, or `At` a cell.
      */
    // claude 2026-10-19: created (L1c review): an ADT, not a nested Option
    enum Shown derives CanEqual:
        case Unknown
        case Hidden
        case At(cell: CursorCell)

    object Shown:
        /** The cursor that a plan with the cursor `c` shows. */
        def of(c: Option[CursorCell]): Shown = c.fold(Shown.Hidden)(Shown.At(_))
end Canvas
