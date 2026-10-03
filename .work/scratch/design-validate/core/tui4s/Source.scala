package tui4s

import kyo.*
import tui4s.spi.Emitter

/** Background work that folds values into the state. Used two ways: declared by `sources(state)` (standing, diffed
  * by identity), or started once by `state.withTask(...)` (one-shot). Identity: the call site, or `.id(name)`.
  * Equality and hashCode: by label.
  */
final class Source[S] private[tui4s] (
    val label: String,
    private[tui4s] val body: Emitter[Any] => Unit < (Async & Abort[Throwable]),
    private[tui4s] val fold: (S, Any) => Next[S],
    private[tui4s] val recoverWith: ((S, Throwable) => Next[S]) | Null,
    private[tui4s] val kind: Source.Kind,
    private[tui4s] val keyed: Boolean
):
  /** Sets the identity. A new id is a new source: the old one stops. Put changing parameters in it. For a task: a
    * task with an id interrupts the running task with the same id (latest wins).
    */
  def id(name: String): Source[S] = new Source(name, body, fold, recoverWith, kind, keyed = true)

  /** Folds a failure into the state instead of ending the app. */
  def recover(f: (S, Throwable) => Next[S]): Source[S] = new Source(label, body, fold, f, kind, keyed)

  override def equals(that: Any): Boolean = that match
    case s: Source[?] => s.label == label
    case _            => false
  override def hashCode: Int    = label.hashCode
  override def toString: String = s"Source($label)"
end Source

object Source:
  /** What the runtime needs to know about a body (live-test sleeper count, chunked folds). */
  private[tui4s] enum Kind derives CanEqual:
    case Periodic, Task, Chunked, General

  private[tui4s] def labelOf(frame: Frame): String =
    s"${frame.position.fileName}:${frame.position.lineNumber}"

  private[tui4s] def make[S, A](label: String, kind: Kind)(body: Emitter[A] => Unit < (Async & Abort[Throwable]))(
      fold: (S, A) => Next[S]
  ): Source[S] =
    new Source[S](
      label,
      body.asInstanceOf[Emitter[Any] => Unit < (Async & Abort[Throwable])],
      fold.asInstanceOf[(S, Any) => Next[S]],
      null,
      kind,
      keyed = false
    )

  /** Applies `f` every `period` (first after one period). */
  def every[S](period: Duration)(f: S => Next[S])(using Frame): Source[S] =
    For[S].every(period)(f)

  /** Runs `task` now, then again `period` after each result; folds each result. */
  def poll[S, A](period: Duration)(task: => A < (Async & Abort[Throwable]))(using NotForeign[A])(
      f: (S, A) => Next[S]
  )(using Frame): Source[S] = For[S].poll(period)(task)(f)

  /** Runs `task` once and folds its result. */
  def task[S, A](task: => A < (Async & Abort[Throwable]))(using NotForeign[A])(f: (S, A) => Next[S])(using
      Frame
  ): Source[S] = For[S].task(task)(f)

  /** Folds every element of a Kyo stream; one state change per chunk. The stream is created at start. */
  def stream[S, A](stream: => Stream[A, Async & Abort[Throwable]])(f: (S, A) => Next[S])(using
      Tag[Emit[Chunk[A]]],
      Frame
  ): Source[S] = For[S].stream(stream)(f)

  /** Folds the changes of a Kyo Signal (latest value). Exact for a SignalRef (5.8). */
  def signal[S, A](signal: Signal[A])(f: (S, A) => Next[S])(using Frame): Source[S] = For[S].signal(signal)(f)

  /** The general form: a fiber body that sends state changes whenever it wants. */
  def apply[S](body: Dispatch[S] => Unit < (Async & Abort[Throwable]))(using Frame): Source[S] = For[S](body)

  /** The same constructors with `S` fixed, so folds like `_ sample _` infer. `TuiApp[S]` has one as `source`; for an
    * app built as a value write `Source.For[MyState]`.
    */
  final class For[S] private[tui4s] ():
    def every(period: Duration)(f: S => Next[S])(using frame: Frame): Source[S] =
      make[S, Unit](labelOf(frame), Kind.Periodic)(emit =>
        Loop.forever(Async.sleep(period).andThen(emit(())))
      )((s, _) => f(s))

    def poll[A](period: Duration)(task: => A < (Async & Abort[Throwable]))(using NotForeign[A])(
        f: (S, A) => Next[S]
    )(using frame: Frame): Source[S] =
      make[S, A](labelOf(frame), Kind.Periodic)(emit =>
        Loop.forever(task.map(a => emit(a)).andThen(Async.sleep(period)))
      )(f)

    def task[A](task: => A < (Async & Abort[Throwable]))(using NotForeign[A])(f: (S, A) => Next[S])(using
        frame: Frame
    ): Source[S] =
      make[S, A](labelOf(frame), Kind.Task)(emit => task.map(a => emit(a)))(f)

    def stream[A](stream: => Stream[A, Async & Abort[Throwable]])(f: (S, A) => Next[S])(using
        tag: Tag[Emit[Chunk[A]]],
        frame: Frame
    ): Source[S] =
      make[S, A](labelOf(frame), Kind.Chunked)(emit =>
        stream.foreachChunk(c => emit.asInstanceOf[Emitter[Any]](c))
      )(f)

    def signal[A](signal: Signal[A])(f: (S, A) => Next[S])(using frame: Frame): Source[S] =
      make[S, A](labelOf(frame), Kind.General)(emit => tui4s.internal.Watch.signal(signal, emit))(f)

    def apply(body: Dispatch[S] => Unit < (Async & Abort[Throwable]))(using frame: Frame): Source[S] =
      make[S, S => Next[S]](labelOf(frame), Kind.General)(emit => body(new Dispatch[S](emit)))((s, g) => g(s))
  end For

  object For:
    private val shared = new For[Any]
    def apply[S]: For[S] = shared.asInstanceOf[For[S]] // one shared instance, cast; no allocation
end Source

/** How a general source sends state changes into the loop. Thread-safe. */
final class Dispatch[S] private[tui4s] (emit: Emitter[S => Next[S]]):
  /** Waits while the inbox is full (backpressure). Dropped when this source stopped or the app ended. */
  def apply(f: S => Next[S])(using Frame): Unit < Async = emit(f)

/** Compile-time guard (G6). Kyo lifts any plain value into `A < S`, so a zio.ZIO, cats.effect.IO, fs2.Stream,
  * zio.stream.ZStream or scala.concurrent.Future given to `task` or `poll` would compile and never run. The macro
  * rejects these types with a message that names the fix.
  */
sealed trait NotForeign[A]

object NotForeign:
  /** The one instance. Public because the macro expansion is inlined into user code. */
  object Evidence extends NotForeign[Any]
  inline given [A]: NotForeign[A] = ${ tui4s.internal.Macros.notForeign[A] }

/** The source position of a call. Used as the identity of facade sources, so facade signatures show no Kyo type.
  * Filled at the call site from Kyo's Frame macro (summonInline); users never write it.
  */
final class CallSite private[tui4s] (val file: String, val line: Int):
  def show: String               = s"$file:$line"
  override def toString: String  = show

object CallSite:
  inline given here: CallSite = fromFrame(compiletime.summonInline[Frame])
  private[tui4s] def fromFrame(frame: Frame): CallSite =
    new CallSite(frame.position.fileName, frame.position.lineNumber)
