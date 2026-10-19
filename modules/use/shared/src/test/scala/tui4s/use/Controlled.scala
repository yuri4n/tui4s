package tui4s.use

import kyo.*

/** Helpers for the tests on Kyo's controlled clock (`Clock.withTimeControl`). A
  * fiber of a composable reacts on the scheduler at wall-clock speed, so a test
  * moves the controlled time in small steps and reads a signal after each step.
  */
// claude 2026-10-19: created (L2a)
object Controlled:

    /** Moves the controlled time by `step` until `p` holds for the value of
      * `s`, at most `max` steps. The result: the steps that it took, or Absent
      * when `p` never held.
      */
    def advanceUntil[A](
        ctl: Clock.TimeControl,
        s: Signal[A],
        step: Duration,
        max: Int = 100,
    )(p: A => Boolean)(using Frame): Maybe[Int] < Async =
      Loop(0) { i =>
        s.current.map { v =>
          if p(v) then Loop.done(Present(i))
          else if i >= max then Loop.done(Absent)
          else ctl.advance(step).andThen(Loop.continue(i + 1))
        }
      }

    /** Moves the controlled time by `step`, `n` times, and gives the value of
      * `s` after each step.
      */
    def values[A](ctl: Clock.TimeControl, s: Signal[A], step: Duration, n: Int)(
        using Frame,
    ): Chunk[A] < Async =
      Kyo.foreach(Chunk.range(0, n))(_ => ctl.advance(step).andThen(s.current))

    /** On the live clock: waits until `p` holds for the value of `s`, at most
      * `timeout` (a read every 5 ms). True when it holds.
      */
    def eventually[A](s: Signal[A], timeout: Duration = 5.seconds)(
        p: A => Boolean,
    )(using Frame): Boolean < Async =
      Clock
        .deadline(timeout)
        .map(deadline =>
          Loop.foreach {
            s.current.map(v =>
              if p(v) then Loop.done(true)
              else
                  deadline.isOverdue.map(over =>
                    if over then Loop.done(false)
                    else Async.sleep(5.millis).andThen(Loop.continue),
                  ),
            )
          },
        )
end Controlled
