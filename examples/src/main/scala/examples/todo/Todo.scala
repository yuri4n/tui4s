package examples.todo

import kyo.*
import kyo.UI.*
// claude 2026-10-19: created: the composables of tui4s-use (L2a)
import tui4s.use.{Binding, Chord, Keymap, useHistory}
// claude 2026-10-13: changed (T2, adapt): tui4s.Tui (the prototype had
// tui4s.kyoui.Tui)
import tui4s.{Tui, TuiApp}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** The Todo app in plain kyo-ui. Components are plain functions of signals and
  * actions. Keys: type a text and press Enter to add it; Tab moves between the
  * input and the list; in the list, the arrows (or `k` and `j`) move, Space
  * toggles, `d` deletes; Ctrl-Z undoes a change of the list and Ctrl-Y redoes
  * it (`useHistory` of tui4s-use); Ctrl-S writes the list to the file; Esc
  * quits (`Tui.quit`). In the input, `d` and `q` are text. A paste (the
  * bracketed paste of the terminal) is one edit of the input, and a line break
  * in it is a space: a paste never adds an item.
  *
  * The list and the input under it are in one viewport that scrolls (`body`): a
  * list longer than the screen keeps the focused input in view, so each new
  * item shows above it; in the list, the selected row stays in view (the list
  * names it with `aria-activedescendant`). A long title ends with "…".
  */
// claude 2026-10-19: changed: the Scaladoc names the scroll and the ellipsis
// (L1a), undo and redo (L2a)
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

    // claude 2026-10-19: changed: the input has the first focus
    // (`focusAuto`), as it comes after the list (L1a)
    def inputBar(draft: SignalRef[String], add: => Any < Async): UI =
      form.onSubmit(add)(
        div.style(_.row.gap(1.px))(
          span("New:"),
          input
            .id("draft")
            .focusAuto(true)
            .placeholder("What needs doing? (Enter adds)")
            .value(draft)
            .style(_.width(40.px)),
        ),
      )

    // claude 2026-10-19: created the region below (L1a): the README section
    // "Scroll, wrap and ellipsis" shows it; the list is built again only for
    // a new selection, and the body needs no `tabIndex(-1)` (L1a review)
    // region: scroll
    /** One line of text: a longer text ends with "…" at the edge of its box
      * (CSS `overflow: hidden`, `text-overflow: ellipsis`,
      * `white-space: nowrap`).
      */
    val oneLine: Style =
      Style.overflow(_.hidden).textOverflow(_.ellipsis).whiteSpace(_.noWrap)

    /** A row: the check box keeps its 3 cells, and a long title ends with "…".
      * The list names the selected row by its id.
      */
    def row(r: Row): Ast.Li =
      li.id(s"item-${r.item.id}")
        .style(
          (if r.selected then
               Style.bg(Style.Color.indigo).color(Style.Color.white)
           else Style.empty).gap(1.px),
        )(
          checkbox.checked(r.item.done).style(_.flexShrink(0)),
          span(r.item.text).style(
            if r.item.done then oneLine.strikethrough.color(Style.Color.gray)
            else oneLine,
          ),
        )

    /** The list keeps the focus while the arrows move the selection:
      * `aria-activedescendant` names the selected row (`selected`, its id), so
      * the viewport around the list scrolls to it, and `preventScrollKeys`
      * keeps the arrows from scrolling it too. kyo-ui's `aria` takes a fixed
      * value, so the list is built again when `selected` changes; a toggle or
      * an add changes only its rows (the `foreachKeyed`).
      */
    def list(
        selected: Signal[String],
        rows: Signal[Chunk[Row]],
        onKey: KeyboardEvent => Any < Async,
    ): UI =
      selected.map(id =>
        ul.id("list")
          .tabIndex(0)
          .preventScrollKeys
          .aria("activedescendant", id)
          .onKeyDown(onKey)
          .style(
            _.border(1.px, Style.Color.slate)
              .minHeight(4.px)
              .focus(_.border(1.px, Style.Color.blue)),
          )(
            rows.foreachKeyed(_.item.id.toString)(row),
          ): UI,
      )

    /** The list and the input bar under it, in a viewport that takes the free
      * rows and scrolls (`overflowY: auto`). It is not a tab stop, because it
      * holds focusable elements (its list and its input).
      */
    def body(list: UI, input: UI): UI =
      div.style(_.overflowY(_.auto).flexGrow(1))(list, input)
    // endregion: scroll

    /** The id of the row at `cursor`, or "" when there is none. */
    // claude 2026-10-19: created (L1a review)
    def rowId(items: Chunk[Item], cursor: Int): String =
      items.lift(cursor).fold("")(t => s"item-${t.id}")

    /** The status and two help lines: the keys of the list, then the keys of
      * the app (from its keymap: "Ctrl-Z: undo Ctrl-Y: redo ...").
      */
    // claude 2026-10-19: changed: the keys of the app come from its keymap
    // (L2a)
    def footer(status: Signal[String], keys: Keymap): UI =
      div(
        span(status).id("status").style(_.color(Style.Color.green)),
        span("Tab: input/list  ↑↓: move  Space: toggle  d: delete")
          .style(_.color(Style.Color.gray)),
        span(keys.help.map((k, h) => s"$k: $h").mkString("  "))
          .style(_.color(Style.Color.gray)),
      )

    // ---- the app ----

    // claude 2026-10-19: changed: the writes of the list go through its
    // history (undo, redo), and the keys of the app are a keymap (L2a)
    def app(file: String): UI < (Async & Scope) =
      for
          // claude 2026-10-13: changed (T2): the status of a failed load
          result   <- load(file)
          loaded    = result.getOrElse(Chunk.empty)
          items    <- Signal.initRef(loaded)
          history  <- useHistory(items, 100)
          draft    <- Signal.initRef("")
          cursor   <- Signal.initRef(0)
          // claude 2026-10-19: created (L1a review): the id of the selected
          // row; a SignalRef notifies only a new value
          selected <- Signal.initRef(rowId(loaded, 0))
          status   <- Signal.initRef(loadStatus(file, result))
          nextId   <- AtomicInt.init(loaded.size + 1)
          rows      =
            items
              .combineLatest(cursor)
              .map((ts, c) => ts.zipWithIndex.map((t, i) => Row(t, i == c)))
          count     = items.map(ts => s"${ts.count(!_.done)} open / ${ts.size}")
          // The cursor stays in the list, and the selected row follows it.
          select    = items.get.map(ts =>
                        cursor
                          .updateAndGet(c => c.min(ts.size - 1).max(0))
                          .map(c => selected.set(rowId(ts, c))),
                      )
          add       =
            draft.get.map { text =>
              if text.trim.isEmpty then ()
              else
                  nextId.getAndIncrement
                    .map(id => history.update(_ :+ Item(id, text.trim, false)))
                    .andThen(draft.set(""))
                    .andThen(select)
            }
          atCursor  = (f: (Chunk[Item], Int) => Chunk[Item]) =>
                        cursor.get.map(c =>
                          history.update(ts =>
                            if c >= 0 && c < ts.size then f(ts, c) else ts,
                          ),
                        )
          toggle    =
            atCursor((ts, c) => ts.updated(c, ts(c).copy(done = !ts(c).done)))
          delete    =
            atCursor((ts, c) => ts.take(c) ++ ts.drop(c + 1)).andThen(select)
          move      = (d: Int) =>
                        items.get
                          .map(ts =>
                            cursor.updateAndGet(c =>
                              (c + d).max(0).min((ts.size - 1).max(0)),
                            ),
                          )
                          .andThen(select)
          // The result type is load-bearing: without it the match widens to
          // `Any`, and Kyo lifts the effects as plain values, so they never run
          // (the lift trap; AGENTS.md "Rules for code").
          // claude 2026-10-13: changed (T2): the comment names AGENTS.md (the
          // prototype named its REPORT.md)
          listKeys  = (e: KeyboardEvent) =>
                        (e.key match
                            case Keyboard.ArrowUp | Keyboard.Char('k')   =>
                              move(-1)
                            case Keyboard.ArrowDown | Keyboard.Char('j') =>
                              move(1)
                            case Keyboard.Space                          => toggle
                            case Keyboard.Char('d')                      => delete
                            case _                                       => ()
                        ): Any < Async
          saveAll   = items.get.map(ts =>
                        Abort.run[java.io.IOException](save(file, ts)).map {
                          case Result.Success(_) =>
                            status.set(s"saved ${ts.size} items to $file")
                          case other             => status.set(s"save failed: $other")
                        },
                      )
          keys      = Keymap.of(
                        Binding.of("undo", Chord.ctrl('z'))(
                          history.undo.andThen(select),
                        ),
                        Binding.of("redo", Chord.ctrl('y'))(
                          history.redo.andThen(select),
                        ),
                        Binding.of("save", Chord.ctrl('s'))(saveAll),
                        Binding.of("quit", Chord(Keyboard.Escape))(Tui.quit),
                      )
      // claude 2026-10-19: changed: the screen height, and the body that
      // scrolls (L1a)
      yield main
        .onKeyDown(keys.handler)
        .style(_.height(100.pct))(
          header(count),
          body(list(selected, rows, listKeys), inputBar(draft, add)),
          footer(status, keys),
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
