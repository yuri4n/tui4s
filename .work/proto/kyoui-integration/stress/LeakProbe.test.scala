package integration

import munit.FunSuite

// EXTRA leak probe. Compiled with the suites, IGNORED unless -Dprobe.leak=<rounds> is set. Run it with:
//   scala-cli test --jvm system . --java-prop probe.leak=300 --test-only integration.LeakProbeSuite
// It starts and stops a headless runner many times in one JVM and prints the used heap after a GC, for two ways to stop:
// (a) the Scope ends (the runner fiber is INTERRUPTED: what a test or a ZIO/Cats cancel does), (b) a quit message first
// (the runner loop ends by itself), then the Scope ends. It does this for the plain Kyo runner and for the ZIO and Cats
// glue. claude 2026-10-03: changed this comment: since the glue fix, TuiHeadless quits first, so the glue phases are (b).

// claude 2026-10-03: created the leak probe (start/stop many runners in one JVM)
class LeakProbeSuite extends FunSuite:
    override val munitTimeout = scala.concurrent.duration.Duration(600, "s")
    private val rounds = sys.props.getOrElse("probe.leak", "300").toInt
    override def munitIgnore: Boolean = !sys.props.contains("probe.leak")

    // claude 2026-10-03: created usedMb: used heap in MB after two GCs
    private def usedMb(): Long =
        val rt = java.lang.Runtime.getRuntime
        System.gc(); Thread.sleep(200); System.gc(); Thread.sleep(200)
        (rt.totalMemory - rt.freeMemory) / (1024 * 1024)

    // claude 2026-10-03: created phase: runs `rounds` rounds and prints the heap and the Kyo scheduler load
    private def phase(name: String)(round: => Unit): Unit =
        val before = usedMb()
        val t0     = System.nanoTime()
        (1 to rounds).foreach(_ => round)
        val ms     = (System.nanoTime() - t0) / 1000000
        Thread.sleep(1500) // two repair intervals of kyo's Signal.observe (1 s): leaked watchers wake up in this time
        val after = usedMb()
        val load  = tui4s.kyoui.internal.SchedulerProbe.totalLoad()
        println(f"[leak-probe] $name%-34s rounds=$rounds heapMB before=$before%4d after=$after%4d delta=${after - before}%4d " +
            f"perRoundKB=${(after - before) * 1024 / rounds}%5d wallMs=$ms%6d kyoLoadAfter=$load")

    test("plain Kyo runner (tui4s.kyoui.Headless) and the ZIO glue (TuiHeadless)") {
        import kyo.*
        import kyo.UI.*
        import tui4s.kyoui.Headless
        def runK[A](v: => A < (Async & Scope)): A =
            import AllowUnsafe.embrace.danger
            KyoApp.Unsafe.runAndBlock(100.seconds)(Scope.run(v)).getOrThrow
        val ui: UI < Sync =
            for n <- Signal.initRef(0)
            yield div(span(n.map(v => s"n=$v")), button("+").onClick(n.updateAndGet(_ + 1)))
        // claude 2026-10-03: changed the order: the leaking phase runs LAST (the first version ran out of heap after it)
        phase("kyo: quit, then Scope ends")(runK(Headless.start(100, 30)(ui).map(h =>
            h.key(Keyboard.Enter).andThen(h.quit).andThen(h.awaitEnd(10.seconds)).unit
        )))

        import tui4s.interop.zio.ui.TuiHeadless
        import tui4s.interop.zio.ui.useSignal
        import tui4s.interop.zio.ui.{div as zdiv, p as zp, button as zbutton, Keyboard as ZKeyboard, *}
        import zio.{Scope as ZScope, ZIO, Runtime, Unsafe, durationInt}
        def runZ[A](z: ZIO[ZScope, Any, A]): A = Unsafe.unsafe(implicit u => Runtime.default.unsafe.run(ZIO.scoped(z)).getOrThrowFiberFailure())
        val zapp = for n <- useSignal(0) yield zdiv(n.render(v => zp(s"n=$v")), zbutton("+").id("inc").onClick(n.update(_ + 1)))
        phase("zio glue: Scope ends (glue quits first)")(runZ(TuiHeadless.start(zapp).flatMap(h => h.key(ZKeyboard.Enter)).unit))
        phase("zio glue: Ctrl-C quit, then Scope ends")(runZ(TuiHeadless.start(zapp).flatMap(h =>
            h.key(ZKeyboard.Enter) *> h.key(ZKeyboard.Char('c'), ctrl = true) *> h.awaitEnd(10.seconds)
        ).unit))
        phase("kyo: interrupt (Scope ends)")(runK(Headless.start(100, 30)(ui).map(h => h.key(Keyboard.Enter).unit)))
    }

    // claude 2026-10-03: created the Cats glue phase
    test("Cats glue (TuiHeadless[IO])") {
        import cats.effect.{IO, Resource}
        import cats.effect.unsafe.IORuntime
        import tui4s.interop.cats.ui.*
        def runC[A](io: IO[A]): A = io.unsafeRunSync()(using IORuntime.global)
        val capp: Resource[IO, UI] = useSignal[IO](0).map(n => div(n.render(v => p(s"n=$v")), button("+").id("inc").onClick(n.update(_ + 1))))
        phase("cats glue: Resource ends")(runC(TuiHeadless.start(capp).use(h => h.key(Keyboard.Enter)).void))
    }
end LeakProbeSuite
