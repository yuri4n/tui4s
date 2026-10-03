# Plan draft: risk first (tui4s as a terminal backend for kyo-ui)

<!-- claude 2026-10-03: created this file (planning agent "risk-first plan"; no build, no checkout, no commit) -->

Status: DONE (2026-10-03). Planning only. I did no build, no checkout, no commit, no push and no GitHub write.
Angle: find the unknowns that can stop or change the design. Settle them with short spikes and explicit gates
BEFORE the bulk of the work. Then do the bulk in a stack of small, ordered PRs.

How to read this file:

- Gate words: GO = the gate passes; the plan continues as written. SOFT = the gate passes with a known limit; the
  plan adds the change that the gate names. NO-GO = the gate fails; the plan uses the fallback that the gate names.
- ASSUMED = a working assumption that the user accepted with "I like it, go ahead". The user must still confirm it
  (A1-A4, section 10.5).
- Ids: OS-* = `inventory-old-stack.md`; RC-* = `inventory-runner.md`; FC-* = `inventory-facades.md`. RR, FR, IR = the
  runner, facade and integration `REPORT.md` in `.work/proto/`. R1-R5 and F1-F3 = IR "What must change". FR O1-O8,
  RR O1-O7 and N1-N10 = the report findings. F-* = new facts (section 2). K-* = risks (section 3). S-* = spikes,
  G-* = gates (section 4). P-* = PRs (section 5). D-* = decisions (section 10).
- Git refs are `origin/<branch>`. Kyo source on this machine: `.work/scratch/kyo-src` (RC7; RR "Independent
  verification" confirms that its class layout agrees with the RC7 jars), `.work/scratch/kyo-src-rc5`, `kyo-src-rc4`.

---

## 1. The plan in short

1. Run ONE spike workflow first: 5 agents, at most 3 at once, at most 2 that build. No PR, no push. Seven spikes
   answer the questions that can stop or change the design:
   S1 settle exactness and the scheduler reflection; S2 the R1 leak and R2 first-key fixes; S3 kyo-ui drift across
   versions; S4 kyo-ui dependency weight; S5 the generator inside sbt; S6 the strict flags on the prototype code;
   S7 runner-mounted facade components. At the end, one agent re-measures all timing gates on a quiet machine and
   writes `.work/design/plan/gates.md`.
2. The user reads `gates.md` and answers the decisions (section 10). This is the one stop before the bulk work.
3. Then nine PRs in one stack. Each one is green with `sbt check` before review:
   P-a runner core, P-b terminal and app, P-c examples/ptycheck/bench, P-gen codegen, P-zio, P-cats, P-layout, then
   PR 2 (docs) by a merge-up, then P-rules (AGENTS.md, README). One workflow per PR, 8 agents or fewer.
4. Old PRs: the user closes PRs 5, 4 and 3. No branch is deleted, rebased or force-pushed (option A of OS-46).
   PR 1 merges first. PR 2 stays open and gets the code stack by a merge commit, not by a rebase.
5. The spikes leave guards in CI, so the next Kyo bump shows the same risks early: settle canary, kyo-ui surface
   guard, glitch and first-key tests, mirror drift check, fail-closed generator, surface leak check.

No gate outcome changes the PR order. A NO-GO changes the content and the size of P-a, P-zio and P-cats only.

---

## 2. New facts from this pass

I checked the inventories where they looked wrong. These facts are new, or they correct an inventory.

| Id | Fact | Evidence | Effect on the plan |
| --- | --- | --- | --- |
| F-1 | The scheduler has a PUBLIC way to read `currentWorkers`. `Scheduler.status()` returns `kyo.scheduler.top.Status(currentWorkers, allocatedWorkers, loadAvg, ..., workers: Seq[WorkerStatus])`, and each `WorkerStatus` has `load`. The same fields exist in RC5. The runner inventory (RC 2.3) calls `status()` "debug only". | `kyo-src/kyo-scheduler/kyo/scheduler/top/Status.scala:3-12`; `Scheduler.scala:584-605`; same `Status` in `kyo-src-rc5` | The reflection in `SchedulerProbe.scala:17-22` can go. `status()` allocates and calls `ThreadPoolExecutor.getActiveCount()` (it takes a lock), so S1 measures its cost per settle. |
| F-2 | Kyo moves its scheduler internals between RCs. `busyWorkers()`, which the probe calls, does not exist in RC5; it is new in RC7. `Scheduler.scala` RC5 -> RC7: 194 diff lines; `Worker.scala`: 125. The private field `currentWorkers` kept its name, and the `loadAvg` body did not change. | `grep busyWorkers` in `kyo-src-rc5/.../Scheduler.scala` (no match); `kyo-src/.../Scheduler.scala:442`; `diff ... \| wc -l` | K2 is real. The settle canary (RC-11) must test every probe call, not only the private field. |
| F-3 | The build has NO fatal-warning flag. `-Wunused:all`, `-Wvalue-discard` and `-Wnonunit-statement` give warnings, and `sbt check` stays green with warnings. Only `-Yexplicit-nulls` (type errors), `scalafmtCheckAll` (in the `check` alias) and the `EventMapping` `-Wconf ...:e` rule fail the build. | `build.sbt`, `project/plugins.sbt` at `chore/cleanup` and at `feat/core-3-terminal`; PR 5 `modules/core/build.sbt` | The "strict flags" risk (OS-39, RC-18) is a hard gate for nulls and format only. Scala 3 has a per-file escape: `import scala.language.unsafeNulls`. A zero-warning rule is a policy choice (D-7). |
| F-4 | Derived Kyo signals compute `current` by reading their sources synchronously: `map`, `switchMap`, `zip`, `combineLatest` and the `All` forms. So a pull of `current` right after a handler sees every write that the handler made. But the runner compares the VALUE of a `Reactive` region, which is a `UI`, and two renders of a UI with a handler are never equal (closures). For bound refs and `Foreach` item lists the values are plain data and compare well. | `kyo-core/kyo/Signal.scala:160-224`; `proto/kyoui-runner/.../internal/Runner.scala` `apply` (`r.content == content`); FR "Results" 8 | A pull check can make frames EXACT for bound and list regions with no scheduler read. For `Reactive` regions it gives false changes. S1 counts the split frames per region kind. |
| F-5 | CI is a CPU-contention case. `build.sbt` sets no `concurrentRestrictions`, so sbt runs the forked test JVMs of core, zio, cats and examples at the same time. The repo is private (DECISIONS round 3); GitHub documents fewer vCPUs for the standard runners of private repositories (TO VERIFY with `nproc` in a CI log). CPU contention raised the first-key splits from 2 of 500 to 13 of 300. The prototype runner tests never ran in CI. | `build.sbt` at `chore/cleanup`; IR "What does not work" 2 | S1 tests under a CI-like load. P-a likely needs `Tags.limit(Tags.Test, 1)` (D-8). |
| F-6 | The generated mirror needs the hand-written runtime. `ZioUi.scala` imports `tui4s.interop.zio.ui.internal.Bridge` and uses `Bridge.action` (14 times), `Bridge.handler` (12) and `SignalRef`. | `proto/kyoui-facades/zio/generated/ZioUi.scala:8` | Correction to FC 5.2/5.3: P-gen cannot commit a mirror into `modules/zio` or `modules/cats`, because the module would not compile. P-gen ships the generator with golden tests. P-zio and P-cats add their mirror targets. |
| F-7 | The real-terminal gate needs an app. The runner inventory puts all apps in (c) but the PTY gate in (b) (RC section 6). | `inventory-runner.md` section 6 | Correction: P-b carries the PTY kit, the launchers and ONE app (the Counter), so its terminal code has a real-terminal gate. P-c adds the other apps and cases. |
| F-8 | The runner copies kyo-ui SEMANTICS, not code. Of 135 long lines in `Dispatch.scala`, 0 are verbatim in kyo-ui. `Mount.scala`: 4 of 84; `Props.scala`: 1 of 127 (one-line pattern cases). | Line match against `.work/scratch/kyo-src/kyo-ui` (this pass, Python) | License risk K15 is low. One comment line ("the dispatch rules follow kyo-ui, Apache-2.0") is enough. |
| F-9 | kyo-ui has five releases (RC3-RC7). RC7 is the newest in the cache (metadata of 2026-09-28). No older kyo-ui source is on this machine, so the drift rate of the copied semantics is UNKNOWN. | `/root/.cache/coursier/v1/.../io/getkyo/kyo-ui_3/maven-metadata.xml`; `ls .work/scratch` | S3 downloads the RC5-RC7 sources and measures the drift. |
| F-10 | Two remote branches are in no inventory: `chore/cleanup-7p2rll` (the same commit as `chore/cleanup`, 6f70179) and `first-take` (05599dd, 2025-11-09, the user's first work, an ancestor of `chore/cleanup`). | `git log`, `git merge-base --is-ancestor` | Do not touch them. |
| F-11 | `docs.yml` says "Vercel deploys the site (project Root Directory = docs)". If the Vercel Git link is on, a merge to `main` that touches `docs/` can start a production deploy. | `origin/docs/docus:.github/workflows/docs.yml` | The user checks the Vercel link before the docs merge (K16). Agents never merge. |
| F-12 | The code of PRs 3-5 has no `// claude <date>` marks. The runner prototype has 11. | `git grep 'claude 2026' origin/feat/core-3-terminal` (0 matches); `grep -rc` in `proto/kyoui-runner/src` | P-a removes the marks. The git history keeps the authorship. |
| F-13 | `tasty-query` 1.9.0 is a test dependency of core at PR 5 and of the facade tests, and the codegen project needs it. AGENTS.md: a version that more than one module uses goes in `project/Versions.scala`. | PR 5 `modules/core/build.sbt`; FC 1.1 | P-a adds `Versions.tastyQuery`. kyo-ui needs no constant of its own: it is released with Kyo (the same numbers RC3-RC7), so it uses `Versions.kyo`. |

---

## 3. Risk register

L = likelihood, I = impact (H high, M medium, L low). "Detect early" says how the plan finds the risk before the
bulk work, or which guard finds it later.

| Id | Risk | L | I | Evidence | Detect early | Fallback |
| --- | --- | --- | --- | --- | --- | --- |
| K1 | The settle is not exact: a frame can miss a consequence (split frame). Tests that read the screen right after a key become flaky, and the rule "green before review" then blocks PRs. | M | H | RR N2 (probe races in the worker loop); RR "refuted" 3 (one settle timed out per contention log); IR contention run 1 (a Cats test failed once) | S1, gate G1; glitch test in P-a | Auto-wait assertions in the kits; frame contract "eventually consistent within `max`"; upstream rank 1 becomes a 1.0 blocker |
| K2 | A new Kyo RC changes the scheduler internals; the probe falls back in silence (more splits, no error). | H | M | F-2; `SchedulerProbe.scala:12, 40` | S1 (public `status()` path); settle canary in P-a (RC-11) | Pin Kyo; each bump is its own PR with the canary |
| K3 | The R2 cause is a hypothesis. The fix (settle after the mount, verify young regions) may not remove the 1 s first-key delay. A 1 s lag on the first key is VISIBLE to users. | M | H | RC 2.1 "R2, more analysis"; IR split probe | S2, gate G2 | Short repair interval for young regions; auto-wait in kits; a time-boxed hunt for the cause |
| K4 | The R1 fix (a run-owned `Scope`) changes the end order: a watcher paints after the canvas is gone, or the release waits on a watcher. | M | M | RC-8 risk column; RC-21 (release order) | S2 leak probe and order test, gate G2 | RC 2.1 option (b): the last world in an `AtomicRef`, `unmountAll` in `Sync.ensure` |
| K5 | kyo-ui changes the AST, `Attrs`, `Style.Prop` or its dispatch rules between versions. The wildcard matches of the runner ignore the change in silence, and the copied semantics drift. | M (rate unknown) | H | RC 2.4 (K1-K9); F-9 | S3, gate G3; RC-12 guard in P-a | Pin the exact RC; a bump checklist; the runner SPI (upstream rank 2) moves up |
| K6 | kyo-ui adds about 15.2 MB and kyo-http, kyo-net, kyo-ffi. They can load classes or native code at start and slow the first frame. | L | M | RC 3.1 (8 jars); RR "Real PTY run" (first frame 0.8-0.9 s) | S4, gate G4 | Accept and document; the module split (upstream rank 7) moves up |
| K7 | The generator does not run well inside sbt (Scala 2.12 meta-build, class path, stack size, time added to `sbt check`). The Cats FULL mirror was never generated. | M | M | FC summary 3 and 5; FR "Measurements" | S5, gate G5 | Fork the run; drift check as a CI step; a smaller Cats mirror |
| K8 | `-Yexplicit-nulls` and scalafmt on 2257 prototype lines need more than mechanical edits. fs2 3.14.0 breaks `KyoSignallingRef` (it was compiled with fs2 3.12.2 only). | M | M | RC 3.2 (71 Java-API lines); F-3; FC summary 16 | S6, gate G6 | `import scala.language.unsafeNulls` per jatatui-facing file; pin fs2 |
| K9 | Runner-mounted facade components (FC-17) are not prototyped. The core key hook or the link from the ZIO `Scope` to the Kyo `Scope` may not work. | M | M | FC summary 11 and 12 | S7, gate G7 | No runner-mounted facade components in the first release |
| K10 | The test kits stall on virtual time (Kyo `Clock.withTimeControl`, ZIO `TestClock`): `awaitEnd`, `pendingTimeout` and inline handlers wait for a clock that nothing moves. | M | M | RC 3.3; OS-31 (ZIO api-1, runtime-3) | Kit tests with virtual time in P-a and P-zio | A real-clock option in the kit; a documented rule |
| K11 | CI contention (F-5) makes the settle-sensitive suites slow or flaky. | M | H | F-5; RC 2.3 point 6 | S1 CI-like run; the first CI runs of P-a | `Tags.limit(Tags.Test, 1)`; suites run in sequence |
| K12 | Facade opaque types erase to kyo-ui classes, so after a release every Kyo bump is a binary break for zio and cats. | H (after a release) | L now | FC summary 8 | `versionPolicyCheck` after the first release | `Compatibility.None` on each Kyo bump (AGENTS.md) |
| K13 | A new Kyo RC (RC8 or 1.0.0) comes out during the rework and changes kyo-ui or `Signal`. | M | M | F-2; F-9 | S3 measures the churn; the CI guards | Finish the stack on RC7; bump in its own PR after P-cats |
| K14 | Review load: P-a and P-b are L (about 4000 lines each). | H | M | Old PR 3: +4871, PR 5: +5041 (OS section 0) | - | A commit order that separates moves, format and logic; optional split of P-a (D-35) |
| K15 | License: copied kyo-ui code needs attribution. | L | L | F-8 (semantics only) | - | One comment line |
| K16 | A docs merge deploys the site through the Vercel Git link. | M | M | F-11 | The user checks the Vercel link before the merge | Turn off the Git link, or keep PR 2 open |
| K17 | PTY checks are timing-sensitive on CI runners. | M | M | RC-31 risk column | The P-b terminal job | Screen checks at marks (`vt.py`); long waits; retries only around the process start |
| K18 | R3 has no full fix: `Tui.run` inside a foreign `main` keeps `scala.Console` output on the screen. | H (by design) | M | RC 2.1 R3; IR "What does not work" 3 | Output probe in P-b and P-zio | `TuiApp` installs the capture first; a start-time warning; docs |
| K19 | Squash merges of stacked PRs cause conflicts in the next branch. | M | L | GitHub stack behaviour (HANDOFF "Merge order") | - | Merge commits; or, after a squash, merge `main` into the next branch (no rewrite) |

---

## 4. Spike phase (before the bulk work)

### 4.1 Rules for the spikes

- Spikes are throw-away code in `.work/spikes/<id>/` (git-excluded). The sbt spikes use ONE local worktree,
  `.work/worktrees/spike-sbt`, on a local branch `spike/sbt-port` from `origin/chore/cleanup`. It is NEVER pushed.
  P-a may copy converted files from it.
- Each spike agent writes `.work/spikes/<id>/REPORT.md` early and adds to it as it works (user rule). Each number
  names its log file.
- Timing numbers are valid only on a quiet machine. Each run logs the load average. The last agent (M) re-runs all
  timing gates alone.
- No spike changes a prototype folder. Spikes copy what they need.
- User limits: at most 4 agents at once, at most 2 that build, one workflow at a time. The spike workflow has 5
  agents, so it needs no announcement (the limit is more than 10).

### 4.2 The spikes and their gates

#### S1. Settle exactness and the scheduler reflection (K1, K2, K11)

- Question: can the settle reach "no split frame" with a public Kyo API only, also under CI-like load? Which region
  kinds split?
- Method (scala-cli copy of `proto/kyoui-runner`):
  1. Add a probe variant that uses `Scheduler.status()` (F-1) and no reflection. Measure the settle p50/p99 per
     event with both variants (bench rows Counter and Todo; the RR "Measurements" method).
  2. Glitch probe (`dev/Glitch.scala`). Count splits by `FrameStats.updates`, not by the bench column "late frames"
     (RR "refuted" 2 shows that this column cannot see a split). Runs: 20 000 events with no load; 20 000 under the
     2x2 pinned contention of RR N1; 20 000 with 3 probe JVMs at once on 2 CPUs (`taskset -c 0,1`) as a model of CI.
  3. Count "settle timed out" per run.
  4. Pull check (F-4): after the settle, read `current` of every bound-ref and `Foreach` region and compare it
     with the applied value. For each split, record the region kind (`Reactive`, bound, list).
  5. Run the 9 prototype tests 5 times under the CI model.
- Gate G1:
  - GO: 0 split frames in all runs with the `status()` probe; the `status()` probe costs at most +50 us p50 against
    the reflection; tests 5 of 5 under the CI model.
  - SOFT: 0 splits with no load and at most 1 per 10 000 under load; or GO only with the reflection.
  - NO-GO: more than 1 split per 10 000 under load, or a test failure under the CI model.
- What changes in the plan:
  - GO: RC-11 drops the reflection and uses `status()`. The canary tests the `Status` fields and `busyWorkers()`.
    If the pull check found splits in bound or list regions, P-a adds the pull check for these region kinds.
  - SOFT, GO only with reflection: keep the reflection, with the loud fallback and the canary (RC-11 as written).
  - SOFT on splits: P-a adds `awaitScreen(predicate, timeout)` to the core `Headless` (about 80 lines), and the
    facade kits wrap it (FC-14). A test that reads the screen right after a key uses it. The docs say "very likely
    one frame per event". P-a adds `Tags.limit(Tags.Test, 1)` (D-8).
  - NO-GO: the frame contract changes to "eventually consistent within `max`" (RC-6 Scaladoc, `FrameStats`
    docs). All kit assertions auto-wait by default (RC-7 and FC-14 change shape). The upstream draft (A2) makes
    rank 1 (a synchronous change hook) a blocker for a tui4s 1.0, not for 0.x releases.

#### S2. The R1 leak and the R2 first-key fixes (K3, K4)

- Question: does a run-owned `Scope` stop all watchers on an interrupt, with a safe end order? Does "settle after
  the initial mount + verify young regions" remove the first-key delay?
- Method (the S1 copy, after S1; a copy of `proto/kyoui-integration` for the facade paths):
  1. R1, option (a) of RC 2.1: start the watchers, the component effects and the hook bodies with `Fiber.init` in a
     `Scope` that the run owns. Leak probe (IR `stress/LeakProbe.test.scala`): 300 interrupted runs, heap after GC
     per run. A new test counts live watcher fibers after an interrupted run. An order test: no paint after the
     canvas closes.
  2. R2, options (a) + (b) of RC 2.1. Split probe (IR `stress/SplitProbe.test.scala`): first key; 1000 starts with
     no load, 600 under contention; plain Kyo, ZIO glue, Cats glue.
- Gate G2:
  - GO: at most 10 KB per interrupted run (now 1754 KB, IR); 0 live watchers; order test green; 0 first-key splits
    in all runs.
  - SOFT: R1 is GO, and the first-key splits drop by 10x or more but do not reach 0.
  - NO-GO: R1 option (a) breaks the end order, or the first-key splits do not drop.
- What changes in the plan:
  - GO: RC-8 and RC-9 go into P-a as written. The probes become ignored stress suites (RC-30).
  - SOFT: the runner also starts each young-region watcher with `observe(250.millis)` for its first 2 s (public API,
    `kyo-core/kyo/Signal.scala:131`), so a missed wakeup costs at most 250 ms. The kits get `awaitScreen` (as in S1 SOFT).
  - NO-GO on R1: use option (b) of RC 2.1. The `Session` release order in P-b follows it.
  - NO-GO on R2: one more time-boxed spike to find the cause (trace a split case: watcher states, inbox
    timestamps). Until then the kits auto-wait and the docs name the limit. P-a ships without an R2 claim.

#### S3. kyo-ui drift across versions (K5, K13)

- Question: how much do the AST, `Attrs`, `Style.Prop`, and the dispatch and region rules change from one RC to the
  next?
- Method: download the sources jars of kyo-ui RC5, RC6 and RC7 with coursier (a download, no build). Diff `UI.scala`
  (`Ast` classes, `Attrs` fields), `Style.scala` (`Prop` cases), `internal/ReactiveUI.scala`,
  `ReactiveRegion.scala`, `UIEvent.scala`, `EventHandler.scala` and the focus rules in `HtmlRenderer`. Count the
  added, removed and changed names per RC. Mark each change that needs a change in `Dispatch.scala`, `Mount.scala`,
  `Props.scala` or `Layout.scala`. Then write the RC-12 tasty-query guard as a small scala-cli program, and run it on
  the RC6 and RC7 jars: it must pass on RC7 and list the RC6 -> RC7 changes.
- Gate G3:
  - GO: at most about 10 changed names per RC, and no change in the dispatch rules (bubbling, `stopPropagation`,
    bound ref before `onInput`, Enter/Space activation, form submit, focus rules).
  - SOFT: more changed names, but no rule change; the guard catches all of them.
  - NO-GO: a dispatch or region rule changed between two RCs.
- What changes in the plan:
  - GO or SOFT: RC-12 as written. A Kyo bump is its own PR, with the guard list (FC 1.6 procedure).
  - NO-GO: pin Kyo to RC7 for the first release. The upstream draft puts the runner SPI (rank 2) before the change
    hook. The docs name the supported kyo-ui version. AGENTS.md gets a checklist to re-read the copied rules at each
    bump.

#### S4. kyo-ui dependency weight (K6)

- Question: does a terminal run load kyo-http, kyo-net or kyo-ffi code? Does kyo-ui slow the first frame?
- Method: scala-cli assembly of the Counter (command of RR "Real PTY run"). Run it in a PTY with
  `-Xlog:class+load:file=<log>`. Count the loaded classes per jar. Look for native libraries other than crossterm.
  Measure the time to the first frame (10 runs, median). Try a build that excludes kyo-http: a link error confirms RC
  3.1 ("exclude is not safe").
- Gate G4:
  - GO: no class from kyo-http, kyo-net or kyo-ffi loads; median first frame at most 1.0 s (now 0.8-0.9 s).
  - SOFT: some of these classes load, but they cost less than 100 ms.
  - NO-GO: kyo-http or kyo-net starts threads or native code at start, or costs more than 100 ms.
- What changes in the plan: GO or SOFT: accept the dependency (D-5); the docs state the size. NO-GO: the upstream
  draft moves the module split (rank 7) up, and the docs name the start cost. Nothing else changes: kyo-ui is the
  API, so there is no way around it.

#### S5. The generator inside sbt (K7)

- Question: can a `codegen` sbt sub-project run the generator, write the same mirrors and check drift at an
  acceptable cost? Does the Cats FULL mirror compile?
- Method (local worktree `spike-sbt`, after S6 there): add `codegen` (Scala 3.9.0, `publish / skip`, deps
  tasty-query 1.9.0 and kyo-ui, no `dependsOn`). Port `proto/kyoui-facades/gen` (781 lines). The class path, the Kyo
  version and the targets are task inputs (FC-2). Run it forked with `-Xss16m`. Write the ZIO full mirror and compare
  it with `full-zio/generated/ZioUiFull.scala`. Write the Cats full mirror for the first time. Compile both with
  their hand-written runtime (copied from the prototypes) under the real flags, against zio 2.1.26, cats-effect 3.7.1
  and fs2 3.14.0. Measure the time that `mirrorsCheck` adds to `sbt check`, cold and warm. Add an unknown setter to
  show that the generator fails closed.
- Gate G5:
  - GO: the ZIO full mirror is byte-identical (except the header line with the version); the Cats full mirror
    compiles; `mirrorsCheck` adds at most 60 s cold and 15 s warm; the unknown setter fails the run.
  - SOFT: the Cats full mirror needs small rule fixes (fewer than about 50 generator lines).
  - NO-GO: the generator cannot run in sbt, or the check costs much more.
- What changes in the plan: GO: P-gen as written (FC-1 to FC-8); drift check in the `check` alias (D-6). SOFT: P-gen
  grows by the rule fixes. NO-GO: the drift check becomes a separate CI step (FC-D9 option b). If sbt cannot host the
  generator, keep it as a scala-cli project in `codegen/` with a pinned scala-cli version (a new tool in CI; the user
  decides). If the Cats full mirror does not compile, P-cats ships the elements that compile and lists the others
  (FC-D6 option b, for Cats only).

#### S6. Strict flags, format and fs2 on the prototype code (K8)

- Question: what does the port to the real build cost? Is any change more than mechanical?
- Method (local worktree `spike-sbt`, first): copy the runner sources into `modules/core` (package `tui4s`, the
  default of D-2). Add kyo-ui. Compile with the real flags. Count the errors and the warnings by kind and by file.
  Then copy the facade runtime and the prototype mirrors into `modules/zio` and `modules/cats`, and compile them
  against fs2 3.14.0. Run scalafmt and count the changed lines. Run the 9 runner tests (munit is fine here) to show
  that the conversion did not change the behaviour.
- Gate G6:
  - GO: fewer than about 300 changed lines; no `.nn` on a value that can be null at run time (check each `.nn` on a
    jatatui or JDK result against its source or Javadoc); fs2 3.14.0 compiles; tests 9 of 9.
  - SOFT: more lines, but still mechanical.
  - NO-GO: explicit nulls force a design change, or fs2 3.14.0 breaks `KyoSignallingRef`.
- What changes in the plan: GO or SOFT: RC-18 as written (format in its own commit). NO-GO on nulls:
  `import scala.language.unsafeNulls` in the jatatui-facing files only (`Paint`, `Canvas`, `TerminalIO`,
  `Crossterm`), with a rule in AGENTS.md. NO-GO on fs2: pin fs2 in `modules/cats/build.sbt` to the last version that
  works, and write down why. The warning counts are the input to D-7 (warning policy).

#### S7. Facade components at runner mount, quit as a value, error channel (K9)

- Question: can ZIO and Cats components be mounted by the runner, keep their state across a parent re-render, and
  close their effect scope at unmount?
- Method (scala-cli copy of `proto/kyoui-integration`): add to the core copy `Tui.run(options)(quit => ui)` (R5),
  `Abort[TuiError]` in `run`, and an overridable state key on `Component` (FC-17). Write the ZIO `component(key)` and
  `Component` (and the Cats forms) over the core hooks. Tests: the state survives a parent re-render; two facade
  `Component` classes at one position do not share state; the ZIO `Scope` finalizer runs at unmount; quit works with
  no extra Kyo fiber; `NotATerminal` reaches the ZIO error channel. Also probe FC-D8 (a `NotGiven` guard against a
  nested ZIO; the overload resolution must stay clear) and the ZIO console override (`ZIO.withConsole`) with the IR
  output probe.
- Gate G7:
  - GO: all tests green, 3 of 3 runs.
  - SOFT: it works, but the Cats form needs another shape (for example a `Resource` in place of a scope link).
  - NO-GO: the scope link or the key hook does not work.
- What changes in the plan: GO: FC-17 goes into P-zio and P-cats; the key hook goes into P-a (RC-5). NO-GO: no
  runner-mounted facade components in the first release. The facades keep the build-time `Component.mount`
  (FC-D11 reverses). The docs say "a facade component cannot be inside `render`, `foreach` or `when`". P-a has no key
  hook.

### 4.3 Spike schedule and agents (one workflow, 5 agents)

| Wave | Agent | Spikes | Builds | Notes |
| --- | --- | --- | --- | --- |
| 1 | A | S1, then S2 | scala-cli | The most important spikes. Notes in `.work/spikes/s1-settle/`, `s2-r1r2/`. |
| 1 | B | S6, then S5 | sbt (local worktree) | The only sbt user in the repo at this time (AGENTS.md: one sbt process). |
| 1 | C | S3 part 1 (download and diff) | none | Reads only. |
| 2 | C | S3 part 2 (guard), then S4 | scala-cli | Starts when A or B ends. |
| 2 | D | S7 | scala-cli | Starts when A or B ends. |
| 3 | M | Re-measure the timing gates G1, G2, G4, G5 alone; skeptic review of all spike reports | scala-cli, sbt | Writes `.work/design/plan/gates.md` with GO / SOFT / NO-GO per gate and its evidence. |

At most 3 agents at once, at most 2 that build. Token cost: about 0.3-0.5M per agent, from the earlier runs (HANDOFF:
7 agents used about 2.0M). After the run, update the upstream draft (A2) with the measured numbers (S1 split rates and
settle share, S3 churn, S4 class counts). Nobody posts the draft without the user.

### 4.4 Summary: what each gate outcome changes

| Gate | GO | SOFT | NO-GO |
| --- | --- | --- | --- |
| G1 settle | No reflection; canary on the public API | `awaitScreen` in the kits; `Tags.limit` | Eventual-consistency contract; auto-wait kits; upstream rank 1 = 1.0 blocker |
| G2 R1/R2 | RC-8, RC-9 as written | Short repair interval for young regions; `awaitScreen` | R1 option (b); R2 cause spike; no R2 claim in P-a |
| G3 drift | RC-12 guard | Guard + bump checklist | Pin RC7; SPI first in the upstream draft; manual re-read per bump |
| G4 weight | Accept | Accept + docs | Upstream split first; docs name the cost |
| G5 generator | P-gen as written; drift in `check` | Rule fixes in P-gen | Drift as a CI step; scala-cli generator; smaller Cats mirror |
| G6 flags | RC-18 as written | More lines, same plan | `unsafeNulls` per file; pin fs2 |
| G7 components | FC-17 + key hook | Other Cats shape | No runner-mounted facade components; no key hook |

---

## 5. PR sequence

### 5.1 Common rules

- All branches are new. Base of P-a: `main` when the user merges PR 1 first (recommended, D-31); else
  `origin/chore/cleanup`. Each later PR uses the branch before it as its base.
- Carried files come with `git checkout <old ref> -- <path>`. The commit message names the source ref and commit.
  No old branch changes.
- Each PR adds the AGENTS.md rows for its own new commands (AGENTS.md rule). P-rules does the full rewrite.
- `compat.sbt` stays `Compatibility.None` in all three modules in every PR (section 8).
- Process per PR (user rules): implementer -> `sbt check` green locally and in CI -> 3 reviewers (read only) ->
  3 skeptics (verify; at most 2 build at once) -> fixer -> `sbt check` green -> the user reviews. One workflow per
  PR with 8 agents or fewer, so no announcement. The next PR's workflow can start while the user reviews the PR
  before it. Changes from that review reach the later branches by a merge commit (no rewrite).
- The reusable one-PR workflow script is on the Mac (HANDOFF "Facts the next session must keep"), not in this cloud
  session. Write it again once, before P-a (small).
- Measurements that need a quiet machine (stress probes, bench) run with no other build on the machine.

### 5.2 The PRs

| # | Branch (base) | Content | Acceptance | Size | Agents |
| --- | --- | --- | --- | --- | --- |
| P-a | `feat/kyoui-1-runner` (`main`) | Runner core, headless only: build and deps, the port of `proto/kyoui-runner/src`, strict flags and format, kyo-test, Kyo-free options, quit as a value, error channel, `Headless` with `Screen`, R1, R2, settle hardening, kyo-ui surface guard, grapheme editing, tests for unverified paths, state-key hook. | `sbt check` green locally and in CI; core suite green 3 runs in a row, one of them under the 2x2 contention; glitch test 0 splits in 500 events; settle canary and kyo-ui surface guard pass; stress probes run once (leak: at most 10 KB per interrupted run; first key: per G2), numbers in the PR body; no jatatui or Kyo-internal type in the public API; no stdout or stderr write. Real terminal: not applicable (no terminal code). | L: about 4400 lines (main about 2700, test about 1700) | 8 (1 implementer, 3 reviewers, 3 skeptics, 1 fixer); about 6 `sbt check` runs |
| P-b | `feat/kyoui-2-terminal` (P-a) | Terminal and app parts from PR 5 on the runner: `TerminalIO`, `Crossterm`, `Session` and input thread, `EventMapping`, `OutputCapture`, `LogSink`, `TuiLog`, `TuiApp`, `SchedulerDefaults`, `Trace`, carried tests; the 3 open bot findings; bracketed paste as one edit; the PTY kit, the launchers task and the Counter app (F-7); CI `terminal` job. | `sbt check` green; CI `terminal` job green for the Counter: alternate screen, raw mode while running, keys, `q` exit 0, Ctrl-C per D-12, `kill -TERM` exit 143, stdin pipe gives `NotATerminal`, native access denied message, resize (WINCH), `stty -a` after = before, nothing stray; output probe: a `println` before the run under `TuiApp` goes to the log. | L: about 4200 (main about 1700, test about 2100, kit and Counter about 400) | 8; builds plus local PTY runs |
| P-c | `feat/kyoui-3-examples` (P-b) | Other examples (CounterStyles with hooks and `Component`, Todo, Dashboard, Widgets) with munit suites; all PTY cases; bench probes rewritten, JMH benches of `Layout` and `Paint`, new `BASELINE.md` section; `examples/README.md`; AGENTS.md bench rows. | `sbt check` green; CI `terminal` job green for all apps and cases (Todo typing, Enter, Tab, Space, paste as one edit, save; Dashboard pause and live; resize); bench run once on a quiet machine, numbers in `BASELINE.md` (not a CI gate). | M-L: about 2300 | 8 (or 6); 1 quiet-machine bench run |
| P-gen | `feat/kyoui-4-codegen` (P-c) | `codegen` sbt project (port of `gen/`, class path and version as task inputs, fail closed, both ecosystems, guard spec generator, alias and exclusion lists); `sbt mirrors` alias and `codegen/mirrorsCheck` in `check` (no targets yet: F-6); scalafmt exclude for `**/src/*/generated/**`; shared surface-leak helper in `codegen/src/surface`; AGENTS.md row and the Kyo bump procedure. | `sbt check` green with `mirrorsCheck`; generator tests: golden output for a small fixed input, fail-closed cases (unknown setter, unknown leak, a data type with no alias); two `sbt mirrors` runs into a temp folder are byte-identical; added `sbt check` time recorded (G5 number). | M: about 1200 | 6 (1, 2, 2, 1) |
| P-zio | `feat/kyoui-5-zio` (P-gen) | ZIO mirror target and the checked-in `ZioUi.scala`; `SignalRef`, `Bridge`, `Hooks` (with runner-mounted components per G7); `AmbientZIOs` and its suite from `feat/zio`; `Tui` (typed `TuiError`, `quietly`, `Tui.quit` FiberRef, console override), `TuiApp` (args, capture install, scheduler defaults), test kit, re-exports; generated guard spec; surface-leak check; examples and PTY rows; rules for clocks, acquires and Kyo promises. | `sbt check` green incl. drift for `ZioUi.scala`; CI `terminal` job ZIO rows (Counter, Todo, Ctrl-C, `kill -TERM`, early-`println` probe under `TuiApp`); stress suites green 3 of 3; surface check: no Kyo type outside the allow-list; the guard spec covers all 56 effect points. | L: about 1900 (+ about 1200 generated) | 8 |
| P-cats | `feat/kyoui-6-cats` (P-zio) | Cats mirror target and `CatsUi.scala` (first full Cats mirror); `KyoSignallingRef` with the `UiSignallingRef` subtype; `CatsBridge`, `AmbientDispatcher` and suites from `feat/cats`; `Tui` (`run`, `resource` CE bracket, `end` waiter fiber, explicit quit), `TuiApp` (`IOApp`, starvation checker off), test kit; guard spec; examples and PTY rows; screen-parity test (Kyo, ZIO and Cats Counter: same keys, same screen). | `sbt check` green incl. drift for `CatsUi.scala`; CI `terminal` job Cats rows; `CatsBridgeSuite` stress 3 runs; screen-parity test green. | L: about 2100 (+ about 1200 generated) | 8 |
| P-layout | `feat/kyoui-7-layout` (P-cats) | First-release layout scope (D-18): scroll with focus-follows in `overflow` columns, row min-content width, ellipsis, 256-colour fallback; one log line per unsupported element or attribute kind; layout and paint benches updated. | `sbt check` green; CI `terminal` job: Todo with a list longer than the screen, focus moves scroll; bench "Todo grow" numbers recorded. | M-L: about 800 | 8 (or 6) |
| PR 2 | `docs/docus` (base changed to P-layout) | Merge commit of the P-layout tip into `docs/docus`; then new commits: pages per ecosystem (Kyo, ZIO, Cats), concepts (kyo-ui UI, signals, hooks, `Component`), testing (kits), performance (frame stats, settle, scheduler flags), troubleshooting (no `sbt run`, launchers, lift trap, `scala.Console` limit, clock rule, `Signal` clash with fs2). Code comes from `examples/` by file include. | Docs CI (`pnpm build`, the agent files) green; no deploy; the user checks the Vercel Git link before the merge (F-11). | M: about 800 lines of Markdown | 4 (1 writer, 2 reviewers, 1 fixer); node builds only |
| P-rules | `chore/kyoui-rules` (`docs/docus`) | AGENTS.md: what tui4s is, module table with `codegen`, rules (never edit generated mirrors; type Kyo handler values `: Any < Async`; no `private[kyo]` use; Kyo bump procedure; test concurrency; spike guards); README: kyo-ui Quick start (hooks Counter + `Headless`), JVM flags, launchers, scheduler flags. | `sbt check` green (no code change); AGENTS.md in ASD-STE100; all links resolve. | S: about 150 | 3 (1 writer, 1 reviewer, 1 fixer) |

Total: about 61 agents in 9 workflows, plus 5 in the spike workflow.

### 5.3 Commit order inside P-a (risk first)

1. Build: kyo-ui dependency, drop jatatui-widgets, kyo-test-runner and tasty-query (via `Versions.tastyQuery`),
   fork and JVM flags, `Tags.limit` if D-8 says so.
2. Port the prototype files as they are, with the package move only (D-2). Remove the 11 authorship marks (F-12).
3. Strict flags and scalafmt, in their own commit (no logic change). The G6 numbers predict the size.
4. Tests to kyo-test (RC-19), `config.sequential`; the fixtures from `apps/`.
5. R1 and R2 with their tests and the ignored stress suites (G2 decides the variant).
6. Settle hardening and the canary (G1 decides the variant).
7. API: Kyo-free `TuiOptions` (R4), quit as a value (R5), `Abort[TuiError]`, `Headless` (quit and wait before the
   scope ends, `Screen` with `CellStyle`), state-key hook (G7).
8. Guards and new tests: kyo-ui surface guard (RC-12), name-clash and `CanEqual` tests (OS-16), RC-15 tests, the
   lift-trap canary for Kyo users (RR O5), grapheme editing (OS-11).

Reviewers can then read the PR commit by commit: moves, format and logic stay apart (K14).

---

## 6. Where every inventory item goes

### 6.1 Old stack (OS-1 to OS-50)

| PR | Items |
| --- | --- |
| P-a | OS-5 (runner fields), OS-8 (run API, `Headless`), OS-11, OS-12 (`Screen`, `discard`), OS-16 (two lesson tests), OS-34 (kit rule: no unbounded Kyo wait; no join of the run fiber), OS-37, OS-38, OS-39 |
| P-b | OS-1, OS-2, OS-3, OS-4, OS-5 (terminal fields), OS-6, OS-7, OS-8 (`TuiRun`), OS-9, OS-10, OS-12 (`ScriptedInput`, `RecordingControl`), OS-15, OS-19 (kit, Counter cases), OS-20, OS-41 (terminal job), OS-47 (rows) |
| P-c | OS-18, OS-19 (all cases), OS-21, OS-40, OS-50, OS-47 (bench rows) |
| P-gen | OS-17 (surface helper in `codegen/src/surface`), OS-42 |
| P-zio | OS-22, OS-24, OS-25, OS-27 (lesson tests), OS-28, OS-29, OS-30, OS-31, OS-32, OS-33, OS-34, OS-35 |
| P-cats | OS-23, OS-24, OS-25, OS-27 (lesson tests), OS-28, OS-29, OS-30, OS-32, OS-34, OS-35, OS-18 (screen-parity test, D-27) |
| PR 2 | OS-49 |
| P-rules | OS-47, OS-48 |
| No PR (process) | OS-43 (no change: all `None`), OS-45 (no change), OS-46 (section 7) |
| Dropped | OS-13, OS-14 (lessons kept: batching -> D-16; clock rule -> P-a kit docs; `NotForeign` idea -> deferred, 6.4), OS-26, OS-36 (obsolete), OS-44 (never ported) |

### 6.2 Runner (RC-1 to RC-36)

| PR | Items |
| --- | --- |
| P-a | RC-1, RC-2, RC-3, RC-4, RC-5 (+ key hook), RC-6, RC-7, RC-8, RC-9, RC-11, RC-12, RC-14 (log part), RC-15, RC-16, RC-17 (decision after G4), RC-18, RC-19, RC-28, RC-30 (core stress suites), RC-36 |
| P-b | RC-10, RC-14 (paste as one edit), RC-20 (inline viewport per D-15), RC-21, RC-22, RC-23, RC-24, RC-25, RC-26, RC-27, RC-31 (kit + Counter), RC-33 (Counter) |
| P-c | RC-30 (bench probes), RC-31 (all cases), RC-32, RC-33 (other apps) |
| P-layout | RC-13, RC-14 (other warnings) |
| No PR | RC-29 (old files are never ported), RC-34 (upstream draft in `.work/`, after the gates), RC-35 (section 7) |

### 6.3 Facades (FC-1 to FC-38)

| PR | Items |
| --- | --- |
| P-a | FC-17 (core state-key hook only) |
| P-gen | FC-1, FC-2, FC-3, FC-4, FC-5, FC-6, FC-7, FC-8, FC-16, FC-25, FC-26, FC-29 (helper), FC-30 (generator part), FC-31, FC-32 |
| P-zio | FC-9, FC-11 (ZIO), FC-12, FC-13, FC-14, FC-15, FC-17, FC-19, FC-21, FC-23, FC-27, FC-28, FC-29 (wiring), FC-30 (spec), FC-33, FC-34, FC-35, FC-36 |
| P-cats | FC-10, FC-11 (Cats), FC-12, FC-13, FC-14, FC-15, FC-17, FC-20, FC-22, FC-24, FC-28, FC-29 (wiring), FC-30 (spec), FC-33, FC-35 |
| PR 2, P-rules | FC-37 |
| No PR | FC-18 (prototype-only stand-in, dropped), FC-38 (section 7) |

### 6.4 Fixes and findings that need code

| Item | Place | Note |
| --- | --- | --- |
| IR R1 leak | P-a (RC-8) | Variant from G2 |
| IR R2 first key | P-a (RC-9) | Variant from G2 |
| IR R3 `scala.Console` | P-b (RC-10); P-zio and P-cats `TuiApp` install; P-zio console override | No full fix (K18) |
| IR R4 Kyo-free options | P-a (RC-3) | `java.time.Duration` (D-10) |
| IR R5 quit as a value | P-a (RC-2) | Facades drop `withQuit` |
| IR F1 drop HTML stand-in | P-zio, P-cats | Never ported |
| IR F2 glue into the modules | P-zio, P-cats | `Tui.scala`, `testkit` |
| IR F3 stale dispatcher text | P-cats | Real entry points |
| FR O1 drag and drop | P-gen (exclusion list, D-20) | |
| FR O2 Kyo data types via aliases | P-gen (D-19) | |
| FR O3 Cats ref type | P-cats (D-21) | |
| FR O4 silent missing id | P-zio, P-cats (bounded `focus(id)` in the kits) | |
| FR O5 stale texts | P-zio, P-cats | |
| FR O6 folders not self-contained | Obsolete in sbt | |
| FR O7 `changes` semantics | P-zio (Scaladoc + interrupt stress test, FC-36) | |
| FR O8 headless only | Closed by IR; PTY rows in P-zio, P-cats | |
| RR O2 private machinery | P-a (RC-12 guard); upstream draft | |
| RR O4 no change hook | P-a (settle, G1) | |
| RR O5 lift trap | P-a (canary test); P-rules (rule); upstream draft | |
| RR O6 settle limits | P-a (timeout counter in `FrameStats`; no stderr print); P-b (trace line) | |
| RR O7 component state | P-a (tests carried) | |
| RR N1 settle order | P-a (keep the fixed code) | |
| RR N2 probe races | S1; P-a (RC-11) | |
| RR N3 fixed sleeps | P-a (keep `awaitEnd`) | |
| RR N4, N5 numbers | P-c (bench, `BASELINE.md`) | |
| RR N6 PTY | P-b, P-c | |
| RR N7 layout gaps | P-layout | |
| RR N8 unsupported elements | P-a (log line), P-layout, deferred list below | |
| RR N9 kyo internals | P-a (RC-12), S1 (reflection) | |
| RR N10 terminal limits | P-b | |
| PR 5 bot P1 `CountingBackend:33` | P-b (OS-10: draw and restore serialised in `Canvas`) | |
| PR 5 bot P2 `Crossterm:103` | P-b (OS-1) | |
| PR 4 bot P2 `Session:150` | P-b (OS-2) | |
| ZIO findings (14) | OS-31 to OS-36 rows above | spec-4 obsolete |

Deferred (not in this plan's PRs), with the reason:

| Item | Reason |
| --- | --- |
| Mouse, hover, wheel, drag and drop in the runner (RR N8) | D-18: no mouse in the first release; mouse capture stops text selection (RR Q4) |
| Inline viewport (RC-20 part) | D-15 recommends defer: new `Canvas` code with no prototype; it is additive later |
| Virtual lists | After scroll (RC 2.5) |
| Real rendering of `Img`, SVG, `Chart`, multi-line `Textarea`, pickers, `Select` drop-down | Placeholders in the first release (D-18) |
| `NotForeign`-style guard for tui4s Kyo hooks (OS-14 lesson) | kyo-ui handlers stay `Any < Async`, so it is a partial guard; upstream rank 4 is the real fix |
| `onKeyUp` support | Not possible on a plain terminal; a log line only |
| macOS Terminal / iTerm2 look check | A human check before the first release (HANDOFF open items) |

---

## 7. The open PRs and branches

Chosen: option A of OS-46. The user closes PRs 5, 4 and 3 (top first) after P-a is open. Each close comment links
the new PR and lists the three carried bot findings. All old branches stay unchanged as reference. No force push and
no history rewrite. Carried files come with `git checkout <old ref> -- <path>`.

Why: most of PRs 3-4 drops (4724 main lines, OS section 1), so a new diff is easier to review; the old review
threads stay readable; no rewrite on branches that the user may have read.

| Option | For | Against |
| --- | --- | --- |
| A. Close 3-5, new stacked PRs (CHOSEN) | No rewrite; clean diffs; old threads readable | Three closed PRs; new numbers; the 3 bot findings are copied by hand |
| B. Force-push new history to the old branch names | Same PR numbers | History rewrite (against the user's preference); old comments point to missing commits |
| C. Keep PR 5, add commits that delete the old model | No rewrite; keeps PR 5 | 34 old commits stay in the history; one very large PR; noisy blame |
| C'. Rebase PR 5 | Clean history, same number | A rewrite (force push); not without the user |

Other branches:

| Branch | Action | Why |
| --- | --- | --- |
| PR 1 `chore/cleanup` | The user merges it first (D-31); P-a then starts from `main` | It is green and independent of the rework |
| PR 2 `docs/docus` | Keep open. After P-layout is green: merge the P-layout tip into `docs/docus` (a merge commit), change the PR base to `feat/kyoui-7-layout`, then rewrite the content in new commits (D-32) | No rewrite; one docs PR; file includes from `examples/` resolve |
| `feat/zio`, `feat/cats` (no PR) | Keep unchanged as the source of carried files | Non-destructive |
| `feat/core-1-model-view`, `feat/core-2-engine`, `feat/core-3-terminal` | Keep unchanged after the close (D-30) | Reference for the carried tests and the bot threads |
| `wip/handoff` | Never merge; keep it for `.work/` snapshots; delete only when the user says | It holds the notes |
| `chore/cleanup-7p2rll`, `first-take` | Do not touch (F-10) | Not part of this work |
| `spike/sbt-port` (local) | Never pushed; delete after the gates | Throw-away |

PR 2 alternatives (D-32): (B) rebase `docs/docus` on the stack tip (clean history; a rewrite, so only with the
user's yes); (C) a new branch `docs/kyoui` from the stack tip that takes `docs/` with
`git checkout origin/docs/docus -- docs .github/workflows/docs.yml`, and close PR 2 (no rewrite; loses the PR 2
thread); (D) merge PR 2 as it is after PR 1 and fix the content later (fast, but `main` has old-model docs for a
time; the Vercel link can deploy them, F-11).

Merge order (always the user, never an agent): PR 1 -> P-a -> P-b -> P-c -> P-gen -> P-zio -> P-cats -> P-layout ->
PR 2 -> P-rules. GitHub moves a stacked PR to the new base when its base branch is merged and deleted. Use merge
commits. After a squash merge, merge `main` into the next branch before its merge (K19).

---

## 8. Versioning and compatibility

- Nothing is released, and publishing is deferred (DECISIONS round 3).
- `compat.sbt`: core, zio and cats stay `Compatibility.None` in every PR of this plan. The next release of each
  module is its first one. After a first release, set the module back to `BinaryCompatible` (AGENTS.md
  "Compatibility").
- `project/Versions.scala` (P-a): add `tastyQuery = "1.9.0"` (F-13). Change the comment of `kyo` to say that
  kyo-ui and kyo-zio use the same version. No separate kyo-ui constant: kyo-ui is released with Kyo. A change in this
  file marks all modules unreleased: no effect now.
- `ModuleVersioning` paths (`modules/<m>`, `project/Versions.scala`): a change in `codegen/` does not mark zio or
  cats unreleased; a change of a checked-in mirror in `modules/<m>` does. This is correct: only the output ships
  (FC 1.1).
- `codegen`, `bench` and `examples` have `publish / skip := true`.
- `versionPolicyCheck` finds no earlier release, so it passes. After the first release: the facade binaries name
  kyo-ui classes (K12), so a Kyo bump sets `Compatibility.None` in core, zio and cats in the same PR, and core is
  released first (AGENTS.md "Releasing").
- `private[tui4s]` seams are public in the bytecode (RC-36). P-a lists them in the PR body and keeps them few.
- Version literals: ZIO stays in `modules/zio/build.sbt` and `examples/zio.sbt` reads it (D-28); fs2 stays 3.14.0
  in `modules/cats/build.sbt` unless G6 says otherwise.

---

## 9. Guards that stay after the spikes (early warning for later changes)

| Guard | Where | Finds |
| --- | --- | --- |
| Settle canary (public probe calls and fields) | P-a core tests | K2 at the next Kyo bump |
| kyo-ui surface guard (AST classes, `Attrs` fields, `Style.Prop` cases, `Foreach.applyTyped` still private) | P-a core tests | K5 |
| Glitch test (500 events, splits by `updates`) and first-key test | P-a core tests | K1, K3 |
| Ignored stress suites (leak, split, glitch) | P-a (`-Dprobe.*`) | K1, K3, K4 when run by hand before a release |
| Mirror drift check | P-gen, in `check` | Hand edits and stale mirrors |
| Fail-closed generator | P-gen | New kyo-ui effect points with no rule |
| Surface-leak check and guard spec | P-zio, P-cats | Kyo types in facade signatures; the lift trap |
| Screen-parity test | P-cats | Facade behaviour drift |
| `EventMapping` exhaustivity `-Wconf ...:e` | P-b | New crossterm event types |
| CI `terminal` job | P-b onward | Real-terminal behaviour |

---

## 10. Decisions for the user

### 10.1 Before the spikes

- D-1. Approve the spike workflow (5 agents, at most 3 at once, at most 2 that build; local only; no push).
  RECOMMENDED. Alternative: start P-a now and find the risks during the PR (faster start, but a NO-GO then
  rewrites a large open PR).
- D-2. Package of the core user API: `tui4s` (RECOMMENDED: shorter; tui4s IS the kyo-ui backend) or `tui4s.kyoui`
  (explicit; matches the side-by-side page). S6 uses `tui4s` as the default; the answer can come later.

### 10.2 After the gates (the spike reports give the numbers)

- D-3. Settle contract (G1): default `Settle.Quiescent(100 ms, confirm = 2)` with the public `status()` probe if G1
  is GO (RECOMMENDED); with SOFT or NO-GO, choose between `awaitScreen` in the kits and auto-wait by default.
- D-4. R2 fix variant (G2): settle after the mount + verify young regions (RECOMMENDED); shorter repair interval for
  young regions (bounds the delay; more wakeups); keep new regions pending (does not close the window, RC 2.1).
- D-5. kyo-ui transitive dependencies (+15.2 MB; G4): accept (RECOMMENDED); exclude jars (not safe: `HttpUrl` in the
  AST); wait for an upstream split.
- D-6. Drift check in the `check` alias (RECOMMENDED: local `sbt check` = CI) or a separate CI step (if G5 shows a
  high cost).
- D-7. Warning policy (F-3, G6): (a) warnings allowed, as today (no work; warnings can pile up); (b) zero warnings in
  hand-written code: `-Werror` in CI only, `-Wconf` hides generated and inlined-Kyo noise (RECOMMENDED if G6 counts
  are small); (c) a review rule only.
- D-8. Test concurrency (F-5): `Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)` (RECOMMENDED: one test
  JVM at a time; slower CI, stable settle) or parallel test JVMs (faster; more contention).

### 10.3 API and scope

- D-9. Facade packages: flat `tui4s.interop.zio` / `tui4s.interop.cats` (RECOMMENDED; DECISIONS round 4; one import);
  `....ui` (prototype); DSL in `.ui` with the run API in the parent (two imports).
- D-10. Duration type in the Kyo-free options: `java.time.Duration` (RECOMMENDED: equal to `zio.Duration`; Kyo has
  `Duration.fromJava`), `FiniteDuration`, or `Long` milliseconds.
- D-11. Quit: `Tui.run(options)(quit => ui)` plus the Kyo `Tui.quit` Local; ZIO also keeps `Tui.quit` through a
  FiberRef; Cats has the explicit `quit: F[Unit]` only (RECOMMENDED). Alternatives: a run handle only; one JVM-wide
  current run (global state; breaks parallel headless tests).
- D-12. Ctrl-C: as a key it quits with exit code 0; 130 and 143 only for real signals from outside (RECOMMENDED;
  the PR 5 behaviour) or 130 always.
- D-13. `Screen` cell styles: a small tui4s `CellStyle` (RECOMMENDED: no jatatui type in the API) or jatatui `Style`.
- D-14. `InputPort` element: the crossterm `Event` (RECOMMENDED: one mapping path, and headless input tests cover
  `EventMapping`) or a runner message (simpler scripts; the mapping is not covered).
- D-15. Inline viewport: defer (RECOMMENDED from the risk angle: new `Canvas` code with no prototype; additive later)
  or implement in P-b (keeps a PR 5 feature).
- D-16. Input bursts: one frame per input batch (at most 512 events) in the real session and one frame per key in
  `Headless` (RECOMMENDED; `BurstProbe` in P-c checks it), or one frame per event everywhere (simpler; 1000 frames
  for a burst of 1000 keys).
- D-17. jatatui-widgets: drop (RECOMMENDED: the runner imports none of it) or keep for a later `Chart`.
- D-18. First-release layout scope (P-layout): scroll with focus-follows, row min-content width, ellipsis, 256-colour
  fallback, 1 px = 1 cell, no mouse, log lines for unsupported attributes, placeholders for `Img` and `Chart`, all
  PTY cases (RECOMMENDED). Alternative: drop P-layout from the first release (smaller; long lists cannot be reached).
- D-19. Kyo data types through the facade aliases: opaque event types with accessors that return Scala types
  (RECOMMENDED), allow and document them, or mirror every data type (large).
- D-20. Drag and drop in the mirror: exclude all 9 names (RECOMMENDED) or generate the full ZIO/F forms.
- D-21. Cats ref type: a `UiSignallingRef[F, A] extends SignallingRef[F, A]` subtype (RECOMMENDED: compile-time
  check) or plain `SignallingRef` (run-time check).
- D-22. Mirror scope: all 52 HTML elements (RECOMMENDED) or only the elements that the runner supports.
- D-23. ZIO handler result: `Any` plus a `NotGiven` guard against a nested ZIO if the S7 probe shows clear overload
  resolution (RECOMMENDED); `Any` only; or `Unit`.
- D-24. Generated code format: scalafmt exclude (RECOMMENDED) or format inside the generator.
- D-25. Facade build-time `Component.mount`: drop (RECOMMENDED if G7 is GO) or keep next to the runner-mounted form.
- D-26. Core test framework: kyo-test (RECOMMENDED: DECISIONS round 4; the carried terminal tests use it) or munit
  (the prototype tests; saves about 210 converted lines).
- D-27. Replace `FacadeParity` with a screen-parity test in P-cats (RECOMMENDED: yes).
- D-28. ZIO version literal: keep it in `modules/zio/build.sbt` and read it from `examples/zio.sbt` (RECOMMENDED) or
  move it to `project/Versions.scala`.

### 10.4 Process

- D-29. PR objects (ASSUMED A1): option A (RECOMMENDED), B, C or C' (section 7).
- D-30. Old branches after the close: keep as reference (RECOMMENDED) or delete.
- D-31. Merge PR 1 before P-a opens, so the stack starts from `main` (RECOMMENDED).
- D-32. PR 2: merge-up and new commits (RECOMMENDED), rebase, a new branch, or merge as it is (section 7).
- D-33. Merge method for the stack: merge commits (RECOMMENDED), or squash and then merge `main` into the next
  branch.
- D-34. Position of P-layout: after P-cats (RECOMMENDED: facade work starts earlier; no shared files) or a sibling
  PR on P-c (earlier review; two stacks).
- D-35. Split P-a into P-a1 (port, flags, tests; same behaviour) and P-a2 (fixes and API), if the user wants
  smaller PRs. Default: one PR with the commit order of 5.3.
- D-36. Upstream issue (ASSUMED A2): draft only, updated with the gate numbers; the user posts it, or tells Claude to
  post it.

### 10.5 Working assumptions (ASSUMED; the user must confirm)

- A1 ASSUMED: the core rework is (a) runner core, (b) terminal and app parts from PR 5, (c) examples, ptycheck and
  bench; PRs 3 and 4 are closed or replaced. This plan also replaces PR 5 (P-a, P-b, P-c are new PRs), moves the
  Counter and the PTY kit into (b) (F-7), and adds P-gen, P-zio, P-cats, P-layout, PR 2 and P-rules after them.
- A2 ASSUMED: a kyo-ui upstream issue is DRAFTED and never posted without the user. This plan updates the draft
  with the gate numbers.
- A3 ASSUMED: the ZIO facade state type is a facade-owned `SignalRef[A]` on the one kyo cell, not a `zio.Ref`
  (FR F4, F5). Effect on the core: P-a keeps writing the bound ref before `onInput`.
- A4 ASSUMED: generated mirrors are checked in, with a `codegen` sbt project and a drift check. This plan puts the
  mirror targets in P-zio and P-cats, not in P-gen (F-6). G5 decides if the drift check is in `check` or in CI.

---

## 11. Progress log

- 2026-10-03: read AGENTS.md, DECISIONS.md (rounds 1-5), HANDOFF.md, the three inventories and the three reports.
- 2026-10-03: checked the Kyo source (RC7, RC5, RC4), the build files of `chore/cleanup` and PR 5, the coursier cache,
  the generator sources and the remote branches. Wrote section 2 (F-1 to F-13) first.
- 2026-10-03: wrote sections 1 and 3-10. DONE.
