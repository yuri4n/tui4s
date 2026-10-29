<!-- claude 2026-10-20: created (L6, ADR 0014) -->
# Changelog

<!-- claude 2026-10-20: changed: CONTRIBUTING.md names the versioning rules (ADR 0021) -->
All notable changes to the published modules of tui4s are in this file. The
format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Each
module has its own version and its own tag (`<module>-v<version>`); the
versions follow early semantic versioning (CONTRIBUTING.md, "Compatibility
and versioning").

<!-- codex 2026-10-22: changed: the first release is published; keep the Kyo qualifier. -->
The first release is `0.1.0-RC1` for each module. Releases keep an RC
qualifier while Kyo and kyo-ui are release candidates.

<!-- claude 2026-10-20: created: the release headings and the release notes (ADR 0020) -->
This file is the one source of the release notes. Before a release, its pull
request renames `## [Unreleased]` to `## [<version>] - <YYYY-MM-DD>`. Under
that heading, each module of the release has its subsection
`### tui4s-<module>`. The release workflow gives the text of that subsection
to the GitHub release of the module (`scripts/changelog-section.sh`). The
workflow stops before it creates a tag when a subsection is missing.

## [Unreleased]
<!-- codex 2026-10-22: created: settlement progress fixes after the diagnostic baseline. -->

### tui4s-runner

- Improved: dirty-region membership compares path steps without temporary
  vector equality work. Repeated marks keep the current world and preserve
  first-seen order.
  <!-- codex 2026-10-28: created: reduce repeated work during notification bursts. -->
- Changed: at most 2 reactive regions keep a watcher. Further reactive
  regions are sampled in slices of 32. An input frame still reads the watched
  regions, and a queued input paints before the next background frame.
  Sampled regions can update on a later frame.
  <!-- cursor 2026-10-28: created: keep loaded input off a watcher per derived region; cursor 2026-10-28: changed: watch two reactive regions. -->
<!-- codex 2026-10-22: created: bounded settlement with the existing public policies. -->
- Fixed: bounded inbox batches keep continuous notifications from bypassing
  the settlement deadline. Verification and its re-check share the same
  active time budget. Component startup keeps its separate bounded wait.
  <!-- codex 2026-10-22: created: enforce progress under continuous traffic. -->
- Fixed: quit is checked between notification batches, including with the
  explicit `Settle.Hops` policy.
  <!-- codex 2026-10-22: created: prompt shutdown and the remaining input-queue limitation; codex 2026-10-23: changed: describe shutdown separately from admission. -->
- Added: `Settle.Local(max, window, confirm)`, now the default, with values of
  16 ms, 1 ms and two empty observations. Code that exhaustively matches
  `Settle` must handle `Local`; an older compiled match can throw `MatchError`
  for this new case. Explicit `Quiescent` and `Hops` remain available.
  <!-- codex 2026-10-23: created: local settlement and its enum-match compatibility limit. -->
- Changed: signal watchers send change notices and the runner reads their
  current values. Intermediate signal states can coalesce. Input passes
  reconcile reactive regions before painting. Background admission leaves
  room for input in inboxes with capacity greater than one.
  <!-- codex 2026-10-23: created: current-state reconciliation and notification backpressure. -->
- Fixed: remove obsolete region ownership when flattened reactive content
  becomes a new item, including text inside a new container. Later text
  changes reuse the leaf without scanning the region map for removal.
  <!-- codex 2026-10-28: created: preserve cleanup while reducing repeated text work. -->

## [0.1.1-RC1] - 2026-10-23
<!-- codex 2026-10-23: changed: prepare the coordinated diagnostic release. -->

<!-- codex 2026-10-22: created: optional diagnostics, with settlement behavior unchanged. -->
### tui4s-runner

- Added: opt-in bounded JSON diagnostics with
  `-Dtui4s.diagnostics.file=<path>`. The report includes terminal receipt to
  frame-pass timings, pending input, backlog, runner phases, notification
  counts and settlement observations. It contains no application contents.
- Added: a daemon exporter outside the Kyo scheduler. It can report a
  stalled settlement before an input completes.
- Known issue: settlement still depends on global scheduler idleness.
  Unrelated work can delay input, and continuous notifications can extend
  settlement past its configured maximum. This release measures those
  conditions; it does not fix them.

<!-- codex 2026-10-23: created: app dependency for the coordinated diagnostic release. -->
### tui4s-app

- Changed: use the diagnostic release of tui4s-runner. A `TuiApp` can enable
  the report with the JVM option; its entry point stays the same.

<!-- codex 2026-10-23: created: testkit dependency for the coordinated diagnostic release. -->
### tui4s-testkit

- Changed: align the runner dependency with the diagnostic release.
  `Headless` retains its existing API and measurement boundaries.

<!-- codex 2026-10-22: created: the composite includes the runner diagnostics. -->
### tui4s-core

- Added: the runner's optional input and settlement diagnostics through the
  composite dependency. See the diagnostics guide for an independently
  paced PTY reproduction and the limits of each metric.

<!-- codex 2026-10-23: created: use participates in the coordinated version. -->
### tui4s-use

- Changed: align the artifact version with the coordinated `0.1.1-RC1`
  release. The composable APIs and behavior are unchanged.

<!-- codex 2026-10-23: created: widgets participates in the coordinated version. -->
### tui4s-widgets

- Changed: align the artifact version with the coordinated `0.1.1-RC1`
  release. The widget APIs and behavior are unchanged.

## [0.1.0-RC1] - 2026-10-20
<!-- claude 2026-10-20: changed: the heading of the first release (Releasing, step 1) -->

### tui4s-runner

- Added: `Tui.run` runs a kyo-ui UI in the real terminal (raw mode, the
  alternate screen) on jatatui, and restores the terminal in every way that a
  run ends, also from a JVM shutdown hook (SIGTERM, SIGINT, SIGHUP).
- Added: the layout of kyo-ui elements in terminal cells (flex rows and
  columns, wrap, layers, viewports that scroll, text wrap and ellipsis) and a
  pure painter.
- Added: the signals of a UI drive regions of the screen: one frame for each
  event, with all of its consequences.
- Added: keys and focus as in a browser: handlers that bubble, Tab and focus
  groups, buttons, forms, text inputs, a textarea, the password, number,
  range and dropdown inputs, bracketed paste.
- Added: components in two styles: hooks (`Hooks`: `useHold`, `useFold`,
  `useEffect`, `useInterval`, `component(key)(body)`) and the class
  `Component`, with a state that a parent render keeps.
- Added: `tui4s.initSig(initial)`, the short name of `Signal.initRef`.
  <!-- claude 2026-10-20: created: initSig (D26) -->
- Added: `TuiOptions` (a log file, the focus, the timeouts) and `TuiError`.
  Output to stdout and stderr during a run is kept and printed after the
  restore.
- Added: GraalVM native-image metadata for the crossterm library and the
  isatty downcall (`META-INF/native-image`).

### tui4s-app

- Added: `TuiApp`, the main class of an app: `def ui`, `options`, `args`,
  `onExit`, the stylesheet of the run.
- Added: the exit code 0 after a quit (also Ctrl-C) and 1 after a failure,
  with the failure printed after the restore.
- Added: the scheduler values of tui4s are set before the code of the app
  (`tui4s.spi.SchedulerDefaults`), unless a flag sets them.

### tui4s-testkit

- Added: `tui4s.testkit.Headless` runs an app with no terminal: keys
  (`press`, `key`, `char`, `typeText`), paste, resize and the writes of the
  app's signals.
- Added: `Screen` (the text and the style of each cell), `awaitScreen`, and
  `assertScreen` and `diffScreen` for golden screens.

### tui4s-core

- Added: one dependency for an app: tui4s-runner, tui4s-app and
  tui4s-testkit.

### tui4s-use

- Added: composables over Kyo signals for the JVM and Scala.js:
  `useHistory`, `useSelection`, `useScrollWindow`, `useDialog`, `useAsync`,
  `useStreamText`, `useNow`, `debounced`, `throttled`, `useDebouncedFn`,
  `usePersisted`, `useProcess` (JVM) and `Keymap`.
- Added: each composable takes its lifetime from the `Scope` of its caller.

### tui4s-widgets

- Added: widgets for the JVM and Scala.js in five families: containers,
  data, input, feedback and navigation (for example `Tabs`, `ListView`,
  `Gauge`, `Sparkline`, `StatusBar`, `CommandPalette`, `KeyHints`).
- Added: a `Model` for the value of a widget (`Local`, `Bound`,
  `Controlled`), typed props and function slots.
- Added: colours from the `--tui4s-*` custom properties (`Colors`), and a
  mark for each state, so a widget is clear with no colour.

### Removed before the first release

- The empty modules `tui4s-zio` and `tui4s-cats`. A ZIO and a Cats Effect
  integration are issues #18 and #19.
- `Hooks.useSignal`: use `Signal.initRef`.
