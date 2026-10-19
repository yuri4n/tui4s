package tui4s.use

import kyo.*
import kyo.test.*

/** `useNow`, `useTimeAgo`, `debounced`, `throttled` and `useDebouncedFn` on
  * Kyo's controlled clock: the tests move the time, so they do not wait for it.
  * A fiber of a composable starts and reacts at wall-clock speed, so the tests
  * do not depend on the time when it reads its first value. A wait for a value
  * covers Kyo's repair interval of `Signal.observe` (1 s of the controlled
  * clock): a write that lands while the watch arms is seen at the next repair.
  * The leaves run one at a time. The laws of the timing rules are in
  * [[TimingPropertyTest]].
  */
// claude 2026-10-19: created (L2a)
class TimeTest extends Test[Any]:
    import Controlled.*
    import TimeAgo.Scale

    override def config: RunConfig = super.config.sequential

    "useNow starts at the time of the clock and ticks on each multiple of the period" in {
      Clock.withTimeControl { ctl =>
        Scope.run {
          for
              now   <- useNow(1.second)
              start <- now.current
              _     <- ctl.advance(400.millis)
              early <- now.current
              tick  <- advanceUntil(ctl, now, 100.millis)(_ > start)
              t1    <- now.current
          yield
              assert(start == Instant.Epoch)
              assert(early == Instant.Epoch)
              assert(tick.isDefined)
              // The tick is at 1 s; the fiber can read the clock a step late.
              assert(t1 >= Instant.Epoch + 1.second)
              assert(t1 < Instant.Epoch + 1500.millis)
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

    "useTimeAgo follows both signals and takes a formatter" in {
      for
          at   <- Signal.initRef(Instant.Epoch)
          now  <- Signal.initRef(Instant.Epoch + 2.minutes)
          text  = useTimeAgo(at, now)
          short = useTimeAgo(at, now, _.toString)
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

    "debounced: a burst shows nothing before the quiet time, then its last value" in {
      Clock.withTimeControl { ctl =>
        Scope.run {
          for
              src   <- Signal.initRef(0)
              out   <- debounced(src, 100.millis)
              _     <- src.set(1)
              _     <- src.set(2)
              _     <- src.set(3)
              early <- values(ctl, out, 10.millis, 5)
              last  <- advanceUntil(ctl, out, 20.millis)(_ == 3)
              after <- values(ctl, out, 50.millis, 4)
          yield
              assert(early.forall(_ == 0))
              assert(last.isDefined)
              assert(after.forall(_ == 3))
        }
      }
    }

    "throttled: a value shows at once; the values in its window wait for the end, and the newest wins" in {
      Clock.withTimeControl { ctl =>
        Scope.run {
          for
              src    <- Signal.initRef(0)
              out    <- throttled(src, 100.millis)
              // 1 shows at once or at the end of the window of the first
              // value: then a window is open.
              _      <- src.set(1)
              one    <- advanceUntil(ctl, out, 10.millis, 150)(_ == 1)
              t1     <- Clock.now
              _      <- src.set(2)
              _      <- src.set(3)
              inside <- values(ctl, out, 5.millis, 4)
              three  <- advanceUntil(ctl, out, 10.millis, 150)(_ == 3)
              t3     <- Clock.now
              // After a quiet window, a value shows at once.
              _      <- ctl.advance(300.millis)
              _      <- src.set(4)
              four   <- advanceUntil(ctl, out, 1.millis, 300)(_ == 4)
          yield
              assert(one.isDefined)
              assert(inside.forall(_ == 1))
              assert(three.isDefined)
              assert(t3 >= t1 + 90.millis)
              assert(four.exists(_ < 50))
        }
      }
    }

    "useDebouncedFn: a burst of calls runs the function once, with the last argument" in {
      Clock.withTimeControl { ctl =>
        Scope.run {
          for
              ran  <- Signal.initRef(Chunk.empty[String])
              fn   <- useDebouncedFn[String](100.millis)(q =>
                        ran.updateAndGet(_ :+ q).unit,
                      )
              _    <- fn("a")
              _    <- fn("ab")
              _    <- fn("abc")
              done <- advanceUntil(ctl, ran, 20.millis, 150)(_.nonEmpty)
              more <- values(ctl, ran, 50.millis, 4)
          yield
              assert(done.isDefined)
              assert(more.forall(_ == Chunk("abc")))
        }
      }
    }
end TimeTest
