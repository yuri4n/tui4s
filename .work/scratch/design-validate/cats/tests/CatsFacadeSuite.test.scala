package tui4s.cats

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import jatatui.core.backend.TestBackend
import munit.FunSuite
import scala.concurrent.duration.*
import tui4s.testkit.*

/** SPEC 6.3 "tui4s-cats" tests: the Cats Counter of SPEC 3.5 headless on a TestBackend, the caller's IORuntime,
  * cancel-and-await with restore (F8), and the Cats Dashboard's sources (fs2 + IO poll).
  */
class CatsFacadeSuite extends FunSuite:
  override def munitTimeout = 30.seconds

  private def screenText(b: TestBackend): String = tui4s.testkit.Screen.of(b, None).text

  /** Waits (max 5 s) until the screen shows `s`: a fixed sleep is flaky on a cold JVM (the 'q' can join the batch). */
  private def awaitText(b: TestBackend, s: String): IO[Unit] =
    def loop(n: Int): IO[Unit] =
      IO(screenText(b).contains(s)).flatMap(ok => if ok || n == 0 then IO.unit else IO.sleep(20.millis) *> loop(n - 1))
    loop(250)

  test("the shared Counter behaves the same under tui4s.cats (sync test kit)") {
    assertEquals(TuiTest(examples.catscounter.CounterApp).press('+', '+').state, 2)
  }

  test("the Cats Counter runs headless on a TestBackend through Tui.runWith[IO]") {
    val backend = TestBackend(40, 5)
    val input   = ScriptedInput('+', '+', '+', '-')
    val n = Tui
      .runWith[IO, Int](examples.catscounter.CounterApp, backend, input)
      .start
      .flatMap(f => awaitText(backend, "Count: 2") *> IO(input.push(Key.Char('q'))) *> f.joinWithNever)
      .unsafeRunSync()
    assertEquals(n, 2)
    assert(screenText(backend).contains("Count: 2"), screenText(backend))
  }

  test("an IO task source runs on the caller's IORuntime") {
    val app = App[String](
      init = "",
      view = s => text(s),
      update = (s, _) => s,
      sources = s => if s.nonEmpty then Nil else Seq(Source.For[IO, String].task(IO(Thread.currentThread().getName))((_, n) => Quit(n)))
    )
    val name = Tui.runWith[IO, String](app, TestBackend(10, 2), ScriptedInput()).unsafeRunSync()
    assert(name.startsWith("io-compute"), name)
  }

  test("cancel returns only after the terminal is restored (cancel-and-await, F8)") {
    val control = tui4s.internal.HeadlessControl(TestBackend(20, 5), ScriptedInput())
    val log = Tui
      .runControl[IO, Int](examples.catscounter.CounterApp, control)
      .start
      .flatMap(f => IO.sleep(200.millis) *> f.cancel *> IO(control.log.toArray.toList))
      .unsafeRunSync()
    assertEquals(log.lastOption, Some("leave-raw-mode"), log)
  }

  test("the Cats Dashboard: fs2 metered stream + IO poll fold; pause stops the poller") {
    val backend = TestBackend(72, 16)
    val input   = ScriptedInput()
    val d = Tui
      .runWith[IO, examples.catsdashboard.Dash](examples.catsdashboard.DashboardApp, backend, input)
      .start
      .flatMap(f =>
        IO.sleep(1100.millis) *> IO(input.push(Key.Char('p'))) *> IO.sleep(500.millis) *>
          IO(input.push(Key.Char('q'))) *> f.joinWithNever
      )
      .unsafeRunSync()
    assert(d.history.size >= 4 && d.history.size <= 6, d.history)
    assert(d.logs.nonEmpty && d.paused, d)
    assert(screenText(backend).contains("CPU (paused)"), screenText(backend))
  }

  test("a facade source label is the user's call site") {
    val s = Source.For[IO, Int].task(IO.pure(1))((n, a) => n + a)
    assert(s.label.startsWith("CatsFacadeSuite.test.scala:"), s.label)
  }

  test("Tui.resource + TuiHandle") {
    val backend = TestBackend(40, 5)
    val (n, st) = Tui
      .resourceWith[IO, Int](examples.catscounter.CounterApp, tui4s.internal.HeadlessControl(backend, ScriptedInput()))
      .use(h =>
        h.update(_ + 5) *> h.send(Key.Char('+')) *> IO.sleep(200.millis) *> h.stats.flatMap(st =>
          h.stop *> h.await.map(n => (n, st))
        )
      )
      .unsafeRunSync()
    assertEquals(n, 6)
    assert(st.frames >= 2, st)
  }

  test("the TuiApp runtime config turns the CPU starvation checker off") {
    object Probe extends TuiApp[Int]:
      def init: Int                               = 0
      def update(n: Int, event: Event): Next[Int] = n
      def view(n: Int): View                      = text(n.toString)
      def cfg                                     = runtimeConfig
    assertEquals(Probe.cfg.cpuStarvationCheckInitialDelay, Duration.Inf)
  }
end CatsFacadeSuite
