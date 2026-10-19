package tui4s.use

import kyo.*

import java.util.concurrent.atomic.AtomicReference

/** Helpers for the tests on Kyo's controlled clock (`Clock.withTimeControl`).
  * The clock moves only when a test moves it. A test moves it only after a
  * fence: the composable has taken its step (a recorded [[Steps]] entry or a
  * value of a signal) and has armed its sleeps (`awaitPendingSleepers`). So
  * each time that a composable reads is exact, whatever the speed of the
  * scheduler. The waits for a fence are on the live clock, with a bound.
  */
// claude 2026-10-19: created (L2a); changed: fences in place of steps of the
// clock with wall-clock waits (L2a review)
object Controlled:

    /** Waits until at least `n` sleeps on the controlled clock are armed and
      * not done, at most 5 s of wall-clock time. True when they are.
      */
    def fence(ctl: Clock.TimeControl, n: Int)(using Frame): Boolean < Async =
      Clock.let(Clock.live)(
        Abort
          .run[Timeout](Async.timeout(5.seconds)(ctl.awaitPendingSleepers(n)))
          .map(_.isSuccess),
      )

    /** On the live clock: waits until `p` holds for the value of `s`, at most
      * `timeout` (a read every 5 ms). True when it holds.
      */
    def eventually[A](s: Signal[A], timeout: Duration = 5.seconds)(
        p: A => Boolean,
    )(using Frame): Boolean < Async =
      poll(timeout)(s.current.map(p))

    /** On the live clock: polls `cond` every 5 ms until it is true, at most
      * `timeout`. True when it is.
      */
    def poll(timeout: Duration)(cond: => Boolean < Sync)(using
        Frame,
    ): Boolean < Async =
      Clock.let(Clock.live)(
        Abort
          .run[Timeout](Async.timeout(timeout)(Loop.foreach {
            cond.map(ok =>
              if ok then Loop.done(true)
              else Async.sleep(5.millis).andThen(Loop.continue),
            )
          }))
          .map(_.getOrElse(false)),
      )
end Controlled

/** The steps of a pacing rule, with the controlled time of each: `traced` is
  * the rule, and it records each write and each wake-up before it steps. A test
  * waits for an entry to know that the shell has taken that step.
  */
// claude 2026-10-19: created (L2a review)
final class Steps[S, A](rule: Pacing.Rule[S, A]):
    private val log = AtomicReference(Chunk.empty[Steps.Entry[A]])

    private def record(e: Steps.Entry[A]): Unit =
      log.set(log.get.nn :+ e)

    /** The rule that records its steps. */
    val traced: Pacing.Rule[S, A] = Pacing.Rule(
      rule.start,
      (s, a, now) =>
          record(Steps.Entry.Write(a, now)); rule.write(s, a, now)
      ,
      (s, now) =>
          record(Steps.Entry.Wake(now)); rule.wake(s, now),
    )

    /** The steps so far. */
    def entries(using Frame): Chunk[Steps.Entry[A]] < Sync =
      Sync.defer(log.get.nn)

    /** Waits until there are `n` steps (at most 5 s); true when there are. */
    def await(n: Int)(using Frame): Boolean < Async =
      Controlled.poll(5.seconds)(entries.map(_.size >= n))
end Steps

object Steps:
    /** A step: a write of `value`, or a wake-up, at the time `at`. */
    enum Entry[+A] derives CanEqual:
        case Write(value: A, at: Instant)
        case Wake(at: Instant)
