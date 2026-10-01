package tui4s

import _root_.jatatui.core.style.Color as JColor
import _root_.jatatui.core.style.Modifier as JModifier
import _root_.jatatui.core.style.Style as JStyle
import _root_.jatatui.core.text.Line as JLine
import _root_.jatatui.core.text.Span as JSpan

// Opaque types over jatatui's immutable records: no conversion at paint time,
// structural equality. Inside a companion object the alias is transparent, so
// code there must never call a tui4s extension method on these values: a
// member of the jatatui record would win (for example the accessor
// `Style.fg()`). The extension methods live in objects nested in the
// companions (`Style.StyleOps`, `Line.SpanOps`) and `dsl` re-exports them.

/** A terminal color: a named color, an RGB color or an index into the 256-color
  * palette.
  */
opaque type Color = JColor

/** The named colors and the color constructors. */
object Color:
    /** The terminal's default color. */
    val Reset: Color = JColor.RESET

    /** A named color of the 16-color palette. */
    val Black: Color = JColor.BLACK

    /** A named color of the 16-color palette. */
    val Red: Color = JColor.RED

    /** A named color of the 16-color palette. */
    val Green: Color = JColor.GREEN

    /** A named color of the 16-color palette. */
    val Yellow: Color = JColor.YELLOW

    /** A named color of the 16-color palette. */
    val Blue: Color = JColor.BLUE

    /** A named color of the 16-color palette. */
    val Magenta: Color = JColor.MAGENTA

    /** A named color of the 16-color palette. */
    val Cyan: Color = JColor.CYAN

    /** A named color of the 16-color palette. */
    val Gray: Color = JColor.GRAY

    /** A named color of the 16-color palette. */
    val DarkGray: Color = JColor.DARK_GRAY

    /** A named color of the 16-color palette. */
    val White: Color = JColor.WHITE

    /** A named color of the 16-color palette. */
    val LightRed: Color = JColor.LIGHT_RED

    /** A named color of the 16-color palette. */
    val LightGreen: Color = JColor.LIGHT_GREEN

    /** A named color of the 16-color palette. */
    val LightYellow: Color = JColor.LIGHT_YELLOW

    /** A named color of the 16-color palette. */
    val LightBlue: Color = JColor.LIGHT_BLUE

    /** A named color of the 16-color palette. */
    val LightMagenta: Color = JColor.LIGHT_MAGENTA

    /** A named color of the 16-color palette. */
    val LightCyan: Color = JColor.LIGHT_CYAN

    /** A 24-bit color; each component is 0 to 255. */
    def rgb(r: Int, g: Int, b: Int): Color = JColor.Rgb(r, g, b)

    /** A color of the 256-color palette, 0 to 255. */
    def indexed(i: Int): Color = JColor.Indexed(i)

    private[tui4s] def toJ(c: Color): JColor = c
end Color

/** How text looks: colors and modifiers (bold, dim, ...). Styles combine:
  * `Style.bold.fg(Color.Red)`, `base ++ highlight`.
  */
opaque type Style = JStyle

/** The basic styles. Combine them with the extension methods of [[dsl]]. */
object Style:
    /** No color and no modifier. */
    val default: Style = JStyle.empty()

    /** Bold text. */
    val bold: Style = JStyle.empty().bold()

    /** Dim (faint) text. */
    val dim: Style = JStyle.empty().dim()

    /** Italic text. */
    val italic: Style = JStyle.empty().italic()

    /** Underlined text. */
    val underlined: Style = JStyle.empty().underlined()

    /** Foreground and background swapped. */
    val reversed: Style = JStyle.empty().withAddModifier(JModifier.REVERSED)

    /** Crossed-out text. */
    val crossedOut: Style =
      JStyle.empty().withAddModifier(JModifier.CROSSED_OUT)

    /** The foreground color `c`. */
    def fg(c: Color): Style = JStyle.empty().withFg(c)

    /** The background color `c`. */
    def bg(c: Color): Style = JStyle.empty().withBg(c)

    private[tui4s] def toJ(s: Style): JStyle   = s
    private[tui4s] def fromJ(s: JStyle): Style = s

    /** Extension methods on [[Style]]. `dsl` (and so `import tui4s.*`)
      * re-exports them.
      */
    object StyleOps:
        extension (s: Style)
            /** This style with the foreground color `c`. */
            def fg(c: Color): Style = s.withFg(c) // never the accessor fg()

            /** This style with the background color `c`. */
            def bg(c: Color): Style = s.withBg(c)

            /** This style, bold. */
            def bold: Style = s.withAddModifier(JModifier.BOLD)

            /** This style, dim. */
            def dim: Style = s.withAddModifier(JModifier.DIM)

            /** This style, italic. */
            def italic: Style = s.withAddModifier(JModifier.ITALIC)

            /** This style, underlined. */
            def underlined: Style = s.withAddModifier(JModifier.UNDERLINED)

            /** This style, reversed. */
            def reversed: Style = s.withAddModifier(JModifier.REVERSED)

            /** This style, crossed out. */
            def crossedOut: Style = s.withAddModifier(JModifier.CROSSED_OUT)

            /** This style patched with `other`: the colors and modifiers that
              * `other` sets win.
              */
            def ++(other: Style): Style = s.patch(other)
        end extension
    end StyleOps
end Style

/** One line of styled text: a sequence of [[Line.Span]]s. Build it with
  * `line("✔ ".green, title)` or `Line(...)`.
  */
opaque type Line = JLine

/** Builds lines; holds the [[Line.Span]] type. */
object Line:
    /** A piece of a line with one style: `"error".bold.red`. Nested in `Line`
      * because `kyo.Span` exists and `import kyo.*` is common.
      */
    opaque type Span = JSpan

    /** A line of plain strings and spans. */
    def apply(parts: (String | Span)*): Line =
      JLine.fromSpans(java.util.Arrays.asList(parts.map(toSpan)*))

    /** The empty line. */
    val empty: Line = JLine.empty()

    private[tui4s] def toSpan(p: String | Span): JSpan = p match
        case s: String => JSpan.raw(s)
        case s: JSpan  => s

    private[tui4s] def toJ(l: Line): JLine = l

    private[tui4s] def fromJ(l: JLine): Line = l

    private[tui4s] def lineOf(p: String | Line): JLine = p match
        case s: String => JLine.raw(s)
        case l: JLine  => l

    /** Extension methods on `String` and [[Line.Span]]. Each returns a span, so
      * calls chain: `"x".bold.red`. `dsl` (and so `import tui4s.*`) re-exports
      * them.
      */
    object SpanOps:
        private def mod(sp: JSpan, m: JModifier): Span =
          sp.patchStyle(JStyle.empty().withAddModifier(m))

        private def col(sp: JSpan, c: JColor): Span =
          sp.patchStyle(JStyle.empty().withFg(c))

        private def back(sp: JSpan, c: JColor): Span =
          sp.patchStyle(JStyle.empty().withBg(c))

        private def raw(s: String): JSpan = JSpan.raw(s)

        extension (s: String)
            /** `s`, bold. */
            def bold: Span = mod(raw(s), JModifier.BOLD)

            /** `s`, dim. */
            def dim: Span = mod(raw(s), JModifier.DIM)

            /** `s`, italic. */
            def italic: Span = mod(raw(s), JModifier.ITALIC)

            /** `s`, underlined. */
            def underlined: Span = mod(raw(s), JModifier.UNDERLINED)

            /** `s`, reversed. */
            def reversed: Span = mod(raw(s), JModifier.REVERSED)

            /** `s`, crossed out. */
            def crossedOut: Span = mod(raw(s), JModifier.CROSSED_OUT)

            /** `s` in the foreground color `c`. */
            def fg(c: Color): Span = col(raw(s), Color.toJ(c))

            /** `s` on the background color `c`. */
            def bg(c: Color): Span = back(raw(s), Color.toJ(c))

            /** `s` in the style `st`. */
            def styled(st: Style): Span = JSpan.styled(s, Style.toJ(st))

            /** `s` in red. */
            def red: Span = col(raw(s), JColor.RED)

            /** `s` in green. */
            def green: Span = col(raw(s), JColor.GREEN)

            /** `s` in yellow. */
            def yellow: Span = col(raw(s), JColor.YELLOW)

            /** `s` in blue. */
            def blue: Span = col(raw(s), JColor.BLUE)

            /** `s` in magenta. */
            def magenta: Span = col(raw(s), JColor.MAGENTA)

            /** `s` in cyan. */
            def cyan: Span = col(raw(s), JColor.CYAN)

            /** `s` in gray. */
            def gray: Span = col(raw(s), JColor.GRAY)

            /** `s` in white. */
            def white: Span = col(raw(s), JColor.WHITE)
        end extension

        extension (s: Span)
            /** This span, bold. */
            def bold: Span = mod(s, JModifier.BOLD)

            /** This span, dim. */
            def dim: Span = mod(s, JModifier.DIM)

            /** This span, italic. */
            def italic: Span = mod(s, JModifier.ITALIC)

            /** This span, underlined. */
            def underlined: Span = mod(s, JModifier.UNDERLINED)

            /** This span, reversed. */
            def reversed: Span = mod(s, JModifier.REVERSED)

            /** This span, crossed out. */
            def crossedOut: Span = mod(s, JModifier.CROSSED_OUT)

            /** This span in the foreground color `c`. */
            def fg(c: Color): Span = col(s, Color.toJ(c))

            /** This span on the background color `c`. */
            def bg(c: Color): Span = back(s, Color.toJ(c))

            /** This span patched with the style `st`. */
            def styled(st: Style): Span = s.patchStyle(Style.toJ(st))

            /** This span in red. */
            def red: Span = col(s, JColor.RED)

            /** This span in green. */
            def green: Span = col(s, JColor.GREEN)

            /** This span in yellow. */
            def yellow: Span = col(s, JColor.YELLOW)

            /** This span in blue. */
            def blue: Span = col(s, JColor.BLUE)

            /** This span in magenta. */
            def magenta: Span = col(s, JColor.MAGENTA)

            /** This span in cyan. */
            def cyan: Span = col(s, JColor.CYAN)

            /** This span in gray. */
            def gray: Span = col(s, JColor.GRAY)

            /** This span in white. */
            def white: Span = col(s, JColor.WHITE)
        end extension
    end SpanOps
end Line
