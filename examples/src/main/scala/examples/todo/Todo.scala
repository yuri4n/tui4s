package examples.todo

import kyo.*
import kyo.UI.*
// claude 2026-10-13: changed (T2, adapt): tui4s.Tui (the prototype had
// tui4s.kyoui.Tui)
import tui4s.{Tui, TuiApp}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** The Todo app in plain kyo-ui. Components are plain functions of signals and
  * actions. Keys: type a text and press Enter to add it; Tab moves between the
  * input and the list; in the list, the arrows (or `k` and `j`) move, Space
  * toggles, `d` deletes; Ctrl-S writes the list to the file; Esc quits
  * (`Tui.quit`). In the input, `d` and `q` are text. A paste (the bracketed
  * paste of the terminal) is one edit of the input, and a line break in it is a
  * space: a paste never adds an item.
  */
// claude 2026-10-13: changed (T2, RC-33): the Scaladoc names the keys; a file
// that cannot be read is in the status line (the prototype showed "loaded 0
// items")
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

    /** The items of `file`: no file is no item; a file that cannot be read is a
      * failure.
      */
    // claude 2026-10-13: changed (T2): the result keeps the failure
    def load(file: String)(using
        Frame,
    ): Result[java.io.IOException, Chunk[Item]] < Sync =
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
        .map(_.map(parse))

    /** The status line after the load of `file`. */
    // claude 2026-10-13: created (T2)
    def loadStatus(
        file: String,
        loaded: Result[java.io.IOException, Chunk[Item]],
    ): String = loaded match
        case Result.Success(ts) => s"loaded ${ts.size} items from $file"
        case Result.Failure(e)  => s"could not load $file: ${e.getMessage}"
        case Result.Panic(e)    => s"could not load $file: $e"

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

    def app(file: String): UI < (Async & Scope) =
      for
          // claude 2026-10-13: changed (T2): the status of a failed load
          result  <- load(file)
          loaded   = result.getOrElse(Chunk.empty)
          items   <- Signal.initRef(loaded)
          draft   <- Signal.initRef("")
          cursor  <- Signal.initRef(0)
          status  <- Signal.initRef(loadStatus(file, result))
          nextId  <- AtomicInt.init(loaded.size + 1)
          rows     =
            items
              .combineLatest(cursor)
              .map((ts, c) => ts.zipWithIndex.map((t, i) => Row(t, i == c)))
          count    = items.map(ts => s"${ts.count(!_.done)} open / ${ts.size}")
          add      = draft.get.map { text =>
                       if text.trim.isEmpty then ()
                       else
                           nextId.getAndIncrement
                             .map(id =>
                               items.updateAndGet(_ :+ Item(id, text.trim, false)),
                             )
                             .andThen(draft.set(""))
                     }
          atCursor = (f: (Chunk[Item], Int) => Chunk[Item]) =>
                       cursor.get.map(c =>
                         items.updateAndGet(ts =>
                           if c >= 0 && c < ts.size then f(ts, c) else ts,
                         ),
                       )
          toggle   =
            atCursor((ts, c) => ts.updated(c, ts(c).copy(done = !ts(c).done)))
          delete   = atCursor((ts, c) => ts.take(c) ++ ts.drop(c + 1)).map(ts =>
                       cursor.updateAndGet(c => c.min(ts.size - 1).max(0)),
                     )
          move     = (d: Int) =>
                       items.get.map(ts =>
                         cursor.updateAndGet(c =>
                           (c + d).max(0).min((ts.size - 1).max(0)),
                         ),
                       )
          // The result type is load-bearing: without it the match widens to
          // `Any`, and Kyo lifts the effects as plain values, so they never run
          // (the lift trap; AGENTS.md "Rules for code").
          // claude 2026-10-13: changed (T2): the comment names AGENTS.md (the
          // prototype named its REPORT.md)
          listKeys = (e: KeyboardEvent) =>
                       (e.key match
                           case Keyboard.ArrowUp | Keyboard.Char('k')   =>
                             move(-1)
                           case Keyboard.ArrowDown | Keyboard.Char('j') =>
                             move(1)
                           case Keyboard.Space                          => toggle
                           case Keyboard.Char('d')                      => delete
                           case _                                       => ()
                       ): Any < Async
          saveAll  = items.get.map(ts =>
                       Abort.run[java.io.IOException](save(file, ts)).map {
                         case Result.Success(_) =>
                           status.set(s"saved ${ts.size} items to $file")
                         case other             => status.set(s"save failed: $other")
                       },
                     )
      yield main.onKeyDown {
        case KeyboardEvent(Keyboard.Char('s'), mods, _) if mods.ctrl => saveAll
        case KeyboardEvent(Keyboard.Escape, _, _)                    => Tui.quit
        case _                                                       => ()
      }(
        header(count),
        inputBar(draft, add),
        list(rows, listKeys),
        footer(status),
      )
end Todo

/** Runs the Todo app in the real terminal. The list is in a file: the first
  * argument, or `todos.txt` in the working directory. Start it with its start
  * script, not with `sbt run` (sbt also reads the keyboard):
  * {{{
  * sbt examples/launchers
  * examples/target/bin/todo my-list.txt
  * }}}
  */
// claude 2026-10-13: created (T2, RC-33): the prototype's TodoMain read the
// file from the variable TODO_FILE
object TodoApp extends TuiApp:
    def ui: UI < (Async & Scope) =
      Todo.app(args.headOption.getOrElse("todos.txt"))
