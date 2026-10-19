package tui4s

import jatatui.core.backend.TestBackend
import kyo.*
import kyo.UI.*
import tui4s.Hooks.*
import tui4s.internal.{
  Canvas,
  FiberGauge,
  Knobs,
  LogSink,
  Msg,
  RunOptions,
  Runner,
  TuiLog,
}
import tui4s.spi.Settle
import tui4s.testkit.{Headless, Screen}

import java.time.Duration as JDuration
import java.util.concurrent.atomic.AtomicInteger

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
          // claude 2026-10-19: changed: Runner.init with RunOptions (A10, P2)
          runner <-
            Runner.init(inbox, canvas, RunOptions.of(TuiOptions()), Absent)
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
          // claude 2026-10-19: changed: Runner.init with RunOptions (A10, P2)
          runner <- Runner.init(inbox, canvas, RunOptions.of(options), Absent)
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

    // ---- the gauge (P2, amendment A7) ----

    // claude 2026-10-19: created (P2, A7): the close of FiberGauge
    "gauge: a fiber that joins the gauge after its close is interrupted at once (fork and add)" in {
      for
          g      <- FiberGauge.init
          _      <- g.close
          forked <- g.fork(Async.never[Unit])
          plain  <- Fiber.initUnscoped(Async.never[Unit])
          _      <- g.add(plain)
          ms     <- waitFor(2.seconds)(forked.done.map(a => plain.done.map(a && _)))
          live   <- g.live
      yield
          assert(ms >= 0L, "a fiber added after the close still runs")
          assert(live == 0, s"the closed gauge counts $live live fibers")
      end for
    }

    // claude 2026-10-19: created (P2, A7): adds that race with the close.
    // The bracket of `fork` (no interrupt between the spawn and the add) is
    // Kyo's Bracket contract and is not under test here.
    "gauge: a close during adds leaves no live fiber (50 rounds of 20 forks and one close behind a latch)" in {
      def round(using Frame): (Long, Int) < Async =
        for
            g       <- FiberGauge.init
            go      <- Latch.init(1)
            forkers <- Kyo.foreach(1 to 20)(_ =>
                         Fiber.initUnscoped(
                           go.await.andThen(g.fork(Async.never[Unit])),
                         ),
                       )
            closer  <- Fiber.initUnscoped(go.await.andThen(g.close))
            _       <- go.release
            forked  <- Kyo.foreach(forkers)(_.get)
            _       <- closer.get
            ms      <- waitFor(2.seconds)(
                         Kyo.foldLeft(forked)(true)((ok, f) => f.done.map(ok && _)),
                       )
            live    <- g.live
        yield (ms, live)
      Kyo.foreach(1 to 50)(_ => round).map { rounds =>
        val bad = rounds.zipWithIndex.filter { case ((ms, live), _) =>
          ms < 0L || live != 0
        }
        assert(bad.isEmpty, s"rounds with a live fiber: $bad")
      }
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

    // claude 2026-10-13: created (C4, review R2-4)
    "state key: two positions whose paths join to one string (a keyed item 'a/0', and the first child of the item 'a') do not share state" in {
      for
          items <- Signal.initRef(Chunk("a/0", "a"))
          row    = (k: String) =>
                     if k == "a/0" then (SameKey("x").ui: UI)
                     else div(SameKey("y").ui)
          h     <-
            Headless.start(30, 6)(_ => div(items.foreachKeyed(identity)(row)))
          ok0   <- h.awaitScreen(
                     s => s.contains("[x=0]") && s.contains("[y=0]"),
                     2.seconds,
                   )
          _     <- h.key(Keyboard.Enter) // the `+` of x: the first focus
          ok1   <- h.awaitScreen(showing("[x=1]"), 2.seconds)
          _     <- Async.sleep(200.millis)
          s     <- h.screen
      yield
          assert(ok0 && ok1, s.text)
          assert(
            s.contains("[y=0]"),
            s"the two components share one state: ${s.text}",
          )
      end for
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

    /** A Component whose `onMount` starts an interval and returns at once. */
    final class Ticking(ticks: AtomicInteger) extends Component:
        type State = Int
        def initial: Int                                               = 0
        def view(n: SignalRef[Int]): UI                                =
          span(n.map(v => s"[ticking=$v]"))
        override def onMount(n: SignalRef[Int]): Any < (Async & Scope) =
          useInterval(20.millis)(
            n.updateAndGet(_ + 1).andThen(Sync.defer(ticks.incrementAndGet())),
          )
    end Ticking

    "Component.onMount: an effect that it starts lives as long as the mount, also when onMount returns at once; the unmount ends it" in {
      for
          ticks <- Sync.defer(AtomicInteger(0))
          shown <- Signal.initRef(true)
          h     <- Headless.start(30, 3)(_ => div(when(shown)(Ticking(ticks).ui)))
          ok    <- h.awaitScreen(
                     _.rows.exists(_.matches(""".*\[ticking=([5-9]|\d\d+)\].*""")),
                     3.seconds,
                   )
          t1    <- read(ticks)
          _     <- shown.set(false)
          gone  <- h.awaitScreen(!_.contains("[ticking="), 2.seconds)
          still <- stopped(ticks)
          s     <- h.screen
      yield
          assert(
            ok,
            s"the interval of onMount stopped when onMount returned: $t1 ticks; ${s.text}",
          )
          assert(gone && still, "the interval ticks after the unmount")
      end for
    }

    // ---- the pending frame (TuiOptions.pendingTimeout) ----

    /** A hook component behind a key: `s` shows it, and its body takes `delay`
      * before it gives its UI. The root has the key handler (with no focusable
      * element, a key goes to the root); `extra` is its first child.
      */
    def slowApp(delay: Duration, extra: UI = UI.empty)(using
        Frame,
    ): UI < Sync =
      Signal.initRef(false).map { shown =>
        div.onKeyDown {
          case KeyboardEvent(Keyboard.Char('s'), _, _) => shown.set(true)
          case _                                       => ()
        }(
          extra,
          span("[top]"),
          when(shown)(component(Async.sleep(delay).andThen(span("[slow]")))),
          span("[bottom]"),
        )
      }

    /** The milliseconds of a frame's step. */
    def ms(f: FrameStats): Long = f.nanos / 1_000_000

    "pending frame: a component whose first content takes longer than pendingTimeout: the frame comes without it (an empty place), and the content follows in a later frame" in {
      // The body takes 1.5 s and the frame waits at most 100 ms. Allowance:
      // the frame of the key comes after at least 90 ms (the wait) and
      // before 1 s (the wait, one settle, a loaded machine), well before the
      // content.
      val options = TuiOptions(pendingTimeout = JDuration.ofMillis(100))
      for
          h  <- Headless.start(30, 4, options)(_ => slowApp(1500.millis))
          f  <- h.char('s')
          s1 <- h.screen
          ok <- h.awaitScreen(showing("[slow]"), 5.seconds)
          fs <- framesUntil(h, 1.second)(f =>
                  f.cause == FrameCause.Background && f.flushed > 0,
                )
      yield
          java.lang.System.out.println(
            s"[pending] the frame of the key: ${ms(f)} ms, without the content; then ${fs
                .count(f => f.cause == FrameCause.Background && f.flushed > 0)} Background frame(s) drew it",
          )
          assert(ms(f) >= 90L, s"the frame did not wait: ${ms(f)} ms")
          assert(
            ms(f) < 1000L,
            s"the frame waited for the content: ${ms(f)} ms",
          )
          // claude 2026-10-13: changed (C4, review R1-1, R2-1): the wait is
          // not a settle that ran out of its max
          assert(
            f.settleTimeouts == 0,
            s"the wait for the component counted as a settle timeout: $f",
          )
          assert(
            s1.contains("[top]") && s1.contains("[bottom]") &&
              !s1.contains("[slow]"),
            s1.text,
          )
          assert(ok, "the content did not come in a later frame")
          assert(
            fs.exists(f => f.cause == FrameCause.Background && f.flushed > 0),
            s"no Background frame drew the content: $fs",
          )
      end for
    }

    "pending frame: with a pendingTimeout longer than the body, the frame of the event waits and holds the content" in {
      val options = TuiOptions(pendingTimeout = JDuration.ofSeconds(5))
      for
          h  <- Headless.start(30, 4, options)(_ => slowApp(300.millis))
          f  <- h.char('s')
          s1 <- h.screen
      yield
          assert(s1.contains("[slow]"), s1.text)
          assert(ms(f) >= 250L, s"the frame came after ${ms(f)} ms")
          // claude 2026-10-13: changed (C4, review R1-1, R2-1): the wait is
          // not a settle that ran out of its max, and the settle after it
          // confirms the quiescence as usual
          assert(
            f.settleTimeouts == 0,
            s"the wait for the component counted as a settle timeout: $f",
          )
      end for
    }

    /** The value of `[tick=<n>]` on the screen, or -1. */
    def tickOn(s: Screen): Int =
      """\[tick=(\d+)\]""".r
        .findFirstMatchIn(s.text)
        .flatMap(m => Option(m.group(1)).flatMap(_.toIntOption))
        .getOrElse(-1)

    "pending frame: the wait is bounded in total, also when other messages arrive while it waits (a region that changes every 50 ms keeps changing on the screen)" in {
      // pendingTimeout 200 ms; the body takes 3 s; a top-level interval
      // changes another region every 50 ms. While a settle waits for the
      // component, no frame comes, so the tick on the screen stops. The
      // allowance: after the key and 400 ms (the wait and one settle), the
      // tick on the screen moves on by 5 (250 ms of ticks) within 1.5 s, long
      // before the content (3 s).
      val options = TuiOptions(pendingTimeout = JDuration.ofMillis(200))
      for
          h   <- Headless.start(40, 5, options)(_ =>
                   for
                       n  <- Signal.initRef(0)
                       _  <- useInterval(50.millis)(n.updateAndGet(_ + 1))
                       ui <- slowApp(3.seconds, span(n.map(v => s"[tick=$v]")))
                   yield ui,
                 )
          ok0 <- h.awaitScreen(s => tickOn(s) >= 3, 2.seconds)
          f   <- h.char('s')
          _   <- Async.sleep(400.millis)
          s1  <- h.screen
          ok1 <- h.awaitScreen(s => tickOn(s) >= tickOn(s1) + 5, 1500.millis)
          s2  <- h.screen
          ok2 <- h.awaitScreen(showing("[slow]"), 5.seconds)
      yield
          assert(ok0, "the interval did not tick")
          assert(f.updates > 0, s"the key did not reach its handler: $f")
          assert(
            ms(f) < 1500L,
            s"the wait started again with each message: the frame of the key came after ${ms(f)} ms",
          )
          assert(
            ok1 && !s2.contains("[slow]"),
            s"no frame while the settle waited for the component: the tick stayed at ${tickOn(s1)} (${tickOn(s2)})",
          )
          assert(ok2, "the content did not come in a later frame")
      end for
    }

    // claude 2026-10-13: changed (C4): 200 ms between the show and the hide (a
    // loaded machine)
    "pending frame: a component that mounts and unmounts inside one settle leaves no wait behind" in {
      // pendingTimeout 3 s; the body takes 2 s. The handler of the key shows
      // the component and hides it again 200 ms later (time for the watcher
      // of `shown` to post the first value, also on a loaded machine), so the
      // settle of the key mounts it and unmounts it. Allowance: the frame of
      // the key (the handler's 200 ms included) comes before 1 s.
      val options = TuiOptions(pendingTimeout = JDuration.ofSeconds(3))
      for
          h <- Headless.start(30, 4, options)(_ =>
                 Signal.initRef(false).map { shown =>
                   div.onKeyDown {
                     case KeyboardEvent(Keyboard.Char('b'), _, _) =>
                       shown
                         .set(true)
                         .andThen(Async.sleep(200.millis))
                         .andThen(shown.set(false))
                     case _                                       => ()
                   }(
                     span("[top]"),
                     when(shown)(
                       component(Async.sleep(2.seconds).andThen(span("[slow]"))),
                     ),
                   )
                 },
               )
          f <- h.char('b')
          s <- h.screen
      yield
          assert(
            f.updates >= 2,
            s"the settle of the key did not mount and unmount the component: $f",
          )
          assert(
            ms(f) < 1000L,
            s"the frame waited for a component that is gone: ${ms(f)} ms",
          )
          assert(s.contains("[top]") && !s.contains("[slow]"), s.text)
      end for
    }

    "pending frame: only the frame of the mount waits, once: the re-check of that frame and the later renders of the parent (the repair tick) do not wait again" in {
      // pendingTimeout 1 s; the body takes 4 s; a repair tick every 100 ms
      // renders the parent of the component again (its UI holds closures),
      // and a region changes every 50 ms. Allowance: the frame of the key
      // takes 0.9 s to 1.6 s (one wait of 1 s, not two); then, for 1.5 s and
      // before the content comes, no frame takes 500 ms or more (a new wait
      // would take up to 1 s).
      val options = TuiOptions(pendingTimeout = JDuration.ofSeconds(1))
      Knobs.let(Knobs(repairInterval = 100.millis)) {
        for
            h   <- Headless.start(40, 5, options)(_ =>
                     for
                         n  <- Signal.initRef(0)
                         _  <- useInterval(50.millis)(n.updateAndGet(_ + 1))
                         ui <- slowApp(4.seconds, span(n.map(v => s"[tick=$v]")))
                     yield ui,
                   )
            ok0 <- h.awaitScreen(s => tickOn(s) >= 3, 2.seconds)
            f   <- h.char('s')
            fs  <- framesUntil(h, 1500.millis)(_ => false)
            s   <- h.screen
            ok2 <- h.awaitScreen(showing("[slow]"), 5.seconds)
        yield
            val slow = fs.filter(ms(_) >= 500L).map(ms)
            assert(ok0, "the interval did not tick")
            assert(
              ms(f) >= 900L && ms(f) < 1600L,
              s"the frame of the key: ${ms(f)} ms (one wait is about 1 s)",
            )
            assert(
              slow.isEmpty,
              s"frames that waited again for the component: $slow ms (of ${fs.size} frames)",
            )
            assert(!s.contains("[slow]") && ok2, s.text)
        end for
      }
    }

    // claude 2026-10-13: created (C4, review R2-2)
    "pending frame: a quit inside the wait ends the wait at once: the run ends, and does not wait for the content" in {
      // pendingTimeout 5 s; the body takes 4 s. The quit (Runner.quit, as
      // Tui.quit) comes 300 ms after the key, inside the wait. Allowance: the
      // run ends within 1 s of it (the frame of the key, one settle, the end
      // of the run), long before the content (4 s).
      val options = TuiOptions(pendingTimeout = JDuration.ofSeconds(5))
      for
          h   <- Headless.start(30, 4, options)(_ => slowApp(4.seconds))
          _   <- Fiber.initUnscoped(h.char('s'))
          _   <- Async.sleep(300.millis)
          _   <- h.quit
          fin <- h.awaitEnd(1.second)
      yield assert(
        fin,
        "a quit inside the wait for a component did not end the run within 1 s",
      )
      end for
    }

    // claude 2026-10-13: created (C4, review R2-2)
    "pending frame: a Ctrl-C inside the wait ends the wait at once: the run ends, and does not wait for the content" in {
      // As above, with a Ctrl-C key (TuiOptions.ctrlCQuits): inside a settle
      // a key goes to the backlog, so the wait looks at the backlog too.
      val options = TuiOptions(pendingTimeout = JDuration.ofSeconds(5))
      for
          h   <- Headless.start(30, 4, options)(_ => slowApp(4.seconds))
          _   <- Fiber.initUnscoped(h.char('s'))
          _   <- Async.sleep(300.millis)
          _   <- Fiber.initUnscoped(
                   h.key(Keyboard.Char('c'), Modifiers(ctrl = true)),
                 )
          fin <- h.awaitEnd(1.second)
      yield assert(
        fin,
        "a Ctrl-C inside the wait for a component did not end the run within 1 s",
      )
      end for
    }

    // claude 2026-10-13: created (C4, review R1-2)
    "pending frame: a component that mounts and gives its place to another region inside one settle leaves no wait behind" in {
      // pendingTimeout 3 s; the body takes 2 s. The handler of the key shows
      // the component and, 200 ms later, puts another `Reactive` region at its
      // path (the `else` branch of the `render`). Allowance: the frame of the
      // key (the handler's 200 ms included) comes before 1 s.
      val options = TuiOptions(pendingTimeout = JDuration.ofSeconds(3))
      for
          h <- Headless.start(30, 4, options)(_ =>
                 for
                     shown <- Signal.initRef(false)
                     other <- Signal.initRef(0)
                 yield div.onKeyDown {
                   case KeyboardEvent(Keyboard.Char('b'), _, _) =>
                     shown
                       .set(true)
                       .andThen(Async.sleep(200.millis))
                       .andThen(shown.set(false))
                   case _                                       => ()
                 }(
                   span("[top]"),
                   shown.render(on =>
                     if on then
                         component(
                           Async.sleep(2.seconds).andThen(span("[slow]")),
                         )
                     else other.render(v => span(s"[other=$v]")),
                   ),
                 ),
               )
          f <- h.char('b')
          s <- h.screen
      yield
          assert(
            f.updates >= 2,
            s"the settle of the key did not mount the component and the other region: $f",
          )
          assert(
            ms(f) < 1000L,
            s"the frame waited for a component whose place another region took: ${ms(f)} ms",
          )
          assert(
            s.contains("[other=0]") && !s.contains("[slow]"),
            s.text,
          )
      end for
    }

    /** A body that fails. */
    def boom(): UI = throw RuntimeException("the body of the component fails")

    // claude 2026-10-13: created (C4, review R2-5)
    "pending frame: a hook component whose body fails ends the wait at once; its place stays empty" in {
      // pendingTimeout 3 s; the body fails after 50 ms. Allowance: the frame
      // of the key comes before 1 s.
      val options = TuiOptions(pendingTimeout = JDuration.ofSeconds(3))
      for
          h <- Headless.start(30, 4, options)(_ =>
                 Signal.initRef(false).map { shown =>
                   div.onKeyDown {
                     case KeyboardEvent(Keyboard.Char('s'), _, _) =>
                       shown.set(true)
                     case _                                       => ()
                   }(
                     span("[top]"),
                     when(shown)(
                       component(
                         Async.sleep(50.millis).andThen(Sync.defer(boom())),
                       ),
                     ),
                     span("[bottom]"),
                   )
                 },
               )
          f <- h.char('s')
          s <- h.screen
      yield
          assert(f.updates > 0, s"the key did not reach its handler: $f")
          assert(
            ms(f) < 1000L,
            s"the frame waited for a component whose body failed: ${ms(f)} ms",
          )
          assert(s.contains("[top]") && s.contains("[bottom]"), s.text)
      end for
    }

    /** A `Component` whose `onMount` fails. */
    final class FailingMount extends Component:
        type State = Int
        def initial: Int                                               = 7
        def view(n: SignalRef[Int]): UI                                =
          span(n.map(v => s"[mounted $v]"))
        override def onMount(n: SignalRef[Int]): Any < (Async & Scope) =
          Sync.defer(throw IllegalStateException("onMount fails"))

    // claude 2026-10-19: created: the failure of onMount and of a hook body
    // is logged once
    "a failure of Component.onMount and of a hook body: one line each in the run's log (in memory: no log file); the app goes on and renders" in {
      val sink                = LogSink.openNow(None)
      def count(what: String) = sink.kept.count(_.contains(what))
      val onMount             = "the onMount of "
      val hook                = "the body of the hook component "
      for
          rev    <- Signal.initRef(0)
          h      <- TuiLog.let(sink)(
                      Headless.start(60, 4)(_ =>
                        rev.render(n =>
                          div(
                            span(s"[rev $n]"),
                            FailingMount().ui,
                            component(Sync.defer(boom())),
                          ),
                        ),
                      ),
                    )
          logged <- waitFor(5.seconds)(
                      Sync.defer(count(onMount) >= 1 && count(hook) >= 1),
                    )
          // a render of the parent keeps both mounts: no second line
          _      <- rev.set(1)
          shown  <- h.awaitScreen(_.contains("[rev 1]"), 5.seconds)
          _      <- TuiLog.flush(2.seconds)
          s      <- h.screen
      yield
          val lines =
            sink.kept.filter(l => l.contains(onMount) || l.contains(hook))
          assert(logged >= 0, s"no log line: ${sink.kept}")
          assert(
            count(onMount) == 1 && count(hook) == 1,
            lines.mkString("\n"),
          )
          assert(
            lines.exists(l =>
              l.contains("ERROR") && l.contains("FailingMount at ") &&
                l.contains("IllegalStateException: onMount fails"),
            ),
            lines.mkString("\n"),
          )
          assert(shown && s.contains("[mounted 7]"), s.text)
      end for
    }
end ComponentTest
