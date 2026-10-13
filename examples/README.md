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
| Printing       | `examples.printing.PrintingApp`            | `examples/target/bin/printing [--log file]` | A `println` while the UI is up does not reach the screen: the lines come after the terminal is restored, or go to the log file. `args` and `options` of a `TuiApp`. |
| Widgets        | none (a library)                           | none                                | The gauge, the sparkline and the tabs of the Dashboard, in plain kyo-ui (`examples.widgets.Widgets`). |

## Run an example

Write the start scripts, then start an app in a real terminal:

```sh
sbt examples/launchers
examples/target/bin/counter
```

`sbt examples/launchers` writes one script for each main class of the
examples into `examples/target/bin`, named after the package of the main
(`examples.counter.CounterApp` gets `counter`), and the class path into
`examples/target/bin/classpath`. Two mains in one package stop the task. Each
script starts the app in its own JVM with `$JAVA_HOME/bin/java` (or `java` on
`PATH`), which must be JDK 25, and with these flags (`examples / run /
javaOptions` in `build.sbt`):

- `--enable-native-access=ALL-UNNAMED`: crossterm is a native library.
  Without it, JDK 25 prints a warning into the terminal before the UI starts.
- `--sun-misc-unsafe-memory-access=allow`: Kyo and Scala use
  `sun.misc.Unsafe`.
- `-Xss16m`: Kyo's deep stacks.
- `-Dkyo.scheduler.cycleIntervalNs=10000000`,
  `-Dkyo.scheduler.blockingMonitorIntervalNs=20000000` and
  `-Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100`: the tui4s
  scheduler values. With them the runner gives fewer split frames under load
  and uses less CPU when the app is idle. A `TuiApp` also sets them in code
  (`tui4s.spi.SchedulerDefaults`, before the app's own code), each one that
  no flag and no environment variable sets;
  `-Dtui4s.schedulerDefaults=false` turns this off. An app with a main of its
  own (`counterkyo`) needs them as JVM flags.

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
| `counter ctrl-c` | Ctrl-C (a key in raw mode) gives exit code 0, with the same restore. |
| `counter resize`, `resize` | At 40x20 the screen is laid out again inside 40 columns (the help line wraps); at 100x30 again, the help line is one line. |
| `counter kill -TERM`, `term` | `kill -TERM` to the app after its first frame: exit code 143, the alternate screen left, the cursor shown, nothing stray. |
| `counter kill -INT`, `int` | `kill -INT`: exit code 130, the same restore. |
| `printing`, `print` | A `println` when the UI is built and at `+` never reaches the screen; after the restore come the two printed lines, then `EXIT=0`. |
| `printing --log`, `printlog` | With `--log printing.log`, no printed line comes in the terminal, and the log file has exactly the two lines. |
| `counterkyo`, `kyoapp` | The KyoApp main: `+` shows `Count: 1`; `q` gives exit code 0 and the restore. |
| `counterstyles`, `styles` | Enter on the `+` of a hook counter, then on the `+` of a `Component` counter: the counts are 1, 0, 1, 0; `h` hides the hook counters (1, 0); `h` shows them again at 0 (0, 0, 1, 0); `q`. |
| `todo` | `todo todo-pty.txt`: raw mode; typed text shows in the input; Enter adds `[ ] buy milk` and empties the input; a bracketed paste of one line shows in the input and adds nothing; a paste with a line break gives a space (`tea time and cake`) and adds nothing; Enter adds it; Tab moves the focus to the list; Space toggles `[x] buy milk`; Ctrl-S shows `saved 2 items to todo-pty.txt`; Esc gives exit code 0 and the restore; the file is `[x] buy milk` and `[ ] tea time and cake`. |
| `dashboard`, `dash` | Live: the gauge or the sparkline differ in two screens 1 s apart; `p` pauses: they are the same in two screens 1 s apart, and the log goes on; `p` resumes; ArrowRight shows `Memory usage` and the bold tab moves; ArrowLeft back to `CPU usage`; Tab, then Space selects the focused tab; Tab, then Enter too; at 40x20 the boxes are 40 wide, at 100x30 100 wide; `q`. The Dashboard has no use for Esc. |
| `class-load` (INFO) | `JAVA_OPTS=-Xlog:class+load:file=<work>/classload.log examples/target/bin/counter`, first frame, `q`: the number of classes that the JVM loads (all, JDK, Scala, Kyo with kyo-ui, kyo-http and kyo-net, jatatui, tui4s core, examples). No PASS or FAIL. |
| `stdin is a pipe`, `pipe` | `echo \| examples/target/bin/counter`: "stdin/stdout is not a terminal", exit code 1, no alternate screen. |
| `native access denied`, `deny` | `java --illegal-native-access=deny ... examples.counter.CounterApp`: "start the JVM with --enable-native-access=ALL-UNNAMED", exit code 1, no alternate screen. |

<!-- claude 2026-10-13: changed the rows Resize and Native access (T1, review R3-9): the stty -a comparison -->
<!-- claude 2026-10-13: changed the rows (T2, RC-31 rest, OS-19): the names of the output, the rows of all the examples -->

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
