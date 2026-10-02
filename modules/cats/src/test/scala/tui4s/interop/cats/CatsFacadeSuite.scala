package tui4s.interop.cats

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.IORuntime
import _root_.cats.syntax.all.*
import java.util.concurrent.Executors
import munit.CatsEffectSuite
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import tui4s.interop.cats.fixtures.Apps
import tui4s.interop.cats.fixtures.CounterApp
import tui4s.testkit.*

/** SPEC 6.3, tui4s-cats: the facade runs the shared model, IO sources run on
  * the caller's runtime, and a cancel returns after the terminal is restored.
  */
class CatsFacadeSuite extends CatsEffectSuite:
    override def munitIOTimeout: Duration = 60.seconds

    /** Waits (at most 10 s) until the test terminal shows `s`. */
    private def shows(control: RecordingControl, s: String): IO[Unit] =
      IO(control.screen.contains(s))
        .flatMap(ok =>
          if ok then IO.unit else IO.sleep(10.millis) *> shows(control, s),
        )
        .timeout(10.seconds)

    test("the shared Counter behaves the same under tui4s.interop.cats") {
      val t = TuiTest(CounterApp, 40, 6)
      t.press('+', '+')
      assertEquals(t.state, 2)
      assert(t.screen.contains("Count: 2"), t.screen.text)
      assert(t.screen.contains("+ increment · - decrement · q quit"))
      assert(TuiTest(CounterApp).press('q').quit)
    }

    test("the Counter runs on the real loop through Tui.runWith[IO]") {
      val control = RecordingControl(40, 5)
      val input   = ScriptedInput('+', '+', '+', '-')
      for
          fiber <- Tui.runWith[IO, Int](CounterApp, control, input).start
          _     <- shows(control, "Count: 2")
          _     <- IO(input.push(Key.Char('q')))
          n     <- fiber.joinWithNever
      yield
          assertEquals(n, 2)
          assertEquals(
            control.events,
            Seq(
              "enter",
              "input-started",
              "input-stopped",
              "input-joined",
              "leave-raw-mode",
            ),
          )
      end for
    }

    test("an IO task source runs on the caller's IORuntime") {
      Tui
        .runWith[IO, String](
          Apps.threadNameOnce,
          RecordingControl(),
          ScriptedInput.empty,
        )
        .map(name => assert(name.startsWith("io-compute"), name))
    }

    test(
      "an IO task source runs on the runtime that runs Tui.run, not global",
    ) {
      IO.blocking {
        val pool    = Executors.newFixedThreadPool(
          2,
          r =>
              val t = new Thread(r, s"caller-ce-${r.hashCode}")
              t.setDaemon(true)
              t,
        )
        val ec      = ExecutionContext.fromExecutor(pool)
        val runtime =
          IORuntime.builder().setCompute(ec, () => pool.shutdown()).build()
        try
          Tui
            .runWith[IO, String](
              Apps.threadNameOnce,
              RecordingControl(),
              ScriptedInput.empty,
            )
            .timeout(20.seconds)
            .unsafeRunSync()(using runtime)
        finally runtime.shutdown()
        end try
      }.map(name => assert(name.startsWith("caller-ce-"), name))
    }

    test(
      "cancel returns only after the terminal is restored (cancel-and-await, F8)",
    ) {
      val log = RecordingControl()
      Tui
        .runWith[IO, Int](CounterApp, log, ScriptedInput.never)
        .start
        .flatMap(f => shows(log, "Count: 0") *> f.cancel *> f.join)
        .map { outcome =>
          assert(outcome.isCanceled, outcome.toString)
          assertEquals(log.events.lastOption, Some("leave-raw-mode"))
        }
    }

    test("a cancel at any time leaves no terminal entered (30 rounds)") {
      val rounds = (0 until 30).toList.traverse { i =>
        val log = RecordingControl()
        Tui
          .runWith[IO, Int](CounterApp, log, ScriptedInput.never)
          .start
          .flatMap(f => IO.sleep((i % 10).millis) *> f.cancel)
          .map(_ => log.events)
      }
      rounds.map { all =>
        val bad = all.filter(e =>
          e.nonEmpty && !e.lastOption.contains("leave-raw-mode"),
        )
        assert(bad.isEmpty, bad.mkString("\n"))
      }
    }

    test("user code that throws fails the run with TuiError.AppFailed") {
      val app = App[Int](
        init = 0,
        view = n => text(n.toString),
        update = (n, e) => if e == Key.Char('x') then sys.error("boom") else n,
      )
      Tui
        .runWith[IO, Int](app, RecordingControl(), ScriptedInput('x'))
        .attempt
        .map {
          case Left(e: TuiError.AppFailed) =>
            assertEquals(e.error.getMessage, "boom")
          case other                       => fail(s"not AppFailed: $other")
        }
    }

    test("a facade source label is the user's call site") {
      val s = Source.For[IO, Int].task(IO.pure(1))((n, a) => n + a)
      assert(s.label.startsWith("CatsFacadeSuite.scala:"), s.label)
      val e = Source.every[Int](1.second)(_ + 1)
      assert(e.label.startsWith("CatsFacadeSuite.scala:"), e.label)
      assertNotEquals(s.label, e.label)
    }

    test("the TuiApp runtime config turns the CPU starvation checker off") {
      object Probe extends TuiApp[Int]:
          def init: Int                               = 0
          def update(n: Int, event: Event): Next[Int] = n
          def view(n: Int): View                      = text(n.toString)
          def config                                  = runtimeConfig
      assertEquals(Probe.config.cpuStarvationCheckInitialDelay, Duration.Inf)
    }

    test("a TuiApp sets the unset scheduler settings in its constructor") {
      val key = "kyo.scheduler.cycleIntervalNs"
      IO {
        val before = java.lang.System.clearProperty(key)
        try
            object Probe extends TuiApp[Int]:
                def init: Int                               = 0
                def update(n: Int, event: Event): Next[Int] = n
                def view(n: Int): View                      = text(n.toString)
            assertEquals(Probe.init, 0)
            val env = java.lang.System.getenv("KYO_SCHEDULER_CYCLEINTERVALNS")
            if env == null then
                assertEquals(java.lang.System.getProperty(key), "10000000")
        finally
            if before != null then
                val _ = java.lang.System.setProperty(key, before)
        end try
      }
    }
end CatsFacadeSuite
