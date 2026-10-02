package tui4s.interop.cats.fixtures

import cats.effect.IO
import tui4s.interop.cats.*

/** Small apps for the facade tests, built as values with only the facade
  * import.
  */
object Apps:
    /** Quits with the name of the thread that ran its IO task. */
    val threadNameOnce: App[String] = App[String](
      init = "",
      view = s => text(s),
      update = (s, _) => s,
      sources = s =>
        if s.nonEmpty then Nil
        else
            Seq(
              Source
                .For[IO, String]
                .task(IO(Thread.currentThread().getName))((_, n) => Quit(n)),
            ),
    )

    /** A counter whose state is the number of folds and the sum of the values;
      * it quits when `count` reaches `quitAt`.
      */
    final case class Tally(count: Int = 0, sum: Long = 0) derives CanEqual:
        def add(v: Int, quitAt: Int): Next[Tally] =
            val next = Tally(count + 1, sum + v)
            if next.count >= quitAt then Quit(next) else next

    /** An app that shows the tally and declares `sources(tally)`. */
    def tally(
        sources: Tally => Seq[Source[Tally]],
        options: TuiOptions = TuiOptions(),
    ): App[Tally] =
      App[Tally](
        init = Tally(),
        view = t => text(s"count ${t.count} sum ${t.sum}"),
        update = (t, e) =>
          e match
              case Key.Char('q') => Quit
              case Key.Char('+') => t.copy(count = t.count + 1)
              case _             => t,
        sources = sources,
        options = options,
      )
end Apps
