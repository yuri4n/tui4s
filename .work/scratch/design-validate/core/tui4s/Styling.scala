package tui4s

import jatatui.core.style.Color as JColor
import jatatui.core.style.Modifier as JModifier
import jatatui.core.style.Style as JStyle
import jatatui.core.text.Line as JLine
import jatatui.core.text.Span as JSpan

// Opaque types over jatatui's immutable records: no conversion, structural equality.
// Everything in this file sees the jatatui types (the aliases are transparent here), so no code in this file
// calls a tui4s extension method on them: a member of the jatatui type would win (for example `Style.fg()`).

opaque type Color = JColor

object Color:
  val Reset: Color        = JColor.RESET
  val Black: Color        = JColor.BLACK
  val Red: Color          = JColor.RED
  val Green: Color        = JColor.GREEN
  val Yellow: Color       = JColor.YELLOW
  val Blue: Color         = JColor.BLUE
  val Magenta: Color      = JColor.MAGENTA
  val Cyan: Color         = JColor.CYAN
  val Gray: Color         = JColor.GRAY
  val DarkGray: Color     = JColor.DARK_GRAY
  val White: Color        = JColor.WHITE
  val LightRed: Color     = JColor.LIGHT_RED
  val LightGreen: Color   = JColor.LIGHT_GREEN
  val LightYellow: Color  = JColor.LIGHT_YELLOW
  val LightBlue: Color    = JColor.LIGHT_BLUE
  val LightMagenta: Color = JColor.LIGHT_MAGENTA
  val LightCyan: Color    = JColor.LIGHT_CYAN
  def rgb(r: Int, g: Int, b: Int): Color = JColor.Rgb(r, g, b)
  def indexed(i: Int): Color             = JColor.Indexed(i)

  private[tui4s] def toJ(c: Color): JColor = c
end Color

opaque type Style = JStyle

object Style:
  val default: Style    = JStyle.empty()
  val bold: Style       = JStyle.empty().bold()
  val dim: Style        = JStyle.empty().dim()
  val italic: Style     = JStyle.empty().italic()
  val underlined: Style = JStyle.empty().underlined()
  val reversed: Style   = JStyle.empty().withAddModifier(JModifier.REVERSED)
  val crossedOut: Style = JStyle.empty().withAddModifier(JModifier.CROSSED_OUT)
  def fg(c: Color): Style = JStyle.empty().withFg(c)
  def bg(c: Color): Style = JStyle.empty().withBg(c)

  private[tui4s] def toJ(s: Style): JStyle   = s
  private[tui4s] def fromJ(s: JStyle): Style = s

  // (validated: a separate top-level object in this file does NOT see through a top-level opaque type; only the
  // companion and the top-level definitions do. So the ops live in an object nested in the companion.)
  /** Extensions on Style (re-exported by `dsl`). */
  object StyleOps:
    extension (s: Style)
      def fg(c: Color): Style       = s.withFg(c) // withFg, never the record accessor fg()
      def bg(c: Color): Style       = s.withBg(c)
      def bold: Style               = s.withAddModifier(JModifier.BOLD)
      def dim: Style                = s.withAddModifier(JModifier.DIM)
      def italic: Style             = s.withAddModifier(JModifier.ITALIC)
      def underlined: Style         = s.withAddModifier(JModifier.UNDERLINED)
      def reversed: Style           = s.withAddModifier(JModifier.REVERSED)
      def crossedOut: Style         = s.withAddModifier(JModifier.CROSSED_OUT)
      def ++(other: Style): Style   = s.patch(other)
  end StyleOps
end Style


opaque type Line = JLine

object Line:
  /** A styled piece of a line. Nested, because `kyo.Span` exists and `import kyo.*` is common. */
  opaque type Span = JSpan

  def apply(parts: (String | Span)*): Line = JLine.from(parts.map(toSpan)*)
  val empty: Line                          = JLine.empty()

  private[tui4s] def toSpan(p: String | Span): JSpan = p match
    case s: String => JSpan.raw(s)
    case s: JSpan  => s
  private[tui4s] def toJ(l: Line): JLine          = l
  private[tui4s] def lineOf(p: String | Line): JLine = p match
    case s: String => JLine.raw(s)
    case l: JLine  => l
  private[tui4s] def fromJ(l: JLine): Line = l
  private[tui4s] def styled(s: String, st: JStyle): Span = JSpan.styled(s, st)

  /** Extensions on String and Line.Span (re-exported by `dsl`); each returns a Line.Span, so calls chain. */
  object SpanOps:
    private inline def mod(sp: JSpan, m: JModifier): Span = sp.patchStyle(JStyle.empty().withAddModifier(m))
    private inline def col(sp: JSpan, c: JColor): Span     = sp.patchStyle(JStyle.empty().withFg(c))

    extension (s: String)
      def bold: Span             = mod(JSpan.raw(s), JModifier.BOLD)
      def dim: Span              = mod(JSpan.raw(s), JModifier.DIM)
      def italic: Span           = mod(JSpan.raw(s), JModifier.ITALIC)
      def underlined: Span       = mod(JSpan.raw(s), JModifier.UNDERLINED)
      def reversed: Span         = mod(JSpan.raw(s), JModifier.REVERSED)
      def crossedOut: Span       = mod(JSpan.raw(s), JModifier.CROSSED_OUT)
      def fg(c: Color): Span     = col(JSpan.raw(s), c)
      def bg(c: Color): Span     = JSpan.raw(s).patchStyle(JStyle.empty().withBg(c))
      def styled(st: Style): Span = JSpan.styled(s, st)
      def red: Span              = col(JSpan.raw(s), JColor.RED)
      def green: Span            = col(JSpan.raw(s), JColor.GREEN)
      def yellow: Span           = col(JSpan.raw(s), JColor.YELLOW)
      def blue: Span             = col(JSpan.raw(s), JColor.BLUE)
      def magenta: Span          = col(JSpan.raw(s), JColor.MAGENTA)
      def cyan: Span             = col(JSpan.raw(s), JColor.CYAN)
      def gray: Span             = col(JSpan.raw(s), JColor.GRAY)
      def white: Span            = col(JSpan.raw(s), JColor.WHITE)
    end extension

    extension (s: Span)
      def bold: Span             = mod(s, JModifier.BOLD)
      def dim: Span              = mod(s, JModifier.DIM)
      def italic: Span           = mod(s, JModifier.ITALIC)
      def underlined: Span       = mod(s, JModifier.UNDERLINED)
      def reversed: Span         = mod(s, JModifier.REVERSED)
      def crossedOut: Span       = mod(s, JModifier.CROSSED_OUT)
      def fg(c: Color): Span     = col(s, c)
      def bg(c: Color): Span     = s.patchStyle(JStyle.empty().withBg(c))
      def styled(st: Style): Span = s.patchStyle(st)
      def red: Span              = col(s, JColor.RED)
      def green: Span            = col(s, JColor.GREEN)
      def yellow: Span           = col(s, JColor.YELLOW)
      def blue: Span             = col(s, JColor.BLUE)
      def magenta: Span          = col(s, JColor.MAGENTA)
      def cyan: Span             = col(s, JColor.CYAN)
      def gray: Span             = col(s, JColor.GRAY)
      def white: Span            = col(s, JColor.WHITE)
    end extension
  end SpanOps
end Line
