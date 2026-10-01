# Performance baseline

The budgets are in SPEC section 7.3. The `bench` project measures them: JMH for
the micro benchmarks, and probe mains for the rest. Each probe prints a row and
appends a JSON line to `bench/results/<probe>.jsonl` (not in git).

Run the benchmarks alone. Other builds or agents on the machine make the
numbers useless. Write the load average with each result.

## How to measure

```sh
sbt "bench/Jmh/run -i 5 -wi 5 -w 1s -r 1s -f 1 -prof gc tui4s.bench.EngineBench tui4s.bench.PaintBench"
sbt "bench/runMain tui4s.bench.BurstProbe 8"
sbt "bench/runMain tui4s.bench.LatencyProbe 400"
sbt "bench/runMain tui4s.bench.QuitProbe 30"
sbt "bench/runMain tui4s.bench.IdleProbe 20 2"
sbt "bench/runMain tui4s.bench.DashboardProbe 20"
sbt "bench/runMain tui4s.bench.SoakProbe 600"
```

## 2026-10-01, first measurement (core C5-C7, not released)

- Machine: MacBook Pro, Intel Core i7-4980HQ 2.8 GHz (4 cores, 8 threads),
  16 GB, macOS 15.7.5.
- JDK: Temurin 25.0.4.1+1. JVM flags: `--enable-native-access=ALL-UNNAMED
  --sun-misc-unsafe-memory-access=allow`; the probes fork JVMs with `-Xmx512m`.
- Kyo scheduler: the flags of `tui4s.spi.SchedulerDefaults` (SPEC 5.14), except
  where the table says "Kyo defaults".
- Load average during the runs: 4 to 7 on 8 CPUs. Other work ran on the
  machine at the same time, so the times are pessimistic. Do the next
  measurement on an idle machine.
- The input is scripted (`ScriptedInput`, `TimedInput`): `poll` waits for its
  timeout like the terminal, but there is no crossterm.

| #  | Metric | Budget | Measured | Status |
| -- | ------ | ------ | -------- | ------ |
| B1 | Runtime cost per event, 10 000 keys queued (Counter, Headless, fps 60) | <= 10 us/event | 12.1 us/event (median of 4 rounds; best 11.5); 4 frames, 7 batches | **miss** |
| B2 | Fold + sources diff + `view` + equality, Dashboard change with an equal view | <= 50 us | 2.25 us/op, 3.0 KB/op | ok |
| B3 | One 120x40 frame on `TestBackend`: Counter / Todo (100 items) / Dashboard | <= 0.5 / 2.0 / 1.5 ms; <= 2 MiB per frame | 0.86 / 1.86 / 1.61 ms (errors +-0.4 / 0.3 / 0.7); 0.35 / 1.47 / 0.56 MiB | time: **miss** (Counter, Dashboard); allocation: ok |
| B4 | Key-to-flush latency, idle app (`InputPort.read` to `Backend.flush`) | p50 <= 1 ms, p99 <= 3 ms, plus one paint (Counter: 0.86 ms) | p50 1.56 ms, p99 3.91 ms, max 4.95 ms (300 keys) | p50 ok; p99 **miss** by 0.05 ms |
| B5 | Idle CPU, Counter, no sources | <= Kyo alone + 0.5 points; <= 8% | Counter 2.77% vs Kyo alone 2.57% (with the flags); 11.1% vs 11.0% with Kyo defaults | ok |
| B6 | Dashboard running (4 samples/s, about 1.4 log lines/s) | <= 2% of one core above idle; <= 15 MiB/s | 8.35% vs idle Counter 2.67%: 5.7 points above; 3.6 MiB/s | CPU: **miss**; allocation: ok |
| B7 | Quit latency (`q` to the end of the run), poll 25 ms | <= 60 ms | p50 34.4 ms, max 36.0 ms (20 rounds) | ok |
| B8 | Soak: Dashboard + one task per 100 ms, 600 s | heap within +-5%; sources and tasks bounded; threads constant | heap after GC 7.00 MB at 400 s, 7.00 MB at 610 s (+0.09%); 3 sources, at most 1 task; 18-19 threads; 6 027 tasks, 0 dropped | ok |

Notes:

- B1: the burst folds into 4 frames and 7 batches, as SPEC 7.2 C4 asks. The
  time includes the start, the first frame and the release of the run.
- B3: two views alternate, so each frame changes some cells. Most of the time
  is in jatatui (`Terminal.draw`: reset, paint, diff), as SPEC 7.1 expects
  (about 2 ms for a typical 120x40 frame on this machine).
- B4: the probe sends one key every 20 ms, so each key comes to an idle app and
  is drawn at once (SPEC 2.13 rule 5).
- B5: the scheduler flags take the idle cost of Kyo from 11% to 2.6% of one
  core on this machine; the Counter adds 0.2 points (the input thread polls 40
  times a second).
- B6: the miss is the first lead for a phase-2 item (SPEC 1.5). About 5.4
  frames are drawn each second (1.6 ms each, B3), which is about 0.9% of a core;
  the rest is not explained yet. Look at it with JFR on an idle machine.

### B8 soak

Every 10 s the probe forces a full GC and reads the heap, the threads and the
counters of the run. The heap stayed between 6.8 and 7.0 MB for the whole run
(the log pane fills up to 500 lines in the first 350 s). The thread count is 18,
and 19 for one sample: Kyo's scheduler adds and removes workers. Load average
during the soak: 5 to 9.

### Real terminal

Not measured: this environment has no terminal. Before a release, run the
Counter example in a real terminal and write `ps -o %cpu -p <pid>` after 30
seconds of idle time here (SPEC 7.4).
