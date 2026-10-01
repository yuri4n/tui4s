package tui4s

// `_root_`: this file is in package tui4s, whose package object exports dsl,
// and dsl's signatures mention Source. A plain `import kyo.*` would look
// `kyo` up in package tui4s first and can make an incremental compile cyclic.
import _root_.kyo.*
import _root_.tui4s.spi.Emitter

/** Background work that folds values into the state.
  *
  * A source is used in two ways: declared by `sources(state)` (standing work,
  * compared by label after each change), or started once by
  * `state.withTask(...)` (a one-shot task).
  *
  * Identity: the call site where the source was built
  * ("DashboardApp.scala:41"), or `.id(name)`. Two sources are equal when their
  * labels are equal. A source whose parameters change but whose label does not
  * keeps running with the old parameters: put changing parameters in the id.
  *
  * @param label
  *   the identity shown in tests and traces: "DashboardApp.scala:41", or the id
  */
final class Source[S] private[tui4s] (
    val label: String,
    private[tui4s] val body: Emitter[Any] => Unit < (Async & Abort[Throwable]),
    private[tui4s] val fold: (S, Any) => Next[S],
    private[tui4s] val recoverWith: ((S, Throwable) => Next[S]) | Null,
    private[tui4s] val kind: Source.Kind,
    private[tui4s] val keyed: Boolean,
):
    /** The same work with the identity `name`. A new id is a new source: the
      * runtime stops the old one. Put changing parameters in the id. For a
      * task: a task with an id interrupts the running task with the same id
      * (the latest wins).
      */
    def id(name: String): Source[S] =
      new Source(name, body, fold, recoverWith, kind, keyed = true)

    /** The same work; a failure folds into the state with `f` instead of ending
      * the app.
      */
    def recover(f: (S, Throwable) => Next[S]): Source[S] =
      new Source(label, body, fold, f, kind, keyed)

    override def equals(that: Any): Boolean = that match
        case s: Source[?] => s.label == label
        case _            => false

    override def hashCode: Int = label.hashCode

    override def toString: String = s"Source($label)"
end Source

/** Builds sources. Each constructor takes the call site as the identity.
  *
  * Kyo `Duration` here is `kyo.Duration` (`250.millis` with `import kyo.*`).
  * Streams for a UI should use `chunkSize = 1`: `Stream.unfold` and
  * `Stream.repeatPresent` buffer up to 4096 elements before they emit.
  */
object Source:
    /** What the runtime needs to know about a body: the live test kit counts
      * periodic sleepers, `TuiTest.emit` completes a task, a chunked fold folds
      * element by element.
      */
    private[tui4s] enum Kind derives CanEqual:
        /** Sleeps between values: `every`, `poll`, `Sources.makePeriodic`. */
        case Periodic

        /** Runs once: `task`, `Sources.makeTask`. */
        case Task

        /** Emits chunks; the fold takes one element: `stream`. */
        case Chunked

        /** Anything else: `signal`, `apply`, `Sources.make`. */
        case General
    end Kind

    /** The label of a call site: "File.scala:42". */
    private[tui4s] def labelOf(frame: Frame): String =
      s"${frame.position.fileName}:${frame.position.lineNumber}"

    /** A source with a body and a fold for each value (for a chunked kind: for
      * each element of an emitted chunk). The emitter is contravariant, so a
      * body for any `Emitter[E]` fits.
      */
    private[tui4s] def make[S, A](label: String, kind: Kind)(
        body: Emitter[Any] => Unit < (Async & Abort[Throwable]),
    )(fold: (S, A) => Next[S]): Source[S] =
      new Source[S](
        label,
        body,
        fold.asInstanceOf[(S, Any) => Next[S]],
        null,
        kind,
        keyed = false,
      )

    /** Applies `f` every `period`; the first time after one period. */
    def every[S](period: Duration)(f: S => Next[S])(using Frame): Source[S] =
      For[S].every(period)(f)

    /** Runs `task` now, then again `period` after each result, and folds each
      * result with `f`.
      */
    def poll[S, A](period: Duration)(task: => A < (Async & Abort[Throwable]))(
        using NotForeign[A],
    )(f: (S, A) => Next[S])(using Frame): Source[S] =
      For[S].poll(period)(task)(f)

    /** Runs `task` once and folds its result with `f`. */
    def task[S, A](task: => A < (Async & Abort[Throwable]))(using
        NotForeign[A],
    )(f: (S, A) => Next[S])(using Frame): Source[S] =
      For[S].task(task)(f)

    /** Folds every element of a Kyo stream with `f`; one state change per
      * chunk. The stream is created when the source starts.
      */
    def stream[S, A](stream: => Stream[A, Async & Abort[Throwable]])(
        f: (S, A) => Next[S],
    )(using Tag[Emit[Chunk[A]]], Frame): Source[S] =
      For[S].stream(stream)(f)

    /** Folds the changes of a Kyo `Signal` with `f`, starting with its current
      * value. Exact for a `SignalRef`: the last value always arrives. For other
      * signals a change between two reads shows at the next change.
      */
    def signal[S, A](signal: Signal[A])(f: (S, A) => Next[S])(using
        Frame,
    ): Source[S] =
      For[S].signal(signal)(f)

    /** The general form: a fiber body that sends state changes through a
      * [[Dispatch]] whenever it wants.
      */
    def apply[S](body: Dispatch[S] => Unit < (Async & Abort[Throwable]))(using
        Frame,
    ): Source[S] =
      For[S](body)

    /** The constructors of [[Source]] with `S` fixed, so that folds such as
      * `_.sample(_)` infer. An app built as a value writes
      * `Source.For[MyState]`.
      */
    final class For[S] private[tui4s] ():
        /** Applies `f` every `period`; the first time after one period. */
        def every(period: Duration)(f: S => Next[S])(using
            frame: Frame,
        ): Source[S] =
          make[S, Unit](labelOf(frame), Kind.Periodic)(emit =>
            Loop.forever(Async.sleep(period).andThen(emit(()))),
          )((s, _) => f(s))

        /** Runs `task` now, then again `period` after each result, and folds
          * each result with `f`.
          */
        def poll[A](period: Duration)(
            task: => A < (Async & Abort[Throwable]),
        )(using
            NotForeign[A],
        )(f: (S, A) => Next[S])(using
            frame: Frame,
        ): Source[S] =
          make[S, A](labelOf(frame), Kind.Periodic)(emit =>
            Loop.forever(task.map(a => emit(a)).andThen(Async.sleep(period))),
          )(f)

        /** Runs `task` once and folds its result with `f`. */
        def task[A](task: => A < (Async & Abort[Throwable]))(using
            NotForeign[A],
        )(f: (S, A) => Next[S])(using frame: Frame): Source[S] =
          make[S, A](labelOf(frame), Kind.Task)(emit => task.map(a => emit(a)))(
            f,
          )

        /** Folds every element of a Kyo stream with `f`; one state change per
          * chunk. The stream is created when the source starts.
          */
        def stream[A](stream: => Stream[A, Async & Abort[Throwable]])(
            f: (S, A) => Next[S],
        )(using tag: Tag[Emit[Chunk[A]]], frame: Frame): Source[S] =
          make[S, A](labelOf(frame), Kind.Chunked)(emit =>
            stream.foreachChunk(c => emit(c)),
          )(f)

        /** Folds the changes of a Kyo `Signal` with `f`, starting with its
          * current value (see [[Source.signal]]).
          */
        def signal[A](signal: Signal[A])(f: (S, A) => Next[S])(using
            frame: Frame,
        ): Source[S] =
          make[S, A](labelOf(frame), Kind.General)(emit =>
            tui4s.internal.Watch.signal(signal, emit),
          )(f)

        /** The general form: a fiber body that sends state changes through a
          * [[Dispatch]].
          */
        def apply(body: Dispatch[S] => Unit < (Async & Abort[Throwable]))(using
            frame: Frame,
        ): Source[S] =
          make[S, S => Next[S]](labelOf(frame), Kind.General)(emit =>
            body(new Dispatch[S](emit)),
          )((s, g) => g(s))
    end For

    /** The shared constructors for one state type. */
    object For:
        private val shared = new For[Any]

        /** The constructors with `S` fixed. One shared instance; no allocation. */
        def apply[S]: For[S] = shared.asInstanceOf[For[S]]
end Source

/** How a general source ([[Source.apply]]) sends state changes into the loop.
  * Thread-safe.
  */
final class Dispatch[S] private[tui4s] (emit: Emitter[S => Next[S]]):
    /** Sends the change `f`. Waits while the inbox is full (backpressure). The
      * change is dropped when this source stopped or the app ended.
      */
    def apply(f: S => Next[S])(using Frame): Unit < Async = emit(f)

/** Compile-time guard. Kyo lifts any plain value into `A < S`, so a `zio.ZIO`,
  * `cats.effect.IO`, `fs2.Stream`, `zio.stream.ZStream` or
  * `scala.concurrent.Future` given to `source.task` or `source.poll` would
  * compile and never run. The given rejects these types at compile time, with a
  * message that names the fix.
  *
  * The check runs when the rest of the call type-checks: with a fold of the
  * wrong type the compiler reports the fold first.
  */
sealed trait NotForeign[A]

/** The compile-time check of [[NotForeign]]. */
object NotForeign:
    /** The one instance. Public because the macro expansion is inlined into
      * user code.
      */
    object Evidence extends NotForeign[Any]

    /** Accepts `A`, or stops the compile when `A` is a foreign effect. */
    inline given [A]: NotForeign[A] = ${
      _root_.tui4s.internal.Macros.notForeign[A]
    }
end NotForeign

/** The source position of a call: the identity of the sources that the ZIO and
  * Cats Effect facades build, so that their signatures show no Kyo type. The
  * compiler fills it at the call site; users never write it.
  *
  * @param file
  *   the file name, without the directory
  * @param line
  *   the line, from 1
  */
final class CallSite private[tui4s] (val file: String, val line: Int):
    /** "File.scala:42". */
    def show: String = s"$file:$line"

    override def toString: String = show

    override def equals(that: Any): Boolean = that match
        case c: CallSite => c.file == file && c.line == line
        case _           => false

    override def hashCode: Int = file.hashCode * 31 + line
end CallSite

/** Fills a [[CallSite]] at the call site. */
object CallSite:
    /** The position of the code that needs a `CallSite`. */
    inline given here: CallSite =
      fromFrame(compiletime.summonInline[Frame])

    private[tui4s] def fromFrame(frame: Frame): CallSite =
      new CallSite(frame.position.fileName, frame.position.lineNumber)
