package tui4s.interop.zio.internal

import _root_.java.time.Duration
import _root_.tui4s.Next
import _root_.tui4s.Source
import _root_.tui4s.spi.Emitter
import _root_.tui4s.spi.Sources
import _root_.zio.Trace
import _root_.zio.UIO
import _root_.zio.ZIO
import _root_.zio.stream.SubscriptionRef
import _root_.zio.stream.ZStream

/** The facade sources, lowered to the core port `tui4s.spi.Sources` (SPEC 4.2).
  * Each body is one bridge call for the whole life of the source: one ZIO fiber
  * on the caller's runtime ([[AmbientZIOs.get]]), so it sees the caller's
  * FiberRefs, executor and clock. When the app stops the source, the Kyo fiber
  * is interrupted, and that interrupts the ZIO fiber.
  */
private[tui4s] object ZioSources:
    /** `f` every `period` on the caller's ZIO clock; the first time after one
      * period.
      */
    def every[S](label: String, period: Duration)(
        f: S => Next[S],
    ): Source[S] =
      Sources.makePeriodic[S, Unit](label)(emit =>
        AmbientZIOs.get((ZIO.sleep(period) *> put(emit, ())).forever),
      )((s, _) => f(s))

    /** `task` now, then again `period` after each result, on the caller's ZIO
      * clock.
      */
    def poll[S, A](
        label: String,
        period: Duration,
        task: ZIO[Any, Throwable, A],
    )(
        f: (S, A) => Next[S],
    )(using Trace): Source[S] =
      Sources.makePeriodic[S, A](label)(emit =>
        AmbientZIOs.get(
          (task.flatMap(a => put(emit, a)) *> ZIO.sleep(period)).forever,
        ),
      )(f)

    /** `task` once. */
    def task[S, A](label: String, task: ZIO[Any, Throwable, A])(
        f: (S, A) => Next[S],
    )(using Trace): Source[S] =
      Sources.makeTask[S, A](label)(emit =>
        AmbientZIOs.get(task.flatMap(a => put(emit, a))),
      )(f)

    /** Every element of `stream`, one state change per chunk. */
    def stream[S, A](label: String, stream: ZStream[Any, Throwable, A])(
        f: (S, A) => Next[S],
    )(using Trace): Source[S] =
      Sources.makeChunked[S, A](label)(emit =>
        AmbientZIOs.get(
          stream.runForeachChunk(c =>
            if c.isEmpty then ZIO.unit else put(emit, c),
          ),
        ),
      )(f)

    /** The current value of `ref`, then its changes. The subscription takes all
      * the values that were set since its last read at once, and only the
      * newest one is folded; a value equal to the last folded one is skipped.
      */
    def signal[S, A](label: String, ref: SubscriptionRef[A])(
        f: (S, A) => Next[S],
    )(using Trace): Source[S] =
      Sources.make[S, A](label)(emit =>
        AmbientZIOs.get(
          ref.changes.chunks
            .collect { case c if c.nonEmpty => c.last }
            .changes
            .runForeach(a => put(emit, a)),
        ),
      )(f)

    /** Puts `value` into the app's inbox; completes once it is there (or
      * dropped: the source stopped, the app ended). So inbox backpressure
      * reaches the ZIO or the ZStream.
      */
    def put[A](emit: Emitter[A], value: A)(using Trace): UIO[Unit] =
      ZIO.async[Any, Nothing, Unit] { cb =>
        import _root_.kyo.AllowUnsafe.embrace.danger
        emit.unsafe.put(value, () => cb(ZIO.unit))
      }
end ZioSources
