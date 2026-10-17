package spike

import kyo.*

/** An arbitrary feature object returned by a setup function that groups
  * related composables. This is application code, not a new store runtime.
  */
final class CounterGroup(
    val count: SignalRef[Int],
    val title: SignalRef[String],
    val doubled: Signal[Int],
    val observations: SignalRef[Vector[Int]],
    val watcher: Fiber[Unit, Any],
):
    def increment: Unit < Sync = count.updateAndGet(_ + 1).unit
    def rename(next: String): Unit < Sync = title.set(next)
end CounterGroup

object CounterGroup:
    // These are renderer-independent composables: native Kyo effects,
    // references, and derived signals, with no tui4s Hooks dependency.
    private def useCounter: (SignalRef[Int], Signal[Int]) < Sync =
      Signal.initRef(0).map(count => (count, count.map(_ * 2)))

    private def useTitle: SignalRef[String] < Sync = Signal.initRef("Counter")

    /** The caller's Scope owns the watcher. Eagerly initialize this group
      * under the application's provider scope before handing it to children.
      */
    def setup(
        onSetup: => Unit < Sync,
        onClose: => Unit < Sync,
        onValue: Int => Unit < Async = _ => (),
    ): CounterGroup < (Async & Scope) =
      for
          _             <- onSetup
          _             <- Scope.ensure(onClose)
          counter       <- useCounter
          (count, twice)  = counter
          title         <- useTitle
          observations  <- Signal.initRef(Vector.empty[Int])
          ready         <- Promise.init[Unit, Any]
          watcher       <- Fiber.init(
                             count.observe(5.millis) { value =>
                               observations.updateAndGet(_ :+ value).unit
                                 .andThen(ready.completeUnitDiscard)
                                 .andThen(onValue(value))
                             },
                           )
          _             <- ready.get
      yield new CounterGroup(count, title, twice, observations, watcher)
end CounterGroup
