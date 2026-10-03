# core-1: fix review findings

Worktree: .work/worktrees/core, branch feat/core-1-model-view. Agent: fixer. Started 2026-10-01.
Findings: spec-1, spec-3, spec-4, spec-6, spec-7, api-1..api-7, api-9, runtime-1, runtime-3, runtime-5.

## Progress
- 12:15 re-checked every finding in the code and in the jatatui 0.31 sources. All are real:
  - Line.raw splits on '\n' into spans of ONE line (Line.java splitToSpans); Text.raw splits into lines.
  - Terminal.draw autoresizes (and clears the backend) BEFORE the paint callback.
  - Painter.slot compares only the tag; tree positions 31p+i+1 collide from index 31; ids use String.hashCode.
  Plan: write the regression tests first (expect failures), then fix, then fmt + check.
- 12:20 regression tests written (PainterTest, TuiTestTest, KeymapTest, NameClashTest, new StrictEqualityTest).
  Red run (log .work/logs/core-1-fix-red.log): StrictEqualityTest does not compile (api-7 confirmed); with it
  set aside, 19 new leaves fail for the reported reasons (shared ListState "item2..item4", CCE in the generic
  stateful helper, blank table header, glued lines, no ellipsis, reflowed screen after resize, no
  AssertionError for click, no Keymap rejection). One test bug fixed: a stateful-only view is equal after
  '+' (the by-name thunk is a singleton), so the swapper view now holds a hidden count.
- 12:40 fixes written (not compiled yet): Painter (64-bit positions, second-visit rule in slot, state class in
  the Stateful slot, no empty header, wide grapheme at the cursor, log rows split, help always ends with "…"),
  Styling (oneLine/linesOf, CanEqual givens), dsl (paragraph split, stateful[St: ClassTag]), View (derives
  CanEqual, Stateful.kind), Focus (derives CanEqual), Key.sent + Keymap check, TuiTest (resize at draw time,
  kept screen, option checks, key mapping), core build.sbt (Test / fork).
- 12:35 `sbt -batch fmt check` green: 113 kyo-test leaves, versionPolicyCheck ok (log .work/logs/core-1-fix-check.log).
  One wrong expectation fixed in my own test (table offset after Down, Down, Up, Up is 4..6). Removed the now
  unused `Line.fromJ`. Next: core/doc link check, commits, final check.

## Deviations from SPEC added by these fixes
- SPEC 5.7 positions: a child is `mix(parent ^ mix(index + c))` (MurmurHash3 fmix64), an `.id` is the mixed
  64-bit FNV-1a hash of the name, not `parent * 31 + index + 1` / a 32-bit hash. A node whose position was
  already visited in the frame (one `.id` used twice, or a collision) takes `childPosition(pos, -n)`, in
  paint order. Still a `Long`, no allocation.
- SPEC 2.7 `stateful[St: ClassTag]` (was `stateful[St]`): the slot key is (paint class, state class), so a
  generic helper with two state types gets two states. `View.Stateful` has a third field `kind: Class[?]`.
- Help bar: when entries are left out, the bar always ends with "…" (" …" after whole entries; the first
  entry is cut to width-1 and "…" follows when not even it and " …" fit).
- `paragraph` and `logPane` split a String at '\n' / "\r\n" (ratatui `Text::raw` rules); one-line nodes
  (text, line, spans, list items, table cells, tab titles) turn line breaks and tabs into spaces.
- `table(Nil, ...)` has no header row.
- TuiTest: keys go through `Key.sent` (terminal mapping); Mouse needs `mouse = true`, Focus events need
  `focusEvents = true` (AssertionError); a Resize resizes the backend only when its frame is drawn, and a
  failed paint of a resized frame keeps a copy of the last frame. `resize` = `send(Resize(...))`.
- Keymap.apply also rejects keys a terminal never sends (control `Key.Char`, `Key.Ctrl` upper case).
- CanEqual: View, Focus (derives), givens for Color, Style, Line, Line.Span.
- core tests run in a forked JVM (`Test / fork`, `-Xmx1g`, `--sun-misc-unsafe-memory-access=allow`).
- 12:55 commits on feat/core-1-model-view:
  19ea18f core: CanEqual for View, Focus, Color, Style, Line and Line.Span (api-7, api-9b)
  d2d3cc7 core: painter fixes for widget-state identity, line breaks and edges (spec-1, api-6, runtime-3,
          runtime-1, api-9a, api-1, spec-6, spec-3, spec-4, api-5)
  a89bb84 testkit: input as a terminal sends it; resize only with a drawn frame (spec-7, api-4, api-2, api-3)
  850a83c build: run the core tests in a forked JVM (runtime-5)
  19ea18f and d2d3cc7 each pass core/test on their own (logs core-1-fix-<sha>.log). Tip: `sbt -batch fmt check`
  green, 113 leaves, no reformat, versionPolicyCheck ok; core/doc has no link warnings. Not pushed. DONE.
