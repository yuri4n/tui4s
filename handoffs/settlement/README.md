# Settlement and loaded-input handoff

<!-- codex 2026-10-28: created: preserve the implementation, evidence, limits, and next steps. -->
This is a work-in-progress checkpoint for [draft PR #58](https://github.com/yuri4n/tui4s/pull/58).
The goal is correct, responsive typing while unrelated scheduler work,
reactive notifications, and status updates continue. The private application
is unavailable. The owner offered to rerun diagnostics when a candidate is ready.

<!-- codex 2026-10-28: created: distinguish the saved code from tested snapshots. -->
**The fix is not release-ready.** The 512-region workload still fails.
The last edit in [Layout.scala](../../modules/runner/src/main/scala/tui4s/internal/Layout.scala)
has not been compiled, tested, or measured. No regression test for that edit
was added before this handoff. All performance results below exclude it.

## Revisions and files

<!-- codex 2026-10-28: created: pin the two earlier checkpoints. -->
- Main at the investigation start: [339836b](https://github.com/yuri4n/tui4s/commit/339836bcee5d704add5b3295167e60fbdbf0192b).
  It includes optional diagnostics and the first PTY latency harness.
- Parent of this checkpoint: [69851e9](https://github.com/yuri4n/tui4s/commit/69851e9e53414abfeec93fb16861916d5f409251).
  It bounds drains and shares settlement time. It keeps Quiescent at 100 ms.
  Its full check passed, but loaded typing still failed.
- This checkpoint saves the later source changes and this handoff on
  the PR branch, fix/settlement-progress. The local development branch was
  fix/loaded-input in /workspace/tui4s-loaded-input.
- The [evidence manifest](evidence/manifest.json) identifies the measured
  stage and records hashes. The raw reports use generated fixture input.

## Implementation saved here

<!-- codex 2026-10-28: created: record the completion-policy changes. -->
- [Settlement](../../modules/runner/src/main/scala/tui4s/internal/Settlement.scala)
  uses pure decisions and a shared active budget. Drains consume at most
  64 messages. Pending component waits have a separate allowance.
- [Settle.Local](../../modules/runner/src/main/scala/tui4s/spi/Settle.scala)
  is the new default: 16 ms active maximum, 1 ms quiet window, two empty
  observations. It does not inspect global scheduler idleness.
  Quiescent and Hops remain available.
- These are cooperative bounds. User callbacks, signal reads, verification,
  rendering, terminal writes, and scheduler pauses can exceed them.

<!-- codex 2026-10-28: created: record admission ownership and cancellation constraints. -->
- [BackgroundAdmission](../../modules/runner/src/main/scala/tui4s/internal/BackgroundAdmission.scala)
  limits background offers when inbox occupancy reaches one quarter of its
  capacity, capped at 64. It leaves room for input in normal capacities.
  It cannot reserve a separate input slot at capacity one.
- The run owns the gate. Removal and shutdown wake waiters.
  A waiter's cancellation must not cancel another waiter's promise.
  Nine targeted tests cover capacity, races, cancellation, closure, and
  deferred message construction. Hot/cold producer fairness still needs review.
- The original three-argument Mount constructor and its default getter
  remain. Actual final binary compatibility checks are still required.

<!-- codex 2026-10-28: created: record the intentional latest-state contract. -->
- [Mount](../../modules/runner/src/main/scala/tui4s/internal/Mount.scala)
  posts generation-tagged Changed notices for signals. The runner reads
  current state when it consumes a notice. A delayed notice cannot replay
  an older captured UI. Component first-content messages still carry values.
- [Runner](../../modules/runner/src/main/scala/tui4s/internal/Runner.scala)
  pulls current reactive values before an input frame paints. It visits a
  finite snapshot of paths and generations, parents first. It skips
  descendants whose generation changed. Fresh mounts read their own values.
- Intermediate signal states can coalesce. This is an intentional behavior
  change. FIFO inbox admission does not imply strict cross-kind application
  order: settlement already defers input while it applies notices.
- ComponentTest now separates an unobserved show/hide transition from an
  actual mount that must release its pending wait and effect scope.
  Do not restore the old timing sleeps or weaken cleanup checks.

<!-- codex 2026-10-28: created: identify tested and untested optimizations. -->
- Runner skips remount scans only when both old and new content are plain
  Text. Component-to-text transitions still use normal cleanup.
- [Geometry](../../modules/runner/src/main/scala/tui4s/internal/Geometry.scala)
  compares path prefixes by index. It avoids iterator allocation while
  retaining the same ancestor relation.
- **Untested last edit:** Layout clears old ownership when a new Text path
  appears, then replaces later text nodes without a whole-map removal.
  A simple text-to-text shortcut alone was unsafe: flattened nested
  reactive regions can leave stale regionOwner entries beneath a text path.
  Prove the new ownership invariant before keeping this optimization.

## Validation actually completed

<!-- codex 2026-10-28: created: preserve stage-specific validation boundaries. -->
| Evidence | Result | Scope |
| --- | --- | --- |
| [prefix-build.txt](evidence/prefix-build.txt) | LocPropertyTest 4/4; SignalSettlementTest 4/4; launchers built | Includes text remount shortcut and indexed prefixes. Excludes final Layout edit. |
| [widgets.txt](evidence/widgets.txt) | Component 20/20; Dashboard 11/11; Gallery 35/35; fixture 10/10 | Earlier Local/admission/current-state stage. |
| [focused4.txt](evidence/focused4.txt) | Admission 9, settlement 10, progress 3, options 9, diagnostics 3, Apps 14, Quit 6, Settle 3 passed | Invocation failed on a ComponentTest assertion. That case was later rewritten for the new contract; Component 20/20 then passed. |
| Existing harness tests | 20/20 | Tests deadline accounting, missing input, and failed shutdown cases. |
| Version/changelog/edge scripts | 70 cases / 21 cases / edge check passed | Earlier candidate stage. |
| Final full check, all MiMa checks, full PTY kit, docs build | **Not completed for this checkpoint** | Earlier parent results do not validate the later candidate. |

<!-- codex 2026-10-28: created: retain the unresolved earlier terminal assertion. -->
At parent 69851e9, the full PTY kit passed 73/73 terminal checks and 90/91
screen checks. Dashboard Space-on-tab selection failed. Keep that assertion
and investigate it. Do not report this checkpoint's full PTY suite as passing.

## Latest measured performance

<!-- codex 2026-10-28: created: state workload, endpoints, and machine limits. -->
The following reports measure the stage before the final Layout edit.
Each run sends 60 keys independently at 10 keys/second. The workload combines
unrelated CPU work, continuous notifications, and the stated region count.
The host had four effective CPUs, 16 GiB memory, and JDK 25.0.5.

<!-- codex 2026-10-28: created: retain missing input and full deadline failures. -->
| Regions | Diagnostics | Visible by watchdog | Missing | Deadline failures at 100 ms | p95 observed | Maximum observed |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| [128](evidence/prefix-fanout128-off.json) | Off | 60/60 | 0 | 11 | 190.5 ms | 251.6 ms |
| [128](evidence/prefix-fanout128-on.json) | On | 60/60 | 0 | 20 | 181.1 ms | 225.1 ms |
| [512](evidence/prefix-fanout512-off.json) | Off | 38/60 | 22 | 60 | 3126.6 ms | 3185.4 ms |
| [512](evidence/prefix-fanout512-on.json) | On | 46/60 | 14 | 60 | 2410.7 ms | 2499.3 ms |

<!-- codex 2026-10-28: created: prevent a passing-percentile or shutdown overclaim. -->
All four reports have zero incorrect input and graceful shutdown with restored
terminal state. Missing means no correct effect before the watchdog. It does
not mean permanent loss. Percentiles and maxima include observed effects only.
All four fail the 100 ms deadline. Even 128-region acceptance at the CI
threshold of 250 ms is not robustly passing. Do not relax the threshold.

<!-- codex 2026-10-28: created: distinguish internal timings from visible input. -->
The acceptance clock starts at each planned send and ends at the decoded PTY
screen with the correct native input field. Sender drift remains visible.
Internal diagnostic completion ends at the runner frame pass. It does not
prove that text appeared. Do not substitute layout-only timing, completed-only
averages, or FrameStats.nanos for the external endpoint.

<!-- codex 2026-10-28: created: record the bounded profiling conclusion. -->
Profiling an earlier 512-region stage found repeated path comparisons and
region-map scans. Vector iterators accounted for about one third of sampled
allocation pressure. GC pauses peaked at 42.4 ms; retained heap after the
last collection was about 23 MB. This does not support a full-heap explanation
for that local run. It says nothing conclusive about the private application.

## Resume in this order

<!-- codex 2026-10-28: created: make the first next step concrete. -->
1. Review the last Layout edit. Add the missing RelayoutTest for nested
   Reactive/Fragment content becoming Text, then changing Text again.
   Compare regionOwner, layers, layout, and paint with a full layout.
   Include later structural replacement so stale ownership cannot hide.
   Drop or revise the optimization if its invariant does not hold.
2. Run focused layout/property tests and rebuild launchers. Then repeat
   128- and 512-region PTY runs, with diagnostics off and on.
   Run each scenario alone. Keep all missing, wrong, late, and shutdown
   failures in acceptance.
3. If settlement/verification remains slow, profile the current candidate.
   World.markRegion uses linear Vector membership and may cause repeated
   path equality work. This is a hypothesis, not a measured attribution.
   Also check background producer fairness and Armed/remount churn.
4. Run the quiet, busy, paced-update, combined, and continuous-fanout matrix.
   Run the full strict check, all eight published compatibility baselines,
   full PTY kit, and docs production build. Fix failures without weakening
   assertions or redefining latency.
5. Update the PR and release notes around the final measured behavior.
   Only then prepare a release candidate and a small diagnostic rerun request.
   The user does not need to obtain the private repository or wait for its
   metrics before local development continues.

## Commands and environment

<!-- codex 2026-10-28: created: provide portable reproduction commands. -->
Use JDK 25. Initialize the pinned submodule. Use only one sbt process across
all worktrees and build roots. The repository's vendored functional-design
skills remain the design guide. Prefer Scala CLI for new repository scripts.

~~~sh
git submodule update --init --recursive
SBT_TPOLECAT_CI=1 sbt fmt \
  'runner/testOnly *LocPropertyTest *RelayoutTest' \
  'core/testOnly *SignalSettlementTest *ComponentTest' \
  'examples/testOnly *DashboardSuite *GallerySuite *SettlementSuite' \
  examples/launchers

python3 examples/ptycheck/latency.py \
  --rate 10 --keys 60 --deadline-ms 100 \
  --require-updates --require-load-running \
  --output /tmp/fanout128.json \
  --command examples/target/bin/settlement \
  --busy --continuous --regions=128 --duration=60
~~~

<!-- codex 2026-10-28: created: prevent another falsely diagnosed run. -->
The PTY session removes JAVA_TOOL_OPTIONS and JDK_JAVA_OPTIONS.
Enable diagnostics through JAVA_OPTS instead. Give each process a separate
output path in an existing writable directory.

~~~sh
JAVA_OPTS="-Dtui4s.diagnostics.file=/tmp/fanout128-diagnostics.json" \
python3 examples/ptycheck/latency.py \
  --rate 10 --keys 60 --deadline-ms 100 \
  --require-updates --require-load-running \
  --output /tmp/fanout128-on.json \
  --command examples/target/bin/settlement \
  --busy --continuous --regions=128 --duration=60
~~~

<!-- codex 2026-10-28: created: retain compatibility and environmental caveats. -->
Run the complete strict check with SBT_TPOLECAT_CI=1 sbt fmt check.
Compatibility must use the actual published 0.1.0-RC1 artifacts for all eight
checks. Adding Local also affects exhaustive source matches; an older compiled
match can throw MatchError when it receives the new default.
MiMa alone does not prove source or behavior compatibility.

<!-- codex 2026-10-28: created: preserve the container-specific process-test workaround. -->
This container's PID 1 does not reap orphan processes. The existing process
descendant test required running sbt beneath a temporary child subreaper
(PR_SET_CHILD_SUBREAPER). Do not alter the test or production cleanup to hide
zombies caused by the container. The earlier full check passed with that
environment workaround.

<!-- codex 2026-10-28: created: distinguish local scratch from repository evidence. -->
The original workspace also has /workspace/work/loaded-input-validation,
the prior /workspace/work/settlement-fix-validation, and archives under
/workspace/output. They are optional local scratch. The linked evidence here
is committed so the handoff does not depend on those directories.
The scratch experiment-index.json is stale. The scratch heavy-on.json was
actually diagnostics off. Historical experiments include shutdown failures;
do not generalize the latest four clean shutdowns to every experiment.

## Release boundary

<!-- codex 2026-10-28: created: preserve release authorization and remaining quota constraints. -->
No release is approved by this handoff. Keep PR #58 in draft.
Maven Central publication was approaching the organization's monthly release
limit; the user was handling the support request. Verify current quota and
workflow state before any later release. Do not approve the maven-central
environment, push release tags, or publish from this checkpoint.
