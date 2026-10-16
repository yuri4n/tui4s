package examples.widgets

import kyo.*
import kyo.UI.*

/** Terminal-ish widgets written in PLAIN kyo-ui (spans of block characters), so
  * they also render in a browser. A library of UI functions, not an app: it has
  * no main and no start script; the Dashboard (`examples.dashboard`) uses it.
  */
// claude 2026-10-13: changed (T2, RC-33): the Scaladoc says that this is a
// library (no main)
object Widgets:

    private val blocks = "▁▂▃▄▅▆▇█"

    /** A horizontal gauge of `width` cells for a ratio in [0, 1]. */
    def gaugeText(ratio: Double, width: Int): String =
        val r      = ratio.max(0).min(1)
        val filled = (r * width).round.toInt
        "█" * filled + "░" * (width - filled) + f" ${r * 100}%3.0f%%"

    /** A sparkline: one block character per value, scaled to [lo, hi]. */
    def sparklineText(
        values: Chunk[Double],
        lo: Double = 0,
        hi: Double = 1,
    ): String =
      values.map { v =>
        val i = (((v - lo) / (hi - lo).max(
          1e-9,
        )) * (blocks.length - 1)).round.toInt.max(0).min(blocks.length - 1)
        blocks.charAt(i)
      }.mkString

    def gauge(ratio: Signal[Double], width: Int, color: Style.Color): UI =
      ratio.render(r => span(gaugeText(r, width)).style(_.color(color)))

    def sparkline(values: Signal[Chunk[Double]], color: Style.Color): UI =
      values.render(vs => span(sparklineText(vs)).style(_.color(color)))

    /** Tabs with component-owned selection. Use [[Tabs]] for a panel slot, an
      * initial tab, a change callback, or a parent-owned selection signal.
      */
    def tabs(labels: Seq[String])(using Frame): UI = new Tabs(labels).ui

    /** A two-way binding to a parent's state. */
    def tabs(selected: SignalRef[Int], labels: Seq[String])(using Frame): UI =
      new Tabs(labels, selected = Some(selected), onChange = selected.set).ui
end Widgets
