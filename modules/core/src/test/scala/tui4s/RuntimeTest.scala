package tui4s

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kyo.*
import kyo.test.*
import scala.jdk.CollectionConverters.*
import tui4s.fixtures.*
import tui4s.testkit.*

/** The real loop on a test terminal (level 3, real time): the run seam, the
  * handle, the release order, failures, the trace file (SPEC 5.4-5.9, 5.16).
  */
class RuntimeTest extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    /** Waits (real time, at most `max`) until `cond` holds. */
    private def eventually(max: Duration = 5.seconds)(cond: => Boolean)(using
        Frame,
    ): Boolean < Async =
      Loop(0) { n =>
        if cond then Loop.done(true)
        else if n * 10 >= max.toMillis then Loop.done(false)
        else Async.sleep(10.millis).andThen(Loop.continue(n + 1))
      }

    "the Counter on the run seam: keys, frames, then Quit returns the state"
      .timeout(30.seconds) in {
      val control = RecordingControl(40, 5)
      val input   = ScriptedInput('+', '+', '+')
      Fiber.initUnscoped(Tui.runWith(CounterApp, control, input)).map { fiber =>
        eventually()(control.screen.contains("Count: 3"))
          .andThen(Sync.defer(input.push(Key.Char('q'))))
          .andThen(fiber.get)
          .map { n =>
            assert(n == 3)
            assert(control.screen.contains("Count: 3"))
            assert(
              control.events == Seq(
                "enter",
                "input-started",
                "input-stopped",
                "input-joined",
                "leave-raw-mode",
              ),
            )
          }
      }
    }

    "C8: interrupted, the run releases the terminal before interruptAwait returns"
      .timeout(30.seconds) in {
      val control = RecordingControl(20, 5)
      Fiber
        .initUnscoped(Tui.runWith(CounterApp, control, ScriptedInput.never))
        .map { fiber =>
          eventually()(control.events.contains("input-started"))
            .andThen(Async.sleep(50.millis))
            .andThen(fiber.interruptAwait)
            .andThen(Sync.defer(control.events))
            .map { seen =>
              assert(
                seen == Seq(
                  "enter",
                  "input-started",
                  "input-stopped",
                  "input-joined",
                  "leave-raw-mode",
                ),
              )
            }
        }
    }

    "a handle: update, send, states, stats, stop".timeout(30.seconds) in {
      val backend = jatatui.core.backend.TestBackend(40, 5)
      Scope.run(
        spi.Headless.start(CounterApp, backend, ScriptedInput.empty).map { h =>
          h.states.current.map { first =>
            h.update(_ + 5)
              .andThen(h.send(Key.Char('+')))
              .andThen(
                eventually()(
                  Screen.of(backend.buffer(), None).contains("Count: 6"),
                ),
              )
              .andThen(h.states.current)
              .map { now =>
                h.stats.map { st =>
                  h.stop.andThen(h.await).map { last =>
                    assert(first == 0 && now == 6 && last == 6)
                    assert(st.frames >= 2 && st.folds == 1 && st.events == 1)
                  }
                }
              }
          }
        },
      )
    }

    "Ctrl-C quits the run with the current state".timeout(30.seconds) in {
      val input = ScriptedInput('+', Key.Ctrl('c'), '+')
      Tui
        .runWith(CounterApp, RecordingControl(40, 5), input)
        .map(n => assert(n == 1))
    }

    "a failing task ends the run with SourceFailed after the release"
      .timeout(30.seconds) in {
      val boom    = new RuntimeException("boom")
      val app     = App[Int](
        0,
        n => text(n.toString),
        (n, _) => n,
        _ =>
          Seq(
            Source
              .For[Int]
              .task(Abort.fail(boom): Int < Abort[Throwable])((s, a) => s + a)
              .id("bad"),
          ),
      )
      val control = RecordingControl(10, 2)
      Abort.run[TuiError](Tui.runWith(app, control, ScriptedInput.empty)).map {
        r =>
          assert(r == Result.fail(TuiError.SourceFailed("bad", boom)))
          assert(control.events.last == "leave-raw-mode")
      }
    }

    "update that throws ends the run with AppFailed".timeout(30.seconds) in {
      val boom = new IllegalStateException("bad")
      val app  = App[Int](0, n => text(n.toString), (_, _) => throw boom)
      Abort
        .run[TuiError](
          Tui.runWith(app, RecordingControl(10, 2), ScriptedInput('x')),
        )
        .map(r =>
          assert(r == Result.fail(TuiError.AppFailed("update on key:x", boom))),
        )
    }

    "an input port that fails ends the run with TerminalIO".timeout(
      30.seconds,
    ) in {
      val broken = new spi.InputPort:
          def poll(timeoutMillis: Int): Boolean =
            throw java.io.IOException("tty gone")
          def read(): Event | Null              = null
      Abort
        .run[TuiError](Tui.runWith(CounterApp, RecordingControl(10, 2), broken))
        .map {
          case Result.Failure(TuiError.TerminalIO(e)) =>
            assert(e.getMessage == "tty gone")
          case other                                  =>
            assert(false, s"unexpected: $other")
        }
    }

    "a stream source folds on its fiber; pause stops the poller (real time)"
      .timeout(30.seconds) in {
      val control = RecordingControl(72, 16)
      val input   = ScriptedInput.empty
      Fiber.initUnscoped(Tui.runWith(DashboardApp, control, input)).map {
        fiber =>
          eventually()(control.screen.contains("GET /api/items/1 200"))
            .andThen(Sync.defer(input.push(Key.Char('p'))))
            .andThen(eventually()(control.screen.contains("CPU (paused)")))
            .andThen(Sync.defer(input.push(Key.Char('q'))))
            .andThen(fiber.get)
            .map { d =>
              assert(d.paused && d.logs.nonEmpty && d.history.nonEmpty)
            }
      }
    }

    "an inline viewport draws in its rows only".timeout(30.seconds) in {
      val control = RecordingControl(20, 10)
      val app     = App[Int](
        0,
        n => text(s"n=$n"),
        (n, e) => if e == Key.Char('q') then Quit else n + 1,
        options = TuiOptions(viewport = Viewport.Inline(3)),
      )
      val input   = ScriptedInput('+')
      Fiber.initUnscoped(Tui.runWith(app, control, input)).map { fiber =>
        eventually()(control.screen.contains("n=1"))
          .andThen(Sync.defer(input.push(Key.Char('q'))))
          .andThen(fiber.get)
          .map { n =>
            val rows = control.screen.rows
            assert(n == 1 && rows.count(_.contains("n=1")) == 1)
            assert(rows.indexWhere(_.contains("n=1")) < 3)
          }
      }
    }

    "the trace file has a start line, batch lines and a quit line; replay reads it"
      .timeout(30.seconds) in {
      val file = Files.createTempFile("tui4s-runtime", ".jsonl")
      val app  = App[Int](
        CounterApp.init,
        CounterApp.view,
        CounterApp.update,
        options = TuiOptions(trace = Some(file)),
      )
      Tui
        .runWith(
          app,
          RecordingControl(40, 5),
          ScriptedInput('+', '+', 'x', '-', 'q'),
        )
        .map { n =>
          val lines =
            Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toVector
          assert(n == 1)
          assert(lines.head.contains("\"kind\":\"start\",\"size\":\"40x5\""))
          assert(lines.exists(_.contains("\"key:+\"")))
          assert(lines.last.contains("\"kind\":\"quit\",\"reason\":\"update\""))
          assert(!lines.exists(_.contains("\"kind\":\"slow\"")))
          val again = TuiTest.replay(app, file, 40, 5)
          assert(again.state == 1 && again.quit)
          Files.delete(file)
        }
    }

    "the handle's states end with the state the app quits with".timeout(
      30.seconds,
    ) in {
      val app = App[Int](
        1,
        n => text(n.toString),
        (n, e) => if e == Key.Char('q') then Quit(n * 10) else n + 1,
      )
      Scope.run(
        spi.Headless
          .start(
            app,
            jatatui.core.backend.TestBackend(10, 1),
            ScriptedInput('+', 'q'),
          )
          .map(h =>
            h.await.map(last =>
              h.states.current.map(now => assert(last == 20 && now == 20)),
            ),
          ),
      )
    }

    "a caller interrupted before the first frame stops the run it started"
      .timeout(30.seconds) in {
      val control = RecordingControl(20, 2)
      val first   = java.util.concurrent.atomic.AtomicBoolean(true)
      val slow    = App[Int](
        0,
        n =>
            if first.getAndSet(false) then Thread.sleep(300)
            text(n.toString)
        ,
        (n, _) => n,
      )
      Abort
        .run[Timeout](
          Async.timeout(50.millis)(
            Tui.startWith(slow, control, ScriptedInput.never),
          ),
        )
        .map { r =>
          eventually()(control.events.contains("leave-raw-mode"))
            .map(left => assert(r.isFailure && left, s"$r ${control.events}"))
        }
    }

    "a run that fails before its first frame gives no handle: start panics"
      .timeout(30.seconds) in {
      val boom = new IllegalStateException("no init")
      val app  = new App[Int]:
          def init: Int                           = throw boom
          def view(n: Int): View                  = text(n.toString)
          def update(n: Int, e: Event): Next[Int] = n
      Abort
        .run[Throwable](
          Scope.run(
            spi.Headless.start(
              app,
              jatatui.core.backend.TestBackend(10, 1),
              ScriptedInput.empty,
            ),
          ),
        )
        .map { r =>
          r.failureOrPanic match
              case Present(TuiError.AppFailed("init", e)) => assert(e eq boom)
              case _                                      =>
                assert(false, s"unexpected: $r")
        }
    }

    "RecordingControl.screen is the frame copied at the last flush".timeout(
      30.seconds,
    ) in {
      val control = RecordingControl(40, 5)
      val input   = ScriptedInput('+')
      Fiber.initUnscoped(Tui.runWith(CounterApp, control, input)).map { fiber =>
        eventually()(control.screen.contains("Count: 1"))
          .andThen(Sync.defer(input.push(Key.Char('q'))))
          .andThen(fiber.get)
          .map { n =>
            control.backend.resize(5, 1) /* the live buffer changes, the copy
             * not */
            assert(n == 1 && control.screen.contains("Count: 1"))
          }
      }
    }
end RuntimeTest
