package tui4s.fixtures

import tui4s.*

enum Pane derives CanEqual:
    case Input, Items

final case class Item(title: String, done: Boolean = false) derives CanEqual

/** The model of the Todo sample (SPEC 3.2) without the load and save work,
  * which needs sources.
  */
final case class Todo(
    field: TextField = TextField.empty,
    items: Vector[Item] = Vector.empty,
    selected: Int = 0,
    focus: Focus[Pane] = Focus(Pane.Input, Pane.Items),
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
end Todo

object TodoApp extends App[Todo]:
    val global: Keymap[Todo]    = Keymap(
      bind(Key.Tab, "focus")(_.nextPane).or(Key.BackTab),
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
          help(keys(t)).size(1),
        )
    end view

    private def itemLine(item: Item): Line =
      if item.done then line("✔ ".green, item.title.dim.crossedOut)
      else line("☐ ", item.title)
end TodoApp
