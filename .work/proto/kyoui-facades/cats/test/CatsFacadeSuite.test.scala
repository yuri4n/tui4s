package tui4s.interop.cats.ui

import apps.cats.CounterApp
import apps.cats.TodoApp
import cats.effect.*
import cats.effect.unsafe.IORuntime
import cats.syntax.all.*
import java.util.concurrent.Executors
import munit.FunSuite
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt // not duration.*: its `span` would shadow the mirror's span here

/** The Cats Effect mirror, run headlessly through kyo-ui's own mount / dispatch / render. */
class CatsFacadeSuite extends FunSuite:
    override val munitTimeout = 120.seconds

    private def run[A](io: IO[A]): A = io.unsafeRunSync()(using IORuntime.global)

    private def eventually(h: Headless[IO])(p: String => Boolean): IO[String] =
        def loop: IO[String] = h.text.flatMap(t => if p(t) then IO.pure(t) else IO.sleep(5.millis) *> loop)
        loop.timeout(5.seconds)

    test("Counter: hooks style and Component style, two independent counters") {
        val (t0, t1, t2) = run(Headless.mount(CounterApp.app).use { h =>
            for
                t0 <- h.text
                _  <- h.click("a-inc") *> h.click("a-inc") *> h.click("b-dec") *> h.click("c-inc")
                t1 <- h.text
                _  <- h.click("c-reset")
                t2 <- h.text
            yield (t0, t1, t2)
        })
        println(s"[cats counter] after a+2, b-1, c+1:\n$t1")
        assertEquals(t0.linesIterator.filter(_.startsWith("Count")).toList, List("Count: 0", "Count: 0", "Count: 0"))
        assertEquals(t1.linesIterator.filter(_.startsWith("Count")).toList, List("Count: 2", "Count: -1", "Count: 1"))
        assertEquals(t2.linesIterator.filter(_.startsWith("Count")).toList, List("Count: 2", "Count: -1", "Count: 0"))
    }

    test("Todo: two-way bound input, Enter and button to add, checkbox, delete, conditional button") {
        val screens = run(Headless.mount(TodoApp[IO]).use { h =>
            for
                s0 <- h.text
                _  <- h.input("new", "Buy milk") *> h.click("add")
                s1 <- h.text
                _  <- h.input("new", "Walk the dog") *> h.keyDown("new", "Enter")
                s2 <- h.text
                _  <- h.check("done-1", true)
                s3 <- h.text
                _  <- h.click("del-2")
                s4 <- h.text
                _  <- h.click("clear")
                s5 <- h.text
            yield List(s0, s1, s2, s3, s4, s5)
        })
        screens.zipWithIndex.foreach((s, i) => println(s"[cats todo] screen $i:\n$s"))
        assert(screens(0).contains("0 left") && !screens(0).contains("Clear done"))
        assert(screens(1).contains("Buy milk") && screens(1).contains("1 left"), screens(1))
        assert(screens(1).contains("[]"), "the input is cleared after add")
        assert(screens(2).contains("Walk the dog") && screens(2).contains("2 left"), screens(2))
        assert(screens(3).contains("[x]") && screens(3).contains("~Buy milk~") && screens(3).contains("1 left"))
        assert(screens(3).contains("(Clear done)"))
        assert(!screens(4).contains("Walk the dog") && screens(4).contains("0 left"), screens(4))
        assert(!screens(5).contains("Buy milk") && !screens(5).contains("Clear done"), screens(5))
    }

    test("handlers run on the caller's IORuntime (its compute pool) and see the IOLocal of the mount") {
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
                text <- Headless.mount(app).use(h => local.set("changed after mount") *> h.click("probe") *> h.text)
            yield text
        val text = program.unsafeRunSync()(using rt)
        rt.shutdown()
        println(s"[cats runtime] $text")
        assert(text.contains("on caller-ce-pool"), text)
        assert(text.contains("seen: caller-42"), s"IOLocal of the mount: $text")
    }

    test("one source of truth: an fs2 Stream, a fiber outside any handler, and `discrete` of a ref that kyo-ui writes") {
        val (screen, observed) = run(
            (for
                draft <- useSignal[IO]("")
                count <- useSignal[IO](0)
                clock <- useStream(fs2.Stream.emits(1 to 3).covary[IO].metered(20.millis), 0)
                ui = div(
                    count.render(n => p(s"count: $n")),
                    clock.render(n => p(s"tick: $n")),
                    input.id("in").value(draft),
                    p(draft),                      // a SignallingRef[F, String] as a reactive text child
                    p(clock.map(n => s"clock=$n")) // a Signal[String] as a reactive text child
                )
                h <- Headless.mount(Resource.pure[IO, UI](ui))
            yield (h, draft, count)).use { (h, draft, count) =>
                for
                    _        <- (1 to 5).toList.traverse_(i => count.set(i) *> IO.sleep(5.millis)).start
                    screen   <- eventually(h)(t => t.contains("count: 5") && t.contains("tick: 3"))
                    seen     <- draft.discrete.find(_ == "typed by the user").compile.lastOrError.start
                    _        <- h.input("in", "typed by the user")
                    _        <- seen.joinWithNever.timeout(5.seconds)
                    observed <- draft.get
                    after    <- eventually(h)(t => "typed by the user".r.findAllIn(t).size >= 2) // the input AND the text child
                yield (screen + "\n" + after, observed)
            }
        )
        assert(screen.contains("count: 5") && screen.contains("tick: 3") && screen.contains("clock=3"), screen)
        assertEquals(observed, "typed by the user")
    }

    test("fast typing: the handler always reads the last typed value") {
        val items = run(Headless.mount(TodoApp[IO]).use { h =>
            (1 to 20).toList.traverse_ { round =>
                val word = s"item-$round"
                (1 to word.length).toList.traverse_(n => h.input("new", word.take(n))) *> h.keyDown("new", "Enter")
            } *> h.text.map(t => """\[[ x]\]~?(item-\d+)""".r.findAllMatchIn(t).map(_.group(1)).toList)
        })
        assertEquals(items, (1 to 20).map(i => s"item-$i").toList)
    }

    test("the lift trap is real WITHOUT the mirror: kyo-ui's own onClick takes an IO and never runs it") {
        val (rawRan, mirrorRan) = run(
            for
                rawFlag    <- Ref.of[IO, Boolean](false)
                mirrorFlag <- Ref.of[IO, Boolean](false)
                raw = kyo.UI.div(kyo.UI.button.id("raw").onClick(rawFlag.set(true)))
                _ <- Headless.mount(Resource.pure[IO, UI](unsafeFromKyo(raw))).use(h => h.click("raw") *> IO.sleep(100.millis))
                ok = div(button("ok").id("ok").onClick(mirrorFlag.set(true)))
                _  <- Headless.mount(Resource.pure[IO, UI](ok)).use(_.click("ok"))
                r1 <- rawFlag.get
                r2 <- mirrorFlag.get
            yield (r1, r2)
        )
        assertEquals(rawRan, false, "kyo-ui lifted the IO VALUE into `Any < Async`")
        assertEquals(mirrorRan, true)
    }

    test("guard: with the mirror imports, the trap does not compile") {
        val notAnEffect = compileErrors("""button("x").onClick(println("hi"))""")
        val notUnit     = compileErrors("""button("x").onClick(IO.pure(1))""")
        val noAsync     = compileErrors("""button("x").onClick(Option(()))""")
        val kyoEffect   = compileErrors("""button("x").onClick(kyo.Sync.defer(println("hi")))""")
        val noKyoType   = compileErrors("""val b: kyo.UI.Ast.Button = button("x")""")
        val ok          = compileErrors("""button("x").onClick(IO.println("hi"))""")
        List(notAnEffect, notUnit, noAsync, kyoEffect, noKyoType).foreach(e => println(s"[cats guard] $e\n"))
        List(notAnEffect, notUnit, noAsync, kyoEffect, noKyoType).foreach(e => assert(e.contains("error"), e))
        assertEquals(ok, "")
    }
    test("the mirror builds the same kyo-ui AST as kyo-ui itself") {
        val mirror = unsafeToKyo(div(h1("Todos"), ul(li(span("a")), li(span("b"))), input.id("new").placeholder("x")))
        val direct: kyo.UI = kyo.UI.div(
            kyo.UI.h1("Todos"),
            kyo.UI.ul(kyo.UI.li(kyo.UI.span("a")), kyo.UI.li(kyo.UI.span("b"))),
            kyo.UI.input.id("new").placeholder("x")
        )
        assertEquals(mirror, direct)
    }

    test("surface: the public API of the mirror names no Kyo type, except the aliases of kyo-ui data types") {
        val leaks   = surface.SurfaceLeaks.leaks(List("tui4s.interop.cats.ui", "tui4s.interop.cats.ui.internal"))
        leaks.foreach(l => println(s"[cats surface] $l"))
        val allowed = Set("KeyboardEvent", "MouseEvent", "WheelEvent", "Keyboard", "Modifiers", "InputFilter", "Style")
        val unexpected = leaks.filterNot(l => allowed.exists(a => l.where.endsWith("." + a)))
        assert(unexpected.isEmpty, unexpected.mkString("\n"))
    }
end CatsFacadeSuite
