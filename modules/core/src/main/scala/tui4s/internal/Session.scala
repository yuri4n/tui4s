// scalafix:off DisableSyntax
// EDGE: the input Thread (crossterm poll, read, join)
// claude 2026-10-17: created this header (purity plan, phase P0)
package tui4s.internal

import jatatui.core.backend.Backend
import kyo.*
import tui4s.*

import scala.util.control.NonFatal

/** The terminal side of a run, without the input thread: raw mode, the
  * alternate screen, the terminal modes, and the backend to draw on. The real
  * terminal implements it ([[TerminalIO]]), and so does the recording control
  * of the tests. A [[Session]] calls [[enter]] once, then [[step]] for each
  * step of the input thread.
  */
// claude 2026-10-19: changed (P3, A1): enter pushes each release to the
// stack of the run; no `opened` and no `leave`
private[tui4s] trait TerminalControl:
    /** Enters the terminal modes for `opts` and returns the backend to draw on.
      * Each step pushes its release to `stack` in the same step, so the stack
      * leaves the terminal again (the last step first). Throws a [[TuiError]]
      * (`NotATerminal`, `NativeAccessDenied`, `NativeLoadFailed`,
      * `TerminalIO`); the releases of the steps before the failure are then on
      * `stack`.
      */
    def enter(opts: TuiOptions, stack: ReleaseStack): Backend

    /** A step of the session: "input-started", "input-stopped", "input-joined",
      * "input-hung". For logs and tests; the default does nothing.
      */
    def step(name: String): Unit = ()

    /** The input of this terminal (the crossterm reader), asked once after
      * [[enter]] when the run has no input of its own. The default has none.
      */
    def input(): Option[InputPort] = None
end TerminalControl

/** An open terminal: the canvas of the run and the input thread. Built by
  * [[Session.open]]; [[close]] is the synchronous release.
  */
// claude 2026-10-19: changed (P3, A1): the release stack of the run holds the
// releases; no closed flag, no interrupted flag, no `safely`
private[tui4s] final class Session private (
    val canvas: Canvas,
    stack: ReleaseStack,
    thread: Maybe[InputThread],
):
    /** Why the input thread failed (it then asked the run to end); `Absent`
      * when it did not fail.
      */
    // claude 2026-10-19: changed (P3): the thread is a Maybe
    def inputFailure: Maybe[Throwable] =
      thread.flatMap(t => Maybe(t.failure).map(_.nn))

    /** The release of the whole run: [[ReleaseStack.runAll]]. The canvas closes
      * first (it waits for a draw in progress, and the runner draws nothing
      * after it), then the input thread stops, then the terminal modes are
      * left. Synchronous and idempotent; it never throws.
      */
    // claude 2026-10-19: changed (P3, A1): the stack runs the release
    def close(): Unit = stack.runAll()
end Session

private[tui4s] object Session:
    /** Opens the terminal through `control`, starts the input thread on `input`
      * (the control's own input for `None`) and makes the canvas on the
      * backend. Each step pushes its release to `stack` in the same step: the
      * input thread (stop it, close the inbox, join it), then the canvas (close
      * it), so the canvas closes first. A failure runs the stack (the terminal
      * is left and the input thread stops) and gives the [[TuiError]].
      *
      * One step, with no `map` after it: as the acquire of a bracket, its value
      * reaches the bracket with no point in between where an interrupt could
      * drop an open session.
      */
    // claude 2026-10-19: changed (P3, A1): the release stack; no `safely` and
    // no undo of its own
    def open(
        runOpts: RunOptions,
        control: TerminalControl,
        input: Option[InputPort],
        inbox: Channel[Msg],
        stack: ReleaseStack,
    )(using Frame): Either[TuiError, Session] < Sync =
      Sync.Unsafe
        .defer {
          try
              val backend = control.enter(runOpts.options, stack)
              val thread  = Maybe.fromOption(
                input
                  .orElse(control.input())
                  .map(port => InputThread(port, inbox, runOpts.pollMillis)),
              )
              // Pushed before the start: on a closed stack the stop runs at
              // once, and a thread that starts after its stop ends at once.
              stack.add("input")(() =>
                stopInput(control, inbox, thread, runOpts.pollMillis),
              )
              thread.foreach { t =>
                t.start()
                control.step("input-started")
              }
              val canvas  = Canvas(backend)
              stack.add("canvas")(() => canvas.close())
              Right(Session(canvas, stack, thread))
          catch
              case e: Throwable =>
                stack.runAll()
                e match
                    case e: TuiError => Left(e)
                    case NonFatal(e) => Left(TuiError.TerminalIO(e))
                    case e           => throw e
        }

    /** Closes the inbox; the messages that were still in it are dropped. */
    // claude 2026-10-13: changed (T1): the runner's inbox has no probes
    def closeInbox(inbox: Channel[Msg])(using AllowUnsafe, Frame): Unit =
      discard(inbox.unsafe.close())

    /** The release of the input: stop the thread, close the inbox (a put that
      * waits for room ends, so the thread is released, and the runner ends at
      * its next take), then wait for two poll intervals for the thread to end.
      * An interrupt does not cut the wait short; it is given to the stack at
      * the end.
      */
    // claude 2026-10-19: created (P3, A1): the input steps of Session.close
    private def stopInput(
        control: TerminalControl,
        inbox: Channel[Msg],
        thread: Maybe[InputThread],
        pollMillis: Int,
    )(using AllowUnsafe, Frame): Unit =
        thread.foreach(_.requestStop())
        closeInbox(inbox)
        thread.foreach { t =>
          control.step("input-stopped")
          val (joined, interrupted) = join(t, 2L * pollMillis + 50)
          control.step(if joined then "input-joined" else "input-hung")
          if interrupted then
              throw InterruptedException("interrupted in the join of the input")
        }
    end stopInput

    /** Waits at most `timeoutMillis` for `t` to end: (it ended, an interrupt
      * came). An interrupt is remembered and the wait goes on.
      */
    private def join(t: Thread, timeoutMillis: Long): (Boolean, Boolean) =
        val deadline    = java.lang.System.nanoTime() + timeoutMillis * 1_000_000
        var left        = timeoutMillis
        var interrupted = false
        while t.isAlive && left > 0 do
            try t.join(left)
            catch case _: InterruptedException => interrupted = true
            left =
              (deadline - java.lang.System.nanoTime() + 999_999) / 1_000_000
        (!t.isAlive, interrupted)
    end join
end Session

/** The input thread: a platform daemon thread, so no Kyo worker waits in a
  * native call. It calls `read` only after `poll` returned true, reads every
  * event that is already queued (at most 512), maps them to runner messages
  * ([[EventMapping]], one sequence number per message) and puts them into the
  * inbox as one batch; the runner still takes them one at a time, one frame per
  * event. It waits while the inbox is full (backpressure); closing the inbox
  * releases it. When the input fails (any error but a fatal error of the JVM,
  * also a `LinkageError` of a native call), the thread keeps the failure and
  * asks the run to end ([[Msg.Quit]]).
  */
// claude 2026-10-13: changed (T1, RC-21, D-21 a): crossterm events, mapped
// here to Msg; the failure is kept for the run and the run gets Msg.Quit
// claude 2026-10-13: changed (T1, review R2-4): the Scaladoc names the errors
private[tui4s] final class InputThread(
    port: InputPort,
    inbox: Channel[Msg],
    pollMillis: Int,
) extends java.lang.Thread("tui4s-input"):
    setDaemon(true)
    @volatile private var running = true

    // The sequence number of the next message (only this thread uses it).
    private var seq = 1L

    /** Why the input failed; `null` while it did not. */
    @volatile var failure: Throwable | Null = null

    override def run(): Unit =
        import AllowUnsafe.embrace.danger
        try
            while running do
                if port.poll(pollMillis) then
                    val batch = scala.collection.mutable.ArrayBuffer.empty[Msg]
                    var n     = 0
                    while running && n < 512 && (n == 0 || port.poll(0)) do
                        // claude 2026-10-19: changed: Maybe from the port and
                        // from the mapping (purity plan, P1)
                        port.read().flatMap(EventMapping(_, seq)).foreach { m =>
                          batch += m
                          seq += 1
                        }
                        n += 1
                    end while
                    if batch.nonEmpty && running then
                        val accepted = java.util.concurrent.CountDownLatch(1)
                        inbox.unsafe
                          .putBatchFiber(batch.toSeq)
                          .onComplete(_ => accepted.countDown())
                        accepted.await()
                end if
            end while
        catch
            case _: InterruptedException => ()
            // claude 2026-10-13: changed (T1, review R2-4): every error that is
            // not fatal to the JVM is the failure of the input, also a
            // LinkageError of a native call (NonFatal does not match it): the
            // thread ended, and q and Ctrl-C were dead keys
            case e: VirtualMachineError  => throw e
            case e: Throwable            =>
              failure = e
              // A put that waits for room (an offer drops the message when the
              // inbox is full); closing the inbox ends it.
              discard(inbox.unsafe.putFiber(Msg.Quit))
        end try
    end run

    /** Asks the thread to stop after its current poll. */
    def requestStop(): Unit = running = false
end InputThread
