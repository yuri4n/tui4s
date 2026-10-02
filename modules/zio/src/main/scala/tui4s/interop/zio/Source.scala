package tui4s.interop.zio

import _root_.tui4s.interop.zio.internal.ZioSources
import _root_.zio.Duration
import _root_.zio.Trace
import _root_.zio.ZIO
import _root_.zio.stream.SubscriptionRef
import _root_.zio.stream.ZStream

/** Builds sources from ZIO effects and streams: background work that folds
  * values into the state. Declare them in `sources(state)` (standing work,
  * compared by label after each change) or start them once with
  * `state.withTask(...)`.
  *
  * Each source runs its ZIO on the runtime of the fiber that runs the app
  * (`Tui.run`, `Tui.start`, `TuiApp`): it sees that fiber's FiberRefs (log
  * annotations, configuration), its executor and its clock, so a zio-test
  * `TestClock` drives `every` and `poll`. When the app stops a source (its
  * label is no longer declared, or the app ends), the ZIO is interrupted.
  *
  * Identity: the call site where the source was built
  * ("DashboardApp.scala:41"), or `.id(name)`. A source whose parameters change
  * but whose label does not keeps running with the old ones: put changing
  * parameters in the id.
  *
  * Folds run on the app's render fiber: keep them pure and fast.
  */
object Source:
    /** Applies `f` every `period` (on the ZIO clock); the first time after one
      * period.
      */
    def every[S](period: Duration)(f: S => Next[S])(using CallSite): Source[S] =
      For[S].every(period)(f)

    /** Runs `task` now, then again `period` after each result (on the ZIO
      * clock), and folds each result with `f`. A failure of `task` ends the app
      * with `TuiError.SourceFailed`, unless the source has `.recover`.
      */
    def poll[S, A](period: Duration)(task: ZIO[Any, Throwable, A])(
        f: (S, A) => Next[S],
    )(using CallSite, Trace): Source[S] =
      For[S].poll(period)(task)(f)

    /** Runs `task` once and folds its result with `f`. */
    def task[S, A](task: ZIO[Any, Throwable, A])(f: (S, A) => Next[S])(using
        CallSite,
        Trace,
    ): Source[S] =
      For[S].task(task)(f)

    /** Folds every element of `stream` with `f`, one state change per chunk.
      * The app's inbox applies backpressure to the stream: the next chunk is
      * pulled once the last one is in the inbox.
      */
    def stream[S, A](
        stream: ZStream[Any, Throwable, A],
    )(f: (S, A) => Next[S])(using
        CallSite,
        Trace,
    ): Source[S] =
      For[S].stream(stream)(f)

    /** Folds the current value of `ref`, then its changes, with `f`. When the
      * app is slower than the writes, it folds the newest value (the last value
      * always arrives); a value equal to the last folded one is skipped.
      */
    def signal[S, A](ref: SubscriptionRef[A])(f: (S, A) => Next[S])(using
        CallSite,
        Trace,
    ): Source[S] =
      For[S].signal(ref)(f)

    /** The constructors of [[Source]] with `S` fixed, so that folds such as
      * `_.sample(_)` infer. `TuiApp[S]` has one as `source`; an app built as a
      * value writes `Source.For[MyState]`.
      */
    final class For[S] private[zio] ():
        /** Applies `f` every `period` (on the ZIO clock); the first time after
          * one period.
          */
        def every(period: Duration)(f: S => Next[S])(using
            site: CallSite,
        ): Source[S] =
          ZioSources.every(site.show, period)(f)

        /** Runs `task` now, then again `period` after each result (on the ZIO
          * clock), and folds each result with `f`.
          */
        def poll[A](period: Duration)(task: ZIO[Any, Throwable, A])(
            f: (S, A) => Next[S],
        )(using site: CallSite, trace: Trace): Source[S] =
          ZioSources.poll(site.show, period, task)(f)

        /** Runs `task` once and folds its result with `f`. */
        def task[A](task: ZIO[Any, Throwable, A])(f: (S, A) => Next[S])(using
            site: CallSite,
            trace: Trace,
        ): Source[S] =
          ZioSources.task(site.show, task)(f)

        /** Folds every element of `stream` with `f`, one state change per
          * chunk, with backpressure from the app's inbox.
          */
        def stream[A](
            stream: ZStream[Any, Throwable, A],
        )(f: (S, A) => Next[S])(using
            site: CallSite,
            trace: Trace,
        ): Source[S] =
          ZioSources.stream(site.show, stream)(f)

        /** Folds the current value of `ref`, then its changes (the newest one
          * when the app is slower than the writes), with `f`.
          */
        def signal[A](ref: SubscriptionRef[A])(f: (S, A) => Next[S])(using
            site: CallSite,
            trace: Trace,
        ): Source[S] =
          ZioSources.signal(site.show, ref)(f)
    end For

    /** The shared constructors for one state type. */
    object For:
        private val shared = new For[Any]

        /** The constructors with `S` fixed (one shared instance). */
        def apply[S]: For[S] = shared.asInstanceOf[For[S]]
end Source
