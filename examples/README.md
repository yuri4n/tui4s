# tui4s examples

This project has the sample apps of tui4s. They are not published.

| App       | Main class                         | What it shows                                                 |
| --------- | ---------------------------------- | ------------------------------------------------------------- |
| Counter   | `examples.counter.CounterApp`      | A keymap, a help bar, `Quit`.                                 |
| Todo      | `examples.todo.TodoApp`            | A text field, a list, focus, a load task, a save task.        |
| Dashboard | `examples.dashboard.DashboardApp`  | Tabs, a gauge and a sparkline from a poller, a log stream.    |

## Run an example

Use a real terminal (not the sbt shell of an IDE):

```sh
sbt "examples/runMain examples.counter.CounterApp"
sbt "examples/runMain examples.todo.TodoApp"
sbt "examples/runMain examples.dashboard.DashboardApp"
```

The build forks a JVM, connects stdin, and gives the JVM these flags:

- `--enable-native-access=ALL-UNNAMED`: crossterm is a native library.
- `--sun-misc-unsafe-memory-access=allow`: Kyo and Scala use `sun.misc.Unsafe`.

Stdin must be a terminal. The app draws on stdout: sbt sends the stdout of the
forked JVM to the terminal through a pipe, and that is correct. Do not redirect
stdout to a file. Nothing checks stdout, so the frames go into the file.

The Todo app reads and writes `~/.tui4s-todo.txt`.

## Test the examples

```sh
sbt examples/test
```

The tests do not need a terminal. They use `tui4s.testkit.TuiTest` with munit.

## Real-terminal checklist

The automatic tests cannot open a real terminal. Before each release, a human
(or an agent with a terminal) does this checklist on macOS Terminal and on
iTerm2. Write the result in the pull request.

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
- [ ] Start an example with `--illegal-native-access=deny` in place of
      `--enable-native-access=ALL-UNNAMED`. The app stops with "start the JVM
      with --enable-native-access=ALL-UNNAMED", before raw mode:

      ```sh
      sbt 'set examples / run / javaOptions := Seq("--illegal-native-access=deny", "--sun-misc-unsafe-memory-access=allow")' "examples/runMain examples.counter.CounterApp"
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
