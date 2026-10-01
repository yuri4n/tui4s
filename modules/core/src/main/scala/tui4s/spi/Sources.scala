package tui4s.spi

import kyo.*
import tui4s.Next
import tui4s.Source
import tui4s.Source.Kind

/** The port that every facade source lowers to (for integrations: the ZIO and
  * Cats Effect facades). The core knows nothing about ZIO or Cats Effect: a
  * facade source is a fiber body that emits values, plus a fold.
  *
  * Each emitted value folds with the CURRENT fold of the source's identity: a
  * source that is declared again with a new fold uses the new one for its next
  * values. A value from a source that stopped is dropped.
  *
  * The kind of body matters to the test kits: `TuiTest.emit` completes a task,
  * and the live kit counts periodic sources as sleepers.
  */
object Sources:
    /** A source whose body emits values; each value folds with `fold`.
      *
      * @param label
      *   the identity (a call site "File.scala:42", or an id)
      */
    def make[S, A](label: String)(
        body: Emitter[A] => Unit < (Async & Abort[Throwable]),
    )(fold: (S, A) => Next[S])(using Frame): Source[S] =
      Source.make[S, A](label, Kind.General)(body)(fold)

    /** A source whose body emits chunks: one state change per chunk, with
      * `fold` applied to each element in order.
      */
    def makeChunked[S, A](label: String)(
        body: Emitter[Seq[A]] => Unit < (Async & Abort[Throwable]),
    )(fold: (S, A) => Next[S])(using Frame): Source[S] =
      Source.make[S, A](label, Kind.Chunked)(body)(fold)

    /** A one-shot body (a `task`): it emits once and ends. */
    def makeTask[S, A](label: String)(
        body: Emitter[A] => Unit < (Async & Abort[Throwable]),
    )(fold: (S, A) => Next[S])(using Frame): Source[S] =
      Source.make[S, A](label, Kind.Task)(body)(fold)

    /** A periodic body (`every`, `poll`): it sleeps between values. */
    def makePeriodic[S, A](label: String)(
        body: Emitter[A] => Unit < (Async & Abort[Throwable]),
    )(fold: (S, A) => Next[S])(using Frame): Source[S] =
      Source.make[S, A](label, Kind.Periodic)(body)(fold)
end Sources

/** Sends the values of one source into the app's inbox. Thread-safe. */
abstract class Emitter[-A]:
    /** Sends `value`. Waits while the inbox is full (backpressure). The value
      * is dropped when the source stopped or the app ended.
      */
    def apply(value: A)(using Frame): Unit < Async

    /** The callback form, for foreign runtimes. */
    def unsafe: Emitter.Unsafe[A]
end Emitter

/** The callback form of an [[Emitter]]. */
object Emitter:
    /** Sends values without a Kyo computation. */
    abstract class Unsafe[-A]:
        /** Sends `value` and never blocks. `accepted` runs once the value is in
          * the inbox, or when it is dropped (the source stopped, the app
          * ended). Use it as the callback of `ZIO.async` or `F.async_`, so that
          * inbox backpressure reaches the foreign stream.
          */
        def put(value: A, accepted: () => Unit)(using AllowUnsafe): Unit
end Emitter
