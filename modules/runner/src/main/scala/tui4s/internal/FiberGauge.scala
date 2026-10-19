package tui4s.internal

import kyo.*

/** The live-fiber gauge of one run: the fibers that the run started and that
  * have not ended. They are the watchers of the regions, the bodies of the hook
  * components, the `onMount` fibers, the repair tick, and the fibers of the
  * hooks ([[Tui4sTrack]]).
  *
  * While the run goes on, the gauge is `Open`: [[fork]] (or [[add]]) puts a
  * fiber in it, and a fiber leaves it when it ends. [[close]] (the end of the
  * run) sets the gauge to `Closed` one time and interrupts every fiber that it
  * held; it does not wait for them. A fiber that is added after the close is
  * interrupted at once. A closed gauge keeps the fibers that it interrupted
  * until they end, so that [[live]] counts them for the tests.
  *
  * The references are strong: a fiber that waits for ever (`Async.never`) and
  * that no region holds is still in the gauge, so the close interrupts it and
  * its `Scope` closes.
  */
// claude 2026-10-19: created (P2, amendment A7): one Kyo AtomicRef, not a
// synchronized weak set; not `Gauge`, a name of `kyo.*`
private[tui4s] final class FiberGauge private (
    state: AtomicRef[FiberGauge.State],
):
    import FiberGauge.State

    /** Adds `f`. On a closed gauge it also interrupts `f` (the run has ended).
      * The fiber leaves the gauge when it ends.
      */
    def add(f: Fiber[Any, Nothing])(using Frame): Unit < Sync =
      state
        .getAndUpdate(_.plus(f))
        .map {
          case State.Open(_)   => Kyo.unit
          case State.Closed(_) => f.interrupt.unit
        }
        .andThen(f.onComplete(_ => state.updateAndGet(_.minus(f)).unit))

    /** Starts `v` in a fiber that is not in a `Scope`, and adds the fiber to
      * the gauge. The spawn and the add are one bracket: Kyo registers the
      * release (the add) as the fiber arrives, with nothing schedulable between
      * them, so an interrupt of the caller cannot land between the spawn and
      * the add. Thus no fiber runs outside the gauge while [[close]] runs.
      */
    def fork(v: => Any < Async)(using Frame): Fiber[Any, Nothing] < Sync =
      Sync.acquireReleaseWith(Fiber.initUnscoped(v))(add)(f => f)

    /** The number of fibers in the gauge that have not ended. */
    def live(using Frame): Int < Sync =
      state.get.map(s =>
        Kyo.foldLeft(s.fibers.toSeq)(0)((n, f) =>
          f.done.map(d => if d then n else n + 1),
        ),
      )

    /** Closes the gauge (one time) and interrupts every fiber that it held. It
      * does not wait. A second close does nothing.
      */
    def close(using Frame): Unit < Sync =
      state.getAndUpdate(s => State.Closed(s.fibers)).map {
        case State.Open(fs)  => Kyo.foreachDiscard(fs.toSeq)(_.interrupt.unit)
        case State.Closed(_) => Kyo.unit
      }
end FiberGauge

private[tui4s] object FiberGauge:
    /** The state of the gauge: open with its fibers, or closed with the fibers
      * that the close interrupted and that have not ended yet.
      */
    enum State:
        case Open(fibers: Set[Fiber[Any, Nothing]])
        case Closed(fibers: Set[Fiber[Any, Nothing]])

        /** The fibers that the gauge holds. */
        def fibers: Set[Fiber[Any, Nothing]]

        def plus(f: Fiber[Any, Nothing]): State = this match
            case Open(fs)   => Open(fs + f)
            case Closed(fs) => Closed(fs + f)

        def minus(f: Fiber[Any, Nothing]): State = this match
            case Open(fs)   => Open(fs - f)
            case Closed(fs) => Closed(fs - f)
    end State

    def init(using Frame): FiberGauge < Sync =
      AtomicRef.init[State](State.Open(Set.empty)).map(new FiberGauge(_))
end FiberGauge
