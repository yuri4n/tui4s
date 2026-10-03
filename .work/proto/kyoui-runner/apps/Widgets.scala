package apps

import kyo.*
import kyo.UI.*

/** Terminal-ish widgets written in PLAIN kyo-ui (spans of block characters), so they also render in a browser. */
object Widgets:

    private val blocks = "▁▂▃▄▅▆▇█"

    /** A horizontal gauge of `width` cells for a ratio in [0, 1]. */
    def gaugeText(ratio: Double, width: Int): String =
        val r      = ratio.max(0).min(1)
        val filled = (r * width).round.toInt
        "█" * filled + "░" * (width - filled) + f" ${r * 100}%3.0f%%"

    /** A sparkline: one block character per value, scaled to [lo, hi]. */
    def sparklineText(values: Chunk[Double], lo: Double = 0, hi: Double = 1): String =
        values.map { v =>
            val i = (((v - lo) / (hi - lo).max(1e-9)) * (blocks.length - 1)).round.toInt.max(0).min(blocks.length - 1)
            blocks.charAt(i)
        }.mkString

    def gauge(ratio: Signal[Double], width: Int, color: Style.Color): UI =
        ratio.render(r => span(gaugeText(r, width)).style(_.color(color)))

    def sparkline(values: Signal[Chunk[Double]], color: Style.Color): UI =
        values.render(vs => span(sparklineText(vs)).style(_.color(color)))

    /** Tabs: a row of buttons in one focus group (ArrowLeft / ArrowRight move between them); the selected one is bold. */
    def tabs(selected: SignalRef[Int], labels: Seq[String]): UI =
        selected.render { sel =>
            nav.style(_.gap(1.px))(
                fragment(labels.zipWithIndex.map { (l, i) =>
                    button(l).focusGroup("tabs").onClick(selected.set(i))
                        .style(if i == sel then Style.bold.color(Style.Color.yellow) else Style.color(Style.Color.gray))
                }*)
            )
        }
end Widgets
