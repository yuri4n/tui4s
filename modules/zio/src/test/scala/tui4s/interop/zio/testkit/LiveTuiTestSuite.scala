package tui4s.interop.zio.testkit

import _root_.zio.Promise
import _root_.zio.ZIO
import _root_.zio.durationInt
import _root_.zio.test.TestClock
import _root_.zio.test.testEnvironment
import tui4s.interop.zio.*
import tui4s.interop.zio.fixtures.CounterApp

/** The facade's live kit (SPEC 4.4, 8.3 Z3) on the real loop, headless. */
class LiveTuiTestSuite extends ZioSuite:
    /** Repeats `z` on this fiber (it may read a TestClock of this fiber), with
      * short real-time pauses, until it gives true.
      */
    private def until(
        z: ZIO[Any, Nothing, Boolean],
    ): ZIO[Any, Throwable, Unit] =
      z.flatMap(ok =>
        if ok then ZIO.unit
        else ZIO.attemptBlocking(Thread.sleep(5)) *> until(z),
      )

    test("press: one batch per key, then settle; the screen shows it") {
      val (n, screen, frames) = run(
        LiveTuiTest(CounterApp, 40, 5) { t =>
          for
              _      <- t.press('+', '+', '-', '+')
              n      <- t.state
              screen <- t.screen
              stats  <- t.stats
          yield (n, screen, stats.frames)
        },
      )
      assertEquals(n, 2)
      assert(screen.contains("Count: 2"), screen.text)
      // The first frame, then one per changed batch (no frame cap).
      assertEquals(frames, 5L)
    }

    test("q quits; the test still ends with the last state") {
      val (quit, n) = run(
        LiveTuiTest(CounterApp) { t =>
          t.press('+', 'q') *> t.quit.zip(t.state)
        },
      )
      assert(quit)
      assertEquals(n, 1)
    }

    test("typeText and send") {
      final case class Form(field: TextField, sent: Vector[String])
          derives CanEqual
      val app       = App[Form](
        init = Form(TextField.empty, Vector.empty),
        view = f =>
          column(input(f.field, focused = true), text(f.sent.mkString(","))),
        update = (f, e) =>
          e match
              case Key.Enter => Form(TextField.empty, f.sent :+ f.field.text)
              case other     => f.copy(field = f.field.edit(other)),
      )
      val (sent, s) = run(
        LiveTuiTest(app, 30, 4) { t =>
          t.typeText("hi\n") *> t.typeText("yo") *>
            t.send(Key.Backspace, Key.Char('u')) *> t.state.zip(t.screen)
        },
      )
      assertEquals(sent.sent, Vector("hi"))
      assertEquals(sent.field.text, "yu")
      assert(s.contains("yu"), s.text)
    }

    test("settle waits for a running task (real time)") {
      val app = App[String](
        init = "",
        view = s => text(s),
        update = (s, e) =>
          e match
              case Key.Char('l') =>
                s.withTask(
                  Source
                    .For[String]
                    .task(ZIO.succeed("loaded").delay(150.millis))((_, v) => v),
                )
              case _             => s,
      )
      val s   = run(LiveTuiTest(app)(t => t.press('l') *> t.state))
      assertEquals(s, "loaded")
    }

    test("sources lists the declared standing sources") {
      val app       = App[Boolean](
        init = true,
        view = on => text(on.toString),
        update = (_, _) => false,
        sources = on =>
          if on then Seq(Source.For[Boolean].every(1.hour)(identity).id("tick"))
          else Nil,
      )
      val (on, off) = run(
        LiveTuiTest(app)(t =>
          t.sources.flatMap(on =>
            t.press('x') *> t.sources.map(off => (on, off)),
          ),
        ),
      )
      assertEquals(on, Seq("tick"))
      assertEquals(off, Nil)
    }

    test("an app failure fails the test with the app's TuiError") {
      val boom = IllegalStateException("bad key")
      val app  = App[Int](
        init = 0,
        view = n => text(n.toString),
        update = (n, e) => if e == Key.Char('x') then throw boom else n,
      )
      val r    = exit(LiveTuiTest(app)(t => t.press('x') *> t.error))
      r match
          case _root_.zio.Exit.Failure(cause) =>
            cause.failureOption match
                case Some(TuiError.AppFailed(_, e)) => assertEquals(e, boom)
                case other                          =>
                  fail(s"unexpected $other")
          case other                          =>
            fail(s"unexpected $other")
      end match
    }

    test("a mouse event without TuiOptions(mouse = true) dies") {
      val r = exit(
        LiveTuiTest(CounterApp)(t =>
          t.send(Mouse(MouseKind.Down(Button.Left), 1, 1, Mods(), Nil)),
        ),
      )
      assert(
        r.causeOption.exists(
          _.dieOption.exists(_.isInstanceOf[AssertionError]),
        ),
        r,
      )
    }

    test("interrupting the test stops the app") {
      val stopped = run(
        Promise.make[Nothing, LiveTest[Int]].flatMap { p =>
          LiveTuiTest(CounterApp)(t => p.succeed(t) *> ZIO.never).fork
            .flatMap(f => p.await.flatMap(t => f.interrupt *> t.quit))
        },
      )
      assert(stopped)
    }

    test("with a TestClock, adjust drives a poller of the app") {
      val app = App[Int](
        init = 0,
        view = n => text(n.toString),
        update = (n, _) => n,
        sources =
          _ => Seq(Source.For[Int].poll(1.minute)(ZIO.succeed(1))(_ + _)),
      )
      val n   = run(
        ZIO.scoped(
          testEnvironment.build *>
            LiveTuiTest(app) { t =>
              until(t.state.map(_ == 1)) *>
                until(TestClock.sleeps.map(_.nonEmpty)) *>
                TestClock.adjust(1.minute) *> until(t.state.map(_ == 2)) *>
                t.state
            },
        ),
      )
      assertEquals(n, 2)
    }
end LiveTuiTestSuite
