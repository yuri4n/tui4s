package integration

import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.IORuntime
import munit.FunSuite
import tui4s.kyoui.FrameStats

// EXTRA stress probe. It is compiled with the suites, but its tests are IGNORED unless the system property probe.rounds is
// set. Run it with:
//   scala-cli test --jvm system . --java-prop probe.rounds=200 --test-only integration.SplitProbeSuite
// Two cases per ecosystem: (a) "first key": start a fresh app, press Enter at once, read the screen; (b) "steady": one app,
// many presses. A press whose own frame does not show the new value is a "split". The probe then reads the model (the
// facade ref) and the screen again after 200 ms ("late") and after 1500 ms ("repaired": kyo's Signal.observe re-reads a
// signal every 1 s to repair a missed wakeup); "lost" = the screen did not catch up in 1500 ms.

// claude 2026-10-03: created the split-frame stress probe for the facade apps on the terminal runner
class SplitProbeSuite extends FunSuite:
    override val munitTimeout = scala.concurrent.duration.Duration(600, "s")
    private val rounds = sys.props.getOrElse("probe.rounds", "200").toInt
    // claude 2026-10-03: changed: ignored in the default test run (scala-cli `using exclude` also drops explicit inputs)
    override def munitIgnore: Boolean = !sys.props.contains("probe.rounds")

    /** One split: the round, the frame of the press, the model value right after, the screen 200 ms later shows the value. */
    // claude 2026-10-03: changed: `late` is now 0 (not seen in 1500 ms), 200 (seen after 200 ms) or 1500 (seen after 1500 ms)
    final case class Split(round: Int, frame: FrameStats, model: Int, late: Int, screen: String)

    // claude 2026-10-03: created report: prints one summary line per case, and the first splits in full
    private def report(name: String, splits: List[Split]): Unit =
        val late     = splits.count(_.late == 200)
        val repaired = splits.count(_.late == 1500)
        println(f"[split-probe] $name%-16s rounds=$rounds%4d splits=${splits.size}%3d late200ms=$late%3d repaired1500ms=$repaired%3d lost=${splits.size - late - repaired}%3d")
        splits.take(4).foreach(s =>
            println(s"[split-probe]   round ${s.round}: model=${s.model} late=${s.late} updates=${s.frame.updates} " +
                s"settleUs=${s.frame.settleNanos / 1000} handleUs=${s.frame.handleNanos / 1000} focus=${s.frame.focus} " +
                s"screen(first line, at once)='${s.screen.linesIterator.nextOption().getOrElse("")}'")
        )

    test("ZIO: first key after start, and steady presses") {
        import tui4s.interop.zio.ui.*
        import zio.*
        def run[A](z: ZIO[Scope, Any, A]): A = Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(ZIO.scoped(z)).getOrThrowFiberFailure())
        def ui(n: SignalRef[Int]) = div(n.render(v => p(s"n=$v")), button("+").id("inc").onClick(n.update(_ + 1)))
        // claude 2026-10-03: created press: one Enter, then the checks of one round
        def press(h: TuiHeadless, n: SignalRef[Int], i: Int, want: Int): UIO[List[Split]] =
            for
                f     <- h.key(Keyboard.Enter)
                s     <- h.screen
                model <- n.get
                later <- if s.contains(s"n=$want") then ZIO.succeed(0)
                         else (ZIO.sleep(200.millis) *> h.screen).flatMap(s2 =>
                             if s2.contains(s"n=$want") then ZIO.succeed(200)
                             else (ZIO.sleep(1300.millis) *> h.screen).map(s3 => if s3.contains(s"n=$want") then 1500 else 0))
            yield if s.contains(s"n=$want") then Nil else List(Split(i, f, model, later, s))
        val first = (1 to rounds).toList.flatMap { i =>
            run(for
                n <- useSignal(0)
                h <- TuiHeadless.start(ZIO.succeed(ui(n)))
                r <- press(h, n, i, 1)
            yield r)
        }
        report("zio first key", first)
        val steady = run(
            for
                n <- useSignal(0)
                h <- TuiHeadless.start(ZIO.succeed(ui(n)))
                r <- ZIO.foreach((1 to rounds).toList)(i => press(h, n, i, i))
            yield r.flatten
        )
        report("zio steady", steady)
    }

    test("Cats: first key after start, and steady presses") {
        import tui4s.interop.cats.ui.*
        import fs2.concurrent.SignallingRef
        import scala.concurrent.duration.DurationInt
        def run[A](io: IO[A]): A = io.unsafeRunSync()(using IORuntime.global)
        def ui(n: SignallingRef[IO, Int]) = div(n.render(v => p(s"n=$v")), button("+").id("inc").onClick(n.update(_ + 1)))
        // claude 2026-10-03: created press: one Enter, then the checks of one round
        def press(h: TuiHeadless[IO], n: SignallingRef[IO, Int], i: Int, want: Int): IO[List[Split]] =
            for
                f     <- h.key(Keyboard.Enter)
                s     <- h.screen
                model <- n.get
                later <- if s.contains(s"n=$want") then IO.pure(0)
                         else (IO.sleep(200.millis) *> h.screen).flatMap(s2 =>
                             if s2.contains(s"n=$want") then IO.pure(200)
                             else (IO.sleep(1300.millis) *> h.screen).map(s3 => if s3.contains(s"n=$want") then 1500 else 0))
            yield if s.contains(s"n=$want") then Nil else List(Split(i, f, model, later, s))
        val first = (1 to rounds).toList.flatMap { i =>
            run(useSignal[IO](0).flatMap(n => TuiHeadless.start(Resource.pure[IO, UI](ui(n))).map((n, _))).use((n, h) => press(h, n, i, 1)))
        }
        report("cats first key", first)
        val steady = run(useSignal[IO](0).flatMap(n => TuiHeadless.start(Resource.pure[IO, UI](ui(n))).map((n, _))).use { (n, h) =>
            (1 to rounds).toList.foldLeft(IO.pure(List.empty[Split]))((acc, i) => acc.flatMap(a => press(h, n, i, i).map(a ++ _)))
        })
        report("cats steady", steady)
    }
    // claude 2026-10-03: created the control case: the same first-key test on the PLAIN Kyo runner (no facade, a Kyo handler)
    test("Kyo (no facade): first key after start") {
        import kyo.*
        import kyo.UI.*
        import tui4s.kyoui.Headless
        def runK[A](v: => A < (Async & Scope)): A =
            import AllowUnsafe.embrace.danger
            KyoApp.Unsafe.runAndBlock(100.seconds)(Scope.run(v)).getOrThrow
        // claude 2026-10-03: changed: two handlers: a plain write, and a write after one scheduler hop (the handler suspends
        // first, like a ZIO or Cats handler that waits for its effect on another thread)
        def firstKey(name: String, handler: SignalRef[Int] => Any < Async) = (1 to rounds).toList.flatMap { i =>
            runK(
                for
                    n <- Signal.initRef(0)
                    h <- Headless.start(100, 30)(div(span(n.map(v => s"n=$v")), button("+").onClick(handler(n))))
                    f <- h.key(Keyboard.Enter)
                    s <- h.screen
                    m <- n.get
                    later <- if s.contains("n=1") then Kyo.lift(0)
                             else Async.sleep(200.millis).andThen(h.screen).map(s2 =>
                                 if s2.contains("n=1") then Kyo.lift(200)
                                 else Async.sleep(1300.millis).andThen(h.screen).map(s3 => if s3.contains("n=1") then 1500 else 0))
                    _ <- h.quit.andThen(h.awaitEnd(5.seconds))
                yield if s.contains("n=1") then Nil else List(Split(i, f, m, later, s))
            )
        }
        report("kyo first key", firstKey("plain", n => n.updateAndGet(_ + 1)))
        report("kyo first key, hop", firstKey("hop", n => Fiber.initUnscoped(Kyo.unit).map(_.get).andThen(n.updateAndGet(_ + 1))))
    }
end SplitProbeSuite
