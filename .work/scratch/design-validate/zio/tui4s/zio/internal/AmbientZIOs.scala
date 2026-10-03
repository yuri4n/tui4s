/*
 * AmbientZIOs: kyo-zio's ZIOs.get / ZIOs.run, but on the CALLER's zio.Runtime (F12).
 *
 * `getOn` is adapted from kyo-zio 1.0.0-RC7, file kyo-zio/shared/src/main/scala/kyo/ZIOs.scala.
 * Original work: Copyright 2015 Flavio Brasil and the Kyo contributors, licensed under the Apache License,
 * Version 2.0 (http://www.apache.org/licenses/LICENSE-2.0).
 * Changes: the ZIO is forked on the caller's runtime (captured by `run` in an inheritable Kyo Local) instead of
 * Runtime.default; the cancel goes through a `Sync.ensure` region.
 *
 * Copyright 2026 the tui4s authors. Licensed under the Apache License, Version 2.0.
 */
package tui4s.zio.internal

import _root_.zio.Cause
import _root_.zio.Exit
import _root_.zio.FiberId
import _root_.zio.Runtime
import _root_.zio.StackTrace
import _root_.zio.Trace
import _root_.zio.Unsafe
import _root_.zio.ZIO
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kyo.*

private[tui4s] object AmbientZIOs:
    private val callerRuntime: Local[Maybe[Runtime[Any]]] = Local.init(Maybe.empty[Runtime[Any]])

    private inline def discard[A](inline v: => A): Unit =
        val _ = v
        ()

    def run[A, E](v: => A < (Abort[E] & Async))(using Frame, Trace): ZIO[Any, E, A] =
        ZIO.runtime[Any].flatMap(rt => ZIOs.run(callerRuntime.let(Present(rt))(v)))

    def get[E, A](v: => ZIO[Any, E, A])(using Frame, Trace): A < (Abort[E] & Async) =
        callerRuntime.use(rt => getOn(rt.getOrElse(Runtime.default))(v))

    def getOn[E, A](rt: Runtime[Any])(v: => ZIO[Any, E, A])(using frame: Frame, t: Trace): A < (Abort[E] & Async) =
        Sync.Unsafe.defer {
            given CanEqual[E, E] = CanEqual.derived
            import ZIOs.toResult
            val p        = Promise.Unsafe.init[A, Abort[E]]()
            val fiberRef = new AtomicReference[zio.Fiber.Runtime[E, A]](null)
            val settled  = new AtomicBoolean(false)
            // `Sync.ensure` with an outcome needs a ConcreteTag for the Abort[E] of its body, and E is generic here, so the
            // body joins with `getResult` (no Abort in its row) and the error is re-raised after the region.
            Sync.ensure { (outcome: Maybe[Result.Error[Any]]) =>
                if outcome.nonEmpty && !settled.get() then
                    val f = fiberRef.get()
                    if f ne null then
                        Unsafe.unsafely(discard(f.unsafe.interrupt(Cause.interrupt(FiberId.None, StackTrace(FiberId.None, _root_.zio.Chunk(t))))))
                end if
            } {
                Sync.Unsafe.defer {
                    Unsafe.unsafely {
                        val f = rt.unsafe.fork(v)
                        fiberRef.set(f)
                        f.unsafe.addObserver { (exit: Exit[E, A]) =>
                            settled.set(true)
                            p.completeDiscard(exit.toResult.map(Kyo.lift))
                        }
                    }
                    p.safe.getResult
                }
            }.map(r => Abort.get(r))
        }
    end getOn
end AmbientZIOs
