package tui4s.interop.zio.ui

import apps.zio.TodoApp
import munit.FunSuite
import scala.concurrent.duration.Duration as SDuration
import zio.*

/** The Todo scenarios of ZioFacadeSuite, repeated in one JVM: a race that shows once in three runs shows here every
  * time. Each round mounts a fresh app.
  */
// claude 2026-10-03: created a stress suite that measures the flaky rate of the two-way binding in one JVM
class ZioStressSuite extends FunSuite:
    override val munitTimeout = SDuration(300, "s")

    private val rounds = 150

    // claude 2026-10-03: created the runner of one scoped ZIO program
    private def run[A](z: ZIO[Scope, Any, A]): A =
        Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(ZIO.scoped(z)).getOrThrowFiberFailure())

    // claude 2026-10-03: created the "input then click add" stress case (failure 1 of the baseline)
    test(s"stress: input then click add, $rounds fresh mounts: the handler reads the typed draft every time") {
        val failures = run(ZIO.foreach((1 to rounds).toList) { i =>
            ZIO.scoped(
                for
                    h <- Headless.mount(TodoApp.app)
                    _ <- h.input("new", s"task-$i") *> h.click("add")
                    t <- h.text
                yield Option.when(!(t.contains(s"task-$i") && t.contains("1 left") && t.contains("[]")))(s"round $i:\n$t")
            )
        }).flatten
        println(s"[zio stress add] failures: ${failures.size} of $rounds" + failures.headOption.fold("")(t => s"; first: $t"))
        assertEquals(failures.size, 0, failures.take(3).mkString("\n---\n"))
    }

    // claude 2026-10-03: created the "type fast, then Enter" stress case (failure 2 of the baseline)
    test(s"stress: type char by char then Enter, $rounds rounds on one mount: every item has the full word") {
        val items = run(
            for
                h <- Headless.mount(TodoApp.app)
                _ <- ZIO.foreachDiscard(1 to rounds) { round =>
                    val word = s"item-$round"
                    ZIO.foreachDiscard(1 to word.length)(n => h.input("new", word.take(n))) *> h.keyDown("new", "Enter")
                }
                t <- h.text
            yield """\[[ x]\]~?(item-\d+)""".r.findAllMatchIn(t).map(_.group(1)).toList
        )
        val expected = (1 to rounds).map(i => s"item-$i").toList
        val wrong    = items.diff(expected) ++ expected.diff(items).map("missing " + _)
        println(s"[zio stress typing] items: ${items.size} of $rounds, wrong or missing: ${wrong.size}" +
            (if wrong.isEmpty then "" else wrong.take(5).mkString(" (", ", ", ")")))
        assertEquals(items, expected)
    }
end ZioStressSuite
