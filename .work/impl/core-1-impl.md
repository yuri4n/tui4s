# core-1: model and view (C1 + C2) - implementation notes

Worktree: .work/worktrees/core, branch feat/core-1-model-view (from chore/cleanup 6f70179).
Agent: implementer (workflow W1). Started 2026-10-01.

## Plan

Scope = SPEC 8.2 rows C1 + C2. Sources/engine fold for sources/loop/terminal/facades = later PRs.

Files (modules/core/src/main/scala/tui4s/):
1. package.scala   - Scaladoc overview of the model (package object holds `export dsl.*`)
2. App.scala       - App, App.apply, Next, Quit, Next.Then
3. Event.scala     - Event, Key (+show), Mods, Mouse, MouseKind, Button, Resize, Paste, FocusGained/Lost
4. Keymap.scala    - Bind, Keymap (+help entries); Focus.scala; TextField.scala (grapheme aware)
5. Source.scala    - MINIMAL Source[S] class: label, id, recover, equality by label (no constructors: C3)
6. Options.scala   - TuiOptions, Viewport, TuiError, Stats, Theme (needed by App.options / TuiTest)
7. Styling.scala   - opaque Color, Style, Line, Line.Span + Style.StyleOps / Line.SpanOps
8. View.scala      - View + nodes + modifiers (Sized lifted outside), Flex, Align, BorderKind, Border, Constraint
9. dsl.scala       - every constructor + extensions + withTask
10. internal/Painter.scala, internal/CountingBackend.scala, internal/Engine.scala (events only)
11. testkit/TuiTest.scala (C2 subset), testkit/Screen.scala, testkit/package.scala (diffScreen, assertScreen)

Build: modules/core/build.sbt adds kyo-test-runner (Versions.kyo) + SbtFramework; root build.sbt adds -Wconf.

Tests (modules/core/src/test/scala/tui4s/): KeymapTest, TextFieldTest, FocusTest, NextTest, KeyShowTest,
ViewTest (equality/sizing), PainterTest (golden screens per node), FrameContractTest (C1-C3),
ScreenTest, CounterTest (+fixtures), NameClashTest.

Open decisions to record as deviations: help -> View.Help node (SPEC 5.7 open item); minimal Source;
TuiTest subset; sparkline shows newest values; Screen.find/region in cells.

## Progress
- 10:50 build wiring: kyo-test-runner (Versions.kyo) + SbtFramework in modules/core/build.sbt, -Wconf in
  root build.sbt. Smoke suite: kyo-test runs under sbt 1.13, reports failures, fails the build. munit removed
  from core (no core test needs it).
- 11:01 all main sources of C1+C2 written and compiling (package object + export dsl.* works, no warning):
  package.scala, App, Event, Keymap, Focus, TextField, Source (minimal), Options (TuiOptions, Viewport,
  TuiError, Stats), Styling, View (+ View.Help), dsl, internal/{package, CountingBackend, Painter, Engine},
  testkit/{Screen, TuiTest}. Next: tests.
- 11:20 tests written (KeyTest, KeymapTest, TextFieldTest, FocusTest, NextTest, ViewTest, StylingTest, PainterTest, FrameContractTest, ScreenTest, CounterTest, TodoTest, TuiTestTest, NameClashTest) + fixtures (Counter, Todo without sources) + TuiSuite base. First run next.
- 11:14 FOUND: incremental compile cycle. `export dsl.*` (package object) + `import jatatui...` in a file of
  package tui4s: the import qualifier is looked up in package tui4s -> package object -> export elaboration
  -> dsl signatures -> Color -> the same import. Full compiles pass by luck of order. FIX: files in package
  tui4s write `import _root_.jatatui...` (Styling, View, dsl). Verified: 11 single-file incremental
  compiles (each tui4s-package file touched in turn) all pass. Log .work/logs/core-1-incr.log.
- 11:18 core/test: kyo-test 96 tests, 96 passed (all failures were wrong hand-made expectations: jatatui table spacing, ratatui gauge label quirk, sparkline levels, two miscounts, two test bugs). Next: fmt, commits, check.
- 11:25 fmt done; 5 commits (14b26dd build, 36be294 model+view API, 8517eb1 painter/engine/testkit, e8c92d4 C1 tests, bee1974 C2 tests). Running check + core/doc.
- 11:30 sbt -batch check: green (fmt check, compile, kyo-test 96/96, versionPolicyCheck). core/doc: no link
  warnings; the package overview is in api/tui4s.html. Commit e1: dropped two dead private[tui4s] helpers;
  fmt + check green again. DONE.

## Deviations from SPEC (also in the structured result)
- help(keys) returns View.Help (entries + align, .centered/.right), not View.Text: resolves SPEC 5.7 open item
  (theme helpKey/helpText). Truncation: whole entries, then " …"; first entry cut when none fits.
- Source: identity API only (label, id, recover, equals by label); private ctor (label, recoverWith, keyed).
  C3 adds body/fold/kind to the private ctor and the constructors to object Source.
- TuiTest: C2 subset (state, quit, error, frames, stats, screen, view, cursor, press/send/typeText/paste/click/
  resize). sources/started/emit/fail/fold/lastTrace/trace/viewTree/replay/live -> C3/C4. withTask tasks are
  dropped by TuiTest for now (Engine.takePending).
- Engine.fold takes Iterable[Event] (C3 widens to Item). AppFailed where-strings use trace codes
  ("update on key:x"), plus "init" and "paint" (escape-hatch paint errors).
- Painter: no markResized (the engine keeps the resize flag); sparkline shows the newest values that fit;
  Stateful slots keyed by position + paint-function class; list/table highlight symbol "> "; table header bold;
  tabs selection clamped; gauge clamped already in the dsl (NaN -> 0) so equal screens compare equal.
- Screen.find returns a cell column; region is in cells and trimmed.
- TextField: Paste turns line breaks/tabs into spaces, drops other control chars; control Key.Char ignored.
- Files in package tui4s import `_root_.jatatui...` (incremental-compile cycle with export dsl.*).
- package.scala uses `package object tui4s` (holds export dsl.* and the package Scaladoc).
