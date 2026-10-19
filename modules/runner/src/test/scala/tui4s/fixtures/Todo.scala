package tui4s.fixtures

import kyo.*
import kyo.UI.*
import tui4s.Tui

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** The Todo app in plain kyo-ui. Components are plain functions of signals and
  * actions. Keys: type a text and press Enter to add it; Tab moves between the
  * input and the list; in the list, the arrows (or `k` and `j`) move, Space
  * toggles, `d` deletes; Ctrl-S saves; Escape quits (`Tui.quit`).
  */
// claude 2026-10-13: changed the Scaladoc (C3): the keys are back, Escape
// quits
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

    // claude 2026-10-13: changed (C3): the key handler of the list is back
    def list(
        rows: Signal[Chunk[Row]],
        onKey: KeyboardEvent => Any < Async,
    ): UI =
      ul.id("list")
        .tabIndex(0)
        .onKeyDown(onKey)
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

    // claude 2026-10-12: created (RC-19): the state, the init, the add action
    // and the view are separate, so that a test writes the refs
    /** The refs of the app, and the file that Ctrl-S writes. */
    // claude 2026-10-13: changed (C3): the file (Ctrl-S saves to it)
    final case class State(
        items: SignalRef[Chunk[Item]],
        draft: SignalRef[String],
        cursor: SignalRef[Int],
        status: SignalRef[String],
        nextId: AtomicInt,
        file: String,
    )

    def init(file: String): State < Sync =
      for
          loaded <- load(file)
          items  <- Signal.initRef(loaded)
          draft  <- Signal.initRef("")
          cursor <- Signal.initRef(0)
          status <- Signal.initRef(s"loaded ${loaded.size} items from $file")
          nextId <- AtomicInt.init(loaded.size + 1)
      yield State(items, draft, cursor, status, nextId, file)

    /** The add action: the draft becomes a new item (Enter in the input). */
    def add(s: State): Unit < Sync =
      s.draft.get.map { text =>
        if text.trim.isEmpty then ()
        else
            s.nextId.getAndIncrement
              .map(id => s.items.updateAndGet(_ :+ Item(id, text.trim, false)))
              .andThen(s.draft.set(""))
      }.unit

    /** Applies `f` to the items and the cursor, when the cursor is on an item. */
    // claude 2026-10-13: created (C3; the prototype's atCursor)
    def atCursor(s: State)(f: (Chunk[Item], Int) => Chunk[Item]): Chunk[Item] <
      Sync =
      s.cursor.get.map(c =>
        s.items.updateAndGet(ts =>
          if c >= 0 && c < ts.size then f(ts, c) else ts,
        ),
      )

    // claude 2026-10-13: created (C3; the prototype's toggle, delete, move)
    def toggle(s: State): Chunk[Item] < Sync =
      atCursor(s)((ts, c) => ts.updated(c, ts(c).copy(done = !ts(c).done)))

    def delete(s: State): Int < Sync =
      atCursor(s)((ts, c) => ts.take(c) ++ ts.drop(c + 1)).map(ts =>
        s.cursor.updateAndGet(c => c.min(ts.size - 1).max(0)),
      )

    def move(s: State, d: Int): Int < Sync =
      s.items.get.map(ts =>
        s.cursor.updateAndGet(c => (c + d).max(0).min((ts.size - 1).max(0))),
      )

    /** Ctrl-S: writes the items to the file and shows the result. */
    // claude 2026-10-13: created (C3; the prototype's saveAll)
    def saveAll(s: State): Unit < Sync =
      s.items.get.map(ts =>
        Abort.run[java.io.IOException](save(s.file, ts)).map {
          case Result.Success(_) =>
            s.status.set(s"saved ${ts.size} items to ${s.file}")
          case other             => s.status.set(s"save failed: $other")
        },
      )

    // claude 2026-10-13: changed (C3): the key handlers of the prototype are
    // back (the list keys, Ctrl-S)
    def view(s: State): UI =
        val rows     =
          s.items
            .combineLatest(s.cursor)
            .map((ts, c) => ts.zipWithIndex.map((t, i) => Row(t, i == c)))
        val count    = s.items.map(ts => s"${ts.count(!_.done)} open / ${ts.size}")
        // The result type is load-bearing: without it the match widens to
        // `Any`, and Kyo lifts the effects as plain values, so they never run
        // (the lift trap; AGENTS.md "Rules for code").
        val listKeys = (e: KeyboardEvent) =>
          (e.key match
              case Keyboard.ArrowUp | Keyboard.Char('k')   => move(s, -1)
              case Keyboard.ArrowDown | Keyboard.Char('j') => move(s, 1)
              case Keyboard.Space                          => toggle(s)
              case Keyboard.Char('d')                      => delete(s)
              case _                                       => ()
          ): Any < Async
        main.onKeyDown {
          case KeyboardEvent(Keyboard.Char('s'), mods, _) if mods.ctrl =>
            saveAll(s)
          // claude 2026-10-13: created (C3): the quit key (Tui.quit)
          case KeyboardEvent(Keyboard.Escape, _, _)                    => Tui.quit
          case _                                                       => ()
        }(
          header(count),
          inputBar(s.draft, add(s)),
          list(rows, listKeys),
          footer(s.status),
        )
    end view

    def app(file: String): UI < (Async & Scope) = init(file).map(view)
end Todo
