package examples.widgets

import kyo.*
import kyo.UI.*
import munit.FunSuite
import pini4s.*
import tui4s.Hooks.component
import tui4s.testkit.Headless
import tui4s.widgets.Model

class StateStoreSuite extends FunSuite:

    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    // claude 2026-10-19: changed: a bound Model, not a signal and its set
    // (L2b)
    private def sharedTabs(name: String)(using StateStore, Frame): UI < Sync =
      useState("tabs.selected")(0).map(selected =>
        new Tabs("One", "Two")(
          selected = Model.Bound(selected),
          panel = index => span(s"[$name=$index]"),
        ).ui,
      )

    test("two tab widgets update each other through named state") {
      val (fromFirst, fromSecond) = run {
        for
            store      <- StateStore.init
            h          <- Headless.start(40, 8)(_ =>
                              given StateStore = store
                              for
                                  first  <- sharedTabs("first")
                                  second <- sharedTabs("second")
                              yield div(first, second),
                          )
            _          <- h.key(Keyboard.ArrowRight)
            fromFirst  <- h.screen
            _          <- h.key(Keyboard.Tab)
            _          <- h.key(Keyboard.Enter)
            fromSecond <- h.screen
        yield (fromFirst, fromSecond)
      }
      assert(
        fromFirst.contains("[first=1]") && fromFirst.contains("[second=1]"),
        fromFirst.text,
      )
      assert(
        fromSecond.contains("[first=0]") && fromSecond.contains("[second=0]"),
        fromSecond.text,
      )
    }

    test(
      "deferred components capture their store and keep state after remount",
    ) {
      def app(shown: Signal[Boolean])(using StateStore, Frame): UI =
        div(
          when(shown)(
            div(
              component(sharedTabs("first")),
              component(sharedTabs("second")),
            ),
          ),
        )

      val (selected, gone, back, remounted) = run {
        for
            store     <- StateStore.init
            shown     <- Signal.initRef(true)
            h         <-
              Headless.start(40, 8)(_ => app(shown)(using store, summon[Frame]))
            _         <- h.key(Keyboard.ArrowRight)
            selected  <- h.screen
            _         <- shown.set(false)
            gone      <- h.awaitScreen(!_.contains("[first="), 5.seconds)
            _         <- shown.set(true)
            back      <- h.awaitScreen(
                           s => s.contains("[first=1]") && s.contains("[second=1]"),
                           5.seconds,
                         )
            remounted <- h.screen
        yield (selected, gone, back, remounted)
      }
      assert(
        selected.contains("[first=1]") && selected.contains("[second=1]"),
        selected.text,
      )
      assert(gone, "the tab components stayed mounted")
      assert(back, remounted.text)
    }

    test("a store created by each app keeps repeated runs independent") {
      def app(using Frame): UI < Sync =
        StateStore.init.map { store =>
          given StateStore = store
          sharedTabs("app")
        }

      val changed = run {
        for
            h <- Headless.start(40, 5)(_ => app)
            _ <- h.key(Keyboard.ArrowRight)
            s <- h.screen
        yield s
      }
      val fresh   = run(Headless.start(40, 5)(_ => app).map(_.screen))

      assert(changed.contains("[app=1]"), changed.text)
      assert(fresh.contains("[app=0]"), fresh.text)
    }
end StateStoreSuite
