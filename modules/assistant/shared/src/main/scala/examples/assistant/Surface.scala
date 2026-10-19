package examples.assistant

import kyo.*
import tui4s.widgets.Theme

/** The platform that shows the UI, and what the shared UI does differently on
  * it: the units of its sizes, the chords of its commands, the keys of its
  * composer, how the person gives a key, and its first look. The main of each
  * platform gives it as a Kyo layer, as the other services.
  *
  * The units: tui4s reads one em (and one px) as one cell, and a browser reads
  * one em as the size of the font. So the shared UI gives each size that means
  * cells through [[rows]] and [[cols]]: in a terminal, `n` cells; in a browser,
  * `n` lines of text ([[Theme.lineHeight]] em each) or `n` columns
  * ([[Surface.column]] em each, about the width of a character).
  */
// claude 2026-10-19: created (L4)
enum Surface derives CanEqual:
    /** A terminal: tui4s. The keys come from the environment or a key file. */
    case Terminal

    /** A browser: kyo-ui's DOM runner. `vault` keeps the keys that the person
      * types in the settings.
      */
    case Browser(vault: KeyVault)

    /** The height of `n` rows of text. */
    def rows(n: Int): Length.Em = this match
        case Terminal   => n.em
        case Browser(_) => (n * Theme.lineHeight).em

    /** The width of `n` columns (cells). */
    def cols(n: Int): Length.Em = this match
        case Terminal   => n.em
        case Browser(_) => (n * Surface.column).em

    /** The style of a pane whose widget sizes its window of lines in em (the
      * `Pager` of the log): in a browser, one line is one em high there.
      */
    def lines: Style = this match
        case Terminal   => Style.empty
        case Browser(_) => Style.lineHeight(1.0)

    /** The style of a `Panel`: in a browser a border is a thin line, not a row,
      * so a panel has a row of margin above it for its title (the title sits
      * one row above the top of the box).
      */
    def panel: Style = this match
        case Terminal   => Style.empty
        case Browser(_) => Style.margin(rows(1), 0.px, 0.px, 0.px)

    /** The look at the first start (before the person picks one). */
    def look: Look = this match
        case Terminal   => Look.Terminal
        case Browser(_) => Look.Web

    /** The keys of the composer, for the help. */
    def composerHelp: Chunk[(String, String)] = this match
        case Terminal   =>
          Chunk(
            "Enter"            -> "send the message (in the composer)",
            "Alt-Enter/Ctrl-J" -> "a new line (in the composer)",
          )
        case Browser(_) =>
          Chunk(
            "Enter"            -> "send the message (in the composer)",
            "Shift-Enter"      -> "a new line where the caret is",
            "Alt-Enter/Ctrl-J" -> "a new line at the end of the message",
          )

    /** The title of the composer. */
    def composerTitle: String = this match
        case Terminal   => "Message · Enter sends · Alt-Enter new line"
        case Browser(_) => "Message · Enter sends · Shift-Enter new line"

    /** The hint of an empty conversation. */
    def hint: String = this match
        case Terminal   => Line.hint
        case Browser(_) =>
          "Type a message below. Enter sends it; Shift-Enter starts a new line. Alt-P (Ctrl-P on a Mac) lists the commands."
end Surface

/** The rules of the units and of the composer of the web. */
// claude 2026-10-19: created (L4)
object Surface:
    /** The width of a column in a browser, in em: about the width of a
      * character of a monospace font (most characters of a proportional font
      * are narrower).
      */
    val column: Double = 0.6

    /** The size in cells of a window of `width` x `height` CSS pixels, with a
      * font of `fontPx` px.
      */
    def cells(width: Int, height: Int, fontPx: Int): ScreenSize =
        val px = fontPx.max(1).toDouble
        ScreenSize(
          (width / (px * column)).toInt.max(1),
          (height / (px * Theme.lineHeight)).toInt.max(1),
        )

    /** What the next input event of the composer of the web does: nothing, or
      * the end of a send by Enter.
      */
    enum Echo derives CanEqual:
        /** No send waits for its input event. */
        case Off

        /** Enter sent `before` and left `after` in the composer. */
        case Sent(before: String, after: String)

    /** The text of the composer for the input event `text` that comes after
      * `echo`. A browser inserts a line break into a text area for Enter, after
      * the key: so after a send, a text with more line breaks than the sent one
      * is that break, and the composer keeps what the send left (`after`).
      * Another text stays as it is.
      */
    def echo(echo: Echo, text: String): String = echo match
        case Echo.Sent(before, after) if breaks(text) > breaks(before) => after
        case _                                                         => text

    private def breaks(s: String): Int = s.count(_ == '\n')
end Surface
