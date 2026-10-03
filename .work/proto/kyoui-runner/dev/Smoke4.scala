package dev

import kyo.*
import kyo.UI.*
import tui4s.kyoui.*

object Smoke4:
    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        val file = java.nio.file.Files.createTempFile("todo", ".txt")
        java.nio.file.Files.writeString(file, "[ ] buy milk\n[x] walk dog\n[ ] write report\n")
        val r = KyoApp.Unsafe.runAndBlock(60.seconds) {
            for
                h <- Headless.start(80, 20)(apps.Todo.app(file.toString))
                f1 <- h.key(Keyboard.Tab)
                f2 <- h.key(Keyboard.ArrowDown)
                f3 <- h.key(Keyboard.Space)
                _ <- Console.printLine(Seq(f1, f2, f3).mkString("\n"))
                s <- h.screen
                _ <- Console.printLine(s)
                _ <- Async.sleep(1.second)
                late <- h.drainFrames
                _ <- Console.printLine("late: " + late.mkString("\n"))
                s2 <- h.screen
                _ <- Console.printLine(s2)
            yield ()
        }
        println(r)
    end main
end Smoke4
