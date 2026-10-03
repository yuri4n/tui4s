package examples.clash

import kyo.*
import tui4s.*

/** SPEC 2.12: "No clash with import kyo.*". Every name that `import tui4s.*` brings, used once while `import kyo.*` is
  * also in scope. A clash is a compile error ("Reference to X is ambiguous").
  */
object NameClash:
  val terms: Seq[Any] = Seq(
    App, Quit, Next, Key, Mods, Mouse, MouseKind, Button, Resize, Paste, FocusGained, FocusLost, Keymap, Bind, Focus,
    TextField, View, Style, Color, Line, Flex, Align, Border, BorderKind, Constraint, Theme, Source, CallSite, Tui,
    TuiOptions, Viewport, TuiError, Stats, NotForeign, dsl
  )
  type Types = (
      App[Int], Next[Int], Event, Key, Mods, Mouse, MouseKind, Button, Resize, Paste, Keymap[Int], Bind[Int],
      Focus[Int], TextField, View, Style, Color, Line, Line.Span, Flex, Align, Border, BorderKind, Constraint, Theme,
      Source[Int], Dispatch[Int], NotForeign[Int], CallSite, TuiHandle[Int], TuiApp[Int], TuiOptions, Viewport,
      TuiError, Stats
  )
  val views: Seq[View] = Seq(
    text("a"), paragraph("a"), column(), row(), layers(), empty, clear, list(Nil, 0), table(Nil, Nil, Nil),
    tabs(Nil, 0), gauge(0.5), lineGauge(0.5), sparkline(Nil), input(TextField.empty), logPane(Nil),
    help(Keymap[Int]())
  )
  val line1: Line            = line("a".bold, "b".red.underlined, "c".fg(Color.rgb(1, 2, 3)))
  val style1: Style          = Style.bold.fg(Color.Red) ++ Style.dim.bg(Color.indexed(3))
  val next1: Next[Int]       = 1.withTask(Source.For[Int].every(1.second)(_ + 1))
  val span1: Line.Span       = "x".styled(Style.italic).crossedOut
  val kyoSpan: Span[Int]     = Span(1, 2) // kyo.Span is still usable
end NameClash
