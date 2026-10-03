package probes.errors

import scala.compiletime.testing.typeCheckErrors
import zio.ZIO

object M:
    opaque type B = String
    def b: B = "b"
    extension (e: B)
        def onClick(action: ZIO[Any, Nothing, Any]): B = e
        def onClick(f: Int => ZIO[Any, Nothing, Any]): B = e
        /** Catch-all: a precise message instead of "does not take parameters". */
        inline def onClick[A](inline other: A)(using DummyImplicit): B =
            scala.compiletime.error(
                "tui4s: a handler is a ZIO[Any, Nothing, Any] (or Int => ZIO[Any, Nothing, Any]). " +
                    "Handle the errors of a ZIO that may fail; a plain value would never run."
            )
end M

@main def errorProbe(): Unit =
    import M.*
    println("E1 ok ZIO:      " + typeCheckErrors("""b.onClick(ZIO.unit)""").map(_.message))
    println("E2 ok lambda:   " + typeCheckErrors("""b.onClick(i => ZIO.succeed(i))""").map(_.message))
    println("E3 Unit:        " + typeCheckErrors("""b.onClick(println("x"))""").map(_.message))
    println("E4 failing ZIO: " + typeCheckErrors("""b.onClick(ZIO.attempt(1))""").map(_.message))
    println("E5 lambda Unit: " + typeCheckErrors("""b.onClick(i => println(i))""").map(_.message))
