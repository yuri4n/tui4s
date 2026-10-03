package dev

import kyo.*
import kyo.UI.*
import tui4s.kyoui.*

/** Counts frames where one event's consequences are split over two frames. */
object Glitch:
    val app: UI < Sync =
        for
            a <- Signal.initRef(0)
            b <- Signal.initRef(0)
        yield div.onKeyDown {
            case KeyboardEvent(Keyboard.Char('+'), _, _) => a.updateAndGet(_ + 1).andThen(b.updateAndGet(_ + 1))
            case _                                       => ()
        }(
            span(a.map(n => s"a=$n")),
            span(b.map(n => s"b=$n")),
            span(a.combineLatest(b).map((x, y) => s"sum=${x + y}")),
            span(a.map(n => s"twice=${n * 2}"))
        )

    def run(settle: Settle, n: Int)(using Frame): (Int, Long) < (Async & Scope) =
        for
            h   <- Headless.start(40, 6, TuiOptions(settle = settle))(app)
            res <- Kyo.foreach(1 to n) { i =>
                h.char('+').map { f =>
                    h.screen.map { s =>
                        val ok = f.updates >= 4 && s.contains(s"a=$i") && s.contains(s"b=$i") && s.contains(s"sum=${2 * i}") &&
                            s.contains(s"twice=${2 * i}")
                        (if ok then 0 else 1, f.nanos)
                    }
                }
            }
            _ <- h.quit
        yield (res.map(_._1).sum, res.map(_._2).sorted.apply(n / 2) / 1000)

    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        val n = args.headOption.map(_.toInt).getOrElse(2000)
        val r = KyoApp.Unsafe.runAndBlock(600.seconds) {
            Kyo.foreach(Seq(Settle.Quiescent(20.millis), Settle.Hops(1, Duration.Zero), Settle.Hops(3, Duration.Zero),
                Settle.Hops(6, Duration.Zero), Settle.Hops(3, 250.micros), Settle.Quiescent(20.millis))) { st =>
                Scope.run(run(st, n)).map { (bad, med) =>
                    Console.printLine(s"$st: split frames=$bad of $n, median us=$med")
                }
            }
        }
        println(r)
    end main
end Glitch
