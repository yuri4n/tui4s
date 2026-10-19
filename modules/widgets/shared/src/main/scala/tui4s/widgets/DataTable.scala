package tui4s.widgets

import kyo.*
import kyo.UI.*
import tui4s.use.{Edge, useSelection}

/** A table: a header row and a body of rows with a selected row, as a
  * [[ListView]] of rows (the body keeps the focus and names its selected row
  * with `aria-activedescendant`). Each [[DataTable.Column]] has a title, the
  * text of its cell, a width and an order. A cell that is wider than its column
  * ends with "…".
  *
  * The sort is a [[Model]] of [[DataTable.Sort]]; the selection follows the key
  * of its row when the sort changes. The header marks the sorted column with
  * "▲" (ascending) or "▼" (descending).
  *
  * Keys (on the body): the keys of a [[ListView]] (ArrowUp, ArrowDown, `k`,
  * `j`, PageUp, PageDown, Home, End, Enter); `1` to `9` sort by that column
  * (again: descending; a third time: unsorted).
  *
  * {{{
  * DataTable(procs, _.pid,
  *   DataTable.Column("PID", _.pid.toString, DataTable.Width.Fixed(6)),
  *   DataTable.Column("Name", _.name, DataTable.Width.Fill),
  * )(height = 8, onActivate = inspect)
  * }}}
  */
// claude 2026-10-19: created (L2b)
object DataTable:

    /** The width of a column. */
    enum Width derives CanEqual:
        /** `cells` cells. */
        case Fixed(cells: Int)

        /** The widest cell of the column, its title included. */
        case Fit

        /** The rest of the row (shared by the `Fill` columns). */
        case Fill
    end Width

    /** The direction of a sort. */
    enum Direction derives CanEqual:
        case Ascending, Descending

    /** The sort of the rows: none, or by the column at `column`. */
    enum Sort derives CanEqual:
        case Unsorted
        case By(column: Int, direction: Direction)

    /** A column: its title, the text of a cell, its width, and its order
      * (Absent: the order of the cell texts).
      */
    final case class Column[A](
        title: String,
        cell: A => String,
        width: Width = Width.Fit,
        order: Maybe[Ordering[A]] = Absent,
    )

    /** The table of `rows` with the columns `first` and `more`. */
    def apply[A, K](
        rows: Signal[Chunk[A]],
        key: A => K,
        first: Column[A],
        more: Column[A]*,
    )(
        height: Int = 10,
        sort: Model[Sort] = Model.Local(Sort.Unsorted),
        onSort: Sort => Any < Async = (_: Sort) => (),
        onActivate: A => Any < Async = (_: A) => (),
    )(using Frame): UI < Sync =
        val cols = Chunk.from(first +: more)
        for
            s    <- Model.open(sort, onSort)
            shown =
              rows.combineLatest(s.value).map((xs, o) => sorted(xs, cols, o))
            sel  <- useSelection(shown, key, Edge.Clamp, height)
            id   <- Looks.freshId("table")
        yield
            val widths                              = rows.map(xs => fit(cols, xs))
            val activate: Unit < Async              =
              for
                  xs <- shown.current
                  i  <- sel.index.current
                  _  <- if i < xs.size then onActivate(xs(i)).unit else Kyo.unit
              yield ()
            val list                                = Listbox.keys(sel, m => m, activate)
            def keys(e: KeyboardEvent): Any < Async =
              e.key match
                  case Keyboard.Char(c)
                      if c >= '1' && c <= '9' && c - '1' < cols.size =>
                    s.value.current.map(o => s.change(nextSort(o, c - '1')))
                  case _ => list(e)
            div.role("table")(
              widths
                .combineLatest(s.value)
                .render((ws, o) =>
                  div
                    .role("row")
                    .style(_.row.bold)(
                      span("  "),
                      UI.fragment(cols.zip(ws).zipWithIndex.map {
                        case ((c, w), i) =>
                          cell(c.title + mark(o, i), w)
                      }*),
                    ),
                ),
              widths.render(ws =>
                Listbox(
                  id,
                  shown.map(xs => (0, xs)),
                  sel.index,
                  height,
                  Style.Overflow.auto,
                  keys,
                )((a, _) =>
                  div.style(_.row.flexGrow(1))(
                    UI.fragment(cols.zip(ws).map((c, w) => cell(c.cell(a), w))*),
                  ),
                ),
              ),
            )
        end for
    end apply

    /** One cell of `w` cells (Absent: the rest of the row), one cell apart. */
    private def cell(text: String, w: Maybe[Int])(using Frame): UI =
      span(text).style(
        w.fold(Looks.oneLine.flexGrow(1).minWidth(0.px))(n =>
          Looks.oneLine.width(n.px).flexShrink(0),
        ).margin(0.px, 1.px, 0.px, 0.px),
      )

    private def mark(o: Sort, i: Int): String = o match
        case Sort.By(`i`, Direction.Ascending)  => " ▲"
        case Sort.By(`i`, Direction.Descending) => " ▼"
        case _                                  => ""

    /** The width of each column for `rows` (Absent for a `Fill` column). A
      * `Fit` column takes its widest text, its title and the sort mark
      * included; the width counts characters.
      */
    def fit[A](cols: Chunk[Column[A]], rows: Chunk[A]): Chunk[Maybe[Int]] =
      cols.map(c =>
        c.width match
            case Width.Fixed(n) => Present(n.max(1))
            case Width.Fill     => Absent
            case Width.Fit      =>
              Present(
                rows.map(c.cell(_).length).foldLeft(c.title.length + 2)(_ max _),
              ),
      )

    /** `rows` in the order of the sort `s` (a stable sort; an index outside the
      * columns leaves them as they are).
      */
    def sorted[A](rows: Chunk[A], cols: Chunk[Column[A]], s: Sort): Chunk[A] =
      s match
          case Sort.By(c, d) if c >= 0 && c < cols.size =>
            val col = cols(c)
            val ord = col.order.getOrElse(Ordering.by[A, String](col.cell))
            Chunk.from(
              rows.toSeq.sorted(using
                d match
                    case Direction.Ascending  => ord
                    case Direction.Descending => ord.reverse,
              ),
            )
          case _                                        => rows

    /** The sort after a press of the key of the column `c`: ascending, then
      * descending, then unsorted.
      */
    def nextSort(s: Sort, c: Int): Sort = s match
        case Sort.By(`c`, Direction.Ascending)  =>
          Sort.By(c, Direction.Descending)
        case Sort.By(`c`, Direction.Descending) => Sort.Unsorted
        case _                                  => Sort.By(c, Direction.Ascending)
end DataTable
