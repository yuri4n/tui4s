package examples.widgets

import kyo.*
import kyo.UI.*
import munit.FunSuite
import tui4s.testkit.{Headless, Screen}

class TabsSuite extends FunSuite:


    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    private def bold(s: Screen, text: String): Boolean =
      s.find(text).exists((x, y) => s.styleAt(x, y).bold)

    private def panel(index: Int): UI = span(s"[panel=$index]")

    test("local tabs start at zero and arrows wrap the selection and panel") {
      val (first, last, wrapped, second) = run {
        for
            h       <- Headless.start(50, 5)(_ =>
                         new Tabs("One", "Two", "Three")(
                           heading = span("Heading"),
                           panel = panel,
                         ).ui,
                       )
            first   <- h.screen
            _       <- h.key(Keyboard.ArrowLeft)
            last    <- h.screen
            _       <- h.key(Keyboard.ArrowRight)
            wrapped <- h.screen
            _       <- h.key(Keyboard.ArrowRight)
            second  <- h.screen
        yield (first, last, wrapped, second)
      }
      assert(first.contains("Heading"), first.text)
      assert(first.contains("[panel=0]") && bold(first, "One"), first.text)
      assert(last.contains("[panel=2]") && bold(last, "Three"), last.text)
      assert(!bold(last, "One"), last.text)
      assert(wrapped.contains("[panel=0]"), wrapped.text)
      assert(second.contains("[panel=1]") && bold(second, "Two"), second.text)
    }

    test("initialTab wraps; arrows select their focused destination") {
      val (initial, next, entered) = run {
        for
            h       <- Headless.start(40, 5)(_ =>
                         new Tabs("One", "Two", "Three")(initialTab = -1, panel = panel).ui,
                       )
            initial <- h.screen
            _       <- h.key(Keyboard.ArrowRight)
            next    <- h.screen
            _       <- h.key(Keyboard.Enter)
            entered <- h.screen
        yield (initial, next, entered)
      }
      assert(
        initial.contains("[panel=2]") && bold(initial, "Three"),
        initial.text,
      )
      assert(next.contains("[panel=1]") && bold(next, "Two"), next.text)
      assert(entered.contains("[panel=1]"), entered.text)
    }

    test("parent renders preserve local selection; unmount resets it") {
      val (rerendered, kept, gone, remounted, reset) = run {
        for
            revision   <- Signal.initRef(0)
            shown      <- Signal.initRef(true)
            h          <- Headless.start(50, 7)(_ =>
                            div(
                              when(shown)(
                                revision.render(n =>
                                  new Tabs("One", "Two", "Three")(
                                    heading = span(s"[revision=$n]"),
                                    panel = panel,
                                  ).ui,
                                ),
                              ),
                            ),
                          )
            _          <- h.key(Keyboard.ArrowRight)
            _          <- revision.set(1)
            rerendered <- h.awaitScreen(_.contains("[revision=1]"), 5.seconds)
            kept       <- h.screen
            _          <- shown.set(false)
            gone       <- h.awaitScreen(!_.contains("[panel="), 5.seconds)
            _          <- shown.set(true)
            remounted  <- h.awaitScreen(_.contains("[panel=0]"), 5.seconds)
            reset      <- h.screen
        yield (rerendered, kept, gone, remounted, reset)
      }
      assert(rerendered, kept.text)
      assert(kept.contains("[panel=1]") && bold(kept, "Two"), kept.text)
      assert(gone, "tabs stayed visible after unmount")
      assert(remounted, reset.text)
      assert(bold(reset, "One") && !bold(reset, "Two"), reset.text)
    }

    test("two mounted tabs keep their selection and arrow focus separate") {
      val (firstChanged, secondChanged) = run {
        for
            h             <- Headless.start(40, 8)(_ =>
                               div(
                                 new Tabs("A0", "A1")(
                                   panel = n => span(s"[a=$n]"),
                                 ).ui,
                                 new Tabs("B0", "B1")(
                                   panel = n => span(s"[b=$n]"),
                                 ).ui,
                               ),
                             )
            _             <- h.key(Keyboard.ArrowLeft)
            _             <- h.key(Keyboard.Enter)
            firstChanged  <- h.screen
            _             <- h.key(Keyboard.Tab)
            _             <- h.key(Keyboard.ArrowRight)
            _             <- h.key(Keyboard.Enter)
            secondChanged <- h.screen
        yield (firstChanged, secondChanged)
      }
      assert(
        firstChanged.contains("[a=1]") && firstChanged.contains("[b=0]"),
        firstChanged.text,
      )
      assert(
        secondChanged.contains("[a=1]") && secondChanged.contains("[b=1]"),
        secondChanged.text,
      )
      assert(
        bold(secondChanged, "A1") && bold(secondChanged, "B1"),
        secondChanged.text,
      )
    }

    test("reusing one Tabs value mounts independent state and focus groups") {
      val tabs                              = new Tabs("Zero", "One")(panel = panel)
      val (firstChanged, secondChanged)     = run {
        for
            h             <- Headless.start(40, 8)(_ => div(tabs.ui, tabs.ui))
            _             <- h.key(Keyboard.ArrowLeft)
            _             <- h.key(Keyboard.Enter)
            firstChanged  <- h.screen
            _             <- h.key(Keyboard.Tab)
            _             <- h.key(Keyboard.ArrowRight)
            _             <- h.key(Keyboard.Enter)
            secondChanged <- h.screen
        yield (firstChanged, secondChanged)
      }
      def panels(s: Screen): Vector[String] =
        s.rows.filter(_.contains("[panel=")).map(_.trim)

      assertEquals(panels(firstChanged), Vector("[panel=1]", "[panel=0]"))
      assertEquals(panels(secondChanged), Vector("[panel=1]", "[panel=1]"))
    }

    test(
      "a parent callback accepts or rejects requests and controls later changes",
    ) {
      val (accepted, rejected, source, changed, external, emitted) = run {
        for
            selected <- Signal.initRef(0)
            requests <- Signal.initRef(Vector.empty[Int])
            h        <- Headless.start(40, 5)(_ =>
                          new Tabs("One", "Two", "Three")(
                            selected = Some(selected),
                            onChange = next =>
                              requests
                                .updateAndGet(_ :+ next)
                                .andThen(
                                  if next == 2 then Kyo.unit else selected.set(next),
                                ),
                            panel = panel,
                          ).ui,
                        )
            _        <- h.key(Keyboard.ArrowRight)
            accepted <- h.screen
            _        <- h.key(Keyboard.ArrowRight)
            rejected <- h.screen
            source   <- selected.get
            _        <- selected.set(2)
            changed  <- h.awaitScreen(_.contains("[panel=2]"), 5.seconds)
            external <- h.screen
            emitted  <- requests.get
        yield (accepted, rejected, source, changed, external, emitted)
      }
      assert(accepted.contains("[panel=1]"), accepted.text)
      assert(
        rejected.contains("[panel=1]") && bold(rejected, "Two"),
        rejected.text,
      )
      assertEquals(source, 1)
      assert(changed && bold(external, "Three"), external.text)
      assertEquals(emitted, Vector(1, 2))
    }

    test("callbacks report local button and arrow selection requests") {
      val (before, after, screen) = run {
        for
            requests <- Signal.initRef(Vector.empty[Int])
            h        <- Headless.start(40, 5)(_ =>
                          new Tabs("One", "Two", "Three")(
                            onChange = next => requests.updateAndGet(_ :+ next).unit,
                            panel = panel,
                          ).ui,
                        )
            before   <- requests.get
            _        <- h.key(Keyboard.Tab)
            _        <- h.key(Keyboard.Space)
            _        <- h.key(Keyboard.ArrowRight)
            after    <- requests.get
            screen   <- h.screen
        yield (before, after, screen)
      }
      assertEquals(before, Vector.empty[Int])
      assertEquals(after, Vector(1, 2))
      assert(screen.contains("[panel=2]"), screen.text)
    }

    // claude 2026-10-19: changed: tabs without a label do not compile (the
    // labels are `first, rest*`), not a failure at construction (P1b, A5 g)
    test("tabs without a label do not compile") {
      val errors = compileErrors("new Tabs()()")
      assert(errors.nonEmpty, "new Tabs()() compiled")
      assert(compileErrors("""new Tabs("One")()""").isEmpty)
    }
end TabsSuite
