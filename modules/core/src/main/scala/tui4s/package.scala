/** tui4s is a terminal runner for kyo-ui. A [[kyo.UI]] value describes the
  * screen; tui4s lays it out as a flexbox in terminal cells and paints it into
  * a jatatui buffer. The UI DSL is kyo-ui: an app imports `kyo.*` and
  * `kyo.UI.*` for the elements and styles, and `tui4s.*` to run them.
  *
  * In this version tui4s lays out and paints a value (`tui4s.internal.Layout`,
  * `tui4s.internal.Paint`) and follows its signals: a `Signal`, a `Foreach` or
  * a bound `SignalRef` drives a region of the screen, and each change gives one
  * frame (`tui4s.internal.Runner`; [[tui4s.TuiOptions]] tunes it,
  * [[tui4s.FrameStats]] describes a frame). Keys reach the UI: the handlers of
  * the focused element and its ancestors run, Tab moves the focus, a text input
  * edits by grapheme clusters, a bracketed paste is one edit, and
  * [[tui4s.Tui.quit]] ends the run. [[tui4s.Tui.run]] runs a UI in the real
  * terminal (raw mode, the alternate screen, an input thread for the keys),
  * restores the terminal when the run ends, and fails with a [[tui4s.TuiError]]
  * when it cannot start. The headless test kit [[tui4s.testkit.Headless]] runs
  * an app without a terminal and sends it keys. Components come in two styles:
  * [[tui4s.Hooks]] (a component is a function with `Signal.initRef`,
  * `useEffect`, `useInterval`, ...; `component(key)` lets the runner mount it)
  * and the class [[tui4s.Component]] (a `State` type member; the runner keeps
  * the state under the position and the state key). A component in a `when`
  * loses its state when it is hidden, and the fibers of the components and
  * hooks end with the run. [[tui4s.TuiApp]] is the main class of an app: it
  * prepares the JVM (the scheduler values of [[tui4s.spi.SchedulerDefaults]],
  * the output capture), runs the UI with `Tui.run`, and gives the exit code (0
  * after a quit, 1 after a failure; 143 and 130 when `kill -TERM` and
  * `kill -INT` stop the JVM).
  *
  * Scroll, wrap and ellipsis are plain kyo-ui styles:
  * {{{
  * div.style(_.height(10.px).overflowY(_.scroll))(lines*)   // a viewport
  * span(title).style(
  *   _.overflow(_.hidden).textOverflow(_.ellipsis).whiteSpace(_.noWrap))
  * }}}
  *   - Viewports (`overflow`, `overflowX`, `overflowY`): an element with an
  *     axis that is not `visible` lays out its content at its natural size on
  *     that axis and clips it to its box. `hidden` does not scroll; `scroll`
  *     and `auto` scroll. Such an element is a tab stop when its content does
  *     not fit and it holds no focusable element (`tabIndex(-1)` takes it out);
  *     its track is heavy while it has the focus. A content taller than a box
  *     of 3 rows or more shows a scroll indicator in the right column
  *     (`scrollbarWidth`, `scrollbarColor`). The scroll offset is kept while
  *     the viewport stays mounted, and it starts at 0 after an unmount.
  *   - The keys of a viewport (on the focused element or inside it): ArrowUp
  *     and ArrowDown scroll one row, PageUp and PageDown one viewport height,
  *     Home and End to the top and to the bottom, ArrowLeft and ArrowRight one
  *     column (horizontal scroll only). The innermost viewport that can move
  *     scrolls. No scroll when the focused element uses the key (a text input,
  *     a select, a focus group) or when the focus chain has kyo-ui's
  *     `preventScrollKeys`. With no focus, a key scrolls nothing.
  *   - Follow-focus: when the focus moves (or the element that its
  *     `aria-activedescendant` names), and after a key or a paste on the
  *     focused element, each viewport around it scrolls the least distance that
  *     shows it.
  *   - Text: `whiteSpace` (`noWrap`, `pre`, `preWrap`, `normal`, `preLine`),
  *     `textWrap` (`wrap`, `noWrap`, `ellipsis`) and `textOverflow` with
  *     `overflow(_.hidden)` on the element that holds the text ("…" at the
  *     cut). A text wraps in a column and in a row of a definite `width`. Every
  *     measure counts cells: a wide character takes two, and a cut never splits
  *     it.
  *
  * The style properties in the terminal: the 76 cases of kyo-ui's `Style.Prop`
  * (kyo-ui 1.0.0-RC7), by case name. `StyleTableTest` compares this list with
  * the surface list of kyo-ui and with the runner, so they do not drift.
  *   - honored: `Align`, `AlignSelf`, `BgColor`, `BorderColorProp`,
  *     `BorderRadiusProp`, `BorderStyleProp`, `BorderWidthProp`, `Bottom`,
  *     `DisabledProp`, `DisplayProp`, `FlexDirectionProp`, `FlexGrowProp`,
  *     `FlexShrinkProp`, `FlexWrapProp`, `FocusProp`, `FontStyleProp`,
  *     `FontWeightProp`, `Gap`, `Height`, `HiddenProp`, `Justify`, `Left`,
  *     `ListStyleProp`, `Margin`, `MaxHeight`, `MaxWidth`, `MinHeight`,
  *     `MinWidth`, `OpacityProp`, `OverflowProp`, `OverflowXProp`,
  *     `OverflowYProp`, `Padding`, `PositionProp`, `Right`,
  *     `ScrollbarColorProp`, `ScrollbarWidthProp`, `TextAlignProp`,
  *     `TextColor`, `TextDecorationProp`, `TextOverflowProp`,
  *     `TextTransformProp`, `TextWrapProp`, `Top`, `WhiteSpaceProp`, `Width`,
  *     `ZIndexProp`.
  *   - ignored: `AccentColorProp`, `ActiveProp`, `AnimationDelayProp`,
  *     `AnimationProp`, `BackgroundClipProp`, `BgGradientProp`, `BlurProp`,
  *     `BorderCollapseProp`, `BrightnessProp`, `ContrastProp`, `CursorProp`,
  *     `FlexBasisProp`, `FontFamilyProp`, `FontSizeProp`,
  *     `FontVariantLigaturesProp`, `GrayscaleProp`, `HoverProp`,
  *     `HueRotateProp`, `InvertProp`, `LetterSpacingProp`, `LineHeightProp`,
  *     `SaturateProp`, `ScrollMarginTopProp`, `ScrollbarGutterProp`,
  *     `SepiaProp`, `ShadowProp`, `StrokeDashoffsetProp`, `TransitionProp`,
  *     `TranslateProp`.
  */
// claude 2026-10-19: changed: the package Scaladoc names scroll, wrap and
// ellipsis, with the key table (L1a), and the table of the style properties
// (L1b)
package object tui4s
