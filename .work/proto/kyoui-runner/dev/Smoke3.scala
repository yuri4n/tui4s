package dev

import kyo.*
import kyo.UI.*
import tui4s.kyoui.*

object Smoke3:
    def show(h: Headless, title: String)(using Frame): Unit < Sync =
        h.screen.map(s => Console.printLine(s"--- $title\n$s").handle(Abort.run[java.io.IOException]).unit)

    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        val file = java.nio.file.Files.createTempFile("todo", ".txt")
        java.nio.file.Files.writeString(file, "[ ] buy milk\n[x] walk dog\n[ ] write report\n")
        val r = KyoApp.Unsafe.runAndBlock(60.seconds) {
            for
                h <- Headless.start(80, 20)(apps.Todo.app(file.toString))
                _ <- show(h, "todo initial")
                _ <- h.typeText("call mom")
                _ <- show(h, "typed")
                f <- h.key(Keyboard.Enter)
                _ <- show(h, s"enter $f")
                _ <- h.key(Keyboard.Tab)
                _ <- h.key(Keyboard.ArrowDown)
                f2 <- h.key(Keyboard.Space)
                _ <- show(h, s"tab down space $f2")
                _ <- h.char('d')
                _ <- h.key(Keyboard.Char('s'), ctrl = true)
                _ <- show(h, "d ctrl-s")
                _ <- Console.printLine("file:\n" + java.nio.file.Files.readString(file))
                h2 <- Headless.start(80, 24)(apps.Dashboard.app)
                _ <- Async.sleep(1200.millis)
                _ <- h2.drainFrames
                _ <- show(h2, "dashboard after 1.2s")
                _ <- h2.key(Keyboard.ArrowRight)
                _ <- show(h2, "dashboard right")
                h3 <- Headless.start(80, 12)(CounterComponentsWrap.app)
                _ <- show(h3, "components")
                _ <- h3.key(Keyboard.Enter)
                _ <- h3.key(Keyboard.Tab)
                _ <- h3.key(Keyboard.Tab)
                _ <- h3.key(Keyboard.Enter)
                _ <- h3.key(Keyboard.Enter)
                _ <- show(h3, "components after presses")
                _ <- h3.char('r')
                _ <- show(h3, "components after parent re-render")
                h4 <- Headless.start(80, 12)(apps.CounterHooks.dynamic)
                _ <- h4.key(Keyboard.Enter)
                _ <- h4.key(Keyboard.Enter)
                _ <- show(h4, "hooks dynamic after 2 presses")
                _ <- h4.char('h')
                _ <- show(h4, "hidden")
                _ <- h4.char('h')
                _ <- show(h4, "shown again")
            yield ()
        }
        println(r)
    end main
end Smoke3

object CounterComponentsWrap:
    val app: UI < Sync =
        for title <- Signal.initRef("A")
        yield div.onKeyDown {
            case KeyboardEvent(Keyboard.Char('r'), _, _) => title.updateAndGet(_ + "A")
            case _                                       => ()
        }(
            title.render(t => div(h2(s"title $t"), div.style(_.row.gap(2.px))(apps.CounterBox("Left"), apps.CounterBox("Right"))))
        )
end CounterComponentsWrap
