package tui4s.interop.cats

import _root_.cats.effect.IO
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import tui4s.interop.cats.fixtures.CounterApp
import tui4s.testkit.*

/** `Tui.resource` and its `TuiHandle` (SPEC 4.3): the release is CE's own
  * bracket and returns after the terminal is restored, also on cancel.
  */
class ResourceSuite extends CatsEffectSuite:
    override def munitIOTimeout: Duration = 60.seconds

    private def counter(control: RecordingControl) =
      Tui.resourceWith[IO, Int](CounterApp, control, ScriptedInput.never)

    test("the handle: update, send, stats, stop, await") {
      val control = RecordingControl(40, 5)
      counter(control)
        .use { h =>
          for
              _     <- h.update(_ + 5)
              _     <- h.send(Key.Char('+'))
              _     <- h.states.find(_ == 6).compile.lastOrError
              stats <- h.stats
              _     <- h.stop
              n     <- h.await
          yield (n, stats)
        }
        .map { (n, stats) =>
          assertEquals(n, 6)
          assert(stats.frames >= 2, stats)
          assert(control.screen.contains("Count: 6"), control.screen.text)
          assertEquals(control.events.lastOption, Some("leave-raw-mode"))
        }
    }

    test("states starts with the current state and ends with the last one") {
      counter(RecordingControl())
        .use { h =>
          for
              first <- h.states.head.compile.lastOrError
              all   <- h.states.compile.toList.start
              _     <- h.send(Key.Char('+'))
              _     <- h.send(Key.Char('+'))
              _     <- h.states.find(_ == 2).compile.lastOrError
              _     <- h.send(Key.Char('q'))
              seen  <- all.joinWithNever
          yield (first, seen)
        }
        .map { (first, seen) =>
          assertEquals(first, 0)
          // The second stream can start after a '+': it starts with the
          // state of that moment.
          assertEquals(seen.lastOption, Some(2))
          assertEquals(seen, seen.distinct.sorted)
        }
    }

    test("the release stops the app and returns after the restore") {
      val control = RecordingControl()
      counter(control)
        .use(h => h.send(Key.Char('+')) *> h.stats)
        .map { stats =>
          assert(stats.frames >= 1, stats)
          assertEquals(control.events.lastOption, Some("leave-raw-mode"))
        }
    }

    test("a cancelled use restores the terminal before the release returns") {
      val control = RecordingControl()
      for
          ready   <- IO.deferred[Unit]
          fiber   <- counter(control)
                       .use(_ => ready.complete(()) *> IO.never[Unit])
                       .start
          _       <- ready.get.timeout(10.seconds)
          _       <- fiber.cancel
          outcome <- fiber.join
      yield
          assert(outcome.isCanceled, outcome.toString)
          assertEquals(control.events.lastOption, Some("leave-raw-mode"))
      end for
    }

    test("an app that fails before its first frame fails the acquire") {
      val app     = App[Int](
        init = 0,
        view = _ => sys.error("no view"),
        update = (n, _) => n,
      )
      val control = RecordingControl()
      Tui
        .resourceWith[IO, Int](app, control, ScriptedInput.never)
        .use(_ => IO.unit)
        .attempt
        .map {
          case Left(e: TuiError.AppFailed) =>
            assertEquals(e.error.getMessage, "no view")
            assertEquals(control.events.lastOption, Some("leave-raw-mode"))
          case other                       => fail(s"not AppFailed: $other")
        }
    }
end ResourceSuite
