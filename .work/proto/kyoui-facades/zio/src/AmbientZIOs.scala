/* AmbientZIOs: kyo-zio's `ZIOs.run` / `ZIOs.get`, on the CALLER's zio.Runtime.
 *
 * `getOn` is derived from the method `get` of kyo-zio 1.0.0-RC7, file
 * `kyo/ZIOs.scala` of the module kyo-zio
 * (https://github.com/getkyo/kyo/tree/v1.0.0-RC7/kyo-zio).
 * Original work: Copyright 2015 Flavio Brasil and the Kyo contributors,
 * licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). The upstream file has no
 * per-file header; the copyright line is the one in the upstream LICENSE.txt.
 * Kyo has no NOTICE file.
 *
 * Changes from the original (Apache-2.0 section 4(b) "prominent notices"):
 * - moved out of package `kyo`, into `tui4s.interop.zio.internal`;
 * - the ZIO is forked on a runtime given as a parameter (the caller's runtime,
 * which `run` keeps in an inheritable Kyo `Local`) instead of
 * `Runtime.default`;
 * - the ZIO is forked interruptible, also when the runtime was captured in an
 * uninterruptible region;
 * - the cancel of the ZIO fiber goes through a `Sync.ensure` region instead of
 * `Promise.onInterrupt`, and the error is raised after the region.
 *
 * Copyright 2026 the tui4s authors. Licensed under the Apache License, Version
 * 2.0. */
package tui4s.interop.zio.ui.internal

import _root_.java.util.concurrent.atomic.AtomicBoolean
import _root_.java.util.concurrent.atomic.AtomicReference
import _root_.kyo.*

import _root_.zio.Cause
import _root_.zio.Exit
import _root_.zio.Fiber as ZFiber
import _root_.zio.FiberId
import _root_.zio.Runtime
import _root_.zio.StackTrace
import _root_.zio.Trace
import _root_.zio.Unsafe
import _root_.zio.ZIO

/** The bridge between the facade and Kyo (SPEC 4.2, F11, F12).
  *
  * kyo-zio's `ZIOs.get` always forks on `zio.Runtime.default`, so a ZIO inside
  * a Kyo computation loses the FiberRefs (log annotations, a `TestClock`, an
  * executor) of the ZIO program that started the Kyo computation. Here, [[run]]
  * captures the caller's runtime with `ZIO.runtime` and keeps it in an
  * inheritable Kyo `Local`, so [[get]] forks on that runtime, also from a Kyo
  * fiber that the computation forked (a source fiber of the app).
  *
  * Interruption works both ways: a ZIO interrupt of [[run]] interrupts the Kyo
  * fiber and waits until it ended (its `Sync.ensure` finalizers ran: the
  * terminal is restored); a Kyo interrupt of [[get]] interrupts the ZIO fiber
  * (signalled, not awaited).
  */
private[tui4s] object AmbientZIOs:
    private val callerRuntime: Local[Maybe[Runtime[Any]]] =
      Local.init(Maybe.empty[Runtime[Any]])

    /** Runs `v` as a ZIO, on Kyo's scheduler. Every [[get]] inside `v` (also in
      * fibers that `v` forks) runs its ZIO on the runtime of the fiber that
      * runs this ZIO. An interrupt of this ZIO interrupts `v` and waits for its
      * end.
      */
    def run[A, E](v: => A < (Abort[E] & Async))(using
        Frame,
        Trace,
    ): ZIO[Any, E, A] =
      ZIO
        .runtime[Any]
        .flatMap(rt => ZIOs.run(callerRuntime.let(Present(rt))(v)))

    /** Runs the ZIO `v` on the runtime that [[run]] captured, or on
      * `Runtime.default` outside [[run]]. Its typed error is an `Abort[E]`, its
      * defect a panic. An interrupt of the Kyo fiber interrupts the ZIO.
      */
    def get[E, A](v: => ZIO[Any, E, A])(using
        Frame,
        Trace,
    ): A < (Abort[E] & Async) =
      callerRuntime.use(rt => getOn(rt.getOrElse(Runtime.default))(v))

    /** Runs the ZIO `v` on `rt` (derived from kyo-zio's `ZIOs.get`). */
    def getOn[E, A](rt: Runtime[Any])(v: => ZIO[Any, E, A])(using
        frame: Frame,
        trace: Trace,
    ): A < (Abort[E] & Async) =
      Sync.Unsafe.defer {
        given CanEqual[E, E] = CanEqual.derived
        import ZIOs.toResult

        val p       = Promise.Unsafe.init[A, Abort[E]]()
        val forked  = new AtomicReference[ZFiber.Runtime[E, A]](null)
        val settled = new AtomicBoolean(false)
        // `Sync.ensure` with an outcome needs a ConcreteTag for the Abort[E] of
        // its body, and E is generic here, so the body joins with `getResult`
        // (no Abort in its row) and the error is raised after the region.
        Sync
          .ensure { (outcome: Maybe[Result.Error[Any]]) =>
            if outcome.nonEmpty && !settled.get() then
                val f = forked.get()
                if f != null then
                    Unsafe.unsafely(
                      discard(
                        f.unsafe.interrupt(
                          Cause.interrupt(
                            FiberId.None,
                            StackTrace(FiberId.None, _root_.zio.Chunk(trace)),
                          ),
                        ),
                      ),
                    )
                end if
          } {
            Sync.Unsafe.defer {
              Unsafe.unsafely {
                // A runtime captured in an uninterruptible region (the
                // acquire of a ZIO bracket) has interruption off; a source
                // must stop when the app stops it.
                val f = rt.unsafe.fork(ZIO.suspendSucceed(v).interruptible)
                forked.set(f)
                f.unsafe.addObserver { (exit: Exit[E, A]) =>
                  settled.set(true)
                  p.completeDiscard(exit.toResult.map(Kyo.lift))
                }
              }
              p.safe.getResult
            }
          }
          .map(r => Abort.get(r))
      }
    end getOn
end AmbientZIOs

// kyo.discard is private[kyo]; the copy in tui4s core is not on this prototype's class path.
private inline def discard[A](inline value: A): Unit =
    val _ = value
    ()
