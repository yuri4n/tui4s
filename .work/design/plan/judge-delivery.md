<!-- claude 2026-10-03: created this file (judge agent, lens "delivery risk / correctness"; no build, no checkout, no commit) -->
# Judge verdict: delivery risk and correctness

Status: DONE (2026-10-03). Planning only. I did no build, no checkout, no commit and no push. I read git refs with
`git show` / `git diff`, and the repo settings with one read-only `gh api` call.

Lens: does the plan find the real risks EARLY (settle, scheduler reflection, kyo-ui internals, R1/R2/R3, strict
flags on prototype code, the generator in sbt, kyo-ui transitive deps)? Does every inventory item, R1-R5, F1-F3 and
O1-O8 have a place? Does the plan keep the user decisions (DECISIONS rounds 1-5)?

Short names: DR = `draft-reviewable.md`. DK = `draft-risk.md`. RR, FR, IR = the runner, facade and integration
`REPORT.md` in `.work/proto/`. OS, RC, FC = the three inventories.

## 1. Result

| Draft | Score (0-10) | Short reason |
| --- | --- | --- |
| DK (risk first) | 8 | It settles the unknowns with 7 spikes and GO/SOFT/NO-GO gates BEFORE the bulk work. Its new facts are true (I checked them). Coverage is complete. Some gaps in the GitHub process and two fallbacks that need the user. |
| DR (reviewability first) | 6 | Best per-item placement and best process rules. But it finds most unknowns only inside the stack (PR 6 to PR 8), its R2 proof cannot fail, and it misses three verified build facts. |

WINNER: DK. The final plan must take the grafts in section 5.

## 2. Facts I checked

| Claim | Draft | Result | Evidence |
| --- | --- | --- | --- |
| `Scheduler.status()` is public and returns `currentWorkers` | DK F-1 | TRUE | `.work/scratch/kyo-src/kyo-scheduler/kyo/scheduler/Scheduler.scala:584`; `top/Status.scala:3-12` |
| `busyWorkers()` does not exist in RC5 | DK F-2 | TRUE | grep in `.work/scratch/kyo-src-rc5/.../Scheduler.scala`: no match; RC7 `Scheduler.scala:442` |
| No fatal-warning flag; only explicit nulls, format and the `EventMapping` `-Wconf ...:e` fail the build | DK F-3 | TRUE | `origin/chore/cleanup:build.sbt` (`scalacOptions`), `origin/feat/core-3-terminal:modules/core/build.sbt` |
| No `concurrentRestrictions`; sbt runs the forked test JVMs of all modules at once | DK F-5 | TRUE (no setting) | `origin/chore/cleanup:build.sbt` |
| The generated mirror needs the hand-written runtime | DK F-6 | TRUE | `proto/kyoui-facades/zio/generated/ZioUi.scala:8` imports `Bridge`; 14 `Bridge.action`, 12 `Bridge.handler` |
| `delete_branch_on_merge` is false, so GitHub does not retarget a stacked PR by itself | DR section 9 item 4 | TRUE | `gh api repos/yuri4n/tui4s` (read only) |
| `GuardTest` drops (RC-27 lists it as carried) | DR section 9 item 2 | TRUE | `origin/feat/core-3-terminal:modules/core/src/test/scala/tui4s/GuardTest.scala` tests `tui4s.Source` (old model) |
| A render-only first PR (C1) can compile without `World` | DR C1 | TRUE | `proto/kyoui-runner/src/tui4s/kyoui/internal/Layout.scala:27` (`Scene`), `Geometry.scala:7-45` (`Loc`, `Rect`, `Size`) |
| A kyo-ui upstream draft exists | both assume a reader or an "update" | FALSE: no file | `find .work -iname '*upstream*'` (outside `scratch/`): none |

## 3. The lens, risk by risk

| Risk | DR | DK | Better |
| --- | --- | --- | --- |
| Settle exactness (RR N2, O6) | Canary `totalLoad() >= 0`, glitch test, loud fallback in C2 (K-3). Tested only "alone on the machine". | S1: 20 000 events with no load, 2x2 contention and a CI model (3 JVMs on 2 CPUs); split count by `updates`; per region kind; gate G1 with a SOFT path (`awaitScreen`, `Tags.limit`). | DK |
| Scheduler reflection (RC 2.3, K5) | Keeps the reflection. Canary checks one field only. | Finds the public `status()` (F-1, true). Canary covers every probe call, also `busyWorkers()` (F-2, true). S1 measures the cost of `status()` (it takes a lock). | DK |
| kyo-ui internals and drift (RR O2, N9; RC-12) | Surface guard in C1-C3. No data on how fast kyo-ui changes. | Same guard, plus S3: diff of kyo-ui RC5-RC7 sources and a run of the guard on RC6 and RC7 (gate G3). K13: a new Kyo RC during the stack. | DK |
| R1 leak (IR "does not work" 1) | Run-owned `Scope` in C2 (watchers) and C4 (effects). Deterministic count of live watchers. No risk entry for the end order. | S2 + G2: leak probe, live-watcher count, AND an order test (no paint after the canvas closes; K4). Fallback option (b). | DK |
| R2 first-key race (IR "does not work" 2) | C2 test "200 starts with a write at once give 0 late frames". This test cannot fail: the plain Kyo path shows 0 of 500 with no load (IR table). The facade paths (ZIO 13 of 300) come only in F2 = PR 8. The "IR split probe before and after" needs the scala-cli prototype, and DR does not say how. | S2 runs the split probe on plain Kyo, ZIO glue and Cats glue (copies of the prototypes) before any PR. G2 SOFT and NO-GO paths are concrete. | DK |
| R3 output capture | T1, T2, F2, D; ZIO `Console` override first tested in F2. | P-b, P-zio; the ZIO `withConsole` override is probed in S7 with the IR output probe. | DK (earlier) |
| Strict flags, explicit nulls on prototype code (OS-39, RC-18) | K-1: C1 reports the real count; "never relax the flags". It does not see that warnings do not fail the build, so warnings can pile up while CI is green. | S6 + G6 counts errors and warnings, checks each `.nn` on a jatatui or JDK result against its source (a wrong `.nn` is a run-time NPE), compiles `KyoSignallingRef` against fs2 3.14.0. D-7 warning policy. | DK |
| Generator in sbt (FC summary 3, 5, 6, 7) | F1 = PR 7. Cats full-mirror pre-flight in F1 (good). Cost of `mirrorsCheck` first seen in PR 7. | S5 + G5: generator forked in sbt, ZIO full mirror byte-identical, first Cats full mirror compiled, cold/warm cost of `mirrorsCheck`, fail-closed proof. | DK |
| kyo-ui transitive deps (RC 3.1, +15.2 MB) | `-verbose:class` count in T2 = PR 6. | S4 + G4: class-load log, native libraries, first-frame time, an exclude test. | DK |
| Runner-mounted facade components (FC-17; FC summary 11: NOT prototyped) | No risk entry, no probe, no fallback. A failure in F2 (PR 8) changes the core key hook in C4 (PR 4) after 4 PRs sit on it. | S7 + G7 probes the key hook, the ZIO/Kyo scope link, quit as a value, the error channel and the FC-D8 `NotGiven` guard. | DK |
| CI contention (IR contention runs; RC 2.3 point 6) | K-5 detection is "3 local runs alone", which does not model CI. | F-5 + D-8 `Tags.limit(Tags.Test, 1)`; P-a acceptance has one of 3 runs under 2x2 contention. | DK |
| GitHub stack mechanics | `delete_branch_on_merge: false` (true), PD-5, merge commits, K-13, K-15 (keep `wip/handoff`). | Says "GitHub moves a stacked PR when its base is merged and deleted" but misses that deletion is off. | DR |
| Docs deploy (Vercel) | K-14 + PD-4. | K16 + F-11. | equal |
| Fix the bot findings with the code | P-6: own commit + regression test each (T1). | Placed in P-b; no commit rule. | DR |
| Rework when a review changes a low PR | Gates 1 and 2 at the API ripple points; WIP limit P-11; merge-up P-9. | One stop before the bulk; "the next PR's workflow can start while the user reviews". No WIP limit. | DR |

## 4. Coverage and user decisions

Coverage (both drafts): I listed OS-1..OS-50, RC-1..RC-36 and FC-1..FC-38 against each placement table. Each item
has a place in both. R1-R5, IR F1-F3, FR F1-F8, FR O1-O8, RR O1-O7 and N1-N10, the three bot comments and the 14 ZIO
findings have a place in both (DR section 4.4-4.6; DK section 6.4). DR has the finer grain (one row per item). DK
groups by PR, and the grouping is complete. Small errors: DK carries `GuardTest` in RC-27 (it must drop); DK's
"`git checkout <old ref> -- <path>`" does not work for prototype files (they live under `.work/proto/` on
`wip/handoff`, and their package changes): use `git show <ref>:<path> > <new path>` (DR section 3.1).

User decisions (DECISIONS rounds 1-5):

| Decision | DR | DK |
| --- | --- | --- |
| Kyo-free facades (round 5) | Yes: mirror, `SurfaceLeaks`, opaque event types, re-exports | Yes |
| Both component styles, runner allocates state at mount (round 5) | Yes, incl. facades (F2, F4) | Yes if G7 is GO. G7 NO-GO keeps the build-time `Component.mount` and no runner-mounted facade component: this is AGAINST round 5 and must be a user decision, not an automatic fallback |
| kyo-test for core, munit elsewhere (round 4) | Yes (PD-7) | Yes (D-26) |
| Testkit inside core (round 4) | Yes; test doubles move to test sources | Yes |
| Scheduler flags in `TuiApp`, opt-out property (round 4) | Yes (T2, F2, F4; `SchedulerDefaults` carried unchanged, it has `tui4s.schedulerDefaults`) | Yes (P-b, P-zio, P-cats) |
| Output capture on by default, opt-out (round 4) | Yes | Yes |
| Facade packages `tui4s.interop.zio` / `.cats` (round 4) | Yes | Yes |
| No release, no deploy, no posting without the user (rounds 3, 4; A2) | Yes | Yes |
| No `com.olvind.tui` on the class path (round 2) | Yes | Yes |
| Vendored Cats bridge keeps its Apache-2.0 attribution (round 2) | Not stated | Not stated (FC 2.3 says it) |
| Agent limits (<= 4, <= 2 build, one workflow, announce > 10) | Yes; W9 (14 agents) is flagged | Yes; spike workflow 5 agents, at most 3 at once, at most 2 build |

Also against the user's direction: DK G1 NO-GO changes the frame contract to "eventually consistent" and makes the
kits auto-wait by default. IR Q3 and FC section 3 keep the kit STRICT. This is a user decision (DK D-3 lists it; the
final plan must say "ask" at the gate, not "the plan changes").

## 5. Grafts the final plan must take

From DR into DK:

1. GitHub: `delete_branch_on_merge` is false (`gh api`). Add DR PD-5: the user turns on "Automatically delete head
   branches", or retargets each stacked PR by hand. Fix DK section 7 text.
2. DR P-6 to P-8 and P-12: fix each known bug in the PR that brings the code, in its own commit, with a regression
   test; commit map (carry, format, flag fixes, one behaviour change per commit); delta guide with
   `git diff <old ref>:<path> HEAD:<path>`; "Not in this PR" list.
3. DR gates and WIP limit: a user review gate after the core public API (`TuiOptions`, `Headless`, `Screen`,
   `FrameStats`, quit) and after the first generated mirror (the mirror IS the API); implementation at most 2 PRs
   ahead of the user's review (P-11).
4. Split DK P-a (about 4400 lines) on DR's verified lines when the user wants smaller PRs (DK D-35): render (Layout,
   Paint, Props, Geometry; no `World`), regions + `Headless` + R1/R2/settle, input + grapheme editing, components +
   key hook.
5. DR section 9 corrections: `GuardTest` drops; `RecordingControl` and `ScriptedInput` go to test sources (smaller
   binary surface, RC-36); carry prototype files with `git show ... >` (not `git checkout --`).
6. DR P-5 for OS-11: the grapheme editing replaces the UTF-16 editing in the carry PR; keep `wip/handoff` until the
   stack merges (DR K-15), because delta guides cite it.
7. DR T1: a LOCAL PTY run with a scratch launcher before the CI `terminal` job exists; the CI `terminal` job green
   3 times before review (DR K-7).

Fixes that neither draft has (new):

8. R2 proof must be able to fail. Add a test seam in `Mount.watch` that widens the window between the `current`
   read and the `nextWith` registration (or a handler with several async hops), so the core PR shows red before the
   fix and green after it, without the facades. Reason: plain Kyo shows 0 first-key splits in 500 starts with no load
   (IR "What does not work" 2 table).
9. Negative control for each regression test (R1, R2, the three bot comments): the PR body shows the test fails on
   the code before the fix. The IR used negative controls (`logs/negative-control-*.log`).
10. Validate the settle under BOTH scheduler configurations: Kyo defaults (a `Tui.run` user without `TuiApp`, and
    the test JVMs) and the `SchedulerDefaults` values (`cycleIntervalNs` 100 us -> 10 ms, regulator 10 ms -> 100 ms;
    `origin/feat/core-3-terminal:modules/core/src/main/scala/tui4s/spi/SchedulerDefaults.scala:22-27`), which
    `TuiApp` ships. Add this to S1 and to the glitch suite.
11. Mark the fallbacks of G1 NO-GO, G3 NO-GO (pin RC7) and G7 NO-GO as "ask the user" (they change DECISIONS round 5
    or the strict-kit choice of IR Q3).
12. DK D-16 (batch in the real session, one frame per key in `Headless`): the test kit then does not run the
    production path. Take DR PD-13 (one frame per event, measured by `BurstProbe`) unless a test drives the batched
    path through `InputPort` and `EventMapping`.
13. A zero-new-warnings rule for hand-written code (DK D-7 option b, or at least a warning count in each PR body),
    because the build has no fatal-warning flag (F-3) and green CI does not show warnings.
14. Keep the Apache-2.0 header and the "Changes from the original" list in the vendored `CatsBridge` and
    `AmbientDispatcher` (DECISIONS round 2; FC 2.3). The `feat/cats` code never had a review (task context: "Cats:
    review not started"), so the P-cats reviewers read it as new code, not as a carried delta.

## 6. Missing in both drafts

- A facade `Tui.run` cancel test. IR "What does not work" 1: "`Tui.run` has no workaround: a cancel of the ZIO or `F`
  that runs it interrupts the runner ... so it leaks too (not measured)". Both drafts test the leak on the core
  `Headless` only. Add: interrupt a ZIO `Tui.run` and cancel a Cats `Tui.run` on a headless control, then count live
  watcher fibers (P-zio, P-cats).
- A writer for the upstream draft. No draft file exists in `.work/`. DR expects the user to read it at gate 1; DK
  "updates" it. Assign one agent after the gates (with the S1/S3/S4 numbers); the user posts it, or nobody does (A2).
- The R2 red-before-green reproducer at core level (graft 8).
- Settle checks under the shipped scheduler flags (graft 10).
- Agent prompts must name the cloud toolchain. `.work/env.sh` is for the Mac (HANDOFF lines 6 and 14); in this
  container JDK 25, `sbt` and `scala-cli` are on the PATH (`/usr/local/bin`), and scala-cli needs `--jvm system`.
  An agent that follows the DECISIONS line "source .work/env.sh" sets a JAVA_HOME that does not exist here.
- Generated mirrors and the strict flags: the catch-all overloads have an unused `other` parameter (FC summary 7).
  Neither draft says which mechanism wins (`@nowarn` from the generator, or a `-Wconf` for `src/*/generated`).
  S5/F1 must choose one, and the drift check then pins it.

## 7. Progress log

- 2026-10-03: read both drafts, DECISIONS.md, the three inventories, and the report sections on findings, open items,
  "What carries", "Gaps for the real modules", and the skeptic results.
- 2026-10-03: checked the facts in section 2 (git refs, Kyo sources, prototype files, one read-only `gh api`).
- 2026-10-03: wrote the verdict. DONE.
