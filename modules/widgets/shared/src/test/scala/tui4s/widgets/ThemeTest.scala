package tui4s.widgets

import kyo.*
import kyo.test.*
import kyo.test.prop.{Gen, PropertyTest}

/** The themes: the colours of the terminal look are the defaults of L2b, the
  * palette has only custom properties (what a terminal reads), and the web
  * stylesheet has the dark colours and the rules of the look.
  */
// claude 2026-10-19: created (L4)
class ThemeTest extends Test[Any]:

    "the terminal look has the colours of the L2b defaults; Colors.defaults is its palette" in {
      val old = Map(
        "tui4s-accent"    -> "#eab308",
        "tui4s-muted"     -> "#6b7280",
        "tui4s-border"    -> "#64748b",
        "tui4s-surface"   -> "#111827",
        "tui4s-selection" -> "#1e3a5f",
        "tui4s-success"   -> "#22c55e",
        "tui4s-warning"   -> "#f59e0b",
        "tui4s-danger"    -> "#ef4444",
      )
      assert(
        Theme.terminal.terminalColors.widgets.toMap == old &&
          Colors.defaults == Theme.palette(Theme.terminal) &&
          Colors.defaults.entries == Chunk(
            Stylesheet.Entry.Vars(Theme.terminal.colors.widgets.toList),
          ),
      )
    }

    "a palette has only custom properties: no rule for a terminal to ignore" in {
      val sheet = Theme.palette(Theme.terminal, Theme.web)
      assert(sheet.entries.forall {
        case Stylesheet.Entry.Vars(_)          => true
        case Stylesheet.Entry.ScopedVars(_, _) => true
        case _                                 => false
      })
    }

    "the web stylesheet: the dark colours under prefers-color-scheme, the font, the corners and the backdrop" in {
      val css = Theme.stylesheet(Theme.web, Theme.terminal).render
      assert(
        css.contains("prefers-color-scheme: dark") &&
          css.contains("--tui4s-background: #111827") &&
          css.contains("system-ui") && css.contains("ui-monospace") &&
          css.contains("[data-theme=\"terminal\"]") &&
          css.contains("border-radius: 6px") &&
          css.contains("[role=\"dialog\"]"),
        css,
      )
    }
end ThemeTest

/** The law of the themes: each stylesheet of each theme declares every variable
  * of [[Colors]], at the root and in the block of each theme.
  */
// claude 2026-10-19: created (L4)
class ThemePropertyTest extends PropertyTest[Any]:

    private val themes: Gen[Theme] = Gen.oneOf(Theme.terminal, Theme.web)

    private val names: Gen[String] = Gen.oneOf(Colors.names.toSeq*)

    /** The names of the custom properties of each block of `sheet`. */
    private def blocks(sheet: Stylesheet): Chunk[Set[String]] =
      sheet.entries.collect {
        case Stylesheet.Entry.Vars(ps)          => ps.map(_._1).toSet
        case Stylesheet.Entry.ScopedVars(_, ps) => ps.map(_._1).toSet
      }

    "every Colors variable is in every block of the palette and of the stylesheet" - {
      forAll(themes, themes, names) { (a, b, name) =>
        val sheets = Chunk(
          Theme.palette(a),
          Theme.palette(a, b),
          Theme.stylesheet(a),
          Theme.stylesheet(a, b),
        )
        assert(
          sheets.forall(s =>
            blocks(s).nonEmpty && blocks(s).forall(_.contains(name)),
          ),
        )
      }
    }
end ThemePropertyTest
