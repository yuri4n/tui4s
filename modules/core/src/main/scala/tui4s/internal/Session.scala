package tui4s.internal

import jatatui.core.backend.Backend
import jatatui.core.backend.TestBackend
import jatatui.core.terminal.Terminal
import jatatui.core.terminal.TerminalOptions
import jatatui.core.terminal.Viewport as JViewport
import java.util.concurrent.atomic.AtomicBoolean
import kyo.*
import scala.util.control.NonFatal
import tui4s.*
import tui4s.spi.InputPort

/** The terminal side of a run (SPEC 5.9), without the input thread: raw mode,
  * the alternate screen, the terminal modes, and the backend to draw on. The
  * real terminal implements it (C5), and so do the headless and recording
  * controls of the tests. A [[Session]] calls it in this order: [[enter]],
  * [[step]] for each step of the input thread, [[leave]].
  */
private[tui4s] trait TerminalControl:
    /** Enters the terminal modes for `opts` and returns the backend to draw on.
      * Throws a [[TuiError]] (`NotATerminal`, `NativeAccessDenied`,
      * `TerminalIO`); a failing enter undoes its own earlier steps.
      */
    def enter(opts: TuiOptions): Backend

    /** A step of the session: "input-started", "input-stopped", "input-joined",
      * "input-hung". For logs and tests; the default does nothing.
      */
    def step(name: String): Unit = ()

    /** The app has a new size (a folded [[Resize]]); called on the render fiber
      * before the next draw. A real terminal resizes itself; a test backend
      * follows the event. The default does nothing.
      */
    def resize(width: Int, height: Int): Unit = ()

    /** Leaves the terminal modes: the last step of the release. Never throws. */
    def leave(opts: TuiOptions): Unit
end TerminalControl

/** Any jatatui backend, no terminal modes (`spi.Headless`). A [[Resize]] event
  * resizes a `TestBackend`.
  */
private[tui4s] final class HeadlessControl(backend: Backend)
    extends TerminalControl:
    def enter(opts: TuiOptions): Backend = backend

    override def resize(width: Int, height: Int): Unit = backend match
        case t: TestBackend => t.resize(width, height)
        case _              => ()

    def leave(opts: TuiOptions): Unit = ()
end HeadlessControl

/** An open terminal: the jatatui terminal and the input thread. Built by
  * [[Session.open]]; [[close]] is the synchronous release of SPEC 5.9.
  */
private[tui4s] final class Session private (
    val terminal: Terminal[CountingBackend],
    control: TerminalControl,
    opts: TuiOptions,
    inbox: Channel[Item],
    thread: InputThread | Null,
):
    private val closed = AtomicBoolean(false)

    /** The first error of the release, if any (the app classes print it after
      * the restore).
      */
    @volatile var releaseError: Throwable | Null = null

    /** The release (SPEC 5.9, G8): stop the input thread and close the inbox (a
      * put that waits for room ends, so the thread is released), join the
      * thread, then leave the terminal modes. Synchronous, idempotent, never
      * throws; every step runs even if an earlier one failed.
      */
    def close()(using AllowUnsafe, Frame): Unit =
      if closed.compareAndSet(false, true) then
          if thread != null then safely(thread.requestStop())
          safely(Session.closeInbox(inbox))
          if thread != null then
              safely(control.step("input-stopped"))
              safely {
                if thread.stopAndJoin(2L * opts.pollMillis.max(0) + 50) then
                    control.step("input-joined")
                else control.step("input-hung")
              }
          safely(control.leave(opts))

    private def safely(f: => Unit): Unit =
      try f
      catch case NonFatal(e) => if releaseError == null then releaseError = e
end Session

private[tui4s] object Session:
    /** Opens the terminal through `control` and starts the input thread on
      * `input` (none for `None`). A failure leaves the terminal modes again and
      * fails with a [[TuiError]].
      */
    def open(
        opts: TuiOptions,
        control: TerminalControl,
        input: Option[InputPort],
        inbox: Channel[Item],
    )(using Frame): Session < (Sync & Abort[TuiError]) =
      Sync.Unsafe
        .defer {
          try
              val backend = control.enter(opts)
              try
                  val counting = CountingBackend(backend)
                  val terminal = opts.viewport match
                      case Viewport.Fullscreen => Terminal.create(counting)
                      case Viewport.Inline(h)  =>
                        Terminal.withOptions(
                          counting,
                          TerminalOptions(JViewport.inline(h)),
                        )
                  val thread   = input.map { port =>
                    val t = InputThread(port, inbox, opts.pollMillis)
                    t.start()
                    control.step("input-started")
                    t
                  }
                  Right(Session(terminal, control, opts, inbox, thread.orNull))
              catch
                  case NonFatal(e) =>
                    try control.leave(opts)
                    catch case NonFatal(_) => ()
                    throw e
              end try
          catch
              case e: TuiError => Left(e)
              case NonFatal(e) => Left(TuiError.TerminalIO(e))
        }
        .map(r => Abort.get(r))

    /** Closes the inbox. The items that were still in it are dropped, except
      * that a waiting `settle` (a [[Probe]]) is released.
      */
    def closeInbox(inbox: Channel[Item])(using AllowUnsafe, Frame): Unit =
      inbox.unsafe.close().onComplete { r =>
        r.foreach(_.eval.foreach(_.foreach {
          case p: Probe =>
            p.promise.completeDiscard(Result.succeed(Session.noStats))
          case _        => ()
        }))
      }

    /** The stats of a probe that the app never reached. */
    val noStats: Stats = Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
end Session

/** The input thread (SPEC 5.3): a platform daemon thread, so no Kyo worker
  * waits in a native call. It calls `read` only after `poll` returned true,
  * reads every event that is already queued (at most 512), and puts them into
  * the inbox as one batch. It waits while the inbox is full (backpressure);
  * closing the inbox releases it.
  */
private[tui4s] final class InputThread(
    port: InputPort,
    inbox: Channel[Item],
    pollMillis: Int,
) extends java.lang.Thread("tui4s-input"):
    setDaemon(true)
    @volatile private var running = true

    override def run(): Unit =
        import AllowUnsafe.embrace.danger
        try
            while running do
                if port.poll(pollMillis) then
                    val batch = scala.collection.mutable.ArrayBuffer.empty[Item]
                    var n     = 0
                    while running && n < 512 && (n == 0 || port.poll(0)) do
                        val e = port.read()
                        if e != null then batch += e
                        n += 1
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
            case NonFatal(e)             =>
              discard(inbox.unsafe.offer(Failed(TuiError.TerminalIO(e))))
        end try
    end run

    /** Asks the thread to stop after its current poll. */
    def requestStop(): Unit = running = false

    /** Stops the thread and waits at most `timeoutMillis`; true when it ended. */
    def stopAndJoin(timeoutMillis: Long): Boolean =
        running = false
        join(timeoutMillis)
        !isAlive
end InputThread
