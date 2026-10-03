package tui4s

import examples.counter.CounterApp
import examples.dashboard.DashboardApp
import jatatui.core.backend.TestBackend
import kyo.*
import munit.FunSuite
import tui4s.testkit.*

/** The real loop (fibers, input thread, supervisor) on a TestBackend: SPEC 5.4-5.9, Appendix A items 11-13. */
class RuntimeSuite extends FunSuite:
  override def munitTimeout = scala.concurrent.duration.Duration(30, "s")

  private def runK[A](v: A < (Async & Scope & Abort[Any]))(using Frame): A =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(20.seconds)(v).getOrThrow

  private def rows(b: TestBackend): String = Screen.of(b, None).text

  /** Waits (max 5 s) until the screen shows `s`: a fixed sleep is flaky on a cold JVM (the 'q' can join the batch). */
  private def awaitText(b: TestBackend, s: String)(using Frame): Unit < Async =
    Loop(0)(n => if n > 250 || rows(b).contains(s) then Loop.done(()) else Async.sleep(20.millis).andThen(Loop.continue(n + 1)))

  test("Counter on the real loop: keys -> frames -> Quit returns the last state") {
    val backend = TestBackend(40, 5)
    val input   = ScriptedInput('+', '+', '+')
    val n = runK(
      Fiber.initUnscoped(spi.Headless.run(CounterApp, backend, input)).map { fiber =>
        awaitText(backend, "Count: 3").andThen(Sync.defer(input.push(Key.Char('q')))).andThen(fiber.get)
      }
    )
    assertEquals(n, 3)
    assert(rows(backend).contains("Count: 3"), rows(backend))
  }

  test("Dashboard on the real loop: poll + stream sources fold, pause stops the poller") {
    val backend = TestBackend(72, 16)
    val input   = ScriptedInput()
    val d = runK(
      Fiber.initUnscoped(spi.Headless.run(DashboardApp, backend, input)).map { fiber =>
        Async
          .sleep(1100.millis)
          .andThen(Sync.defer(input.push(Key.Char('p'))))
          .andThen(Async.sleep(600.millis))
          .andThen(Sync.defer(input.push(Key.Char('q'))))
          .andThen(fiber.get)
      }
    )
    // poll(250 ms) runs at 0, 250, 500, 750, 1000 ms; then paused. Real time, so allow one either way.
    assert(d.history.size >= 4 && d.history.size <= 6, d.history)
    assert(d.paused, d)
    assert(d.logs.nonEmpty, d.logs) // the stream emits every 700 ms
    assert(rows(backend).contains("CPU (paused)"), rows(backend))
  }

  test("interrupt: the terminal is restored before interruptAwait returns (F8 shape of 5.9)") {
    val control = tui4s.internal.HeadlessControl(TestBackend(20, 5), ScriptedInput())
    val seen = runK(
      Fiber.initUnscoped(tui4s.internal.Runner.run(CounterApp, control)).map { fiber =>
        Async.sleep(200.millis).andThen(fiber.interruptAwait).andThen(Sync.defer(control.log.toArray.toList))
      }
    )
    assertEquals(
      seen,
      List("enter", "input-started", "input-stopped", "input-joined", "leave-raw-mode")
    )
  }

  test("TuiHandle: update, send, stats, stop (Headless.start)") {
    val backend = TestBackend(40, 5)
    val (state, stats) = runK(
      Scope.run(
        spi.Headless.start(CounterApp, backend, ScriptedInput()).map { h =>
          h.update(_ + 5)
            .andThen(h.send(Key.Char('+')))
            .andThen(Async.sleep(200.millis))
            .andThen(h.stats)
            .map(st => h.stop.andThen(h.await).map(s => (s, st)))
        }
      )
    )
    assertEquals(state, 6)
    assert(stats.frames >= 2, stats)
    assert(rows(backend).contains("Count: 6"), rows(backend))
  }

  test("a source that fails ends the app with SourceFailed after the restore") {
    val app = App[Int](
      init = 0,
      view = n => text(n.toString),
      update = (n, _) => n,
      sources = _ => Seq(Source.For[Int].task(Abort.fail(new RuntimeException("boom")): Int < Abort[Throwable])((s, a) => s + a).id("bad"))
    )
    val r = runK(Abort.run[TuiError](spi.Headless.run(app, TestBackend(10, 2), ScriptedInput())))
    r match
      case Result.Failure(TuiError.SourceFailed("bad", e)) => assertEquals(e.getMessage, "boom")
      case other                                           => fail(s"unexpected: $other")
  }

  test("a stopped source's late value is dropped; keyed tasks: latest wins (sync kit)") {
    final case class S(n: Int, gen: Int)
    val app = App[S](
      init = S(0, 0),
      view = s => text(s.toString),
      update = (s, e) =>
        e match
          case Key.Char('t') => s.copy(gen = s.gen + 1).withTask(Source.For[S].task(Kyo.lift(1))((s, a) => s.copy(n = s.n + a)).id("t"))
          case _             => s
    )
    val t = TuiTest(app, 20, 2).press('t', 't')
    assertEquals(t.started, Seq("t", "t"))
    t.emit("t", 10)
    assertEquals(t.state.n, 10)
    assertEquals(t.stats.droppedFolds, 0L)
  }
end RuntimeSuite
