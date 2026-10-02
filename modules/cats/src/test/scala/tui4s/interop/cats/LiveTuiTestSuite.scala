package tui4s.interop.cats

import _root_.cats.effect.IO
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import tui4s.interop.cats.fixtures.Apps
import tui4s.interop.cats.fixtures.Apps.Tally
import tui4s.interop.cats.fixtures.CounterApp
import tui4s.interop.cats.testkit.LiveTuiTest

/** The live test kit of the facade: the real loop driven from IO. */
class LiveTuiTestSuite extends CatsEffectSuite:
    override def munitIOTimeout: Duration = 60.seconds

    test("press folds and draws before it returns") {
      LiveTuiTest[IO](CounterApp, 40, 5) { t =>
        for
            _      <- t.press('+', '+', '+', '-')
            n      <- t.state
            screen <- t.screen
            stats  <- t.stats
        yield
            assertEquals(n, 2)
            assert(screen.contains("Count: 2"), screen.text)
            assertEquals(stats.frames, 5L)
      }
    }

    test("q quits; the test still gets the last state") {
      LiveTuiTest[IO](CounterApp) { t =>
        t.press('+', 'q') *> t.quit.product(t.state)
      }.map(r => assertEquals(r, (true, 1)))
    }

    test("settle waits for a running task; eventually waits for a poller") {
      val source = Source.For[IO, Tally]
      val app    = Apps.tally(_ =>
        Seq(
          source.task(IO.sleep(200.millis) *> IO(40))((t, v) =>
            t.copy(sum = v.toLong),
          ),
          source.poll(30.millis)(IO(1))((t, _) => t.copy(count = t.count + 1)),
        ),
      )
      LiveTuiTest[IO](app) { t =>
        for
            first   <- t.state
            sources <- t.sources
            three   <- t.eventually()(s => Option.when(s.count >= 3)(s.count))
        yield
            assertEquals(first.sum, 40L)
            assertEquals(sources.size, 2)
            assert(three >= 3)
      }
    }

    test("typeText and send reach update") {
      val app = App[String](
        init = "",
        view = s => text(s),
        update = (s, e) =>
          e match
              case Key.Char(c) => s + c
              case Key.Enter   => s + "|"
              case Paste(p)    => s + p
              case _           => s,
      )
      LiveTuiTest[IO](app) { t =>
        t.typeText("ab\n") *> t.send(Paste("cd"), Key.Char('e')) *> t.state
      }.map(s => assertEquals(s, "ab|cde"))
    }

    test("an app failure fails the test with the TuiError") {
      val app = App[Int](
        init = 0,
        view = n => text(n.toString),
        update = (n, e) => if e == Key.Char('x') then sys.error("boom") else n,
      )
      LiveTuiTest[IO](app)(t => t.press('x') *> t.error).attempt.map {
        case Left(e: TuiError.AppFailed) =>
          assertEquals(e.error.getMessage, "boom")
        case other                       => fail(s"not AppFailed: $other")
      }
    }
    test("a cancelled live test stops the app and its sources") {
      for
          started   <- IO.deferred[Unit]
          cancelled <- IO.deferred[Unit]
          work       = (started.complete(()) *> IO.never[Int])
                         .onCancel(cancelled.complete(()).void)
          app        = Apps.tally(_ =>
                         Seq(Source.For[IO, Tally].poll(1.second)(work)(_.add(_, 1))),
                       )
          result    <- LiveTuiTest[IO](app)(_ => started.get *> IO.never[Unit])
                         .timeout(2.seconds)
                         .attempt
          _         <- cancelled.get.timeout(10.seconds)
      yield assert(result.isLeft, result.toString)
    }
end LiveTuiTestSuite
