package tui4s.fixtures

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kyo.*
import scala.jdk.CollectionConverters.*
import tui4s.*

enum Pane derives CanEqual:
    case Input, Items

final case class Item(title: String, done: Boolean = false) derives CanEqual

/** The model of the Todo sample (SPEC 3.2). */
final case class Todo(
    field: TextField = TextField.empty,
    items: Vector[Item] = Vector.empty,
    selected: Int = 0,
    focus: Focus[Pane] = Focus(Pane.Input, Pane.Items),
    ready: Boolean = false,
    status: String = "loading…",
) derives CanEqual:
    def left: Int = items.count(!_.done)

    def add: Todo =
      if field.isBlank then this
      else
          copy(
            field = TextField.empty,
            items = items :+ Item(field.text.trim),
            selected = items.size,
          )

    def move(by: Int): Todo =
      copy(selected = (selected + by).max(0).min((items.size - 1).max(0)))

    def toggle: Todo =
      if items.isEmpty then this
      else
          val item = items(selected)
          copy(items = items.updated(selected, item.copy(done = !item.done)))

    def delete: Todo =
      if items.isEmpty then this
      else
          val rest = items.patch(selected, Nil, 1)
          copy(items = rest, selected = selected.min((rest.size - 1).max(0)))

    def nextPane: Todo = copy(focus = focus.next)

    def typed(event: Event): Todo =
        val f = field.edit(event)
        if f eq field then this else copy(field = f)

    def loaded(r: Result[IOException, Vector[Item]]): Todo = r match
        case Result.Success(items) =>
          copy(items = items, ready = true, status = s"${items.size} loaded")
        case failed                =>
          copy(ready = true, status = s"could not load: $failed")

    def saved(r: Result[IOException, Unit]): Todo = r match
        case Result.Success(_) => copy(status = "saved")
        case failed            => copy(status = s"could not save: $failed")
end Todo

/** Plain Kyo code. A test copy: it writes into the temp directory. */
object TodoStore:
    val file: Path =
      Path.of(
        java.lang.System.getProperty("java.io.tmpdir"),
        "tui4s-todo-test.txt",
      )

    def load(using Frame): Vector[Item] < (Sync & Abort[IOException]) =
      Abort.catching[IOException](Sync.defer {
        if !Files.exists(file) then Vector.empty
        else
            Files
              .readAllLines(file)
              .asScala
              .toVector
              .map(l => Item(l.drop(4), done = l.startsWith("[x]")))
      })

    def save(
        items: Vector[Item],
    )(using Frame): Unit < (Sync & Abort[IOException]) =
      Abort
        .catching[IOException](Sync.defer {
          Files.write(
            file,
            items.map(i => (if i.done then "[x] " else "[ ] ") + i.title).asJava,
          )
        })
        .unit
end TodoStore

/** The Todo of SPEC 3.2, as an `App` with `Source.For` for `source`. */
object TodoApp extends App[Todo]:
    private val source = Source.For[Todo]

    val global: Keymap[Todo]    = Keymap(
      bind(Key.Tab, "focus")(_.nextPane).or(Key.BackTab),
      bind(Key.Ctrl('s'), "save")(save),
      bind(Key.Esc, "quit")(_ => Quit),
    )
    val inputKeys: Keymap[Todo] =
      Keymap(bind(Key.Enter, "add")(_.add)) ++ global
    val itemKeys: Keymap[Todo]  = Keymap(
      bind(Key.Up, "up")(_.move(-1)).or('k'),
      bind(Key.Down, "down")(_.move(1)).or('j'),
      bind(' ', "toggle")(_.toggle),
      bind('d', "delete")(_.delete),
      bind('q', "quit")(_ => Quit),
    ) ++ global

    def keys(t: Todo): Keymap[Todo] =
      if t.focus.is(Pane.Input) then inputKeys else itemKeys

    def init: Todo = Todo()

    /** Load once at start: the initial state declares the task; its fold sets
      * `ready`, which removes it.
      */
    override def sources(t: Todo): Seq[Source[Todo]] =
      if t.ready then Nil
      else
          Seq(
            source
              .task(Abort.run[IOException](TodoStore.load))(_.loaded(_))
              .id(
                "load",
              ),
          )

    /** One-shot: with an id, a second Ctrl-S replaces a running save. */
    private def save(t: Todo): Next[Todo] =
      t.copy(status = "saving…")
        .withTask(
          source
            .task(Abort.run[IOException](TodoStore.save(t.items)))(
              _.saved(_),
            )
            .id("save"),
        )

    def update(t: Todo, event: Event): Next[Todo] =
        val active = keys(t)
        event match
            case active(action)              => action(t)
            case e if t.focus.is(Pane.Input) => t.typed(e)
            case _                           => t

    def view(t: Todo): View =
        val inInput = t.focus.is(Pane.Input)
        column(
          input(t.field, focused = inInput, placeholder = "What needs doing?")
            .bordered(" New ", focused = inInput)
            .size(3),
          list(t.items.map(itemLine), t.selected, focused = !inInput)
            .bordered(s" Todo · ${t.left} left ", focused = !inInput),
          row(help(keys(t)), text(t.status.dim).right.size(24)).size(1),
        )
    end view

    private def itemLine(item: Item): Line =
      if item.done then line("✔ ".green, item.title.dim.crossedOut)
      else line("☐ ", item.title)
end TodoApp
