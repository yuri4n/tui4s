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

    // codex 2026-10-22: created: the baseline has no background load; changed: fanout and continuous notifications are off by default.
    test("the default configuration is quiet") {
      val config = Settlement.Settings.parse(Vector.empty).toOption.get
      assertEquals(config.workload, Settlement.Workload.Quiet)
      assertEquals(config.updatesPerSecond, 0)
      assertEquals(config.seconds, 10)
      assertEquals(config.regions, 0)
      assertEquals(config.cadence, Settlement.Cadence.Paced)
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

    // codex 2026-10-22: created: fanout does not implicitly start a notification producer.
    test(
      "fanout and continuous notifications are explicit independent options",
    ) {
      val idle   = Settlement.Settings.parse(Vector("--regions=512")).toOption.get
      assertEquals(idle.regions, 512)
      assert(!idle.active)
      val active = Settlement.Settings
        .parse(
          Vector("--regions=64", "--busy", "--continuous", "--duration=30"),
        )
        .toOption
        .get
      assertEquals(active.regions, 64)
      assertEquals(active.cadence, Settlement.Cadence.Continuous)
      assertEquals(active.workload, Settlement.Workload.Busy)
      assertEquals(active.seconds, 30)
      assert(active.notifications && active.active)
      Vector(
        Vector("--continuous", "--updates=1000"),
        Vector("--updates=1000", "--continuous"),
        Vector("--continuous", "--updates=0"),
      ).foreach(args => assert(Settlement.Settings.parse(args).isLeft))
    }

    // codex 2026-10-22: created: bad CLI values do not silently select a baseline; changed: reject invalid fanout and cadence flags.
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
        "--regions=-1",
        "--regions=513",
        "--regions=oops",
        "--regions=",
        "--continuous=false",
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

    // codex 2026-10-22: created: each derived status region follows both source signals.
    test(
      "fanout shows the prompt length and status value without covering the input",
    ) {
      val config       = Settlement.Settings.parse(Vector("--regions=4")).toOption.get
      val (ok, screen) = run {
        for
            state  <- Settlement.init(config)
            h      <- Headless.start(120, 20)(_ => Settlement.view(state))
            _      <- state.updates.set(7L)
            _      <- h.typeText("abc")
            ok     <- h.awaitScreen(
                        s =>
                          (1 to 4).forall(i =>
                            s.contains(s"Task $i: chars=3 updates=7"),
                          ),
                        5.seconds,
                      )
            screen <- h.screen
        yield (ok, screen)
      }
      assert(ok, screen.text)
      assert(screen.contains("Input: abc"), screen.text)
      assert(screen.contains("Updates: 7"), screen.text)
    }

    // codex 2026-10-22: created: active producers survive input and both stop with their owning scope; changed: exercise continuous writes with 32 derived regions.
    test(
      "input works with continuous fanout and closing the scope stops both producers",
    ) {
      val config                                         = Settlement.Settings
        .parse(
          Vector("--busy", "--continuous", "--regions=32", "--duration=3600"),
        )
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

    // codex 2026-10-22: created: a loaded quit ends the runner and closing the producer scope stops its writes.
    test(
      "Ctrl-C ends continuous fanout and its owner scope stops both producers",
    ) {
      val config                   = Settlement.Settings
        .parse(
          Vector("--busy", "--continuous", "--regions=32", "--duration=3600"),
        )
        .toOption
        .get
      val (active, stopped, later) = run {
        for
            state   <- Settlement.init(config)
            active  <- Scope.run {
                         for
                             h       <- Headless.start(120, 20)(_ =>
                                          Settlement
                                            .start(state, config)
                                            .andThen(Settlement.view(state)),
                                        )
                             started <- awaitProgress(state, (0L, 0L))
                             _       <- h.key(
                                          Keyboard.Char('c'),
                                          UI.Modifiers(ctrl = true),
                                        )
                             ended   <- h.awaitEnd(5.seconds)
                             load    <- state.load.get
                         yield (started, ended, load)
                       }
            stopped <- progress(state)
            _       <- Async.sleep(50.millis)
            later   <- progress(state)
        yield (active, stopped, later)
      }
      val (started, ended, load)   = active
      assert(started, "both producers must run before the quit")
      assert(ended, "the run must end while the configured load is active")
      assertEquals(load, Settlement.Load.Running)
      assertEquals(
        later,
        stopped,
        "closing the producer scope after quit must stop its writes",
      )
    }
end SettlementSuite
