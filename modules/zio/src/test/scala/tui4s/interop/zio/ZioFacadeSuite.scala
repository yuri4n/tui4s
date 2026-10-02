package tui4s.interop.zio

import _root_.zio.Exit
import _root_.zio.FiberRef
import _root_.zio.Runtime
import _root_.zio.ZIO
import _root_.zio.durationInt
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import tui4s.testkit.*

/** SPEC 6.3 "tui4s-zio": the facade on the run seam (a test terminal and
  * scripted input, through the facade's own bridge), the caller's runtime
  * (F12), interruption with the restore (F11), the handle, failures, and the
  * logger rule.
  */
class ZioFacadeSuite extends ZioSuite:
    private def await(latch: CountDownLatch): Boolean =
      latch.await(10, TimeUnit.SECONDS)

    test("the shared Counter behaves the same under tui4s.interop.zio") {
      assertEquals(TuiTest(fixtures.CounterApp).press('+', '+').state, 2)
    }

    test("a task source runs on the caller's runtime (FiberRef visible, F12)") {
      val seen = run(ZIO.scoped(FiberRef.make(0).flatMap { ref =>
        ref.locally(42)(
          Tui.runWith(
            fixtures.readRefOnce(ref),
            RecordingControl(),
            ScriptedInput.empty,
          ),
        )
      }))
      assertEquals(seen, 42)
    }

    test("interrupting Tui.run returns only after the terminal is restored") {
      val log = RecordingControl()
      val r   = run(
        Tui
          .runWith(fixtures.CounterApp, log, ScriptedInput.never)
          .fork
          .flatMap { f =>
            ZIO.attemptBlocking(
              eventually("the app started")(
                log.events.contains("input-started"),
              ),
            ) *> f.interrupt.map(e => (e.isInterrupted, log.events))
          },
      )
      assertEquals(
        r,
        (
          true,
          Seq(
            "enter",
            "input-started",
            "input-stopped",
            "input-joined",
            "leave-raw-mode",
          ),
        ),
      )
    }

    test("the Counter on the real loop returns its last state") {
      val control = RecordingControl(40, 5)
      val input   = ScriptedInput('+', '+', '+', '-')
      val n       = run(
        Tui.runWith(fixtures.CounterApp, control, input).fork.flatMap { f =>
          ZIO.attemptBlocking(
            eventually(control.screen.text)(
              control.screen.contains("Count: 2"),
            ),
          ) *> ZIO.succeed(input.push(Key.Char('q'))) *> f.join
        },
      )
      assertEquals(n, 2)
      assertEquals(control.events.last, "leave-raw-mode")
    }

    test("a facade source's label is the user's call site") {
      val s = Source.For[Int].task(ZIO.succeed(1))((n, a) => n + a)
      assert(s.label.startsWith("ZioFacadeSuite.scala:"), s.label)
      val t = Source.task(ZIO.succeed(1))((n: Int, a) => n + a).id("load")
      assertEquals(t.label, "load")
    }

    test("a failing ZIO task ends the run with SourceFailed (typed)") {
      val boom = RuntimeException("boom")
      val app  = App[Int](
        init = 0,
        view = n => text(n.toString),
        update = (n, _) => n,
        sources =
          _ => Seq(Source.For[Int].task(ZIO.fail(boom))((n, _) => n).id("bad")),
      )
      val r    = exit(
        Tui.runWith(app, RecordingControl(), ScriptedInput.empty).either,
      )
      r match
          case Exit.Success(Left(TuiError.SourceFailed(label, e))) =>
            assertEquals(label, "bad")
            assertEquals(e, boom)
          case other                                               =>
            fail(s"unexpected $other")
    }

    test("recover folds a failing ZIO task into the state") {
      val app = App[String](
        init = "",
        view = s => text(s),
        update = (s, _) => s,
        sources = s =>
          if s.nonEmpty then Nil
          else
              Seq(
                Source
                  .For[String]
                  .task(ZIO.fail(RuntimeException("no file")))((s, _) => s)
                  .recover((_, e) => Quit(e.getMessage.nn)),
              ),
      )
      val r   = run(Tui.runWith(app, RecordingControl(), ScriptedInput.empty))
      assertEquals(r, "no file")
    }

    test("update throws: the run fails with AppFailed (typed, not a defect)") {
      val boom = IllegalStateException("bad key")
      val app  = App[Int](
        init = 0,
        view = n => text(n.toString),
        update = (_, _) => throw boom,
      )
      val r    = exit(
        Tui.runWith(app, RecordingControl(), ScriptedInput('x')).either,
      )
      r match
          case Exit.Success(Left(TuiError.AppFailed(where, e))) =>
            assert(where.startsWith("update"), where)
            assertEquals(e, boom)
          case other                                            =>
            fail(s"unexpected $other")
    }

    test("ZIO's default loggers are off while the UI is up, on after") {
      val app                     = App[Boolean](
        init = false,
        view = b => text(b.toString),
        update = (b, _) => b,
        sources = b =>
          if b then Nil
          else
              Seq(
                Source
                  .For[Boolean]
                  .task(
                    ZIO.loggers.map(_.exists(Runtime.defaultLoggers.contains)),
                  )((_, on) => Quit(on)),
              ),
      )
      val (during, before, after) = run(
        for
            before <- ZIO.loggers
            during <- Tui.runWith(app, RecordingControl(), ScriptedInput.empty)
            after  <- ZIO.loggers
        yield (during, before, after),
      )
      assert(before.exists(Runtime.defaultLoggers.contains))
      assert(!during, "a default logger was on in a source")
      assertEquals(after, before)
    }

    test("Tui.start: update, send, states, stats, stop and await") {
      val control                       = RecordingControl(40, 5)
      val (last, frames, first, states) = run(
        ZIO.scoped(
          Tui
            .startWith(fixtures.CounterApp, control, ScriptedInput.empty)
            .flatMap { h =>
              for
                  first  <- h.states.take(1).runCollect
                  all    <- h.states.takeUntil(_ == 6).runCollect.fork
                  _      <- h.update(_ + 5)
                  _      <- h.send(Key.Char('+'))
                  states <- all.join
                  stats  <- h.stats
                  _      <- h.stop
                  last   <- h.await
              yield (last, stats.frames, first, states)
            },
        ),
      )
      assertEquals(last, 6)
      assertEquals(first.toList, List(0))
      assertEquals(states.last, 6)
      assertEquals(states.distinct, states)
      assert(frames >= 2, frames.toString)
      assert(control.screen.contains("Count: 6"), control.screen.text)
      assertEquals(control.events.last, "leave-raw-mode")
    }

    test("the states stream ends with the last state when the app quits") {
      val states = run(
        ZIO.scoped(
          Tui
            .startWith(
              fixtures.CounterApp,
              RecordingControl(),
              ScriptedInput.empty,
            )
            .flatMap { h =>
              for
                  all <- h.states.runCollect.fork
                  _   <- h.send(Key.Char('+'))
                  _   <- h.send(Key.Char('q'))
                  s   <- all.join
              yield s
            },
        ),
      )
      assertEquals(states.last, 1)
      assertEquals(states.distinct, states)
    }

    test("interrupting await does not stop the app; the scope's end does") {
      val control                    = RecordingControl()
      val (timedOut, running, after) = run(
        for r <-
              ZIO.scoped(
                Tui
                  .startWith(fixtures.CounterApp, control, ScriptedInput.empty)
                  .flatMap { h =>
                    for
                        t <- h.await.timeout(100.millis)
                        _ <- h.send(Key.Char('+'))
                        s <- h.states.takeUntil(_ == 1).runLast
                    yield (t, s)
                  },
              )
        yield (r._1, r._2, control.events.last),
      )
      assertEquals(timedOut, None)
      assertEquals(running, Some(1))
      assertEquals(after, "leave-raw-mode")
    }

    test("Tui.start fails with the TuiError of an app that cannot start") {
      val boom = IllegalStateException("no init")
      val app  = new App[Int]:
          def init: Int                               = throw boom
          def view(n: Int): View                      = text(n.toString)
          def update(n: Int, event: Event): Next[Int] = n
      val r    = exit(
        ZIO
          .scoped(Tui.startWith(app, RecordingControl(), ScriptedInput.empty))
          .either,
      )
      r match
          case Exit.Success(Left(TuiError.AppFailed(_, e))) =>
            assertEquals(e, boom)
          case other                                        =>
            fail(s"unexpected $other")
    }

    test("a stopped source interrupts its ZIO (onInterrupt runs)") {
      val started     = CountDownLatch(1)
      val interrupted = CountDownLatch(1)
      val work        = (ZIO.succeed(started.countDown()) *> ZIO.never)
        .onInterrupt(ZIO.succeed(interrupted.countDown()))
      val app         = App[Boolean](
        init = true,
        view = on => text(on.toString),
        update = (on, e) =>
          e match
              case Key.Char('x') => false
              case Key.Char('q') => Quit
              case _             => on,
        sources = on =>
          if on then Seq(Source.For[Boolean].task(work)((s, _) => s)) else Nil,
      )
      val input       = ScriptedInput.empty
      val r           = run(
        Tui.runWith(app, RecordingControl(), input).fork.flatMap { f =>
          ZIO.attemptBlocking {
            assert(await(started), "the task did not start")
            input.push(Key.Char('x'))
            val stopped = await(interrupted)
            input.push(Key.Char('q'))
            stopped
          } <* f.join
        },
      )
      assert(r, "the ZIO of the stopped source was not interrupted")
    }

    test(
      "Tui.start: a source dropped by the app is interrupted (bracket acquire)",
    ) {
      // The handle is acquired in an uninterruptible region; its sources
      // must still stop.
      val started     = CountDownLatch(1)
      val interrupted = CountDownLatch(1)
      val work        = (ZIO.succeed(started.countDown()) *> ZIO.never)
        .onInterrupt(ZIO.succeed(interrupted.countDown()))
      val app         = App[Boolean](
        init = true,
        view = on => text(on.toString),
        update = (on, _) => on,
        sources = on =>
          if on then Seq(Source.For[Boolean].poll(1.second)(work)((s, _) => s))
          else Nil,
      )
      val r           = run(
        ZIO.scoped(
          Tui.startWith(app, RecordingControl(), ScriptedInput.empty).flatMap {
            h =>
              ZIO.attemptBlocking(await(started)) *> h.update(_ => false) *>
                ZIO.attemptBlocking(await(interrupted))
          },
        ),
      )
      assert(r, "the ZIO of the dropped source was not interrupted")
    }

    test("the end of the app interrupts the ZIO of every running source") {
      val started     = CountDownLatch(2)
      val interrupted = CountDownLatch(2)
      def work        = (ZIO.succeed(started.countDown()) *> ZIO.never)
        .onInterrupt(ZIO.succeed(interrupted.countDown()))
      val app         = App[Int](
        init = 0,
        view = n => text(n.toString),
        update = (_, _) => Quit,
        sources = _ =>
          Seq(
            Source.For[Int].task(work)((s, _) => s).id("a"),
            Source
              .For[Int]
              .stream(_root_.zio.stream.ZStream.fromZIO(work))((s, _) => s)
              .id("b"),
          ),
      )
      val input       = ScriptedInput.empty
      val last        = run(
        Tui.runWith(app, RecordingControl(), input).fork.flatMap { f =>
          ZIO.attemptBlocking(assert(await(started))) *>
            ZIO.succeed(input.push(Key.Char('q'))) *> f.join
        },
      )
      assertEquals(last, 0)
      assert(await(interrupted), "a source's ZIO was not interrupted")
    }

    test("every and signal fold on the caller's runtime") {
      val app            = (ref: _root_.zio.stream.SubscriptionRef[Int]) =>
        App[(Int, Int)](
          init = (0, 0),
          view = s => text(s.toString),
          update = (s, _) => s,
          sources = _ =>
            Seq(
              Source.For[(Int, Int)].every(20.millis)((t, v) => (t + 1, v)),
              Source
                .For[(Int, Int)]
                .signal(ref)((s, v) =>
                  if v == 3 then Quit((s._1, v)) else (s._1, v),
                ),
            ),
        )
      val (ticks, value) = run(
        _root_.zio.stream.SubscriptionRef.make(0).flatMap { ref =>
          Tui
            .runWith(app(ref), RecordingControl(), ScriptedInput.empty)
            .fork
            .flatMap(f =>
              ref.set(1) *> ref.set(2) *> ZIO.sleep(300.millis) *> ref.set(3) *>
                f.join,
            )
        },
      )
      assertEquals(value, 3)
      assert(ticks >= 2, ticks.toString)
    }

    test("signal folds the newest value; an equal write folds nothing") {
      val folds = java.util.concurrent.atomic.AtomicInteger(0)
      val app   = (ref: _root_.zio.stream.SubscriptionRef[Int]) =>
        App[Int](
          init = -1,
          view = n => text(n.toString),
          update = (n, _) => n,
          sources = _ =>
            Seq(Source.For[Int].signal(ref) { (_, v) =>
              val _ = folds.incrementAndGet()
              if v == 1000 then Quit(v) else v
            }),
        )
      val last  = run(
        _root_.zio.stream.SubscriptionRef.make(0).flatMap { ref =>
          Tui
            .runWith(app(ref), RecordingControl(), ScriptedInput.empty)
            .fork
            .flatMap(f =>
              ZIO.foreachDiscard(1 to 1000)(i => ref.set(i) *> ref.set(i)) *>
                f.join,
            )
        },
      )
      assertEquals(last, 1000)
      assert(folds.get <= 1001, folds.get.toString)
    }
end ZioFacadeSuite
