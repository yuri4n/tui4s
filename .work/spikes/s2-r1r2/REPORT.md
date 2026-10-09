<!-- claude 2026-10-09: created this file from the structured output of W0 (wf_bf024cec-c9f); the subagent harness refuses report files -->

This is the report of agent A. It covers S1 and S2. The corrections of agent M are in `.work/design/plan/gates.md` ("Corrections").

<!-- file: .work/spikes/s1-settle/REPORT.md -->
# S1 settle: report

Agent A, W0, 2026-10-09. This text uses ASD-STE100. The numbers are provisional. Agent M must run the gate again alone (section 8).

## 1. Verdict

**G1: NO-GO** by the gate words. The result is near the limit. ASK THE USER (D-17 d). See section 7.

- No probe gets 0 split frames under load. This is also true for the reflection probe of the prototype.
- The public `Scheduler.status()` probe on a quiet machine (run 5):
  - Kyo default scheduler values: 0 splits with no load. 1.25 per 10 000 events under the 2x2 load. 0.42 per 10 000 under the CI model. 1.25 is more than 1, thus NO-GO.
  - tui4s scheduler values (`SchedulerDefaults` of PR 5): 0, 0.50 and 0.58 per 10 000. These numbers meet SOFT.
- The 9 prototype tests under the CI model with `status()`: 5 of 5 with each config.
- The cost of `status()`: the settle p50 is 37 to 77 us more than with the reflection probe. The limit is +50 us.

## 2. What I did

- Base: a scala-cli copy of `.work/proto/kyoui-runner` in `runner/`. No file of `.work/proto/` changed.
- Changed files in the copy:
  - `src/tui4s/kyoui/internal/SchedulerProbe.scala`: the probe modes, and a timer for each probe call.
  - `src/tui4s/kyoui/internal/Runner.scala`: a hook at the settle start; a flag for the final observation; the pull check; the timeout flag.
  - `src/tui4s/kyoui/Tui.scala`: `TuiOptions.pullCheck`; `FrameStats.settleTimedOut` and `FrameStats.pulled`.
  - `src/tui4s/kyoui/internal/World.scala`: two fields.
  - New: `dev/SettleProbe.scala` (the glitch probe).
- The probe app: one key writes 4 refs. 6 regions depend on the key: 4 `Reactive` spans (`a.map`, `b.map`, `a.combineLatest(b).map`, `a.map`), 1 bound input (`value(t)`) and 1 `Foreach`. A frame is split for a kind when the screen after the key's frame does not show the new value of that kind.
- Probe modes (system property `tui4s.probe`):

| Mode | What it reads | Reflection |
| --- | --- | --- |
| `reflect` | The prototype: `loadAvg * currentWorkers` (private field) and `busyWorkers()` | yes |
| `status` | `Scheduler.status()` at each observation: the sum of worker loads, the workers with a load | no |
| `statusCw` | `status().currentWorkers` one time per settle, then `loadAvg * cw` and `busyWorkers()` | no |
| `statusRun` | `statusCw`, then one `status()` to confirm: no other worker is in state Running | no |
| `mounted` | `busyWorkers()` at each observation; at the final observation one `status()`: no other worker has a mounted thread or a load | no |

- Loads:
  - `none`: one JVM.
  - `2x2`: the JVM and 2 busy loops on CPUs 2,3; `-XX:ActiveProcessorCount=4` (RR N1).
  - `ci3` (the CI model): 3 probe JVMs at the same time on CPUs 0,1 (`taskset -c 0,1`).
- Configs: `kyo` = the Kyo defaults. `tui4s` = `cycleIntervalNs` 10 ms, `blockingMonitorIntervalNs` 20 ms, `concurrencyCollectIntervalMs` 100.
- Each cell: 20 000 events without the pull check, then 20 000 events with it, in one JVM. A `ci3` cell has 3 JVMs, thus 60 000 events per pull mode.
- The settle is `Settle.Quiescent(100 ms, confirm = 2)` in all runs.
- Item 6: the 9 prototype tests, 5 runs, under the CI model. The test JVM and 2 settle-probe JVMs run on CPUs 0,1.
- Each log has `uptime` and `nproc` at the start and at the end. Each run folder has `others.log`: every 10 s, it records the load and the JVMs of other agents.

## 3. Runs and their quality

| Run | Folder (under `logs/`) | Time | Other agents | Use |
| --- | --- | --- | --- | --- |
| 1, 2 | `run1-loaded`, `run2` | 20:00-20:42 | B, C and E built (the earlier W0 run) | Provisional only |
| 3 | `run3-quiet` | 21:32-21:54 | Agent E's S3 guard JVMs in 41 of 126 samples (21:35-21:50) | No-load cells only; `statusCw` and `statusRun` |
| 4 | `run4-stopped` | 21:54 | Agent E started S4 timing. I stopped run 4. | None |
| 5 | `run5-quiet` | 22:02-22:17 | None (0 samples) | The main numbers |
| Tests | `tests5-quiet` | 22:18-22:29 | None | Item 6 |
| Tests | `tests-loaded` | 20:42-20:49 | B, C and E | Provisional only |

## 4. Results (run 5, quiet machine)

### 4.1 Split frames

Source: `logs/run5-quiet/glitch-summary.txt` (one row per pull mode). The cell logs are `logs/run5-quiet/glitch-<load>-<cfg>-<probe>[-jN].log`. The table adds the two pull modes. The value in brackets is per 10 000 events.

| Load | Config | `reflect` | `status` | `mounted` |
| --- | --- | --- | --- | --- |
| none (40 000 events) | kyo | 5 (1.25) | 0 | 0 |
| none | tui4s | 1 (0.25) | 0 | 0 |
| 2x2 (40 000) | kyo | 6 (1.50) | 5 (1.25) | 25 (6.25) |
| 2x2 | tui4s | 4 (1.00) | 2 (0.50) | 5 (1.25) |
| ci3 (120 000) | kyo | 33 (2.75) | 5 (0.42) | 45 (3.75) |
| ci3 | tui4s | 0 | 7 (0.58) | 0 |

- The spread between runs is larger than the difference between probes. Example: ci3, kyo, `status`: run 3 had 61 splits in 120 000 events (agent E's JVMs ran for part of it). Run 5 had 5.
- Run 3, no-load cells (clean): `statusCw` had 6 splits in 20 000 events (kyo, pull off; all three kinds). `statusRun` had 0. Source: `logs/run3-quiet/glitch-summary.txt`.
- The earlier loaded runs had 3 to 18 splits per 10 000 in ci3 with the kyo config, and 0 to 1.83 with the tui4s config (`logs/run1-loaded/glitch-summary.txt`, `logs/run2/glitch-summary.txt`).

### 4.2 Settle timeouts

Source: `logs/run5-quiet/glitch-summary.txt`.

- No load: 0 in all cells.
- 2x2: 0 to 7 per 40 000 events per probe and config. 13 of the 47 splits under 2x2 came with a timeout (`status`: 5 of 7).
- ci3: 23 to 33 per 120 000 events. 17 of the 90 splits under ci3 came with a timeout.

### 4.3 Kinds and the pull check

Source: the run-5 logs, added over the probes and the configs.

| Load | Pull check | Events | Reactive | Bound | Foreach | Pulled frames |
| --- | --- | --- | --- | --- | --- | --- |
| none | off | 120 000 | 5 | 5 | 5 | 0 |
| none | on | 120 000 | 1 | 0 | 0 | 0 |
| 2x2 | off | 120 000 | 5 | 1 | 0 | 0 |
| 2x2 | on | 120 000 | 41 | 0 | 0 | 5 |
| ci3 | off | 360 000 | 55 | 3 | 4 | 0 |
| ci3 | on | 360 000 | 33 | 0 | 0 | 7 |

- The pull check removes all bound and `Foreach` splits.
- `Reactive` splits stay. A `Reactive` value is a `UI` with closures, so the check cannot compare it.
- The value 41 includes one burst cell (2x2, kyo, `mounted`: 24).

### 4.4 Cost (no load)

Settle p50 in us, pull off / pull on. Sources: `logs/run5-quiet/glitch-none-*.log`, `logs/run3-quiet/glitch-none-*.log`.

| Config, run | `reflect` | `status` | `mounted` | `statusCw` | `statusRun` |
| --- | --- | --- | --- | --- | --- |
| kyo, run 5 | 180 / 162 | 232 / 220 (+52 / +58) | 205 / 194 (+25 / +32) | - | - |
| tui4s, run 5 | 171 / 155 | 213 / 192 (+42 / +37) | 203 / 200 (+32 / +45) | - | - |
| kyo, run 3 | 168 / 161 | 245 / 221 (+77 / +60) | - | 201 / 184 (+33 / +23) | 231 / 214 (+63 / +53) |
| tui4s, run 3 | 163 / 155 | 214 / 213 (+51 / +58) | - | 202 / 185 (+39 / +30) | 235 / 221 (+72 / +66) |

- One probe call: `reflect` 57 to 103 ns. `status` 10.1 to 12.8 us. One `status()` per settle (`statusCw`) 17 to 22 us.
- Why `status()` is slow: `Worker.status()` calls `Thread.getStackTrace()` on each mounted worker thread (kyo-scheduler `Worker.scala:448-456`).

### 4.5 The 9 tests under the CI model (item 6)

Source: `logs/tests5-quiet/tests-ci-summary.txt` and `logs/tests5-quiet/tests-ci-<cfg>-<probe>-<n>.log`.

| Probe | Config | Green runs | Failed tests |
| --- | --- | --- | --- |
| `status` | kyo | 5 of 5 | none |
| `status` | tui4s | 5 of 5 | none |
| `mounted` | tui4s | 3 of 5 | dashboard (run 3), glitch-free (run 4) |
| `reflect` (prototype) | kyo | 4 of 5 | dashboard and glitch-free (run 4) |

- The loaded runs (`logs/tests-loaded`): `status` kyo 4 of 5, `status` tui4s 4 of 5. The dashboard test failed each time.
- The dashboard test reads the next frame after a background write. The glitch-free test counts frames with fewer than 4 updates in 500 events.

## 5. Why no probe gets 0 (Kyo 1.0.0-RC7 source)

1. The load counters do not see all tasks.
   - `Worker.load()` is `queue.size()`, plus 1 when `currentTask` is set (`Worker.scala:178-183`).
   - The worker takes a task with `queue.poll()` before `runTask` sets `currentTask` (`Worker.scala:325`, `:415`).
   - A preempted task can stay in a local variable of `Worker.run` (`Worker.scala:353-366`).
   - The scheduler drains the queue of a stalled worker to other workers (`Worker.drain`).
   - In these places a task is in no counter. When the OS stops the thread there (3 JVMs on 2 CPUs), the gap is some milliseconds long. Two observations one hop apart do not cover it.
   - The `mounted` mode closes the first two gaps at the final observation. It does not close the drain. Under 2x2 it was not better than `status`.
2. Kyo signals lose wakeups.
   - `observe` reads `current`, runs the callback, and then registers `nextWith` (`Signal.scala:101-107`; Kyo documents this window).
   - `combineLatest` re-arms with `awaitAny` after it reads both values (`Signal.scala:220-224`).
   - The probe handler writes `a`, then `b`. Another worker can run the woken watcher between the two writes. Then `b` can change before the re-arm.
   - Then nothing is pending in the scheduler, so no probe can see it. The next event or the 1 s repair timer corrects the screen.
   - Run 5, ci3, kyo config: 18 of 25 (`reflect`) and 23 of 26 (`mounted`) logged split details are this case. The log pattern is `Rs:6`: all 6 regions updated, but the sum region shows an intermediate value.
3. Timeouts. The settle max (100 ms) runs out under load (4.2). The frame then shows the old value.
4. The scheduler values. Kyo's default `cycleIntervalNs` is 100 us. The scheduler cycle finds stalled workers and drains them. With 10 ms (the tui4s values), the ci3 splits drop: `reflect` 2.75 to 0, `mounted` 3.75 to 0 per 10 000.
5. `statusCw` is not safe. It reads `currentWorkers` one time per settle, but the regulator changes it during a settle. Run 3: 6 splits with no load (4.1).

## 6. Gate G1

| Condition | Result | Source |
| --- | --- | --- |
| GO: 0 splits with `status()` in all runs and both configs | No: 5 and 2 (2x2), 5 and 7 (ci3) | `run5-quiet/glitch-summary.txt` |
| GO: `status()` costs at most +50 us p50 | No in 6 of 8 cells: +37 to +77 us | `run5-quiet`, `run3-quiet`, `glitch-none-*.log` |
| GO: tests 5 of 5 | Yes with `status()`, both configs | `tests5-quiet` |
| SOFT: 0 splits with no load | Yes with `status()` and `mounted` (runs 3 and 5). No with `reflect` | `run5-quiet`, `run3-quiet` |
| SOFT: at most 1 per 10 000 under load | tui4s config: yes (0.50, 0.58). kyo config: no (2x2: 1.25) | `run5-quiet` |
| NO-GO: more than 1 per 10 000 under load | kyo config, 2x2: 1.25 (5 of 40 000; 3 with a timeout) | `run5-quiet` |
| NO-GO: a test failure under the CI model | None with `status()` on the quiet machine. 1 of 5 in each loaded batch | `tests5-quiet`, `tests-loaded` |

Result: NO-GO with the Kyo default values. SOFT with the tui4s values. GO is not possible with a public probe on Kyo RC7 (section 5).

## 7. ASK THE USER (D-17 d)

The user must choose one option:

- (c, with conditions) Accept the SOFT level:
  - C2 uses the public `status()` probe with `confirm = 2`. No reflection.
  - tui4s sets its scheduler values in `TuiApp` and in the forked test JVM (`javaOptions`). The docs say that `Tui.run` without these values gives more split frames.
  - C2 adds the pull check for bound and `Foreach` regions.
  - The kit stays strict, and gets `awaitScreen(predicate, timeout)` (D-17 c). The dashboard and glitch-free tests use it or get an allowance.
  - The cost is about +40 to +60 us per frame.
- (d) An "eventually consistent within `max`" frame contract with auto-wait kits. Or a pinned Kyo, with an upstream change as a blocker for 1.0.

For each option, the upstream draft gets the two causes of section 5. The upstream ask: an exact count of pending tasks, or a batch for signal writes (the watchers see the batch after it ends).

## 8. Run it again (agent M, alone on the machine)

- All of G1, about 23 min: `RUN=m1 bash .work/spikes/s1-settle/scripts/gate.sh`. It runs the matrix (probes `reflect`, `status`, `mounted`; loads none, 2x2, ci3; configs kyo, tui4s; 20 000 events per cell and pull mode). Then it runs the 9 tests 5 times for `status` kyo, `status` tui4s and `reflect` kyo.
- Smaller: `RUN=m1 N=5000 RUNS=3 bash .work/spikes/s1-settle/scripts/gate.sh`.
- One cell: `bash .work/spikes/s1-settle/scripts/glitch.sh <none|2x2|ci3> <kyo|tui4s> <reflect|status|mounted|statusCw|statusRun> [events]`.
- The table of a run folder: `python3 .work/spikes/s1-settle/scripts/summary.py .work/spikes/s1-settle/logs/<run>`.
- Read `logs/<run>/others.log` first. A line with `others=1` or more shows a JVM of another agent. A line with only `scala-cli.bin --power config` or `java -version` is the scala-cli wrapper of this run.

## 9. Limits

- I did not reduce a size. Each cell has 20 000 events per pull mode, as the plan says.
- The probe app has one shape (4 writes, 6 regions). Its `combineLatest` region causes the lost-wakeup case. An app without derived signals of two refs gets fewer splits.
- The modes `mounted` and `statusRun` are my additions. The plan does not name them.
- The counts per cell are small (0 to 45). The spread between runs is large.

## 10. Files

- `runner/`: the scala-cli copy of the runner; `runner/dev/SettleProbe.scala`.
- `scripts/`: `common.sh`, `glitch.sh` (one cell), `run-all.sh` (the matrix), `gate.sh` (all of G1), `tests-ci.sh` (item 6), `summary.py`, `others.sh` (the load watcher).
- `logs/`: see section 3. `logs/smoke/` has functional checks (not timing).

<!-- file: .work/spikes/s2-r1r2/REPORT.md -->
# S2 R1 and R2: report

Agent A, W0, 2026-10-09. This text uses ASD-STE100. Part 4 (facade cancel) is not in W0. It moved to SZ (plan section 0.2).

## 1. Verdict

- **Part 1, R1: NO-GO for option (a)** (a `Scope` that the run owns, `Fiber.init`).
  - The release waits 1401 ms for a CPU-bound watcher.
  - The run scope keeps 4.7 KB per remount until the run ends.
  - Option (b) (the plan's fallback: the last world in an `AtomicReference`, `unmountAll` in `Sync.ensure`) passes all R1 checks. The user does not have to answer.
- **Parts 2 and 3, R2: GO with a changed D-16 (a)** ("armkey", section 4.3).
  - D-16 (a) as the plan writes it passes only 7 of 10 and 3 of 10 seam rounds (two runs).
  - armkey passes 10 of 10. It has 0 first-key splits in all 12 split cases.
- **New risk** (not in the plan): Kyo RC7 `Signal.observe` keeps about 1.7 KB per value change on the watcher fiber, for the life of the watcher. With a 48 MB heap, one watcher stops with `OutOfMemoryError` before 30 000 changes (section 6).

## 2. What I did

- Base: scala-cli copies of `.work/proto/kyoui-runner` (`runner-src/`, `runner-apps/`, `runner-test/`) and `.work/proto/kyoui-integration` (`integration/`, `facades/`). No file of `.work/proto/` changed.
- `TuiOptions.r1` (default from `-Dtui4s.r1`):
  - `unscoped`: the prototype.
  - `scope`: option (a). Each watcher, component effect and hook body is a `Fiber.init` in a `Scope` that the run owns.
  - `ensure`: option (b).
- `TuiOptions.r2` (default from `-Dtui4s.r2`): `off` (the prototype), `step` (D-16 a as written), `armed`, `armmsg`, `armkey` (section 4).
- `TuiOptions.seamDelay`: the R2 seam. The watcher callback sleeps after its FIRST value. This makes the window between `current` and `nextWith` of `observe` wider.
- Gauges: a weak set of the run's fibers (the live-fiber count); flush counters on the canvas. The canvas "closes" in a `Sync.ensure` around the run, where `Tui.run` restores the terminal.
- Tests: `core/test/S2.test.scala` (R1 live fibers, R1 order, R2 seam, R2 external write, R1 growth). `core/test/S2Debug.test.scala` (the frames after a key and after an external write).
- Probes: `probes/ScopeGrowth.scala`, `probes/RaceGrowth.scala`, `probes/ObserveBound.scala`.
- The spike adds about 210 lines to the runner copy (all modes, comments and gauges). The chosen form (ensure and armkey) is about +80 to +100 main lines in C2 (estimate).

## 3. Part 1: R1

Source: `logs/run2-quiet/` (quiet machine, 22:29-22:46, 0 samples with other JVMs), unless the text names another log.

### 3.1 Leak per run (300 runs; `leak-<mode>-300.log`)

| Phase | unscoped (prototype) | scope (a) | ensure (b) |
| --- | --- | --- | --- |
| Kyo: interrupt (the Scope ends) | 1762.9 KB | 0.1 KB | 0.3 KB |
| Kyo: quit, then the Scope ends | 6.4 | 7.2 | 7.0 |
| ZIO glue: the Scope ends | 2.4 | 0.7 | 0.7 |
| ZIO glue: Ctrl-C quit | -0.6 | -0.1 | -0.3 |
| Cats glue: the Resource ends | 12.8 | 3.6 | 3.7 |

Live fibers after the last interrupted run: 1 (unscoped), 0 (a), 0 (b). The earlier, loaded run gives the same values (`logs/run1-loaded/leak-all.log`).

### 3.2 Live fibers after one run (`suites-ensure-armkey-1.log`, lines `r1-live`)

- 15 fibers start (5 keys).
- After an interrupt: unscoped 6 live, and not 0 after 2 s. Option (a): 0 at once. Option (b): 0 at once.
- After a quit: 0 in all modes.

### 3.3 End order (`suites-ensure-armkey-1.log`, lines `r1-order`)

- Flushes after the canvas closes: 0 in all 18 cases.
- A watcher parked in a 5 s callback: no mode waits for it.
- A watcher in a 1.5 s CPU-bound `map`:

| Mode | Interrupt: ms to canvas close / to the caller's scope end | Quit: ms to canvas close / to the scope end |
| --- | --- | --- |
| unscoped | 0 / 0 | 100 / 103 |
| scope (a) | 0 / 1401 | 1401 / 1406 |
| ensure (b) | 0 / 0 | 100 / 103 |

- The 100 ms is the settle max of the last frame: the busy watcher keeps a worker busy. The prototype has the same 100 ms.
- The cause (kyo-core `Fiber.scala:129-149`): the release of `Fiber.init` interrupts the fiber, and then waits for its result (`Async.useResult`). A CPU-bound fiber sees the interrupt only when it yields.

### 3.4 Growth in one run (20 000 keys; each key remounts one nested region; lines `r1-growth`)

| Mode | Bytes per key |
| --- | --- |
| unscoped | 10 335 |
| scope (a) | 15 106 |
| ensure (b) | 10 728 |

- Option (a) keeps 4.7 KB more per remount. Each `Fiber.init` adds one `acquireRelease` entry to the run scope. The entry holds the fiber until the run ends.
- The 10.3 KB in all modes is mostly the observe leak of section 6: 5 to 6 watchers get a new value per key.

## 4. Part 2: R2

### 4.1 The seam reproducer (seam 300 ms; the key at once after the start; 10 rounds per mode)

Sources: `logs/run2-quiet/suites-ensure-armkey-1.log` (lines `r2-seam`); the earlier run `logs/run1-loaded/suites-scope-armmsg-1.log`.

| Mode | Key frame right (quiet run) | Key frame right (earlier run) | When the frame is wrong |
| --- | --- | --- | --- |
| off | 0 of 10 | 0 of 10 | the screen is right at 1300-1305 ms |
| step (D-16 a as written) | 7 of 10 | 3 of 10 | the screen is right at about 1300 ms |
| armed | 10 of 10 | 10 of 10 | - |
| armmsg | 10 of 10 | 10 of 10 | - |
| armkey | 10 of 10 | not run | - |

- RED without the fix in 10 of 10 rounds, in both runs (K23 is met).
- Why "step" fails: the first value of each new watcher comes as its own message, thus as its own step. After two steps the new regions are not "young". The key's verify pass then skips them (`r2-frames-1.log`: step, key frame, updates=1, verified=0).

### 4.2 The cascade of "armed" and "armmsg" (`logs/run2-quiet/r2-frames-1.log`)

- "armed" adds the regions whose watcher has not returned from its first callback (an "unarmed" region) to the verify pass of EVERY step.
- A `Reactive` value holds closures, so it never compares equal. Each verify re-renders the region. The re-render mounts new nested watchers. Their first values make the next steps.
- Frames in 500 ms after one key (seam 300 ms): armed 204, armmsg 320, armkey 1.
- Frames in 400 ms after an external write: armed 34, armmsg 48, armkey 1.

### 4.3 armkey: the recommended form of D-16 (a)

- After each settle, the verify pass reads `current` of the regions mounted in this step or the step before (D-16 a).
- In an Input step (a key or a resize), the verify pass also reads the unarmed regions.
- The watcher posts `Msg.Armed(region, gen)` when its first callback returns. The runner then verifies that region one time.
- Results:
  - The key frame is right in 10 of 10 rounds.
  - After an external write in the window, the screen is right at arm time: 5 of 5 within 300 ms (`suites-ensure-armkey-1.log`, `r2-external`). In `r2-frames-1.log`: one frame, 106 ms after the write, when the seam ends.
  - One extra frame per new `Reactive` region.

## 5. Part 3: the split probe (the first key after the start; ZIO glue, Cats glue, plain Kyo)

Source: `logs/run2-quiet/split-<load>-ensure-<mode>-<rounds>.log` (r1 = ensure). The earlier run (r1 = scope, loaded): `logs/run1-loaded/split-*.log`.

| Mode | No load (6 cases x 1000) | 2x2 (6 cases x 600) |
| --- | --- | --- |
| off | 5 (ZIO first key 2, Cats steady 1, Kyo first key 1, Kyo hop 1) | 18 (ZIO first key 16, Cats first key 2) |
| step | 0 | 0 |
| armkey | 0 | 0 |

- The earlier run: off 6 and 20; step 0 and 0; armmsg 0 and 0.
- The 1 s repair timer or a later frame repaired all prototype splits. None was lost.

## 6. New: the observe leak (Kyo 1.0.0-RC7)

| Probe | Result | Log (under `logs/`) |
| --- | --- | --- |
| One `observe` watcher, 100 000 writes, `-Xmx48m` | +1.7 KB per write, linear. `OutOfMemoryError` after 20 000 writes, before 30 000 (estimate: about 26 000) | `observe-bound-observe-100000-48m.log` |
| One fiber, 100 000 `Async.race(sleep 0, sleep 1 s)` | +540 to 720 B per race while the fiber lives. The heap goes back to 4.7 MB when the fiber ends | `observe-bound-race-100000-48m.log` |
| A `currentWith`/`nextWith` loop, no race, no Scope | Flat: at most 14 B per write over 100 000 writes | `observe-bound-ownloop-100000-48m.log` |
| The same loop; the writer waits for each value | It stops: a write in the window is lost forever (no repair timer) | `observe-bound-ownloop-100000-48m-stalled.log` |
| Earlier: ScopeGrowth (with and without a Scope), RaceGrowth | 1.7 to 2.1 KB per write; 365 B per race | `run1-loaded/scope-growth-*.log`, `run1-loaded/race-growth-1.log` |

- `observe` runs one race per value (`holdUntilChanged`, `Signal.scala:135-138`). A race in a long-lived fiber keeps memory until the fiber ends. A runner watcher lives for the whole run.
- Example: 5 regions that change 10 times per second keep about 300 MB per hour.
- The plan has OS-35 (no race on a long-lived Kyo promise, for the facades). It has no risk for the watchers of the runner.
- Proposal: a new risk K29. C2 must choose one of:
  1. An upstream fix: a race must remove its entries when it ends.
  2. A runner watcher loop without a race (`currentWith`/`nextWith`), and a repair from the runner side. Example: a ticker fiber that runs `Async.sleep` in a loop (it keeps no memory: `run1-loaded/race-growth-1.log`, case I) and posts a repair message. A `Reactive` region then needs a value compare, which the runner does not have now.
  3. Accept the leak, and write it in the docs.

## 7. The 9 tests under the CI model (the S1 model)

Source: `logs/run2-quiet/tests-ci-ensure-<mode>-<n>.log`.

- ensure and armkey: 3 of 5. The glitch-free test failed in runs 1 and 4.
- ensure and off: 5 of 5.
- The failed frames have `verified = 0`: the verify pass changed nothing. Their settles ended after 11.7, 15.3 and 1.3 ms with 2 or 3 of 4 regions. This is the S1 settle problem: this copy uses the prototype's reflection probe (S1: 4 of 5 with it).
- Normal runs (`suites-ensure-armkey-1.log`): the 9 runner tests and the 12 integration tests are green with ensure and armkey.

## 8. Gate G2

| Condition | Result | Source |
| --- | --- | --- |
| At most 10 KB per interrupted run | (a) 0.1 KB; (b) 0.3 KB (the prototype: 1762.9 KB) | `leak-*-300.log` |
| 0 live watchers | (a) 0; (b) 0 | `suites-ensure-armkey-1.log` (`r1-live`), `leak-*-300.log` |
| Order test green | (a) no: it waits 1401 ms for a CPU-bound watcher. (b) yes | `suites-ensure-armkey-1.log` (`r1-order`) |
| Seam test red, then green | Red 10 of 10. Green 10 of 10 with armkey. D-16 (a) as written: 3 and 7 of 10 | `suites-ensure-armkey-1.log` (`r2-seam`) |
| 0 first-key splits in all runs | armkey and step: 0 in all 12 cases | `split-*.log` |

Result: R1 is NO-GO for option (a); option (b) passes. R2 is GO with armkey.

## 9. What the plan must change

- C2 and C4 (RC-8): use R1 option (b), not option (a).
- T1: the `Session` release order follows option (b). First restore the terminal, then do the runner's `unmountAll` in `Sync.ensure`. It does not wait for watchers.
- RC-9 and D-16 (a): use armkey (4.3). Keep the seam test (red in 10 of 10 rounds without the fix). Add a cascade test: one key in the seam window makes at most 2 frames.
- Add the new risk K29 (section 6) at gate 0, and put it in the upstream draft.
- The flaky tests under the CI model are an S1 matter (D-17).

## 10. Run it again

- All of S2 parts 1 to 3, about 16 min alone: `RUN=m1 bash .work/spikes/s2-r1r2/scripts/run-all.sh`. It needs the S1 class path: run the S1 scripts one time first. Smaller: `RUN=m1 LEAK=150 NOLOAD=500 LOADED=300 CIRUNS=3 bash .work/spikes/s2-r1r2/scripts/run-all.sh`.
- The observe leak (not a timing test): `bash .work/spikes/s2-r1r2/scripts/observe-bound.sh "observe ownloop race" 100000 48m`.
- The R2 frame counts only: `RUN=m1 bash .work/spikes/s2-r1r2/scripts/r2-frames.sh`.
- Read `logs/<run>/others.log` first (as in S1).

## 11. Limits

- The S2 copy uses the prototype probe (`reflect`). The R1 and R2 numbers do not depend on the probe. The test runs under the CI model do (section 7).
- The heap numbers are not timing numbers. The loaded run and the quiet run agree.
- The first observe-bound try (200 000 writes, 64 MB heap) was stopped. Its writer wrote in the R2 window, so each write waited for the 1 s repair. I added a 200 us pause after each seen value, and I used 100 000 writes with a 48 MB heap.
- I did not reduce a gate size (300 leak runs; 1000 and 600 split rounds; 10 seam rounds; 5 test runs).

## 12. Files

- `runner-src/`, `runner-apps/`, `runner-test/`: the runner copy with the R1 and R2 modes.
- `core/`: `project.scala`, `test/S2.test.scala`, `test/S2Debug.test.scala`.
- `integration/`, `facades/`: the integration copy (glue, stress probes `LeakProbe`, `SplitProbe`).
- `probes/`: `ScopeGrowth.scala`, `RaceGrowth.scala`, `ObserveBound.scala`.
- `scripts/`: `common.sh`, `run-all.sh`, `suites.sh`, `r2-frames.sh`, `leak.sh`, `split.sh`, `tests-ci.sh`, `observe-bound.sh`.
- `logs/run2-quiet/` (the main numbers), `logs/run1-loaded/` (the earlier run), and the observe-bound and frame logs at the top of `logs/`.
