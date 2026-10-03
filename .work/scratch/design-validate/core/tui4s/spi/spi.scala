package tui4s.spi

import kyo.*
import tui4s.*

/** The one port that every facade source lowers to. The core knows nothing about ZIO or Cats Effect. */
object Sources:
  /** A source whose body emits values; each value folds with the CURRENT fold of this identity (G7). */
  def make[S, A](label: String)(body: Emitter[A] => Unit < (Async & Abort[Throwable]))(
      fold: (S, A) => Next[S]
  )(using Frame): Source[S] =
    Source.make[S, A](label, Source.Kind.General)(body)(fold)

  /** The same for chunks: one state change per emitted chunk. */
  def makeChunked[S, A](label: String)(body: Emitter[Seq[A]] => Unit < (Async & Abort[Throwable]))(
      fold: (S, A) => Next[S]
  )(using Frame): Source[S] =
    Source.make[S, A](label, Source.Kind.Chunked)(body.asInstanceOf[Emitter[A] => Unit < (Async & Abort[Throwable])])(
      fold
    )

  /** The same with a periodic body (counted as a sleeper by the live test kit). */
  def makePeriodic[S, A](label: String)(body: Emitter[A] => Unit < (Async & Abort[Throwable]))(
      fold: (S, A) => Next[S]
  )(using Frame): Source[S] =
    Source.make[S, A](label, Source.Kind.Periodic)(body)(fold)

  /** The same for a one-shot body (a `task`). */
  def makeTask[S, A](label: String)(body: Emitter[A] => Unit < (Async & Abort[Throwable]))(
      fold: (S, A) => Next[S]
  )(using Frame): Source[S] =
    Source.make[S, A](label, Source.Kind.Task)(body)(fold)
end Sources

abstract class Emitter[-A]:
  /** Waits while the inbox is full. */
  def apply(value: A)(using Frame): Unit < Async
  def unsafe: Emitter.Unsafe[A]

object Emitter:
  abstract class Unsafe[-A]:
    /** Never blocks. `accepted` runs once the value is in the inbox, or when it is dropped (source stopped, app
      * ended). Used as ZIO.async / F.async_ callbacks: inbox backpressure reaches the foreign stream.
      */
    def put(value: A, accepted: () => Unit)(using AllowUnsafe): Unit

/** 5.14. Touches no Kyo class, so calling it never starts the Kyo scheduler. */
object SchedulerDefaults:
  val defaults: Seq[(String, String)] = Seq(
    "kyo.scheduler.cycleIntervalNs"                        -> "10000000", // 100 us -> 10 ms
    "kyo.scheduler.blockingMonitorIntervalNs"              -> "20000000", // 2 ms   -> 20 ms
    "kyo.scheduler.regulator.concurrencyCollectIntervalMs" -> "100"       // 10 ms  -> 100 ms
  )

  def applyIfUnset(): Unit =
    if java.lang.System.getProperty("tui4s.schedulerDefaults") != "false" then
      defaults.foreach { (key, value) =>
        val env = key.toUpperCase.nn.replace('.', '_') // KYO_SCHEDULER_CYCLEINTERVALNS
        if java.lang.System.getProperty(key) == null && java.lang.System.getenv(env) == null then
          val _ = java.lang.System.setProperty(key, value)
      }
end SchedulerDefaults

/** Input for the runtime and for headless runs. Called only on the input thread. */
trait InputPort:
  def poll(timeoutMillis: Int): Boolean // true when an event is ready
  def read(): Event | Null              // null: an event tui4s ignores (key release, media key)

/** The real loop on any jatatui Backend (TestBackend, a counting wrapper) and any InputPort: facade tests,
  * benchmarks. No raw mode, no alternate screen.
  */
object Headless:
  def run[S](app: App[S], backend: jatatui.core.backend.Backend, input: InputPort)(using
      Frame
  ): S < (Async & Abort[TuiError]) =
    tui4s.internal.Runner.run(app, tui4s.internal.HeadlessControl(backend, input))

  def start[S](app: App[S], backend: jatatui.core.backend.Backend, input: InputPort)(using
      Frame
  ): TuiHandle[S] < (Async & Scope) =
    tui4s.internal.Runner.start(app, tui4s.internal.HeadlessControl(backend, input))
end Headless
