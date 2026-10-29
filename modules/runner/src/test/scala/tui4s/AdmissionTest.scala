package tui4s

import kyo.*
import kyo.test.*
import tui4s.internal.{BackgroundAdmission, Loc, Msg}

/** Controlled admission races use promises, not timing assumptions. */
// codex 2026-10-23: created: capacity, wakeup, cancellation and ordering regressions.
class AdmissionTest extends Test[Any]:
    // codex 2026-10-23: created: every test closes channels, gates and child fibers.
    private def checked(
        body: => Unit < (Async & Scope & Abort[Closed]),
    )(using Frame): Unit < Async =
      Scope.run {
        Abort.run[Closed](body).map {
          case Result.Success(_) => ()
          case Result.Failure(e) => Abort.panic(e)
          case Result.Panic(e)   => Abort.panic(e)
        }
      }

    // codex 2026-10-23: created: the test scope owns the same close as a runner.
    private def open(
        inbox: Channel[Msg],
        beforeWait: Fiber[Unit, Any] => Unit < Async = _ => Kyo.unit,
    )(using Frame): BackgroundAdmission < (Sync & Scope) =
      Scope.acquireRelease(BackgroundAdmission.init(inbox, beforeWait))(_.close)

    // codex 2026-10-23: created: identify rejected producers without a sleep.
    private def notify(channel: Channel[Unit])(using
        Frame,
    ): Fiber[Unit, Any] => Unit < Async =
      _ => Abort.run[Closed](channel.put(())).unit

    // codex 2026-10-23: created: observe actual wait attachment before testing cancellation.
    private def attached(waiting: Fiber[Unit, Any])(using Frame): Unit < Async =
      Loop.foreach {
        waiting.waiters.map { count =>
          if count > 0 then Loop.done
          else Async.sleep(1.millis).andThen(Loop.continue)
        }
      }

    // codex 2026-10-23: created: a stable notice for FIFO assertions.
    private def notice(n: Long): Msg = Msg.Changed(Loc.root, n)

    // codex 2026-10-23: created: preserve the capacity-one progress exception.
    "the background limit leaves input space where the capacity permits" in {
      assert(BackgroundAdmission.limit(1) == 1)
      assert(BackgroundAdmission.limit(8) == 2)
      assert(BackgroundAdmission.limit(1024) == 64)
    }

    // codex 2026-10-23: created: concurrent writers cannot fill the input reservation; codex 2026-10-28: changed: observe the sole capacity holder while retaining all eight producers.
    "background pressure leaves room for a direct input batch"
      .timeout(5.seconds) in {
      checked {
        for
            inbox    <- Channel.init[Msg](8)
            blocked  <- Channel.init[Unit](8)
            gate     <- open(inbox, notify(blocked))
            _        <- Kyo.foreach(1L to 8L)(n => Fiber.init(gate.send(notice(n))))
            _        <- blocked.take
            size     <- inbox.size
            inputs    =
              Vector(Msg.Paste("a", 1L), Msg.Paste("b", 2L), Msg.Paste("c", 3L))
            _        <- inbox.putBatch(inputs)
            messages <- inbox.drainUpTo(8)
            _        <- gate.close
        yield
            assert(size == 2)
            assert(messages.size == 5)
            assert(messages.toVector.takeRight(3) == inputs)
      }
    }

    // codex 2026-10-23: created: no priority queue can move input ahead of admitted notices.
    "admitted notices and input remain in the shared FIFO"
      .timeout(5.seconds) in {
      checked {
        for
            inbox <- Channel.init[Msg](16)
            gate  <- open(inbox)
            _     <- gate.send(notice(1L))
            key    = Msg.Paste("x", 1L)
            _     <- inbox.put(key)
            _     <- gate.send(Msg.Armed(Loc.root, 2L))
            all   <- inbox.drainUpTo(16)
        yield assert(
          all.toVector == Vector(notice(1L), key, Msg.Armed(Loc.root, 2L)),
        )
      }
    }

    // codex 2026-10-23: created: force removal after rejection but before the promise wait.
    "a removal before the rejected writer starts waiting is not lost"
      .timeout(5.seconds) in {
      checked {
        for
            inbox    <- Channel.init[Msg](4)
            rejected <- Promise.init[Unit, Any]
            resume   <- Promise.init[Unit, Any]
            gate     <-
              open(inbox, _ => rejected.completeUnitDiscard.andThen(resume.get))
            _        <- gate.send(notice(1L))
            writer   <- Fiber.init(gate.send(notice(2L)))
            _        <- rejected.get
            first    <- inbox.take
            _        <- gate.released
            _        <- resume.completeUnitDiscard
            _        <- writer.get
            second   <- inbox.take
        yield assert(first == notice(1L) && second == notice(2L))
      }
    }

    // codex 2026-10-23: created: one canceled producer cannot cancel another producer's notification; codex 2026-10-28: changed: cancellation transfers the capacity-wait permit to the next sender.
    "canceling one rejected writer leaves another writer able to proceed"
      .timeout(5.seconds) in {
      checked {
        for
            inbox      <- Channel.init[Msg](4)
            blocked    <- Channel.init[Fiber[Unit, Any]](2)
            gate       <- open(
                            inbox,
                            waiting => Abort.run[Closed](blocked.put(waiting)).unit,
                          )
            _          <- gate.send(notice(1L))
            first      <- Fiber.init(gate.send(notice(2L)))
            firstWait  <- blocked.take
            _          <- attached(firstWait)
            second     <- Fiber.init(gate.send(notice(3L)))
            _          <- first.interrupt
            secondWait <- blocked.take
            _          <- attached(secondWait)
            _          <- inbox.take
            _          <- gate.released
            _          <- second.get
            all        <- inbox.drainUpTo(4)
        yield assert(all.toVector == Vector(notice(3L)))
      }
    }

    // codex 2026-10-23: created: close wakes capacity waiters without any consumer progress; codex 2026-10-28: changed: close also ends all senders queued behind the holder.
    "close ends rejected sends even without another inbox removal"
      .timeout(5.seconds) in {
      checked {
        for
            inbox   <- Channel.init[Msg](4)
            blocked <- Channel.init[Unit](1)
            started <- Channel.init[Unit](8)
            gate    <- open(inbox, notify(blocked))
            _       <- gate.send(notice(1L))
            writers <-
              Kyo.foreach(2L to 9L)(n =>
                Fiber.init(started.put(()).andThen(gate.send(notice(n)))),
              )
            _       <- started.takeExactly(8)
            _       <- blocked.take
            _       <- gate.close
            results <-
              Kyo.foreach(writers)(writer => Abort.run[Closed](writer.get))
            _       <- gate.close
            all     <- inbox.drainUpTo(4)
        yield
            assert(results.forall {
              case Result.Failure(_) => true
              case _                 => false
            })
            assert(all.toVector == Vector(notice(1L)))
      }
    }

    // codex 2026-10-28: created: closure between rejection and await must not strand the holder on a fresh generation.
    "close before the capacity holder starts waiting is not lost"
      .timeout(5.seconds) in {
      checked {
        for
            inbox    <- Channel.init[Msg](4)
            rejected <- Promise.init[Unit, Any]
            resume   <- Promise.init[Unit, Any]
            gate     <-
              open(inbox, _ => rejected.completeUnitDiscard.andThen(resume.get))
            _        <- gate.send(notice(1L))
            writer   <- Fiber.init(gate.send(notice(2L)))
            _        <- rejected.get
            _        <- gate.close
            _        <- resume.completeUnitDiscard
            result   <- Abort.run[Closed](writer.get)
            all      <- inbox.drainUpTo(4)
        yield
            assert(result match
                case Result.Failure(_) => true
                case _                 => false)
            assert(all.toVector == Vector(notice(1L)))
      }
    }

    // codex 2026-10-23: created: waiting does not capture an older value or execute the message effect.
    "a blocked message is evaluated only after capacity becomes available"
      .timeout(5.seconds) in {
      checked {
        for
            inbox   <- Channel.init[Msg](4)
            blocked <- Channel.init[Unit](1)
            gate    <- open(inbox, notify(blocked))
            value   <- Signal.initRef("old")
            reads   <- AtomicLong.init(0L)
            _       <- gate.send(notice(1L))
            writer  <- Fiber.init(
                         gate.send(
                           reads.incrementAndGet.andThen(
                             value.currentWith(v => Msg.Content(Loc.root, 2L, v)),
                           ),
                         ),
                       )
            _       <- blocked.take
            before  <- reads.get
            _       <- value.set("middle")
            _       <- value.set("latest")
            _       <- inbox.take
            _       <- gate.released
            _       <- writer.get
            message <- inbox.take
            after   <- reads.get
        yield
            assert(before == 0L && after == 1L)
            assert(message == Msg.Content(Loc.root, 2L, "latest"))
      }
    }

    // codex 2026-10-23: created: a one-slot inbox makes progress without a reserved input slot.
    "capacity one releases a blocked notice after consumption"
      .timeout(5.seconds) in {
      checked {
        for
            inbox   <- Channel.init[Msg](1)
            blocked <- Channel.init[Unit](1)
            gate    <- open(inbox, notify(blocked))
            _       <- gate.send(notice(1L))
            writer  <- Fiber.init(gate.send(notice(2L)))
            _       <- blocked.take
            first   <- inbox.take
            _       <- gate.released
            _       <- writer.get
            second  <- inbox.take
        yield assert(first == notice(1L) && second == notice(2L))
      }
    }

    // codex 2026-10-23: created: internal callers can still use a rendezvous inbox.
    "a zero-capacity inbox retains direct rendezvous admission"
      .timeout(5.seconds) in {
      checked {
        for
            inbox  <- Channel.init[Msg](0)
            gate   <- open(inbox)
            writer <- Fiber.init(gate.send(notice(1L)))
            value  <- inbox.take
            _      <- writer.get
        yield assert(value == notice(1L))
      }
    }
end AdmissionTest
