package tui4s.zio

import _root_.zio.FiberRef
import _root_.zio.Runtime
import _root_.zio.Unsafe
import _root_.zio.ZIO
import _root_.zio.durationInt
import jatatui.core.backend.TestBackend
import munit.FunSuite
import tui4s.testkit.*

/** SPEC 6.3 "tui4s-zio" tests: the ZIO Counter of SPEC 3.4 headless on a TestBackend, the caller's runtime (F12),
  * interruption with restore (F11), and the ZIO Dashboard's sources (ZStream, zio.Random, ZIO clock).
  */
class ZioFacadeSuite extends FunSuite:
  override def munitTimeout = scala.concurrent.duration.Duration(30, "s")

  private def run[A](z: ZIO[Any, Any, A]): A =
    Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(z).getOrThrowFiberFailure())

  private def screenText(b: TestBackend): String = tui4s.testkit.Screen.of(b, None).text

  /** Waits (max 5 s) until the screen shows `s`: a fixed sleep is flaky on a cold JVM (the 'q' can join the batch). */
  private def awaitText(b: TestBackend, s: String): ZIO[Any, Nothing, Unit] =
    (ZIO.sleep(20.millis) *> ZIO.succeed(screenText(b).contains(s))).repeatUntil(identity).timeout(5.seconds).unit

  test("the shared Counter behaves the same under tui4s.zio (sync test kit)") {
    assertEquals(TuiTest(examples.ziocounter.CounterApp).press('+', '+').state, 2)
  }

  test("the ZIO Counter runs headless on a TestBackend through Tui.runWith (ZIO effect)") {
    val backend = TestBackend(40, 5)
    val input   = ScriptedInput('+', '+', '+', '-')
    val n = run(
      Tui.runWith(examples.ziocounter.CounterApp, backend, input).fork.flatMap { f =>
        awaitText(backend, "Count: 2") *> ZIO.succeed(input.push(Key.Char('q'))) *> f.join
      }
    )
    assertEquals(n, 2)
    assert(screenText(backend).contains("Count: 2"), screenText(backend))
  }

  test("a task source runs on the caller's runtime (FiberRef visible, F12)") {
    val seen = run(ZIO.scoped(FiberRef.make(0).flatMap { ref =>
      val app = App[Int](
        init = -1,
        view = n => text(n.toString),
        update = (n, _) => n,
        sources = n => if n >= 0 then Nil else Seq(Source.For[Int].task(ref.get)((_, v) => Quit(v)))
      )
      ref.locally(42)(Tui.runWith(app, TestBackend(10, 2), ScriptedInput()))
    }))
    assertEquals(seen, 42)
  }

  test("interrupting the run returns only after the terminal is restored (F11)") {
    val control = tui4s.internal.HeadlessControl(TestBackend(20, 5), ScriptedInput())
    val log = run(
      internal.AmbientZIOs.run(tui4s.internal.Runner.run(examples.ziocounter.CounterApp, control)).fork.flatMap(f =>
        ZIO.sleep(200.millis) *> f.interrupt *> ZIO.succeed(control.log.toArray.toList)
      )
    )
    assertEquals(log.lastOption, Some("leave-raw-mode"), log)
  }

  test("the ZIO Dashboard: ZStream + poll(zio.Random) fold on the caller's runtime; pause stops the poller") {
    val backend = TestBackend(72, 16)
    val input   = ScriptedInput()
    val d = run(
      Tui.runWith(examples.ziodashboard.DashboardApp, backend, input).fork.flatMap { f =>
        ZIO.sleep(1100.millis) *> ZIO.succeed(input.push(Key.Char('p'))) *> ZIO.sleep(500.millis) *>
          ZIO.succeed(input.push(Key.Char('q'))) *> f.join
      }
    )
    assert(d.history.size >= 4 && d.history.size <= 6, d.history)
    assert(d.logs.nonEmpty && d.paused, d)
    assert(screenText(backend).contains("CPU (paused)"), screenText(backend))
  }

  test("a facade source label is the user's call site (CallSite, no Kyo type in the signature)") {
    val s = Source.For[Int].task(ZIO.succeed(1))((n, a) => n + a)
    assert(s.label.startsWith("ZioFacadeSuite.test.scala:"), s.label)
  }

  test("Tui.start + TuiHandle in a ZIO scope") {
    val backend = TestBackend(40, 5)
    val (n, st) = run(ZIO.scoped(
      Tui.startWith(examples.ziocounter.CounterApp, tui4s.internal.HeadlessControl(backend, ScriptedInput())).flatMap {
        h =>
          h.update(_ + 5) *> h.send(Key.Char('+')) *> ZIO.sleep(200.millis) *> h.stats.flatMap(st =>
            h.stop *> h.await.map(n => (n, st))
          )
      }
    ))
    assertEquals(n, 6)
    assert(st.frames >= 2, st)
  }
end ZioFacadeSuite
