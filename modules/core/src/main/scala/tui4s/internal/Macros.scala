package tui4s.internal

import scala.quoted.*
import tui4s.NotForeign

/** The macro of [[tui4s.NotForeign]]. */
private[tui4s] object Macros:
    /** Full class name -> (what it is, how to fix it). */
    private val foreign: Map[String, (String, String)] = Map(
      "zio.ZIO"                 -> ("a zio.ZIO", "kyo-zio ZIOs.get"),
      "zio.stream.ZStream"      -> ("a zio.stream.ZStream", "kyo-zio ZStreams.get"),
      "cats.effect.IO"          -> ("a cats.effect.IO", "the tui4s-cats facade"),
      "fs2.Stream"              -> ("an fs2.Stream", "the tui4s-cats facade"),
      "scala.concurrent.Future" ->
        ("a scala.concurrent.Future", "Async.fromFuture"),
    )

    def notForeign[A: Type](using Quotes): Expr[NotForeign[A]] =
        import quotes.reflect.*
        val tpe = TypeRepr.of[A].dealias.widen
        tpe.baseClasses.iterator.map(_.fullName).find(foreign.contains) match
            case Some(name) =>
              val (what, fix) = foreign(name)
              report.errorAndAbort(
                s"source.task got $what (${tpe.show}). " +
                  "A Kyo app runs Kyo computations only. " +
                  "In a ZIO app import tui4s.interop.zio.*; " +
                  "in a Cats Effect app import tui4s.interop.cats.*; " +
                  s"in a Kyo app convert it first ($fix).",
              )
            case None       =>
              '{ NotForeign.Evidence.asInstanceOf[NotForeign[A]] }
        end match
    end notForeign
end Macros
