# tui4s examples

<!-- claude 2026-10-13: changed this file (T1): the PR 5 text for the kyo-ui Counter, the launchers and the PTY kit -->
<!-- claude 2026-10-13: changed this file (T2, OS-50): every example, TuiApp, the rows of all the PTY cases, the human checklist of Todo and Dashboard -->

This project has the sample apps of tui4s. They are not published. Each app
is a main class in its own package, and its start script has the name of the
package.

| App            | Main class                                 | Script                              | What it shows |
| -------------- | ------------------------------------------ | ----------------------------------- | ------------- |
| Counter        | `examples.counter.CounterApp`              | `examples/target/bin/counter`       | The smallest app: a kyo-ui UI with one hook (`useSignal`), key handlers, buttons; a `TuiApp` main (README "Quick start"). |
| Counter (KyoApp) | `examples.counterkyo.CounterKyoApp`      | `examples/target/bin/counterkyo`    | The same UI with a main of its own: a `KyoApp` that calls `Tui.run` and gives the UI the quit value. |
| Two counters   | `examples.twocounters.TwoCountersApp`      | `examples/target/bin/twocounters`   | A counter is a function that allocates its own state: two counters count on their own. |
| Counter styles | `examples.counterstyles.CounterStylesApp`  | `examples/target/bin/counterstyles` | Both component styles side by side: hook counters that the runner mounts (`component`, inside a `when`: `h` hides them and they lose their state) and `Component` counters. |
| Todo           | `examples.todo.TodoApp`                    | `examples/target/bin/todo [file]`   | A list in a file (the argument, or `todos.txt` in the working directory): a text input, Enter adds, Tab to the list, Space toggles, `d` deletes, Ctrl-S saves, a paste is one edit, Esc quits. |
| Dashboard      | `examples.dashboard.DashboardApp`          | `examples/target/bin/dashboard`     | Tabs, a gauge and a sparkline fed by a sampler (250 ms), a log pane fed by a stream (400 ms); the arrows, or Tab and Space or Enter, change the tab; `p` pauses the sampler; `q` quits. |
| Printing       | `examples.printing.PrintingApp`            | `examples/target/bin/printing [--log file]` | A `println` while the UI is up does not reach the screen: the lines come after the terminal is restored, or go to the log file. This is also true after a `println` before the run (the app prints one line when it starts). `args` and `options` of a `TuiApp`. |
| Widgets        | none (a library)                           | none                                | Reusable gauge and sparkline helpers, Kyo-only `TabsView`, and the terminal `Tabs` component adapter in `examples.widgets`. |

<!-- claude 2026-10-13: changed the Printing row (T2, review R1-1): the println before the run -->

## State and events with Kyo

Use the Kyo primitives directly. A `SignalRef[A]` owns a mutable value; its
`Signal[A]` interface supplies reactive reads. Derive values with `map` or
`combineLatest`, pass signals to kyo-ui, and update references from typed
callbacks. The examples below are independent objects that can be copied into
the examples project. `Tabs`, `TabsView`, and `Widgets` are reusable example
code, not published tui4s-core APIs.

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

object ControlledTabsExample:
    def ui: UI < Sync =
      Signal.initRef(0).map { selected =>
        div(
          new Tabs("CPU", "Memory", "Network")(
            selected = Some(selected),
            onChange = next =>
              if next == 2 then () else selected.set(next),
            panel = index => p(s"Selected metric: $index"),
          ).ui,
          button("Select Network externally").onClick(selected.set(2)),
        )
      }
```

When `selected` is supplied, Tabs renders that signal and sends requests to
`onChange: Int => Unit < Async`. It does not write a second selection value.
This parent rejects user requests for Network by leaving the reference alone;
the separate button shows that an external write still updates the tabs.
Providing `selected` without a callback makes selection read-only. `initialTab`
applies only to locally owned selection.

Indices wrap into the label range, including the index passed to `onChange`
and the panel function. Labels must be nonempty. `heading: UI` and
`panel: Int => UI` are ordinary typed parameters: the panel is a scoped slot
without another slot abstraction.

`Widgets.tabs("One", "Two")` is the short form with local state.
`Widgets.tabs(selectedRef, "One", "Two")` supplies the ref as a signal and
writes accepted requests straight back to it. Use `Tabs` directly for a panel, heading,
custom change handler, or initial selection.

### Construction and mount lifetime

`TabsView` contains the widget behavior using public Kyo APIs only. Its `ui`
method returns an allocation effect, `UI < Sync`:

```scala
import kyo.*
import kyo.UI.*
import examples.widgets.TabsView

object PortableTabsExample:
    def ui: UI < Sync =
      new TabsView("Overview", "Details")(
        panel = index => span(s"Panel $index"),
      ).ui
```

Each execution allocates fresh local state and a fresh focus group. Keep the
returned UI for ordinary signal updates. Kyo follows its signals; it does not
re-execute this construction effect on each update. Allocating again creates
another widget. This behavior is exercised through public Kyo UI trees and
event callbacks without a terminal. This project's widget tests do not claim
a browser integration test.

`Tabs` wraps `TabsView` in `tui4s.Component`. Its `ui` returns a plain UI node;
the terminal runner allocates state at mount, keeps it across parent renders,
and drops it at unmount. This retained identity is a tui4s lifecycle feature.
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
            selected = Some(dashboard.selected),
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

object KeyedStoreExample:
    def tabs(using StateStore): UI < Sync =
      useState("dashboard.currentTab")(0).map { selected =>
        new Tabs("CPU", "Memory", "Network")(
          selected = Some(selected),
          onChange = index => selected.set(index),
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
published artifact is needed. `TabsView` uses Kyo only; its terminal `Tabs`
adapter uses tui4s.

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
`todo`, `dashboard`, and `printing`. Two mains in one package stop the build.

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
`Headless.awaitScreen`. `ReadmeSuite` checks that the code blocks of the root
`README.md` that have a `<!-- from: <path>#<region> -->` line above them are
the same text as the example files (a region is the lines between
`// region: <name>` and `// endregion: <name>`).

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
| `printing`, `print` | The `println` before the run comes before the alternate screen. A `println` when the UI is built and at `+` never reaches the screen; after the restore come the two printed lines, then `EXIT=0`, and no warning that `scala.Console` was in use before the run (the constructor of `TuiApp` installed the capture first). |
| `printing --log`, `printlog` | With `--log printing.log`: the `println` before the run comes before the alternate screen; after it, no printed line and no warning comes in the terminal, and the log file has exactly the two lines of the run. |
| `counterkyo`, `kyoapp` | The KyoApp main: `+` shows `Count: 1`; `q` gives exit code 0 and the restore. |
| `counterstyles`, `styles` | Enter on the `+` of a hook counter, then on the `+` of a `Component` counter: the counts are 1, 0, 1, 0; `h` hides the hook counters (1, 0); `h` shows them again at 0 (0, 0, 1, 0); `q`. |
| `todo` | `todo todo-pty.txt`: raw mode; typed text shows in the input; Enter adds `[ ] buy milk` and empties the input; a bracketed paste of one line shows in the input and adds nothing; a paste with a line break gives a space (`tea time and cake`) and adds nothing; Enter adds it; Tab moves the focus to the list; Space toggles `[x] buy milk`; Ctrl-S shows `saved 2 items to todo-pty.txt`; Esc gives exit code 0 and the restore; the file is `[x] buy milk` and `[ ] tea time and cake`. |
| `dashboard`, `dash` | Live: the gauge or the sparkline differ in two screens 1 s apart; `p` pauses: they are the same in two screens 1 s apart, and the log goes on; `p` resumes; ArrowRight shows `Memory usage` and the bold tab moves; ArrowLeft back to `CPU usage`; Tab, then Space selects the focused tab; Tab, then Enter too; at 40x20 the boxes are 40 wide, at 100x30 100 wide; `q`. The Dashboard has no use for Esc. |
| `class-load` (INFO) | `JAVA_OPTS=-Xlog:class+load:file=<work>/classload.log examples/target/bin/counter`, first frame, `q`: the number of classes that the JVM loads (all, JDK, Scala, Kyo with kyo-ui, kyo-http and kyo-net, jatatui, tui4s core, examples). No PASS or FAIL. |
| `stdin is a pipe`, `pipe` | `echo \| examples/target/bin/counter`: "stdin/stdout is not a terminal", exit code 1, no alternate screen. |
| `native access denied`, `deny` | `java --illegal-native-access=deny ... examples.counter.CounterApp`: "start the JVM with --enable-native-access=ALL-UNNAMED", exit code 1, no alternate screen. |

<!-- claude 2026-10-13: changed the rows Resize and Native access (T1, review R3-9): the stty -a comparison -->
<!-- claude 2026-10-13: changed the rows (T2, RC-31 rest, OS-19): the names of the output, the rows of all the examples -->
<!-- claude 2026-10-13: changed the rows Ctrl-C and Printing (T2, reviews R1-8, R1-1): the name ctrlc; the println before the run -->

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
