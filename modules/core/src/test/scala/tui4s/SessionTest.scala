package tui4s

import kyo.*
import kyo.UI.{Keyboard, KeyboardEvent, Modifiers}
import kyo.test.*
import tui.crossterm.{Event as CEvent, KeyCode}
import tui4s.internal.{InputPort, InputThread, Msg, RunOptions, Session, Size}
import tui4s.testkit.*

import java.util.concurrent.atomic.AtomicBoolean

/** The terminal session and the inbox at the edges of a run: the release, the
  * input thread (it maps crossterm events, D-21 a), a failing input thread.
  */
// claude 2026-10-13: changed (T1, RC-21): the runner's inbox (Channel[Msg]);
// the input thread maps crossterm events; the emit leaf of the old engine
// (TokenEmitter) is not carried
class SessionTest extends Test[Any]:
    override def config: RunConfig = super.config.sequential

    /** The checked default options. */
    // claude 2026-10-19: created: Session.open takes RunOptions (A10)
    private val defaults: RunOptions = RunOptions.of(TuiOptions())

    private def inbox(capacity: Int)(using Frame): Channel[Msg] < Sync =
      Channel.initUnscoped[Msg](capacity)

    "the release leaves the terminal also when the closing thread is interrupted"
      .timeout(30.seconds) in {
      val control = RecordingControl(10, 2)
      inbox(16).map { box =>
        Session
          .open(defaults, control, Some(ScriptedInput.never), box)
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

    // claude 2026-10-13: created (T1, D-21 a): the input-thread test also
    // tests the mapping
    "the input thread maps crossterm events to messages and numbers them"
      .timeout(
        30.seconds,
      ) in {
      val input = ScriptedInput(
        'a',
        EventMappingTest
          .key(KeyCode.Char('a'), kind = EventMappingTest.Release),
        CEvent.FocusGained(),
        CEvent.Paste("x y"),
        CEvent.Resize(50, 20),
        '\n',
      )
      inbox(16).map { box =>
        Sync
          .defer {
            val thread = InputThread(input, box, 5)
            thread.start()
            thread
          }
          .map { thread =>
            Kyo
              .fill(4)(Async.timeout(5.seconds)(box.take))
              .map { msgs =>
                Sync.defer {
                  thread.requestStop()
                  thread.join(5000)
                  val none = KeyboardEvent(_, Modifiers.none, Absent)
                  assert(
                    msgs.toSeq == Seq(
                      Msg.Key(none(Keyboard.Char('a')), 1L),
                      Msg.Paste("x y", 2L),
                      Msg.Resize(Size(50, 20), 3L),
                      Msg.Key(none(Keyboard.Enter), 4L),
                    ),
                    msgs.toString,
                  )
                  assert(!thread.isAlive && thread.failure == null)
                }
              }
          }
      }
    }

    "an input failure is kept and ends the run, also when the inbox is full"
      .timeout(30.seconds) in {
      val broken = new InputPort:
          def poll(timeoutMillis: Int): Boolean =
            throw java.io.IOException("tty gone")
          // claude 2026-10-19: changed: read gives a Maybe (P1)
          def read(): Maybe[CEvent]             = Absent
      inbox(1).map { box =>
        box
          .put(Msg.Repair)
          .andThen(Sync.defer {
            val thread = InputThread(broken, box, 5)
            thread.start()
            thread.join(5000) // it failed and ended while the inbox was full
            thread
          })
          .map { thread =>
            Abort
              .run[Closed | Timeout](
                box.take.andThen(Async.timeout(5.seconds)(box.take)),
              )
              .map {
                case Result.Success(Msg.Quit) =>
                  val f = thread.failure
                  assert(
                    f != null && f.getMessage == "tty gone",
                    String.valueOf(f),
                  )
                case other                    => assert(false, s"unexpected: $other")
              }
          }
      }
    }

    "a failure after the session is built closes the whole session".timeout(
      30.seconds,
    ) in {
      val steps   = java.util.concurrent.ConcurrentLinkedQueue[String]()
      val control = new tui4s.internal.TerminalControl:
          def enter(opts: TuiOptions): jatatui.core.backend.Backend =
              val _ = steps.add("enter")
              jatatui.core.backend.TestBackend(10, 2)
          override def step(name: String): Unit                     =
              val _ = steps.add(name)
          override def opened(release: () => Unit): Unit            =
            throw IllegalStateException("no hook")
          def leave(opts: TuiOptions): Unit                         =
              val _ = steps.add("leave")
      inbox(16).map { box =>
        Session
          .open(defaults, control, Some(ScriptedInput.never), box)
          .map { r =>
            val all = scala.jdk.CollectionConverters
              .IteratorHasAsScala(steps.iterator())
              .asScala
              .toVector
            assert(
              r match
                  case Left(TuiError.TerminalIO(_)) => true
                  case _                            => false
              ,
              r.toString,
            )
            assert(
              all == Seq(
                "enter",
                "input-started",
                "input-stopped",
                "input-joined",
                "leave",
              ),
              all.toString,
            )
          }
      }
    }
    // claude 2026-10-13: created (T1, the bot fix of the review thread
    // CountingBackend.scala:33 of PR 5): the shutdown hook can release while
    // the runner draws a frame
    "the release waits for a draw in progress, and a draw after the release writes nothing"
      .timeout(30.seconds) in {
      val log                  = java.util.concurrent.ConcurrentLinkedQueue[String]()
      val started              = java.util.concurrent.CountDownLatch(1)
      val go                   = java.util.concurrent.CountDownLatch(1)
      def add(s: String): Unit =
          val _ = log.add(s)
      // A draw that waits until the test lets it end.
      val slow                 = new Delegating(jatatui.core.backend.TestBackend(10, 2)):
          override def draw(
              content: java.lang.Iterable[jatatui.core.buffer.BufferUpdate],
          ): Unit =
              add("draw-start")
              started.countDown()
              val _ = go.await(10, java.util.concurrent.TimeUnit.SECONDS)
              super.draw(content)
              add("draw-end")
          end draw
      val control              = new tui4s.internal.TerminalControl:
          def enter(opts: TuiOptions): jatatui.core.backend.Backend = slow
          def leave(opts: TuiOptions): Unit                         = add("leave")
      val screen               = tui4s.internal.Rect(0, 0, 10, 2)
      val style                = jatatui.core.style.Style.empty()
      inbox(16).map { box =>
        Session.open(defaults, control, None, box).map {
          case Left(e)        => assert(false, s"open failed: $e")
          case Right(session) =>
            Sync
              .defer {
                import AllowUnsafe.embrace.danger
                val drawer = Thread { () =>
                  session.canvas.put(0, 0, "x", style, screen)
                  discard(session.canvas.flush(Vector(screen)))
                }
                drawer.start()
                val _      = started.await(10, java.util.concurrent.TimeUnit.SECONDS)
                // The release from another thread, as the shutdown hook does,
                // while the frame is drawn.
                val closer = Thread(() => session.close())
                closer.start()
                Thread.sleep(300)
                go.countDown()
                drawer.join(10000)
                closer.join(10000)
                // A frame after the release.
                session.canvas.put(1, 0, "y", style, screen)
                discard(session.canvas.flush(Vector(screen)))
                scala.jdk.CollectionConverters
                  .IteratorHasAsScala(log.iterator())
                  .asScala
                  .toVector
              }
              .map(events =>
                assert(
                  events == Seq("draw-start", "draw-end", "leave"),
                  events.mkString(", "),
                ),
              )
        }
      }
    }

    // claude 2026-10-13: created (T1, the bot fix of the review thread
    // Session.scala:150 of PR 4): the acquire fails after the input thread
    // started
    "an acquire that fails after the input thread started stops the thread"
      .timeout(30.seconds) in {
      val steps   = java.util.concurrent.ConcurrentLinkedQueue[String]()
      val polls   = java.util.concurrent.atomic.AtomicInteger(0)
      val port    = new InputPort:
          def poll(timeoutMillis: Int): Boolean =
              val _ = polls.incrementAndGet()
              Thread.sleep(timeoutMillis.max(1).toLong)
              false
          // claude 2026-10-19: changed: read gives a Maybe (P1)
          def read(): Maybe[CEvent]             = Absent
      val control = new tui4s.internal.TerminalControl:
          def enter(opts: TuiOptions): jatatui.core.backend.Backend =
              val _ = steps.add("enter")
              jatatui.core.backend.TestBackend(10, 2)
          override def step(name: String): Unit                     =
              val _ = steps.add(name)
              if name == "input-started" then
                  throw IllegalStateException("the step failed")
          def leave(opts: TuiOptions): Unit                         =
              val _ = steps.add("leave")
      inbox(16).map { box =>
        Session.open(defaults, control, Some(port), box).map { r =>
          Sync
            .defer {
              Thread.sleep(200)
              val before = polls.get
              Thread.sleep(300)
              (before, polls.get)
            }
            .map { (before, after) =>
              val all = scala.jdk.CollectionConverters
                .IteratorHasAsScala(steps.iterator())
                .asScala
                .toVector
              assert(
                r match
                    case Left(TuiError.TerminalIO(e)) =>
                      e.getMessage == "the step failed"
                    case _                            => false
                ,
                r.toString,
              )
              assert(
                after == before,
                s"the input thread still polls: $before polls, then $after",
              )
              assert(
                all == Seq(
                  "enter",
                  "input-started",
                  "input-stopped",
                  "input-joined",
                  "leave",
                ),
                all.toString,
              )
            }
        }
      }
    }

    // claude 2026-10-13: created (T1, review R2-4): an Error that NonFatal
    // does not match (a LinkageError of a JNI call) ended the thread and left
    // the run without keys
    "an input error that NonFatal does not match (UnsatisfiedLinkError) is kept and ends the run too"
      .timeout(30.seconds) in {
      val broken = new InputPort:
          def poll(timeoutMillis: Int): Boolean =
            throw UnsatisfiedLinkError(
              "'boolean tui.crossterm.CrosstermJni.poll(tui.crossterm.Duration)'",
            )
          // claude 2026-10-19: changed: read gives a Maybe (P1)
          def read(): Maybe[CEvent]             = Absent
      inbox(16).map { box =>
        Sync
          .defer {
            val thread = InputThread(broken, box, 5)
            thread.start()
            thread.join(5000)
            thread
          }
          .map { thread =>
            Abort
              .run[Closed | Timeout](Async.timeout(5.seconds)(box.take))
              .map {
                case Result.Success(Msg.Quit) =>
                  val f = thread.failure
                  assert(
                    f.isInstanceOf[UnsatisfiedLinkError],
                    String.valueOf(f),
                  )
                case other                    =>
                  assert(false, s"no Quit from the input thread: $other")
              }
          }
      }
    }

    // claude 2026-10-13: created (T1, review R2-1): a zero interval made the
    // input thread spin (crossterm's poll(0) returns at once)
    "a poll interval below 1 ms polls with 1 ms: the input thread does not spin"
      .timeout(30.seconds) in {
      import java.time.Duration as JDuration
      // claude 2026-10-19: changed: RunOptions checks the interval (A10)
      def ms(d: JDuration): Int =
        RunOptions.of(TuiOptions(pollInterval = d)).pollMillis
      val polls                 = java.util.concurrent.atomic.AtomicInteger(0)
      val shortest              =
        java.util.concurrent.atomic.AtomicInteger(Int.MaxValue)
      val port                  = new InputPort:
          def poll(timeoutMillis: Int): Boolean =
              val _ = polls.incrementAndGet()
              val _ = shortest.accumulateAndGet(timeoutMillis, math.min)
              Thread.sleep(timeoutMillis.max(0).toLong)
              false
          // claude 2026-10-19: changed: read gives a Maybe (P1)
          def read(): Maybe[CEvent]             = Absent
      // claude 2026-10-19: changed: Session.open takes RunOptions (A10)
      val opts                  =
        RunOptions.of(TuiOptions(pollInterval = JDuration.ZERO))
      inbox(16).map { box =>
        Session.open(opts, RecordingControl(10, 2), Some(port), box).map {
          case Left(e)        => assert(false, s"open failed: $e")
          case Right(session) =>
            Sync
              .defer {
                import AllowUnsafe.embrace.danger
                Thread.sleep(200)
                session.close()
                (polls.get, shortest.get)
              }
              .map { (n, least) =>
                val millis = Seq(
                  JDuration.ZERO,
                  JDuration.ofNanos(500_000),
                  JDuration.ofMillis(-5),
                  JDuration.ofMillis(25),
                  JDuration.ofDays(365L * 100),
                  JDuration.ofSeconds(Long.MaxValue),
                ).map(d =>
                  scala.util.Try(ms(d)).fold(e => e.toString, _.toString),
                )
                assert(
                  millis == Seq(
                    "1",
                    "1",
                    "1",
                    "25",
                    s"${Int.MaxValue}",
                    s"${Int.MaxValue}",
                  ),
                  millis.toString,
                )
                assert(least >= 1, s"a poll with $least ms")
                assert(n > 0 && n < 10_000, s"$n polls in 200 ms")
              }
        }
      }
    }

    private def discard(a: Any): Unit =
        val _ = a
end SessionTest
