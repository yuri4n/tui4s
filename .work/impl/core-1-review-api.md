# core-1 review: Public API, DX and tests

Reviewer lens: public surface vs SPEC, naming, Scaladoc, Kyo-free shared parts,
no jatatui leaks outside escape hatches, internals hidden, SPEC 8 tests.

Status: done. 9 findings (1 major, 8 minor). No Kyo type in the shared surface; jatatui types only in the escape hatches; internals are private[tui4s]; the SPEC 8.2 C1/C2 test list is covered.

## Findings

### api-1 (major) paragraph/logPane/list/table glue the lines of a multi-line String
- File: modules/core/src/main/scala/tui4s/dsl.scala:22-28 (paragraph), Styling.scala:185-187 (Line.lineOf -> JLine.raw)
- jatatui `Line.raw("a\nb")` splits on '\n' into SPANS of ONE line (verified in jatatui-core 0.31 sources,
  Line.splitToSpans); `Text.raw` is the one that splits into lines. `text(...)` uses `Span.raw`, whose
  styledGraphemes drop the control char.
- Scenario: `paragraph("first line\nsecond line")` on 30x2 shows row 0 = "first linesecond line", row 1 empty.
  Same for `logPane(Seq(e.getMessage))` with a multi-line message, list items, table cells, `text("a\nb")` -> "ab".
  ratatui users expect two lines (Paragraph::new("a\nb") is two lines). No test uses '\n'.
- Fix: in `paragraph`, split each String part on line breaks (`s.split("\r?\n", -1)`) into separate Lines; for
  the one-line nodes (text, list item, table cell, log line) document "one line" and map '\n' to ' ' (or split
  log lines too). Add a golden test with "a\nb".

### api-2 (minor) TuiTest.click delivers Mouse events although TuiOptions.mouse is off (the default)
- File: modules/core/src/main/scala/tui4s/testkit/TuiTest.scala:100-108 (also send(...) of Mouse/FocusGained/FocusLost)
- Scenario: app with `.tag("ok")` and `case m: Mouse if m.tags.contains("ok")`, default `options`.
  `TuiTest(app).click(x, y)` changes the state and the test is green; the real run never enables mouse capture
  (Mouse doc: "reports mouse events only when TuiOptions.mouse is on"), so the button never works.
- Fix: `click` throws AssertionError("click needs TuiOptions(mouse = true) ...") when `!options.mouse`
  (same idea for FocusGained/FocusLost vs focusEvents in send), or at least document it on click.

### api-3 (minor) the test kit and Keymap accept keys the runtime never produces
- Files: testkit/TuiTest.scala:85-90 (typeText), :72-74 (press); Keymap.scala:137-147 (Keymap.apply checks)
- crossterm mapping (SPEC 2.4): Enter/Tab/Esc/Backspace are named keys; Ctrl+letter is `Key.Ctrl(c.toLower)`.
- Scenario A: `t.typeText("buy milk\n")` sends Key.Char('\n'): TextField ignores it and the Enter binding does
  not fire; a real terminal sends Key.Enter. "\r\n" is one grapheme -> Paste -> a space; "\n" -> ignored.
- Scenario B: `bind(Key.Ctrl('S'), "save")` + `t.press(Key.Ctrl('S'))` passes; a real Ctrl+S arrives as
  Key.Ctrl('s') and nothing happens. Same for `bind('\t', ...)`.
- Fix: TuiTest maps '\n'/'\r' -> Key.Enter, '\t' -> Key.Tab, ESC -> Key.Esc, DEL/'\b' -> Key.Backspace in
  press/typeText; Keymap.apply (dev check like the duplicate check) rejects Key.Char(control) and Key.Ctrl(upper)
  with a message that names the right key.

### api-4 (minor) TuiTest.send(Resize(w, h)) does not resize the test terminal
- File: testkit/TuiTest.scala:77-79 vs :113-116
- Scenario: `t.send(Resize(30, 5))`: update sees 30x5 and a frame is drawn (resize flag), but on the old 80x24
  buffer; `t.screen.rows` still has 80 columns. Only `t.resize` resizes the backend. Resize is an Event, so an
  agent sends it with `send`.
- Fix: in `batch`, resize the backend for each Resize event before folding (make `resize` call `send`).

### api-5 (minor) View.Help Scaladoc promises "…" but the painter drops it when the first entry fills the bar
- Files: modules/core/src/main/scala/tui4s/View.scala:240-243 (doc), internal/Painter.scala:326-341; the test
  PainterTest.scala:208-209 pins the behaviour ("+ increment" at width 12, "+ increm" at width 8, no "…").
- Scenario: Counter help bar in a 12-column area shows "+ increment" with no ellipsis; the user (or an agent
  reading t.screen) cannot see that "- decrement" and "q quit" exist. Widths 11-12 and < 11 both hide it.
- Fix: when entries are left out, always end with "…": cut the first entry to `max - 2` cells and append " …"
  (or "…" alone at width 1-2); or change the doc to say the ellipsis shows only when it fits.

### api-6 (minor) .id identity is a 32-bit String hash: different names can share one widget state
- File: modules/core/src/main/scala/tui4s/internal/Painter.scala:407-408 (idPosition), used at :158-159
- Scenario: `row(list(a, 0).id("Aa"), list(b, 7).id("BB"))` ("Aa".hashCode == "BB".hashCode == 2112): both lists
  get the same slot, so list b's render moves the offset that list a reads in the next frame (offsets fight).
  The same happens silently when an agent copies a pane and keeps `.id("items")` twice.
- Fix: key identified subtrees by the name itself (a name -> unique Long map kept and swept with the slots), and
  report a name used twice in one frame (dev check, like duplicate keys).

### api-7 (minor) View, Focus and the opaque Color / Style / Line / Line.Span have no CanEqual
- Files: View.scala:22, Focus.scala:20, Styling.scala:19/86/161/168
- Every other model type derives CanEqual (Event, Key, Mods, Screen, TextField, Theme, Border, ...), so the
  design supports `-language:strictEquality` users. Under it, the SPEC 2.10 idiom
  `t.screen.styleAt(x, y) == Style.bold.fg(Color.Yellow)` fails to compile ("Values of types tui4s.Style and
  tui4s.Style cannot be compared"), and so do `t.view == expected`, `line("a") == Line("a")`, `focus.next == f`.
- Fix: `sealed abstract class View derives CanEqual`, `final class Focus[A] ... derives CanEqual`, and in each
  opaque companion `given CanEqual[Color, Color] = CanEqual.derived` (same for Style, Line, Line.Span).

### api-8 (minor) public binary names depend on source file names
- Files: modules/core/src/main/scala/tui4s/App.scala:98-124, Styling.scala, testkit/Screen.scala:134-165
- Verified in target/: `Next.Then` is `tui4s.App$package$Next$Then`; Color/Style/Line and the ops objects are
  `tui4s.Styling$package$...`; diffScreen/assertScreen are in `tui4s.testkit.Screen$package`.
- Scenario: C3 moves `type Next`/`object Next` to Next.scala, or splits Styling.scala into Color/Style/Line
  files. Before the first release nothing notices; after it, versionPolicyCheck reports missing classes and
  forces a minor bump for a pure file move.
- Fix: decide the file names now (Next.scala for type/object Next and Quit; keep Styling.scala) and add a header
  comment "the file name is part of the binary API" to each file that holds top-level public definitions.

### api-9 (minor) test gaps: slot-kind replacement and the clash test
- Files: modules/core/src/test/scala/tui4s/PainterTest.scala, NameClashTest.scala:7-9
- (a) SPEC 5.7: "a slot of another node kind at that position is replaced by a fresh state". The implementer
  keys Stateful slots by the paint-function class precisely so that "a kept state never reaches the wrong
  paint", but no test swaps list <-> table or two different `stateful` nodes at one position. A regression
  gives a ClassCastException inside user paint code, reported as AppFailed("paint").
- (b) NameClashTest says it "uses every name of the model and the view once" but never names `Source` (a public
  top-level type now) or the testkit names (`Screen`, `TuiTest`, `diffScreen`) next to `import kyo.*` and
  `import kyo.test.*`.
- Fix: add the two PainterTest cases (assert fresh state after the swap) and extend NameClashTest.
