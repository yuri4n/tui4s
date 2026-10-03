package tui4s.interop.zio.ui.internal

import _root_.java.util.concurrent.ConcurrentHashMap
import _root_.kyo.<
import _root_.kyo.Abort
import _root_.kyo.AllowUnsafe
import _root_.kyo.Async
import _root_.kyo.Frame
import _root_.kyo.Signal as KSignal
import _root_.kyo.Signal.SignalRef as KRef
import _root_.zio.Ref
import _root_.zio.Scope
import _root_.zio.UIO
import _root_.zio.ZIO
import _root_.zio.stream.SubscriptionRef
import _root_.zio.stream.ZStream

/** The ZIO side of the mirror: effect points and state.
  *
  * Effect points: a ZIO handler becomes a Kyo computation through [[AmbientZIOs]], so it runs on the runtime of the
  * fiber that mounted the UI (its FiberRefs, its executor), never on `Runtime.default`.
  *
  * State: kyo-ui needs a `kyo.SignalRef` (it re-renders on its changes and WRITES it on input), the user holds a
  * `zio.stream.SubscriptionRef` (sealed: it cannot be backed by the Kyo ref). So each ref made by `useSignal` is a
  * [[Binding]] of two refs, kept equal by:
  *   - a ZIO -> Kyo pump fiber (`sub.changes`), which skips stale values;
  *   - a Kyo -> ZIO pump fiber (`kref.next`), which copies only values that kyo-ui wrote;
  *   - a synchronous pull before and push after every handler, so a handler reads what the user just typed, and the
  *     screen after an event shows what the handler wrote (no lag in the common case).
  * Both fibers are scoped to the component: they stop when the UI is unmounted.
  */
private[tui4s] object Bridge:

    // ---- effect points ----

    def action(z: ZIO[Any, Nothing, Any])(using Frame): Any < Async =
        Abort.run[Nothing](AmbientZIOs.get(Registry.pullAll *> z <* Registry.pushAll)).map(_.getOrThrow)

    def handler[X](f: X => ZIO[Any, Nothing, Any])(using Frame): X => Any < Async =
        x => action(f(x))

    // ---- state ----

    def ref[A](sub: SubscriptionRef[A]): KRef[A] = Registry.binding(sub).kref

    def signal[A](sub: SubscriptionRef[A]): KSignal[A] = ref(sub)

    def useSignal[A](initial: A)(using CanEqual[A, A]): ZIO[Scope, Nothing, SubscriptionRef[A]] =
        for
            sub  <- SubscriptionRef.make(initial)
            last <- Ref.make(initial)
            kref <- ZIO.succeed(KRef.Unsafe.init(initial)(using AllowUnsafe.embrace.danger).safe)
            binding = Binding(sub, kref, last)
            _ <- ZIO.acquireRelease(ZIO.succeed(Registry.add(binding)))(_ => ZIO.succeed(Registry.remove(binding)))
            _ <- sub.changes.foreach(binding.zioToKyo).forkScoped
            _ <- binding.kyoToZioLoop.forkScoped
        yield sub

    /** A read-only Kyo signal that follows a ZStream, for the lifetime of the scope. */
    def useStream[A](stream: ZStream[Any, Nothing, A], initial: A)(using CanEqual[A, A]): ZIO[Scope, Nothing, KSignal[A]] =
        for
            kref <- ZIO.succeed(KRef.Unsafe.init(initial)(using AllowUnsafe.embrace.danger).safe)
            _    <- stream.foreach(a => ZIO.succeed(kref.unsafe.set(a)(using AllowUnsafe.embrace.danger))).forkScoped
        yield kref

    /** A SubscriptionRef and the kyo SignalRef that kyo-ui uses for it. `last` is the last value that the ZIO side
      * gave to the Kyo side: a Kyo value that differs from it was written by kyo-ui (an input, a checkbox).
      */
    final class Binding[A](val sub: SubscriptionRef[A], val kref: KRef[A], last: Ref[A])(using CanEqual[A, A]):
        private given AllowUnsafe = AllowUnsafe.embrace.danger

        /** ZIO -> Kyo for one value of `sub.changes`; a value that `sub` no longer holds is stale and skipped. */
        def zioToKyo(a: A): UIO[Unit] =
            sub.get.flatMap(cur => ZIO.when(cur == a)(last.set(a) *> ZIO.succeed(kref.unsafe.set(a))).unit)

        /** Kyo -> ZIO: copies the Kyo value when kyo-ui wrote it. */
        def pull: UIO[Unit] =
            for
                k <- ZIO.succeed(kref.unsafe.get())
                l <- last.get
                _ <- ZIO.when(k != l)(last.set(k) *> sub.set(k))
            yield ()

        /** ZIO -> Kyo, now. */
        def push: UIO[Unit] = sub.get.flatMap(s => last.set(s) *> ZIO.succeed(kref.unsafe.set(s)))

        /** Waits for every Kyo change. The next change is registered BEFORE the value is read, so none is lost. */
        def kyoToZioLoop: UIO[Nothing] =
            ZIO.succeed(kref.unsafe.next()).flatMap { next =>
                pull *> ZIO.async[Any, Nothing, Unit](cb => next.onComplete(_ => cb(ZIO.unit)))
            }.forever
    end Binding

    /** The bindings of the mounted refs, by SubscriptionRef identity. The only global mutable state of the facade: the
      * mirror's setters are pure functions that get a SubscriptionRef and must find its Kyo ref.
      */
    private object Registry:
        private val bindings = new ConcurrentHashMap[AnyRef, Binding[?]]()

        def add(b: Binding[?]): Unit    = discard(bindings.put(b.sub, b))
        def remove(b: Binding[?]): Unit = discard(bindings.remove(b.sub, b))

        def binding[A](sub: SubscriptionRef[A]): Binding[A] =
            Option(bindings.get(sub)).map(_.asInstanceOf[Binding[A]]).getOrElse(
                throw new IllegalArgumentException(
                    "this SubscriptionRef was not made by tui4s useSignal (or its component is unmounted); " +
                        "kyo-ui can only bind refs made by useSignal"
                )
            )

        private def all: List[Binding[?]] =
            import scala.jdk.CollectionConverters.*
            bindings.values.asScala.toList

        def pullAll: UIO[Unit] = ZIO.suspendSucceed(ZIO.foreachDiscard(all)(_.pull))
        def pushAll: UIO[Unit] = ZIO.suspendSucceed(ZIO.foreachDiscard(all)(_.push))
    end Registry
end Bridge
