package tui4s.widgets

import kyo.*
import kyo.UI.*
import tui4s.use.useNow

/** The block characters of the charts: eighths of a cell and the levels of a
  * sparkline.
  */
// claude 2026-10-19: created (L2b)
private[widgets] object Blocks:
    /** A cell filled by 0 to 8 eighths, from the left. */
    val eighths: Chunk[String] =
      Chunk(" ", "▏", "▎", "▍", "▌", "▋", "▊", "▉", "█")

    /** A bar of `cells` (a fraction of cells) in eighth blocks. */
    def bar(cells: Double): String =
        val n = (cells.max(0) * 8).round.toInt
        "█" * (n / 8) + (if n % 8 == 0 then "" else eighths(n % 8))

    /** A number with no decimals when it is whole, else with one. */
    def num(v: Double): String =
      if v == math.rint(v) && v.abs < 1e15 then v.toLong.toString
      else s"${(v * 10).round / 10.0}"
end Blocks

/** A sparkline: one block character per value ("▁▂▃▄▅▆▇█"), scaled to `lo` to
  * `hi`, in `color` (default `--tui4s-accent`). A pure UI value.
  *
  * {{{
  * Sparkline(cpuHistory)
  * }}}
  */
// claude 2026-10-19: created (L2b): moved from examples.widgets.Widgets
object Sparkline:
    private val levels = "▁▂▃▄▅▆▇█"

    /** The sparkline of `values`. */
    def apply(
        values: Signal[Chunk[Double]],
        lo: Double = 0,
        hi: Double = 1,
        color: Style.Color = Colors.accent,
    )(using Frame): UI =
      values.render(vs => span(text(vs, lo, hi)).style(_.color(color)))

    /** The text of the sparkline of `values`. */
    def text(values: Chunk[Double], lo: Double = 0, hi: Double = 1): String =
      values.map { v =>
        val i =
          (((v - lo) / (hi - lo).max(1e-9)) * (levels.length - 1)).round.toInt
        levels.charAt(i.max(0).min(levels.length - 1))
      }.mkString
end Sparkline

/** A gauge: a ratio in 0 to 1 as a bar of `width` cells ("█" filled, "░" empty)
  * and a percentage, in `color` (default `--tui4s-accent`). A ratio outside 0
  * to 1 is cut to it. A pure UI value.
  *
  * {{{
  * Gauge(memory.map(m => m.used / m.total), width = 30)
  * }}}
  */
// claude 2026-10-19: created (L2b): moved from examples.widgets.Widgets
object Gauge:
    /** The gauge of `ratio`. */
    def apply(
        ratio: Signal[Double],
        width: Int = 20,
        color: Style.Color = Colors.accent,
    )(using Frame): UI =
      ratio.render(r =>
        span(text(r, width)).role("meter").style(_.color(color)),
      )

    /** The text of the gauge of `ratio`: "████░░░░░░  42%". */
    def text(ratio: Double, width: Int): String =
        val r      = ratio.max(0).min(1)
        val filled = (r * width).round.toInt
        val pct    = (r * 100).round.toString
        "█" * filled + "░" * (width - filled) + " " + " " * (3 - pct.length) + pct + "%"
end Gauge

/** A progress bar: `done` of `total` as a bar of `width` cells in eighth blocks
  * between "▕" and "▏", and "3/10". The bar is `--tui4s-accent`. A pure UI
  * value.
  *
  * {{{
  * ProgressBar(downloaded, Signal.initConst(files.size))
  * }}}
  */
// claude 2026-10-19: created (L2b)
object ProgressBar:
    /** The progress bar of `done` of `total`. */
    def apply(done: Signal[Int], total: Signal[Int], width: Int = 20)(using
        Frame,
    ): UI =
      done
        .combineLatest(total)
        .render((d, t) =>
          div
            .role("progressbar")
            .style(_.row)(
              span(bar(d, t, width)).style(_.color(Colors.accent)),
              span(s" ${d.max(0).min(t.max(0))}/${t.max(0)}"),
            ),
        )

    /** The bar of `done` of `total`: `width` cells between "▕" and "▏". */
    def bar(done: Int, total: Int, width: Int): String =
        val w    = width.max(0)
        val r    =
          if total <= 0 then 0.0 else done.max(0).min(total).toDouble / total
        val fill = Blocks.bar(r * w)
        "▕" + fill + " " * (w - fill.length) + "▏"
end ProgressBar

/** Horizontal bars in text cells: one row per bar, with its label, a bar of
  * eighth blocks scaled to `max` (Absent: the largest value) in `width` cells,
  * and its value. The labels are one column and the bars another, so the layout
  * measures the labels in cells: a wide character in a label does not move its
  * bar. A pure UI value.
  *
  * {{{
  * BarChart(Signal.initConst(Chunk("Mon" -> 3.0, "Tue" -> 7.5)), width = 24)
  * }}}
  */
// claude 2026-10-19: created (L2b)
object BarChart:
    /** The chart of `bars` (a label and a value each). */
    def apply(
        bars: Signal[Chunk[(String, Double)]],
        width: Int = 30,
        max: Maybe[Double] = Absent,
        color: Style.Color = Colors.accent,
    )(using Frame): UI =
      // claude 2026-10-19: changed (L2b review): a column of labels and a
      // column of bars, not labels padded by String.length
      bars.render { bs =>
        val top = max.getOrElse(bs.map(_._2).foldLeft(0.0)(_ max _))
        div
          .role("img")
          .style(_.row)(
            div.style(_.margin(0.px, 1.px, 0.px, 0.px))(
              UI.fragment(
                bs.map((l, _) => div(if l.isEmpty then " " else l))*,
              ),
            ),
            div(
              UI.fragment(bs.map { (_, v) =>
                div.style(_.row)(
                  span(bar(v, top, width)).style(_.color(color)),
                  span(" " + Blocks.num(v)).style(Looks.muted),
                )
              }*),
            ),
          )
      }

    /** The bar of `value` of `max` in at most `width` cells. */
    def bar(value: Double, max: Double, width: Int): String =
      if max <= 0 || value <= 0 then ""
      else Blocks.bar((value / max).min(1) * width.max(0))
end BarChart

/** A spinner: a frame of `frames` that changes every `every`, from the clock of
  * tui4s-use (`useNow`), and a label. The clock is a fiber that ends with the
  * caller's `Scope`. [[Spinner.dots]] and [[Spinner.line]] are two sets of
  * frames.
  *
  * {{{
  * when(loading)(spinner) // spinner <- Spinner(label = "loading")
  * }}}
  */
// claude 2026-10-19: created (L2b)
object Spinner:
    /** Braille dots: "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏". */
    val dots: Chunk[String] =
      Chunk("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")

    /** A line that turns: "-\|/". */
    val line: Chunk[String] = Chunk("-", "\\", "|", "/")

    /** The spinner with `label` after its frame. */
    def apply(
        label: String = "",
        frames: Chunk[String] = dots,
        every: Duration = 100.millis,
    )(using Frame): UI < (Sync & Scope) =
      useNow(every).map(now =>
        span(
          now.map(t =>
            if frames.isEmpty then label
            else s"${frames(frameAt(t, every, frames.size))} $label",
          ),
        ).role("status").style(_.color(Colors.accent)),
      )

    /** The index of the frame at the time `t`: one step per `every` (at least 1
      * ms) since the epoch, in 0 to `n - 1`.
      */
    def frameAt(t: Instant, every: Duration, n: Int): Int =
      if n <= 0 then 0
      else
          Math
            .floorMod(t.toDuration.toMillis / every.toMillis.max(1), n.toLong)
            .toInt
end Spinner
