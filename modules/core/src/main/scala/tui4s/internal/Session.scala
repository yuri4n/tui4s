// scalafix:off DisableSyntax
// EDGE: the input Thread (crossterm poll, read) and the hook-safe release
// claude 2026-10-17: created this header (purity plan, phase P0)
package tui4s.internal

import jatatui.core.backend.Backend
import kyo.*
import tui4s.*

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/** The terminal side of a run, without the input thread: raw mode, the
  * alternate screen, the terminal modes, and the backend to draw on. The real
  * terminal implements it ([[TerminalIO]]), and so does the recording control
  * of the tests. A [[Session]] calls it in this order: [[enter]], [[step]] for
  * each step of the input thread, [[leave]].
  */
// claude 2026-10-13: changed (T1, RC-21): no resize (the runner's Canvas
// resizes a TestBackend) and no jatatui Terminal in `opened` (the runner draws
// on the Backend through its Canvas); HeadlessControl is not carried (no run
// uses it: the tests have RecordingControl)
private[tui4s] trait TerminalControl:
    /** Enters the terminal modes for `opts` and returns the backend to draw on.
      * Throws a [[TuiError]] (`NotATerminal`, `NativeAccessDenied`,
      * `NativeLoadFailed`, `TerminalIO`); a failing enter undoes its own
      * earlier steps.
      */
    // claude 2026-10-13: changed (T1, review R1-2): NativeLoadFailed
    def enter(opts: TuiOptions): Backend

    /** A step of the session: "input-started", "input-stopped", "input-joined",
      * "input-hung". For logs and tests; the default does nothing.
      */
    def step(name: String): Unit = ()

    /** The input of this terminal (the crossterm reader), asked once after
      * [[enter]] when the run has no input of its own. The default has none.
      */
    def input(): Option[InputPort] = None

    /** The session is open: the canvas draws on the backend of [[enter]], the
      * input thread runs, and `release` is the synchronous, idempotent release
      * of the whole session (the real terminal runs it from a JVM shutdown
      * hook). The default does nothing.
      */
    def opened(release: () => Unit): Unit = ()

    /** Leaves the terminal modes: the last step of the release. Never throws. */
    def leave(opts: TuiOptions): Unit
end TerminalControl

/** An open terminal: the canvas of the run and the input thread. Built by
  * [[Session.open]]; [[close]] is the synchronous release.
  */
// claude 2026-10-13: changed (T1, RC-21): the runner's Canvas and inbox
// (Channel[Msg]) replace the jatatui Terminal and the old engine's inbox
private[tui4s] final class Session private (
    val canvas: Canvas,
    control: TerminalControl,
    // claude 2026-10-19: changed: the checked options (RunOptions, A10)
    runOpts: RunOptions,
    inbox: Channel[Msg],
    thread: InputThread | Null,
):
    private val closed = AtomicBoolean(false)

    // The thread that runs `close` was interrupted during the release.
    private var interrupted = false

    /** The first error of the release, if any. */
    @volatile var releaseError: Throwable | Null = null

    /** Why the input thread failed (it then asked the run to end); `Absent`
      * when it did not fail.
      */
    // claude 2026-10-13: created (T1): the run fails with TerminalIO after it
    // claude 2026-10-19: changed: a Maybe, not null (purity plan, P1)
    def inputFailure: Maybe[Throwable] =
        val t = thread
        if t == null then Absent else Maybe(t.failure).map(_.nn)

    /** The release: close the canvas (it waits for a draw in progress, and the
      * runner draws nothing after it), stop the input thread and close the
      * inbox (a put that waits for room ends, so the thread is released, and
      * the runner ends at its next take), join the thread, then leave the
      * terminal modes. Synchronous, idempotent, never throws; every step runs
      * even if an earlier one failed. An interrupt of the calling thread does
      * not cut the release short: the interrupt flag is set again after the
      * last step. A second caller (the shutdown hook and the run's own release)
      * waits until the first one is done.
      */
    // claude 2026-10-13: changed (T1): the inbox of the runner; the join waits
    // for two poll intervals
    def close()(using AllowUnsafe, Frame): Unit = synchronized {
      if closed.compareAndSet(false, true) then
          // claude 2026-10-13: created (T1, the bot fix of the review thread
          // CountingBackend.scala:33): a release during a draw (the shutdown
          // hook) waits for it, and no frame comes after the restore
          safely(canvas.close())
          if thread != null then safely(thread.requestStop())
          safely(Session.closeInbox(inbox))
          if thread != null then
              safely(control.step("input-stopped"))
              // claude 2026-10-19: changed: the checked poll interval
              // (RunOptions, A10)
              safely {
                if join(thread, 2L * runOpts.pollMillis + 50) then
                    control.step("input-joined")
                else control.step("input-hung")
              }
          end if
          // claude 2026-10-19: changed: the options of RunOptions (A10)
          safely(control.leave(runOpts.options))
          if interrupted then Thread.currentThread().interrupt()
    }

    /** Waits at most `timeoutMillis` for `t` to end; true when it ended. An
      * interrupt is remembered and the wait goes on.
      */
    private def join(t: Thread, timeoutMillis: Long): Boolean =
        val deadline = java.lang.System.nanoTime() + timeoutMillis * 1_000_000
        var left     = timeoutMillis
        while t.isAlive && left > 0 do
            try t.join(left)
            catch case _: InterruptedException => interrupted = true
            left =
              (deadline - java.lang.System.nanoTime() + 999_999) / 1_000_000
        !t.isAlive
    end join

    private def safely(f: => Unit): Unit =
      try f
      catch
          case _: InterruptedException => interrupted = true
          case NonFatal(e)             =>
            if releaseError == null then releaseError = e
end Session

private[tui4s] object Session:
    /** Opens the terminal through `control`, makes the canvas on its backend,
      * and starts the input thread on `input` (the control's own input for
      * `None`). A failure leaves the terminal modes again, stops the input
      * thread when it started, and gives the [[TuiError]].
      *
      * One step, with no `map` after it: as the acquire of a bracket, its value
      * reaches the bracket with no point in between where an interrupt could
      * drop an open session.
      */
    // claude 2026-10-13: changed (T1, RC-21): the Canvas on the backend, the
    // runner's inbox; no jatatui Terminal and no inline viewport (D-22)
    // claude 2026-10-19: changed: the checked options (RunOptions, A10)
    def open(
        runOpts: RunOptions,
        control: TerminalControl,
        input: Option[InputPort],
        inbox: Channel[Msg],
    )(using Frame): Either[TuiError, Session] < Sync =
      Sync.Unsafe
        .defer {
          try
              val opts                    = runOpts.options
              val backend                 = control.enter(opts)
              var session: Session | Null = null
              try
                  val canvas = Canvas(backend)
                  // claude 2026-10-13: changed (T1, the bot fix of the review
                  // thread Session.scala:150 of PR 4): the session exists
                  // before the input thread starts, so a failure after the
                  // start closes the session, which stops and joins the
                  // thread (it leaked before)
                  val thread = input
                    .orElse(control.input())
                    .map(port => InputThread(port, inbox, runOpts.pollMillis))
                  val s      =
                    Session(canvas, control, runOpts, inbox, thread.orNull)
                  session = s
                  thread.foreach { t =>
                    t.start()
                    control.step("input-started")
                  }
                  control.opened(() => s.close())
                  Right(s)
              catch
                  case NonFatal(e) =>
                    val s = session
                    if s != null then s.close()
                    else
                        try control.leave(opts)
                        catch case NonFatal(_) => ()
                    throw e
              end try
          catch
              case e: TuiError => Left(e)
              case NonFatal(e) => Left(TuiError.TerminalIO(e))
        }

    /** Closes the inbox; the messages that were still in it are dropped. */
    // claude 2026-10-13: changed (T1): the runner's inbox has no probes
    def closeInbox(inbox: Channel[Msg])(using AllowUnsafe, Frame): Unit =
      discard(inbox.unsafe.close())
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
