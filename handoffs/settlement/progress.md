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

## Checkpoint 4: one capacity waiter

<!-- codex 2026-10-29: created: record the admission experiment and its measured limits; codex 2026-10-29: changed: link its draft pull request. -->
The branch `perf/admission-capacity-waiter` is based on checkpoint 3 and is
[draft PR #62](https://github.com/yuri4n/tui4s/pull/62). The sender holds the async mutex permit while it waits for inbox capacity. This
leaves one sender waiting on the inbox generation. Other senders wait for the
mutex. The consumer still signals capacity without taking that mutex. Closure
ends the holder and queued senders. Cancellation releases the permit. The
mutex does not promise FIFO order.

<!-- codex 2026-10-29: created: report focused correctness checks without claiming a full candidate check. -->
The focused checks passed: Admission 10/10, Quit 6/6, Component 20/20, and the
example launchers built. The admission cases cover one capacity waiter,
producer cancellation, closure of queued senders, closure between rejection
and wait, lazy message construction, and capacity-one progress. **The full
strict check has not run on this checkpoint.** The earlier full check covered
checkpoint 3, not this mutex change.

<!-- codex 2026-10-29: created: preserve the diagnostics-off acceptance results. -->
The initial 128/512-region PTY runs fail the 100 ms deadline with
diagnostics both off and on. The two diagnostics-off runs are summarized
here.
Missing input and a failed shutdown also occurred at 128 regions. At 512
regions, all 60 inputs appeared but every input missed the deadline.

| Regions | Visible | Missing | Deadline failures, including missing | p95 of observed | Maximum observed | Graceful shutdown |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 128 | 52/60 | 8 | 22 | 274 ms | 382 ms | No |
| 512 | 60/60 | 0 | 60 | 296 ms | 341 ms | Yes |

<!-- codex 2026-10-29: created: separate diagnostic runs and repeated runs from acceptance. -->
A later diagnostics-off repeat at 128 regions showed 60/60 inputs but 37
missed deadlines; its p95 was 1.61 seconds and its maximum was 1.81 seconds.
The 512-region repeat showed 60/60 inputs, all late, with a 437 ms p95 and a
483 ms maximum. This variation does not show a stable improvement. The
`waiter-live-*` runs captured thread dumps during long delays and are
instrumented; they are not acceptance runs. Those dumps show one active Kyo
worker and other workers parked, with no all-workers-blocked condition. They
do not identify the cause of the queue backlog. Additional diagnostic-on
reports and every repeat are in the
[waiter evidence manifest](evidence/waiter-manifest.json).

<!-- codex 2026-10-29: created: state the next decision and release boundary. -->
This is an investigation checkpoint, not a fix or release candidate. The
single-waiter change passes focused concurrency checks, but the loaded-input
results do not meet latency acceptance and include a shutdown failure. Keep
this pull request in draft. Do not treat the tests as proof of fairness or
starvation freedom. The heap-capacity hypothesis remains unproven; the local
profile from checkpoint 3 found a small post-GC heap, which only describes
that run. Next work should identify the long runner delays in the no-diagnostics
case, then repeat the same external PTY endpoint before changing acceptance.
