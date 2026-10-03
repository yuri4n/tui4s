package tui4s.interop.zio.ui

import _root_.kyo.AllowUnsafe
import _root_.kyo.Fiber as KFiber
import _root_.kyo.Signal.SignalRef as KRef
import _root_.zio.UIO
import _root_.zio.ZIO
import _root_.zio.stream.ZStream
import scala.annotation.tailrec

/** A piece of state that kyo-ui can bind (`value`, `checked`, `render`, a text child), with the methods of a `zio.Ref`
  * and the `changes` of a `zio.stream.SubscriptionRef`.
  *
  * Its state IS the `kyo.SignalRef` cell that kyo-ui reads and writes. There is one source of truth: a handler reads
  * what the user just typed, and the screen shows what a handler just wrote. There is no copy and no pump fiber.
  *
  * It is not a `zio.Ref`: ZIO seals `Ref`, `Ref.Synchronized` and `SubscriptionRef`, so no type outside ZIO can extend
  * them. Each write is atomic (a compare-and-set loop on the cell), also against a write of kyo-ui (an input event).
  */
// claude 2026-10-03: created SignalRef, the single-cell ZIO ref of the facade (it replaces SubscriptionRef + 2 pumps)
final class SignalRef[A] private (private[ui] val kref: KRef[A]):
    private given AllowUnsafe = AllowUnsafe.embrace.danger
    private def cell          = kref.unsafe

    // claude 2026-10-03: created the Ref-like read and write methods, on the kyo-ui cell
    def get: UIO[A]                                      = ZIO.succeed(cell.get())
    def set(a: A): UIO[Unit]                             = ZIO.succeed(cell.set(a))
    def getAndSet(a: A): UIO[A]                          = ZIO.succeed(cell.getAndSet(a))
    def modify[B](f: A => (B, A)): UIO[B]                = ZIO.succeed(modifyNow(f))
    def update(f: A => A): UIO[Unit]                     = modify(a => ((), f(a)))
    def updateAndGet(f: A => A): UIO[A]                  = modify { a => val n = f(a); (n, n) }
    def getAndUpdate(f: A => A): UIO[A]                  = modify(a => (a, f(a)))
    def updateSome(pf: PartialFunction[A, A]): UIO[Unit] = update(a => pf.applyOrElse(a, identity[A]))

    /** A compare-and-set loop. The cell compares references, and `prev` is the reference just read. */
    // claude 2026-10-03: created the atomic modify (the same loop as the Cats KyoSignallingRef)
    @tailrec private def modifyNow[B](f: A => (B, A)): B =
        val prev      = cell.get()
        val (b, next) = f(prev)
        if cell.compareAndSet(prev, next) then b else modifyNow(f)

    /** The current value, then the value after each change. The wait for the next change is registered BEFORE the value
      * is read, so no change is lost. Changes that come faster than the consumer are merged: the stream gives the
      * latest value (a value can repeat). This is less than `SubscriptionRef.changes`, which gives every value.
      */
    // claude 2026-10-03: created changes, a ZStream of the kyo-ui cell (register-before-read, latest value)
    def changes: ZStream[Any, Nothing, A] =
        ZStream.unwrap(ZIO.succeed {
            val next = cell.next()
            (cell.get(), next)
        }.map { (first, next0) =>
            ZStream.succeed(first) ++ ZStream.unfoldZIO(next0) { next =>
                await(next) *> ZIO.succeed {
                    val after = cell.next()
                    Some((cell.get(), after))
                }
            }
        })

    /** Waits for one change. An interrupt leaves the callback on the Kyo promise until the next change (it is then a
      * no-op): the Kyo promise has no way to remove a callback.
      */
    // claude 2026-10-03: created the wait for the next change of the cell
    private def await(next: KFiber.Unsafe[A, Any]): UIO[Unit] =
        ZIO.async[Any, Nothing, Unit](cb => next.onComplete(_ => cb(ZIO.unit)))
end SignalRef

object SignalRef:
    // claude 2026-10-03: created the only constructor (useSignal calls it)
    private[ui] def unsafeMake[A](initial: A)(using CanEqual[A, A]): SignalRef[A] =
        new SignalRef(KRef.Unsafe.init(initial)(using AllowUnsafe.embrace.danger).safe)
end SignalRef
