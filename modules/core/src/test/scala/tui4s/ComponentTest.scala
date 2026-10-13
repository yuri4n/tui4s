package tui4s

import java.time.Duration as JDuration
import java.util.concurrent.atomic.AtomicInteger
import jatatui.core.backend.TestBackend
import kyo.*
import kyo.UI.*
import tui4s.Hooks.*
import tui4s.internal.Canvas
import tui4s.internal.Knobs
import tui4s.internal.Msg
import tui4s.internal.Runner
import tui4s.spi.Settle
import tui4s.testkit.Headless

/** The components (C4): R1 for the fibers that a component mount or a hook
  * starts; the state key of a `Component`.
  */
// claude 2026-10-13: created (C4, RC-8); changed: the state key (FC-17)
// claude 2026-10-13: changed (C4): the helpers are in ComponentSuite
class ComponentTest extends ComponentSuite:

    /** The body of a hook component: an effect that waits for ever and counts
      * its own end (its `Scope` closes), an interval that counts its ticks, and
      * a `Scope.ensure` of the body itself (it runs when the unmount, or the
      * end of the run, closes the body's `Scope`).
      */
    def hookBody(ticks: AtomicInteger, ends: AtomicInteger)(using
        Frame,
    ): UI < (Async & Scope) =
      for
          _ <- useEffect(
                 Scope
                   .ensure(Sync.defer(ends.incrementAndGet()))
                   .andThen(Async.never[Unit]),
               )
          _ <- useInterval(10.millis)(Sync.defer(ticks.incrementAndGet()))
          _ <- Scope.ensure(Sync.defer(ends.incrementAndGet()))
      yield span("hook")

    /** A `Component` whose `onMount` starts an interval. */
    final class Ticker(ticks: AtomicInteger) extends Component:
        type State = Int
        def initial: Int                                               = 0
        def view(n: SignalRef[Int]): UI                                =
          span(n.map(v => s"ticker $v"))
        override def onMount(n: SignalRef[Int]): Any < (Async & Scope) =
          useInterval(10.millis)(Sync.defer(ticks.incrementAndGet()))

    "R1: 0 effect fibers after an INTERRUPTED run (a hook component with useEffect and useInterval; a Component whose onMount starts an interval)" in {
      for
          ticks  <- Sync.defer(AtomicInteger(0))
          ends   <- Sync.defer(AtomicInteger(0))
          inbox  <- Channel.initUnscoped[Msg](1024)
          canvas <- Sync.defer(Canvas(TestBackend(60, 12)))
          runner  = Runner(inbox, canvas, TuiOptions(), Absent)
          ui      = div(
                      span("effects"),
                      component(hookBody(ticks, ends)),
                      Ticker(ticks),
                    )
          fiber  <- Fiber.initUnscoped(runner.run(ui))
          // the repair tick, the hook fiber, its effect and its interval, the
          // onMount fiber and its interval, the watcher of the ticker's span
          ok     <- waitFor(5.seconds)(runner.liveFibers.map(_ >= 7))
          live0  <- runner.liveFibers
          t0     <- waitFor(2.seconds)(read(ticks).map(_ >= 4))
          _      <- fiber.interrupt
          ms     <- waitFor(2.seconds)(runner.liveFibers.map(_ == 0))
          ended  <- waitFor(2.seconds)(read(ends).map(_ == 2))
          still  <- stopped(ticks)
      yield
          assert(
            ok >= 0L,
            s"the gauge before the interrupt: $live0 (7 or more)",
          )
          assert(t0 >= 0L, "the intervals did not tick before the interrupt")
          assert(ms >= 0L, "effect fibers stay after the interrupted run")
          assert(
            ended >= 0L,
            "the Scope of the hook body and of its effect did not close",
          )
          assert(still, "an interval ticks after the interrupted run")
      end for
    }

    "R1: 0 effect fibers after a quit, also for a hook at the top of the app (before the kit's scope ends)" in {
      for
          ticks <- Sync.defer(AtomicInteger(0))
          ends  <- Sync.defer(AtomicInteger(0))
          top   <- Sync.defer(AtomicInteger(0))
          res   <- Scope.run(
                     Headless
                       .start(60, 12)(_ =>
                         for
                             // a hook at the top: its Scope is the kit's, which
                             // closes only when the test's scope ends
                             _ <- useInterval(10.millis)(
                                    Sync.defer(top.incrementAndGet()),
                                  )
                         yield div(
                           span("top"),
                           component(hookBody(ticks, ends)),
                         ),
                       )
                       .map { h =>
                         for
                             live0 <- waitFor(2.seconds)(
                                        h.liveFibers.map(_ >= 5),
                                      )
                             t0    <- waitFor(2.seconds)(
                                        read(top).map(_ >= 4),
                                      )
                             _     <- h.quit
                             fin   <- h.awaitEnd(10.seconds)
                             ms    <- waitFor(2.seconds)(
                                        h.liveFibers.map(_ == 0),
                                      )
                             ended <- waitFor(2.seconds)(read(ends).map(_ == 2))
                             still <- stopped(top)
                         yield (live0, t0, fin, ms, ended, still)
                       },
                   )
      yield
          val (live0, t0, fin, ms, ended, still) = res
          // the behaviour first, then the gauge
          assert(t0 >= 0L, "the top-level interval did not tick")
          assert(fin, "the run did not end after the quit")
          assert(
            still,
            "the top-level interval ticks after the run ended (before the kit's scope ends)",
          )
          assert(ended >= 0L, "the Scope of the hook component did not close")
          assert(live0 >= 0L, "the gauge did not count the hook fibers")
          assert(ms >= 0L, "effect fibers stay after the quit")
      end for
    }

    "R1: 0 effect fibers after an interrupt INSIDE A SETTLE (a component mounted in flight)" in {
      // As the WatcherTest leaf: a CPU-bound fiber keeps the probe busy, so the
      // settle of the first write runs on. The second write is absorbed inside
      // that settle: its remount mounts a hook component, whose fiber is in
      // the step's state only (not in `last`) when the interrupt lands.
      val options =
        TuiOptions(settle = Settle.Quiescent(JDuration.ofSeconds(2)))
      for
          ticks  <- Sync.defer(AtomicInteger(0))
          ends   <- Sync.defer(AtomicInteger(0))
          a      <- Signal.initRef(0)
          inbox  <- Channel.initUnscoped[Msg](1024)
          canvas <- Sync.defer(Canvas(TestBackend(60, 12)))
          runner  = Runner(inbox, canvas, options, Absent)
          ui      = div(
                      a.render(n =>
                        if n == 2 then component(hookBody(ticks, ends))
                        else span(s"n=$n"),
                      ),
                    )
          fiber  <- Fiber.initUnscoped(runner.run(ui))
          _      <- waitFor(5.seconds)(runner.liveFibers.map(_ >= 2))
          _      <- Async.sleep(100.millis) // the arm steps of the start pass
          busy   <- Fiber.initUnscoped(Sync.defer(spin(1000)))
          _      <- Async.sleep(20.millis)
          _      <- a.set(1)                // a step; its settle runs on
          _      <- Async.sleep(100.millis)
          _      <- a.set(2)                // absorbed inside that settle
          t0     <- waitFor(1.second)(read(ticks).map(_ >= 3))
          live0  <- runner.liveFibers
          _      <- fiber.interrupt
          ms     <- waitFor(2.seconds)(runner.liveFibers.map(_ == 0))
          ended  <- waitFor(2.seconds)(read(ends).map(_ == 2))
          _      <- busy.get
          still  <- stopped(ticks)
      yield
          // the behaviour first, then the gauge
          assert(t0 >= 0L, "the component did not mount inside the settle")
          assert(
            ended >= 0L,
            "the Scope of the hook component did not close (its fiber was not stopped)",
          )
          assert(still, "the interval ticks after the interrupted run")
          // the repair tick, the watcher of `a`, the hook fiber, its effect
          // and its interval
          assert(live0 >= 5, s"fibers before the interrupt: $live0")
          assert(ms >= 0L, "effect fibers stay after the interrupt")
      end for
    }

    // ---- the state key (plan 0.2, FC-17) ----

    /** A counter whose text starts with `name`; `+` (Enter on the button) adds
      * one.
      */
    def counterView(name: String, n: SignalRef[Int])(using Frame): UI =
      div(
        span(n.map(v => s"[$name=$v]")),
        button("+").onClick(n.updateAndGet(_ + 1)),
      )

    /** Two classes with the same state type and the same initial value. */
    final class CountA extends Component:
        type State = Int
        def initial: Int                = 0
        def view(n: SignalRef[Int]): UI = counterView("A", n)

    final class CountB extends Component:
        type State = Int
        def initial: Int                = 0
        def view(n: SignalRef[Int]): UI = counterView("B", n)

    /** One class, one state per label: a custom state key. */
    final class PerLabel(label: String) extends Component:
        type State = Int
        def initial: Int                        = 0
        def view(n: SignalRef[Int]): UI         = counterView(label, n)
        override protected def stateKey: String = s"PerLabel:$label"

    /** One class, the default state key (the class name). */
    final class SameKey(label: String) extends Component:
        type State = Int
        def initial: Int                = 0
        def view(n: SignalRef[Int]): UI = counterView(label, n)

    /** Starts `ui` over the signal `which`, adds 3 to the first component, then
      * switches `which` to `next` and waits for `expect`. The screen after the
      * switch.
      */
    def switchAfter3(which: SignalRef[String], ui: UI, first: String)(
        next: String,
        expect: String,
    )(using Frame, kyo.test.AssertScope): (Boolean, String) < (Async & Scope) =
      for
          h  <- Headless.start(40, 6)(_ => ui)
          _  <- Kyo.foreachDiscard(1 to 3)(_ => h.key(Keyboard.Enter))
          ok <- h.awaitScreen(showing(s"[$first=3]"), 2.seconds)
          _   = assert(ok, s"the first component did not count to 3")
          _  <- which.set(next)
          ok <- h.awaitScreen(showing(expect), 2.seconds)
          s  <- h.screen
      yield (ok, s.text)

    "state key: two Component classes at one position do not share state" in {
      for
          which     <- Signal.initRef("A")
          ui         =
            div(
              which.render(w => if w == "A" then CountA().ui else CountB().ui),
            )
          (ok, txt) <- switchAfter3(which, ui, "A")("B", "[B=0]")
      yield assert(ok && !txt.contains("[B=3]"), txt)
    }

    "state key: a custom stateKey separates two instances of one class at one position; without it they share (the default)" in {
      for
          who       <- Signal.initRef("x")
          custom     = div(who.render(l => PerLabel(l).ui))
          (ok1, t1) <- switchAfter3(who, custom, "x")("y", "[y=0]")
          _          = assert(ok1 && !t1.contains("[y=3]"), t1)
          who2      <- Signal.initRef("x")
          default    = div(who2.render(l => SameKey(l).ui))
          (ok2, t2) <- switchAfter3(who2, default, "x")("y", "[y=3]")
      yield assert(
        ok2,
        s"the default key (the class name) shares the state: $t2",
      )
    }

    // ---- the mount of a Component ----

    /** A Component that counts the runs of its `onMount`; the effect waits for
      * ever, and its `Scope.ensure` counts the ends.
      */
    final class Mounts(runs: AtomicInteger, ends: AtomicInteger)
        extends Component:
        type State = Int
        def initial: Int                                               = 0
        def view(n: SignalRef[Int]): UI                                = counterView("m", n)
        override def onMount(n: SignalRef[Int]): Any < (Async & Scope) =
          Sync
            .defer(runs.incrementAndGet())
            .andThen(Scope.ensure(Sync.defer(ends.incrementAndGet())))
            .andThen(Async.never[Unit])
    end Mounts

    "Component.onMount runs once per mount: a parent that renders again (also the verify pass and the repair tick) keeps the mount, its state and its effects; the unmount ends them" in {
      // a repair tick every 50 ms: it renders the parent again (its UI holds
      // closures), as the 1 s tick does in a real run
      Knobs.let(Knobs(repairInterval = 50.millis)) {
        for
            runs  <- Sync.defer(AtomicInteger(0))
            ends  <- Sync.defer(AtomicInteger(0))
            title <- Signal.initRef(0)
            shown <- Signal.initRef(true)
            h     <- Headless.start(40, 6)(_ =>
                       div(
                         when(shown)(
                           div(
                             title.render(t =>
                               div(span(s"[t=$t]"), Mounts(runs, ends).ui),
                             ),
                           ),
                         ),
                       ),
                     )
            ok0   <- h.awaitScreen(showing("[m=0]"), 2.seconds)
            _     <- h.key(Keyboard.Enter)
            ok1   <- h.awaitScreen(showing("[m=1]"), 2.seconds)
            _     <- title.set(1)
            ok2   <- h.awaitScreen(showing("[t=1]"), 2.seconds)
            _     <- title.set(2)
            ok3   <- h.awaitScreen(showing("[t=2]"), 2.seconds)
            _     <- Async.sleep(300.millis) // about 6 repair ticks
            r     <- read(runs)
            e     <- read(ends)
            s     <- h.screen
            _     <- shown.set(false)
            gone  <- h.awaitScreen(!_.contains("[m="), 2.seconds)
            ended <- waitFor(2.seconds)(read(ends).map(_ == 1))
        yield
            assert(ok0 && ok1 && ok2 && ok3, s.text)
            assert(
              r == 1 && e == 0,
              s"onMount ran $r times and ended $e times while the mount lasted",
            )
            assert(s.contains("[m=1]"), s.text)
            assert(gone && ended >= 0L, "the unmount did not end onMount")
        end for
      }
    }
end ComponentTest
