<!-- claude 2026-10-20: created (L6, ADR 0014) -->
# Changelog

All notable changes to the published modules of tui4s are in this file. The
format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Each
module has its own version and its own tag (`<module>-v<version>`); the
versions follow early semantic versioning (AGENTS.md, "Compatibility").

The first release is planned as `0.1.0-RC1` for each module: a release
candidate, because Kyo and kyo-ui are at 1.0.0-RC7. Nothing is published yet.

## [Unreleased]

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
