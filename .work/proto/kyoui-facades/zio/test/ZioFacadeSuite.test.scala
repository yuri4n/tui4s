package tui4s.interop.zio.ui

import apps.zio.CounterApp
import apps.zio.TodoApp
import java.util.concurrent.Executors
import munit.FunSuite
import scala.concurrent.duration.Duration as SDuration
import zio.*
import zio.stream.ZStream

/** The ZIO mirror, run headlessly through kyo-ui's own mount / dispatch / render. */
class ZioFacadeSuite extends FunSuite:
    override val munitTimeout = SDuration(120, "s")

    private def run[A](z: ZIO[Scope, Any, A]): A =
        Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(ZIO.scoped(z)).getOrThrowFiberFailure())

    /** The screen, once `p` holds (signals that a fiber outside a handler changes reach the screen a bit later). */
    private def eventually(h: Headless)(p: String => Boolean): ZIO[Any, Throwable, String] =
        def loop: ZIO[Any, Nothing, String] = h.text.flatMap(t => if p(t) then ZIO.succeed(t) else ZIO.sleep(5.millis) *> loop)
        loop.timeoutFail(new AssertionError("screen did not reach the expected state"))(5.seconds)

    test("Counter: hooks style and Component style, two independent counters") {
        val (t0, t1, t2) = run(
            for
                h  <- Headless.mount(CounterApp.app)
                t0 <- h.text
                _  <- h.click("a-inc") *> h.click("a-inc") *> h.click("b-dec") *> h.click("c-inc")
                t1 <- h.text
                _  <- h.click("c-reset")
                t2 <- h.text
            yield (t0, t1, t2)
        )
        println(s"[zio counter] before:\n$t0\n[zio counter] after a+2, b-1, c+1:\n$t1")
        assertEquals(t0.linesIterator.filter(_.startsWith("Count")).toList, List("Count: 0", "Count: 0", "Count: 0"))
        assertEquals(t1.linesIterator.filter(_.startsWith("Count")).toList, List("Count: 2", "Count: -1", "Count: 1"))
        assertEquals(t2.linesIterator.filter(_.startsWith("Count")).toList, List("Count: 2", "Count: -1", "Count: 0"))
    }

    test("Todo: two-way bound input, Enter and button to add, checkbox, delete, conditional button") {
        val screens = run(
            for
                h  <- Headless.mount(TodoApp.app)
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
        )
        screens.zipWithIndex.foreach((s, i) => println(s"[zio todo] screen $i:\n$s"))
        assert(screens(0).contains("0 left") && !screens(0).contains("Clear done"))
        assert(screens(1).contains("Buy milk") && screens(1).contains("1 left"), screens(1))
        assert(screens(1).contains("[]"), "the input is cleared after add (draft.set(\"\") reached kyo-ui)")
        assert(screens(2).contains("Walk the dog") && screens(2).contains("2 left"), screens(2))
        assert(screens(3).contains("[x]") && screens(3).contains("~Buy milk~") && screens(3).contains("1 left"))
        assert(screens(3).contains("(Clear done)"), "when(...) shows the button once a todo is done")
        assert(!screens(4).contains("Walk the dog") && screens(4).contains("0 left"), screens(4))
        assert(!screens(5).contains("Buy milk") && !screens(5).contains("Clear done"), screens(5))
    }

    test("handlers run on the runtime of the fiber that mounted the UI (FiberRef, executor), not on Runtime.default") {
        val pool = Executors.newFixedThreadPool(2, r => { val t = new Thread(r, "caller-zio-pool"); t.setDaemon(true); t })
        val exec = Executor.fromJavaExecutor(pool)
        val who  = Unsafe.unsafe(implicit u => FiberRef.unsafe.make("default"))
        val app =
            for seen <- useSignal("nothing yet")
            yield div(
                seen.render(s => p(s"seen: $s")),
                button("probe").id("probe").onClick(
                    for
                        w <- who.get
                        _ <- seen.set(s"$w on ${Thread.currentThread.getName}")
                    yield ()
                )
            )
        val text = run(
            for
                // Mounted with FiberRef = caller-42 and on the caller's executor ...
                h <- who.locally("caller-42")(Headless.mount(app).onExecutor(exec))
                // ... clicked from a fiber with FiberRef = default, on the default executor.
                _ <- h.click("probe")
                t <- h.text
            yield t
        )
        pool.shutdown()
        println(s"[zio runtime] $text")
        assert(text.contains("seen: caller-42 on caller-zio-pool"), text)
    }

    // claude 2026-10-03: renamed (was "pumps: ..."): a ref has no pump fiber now, only useStream does
    test("one source of truth: a fiber outside any handler, a ZStream, and `changes` of a ref that kyo-ui writes") {
        val (ticks, observed) = run(
            for
                draft <- useSignal("")
                count <- useSignal(0)
                clock <- useStream(ZStream.fromIterable(1 to 3).schedule(Schedule.spaced(20.millis)), 0)
                ui = div(
                    count.render(n => p(s"count: $n")),
                    clock.render(n => p(s"tick: $n")),
                    input.id("in").value(draft),
                    // claude 2026-10-03: changed the comment below (the ref type is now SignalRef)
                    p(draft),                          // a SignalRef[String] as a reactive text child
                    p(clock.map(n => s"clock=$n"))     // a Signal[String] as a reactive text child
                )
                h        <- Headless.mount(ZIO.succeed(ui))
                _        <- ZIO.foreachDiscard(1 to 5)(i => count.set(i) *> ZIO.sleep(5.millis)).fork
                ticks    <- eventually(h)(t => t.contains("count: 5") && t.contains("tick: 3"))
                seen     <- draft.changes.takeUntil(_ == "typed by the user").runDrain.fork
                _        <- h.input("in", "typed by the user")
                _        <- seen.join.timeoutFail(new AssertionError("ZIO observer did not see the input"))(5.seconds)
                observed <- draft.get
                screen   <- eventually(h)(t => "typed by the user".r.findAllIn(t).size >= 2) // the input AND the text child
            yield (ticks + "\n" + screen, observed)
        )
        assert(ticks.contains("count: 5") && ticks.contains("tick: 3") && ticks.contains("clock=3"), ticks)
        assertEquals(observed, "typed by the user")
    }

    test("two-way binding under fast typing: the handler always reads the last typed value") {
        val items = run(
            for
                h <- Headless.mount(TodoApp.app)
                _ <- ZIO.foreachDiscard(1 to 20) { round =>
                    val word = s"item-$round"
                    ZIO.foreachDiscard(1 to word.length)(n => h.input("new", word.take(n))) *> h.keyDown("new", "Enter")
                }
                t <- h.text
            yield """\[[ x]\]~?(item-\d+)""".r.findAllMatchIn(t).map(_.group(1)).toList
        )
        assertEquals(items, (1 to 20).map(i => s"item-$i").toList)
    }

    test("the lift trap is real WITHOUT the mirror: kyo-ui's own onClick takes a ZIO and never runs it") {
        val (rawRan, mirrorRan) = run(
            for
                rawFlag    <- Ref.make(false)
                mirrorFlag <- Ref.make(false)
                raw = kyo.UI.div(kyo.UI.button.id("raw").onClick(rawFlag.set(true)))
                h  <- Headless.mount(ZIO.succeed(unsafeFromKyo(raw)))
                _  <- h.click("raw") *> ZIO.sleep(100.millis)
                h2 <- Headless.mount(ZIO.succeed(div(button("ok").id("ok").onClick(mirrorFlag.set(true)))))
                _  <- h2.click("ok")
                r1 <- rawFlag.get
                r2 <- mirrorFlag.get
            yield (r1, r2)
        )
        assertEquals(rawRan, false, "kyo-ui lifted the ZIO VALUE into `Any < Async`: the click 'ran' it as a value")
        assertEquals(mirrorRan, true)
    }

    test("guard: with the mirror imports, the trap does not compile") {
        val notAnEffect = compileErrors("""button("x").onClick(println("hi"))""")
        val mayFail     = compileErrors("""button("x").onClick(ZIO.attempt(println("hi")))""")
        val kyoEffect   = compileErrors("""button("x").onClick(kyo.Sync.defer(println("hi")))""")
        val noKyoType   = compileErrors("""val b: kyo.UI.Ast.Button = button("x")""")
        val noRawSetter = compileErrors("""button("x").onClick(f = (_: Any) => ())""")
        val ok          = compileErrors("""button("x").onClick(ZIO.succeed(println("hi")))""")
        List(notAnEffect, mayFail, kyoEffect, noKyoType, noRawSetter).foreach(e => println(s"[zio guard] $e\n"))
        assert(notAnEffect.contains("error"), notAnEffect)
        assert(mayFail.contains("error"), mayFail)
        assert(kyoEffect.contains("error"), kyoEffect)
        assert(noKyoType.contains("error"), noKyoType)
        assert(noRawSetter.contains("error"), noRawSetter)
        assertEquals(ok, "")
    }
    // claude 2026-10-03: changed: the AST check also covers a pure function setter and a Length forwarder
    test("the mirror builds the same kyo-ui AST as kyo-ui itself") {
        val mirror = unsafeToKyo(
            div(h1("Todos").style(_.bold.padding(2.pct)), ul(li(span("a")), li(span("b"))), input.id("new").placeholder("x"))
        )
        val direct: kyo.UI = kyo.UI.div(
            kyo.UI.h1("Todos").style(_.bold.padding(kyo.pct(2))),
            kyo.UI.ul(kyo.UI.li(kyo.UI.span("a")), kyo.UI.li(kyo.UI.span("b"))),
            kyo.UI.input.id("new").placeholder("x")
        )
        assertEquals(mirror, direct)
    }

    // claude 2026-10-03: changed the surface test: it allows a Kyo name only when it is an aliased kyo-ui data type
    // (it allowed whole members by name); and it prints the Kyo names of each entry
    test("surface: the public API of the mirror names no Kyo type, except the aliases of kyo-ui data types") {
        val leaks = surface.SurfaceLeaks.leaks(List("tui4s.interop.zio.ui", "tui4s.interop.zio.ui.internal"))
        leaks.foreach(l => println(s"[zio surface] ${l.where} names ${l.names.mkString(", ")}"))
        val aliases = Set("KeyboardEvent", "MouseEvent", "WheelEvent", "Keyboard", "Modifiers", "InputFilter")
            .map("kyo.UI." + _) ++ Set("kyo.Style", "kyo.Length")
        val unexpected = leaks.filterNot(_.onlyNames(aliases))
        assert(unexpected.isEmpty, unexpected.mkString("\n"))
    }
end ZioFacadeSuite
