package tui4s

import kyo.*
import kyo.UI.*
import tui4s.Hooks.*
import tui4s.testkit.{Headless, Screen}

import java.util.concurrent.atomic.AtomicInteger

/** One test per hook (RC-15 rest; the hooks that had no test: RR skeptic
  * "refuted" 9) and the life of a component in a `when` (RR O7). Each leaf
  * checks the screen or a counter that the hook moves.
  */
// claude 2026-10-13: created (C4)
class HooksTest extends ComponentSuite:

    /** True when the screen shows all `texts`, in this order. */
    def inOrder(texts: String*)(s: Screen): Boolean =
        val at = texts.map(s.text.indexOf(_))
        at.forall(_ >= 0) && at.zip(at.drop(1)).forall(_ < _)

    // claude 2026-10-19: created (P1b, A5 e): the hooks that start a fiber
    // need Sync and Scope, not Async (this leaf does not compile otherwise)
    "useHold, useFold, useEffect and useInterval run in Sync & Scope" in {
      val hold: Signal[Int] < (Sync & Scope)   = useHold(1)(Stream.init(Seq(2)))
      val fold: Signal[Int] < (Sync & Scope)   =
        useFold(0)(Stream.init(Seq(1, 2)))(_ + _)
      val effect: Unit < (Sync & Scope)        = useEffect(Kyo.unit)
      val interval: Unit < (Sync & Scope)      = useInterval(1.hour)(Kyo.unit)
      Scope.run(
        for
            h <- hold
            f <- fold
            _ <- effect
            _ <- interval
            a <- h.current
            b <- f.current
        yield assert(a >= 1 && b >= 0),
      )
    }

    "useSignal: the initial value on the screen; a handler's write and an outside write reach it" in {
      for
          box <- AtomicRef.init(Option.empty[SignalRef[Int]])
          h   <- Headless.start(30, 3)(_ =>
                   useSignal(5).map { c =>
                     box
                       .set(Some(c))
                       .andThen(
                         div(
                           span(c.map(v => s"[s=$v]")),
                           button("+").onClick(c.updateAndGet(_ + 1)),
                         ),
                       )
                   },
                 )
          s0  <- h.screen
          _   <- h.key(Keyboard.Enter) // the button
          s1  <- h.screen
          ref <- box.get
          _   <- ref.fold(Kyo.unit)(_.set(10))
          ok  <- h.awaitScreen(showing("[s=10]"), 2.seconds)
      yield
          assert(s0.contains("[s=5]"), s0.text)
          assert(s1.contains("[s=6]"), s1.text)
          assert(ok, "the outside write did not reach the screen")
      end for
    }

    "useHold: the initial value until the stream's first element, then the last element" in {
      for
          ch  <- Channel.initUnscoped[Int](16)
          h   <- Headless.start(30, 3)(_ =>
                   useHold(-1)(ch.streamUntilClosed()).map(v =>
                     div(span(v.map(n => s"[h=$n]"))),
                   ),
                 )
          s0  <- h.screen
          _   <- Abort.run[Closed](ch.put(1).andThen(ch.put(2)))
          ok2 <- h.awaitScreen(showing("[h=2]"), 2.seconds)
          _   <- Abort.run[Closed](ch.put(3))
          ok3 <- h.awaitScreen(showing("[h=3]"), 2.seconds)
      yield
          assert(s0.contains("[h=-1]"), s0.text)
          assert(
            ok2 && ok3,
            "the last element of the stream is not on the screen",
          )
      end for
    }

    "useFold: folds the stream into a state (a running sum)" in {
      for
          ch <- Channel.initUnscoped[Int](16)
          h  <- Headless.start(30, 3)(_ =>
                  useFold(0)(ch.streamUntilClosed())((sum, n) => sum + n).map(
                    v => div(span(v.map(n => s"[sum=$n]"))),
                  ),
                )
          s0 <- h.screen
          _  <- Abort.run[Closed](Kyo.foreachDiscard(1 to 4)(ch.put(_)))
          ok <- h.awaitScreen(showing("[sum=10]"), 2.seconds)
      yield
          assert(s0.contains("[sum=0]"), s0.text)
          assert(ok, "the sum 1 + 2 + 3 + 4 is not on the screen")
      end for
    }

    "useEffect: runs once at mount, not again when its parent renders again; at the unmount its Scope closes (a Scope.ensure in it runs); a new mount runs it again" in {
      for
          runs  <- Sync.defer(AtomicInteger(0))
          ends  <- Sync.defer(AtomicInteger(0))
          title <- Signal.initRef(0)
          shown <- Signal.initRef(true)
          body   = useEffect(
                     Sync
                       .defer(runs.incrementAndGet())
                       .andThen(
                         Scope.ensure(Sync.defer(ends.incrementAndGet())),
                       )
                       .andThen(Async.never[Unit]),
                   ).andThen(span("[effect]"))
          h     <- Headless.start(40, 4)(_ =>
                     div(
                       when(shown)(
                         div(
                           title.render(t => div(span(s"[t=$t]"), component(body))),
                         ),
                       ),
                     ),
                   )
          ok0   <- h.awaitScreen(showing("[effect]"), 2.seconds)
          r0    <- waitFor(2.seconds)(read(runs).map(_ == 1))
          _     <- title.set(1)
          ok1   <- h.awaitScreen(showing("[t=1]"), 2.seconds)
          _     <- title.set(2)
          ok2   <- h.awaitScreen(showing("[t=2]"), 2.seconds)
          _     <- Async.sleep(100.millis)
          runs2 <- read(runs)
          ends2 <- read(ends)
          _     <- shown.set(false)
          gone  <- h.awaitScreen(!_.contains("[effect]"), 2.seconds)
          e1    <- waitFor(2.seconds)(read(ends).map(_ == 1))
          _     <- shown.set(true)
          back  <- h.awaitScreen(showing("[effect]"), 2.seconds)
          r2    <- waitFor(2.seconds)(read(runs).map(_ == 2))
      yield
          assert(ok0 && r0 >= 0L, "the effect did not run at the mount")
          assert(
            ok1 && ok2 && runs2 == 1 && ends2 == 0,
            s"the parent rendered again: the effect ran $runs2 times and ended $ends2 times",
          )
          assert(
            gone && e1 >= 0L,
            "the Scope of the effect did not close at the unmount",
          )
          assert(back && r2 >= 0L, "a new mount did not run the effect again")
      end for
    }

    "useInterval: its ticks reach the screen; at the unmount it stops (the count and the gauge)" in {
      for
          ticks <- Sync.defer(AtomicInteger(0))
          shown <- Signal.initRef(false)
          body   =
            for
                c <- useSignal(0)
                _ <- useInterval(20.millis)(
                       c.updateAndGet(_ + 1)
                         .andThen(Sync.defer(ticks.incrementAndGet())),
                     )
            yield span(c.map(n => s"[i=$n]"))
          h     <- Headless.start(30, 3)(_ =>
                     div(span("interval"), when(shown)(component(body))),
                   )
          g0    <- h.liveFibers
          _     <- shown.set(true)
          ok    <- h.awaitScreen(
                     _.rows.exists(_.matches(""".*\[i=([3-9]|\d\d+)\].*""")),
                     3.seconds,
                   )
          g1    <- h.liveFibers
          _     <- shown.set(false)
          gone  <- h.awaitScreen(!_.contains("[i="), 2.seconds)
          back  <- waitFor(2.seconds)(h.liveFibers.map(_ == g0))
          still <- stopped(ticks)
      yield
          assert(ok, "the ticks did not reach the screen")
          assert(
            g1 >= g0 + 3,
            s"the gauge: $g0 before the mount, $g1 after (the hook fiber, the interval, the watcher of the count)",
          )
          assert(still, "the interval ticks after the unmount")
          assert(
            gone && back >= 0L,
            "the gauge did not come back after the unmount",
          )
      end for
    }

    "component(key): in a keyed foreach, a component keeps its state when its siblings move" in {
      for
          items <- Signal.initRef(Chunk("a", "b", "c"))
          refs  <- AtomicRef.init(Map.empty[String, SignalRef[Int]])
          row    = (k: String) =>
                     component(k)(
                       useSignal(0).map(c =>
                         refs
                           .updateAndGet(_ + (k -> c))
                           .andThen(span(c.map(n => s"[$k=$n]"))),
                       ),
                     )
          h     <-
            Headless.start(30, 5)(_ => div(items.foreachKeyed(identity)(row)))
          ok0   <- h.awaitScreen(showing("[c=0]"), 2.seconds)
          m     <- refs.get
          _     <- m.get("b").fold(Kyo.unit)(_.set(5))
          ok1   <- h.awaitScreen(showing("[b=5]"), 2.seconds)
          _     <- items.set(Chunk("c", "a", "b")) // every item moves
          ok2   <- h.awaitScreen(inOrder("[c=0]", "[a=0]", "[b=5]"), 2.seconds)
          s     <- h.screen
      yield assert(ok0 && ok1 && ok2, s.text)
    }

    "component(key): another key at the same position mounts fresh; the same key keeps the mount when the parent renders again; without a key the old mount stays" in {
      // `user` picks the key; `rev` renders the parent again; `refs` gets the
      // state of each mount
      def app(
          user: Signal[String],
          rev: Signal[Int],
          refs: AtomicRef[Map[String, SignalRef[Int]]],
          keyed: Boolean,
      )(using Frame): UI =
        div(
          rev.render(r =>
            div(
              span(s"[rev=$r]"),
              user.render(u =>
                  val body = useSignal(0).map(c =>
                    refs
                      .updateAndGet(_ + (u -> c))
                      .andThen(span(c.map(n => s"[$u=$n]"))),
                  )
                  if keyed then component(u)(body) else component(body),
              ),
            ),
          ),
        )
      def set(refs: AtomicRef[Map[String, SignalRef[Int]]], u: String, n: Int)(
          using Frame,
      ): Unit < Sync     =
        refs.get.map(_.get(u).fold(Kyo.unit)(_.set(n)))
      for
          user  <- Signal.initRef("ann")
          rev   <- Signal.initRef(0)
          refs  <- AtomicRef.init(Map.empty[String, SignalRef[Int]])
          h     <- Headless.start(30, 4)(_ => app(user, rev, refs, keyed = true))
          ok0   <- h.awaitScreen(showing("[ann=0]"), 2.seconds)
          _     <- set(refs, "ann", 4)
          ok1   <- h.awaitScreen(showing("[ann=4]"), 2.seconds)
          _     <- rev.set(1)       // the same key: the mount goes on
          ok2   <- h.awaitScreen(showing("[rev=1]"), 2.seconds)
          s2    <- h.screen
          _     <- user.set("bob")  // another key: a fresh mount
          ok3   <- h.awaitScreen(showing("[bob=0]"), 2.seconds)
          user2 <- Signal.initRef("ann")
          rev2  <- Signal.initRef(0)
          refs2 <- AtomicRef.init(Map.empty[String, SignalRef[Int]])
          h2    <-
            Headless.start(30, 4)(_ => app(user2, rev2, refs2, keyed = false))
          ok4   <- h2.awaitScreen(showing("[ann=0]"), 2.seconds)
          _     <- set(refs2, "ann", 4)
          ok5   <- h2.awaitScreen(showing("[ann=4]"), 2.seconds)
          _     <- user2.set("bob") // no key: the old mount stays
          _     <- Async.sleep(200.millis)
          s6    <- h2.screen
      yield
          assert(ok0 && ok1, "the keyed app did not start or count")
          assert(
            ok2 && s2.contains("[ann=4]"),
            s"the same key after the parent rendered again: ${s2.text}",
          )
          assert(ok3, "another key did not mount a fresh component")
          assert(ok4 && ok5, "the app without a key did not start or count")
          assert(
            s6.contains("[ann=4]") && !s6.contains("[bob="),
            s"without a key the node keeps its first mount: ${s6.text}",
          )
      end for
    }

    "a hook component inside `when` loses its state when it is hidden and comes back fresh; a Component too (RR O7)" in {
      final class Box extends Component:
          type State = Int
          def initial: Int                = 0
          def view(n: SignalRef[Int]): UI = div(
            span(n.map(v => s"[box=$v]")),
            button("+").onClick(n.updateAndGet(_ + 1)),
          )
      for
          shown <- Signal.initRef(true)
          refs  <- AtomicRef.init(Option.empty[SignalRef[Int]])
          hook   = useSignal(0).map(c =>
                     refs.set(Some(c)).andThen(span(c.map(n => s"[hook=$n]"))),
                   )
          h     <- Headless.start(30, 5)(_ =>
                     div(when(shown)(div(component(hook), Box().ui))),
                   )
          _     <- h.key(Keyboard.Enter) // the button of Box
          _     <- h.key(Keyboard.Enter)
          c     <- refs.get
          _     <- c.fold(Kyo.unit)(_.set(7))
          ok0   <- h.awaitScreen(
                     s => s.contains("[hook=7]") && s.contains("[box=2]"),
                     2.seconds,
                   )
          _     <- shown.set(false)
          gone  <- h.awaitScreen(
                     s => !s.contains("[hook=") && !s.contains("[box="),
                     2.seconds,
                   )
          _     <- shown.set(true)
          back  <- h.awaitScreen(
                     s => s.contains("[hook=0]") && s.contains("[box=0]"),
                     2.seconds,
                   )
          s     <- h.screen
      yield assert(ok0 && gone && back, s.text)
      end for
    }
end HooksTest
