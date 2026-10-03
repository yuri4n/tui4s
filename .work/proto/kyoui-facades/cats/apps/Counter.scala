package apps.cats

import cats.effect.*
import fs2.concurrent.SignallingRef
import tui4s.interop.cats.ui.*

/** Counter, hooks style, for any F with an Async. No Kyo type, no Kyo import. */
object Counter:
    def apply[F[_]: Async](label: String, key: String): Resource[F, HtmlNode] =
        for count <- useSignal[F](0)
        yield div(
            h1(label),
            count.render(n => p(s"Count: $n")),
            button("-").id(s"$key-dec").onClick(count.update(_ - 1)),
            button("+").id(s"$key-inc").onClick(count.update(_ + 1))
        )
end Counter

/** Counter, Component class style: the runner allocates `State` at mount. */
final class CounterComponent[F[_]: Async] extends Component[F]:
    type State = Int
    def initial = 0

    def view(count: SignallingRef[F, Int]): HtmlNode =
        div(
            h1("Component counter"),
            count.render(n => p(s"Count: $n")),
            button("+").id("c-inc").onClick(count.update(_ + 1)),
            button("reset").id("c-reset").onClick(count.set(0))
        )
end CounterComponent

/** Two independent counters and a component counter on one screen, in IO. */
object CounterApp:
    val app: Resource[IO, UI] =
        for
            a <- Counter[IO]("Left", "a")
            b <- Counter[IO]("Right", "b")
            c <- CounterComponent[IO]().mount
        yield div(a, b, c)
end CounterApp
