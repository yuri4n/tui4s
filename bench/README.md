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
Machine `container-4vcpu`: Intel(R) Xeon(R) Processor @ 2.10GHz, 4 CPUs, 15.7 GB, Linux 6.18.44-fc-v139, measured 2026-10-20T03:47:58Z. The build container of this session: 4 vCPU shared with other work, noisy.
<!-- report:end machine -->

## Compactness

Lines without blank lines and comments, tokens of one simple lexer for all the
languages (a string, a name, a number or a run of operator characters is one
token), and the gzip bytes of the source without comments
(`harness/compact.py`). The sources are formatted with the standard formatter
of each stack.

<!-- report:begin compactness -->
<!-- report:end compactness -->

## Expressiveness

The concepts that a reader needs ([tasks/concepts.md](tasks/concepts.md), one
reader's count), and the size of the same change request in each stack: "the
counter also shows the count in words" and "the dashboard list gets a filter
input" (`impl/<stack>/<task>/change.patch`, the added and removed lines without
blank and comment lines; the harness applies the patch, builds it and runs the
oracle of the change).

<!-- report:begin expressiveness -->
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
<!-- report:end mutants -->

<!-- report:begin mutant-matrix -->
<!-- report:end mutant-matrix -->

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
