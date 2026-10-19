package tui4s.widgets

import kyo.*
import kyo.UI.*
import tui4s.use.{Edge, Selection, useScrollWindow, useSelection}

/** A list with a selected row. The container keeps the focus (a tab stop) and
  * names the selected row with `aria-activedescendant` (WAI-ARIA `listbox`): a
  * runner that follows the focus scrolls that row into view, and the arrows do
  * not leave the list. The rows are a slot (`row`); the widget adds the marker
  * of the selected row ("› "), its colours (`--tui4s-selection`,
  * `--tui4s-accent`) and bold text.
  *
  * The selection is a `Selection` of tui4s-use: it follows the key of its item
  * when the list changes. [[ListView.apply]] owns one; [[ListView.of]] shows
  * the parent's (from `useSelection`, so the parent reads and moves it);
  * [[ListView.virtual]] builds only the rows of the window of a long list
  * (`useScrollWindow`).
  *
  * Keys (on the list): ArrowUp or `k` and ArrowDown or `j` move one row; PageUp
  * and PageDown move one page (`height` rows); Home and End go to the first and
  * the last row; Enter activates the selected row (`onActivate`).
  *
  * {{{
  * ListView(files, _.path, height = 8, onActivate = open)(f => span(f.name))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object ListView:

    /** A list that owns its selection; `onSelect` gets each newly selected
      * item.
      */
    def apply[A, K](
        items: Signal[Chunk[A]],
        key: A => K,
        height: Int = 10,
        edge: Edge = Edge.Clamp,
        onSelect: A => Any < Async = (_: A) => (),
        onActivate: A => Any < Async = (_: A) => (),
    )(row: A => UI)(using Frame): UI < Sync =
      useSelection(items, key, edge, height).map(sel =>
        build(items, sel, height, Rows.All(), onSelect, onActivate)(row),
      )

    /** A list that shows the parent's selection (`useSelection(items, ...)`). */
    def of[A, K](
        items: Signal[Chunk[A]],
        selection: Selection[A, K],
        height: Int = 10,
        onActivate: A => Any < Async = (_: A) => (),
    )(row: A => UI)(using Frame): UI < Sync =
      build(items, selection, height, Rows.All(), (_: A) => (), onActivate)(row)

    /** A list that builds only the `height` rows of its window: for thousands
      * of rows. The window follows the selection in a fiber that ends with the
      * caller's `Scope`.
      */
    def virtual[A, K](
        items: Signal[Chunk[A]],
        selection: Selection[A, K],
        height: Int = 10,
        onActivate: A => Any < Async = (_: A) => (),
    )(row: A => UI)(using Frame): UI < (Sync & Scope) =
      useScrollWindow(items, Signal.initConst(height), selection.index)
        .map(w =>
          build(
            items,
            selection,
            height,
            Rows.Window(w.map(x => (x.offset, x.slice))),
            (_: A) => (),
            onActivate,
          )(row),
        )

    /** Which rows the list builds: all of them in a viewport, or a window. */
    private enum Rows[A]:
        case All()
        case Window(shown: Signal[(Int, Chunk[A])])

    private def build[A, K](
        items: Signal[Chunk[A]],
        sel: Selection[A, K],
        height: Int,
        rows: Rows[A],
        onSelect: A => Any < Async,
        onActivate: A => Any < Async,
    )(row: A => UI)(using Frame): UI < Sync =
        val (shown, scroll)                        = rows match
            case Rows.All()     => (items.map(xs => (0, xs)), Style.Overflow.auto)
            case Rows.Window(w) => (w, Style.Overflow.hidden)
        def at(i: Int): Maybe[A] < Sync            =
          items.current.map(xs => Maybe.when(i >= 0 && i < xs.size)(xs(i)))
        def moved(move: Unit < Sync): Unit < Async =
          for
              before <- sel.key.current
              _      <- move
              after  <- sel.key.current
              i      <- sel.index.current
              item   <- at(i)
              _      <-
                if before == after then Kyo.unit
                else item.fold(Kyo.unit)(a => onSelect(a).unit)
          yield ()
        val activate: Unit < Async                 =
          sel.index.current
            .map(at)
            .map(_.fold(Kyo.unit)(a => onActivate(a).unit))
        Looks
          .freshId("list")
          .map(id =>
            Listbox(
              id,
              shown,
              sel.index,
              height,
              scroll,
              Listbox.keys(sel, moved, activate),
            )((a, _) => row(a)),
          )
    end build
end ListView

/** The container of a list, a table body and a tree: a `listbox` that keeps the
  * focus and names its selected row with `aria-activedescendant`.
  */
// claude 2026-10-19: created (L2b)
private[widgets] object Listbox:

    /** The listbox `id` of the rows of `shown` (the index of its first row, and
      * the rows) with the row at `selected` marked.
      */
    def apply[A](
        id: String,
        shown: Signal[(Int, Chunk[A])],
        selected: Signal[Int],
        height: Int,
        scroll: Style.Overflow,
        keys: KeyboardEvent => Any < Async,
    )(row: (A, Boolean) => UI)(using Frame): UI =
      shown.combineLatest(selected).render { case ((offset, xs), sel) =>
        div
          .id(id)
          .role("listbox")
          .tabIndex(0)
          .preventScrollKeys
          .aria("activedescendant", s"$id-$sel")
          .onKeyDown(keys)
          .style(_.maxHeight(height.em).overflowY(scroll))(
            UI.fragment(xs.zipWithIndex.map { (a, i) =>
              val n  = offset + i
              val on = n == sel
              div
                .id(s"$id-$n")
                .role("option")
                .aria("selected", on.toString)
                .style((if on then Looks.selected else Looks.plain).row)(
                  span(Looks.marker(on)),
                  row(a, on),
                )
            }*),
          )
      }

    /** The keys of a list: the moves of `sel` (through `moved`, which reports a
      * change) and `activate` on Enter.
      */
    def keys[A, K](
        sel: Selection[A, K],
        moved: (Unit < Sync) => Unit < Async,
        activate: Unit < Async,
    )(using Frame): KeyboardEvent => Any < Async = e =>
      e.key match
          case Keyboard.ArrowUp | Keyboard.Char('k')   => moved(sel.moveUp)
          case Keyboard.ArrowDown | Keyboard.Char('j') => moved(sel.moveDown)
          case Keyboard.PageUp                         => moved(sel.pageUp)
          case Keyboard.PageDown                       => moved(sel.pageDown)
          case Keyboard.Home                           => moved(sel.first)
          case Keyboard.End                            => moved(sel.last)
          case Keyboard.Enter                          => activate
          case _                                       => Kyo.unit
end Listbox
