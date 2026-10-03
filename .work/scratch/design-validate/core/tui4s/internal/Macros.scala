package tui4s.internal

import kyo.*
import scala.quoted.*
import tui4s.NotForeign
import tui4s.spi.Emitter

private[tui4s] object Macros:
  private val foreign: Map[String, String] = Map(
    "zio.ZIO"                 -> "a zio.ZIO",
    "zio.stream.ZStream"      -> "a zio.stream.ZStream",
    "cats.effect.IO"          -> "a cats.effect.IO",
    "fs2.Stream"              -> "an fs2.Stream",
    "scala.concurrent.Future" -> "a scala.concurrent.Future"
  )

  def notForeign[A: Type](using Quotes): Expr[NotForeign[A]] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[A].dealias.widen
    tpe.baseClasses.iterator.map(_.fullName).find(foreign.contains) match
      case Some(name) =>
        report.errorAndAbort(
          s"source.task got ${foreign(name)} (${tpe.show}). A Kyo app runs Kyo computations only. " +
            "In a ZIO app import tui4s.zio.*; in a Cats Effect app import tui4s.cats.*; " +
            "in a Kyo app convert it first (kyo-zio ZIOs.get, the tui4s-cats bridge, Async.fromFuture)."
        )
      case None => '{ NotForeign.Evidence.asInstanceOf[NotForeign[A]] }
  end notForeign
end Macros

/** Signal watchers (5.8). */
private[tui4s] object Watch:
  def signal[A](sig: Signal[A], emit: Emitter[A])(using Frame): Unit < Async =
    sig match
      case ref: Signal.SignalRef[A] @unchecked => exact(ref, emit)
      case other                               => loose(other, emit)

  /** Register for the next change FIRST, then read the current value: no write is lost, no timer runs. */
  private def exact[A](ref: Signal.SignalRef[A], emit: Emitter[A])(using Frame): Unit < Async =
    Loop(Maybe.empty[A]) { last =>
      Sync.Unsafe.defer {
        val next = ref.unsafe.next() // 1. register for the next change FIRST
        (next, ref.unsafe.get())     // 2. then read the current value
      }.map { (next, now) =>
        val sent = if last.contains(now) then Kyo.unit else emit(now) // skip a repeat
        sent.andThen(next.safe.get).andThen(Loop.continue(Present(now)))
      }
    }

  /** Any other Signal: read, then wait for the next change (a write in between shows at the next write). */
  private def loose[A](sig: Signal[A], emit: Emitter[A])(using Frame): Unit < Async =
    sig.current.map(a => emit(a)).andThen(Loop.forever(sig.next.map(a => emit(a))))
end Watch
