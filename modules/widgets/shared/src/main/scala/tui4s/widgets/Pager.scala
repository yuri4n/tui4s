package tui4s.widgets

import kyo.*
import kyo.UI.*

/** A text viewer: a window of `height` lines that scrolls, a search, and a
  * follow mode for a log. The widget keeps its own offset and builds only the
  * lines of its window, so a log of many lines stays fast.
  *
  *   - Follow ([[Pager.Follow]], a [[Model]]): with `Tail` the window shows the
  *     last lines and follows each new line; a key that scrolls up turns it
  *     `Off`. `f` turns it on and off.
  *   - Search (a [[Model.Writable]] of the query): the field under the text
  *     (Tab reaches it) edits the query; a line with the query (case does not
  *     count) is in `--tui4s-accent`, its match bold and underlined; `n` and
  *     `N` scroll to the next and the previous match.
  *   - The status line: the lines in the window, the total, the follow mode and
  *     the count of matches.
  *
  * Keys (on the text, a tab stop): ArrowUp or `k` and ArrowDown or `j` scroll
  * one line; PageUp and PageDown one window; Home and End to the start and the
  * end; `f` follows the end or stops; `n` and `N` go to the next and the
  * previous match.
  *
  * {{{
  * Pager(logLines, height = 12, follow = Model.Local(Pager.Follow.Tail))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Pager:

    /** Whether the window follows the end of the text. */
    enum Follow derives CanEqual:
        case Off, Tail

    /** The pager of `lines`. */
    def apply(
        lines: Signal[Chunk[String]],
        height: Int = 10,
        follow: Model[Follow] = Model.Local(Follow.Off),
        query: Model.Writable[String] = Model.Local(""),
        onFollow: Follow => Any < Async = (_: Follow) => (),
    )(using Frame): UI < Sync =
      for
          mode   <- Model.open(follow, onFollow)
          text   <- Model.ref(query)
          offset <- Signal.initRef(0)
      yield
          val h                                    = height.max(1)
          val view                                 = lines
            .combineLatest(offset)
            .combineLatest(mode.value)
            .map { case ((xs, o), f) => (xs, top(xs.size, h, o, f)) }
          def scrollTo(to: Int): Unit < Async      =
            view.current
              .map((xs, _) => offset.set(to.min(xs.size - h).max(0)))
              .andThen(mode.change(Follow.Off))
          def by(delta: Int): Unit < Async         =
            view.current.map((_, t) => scrollTo(t + delta))
          def find(forward: Boolean): Unit < Async =
            for
                (xs, t) <- view.current
                q       <- text.get
                _       <- next(matches(xs, q), t, forward).fold(Kyo.unit)(scrollTo)
            yield ()
          def keys(e: KeyboardEvent): Any < Async  =
            e.key match
                case Keyboard.ArrowUp | Keyboard.Char('k')   => by(-1)
                case Keyboard.ArrowDown | Keyboard.Char('j') => by(1)
                case Keyboard.PageUp                         => by(-h)
                case Keyboard.PageDown                       => by(h)
                case Keyboard.Home                           => scrollTo(0)
                case Keyboard.End                            =>
                  view.current.map((xs, _) => scrollTo(xs.size))
                case Keyboard.Char('f')                      =>
                  mode.value.current.map(f =>
                    mode.change(if f == Follow.Tail then Follow.Off
                    else Follow.Tail),
                  )
                case Keyboard.Char('n')                      => find(true)
                case Keyboard.Char('N')                      => find(false)
                case _                                       => Kyo.unit
          div(
            view.combineLatest(text).render { case ((xs, t), q) =>
              div
                .role("log")
                .tabIndex(0)
                .preventScrollKeys
                .onKeyDown(keys)
                .style(_.height(h.px).overflow(_.hidden))(
                  UI.fragment(xs.slice(t, t + h).map(line(_, q))*),
                )
            },
            view.combineLatest(mode.value).combineLatest(text).render {
              case (((xs, t), f), q) =>
                span(status(xs, t, h, f, q)).style(Looks.muted ++ Looks.preLine)
            },
            input.value(text).placeholder("search").style(_.width(20.px)),
          )
    end apply

    /** One line, with the first match of `q` bold and underlined. */
    private def line(l: String, q: String)(using Frame): UI =
        val at = if q.isEmpty then -1 else l.toLowerCase.indexOf(q.toLowerCase)
        if at < 0 then span(if l.isEmpty then " " else l).style(Looks.preLine)
        else
            div.style(Looks.preLine.row.color(Colors.accent))(
              span(l.take(at)),
              span(l.slice(at, at + q.length)).style(_.bold.underline),
              span(l.drop(at + q.length)),
            )
    end line

    private def status(
        xs: Chunk[String],
        t: Int,
        h: Int,
        f: Follow,
        q: String,
    ): String =
        val last  = (t + h).min(xs.size)
        val tail  = if f == Follow.Tail then "  following" else ""
        val found =
          if q.isEmpty then "" else s"  ${matches(xs, q).size} matches"
        s"lines ${if xs.isEmpty then 0 else t + 1}-$last of ${xs.size}$tail$found"
    end status

    /** The index of the first line of the window: the end with `Tail`, else
      * `offset`, in 0 to `total - height`.
      */
    def top(total: Int, height: Int, offset: Int, follow: Follow): Int =
        val max = (total - height).max(0)
        follow match
            case Follow.Tail => max
            case Follow.Off  => offset.min(max).max(0)

    /** The indices of the lines that have `q` (case does not count); none for
      * an empty `q`.
      */
    def matches(lines: Chunk[String], q: String): Chunk[Int] =
      if q.isEmpty then Chunk.empty
      else
          val l = q.toLowerCase
          lines.zipWithIndex.collect {
            case (s, i) if s.toLowerCase.contains(l) => i
          }

    /** The match after `from` (`forward`) or before it, wrapping at the ends;
      * Absent when there is no match.
      */
    def next(ms: Chunk[Int], from: Int, forward: Boolean): Maybe[Int] =
      if ms.isEmpty then Absent
      else if forward then Present(ms.find(_ > from).getOrElse(ms.head))
      else Present(ms.findLast(_ < from).getOrElse(ms.last))
end Pager
