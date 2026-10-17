package examples.spikeshared

import kyo.*
import kyo.UI.*

/** SPIKE: one platform-free kyo-ui view and its store. Only kyo-ui and Kyo
  * types: no tui4s, no java.nio, no DOM. The same file compiles in the JVM
  * tui4s build (examples) and in the Scala.js build (spike-js).
  */
final class Store(
    val count: SignalRef[Int],
    val messages: SignalRef[Chunk[String]],
    val draft: SignalRef[String],
):
    def inc(using Frame): Unit < Sync = count.updateAndGet(_ + 1).unit

    def add(using Frame): Unit < Sync =
      draft.get.map { text =>
        if text.trim.isEmpty then ()
        else messages.updateAndGet(_ :+ text.trim).andThen(draft.set(""))
      }
end Store

object Store:
    def init(using Frame): Store < Sync =
      for
          count    <- Signal.initRef(0)
          messages <- Signal.initRef(Chunk.empty[String])
          draft    <- Signal.initRef("")
      yield Store(count, messages, draft)
end Store

object SharedView:

    /** The shared view: a pure value over the store. */
    def view(store: Store)(using Frame): UI =
      div(
        h1("Spike: one UI, two surfaces"),
        span("Count: ", store.count.map(_.toString)).id("count"),
        form.onSubmit(store.add)(
          div.style(_.row.gap(1.em))(
            span("Message:"),
            input
              .id("draft")
              .placeholder("type, Enter adds")
              .value(store.draft)
              // em: cells in tui4s, font units in a browser
              .style(_.width(30.em)),
          ),
        ),
        button("+1").id("inc").onClick(store.inc),
        span("Messages:"),
        ul.id("messages")(
          store.messages.foreachIndexed((i, m) => li(s"${i + 1}. $m")),
        ),
      )
end SharedView
