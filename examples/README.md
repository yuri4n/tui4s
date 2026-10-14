# tui4s examples

<!-- claude 2026-10-13: changed this file (T1): the PR 5 text for the kyo-ui Counter, the launchers and the PTY kit -->

This project has the sample apps of tui4s. They are not published.

| App     | Main class                       | Script                        | What it shows                                                        |
| ------- | -------------------------------- | ----------------------------- | -------------------------------------------------------------------- |
| Counter | `examples.counter.CounterKyoApp` | `examples/target/bin/counter` | A kyo-ui UI with a `SignalRef`, key handlers, buttons, the quit value. |

More examples (Todo, Dashboard, Widgets, two counters, the component styles)
come in a later version.

## Run an example

Write the start scripts, then start an app in a real terminal:

```sh
sbt examples/launchers
examples/target/bin/counter
```

`sbt examples/launchers` writes one script for each main class of the
examples into `examples/target/bin`, named after the package of the main
(`examples.counter.CounterKyoApp` gets `counter`), and the class path into
`examples/target/bin/classpath`. Each script starts the app in its own JVM
with `$JAVA_HOME/bin/java` (or `java` on `PATH`), which must be JDK 25, and
with these flags (`examples / run / javaOptions` in `build.sbt`):

- `--enable-native-access=ALL-UNNAMED`: crossterm is a native library.
  Without it, JDK 25 prints a warning into the terminal before the UI starts.
- `--sun-misc-unsafe-memory-access=allow`: Kyo and Scala use
  `sun.misc.Unsafe`.
- `-Xss16m`: Kyo's deep stacks.
- `-Dkyo.scheduler.cycleIntervalNs=10000000`,
  `-Dkyo.scheduler.blockingMonitorIntervalNs=20000000` and
  `-Dkyo.scheduler.regulator.concurrencyCollectIntervalMs=100`: the tui4s
  scheduler values. With them the runner gives fewer split frames under load
  and uses less CPU when the app is idle. An app with its own `main` gives
  them as JVM flags; `TuiApp`, in a later version, sets them in code.

Put more JVM options in `JAVA_OPTS`, for example
`JAVA_OPTS=-Dkyo.Log.defaultLevel=info examples/target/bin/counter`.

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

The tests do not need a terminal. They run the app on
`tui4s.testkit.Headless` with munit
(`examples/src/test/scala/examples/counter/CounterSuite.scala`).

## Real-terminal checklist

The unit tests cannot open a real terminal. The PTY kit does it without a
human:

```sh
examples/ptycheck/run.sh
```

The script writes the start scripts (one sbt process), starts each example in
a pseudo-terminal of 100x30 (with `expect`, `checklist.exp`), sends the keys of
the checklist, checks raw mode and `stty -a` from outside, then replays the
output in a small terminal emulator (`vt.py`) and checks the screens
(`check.py`). It prints one line per row and exits with 1 when a row fails.
It needs `expect` and `python3`. The CI job `terminal` runs it. Run it after
each change to the terminal code. `PTY_SKIP_BUILD=1` uses the scripts as they
are; `PTY_WORK=<dir>` keeps the logs there (the raw output `session.log` and
the replayed screens `screens.txt`).

The rows for the Counter:

| Row | Check |
| --- | --- |
| Start | The app enters the alternate screen and draws the first frame. |
| Raw mode | `stty -a` of the pseudo-terminal shows `-icanon -echo -isig` while the app runs. |
| Keys | `+`, `+`, `-` show `Count: 1`, `Count: 2`, `Count: 1`; each key writes less than 200 bytes (a partial repaint). |
| `q` | The app stops with exit code 0. |
| Ctrl-C | The app stops with exit code 0 (in raw mode Ctrl-C is a key, not a signal). |
| Restore | After `q` and after Ctrl-C the app leaves the alternate screen and shows the cursor, and nothing comes before the prompt. `stty -a` after the run is the same as before. |
| Resize | At 40x20 the screen is laid out again inside 40 columns (the help line wraps); at 100x30 again, the help line is one line. `stty -a` after the run is the same as before. |
| Pipe | `echo \| examples/target/bin/counter`: "stdin/stdout is not a terminal", exit code 1, no alternate screen, `stty -a` unchanged. |
| Native access | `java --illegal-native-access=deny ...`: "start the JVM with --enable-native-access=ALL-UNNAMED", exit code 1, no alternate screen, `stty -a` unchanged. |

<!-- claude 2026-10-13: changed the rows Resize and Native access (T1, review R3-9): the stty -a comparison -->

A human does the steps that a pseudo-terminal cannot show (colours, real
window resizes, look and feel) on the terminals of the platform, and writes
the result in the pull request:

- [ ] Start the Counter. Push `+` and `-`. The count changes at once. Tab
      moves between the buttons; Enter presses the button with the focus.
- [ ] Push `q`. The app stops. The shell prompt is correct, and there is no
      stray output.
- [ ] Start the Counter again. Push Ctrl-C. The app stops, and the prompt is
      correct.
- [ ] Make the window smaller and larger. The screen has no artefacts.
- [ ] Start the Counter without `--enable-native-access`. JDK 25 prints its
      warning before the UI starts, and the app starts:

      ```sh
      java --sun-misc-unsafe-memory-access=allow \
        -cp "$(cat examples/target/bin/classpath)" examples.counter.CounterKyoApp
      ```

## Known limits

- jatatui 0.31.0 cuts a typed character outside the Basic Multilingual Plane
  (an emoji) to 16 bits, so such a key arrives as a wrong character. A paste
  (bracketed paste) keeps it.
- A stop with `kill -INT` or `kill -TERM` from outside: a `KyoApp` main (as
  `CounterKyoApp`) interrupts the run, the run restores the terminal, and the
  app exits with 130 or 143 (Kyo's own signal handling; seen once in a
  pseudo-terminal, not a row of the kit yet). The rows for these signals come
  with `TuiApp` in a later version.
