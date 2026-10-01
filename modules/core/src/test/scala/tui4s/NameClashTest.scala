package tui4s

import kyo.*
import kyo.test.*
import tui4s.*
import tui4s.testkit.*

/** A file with `import kyo.*`, `import kyo.test.*`, `import tui4s.*` and
  * `import tui4s.testkit.*` that uses the public names of the model, the view
  * and the test kit: no name is ambiguous.
  */
class NameClashTest extends Test[Any]:
    "kyo.*, kyo.test.*, tui4s.* and tui4s.testkit.* together" in {
      val keys                     = Keymap[Int](Bind[Int]('a', "a")(_ + 1).or(Key.Up))
      val app: App[Int]            = App[Int](0, _ => empty, (n, _) => n)
      val next: Next[Int]          = Quit(1)
      val events: Seq[Event]       = Seq(
        Key.Char('a'),
        Mouse(MouseKind.Down(Button.Left), 0, 0, Mods(), Nil),
        Resize(1, 1),
        Paste("p"),
        FocusGained,
        FocusLost,
      )
      val focus                    = Focus(1, 2)
      val field                    = TextField("x")
      val span: Line.Span          = "x".bold
      val kyoSpan: kyo.Span[Int]   = kyo.Span(1, 2)
      val style: Style             =
        Style.bold.fg(Color.Red) ++ Style.bg(Color.rgb(1, 2, 3))
      val options                  = TuiOptions(viewport = Viewport.Inline(5), theme = Theme())
      val error: TuiError          = TuiError.AppFailed("x", new Exception)
      val stats                    = Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
      val views: Seq[View]         = Seq(
        text("a", span).centered.right.wrap(false).scroll(1),
        paragraph("a", line("b".dim.italic.underlined.reversed.crossedOut)),
        column(empty, clear).gap(1).flex(Flex.Center),
        row(layers(empty)).flex(Flex.SpaceAround),
        list(Seq("a"), 0),
        table(
          Seq("h"),
          Seq(Seq("c")),
          Seq(Constraint.Length(1), Constraint.Min(1)),
        ),
        tabs(Seq("t"), 0),
        gauge(0.1),
        lineGauge(0.2, "l", Color.Red),
        sparkline(Seq(1L), Some(2L), Color.Blue),
        input(field, placeholder = "p"),
        logPane(Seq("l", Line("x".red, "y".green, "z".yellow))),
        help(keys).centered,
        widget(jatatui.widgets.paragraph.Paragraph.of("w")),
        stateful(0)((_, _, _) => ()),
        draw((_, _) => ()),
        text("x".blue, "y".magenta, "z".cyan, "w".gray, "v".white)
          .size(1)
          .bordered("t")
          .framed(Border("b", BorderKind.Thick, style, Style.dim))
          .padded(1)
          .style(Style.italic.underlined.reversed.crossedOut.dim)
          .popup(3, 3)
          .id("i")
          .tag("t")
          .fill()
          .percent(1)
          .ratio(1, 2)
          .atLeast(1)
          .atMost(2),
        text("x".fg(Color.Gray).bg(Color.Black).styled(Style.default)),
      )
      val withTask: Next[Int]      = 1.withTask()
      val source: Seq[Source[Int]] = Seq.empty
      val t: TuiTest[Int]          = TuiTest(app, 3, 1)
      val screen: Screen           = t.screen
      assertScreen(screen, "")
      val align: Seq[Align]        = Seq(Align.Left, Align.Center, Align.Right)
      assert(
        keys(0, Key.Char('a')) == 1 && app.init == 0 && next != withTask &&
          events.size == 6 && focus.is(1) && field.cursor == 1 &&
          kyoSpan.size == 2 && options.paste && error.getMessage == "x threw" &&
          stats.frames == 0 && views.size == 18 && align.size == 3 &&
          source.isEmpty && diffScreen(screen, "").isEmpty,
      )
    }
end NameClashTest
