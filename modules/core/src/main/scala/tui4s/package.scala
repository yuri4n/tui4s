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
  * and a textarea edit by grapheme clusters, a bracketed paste is one edit, and
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
  * Overlays, flex lines and custom colours are kyo-ui styles too, and the size
  * of the terminal is one tui4s signal:
  * {{{
  * div.style(_.position(_.overlay).justify(_.center).align(_.center))(
  *   div.focusTrap(true).style(_.border(1.px, _.white).bg(_.black))("Help"))
  * span("Saved").style(_.position(_.fixed).bottom(0.px).right(0.px))
  * Tui.size.map(size => div(menu, main).style(size.map(s =>
  *   if s.width >= 100 then Style.row else Style.column)))
  * span("ok").style(_.color(Style.Color.variable("accent")))
  * }}}
  *   - Layers: an element with `position` absolute or fixed (also kyo-ui's
  *     `overlay` and `dropdown`) leaves the flex flow. Its offsets (`top`,
  *     `right`, `bottom`, `left`) place it in its containing block: the padding
  *     box of the nearest positioned ancestor, else the screen; the screen for
  *     fixed. `relative` stays in the flow and moves by its offsets; `sticky`
  *     stays in the flow and does not stick.
  *   - The paint order: the flow first, then the positioned elements by
  *     `zIndex` (0 when none), the equal ones in document order; each one is a
  *     stacking context of its own. In a layer, a background hides what is
  *     under it. A layer is clipped only by the viewports of its chain of
  *     containing blocks. When a frame changes cells under a layer, those rows
  *     are painted again in paint order, so nothing shows through it.
  *   - The focus: a layer keeps its place in the Tab order. A modal is a layer
  *     with kyo-ui's `focusTrap`; `focusAuto(true)` gives the focus to an
  *     element when it appears, and `focusRestore(true)` gives it back when the
  *     element goes. Esc does nothing by itself: the app's `onKeyDown` closes
  *     the modal.
  *   - [[tui4s.Tui.size]]: the size of the terminal as a `Signal[TerminalSize]`
  *     (kyo-ui has no such signal on the JVM). A resize writes it in the frame
  *     of the resize.
  *   - `flexWrap(_.wrap)` breaks the items into lines, each as high as its
  *     tallest item; `displayNone` removes an element from the layout and the
  *     paint.
  *   - `Style.Color.variable(name)`: the colour of the custom property `name`
  *     of the stylesheet of the run (`TuiApp.stylesheet`,
  *     `Tui.run(options, sheet)`), from kyo-ui's `Stylesheet.vars` and
  *     `Stylesheet.scopedVars`. A name with no value gives no colour: a text
  *     keeps the colour of its parent.
  *
  * The inputs are the native kyo-ui elements with their bindings
  * (`.value(ref)`, `.checked(ref)`); [[tui4s.maxLength]] is the one tui4s name
  * (kyo-ui has no such attribute):
  * {{{
  * form.onSubmit(send)(
  *   input.value(name).maxLength(20),
  *   numberInput.value(age).min(0).max(130),
  *   textarea.value(message).style(_.height(3.px)))
  * }}}
  *   - A text input and a textarea edit by grapheme clusters; a paste is one
  *     edit. The focused one shows its caret with the terminal cursor.
  *   - The newline keys of a textarea: Alt-Enter and Ctrl-J insert a line
  *     break, and Shift-Enter too when the terminal reports Shift with Enter. A
  *     plain Enter submits the form that holds the textarea; outside a form it
  *     inserts a line break. ArrowUp and ArrowDown move one line and keep the
  *     column, PageUp and PageDown move by the height, Home and End go to the
  *     ends of the line, Ctrl-A and Ctrl-E to the ends of the text line. A
  *     paste keeps its line breaks. The height is the `height` of the style (3
  *     rows when none; kyo-ui has no `rows`), and a longer text scrolls in it.
  *   - A number input takes digits, one sign first and one decimal point.
  *     ArrowUp and ArrowDown change it by `step` (1 when none), clamped to
  *     `min` and `max`, and run `onChangeNumeric`; Enter commits the number.
  *   - A range moves by `step` with the arrows, by 10 steps with PageUp and
  *     PageDown, and to `min` and `max` with Home and End.
  *   - A password shows one "•" for each grapheme cluster. A select and a
  *     dropdown go to the next option with Space, Enter and ArrowDown, to the
  *     previous one with ArrowUp. `readOnly(true)` stops the edits and keeps
  *     the caret moves; `disabled(true)` takes the input out of the Tab order.
  *     The date, time, colour and file inputs show an empty box and warn once
  *     in the log of the run.
  *
  * The style properties in the terminal: the 76 cases of kyo-ui's `Style.Prop`
  * (kyo-ui 1.0.0-RC7), by case name. When an element has an ignored property,
  * the runner logs one warning for that name, once in a run, with the first
  * element that has it: "tui4s: the style property <name> is not supported in
  * the terminal (first seen at <position>)". The values `DisplayProp` listItem,
  * table, tableRow and tableCell, `PositionProp` sticky (it does not stick) and
  * a `Color.Var` with no value warn too. The values `DisplayProp` block, inline
  * and inlineBlock change nothing and give no warning: the element keeps its
  * flex layout, where a browser uses block or inline flow. The warnings go to
  * the logger of the run (`TuiOptions.logFile`, or the lines printed after the
  * restore), never to the screen; `Headless.awaitLog` gives them to a test.
  * `StyleTableTest` compares this list with the surface list of kyo-ui and with
  * the runner, so they do not drift.
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
// claude 2026-10-19: changed: the package Scaladoc names the inputs and their
// keys (L1c)
package object tui4s
