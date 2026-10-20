package examples.state

import kyo.*
import kyo.UI.Keyboard
import munit.FunSuite
import tui4s.Screen
import tui4s.testkit.Headless

/** The seven examples of the section "State and events with Kyo" of
  * `examples/README.md` do what the README says. Each leaf runs one example on
  * `Headless` and reads the screen.
  */
// claude 2026-10-20: created: the README blocks are compiled sources now, each
// with a leaf here
class StateSuite extends FunSuite:

    /** Runs `v` to its end on the Kyo scheduler (at most 30 s). */
    private def run[A](v: => A < (Async & Scope))(using Frame): A =
        import AllowUnsafe.embrace.danger
        KyoApp.Unsafe.runAndBlock(30.seconds)(v).getOrThrow

    /** The rows of `s` that show `label`, with true when it is bold there. */
    private def boldOn(s: Screen, label: String): Vector[Boolean] =
      s.rows.indices.toVector.flatMap { y =>
        val x = s.row(y).indexOf(label)
        if x < 0 then Vector.empty else Vector(s.styleAt(x, y).bold)
      }

    test("native state: the bound input and checkbox, the derived greeting") {
      val (s0, typed, toggled) = run {
        for
            h       <- Headless.start(40, 8)(_ => NativeStateExample.ui)
            s0      <- h.screen
            _       <- h.key(Keyboard.End)
            _       <- h.typeText("x")
            typed   <- h.screen
            _       <- h.key(Keyboard.Tab)
            _       <- h.key(Keyboard.Space)
            toggled <- h.screen
        yield (s0, typed, toggled)
      }
      assert(s0.contains("Hello, Ada") && s0.contains("Enabled"), s0.text)
      assert(
        typed.contains("Hello, Adax") && typed.contains("Last edit: Adax"),
        typed.text,
      )
      assert(toggled.contains("Disabled"), toggled.text)
    }

    test("owned tabs: the widget keeps the selection and shows the panel") {
      val (s0, s1) = run {
        for
            h  <- Headless.start(50, 6)(_ => OwnedTabsExample.ui)
            s0 <- h.screen
            _  <- h.key(Keyboard.ArrowRight)
            s1 <- h.screen
        yield (s0, s1)
      }
      assert(s0.contains("Dashboard"), s0.text)
      assert(s0.contains("Selected metric: 0"), s0.text)
      assert(s1.contains("Selected metric: 1"), s1.text)
    }

    test(
      "controlled tabs: the parent rejects Network; an outside write sets it",
    ) {
      val (s1, s2, s3) = run {
        for
            h  <- Headless.start(60, 6)(_ => ControlledTabsExample.ui)
            _  <- h.key(Keyboard.ArrowRight)
            s1 <- h.screen
            _  <- h.key(Keyboard.ArrowRight)
            s2 <- h.screen
            _  <- h.key(Keyboard.Tab)
            _  <- h.key(Keyboard.Enter)
            s3 <- h.screen
        yield (s1, s2, s3)
      }
      assert(s1.contains("Selected metric: 1"), s1.text)
      assert(s2.contains("Selected metric: 1"), s2.text)
      assert(s3.contains("Selected metric: 2"), s3.text)
    }

    test("portable tabs: the construction effect gives a working widget") {
      val (s0, s1) = run {
        for
            h  <- Headless.start(40, 6)(_ => PortableTabsExample.ui)
            s0 <- h.screen
            _  <- h.key(Keyboard.ArrowRight)
            s1 <- h.screen
        yield (s0, s1)
      }
      assert(s0.contains("Panel 0"), s0.text)
      assert(s1.contains("Panel 1"), s1.text)
    }

    test("emitted tabs: the callback handles Emit and records each selection") {
      val s = run {
        for
            h <- Headless.start(40, 6)(_ => EmittedTabsExample.ui)
            _ <- h.key(Keyboard.ArrowRight)
            _ <- h.key(Keyboard.ArrowLeft)
            s <- h.screen
        yield s
      }
      assert(s.contains("Selections: 1, 0"), s.text)
    }

    test("ordinary store: Env.run and Env.runLayer give the same store view") {
      val (direct, layered) = run {
        for
            h1      <- Headless.start(50, 6)(_ => OrdinaryStoreExample.ui)
            _       <- h1.key(Keyboard.ArrowRight)
            direct  <- h1.screen
            h2      <- Headless.start(50, 6)(_ => OrdinaryStoreExample.uiWithLayer)
            _       <- h2.key(Keyboard.ArrowRight)
            _       <- h2.key(Keyboard.ArrowRight)
            layered <- h2.screen
        yield (direct, layered)
      }
      assert(direct.contains("Metric 1"), direct.text)
      assert(layered.contains("Metric 2"), layered.text)
    }

    test("keyed store: two tab widgets with the same key share the selection") {
      val (s0, s1) = run {
        for
            h  <- Headless.start(50, 8)(_ => KeyedStoreExample.ui)
            s0 <- h.screen
            _  <- h.key(Keyboard.ArrowRight)
            s1 <- h.screen
        yield (s0, s1)
      }
      assertEquals(boldOn(s0, "CPU"), Vector(true, true), s0.text)
      assertEquals(boldOn(s1, "Memory"), Vector(true, true), s1.text)
      assertEquals(boldOn(s1, "CPU"), Vector(false, false), s1.text)
    }
end StateSuite
