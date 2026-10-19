// scalafix:off DisableSyntax
// EDGE: JVM monitor and java.lang.Runtime shutdown hooks
package tui4s.internal

import kyo.*

import java.io.PrintStream

/** The synchronous release of one run: a stack of release steps.
  *
  * Each acquire of the run pushes its release in the same synchronous step
  * ([[add]]), so no failure and no interrupt can come between the acquire and
  * its release. [[runAll]] runs the releases in the reverse order of the
  * pushes, once: from the end of the run or from the JVM shutdown hook,
  * whichever comes first. It is a plain method that does not use the Kyo
  * scheduler: a JVM shutdown hook cannot run the finalizers of a Kyo `Scope`
  * (in Kyo 1.0.0-RC7 `Scope` only starts a fiber that runs them).
  *
  * Lock order: the monitor of the stack is held only while the state changes,
  * never while a release runs. A release can thus take another monitor (the
  * canvas close waits on the monitor of the backend while a draw is in
  * progress) and cannot deadlock against a second caller of [[runAll]] or
  * [[add]]. Keep this invariant.
  *
  * @param report
  *   gets the first failure of a [[runAll]], after its last step
  * @param trace
  *   gets the name of each step before it runs (for the tests)
  */
// claude 2026-10-19: created (P3, A1)
private[tui4s] final class ReleaseStack(
    report: Throwable => Unit,
    trace: String => Unit = _ => (),
):
    import ReleaseStack.*

    // Guarded by the monitor of this stack.
    private var state: State = State.Open(Nil)

    // True after a runAll ran its last step.
    @volatile private var released = false

    /** Pushes `release` as a step of the stack, or runs it at once when the
      * stack is closed. Call it inside the synchronous step of the acquire.
      */
    def add(step: String)(release: () => Unit): Unit =
        val entry = Entry(step, release)
        val open  = synchronized {
          state match
              case State.Open(entries) =>
                state = State.Open(entry :: entries)
                true
              case State.Closed        => false
        }
        if !open then run(List(entry))
    end add

    /** [[add]] as a `Sync` step, for a release that has no acquire. */
    def push(step: String)(release: () => Unit)(using Frame): Unit < Sync =
      Sync.defer(add(step)(release))

    /** Closes the stack and runs its releases, the last push first. A failure
      * of one step does not stop the next steps; the first failure goes to
      * `report`. An interrupt of the calling thread does not cut the release
      * short: the steps run with the interrupt flag clear, and the flag is set
      * again after the last step. A second call does nothing.
      */
    def runAll(): Unit =
        val taken = synchronized {
          state match
              case State.Open(entries) =>
                state = State.Closed
                Some(entries)
              case State.Closed        => None
        }
        taken.foreach { entries =>
          try run(entries)
          finally
              released = true
              synchronized(notifyAll())
        }
    end runAll

    /** Waits at most `timeoutMillis` until a [[runAll]] has run its last step
      * or `ended` is true; true when one of them happened. The wait releases
      * the monitor.
      */
    def awaitReleased(timeoutMillis: Long, ended: () => Boolean): Boolean =
        val deadline = java.lang.System.nanoTime() + timeoutMillis * 1_000_000
        synchronized {
          var left = timeoutMillis
          try
              while !released && !ended() && left > 0 do
                  wait(left.min(10))
                  left = (deadline - java.lang.System.nanoTime()) / 1_000_000
          catch
              case _: InterruptedException => Thread.currentThread().interrupt()
          released || ended()
        }
    end awaitReleased

    private def run(entries: List[Entry]): Unit =
        var interrupted             = Thread.interrupted()
        var first: Maybe[Throwable] = Absent
        entries.foreach { e =>
          try
              trace(e.step)
              e.release()
          catch
              case v: VirtualMachineError  => throw v
              case _: InterruptedException => interrupted = true
              case t: Throwable            => if first.isEmpty then first = Present(t)
        }
        first.foreach { t =>
          try report(t)
          catch
              case v: VirtualMachineError => throw v
              case _: Throwable           => ()
        }
        if interrupted then Thread.currentThread().interrupt()
    end run
end ReleaseStack

private[tui4s] object ReleaseStack:
    private final case class Entry(step: String, release: () => Unit)

    private enum State:
        case Open(entries: List[Entry])
        case Closed

    /** Prints the first failure of a release to `err`, after the restore. */
    // claude 2026-10-19: created (P3, A1): the text of TerminalIO.leave
    def printFailure(err: () => PrintStream)(e: Throwable): Unit =
        val out     = err()
        val problem = s"tui4s: restoring the terminal failed: $e"
        out.println(problem) // tui4s: allowed
        out.flush()

end ReleaseStack
