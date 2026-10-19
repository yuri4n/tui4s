package tui4s.use

import kyo.*

/** The visible part of a long list: the index of its first item (`offset`) and
  * its items (`slice`).
  */
// claude 2026-10-19: created (L2a)
final case class Window[A](offset: Int, slice: Chunk[A])

/** The visible part of `items` for a list `size` rows high that shows the item
  * at `selected`: a virtual list. When the selection leaves the window, the
  * window moves the least distance that shows it (the "nearest" rule of the
  * CSSOM, as the viewports of tui4s do), and it stays in the list.
  *
  * Use it for thousands of rows: the UI builds only the rows of the window. For
  * a list of tens or hundreds of rows, a viewport (`overflowY(_.auto)`) is
  * simpler, and the runner scrolls it to the focus. The window follows the
  * three signals in a fiber that ends when the caller's `Scope` closes.
  *
  * {{{
  * for
  *     sel <- useSelection(rows, _.id)
  *     win <- useScrollWindow(rows, Signal.initConst(20), sel.index)
  * yield ul(win.map(w => w.slice.map(r => li(r.text))))
  * }}}
  */
// claude 2026-10-19: created (L2a)
def useScrollWindow[A](
    items: Signal[Chunk[A]],
    size: Signal[Int],
    selected: Signal[Int],
)(using Frame): Signal[Window[A]] < (Sync & Scope) =
  for
      xs     <- items.current
      n      <- size.current
      sel    <- selected.current
      window <- Signal.initRef(ScrollWindow.window(0, xs, n, sel))
      _      <- Fiber.init(
                  items.combineLatest(size).combineLatest(selected).observe {
                    case ((xs, n), sel) =>
                      window
                        .updateAndGet(w => ScrollWindow.window(w.offset, xs, n, sel))
                        .unit
                  },
                )
  yield window

/** The pure rule of [[useScrollWindow]]. */
// claude 2026-10-19: created (L2a)
private[use] object ScrollWindow:
    /** The offset nearest to `offset` that shows the item at `selected` (when
      * the list has items and `size` > 0), within 0 to `total - size`.
      */
    def follow(offset: Int, size: Int, total: Int, selected: Int): Int =
        val sel   = selected.min(total - 1).max(0)
        val moved =
          if sel < offset then sel
          else if size > 0 && sel >= offset + size then sel - size + 1
          else offset
        moved.min((total - size).max(0)).max(0)

    /** The window of `xs` after a move from `offset`. */
    def window[A](
        offset: Int,
        xs: Chunk[A],
        size: Int,
        selected: Int,
    ): Window[A] =
        val o = follow(offset, size, xs.size, selected)
        Window(o, xs.slice(o, o + size.max(0)))
    end window
end ScrollWindow
