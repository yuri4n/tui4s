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
