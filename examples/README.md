# tui4s examples

<!-- claude 2026-10-19: changed: the rows of Todo and Dashboard name the scroll and the ellipsis (L1a); the rows of Counter styles and Dashboard name the custom colours, the help, the toast and the two columns (L1b) -->

This project has the sample apps of tui4s. They are not published. Each app
is a main class in its own package, and its start script has the name of the
package.

| App            | Main class                                 | Script                              | What it shows |
| -------------- | ------------------------------------------ | ----------------------------------- | ------------- |
| Counter        | `examples.counter.CounterApp`              | `examples/target/bin/counter`       | The smallest app: a kyo-ui UI with one hook (`useSignal`), key handlers, buttons; a `TuiApp` main (README "Quick start"). |
| Counter (KyoApp) | `examples.counterkyo.CounterKyoApp`      | `examples/target/bin/counterkyo`    | The same UI with a main of its own: a `KyoApp` that calls `Tui.run` and gives the UI the quit value. |
| Two counters   | `examples.twocounters.TwoCountersApp`      | `examples/target/bin/twocounters`   | A counter is a function that allocates its own state: two counters count on their own. |
| Counter styles | `examples.counterstyles.CounterStylesApp`  | `examples/target/bin/counterstyles` | Both component styles side by side: hook counters that the runner mounts (`component`, inside a `when`: `h` hides them and they lose their state) and `Component` counters. Their colours are custom properties of the stylesheet of the app (`Stylesheet.vars`, `Style.Color.variable`). |
| Todo           | `examples.todo.TodoApp`                    | `examples/target/bin/todo [file]`   | A list in a file (the argument, or `todos.txt` in the working directory): a text input, Enter adds, Tab to the list, Space toggles, `d` deletes, Ctrl-Z undoes a change of the list and Ctrl-Y redoes it (`useHistory` of tui4s-use), Ctrl-S saves, a paste is one edit, Esc quits. The keys of the app are a keymap (`Keymap.of`), and its help line lists them. The list and the input scroll in one viewport, and a long title ends with "…". |
| Dashboard      | `examples.dashboard.DashboardApp`          | `examples/target/bin/dashboard`     | Tabs, a gauge and a sparkline fed by a sampler (250 ms), a log pane fed by a stream (400 ms); the arrows, or Tab and Space or Enter, change the tab; `p` pauses the sampler and shows a toast; `?` opens the help (a box over the screen; Esc closes it); `q` quits. The log pane scrolls (Tab reaches it; the arrows, PageUp, PageDown, Home, End). In a terminal of 120 columns or more, the tabs and the log are side by side (`Tui.size`). The keys `p`, `?` and `q` are a keymap (`Keymap.of`; the help box lists them), and the status line says how long ago the last sample came (`useNow`, `timeAgo`). |
| Printing       | `examples.printing.PrintingApp`            | `examples/target/bin/printing [--log file] [--fail-on-f]` | A `println` while the UI is up does not reach the screen: the lines come after the terminal is restored, or go to the log file. This is also true after a `println` before the run (the app prints one line when it starts). `args` and `options` of a `TuiApp`. The test aid `--fail-on-f` makes `f` fail the UI: exit code 1, and the failure comes after the restore. |
| Forms          | `examples.forms.FormsApp`                  | `examples/target/bin/forms`         | The native inputs in one form: a text input with `maxLength`, a password, a number with `min`, `max` and `step`, a range, two checkboxes, two radios of one group, a select, a dropdown, a textarea of 3 rows, a read-only and a disabled field, Submit and Reset. Enter submits the form (in the textarea too: Alt-Enter or Ctrl-J gives a new line), and the result shows under the form; Esc quits (README "Forms and inputs"). |
| Gallery        | `examples.gallery.GalleryApp`              | `examples/target/bin/gallery`       | Every widget of tui4s-widgets with live state: the family list at the left (the arrows, or F1 to F5: containers, data, input, feedback, navigation), the widgets of the family at the right (Tab goes from widget to widget; the keys of each widget are in its label), the hints of the keymap of the app at the bottom (`KeyHints`); Ctrl-P opens the command palette over that keymap, Ctrl-T shows a toast, Ctrl-Q quits. The colours are `Colors.defaults` (README "Widgets (tui4s-widgets)"). |
| Assistant      | `examples.assistant.AssistantApp`          | `examples/target/bin/assistant [--fake]` | The flagship app: a chat with a language model (Anthropic, OpenAI, or a scripted fake provider). The list of the chats at the left, the transcript at the right (the reply streams in place and the view follows the end), the composer (Enter sends; Alt-Enter or Ctrl-J gives a new line), the status bar, the key hints, the command palette (Ctrl-P), the settings (Ctrl-S), the log pane (Ctrl-L), the help (F1); Esc stops a reply, Ctrl-Q quits. Its domain, services, store and UI are in the module `modules/assistant`; this main only wires (README "A real app: the assistant"). See "The keys of the assistant" below. |
| Assistant (web) | `examples.assistant.AssistantWebApp` (project `assistantWeb`, in `examples/web`) | `bash scripts/web-build.sh`, then a static server on `dist/assistant` | The same assistant in a browser (Scala.js): static files, no server of tui4s. With no key the provider is the fake one; a key that you type in the settings stays in the `localStorage` of the browser and goes only to its provider. The look is "native web" (Alt-, opens the settings, where Look switches it to the terminal look); the commands have Alt chords (Alt-P: the commands; on macOS the Ctrl chords too), Enter sends, Shift-Enter starts a new line. `node scripts/web-smoke.mjs` tests it in Chromium (README "The same app on the web"). |
| Widgets        | none (a library)                           | none                                | The terminal `Tabs` component adapter of `tui4s.widgets.Tabs` in `examples.widgets`. |
| Native recipes | none (a library)                           | none                                | Five things that need no composable, each in one or two lines of native Kyo: a toggle, a counter, an interval, a timer that pauses (`Signal.observe`), a derived value (README "You may not need a composable"). |
| Recipes        | none (a library)                           | none                                | One small UI for each other composable of tui4s-use: a search (`debounced`, `useAsync`), a progress (`throttled`), an autosave (`useDebouncedFn`), a streamed answer (`useStreamText`), a long list (`useSelection`, `useScrollWindow`), a delete that asks (`useDialog`), a setting that stays (`usePersisted`), the output of a program (`useProcess`). README "More composables, one recipe each". |

<!-- claude 2026-10-19: changed the Printing row: the test aid --fail-on-f -->
<!-- claude 2026-10-19: created the Forms row (L1c) -->
<!-- claude 2026-10-19: changed the rows of Todo and Dashboard: the composables of tui4s-use; created the rows of the Native recipes and of the Recipes (L2a, L2a review) -->
<!-- claude 2026-10-19: changed: the Widgets row and the Tabs examples of the section below; Tabs is in tui4s-widgets and takes a Model (L2b); created the Gallery row (L2b) -->
<!-- claude 2026-10-19: created the Assistant row and the section "The keys of the assistant" (L3); changed: no redirect, --fake keeps the settings, the fallbacks, the notes, the idle limit (L3 review) -->
<!-- claude 2026-10-19: created the row of the web assistant (L4) -->

## The keys of the assistant

The assistant uses a real provider only when it finds a key. It looks in
this order, for each provider:

1. The environment variable: `ANTHROPIC_API_KEY` for Anthropic,
   `OPENAI_API_KEY` for OpenAI.
2. The key file of the app: `keys` in `tui4s/assistant` of the config
   directory of the platform (`$XDG_CONFIG_HOME/tui4s/assistant/keys`, or
   `~/.config/tui4s/assistant/keys` on Linux; `~/Library/Application
   Support/tui4s/assistant/keys` on macOS). A line is `NAME=key`, with the
   name of the environment variable. The app reads the file only when it is
   a regular file for its owner only:

   ```sh
   mkdir -p ~/.config/tui4s/assistant
   printf 'ANTHROPIC_API_KEY=%s\n' "$KEY" > ~/.config/tui4s/assistant/keys
   chmod 600 ~/.config/tui4s/assistant/keys
   ```

   When others can read the file (for example mode 0644), the app does not
   read it: the log (Ctrl-L) says why and gives the `chmod` that fixes it.

The app reads no other file. It never reads the credentials of another
tool (for example `~/.claude/.credentials.json` or `~/.codex/auth.json`),
and it never uses the login of a web account. It never shows, logs or prints
a key: the settings (Ctrl-S) show only where each key comes from. The HTTP
client follows no redirect, so a key goes only to the host of the provider.

With no key, the app takes the fake provider. With `--fake`, it reads no key
and takes the fake provider (no network): send `/fail` to see an error, and
press Esc while it writes to stop it. The provider of the last run stays the
provider when it still has a key; a run with `--fake` does not change the
stored settings. The conversations, the settings and the log
of a run are files in the data directory of the app
(`~/.local/share/tui4s/assistant` on Linux); the directory and the files
of the conversations and of the settings are for their owner only.

A request to the default model of Anthropic asks for the server fallbacks of
the API (`fallbacks: "default"`): when the safety rules of that model decline
a request, Anthropic runs it again on the model that it recommends for that
kind of request. A reply that a model declines, and a reply that stops at the
token limit, show a note in the transcript. A stream that gives no data for
5 minutes is a network error.

## State and events with Kyo

Use the Kyo primitives directly. A `SignalRef[A]` owns a mutable value; its
`Signal[A]` interface supplies reactive reads. Derive values with `map` or
`combineLatest`, pass signals to kyo-ui, and update references from typed
callbacks. The examples below are independent objects that can be copied into
the examples project. `tui4s.widgets.Tabs` is in the published module
tui4s-widgets; `examples.widgets.Tabs` is its tui4s adapter in this project.

### Native state and input bindings

```scala
import kyo.*
import kyo.UI.*

object NativeStateExample:
    def ui: UI < Sync =
      for
          name     <- Signal.initRef("Ada")
          enabled  <- Signal.initRef(true)
          lastEdit <- Signal.initRef("")
          greeting  = name.map(value => s"Hello, $value")
      yield div(
        input.value(name).onInput(_ => name.get.map(lastEdit.set)),
        checkbox.checked(enabled),
        p(greeting),
        p(lastEdit.map(value => s"Last edit: $value")),
        enabled.render(value => span(if value then "Enabled" else "Disabled")),
      )
```

`.value(name)` and `.checked(enabled)` already provide two-way binding: typing
or toggling writes the reference, and external writes update the control.
The runner writes the reference **before** calling `onInput` or `onChange`.
Here `name.get` therefore reads the new text inside the callback. No callback
is needed just to copy the input value into the bound reference.

A derived `Signal` is read-only. For a custom controlled component, give it a
read signal and a change callback separately. A native input bound to a
`SignalRef` has already changed that ref when its callback runs, so that
callback is not a pre-update veto. The [Kyo UI binding contract](https://github.com/getkyo/kyo/blob/v1.0.0-RC7/kyo-ui/README.md#two-way-binding-via-signalref)
describes this behavior.

### State owned by a widget

The Dashboard passes labels and a panel function to `Tabs`. Tabs owns the
selected index, handles keys and button presses, and gives the selected index
to the panel. The Dashboard owns its metrics, pause flag, and logs.

```scala
import kyo.*
import kyo.UI.*
import examples.widgets.Tabs

object OwnedTabsExample:
    def ui: UI =
      new Tabs("CPU", "Memory", "Network")(
        heading = h1("Dashboard"),
        panel = index => p(s"Selected metric: $index"),
      ).ui
```

Each mounted Tabs has its own state and keyboard focus group. Selection
survives a parent render. Removing the component ends its mount; mounting it
again starts from `initialTab` (zero by default). Tab moves focus without
selecting. Left and Right select relative to the focused tab; Enter and Space
select the focused tab. In local mode, Tabs updates its selection before
calling `onChange(index)`.

The parent can own the selection instead:

```scala
import kyo.*
import kyo.UI.*
import examples.widgets.Tabs
import tui4s.widgets.Model

object ControlledTabsExample:
    def ui: UI < Sync =
      Signal.initRef(0).map { selected =>
        div(
          new Tabs("CPU", "Memory", "Network")(
            selected = Model.Controlled(selected),
            onChange = next =>
              if next == 2 then () else selected.set(next),
            panel = index => p(s"Selected metric: $index"),
          ).ui,
          button("Select Network externally").onClick(selected.set(2)),
        )
      }
```

With `Model.Controlled(signal)`, Tabs renders that signal and sends requests
to `onChange: Int => Any < Async`. It does not write a second selection value.
This parent rejects user requests for Network by leaving the reference alone;
the separate button shows that an external write still updates the tabs.
A controlled model without a callback makes selection read-only.
`Model.Local(index)` gives a locally owned selection its first tab.

Indices wrap into the label range, including the index passed to `onChange`
and the panel function. Labels must be nonempty. `heading: UI` and
`panel: Int => UI` are ordinary typed parameters: the panel is a scoped slot
without another slot abstraction.

`Model.Bound(selectedRef)` gives the widget the parent's ref: the widget
writes each request to it, then calls `onChange`, as a native `.value(ref)`
binding does. A change to the tab that shows already runs nothing.

### Construction and mount lifetime

`tui4s.widgets.Tabs` contains the widget behavior using public Kyo APIs only.
Its `ui` method returns an allocation effect, `UI < Sync`:

```scala
import kyo.*
import kyo.UI.*
import tui4s.widgets.Tabs

object PortableTabsExample:
    def ui: UI < Sync =
      Tabs("Overview", "Details")(
        panel = index => span(s"Panel $index"),
      ).ui
```

Each execution allocates fresh local state and a fresh focus group. Keep the
returned UI for ordinary signal updates. Kyo follows its signals; it does not
re-execute this construction effect on each update. Allocating again creates
another widget. This behavior is exercised through public Kyo UI trees and
event callbacks without a terminal. This project's widget tests do not claim
a browser integration test.

`examples.widgets.Tabs` wraps `tui4s.widgets.Tabs` in `tui4s.Component`. Its
`ui` returns a plain UI node;
the terminal runner allocates state at mount, keeps it across parent renders,
and drops it at unmount. Its `onMount` runs `Tabs.mount`, an effect that
gives the mounted state a fresh focus group (Kyo `Random`, no global counter). This retained identity is a tui4s lifecycle feature.
Use the portable construction effect with another runner; do not assume that
runner implements tui4s mounting. Kyo `Scope` already provides resource cleanup;
mount effects belong in `Component.onMount` or `Hooks.component`, where tui4s
connects the scope to the terminal mount.

### Typed component events

A change callback is often enough. For reusable logic that emits a typed event,
Kyo already supplies `Emit`: define an enum, emit values, and handle them with
`Emit.runForeach`. Handle the event effect **inside the callback execution**:

```scala
import kyo.*
import kyo.UI.*
import examples.widgets.Tabs

object EmittedTabsExample:
    enum Event:
        case Selected(index: Int)

    def selectionChanged(index: Int): Unit < Emit[Event] =
      Emit.value[Event](Event.Selected(index))

    def ui: UI < Sync =
      Signal.initRef(Vector.empty[Int]).map { changes =>
        def record(event: Event): Unit < Sync = event match
            case Event.Selected(index) => changes.updateAndGet(_ :+ index).unit

        div(
          new Tabs("Overview", "Details")(
            onChange = index =>
              Emit.runForeach[Event](selectionChanged(index))(record),
          ).ui,
          p(changes.map(values => s"Selections: ${values.mkString(", ")}")),
        )
      }
```

`selectionChanged` retains `Emit[Event]` in its type. The callback handles it
before returning to the UI, whose callback accepts `Unit < Async`. Wrapping
only UI construction in an `Emit` or `Env` handler does not handle a callback
that executes later. Resolve dependencies during construction and capture
ordinary references, or handle the effects inside the callback. Handle typed
`Abort` failures there too, for example by updating a visible error signal.

Callbacks are awaited. Put ordinary side effects inside `Sync.defer`, and
return the Kyo effect you intend to execute. Long-running background work needs
an explicit application or component scope. Native `onClick` already defers
its by-name action; no additional event wrapper is needed.

### Optional shared stores

An ordinary class can group signals, derived values, and actions. Kyo `Env`
provides a dependency; `Layer` can construct a shared dependency graph. Neither
requires a new store abstraction:

```scala
import kyo.*
import kyo.UI.*
import examples.widgets.Tabs
import tui4s.widgets.Model

object OrdinaryStoreExample:
    final class DashboardStore(val selected: SignalRef[Int]):
        val summary: Signal[String] = selected.map(index => s"Metric $index")
        def select(index: Int): Unit < Sync = selected.set(index)

    def store: DashboardStore < Sync =
      Signal.initRef(0).map(ref => new DashboardStore(ref))

    def view: UI < Env[DashboardStore] =
      Env.use[DashboardStore] { dashboard =>
        div(
          new Tabs("CPU", "Memory", "Network")(
            selected = Model.Controlled(dashboard.selected),
            onChange = index => dashboard.select(index),
          ).ui,
          p(dashboard.summary),
        )
      }

    def ui: UI < Sync = store.map(dashboard => Env.run(dashboard)(view))

    val layer = Layer(store)
    def uiWithLayer: UI < Sync = Memo.run(Env.runLayer(layer)(view))
```

The callback captures the resolved `dashboard` object, so it can use that
object after the construction-time `Env.run` returns. `Layer` uses `Memo` to
share repeated references to a layer within a dependency graph; the example
provides a fresh `Memo.run` for each app construction.

Use [pini4s](../vendor/pini4s/README.md) when components explicitly need the
Nuxt-style `useState("key")(initial)` lookup. It returns the same Kyo
`SignalRef` for a key in one application-owned `StateStore`:

```scala
import kyo.*
import kyo.UI.*
import pini4s.*
import examples.widgets.Tabs
import tui4s.widgets.Model

object KeyedStoreExample:
    def tabs(using StateStore): UI < Sync =
      useState("dashboard.currentTab")(0).map { selected =>
        new Tabs("CPU", "Memory", "Network")(
          selected = Model.Bound(selected),
        ).ui
      }

    def ui: UI < Sync =
      StateStore.init.map { store =>
        given StateStore = store
        for
            first  <- tabs
            second <- tabs
        yield div(first, second)
      }
```

These two tab components share selection because they use the same key and
store. The reference survives component unmounting while the store remains
reachable. A fresh store isolates another app or test. Initialization is lazy,
concurrent first lookups share one successful initialization, and reusing a key
with another type fails. Use component-owned state for a local value; use
ordinary props and callbacks for parent-controlled state. pini4s is optional
for both.

In application code, Kyo derives `Frame` automatically. Reusable library
functions can forward it to retain caller locations. Tabs captures its
construction location once, so later view construction needs no extra Frame
argument.

## Run an example

Clone with `--recurse-submodules`, or run
`git submodule update --init --recursive` in an existing checkout, to fetch
`vendor/pini4s`. The shared-store examples use its local sbt project; no
published artifact is needed. `tui4s.widgets.Tabs` uses Kyo only; its
terminal adapter `examples.widgets.Tabs` uses tui4s.

From the repository root, enter the Nix development shell and stage the apps:

```sh
nix develop
sbt examples/Universal/stage
examples/target/universal/stage/bin/counter
```

Run the last command in a real terminal. The development shell provides JDK 25,
sbt, `expect`, Python, and GNU coreutils for the terminal checklist. It defines
shells for Linux on x86_64 or aarch64 and Apple Silicon macOS; terminal runtime
support still needs validation on each platform. On Intel macOS, or without
Nix, install JDK 25 and sbt yourself; install the checklist tools when running
the PTY suite. `nix develop --command sbt examples/Universal/stage` runs a build
without opening an interactive development shell.

The examples use sbt-native-packager's `JavaAppPackaging`.
`examples/Universal/stage` puts one launcher per main class in
`examples/target/universal/stage/bin`, with the runtime JARs in `lib` and JVM
configuration in `conf/application.ini`. The script names are the package names
in the table above: `counter`, `counterkyo`, `twocounters`, `counterstyles`,
`todo`, `dashboard`, `printing`, `forms`, and `gallery`. Two mains in one
package stop the build.
<!-- claude 2026-10-19: changed: forms (L1c), gallery (L2b) -->

To produce a ZIP that can be unpacked elsewhere:

```sh
sbt examples/Universal/packageBin
```

The archive is under `examples/target/universal/`. Keep its `bin`, `lib`, and
`conf` directories together. It contains scripts and JARs; the receiving
machine must provide JDK 25. No JDK is bundled, and this task does not produce
a GraalVM native binary.

The existing start-script interface remains available:

```sh
sbt examples/launchers
examples/target/bin/counter
```

`examples/launchers` stages the same distribution and writes forwarding scripts
in `examples/target/bin`, plus the `classpath` file used by the PTY kit. Those
scripts preserve the existing program-argument behavior. Native-packager
launchers also accept JVM options such as `-J-Xmx1g`; put `--` before program
arguments that could be mistaken for launcher options:

```sh
examples/target/universal/stage/bin/printing -- --log printing.log
examples/target/bin/printing --log printing.log
```

Both launch paths start an app in its own JVM, using `$JAVA_HOME/bin/java` or
`java` on `PATH`. The shared JVM options in `build.sbt` include:

- `--enable-native-access=ALL-UNNAMED`: crossterm is a native library.
  Without it, JDK 25 prints a warning into the terminal before the UI starts.
- `--sun-misc-unsafe-memory-access=allow`: Kyo and Scala use
  `sun.misc.Unsafe`.
- `-Xss16m`: Kyo's deep stacks.
- `-Dkyo.scheduler.cycleIntervalNs=10000000`,
  `-Dkyo.scheduler.blockingMonitorIntervalNs=20000000`,
  `-Dkyo.scheduler.blockingMonitorMinIntervalNs=20000000` and
  `-Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100`: the tui4s
  scheduler values. With them the runner gives fewer split frames under load
  and uses less CPU when the app is idle, and the app starts faster (Kyo does
  not measure its CPU-time counter for about 90 ms).
  A `TuiApp` also sets them in code
  (`tui4s.spi.SchedulerDefaults`, before the app's own code), each one that
  no flag and no environment variable sets;
  `-Dtui4s.schedulerDefaults=false` turns this off. An app with a main of its
  own (`counterkyo`) needs them as JVM flags.
  <!-- claude 2026-10-19: changed (C2): blockingMonitorMinIntervalNs -->

Put more JVM options in `JAVA_OPTS`, for example
`JAVA_OPTS=-Dkyo.Log.defaultLevel=info examples/target/bin/counter`. Give the
program arguments after the script: `examples/target/bin/todo my-list.txt`.

The exit code of a `TuiApp`: 0 after a quit (also the key Ctrl-C: in raw mode
Ctrl-C is a key, not a signal); 1 after a failure, with the error printed
after the terminal is restored ("stdin/stdout is not a terminal" when stdin is
a pipe); 143 for `kill -TERM` and 130 for `kill -INT` from outside: the JVM
stops the app, a shutdown hook interrupts the run and waits at most 2 s until
the terminal is restored, and nothing more is printed.

When the environment sets `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS` (a proxy
setup can do this), the JVM prints a line "Picked up ..." into the terminal
before the UI starts. It does no harm; unset the variable to remove it.

Do not use `sbt run` or `sbt runMain` to use an app. sbt also reads the
keyboard, so some keys never get to the app, and the forked JVM gets no
terminal on stdin, so the app stops with "stdin/stdout is not a terminal".
This is true for every terminal UI that sbt starts, not only for tui4s.

## Test the examples

```sh
sbt examples/test
```

The tests do not need a terminal. They run the apps on
`tui4s.testkit.Headless` with munit, one suite per example
(`examples/src/test/scala/examples/<package>/`): the keys of each app, the
screen after them, the Todo file, the Dashboard with short periods. A test
that writes a signal of the app waits for the screen with
`Headless.awaitScreen`. `MarkdownRegionsSuite` checks that the code blocks of
the root `README.md` and of the pages of the docs site (`docs/content`) that
have a `<!-- from: <path>#<region> -->` line above them are the same text as
the source files (a region is the lines between `// region: <name>` and
`// endregion: <name>`). In the docs pages, each Scala block must have that
line. The Scala blocks of this file are not checked.
<!-- claude 2026-10-20: changed: MarkdownRegionsSuite, the docs pages; this file is not checked (L5) -->

## Real-terminal checklist

The unit tests cannot open a real terminal. The PTY kit does it without a
human:

```sh
examples/ptycheck/run.sh
```

The script writes the start scripts (one sbt process), starts the examples in
a pseudo-terminal of 100x30 (with `expect`, `checklist.exp`), sends the keys
and the signals of the checklist, checks raw mode, the exit codes and
`stty -a` from outside, then replays the output in a small terminal emulator
(`vt.py`) and checks the screens (`check.py`). It prints one line per row
(`PASS`, `FAIL`, or `INFO` for a number) and exits with 1 when a row fails.
It needs `expect` and `python3`. The CI job `terminal` runs it. Run it after
each change to the terminal code. `PTY_SKIP_BUILD=1` uses the scripts as they
are; `PTY_WORK=<dir>` keeps the logs there (the raw output `session.log`, the
replayed screens `screens.txt`, the `stty -a` files, the Todo file, the log
file of `printing --log`, `classload.log`). The kit starts the apps in its
work directory.

The rows (the first column is the start of the row names in the output; every
run that starts an app also checks that `stty -a` after the run is the same as
before):

| Rows | Check |
| --- | --- |
| `counter` | The app enters the alternate screen and draws the first frame; raw mode (`-icanon -echo -isig`) while it runs; `+`, `+`, `-` show `Count: 1`, `Count: 2`, `Count: 1`, and each key writes less than 200 bytes (a partial repaint); `q` gives exit code 0; the app leaves the alternate screen, shows the cursor, and nothing comes before the prompt. |
| `counter ctrl-c`, `ctrlc` | Ctrl-C (a key in raw mode) gives exit code 0, with the same restore. |
| `counter resize`, `resize` | At 40x20 the screen is laid out again inside 40 columns (the help line wraps); at 100x30 again, the help line is one line. |
| `counter kill -TERM`, `term` | `kill -TERM` to the app after its first frame: exit code 143, the alternate screen left, the cursor shown, nothing stray. |
| `counter kill -INT`, `int` | `kill -INT`: exit code 130, the same restore. |
| `counter kill -HUP`, `hup` | `kill -HUP` (the terminal window closes): exit code 129, the same restore. |
| `printing`, `print` | The `println` before the run comes before the alternate screen. A `println` when the UI is built and at `+` never reaches the screen; after the restore come the two printed lines, then `EXIT=0`, and no warning that `scala.Console` was in use before the run (the constructor of `TuiApp` installed the capture first). |
| `printing --log`, `printlog` | With `--log printing.log`: the `println` before the run comes before the alternate screen; after it, no printed line and no warning comes in the terminal, and the log file has exactly the two lines of the run. |
| `printing --fail-on-f`, `printfail` | With the test aid `--fail-on-f`, the key `f` fails the UI (a render fails): exit code 1; no printed line and no failure on the screen while the UI is up; after the restore come the printed line of the run, then the failure (`java.lang.IllegalStateException: printing: f failed the UI (--fail-on-f)` and its stack trace), then `EXIT=1`. |
| `counterkyo`, `kyoapp` | The KyoApp main: `+` shows `Count: 1`; `q` gives exit code 0 and the restore. |
| `counterstyles`, `styles` | Enter on the `+` of a hook counter, then on the `+` of a `Component` counter: the counts are 1, 0, 1, 0; `h` hides the hook counters (1, 0); `h` shows them again at 0 (0, 0, 1, 0); `q`. |
| `todo` | `todo todo-pty.txt`: raw mode; typed text shows in the input; Enter adds `[ ] buy milk` and empties the input; a bracketed paste of one line shows in the input and adds nothing; a paste with a line break gives a space (`tea time and cake`) and adds nothing; Enter adds it; Tab moves the focus to the list; Space toggles `[x] buy milk`; Ctrl-S shows `saved 2 items to todo-pty.txt`; Esc gives exit code 0 and the restore; the file is `[x] buy milk` and `[ ] tea time and cake`. |
| `dashboard`, `dash` | Live: the gauge or the sparkline differ in two screens 1 s apart; `p` pauses: they are the same in two screens 1 s apart, and the log goes on; `p` resumes; ArrowRight shows `Memory usage` and the bold tab moves; ArrowLeft back to `CPU usage`; Tab, then Space selects the focused tab; Tab, then Enter too; at 40x20 the boxes are 40 wide, at 100x30 100 wide; `q`. The Dashboard has no use for Esc. |
| `forms` | The first screen of the form: the title, the name field, the number `30`, the checked `news` box, the `Free` radio and the Submit button; the terminal cursor in the empty name field, and after 10 Tabs in the empty textarea; `a`, Alt-Enter, `b`, Ctrl-J, `c` give three lines and the cursor follows the caret; Enter submits the form (the result has 3 message lines); Esc gives exit code 0 and the restore. |
| `gallery` | The first screen: the title, the family list with `Containers` selected, and the panel of the containers; ArrowDown on the family list shows the data family (`› Data`, the list and the gauge); Ctrl-Q gives exit code 0 and the restore. |
| `class-load` (INFO) | `JAVA_OPTS=-Xlog:class+load:file=<work>/classload.log examples/target/bin/counter`, first frame, `q`: the number of classes that the JVM loads (all, JDK, Scala, Kyo with kyo-ui, kyo-http and kyo-net, jatatui, tui4s core, examples). No PASS or FAIL. |
| `stdin is a pipe`, `pipe` | `echo \| examples/target/bin/counter`: "stdin/stdout is not a terminal", exit code 1, no alternate screen. |
| `native access denied`, `deny` | `java --illegal-native-access=deny ... examples.counter.CounterApp`: "start the JVM with --enable-native-access=ALL-UNNAMED", exit code 1, no alternate screen. |

<!-- claude 2026-10-13: changed the rows Resize and Native access (T1, review R3-9): the stty -a comparison -->
<!-- claude 2026-10-13: changed the rows (T2, RC-31 rest, OS-19): the names of the output, the rows of all the examples -->
<!-- claude 2026-10-13: changed the rows Ctrl-C and Printing (T2, reviews R1-8, R1-1): the name ctrlc; the println before the run -->
<!-- claude 2026-10-19: created the rows hup and printfail -->
<!-- claude 2026-10-19: created the row forms (L1c); changed: the cursor, the newline keys, the submit (L1c review) -->
<!-- claude 2026-10-19: created the row gallery (L2b) -->

A human does the steps that a pseudo-terminal cannot show (colours, real
window resizes, look and feel) on the terminals of the platform, and writes
the result in the pull request:

- [ ] Start the Counter. Push `+` and `-`. The count changes at once. Tab
      moves between the buttons; Enter presses the button with the focus.
- [ ] Push `q`. The app stops. The shell prompt is correct, and there is no
      stray output.
- [ ] Start the Counter again. Push Ctrl-C. The app stops, and the prompt is
      correct.
- [ ] Start the Counter again. From another terminal, send `kill -TERM` to
      its process. The terminal is restored, and `echo $?` gives 143.
- [ ] Make the window smaller and larger. The screen has no artefacts.
- [ ] Start the Todo app (`examples/target/bin/todo /tmp/todo.txt`). Type a
      text and push Enter: the item is in the list. Paste a text from the
      clipboard: it comes into the input at once, as one edit. Push Tab: the
      border of the list changes colour. Push Space: the item is done (struck
      through, grey). Push Ctrl-S: the status line says "saved". Push Esc.
      `cat /tmp/todo.txt` shows the list.
- [ ] Start the Dashboard. The gauge (green) and the sparkline (blue) move
      four times a second, the log pane gets a line about every 400 ms. Push
      ArrowRight and ArrowLeft: the bold yellow tab moves. Push `p`: the
      gauge and the sparkline stop, the log goes on. Push `p` again, then `q`.
- [ ] Start the Counter without `--enable-native-access`. JDK 25 prints its
      warning before the UI starts, and the app starts:

      ```sh
      java --sun-misc-unsafe-memory-access=allow \
        -cp "$(cat examples/target/bin/classpath)" examples.counter.CounterApp
      ```

## Known limits

- jatatui 0.31.0 cuts a typed character outside the Basic Multilingual Plane
  (an emoji) to 16 bits, so such a key arrives as a wrong character. A paste
  (bracketed paste) keeps it.
- A `span` whose text is wider than its box is cut at the edge; it does not
  wrap (a `p` wraps). At 40 columns the Dashboard's header and help line are
  cut. The layout of the core has this gap; a later pull request can make a
  span wrap.
- With a `KyoApp` main (as `CounterKyoApp`), Kyo's own signal handling stops
  the app on `kill -TERM` and `kill -INT`: the run is interrupted, the run
  restores the terminal, and the exit code is 143 or 130 (seen in a
  pseudo-terminal; the kit has the rows for the `TuiApp` Counter).
