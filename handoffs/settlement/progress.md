# Settlement work checkpoints

<!-- codex 2026-10-28: created: track small reviewable changes and their validation. -->
The [original handoff](README.md) records checkpoint 0961aaa. This page tracks
later changes. A passing checkpoint does not validate the whole loaded-input
fix. Keep [PR #58](https://github.com/yuri4n/tui4s/pull/58) in draft until its
remaining checks pass.

## Checkpoint 1: standalone layout ownership fix

<!-- codex 2026-10-28: created: record the first independently validated PR. -->
[PR #59](https://github.com/yuri4n/tui4s/pull/59) contains the layout fix on a
branch based on main. Its commit is
[4fc26d1](https://github.com/yuri4n/tui4s/commit/4fc26d13f21d7a5863c456182797606517f97d40).
It clears obsolete region ownership before it places any new item. This
includes text inside a new container. Later text changes can replace the leaf
without scanning the region map for removal.

<!-- codex 2026-10-28: created: state the standalone validation boundary. -->
The two regressions compare each incremental step with a full layout and
paint. They cover direct nested-region replacement and replacement through a
new wrapper, followed by text updates and later structural changes. All 13
focused layout checks passed. The full strict check and all eight published
binary compatibility checks also passed. These results validate the standalone
layout change. Hosted CI also passed, including Linux and macOS terminal
checks, browser tests, benchmarks, and packaging. These results do not
establish loaded-input latency.

## Checkpoint 2: layout fix in the loaded-input candidate

<!-- codex 2026-10-28: created: record the measured integration checkpoint and its validation boundary. -->
The integration branch is fix/loaded-input-layout, based on 0961aaa. It carries
the validated layout fix and its regressions alongside Local settlement,
background admission, and current-state reconciliation. The 32 focused
integration checks and launcher build passed. The full strict candidate check
and all eight binary checks against 0.1.0-RC1 also passed. The [manifest](evidence/layout-integration-manifest.json) records the
measured source hashes and report hashes.

<!-- codex 2026-10-28: created: retain every input deadline failure in the next checkpoint. -->
The same workload sends 60 keys at 10 keys/second with a 100 ms planned-send
deadline, unrelated CPU work, and continuous status updates.

| Regions | Diagnostics | Visible by watchdog | Missing | Deadline failures | p95 observed | Maximum observed |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 128 | Off | 60/60 | 0 | 16 | 134.2 ms | 273.5 ms |
| 128 | On | 60/60 | 0 | 39 | 1144.8 ms | 1266.3 ms |
| 512 | Off | 45/60 | 15 | 60 | 2462.9 ms | 2528.3 ms |
| 512 | On | 42/60 | 18 | 60 | 2493.8 ms | 2529.4 ms |

<!-- codex 2026-10-28: created: keep failed latency distinct from passing structural correctness. -->
All four runs failed the 100 ms target. The 128-region case also exceeded
250 ms, so that CI acceptance is not robustly established. All four runs had
zero incorrect input and graceful shutdown. Missing means no correct effect
before the watchdog; percentiles include observed effects only. See the
layout-prefixed reports beside the manifest. The layout fix does not establish
a complete input-latency fix.

<!-- codex 2026-10-28: created: select the next small change from existing profile evidence. -->
The full stacks of the saved 512-region profile attribute 149 of 1,679 CPU
samples to World.markRegion through Vector membership and path equality.
This is earlier-stage CPU evidence, not an allocation percentage for that
method. If new profiling confirms the cost, the next small candidate can
reuse the indexed path comparison for equal-depth paths and avoid copying an
unchanged World. Preserve first-seen order and distinguish equality from
ancestor membership. Measure before changing the data structure.

## Checkpoint 3: exact dirty-region membership

<!-- codex 2026-10-28: created: record the third measured change and its contract tests. -->
The branch perf/dirty-region-membership starts at e253fbd. World.markRegion
compares equal-depth path steps and retains the current world for an existing
mark. It keeps first-seen order and preserves other state. Ten focused tests
passed, including a generated sequence oracle and both ancestor orders. This
retains linear membership and quadratic worst-case total marking cost.

<!-- codex 2026-10-28: created: retain the first full-check failure and explicit canary scope. -->
The first full check failed the quiet settlement canary. That case inherited
the Local default while asserting the scheduler-probe contract. It now selects
Quiescent at 100 ms explicitly, matching the CPU-bound case. The assertions
are unchanged; production Local remains at 16 ms. The focused canary and
full strict rerun passed, including all eight binary compatibility checks.

<!-- codex 2026-10-28: created: preserve all third-checkpoint input failures. -->
The same 60-key, 10-key/second, continuous workload gives these results at a
100 ms planned-send deadline. The [manifest](evidence/marking-manifest.json)
records the source and report hashes.

| Regions | Diagnostics | Visible by watchdog | Missing | Deadline failures | p95 observed | Maximum observed |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 128 | Off | 60/60 | 0 | 3 | 97.6 ms | 231.8 ms |
| 128 | On | 60/60 | 0 | 30 | 1258.2 ms | 1464.4 ms |
| 512 | Off | 47/60 | 13 | 60 | 2266.0 ms | 2311.7 ms |
| 512 | On | 60/60 | 0 | 60 | 909.2 ms | 962.0 ms |

<!-- codex 2026-10-28: created: limit the performance claim to the recorded evidence. -->
These runs do not establish robust 100 ms or 250 ms acceptance. All four had
zero incorrect input and graceful shutdown. Missing input remains a failure.
Percentiles include observed effects only. The 128-region diagnostics-off
p95 is below 100 ms, but its maximum and the other runs remain too high.

<!-- codex 2026-10-28: created: distinguish sampled CPU attribution from latency or allocation gains. -->
A separate instrumented profile has 1,178 CPU samples. World.markRegion
appears in 80 samples and Laid.without in 12. The earlier profile had
149/1,679 and 272/1,679 respectively. Inclusive counts overlap and do not
establish throughput gains. The separate profile is not part of acceptance.
No matching JVM-wide pause explains its long dispatch backlog. GC peaked at
51 ms. Diagnostics use atomic snapshots; no shared exporter file-I/O lock
was found on the runner path.

<!-- codex 2026-10-28: created: bound the next concurrency hypothesis and its proof obligations. -->
The next small candidate can reduce the number of admission capacity waiters.
Currently each removal wakes every capacity waiter. Holding one async mutex
permit across the capacity wait can reduce this fanout. The consumer's wakeup
must stay independent. Snapshot the generation before checking closure on
every retry, then fail with Closed after shutdown. Preserve cancellation and
lazy message construction. Meter does not guarantee FIFO fairness; do not
claim starvation freedom or a latency fix before measurement.

## Checkpoint 4, rejected: one capacity waiter

<!-- cursor 2026-10-28: created: record the waiter result as a rejected draft. -->
Draft PR #62 held one admission permit across the capacity wait. Focused
concurrency checks passed. The loaded input runs did not. At 128 regions
with diagnostics off, one run missed 8 keys and exited 143. A repeat showed
every key and a 1.61 s p95. At 512 regions the p95 fell from about 2.3 s to
about 300–480 ms, and every key was still late against 100 ms. Thread dumps
showed Kyo workers in effect-remainder draining, with one busy worker. The
change stays a draft. It is not the base of the next fix.

## Checkpoint 5: sample reactive regions past 32 watchers

<!-- cursor 2026-10-28: created: state the sampling decision and the acceptance gate. -->
The branch `cursor/sample-reactive-regions-296f` starts at checkpoint 3.
The mount keeps a watcher for the first 32 reactive regions. Further reactive
regions are read in slices of 32 on background frames. An input frame still
reads the watched set and the focused region. A queued input paints before
the next background frame. The CI gate is 250 ms on the continuous
128-region workload with diagnostics off. ADR 0023 records the contract.

<!-- cursor 2026-10-28: created: record two 128-region passes and the remaining 512-region misses. -->
Local PTY runs on this branch, 60 keys at 10 per second, diagnostics off,
deadline 250 ms, busy continuous load:

| Regions | Visible | Missing | Deadline failures | p95 | Maximum | Graceful shutdown |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 128, run A | 60/60 | 0 | 0 | 48.6 ms | 113.8 ms | Yes |
| 128, run B | 60/60 | 0 | 0 | 52.2 ms | 91.7 ms | Yes |
| 512 | 43/60 | 17 | 19 | 184.7 ms | 302.9 ms | Yes |

The 128-region runs meet the 250 ms gate. The 512-region run is faster than
checkpoint 3 and still misses keys. It is not the CI gate.
