package tui4s.interop.zio.ui.internal

import _root_.kyo.<
import _root_.kyo.Abort
import _root_.kyo.AllowUnsafe
import _root_.kyo.Async
import _root_.kyo.Frame
import _root_.kyo.Signal as KSignal
import _root_.kyo.Signal.SignalRef as KRef
import _root_.zio.Scope
import _root_.zio.UIO
import _root_.zio.ZIO
import _root_.zio.stream.ZStream
import tui4s.interop.zio.ui.SignalRef

/** The ZIO side of the mirror: effect points and state.
  *
  * Effect points: a ZIO handler becomes a Kyo computation through [[AmbientZIOs]], so it runs on the runtime of the
  * fiber that mounted the UI (its FiberRefs, its executor), never on `Runtime.default`. kyo-ui runs a handler inline in
  * its dispatch, and [[AmbientZIOs.get]] waits for the end of the ZIO: when the dispatch returns, the handler is done.
  *
  * State: a [[SignalRef]] made by `useSignal` IS the `kyo.SignalRef` cell that kyo-ui binds. There is one source of
  * truth, like the Cats `KyoSignallingRef`.
  */
// claude 2026-10-03: changed the state part: one cell (SignalRef) instead of a SubscriptionRef, a Kyo ref, two pump
// fibers and a global registry. The two cells raced: a handler could read a stale draft (see the stress suite).
private[tui4s] object Bridge:

    // ---- effect points ----

    // claude 2026-10-03: changed action: no pull of all refs before the handler and no push after it (one cell now)
    def action(z: ZIO[Any, Nothing, Any])(using Frame): Any < Async =
        Abort.run[Nothing](AmbientZIOs.get(z)).map(_.getOrThrow)

    def handler[X](f: X => ZIO[Any, Nothing, Any])(using Frame): X => Any < Async =
        x => action(f(x))

    // ---- state ----

    // claude 2026-10-03: changed ref and signal: they read the cell of the SignalRef (no registry lookup, cannot fail)
    def ref[A](r: SignalRef[A]): KRef[A] = r.kref

    def signal[A](r: SignalRef[A]): KSignal[A] = r.kref

    // claude 2026-10-03: changed useSignal: it makes one cell and starts no fiber, so it needs no Scope
    def useSignal[A](initial: A)(using CanEqual[A, A]): UIO[SignalRef[A]] =
        ZIO.succeed(SignalRef.unsafeMake(initial))

    /** A read-only Kyo signal that follows a ZStream, for the lifetime of the scope. */
    def useStream[A](stream: ZStream[Any, Nothing, A], initial: A)(using CanEqual[A, A]): ZIO[Scope, Nothing, KSignal[A]] =
        for
            kref <- ZIO.succeed(KRef.Unsafe.init(initial)(using AllowUnsafe.embrace.danger).safe)
            _    <- stream.foreach(a => ZIO.succeed(kref.unsafe.set(a)(using AllowUnsafe.embrace.danger))).forkScoped
        yield kref
end Bridge
