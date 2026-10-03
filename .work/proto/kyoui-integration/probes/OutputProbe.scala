package integration

import cats.effect.IO
import cats.effect.IOApp
import zio.ZIO
import zio.ZIOAppDefault

// EXTRA probe (not part of the task's apps): where does output of a handler go while the terminal UI runs? The runner's
// OutputGuard swaps System.out / System.err for a file (tui4s.log). This checks four kinds of output from a ZIO handler
// and three from a Cats handler. Each press of the button writes one line of each kind.

// claude 2026-10-03: created the ZIO output probe (ZIO.logInfo, zio.Console.printLine, scala println, System.out)
object ZioOutputProbeMain extends ZIOAppDefault:
    import tui4s.interop.zio.ui.*
    val app =
        for n <- useSignal(0)
        yield div(
            n.render(v => p(s"presses: $v")),
            button("write").id("write").onClick(
                ZIO.logInfo("PROBE-ZIO-LOGINFO") *>
                    zio.Console.printLine("PROBE-ZIO-CONSOLE").orDie *>
                    ZIO.succeed(println("PROBE-ZIO-PRINTLN")) *>
                    ZIO.succeed(java.lang.System.out.println("PROBE-ZIO-SYSTEMOUT")) *>
                    n.update(_ + 1)
            )
        )
    def run = Tui.run(app)
end ZioOutputProbeMain

// claude 2026-10-03: created the Cats output probe (IO.println, scala println, System.out)
object CatsOutputProbeMain extends IOApp.Simple:
    import tui4s.interop.cats.ui.*
    val app =
        useSignal[IO](0).map(n =>
            div(
                n.render(v => p(s"presses: $v")),
                button("write").id("write").onClick(
                    IO.println("PROBE-CATS-IOPRINTLN") *>
                        IO(println("PROBE-CATS-PRINTLN")) *>
                        IO(java.lang.System.out.println("PROBE-CATS-SYSTEMOUT")) *>
                        n.update(_ + 1)
                )
            )
        )
    def run: IO[Unit] = Tui.run(app)
end CatsOutputProbeMain

// claude 2026-10-03: created the "early println" variant: the program prints one line BEFORE Tui.run, so scala.Console is
// loaded (and keeps the real System.out) before the runner swaps System.out
object ZioOutputProbeEarlyMain extends ZIOAppDefault:
    def run = ZIO.succeed(println("PROBE-EARLY-LINE before Tui.run")) *> tui4s.interop.zio.ui.Tui.run(ZioOutputProbeMain.app)
end ZioOutputProbeEarlyMain
