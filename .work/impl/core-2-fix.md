# core-2 fix: review findings on feat/core-2-engine

Worktree: .work/worktrees/core, branch feat/core-2-engine (HEAD 8b952d8 at start).
Agent: fixer. Started 2026-10-01.

## Findings to address
spec-1..7, api-1..6, runtime-1..8 (duplicates: spec-1 = api-2 = runtime-2; spec-2 = api-1; spec-6 = runtime-5;
spec-7 = api-5; spec-5 = runtime-6 (part); api-3 = runtime-1).

## Progress
- re-checked every finding in the code: all real. Plan:
  * settle/advance (spec-1, api-2, runtime-2, runtime-3): Probe(promise, after: Long) completes at the first
    decision with more than `after` app batches (-1: its own decision). settle = loop { plain probe; read a
    snapshot (batches, sleepers, busy tasks, ended); done when no task runs; else race(awaitPendingSleepers
    (sleepers + busy), a news probe after the snapshot's batches) }; "parked" + no new batch after one more
    probe = done. A task parked on the virtual clock counts as idle. advance = parked; { advance 1 ms;
    parked; settle }; parked re-reads the counts on news (no stale count). No lost wake-up: the news probe
    carries the batch count that the test fiber saw.
  * spec-2/api-1: LiveRun reads the run result after stop: an app failure fails `live`; LiveTest.error.
  * spec-3: observers publish engine.state at the end (LiveView.state, handle states).
  * spec-4: Supervisor.sync keeps the declared order (rebuild the LinkedHashMap when the order changed).
  * spec-5/runtime-6: Launcher.startOne in a bracket (an interrupt before the registration interrupts the
    child); TokenEmitter panics on Closed (a lost body ends); Session.open returns Either from one defer.
  * spec-6/runtime-5: InputThread reports Failed with putFiber.
  * spec-7/api-5: LiveRun.typeText maps keys with Key.sent.
  * api-3/runtime-1: startUnscoped starts the run fiber in a bracket; an interrupted wait interrupts the run.
  * api-4: no handle without a state: startOrFail (typed, live kit) / startUnscoped panics with the TuiError.
  * api-6: emit's "does not fit" only when the CCE names the emitted value's class; else AppFailed.
  * runtime-4: Session.close survives an InterruptedException in the join; flag set again after leave.
  * runtime-7: EventCode only when tracing (or in the failure message).
  * runtime-8: RecordingControl.screen = snapshot taken at flush on the render fiber.
- 15:12 all fixes written; new tests: LiveKitTest (+7), RuntimeTest (+4), SessionTest (new, 3), SourceTest (+2).
  Targeted run green (after fixing my RecordingControl test: '+' and 'q' fold in one batch, Quit draws no frame).
- 15:25 regression check: with the fixes reverted by hand (12 small reverts), every new test fails (7 live
  tests time out, the rest fail); restored the fixes from a backup (diff identical).
- 15:30 settle inside advance uses advance's `sleepers` count; docs (TuiTest.live, Headless.start,
  Tui.startWith). `sbt -batch fmt` + `sbt -batch check` green (25 suites, all leaves).
- 15:40 split into two commits; the first state (runtime fixes, old test kit) compiled and its new tests
  passed. Seen flaky under load (load avg ~12, a VTDecoder process at 300% CPU), not caused by the fixes:
  RuntimeContractTest C4/C7 and TraceTest "first line" fail when a cold JVM writes a "slow" line (> 16.6 ms
  for the first view/update/sources). Pre-existing timing contracts.
  COMMITS: 707bc13 core: no fiber, thread or terminal left behind at the edges of a run
           (second) testkit: settle and advance under virtual time; live reports app failures
- 15:52 `sbt -batch check` after the commits: everything green except the timing flakes above (C4 "slow"
  line twice in 2 runs). Proof that they are pre-existing: the original PR sources (8b952d8, copied into
  the tree for one run, then restored with `git checkout HEAD`) fail C4 and C7 in 2 of 2 runs at load ~16.
- 16:05 the timing flakes failed `check` 3 times in a row (load ~12-16). Added a warm-up (same code once
  before the timed part) to C4, C7 and the TraceTest start-line test; cold `testOnly` green 2 of 2.
  `sbt -batch fmt` + `sbt -batch check` GREEN (25 suites, 196 leaves, versionPolicyCheck ok).
  COMMIT 5a441a5 test(core): warm the JVM before the timed slow-line checks.

## Commits on feat/core-2-engine (this fix round)
- 707bc13 core: no fiber, thread or terminal left behind at the edges of a run
- 49264b1 testkit: settle and advance under virtual time; live reports app failures
- 5a441a5 test(core): warm the JVM before the timed slow-line checks

## Deviations / decisions to tell the user
- settle (SPEC 6.2 says "repeats while a one-shot task is running") now also treats a task body that is
  asleep on the virtual clock as idle (counted: parked sleepers >= every/poll/stream + running tasks).
  It still waits for standing tasks (declared deviation kept). Limit: another body that sleeps on the clock
  (a general source) can make settle return before a busy task ends; advance passes its `sleepers` count.
- advance waits for "parked" before AND after each 1 ms step (SPEC: before), so a fold happens at the exact
  virtual time of its source (no 1 ms skew).
- No handle without a state (api-4): startOrFail (internal, typed); startUnscoped / Tui.startWith /
  Headless.start keep their SPEC signatures and panic with the TuiError when the run ends before its first
  frame. The facades (Z2/K2) may use startOrFail to map it into their error channel.
- api-6 heuristic: "does not fit" only when the CCE message starts with "class <emitted value's class>
  cannot be cast"; an inner cast from the same class is still reported as "does not fit".
- Residual windows (not closable with Kyo's public API): an interrupt after the handle is handed out but
  before the caller registers its own stop; awaitPendingSleepers has one waiter slot (a race loser that
  wakes in the same instant could overwrite the next waiter; a news probe still wakes it).
