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
end LiveKitTest
