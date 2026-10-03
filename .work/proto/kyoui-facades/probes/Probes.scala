package probes

import scala.compiletime.testing.typeCheckErrors
import zio.ZIO

class Box:
    def put(x: Int): String     = "member(Int)"
    def lifted(x: => Any): String = "member(=> Any)"

object BoxExt:
    extension (b: Box)
        def put(x: String): String                    = "extension(String)"
        def lifted(x: ZIO[Any, Nothing, Any]): String = "extension(ZIO)"

object KyoExt:
    extension (b: kyo.UI.Ast.Button)
        def onClick(z: ZIO[Any, Nothing, Any]): String = "extension(ZIO)"

@main def runProbes(): Unit =
    import BoxExt.*
    val b = Box()
    println("P1a member applicable (Int arg):          " + b.put(1))
    println("P1b member NOT applicable (String arg):   " + b.put("s"))
    println("P1c member applicable via Any (ZIO arg):   " + b.lifted(ZIO.unit))

    import KyoExt.*
    val chosen: Any = kyo.UI.button.onClick(ZIO.succeed(println("ZIO ran")))
    println("P1d kyo-ui Button.onClick(ZIO) picks:      " + chosen.getClass.getSimpleName)

    // P3: where does the frame point?
    println("P3a Here at the call site:                 " + _root_.probes.mirror.where)
    println("P3b frame stored in the kyo-ui node:       " + _root_.probes.mirror.mirrorButton.frame.position.show)

    // P4: the raw kyo-ui setter with a ZIO: does it warn / fail?
    val errs = typeCheckErrors("""kyo.UI.button.onClick(zio.ZIO.succeed(1))""")
    println("P4a raw kyo-ui onClick(ZIO) type errors:   " + errs.map(_.message))
    val errs2 = typeCheckErrors("""kyo.UI.button.onClick(println("x"))""")
    println("P4b raw kyo-ui onClick(Unit) type errors:  " + errs2.map(_.message))
end runProbes
