package tests

import kyo.*
import kyo.UI.*
import tui4s.kyoui.*

/** Headless tests: drive the apps with keys and check the screen text (100x30 TestBackend). */
class AppsTest extends munit.FunSuite:
    override val munitTimeout = scala.concurrent.duration.Duration(120, "s")

    def run[A](v: => A < (Async & Scope)): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(100.seconds)(v).getOrThrow

    def has(h: Headless, text: String)(using Frame): Boolean < Sync = h.screen.map(_.contains(text))

    test("counter: + + - with keys, Tab and Enter on the buttons, nothing happens for an unknown key") {
        run {
            for
                h  <- Headless.start(100, 30)(apps.Counter.app)
                s0 <- h.screen
                _ = assert(s0.contains("Count: 0"), s0)
                _  <- h.char('+')
                _  <- h.char('+')
                _  <- h.char('-')
                s1 <- h.screen
                _ = assert(s1.contains("Count: 1"), s1)
                f1 <- h.key(Keyboard.Enter) // the first button (+) has the focus
                s2 <- h.screen
                _ = assert(s2.contains("Count: 2"), s2)
                _  <- h.key(Keyboard.Tab) // to (-)
                _  <- h.key(Keyboard.Space)
                _  <- h.key(Keyboard.Space)
                s3 <- h.screen
                _ = assert(s3.contains("Count: 0"), s3)
                z  <- h.char('z')
                _ = assertEquals((z.repainted, z.flushed, z.updates), (0, 0, 0))
                q  <- h.char('q')
                _  <- Async.sleep(50.millis)
                fin <- h.finished
                _ = assert(fin, "q quits")
            yield ()
        }
    }

    test("two counters are independent; Tab order is document order") {
        run {
            for
                h <- Headless.start(100, 30)(apps.Counter.twoCounters)
                _ <- h.key(Keyboard.Enter)                               // Left +
                _ <- h.key(Keyboard.Tab).andThen(h.key(Keyboard.Tab))    // Right +
                _ <- h.key(Keyboard.Enter).andThen(h.key(Keyboard.Enter))
                f <- h.key(Keyboard.Tab, shift = true)                   // back to Left -
                _ <- h.key(Keyboard.Enter)
                lines <- h.lines
                row = lines.find(_.contains("Count:")).getOrElse("")
                _ = assert(row.matches(""".*Count: 0.*Count: 2.*"""), lines.mkString("\n"))
            yield ()
        }
    }

    test("hooks: top-level sequencing and runner-mounted components (state dropped when hidden)") {
        run {
            for
                h  <- Headless.start(100, 30)(apps.CounterHooks.app)
                _  <- h.key(Keyboard.Enter)
                s  <- h.screen
                _ = assert(s.linesIterator.exists(_.matches(""".*Count: 1.*Count: 0.*""")), s)
                d  <- Headless.start(100, 30)(apps.CounterHooks.dynamic)
                _  <- d.key(Keyboard.Enter).andThen(d.key(Keyboard.Enter))
                s1 <- d.screen
                _ = assert(s1.linesIterator.exists(_.matches(""".*Count: 2.*Count: 0.*""")), s1)
                _  <- d.char('h')
                s2 <- d.screen
                _ = assert(!s2.contains("Count:"), s2)
                _  <- d.char('h')
                s3 <- d.screen
                _ = assert(s3.linesIterator.exists(_.matches(""".*Count: 0.*Count: 0.*""")), s3)
            yield ()
        }
    }

    test("Component class: state per position, kept across a parent re-render") {
        run {
            for
                title <- Signal.initRef("A")
                ui = div.onKeyDown {
                    case KeyboardEvent(Keyboard.Char('r'), _, _) => title.updateAndGet(_ + "A")
                    case _                                       => ()
                }(title.render(t => div(h2(s"title $t"), div.style(_.row.gap(2.px))(apps.CounterBox("Left"), apps.CounterBox("Right")))))
                h  <- Headless.start(100, 30)(ui)
                _  <- h.key(Keyboard.Enter)
                _  <- h.key(Keyboard.Tab).andThen(h.key(Keyboard.Tab)).andThen(h.key(Keyboard.Enter)).andThen(h.key(Keyboard.Enter))
                _  <- h.char('r')
                s  <- h.screen
                _ = assert(s.contains("title AA"), s)
                _ = assert(s.linesIterator.exists(_.matches(""".*Count: 1.*Count: 2.*""")), s)
            yield ()
        }
    }

    test("todo: load, type + Enter adds, Tab to the list, arrows + Space toggle, d deletes, Ctrl-S saves, Esc quits") {
        val file = java.nio.file.Files.createTempFile("todo", ".txt")
        java.nio.file.Files.writeString(file, "[ ] buy milk\n[x] walk dog\n[ ] write report\n")
        run {
            for
                h  <- Headless.start(100, 30)(apps.Todo.app(file.toString))
                s0 <- h.screen
                _ = assert(s0.contains("2 open / 3") && s0.contains("[x] walk dog"), s0)
                _  <- h.typeText("call mom")
                s1 <- h.screen
                _ = assert(s1.contains("New: call mom"), s1)
                f  <- h.key(Keyboard.Enter)
                s2 <- h.screen
                _ = assert(s2.contains("[ ] call mom") && s2.contains("3 open / 4"), s2)
                t  <- h.key(Keyboard.Tab)
                _ = assertEquals(t.focus, "list")
                _  <- h.key(Keyboard.ArrowDown)
                sp <- h.key(Keyboard.Space)
                s3 <- h.screen
                _ = assert(s3.contains("[ ] walk dog") && s3.contains("4 open / 4"), s3)
                c  <- h.cell(2, s3.linesIterator.indexWhere(_.contains("walk dog")))
                _ = assert(c.bg.toString.contains("Rgb[r=99, g=102, b=241]"), s"selected row has the indigo bg: ${c.bg}")
                _  <- h.char('d')
                s4 <- h.screen
                _ = assert(!s4.contains("walk dog") && s4.contains("3 open / 3"), s4)
                _  <- h.key(Keyboard.Char('s'), ctrl = true)
                s5 <- h.screen
                _ = assert(s5.contains("saved 3 items"), s5)
                _ = assertEquals(java.nio.file.Files.readString(file), "[ ] buy milk\n[ ] write report\n[ ] call mom\n")
                _  <- h.key(Keyboard.Escape)
                _  <- Async.sleep(50.millis)
                fin <- h.finished
                _ = assert(fin)
            yield ()
        }
    }

    test("dashboard: a sampler tick updates gauge and sparkline in one frame; tabs; pause") {
        run {
            for
                s  <- apps.Dashboard.init
                h  <- Headless.start(100, 30)(apps.Dashboard.view(s))
                _  <- apps.Dashboard.tick(s, Chunk(0.5, 0.25, 1.0))
                f  <- h.nextFrame
                s0 <- h.screen // the frame of the tick has both the gauge and the sparkline
                _ = assert(s0.contains("CPU usage") && s0.contains("███████████████░░░░░░░░░░░░░░░  50%") && s0.contains("▅"), s0)
                _  <- h.key(Keyboard.ArrowRight)
                s1 <- h.screen
                _ = assert(s1.contains("Memory usage") && s1.contains(" 25%"), s1)
                _  <- h.char('p')
                s2 <- h.screen
                _ = assert(s2.contains("sampler PAUSED"), s2)
            yield ()
        }
    }

    test("idle: no frame at all when nothing changes") {
        run {
            for
                h <- Headless.start(100, 30)(apps.Counter.app)
                _ <- h.char('+')
                _ <- Async.sleep(300.millis)
                _ <- h.drainFrames
                _ <- Async.sleep(1500.millis)
                late <- h.drainFrames
                _ = assertEquals(late.size, 0, s"frames while idle: $late")
            yield ()
        }
    }

    test("glitch-free: one event that writes two refs updates all 4 dependent regions in one frame (500 events)") {
        run {
            for
                a <- Signal.initRef(0)
                b <- Signal.initRef(0)
                ui = div.onKeyDown {
                    case KeyboardEvent(Keyboard.Char('+'), _, _) => a.updateAndGet(_ + 1).andThen(b.updateAndGet(_ + 1))
                    case _                                       => ()
                }(span(a.map(n => s"a=$n")), span(b.map(n => s"b=$n")), span(a.combineLatest(b).map((x, y) => s"sum=${x + y}")),
                    span(a.map(n => s"twice=${n * 2}")))
                h  <- Headless.start(40, 6)(ui)
                fs <- Kyo.foreach(1 to 500)(_ => h.char('+'))
                // 4 regions depend on the event; combineLatest may also emit an intermediate (1, 0), so 5 is fine too
                split = fs.count(_.updates < 4)
                _ = assertEquals(split, 0, s"split frames: ${fs.filter(_.updates < 4).take(5)}")
                s <- h.screen
                _ = assert(s.contains("a=500") && s.contains("b=500") && s.contains("sum=1000") && s.contains("twice=1000"), s)
            yield ()
        }
    }
end AppsTest
