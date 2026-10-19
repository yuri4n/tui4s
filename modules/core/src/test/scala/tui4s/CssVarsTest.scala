package tui4s

import jatatui.core.style.Color as JColor
import kyo.*
import kyo.UI.*
import kyo.test.*
import tui4s.internal.{CssColor, CssVars}

/** The custom properties of a stylesheet (`CssVars`): the values, the
  * selectors, the problems.
  */
// claude 2026-10-19: created (L1b)
class CssVarsTest extends Test[Any]:

    "the values: hex, rgb, rgba, transparent, var() with and without a fallback, a cycle" in {
      val (v, problems) = CssVars.of(
        Stylesheet.vars(
          "a" -> "#fff",
          "b" -> "rgb(1, 2, 3)",
          "c" -> "rgba(1, 2, 3, 0.2)",
          "d" -> "transparent",
          "e" -> "var(--a)",
          "f" -> "var(--none, #000000)",
          "g" -> "var(--g)",
          "h" -> "blue",
        ),
      )
      assert(problems.isEmpty)
      assert(v.color("a") == CssColor.Of(Some(JColor.Rgb(255, 255, 255))))
      assert(v.color("b") == CssColor.Of(Some(JColor.Rgb(1, 2, 3))))
      assert(v.color("c") == CssColor.Of(None))
      assert(v.color("d") == CssColor.Of(None))
      assert(v.color("e") == v.color("a"))
      assert(v.color("f") == CssColor.Of(Some(JColor.Rgb(0, 0, 0))))
      assert(v.color("g") == CssColor.Unset)
      assert(v.color("h") == CssColor.Unset)
      assert(v.color("missing") == CssColor.Unset)
    }

    "the selectors: a class, an id, a data attribute; the other entries are problems" in {
      val (v, problems)                    = CssVars.of(
        Stylesheet
          .vars("x" -> "#000000")
          .scopedVars(Selector.cls("c"), "x" -> "#111111")
          .scopedVars(Selector.id("i"), "x" -> "#222222")
          .scopedVars(Selector.data("theme", "dark"), "x" -> "#333333")
          .scopedVars(
            Selector.cls("a").descendant(Selector.cls("b")),
            "x" -> "#444444",
          )
          .rule("r", Style.bold),
      )
      def at(el: UI.Ast.Element): CssColor = v.enter(el).color("x")
      assert(at(div) == CssColor.Of(Some(JColor.Rgb(0, 0, 0))))
      assert(at(div.cssClass("c")) == CssColor.Of(Some(JColor.Rgb(17, 17, 17))))
      assert(at(div.id("i")) == CssColor.Of(Some(JColor.Rgb(34, 34, 34))))
      assert(
        at(div.data("theme", "dark")) == CssColor.Of(
          Some(JColor.Rgb(51, 51, 51)),
        ),
      )
      assert(problems.size == 2, problems.toString)
    }
end CssVarsTest
