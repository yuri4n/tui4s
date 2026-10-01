package tui4s

// `_root_`: see Source.scala (package tui4s and its exported dsl).
import _root_.kyo.*
import _root_.tui4s.internal.Fold
import _root_.tui4s.internal.HandleObserver
import _root_.tui4s.internal.Item
import _root_.tui4s.internal.Runner
import _root_.tui4s.internal.Stop
import _root_.tui4s.internal.TerminalControl
import _root_.tui4s.spi.InputPort

/** Runs apps. */
object Tui:
    /** The run seam of the facades and the tests: `app` on `control` (the
      * terminal side of SPEC 5.9) with input from `input`. The terminal is
      * released before the result is visible, also on interruption.
      */
    private[tui4s] def runWith[S](
        app: App[S],
        control: TerminalControl,
        input: InputPort,
    )(using Frame): S < (Async & Abort[TuiError]) =
      Runner.run(app, control, Some(input))

    /** The start seam: `app` on its own fiber; the handle comes after the first
      * frame. The caller stops it.
      */
    private[tui4s] def startWith[S](
        app: App[S],
        control: TerminalControl,
        input: InputPort,
    )(using Frame): TuiHandle[S] < Async =
      Runner.startUnscoped(app, control, Some(input))
end Tui

/** A running app, seen from outside: send it changes and events, read its
  * states and counters, stop it, wait for its result.
  */
final class TuiHandle[S] private[tui4s] (
    private[tui4s] val inbox: Channel[Item],
    fiber: Fiber[S, Abort[TuiError]],
    observer: HandleObserver[S],
):
    /** Folds `f` into the state, like a source would. Dropped after the end. */
    def update(f: S => Next[S])(using Frame): Unit < Async =
      Abort.run[Closed](inbox.put(Fold(Fold.Direct, f))).unit

    /** Sends a synthetic input event. Dropped after the end. */
    def send(event: Event)(using Frame): Unit < Async =
      Abort.run[Closed](inbox.put(event)).unit

    /** The state after each batch, from `init` on (equal states once). When the
      * app failed before its first frame, the signal holds `null`.
      */
    def states: Signal[S] =
        val s = observer.states
        if s != null then s
        else
            import AllowUnsafe.embrace.danger
            given CanEqual[S, S] = CanEqual.derived
            Signal.SignalRef.Unsafe.init[S](null.asInstanceOf[S]).safe

    /** A snapshot of the counters, taken after the last batch. */
    def stats(using Frame): Stats < Sync = Sync.defer(observer.stats)

    /** The result of the run: the last state, or the failure. */
    def await(using Frame): S < (Async & Abort[TuiError]) = fiber.get

    /** Stops the app like `Quit`; returns when the terminal is released. */
    def stop(using Frame): Unit < Async =
      Abort.run[Closed](inbox.put(Stop)).andThen(fiber.getResult).unit

    /** True after the run ended. */
    private[tui4s] def ended(using Frame): Boolean < Sync = fiber.done

    /** Interrupts the run (signalled, not awaited): a safety net for tests. */
    private[tui4s] def kill(using Frame): Unit < Sync = fiber.interrupt.unit
end TuiHandle
