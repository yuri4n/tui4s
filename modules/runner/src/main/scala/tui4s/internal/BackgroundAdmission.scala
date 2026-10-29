package tui4s.internal

import kyo.*

/** Bounds background notices in the same inbox that carries input. */
// codex 2026-10-23: created: background backpressure before notice admission.
private[tui4s] sealed abstract class BackgroundAdmission:
    /** Waits for background capacity, then constructs and offers the notice. A
      * failed offer retries construction. Accepted notices retain inbox order.
      */
    // codex 2026-10-23: created: construct each notice after admission becomes possible.
    def send(message: => Msg < Sync)(using
        Frame,
    ): Unit < (Async & Abort[Closed])

    /** The runner calls this after each successful take or nonempty drain. */
    // codex 2026-10-23: created: wake all writers that observed the previous generation.
    def released(using Frame): Unit < Sync

    /** Ends blocked sends even when no further inbox removal can occur. */
    // codex 2026-10-23: created: one synchronous close owned by the run.
    def close(using Frame): Unit < Sync
end BackgroundAdmission

// codex 2026-10-23: created: allocate the admission state once per run.
private[tui4s] object BackgroundAdmission:
    /** The limit uses the actual channel capacity. A capacity of one keeps
      * progress but cannot reserve a separate slot for input.
      */
    // codex 2026-10-23: created: keep most inbox slots available to input.
    private[tui4s] def limit(capacity: Int): Int =
      (capacity / 4).min(64).max(1)

    /** `beforeWait` is a test seam after rejection and outside the mutex. It
      * receives the isolated waiter and has no work in a real run. The caller
      * owns [[BackgroundAdmission.close]]. A zero-capacity channel retains its
      * direct rendezvous behavior.
      */
    // codex 2026-10-23: created: only public Kyo primitives, with no worker fiber.
    def init(
        inbox: Channel[Msg],
        beforeWait: Fiber[Unit, Any] => Unit < Async = _ => Kyo.unit,
    )(using Frame): BackgroundAdmission < Sync =
      if inbox.capacity == 0 then direct(inbox)
      else
          for
              mutex      <- Meter.initMutexUnscoped(reentrant = false)
              first      <- Promise.init[Unit, Any]
              generation <- AtomicRef.init(first)
          yield new Bounded(
            inbox,
            limit(inbox.capacity),
            mutex,
            generation,
            beforeWait,
          )

    /** Retains the original Mount constructor's direct inbox behavior. */
    // codex 2026-10-23: created: compatibility path with no owned state.
    def direct(inbox: Channel[Msg]): BackgroundAdmission = new Direct(inbox)

    // codex 2026-10-23: created: direct admission for existing Mount callers.
    private final class Direct(inbox: Channel[Msg]) extends BackgroundAdmission:
        // codex 2026-10-23: created: preserve the previous put semantics.
        def send(message: => Msg < Sync)(using
            Frame,
        ): Unit < (Async & Abort[Closed]) =
          message.map(inbox.put)

        // codex 2026-10-23: created: direct puts use the channel's own wakeups.
        def released(using Frame): Unit < Sync = Kyo.unit

        // codex 2026-10-23: created: the direct adapter owns no resource.
        def close(using Frame): Unit < Sync = Kyo.unit
    end Direct

    // codex 2026-10-23: created: serialize background admission without holding a permit while waiting.
    private final class Bounded(
        inbox: Channel[Msg],
        maximum: Int,
        mutex: Meter,
        generation: AtomicRef[Promise[Unit, Any]],
        beforeWait: Fiber[Unit, Any] => Unit < Async,
    ) extends BackgroundAdmission:
        // codex 2026-10-23: created: snapshot before checking so a removal cannot be missed.
        def send(
            message: => Msg < Sync,
        )(using Frame): Unit < (Async & Abort[Closed]) =
          Loop.foreach {
            generation.get.map { changed =>
              mutex
                .run {
                  inbox.size.map { size =>
                    if size < maximum then message.map(inbox.offer)
                    else false
                  }
                }
                .map { accepted =>
                  if accepted then Loop.done
                  else
                      changed.uninterruptible.map { waiting =>
                        beforeWait(waiting)
                          .andThen(waiting.get)
                          .andThen(Loop.continue)
                      }
                }
            }
          }

        /** The bracket completes the old generation even if the notifying fiber
          * is interrupted after the swap. Each waiter joins a shielded copy, so
          * its cancellation cannot cancel this promise.
          */
        // codex 2026-10-23: created: close the swap-to-notification cancellation gap.
        def released(using Frame): Unit < Sync =
          Promise.init[Unit, Any].map { next =>
            Sync.acquireReleaseWith(generation.getAndUpdate(_ => next))(
              _.completeUnitDiscard,
            )(_ => ())
          }

        // codex 2026-10-23: created: close the meter before waking capacity waiters.
        def close(using Frame): Unit < Sync =
          Sync.acquireReleaseWith(mutex.close)(_ => released)(_ => ())
    end Bounded
end BackgroundAdmission
