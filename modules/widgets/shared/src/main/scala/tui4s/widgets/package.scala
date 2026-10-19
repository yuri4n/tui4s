package tui4s

/** Reusable kyo-ui widgets for the terminal and the web. They are plain kyo-ui
  * code: the package does not depend on tui4s-core, and it builds for the JVM
  * and for Scala.js. It uses the composables of tui4s-use (`useSelection`,
  * `useScrollWindow`, `useDialog`, `Keymap`, `debounced`, `useNow`).
  *
  * The conventions of every widget:
  *   - Shape: a function of a companion object that returns the UI
  *     (`ListView(items, key)(row)`), or a handle with a `view` and its actions
  *     (`SearchBox`, `CommandPalette`, `Modal`, `Toasts`). `Tabs` is the one
  *     class with `.ui`: a runner that keeps a mount across renders uses its
  *     `initial`, `mount` and `view` (the tui4s adapter `examples.widgets.Tabs`
  *     does).
  *   - Typed props: named parameters with defaults; slots are functions (`row:
  *     A => UI`, `panel: Int => UI`).
  *   - State: a [[Model]] says who owns the value (`Local`: the widget;
  *     `Bound`: the parent's `SignalRef`, written before `onChange`;
  *     `Controlled`: the parent's `Signal`, and `onChange` asks). A list owns
  *     its `Selection` (tui4s-use), or takes the parent's.
  *   - Effects: a widget that only builds a UI is pure (`UI`); one that
  *     allocates state is `UI < Sync`; one that starts a fiber is
  *     `< (Sync & Scope)`. Build a widget once, where its state lives (the
  *     start of the app, the body of a component), not in a `render`.
  *   - Keys: the Scaladoc of each focusable widget has its key table. A list
  *     keeps the focus on its container and names the selected row with
  *     `aria-activedescendant` (a runner scrolls that row into view). A field
  *     with a list (`Autocomplete`, the palette) keeps the focus in the field
  *     and names the highlighted option in the same way; so does a menu. A
  *     layer (a modal, a menu, the palette) is `position` absolute or overlay,
  *     with `focusTrap`, `focusAuto` and `focusRestore`, and its own
  *     `onKeyDown` closes it on Esc and stops the keys there
  *     (`stopPropagation`); the field of an `Autocomplete` stops its keys while
  *     its list shows.
  *   - Layout and colours: kyo-ui `Style` only, and the colour variables of
  *     [[Colors]].
  *   - Sizes: a size that counts rows or cells (the height of a list, the width
  *     of a column or of a layer, the offset of a title) is in `em`; a space
  *     between parts (a padding, a margin, a gap, a border) is in `px`. The
  *     terminal gives one cell to each `em` and to each `px`. On the web, `1em`
  *     is the font size: a line is about `1.2em` high and a monospace cell
  *     about `0.6em` wide, so a size in `em` is near the terminal size, not
  *     equal to it, and a `px` space is thin. Thus these sizes are terminal
  *     cells: the widgets have no unit scale yet (a later step), and on the web
  *     a gap, a padding or a border of `1.px` is one CSS pixel, a width of
  *     `50.em` is 50 font sizes, and the window of a list or a pager (its
  *     height in `em`) clips about one line in five unless the line height is
  *     1. An app for both platforms gives its own sizes in units of its
  *     platform (the assistant: its `Surface`).
  *
  * The widgets:
  *
  * | Family     | Widget           | What it is                                                            |
  * |:-----------|:-----------------|:----------------------------------------------------------------------|
  * | containers | `Panel`          | A box with a border style and a title on the border.                  |
  * |            | `SplitPane`      | Two panes; the arrows on the divider resize them.                     |
  * |            | `Accordion`      | Sections that open and close (one or many open).                      |
  * |            | `Tabs`           | Tab buttons and the panel of the selected tab.                        |
  * | data       | `ListView`       | A list with a selection, keys, a viewport; `virtual` for a long list. |
  * |            | `DataTable`      | Columns with widths, sorting by a key, a selected row.                |
  * |            | `TreeView`       | A tree whose nodes expand and collapse.                               |
  * |            | `Pager`          | A text viewer: scroll, search, follow the end (a log).                |
  * |            | `Sparkline`      | One block character per value.                                        |
  * |            | `BarChart`       | Horizontal bars in text cells.                                        |
  * |            | `Gauge`          | A ratio as a bar and a percentage.                                    |
  * |            | `ProgressBar`    | Done of a total, as a bar and "3/10".                                 |
  * |            | `Spinner`        | A frame that changes on a clock.                                      |
  * | input      | `SearchBox`      | A text field and its debounced query.                                 |
  * |            | `Autocomplete`   | A text field with suggestions in a dropdown layer.                    |
  * |            | `Checklist`      | Items that Space checks and unchecks.                                 |
  * |            | `RadioGroup`     | One choice of several; the arrows choose.                             |
  * |            | `NumberStepper`  | A number that the arrows change, in a range.                          |
  * |            | `DateField`      | A date as text (YYYY-MM-DD), with its error.                          |
  * | feedback   | `StatusBar`      | A row: left text that ends with "…", right text.                      |
  * |            | `KeyHints`       | The key hints of a `Keymap`.                                          |
  * |            | `CommandPalette` | A layer: a search over the bindings of a `Keymap`.                    |
  * |            | `Modal`          | A confirm or a prompt layer over `useDialog`.                         |
  * |            | `Toasts`         | Short messages that go away after a time.                             |
  * |            | `Menu`           | A menu bar of `Keymap`s, each with a dropdown layer.                  |
  * |            | `Tooltip`        | A text under an element while it has the focus.                       |
  * |            | `Breadcrumbs`    | The path to the current place; each part is a button.                 |
  * |            | `Pagination`     | Page buttons with gaps ("1 … 4 5 6 … 20").                            |
  * |            | `Tags`           | Chips that Delete removes, and a field that adds one.                 |
  * |            | `Wizard`         | Steps with Back and Next.                                             |
  *
  * The colour variables (kyo-ui `Style.Color.variable`; [[Colors.defaults]] has
  * these values, the colours of [[Theme.terminal]]; a [[Theme]] gives others,
  * and the look of the web):
  *
  * | Variable            | Default   | Used for                                    |
  * |:--------------------|:----------|:--------------------------------------------|
  * | `--tui4s-accent`    | `#eab308` | the selection, the active tab, a filled bar |
  * | `--tui4s-muted`     | `#6b7280` | secondary text, hints, an inactive tab      |
  * | `--tui4s-border`    | `#64748b` | the borders of panels and layers            |
  * | `--tui4s-surface`   | `#111827` | the background of a layer                   |
  * | `--tui4s-selection` | `#1e3a5f` | the background of the selected row          |
  * | `--tui4s-success`   | `#22c55e` | a valid value, a done step                  |
  * | `--tui4s-warning`   | `#f59e0b` | a warning toast                             |
  * | `--tui4s-danger`    | `#ef4444` | an error message                            |
  *
  * The plan (L2b) of the 28 widgets of the terminal catalog: built now, later
  * (and why), or out of scope.
  *
  * | #  | Catalog widget         | Decision                                                                      |
  * |:---|:-----------------------|:------------------------------------------------------------------------------|
  * | 1  | Panel / block          | built: `Panel`                                                                |
  * | 2  | Tabs                   | built: `Tabs`                                                                 |
  * | 3  | List with selection    | built: `ListView`                                                             |
  * | 4  | Table                  | built: `DataTable`                                                            |
  * | 5  | Tree                   | built: `TreeView`                                                             |
  * | 6  | Text input             | out: the native kyo-ui `input`; `SearchBox` and `Autocomplete` build on it    |
  * | 7  | Textarea / editor      | out: the native kyo-ui `textarea`; an editor with line numbers is later       |
  * | 8  | Viewport / pager       | built: `Pager`                                                                |
  * | 9  | Log tail               | built: `Pager` with `Follow.Tail`                                             |
  * | 10 | Progress / gauge       | built: `Gauge`, `ProgressBar`                                                 |
  * | 11 | Spinner, timer         | built: `Spinner`; a stopwatch is later (`useNow` and `timeAgo` cover it)      |
  * | 12 | Sparkline              | built: `Sparkline`                                                            |
  * | 13 | Bar / line chart       | built: `BarChart`; a line chart is later (braille cells, or a Chart renderer) |
  * | 14 | Modal dialog           | built: `Modal.confirm`, `Modal.prompt`                                        |
  * | 15 | Toast                  | built: `Toasts`                                                               |
  * | 16 | Command palette        | built: `CommandPalette`                                                       |
  * | 17 | Menu (bar, dropdown)   | built: `Menu.bar`; a context menu needs the mouse: out                        |
  * | 18 | Help / key bar         | built: `KeyHints`                                                             |
  * | 19 | Status bar             | built: `StatusBar`                                                            |
  * | 20 | Split panes            | built: `SplitPane` (keys); a drag resize needs the mouse: out                 |
  * | 21 | Form with validation   | later: a typed field-error design; `DateField` shows one field                |
  * | 22 | Select / dropdown      | built: `Autocomplete` (a dropdown layer); the native `select` cycles          |
  * | 23 | Radio / checkbox group | built: `RadioGroup`, `Checklist`                                              |
  * | 24 | File picker            | later: the files of the JVM only (the module is portable)                     |
  * | 25 | Markdown viewer        | later: a Markdown parser for the JVM and Scala.js                             |
  * | 26 | Key-value list         | later: a `DataTable` with two columns covers it                               |
  * | 27 | Calendar / date picker | later: a calendar layer; `DateField` checks a typed date                      |
  * | 28 | Chat transcript        | later: the flagship app (L3)                                                  |
  */
// claude 2026-10-19: created (L2a): the empty package of tui4s-widgets;
// changed: the conventions, the widgets, the colour variables and the plan
// (L2b); the shapes, the field lists and the sizes (L2b review); the themes
// (L4); the sizes are terminal cells (L4 review)
package object widgets
