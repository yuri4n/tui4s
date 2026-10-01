package tui4s

import kyo.test.*

class StylingTest extends Test[Any]:
    "style extensions chain and commute" in {
      assert(Style.bold.fg(Color.Red) == Style.fg(Color.Red).bold)
      assert(Style.bold ++ Style.fg(Color.Red) == Style.bold.fg(Color.Red))
      assert(Style.default.bg(Color.Blue) == Style.bg(Color.Blue))
      assert(Style.bold != Style.dim)
      assert(
        Style.default.italic.underlined.reversed.crossedOut.dim ==
          Style.dim ++ Style.italic ++ Style.underlined ++ Style.reversed ++
          Style.crossedOut,
      )
    }

    "fg is the tui4s extension, not the jatatui accessor" in {
      val s: Style = Style.bold.fg(Color.Yellow)
      assert(s == Style.fg(Color.Yellow) ++ Style.bold)
    }

    "string and span extensions build equal spans" in {
      assert("x".bold.red == "x".red.bold)
      assert("x".styled(Style.italic) == "x".italic)
      assert("x".fg(Color.Green) == "x".green)
      assert("x".bold.styled(Style.fg(Color.Red)) == "x".bold.red)
      assert("x".bg(Color.Blue) != "x".blue)
      assert("x".bold != "x".dim)
      val all = Seq(
        "x".bold,
        "x".dim,
        "x".italic,
        "x".underlined,
        "x".reversed,
        "x".crossedOut,
        "x".red,
        "x".green,
        "x".yellow,
        "x".blue,
        "x".magenta,
        "x".cyan,
        "x".gray,
        "x".white,
      )
      assert(all.distinct.size == all.size)
    }

    "lines compare by value" in {
      assert(line("a", "b".bold) == Line("a", "b".bold))
      assert(line("a") != line("b"))
      assert(Line() == Line.empty)
    }

    "colors" in {
      assert(Color.rgb(1, 2, 3) == Color.rgb(1, 2, 3))
      assert(Color.indexed(7) != Color.indexed(8))
      assert(Theme().accent == Color.Cyan)
    }
end StylingTest
