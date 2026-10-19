// scalafix:off DisableSyntax
// EDGE: JVM monitor, java.lang.Runtime shutdown hooks, unsafe Kyo fiber API
package tui4s.internal

import kyo.*
import tui4s.TuiError

import java.io.PrintStream

/** JVM shutdown hooks, so that tests can see them. */
// claude 2026-10-19: changed: moved here from TerminalIO.scala; `stopping`
// replaces the probe hook (P3, A2)
private[tui4s] trait Hooks:
    def add(hook: Thread): Unit

    /** Removes `hook`; does nothing while the JVM shuts down. */
    def remove(hook: Thread): Unit

    /** True when the JVM shuts down: the stop hook of a run started, or a
      * [[add]] or [[remove]] found that the shutdown is in progress.
      */
    def shuttingDown: Boolean

    /** Records that the JVM shuts down. The stop hook calls it first. */
    def stopping(): Unit
end Hooks

private[tui4s] object Hooks:
    /** The JVM's own hooks. */
    val runtime: Hooks = new Hooks:
        @volatile private var down = false

        def add(hook: Thread): Unit    =
          try java.lang.Runtime.getRuntime.nn.addShutdownHook(hook)
          catch
              case e: IllegalStateException => // shutdown in progress
                down = true
                throw e
        def remove(hook: Thread): Unit =
          try discard(java.lang.Runtime.getRuntime.nn.removeShutdownHook(hook))
          catch case _: IllegalStateException => down = true // shutdown in progress
        def shuttingDown: Boolean      = down
        def stopping(): Unit           = down = true
end Hooks

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
      * stack is closed. Call it inside the synchronous step of the acquire,
      * after the acquire: on a closed stack, a push before its acquire runs the
      * release before the acquire.
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

    /** Runs `body` on a fiber of its own (the run fiber) with a new stack and
      * one JVM shutdown hook, "tui4s-stop". The hook is added, and its removal
      * pushed as the first step of the stack (so it runs last), in one step.
      * The hook records that the JVM stops ([[Hooks.stopping]]), interrupts the
      * run fiber with no wait, runs [[ReleaseStack.runAll]], then waits at most
      * 2 s until the release is complete or the run fiber ended (a fiber ends
      * after its synchronous releases). The caller waits on a copy of the
      * result of the run fiber, never on the run fiber itself. The release of
      * `run` interrupts the run fiber with no wait and runs `runAll`: an
      * interrupt of the caller restores the terminal at once.
      *
      * The hook restores the terminal also when the interrupt of the run fiber
      * fails. A JVM stop before the hook knows the run fiber (the first step
      * after the spawn) does not wait for the run fiber: that fiber then runs
      * each later release at once on the closed stack, but the JVM can halt
      * before an acquire pushes its release.
      */
    // claude 2026-10-19: created (P3, A2)
    def run(hooks: Hooks, report: Throwable => Unit, trace: String => Unit)(
        body: ReleaseStack => Unit < (Async & Abort[TuiError]),
    )(using Frame): Unit < (Async & Abort[TuiError]) =
      Sync.acquireReleaseWith(Sync.defer(Stop.open(hooks, report, trace)))(
        stop => Sync.defer(stop.stack.runAll()),
      ) { stop =>
        Fiber.use[TuiError, Unit, Any, Any](body(stop.stack)) { fiber =>
          Sync.Unsafe
            .defer {
              stop.run = Present(fiber)
              // The caller waits on a copy of the result, not on the run fiber
              // (AGENTS.md: never join the run fiber from an interruptible
              // wait). An interrupt of the caller ends this wait at once, and
              // the releases of the brackets interrupt the run and restore the
              // terminal. Kyo 1.0.0-RC7 also ends a join at once on an
              // interrupt, so this copy is a second guard: no test needs it.
              val end = Promise.Unsafe.init[Unit, Abort[TuiError]]()
              fiber.unsafe.onComplete(end.completeDiscard)
              end.safe
            }
            .map(_.get)
        }
      }

    /** Prints the first failure of a release to `err`, after the restore. */
    // claude 2026-10-19: created (P3, A1): the text of TerminalIO.leave
    def printFailure(err: () => PrintStream)(e: Throwable): Unit =
        val out     = err()
        val problem = s"tui4s: restoring the terminal failed: $e"
        out.println(problem) // tui4s: allowed
        out.flush()

    /** The stop hook of one run and the run fiber that it interrupts. */
    private final class Stop(val stack: ReleaseStack, hooks: Hooks)(using
        Frame,
    ):
        @volatile var run: Maybe[Fiber[Unit, Abort[TuiError]]] = Absent

        // True while the hook runs: a running hook is not removed.
        @volatile private var inHook = false

        val thread: Thread = Thread(() => stop(), "tui4s-stop")

        // claude 2026-10-19: changed: a failing interrupt does not stop the
        // restore (P3 skeptic fix)
        private def stop(): Unit =
            import AllowUnsafe.embrace.danger
            inHook = true
            hooks.stopping()
            try run.foreach(f => discard(f.unsafe.interrupt()))
            catch
                case v: VirtualMachineError => throw v
                case _: Throwable           => ()
            stack.runAll()
            discard(
              stack.awaitReleased(2000, () => run.exists(_.unsafe.done())),
            )
        end stop

        def remove(): Unit = if !inHook then hooks.remove(thread)
    end Stop

    private object Stop:
        def open(
            hooks: Hooks,
            report: Throwable => Unit,
            trace: String => Unit,
        )(using
            Frame,
        ): Stop =
            val stop = Stop(ReleaseStack(report, trace), hooks)
            hooks.add(stop.thread)
            stop.stack.add("shutdown hook")(() => stop.remove())
            stop
        end open
    end Stop
end ReleaseStack
