# core-1 review: correctness and SPEC conformance

Reviewer lens: correctness and SPEC conformance (C1 + C2). Read-only review of
feat/core-1-model-view in .work/worktrees/core. No build was run.
Status: done. 7 findings, all minor. No critical or major problem found.

Checked and correct (no finding): App/Next/Quit split and level 1 `same`; Keymap lookup, `++`
left-wins, explain, duplicate check; Focus; TextField grapheme edits (insert/remove cursor stays in
range also when graphemes merge); order-independent sizing (`decorate` lifts Sized); public API vs
SPEC 2.3-2.10; Painter walk, list/table state select+clamp, sweep, hit map order, cursor; Layout.split
array is safe to read during the child walk (jatatui cache never mutates entries); CountingBackend
(Terminal.flush always calls backend.draw); Screen wide-cell skip and Reset normalisation (Style
record field order checked); diffScreen; the C1/C2 tests test what they claim.

## spec-1 (minor) Widget-state slot keys collide: two stateful nodes share one jatatui state
File: modules/core/src/main/scala/tui4s/internal/Painter.scala:401-408
- `.id(name)` key is `-(name.hashCode & 0xffffffff) - 2`. Java String hashes collide often
  ("Aa" and "BB" are both 2112). `row(list(xs, 0).id("Aa"), list(ys, sel).id("BB"))`: both lists
  get the same ListState in the same frame. List 1 resets the offset to 0, list 2 then scrolls from 0,
  so list 2 always puts its selection at the bottom edge: 5x Down then Up shows item2..item4, not
  item3..item5 (the "offset kept across frames" rule, SPEC 2.13 rule 15, is broken for both lists).
- Positions `parent*31 + i + 1` collide when a container has 32+ children: child 31 of sibling q
  == child 0 of sibling q+1 (`row(column(31 texts, list A), column(list B))`).
Fix: key `.id` subtrees by the name itself (HashMap[String, ...] or a (name, relative position)
key), and make tree positions collision-free (a path key, or a multiplier larger than any child
count with overflow fallback).

## spec-2 (minor) Engine.drawIfNeeded labels terminal I/O failures as AppFailed("paint")
File: modules/core/src/main/scala/tui4s/internal/Engine.scala:139-152
The catch wraps the whole `draw` (terminal.draw = paint + diff + backend.draw/hideCursor/flush).
With the real backend (C4/C5 reuse this method) an IOException or crossterm
RuntimeException("IO error ...") from flush becomes TuiError.AppFailed("paint", e); SPEC 5.6 and
5.12 require TuiError.TerminalIO. The run loop cannot tell user paint errors from I/O errors.
Fix: catch NonFatal only around `painter.paint` inside the draw callback (rethrow as a private
marker -> AppFailed("paint")); map every other NonFatal from `draw` to TuiError.TerminalIO.

## spec-3 (minor) Input hides the wide grapheme under the cursor at the right edge
File: modules/core/src/main/scala/tui4s/internal/Painter.scala:284-297
The scroll loop only keeps `before <= width - 1`. `input(TextField("abcd日").edit(Key.Left))` in a
5-cell area: before = 4, start = 0, setStringn draws "abcd" and drops 日 (needs 2 cells, 1 left).
The cursor sits at x = 4 on a blank cell; the character under the cursor is not shown.
Fix: reserve the width of the grapheme at the cursor (1 at the end):
`val need = if cursor < widths.size then widths(cursor).max(1) else 1;
 while before + need > area.width && start < cursor do ...`.

## spec-4 (minor) Help bar drops the " …" when the first entry fits but entry + " …" does not
File: modules/core/src/main/scala/tui4s/internal/Painter.scala:326-340 (doc: View.scala:240-242)
Counter keys at width 12: ends(1) = 11, 11 + 2 > 12, so shown = 1 and cut = false: the bar reads
"+ increment" with two entries hidden and no ellipsis. View.Help's doc says left-out entries end
with "…". PainterTest line 208 pins the wrong output.
Fix: when shown == 1 and n > 1 and ends(1) + 2 > max, cut the first entry to max - 2 cells and
append " …" (or "…"); update the test.

## spec-5 (minor) A view with `stateful(...)` is never equal to itself: level 2 never skips it
File: modules/core/src/main/scala/tui4s/dsl.scala:152-158 (View.scala:283-286)
`View.Stateful(() => fresh, paint)` holds a new closure on every view build, and case-class equality
compares it by reference. Even with a stable `paint` val, `App[Int](0, n => column(text((n/2).toString),
stateful(0)(paintVal)), (n, _) => n + 1)` + press('a') draws a frame (skippedEqualView = 0). SPEC 2.6/5.6
promise reference equality for functions, which `draw(paint)` gives and `stateful` cannot.
Fix: override equals/hashCode of View.Stateful to compare `paint` only (`fresh` is used only when
the slot is created).

## spec-6 (minor) `table(Nil, rows, widths)` still reserves an empty header row
File: modules/core/src/main/scala/tui4s/internal/Painter.scala:216-218
`withHeader(Row.of(emptyList))` gives a header of height 1 (jatatui Table: headerHeight =
header.map(Row::height)). `table(Nil, Seq(Seq("a"), Seq("b")), Nil)` in a 2-row area shows a blank row
and only "a".
Fix: call `withHeader` only when `t.header.nonEmpty`.

## spec-7 (minor) TuiTest.resize changes the screen when no frame follows
File: modules/core/src/main/scala/tui4s/testkit/TuiTest.scala:113-116
`backend.resize` runs before the `ended` check in `batch`. TestBackend.resize -> Buffer.resize is a
flat array copy, so the old frame is re-flowed into the new width. `t.press('q'); t.resize(30, 5);
t.screen` shows shifted, garbled rows although "later input is ignored". The same happens when
update throws on the Resize (Step.Failed, no redraw).
Fix: `if !ended then { backend.resize(w, h); batch(Seq(Resize(w, h))) }`; for a failed resize,
document it or resize the backend only after a successful draw.
