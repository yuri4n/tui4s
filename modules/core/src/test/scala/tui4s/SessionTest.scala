package tui4s

import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import kyo.test.*
import tui4s.internal.Failed
import tui4s.internal.InputThread
import tui4s.internal.Item
import tui4s.internal.Session
import tui4s.internal.TokenEmitter
import tui4s.testkit.*

/** The terminal session and the inbox at the edges of a run: the release, a
  * failing input thread, an emit after the end (SPEC 5.3, 5.8, 5.9, 5.12).
  */
class SessionTest extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    private def inbox(capacity: Int)(using Frame): Channel[Item] < Sync =
      Channel.initUnscoped[Item](capacity, Access.MultiProducerSingleConsumer)

    "the release leaves the terminal also when the closing thread is interrupted"
      .timeout(30.seconds) in {
      val control = RecordingControl(10, 2)
      inbox(16).map { box =>
        Session
          .open(TuiOptions(), control, Some(ScriptedInput.never), box)
          .map {
            case Left(e)        => assert(false, s"open failed: $e")
            case Right(session) =>
              Sync
                .defer {
                  val flagged = AtomicBoolean(false)
                  val closer  = new Thread(() =>
                      import AllowUnsafe.embrace.danger
                      Thread.currentThread().interrupt()
                      session.close()
                      flagged.set(Thread.currentThread().isInterrupted),
                  )
                  closer.start()
                  closer.join(5000)
                  flagged.get
                }
                .map { flagged =>
                  assert(
                    control.events == Seq(
                      "enter",
                      "input-started",
                      "input-stopped",
                      "input-joined",
                      "leave-raw-mode",
                    ),
                    control.events.toString,
                  )
                  assert(flagged, "the interrupt flag is set again")
                }
          }
      }
    }

    "an input failure is reported also when the inbox is full".timeout(
      30.seconds,
    ) in {
      val broken = new spi.InputPort:
          def poll(timeoutMillis: Int): Boolean =
            throw java.io.IOException("tty gone")
          def read(): Event | Null              = null
      inbox(1).map { box =>
        box
          .put(Key.Char('a'))
          .andThen(Sync.defer {
            val thread = InputThread(broken, box, 5)
            thread.start()
            thread.join(5000) // it failed and ended while the inbox was full
          })
          .andThen(
            Abort.run[Closed | Timeout](
              box.take.andThen(Async.timeout(5.seconds)(box.take)),
            ),
          )
          .map {
            case Result.Success(f: Failed) =>
              assert(f.error.getCause.getMessage == "tty gone")
            case other                     => assert(false, s"unexpected: $other")
          }
      }
    }

    "after the end, an emit ends the body instead of returning".timeout(
      30.seconds,
    ) in {
      inbox(4).map { box =>
        box.close
          .andThen {
            val emit = TokenEmitter(box, 1L)
            Abort.run[Throwable](Loop(0) { i =>
              if i >= 1000 then Loop.done(i)
              else emit(()).andThen(Loop.continue(i + 1))
            })
          }
          .map {
            case Result.Panic(_: Closed) => assert(true)
            case other                   => assert(false, s"unexpected: $other")
          }
      }
    }
end SessionTest
