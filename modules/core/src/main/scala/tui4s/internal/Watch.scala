package tui4s.internal

import kyo.*
import tui4s.spi.Emitter

/** The bodies of `Source.signal` (SPEC 5.8). */
private[tui4s] object Watch:
    /** Emits the current value of `sig`, then each change. */
    def signal[A](sig: Signal[A], emit: Emitter[A])(using
        Frame,
    ): Unit < Async =
      sig match
          case ref: Signal.SignalRef[A] @unchecked => exact(ref, emit)
          case other                               => loose(other, emit)

    /** A SignalRef: register for the next change FIRST, then read the current
      * value. A changing write sets the value before it completes the promise
      * of the next change, so a write after the registration completes the
      * promise (one more round) and a write before it is read. No value is lost
      * and no timer runs.
      */
    def exact[A](ref: Signal.SignalRef[A], emit: Emitter[A])(using
        Frame,
    ): Unit < Async =
      Loop(Maybe.empty[A]) { last =>
        Sync.Unsafe
          .defer {
            val next = ref.unsafe.next() // 1. register for the next change
            (next, ref.unsafe.get()) // 2. then read the current value
          }
          .map { (next, now) =>
            val sent =
              if last.exists(_.asInstanceOf[Any] == now) then Kyo.unit
              else emit(now)
            sent.andThen(next.safe.get).andThen(Loop.continue(Present(now)))
          }
      }

    /** Any other Signal: read, then wait for each change. A write between two
      * reads shows at the next write.
      */
    private def loose[A](sig: Signal[A], emit: Emitter[A])(using
        Frame,
    ): Unit < Async =
      sig.current
        .map(a => emit(a))
        .andThen(Loop.forever(sig.next.map(a => emit(a))))
end Watch
