package integration

import apps.cats.CounterApp
import apps.cats.TodoApp
import cats.effect.*
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*
import java.util.concurrent.Executors
import munit.FunSuite
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt // not duration.*: its `span` would shadow the mirror's span here
import tui4s.interop.cats.ui.*
import tui4s.kyoui.FrameStats

/** The UNCHANGED Cats facade apps on the kyo-ui TERMINAL runner (headless: a jatatui TestBackend of 100x30). */
// claude 2026-10-03: created the Cats integration suite (facade apps x terminal runner)
class CatsOnTerminalSuite extends FunSuite:
    override val munitTimeout = 120.seconds

    // claude 2026-10-03: created run
    private def run[A](io: IO[A]): A = io.unsafeRunSync()(using IORuntime.global)

    // claude 2026-10-03: created counts: the "Count: n" texts of the screen, top to bottom
    private def counts(s: String): List[String] = "Count: -?\\d+".r.findAllIn(s).toList

    // claude 2026-10-03: created check: an assertion inside an IO for-comprehension
    private def check(ok: Boolean, msg: => String): IO[Unit] = IO(assert(ok, msg))

    /** Presses Tab (or Shift-Tab) until the element with `id` has the focus (at most `max` presses). */
    // claude 2026-10-03: created focusOn
    private def focusOn(h: TuiHeadless[IO], id: String, back: Boolean = false, max: Int = 12): IO[FrameStats] =
        def loop(n: Int): IO[FrameStats] =
            h.key(Keyboard.Tab, shift = back).flatMap { f =>
                if f.focus == id then IO.pure(f)
                else if n >= max then IO.raiseError(new AssertionError(s"no focus on '$id' after $max presses (focus: ${f.focus})"))
                else loop(n + 1)
            }
        loop(1)

    // claude 2026-10-03: created the test "Cats Counter (facade app, unchanged)"
    test("Cats Counter (facade app, unchanged): Tab / Shift-Tab move, Enter / Space press, 3 independent counters, Ctrl-C quits") {
        run(TuiHeadless.start(CounterApp.app, 100, 30).use { h =>
            for
                s0 <- h.screen
                _  <- check(counts(s0) == List("Count: 0", "Count: 0", "Count: 0"), s0)
                _  <- check(s0.contains("Left") && s0.contains("Right") && s0.contains("Component counter"), s0)
                f1 <- h.key(Keyboard.Enter) // auto focus: the first button, "-" of Left (id a-dec)
                _  <- check(f1.focus == "a-dec", s"focus after start: ${f1.focus}")
                _  <- check(f1.updates == 1 && f1.flushed > 0 && f1.flushed <= 4, s"one region, a few cells: $f1")
                s1 <- h.screen
                _  <- check(counts(s1) == List("Count: -1", "Count: 0", "Count: 0"), s1)
                ft <- h.key(Keyboard.Tab)
                _  <- check(ft.focus == "a-inc" && ft.updates == 0, s"Tab: $ft")
                _  <- h.key(Keyboard.Enter) *> h.key(Keyboard.Enter) *> h.key(Keyboard.Enter)
                fb <- h.key(Keyboard.Tab)
                _  <- check(fb.focus == "b-dec", s"Tab: $fb")
                _  <- h.key(Keyboard.Space)
                _  <- focusOn(h, "c-inc")
                _  <- h.key(Keyboard.Enter) *> h.key(Keyboard.Enter)
                s2 <- h.screen
                _  <- check(counts(s2) == List("Count: 2", "Count: -1", "Count: 2"), s2)
                fr <- h.key(Keyboard.Tab)
                _  <- check(fr.focus == "c-reset", s"Tab: $fr")
                _  <- h.key(Keyboard.Enter)
                s3 <- h.screen
                _  <- check(counts(s3) == List("Count: 2", "Count: -1", "Count: 0"), s3)
                fz <- h.char('z')
                _  <- check((fz.repainted, fz.flushed, fz.updates) == (0, 0, 0), s"an unknown key changes nothing: $fz")
                fs <- h.key(Keyboard.Tab, shift = true)
                _  <- check(fs.focus == "c-inc", s"Shift-Tab: $fs")
                _  <- h.key(Keyboard.Char('c'), ctrl = true)
                end <- h.awaitEnd(10.seconds)
                _  <- check(end, "Ctrl-C ends the run")
            yield ()
        })
    }

    // claude 2026-10-03: created the test "Cats Todo (facade app, unchanged)"
    test("Cats Todo (facade app, unchanged): type into the bound input, Enter and the Add button add, checkbox, delete, Clear done") {
        run(TuiHeadless.start(TodoApp[IO], 100, 30).use { h =>
            for
                s0 <- h.screen
                _  <- check(s0.contains("Todos") && s0.contains("0 left") && !s0.contains("Clear done"), s0)
                _  <- h.typeText("Buy milk") // the input has the auto focus
                s1 <- h.screen
                _  <- check(s1.contains("Buy milk") && s1.contains("0 left"), s1)
                fe <- h.key(Keyboard.Enter) // the input's onKeyDown handler (an F[Unit]) adds
                _  <- check(fe.focus == "new", s"focus: $fe")
                s2 <- h.screen
                _  <- check(s2.contains("[ ]Buy milk") && s2.contains("1 left"), s2)
                _  <- check("Buy milk".r.findAllIn(s2).size == 1, s"the input is cleared (draft.set(\"\") reached the screen):\n$s2")
                _  <- h.typeText("Walk the dog")
                fa <- h.key(Keyboard.Tab)
                _  <- check(fa.focus == "add", s"Tab to the Add button: $fa")
                _  <- h.key(Keyboard.Enter) // the button's onClick reads the draft and adds
                s3 <- h.screen
                _  <- check(s3.contains("[ ]Walk the dog") && s3.contains("2 left") && s3.contains("What needs doing?"), s3)
                fc <- h.key(Keyboard.Tab)
                _  <- check(fc.focus == "done-1", s"Tab to the first checkbox: $fc")
                _  <- h.key(Keyboard.Space) // checkbox onChange(true)
                s4 <- h.screen
                _  <- check(s4.contains("[x]~Buy milk~") && s4.contains("1 left") && s4.contains("Clear done"), s4)
                _  <- focusOn(h, "del-2")
                _  <- h.key(Keyboard.Enter)
                s5 <- h.screen
                _  <- check(!s5.contains("Walk the dog") && s5.contains("0 left") && s5.contains("Buy milk"), s5)
                _  <- focusOn(h, "clear", back = true)
                _  <- h.key(Keyboard.Enter)
                s6 <- h.screen
                _  <- check(!s6.contains("Buy milk") && !s6.contains("Clear done") && s6.contains("0 left"), s6)
                _  <- h.key(Keyboard.Char('c'), ctrl = true)
                end <- h.awaitEnd(10.seconds)
                _  <- check(end, "Ctrl-C ends the run")
            yield ()
        })
    }

    // claude 2026-10-03: created the test "Cats Todo: 15 items typed key by key"
    test("Cats Todo: 15 items typed key by key, each added with Enter, all in order") {
        val items = run(TuiHeadless.start(TodoApp[IO], 100, 30).use { h =>
            (1 to 15).toList.traverse_(i => h.typeText(s"item-$i") *> h.key(Keyboard.Enter)) *>
                h.screen.map(s => """\[[ x]\]~?(item-\d+)""".r.findAllMatchIn(s).map(_.group(1)).toList)
        })
        assertEquals(items, (1 to 15).map(i => s"item-$i").toList)
    }

    // claude 2026-10-03: created: an fs2 Stream (on the Cats runtime, not in a handler) drives a region of the terminal runner
    test("Cats useStream: values of an fs2 Stream reach the terminal screen (background frames, no key)") {
        val app: Resource[IO, UI] =
            useStream(fs2.Stream.emits(1 to 3).covary[IO].metered(20.millis), 0).map(clock =>
                div(h1("Clock"), clock.render(n => p(s"tick: $n")))
            )
        val screen = run(TuiHeadless.start(app).use { h =>
            (h.screen.flatMap(s => if s.contains("tick: 3") then IO.pure(Some(s)) else IO.sleep(5.millis).as(None)))
                .untilDefinedM.timeout(5.seconds)
        })
        assert(screen.contains("tick: 3"), screen)
    }

    // claude 2026-10-03: created the test of the quit glue (runWithQuit / startWithQuit)
    test("glue: the quit action of runWithQuit / startWithQuit ends the terminal run") {
        def app(quit: IO[Unit]): Resource[IO, UI] =
            useSignal[IO](0).map(n =>
                div(n.render(v => p(s"n=$v")), button("+").id("inc").onClick(n.update(_ + 1)))
                    .onKeyDown(e => if e.key == Keyboard.Char('q') then quit else IO.unit)
            )
        run(TuiHeadless.startWithQuit[IO](app).use { h =>
            for
                _   <- h.key(Keyboard.Enter)
                s   <- h.screen
                _   <- check(s.contains("n=1"), s)
                _   <- h.char('q')
                end <- h.awaitEnd(10.seconds)
                _   <- check(end, "quit ends the run")
            yield ()
        })
    }

    // claude 2026-10-03: created the test "handlers run on the caller's IORuntime (its compute pool) an"
    test("handlers run on the caller's IORuntime (its compute pool) and see the IOLocal of the start, also on the terminal runner") {
        val pool = Executors.newFixedThreadPool(2, r => { val t = new Thread(r, "caller-ce-pool"); t.setDaemon(true); t })
        val rt   = IORuntime.builder().setCompute(ExecutionContext.fromExecutor(pool), () => pool.shutdown()).build()
        val program =
            for
                local <- IOLocal("default")
                _     <- local.set("caller-42")
                app = useSignal[IO]("nothing yet").map(seen =>
                    div(
                        seen.render(s => p(s"seen: $s")),
                        button("probe").id("probe").onClick(
                            for
                                w <- local.get
                                _ <- seen.set(s"$w on ${Thread.currentThread.getName}")
                            yield ()
                        )
                    )
                )
                text <- TuiHeadless.start(app).use(h => local.set("changed after start") *> h.key(Keyboard.Enter) *> h.screen)
            yield text
        val text = program.unsafeRunSync()(using rt)
        rt.shutdown()
        assert(text.contains("on caller-ce-pool"), text)
        assert(text.contains("seen: caller-42"), s"IOLocal of the start: $text")
    }
end CatsOnTerminalSuite
