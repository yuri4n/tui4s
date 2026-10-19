package tui4s.widgets

import kyo.*
import kyo.UI.*
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}

import UiTree.*

/** The containers: the tree of a panel, the keys of a split pane, the sections
  * of an accordion.
  */
// claude 2026-10-19: created (L2b)
class ContainersTest extends Test[Any]:

    "a panel: the title and the content, in a group" in {
      val ui = Panel(title = "CPU")(span("42 %"))
      for
          t <- text(ui)
          g <- byRole(ui, "group")
      yield assert(t.contains("CPU") && t.contains("42 %") && g.nonEmpty, t)
    }

    "a panel with no border puts the title in the flow" in {
      val ui = Panel(title = "T", border = Panel.Border.None)(span("x"))
      for t <- text(ui) yield assert(t == "Tx", t)
    }

    "a split pane: the arrows on the divider write a bound size" in {
      for
          size <- Signal.initRef(50)
          ui   <- SplitPane(span("a"), span("b"))(size = Model.Bound(size))
          _    <- press(ui, "separator", Keyboard.ArrowRight)
          a    <- size.get
          _    <- press(ui, "separator", Keyboard.Home)
          b    <- size.get
          _    <- press(ui, "separator", Keyboard.ArrowUp)
          c    <- size.get
      yield assert(a == 55 && b == 10 && c == 10, (a, b, c).toString)
    }

    "an accordion: a click opens a section and shows its body" in {
      for
          open <- Signal.initRef(Set.empty[Int])
          ui   <- Accordion(
                    Accordion.Section("One", span("[one]")),
                    Accordion.Section("Two", span("[two]")),
                  )(open = Model.Bound(open))
          t0   <- text(ui)
          _    <- click(ui, 1)
          t1   <- text(ui)
          _    <- click(ui, 0)
          set  <- open.get
          t2   <- text(ui)
      yield
          assert(!t0.contains("[two]") && t0.contains("▸ Two"), t0)
          assert(t1.contains("[two]") && t1.contains("▾ Two"), t1)
          assert(
            set == Set(0) && t2.contains("[one]") && !t2.contains("[two]"),
            t2,
          )
    }
end ContainersTest

/** The laws of the containers. */
// claude 2026-10-19: created (L2b)
class ContainersPropertyTest extends PropertyTest[Any]:

    override protected def numSamples: Int = 200

    private val small: Gen[Int] = Gen.int.map(n => Math.floorMod(n, 200) - 50)

    "a resize stays in min to max" - {
      forAll(small, small, small) { (size, delta, a) =>
        val (lo, hi) = (a.min(a + 40), a + 40)
        val r        = SplitPane.resize(size, delta, lo, hi)
        assert(r >= lo && r <= hi)
      }
    }

    "Mode.One keeps at most one section open; a toggle twice undoes it" - {
      forAll(Gen.listOfN(12, small), small) { (toggles, i) =>
        val one  = toggles.foldLeft(Set.empty[Int])((o, t) =>
          Accordion.toggle(o, t, Accordion.Mode.One),
        )
        val many = toggles.foldLeft(Set.empty[Int])((o, t) =>
          Accordion.toggle(o, t, Accordion.Mode.Many),
        )
        val back = Accordion.toggle(
          Accordion.toggle(many, i, Accordion.Mode.Many),
          i,
          Accordion.Mode.Many,
        )
        assert(one.size <= 1 && back == many)
      }
    }
end ContainersPropertyTest
