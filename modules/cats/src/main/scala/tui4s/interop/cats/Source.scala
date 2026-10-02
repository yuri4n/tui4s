package tui4s.interop.cats

import _root_.cats.effect.kernel.Async
import _root_.fs2.concurrent.Signal
import _root_.kyo.<
import _root_.scala.concurrent.duration.FiniteDuration
import _root_.tui4s.interop.cats.internal.AmbientDispatcher
import _root_.tui4s.interop.cats.internal.CatsBridge
import _root_.tui4s.spi.Emitter
import _root_.tui4s.spi.Sources

/** Builds sources from Cats Effect values: an `F[A]` with `Async[F]`, an
  * `fs2.Stream[F, A]`, an `fs2.concurrent.Signal[F, A]`. A [[TuiApp]] has the
  * same constructors with `F = IO` and its state fixed as `source`; an app
  * built as a value writes `Source.For[IO, MyState]`.
  *
  * Identity: the call site where the source was built ("DashboardApp.scala:41")
  * or `.id(name)`; see `tui4s.Source`. Each `F` runs on the runtime that called
  * [[Tui.run]], with one bridge call for the whole life of the source. When the
  * app stops a source, its `F` is cancelled (signalled, not awaited) and a late
  * value is dropped.
  *
  * Every value waits for room in the app's inbox before the source goes on, so
  * a fast stream slows down to the speed of the app (backpressure), and no
  * value is lost.
  *
  * One `F` per app: use the `F` of the runner (`IO` in a [[TuiApp]]). A source
  * for another `F` fails at run time.
  */
object Source:
    /** Applies `f` every `period`; the first time after one period. */
    def every[S](period: FiniteDuration)(f: S => Next[S])(using
        CallSite,
    ): Source[S] =
      For.every(period)(f)

    /** Runs `task` now, then again `period` after each result (on the clock of
      * `F`), and folds each result with `f`.
      */
    def poll[F[_]: Async, S, A](period: FiniteDuration)(task: F[A])(
        f: (S, A) => Next[S],
    )(using CallSite): Source[S] =
      For[F, S].poll(period)(task)(f)

    /** Runs `task` once and folds its result with `f`. */
    def task[F[_]: Async, S, A](task: F[A])(f: (S, A) => Next[S])(using
        CallSite,
    ): Source[S] =
      For[F, S].task(task)(f)

    /** Folds every element of `stream` with `f`; one state change per chunk. */
    def stream[F[_]: Async, S, A](stream: _root_.fs2.Stream[F, A])(
        f: (S, A) => Next[S],
    )(using CallSite): Source[S] =
      For[F, S].stream(stream)(f)

    /** Folds the values of `signal` with `f`: its current value, then each
      * change (`signal.discrete`). While the app is busy, changes in between
      * are skipped; the latest value always arrives.
      */
    def signal[F[_]: Async, S, A](signal: Signal[F, A])(
        f: (S, A) => Next[S],
    )(using CallSite): Source[S] =
      For[F, S].signal(signal)(f)

    /** The constructors of [[Source]] with `F` and the state `S` fixed, so that
      * folds such as `_.sample(_)` infer. A [[TuiApp]] has one as `source`.
      */
    final class For[F[_], S] private[cats] ()(using F: Async[F]):
        /** Applies `f` every `period`; the first time after one period. */
        def every(period: FiniteDuration)(f: S => Next[S])(using
            CallSite,
        ): Source[S] =
          For.every(period)(f)

        /** Runs `task` now, then again `period` after each result (on the clock
          * of `F`), and folds each result with `f`.
          */
        def poll[A](period: FiniteDuration)(task: F[A])(
            f: (S, A) => Next[S],
        )(using cs: CallSite): Source[S] =
          Sources.makePeriodic[S, A](cs.show)(emit =>
            For.run[F](
              F.foreverM[Unit, Unit](
                F.productR(F.flatMap(task)(a => For.put(emit, a)))(
                  F.sleep(period),
                ),
              ),
            ),
          )(f)

        /** Runs `task` once and folds its result with `f`. */
        def task[A](task: F[A])(f: (S, A) => Next[S])(using
            cs: CallSite,
        ): Source[S] =
          Sources.makeTask[S, A](cs.show)(emit =>
            For.run[F](F.flatMap(task)(a => For.put(emit, a))),
          )(f)

        /** Folds every element of `stream` with `f`; one state change per
          * chunk.
          */
        def stream[A](stream: _root_.fs2.Stream[F, A])(f: (S, A) => Next[S])(
            using cs: CallSite,
        ): Source[S] =
          Sources.makeChunked[S, A](cs.show)(emit =>
            For.run[F](
              stream.chunks
                .evalMap(c => For.put(emit, c.toVector))
                .compile
                .drain,
            ),
          )(f)

        /** Folds the values of `signal` with `f`: its current value, then each
          * change (see [[Source.signal]]).
          */
        def signal[A](signal: Signal[F, A])(f: (S, A) => Next[S])(using
            cs: CallSite,
        ): Source[S] =
          Sources.make[S, A](cs.show)(emit =>
            For.run[F](
              signal.discrete.evalMap(a => For.put(emit, a)).compile.drain,
            ),
          )(f)
    end For

    /** Builds the constructors for one `F` and one state type. */
    object For:
        /** The constructors with `F` and `S` fixed. */
        def apply[F[_]: Async, S]: For[F, S] = new For[F, S]()

        /** `every` needs no `F`: the period runs on the Kyo clock. */
        private[cats] def every[S](period: FiniteDuration)(f: S => Next[S])(
            using cs: CallSite,
        ): Source[S] =
            val sleep = kyo.Duration.fromScala(period)
            Sources.makePeriodic[S, Unit](cs.show)(emit =>
              kyo.Loop.forever(kyo.Async.sleep(sleep).andThen(emit(()))),
            )((s, _) => f(s))

        /** Runs `fa` on the ambient Dispatcher (the runner's runtime) for the
          * life of the source fiber; an interrupt cancels it.
          */
        private def run[F[_]](fa: F[Unit])(using
            kyo.Frame,
        ): Unit < (kyo.Abort[Throwable] & kyo.Async) =
          AmbientDispatcher.use[F, Unit](d => CatsBridge.getF(fa)(using d))

        /** Sends `a` into the inbox; completes once it is in (or dropped
          * because the source stopped or the app ended). Cancelable: a
          * cancelled source does not wait for room.
          */
        private def put[F[_], A](emit: Emitter[A], a: A)(using
            F: Async[F],
        ): F[Unit] =
          F.async[Unit] { cb =>
            F.delay {
              import kyo.AllowUnsafe.embrace.danger
              emit.unsafe.put(a, () => cb(Right(())))
              Some(F.unit)
            }
          }
    end For
end Source
