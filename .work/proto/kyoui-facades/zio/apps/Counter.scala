package apps.zio

import tui4s.interop.zio.ui.*
import zio.*

/** Counter, hooks style. No Kyo type, no Kyo import. */
object Counter:
    def apply(label: String, key: String): ZIO[Scope, Nothing, HtmlNode] =
        for count <- useSignal(0)
        yield div(
            h1(label),
            count.render(n => p(s"Count: $n")),
            button("-").id(s"$key-dec").onClick(count.update(_ - 1)),
            button("+").id(s"$key-inc").onClick(count.update(_ + 1))
        )
end Counter

/** Counter, Component class style: the runner allocates `State` at mount. */
object CounterComponent extends Component:
    type State = Int
    def initial = 0

    // claude 2026-10-03: changed the state type to the facade's SignalRef
    def view(count: SignalRef[Int]): HtmlNode =
        div(
            h1("Component counter"),
            count.render(n => p(s"Count: $n")),
            button("+").id("c-inc").onClick(count.update(_ + 1)),
            button("reset").id("c-reset").onClick(count.set(0))
        )
end CounterComponent

/** Two independent counters and a component counter on one screen. */
object CounterApp:
    val app: ZIO[Scope, Nothing, UI] =
        for
            a <- Counter("Left", "a")
            b <- Counter("Right", "b")
            c <- CounterComponent.mount
        yield div(a, b, c)
end CounterApp
