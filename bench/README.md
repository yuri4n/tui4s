<!-- claude 2026-10-20: created: the page of the bench (L5, stage 1) -->
# tui4s bench

The bench compares tui4s with seven other terminal UI stacks on the same
tasks. Each task has an exact specification, and a harness checks every
implementation in a pseudo-terminal (decision D10). The headline is
compactness, expressiveness, correctness and resource management. The
performance numbers are on their own page, [PERFORMANCE.md](PERFORMANCE.md).

**Rules of honesty.** Every number states the machine that measured it. An
implementation in a stack other than tui4s is reviewed by a person of that
ecosystem before the numbers are published: **this review is pending** for all
seven stacks. The concept counts are the judgment of one reader.

## Stage 1

Two tasks: [counter](tasks/counter.md) and [dashboard](tasks/dashboard.md).
The five tasks of stage 2 are in [tasks/README.md](tasks/README.md).

| Stack | Language | Versions | Notes |
| ----- | -------- | -------- | ----- |
| [tui4s](impl/tui4s) | Scala 3.9.0, JDK 25 | tui4s (this repository), Kyo and kyo-ui 1.0.0-RC7, jatatui 0.31.0 | `TuiApp`, kyo-ui, tui4s-use, tui4s-widgets |
| [jatatui-plain](impl/jatatui-plain) | Java 25 | jatatui 0.31.0 | the same jatatui and crossterm jars as tui4s, no Kyo: the JVM floor |
| [TamboUI](impl/tamboui) | Java 25 | TamboUI 0.6.0 | a Java library (the program listed it as .NET; it is on Maven Central, not NuGet) |
| [layoutz](impl/layoutz) | Scala 3.9.0, JDK 25 | layoutz 0.8.0 | no alternate screen, no resize, no last row: see its README |
| [ratatui](impl/ratatui) | Rust 1.97 | ratatui 0.30.2, crossterm 0.29.0 | `signal-hook` for SIGTERM and SIGINT |
| [bubbletea](impl/bubbletea) | Go 1.24 | bubbletea 1.3.10, bubbles 1.0.0, lipgloss 1.1.0 | |
| [Textual](impl/textual) | Python 3.11 | Textual 8.2.8 | a signal handler for SIGTERM and SIGINT |
| [ink](impl/ink) | Node.js 22 | ink 8.0.0, React 19.3.1 | esbuild for the JSX |

Dropped or reduced in stage 1: TamboUI as a .NET stack (it is a Java stack
here); 1 mutant class of tui4s, 1 of TamboUI, 2 of bubbletea, 4 of Textual,
4 of ink and 6 of layoutz (not applicable, with the reason in
`harness/mutants.py`).
<!-- claude 2026-10-20: changed: the n/a classes of tui4s and bubbletea (review fix); the TamboUI mutants are written (L6) -->

## Run it

```
bench/run.sh            # full: publish tui4s locally, build all, measure, write the tables
bench/run.sh ci         # the CI smoke: build all, conformance, resilience, mutants, one perf run, the check
```

`run.sh` needs JDK 25 and sbt, Rust, Go, Python 3, Node.js 22 with pnpm, and
`patch`. The harness is Python (the standard library only). The results go to
`results/<machine>/` (`BENCH_MACHINE` names the machine), and
`harness/report.py` writes the tables below. The machine of this page:

<!-- report:begin machine -->
Machine `container-4vcpu`: Intel(R) Xeon(R) Processor @ 2.10GHz, 4 CPUs, 15.7 GB, Linux 6.18.44-fc-v139, measured 2026-10-20T03:59:45Z. The build container of this session: 4 vCPU shared with other work, noisy.
<!-- report:end machine -->

## Compactness

Lines without blank lines and comments, tokens of one simple lexer for all the
languages (a string, a name, a number or a run of operator characters is one
token), and the gzip bytes of the source without comments
(`harness/compact.py`). The sources are formatted with the standard formatter
of each stack.

<!-- report:begin compactness -->
| Stack | counter lines | tokens | gzip B | dashboard lines | tokens | gzip B |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| tui4s | 27 | 236 | 473 | 78 | 681 | 1238 |
| jatatui-plain | 70 | 560 | 920 | 154 | 1485 | 1994 |
| TamboUI | 46 | 387 | 646 | 120 | 1221 | 1584 |
| layoutz | 23 | 292 | 555 | 83 | 904 | 1413 |
| ratatui | 53 | 475 | 813 | 138 | 1322 | 1932 |
| bubbletea | 52 | 327 | 636 | 119 | 932 | 1486 |
| Textual | 37 | 233 | 594 | 102 | 713 | 1380 |
| ink | 28 | 265 | 457 | 103 | 722 | 1306 |
<!-- report:end compactness -->

## Expressiveness

The concepts that a reader needs ([tasks/concepts.md](tasks/concepts.md), one
reader's count), and the size of the same change request in each stack: "the
counter also shows the count in words" and "the dashboard list gets a filter
input" (`impl/<stack>/<task>/change.patch`, the added and removed lines without
blank and comment lines; the harness applies the patch, builds it and runs the
oracle of the change).

<!-- report:begin expressiveness -->
| Stack | counter concepts | change lines | change oracle | dashboard concepts | change lines | change oracle |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| tui4s | 11 | 7 | 12/12 | 23 | 33 | 5/5 |
| jatatui-plain | 9 | 12 | 12/12 | 18 | 44 | 5/5 |
| TamboUI | 9 | 12 | 12/12 | 17 | 52 | 5/5 |
| layoutz | 8 | 9 | 9/12 | 16 | 47 | 4/5 |
| ratatui | 10 | 16 | 12/12 | 19 | 53 | 5/5 |
| bubbletea | 9 | 13 | 12/12 | 17 | 51 | 5/5 |
| Textual | 11 | 10 | 12/12 | 22 | 39 | 5/5 |
| ink | 9 | 7 | 12/12 | 16 | 28 | 5/5 |
<!-- report:end expressiveness -->

## Correctness

### Conformance to the specification

The steps of the key script of each task (the screen oracle of
`harness/oracle.py`), and two more steps. `modes` checks raw mode, the
alternate screen and a hidden cursor while the program runs. `layout` (the
dashboard) checks the clock at the right end of row 1, the list panel in the
left C/2 columns, the load panel right of it, and `Item 0001` as the first
visible row. The PageDown, PageUp and ArrowUp steps also check the viewport
rule (the minimum scroll). The resize step also checks the marker and the
panels at the new width. The oracle does not check these rules of the
specification: the styles (free), the bar of the gauge, the sparkline, and
the local time of the clock (the harness sets `TZ=UTC`).
<!-- claude 2026-10-20: changed: the steps modes and layout, and the rules that the oracle does not check (review fix) -->

<!-- report:begin conformance -->
| Stack | counter steps | counter failed | dashboard steps | dashboard failed |
| --- | ---: | ---: | ---: | ---: |
| tui4s | 12/12 |  | 16/16 |  |
| jatatui-plain | 12/12 |  | 16/16 |  |
| TamboUI | 12/12 |  | 16/16 |  |
| layoutz | 9/12 | start, modes, resize 80x24 | 13/16 | start, modes, resize 80x24 |
| ratatui | 12/12 |  | 16/16 |  |
| bubbletea | 12/12 |  | 16/16 |  |
| Textual | 12/12 |  | 16/16 |  |
| ink | 12/12 |  | 16/16 |  |
<!-- report:end conformance -->

### Resource management: the restore contract

After each way to end the program, the terminal is not in raw mode (the
termios flags of the PTY), the alternate screen was left, the cursor is
visible, and no process of the program stays alive (`counter.md`, "Restore
contract"). "failure" is the key Ctrl-X, which throws in the key handler.
`counter.md` accepts two outcomes: "ended" (the program ends with a non-zero
exit code) and "contained" (the runtime catches the failure and the next keys
work). "error-screen" (the runtime catches it, and the next key has no
effect) is not one of them, and the row fails. "kill-mid-frame" sends a burst
of 30 keys and sends SIGTERM when the first byte of output after the burst
arrives; the cell gives the bytes that arrived before the kill.
<!-- claude 2026-10-20: changed: the failure outcomes and the kill of kill-mid-frame (review fixes) -->

<!-- report:begin resilience -->
| Stack, task | quit | ctrl-c | sigterm | sigint | failure | kill-mid-frame |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| tui4s counter | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (contained) | ok (exit 143, kill after 32 B) |
| tui4s dashboard | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (contained) | ok (exit 143, kill after 79 B) |
| jatatui-plain counter | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (ended) | ok (exit 143, kill after 32 B) |
| jatatui-plain dashboard | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (ended) | ok (exit 143, kill after 87 B) |
| TamboUI counter | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | FAIL (error-screen) | ok (exit 143, kill after 31 B) |
| TamboUI dashboard | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | FAIL (error-screen) | ok (exit 143, kill after 302 B) |
| layoutz counter | ok (exit 0, no alt screen) | ok (exit 130, no alt screen) | ok (exit 143, no alt screen) | ok (exit 130, no alt screen) | ok (contained, no alt screen) | ok (exit 143, kill after 3 B, no alt screen) |
| layoutz dashboard | ok (exit 0, no alt screen) | ok (exit 130, no alt screen) | ok (exit 143, no alt screen) | ok (exit 130, no alt screen) | ok (contained, no alt screen) | ok (exit 143, kill after 3 B, no alt screen) |
| ratatui counter | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (ended) | ok (exit 0, kill after 32 B) |
| ratatui dashboard | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (ended) | ok (exit 0, kill after 48 B) |
| bubbletea counter | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 1) | ok (ended) | ok (exit 0, kill after 51 B) |
| bubbletea dashboard | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 1) | ok (ended) | ok (exit 0, kill after 4095 B) |
| Textual counter | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (ended) | ok (exit 0, kill after 3571 B) |
| Textual dashboard | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (ended) | ok (exit 0, kill after 4095 B) |
| ink counter | ok (exit 0) | ok (exit 0) | ok (exit -15) | ok (exit -2) | ok (ended) | ok (exit -15, kill after 369 B) |
| ink dashboard | ok (exit 0) | ok (exit 0) | ok (exit -15) | ok (exit -2) | ok (ended) | ok (exit -15, kill after 4095 B) |
| tui4s AOT cache (JDK 25) counter | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (contained) | ok (exit 143, kill after 32 B) |
| tui4s AOT cache (JDK 25) dashboard | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (contained) | ok (exit 143, kill after 207 B) |
| tui4s native image (GraalVM) counter | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (contained) | ok (exit 143, kill after 32 B) |
| tui4s native image (GraalVM) dashboard | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (contained) | ok (exit 143, kill after 207 B) |
<!-- report:end resilience -->

### Mutants

13 bug classes ([mutants/classes.md](mutants/classes.md)), one small mutant
of the counter per class and stack. The outcome is what finds the bug first:
the compiler, a crash, the oracle of the harness, or nothing. A mutant that
passes the oracle is "defused" only when the defuse probe of its class (the
same for every stack, in `mutants/classes.md`) shows that the bug has no
effect; else it is "silent".
<!-- claude 2026-10-20: changed: the rule of defused (review fix) -->

<!-- report:begin mutants -->
| Stack | compile-error | runtime-crash | test-or-oracle-catches | defused | silent-wrong-behaviour | n/a | not-written | mutants run |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| tui4s | 0 | 1 | 7 | 2 | 2 | 1 | 0 | 12 |
| jatatui-plain | 1 | 1 | 11 | 0 | 0 | 0 | 0 | 13 |
| TamboUI | 1 | 0 | 10 | 0 | 1 | 1 | 0 | 12 |
| layoutz | 0 | 0 | 5 | 0 | 2 | 6 | 0 | 7 |
| ratatui | 2 | 2 | 8 | 0 | 1 | 0 | 0 | 13 |
| bubbletea | 0 | 1 | 9 | 0 | 1 | 2 | 0 | 11 |
| Textual | 0 | 2 | 4 | 2 | 1 | 4 | 0 | 9 |
| ink | 0 | 1 | 7 | 0 | 1 | 4 | 0 | 9 |
<!-- report:end mutants -->

<!-- report:begin mutant-matrix -->
| Stack | no-restore-on-exception | wrong-thread | no-resize | off-by-one | blocking-handler | leaked-timer | non-exhaustive-key | null-read | stale-closure | wrong-unit | stdout-write | missing-binding | lost-update |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| tui4s | n/a | SILENT | SILENT | oracle | oracle | oracle | defused | crash | oracle | oracle | defused | oracle | oracle |
| jatatui-plain | oracle | oracle | oracle | oracle | oracle | oracle | compile | crash | oracle | oracle | oracle | oracle | oracle |
| TamboUI | n/a | SILENT | oracle | oracle | oracle | oracle | compile | oracle | oracle | oracle | oracle | oracle | oracle |
| layoutz | n/a | n/a | n/a | SILENT | oracle | oracle | oracle | oracle | n/a | n/a | SILENT | oracle | n/a |
| ratatui | oracle | compile | crash | oracle | oracle | SILENT | compile | crash | oracle | oracle | oracle | oracle | oracle |
| bubbletea | oracle | oracle | oracle | oracle | oracle | SILENT | n/a | crash | n/a | oracle | oracle | oracle | oracle |
| Textual | n/a | SILENT | n/a | oracle | oracle | oracle | n/a | crash | n/a | crash | defused | oracle | defused |
| ink | n/a | n/a | oracle | oracle | oracle | oracle | n/a | crash | oracle | n/a | SILENT | oracle | oracle |
<!-- report:end mutant-matrix -->

## What stage 1 found

<!-- claude 2026-10-20: changed: the findings from the results of the review fixes; the TamboUI mutants (L6) -->
- **Conformance.** Seven stacks pass every step of both key scripts, with the
  modes, the layout and the viewport rule. layoutz fails the start (its last
  row stays free), the modes (it never enters the alternate screen) and the
  resize (it has no size events): see `impl/layoutz/README.md`.
- **Keys in one read.** bubbletea and ink give the keys of one read (`+++++`)
  to the program as one message. A handler that compares the message with
  `+` loses keys; their counters split the message (the `lost-update` mutants
  of both stacks remove that split, and the oracle catches them).
- **Signals.** Every stack restores the terminal after quit, Ctrl-C, SIGTERM,
  SIGINT and a SIGTERM during the frames of a burst of keys, on this machine.
  tui4s, TamboUI, bubbletea, ink and layoutz do it with no code of the app.
  jatatui-plain needs a shutdown hook, ratatui needs `signal-hook`, and
  Textual needs a handler on the asyncio loop; that code counts in their
  compactness.
- **The forced failure.** tui4s and layoutz contain an exception of a key
  handler and go on; the other stacks except TamboUI end the program and
  restore the terminal. TamboUI shows an error screen, and the next keys have
  no effect: this is not an outcome of `counter.md`, so its failure rows fail.
- **Mutants.** In every stack the oracle, a crash or the compiler finds most
  mutants. The compiler catches one in jatatui-plain, one in TamboUI and two
  in ratatui (a match that is not exhaustive; in ratatui also a draw from
  another thread). In TamboUI an exception in the view shows the error
  screen of `TuiRunner` (the `null-read` mutant), and the oracle sees it.
  The probes defuse two in tui4s (the capture of stdout, the `MatchError` of
  a handler) and two in Textual (the capture of `print()`, one message at a
  time). The silent ones: in tui4s a count written from a raw thread (it
  works, because a `SignalRef` is atomic) and a root with the height of the
  start (the probe sees the help row on row 30 of 40 after a resize to a
  larger terminal); a count written from a thread in Textual (it works here,
  but Textual asks for `call_from_thread`); a draw from a new thread in
  TamboUI (it works here: `Terminal.draw` has no check of the thread, only
  the loop of `TuiRunner` checks it); a leaked timer in ratatui and
  bubbletea (it ends with the process); a write to stdout in ink and in
  layoutz (the text reaches the screen of the UI, and the next frame hides
  it); and an off-by-one row in layoutz (its start step already fails).
- **tui4s.** See "Open issues of tui4s" below.

## Open issues of tui4s

<!-- claude 2026-10-20: created: the open issues of tui4s that the bench shows (review fix: the old bullet named a report that is not in the repository) -->
The numbers are from [PERFORMANCE.md](PERFORMANCE.md) (the build container,
noisy; read them as orders of magnitude).

- **Start.** The first frame comes after about 1.2 s (counter) and 1.6 s
  (dashboard). jatatui-plain, on the same JVM and the same jatatui jars, needs
  about 0.2 s and 0.3 s.
- **Memory and size.** The RSS is about 130 MB (counter) and 200 MB
  (dashboard), and the jars are 38.5 MB (jatatui-plain: 2 MB).
- **CPU.** About 5 % of one CPU when the counter is idle (the other stacks:
  0 % to 2 %), and about 20 % with the 100 ms timer of the dashboard
  (jatatui-plain: about 5 %).
- **Widgets.** `ListView.virtual` takes a fixed height, so the dashboard
  mounts a new list for each terminal height; `useSelection` takes a fixed
  page, so PageDown after a resize moves by the old page.
- **Failures.** An exception in a view ends the run (the `null-read` mutant),
  and an exception in a key handler is contained. This difference is not
  decided or documented yet.

## Layout of this directory

| Path | Content |
| ---- | ------- |
| `tasks/` | The specifications, the stage 2 list, the concepts. |
| `impl/<stack>/` | The implementations: `build.sh`, `run.sh <task>`, a README with the versions, `<task>/change.patch`. |
| `harness/` | The PTY harness (`bench.py`), the oracles, the compactness, the report. |
| `mutants/` | The bug classes and the mutant patches of each stack. |
| `results/<machine>/` | The JSON results of one machine; `results/static/` has the compactness. |
| `infra/` | The AWS run of the bench (alchemy): see its README. |
| `build.sbt`, `project/` | The sbt build of the JVM stacks (not the root build of tui4s). |
