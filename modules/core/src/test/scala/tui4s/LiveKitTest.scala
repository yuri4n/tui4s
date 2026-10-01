package tui4s

import java.nio.file.Files
import kyo.*
import kyo.test.*
import tui4s.fixtures.*
import tui4s.testkit.*

/** The live test kit (SPEC 2.10, 6.2): the real loop under virtual time, a
  * settle that parks, and stepped `advance`.
  */
class LiveKitTest extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    "live: the poller samples every 250 ms and stops when paused".timeout(
      30.seconds,
    ) in {
      TuiTest.live(DashboardApp, 72, 16) { t =>
        t.state
          .map(d => assert(d.history.size <= 1 && d.logs.isEmpty))
          .andThen(t.advance(1.second))
          .andThen(t.state)
          .map(d => assert(d.history.size == 5 && d.logs.size == 1, d.toString))
          .andThen(t.sources)
          .map(s => assert(s == Seq("logs", "cpu")))
          .andThen(t.press('p'))
          .andThen(t.advance(1.second))
          .andThen(t.state)
          .map(d =>
            assert(
              d.history.size == 5 && d.logs.size == 2 && d.paused,
              d.toString,
            ),
          )
          .andThen(t.screen)
          .map(s =>
            assert(
              s.contains("CPU (paused)") && s.contains("GET /api/items/2 200"),
            ),
          )
          .andThen(t.sources)
          .map(s => assert(s == Seq("logs")))
      }
    }

    "live: one advance of 250 ms is one sample".timeout(30.seconds) in {
      TuiTest.live(DashboardApp, 72, 16) { t =>
        t.advance(250.millis)
          .andThen(t.state)
          .map(d => assert(d.history.size == 2))
          .andThen(t.advance(250.millis))
          .andThen(t.advance(250.millis))
          .andThen(t.state)
          .map(d => assert(d.history.size == 4 && d.logs.size == 1))
      }
    }

    "live: keys draw frames; the screen follows; the frame cap is off".timeout(
      30.seconds,
    ) in {
      TuiTest.live(CounterApp, 40, 5) { t =>
        t.press('+', '+', '+')
          .andThen(t.screen)
          .map(s => assert(s.contains("Count: 3")))
          .andThen(t.stats)
          .map(st => assert(st.frames == 4 && st.batches == 3))
          .andThen(t.typeText("--"))
          .andThen(t.state)
          .map(n => assert(n == 1))
          .andThen(t.press('q'))
          .andThen(t.quit)
          .map(q => assert(q))
      }
    }

    "live: settle waits for a one-shot task and its fold".timeout(
      30.seconds,
    ) in {
      Files.deleteIfExists(TodoStore.file)
      TuiTest.live(TodoApp, 48, 12) { t =>
        t.state
          .map(s => assert(s.ready && s.status == "0 loaded"))
          .andThen(t.typeText("milk"))
          .andThen(t.press(Key.Enter, Key.Ctrl('s')))
          .andThen(t.state)
          .map { s =>
            assert(
              s.status == "saved" && s.items.map(_.title) == Vector("milk"),
            )
            assert(Files.readString(TodoStore.file).contains("[ ] milk"))
          }
          .andThen(t.stats)
          .map(st => assert(st.tasks == 0))
      }
    }

    "live: the test's failure stops the app and comes back".timeout(
      30.seconds,
    ) in {
      Abort
        .run[Throwable](
          TuiTest.live(CounterApp, 40, 5)(_ =>
            Abort.fail(new IllegalStateException("x")): Unit < Abort[Throwable],
          ),
        )
        .map {
          case Result.Failure(e) => assert(e.getMessage == "x")
          case other             => assert(false, s"unexpected: $other")
        }
    }

    "liveBlocking runs the live kit from a blocking thread".timeout(
      30.seconds,
    ) in {
      Sync.Unsafe
        .defer(Promise.Unsafe.init[Int, Any]())
        .map { p =>
          Sync
            .defer {
              val thread = new Thread(() =>
                  val n = TuiTest.liveBlocking(DashboardApp, 72, 16)(t =>
                    t.advance(1.second).andThen(t.state).map(_.history.size),
                  )
                  import AllowUnsafe.embrace.danger
                  p.completeDiscard(Result.succeed(n)),
              )
              thread.setDaemon(true)
              thread.start()
            }
            .andThen(p.safe.get)
        }
        .map(n => assert(n == 5))
    }

    "live: a task asleep on the virtual clock does not hold press; advance wakes it"
      .timeout(30.seconds) in {
      final case class Toast(toast: Option[String] = None) derives CanEqual
      val s   = Source.For[Toast]
      val app = App[Toast](
        Toast(),
        t => text(t.toast.getOrElse("-")),
        (t, e) =>
          if e == Key.Char('x') then
              t.copy(toast = Some("saved"))
                .withTask(
                  s.task(Async.sleep(2.seconds))((t, _: Unit) =>
                    t.copy(toast = None),
                  ),
                )
          else t,
      )
      TuiTest.live(app, 20, 2) { t =>
        t.press('x')
          .andThen(t.state)
          .map(s => assert(s.toast.contains("saved")))
          .andThen(t.stats)
          .map(st => assert(st.tasks == 1))
          .andThen(t.advance(1.second))
          .andThen(t.state)
          .map(s => assert(s.toast.contains("saved")))
          .andThen(t.advance(1.second))
          .andThen(t.state)
          .map(s => assert(s.toast.isEmpty))
          .andThen(t.screen)
          .map(sc => assert(sc.row(0) == "-"))
          .andThen(t.stats)
          .map(st => assert(st.tasks == 0))
      }
    }

    "live: a standing task asleep on the clock does not hold the first settle"
      .timeout(30.seconds) in {
      final case class Splash(on: Boolean = true) derives CanEqual
      val s   = Source.For[Splash]
      val app = App[Splash](
        Splash(),
        sp => text(if sp.on then "splash" else "ready"),
        (sp, _) => sp,
        sp =>
          if !sp.on then Nil
          else
              Seq(
                s.task(Async.sleep(500.millis).andThen(1))((sp, _: Int) =>
                  sp.copy(on = false),
                ).id("splash"),
              ),
      )
      TuiTest.live(app, 20, 2) { t =>
        t.state
          .map(sp => assert(sp.on))
          .andThen(t.advance(499.millis))
          .andThen(t.state)
          .map(sp => assert(sp.on))
          .andThen(t.advance(1.millis))
          .andThen(t.screen)
          .map(sc => assert(sc.row(0) == "ready"))
          .andThen(t.sources)
          .map(l => assert(l.isEmpty))
      }
    }

    "live: a periodic fold starts a task that sleeps; advance steps through both"
      .timeout(30.seconds) in {
      final case class Beat(beats: Int = 0, done: Int = 0) derives CanEqual
      val s   = Source.For[Beat]
      val app = App[Beat](
        Beat(),
        b => text(s"${b.beats}/${b.done}"),
        (b, _) => b,
        _ =>
          Seq(
            s.every(1.second)(b =>
              b.copy(beats = b.beats + 1)
                .withTask(
                  s.task(Async.sleep(300.millis).andThen(1))((b, a: Int) =>
                    b.copy(done = b.done + a),
                  ),
                ),
            ).id("beat"),
          ),
      )
      TuiTest.live(app, 20, 2) { t =>
        t.advance(2.seconds)
          .andThen(t.state)
          .map(b => assert(b == Beat(2, 1), b.toString))
          .andThen(t.advance(299.millis))
          .andThen(t.state)
          .map(b => assert(b == Beat(2, 1), b.toString))
          .andThen(t.advance(1.millis))
          .andThen(t.state)
          .map(b => assert(b == Beat(2, 2), b.toString))
      }
    }

    "live: a stream that sleeps and ends no longer counts as a sleeper"
      .timeout(30.seconds) in {
      val s   = Source.For[Vector[Int]]
      val few = Stream.unfold(0, chunkSize = 1)(i =>
        Async
          .sleep(100.millis)
          .andThen(if i < 3 then Present((i, i + 1)) else Absent),
      )
      val app = App[Vector[Int]](
        Vector.empty,
        v => text(v.mkString(",")),
        (v, _) => v,
        _ => Seq(s.stream(few)(_ :+ _).id("few")),
      )
      TuiTest.live(app, 20, 2) { t =>
        t.advance(1.second)
          .andThen(t.state)
          .map(v => assert(v == Vector(0, 1, 2)))
          .andThen(t.advance(1.second))
          .andThen(t.stats)
          .map(st => assert(st.sources == 0))
      }
    }

    "live: an app that fails fails the run; error says why".timeout(
      30.seconds,
    ) in {
      val boom = new IllegalStateException("bad")
      val app  = App[Int](
        0,
        n => text(s"Count: $n"),
        (n, e) => if e == Key.Char('x') then throw boom else n + 1,
      )
      Abort
        .run[Throwable](
          TuiTest.live(app, 20, 2) { t =>
            t.press('+', 'x', '+')
              .andThen(t.quit)
              .map(q => assert(q))
              .andThen(t.error)
              .map(e =>
                assert(e == Some(TuiError.AppFailed("update on key:x", boom))),
              )
              .andThen(t.screen)
              .map(sc => assert(sc.contains("Count: 1")))
          },
        )
        .map {
          case Result.Failure(TuiError.AppFailed(where, e)) =>
            assert(where == "update on key:x" && (e eq boom))
          case other                                        =>
            assert(false, s"unexpected: $other")
        }
    }

    "live: typeText sends keys as a terminal does ('\\n' is Enter)".timeout(
      30.seconds,
    ) in {
      Files.deleteIfExists(TodoStore.file)
      TuiTest.live(TodoApp, 48, 12) { t =>
        t.typeText("milk\n")
          .andThen(t.state)
          .map(s => assert(s.items.map(_.title) == Vector("milk")))
      }
    }

    "live: the state an app quits with is the last state".timeout(
      30.seconds,
    ) in {
      val app = App[Int](
        1,
        n => text(n.toString),
        (n, e) => if e == Key.Char('q') then Quit(n * 10) else n + 1,
      )
      TuiTest.live(app, 10, 1) { t =>
        t.press('+', 'q')
          .andThen(t.state)
          .map(n => assert(n == 20))
          .andThen(t.quit)
          .map(q => assert(q))
      }
    }
end LiveKitTest
