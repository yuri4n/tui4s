package apps.zio

import tui4s.interop.zio.ui.*
import zio.*

final case class Todo(id: Int, title: String, done: Boolean)

/** Todo, hooks style: a two-way bound input, Enter or a button to add, a keyed list with checkboxes. */
object TodoApp:
    val app: ZIO[Scope, Nothing, HtmlNode] =
        for
            todos  <- useSignal(List.empty[Todo])
            draft  <- useSignal("")
            nextId <- Ref.make(1)
        yield
            val add: UIO[Unit] =
                for
                    title <- draft.get
                    _ <- ZIO.when(title.trim.nonEmpty) {
                        nextId.getAndUpdate(_ + 1).flatMap(id => todos.update(_ :+ Todo(id, title.trim, false))) *>
                            draft.set("")
                    }
                yield ()

            def toggle(id: Int, done: Boolean): UIO[Unit] =
                todos.update(_.map(t => if t.id == id then t.copy(done = done) else t))

            div(
                h1("Todos"),
                input.id("new").placeholder("What needs doing?").value(draft)
                    .onKeyDown(e => ZIO.when(e.key == Keyboard.Enter)(add)),
                button("Add").id("add").onClick(add),
                ul(
                    todos.signal.foreachKeyed(_.id.toString)(t =>
                        li(
                            checkbox.id(s"done-${t.id}").checked(t.done).onChange(d => toggle(t.id, d)),
                            span(if t.done then s"~${t.title}~" else t.title),
                            button("x").id(s"del-${t.id}").onClick(todos.update(_.filterNot(_.id == t.id)))
                        )
                    )
                ),
                todos.render(ts => p(s"${ts.count(!_.done)} left")),
                when(todos.signal.map(_.exists(_.done)))(
                    button("Clear done").id("clear").onClick(todos.update(_.filterNot(_.done)))
                )
            )
end TodoApp
