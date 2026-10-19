package tui4s.widgets

import kyo.*

/** A look of an app: the colours of the widgets ([[Colors]]), the colours of
  * the page, and the look of the web (the font, the corners of the controls,
  * the spacing unit). One value gives both platforms (D8): a terminal reads
  * only the colours ([[Theme.palette]], for `TuiApp.stylesheet`), and a browser
  * reads all of it ([[Theme.stylesheet]], for `UI.runStylesheet`).
  *
  * Two looks are built in: [[Theme.terminal]] (monospace, dark, square box
  * corners: the colours of [[Colors.defaults]]) and [[Theme.web]] (the system
  * font of the browser, light or dark by `prefers-color-scheme`, rounded
  * corners).
  *
  * With more than one theme, each one is a block for the selector
  * `[data-theme="<name>"]`: an element with `data("theme", name)` and its
  * subtree take that theme, on both platforms. So an app switches its look at
  * run time when it renders its root with another `data-theme`.
  *
  * {{{
  * object App extends TuiApp:
  *   override def stylesheet = Theme.palette(Theme.terminal, Theme.web)
  *   def ui = look.render(l => div.data("theme", l.name)(content))
  * }}}
  *
  * @param name
  *   the name of the theme: the value of `data-theme` that selects it
  * @param colors
  *   the colours (the light ones, when the theme has a dark variant)
  * @param dark
  *   the colours under `prefers-color-scheme: dark` on the web; a terminal also
  *   takes these when they are present (a terminal does not tell its scheme,
  *   and most are dark)
  * @param font
  *   the font of the web (a terminal has its own)
  * @param corners
  *   the corners of the inputs, the text areas and the buttons on the web
  * @param spacing
  *   the spacing unit of the web, in em (the padding of the controls)
  */
// claude 2026-10-19: created (L4): one Theme for both platforms (D8)
final case class Theme(
    name: String,
    colors: Theme.Palette,
    dark: Maybe[Theme.Palette],
    font: Theme.Font,
    corners: Theme.Corners,
    spacing: Double,
) derives CanEqual:
    /** The colours that a terminal shows: [[dark]] when present, else
      * [[colors]].
      */
    def terminalColors: Theme.Palette = dark.getOrElse(colors)
end Theme

/** The parts of a [[Theme]], the two built-in themes, and the stylesheets. */
// claude 2026-10-19: created (L4)
object Theme:
    /** The colours of a theme: one value for each variable of [[Colors]]
      * (`--tui4s-<name>`), and the background and the text of the page (the web
      * only: `--tui4s-background`, `--tui4s-text`). A value is a CSS colour
      * that the terminal reads too: `#rrggbb`.
      */
    final case class Palette(
        accent: String,
        muted: String,
        border: String,
        surface: String,
        selection: String,
        success: String,
        warning: String,
        danger: String,
        background: String,
        text: String,
    ) derives CanEqual:
        /** The variables of [[Colors]] (with no `--`) and their values, in the
          * order of [[Colors.names]].
          */
        def widgets: Chunk[(String, String)] =
          Colors.names.zip(
            Chunk(
              accent,
              muted,
              border,
              surface,
              selection,
              success,
              warning,
              danger,
            ),
          )

        /** The variables of the page: its background and its text. */
        def page: Chunk[(String, String)] =
          Chunk("tui4s-background" -> background, "tui4s-text" -> text)
    end Palette

    /** The font of the web: a CSS `font-family` list and a size in px. */
    final case class Font(family: String, size: Int) derives CanEqual

    /** The corners of the controls on the web. */
    enum Corners derives CanEqual:
        /** Square corners, as the box-drawing borders of a terminal. */
        case Square

        /** Round corners of `px` px. */
        case Rounded(px: Int)

        /** The radius in px (0 for [[Square]]). */
        def radius: Int = this match
            case Square     => 0
            case Rounded(r) => r
    end Corners

    /** The terminal look: the colours of [[Colors.defaults]] on a dark page, a
      * monospace font and square corners.
      */
    val terminal: Theme = Theme(
      name = "terminal",
      colors = Palette(
        accent = "#eab308",
        muted = "#6b7280",
        border = "#64748b",
        surface = "#111827",
        selection = "#1e3a5f",
        success = "#22c55e",
        warning = "#f59e0b",
        danger = "#ef4444",
        background = "#0b0f19",
        text = "#e5e7eb",
      ),
      dark = Absent,
      font = Font(
        "ui-monospace, SFMono-Regular, Menlo, Consolas, \"Liberation Mono\", monospace",
        16,
      ),
      corners = Corners.Square,
      spacing = 0.5,
    )

    // region: web
    /** The native web look: the system font of the browser, light colours, the
      * dark colours under `prefers-color-scheme: dark`, and round corners.
      */
    val web: Theme = Theme(
      name = "web",
      colors = Palette(
        accent = "#2563eb",
        muted = "#6b7280",
        border = "#d1d5db",
        surface = "#ffffff",
        selection = "#dbeafe",
        success = "#15803d",
        warning = "#b45309",
        danger = "#dc2626",
        background = "#f8fafc",
        text = "#111827",
      ),
      dark = Present(
        Palette(
          accent = "#60a5fa",
          muted = "#9ca3af",
          border = "#374151",
          surface = "#1f2937",
          selection = "#1e3a8a",
          success = "#4ade80",
          warning = "#fbbf24",
          danger = "#f87171",
          background = "#111827",
          text = "#f3f4f6",
        ),
      ),
      font = Font(
        "system-ui, -apple-system, \"Segoe UI\", Roboto, sans-serif",
        16,
      ),
      corners = Corners.Rounded(6),
      spacing = 0.5,
    )
    // endregion: web

    /** The line height of the web, in em: a row of text is this high. */
    val lineHeight: Double = 1.25

    /** The colours of the widgets that a terminal reads (the custom properties
      * of [[Colors]]): `first` at `:root`; with more themes, each one also in a
      * block for `[data-theme="<name>"]`. Each theme gives its
      * [[Theme.terminalColors]].
      */
    def palette(first: Theme, more: Theme*): Stylesheet =
      scopes(first, more).foldLeft(Stylesheet.empty) { case (sheet, (s, t)) =>
        s.block(sheet, t.terminalColors.widgets)
      }

    /** All of a theme for the web: the custom properties (the colours of the
      * widgets and of the page, `--tui4s-font-family`, `--tui4s-font-size`,
      * `--tui4s-radius`, `--tui4s-space`), the dark colours under
      * `prefers-color-scheme: dark`, and the rules of the look: the font, the
      * line height and the colours of the page, the controls (inputs, text
      * areas, buttons: the font of the page, a border, the corners, a padding),
      * and the backdrop of a layer (the parent of a `dialog` or an
      * `alertdialog`). `first` is at `:root`; with more themes, each one is
      * also under `[data-theme="<name>"]`.
      *
      * kyo-ui writes the style of an element on the element (its `style`
      * attribute), and a stylesheet rule does not override that. So these rules
      * set only what the widgets leave to the page: the borders of a `Panel`
      * keep their own corners.
      */
    def stylesheet(first: Theme, more: Theme*): Stylesheet =
      scopes(first, more).foldLeft(Stylesheet.empty) { case (sheet, (s, t)) =>
        val withVars = s.block(sheet, vars(t, t.colors))
        val withDark = t.dark.fold(withVars)(d =>
          withVars.media(Stylesheet.MediaQuery.prefersDark)(
            s.block(Stylesheet.empty, vars(t, d)),
          ),
        )
        rules(withDark, s.selector, t)
      }

    /** Where the block of a theme goes: the root, or the elements with a
      * `data-theme`.
      */
    private enum Scope:
        /** `:root`. */
        case Root

        /** `[data-theme="<name>"]`. */
        case Named(name: String)

        /** The selector of the scope. */
        def selector: Selector = this match
            case Root     => Selector.tag(":root")
            case Named(n) => Selector.data("theme", n)

        /** `sheet` and a block of the custom properties `pairs` here. */
        def block(
            sheet: Stylesheet,
            pairs: Chunk[(String, String)],
        ): Stylesheet =
          this match
              case Root     => sheet.vars(pairs*)
              case Named(_) => sheet.scopedVars(selector, pairs*)
    end Scope

    /** The scope of each theme: `first` at the root, and each theme by its name
      * when there are more than one.
      */
    private def scopes(first: Theme, more: Seq[Theme]): Chunk[(Scope, Theme)] =
      (Scope.Root, first) +:
        (if more.isEmpty then Chunk.empty
         else Chunk.from(first +: more).map(t => (Scope.Named(t.name), t)))

    /** All the custom properties of `t` with the colours `p`. */
    private def vars(t: Theme, p: Palette): Chunk[(String, String)] =
      p.widgets ++ p.page ++ Chunk(
        "tui4s-font-family" -> t.font.family,
        "tui4s-font-size"   -> s"${t.font.size}px",
        "tui4s-radius"      -> s"${t.corners.radius}px",
        "tui4s-space"       -> s"${t.spacing}em",
      )

    private val background = Style.Color.variable("tui4s-background")
    private val text       = Style.Color.variable("tui4s-text")

    /** `sheet` and the rules of the look of `t` under `s`. */
    private def rules(sheet: Stylesheet, s: Selector, t: Theme): Stylesheet =
        val control                       = Style
          .fontFamily(_.Custom("inherit"))
          .fontSize(1.em)
          .color(text)
          .bg(Colors.surface)
          .border(1.px, Colors.border)
          .rounded(t.corners.radius.px)
          .padding((t.spacing / 2).em, t.spacing.em)
        val backdrop                      = Style.bg(Style.Color.rgba(0, 0, 0, 0.45))
        def layer(role: String): Selector =
          s.descendant(Selector.tag("div").pseudo(s"""has(> [role="$role"])"""))
        sheet
          .rule(
            s,
            Style
              .fontFamily(_.Custom(t.font.family))
              .fontSize(t.font.size.px)
              .lineHeight(lineHeight)
              .bg(background)
              .color(text),
          )
          .rule(s.descendant(Selector.tag("input")), control)
          .rule(s.descendant(Selector.tag("textarea")), control)
          .rule(s.descendant(Selector.tag("button")), control)
          .rule(layer("dialog"), backdrop)
          .rule(layer("alertdialog"), backdrop)
    end rules
end Theme
