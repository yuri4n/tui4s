<!-- claude 2026-10-20: created: the concepts of each task and stack (L5) -->
# Concepts per task and stack

A concept is a thing that a reader must know to read and change the
implementation: an API type or function of the stack, a rule of its model, or
a language feature that the task needs and a beginner of the language does not
know. The language basics (a function, a loop, a string) are not concepts.

<!-- claude 2026-10-20: changed: CONTRIBUTING.md (ADR 0021) -->
**This count is the judgment of one reader** (the author of the
implementations), not a measurement. A reviewer of each ecosystem checks it
before the numbers are published (see `CONTRIBUTING.md`, "Benchmarks").

| Stack | counter | dashboard |
| ----- | ------: | --------: |
| tui4s | 11 | 23 |
| jatatui-plain | 9 | 18 |
| TamboUI | 9 | 17 |
| layoutz | 8 | 16 |
| ratatui | 10 | 19 |
| bubbletea | 9 | 17 |
| Textual | 11 | 22 |
| ink | 9 | 16 |

## tui4s

Counter (11): `TuiApp`; the pending type `UI < (Async & Scope)`; a
for-comprehension over effects; `Signal.initRef`; `SignalRef.updateAndGet`
and `.unit`; `Signal.map` in a view; the kyo-ui elements (`div`, `span`);
`style` (`height` in percent, `margin`, `flexGrow`); `onKeyDown` with a match on
`KeyboardEvent` and `Keyboard`; `Tui.quit`; `Sync.defer`.

Dashboard (23): the 11 of the counter, and `useInterval`; `useNow` and
`Instant.toJava`; `useSelection`; `ListView.virtual`; `Gauge`; `Sparkline`;
`StatusBar`; `Theme.palette` and `TuiApp.stylesheet`; `Tui.size` and
`Signal.current`; `component(key)` (the identity of a mount); the focus model of
kyo-ui (`tabIndex`, `onFocus`, `onBlur`); `Signal.render` and `combineLatest`.

## jatatui-plain

Counter (9): `Jatatui.runIo` (raw mode, the alternate screen, the restore); a
shutdown hook with a latch and a volatile flag (the signals); a loop of `poll`
and `read`; the crossterm records (`Event.Key`, `KeyCode`) in a pattern
`switch`; the bits of `KeyModifiers`; `Terminal.draw` with a `Frame` lambda;
`Layout` and `Constraint`; `Paragraph`; the effectively-final rule of a lambda.

Dashboard (18): the 9 of the counter, and the tick time with `nanoTime` and
the poll timeout; `Block`; `List` and `ListState` (a stateful widget);
`HighlightSpacing`; `Gauge`; `Sparkline`; nested layouts and `Block.inner`;
the state class and its `Focus` enum; `DateTimeFormatter`.

## TamboUI

Counter (9): `TuiRunner.create` and `TuiConfig` (`noTick`);
try-with-resources; the event handler `(event, runner)` and its redraw
result; the `KeyEvent` helpers (`isChar`, `isCtrlC`, `hasCtrl`, `isUp`);
`runner.quit`; the renderer `Frame`; `Layout` and `Constraint`; `Paragraph`;
static state for the lambdas.

Dashboard (17): the 9 of the counter, and `tickRate` and `TickEvent`; the
`Block` builder; the `ListWidget` builder, `ListState` and `ScrollMode`;
`Gauge`; the `Sparkline` builder; `Block.inner`; `KeyCode`;
`DateTimeFormatter`.

## layoutz

Counter (8): `LayoutzApp[State, Msg]` (the Elm architecture); `init`,
`update`, `subscriptions`, `view`; the message enum; `Cmd`; `Sub.onKeyPress`
and the `Key` ADT with an `Option` result; the element DSL (`layout`, `Text`,
the conversion of a `String`); `run(quitKey = ...)`; `stty size` through
`scala.sys.process` (layoutz gives no size).

Dashboard (16): the 8 of the counter, and `Sub.batch`; `Sub.time.everyMs`;
the state case class and `copy`; `box` and `row`; `padTo` for the widths; a
viewport offset by hand; a sparkline by hand; `DateTimeFormatter`.

## ratatui

Counter (10): `ratatui::init` and `restore` (and the panic hook);
`DefaultTerminal` and `draw` with a `Frame` closure; `event::poll` and
`event::read`; `Event`, `KeyEvent`, `KeyEventKind`, `KeyCode`,
`KeyModifiers`; a `match` with guards; `Layout::vertical`, `Constraint` and
`areas` into an array; a `&str` and a `String` as a widget; `signal-hook` with
`Arc<AtomicBool>` and `Ordering`; `io::Result` and `?`; the borrow of
`&mut terminal`.

Dashboard (19): the 10 of the counter, and `Instant` and `Duration` for the
tick; `Block`; `List`, `ListState` and `HighlightSpacing`; `Gauge`;
`Sparkline`; `VecDeque`; a struct `App` and an enum with derives;
`chrono::Local`; `Block::inner`.

## bubbletea

Counter (9): `tea.Model` (`Init`, `Update`, `View`); value receivers (the
model is a copy); the type switch on `tea.Msg`; `tea.KeyMsg` and its key
names; `tea.WindowSizeMsg`; `tea.Quit`; `tea.NewProgram`, `WithAltScreen` and
`Run`; a view that is a string (the layout by hand); the runes of one read in
one `KeyMsg`.

Dashboard (17): the counter without the runes rule (8), and `tea.Tick` (a
command that arms itself again); `tea.Cmd`; a message type of the app;
`progress.Model` (`ViewAs`, `Width`) of bubbles; lipgloss styles and borders
(`Width`, `Height`, `MaxHeight`); `JoinHorizontal` and `JoinVertical`; a
viewport offset by hand; a sparkline by hand; the time layout `15:04:05`.

## Textual

Counter (11): an `App` subclass; `compose` and `ComposeResult`; `Static` and
`update`; the CSS of Textual (`margin`, `dock`); `BINDINGS` with action
strings (`add(1)`) and key names (`plus`); `Binding(priority=True)` for
Ctrl-C; `reactive` and `watch_` methods; `action_` methods; `query_one` with
a selector; `App.run` and `return_code`; a signal handler on the asyncio loop
and `App.exit`.

Dashboard (22): the 11 of the counter, and a `Widget` with `render` and
`can_focus`; the bindings of a widget; `content_size`; the containers
(`Horizontal`, `Vertical`, in a `with` block); `border_title` and the `:focus`
pseudo-class; `ProgressBar`; `Sparkline`; `set_interval`; `on_mount`;
`on_descendant_focus` and `has_focus`; `is_mounted`.

## ink

Counter (9): a React function component and JSX; `useState` with an update
function; `render` with `alternateScreen`; `Box` and `Text` (a flex layout);
`useInput` and its `(input, key)` arguments; `useApp().exit`;
`useWindowSize`; the characters of one read in one input; the JSX build
(esbuild).

Dashboard (16): the counter without the input rule (8), and `useAnimation`;
`useFocus` (`autoFocus`, `isFocused`, Tab); the `isActive` option of
`useInput`; `useRef`; a component with props and children; `borderStyle` and
`borderColor`; the `key` of a list item; `Date` for the clock.
