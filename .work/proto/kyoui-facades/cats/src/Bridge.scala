package tui4s.interop.cats.ui.internal

import _root_.cats.data.State
import _root_.cats.effect.kernel.Async as CEAsync
import _root_.cats.effect.kernel.Resource
import _root_.cats.effect.syntax.all.*
import _root_.fs2.Stream as FStream
import _root_.fs2.concurrent.SignallingRef
import _root_.kyo.<
import _root_.kyo.Abort
import _root_.kyo.AllowUnsafe
import _root_.kyo.Async
import _root_.kyo.Fiber
import _root_.kyo.Frame
import _root_.kyo.Signal as KSignal
import _root_.kyo.Signal.SignalRef as KRef
import scala.annotation.tailrec

/** The Cats Effect side of the mirror: effect points and state.
  *
  * Effect points: an `F[Unit]` handler becomes a Kyo computation through the ambient `Dispatcher[F]` (a Kyo `Local`
  * that the runner sets), so it runs on the runtime of the caller of the runner, never on a global one.
  *
  * State: fs2's `SignallingRef` is an open abstract class, so [[KyoSignallingRef]] IMPLEMENTS it on top of the
  * `kyo.SignalRef` that kyo-ui uses: one source of truth, no pump fiber, no lag, nothing to keep in sync.
  */
private[tui4s] object Bridge:

    // ---- effect points ----

    def action[F[_]](fa: F[Unit])(using Frame): Any < Async =
        Abort.run[Throwable](AmbientDispatcher.use[F, Unit](d => CatsBridge.getF(fa)(using d, summon[Frame])))
            .map(_.getOrThrow)

    def handler[F[_], X](f: X => F[Unit])(using Frame): X => Any < Async =
        x => action(f(x))

    // ---- state ----

    def ref[F[_], A](r: SignallingRef[F, A]): KRef[A] = r match
        case k: KyoSignallingRef[?, ?] => k.kref.asInstanceOf[KRef[A]]
        case _ =>
            throw new IllegalArgumentException(
                "this SignallingRef was not made by tui4s useSignal; kyo-ui can only bind refs made by useSignal"
            )

    def signal[F[_], A](r: SignallingRef[F, A]): KSignal[A] = ref(r)

    def useSignal[F[_], A](initial: A)(using F: CEAsync[F], eq: CanEqual[A, A]): Resource[F, SignallingRef[F, A]] =
        Resource.eval(F.delay(new KyoSignallingRef[F, A](KRef.Unsafe.init(initial)(using AllowUnsafe.embrace.danger).safe)))

    def useStream[F[_], A](stream: FStream[F, A], initial: A)(using
        F: CEAsync[F],
        eq: CanEqual[A, A]
    ): Resource[F, KSignal[A]] =
        for
            kref <- Resource.eval(F.delay(KRef.Unsafe.init(initial)(using AllowUnsafe.embrace.danger).safe))
            _    <- stream.foreach(a => F.delay(kref.unsafe.set(a)(using AllowUnsafe.embrace.danger))).compile.drain.background
        yield kref

    /** A `SignallingRef` whose state IS a `kyo.SignalRef`: kyo-ui and the Cats code read and write the same cell. */
    final class KyoSignallingRef[F[_], A](val kref: KRef[A])(using F: CEAsync[F]) extends SignallingRef[F, A]:
        private given AllowUnsafe = AllowUnsafe.embrace.danger
        private def cell          = kref.unsafe

        def get: F[A]          = F.delay(cell.get())
        def set(a: A): F[Unit] = F.delay(cell.set(a))

        def modify[B](f: A => (A, B)): F[B] = F.delay(modifyNow(f))

        /** A compare-and-set loop; `compareAndSet` compares references, and `prev` is the reference just read. */
        @tailrec private def modifyNow[B](f: A => (A, B)): B =
            val prev      = cell.get()
            val (next, b) = f(prev)
            if cell.compareAndSet(prev, next) then b else modifyNow(f)

        def update(f: A => A): F[Unit] = modify(a => (f(a), ()))

        def tryUpdate(f: A => A): F[Boolean] = F.delay {
            val prev = cell.get()
            cell.compareAndSet(prev, f(prev))
        }

        def tryModify[B](f: A => (A, B)): F[Option[B]] = F.delay {
            val prev      = cell.get()
            val (next, b) = f(prev)
            Option.when(cell.compareAndSet(prev, next))(b)
        }

        def access: F[(A, A => F[Boolean])] = F.delay {
            val snapshot = cell.get()
            (snapshot, (a: A) => F.delay(cell.compareAndSet(snapshot, a)))
        }

        def tryModifyState[B](state: State[A, B]): F[Option[B]] = tryModify(a => state.run(a).value)
        def modifyState[B](state: State[A, B]): F[B]            = modify(a => state.run(a).value)

        /** The current value, then every change. The next change is registered BEFORE the value is read: none is lost
          * (a value can repeat, which `discrete` allows).
          */
        def discrete: FStream[F, A] =
            FStream.eval(F.delay((cell.next(), cell.get()))).flatMap { (next, value) =>
                FStream.emit(value) ++ FStream.exec(await(next)) ++ discrete
            }

        def continuous: FStream[F, A] = FStream.repeatEval(get)

        private def await(next: Fiber.Unsafe[A, Any]): F[Unit] =
            F.async_[Unit](cb => next.onComplete(_ => cb(Right(()))))
    end KyoSignallingRef
end Bridge
