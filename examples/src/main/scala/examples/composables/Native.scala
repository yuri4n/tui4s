package examples.composables

import kyo.*

/** Five things that need no composable: each one is one or two lines of native
  * Kyo (README "You may not need a composable"). A library with no main; its
  * suite runs each one.
  */
// claude 2026-10-19: created (L2a)
object Native:

    // region: native
    /** A toggle: `updateAndGet` on a `SignalRef`. */
    def toggle(on: SignalRef[Boolean])(using Frame): Unit < Sync =
      on.updateAndGet(!_).unit

    /** A counter that stops at `max`. */
    def increment(count: SignalRef[Int], max: Int)(using Frame): Unit < Sync =
      count.updateAndGet(n => (n + 1).min(max)).unit

    /** An interval: a fiber that the caller's `Scope` ends. */
    def every(period: Duration)(action: => Any < Async)(using
        Frame,
    ): Unit < (Sync & Scope) =
      Fiber.init(Loop.forever(Async.sleep(period).andThen(action))).unit

    /** A timer that pauses: `Signal.observe` starts the ticks while `paused` is
      * false, and interrupts them when it changes.
      */
    def timer(paused: Signal[Boolean], ticks: SignalRef[Int], period: Duration)(
        using Frame,
    ): Unit < (Sync & Scope) =
      Fiber
        .init(
          paused.observe(p =>
            if p then Kyo.unit
            else every(period)(ticks.updateAndGet(_ + 1)),
          ),
        )
        .unit

    /** A derived value: `Signal.map`. */
    def label(count: Signal[Int])(using Frame): Signal[String] =
      count.map(n => s"$n items")
    // endregion: native
end Native
