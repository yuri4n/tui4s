package examples.settlement

import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.testkit.Headless

/** The screen oracle and the CLI boundary of the settlement probe. */
// codex 2026-10-22: created: checks for the independently driven PTY workload.
class SettlementSuite extends FunSuite:

    // codex 2026-10-22: created: the example tests own the runtime boundary.
    private def run[A](value: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(value).getOrThrow

    // codex 2026-10-22: created: read producer state independently of rendered markers.
    private def progress(
        state: Settlement.State,
    )(using Frame): (Long, Long) < Sync =
      for
          work    <- state.work.get
          updates <- state.updates.get
      yield (work, updates)

    // codex 2026-10-22: created: await actual progress with a test timeout, not a latency target.
    private def awaitProgress(state: Settlement.State, before: (Long, Long))(
        using Frame,
    ): Boolean < Async =
      Abort.recover[Timeout](_ => false)(
        Async.timeout(5.seconds)(
          Loop.foreach {
            progress(state).map { after =>
              if after._1 != before._1 && after._2 > before._2 then
                  Loop.done(true)
              else Async.sleep(1.millis).andThen(Loop.continue)
            }
          },
        ),
      )

    // codex 2026-10-22: created: the baseline has no background load.
    test("the default configuration is quiet") {
      val config = Settlement.Settings.parse(Vector.empty).toOption.get
      assertEquals(config.workload, Settlement.Workload.Quiet)
      assertEquals(config.updatesPerSecond, 0)
      assertEquals(config.seconds, 10)
      assert(!config.active)
    }

    // codex 2026-10-22: created: the two load dimensions are independent.
    test("busy work, update rate and duration parse independently") {
      val config        = Settlement.Settings
        .parse(Vector("--busy", "--updates=1000", "--duration=30"))
        .toOption
        .get
      assertEquals(config.workload, Settlement.Workload.Busy)
      assertEquals(config.updatesPerSecond, 1000)
      assertEquals(config.seconds, 30)
      assert(config.active)
      val notifications =
        Settlement.Settings.parse(Vector("--updates=10")).toOption.get
      assertEquals(notifications.workload, Settlement.Workload.Quiet)
      assert(notifications.active)
    }

    // codex 2026-10-22: created: bad CLI values do not silently select a baseline.
    test("unknown options and invalid bounds fail") {
      val invalid = Vector(
        "--updates=-1",
        "--updates=100001",
        "--updates=oops",
        "--updates=",
        "--duration=0",
        "--duration=3601",
        "--duration=2147483648",
        "--busy=false",
        "--unknown",
      )
      invalid.foreach(arg =>
        assert(Settlement.Settings.parse(Vector(arg)).isLeft, arg),
      )
    }

    // codex 2026-10-22: created: observe the native input and verify editing order.
    test("the screen shows the actual input and preserves editing semantics") {
      val (screen, value) = run {
        for
            state <- Settlement.init(Settlement.Settings.default)
            h     <- Headless.start(120, 20)(_ => Settlement.view(state))
            _     <- h.typeText("abc")
            _     <- h.key(Keyboard.ArrowLeft)
            _     <- h.key(Keyboard.Backspace)
            _     <- h.char('x')
            value <- state.text.get
            s     <- h.screen
        yield (s, value)
      }
      assertEquals(value, "axc")
      assert(screen.contains("Input: axc"), screen.text)
      assert(screen.contains("Updates: 0"), screen.text)
      assert(screen.contains("Load: done"), screen.text)
    }

    // codex 2026-10-22: created: active producers survive input and both stop with their owning scope.
    test(
      "input works with live producers and closing the scope stops their writes",
    ) {
      val config                                         = Settlement.Settings
        .parse(Vector("--busy", "--updates=100", "--duration=3600"))
        .toOption
        .get
      val (active, stopped, later)                       = run {
        for
            state   <- Settlement.init(config)
            active  <- Scope.run {
                         for
                             h          <- Headless.start(120, 20)(_ =>
                                             Settlement
                                               .start(state, config)
                                               .andThen(Settlement.view(state)),
                                           )
                             started    <- awaitProgress(state, (0L, 0L))
                             before     <- progress(state)
                             _          <- h.char('x')
                             live       <- awaitProgress(state, before)
                             afterInput <- progress(state)
                             continues  <- awaitProgress(state, afterInput)
                             load       <- state.load.get
                             text       <- state.text.get
                             screen     <- h.screen
                         yield (started, live, continues, load, text, screen)
                       }
            stopped <- progress(state)
            _       <- Async.sleep(50.millis)
            later   <- progress(state)
        yield (active, stopped, later)
      }
      val (started, live, continues, load, text, screen) = active
      assert(started, "both producers must make progress before the input")
      assert(
        live && continues,
        "both producers must keep making progress after the input",
      )
      assertEquals(load, Settlement.Load.Running)
      assertEquals(text, "x")
      assert(screen.contains("Input: x"), screen.text)
      assertEquals(
        later,
        stopped,
        "producer state must not change after the scope closes",
      )
    }

    // codex 2026-10-22: created: the relative duration stops both producers while their app scope stays open.
    test("the finite duration stops producers without closing their scope") {
      val config                 = Settlement.Settings
        .parse(Vector("--busy", "--updates=100", "--duration=1"))
        .toOption
        .get
      val (done, stopped, later) = run {
        for
            state   <- Settlement.init(config)
            _       <- Settlement.start(state, config)
            done    <- Abort.recover[Timeout](_ => false)(
                         Async.timeout(5.seconds)(
                           Loop.foreach {
                             state.load.get.map { load =>
                               if load == Settlement.Load.Done then Loop.done(true)
                               else Async.sleep(1.millis).andThen(Loop.continue)
                             }
                           },
                         ),
                       )
            stopped <- progress(state)
            _       <- Async.sleep(50.millis)
            later   <- progress(state)
        yield (done, stopped, later)
      }
      assert(done, "both producers must finish within the test timeout")
      assert(
        stopped._1 != 0L && stopped._2 > 0L,
        "both producers must have run",
      )
      assertEquals(
        later,
        stopped,
        "the finite load must stop writing before the scope closes",
      )
    }

    // codex 2026-10-22: created: the quiet app exits through the runner's native key.
    test("Ctrl-C ends the quiet app") {
      val ended = run {
        for
            h   <- Headless.start(120, 20)(_ =>
                     Settlement.app(Settlement.Settings.default),
                   )
            _   <- h.key(Keyboard.Char('c'), UI.Modifiers(ctrl = true))
            end <- h.awaitEnd(5.seconds)
        yield end
      }
      assert(ended)
    }
end SettlementSuite
