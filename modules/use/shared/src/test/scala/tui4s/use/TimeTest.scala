package tui4s.use

import kyo.*
import kyo.test.*

/** `useNow`, `timeAgo`, `debounced`, `throttled` and `useDebouncedFn` on Kyo's
  * controlled clock. A test moves the clock only after a fence (see
  * [[Controlled]]): the composable has taken its step and armed its sleeps. A
  * test writes a source only after [[Controlled.awaitWatch]]: the watch has
  * subscribed. The tests then check exact times. The waits for a fence, a watch
  * or a value are on the live clock, with a bound (`Controlled.bound`). The
  * controlled clock does not move while they wait. The leaves run one at a
  * time. The laws of the timing rules are in [[TimingPropertyTest]].
  */
// claude 2026-10-19: created (L2a); changed: fences and exact times, not
// steps of the clock with wall-clock bounds (L2a review); cursor 2026-10-29: changed: a write waits until the watch has subscribed
class TimeTest extends Test[Any]:
    import Controlled.*
    import Steps.Entry.{Wake, Write}
    import TimeAgo.Scale

    override def config: RunConfig = super.config.sequential

    /** The instant `ms` milliseconds after the epoch. */
    private def at(ms: Int): Instant = Instant.Epoch + ms.millis

    /** The fence of a pacing shell: `n` steps, then `sleeps` armed sleeps. */
    private def step[S, A](ctl: Clock.TimeControl, steps: Steps[S, A])(
        n: Int,
        sleeps: Int,
    )(using Frame): Boolean < Async =
      steps.await(n).map(ok => fence(ctl, sleeps).map(ok && _))

    "useNow starts at the time of the clock and ticks on each multiple of the period" in {
      Clock.withTimeControl { ctl =>
        Scope.run {
          for
              now   <- useNow(1.second)
              start <- now.current
              f0    <- fence(ctl, 1)
              _     <- ctl.advance(400.millis)
              early <- now.current
              _     <- ctl.advance(600.millis)
              t1    <- eventually(now)(_ == at(1000))
              f1    <- fence(ctl, 1)
              _     <- ctl.advance(1.second)
              t2    <- eventually(now)(_ == at(2000))
          yield
              assert(start == Instant.Epoch && f0 && f1)
              assert(early == Instant.Epoch)
              assert(t1 && t2)
        }
      }
    }

    "the next tick is on a multiple of the period; a period under 1 ms is 1 ms" in {
      val t = Instant.Epoch + 1500.millis
      assert(Ticks.untilNext(t, 1.second) == 500.millis)
      assert(Ticks.untilNext(Instant.Epoch + 2.seconds, 1.second) == 1.second)
      assert(Ticks.untilNext(t, Duration.Zero) == 1.millis)
    }

    "TimeAgo.between: just now, the largest whole unit, past and future" in {
      val now             = Instant.Epoch + 400.days
      def at(d: Duration) = TimeAgo.between(now - d, now)
      assert(at(Duration.Zero) == TimeAgo.JustNow)
      assert(at(9.seconds) == TimeAgo.JustNow)
      assert(at(10.seconds) == TimeAgo.Ago(10, Scale.Second))
      assert(at(90.seconds) == TimeAgo.Ago(1, Scale.Minute))
      assert(at(2.hours) == TimeAgo.Ago(2, Scale.Hour))
      assert(at(3.days) == TimeAgo.Ago(3, Scale.Day))
      assert(at(15.days) == TimeAgo.Ago(2, Scale.Week))
      assert(at(61.days) == TimeAgo.Ago(2, Scale.Month))
      assert(at(366.days) == TimeAgo.Ago(1, Scale.Year))
      assert(
        TimeAgo.between(now + 3.minutes, now) == TimeAgo.In(3, Scale.Minute),
      )
    }

    "TimeAgo.english: singular, plural, past and future" in {
      assert(TimeAgo.english(TimeAgo.JustNow) == "just now")
      assert(TimeAgo.english(TimeAgo.Ago(1, Scale.Minute)) == "1 minute ago")
      assert(TimeAgo.english(TimeAgo.Ago(5, Scale.Second)) == "5 seconds ago")
      assert(TimeAgo.english(TimeAgo.In(2, Scale.Day)) == "in 2 days")
    }

    "timeAgo follows both signals and takes a formatter" in {
      for
          at   <- Signal.initRef(Instant.Epoch)
          now  <- Signal.initRef(Instant.Epoch + 2.minutes)
          text  = timeAgo(at, now)
          short = timeAgo(at, now, _.toString)
          t1   <- text.current
          _    <- now.set(Instant.Epoch + 3.hours)
          t2   <- text.current
          _    <- at.set(Instant.Epoch + 3.hours)
          t3   <- text.current
          s1   <- short.current
      yield
          assert(t1 == "2 minutes ago")
          assert(t2 == "3 hours ago")
          assert(t3 == "just now")
          assert(s1 == "JustNow")
    }

    "the shell of debounced: a burst shows only its last value, `quiet` after the last write" in {
      Clock.withTimeControl { ctl =>
        Scope.run {
          val steps = Steps(Pacing.debounce[Int](100.millis))
          for
              src   <- Signal.initRef(0)
              out   <- Pacing.run(src, steps.traced)
              // The value at the start waits; then the watch arms.
              // cursor 2026-10-29: changed: each write waits until the watch has subscribed
              f0    <- step(ctl, steps)(1, 2)
              w0    <- awaitWatch(src)
              _     <- ctl.advance(30.millis)
              _     <- src.set(1)
              f1    <- step(ctl, steps)(2, 2)
              w1    <- awaitWatch(src)
              _     <- ctl.advance(30.millis)
              _     <- src.set(2)
              f2    <- step(ctl, steps)(3, 2)
              w2    <- awaitWatch(src)
              _     <- ctl.advance(30.millis)
              _     <- src.set(3)
              f3    <- step(ctl, steps)(4, 2)
              _     <- ctl.advance(99.millis)
              early <- out.current
              _     <- ctl.advance(1.millis)
              shown <- eventually(out)(_ == 3)
              log   <- steps.entries
          yield
              assert(f0 && w0 && f1 && w1 && f2 && w2 && f3)
              assert(early == 0 && shown)
              assert(
                log == Chunk(
                  Write(0, at(0)),
                  Write(1, at(30)),
                  Write(2, at(60)),
                  Write(3, at(90)),
                  Wake(at(190)),
                ),
              )
          end for
        }
      }
    }

    "the shell of throttled: the values in a window wait for its end, the newest wins; after the window a value shows at once" in {
      Clock.withTimeControl { ctl =>
        Scope.run {
          val steps = Steps(Pacing.throttle[Int](100.millis))
          for
              src    <- Signal.initRef(0)
              out    <- Pacing.run(src, steps.traced)
              // The value at the start shows and opens a window to 100 ms.
              // cursor 2026-10-29: changed: each write waits until the watch has subscribed
              f0     <- step(ctl, steps)(1, 1)
              w0     <- awaitWatch(src)
              _      <- src.set(1)
              f1     <- step(ctl, steps)(2, 2)
              w1     <- awaitWatch(src)
              _      <- src.set(2)
              f2     <- step(ctl, steps)(3, 2)
              _      <- ctl.advance(99.millis)
              inside <- out.current
              _      <- ctl.advance(1.millis)
              two    <- eventually(out)(_ == 2)
              f3     <- step(ctl, steps)(4, 1)
              // The window of 2 ends at 200 ms with no value that waits.
              w3     <- awaitWatch(src)
              _      <- ctl.advance(100.millis)
              _      <- src.set(3)
              three  <- eventually(out)(_ == 3)
              log    <- steps.entries
          yield
              assert(f0 && w0 && f1 && w1 && f2 && f3 && w3)
              assert(inside == 0 && two && three)
              assert(
                log == Chunk(
                  Write(0, at(0)),
                  Write(1, at(0)),
                  Write(2, at(0)),
                  Wake(at(100)),
                  Write(3, at(200)),
                ),
              )
          end for
        }
      }
    }

    "debounced and throttled: a write shows `quiet` after it, or at once when no window is open" in {
      Clock.withTimeControl { ctl =>
        Scope.run {
          for
              src   <- Signal.initRef(0)
              deb   <- debounced(src, 100.millis)
              thr   <- throttled(src, 100.millis)
              // debounced: the wait of 0 and the watch; throttled: the watch.
              // cursor 2026-10-29: changed: the write waits until both watches have subscribed
              f0    <- fence(ctl, 3)
              w0    <- awaitWatch(src, 2)
              _     <- ctl.advance(100.millis)
              _     <- src.set(7)
              // 3 armed sleeps: debounced has seen 7.
              f1    <- fence(ctl, 3)
              now   <- eventually(thr)(_ == 7)
              _     <- ctl.advance(99.millis)
              early <- deb.current
              _     <- ctl.advance(1.millis)
              late  <- eventually(deb)(_ == 7)
          yield
              assert(f0 && w0 && f1)
              assert(now && early == 0 && late)
        }
      }
    }

    "useDebouncedFn: a burst of calls runs the function once, with the last argument, `quiet` after the last call" in {
      Clock.withTimeControl { ctl =>
        Scope.run {
          for
              ran   <- Signal.initRef(Chunk.empty[(String, Instant)])
              fn    <- useDebouncedFn[String](100.millis)(q =>
                         Clock.now.map(t => ran.updateAndGet(_ :+ ((q, t))).unit),
                       )
              _     <- fn("a")
              f1    <- fence(ctl, 1)
              _     <- ctl.advance(30.millis)
              _     <- fn("ab")
              f2    <- fence(ctl, 1)
              _     <- ctl.advance(30.millis)
              _     <- fn("abc")
              f3    <- fence(ctl, 1)
              _     <- ctl.advance(99.millis)
              early <- ran.current
              _     <- ctl.advance(1.millis)
              once  <- eventually(ran)(_.nonEmpty)
              _     <- ctl.advance(1.second)
              after <- ran.current
          yield
              assert(f1 && f2 && f3)
              assert(early.isEmpty && once)
              assert(after == Chunk(("abc", at(160))))
        }
      }
    }

    "useDebouncedFn: a call stops a run that has started at once" in {
      Clock.withTimeControl { ctl =>
        Scope.run {
          for
              gate    <- Promise.init[Unit, Any]
              started <- Signal.initRef(Chunk.empty[String])
              stopped <- Signal.initRef(Chunk.empty[String])
              fn      <- useDebouncedFn[String](100.millis)(q =>
                           started
                             .updateAndGet(_ :+ q)
                             .andThen(
                               Sync.ensure(stopped.updateAndGet(_ :+ q).unit)(
                                 gate.get,
                               ),
                             ),
                         )
              _       <- fn("a")
              f1      <- fence(ctl, 1)
              _       <- ctl.advance(100.millis)
              runs    <- eventually(started)(_ == Chunk("a"))
              // The clock does not move: the call stops the run of "a".
              _       <- fn("b")
              stop    <- eventually(stopped)(_ == Chunk("a"))
              f2      <- fence(ctl, 1)
              _       <- ctl.advance(100.millis)
              again   <- eventually(started)(_ == Chunk("a", "b"))
          yield assert(f1 && runs && stop && f2 && again)
        }
      }
    }
end TimeTest
