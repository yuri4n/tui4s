package smoke

import kyo.*
import kyo.UI.*
import kyo.tui4sproto.Headless

/** Kyo-native Counter through the headless runner: the baseline for the facades. */
object Smoke extends KyoApp:
    run {
        for
            count <- Signal.initRef(0)
            ui = div(
                h1("Counter"),
                count.render(n => p(s"Count: $n")),
                button("+").id("inc").onClick(count.getAndUpdate(_ + 1))
            )
            texts <- Scope.run {
                for
                    h  <- Headless.mount(ui)
                    t0 <- h.text
                    _  <- h.send(Headless.Event.Click("inc"))
                    _  <- h.send(Headless.Event.Click("inc"))
                    t1 <- h.text
                    html <- h.html
                yield (t0, t1, html)
            }
            _ <- Console.printLine(s"before:\n${texts._1}\nafter two clicks:\n${texts._2}\nhtml: ${texts._3}")
        yield ()
    }
end Smoke
