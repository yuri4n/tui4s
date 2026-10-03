package tui4s.interop.cats.ui

import apps.cats.TodoApp
import cats.effect.*
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*
import munit.FunSuite
import scala.concurrent.duration.DurationInt

/** The Todo scenarios of CatsFacadeSuite, repeated in one JVM (the same rounds as ZioStressSuite). */
// claude 2026-10-03: created a stress suite, to check the Cats side for the race of the ZIO two-way binding
class CatsStressSuite extends FunSuite:
    override val munitTimeout = 300.seconds

    private val rounds = 150

    // claude 2026-10-03: created the runner of one IO program
    private def run[A](io: IO[A]): A = io.unsafeRunSync()(using IORuntime.global)

    // claude 2026-10-03: created the "input then click add" stress case
    test(s"stress: input then click add, $rounds fresh mounts: the handler reads the typed draft every time") {
        val failures = run((1 to rounds).toList.traverse { i =>
            Headless.mount(TodoApp[IO]).use { h =>
                h.input("new", s"task-$i") *> h.click("add") *> h.text.map(t =>
                    Option.when(!(t.contains(s"task-$i") && t.contains("1 left") && t.contains("[]")))(s"round $i:\n$t")
                )
            }
        }).flatten
        println(s"[cats stress add] failures: ${failures.size} of $rounds" + failures.headOption.fold("")(t => s"; first: $t"))
        assertEquals(failures.size, 0, failures.take(3).mkString("\n---\n"))
    }

    // claude 2026-10-03: created the "type fast, then Enter" stress case
    test(s"stress: type char by char then Enter, $rounds rounds on one mount: every item has the full word") {
        val items = run(Headless.mount(TodoApp[IO]).use { h =>
            (1 to rounds).toList.traverse_ { round =>
                val word = s"item-$round"
                (1 to word.length).toList.traverse_(n => h.input("new", word.take(n))) *> h.keyDown("new", "Enter")
            } *> h.text.map(t => """\[[ x]\]~?(item-\d+)""".r.findAllMatchIn(t).map(_.group(1)).toList)
        })
        val expected = (1 to rounds).map(i => s"item-$i").toList
        val wrong    = items.diff(expected) ++ expected.diff(items).map("missing " + _)
        println(s"[cats stress typing] items: ${items.size} of $rounds, wrong or missing: ${wrong.size}" +
            (if wrong.isEmpty then "" else wrong.take(5).mkString(" (", ", ", ")")))
        assertEquals(items, expected)
    }
end CatsStressSuite
