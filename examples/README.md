# tui4s examples

This project has the sample apps of tui4s. They are not published.

| App       | Main class                         | What it shows                                                 |
| --------- | ---------------------------------- | ------------------------------------------------------------- |
| Counter   | `examples.counter.CounterApp`      | A keymap, a help bar, `Quit`.                                 |
| Todo      | `examples.todo.TodoApp`            | A text field, a list, focus, a load task, a save task.        |
| Dashboard | `examples.dashboard.DashboardApp`  | Tabs, a gauge and a sparkline from a poller, a log stream.    |

## Run an example

Write the start scripts, then start an app in a real terminal:

```sh
sbt examples/launchers
examples/target/bin/counter
examples/target/bin/todo
examples/target/bin/dashboard
```

Each script starts the app in its own JVM with these flags:

- `--enable-native-access=ALL-UNNAMED`: crossterm is a native library.
- `--sun-misc-unsafe-memory-access=allow`: Kyo and Scala use `sun.misc.Unsafe`.

Put more JVM options in `JAVA_OPTS`, for example
`JAVA_OPTS=-Duser.home=/tmp/x examples/target/bin/todo`.

Do not use `sbt run` or `sbt runMain` to use an app. The app starts, but sbt
also reads the keyboard, so some keys never get to the app (a `q` can be lost).
With `sbt -batch`, sbt gives the app a pipe as stdin, and the app stops with
"stdin is not a terminal". This is true for every terminal UI that sbt starts,
not only for tui4s.

The Todo app reads and writes `~/.tui4s-todo.txt`.

## Test the examples

```sh
sbt examples/test
```

The tests do not need a terminal. They use `tui4s.testkit.TuiTest` with munit.

## Real-terminal checklist

The unit tests cannot open a real terminal. Before each release, do this
checklist.

Most of it runs without a human:

```sh
examples/ptycheck/run.sh
```

The script starts each example in a pseudo-terminal (with `expect`), sends the
keys of the checklist, replays the output in a small terminal emulator, and
checks the screen. After each run it checks with `stty` that the terminal is
back in cooked mode with echo. It needs `expect` and `python3`.

Then a human does the steps that a pseudo-terminal cannot show (colors, real
window resizes, look and feel) on macOS Terminal and on iTerm2. Write the
result in the pull request.

### Counter

- [ ] Start the Counter. Push `+` and `-`. The count changes at once.
- [ ] Push `q`. The app stops. The shell prompt is correct, and there is no
      stray output.
- [ ] Start the Counter again. Push Ctrl-C. The app stops, and the prompt is
      correct.

### Todo

- [ ] Type a title and push Enter. The item shows in the list.
- [ ] Paste a text that has an emoji. The text shows correctly in the field.
- [ ] Push Tab. The focus moves to the list. The help bar shows the list keys.
- [ ] Push Space. The item toggles. Push `d`. The item is deleted.
- [ ] Push Ctrl-S. The status shows "saved". `~/.tui4s-todo.txt` has the items.
- [ ] Stop the app and start it again. The items load.

### Dashboard

- [ ] Push Left and Right. The tab changes.
- [ ] The gauge moves about 4 times each second.
- [ ] Push `p`. The samples stop. The log lines continue.
- [ ] Make the window smaller and larger. The screen has no artefacts.

### Stop and restore

- [ ] Start an example. In a second terminal, run `kill -TERM <pid>`. The
      terminal is restored (no raw mode, no alternate screen, the cursor shows).
      The exit code is 143 (128 + SIGTERM), as for other JVM programs.
- [ ] Start an example with `--illegal-native-access=deny` in place of
      `--enable-native-access=ALL-UNNAMED`. The app stops with "start the JVM
      with --enable-native-access=ALL-UNNAMED", before raw mode:

      ```sh
      java --illegal-native-access=deny --sun-misc-unsafe-memory-access=allow \
        -cp "$(cat examples/target/bin/classpath)" examples.counter.CounterApp
      ```

- [ ] Start an example without `--enable-native-access`. JDK 25 prints its
      JEP 472 warning before raw mode, and the app starts:

      ```sh
      sbt 'set examples / run / javaOptions := Seq("--sun-misc-unsafe-memory-access=allow")' "examples/runMain examples.counter.CounterApp"
      ```

- [ ] Start an example with stdin that is not a terminal. It prints
      "stdin/stdout is not a terminal" (cause: "stdin is not a terminal")
      before raw mode, and the exit code is 1:

      ```sh
      sbt "examples/runMain examples.counter.CounterApp" < /dev/null
      ```

`sbt -J<flag>` does not work for these steps: it changes the JVM of sbt, not
the forked JVM of the app.

### Known risks to look at

- An inline viewport (`TuiOptions(viewport = Viewport.Inline(10))`): the
  cursor query at start must not take a key from the input thread, and the
  prompt must come below the last frame.
- The input thread reads while the render fiber writes (crossterm reader and
  writer at the same time). Type fast while the Dashboard draws. No key is
  lost, and no escape sequence shows on the screen.
