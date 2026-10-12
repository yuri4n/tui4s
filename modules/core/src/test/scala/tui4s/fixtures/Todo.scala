package tui4s.fixtures

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kyo.*
import kyo.UI.*

/** The Todo app in plain kyo-ui. Components are plain functions of signals and
  * actions.
  */
object Todo:

    final case class Item(id: Int, text: String, done: Boolean) derives CanEqual
    final case class Row(item: Item, selected: Boolean) derives CanEqual

    // ---- file ----

    def parse(lines: Chunk[String]): Chunk[Item] =
      lines
        .filter(_.length >= 4)
        .zipWithIndex
        .map((l, i) => Item(i + 1, l.drop(4), l.startsWith("[x]")))

    def render(items: Chunk[Item]): String =
      items
        .map(t => s"${if t.done then "[x]" else "[ ]"} ${t.text}")
        .mkString("", "\n", "\n")

    def load(file: String)(using Frame): Chunk[Item] < Sync =
      Abort
        .run[java.io.IOException](Abort.catching[java.io.IOException] {
          val p = Paths.get(file)
          if Files.exists(p) then
              Chunk.from(
                Files
                  .readAllLines(p, StandardCharsets.UTF_8)
                  .toArray
                  .map(_.toString),
              )
          else Chunk.empty
          end if
        })
        .map(r => parse(r.getOrElse(Chunk.empty)))

    def save(file: String, items: Chunk[Item])(using
        Frame,
    ): Unit < (Sync & Abort[java.io.IOException]) =
      Abort
        .catching[java.io.IOException](
          Files
            .writeString(Paths.get(file), render(items), StandardCharsets.UTF_8),
        )
        .unit

    // ---- components ----

    def header(count: Signal[String]): UI =
      div.style(_.row.gap(2.px))(
        h1("Todo"),
        span(count).style(_.color(Style.Color.gray)),
      )

    def inputBar(draft: SignalRef[String], add: => Any < Async): UI =
      form.onSubmit(add)(
        div.style(_.row.gap(1.px))(
          span("New:"),
          input
            .id("draft")
            .placeholder("What needs doing? (Enter adds)")
            .value(draft)
            .style(_.width(40.px)),
        ),
      )

    def row(r: Row): Ast.Li =
      li.style(if r.selected then
          Style.bg(Style.Color.indigo).color(Style.Color.white)
      else Style.empty)(
        checkbox.checked(r.item.done),
        span(" " + r.item.text).style(if r.item.done then
            Style.strikethrough.color(Style.Color.gray)
        else Style.empty),
      )

    def list(rows: Signal[Chunk[Row]]): UI =
      ul.id("list")
        .tabIndex(0)
        .style(
          _.border(1.px, Style.Color.slate)
            .minHeight(4.px)
            .focus(_.border(1.px, Style.Color.blue)),
        )(
          rows.foreachKeyed(_.item.id.toString)(row),
        )

    def footer(status: Signal[String]): UI =
      div(
        span(status).id("status").style(_.color(Style.Color.green)),
        span(
          "Tab: input/list  ↑↓: move  Space: toggle  d: delete  Ctrl-S: save  Esc: quit",
        ).style(_.color(Style.Color.gray)),
      )

    // ---- the app ----

    def app(file: String): UI < (Async & Scope) =
      for
          loaded <- load(file)
          items  <- Signal.initRef(loaded)
          draft  <- Signal.initRef("")
          cursor <- Signal.initRef(0)
          status <- Signal.initRef(s"loaded ${loaded.size} items from $file")
          nextId <- AtomicInt.init(loaded.size + 1)
          rows    =
            items
              .combineLatest(cursor)
              .map((ts, c) => ts.zipWithIndex.map((t, i) => Row(t, i == c)))
          count   = items.map(ts => s"${ts.count(!_.done)} open / ${ts.size}")
          add     = draft.get.map { text =>
                      if text.trim.isEmpty then ()
                      else
                          nextId.getAndIncrement
                            .map(id =>
                              items.updateAndGet(_ :+ Item(id, text.trim, false)),
                            )
                            .andThen(draft.set(""))
                    }
      yield main(
        header(count),
        inputBar(draft, add),
        list(rows),
        footer(status),
      )
end Todo
