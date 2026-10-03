package apps.cats

import cats.effect.*
import cats.syntax.all.*
import tui4s.interop.cats.ui.*

final case class Todo(id: Int, title: String, done: Boolean)

/** Todo, hooks style, tagless final: a two-way bound input, Enter or a button to add, a keyed list with checkboxes. */
object TodoApp:
    def apply[F[_]: Async]: Resource[F, HtmlNode] =
        for
            todos  <- useSignal[F](List.empty[Todo])
            draft  <- useSignal[F]("")
            nextId <- Resource.eval(Ref.of[F, Int](1))
        yield
            val add: F[Unit] =
                draft.get.flatMap { title =>
                    (nextId.getAndUpdate(_ + 1).flatMap(id => todos.update(_ :+ Todo(id, title.trim, false))) *>
                        draft.set("")).whenA(title.trim.nonEmpty)
                }

            def toggle(id: Int, done: Boolean): F[Unit] =
                todos.update(_.map(t => if t.id == id then t.copy(done = done) else t))

            div(
                h1("Todos"),
                input.id("new").placeholder("What needs doing?").value(draft)
                    .onKeyDown(e => add.whenA(e.key == Keyboard.Enter)),
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
