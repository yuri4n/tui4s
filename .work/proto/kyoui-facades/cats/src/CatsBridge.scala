/* Kyo <-> cats-effect bridge for tui4s-cats, adapted to Kyo 1.0.0-RC7 and
 * cats-effect 3.7.1.
 *
 * Derived from kyo-cats 1.0.0-RC5, file
 * kyo-cats/shared/src/main/scala/kyo/Cats.scala
 * (https://github.com/getkyo/kyo/blob/v1.0.0-RC5/kyo-cats/shared/src/main/scala/kyo/Cats.scala).
 * Original work: Copyright 2015 Flavio Brasil and the Kyo contributors,
 * licensed under the Apache License, Version 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0). The upstream file has no
 * per-file header; the copyright line is the one in the upstream LICENSE.txt.
 * Kyo has no NOTICE file.
 *
 * Changes from the original (Apache-2.0 section 4(b) "prominent notices"):
 * - moved out of package `kyo` (tui4s code must not live in package kyo:
 * dynamic Tags are refused there);
 * - `get` takes the IORuntime as a using-parameter instead of importing
 * `cats.effect.unsafe.implicits.global`;
 * - `get` maps a failed IO to a typed `Abort[Throwable]` failure (was
 * `Abort[Nothing]` + panic), and an IO that cancels itself to a Kyo
 * `Interrupted` panic;
 * - `run` starts the Kyo fiber inside `IO.uncancelable` and registers the
 * cancel-and-await finalizer with `onCancel` around `poll(join)`, so a cancel
 * that lands between the fork and the async node cannot orphan the Kyo fiber
 * (the same fix kyo-zio RC7 applied to ZIOs.run);
 * - added F[_]-polymorphic versions (`runF`, `getF` via `Dispatcher[F]`,
 * `runWithDispatcher`);
 * - `runWithDispatcher` uses Dispatcher.parallel(await = false) (cats-effect
 * 3.7.1 can lose a cancel otherwise).
 *
 * Copyright 2026 the tui4s authors. Licensed under the Apache License, Version
 * 2.0. The full text of the license is in the file LICENSE at the root of this
 * repository. */
package tui4s.interop.cats.ui.internal

import _root_.cats.effect.IO as CatsIO
import _root_.cats.effect.kernel.Async as CEAsync
import _root_.cats.effect.std.Dispatcher
import _root_.cats.effect.unsafe.IORuntime
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

/** The Kyo <-> Cats Effect bridge of the facade (internal; see the header). */
private[tui4s] object CatsBridge:

    // ------------------------------------------------------------------------------------------------
    // IO-specific API
    // ------------------------------------------------------------------------------------------------

    /** Lifts a cats-effect IO into Kyo. The IO is started on the given
      * IORuntime (not the global one, unless the caller imports
      * `cats.effect.unsafe.implicits.global`).
      *
      *   - IO success -> Kyo success; IO error -> `Abort.fail(throwable)`; IO
      *     self-cancel -> `Interrupted` panic.
      *   - Interrupting the Kyo fiber cancels the IO. The cancel is signalled,
      *     not awaited: the IO's finalizers run on the IORuntime shortly after
      *     the Kyo fiber is already interrupted.
      */
    def get[A](
        io: => CatsIO[A],
    )(using rt: IORuntime, frame: Frame): A < (Abort[Throwable] & Async) =
      fromCancelableFuture(io.unsafeToFutureCancelable()(using rt))

    /** Interprets a Kyo computation as a cats-effect IO. Only
      * `Abort[Throwable] & Async` may be pending: handle `Scope`, `Env`, `Var`,
      * ... before. Cancelling the IO interrupts the Kyo fiber and waits until
      * it has completed (so Kyo finalizers have run when the cancel returns).
      */
    def run[A](v: => A < (Abort[Throwable] & Async))(using
        frame: Frame,
    ): CatsIO[A] =
      runF[CatsIO, A](v)

    // ------------------------------------------------------------------------------------------------
    // F[_]-polymorphic API
    // ------------------------------------------------------------------------------------------------

    /** Kyo -> F. Needs only `Async[F]` (no runtime, no Dispatcher): the Kyo
      * fiber runs on Kyo's scheduler and completes the F's async callback.
      */
    def runF[F[_], A](
        v: => A < (Abort[Throwable] & Async),
    )(using F: CEAsync[F], frame: Frame): F[A] =
      F.uncancelable { poll =>
        F.defer {
          import AllowUnsafe.embrace.danger
          Fiber
            .initUnscoped(v)
            .map { fiber =>
              val join: F[A]                 =
                F.async[A] { cb =>
                  F.delay {
                    fiber.unsafe.onComplete(r => cb(r.map(_.eval).toEither))
                    /* Cancelable async node; the real finalizer is the onCancel
                     * below. */
                    Some(F.unit)
                  }
                }
              /* Cancel-and-await: interrupt the Kyo fiber and suspend until it
               * has really completed. */
              val interruptAndAwait: F[Unit] =
                F.async_[Unit] { k =>
                  fiber.unsafe.onComplete(_ => k(Right(())))
                  discard(fiber.unsafe.interrupt())
                }
              F.onCancel(poll(join), interruptAndAwait)
            }
            .handle(Sync.Unsafe.evalOrThrow)
        }
      }
    end runF

    /** F -> Kyo, through a `Dispatcher[F]` (the polymorphic way to start an F
      * from the outside). The F runs on the runtime that owns the Dispatcher.
      * Interrupting the Kyo fiber cancels the F (signalled, not awaited).
      */
    def getF[F[_], A](
        fa: => F[A],
    )(using d: Dispatcher[F], frame: Frame): A < (Abort[Throwable] & Async) =
      fromCancelableFuture(d.unsafeToFutureCancelable(fa))

    /** Runs a Kyo program in F and gives it a Dispatcher bound to the caller's
      * runtime, so nested `getF` calls start their F effects on the same
      * runtime as the caller (no global runtime anywhere).
      */
    def runWithDispatcher[F[_], A](
        v: Dispatcher[F] ?=> A < (Abort[Throwable] & Async),
    )(using
        F: CEAsync[F],
        frame: Frame,
    ): F[A] =
      /* await = FALSE on purpose: with cats-effect 3.7.1,
       * Dispatcher.parallel(await = true) can LOSE a cancel issued */
      /* from a foreign thread while the release is starting (the task's
       * onCancel never runs, the cancel Future never */
      /* completes and the release waits forever). Reproduced without Kyo: 3
       * hangs in 10 rounds */
      /* (dbg/PureDispatcher.scala); await = false: 0 in 1000. With await =
       * false the release cancels what is left. */
      Dispatcher.parallel[F](await = false).use(d => runF(v(using d)))

    // ------------------------------------------------------------------------------------------------
    // shared
    // ------------------------------------------------------------------------------------------------

    // kyo.discard is private[kyo]; outside package kyo we need our own.
    private inline def discard[A](inline v: => A): Unit =
        val _ = v
        ()

    private def fromCancelableFuture[A](
        start: => (Future[A], () => Future[Unit]),
    )(using
        frame: Frame,
    ): A < (Abort[Throwable] & Async) =
      Sync.Unsafe.defer {
        val p          = Promise.Unsafe.init[A, Abort[Throwable]]()
        val cancelRef  = new AtomicReference[() => Future[Unit]](null)
        val settled    = new AtomicBoolean(false) /* the cats side finished on its
         * own */
        val cancelling = new AtomicBoolean(false) /* we cancelled it because Kyo
         * was interrupted */
        /* Sync.ensure, not `p.onInterrupt` (as in kyo-cats RC5 / kyo-zio RC7):
         * `ensure` is a region from the */
        /* start, so its release also runs when the fiber is interrupted AFTER
         * the effect was started but BEFORE the */
        /* fiber reached the join on `p` (the remainder is abandoned, the join's
         * interrupt cascade is never */
        /* registered, and `p.onInterrupt` never fires: the started effect
         * leaked, VERIFIED by a hanging test). */
        Sync.ensure { (outcome: Maybe[Result.Error[Any]]) =>
          if outcome.nonEmpty && !settled.get() then
              val cancel = cancelRef.get()
              if cancel ne null then
                  cancelling.set(true)
                  discard(cancel())
          end if
        } {
          Sync.Unsafe.defer {
            val (future, cancel) = start
            cancelRef.set(cancel)
            future.onComplete { r =>
              settled.set(true)
              r match
                  case Success(v)                        => p.completeDiscard(Result.succeed(v))
                  /* A failure caused by our own cancellation must not race in
                   * as a panic. */
                  case Failure(_) if cancelling.get()    => ()
                  case Failure(_: CancellationException) =>
                    p.completeDiscard(
                      Result.panic(Interrupted(frame, "cats-effect cancel")),
                    )
                  case Failure(ex)                       => p.completeDiscard(Result.fail(ex))
              end match
            }(using ExecutionContext.parasitic)
            p.safe.get
          }
        }
      }
    end fromCancelableFuture

end CatsBridge
