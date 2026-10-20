<!-- claude 2026-10-20: created: the performance page of the bench (L5, stage 1); changed: the rows and the item of the start modes of tui4s (review fix) -->
# Performance

These numbers are not the headline of the bench (see [README.md](README.md)).
They are here so that a reader sees the costs of each stack, and the costs of
tui4s first.

<!-- report:begin machine -->
Machine `container-4vcpu`: Intel(R) Xeon(R) Processor @ 2.10GHz, 4 CPUs, 15.7 GB, Linux 6.18.44-fc-v139, measured 2026-10-20T03:59:45Z. The build container of this session: 4 vCPU shared with other work, noisy.
<!-- report:end machine -->

**The machine is noisy.** The numbers of this page come from a shared build
container (4 vCPU, other work on the same host). They are the median of 3 runs
of each task. Read them as orders of magnitude, not as exact values. The
planned clean run is on a dedicated AWS instance (`infra/`).

**The expectation.** tui4s runs on the JVM, with Kyo and kyo-ui, and it is
likely to lose on startup time, memory and size. The jatatui-plain row is the
floor of the JVM: it uses the same jatatui and crossterm jars with no Kyo, so
the difference between the jatatui-plain row and the tui4s row is the cost of
Kyo, kyo-ui and the tui4s runner. The last two rows of each table are the
start modes of tui4s (ADR 0015): the same program, started with a JDK 25 AOT
cache and the start flags of the examples (`impl/tui4s-aot`), and built as a
GraalVM native image (`impl/tui4s-native`).

## Method

All the times come from the harness clock (`time.monotonic_ns`) and the time
stamps of the bytes that the program writes into the pseudo-terminal (100
columns, 30 rows).

| Column | How |
| ------ | --- |
| first frame | From the start of `run.sh <task>` to the first byte after which the screen shows the first state of the specification (`Count: 0`; the marker on `Item 0001`). The JVM stacks start a JVM; no stack has a warm cache. |
| first key | From the write of the first key (`+`, ArrowDown) to the first byte after which the screen shows its effect. |
| key median, p90 | The same for the next 50 keys, each key after the effect of the last one. |
| CPU | The CPU time of the process tree (`/proc/<pid>/stat`) over 5 s after the keys, as a percent of one CPU. Counter: idle. Dashboard: with its 100 ms timer and 1 s clock. |
| RSS | The resident memory of the process tree at the end of that window. |
| artifact | What the program needs besides its runtime: the staged jars (JVM; no JDK), the binary (Rust, Go), the packages of the virtual environment without pip (Textual; no Python), the production `node_modules` and the bundle (ink; no Node.js). |
| tick gap | Dashboard: the time between two frames that show a new `Ticks: T` (the spec timer is 100 ms), and the percent of the ticks that no frame showed. |

## Counter

<!-- report:begin perf-counter -->
| Stack | first frame ms | first key ms | key median ms | key p90 ms | CPU idle % | RSS MB | artifact MB |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| tui4s | 1227 | 60.2 | 4.7 | 9.2 | 5.0 | 129 | 38.5 |
| jatatui-plain | 181 | 5.2 | 1.0 | 2.1 | 1.8 | 66 | 2.0 |
| TamboUI | 423 | 5.9 | 1.3 | 1.6 | 1.2 | 89 | 0.8 |
| layoutz | 336 | 8.4 | 30.5 | 31.3 | 2.0 | 75 | 10.8 |
| ratatui | 8 | 0.5 | 0.4 | 0.6 | 0.4 | 3 | 0.6 |
| bubbletea | 29 | 15.5 | 13.0 | 13.3 | 1.0 | 5 | 3.6 |
| Textual | 202 | 5.9 | 4.2 | 8.8 | 0.2 | 33 | 29.1 |
| ink | 370 | 7.8 | 22.1 | 39.4 | 0.0 | 116 | 8.2 |
| tui4s AOT cache (JDK 25) | 444 | 31.5 | 5.4 | 7.6 | 4.6 | 115 | 75.4 |
| tui4s native image (GraalVM) | 25 | 1.4 | 1.3 | 1.5 | 3.2 | 40 | 30.4 |
<!-- report:end perf-counter -->

## Dashboard

<!-- report:begin perf-dashboard -->
| Stack | first frame ms | first key ms | key median ms | key p90 ms | CPU at 10 Hz % | RSS MB | artifact MB | tick gap p50 ms | tick gap p90 ms | ticks skipped % |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| tui4s | 1600 | 89.6 | 15.4 | 27.8 | 20.2 | 198 | 38.5 | 100.6 | 103.2 | 0.0 |
| jatatui-plain | 276 | 8.0 | 1.9 | 2.9 | 4.8 | 112 | 2.0 | 100.0 | 100.8 | 0.0 |
| TamboUI | 465 | 6.4 | 1.6 | 2.4 | 12.8 | 125 | 0.8 | 100.0 | 100.5 | 0.0 |
| layoutz | 354 | 44.7 | 30.7 | 32.7 | 9.8 | 150 | 10.8 | 103.1 | 104.1 | 0.0 |
| ratatui | 11 | 0.6 | 0.7 | 0.8 | 0.6 | 4 | 0.8 | 99.7 | 100.7 | 0.0 |
| bubbletea | 30 | 13.2 | 11.8 | 13.1 | 1.8 | 10 | 4.0 | 100.1 | 100.6 | 0.0 |
| Textual | 226 | 75.9 | 76.0 | 79.5 | 9.6 | 35 | 29.1 | 100.0 | 102.2 | 0.0 |
| ink | 388 | 51.9 | 29.7 | 49.0 | 9.8 | 122 | 8.2 | 100.0 | 101.9 | 0.0 |
| tui4s AOT cache (JDK 25) | 586 | 37.3 | 10.4 | 15.6 | 12.2 | 162 | 79.6 | 100.7 | 101.9 | 0.0 |
| tui4s native image (GraalVM) | 49 | 7.9 | 6.5 | 9.6 | 6.4 | 80 | 31.7 | 100.5 | 101.6 | 0.0 |
<!-- report:end perf-dashboard -->

## What the numbers say (this machine)

- **Startup.** tui4s shows its first frame after about 1.2 s (counter) and
  1.6 s (dashboard). jatatui-plain, on the same JVM and the same jatatui jars,
  needs about 0.2 s and 0.3 s: on this machine Kyo, kyo-ui and the tui4s
  runner add about 1 to 1.3 s of startup (class loading and interpretation
  before the JIT). The native stacks need 8 ms (ratatui) and 30 ms
  (bubbletea).
- **Memory and size.** tui4s uses about 130 MB (counter) and 200 MB
  (dashboard) of RSS, and 38.5 MB of jars; jatatui-plain uses about 65 MB and
  110 MB, and 2 MB of jars. The JVM default heap settings are in use for all
  the JVM stacks.
- **Idle CPU.** The tui4s counter uses about 5 % of one CPU when nothing
  happens: the most of all the stacks (the others: 0 % to 2 %). The repair
  tick of the runner (each second) and the Kyo scheduler are the likely
  causes; this is an open issue for tui4s.
- **The 100 ms timer.** With the dashboard timer, tui4s uses about 20 % of one
  CPU (jatatui-plain about 5 %, ratatui under 1 %). Every stack keeps the
  timer: the median gap between two ticks on the screen is 100 to 103 ms, and
  no stack skips a tick.
- **Keys.** After the first key (60 to 90 ms in tui4s, before the JIT), a key
  shows its effect in about 5 ms (counter) and 15 ms (dashboard) in tui4s. The
  stacks that redraw on a timer show it later: layoutz about 30 ms (it renders
  each 50 ms), Textual about 76 ms in the dashboard.
- **The start modes of tui4s.** With the AOT cache, the first frame comes
  after about 0.44 s (counter) and 0.59 s (dashboard), with 115 MB and 162 MB
  of RSS. As a native image, it comes after 25 ms and 49 ms, with 40 MB and
  80 MB of RSS and about 3 % (idle) and 6 % (timer) of one CPU. Both modes
  pass all the resilience rows (README.md).
