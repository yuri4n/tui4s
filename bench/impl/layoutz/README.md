<!-- claude 2026-10-20: created: the layoutz stack of the bench (L5) -->
# layoutz

The two tasks in Scala 3 with layoutz (by Matthieu Court), a library of text
layouts with an Elm-style app runtime (`LayoutzApp`: `init`, `update`,
`subscriptions`, `view`). The runtime uses `stty` for raw mode and three
threads (input, ticks, render every 50 ms).

| Item | Version |
| ---- | ------- |
| layoutz | 0.8.0 (`xyz.matthieucourt::layoutz`) |
| Scala | 3.9.0 |
| JDK | 25 |
| Formatter | scalafmt 3.11.5 (defaults for Scala 3) |
| Lock file | none (sbt has no standard lock file; the versions are exact in `bench/build.sbt`) |

```
bench/impl/layoutz/build.sh
bench/impl/layoutz/run.sh counter
bench/impl/layoutz/run.sh dashboard
```

## Limits of the model (the spec rows that layoutz cannot meet)

- No alternate screen: the runtime clears the main screen and draws from the
  top. The restore contract then has "never entered" for the alternate screen.
- No size and no resize event: the apps read the size once with
  `stty size` (as layoutz does in its own pager). A resize does not change
  the layout.
- Each line of a frame ends with a newline, so a frame cannot use the last
  row (the screen would scroll): the help row and the status bar are on row
  R-1, not on row R.
- Ctrl-C is a signal, not a key (the runtime keeps ISIG): the JVM ends with
  130, and the shutdown hook of layoutz restores the terminal.
- The forced failure (Ctrl-X) is contained: the runtime catches the exception
  of `update` and writes "Input error: ..." on the screen; the app goes on.
- The sparkline of layoutz scales to the minimum and the maximum of the data;
  the spec scales 0 to 100, so the dashboard draws its own.
