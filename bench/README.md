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
here); the mutants of TamboUI (13 classes, not written); 4 mutant classes of
Textual, 4 of ink and 6 of layoutz (not applicable, with the reason in
`harness/mutants.py`).

## Run it

```
bench/run.sh            # full: publish tui4s locally, build all, measure, write the tables
bench/run.sh ci         # the CI smoke: build all, conformance, resilience, mutants, one perf run
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
| tui4s | 11 | 7 | 11/11 | 23 | 33 | 5/5 |
| jatatui-plain | 9 | 12 | 11/11 | 18 | 44 | 5/5 |
| TamboUI | 9 | 12 | 11/11 | 17 | 52 | 5/5 |
| layoutz | 8 | 9 | 9/11 | 16 | 47 | 3/5 |
| ratatui | 10 | 16 | 11/11 | 19 | 53 | 5/5 |
| bubbletea | 9 | 13 | 11/11 | 17 | 51 | 5/5 |
| Textual | 11 | 10 | 11/11 | 22 | 37 | 5/5 |
| ink | 9 | 7 | 11/11 | 16 | 28 | 5/5 |
<!-- report:end expressiveness -->

## Correctness

### Conformance to the specification

The steps of the key script of each task (the screen oracle of
`harness/oracle.py`).

<!-- report:begin conformance -->
| Stack | counter steps | counter failed | dashboard steps | dashboard failed |
| --- | ---: | ---: | ---: | ---: |
| tui4s | 11/11 |  | 14/14 |  |
| jatatui-plain | 11/11 |  | 14/14 |  |
| TamboUI | 11/11 |  | 14/14 |  |
| layoutz | 9/11 | start, resize 80x24 | 12/14 | start, resize 80x24 |
| ratatui | 11/11 |  | 14/14 |  |
| bubbletea | 11/11 |  | 14/14 |  |
| Textual | 11/11 |  | 14/14 |  |
| ink | 11/11 |  | 14/14 |  |
<!-- report:end conformance -->

### Resource management: the restore contract

After each way to end the program, the terminal is not in raw mode (the
termios flags of the PTY), the alternate screen was left, the cursor is
visible, and no process of the program stays alive (`counter.md`, "Restore
contract"). "failure" is the key Ctrl-X, which throws in the key handler:
"ended" (the program ends), "contained" (the runtime catches it and the keys
work), "error-screen" (the runtime catches it and shows an error screen).

<!-- report:begin resilience -->
| Stack, task | quit | ctrl-c | sigterm | sigint | failure | kill-mid-frame |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| tui4s counter | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (contained) | ok (exit 143) |
| tui4s dashboard | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (contained) | ok (exit 143) |
| jatatui-plain counter | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (ended) | ok (exit 143) |
| jatatui-plain dashboard | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (ended) | ok (exit 143) |
| TamboUI counter | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (error-screen) | ok (exit 143) |
| TamboUI dashboard | ok (exit 0) | ok (exit 0) | ok (exit 143) | ok (exit 130) | ok (error-screen) | ok (exit 143) |
| layoutz counter | ok (exit 0, no alt screen) | ok (exit 130, no alt screen) | ok (exit 143, no alt screen) | ok (exit 130, no alt screen) | ok (contained, no alt screen) | ok (exit 143, no alt screen) |
| layoutz dashboard | ok (exit 0, no alt screen) | ok (exit 130, no alt screen) | ok (exit 143, no alt screen) | ok (exit 130, no alt screen) | ok (contained, no alt screen) | ok (exit 143, no alt screen) |
| ratatui counter | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (ended) | ok (exit 0) |
| ratatui dashboard | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (ended) | ok (exit 0) |
| bubbletea counter | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 1) | ok (ended) | ok (exit 0) |
| bubbletea dashboard | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 1) | ok (ended) | ok (exit 0) |
| Textual counter | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (ended) | ok (exit 0) |
| Textual dashboard | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (exit 0) | ok (ended) | ok (exit 0) |
| ink counter | ok (exit 0) | ok (exit 0) | ok (exit -15) | ok (exit -2) | ok (ended) | ok (exit -15) |
| ink dashboard | ok (exit 0) | ok (exit 0) | ok (exit -15) | ok (exit -2) | ok (ended) | ok (exit -15) |
<!-- report:end resilience -->

### Mutants

13 bug classes ([mutants/classes.md](mutants/classes.md)), one small mutant
of the counter per class and stack. The outcome is what finds the bug first:
the compiler, a crash, the oracle of the harness, or nothing.

<!-- report:begin mutants -->
| Stack | compile-error | runtime-crash | test-or-oracle-catches | defused | silent-wrong-behaviour | n/a | mutants run |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| tui4s | 0 | 1 | 6 | 4 | 0 | 2 | 11 |
| jatatui-plain | 1 | 1 | 11 | 0 | 0 | 0 | 13 |
| TamboUI | 0 | 0 | 0 | 0 | 0 | 13 | 0 |
| layoutz | 0 | 0 | 5 | 0 | 2 | 6 | 7 |
| ratatui | 2 | 2 | 8 | 1 | 0 | 0 | 13 |
| bubbletea | 0 | 1 | 9 | 1 | 0 | 2 | 11 |
| Textual | 0 | 2 | 4 | 2 | 1 | 4 | 9 |
| ink | 0 | 1 | 7 | 1 | 0 | 4 | 9 |
<!-- report:end mutants -->

<!-- report:begin mutant-matrix -->
| Stack | no-restore-on-exception | wrong-thread | no-resize | off-by-one | blocking-handler | leaked-timer | non-exhaustive-key | null-read | stale-closure | wrong-unit | stdout-write | missing-binding | lost-update |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| tui4s | n/a | n/a | defused | oracle | oracle | oracle | defused | crash | oracle | defused | defused | oracle | oracle |
| jatatui-plain | oracle | oracle | oracle | oracle | oracle | oracle | compile | crash | oracle | oracle | oracle | oracle | oracle |
| TamboUI | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a |
| layoutz | n/a | n/a | n/a | SILENT | oracle | oracle | oracle | oracle | n/a | n/a | SILENT | oracle | n/a |
| ratatui | oracle | compile | crash | oracle | oracle | defused | compile | crash | oracle | oracle | oracle | oracle | oracle |
| bubbletea | oracle | oracle | oracle | oracle | oracle | defused | n/a | crash | n/a | oracle | oracle | oracle | oracle |
| Textual | n/a | SILENT | n/a | oracle | oracle | oracle | n/a | crash | n/a | crash | defused | oracle | defused |
| ink | n/a | n/a | oracle | oracle | oracle | oracle | n/a | crash | oracle | n/a | defused | oracle | oracle |
<!-- report:end mutant-matrix -->

## What stage 1 found

- **Conformance.** Seven stacks pass every step of both key scripts. layoutz
  fails the start (its last row stays free) and the resize (it has no size
  events): see `impl/layoutz/README.md`.
- **Keys in one read.** bubbletea and ink give the keys of one read (`+++++`)
  to the program as one message. A handler that compares the message with
  `+` loses keys; their counters split the message (the `lost-update` mutants
  of both stacks remove that split, and the oracle catches them).
- **Signals.** Every stack restores the terminal in the six rows of the
  restore contract on this machine. tui4s, TamboUI, bubbletea, ink and layoutz
  do it with no code of the app. jatatui-plain needs a shutdown hook, ratatui
  needs `signal-hook`, and Textual needs a handler on the asyncio loop; that
  code counts in their compactness.
- **The forced failure.** tui4s and layoutz contain an exception of a key
  handler and go on; TamboUI shows an error screen; the other stacks end the
  program and restore the terminal.
- **Mutants.** In every stack the oracle, a crash or the compiler finds most
  mutants. tui4s defuses four (the capture of stdout, the `MatchError` of a
  handler, two fixed root heights) and has no silent mutant; the compiler
  catches one in jatatui-plain and two in ratatui (a match that is not
  exhaustive; in ratatui also a draw from another thread). The silent ones: a
  state change from another thread in Textual (it works here, but Textual asks
  for `call_from_thread`), and in layoutz a write to stdout (the next frame
  overwrites it) and an off-by-one row (its start step already fails).
- **tui4s.** The gaps that the bench shows are in the open issues of the
  bench report: the start time and the memory of the JVM stack, an idle CPU of
  about 5 %, `ListView.virtual` with a fixed height (the dashboard mounts a new
  list for each terminal height), the fixed page of `useSelection`, and an
  exception in a view that ends the run (the `null-read` mutant).

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
