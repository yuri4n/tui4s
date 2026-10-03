<!-- claude 2026-10-03: created this file (final-plan agent; planning only: no build, no checkout, no commit, no push, no GitHub write) -->
<!-- claude 2026-10-03: completeness critic edited this file (small fixes; see the last section "Critic pass") -->
# Plan: tui4s as a terminal backend for kyo-ui (final)

Status: FINAL, for the user to read and confirm (2026-10-03). No work in this plan has started. To write it, I ran no
build, no checkout, no commit and no push, and I did no GitHub write. I read git refs with `git show` / `git log`, and
the repo settings, the open PRs and the review threads with read-only `gh api` calls.

How to read this file:

- Short names for the evidence. OS = `.work/design/plan/inventory-old-stack.md` (OS-1..OS-50). RC =
  `inventory-runner.md` (RC-1..RC-36). FC = `inventory-facades.md` (FC-1..FC-38). RR, FR, IR = the `REPORT.md` of
  `.work/proto/kyoui-runner`, `kyoui-facades`, `kyoui-integration`. DR = `draft-reviewable.md`. DK = `draft-risk.md`.
  J-M = `judge-maintainer.md` (reviewer lens). J-D = `judge-delivery.md` (risk lens). `proto/` = `.work/proto/`.
- Git refs are `origin/<branch>`. "PR 5 head" = `origin/feat/core-3-terminal` (666a8e3). "Prototype" = the files
  under `.work/proto/` at commit `1d100ed` of `origin/wip/handoff` (byte-identical to the local copy: DR section 9
  item 7).
- ASSUMED = a working assumption that the user accepted with "I like it, go ahead". The user must still confirm it
  (section 2).
- Ids in this plan: D-n = a decision for the user (section 3). Sn = a spike, Gn = its gate (S1-S7, G1-G7; section
  5). P-n = a rule for every PR (section 6.0). Kn = a risk (K1-K28, section 8; "DR K-n" is a risk of the draft DR).
  PR ids: C1-C4 (runner core), T1-T2 (terminal and app), P1 (trace and bench), F1-F4 (facades), L1-L2 (layout), D
  (docs, which is the existing PR 2). A bare F1-F4 is always a PR. The findings IR F1-F3 and FR F1-F8 always have
  their prefix (in the tables of 7.4 and 7.5, the table title gives it).
- "Ask the user" in a gate means: the plan stops at that point. No agent takes the fallback without the user's
  answer in the chat.

---

## 1. Summary

1. First, ONE local spike workflow (W0: 6 agents, at most 3 at once, at most 2 that build). It answers the 7
   questions that can stop or change the design: settle exactness, the R1 and R2 fixes, kyo-ui drift, kyo-ui weight,
   the generator inside sbt, the strict flags, and runner-mounted facade components. Nothing is pushed. The user then
   reads `.work/design/plan/gates.md` and answers the decisions of section 3.2 (gate 0).
2. Then 13 new PRs in ONE linear stack, and PR 2 (docs) at the top. Each PR has one purpose, is green before review,
   and can be read in one sitting (aim: at most about 1500 hand-written main lines; carried code comes with a delta
   guide).
3. Each public API lands in its final shape the first time. Quit as a value lands in C2, before gate 1. Known bugs
   are fixed in the PR that brings the code, each in its own commit, with a regression test that fails before the
   fix (negative control).
4. The real-terminal CI job comes back in T1, together with the terminal code. The bench comes back in P1, right
   after the app PR. So the terminal, ptycheck and bench coverage of PR 5 returns early.
5. GitHub: the user closes PRs 3, 4 and 5 (no force push, no rebase). PR 1 merges first. PR 2 stays open until the
   end and gets the stack by a merge commit. Merge commits only. Only the user merges, closes, deletes or posts.
6. Versioning: nothing is released. All three `compat.sbt` files stay `Compatibility.None` in every PR.
7. Cost: 15 workflows plus small fix rounds, about 108 agent runs, about 29-35 M tokens (section 6.15; basis: the old
   8-agent PR workflows cost 2.1-2.4 M each). No workflow has more than 8 agents, so none needs an announcement.

| # | Branch | Base | Purpose: what works after the merge | Main inventory ids | Size: hand-written main / test (G = generated) | Gate and acceptance (in addition to section 6.0) |
| --- | --- | --- | --- | --- | --- | --- |
| W0 | local only (`.work/spikes/`, local branch `spike/sbt-port`, never pushed) | - | The unknowns have numbers: G1-G7 are GO, SOFT or NO-GO. | RC-11, RC-8, RC-9, RC-12, RC-17, RC-18, FC-1..8, FC-17 | throw-away | Gate 0: the user reads `gates.md` and answers section 3.2 |
| C1 | `feat/kyoui-01-render` | `main` after PR 1 merges (D-6 a); else `chore/cleanup` (D-6 b) | A kyo-ui `UI` value renders to terminal cells (layout, paint). | RC-1, RC-12 part, RC-16, RC-18, RC-19, OS-37..39, OS-12 part | ~1300 / ~450 | Golden screens; surface guard fails when one name goes; 0 warnings |
| C2 | `feat/kyoui-02-regions` | C1 | Signals drive the screen. `Headless` with quit as a value, `Screen`. R1 and R2 fixed. | RC-3, RC-6..9, RC-11, RC-28, RC-30 part, RC-36, OS-5, OS-8, OS-16, OS-34 | ~1250 / ~650 | R2 seam test red before, green after; 0 live watchers after an interrupt; 1 run under CPU contention. GATE 1 |
| C3 | `feat/kyoui-03-input` | C2 | Keys reach the UI: focus, handlers, grapheme editing, paste, `Tui.quit`. | RC-2 part, RC-14 part, RC-15 part, OS-11, OS-15 part | ~600 / ~500 | `TextFieldTest` adapted; R2 key variant; focus group/trap tests |
| C4 | `feat/kyoui-04-components` | C3 | Both component styles: hooks and the `Component` class, mounted by the runner. | RC-4, RC-5 (+ state key), RC-8 part, RC-15 rest, FC-17 core part | ~300 / ~400 | A test per hook; no effect fiber after an interrupt |
| T1 | `feat/kyoui-05-terminal` | C4 | `Tui.run` draws on the real terminal and restores it in all cases. CI `terminal` job is back. | RC-2 part, RC-10 part, RC-20..23, RC-27 part, RC-31 part, RC-33 part, OS-1..4, OS-10, OS-20, OS-41 | ~1300 Scala (~1150 carried) + ~450 kit (carried) / ~1300 | CI `terminal` job green 3 times (Counter rows); 3 bot fixes with negative controls |
| T2 | `feat/kyoui-06-app-examples` | T1 | `TuiApp` main class; all examples on kyo-ui; all PTY cases. | RC-24, RC-25, RC-27 rest, RC-31 rest, RC-33, OS-6, OS-7, OS-18, OS-19, OS-50 | ~650 Scala + ~250 script / ~550 | PTY: Ctrl-C 0, `kill -TERM` 143, `kill -INT` 130, early `println` to the log |
| P1 | `feat/kyoui-07-trace-bench` | T2 | Measure it: one trace line per frame, bench probes, JMH benches, a new baseline. | RC-26, RC-30 rest, RC-32, OS-9, OS-21, OS-40 | ~1150 / ~200 | JMH smoke; probes on an idle machine; `BASELINE.md` section |
| F1 | `feat/kyoui-08-codegen-zio-dsl` | P1 | ZIO users build a kyo-ui value with ZIO types: codegen, ZIO mirror, drift check. | FC-1..9, FC-11 ZIO, FC-16, FC-19 part, FC-21, FC-23, FC-25, FC-26, FC-29..32, FC-35, FC-36, OS-17, OS-22, OS-42 | ~1450 (+G ~1120) / ~700 | `sbt mirrors` twice: no diff; fail-closed test. GATE 2 |
| F2 | `feat/kyoui-09-zio-run` | F1 | ZIO users run, test and ship the app. | FC-12..15 ZIO, FC-17 ZIO, FC-19, FC-27, FC-33 ZIO, FC-34, OS-24, OS-25, OS-28, OS-29, OS-31, OS-33 | ~720 / ~900 | ZIO PTY rows; ZIO cancel test: 0 live watchers |
| F3 | `feat/kyoui-10-cats-dsl` | F2 | Cats Effect users build a kyo-ui value with `F[_]`. | FC-10, FC-11 Cats, FC-20 part, FC-22, FC-24, FC-29/30/35 Cats, OS-23, OS-30 Cats, OS-35 | ~480 (+G ~1120) / ~500 | `CatsBridgeSuite` stress green 3 times |
| F4 | `feat/kyoui-11-cats-run` | F3 | Cats users run, test and ship the app; three Counters give the same screen. | FC-12..15 Cats, FC-17 Cats, FC-20, FC-33 Cats, OS-24, OS-25, OS-27 lessons | ~740 / ~900 | Cats PTY rows (one `Tui.resource` app); screen parity |
| L1 | `feat/kyoui-12-scroll` | F4 | Content below the screen is reachable: scroll in `overflow` columns. | RC-13 part, RR N7 | ~450 / ~300 | PTY row: long Todo list; "Todo grow" bench row |
| L2 | `feat/kyoui-13-layout-polish` | L1 | Narrow rows, ellipsis, 256 colours, one log line per unsupported attribute kind. | RC-13 rest, RC-14 rest, RR N8 | ~250 / ~250 | Golden screens; log-line test |
| D | `docs/docus` (= PR 2) | L2 (after a merge-up) | The docs site and the rules describe what exists. | OS-47 sweep, OS-48, OS-49, FC-28, FC-37 | ~1000 Markdown + ~100 rules | `docs` CI green; no deploy; each AGENTS.md rule true |

Gates: gate 0 after W0; gate 1 after C2 (the public run API); gate 2 after F1 (the generated ZIO API); gate 3 is the
final review and the merges. Between the gates, implementation runs at most 2 PRs ahead of the user's review (P-11).

---

## 2. Assumptions to confirm (ASSUMED)

| Id | ASSUMED (the user said "I like it, go ahead") | What this plan does with it | If the user says no |
| --- | --- | --- | --- |
| A1 | The core rework is (a) runner core, (b) terminal and app parts carried from PR 5, (c) examples, ptycheck and bench. PRs 3 and 4 are closed or replaced. | Refines it: (a) = C1-C4; (b) = T1 + the `TuiApp` part of T2; (c) = the rest of T2 + P1. PR 5 is also replaced (it holds the old model below it), and PRs 3, 4 and 5 close (D-2). The facade, layout and docs PRs follow. | If the user wants three large PRs (a)/(b)/(c): choose D-3 option (c) "6 code PRs". The content and the order stay; only the PR borders move. If the user wants to keep PR 5 open: D-2 option C (no rewrite, but a very large PR with 34 old commits). |
| A2 | A kyo-ui upstream issue is DRAFTED and never posted without the user. | The draft is `.work/design/upstream-kyo-ui-issue.md` (written with this plan). After W0, one agent puts the measured numbers in it (S1, S2, S3, S4). No PR depends on it. | If the user wants it posted now: the user posts it, or tells Claude to post it, after a duplicate search in getkyo/kyo (this session cannot search that repo). If the user wants no issue: delete the draft; the plan does not change. |
| A3 | The ZIO facade state type is a facade-owned `SignalRef[A]` on the one kyo-ui cell, not a `zio.Ref`. | F1 carries `SignalRef` (FR F4, F5). The core keeps writing the bound ref BEFORE `onInput` (C3; RC section 9 A3). | ZIO seals `Ref` and `SubscriptionRef` (FR F4), so a `zio.Ref` needs a second cell and pumps. That design raced (FR F5: 2 of 150 test failures). If the user still wants it, F1 adds the pump design and its stress suite, and accepts a known race. Not recommended. |
| A4 | Generated mirrors are checked in, with a `codegen` sbt project and a drift check in CI. | F1 adds `codegen` (in `root.aggregate`, `publish / skip`), the ZIO mirror, `sbt mirrors`, and `codegen/mirrorsCheck` inside the `check` alias. F3 adds the Cats mirror target. S5 measures the cost first. | Option: generate at compile time (`sourceGenerators`; FC section 1.1 option C). No drift is possible, but the reviewer cannot see the API in the diff, Metals sees the mirror only after a compile, and each compile is slower. F1 then has no checked-in mirror and no drift check; gate 2 reviews a generated sample file. |

---

## 3. Decisions for the user

Only real choices are here. Each gives the options, the tradeoff and a recommendation (REC). Items that the user
already decided (DECISIONS rounds 1-5) are not asked again: kyo-test for the core and munit elsewhere; the test kit
in core; the facade packages `tui4s.interop.zio` / `tui4s.interop.cats`; output capture on by default; scheduler
flags in `TuiApp` with the `-Dtui4s.schedulerDefaults=false` opt-out; no release, no deploy.

### 3.1 Now (before W0 and C1)

The user confirms A1-A4 (section 2) at the same time as these decisions, before W0: A4 sets what S5 builds, and A3
sets what S7 builds.

- D-1. Spikes first. (a) REC: run W0 (6 agents, local only, about 2.0-2.4 M tokens) before C1. A NO-GO then changes
  the plan before any PR exists. (b) Start C1 now and find the risks inside the PRs: faster start, but a NO-GO in
  S7 or S2 then changes C2 or C4 after 4 to 6 PRs sit on them (J-D section 3).
- D-2. PRs 3-5 as GitHub objects. (A) REC: the user closes PRs 5, 4, 3 (top first) when C1 is open. New branches.
  No rewrite; the old review stays readable; each new PR shows only new or carried code. (B) Force-push new history
  to the old branch names: same PR numbers, but a history rewrite, and only 3 PRs. (C) Keep PR 5, retarget it, add
  commits that delete the old model: no rewrite, but 34 old commits stay in the history and the PR is very large.
- D-3. Stack size. (a) REC: 13 PRs + PR 2 (section 1). Each PR fits one sitting. (b) 9 PRs: fold C3+C4, T2+P1,
  F3+F4, L1+L2. Fewer retargets; PRs up to about 2000 main lines. (c) 6 code PRs (the inventories' (a), (b), (c),
  (gen), (zio), (cats)): fewest PRs; (a) is about 3000 main lines after the format (DR section 9 item 1).
- D-4. Merge mechanics, three settings. (a) Merge method: REC "Create a merge commit" for every PR of the stack. A
  squash or a rebase-merge gives `main` new commits, so the next branch needs a rebase (a rewrite) or a merge with
  conflicts. (b) REC: turn on "Automatically delete head branches" (today `delete_branch_on_merge: false`, `gh api
  repos/yuri4n/tui4s`). Then GitHub retargets the next stacked PR after each merge. Without it, the user edits each
  base by hand (up to 13 edits). (c) REC: turn off "Allow squash merging" and "Allow rebase merging" while the stack is
  open (both are on today, same `gh api`). Then a wrong button cannot break the stack. Turn them on again after.
- D-5. Required checks on `main`. Today `main` has no protection (`gh api .../branches/main/protection`: 404 "Branch
  not protected"), so "green before merge" depends only on the user. (a) REC if the plan allows it: a ruleset on
  `main` that requires the CI checks `sbt` and `terminal` (from T1 on). TO VERIFY: rulesets and branch protection
  on a PRIVATE repo can need a paid GitHub plan. A hint, not proof: `gh api repos/yuri4n/tui4s/rulesets` answers
  `[]`, not an error (critic pass, 2026-10-03). (b) No rule: the user checks CI before each merge.
- D-6. Merge PR 1 now. (a) REC: yes, with a merge commit. It is green and holds nothing of the old model. The stack
  then starts from `main`. (b) Later: the stack starts from `chore/cleanup`, and each PR base moves once more.
- D-7. PR 2 (docs). (a) REC: keep PR 2 open until the end. At D, merge the L2 tip into `docs/docus` with a merge
  commit, change the PR 2 base to the stack tip (`feat/kyoui-13-layout-polish`, or F4 if D-30 drops L1 and L2), and
  write the content in new commits. No rewrite, no early deploy, and the PR 2 rule "When a public API changes, change its page in the same pull request"
  (`origin/docs/docus:AGENTS.md`) does not bind the code PRs. Cost: the content review sits next to the 15k-line
  `pnpm-lock.yaml` of PR 2. (b) One fix commit on PR 2 now (remove "One model, three effect systems"; the
  installation page points to launchers), then merge it after PR 1. The fix commit must also change the docs rule to
  "until the first release, the docs PR at the end of the kyo-ui stack brings the pages", or every code PR breaks
  that rule. A merge to `main` can start a Vercel deploy (`docs.yml`: "Vercel deploys the site"); the user checks the
  Vercel Git link first. (c) A new branch `docs/kyoui` from the stack tip that takes `docs/` from `docs/docus`, and
  close PR 2: no rewrite, but the PR 2 thread is lost. (d) Rebase PR 2: a rewrite; not recommended.
- D-8. Warning policy. Facts: the build has no fatal-warning flag; `-Wunused:all`, `-Wvalue-discard` and
  `-Wnonunit-statement` only warn, so a green `sbt check` does not prove them (`origin/chore/cleanup:build.sbt`
  `scalacOptions`; DK F-3). (a) REC: fatal warnings in every build: `-Werror`, with the existing
  `-Wconf:msg=@nowarn annotation does not suppress any warnings:s` (PR 3 `build.sbt`) for the inlined Kyo noise.
  Local `sbt check` = CI (AGENTS.md "Daily commands"). Cost: an unused import in work-in-progress code stops the
  compile. (b) Fatal only when the `CI` environment variable is set: easier local work, but a local `sbt check` is
  then weaker than CI. (c) As today, plus a warning count in each PR body: no work, but warnings can pile up while CI
  is green. Generated code: see D-26. Lands in C1.
- D-9. Test concurrency. Fact: no `concurrentRestrictions` in `build.sbt`, so sbt runs the forked test JVMs of all
  modules at the same time (DK F-5). The settle reads the JVM-wide scheduler, and CPU contention raised the first-key
  splits from 2 of 500 to 13 of 300 (IR "What does not work" 2). (a) REC: `Global / concurrentRestrictions +=
  Tags.limit(Tags.Test, 1)` in C2: one test JVM at a time; CI is slower (today a CI run takes about 3 minutes, J-M
  section 2). (b) Parallel test JVMs: faster, but the timing suites can fail on the CI runner.
- D-10. Package of the core user API. (a) REC: `tui4s` (user API), `tui4s.internal`, `tui4s.testkit`, `tui4s.spi`
  (RC section 1.2): shorter, and tui4s IS the kyo-ui backend. (b) `tui4s.kyoui`: explicit, matches the side-by-side
  page, one more import segment.
- D-11. Cloud or desktop toolchain for the agents. Fact: `.work/env.sh` is for the Mac (HANDOFF "2026-10-03:
  RESUMED IN A CLOUD SESSION"); in this container JDK 25 is `/usr/lib/jvm/java-25-openjdk-amd64` and `sbt` and
  `scala-cli` are wrappers in `/usr/local/bin` (`which`, this session). (a) REC: every agent prompt names the
  toolchain of the machine where the workflow runs (section 6.0), and never says "source .work/env.sh" on the cloud
  machine. (b) Run all workflows on the Mac: then the prompts use `env.sh` and the desktop worktrees.
- D-12. `jatatui-widgets`. (a) REC: drop it in C1; the runner imports no widget class (RC section 3.1, grep). (b) Keep
  it for a later `Chart` or `Img`: a jar that nothing uses.
- D-13. `Screen` cell style in the test kit. (a) REC: a small tui4s `CellStyle` with Scala types: no jatatui type in
  the public API (RC-36). (b) The jatatui `Style`: no new type, but the API depends on jatatui.
- D-32 (added by the critic pass). Durable notes. Fact: `.work/` is git-ignored (`.gitignore` line 42), and this
  container is not durable. The spike reports, `gates.md` and the `.work/impl/` notes will exist only here. THIS PLAN
  is also only here: `PLAN-kyoui.md`, `plan/*` and the upstream draft are not on `wip/handoff` (`git ls-tree
  origin/wip/handoff -- .work/design` lists 10 older files). The RR verifier logs are not in `.work/` at all (only in
  the session scratchpad, RR "Independent verification"). Precedent: `.work/` was pushed to `wip/handoff` as commit 1d100ed (HANDOFF "2026-10-03 later").
  (a) REC: after W0 and after each workflow, Claude adds ONE new commit with `.work/` (without `.scala-build/`,
  `.bsp/` and `target/`) to `wip/handoff`, from a separate local worktree. No force push, no rewrite; P-8 cites
  commits, not the branch. Claude also gives the gate results (`gates.md`) in the chat, because the user cannot open
  the container's files. (b) No push: the results stay in this container only; a lost container repeats W0. (c) A new
  branch `wip/notes` for the notes: `wip/handoff` keeps only the prototype; one more branch to delete later.

### 3.2 At gate 0 (after W0; the spike reports give the numbers)

- D-14. Duration type in the Kyo-free options (R4). (a) REC: `java.time.Duration`: the same type as `zio.Duration`;
  Kyo has `Duration.fromJava`; Cats users write `.toJava`. (b) `FiniteDuration`: native for Cats, a conversion for
  ZIO. (c) `Long` milliseconds: no conversion, less clear.
- D-15. Quit (R5). (a) REC: `Headless.start(...)(quit => ui)` and `Tui.run(options)(quit => ui)`, plus the `Tui.quit`
  Local for Kyo users. No extra fiber in the facades. No public `Tui.start` / `TuiHandle` in the first release. (b) A
  run handle only: the UI must exist before the handle. (c) One JVM-wide current run: global state; it breaks
  parallel headless tests.
- D-16. R2 fix (S2 measures). (a) REC: settle after the initial mount, plus a verify pass that re-reads `current` of
  the regions mounted in this step or the step before. (b) A shorter repair interval for young regions
  (`observe(250.millis)` for the first 2 s; public API): the delay is at most 250 ms, but more idle wakeups. (c) Keep a
  new region pending until its first value: does not close the window (the watcher callback runs before `nextWith`
  registers; RC section 2.1).
- D-17. Settle contract and probe (S1 measures). (a) REC if G1 is GO: `Settle.Quiescent(100 ms, confirm = 2)` with the
  public `Scheduler.status()` probe and no reflection (DK F-1). (b) If G1 is GO only with the reflection: keep it, with
  the loud fallback and the canary. (c) If G1 is SOFT: keep the strict kit, and add `awaitScreen(predicate, timeout)`
  as an extra helper that a test uses only after a first key (IR Q3: the kit stays strict). (d) If G1 is NO-GO: ASK
  THE USER. The options then are an "eventually consistent within `max`" frame contract with auto-wait kits (this
  reverses the strict-kit choice), or a pinned Kyo with the upstream change as a blocker for 1.0.
- D-18. kyo-ui transitive jars (+15.2 MB; RC section 3.1; S4 measures). (a) REC: accept, and state the size in the
  docs. (b) Exclude kyo-http: not safe; `UI.Href.Absolute` holds a `kyo.HttpUrl` (kyo-ui `UI.scala:499`). (c) Wait for
  an upstream module split.
- D-19. Drift of the copied kyo-ui rules (S3 measures). (a) REC if G3 is GO or SOFT: the tasty-query surface guard
  (RC-12) and a Kyo bump is its own PR. (b) If G3 is NO-GO: ASK THE USER: pin Kyo to RC7 for the first release, and
  move the runner SPI ahead of the change notice in the upstream draft.
- D-20. Runner-mounted facade components (S7 probes). (a) REC if G7 is GO: ZIO and Cats `component(key)` and
  `Component` mounted by the runner (FC-17), and the facade `Component.mount` drops (FC-D11). (b) If G7 is NO-GO: ASK
  THE USER. The only fallback keeps the build-time `Component.mount` and no runner-mounted facade components. This is
  against DECISIONS round 5 ("the runner allocates state at mount").

### 3.3 Before T1 and T2

- D-21. `InputPort` element. (a) REC: the crossterm `Event`: one mapping path, so the input-thread tests also test
  `EventMapping`. (b) A runner message: simpler test scripts, but headless runs do not test the mapping.
- D-22. Inline viewport (`Viewport.Inline`). (a) REC: defer. It needs new `Canvas` code (the runner draws on the
  `Backend`, not on a jatatui `Terminal`; OS section 1.1 item 4), and no user asked for it. Add it later as one small
  PR. (b) Build it in T1: about +60 main lines and the PR 5 test cases.
- D-23. Input bursts. (a) REC: one frame per event, measured by `BurstProbe` in P1. The prototype is measured this
  way, a bracketed paste is one message, and `Headless` runs the same path as the real session. (b) One frame per
  input batch (old design, up to 512 events): fewer frames for a burst, but the test kit then does not run the
  production path unless a test drives `InputPort` and `EventMapping` (J-D graft 12). DK D-16 (batch in the session,
  one frame per key in `Headless`) is rejected for this reason.
- D-24. Ctrl-C (IR Q2). (a) REC: the Ctrl-C KEY is a quit with exit code 0; 130 and 143 only for `kill -INT` and
  `kill -TERM` from outside (PR 5 `TuiApp` behaviour). (b) 130 always.

### 3.4 Before F1-F4

- D-25. Facade generator choices. Each row has a recommendation; the user can accept all in one answer.

| Id (FC) | Question | Options | REC |
| --- | --- | --- | --- |
| FC-D2 | Kyo data types through the aliases (FR O2, Q1) | (a) allow and document; (b) opaque event types with accessors that return Scala types (`targetId: Option[String]`), short allow-list for the rest; (c) mirror every data type | (b): it removes the leak that handlers meet most, at small cost. NOTE: (a) and (b) leave Kyo DATA types reachable through members of the aliased types (`kyo.Maybe`, `kyo.Chunk`, `kyo.ConcreteTag`, `kyo.Schema`, `kyo.HttpUrl`: 17 leak lines in FR `logs/fixer-generate-full.txt`; FR O2). This is a partial exception to DECISIONS round 5 ("must NOT see Kyo types"). Only (c) meets round 5 fully. The user confirms the exception, or takes (c) |
| FC-D3 | Drag and drop (FR O1, Q2) | exclude the 9 names; or generate the full forms | Exclude: the runner has no mouse (D-30). The mirror then has no drag-and-drop API at all, so "ZIO / F[_] at every effect point" (DECISIONS round 5) holds for the API that the mirror has |
| FC-D4 | Cats ref type (FR O3, Q3) | fs2 `SignallingRef` (run-time check); or `UiSignallingRef[F, A] extends SignallingRef[F, A]` in the setters (compile-time check) | The subtype |
| FC-D5 | Cats quit (IR Q1) | explicit `quit: F[Unit]`; an `IOLocal` quit for `IO` only; one JVM-wide run | Explicit only (ZIO also keeps `Tui.quit` through a `FiberRef`) |
| FC-D6 | Mirror scope | all 52 HTML elements; or only the supported ones | All 52 (the core logs unsupported ones, RC-14) |
| FC-D8 | ZIO handler result | `Any`; `Any` + a `NotGiven` guard against a nested ZIO; `Unit` | `Any` + guard, only if S7 shows clear overload resolution; else `Any` |
| FC-D9 | Drift check | in the `check` alias; or a separate CI step | In `check` (local = CI), unless S5 shows a high cost |
| FC-D10 | Generated code format | scalafmt exclude for `**/src/*/generated/**`; or format in the generator | Exclude |
| FC-D11 | Facade `Component.mount` | drop; or keep next to the runner-mounted form | Drop, if G7 is GO (D-20) |
| new | `Signal` clash in the Cats mirror (FC summary 15) | (a) keep the name, document the clash with `fs2.concurrent.Signal`, pin it with the name-clash test; (b) rename the Cats mirror type (for example `UiSignal`); (c) take fs2 `Signal[F, A]` in the setters (not prototyped) | (a) for the first release |

- D-26. Generated code and the warning policy (D-8). Fact: the catch-all overloads have an unused `other` parameter
  (FC summary 7). (a) REC: the generator writes `@scala.annotation.nowarn("msg=unused")` on each catch-all only. Every
  other warning in generated code stays fatal, so a generator bug fails the build. S5 checks that the mirrors give 0
  warnings. (b) A blanket `-Wconf:src=.*/generated/.*:s`: one line, but it hides all warnings in generated code. Take
  (b) only if S5 finds a warning kind that the generator cannot avoid. The drift check pins the choice.
- D-27. ZIO version literal. (a) REC: keep it in `modules/zio/build.sbt` and read it from `examples/zio.sbt`. A
  change does not mark core and cats unreleased. (b) Move it to `project/Versions.scala`.
- D-28. Long handlers (FR F7). (a) REC: document "a long handler freezes the UI; use `fork` / `background`" for the
  first release, with a test. (b) The runner forks long handlers: this changes kyo-ui's inline dispatch semantics.
- D-29. Screen-parity test (old-stack D12). (a) REC: yes, in F4: the same keys give the same screen for the Kyo, ZIO
  and Cats Counter. It replaces `FacadeParity` (dropped). (b) No.

### 3.5 Before L1, L2 and D

- D-30. First-release scope (RR open questions 2-6 and 8; RC D9). REC: scroll with focus-follows in `overflow`
  columns (L1); row min-content width, ellipsis, 256-colour fallback, one log line per unsupported attribute kind, an
  `onKeyUp` warning (L2); 1 px = 1 cell; no mouse (mouse capture stops text selection); placeholders for `Img` and
  `Chart`; all PTY cases. If scroll is out, L1 drops before it opens, and L2 sits on F4. Alternative: no L1 and no L2
  in the first release (smaller; long lists cannot be reached).
- D-31. Rules sweep. (a) REC: fold the rules sweep (AGENTS.md, README) into D as its last commits. P-10 already keeps
  AGENTS.md true in each PR, so the sweep is small. (b) A separate small PR on top of D.

---

## 4. The open PRs, the branches, and the merge order

### 4.1 PRs 3, 4 and 5 (D-2 option A)

- The user closes PRs 5, 4 and 3 (top first) after C1 is open, or tells Claude in the chat to close them.
- Each close comment links the new stack and gives the place of each UNRESOLVED review thread. GitHub shows 8
  unresolved threads, all from the bot, none from a human (`gh api repos/yuri4n/tui4s/pulls/{3,4,5}/ccr/review_threads`,
  this session):

| PR | Thread | What it says | Where it goes |
| --- | --- | --- | --- |
| 5 | `internal/CountingBackend.scala:33` (P1) | A draw races the restore from the shutdown hook. | FIXED in T1: the restore waits for a draw in progress; the `close()` guard moves into `Canvas`. Own commit, regression test, negative control. |
| 5 | `internal/Crossterm.scala:103` (P2) | An `UnsatisfiedLinkError` is reported as `NativeAccessDenied`. | FIXED in T1: its own `TuiError` message. Own commit, test. |
| 4 | `internal/Session.scala:150` (P2) | The input thread leaks when the acquire fails after `t.start()`. | FIXED in T1: the input thread stops on a failed acquire. Own commit, test. |
| 5 | `examples/.../counter/CounterApp.scala:8` (P1) | The example Scaladoc says `sbt runMain`. | FIXED in T1 (the Counter lands there) and T2 (the other examples): each example Scaladoc names the launchers. Acceptance item. |
| 3 | `Keymap.scala:159` (P2) | Reject key bindings that a terminal cannot emit. | OBSOLETE (`Keymap` drops). The lesson carries: T1 `EventMappingTest` asserts canonical events (Shift with a printable key arrives as the upper-case char; BackTab arrives as Shift-Tab). |
| 3 | `testkit/TuiTest.scala:166` (P2) | The test kit accepts a paste when paste mode is off. | OBSOLETE (`TuiTest` drops). The lesson carries: C3 `Headless.paste` acts as a real terminal does; with `paste = false` it sends the text as single keys. Test in C3. |
| 4 | `internal/Trace.scala:186` (P2) | The trace drops mouse modifiers. | OBSOLETE: P1 writes a new trace schema, and the first release has no mouse (D-30). Note in P1: keep the modifiers when mouse support comes. |
| 4 | `testkit/LiveTest.scala:121` (P2) | A nullable final state is not published. | OBSOLETE: `LiveTest` drops, and a kyo-ui app has no single state. |

- After the close, the old branches stay unchanged (section 4.2). Note: when PR 1 merges and `chore/cleanup` is
  deleted (D-4 b), closed PR 3 cannot be reopened, because its base is gone. This is acceptable; the branch stays.

### 4.2 The other branches

| Branch (head) | Plan | Reason |
| --- | --- | --- |
| `chore/cleanup` (PR 1, 6f70179) | The user merges PR 1 now with a merge commit (D-6). | Green; nothing of the old model. |
| `docs/docus` (PR 2, 8f485c2) | Keep open until D (D-7 a). At D: merge the L2 tip into it (merge commit), change its base, add the content. | No rewrite, no early deploy, no docs rule on the code PRs. |
| `feat/core-1-model-view` (850a83c), `feat/core-2-engine` (5a441a5), `feat/core-3-terminal` (666a8e3) | Keep unchanged. They are the source of the carried files and of the delta guides. | Non-destructive. |
| `feat/zio` (0c8c00d), `feat/cats` (9f4cc05), no PR | Keep unchanged. F1-F4 carry files from them. | Same. Section 7.7 places the 14 confirmed ZIO findings and the Cats open issues. |
| `wip/handoff` (1d100ed) | Never merge. Keep it until the stack merges. Delta guides cite the COMMIT `1d100ed`, not the branch name, so a later push of notes cannot change a reference. | The prototype files live there. |
| `chore/cleanup-7p2rll` (6f70179) | Not in the inventories; same head as `chore/cleanup`. Ask the user if it is still needed. Do not touch. | Unknown origin; harmless. |
| `first-take` (05599dd) | The user's own branch. Do not touch. | Not part of this work. |
| `spike/sbt-port` (local only) | Created by W0 in a local worktree; never pushed; deleted after gate 0. | Throw-away. |
| New: `feat/kyoui-01-render` ... `feat/kyoui-13-layout-polish` | Created by the PR workflows, each from the branch below it. | Section 6. |

How files carry: `git show <ref>:<path> > <new path>`, and the commit message names the source ref and commit.
Never `git checkout <ref> -- <path>`: paths and packages change, and for the prototype the source is under the
ignored `.work/` (`.gitignore` line 42), so that command would stage ignored files (J-M section 2).

### 4.3 Merge order and mechanics

Order: PR 1 -> C1 -> C2 -> C3 -> C4 -> T1 -> T2 -> P1 -> F1 -> F2 -> F3 -> F4 -> L1 -> L2 -> PR 2 (D).

- C1 needs PR 1 below it. Today `origin/main` holds only the initial commit (79b9d9c: no `modules/`, no
  `AGENTS.md`; `git ls-tree origin/main`, critic pass). So C1 opens on `main` only after the user merges PR 1 (D-6
  a). With D-6 (b), C1 opens on `chore/cleanup`, and its base moves to `main` when PR 1 merges.
- Only the user merges. Use "Create a merge commit" (D-4 a). Bisect `main` with `git bisect --first-parent`.
- After each merge, the next PR's base must become `main`. With D-4 (b) GitHub does it. Else the user edits it.
- The user can merge in batches, bottom first (for example C1-C4 after gate 1 and C4's review).
- Review fixes: new commits on the PR branch, then `git merge <lower branch>` into each upper branch, then `sbt
  check` again on each upper branch. No rebase, no force push (P-9).
- If a squash merge happens by mistake: merge `main` into the next branch (no rewrite) before its merge.
- A merge of PR 2 to `main` can start a Vercel deploy. The user checks the Vercel Git link before that merge.

### 4.4 Who does each GitHub write

| Action | Owner |
| --- | --- |
| Push a new `feat/kyoui-*` branch, open its PR, push review-fix commits and merge-ups | Claude (the PR workflow), on branches that this plan creates |
| Push to `docs/docus` (PR 2) at D: the merge commit of the stack tip and the content commits | Claude (W14), after the user says "yes" to W14 in the chat (the branch existed before this plan; Claude made it for the user) and after the Vercel check of 6.14. New commits only, no rewrite |
| Push the `.work/` notes to `wip/handoff` (D-32 a) | Claude, after each workflow, one new commit, no force push. Only if the user takes D-32 (a) |
| Close PRs 3-5; write the close comments | The user, or Claude after an explicit "yes" in the chat |
| Change a PR base | The user, or GitHub itself with D-4 (b) |
| Merge any PR | The user only |
| Change repo settings (auto-delete, merge buttons, ruleset) | The user only |
| Delete any branch | The user only (after the stack merges) |
| Post the upstream issue in getkyo/kyo | The user, or Claude after an explicit "yes" in the chat |
| Release a module, deploy the docs | Not in this plan. The user only (AGENTS.md "Releasing"; PR 2 AGENTS.md "Never deploy") |

### 4.5 After the stack (not part of this plan; needs the user)

A release checklist for later, so that nothing is lost:

- A human look check in macOS Terminal and iTerm2 (HANDOFF "Open items": PR 5 look and feel still needs a human).
- `examples/ptycheck/run.sh` green; the bench probes on an idle machine; numbers in `bench/BASELINE.md`.
- Release order core, then zio and cats (AGENTS.md "Releasing"); `--dry-run` first; only with the user's approval.
- After the first release of a module: set its `compat.sbt` to `BinaryCompatible`, and decide if `tui4s.internal.**`
  gets a MiMa exclusion filter (`private[tui4s]` seams are public in bytecode, RC-36).

---

## 5. Spikes and gates

### 5.1 Rules for the spikes

- Spikes are throw-away code in `.work/spikes/<id>/` (git-excluded). The sbt spikes use one local worktree,
  `.work/worktrees/spike-sbt`, on a local branch `spike/sbt-port` from `origin/main` (or `origin/chore/cleanup` if PR 1
  is not merged). It is never pushed. The scala-cli spikes copy the prototype folders; no prototype file changes.
- Each spike agent writes `.work/spikes/<id>/REPORT.md` early and adds to it as it works. Each number names its log
  file. Each run logs the load average and `nproc`.
- At most 4 agents at once, at most 2 that build. A building agent starts only when another building agent ends.
- Timing numbers count only from a quiet machine. The last agent (M) re-runs every timing gate alone.
- Toolchain: section 6.0. scala-cli always with `--jvm system`. Maven Central sends HTTP 429 under parallel
  downloads: retry with a backoff (HANDOFF).

### 5.2 The spikes and their gates

Gate words: GO = the plan continues as written. SOFT = it passes with a known limit, and the plan adds the change
that the gate names. NO-GO = it fails. A NO-GO that changes a user decision says "ASK THE USER".

| Spike | Question and method | Gate: GO / SOFT / NO-GO | What changes |
| --- | --- | --- | --- |
| S1 settle (K1, K2, K11) | Can the settle reach "no split frame" with a public Kyo API, also under a CI-like load? Scala-cli copy of the runner. (1) A probe variant on `Scheduler.status()` (public; `kyo-scheduler/kyo/scheduler/Scheduler.scala:584`, `top/Status.scala:3-12`; DK F-1), no reflection; settle cost p50/p99 against the reflection probe. (2) Glitch probe (`dev/Glitch.scala`), splits counted by `FrameStats.updates` (not the bench "late frames" column: RR skeptic "refuted" 2): 20 000 events with no load; 20 000 under the 2x2 pinned contention of RR N1; 20 000 with 3 JVMs on 2 CPUs (`taskset -c 0,1`) as a CI model. (3) Each run TWICE: with the Kyo defaults and with the `SchedulerDefaults` values (`cycleIntervalNs` 10 ms, `blockingMonitorIntervalNs` 20 ms, `concurrencyCollectIntervalMs` 100; PR 5 `spi/SchedulerDefaults.scala:22-27`). (4) "Settle timed out" per run. (5) Split frames per region kind (`Reactive`, bound ref, `Foreach`), with and without a pull check: after the settle, read `current` of each bound and list region and compare it with the applied value (derived signals compute `current` synchronously: DK F-4; a `Reactive` region holds a `UI` with closures, so the check does not work there). (6) The 9 prototype tests 5 times under the CI model. | GO: 0 splits with the `status()` probe in all runs and both configs; `status()` costs at most +50 us p50; tests 5 of 5. SOFT: 0 splits with no load and at most 1 per 10 000 under load; or GO only with the reflection. NO-GO: more than 1 split per 10 000 under load, or a test failure under the CI model. | GO: C2 uses `status()`; the canary tests every probe call and field (`busyWorkers()` is new in RC7: DK F-2). SOFT: D-17 (c) or (b). NO-GO: ASK THE USER (D-17 d). Expected outcome: SOFT (RR N2; the RR skeptic saw 1 settle timeout per contention log). |
| S2 R1 and R2 (K3, K4) | (1) R1: watchers, component effects and hook bodies start with `Fiber.init` in a `Scope` that the run owns (RC 2.1 option a). Leak probe (IR `stress/LeakProbe`): 300 interrupted runs. Live-watcher count after an interrupted run. An order test: no paint after the canvas closes, and the release does not wait on a watcher. (2) R2 REPRODUCER that can fail: a test seam in the watcher callback (`Mount.watch`: `signal.observe(v => inbox.put(...))`) adds a delay after the first value. Kyo's `observe` reads `current`, runs the callback, then registers `nextWith` (`kyo-core/kyo/Signal.scala:101-107`), so the delay widens exactly the window. Start, write at once, read the screen within 300 ms: it must be RED without the fix and GREEN with D-16 (a). (3) The IR split probe on plain Kyo, the ZIO glue and the Cats glue (copies of the integration prototype): 1000 starts with no load, 600 under contention. (4) Facade cancel: interrupt the ZIO `Tui.run` and cancel the Cats `Tui.run` on a headless control, then count live watchers (IR "What does not work" 1: not measured). | GO: at most 10 KB per interrupted run (now 1754 KB); 0 live watchers; order test green; seam test red-then-green; 0 first-key splits in all runs. SOFT: R1 GO; the seam test is green; splits drop 10x or more but not to 0. NO-GO: option (a) breaks the end order, or the splits do not drop. | GO: RC-8, RC-9 into C2/C4 as written; probes become ignored stress suites. SOFT: D-16 (a) + (b) (a 250 ms repair interval for young regions) and the kit helper of D-17 (c). NO-GO on R1: RC 2.1 option (b) (last world in an `AtomicRef`, `unmountAll` in `Sync.ensure`); T1 `Session` release order follows it. NO-GO on R2: ASK THE USER (it touches the strict kit, IR Q3); a time-boxed cause spike; C2 then ships with no R2 claim. |
| S3 kyo-ui drift (K5, K13) | Download the sources jars of kyo-ui RC5, RC6 and RC7 with coursier (a download, no build). Diff `UI.scala` (`Ast` classes, `Attrs` fields), `Style.scala` (`Prop` cases), `internal/ReactiveUI.scala`, `ReactiveRegion.scala`, `UIEvent.scala`, `EventHandler.scala`, the focus rules in `HtmlRenderer`. Count changed names per RC. Mark each change that needs a change in `Dispatch`, `Mount`, `Props` or `Layout`. Write the RC-12 tasty-query guard as a small scala-cli program; run it on the RC6 and RC7 jars. | GO: at most about 10 changed names per RC and no change of a dispatch or region rule. SOFT: more names, no rule change, the guard catches all. NO-GO: a dispatch or region rule changed between two RCs. | GO/SOFT: RC-12 as written (C1-C3). NO-GO: ASK THE USER (D-19 b). |
| S4 kyo-ui weight (K6) | Scala-cli assembly of the Counter; run it in a PTY with `-Xlog:class+load:file=<log>`. Classes loaded per jar; native libraries other than crossterm; median time to the first frame (10 runs); a build that excludes kyo-http (a link error confirms RC 3.1). | GO: no class of kyo-http, kyo-net or kyo-ffi loads; first frame at most 1.0 s (now 0.8-0.9 s, RR "Real PTY run"). SOFT: some load, cost under 100 ms. NO-GO: threads or native code at start, or more than 100 ms. | GO/SOFT: D-18 (a). NO-GO: D-18 still (a) (kyo-ui IS the API); the upstream draft moves the module split up; the docs name the start cost. |
| S5 generator in sbt (K7) | In the `spike-sbt` worktree (after S6): add `codegen` (Scala 3.9.0, `publish / skip`, tasty-query 1.9.0, kyo-ui, no `dependsOn`). Port `proto/kyoui-facades/gen` (781 lines). Class path, Kyo version and targets as task inputs (FC-2). Fork with `-Xss16m`. Write the ZIO full mirror and compare it with `full-zio/generated/ZioUiFull.scala`. Write the Cats FULL mirror for the first time (FC summary 3). Compile both with their runtime under the real flags and fatal warnings (D-8, D-26), against zio 2.1.26, cats-effect 3.7.1 and fs2 3.14.0. Measure what `mirrorsCheck` adds to `sbt check`, cold and warm. Add an unknown setter: the run must fail. | GO: ZIO mirror byte-identical (except the header version line); Cats mirror compiles; 0 warnings with D-26 (a); `mirrorsCheck` adds at most 60 s cold and 15 s warm; the unknown setter fails. SOFT: small rule fixes (under about 50 generator lines), or D-26 (b) is needed. NO-GO: the generator cannot run in sbt, or the check costs much more. | GO: F1 as written. SOFT: F1 grows by the fixes. NO-GO: the drift check becomes a CI step (FC-D9 b); if sbt cannot host it, a pinned scala-cli project in `codegen/` (a new tool in CI: ask the user). If the Cats mirror fails: F3 ships the elements that compile and lists the rest (FC-D6 b, Cats only). |
| S6 strict flags (K8) | In the `spike-sbt` worktree (first): copy the runner sources into `modules/core` (package per D-10), add kyo-ui, compile with the real flags plus `-Werror` (D-8). Count errors and warnings per kind and file. Check each `.nn` on a jatatui or JDK result against its source or Javadoc (a wrong `.nn` is a run-time NPE). Copy the facade runtimes and prototype mirrors into `modules/zio` and `modules/cats`; compile against fs2 3.14.0. Run scalafmt; count changed lines. Run the 9 runner tests (munit is fine here). | GO: fewer than about 300 changed lines; no unsafe `.nn`; fs2 3.14.0 compiles; tests 9 of 9. SOFT: more lines, still mechanical. NO-GO: explicit nulls force a design change, or fs2 3.14.0 breaks `KyoSignallingRef`. | GO/SOFT: RC-18 as written; the counts are the size input for C1-C4. NO-GO on nulls: `import scala.language.unsafeNulls` in the jatatui-facing files only (`Paint`, `Canvas`, `TerminalIO`, `Crossterm`), with an AGENTS.md rule. NO-GO on fs2: pin fs2 in `modules/cats/build.sbt` to the last good version, with the reason. |
| S7 facade components (K9) | Scala-cli copy of the integration prototype. Add to the core copy `Tui.run(options)(quit => ui)`, `Abort[TuiError]`, and an overridable state key on `Component` (FC-17; `Mount.scala:117` keys by `getClass.getName`). Write the ZIO and Cats `component(key)` and `Component` over the core hooks. Tests: state survives a parent re-render; two facade `Component` classes at one position do not share state; the ZIO `Scope` finalizer and the Cats `Resource` release run at unmount; quit works with no extra Kyo fiber; `NotATerminal` reaches the ZIO error channel. Also probe FC-D8 (a `NotGiven` guard against a nested ZIO) and the ZIO console override (`ZIO.withConsole`) with the IR output probe. | GO: all green, 3 of 3 runs. SOFT: it works, but the Cats form needs another shape (for example a `Resource` in place of a scope link). NO-GO: the scope link or the key hook does not work. | GO: FC-17 in F2/F4; the key hook in C4. SOFT: F4 takes the other shape. NO-GO: ASK THE USER (D-20 b). |

### 5.3 W0 schedule (one workflow, 6 agents)

| Order | Agent | Work | Builds | Notes |
| --- | --- | --- | --- | --- |
| 1 | A | S1, then S2 | scala-cli | Notes in `.work/spikes/s1-settle/`, `s2-r1r2/` |
| 1 | B | S6, then S5 | sbt (worktree `spike-sbt`) | The only sbt process of the repo at this time |
| 1 | C | S3 part 1: download and diff | none | Read only |
| 2 | D | S7 | scala-cli | Starts when A or B ends (a free build slot) |
| 2 | E | S3 part 2 (guard on RC6/RC7), then S4 | scala-cli | Starts when the next build slot is free |
| 3 | M | Re-runs the timing gates G1, G2, G4, G5 alone; skeptic pass over all spike reports; writes `.work/design/plan/gates.md` (GO / SOFT / NO-GO per gate, with evidence); puts the S1-S4 numbers into `.work/design/upstream-kyo-ui-issue.md` | scala-cli, sbt | Alone on the machine |

At most 3 agents at once, at most 2 that build. Cost: about 0.3-0.4 M tokens per agent (HANDOFF: the resume run of 7
agents used about 2.0 M), so about 2.0-2.4 M.

### 5.4 Review gates and the WIP limit

| Gate | When | The user reviews | Blocks |
| --- | --- | --- | --- |
| 0 | After W0 | `gates.md`; decisions of section 3.2; the upstream draft with numbers | C1 |
| 1 | C2 is open and green | The public run API: `TuiOptions` (core set), `Headless` (with quit as a value), `Screen`, `CellStyle`, `FrameStats`, `Settle` | T1 (C3 and C4 may run under the WIP limit) |
| 2 | F1 is open and green | The generated ZIO API (the mirror file IS the API) and the ZIO runtime types | F2 and F3 |
| 3 | L2 (or F4, if D-30 drops L1 and L2) and D are open and green | The whole stack | The merges |

WIP limit (P-11): implementation runs at most 2 PRs above the newest PR that the user has reviewed. A gate is a hard
stop for the PRs that it blocks.

### 5.5 Guards that stay (early warning for later Kyo or kyo-ui bumps)

| Guard | Owner PR | Finds |
| --- | --- | --- |
| Settle canary: every probe call and field (`status()` fields, or `currentWorkers` + `busyWorkers()`) | C2 | K2 at the next Kyo bump |
| kyo-ui surface guard (tasty-query): `UI.Ast` classes, `Style.Prop` cases (C1), `currentWorkers` / K5 (C2), `Attrs` fields and `Foreach.applyTyped` still private / K2 (C3) | C1, C2, C3 | K5 |
| Glitch test (500 events, splits by `updates`) and the R2 seam test | C2 | K1, K3 |
| Ignored stress suites (`-Dprobe.*`): split, leak (C2); glitch probe in both scheduler configs (P1) | C2, P1 | K1, K3, K4, run by hand before a release |
| Live-watcher count after an interrupt (core, ZIO, Cats) | C2, C4, F2, F4 | R1 class of leaks |
| `EventMapping` exhaustivity `-Wconf ...:e` | T1 | New crossterm event types |
| CI `terminal` job (ptycheck) | T1 onward | Real-terminal behaviour |
| Fatal warnings (D-8) | C1 | Warnings that pile up |
| Mirror drift check; fail-closed generator | F1 | Hand edits, stale mirrors, new kyo-ui effect points |
| `SurfaceLeaks` and the generated guard spec; the lift-trap canary | F1, F3 | Kyo types in facade signatures; the lift trap |
| Name-clash compile tests | C2 (core), F1, F3 | Ambiguous imports |
| Screen-parity test | F4 | Facade behaviour drift |

---

## 6. Per PR

### 6.0 Rules, acceptance and workflow shape for every PR

Rules (the PR body says how the PR meets them; the PR template of C1 has a section for each):

| Rule | What |
| --- | --- |
| P-1 One purpose | The title is one sentence that says what WORKS after the merge. |
| P-2 Size | At most about 1500 changed lines of hand-written main code. Tests, generated files and carried files with a delta guide are extra. If the real count is higher, split at a file border before the PR opens. |
| P-3 Working base | The base PR is green. The new PR adds to it. |
| P-4 Final shape first | A public type or method lands in its final shape. Later PRs only add members or fields. |
| P-5 Never port the replaced | Do not port code that a later PR of this stack replaces. |
| P-6 Fix with the code | A known bug is fixed in the PR that brings the code, in its own commit, with a regression test. The PR body shows that the test FAILS on the code before the fix (negative control, as IR `logs/negative-control-*.log`). |
| P-7 Commit map | (1) carry the files unchanged (new path and package only); (2) format only, and remove the `// claude <date>` marks of the prototype (11 in the runner `src/` and 4 in `test/Apps.test.scala`: `grep -c`, critic pass; PRs 3-5 have 0: DK F-12); (3) strict-flag fixes; (4) one commit per behaviour change, each with its test. The carry commit may not compile alone: bisect with `--first-parent`. |
| P-8 Delta guide | A table: new file, source (`<commit>:<path>`), and the command that shows the delta, for example `git diff 1d100ed:.work/proto/kyoui-runner/src/tui4s/kyoui/internal/Mount.scala HEAD:modules/core/src/main/scala/tui4s/internal/Mount.scala`. |
| P-9 No rewrite | Review fixes are new commits. Then each upper branch gets `git merge <lower branch>` and a new `sbt check`. |
| P-10 Docs true at each merge | Each PR changes the AGENTS.md and README lines that its content makes true or false. |
| P-11 WIP limit | Implementation runs at most 2 PRs above the newest PR that the user has reviewed. Gates are hard stops (section 5.4). |
| P-12 Not in this PR | The body lists what is NOT in the PR and the PR that brings it. |
| P-13 Zero warnings | With D-8 (a) the build fails on a warning. The body says "0 warnings" (or the count, with D-8 c). |
| P-14 New sbt projects | A new project (`bench`, `codegen`) joins `root.aggregate` with `publish / skip := true`, so `sbt check` compiles and tests it. `versionPolicyCheck` stays green, as for `examples` (J-M section 5 item 2). Like `examples`, it sets no `version` and no `publishTo` (AGENTS.md "Dependencies and versions"; `build.sbt` header). A version that it shares with a module goes in `project/Versions.scala` (for example `Versions.tastyQuery`, C1). |

Acceptance for every PR, in addition to its own list:

- `sbt check` is green locally, run ALONE on the machine (no other sbt or test JVM), and the CI job `sbt` is green.
- From T1 on: the CI job `terminal` (`examples/ptycheck/run.sh`) is green.
- Timing-sensitive suites (C2, C3, C4, T1, F1-F4): green in 3 local runs in a row; ONE of the 3 runs is under CPU
  contention (`taskset -c 0,1` with 2 busy loops), as a model of the private-repo CI runner (D-9).
- The reviewers' findings are fixed or answered; the skeptics confirm the fixes.
- `compat.sbt`: no change. All three stay `Compatibility.None` (verified on `origin/chore/cleanup`); nothing is
  released, so `versionPolicyCheck` has no earlier release to compare with. A change in `project/Versions.scala`
  marks all modules unreleased: no cost now.

Toolchain line for every agent prompt (cloud machine; D-11): JDK 25 at `/usr/lib/jvm/java-25-openjdk-amd64` (`java
-version` must show 25); `sbt` and `scala-cli` are wrappers in `/usr/local/bin`; scala-cli always with `--jvm system`;
NEVER `source .work/env.sh` here (it is for the Mac); at most one sbt process at a time on the machine, also across
worktrees (AGENTS.md "Toolchain": one sbt process in this repo; the shapes below run 1 build at a time); worktrees in
`.work/worktrees/<branch>`; Maven Central HTTP 429: retry with a backoff; never start a TUI with `sbt run`; write notes
early in `.work/impl/<pr>-*.md`; end each commit message and PR body with the attribution lines that the session gives.

Workflow shapes (user limits: at most 4 agents at once, at most 2 that build, one workflow at a time, announce more
than 10 agents):

| Shape | Agents | Order | At once | Builds at once |
| --- | --- | --- | --- | --- |
| 8 | implementer, 3 reviewers, 3 skeptics, fixer | implementer -> reviewers (parallel, read only) -> skeptics (parallel; read only, at most one runs sbt in the PR worktree) -> fixer -> 3 final runs alone | 3 | 1 |
| 6 | implementer, 2 reviewers, 2 skeptics, fixer | same | 2 | 1 |
| 4 | implementer, reviewer, skeptic, fixer | same | 1 | 1 |
| fix round | fixer, skeptic | after the user's review: fix -> skeptic -> merge-up into the upper branches, `sbt check` on each | 1 | 1 |

The reusable one-PR workflow script is on the Mac (HANDOFF "Facts the next session must keep"). A cloud session writes
it again once, as part of W1.

Token basis (HANDOFF): the old one-PR workflows of 8 agents cost W1 2.1 M, W2 2.4 M, W3 2.4 M; the resume run of 7
agents cost about 2.0 M. So about 0.27-0.30 M per agent. Estimates: shape 8 about 2.1-2.4 M, shape 6 about
1.6-1.9 M, shape 4 about 1.0-1.3 M, a fix round about 0.4-0.6 M. These are estimates, not measurements.

### 6.1 C1 `feat/kyoui-01-render`: a kyo-ui value renders to cells

- Content: RC-16 (`modules/core/build.sbt`: kyo-ui with `Versions.kyo`; drop `jatatui-widgets` (D-12); test deps
  `kyo-test-runner` and `tasty-query` (new `Versions.tastyQuery = "1.9.0"`: core tests, codegen and facade tests use
  it); `Test / fork` and the JVM flags of PR 5; the PR 3 `-Wconf` for the `@nowarn` noise; the description line);
  D-8 fatal warnings in `build.sbt`; RC-1 package layout (D-10); RC-18 format and flags for these files; RC-19 test
  framework set-up (kyo-test replaces munit in core); OS-37, OS-38, OS-39; OS-12 part (`internal/package.scala`
  `discard`, PR 3); RC-12 part (surface guard: `UI.Ast` element classes and `Style.Prop` cases against
  `src/test/resources/kyo-ui-surface.txt`); RC-17 part (accept the jars, D-18); OS-13 part (new `package.scala`
  Scaladoc); OS-47 part and OS-48 part (AGENTS.md and README "What tui4s is": a terminal backend for kyo-ui;
  modules table "UI DSL: kyo-ui"); `project/Versions.scala` (the `kyo` comment says kyo-zio and kyo-ui use it);
  `.github/pull_request_template.md`.
- Files: proto `internal/Geometry.scala` 60, `Props.scala` 258, `Layout.scala` 461 (with the overflow fix),
  `Paint.scala` 273 (with `Canvas`); a `private[tui4s]` render helper for tests (about 20 lines). `Paint` needs no
  `FrameStats` (a comment only, line 117) and `Layout` needs only `Scene` and `Geometry` (DR section 9 item 6). The
  `Img` and `Chart` placeholders stay (`Paint.scala:224-227`).
- Tests: golden screens for borders, colours, row and column flex, gap and padding, overflow clip, text wrap in a
  column, wide characters, the placeholders; the surface guard.
- Acceptance: the surface guard fails when one name is removed from the list (shown once in the PR body); no
  `jatatui.widgets` import; 0 warnings. Real terminal: not applicable.
- Not in this PR: `World`, `Mount`, `Runner`, `SchedulerProbe`, `Headless`, `Screen` (C2); `Dispatch` (C3); terminal
  (T1).
- Size: ~1050 prototype lines, ~1300 after the format (S6 gives the real number); tests ~450.
- Workflow: shape 6 (+ the one-time rewrite of the workflow script); about 1.8-2.1 M.
- Decisions before start: D-8, D-10, D-12, D-18.

### 6.2 C2 `feat/kyoui-02-regions`: signals drive the screen

- Content: proto `internal/World.scala` (no key and no component cases), `Mount.scala` (no component branch; about
  150), `Runner.scala` (no key path; about 200), `SchedulerProbe.scala` (per G1: `status()` or reflection). RC-6
  (`FrameStats`, `FrameCause` public; `tui4s.spi.Settle`; a "settle timed out" counter in `FrameStats`, RR O6). RC-3
  core set (Kyo-free `TuiOptions`: `settle`; durations per D-14). RC-7 (`testkit.Headless`: `start(w, h,
  options)(quit => ui)`, `screen`, `lines`, `nextFrame`, `drainFrames`, `awaitEnd` on the real clock, `quit`,
  `finished`, `resize`; it quits and waits before its scope ends; no `TestBackend`, no jatatui `Cell`, no
  `kyo.Chunk` in its API). RC-2 part and R5 (the quit value, `quit: Unit < Sync`, D-15). RC-28 + OS-12 + OS-15 part
  (`testkit.Screen` and `ScreenTest` from PR 3 with `CellStyle`, D-13). RC-8 for region watchers (R1: a `Scope` that
  the run owns; an internal live-watcher gauge for tests). RC-9 (R2 per D-16, with the seam of S2). RC-11 (settle
  hardening per G1: loud fallback; canary over every probe call; no stderr print at `Runner.scala:145-146`;
  `Settle.Hops` in `spi`). RC-12 part (pin K5). RC-15 part (resize). RC-19 rest (suites in sequence; D-9
  `Tags.limit`). RC-30 part (IR split and leak probes as ignored stress suites). OS-16 lessons (name-clash test of
  `import kyo.*` + `import tui4s.*` + `import tui4s.testkit.*`; `CanEqual` of `FrameStats`, `TuiOptions`, `Screen`).
  OS-34 rule (never join the run fiber from an interruptible wait). RC-36 (public API review; the body lists every
  `private[tui4s]` seam).
- Tests: the Counter, two counters, Todo and Dashboard fixtures, where the TEST writes a `SignalRef` (they use plain
  `Signal.initRef`: `proto/apps/Counter.scala:11`, `Todo.scala:69`, `Dashboard.scala:29`); idle (no frame without a
  change); glitch (500 changes, 0 mixed frames); R1 (0 live watchers after an interrupted run; the IR leak probe
  once locally: under 5 MB per 150 runs, against 1754 KB per run before); R2 seam test (red without the verify pass,
  green with it; both logs in the body); the IR split probe numbers before and after; settle canary; quit value (the
  test runs the captured `quit`, `awaitEnd` returns); resize.
- Acceptance: as 6.0; the glitch suite once more with the `SchedulerDefaults` values as test JVM flags (a one-off
  `set core / Test / javaOptions ++= ...`; the log in the body); grep: no `System.err` and no `println` in core main
  sources.
- Not in this PR: keys, focus, handlers, `Tui.quit` (C3); components (C4); `Tui.run` (T1).
- Size: ~1250 main; tests ~650.
- Workflow: shape 8; final runs alone (the settle reads the JVM-wide scheduler); about 2.1-2.4 M.
- Decisions before start: D-9, D-13, D-14, D-15, D-16, D-17.
- GATE 1 after this PR.

### 6.3 C3 `feat/kyoui-03-input`: keys reach the UI

- Content: proto `internal/Dispatch.scala` 242 (bubbling, `stopPropagation`, the bound ref written before `onInput`
  (A3), Enter/Space activation, form submit, focus group, focus trap); `Msg.Key` and the key path of `Runner`;
  `Tui.quit` (the `Local`; RC-2 part); `TuiOptions.ctrlCQuits`, `autoFocus`; `Headless.key`, `char`, `typeText`
  (returns `Seq`), `paste` (with `paste = false` it sends single keys, as a real terminal does: lesson of thread
  `TuiTest.scala:166`). OS-11 + OS-15 part: the grapheme editing of PR 3 `TextField.scala` (`BreakIterator`,
  Ctrl-A/E/U/K/W, "line breaks become spaces" on paste) REPLACES the prototype UTF-16 editing
  (`Dispatch.scala:121-132`) in this PR, so that code never lands (P-5). RC-14 part (`Msg.Paste`: one edit, never a
  submit). RC-12 part (`Attrs` fields; `Foreach.applyTyped` still private: K2). RC-15 part (focus group, focus trap,
  `stopPropagation`). AGENTS.md rules: type Kyo handler values `: Any < Async` (RR O5); the runner calls no
  `private[kyo]` member (RR N9). A Kyo lift-trap canary test (an untyped handler `val` compiles and never runs).
- Tests: Counter and Todo by keys; Tab order, focus group, focus trap, `stopPropagation`; PR 3 `TextFieldTest` (126
  lines) adapted (emoji, combining marks, CJK, Ctrl keys, paste); the R2 key variant (seam + Enter at once); paste
  with `paste = false`.
- Not in this PR: the crossterm mapping (T1); mouse (D-30).
- Size: ~600; tests ~500. Workflow: shape 6; about 1.6-1.9 M.

### 6.4 C4 `feat/kyoui-04-components`: both component styles

- Content: RC-4 (`Hooks`: `useSignal`, `useHold`, `useFold`, `useEffect`, `useInterval`, `component(key)`); RC-5
  (`Component` class, `ComponentSpec` registry (K3), and the overridable state key `protected def stateKey: String =
  getClass.getName`, needed by FC-17; `Mount.scala:117`); the component branch of `Mount` and `World` (`comps`,
  `pending`); `TuiOptions.pendingTimeout`; RC-8 for component effects and hook bodies (R1 at `Mount.scala:128, 147`);
  RC-15 rest (`onMount` and the hooks with no test: RR skeptic "refuted" 9); RR O7 tests.
- Tests: one per hook; the `CounterStyles` fixture (both styles); `Component` state survives a parent re-render; a
  hook component inside `when` loses its state (documented, RR O7); two `Component` classes at one position do not
  share state; a custom `stateKey`; 0 effect fibers after an interrupted run.
- Size: ~300; tests ~400. Workflow: shape 6; about 1.6-1.9 M. Decision before start: D-20 (G7 result).

### 6.5 T1 `feat/kyoui-05-terminal`: `Tui.run` on the real terminal, with CI

- Content, carried from the PR 5 head (that code already passes this repo's format and flags): RC-20 + OS-1
  (`TerminalIO` 188, `Crossterm` 155; inline viewport per D-22); RC-21 + OS-2 (`Session`, `InputThread`,
  `TerminalControl` / `HeadlessControl`: 249 lines less the engine types; `InputPort` 18 with the crossterm `Event`,
  D-21); RC-22 + OS-3 (`EventMapping` 103 to kyo-ui `KeyboardEvent`, `Msg.Resize`, `Msg.Paste`; the exhaustivity
  `-Wconf ...:e`; it replaces proto `Keys.scala` and keeps its BackTab and Ctrl-letter rules); RC-23 + OS-4
  (`OutputCapture` 179, `LogSink` 134, `TuiLog` 90; `Log.let` around the run); RC-10 part (R3: a start-time warning
  when `scala.Console.out` is not a tui4s forwarder, to the log and after the restore); RC-2 part (`Tui.run(options)
  (quit => ui): Unit < (Async & Abort[TuiError])`; `TuiRun` merges into it); RC-3 part (`paste`, poll interval,
  `inboxCapacity`, `captureOutput` (default `true`, opt-out: DECISIONS round 4; PR 5 `Options.scala:50`), `logFile:
  Option[Path]`; no `mouse` field and no `focusEvents` field: D-30 defers mouse and terminal focus events (7.2 RC-14
  row), and P-4 / P-5 forbid an option with no effect; PR 5 `focusEvents` only turns on the terminal mode,
  `TerminalIO.scala:173`); OS-8 part; OS-10.
- The three bot fixes, each in its own commit with a regression test and a negative control: `CountingBackend.scala:33`
  (the restore waits for a draw in progress; the `close()` guard moves into `Canvas`); `Crossterm.scala:103` (an
  `UnsatisfiedLinkError` gets its own message); `Session.scala:150` (the input thread stops on a failed acquire).
- Real-terminal CI in this PR (J-M graft 1; DK F-7): RC-31 part (the PTY kit: proto `pty/vt.py` 246 and `check.py`
  80 replace `analyze.py`; `checklist.exp` with the Counter rows; `run.sh`); OS-20 (the `examples/launchers` task;
  the script name from the package, the `feat/cats` form); RC-33 part (`examples.counter.Counter` and
  `CounterKyoApp`, a plain `KyoApp` main that calls `Tui.run`: the "own main" path; its Scaladoc names the launchers,
  thread `CounterApp.scala:8`); OS-41 (CI job `terminal`: JDK 25, `apt-get install expect`, `run.sh`); OS-50 part
  (`examples/README.md`); OS-47 part (AGENTS.md: the launchers row replaces `sbt "examples/runMain <main>"`; "no
  `sbt run`"; run ptycheck after a terminal change); the examples build drops `run / connectInput`.
- PTY rows (Counter, no `TuiApp` yet): alternate screen at start; raw mode while running; screens after `+` and `-`
  (checks after a partial repaint); Ctrl-C ends with exit 0 (C3 `ctrlCQuits`); `q` ends with exit 0; cursor shown;
  nothing stray after the restore; `stty -a` after = before; a pipe as stdin gives `NotATerminal`; without
  `--enable-native-access` the message names the flag; WINCH resize re-lays out the screen.
- Tests (carried and adapted): `TerminalIOTest` 507 (no inline-viewport cases if D-22 = defer; no mouse and no
  focus-change cases (D-30); the `Tui.start` case uses `Tui.run` with quit), `SessionTest` 152, `EventMappingTest` 187 (+ canonical events: lesson of thread
  `Keymap.scala:159`), `OutputCaptureTest` 187 (+ the R3 warning), `NativeTerminalTest` 88, `FakeCrossterm` 141.
  `RecordingControl` (88) and `ScriptedInput` (59) move to TEST sources: test doubles, not user API (RC-36).
- Never ported (replaced here): `Keys.scala`, `OutputGuard`, the prototype `enter` / `restore` and its input fiber,
  the `CountingBackend` class, `TuiRun`, PR 5 `spi/Headless`.
- Acceptance: as 6.0; first a local PTY run (log `.work/impl/T1-pty.log`); then the CI `terminal` job green 3 times
  before review (K17).
- Not in this PR: `TuiApp`, exit codes 130/143, scheduler flags (T2); inline viewport (D-22); trace (P1); Todo and
  Dashboard (T2).
- Size: ~1300 Scala main (~1150 carried, with a delta guide) + ~450 lines of kit and scripts (carried from the
  prototype) + 16 CI lines; tests ~1300. The largest PR; most of it is carried.
- Workflow: shape 8; about 2.3-2.6 M. Decisions before start: D-21, D-22, D-24.

### 6.6 T2 `feat/kyoui-06-app-examples`: `TuiApp`, all examples, all PTY cases

- Content: RC-24 + OS-6 (`TuiApp` for kyo-ui: `def ui`, `options`, `args`, `onExit`; the constructor calls
  `SchedulerDefaults.applyIfUnset()` and `OutputCapture.install()`, which closes R3 for `TuiApp` users (RC-10 part);
  shutdown hook; exit codes per D-24; the error printed after the restore); RC-25 + OS-7 (`SchedulerDefaults` 63,
  unchanged; it keeps the opt-out `-Dtui4s.schedulerDefaults=false` of DECISIONS round 4, PR 5
  `spi/SchedulerDefaults.scala:16-18`); RC-27 rest + OS-15 part (`TuiAppTest` 280, `SchedulerDefaultsTest` 72; `GuardTest` DROPS: it tests the
  old `NotForeign`, DR section 9 item 2); RC-33 + OS-18 (examples from `proto/apps/`: `CounterApp` (a `TuiApp`), two
  counters, `CounterStyles`, Todo, Dashboard, Widgets; munit suites on `Headless`; each Scaladoc names the launchers);
  RC-31 rest + OS-19 (all PTY cases: `kill -TERM` 143 and `kill -INT` 130 with the terminal restored; an early
  `println` under `TuiApp` goes to the log (IR output probe); Todo typing, Enter, Tab, Space, paste as one edit, save;
  Dashboard pause and live; Tab, Space, Esc; resize; `CounterStyles`); OS-50 (README rows); OS-48 part (README "Quick
  start": the hooks Counter and a `Headless` test; the same text as the example file); OS-47 part.
- Acceptance: as 6.0; the CI `terminal` job green 3 times with all rows; one `-Xlog:class+load` count of the real
  `CounterApp` launcher in the body (S4 measured the scala-cli assembly).
- Not in this PR: trace and bench (P1); facades.
- Size: ~650 Scala + ~250 script; tests ~550. Workflow: shape 6; about 1.7-2.0 M.

### 6.7 P1 `feat/kyoui-07-trace-bench`: measure it

- Content: RC-26 + OS-9 (PR 5 `Trace.scala` writers `Tracer`, `MemoryTrace`, `TraceWriter`; one JSON line per frame
  from `FrameStats` plus a "settle timed out" line, RR O6; `TuiOptions.trace`; a code note from thread
  `Trace.scala:186`); RC-32 + OS-21 (bench probes on the kyo-ui apps and `Headless`: latency, idle, quit, soak,
  burst (D-23 data), Dashboard; from proto `dev/Bench.scala`; splits counted by `FrameStats.updates`; JMH benches of
  `Layout` and `Paint` replace `EngineBench` and `PaintBench`); RC-30 rest (`GlitchProbe` in both scheduler configs);
  OS-40 + P-14 (the `bench` project in `root.aggregate`, sbt-jmh 0.4.8 in `project/plugins.sbt`, `.gitignore`
  `bench/results/`); RR N4, N5 (a new `bench/BASELINE.md` section: machine, method, numbers; the old budgets that
  missed on a busy machine are measured again on an idle one, HANDOFF "Open items"); OS-47 part (AGENTS.md bench rows
  and "run benches alone").
- Tests: `TraceTest` 169 adapted; a JMH smoke run (`-i 1 -wi 1 -f 1`).
- Acceptance: as 6.0; the probes run on an idle machine (not a CI gate); `sbt check` compiles `bench`.
- Size: ~1150 (Trace ~250, bench ~900); tests ~200. Workflow: shape 4 (the fixer also does the idle-machine probe
  run); about 1.1-1.4 M.

### 6.8 F1 `feat/kyoui-08-codegen-zio-dsl`: ZIO users build a kyo-ui value with ZIO types

- Content: FC-1..FC-8 (the `codegen` project: Scala 3, `publish / skip`, in `root.aggregate`, no `dependsOn` to zio or
  cats; class path and `Versions.kyo` as task inputs; both ecosystems in the rule table; fail closed on an unknown
  setter, an unknown leak, or a data type with no alias; scalafmt exclude `**/src/*/generated/**` (FC-D10);
  `@nowarn` per D-26; the `sbt mirrors` alias; `codegen/mirrorsCheck` inside `check` (FC-D9); generator tests;
  `codegen/README.md` with the kyo-ui bump steps; `Inventory.scala` is not ported). FC-23 (the ZIO mirror, all 52
  elements, `modules/zio/src/main/generated/ZioUi.scala`), FC-25, FC-26, FC-31 (opaque event types, FC-D2 b), FC-32
  (drag and drop excluded). FC-30 (the generated `GuardSpec` for ZIO with exact messages; the canary "the lift trap is
  real WITHOUT the mirror" on the core `Headless`; raw-access guards; the FC-D8 guard per S7). FC-29 + OS-17
  (`SurfaceLeaks` in `codegen/src/surface`, added to the zio tests). FC-35 + OS-32 (ZIO name-clash test). FC-9 (ZIO
  `SignalRef` (A3), `internal.Bridge`, `useSignal`, `useStream`; Scaladoc of `changes`, FR O7). FC-11 ZIO + OS-22
  (`AmbientZIOs` 131 + `AmbientZIOsSuite` 229 from `feat/zio`; FR O5 texts fixed; its attribution header stays).
  FC-36 + OS-35 (`changes` interrupt stress: the heap stays flat). FR F5, F6 (stress suite in the normal run; a
  `modify` contention test). FC-21 (zio build: the generated source root, fork flags, `Versions.tastyQuery`). FC-16
  (package `tui4s.interop.zio`; qualifier `private[zio]`). OS-30 ZIO part, OS-41 part, OS-42. FC-37 part (AGENTS.md:
  "never edit a generated file; run `sbt mirrors`"; the codegen row).
- Smoke test: the ZIO Counter, built with the mirror, runs on the core `Headless` through a `private[zio]` helper.
- Acceptance: as 6.0; `sbt mirrors` twice gives no diff; the generator exits 1 on an unknown setter (a test);
  `SurfaceLeaks` allows only the listed aliases; the guard spec covers every ZIO effect point (56 in the full mirror:
  FR `logs/fixer-generate-full.txt`); the CI `terminal` job stays green.
- Size: generator ~1000 after the format + ~150 (fail closed, tasks, event types); ZIO runtime ~300 (131 carried);
  build ~80: about 1450. Generated ~1120. Tests ~700. Workflow: shape 8; about 2.2-2.5 M.
- Decisions before start: D-25, D-26, A3, A4.
- GATE 2 after this PR.

### 6.9 F2 `feat/kyoui-09-zio-run`: ZIO users run, test and ship the app

- Content: FC-12 ZIO + OS-25 (`Tui.run(options)(app): IO[TuiError, Unit]`; `Tui.quit` through a `FiberRef` plus the
  explicit quit; logger removal in its own `ZIO.scoped` (spec-3); the `zio.Console` service replaced for the run per
  S7 (FC-27; api-6, spec-6)). FC-13 + OS-24 (`TuiApp extends ZIOAppDefault`; `args` through `ZIOAppArgs` (api-2);
  capture and scheduler flags in the constructor). FC-14 (`testkit.Headless`: a bounded `focus(id)`, `Screen`,
  `FrameStats`, `Seq`). FC-15 (re-export of the Kyo-free core types). FC-17 ZIO (runner-mounted `component(key)` and
  `Component` over the core state key; FC-D11), per G7. FC-19 + OS-28 (FR `ZioFacadeSuite` and IR `ZioOnTerminal`
  ported to the kit; `TuiAppSuite`). OS-31 (the TestClock rule, documented and tested). OS-34 (a `timeout` around
  `Headless.start` ends; never join the Kyo run fiber). FC-34 (Scaladoc: a long handler freezes the UI; D-28). FC-33
  ZIO + OS-29 (examples `ziocounter`, `ziotodo`, `ziodashboard` with `useStream`; `examples/zio.sbt`; each app has a
  quit key, IR item 5; PTY rows: keys, Ctrl-C, `kill -TERM`, `kill -INT`, `stty` restored, Todo, and the
  `ZioOutputProbeEarlyMain` probe under `TuiApp`: no ZIO output on the screen). IR F1 (no HTML stand-in), IR F2 (the
  glue in the module). OS-30 (ZIO version literal, D-27). OS-48 part (README ZIO quick start).
- New test (J-D missing item): interrupt the fiber that runs the ZIO `Tui.run` on a headless control, then count live
  watchers: 0.
- Acceptance: as 6.0; the CI `terminal` job with the ZIO rows.
- Size: ~520 glue + ~200 examples; tests ~900. Workflow: shape 8; about 2.1-2.4 M.

### 6.10 F3 `feat/kyoui-10-cats-dsl`: Cats Effect users build a kyo-ui value with `F[_]`

- Content: FC-24 (the Cats mirror, first full set, `modules/cats/src/main/generated/CatsUi.scala`; one new target line
  in the codegen task). FC-10 (`KyoSignallingRef`, proto `cats/src/Bridge.scala:62`, a file of 111 lines, against fs2 3.14.0 (or the
  pin of S6); FC-D4
  `UiSignallingRef[F, A]` in the setters, so a foreign ref does not compile; FR O3). FC-11 Cats + OS-23 (`CatsBridge`
  208, `AmbientDispatcher` 39, `CatsBridgeSuite` 264, `DispatcherSuite` 75 from `feat/cats`;
  `Dispatcher.parallel(await = false)`, FR F8; FR O5 and IR F3 texts fixed; KEEP the Apache-2.0 header and the
  "Changes from the original" list (DECISIONS round 2; FC 2.3)). FC-22 (cats build). FC-29, FC-30, FC-35 Cats parts
  (`SurfaceLeaks`; `GuardSpec`: a plain value and an `F[A]` that is not `F[Unit]` do not compile; the canary; a
  name-clash test with `cats.*`, `cats.effect.*`, `cats.syntax.all.*`, `fs2.*`, `fs2.concurrent.*`; the `Signal` clash
  per D-25). OS-35 Cats (stress suite), OS-32 Cats, OS-30 Cats part.
- Review note: the `feat/cats` code never had a review (HANDOFF: "review NOT started"). The reviewers read the
  vendored files as NEW code, not as a carried delta.
- Smoke test: the Cats Counter on the core `Headless`.
- Acceptance: as 6.0; `CatsBridgeSuite` stress ("interrupt right after the IO starts never leaks the IO, 300 rounds")
  green 3 times (it backs the "VERIFIED" text of FR O5).
- Size: ~480 hand-written (~360 carried); generated ~1120; tests ~500. Workflow: shape 8 (3 reviewers for code that
  was never reviewed); about 2.1-2.4 M.

### 6.11 F4 `feat/kyoui-11-cats-run`: Cats users run, test and ship the app

- Content: FC-12 Cats + OS-25 (`Tui.run[F]`; `Tui.resource` as a CE bracket; the `end` waiter fiber; the explicit
  `quit: F[Unit]`, FC-D5). FC-13 Cats + OS-24 (`TuiApp extends IOApp` with `args`; the CPU starvation checker off;
  capture and flags in the constructor). FC-14, FC-15. FC-17 Cats (per G7; the SOFT shape if S7 needs it). FC-20 +
  OS-28 (FR `CatsFacadeSuite`, IR `CatsOnTerminal`, `NativeTerminalSuite`: `NotATerminal`, `System.out` unchanged).
  OS-27 lessons as new tests (a cancel at any time leaves no terminal entered, `feat/cats` 9f4cc05; a cancelled wait
  does not stop the app, `ResourceSuite`; a burst of input is bounded). OS-34 Cats (the core join cascade,
  `cats-impl.md` "Open issues"). FC-33 Cats + OS-29 (`catscounter`, `catstodo`, `catsdashboard`; `examples/cats.sbt`;
  PTY rows, one of them with a `Tui.resource` app, which was verified only up to `NotATerminal`). OS-18 + D-29 (the
  screen-parity test of the Kyo, ZIO and Cats Counters). OS-48 part (README Cats quick start).
- New test: cancel the `F` that runs the Cats `Tui.run` on a headless control, then count live watchers: 0.
- Acceptance: as 6.0; the CI `terminal` job with the Cats rows.
- Size: ~540 glue + ~200 examples; tests ~900. Workflow: shape 8; about 2.1-2.4 M.

### 6.12 L1 `feat/kyoui-12-scroll`: content below the screen is reachable

- Content: RC-13 part (a scroll offset per container with `overflow`; focus follows the scroll; clip); RR N7; RR
  open question 2; a Todo with a long list in the examples; a PTY row; the bench "Todo grow" rows recorded again.
- Size: ~450; tests ~300. Workflow: shape 6; about 1.6-1.9 M. Only if D-30 keeps scroll.

### 6.13 L2 `feat/kyoui-13-layout-polish`: small layout gaps and unsupported attributes

- Content: RC-13 rest (row min-content width; ellipsis; 256-colour fallback when `COLORTERM` is not `truecolor`; the
  full-width button of IR item 4, checked against kyo-ui `HtmlRenderer.baseCss` first); RC-14 rest (one log line per
  unsupported attribute kind at mount; the `onKeyUp` warning, RR open question 5); RR N8.
- Size: ~250; tests ~250. Workflow: shape 4; about 1.0-1.3 M.

### 6.14 D = PR 2 `docs/docus`: the docs and the rules describe what exists

- Steps: (1) merge the L2 tip into `docs/docus` with a merge commit (the AGENTS.md "Documentation" section of PR 2 and
  the rows of the stack are in different sections, OS section 4); (2) the user changes the PR 2 base to the
  stack tip (`feat/kyoui-13-layout-polish`, F4 if L1 and L2 drop, or `main` if the stack is merged); (3) content in new commits.
- Content: OS-49 (index: "one UI DSL (kyo-ui)"; "repaints only the regions whose signals changed, and sends only the
  changed cells"; the installation page names the launchers, not `run / fork`); getting started per ecosystem (Kyo,
  ZIO, Cats); concepts (UI values, signals, regions, hooks, `Component`, focus); testing (`Headless`, the facade kits,
  the TestClock rule); performance (settle, `FrameStats`, trace, scheduler flags); troubleshooting (no `sbt run`,
  launchers, the lift trap (RR O5), one import, the `fs2.concurrent.Signal` clash, the `scala.Console` limit (R3),
  long handlers (FC-34)); supported elements and attributes (RC-14 lists); the kyo-ui jar size (RC-17, D-18); FC-28
  (after a release, a kyo-ui bump is a binary break for zio and cats). Code comes from `examples/` files, not copies;
  D chooses the mechanism (a Docus include or a build-time copy).
- CI and hosting: add `examples/**` to the `docs.yml` paths, so a change in an example runs the docs build; change
  the `docs/vercel.json` `ignoreCommand` so that it also watches `../examples`. TO VERIFY by the user: the Vercel
  project can read files outside its Root Directory `docs`.
- Preview deploys (critic pass): if the Vercel Git link is on, a push to `docs/docus` that changes `docs/` passes the
  `ignoreCommand` (`git diff --quiet HEAD^ HEAD -- .`, `origin/docs/docus:docs/vercel.json`), so Vercel builds a
  PREVIEW deploy of the branch. Vercel does this, not an agent, but it is still a deploy. Before W14 pushes, the user
  says if preview deploys are acceptable, or turns them off for the branch in Vercel.
- Rules sweep (D-31 a): OS-47, FC-37 (each AGENTS.md rule is true; the module table has `bench` and `codegen`; the
  kyo-ui bump steps point to `codegen/README.md`; the release order core, zio, cats); OS-48 rest.
- Acceptance: the `docs` CI (`pnpm build`) is green; `sbt check` is green; a reviewer checks each AGENTS.md rule
  against the repo; no deploy from an agent; the user checks the Vercel Git link before the merge.
- Size: ~1000 lines of Markdown + ~100 lines of rules. Workflow: shape 4 (writer, 2 reviewers, fixer; node build
  only); about 1.0-1.3 M.

### 6.15 Workflows and tokens

| Workflow | PR | Shape | Agents | Builds at once | Tokens (estimate) |
| --- | --- | --- | --- | --- | --- |
| W0 | spikes | section 5.3 | 6 | 2 | 2.0-2.4 M |
| W1 | C1 | 6 (+ script rewrite) | 6 | 1 | 1.8-2.1 M |
| W2 | C2 | 8 | 8 | 1 | 2.1-2.4 M |
| W3 | C3 | 6 | 6 | 1 | 1.6-1.9 M |
| W4 | C4 | 6 | 6 | 1 | 1.6-1.9 M |
| W5 | T1 | 8 | 8 | 1 | 2.3-2.6 M |
| W6 | T2 | 6 | 6 | 1 | 1.7-2.0 M |
| W7 | P1 | 4 | 4 | 1 | 1.1-1.4 M |
| W8 | F1 | 8 | 8 | 1 | 2.2-2.5 M |
| W9 | F2 | 8 | 8 | 1 | 2.1-2.4 M |
| W10 | F3 | 8 | 8 | 1 | 2.1-2.4 M |
| W11 | F4 | 8 | 8 | 1 | 2.1-2.4 M |
| W12 | L1 | 6 | 6 | 1 | 1.6-1.9 M |
| W13 | L2 | 4 | 4 | 1 | 1.0-1.3 M |
| W14 | D | 4 | 4 | 1 (node build; one `sbt check` after the merge-up) | 1.0-1.3 M |
| fix rounds | after user reviews, about 6 of 14 PRs | 2 | 2 | 1 | 2.4-3.6 M |
| Total | 15 workflows + fix rounds | | about 108 | | about 29-35 M (without L1 and L2: about 26-31 M) |

No workflow has more than 8 agents, so none needs an announcement. One workflow at a time.

---

## 7. Traceability

"dropped" = never carried, with the reason. "deferred" = after the first release, with the reason. "obsolete" = the
code that it is about drops.

### 7.1 Old stack (OS)

| Id | What | Place |
| --- | --- | --- |
| OS-1 | `TerminalIO`, `Crossterm`; bot P2 fix | T1 |
| OS-2 | `Session`, `InputThread`, `TerminalControl`; bot P2 fix | T1 |
| OS-3 | `EventMapping`, `InputPort`; replaces `Keys.scala` | T1 |
| OS-4 | `OutputCapture`, `LogSink`, `TuiLog` | T1 |
| OS-5 | `TuiOptions`, `Viewport`, `TuiError`; Kyo-free | C2 (core set), C3 (`ctrlCQuits`, `autoFocus`), C4 (`pendingTimeout`), T1 (terminal fields, `TuiError`), P1 (`trace`). `Viewport.Inline`: deferred (D-22: new `Canvas` code; no user asked) |
| OS-6 | Kyo `TuiApp` | T2 |
| OS-7 | `SchedulerDefaults` | T2 |
| OS-8 | `Tui.run`, `TuiRun`, `Headless`, quit value | C2 (`Headless`, quit value), C3 (`Tui.quit`), T1 (`Tui.run`; `TuiRun` merges; no public `Tui.start`, D-15) |
| OS-9 | `Trace` | P1 |
| OS-10 | Draw/restore serialisation; bot P1 fix | T1 |
| OS-11 | Grapheme text editing | C3 |
| OS-12 | `Screen`, `ScriptedInput`, `RecordingControl`, `discard` | C1 (`discard`), C2 (`Screen`), T1 (the doubles go to test sources) |
| OS-13 | Old model files of PR 3 | dropped: kyo-ui replaces them (new package Scaladoc in C1) |
| OS-14 | Old engine files of PR 4 | dropped. Lessons: batching -> D-23 + P1 `BurstProbe`; clock rule -> C2 (real clock), F2 (TestClock); `NotForeign` for Kyo hooks -> deferred (handlers stay `Any < Async`; upstream draft item 2 is the real fix) |
| OS-15 | Terminal tests to carry | C2 (`ScreenTest`), C3 (`TextFieldTest`), T1 (`TerminalIOTest`, `SessionTest`, `EventMappingTest`, `OutputCaptureTest`, `NativeTerminalTest`, `FakeCrossterm`), T2 (`TuiAppTest`, `SchedulerDefaultsTest`) |
| OS-16 | Old tests; two lesson tests | dropped; the name-clash and `CanEqual` tests in C2 |
| OS-17 | Surface check location | F1 (`codegen/src/surface`) |
| OS-18 | Examples; parity | T1 (Counter), T2 (the rest), F4 (screen parity, D-29) |
| OS-19 | ptycheck | T1 (kit, Counter rows), T2 (all cases), F2, F4, L1 (rows) |
| OS-20 | Launchers | T1 |
| OS-21 | Bench probes, JMH, baseline | P1 |
| OS-22 | `AmbientZIOs` + suite | F1 |
| OS-23 | `CatsBridge`, `AmbientDispatcher` + suites | F3 |
| OS-24 | Facade `TuiApp` | F2 (ZIO), F4 (Cats) |
| OS-25 | Facade run entry | F2, F4 |
| OS-26 | Old facade exports and sources | dropped: the mirror and the runtime replace them |
| OS-27 | Old facade kits and tests | dropped; lessons as new tests in F4 (and F2) |
| OS-28 | Facade tests that adapt | F2, F4 |
| OS-29 | Facade examples | F2, F4 |
| OS-30 | Facade build files | F1 (zio), F2 (ZIO literal, D-27), F3 (cats, fs2 version) |
| OS-31 | ZIO api-1, runtime-3 (TestClock) | F2 |
| OS-32 | ZIO api-3 (one import) | F1, F3 (name-clash tests); F2, F4 (the kits re-export shared names) |
| OS-33 | ZIO api-6, spec-6, spec-3 (output during the run) | T2 (capture install), F2 (`Console`, loggers) |
| OS-34 | No unbounded acquire; no join cascade | C2 (Kyo `Headless`), F2, F4 |
| OS-35 | No race on a long-lived Kyo promise | F1, F3 |
| OS-36 | ZIO spec-4 | obsolete: the tests drop |
| OS-37 | kyo-ui dependency, widgets | C1 (D-12) |
| OS-38 | Test framework, test JVM | C1; C2 (`Tags.limit`, D-9) |
| OS-39 | Strict flags | every PR (C1 first; S6 measures; D-8) |
| OS-40 | bench project | P1 |
| OS-41 | CI jobs | T1 (`terminal`), F1 (drift inside `check`) |
| OS-42 | Codegen and drift check | F1 (S5 first) |
| OS-43 | `compat.sbt` | no change (all `None`) |
| OS-44 | `FacadeParity` | dropped: its premise is false under a generated mirror (OS 1.1 item 9); D-29 replaces it |
| OS-45 | Release files | no change |
| OS-46 | PR objects | section 4.1 (D-2) |
| OS-47 | AGENTS.md | C1, C3, T1, T2, P1, F1, D (P-10) |
| OS-48 | README.md | C1, T2, F2, F4, D |
| OS-49 | docs content | D |
| OS-50 | `examples/README.md` | T1, T2, F2, F4, L1 |

### 7.2 Runner (RC)

| Id | What | Place |
| --- | --- | --- |
| RC-1 | Package layout | C1 (D-10) |
| RC-2 | `Tui` entry, quit value, `Abort[TuiError]` | C2 (quit value), C3 (`Tui.quit`), T1 (`Tui.run`, errors) |
| RC-3 | Kyo-free options | C2, C3, C4, T1, P1 (each adds its fields) |
| RC-4 | Hooks | C4 |
| RC-5 | `Component`, registry, state key | C4 (S7 first) |
| RC-6 | `FrameStats`, `spi.Settle` | C2 |
| RC-7 | `Headless` | C2 (C3 adds the key methods) |
| RC-8 | R1 | S2; C2 (watchers), C4 (effects, hooks) |
| RC-9 | R2 | S2; C2 (C3 adds the key variant) |
| RC-10 | R3 | T1 (warning), T2 (`TuiApp` install), F2 (ZIO `Console`), D (the limit) |
| RC-11 | Settle hardening | S1; C2 |
| RC-12 | kyo-ui surface guard | S3; C1, C2, C3 |
| RC-13 | Layout gaps | L1 (scroll), L2 (min-content, ellipsis, 256 colours). Deferred: virtualisation, `flexWrap`, `position`/`zIndex`/overlays, tables with `colspan`, units other than 1 px = 1 cell (no first-release need, RC 2.5; D-30) |
| RC-14 | Unsupported elements and attributes | C3 (paste as one edit), L2 (log line, `onKeyUp` warning). Deferred: mouse, drag and drop, pickers, multi-line `Textarea`, `Select` drop-down, `href`, `focusRestore`, terminal focus events (D-30) |
| RC-15 | Tests of unverified paths | C2 (resize), C3 (focus group, focus trap, `stopPropagation`), C4 (`onMount`, hooks) |
| RC-16 | Core build | C1 |
| RC-17 | kyo-ui jar size | S4; C1 (accept, D-18); T2 (class-load count); D (docs); upstream draft (module split) |
| RC-18 | Strict flags, format | every PR (S6 measures) |
| RC-19 | Test framework, sequential suites | C1 (set-up), C2 (sequence, `Tags.limit`) |
| RC-20 | `TerminalIO`, `Crossterm` | T1 (inline viewport deferred, D-22) |
| RC-21 | `Session`, `InputThread` | T1 |
| RC-22 | `EventMapping` | T1 |
| RC-23 | `OutputCapture`, `LogSink`, `TuiLog` | T1 |
| RC-24 | `TuiApp` | T2 |
| RC-25 | `SchedulerDefaults` | T2 |
| RC-26 | `Trace` | P1 |
| RC-27 | Carried PR 5 tests and doubles | C2 (`ScreenTest`), T1 (terminal tests; doubles to test sources), T2 (`TuiAppTest`, `SchedulerDefaultsTest`), P1 (`TraceTest`). `GuardTest` dropped: it tests the old `NotForeign` on `Source.task` |
| RC-28 | `Screen` style type | C2 (D-13) |
| RC-29 | Old files | dropped (they stay on the old branches) |
| RC-30 | Stress probes | C2 (split, leak), P1 (glitch, both scheduler configs) |
| RC-31 | PTY kit | T1 (kit, Counter), T2 (all cases); rows in F2, F4, L1 |
| RC-32 | Bench | P1 |
| RC-33 | Examples | T1 (Counter), T2 (the rest) |
| RC-34 | Upstream issue draft | not a PR: `.work/design/upstream-kyo-ui-issue.md` (written now; W0 adds the numbers; A2) |
| RC-35 | PR restructure | section 4 (D-2) |
| RC-36 | Binary surface | C2 (API review), T1 (doubles to test sources), every PR |

### 7.3 Facades (FC)

| Id | What | Place |
| --- | --- | --- |
| FC-1 | Codegen sub-project | S5; F1 |
| FC-2 | Class path and version as task inputs | F1 |
| FC-3 | Both ecosystems, full set, stable names | F1 (generator), F3 (Cats target) |
| FC-4 | Fail-closed generator | F1 |
| FC-5 | Format exclude and warning rules | F1 (FC-D10, D-26) |
| FC-6 | `mirrors` alias, `mirrorsCheck` in `check` | F1 |
| FC-7 | Generator tests | F1 |
| FC-8 | kyo-ui bump procedure | F1 (`codegen/README.md`), D (AGENTS.md pointer) |
| FC-9 | ZIO state and runtime | F1 |
| FC-10 | Cats state, ref type | F3 |
| FC-11 | Vendored bridges and suites | F1 (ZIO), F3 (Cats) |
| FC-12 | `Tui` entry points | F2, F4 |
| FC-13 | `TuiApp` main classes | F2, F4 |
| FC-14 | Test kits | F2, F4 |
| FC-15 | Re-exports of core types | F2, F4 |
| FC-16 | Package layout | F1 (DECISIONS round 4: flat `tui4s.interop.zio` / `.cats`) |
| FC-17 | Runner-mounted facade components | S7; C4 (state key); F2, F4 (D-20) |
| FC-18 | HTML stand-in | dropped (IR F1) |
| FC-19 | ZIO tests | F1 (stress, guards), F2 |
| FC-20 | Cats tests | F3, F4 |
| FC-21 | ZIO build | F1 |
| FC-22 | Cats build | F3 |
| FC-23 | Generated ZIO mirror | F1 |
| FC-24 | Generated Cats mirror | S5; F3 |
| FC-25 | Mirror scope | F1 (FC-D6) |
| FC-26 | Reactive template | F1 |
| FC-27 | ZIO output during the run | S7; F2 |
| FC-28 | Compatibility notes | F1 (`codegen/README.md`), D |
| FC-29 | `SurfaceLeaks` | F1, F3 |
| FC-30 | Lift-trap guards | F1, F3 |
| FC-31 | Data-type leaks | F1 (FC-D2) |
| FC-32 | Drag and drop | F1 (excluded, FC-D3) |
| FC-33 | Facade examples and PTY rows | F2, F4 |
| FC-34 | Long handlers, TestClock | F2, D. A runner fork of long handlers: deferred (D-28: it changes kyo-ui's dispatch semantics) |
| FC-35 | Name-clash tests | F1, F3 |
| FC-36 | `changes` interrupt stress | F1 |
| FC-37 | AGENTS.md, module docs | F1, D |
| FC-38 | PR objects and order | section 4 (this plan is FC-D7 option A, one linear stack) |

### 7.4 Integration fixes (IR "What must change")

| Id | Place |
| --- | --- |
| R1 leak of an interrupted run | S2; C2 (region watchers), C4 (effects, hook bodies); F2, F4 (facade cancel tests) |
| R2 first-key race | S2 (seam reproducer); C2 (fix and test), C3 (key variant) |
| R3 `scala.Console` capture | T1 (forwarders, warning), T2 (`TuiApp` install), F2 (ZIO `Console`), D (the limit for a foreign `main`) |
| R4 Kyo-free options | C2 (core set); later PRs add fields; F2, F4 re-export |
| R5 quit as a value | C2 (`Headless`), T1 (`Tui.run`); F2, F4 drop `withQuit` |
| F1 drop the HTML stand-in | never carried; F1, F2 use the terminal kit |
| F2 glue into the modules | F2, F4 |
| F3 stale `AmbientDispatcher` message | F3 |

### 7.5 Facade report (FR)

| Id | Place |
| --- | --- |
| F1 the lift trap is real | F1, F3 (canary) |
| F2 an extension cannot shadow a member | F1 (opaque types; raw guards) |
| F3 trait-level forwarders | F1 (generator design) |
| F4 ZIO seals `Ref` | F1 (`SignalRef`, A3) |
| F5 two cells race | F1, F3 (stress suites in the normal run) |
| F6 CAS by reference | F1 (contention test) |
| F7 handler inline in dispatch | F2 (Scaladoc, TestClock test), D; runner fork deferred (D-28) |
| F8 `Dispatcher.parallel(await = true)` loses a cancel | F3 (`await = false`, `DispatcherSuite`) |
| O1 drag and drop | F1 (excluded, FC-D3) |
| O2 Kyo data types through aliases | F1 (FC-D2 b) |
| O3 Cats foreign `SignallingRef` | F3 (FC-D4) |
| O4 headless drops a missing id | obsolete (stand-in dropped); a bounded `focus(id)` in F2, F4 |
| O5 stale texts in the vendored bridges | F1 (ZIO), F3 (Cats) |
| O6 folders not self-contained | obsolete (sbt) |
| O7 `changes` semantics | F1 (Scaladoc, stress) |
| O8 headless only | closed by IR; PTY rows in F2, F4 |
| Q1, Q2, Q3 | D-25 (FC-D2, FC-D3, FC-D4) |

### 7.6 Runner report (RR) findings, skeptic items and questions

| Id | Place |
| --- | --- |
| O1 kyo-ui works on the JVM; attributes readable | fact; no code |
| O2 kyo-ui machinery is private; the runner copies it | C1-C3 (surface guard); upstream draft (SPI, short item) |
| O3 reactive node kinds | fact; C2 mounts the three kinds |
| O4 no synchronous change hook | C2 (the settle stays); upstream draft item 1 |
| O5 lift trap for Kyo users | C3 (AGENTS.md rule, canary), D (docs page); upstream draft item 2 |
| O6 a busy fiber holds the settle | C2 (`FrameStats` counter, no stderr), P1 (trace line), D |
| O7 component state rules | C4 (tests) |
| N1 settle order | C2 (keep the fixed code) |
| N2 probe races | S1; C2 (verify pass, canary); upstream draft item 1 |
| N3 fixed sleeps in tests | C2 (`awaitEnd`) |
| N4 measurements | P1 (`BASELINE.md`) |
| N5 idle CPU | T2 (`SchedulerDefaults`), P1 (idle probe) |
| N6 PTY Counter and Todo | T1, T2 |
| N7 layout gaps | L1, L2; the rest deferred (RC-13 row) |
| N8 unsupported elements | C3, L2; the rest deferred (RC-14 row) |
| N9 Kyo internals | C1-C3 (guard), C2 (no reflection if G1 is GO), C3 (AGENTS.md rule) |
| N10 old-core hardening | T1, T2 |
| Skeptic "refuted" 2: the bench "late frames" column cannot see a split | S1, P1 (count by `updates`) |
| Skeptic "refuted" 7: kyo PR #1649 not readable | the upstream draft does not cite it |
| Skeptic "refuted" 9: four hooks and `onMount` have no test | C4 |
| Skeptic "refuted" 3: one split frame had a settle of 187.6 ms (the limit expired); each contention log has one "settle timed out" | S1 (4) counts timeouts per run; C2 (`FrameStats` counter, RR O6); P1 (trace line) |
| Skeptic "refuted" 5: the second idle-CPU values (15.7 %, 5.4 %) have no log | P1 measures idle CPU again on an idle machine (`BASELINE.md`); the about 3x drop stays (verifier: 16.39 % -> 5.20 %) |
| Skeptic "refuted" 6: no measurement splits layout from paint | P1 (separate JMH benches for `Layout` and `Paint`) |
| Skeptic "refuted" 10: desktop-only numbers ("3 to 131 split frames" by hop count; the 0.5 ms timer minimum) | S1 re-measures on Linux; the upstream draft gives the range with its sources (critic pass) |
| Skeptic "refuted" 1, 4, 8 (report text: the test extract, run A columns, `article`/`aside`) | no code: report text only. `article`/`aside` are columns already (every element that is not in the row list) |
| Skeptic note: Dashboard frames with 1 region update not checked as splits | S1 (5) splits per region kind; P1 `DashboardProbe` counts by `updates` |
| Open questions 1-8 | 1 -> D-17 (the default), A2 (the upstream discussion); 2, 3, 4, 6 -> D-30; 5 -> L2; 7 -> A1, D-2; 8 -> T1, T2 |
| Upstream proposals 1-6 (+ 7 module split, RC section 5) | upstream draft: 1 and 2 in full; 3 (SPI) and 7 (module split) as short items; 4, 5, 6 left out (small value, or moot if item 1 lands) |

### 7.7 Integration items, review threads, ZIO findings, Cats issues, HANDOFF items

| Item | Place |
| --- | --- |
| IR "does not work" 4: a button takes the full column width | L2 (check `HtmlRenderer.baseCss` first) |
| IR "does not work" 5: the facade apps have no quit key | F2, F4 (each example has one) |
| IR "does not work" 6: not tested | facade Todo in a PTY, resize, SIGTERM/SIGINT, pipe, facade Dashboard -> F2, F4 rows; the full ZIO mirror app -> F1 ships the full mirror; facade idle CPU and latency -> deferred (no correctness need; a probe row after F4 if wanted) |
| IR Q1-Q4 | Q1 -> D-25 (FC-D5); Q2 -> D-24; Q3 -> strict kit (D-17); Q4 -> re-export of the Kyo-free core `TuiOptions` (R4, FC-15; its duration type is D-14) |
| IR "Gaps for the real modules" | module graph: zio and cats already `dependsOn(core)` (`origin/chore/cleanup:build.sbt`); R1-R3 before a release: C2, C4, T1, T2; R4, R5: C2, T1; facade options: IR Q4 row; main classes with flags, capture, exit codes: F2, F4; ZIO default loggers during the run: F2 (spec-3); quit and Ctrl-C: IR Q1, Q2; the kit in each facade module with `FrameStats`: F2, F4 (FC-14), its `Keyboard` alias: D-25 (FC-D2); PTY rows for facade Counter and Todo and the extra cases: F2, F4; FR O1-O8: 7.5 |
| 8 unresolved review threads on PRs 3-5 | section 4.1 (3 fixed in T1; `CounterApp.scala:8` in T1/T2; 4 obsolete, 2 of them with a lesson in C3 and T1) |
| ZIO api-1, runtime-3 | F2 (rule, TestClock test); runner fork deferred (D-28) |
| ZIO api-2 | F2 (`ZIOAppArgs`) |
| ZIO api-3 | F1, F3 (name clash); F2, F4 (re-export) |
| ZIO api-6, spec-6 | T2 (`TuiApp` install), F2 (`Console` service) |
| ZIO runtime-1, spec-2 | F2 (rule, `timeout` test) |
| ZIO runtime-2, spec-1 | F1 (`changes` stress), F3 |
| ZIO runtime-4, spec-5 | C2 (Kyo `Headless`), F2, F4 (never join the run fiber) |
| ZIO spec-3 | F2 (`quietly` in its own `ZIO.scoped`) |
| ZIO spec-4 | obsolete |
| ZIO api-4, api-5 (refuted) | none |
| Cats: the core join cascade | F4 |
| Cats: the `import fs2.*` clash | F3 (name-clash test; `Signal` per D-25) |
| Cats: README and checklist rows | F4 |
| Cats: `Tui.resource` verified only up to `NotATerminal` | F4 (PTY row with a resource app) |
| HANDOFF open item: PR 4 settle waits for standing task sources | obsolete (old engine) |
| HANDOFF open item: macOS Terminal / iTerm2 look check | release checklist (section 4.5) |
| HANDOFF open item: bench budgets missed on a busy machine | P1 (idle machine) |
| HANDOFF follow-ups: facade rows in README and checklist; ZIO literal; `fs2.*` troubleshooting; TestClock note | F2, F4 (rows), D-27, D (troubleshooting), F2 (TestClock) |
| DK F-4: derived signals compute `current` synchronously (a pull check for bound and list regions) | S1 (measures splits per region kind); C2 adds a pull check only if S1 finds splits in those kinds |

### 7.8 Deferred (consolidated)

| Item | Reason |
| --- | --- |
| Inline viewport | D-22: new `Canvas` code with no prototype; additive later |
| Mouse, hover, wheel, drag and drop | D-30: mouse capture stops text selection; no first-release need |
| Virtual lists, `flexWrap`, `position`, overlays, tables with `colspan` | After scroll; no first-release need (RC 2.5) |
| Real rendering of `Img`, SVG, `Chart`, multi-line `Textarea`, pickers, `Select` drop-down | Placeholders in the first release (D-30) |
| `NotForeign`-style guard for Kyo hooks | kyo-ui handlers stay `Any < Async`; upstream item 2 is the real fix |
| A runner fork of long handlers | D-28: it changes kyo-ui's inline dispatch semantics |
| `onKeyUp` support | Not possible on a plain terminal; a log line only (L2) |
| Facade idle CPU and latency probes | No correctness need; optional bench rows after F4 |
| macOS Terminal / iTerm2 look check | A human check before the first release (section 4.5) |

### 7.9 Decision ids of the inventories (added by the critic pass)

The inventories number their own decisions. Each one maps to a plan decision, a user decision of DECISIONS, or a
section.

| Inventory decision | Plan |
| --- | --- |
| OS D1 package of the runner; facade packages | D-10; the facade packages are decided (DECISIONS round 4; FC-16) |
| OS D2 core test framework | decided: kyo-test (DECISIONS round 4); C1 |
| OS D3 PR objects | D-2 |
| OS D4 `InputPort` element | D-21 |
| OS D5 inline viewport | D-22 |
| OS D6 `jatatui-widgets` | D-12 |
| OS D7 input bursts | D-23 |
| OS D8 old branches after the close | section 4.2 (keep); only the user deletes a branch (4.4) |
| OS D9 docs PR 2 | D-7 |
| OS D10 ZIO version literal | D-27 |
| OS D11 Ctrl-C exit code | D-24 |
| OS D12 screen parity | D-29 |
| RC D1 PR restructure | A1, D-2, D-3 |
| RC D2 package | D-10 |
| RC D3 duration type | D-14 |
| RC D4 quit for the facades | D-15 |
| RC D5 settle default | D-17 |
| RC D6 R2 fix | D-16 |
| RC D7 kyo-ui transitive jars | D-18 |
| RC D8 kyo-test for core | decided (DECISIONS round 4); C1 converts the runner tests (about 210 lines, RC D8) |
| RC D9 first-release scope | D-30 |
| RC D10 Ctrl-C exit code | D-24 |
| RC D11 `Screen` cell style | D-13 |
| RC D12 upstream draft | A2 |
| FC-D1 package names | decided (DECISIONS round 4); FC-16 |
| FC-D2, D3, D4, D5, D6, D8, D9, D10, D11 | D-25 (one row each) |
| FC-D7 PR split | section 4 (option A: one linear stack; FC-38) |
| FC "confirm A3, A4" | section 2; confirmed with section 3.1 |

---

## 8. Risks

L = likelihood, I = impact (H high, M medium, L low).

| Id | Risk | L | I | Evidence | How the plan finds it early | Fallback |
| --- | --- | --- | --- | --- | --- | --- |
| K1 | The settle is not exact: a frame misses a consequence (a split frame). Tests that read the screen right after a key become flaky, and "green before review" blocks PRs. | M | H | RR N2; RR skeptic (1 settle timeout per contention log); IR contention run 1 | S1 (3 load levels, 2 scheduler configs); C2 glitch test; settle canary | D-17 (c); NO-GO: ask the user (D-17 d) |
| K2 | A new Kyo RC changes scheduler internals; the probe falls back in silence. | H | M | DK F-2 (`busyWorkers()` is new in RC7; `Scheduler.scala` RC5 -> RC7: 194 diff lines) | S1 tries the public `status()`; C2 canary over every probe call | Pin Kyo; each bump is its own PR with the canary |
| K3 | The R2 cause is a hypothesis; the fix may not remove the 1 s first-key delay, which users can see. | M | H | RC 2.1; IR split probe; kyo `Signal.scala:101-107` | S2 seam reproducer (red, then green) and the split probe on 3 paths | D-16 (b); NO-GO: ask the user |
| K4 | The run-owned `Scope` (R1 fix) changes the end order: a watcher paints after the canvas closes, or the release waits on a watcher. | M | M | RC-8 risk column; RC-21 | S2 order test | RC 2.1 option (b) |
| K5 | kyo-ui changes the AST, `Attrs`, `Style.Prop` or a dispatch rule; the wildcard matches ignore it in silence. | M | H | RC 2.4 (K1-K9); DK F-9 (drift rate unknown) | S3; the surface guard (C1-C3) | Pin RC7 (ask the user, D-19 b) |
| K6 | kyo-ui adds about 15.2 MB and may load kyo-http, kyo-net or kyo-ffi code at start. | L | M | RC 3.1 | S4 | Accept; the upstream module split |
| K7 | The generator does not fit inside sbt; the Cats full mirror was never generated or compiled. | M | M | FC summary 3, 5 | S5 | Drift as a CI step; a scala-cli generator; a smaller Cats mirror |
| K8 | Explicit nulls, fatal warnings and scalafmt on the prototype need more than mechanical edits; fs2 3.14.0 breaks `KyoSignallingRef` (built with 3.12.2 only). | M | M | RC 3.2 (71 Java-API lines); FC summary 16 | S6 | `unsafeNulls` per jatatui-facing file; pin fs2 |
| K9 | Runner-mounted facade components (FC-17) are not prototyped. | M | M | FC summary 11, 12 | S7 | Ask the user (D-20 b) |
| K10 | Test kits stall on virtual time (Kyo `Clock.withTimeControl`, ZIO `TestClock`). | M | M | RC 3.3; OS-31 | C2 real-clock rule and test; F2 TestClock test | A real-clock option in the kit |
| K11 | CI contention makes the settle-sensitive suites slow or flaky. The CI runner size of a private repo is not known. | M | H | DK F-5; IR "What does not work" 2 | D-9 `Tags.limit`; 1 of 3 runs under contention; TO VERIFY: `nproc` in a CI log (C2) | Raise test timeouts only; never hide a split frame |
| K12 | The facade binary API names kyo-ui classes (opaque types erase), so after a release each Kyo bump breaks zio and cats. | H after a release | L now | FC summary 8 | `versionPolicyCheck` after the first release | `Compatibility.None` on each Kyo bump (AGENTS.md) |
| K13 | A new Kyo RC (RC8 or 1.0.0) comes out during the stack. | M | M | DK F-2, F-9 | S3 churn numbers; the guards | Finish the stack on RC7; bump in its own PR after F4 |
| K14 | Review load: 13 PRs and PR 2 for one reviewer; the stack stays open for a long time. | H | M | DR K-17; J-M section 3.1 defect 7 | Small PRs, delta guides, gates, WIP limit, batch merges | D-3 (b): 9 PRs |
| K15 | A review change low in the stack (options, `Headless`, mirror API) ripples up. | M | M | DR K-10 | Gates 1 and 2; WIP limit P-11 | Merge-up (P-9); a fix round |
| K16 | A docs merge deploys the site through the Vercel Git link. A push to `docs/docus` at D can start a PREVIEW deploy. | M | M | `origin/docs/docus:.github/workflows/docs.yml` ("Vercel deploys the site"); `docs/vercel.json` `ignoreCommand` | PR 2 stays open until D (D-7 a); the user decides on previews before W14 (6.14) | The user turns off the Git link (or branch previews) before the push or the merge |
| K17 | PTY checks are timing-sensitive on CI runners. | M | M | RC-31 risk column | T1: the CI `terminal` job green 3 times before review | Screen checks at marks (`vt.py`); longer waits; a retry only around the process start, with a log |
| K18 | R3 has no full fix: `Tui.run` inside a foreign `main` keeps `scala.Console` output on the screen. | H (by design) | M | RC 2.1 R3; IR "What does not work" 3 | T1 start-time warning; T2 `TuiApp` install; F2 ZIO `Console` | Documented limit (D) |
| K19 | A squash or rebase merge breaks the stack. | M | L | `gh api repos/yuri4n/tui4s`: both allowed | D-4 (a) and (c) | Merge `main` into the next branch (no rewrite) |
| K20 | `main` has no required checks, so a red PR can merge. | L | H | `gh api .../branches/main/protection`: 404 | D-5 | The user checks CI before each merge |
| K21 | Docs code included from `examples/` drifts in silence: `docs.yml` runs only on `docs/**`, and Vercel skips a build when `docs/` did not change. | M | M | `origin/docs/docus:.github/workflows/docs.yml`, `docs/vercel.json` (`ignoreCommand`) | D adds `examples/**` to the paths and `../examples` to the `ignoreCommand` | The user checks the Vercel "files outside the Root Directory" setting (TO VERIFY) |
| K22 | An agent follows `source .work/env.sh` (Mac paths) on the cloud machine. | M | L | HANDOFF "RESUMED IN A CLOUD SESSION"; DECISIONS line "source .work/env.sh" | The toolchain line in every prompt (section 6.0; D-11); the agent checks `java -version` first | Stop and fix the prompt |
| K23 | The R2 seam test is not reliably red before the fix (a timing test). | L | M | S2 design | S2 must show red in 10 of 10 runs before the fix | A latch in the watcher callback in place of a sleep (deterministic) |
| K24 | Format growth and the strict flags push a PR over the size aim. | M | L | DR section 9 item 1 (about +25% at 80 columns) | S6 counts; C1 reports the real number | Split at a file border before the PR opens |
| K25 | `wip/handoff` is deleted while PR bodies cite it. | L | M | DR K-15 | Delta guides cite commit `1d100ed`; keep the branch until the stack merges | Restore the commit from any clone |
| K26 | Maven Central sends HTTP 429 during parallel downloads. | M | L | HANDOFF | At most 2 builders; retry with a backoff | Run the downloads one at a time |
| K27 | License: the runner copies kyo-ui rules (semantics, not text); the Cats and ZIO bridges are vendored. | L | L | DK F-8 (0 of 135 long `Dispatch.scala` lines are verbatim in kyo-ui); FC 2.3 | One attribution comment in `Dispatch`; F1 and F3 keep the Apache-2.0 headers and the "Changes from the original" lists | - |
| K28 | Closed PR 3 cannot be reopened after `chore/cleanup` is deleted. | L | L | GitHub behaviour (a closed PR needs its base branch to reopen) | Section 4.1 note | Accepted: the branch and the review stay readable |

---

## 9. Facts this plan uses

| Fact | Evidence |
| --- | --- |
| `delete_branch_on_merge` is false; squash and rebase merges are allowed; the repo is private | `gh api repos/yuri4n/tui4s` (this session) |
| `main` has no branch protection | `gh api repos/yuri4n/tui4s/branches/main/protection`: 404 (this session) |
| 8 bot review threads are unresolved on PRs 3-5 | `gh api .../pulls/{3,4,5}/ccr/review_threads` and `.../comments` (this session) |
| All three `compat.sbt` are `Compatibility.None`; no release tag exists | `git show origin/chore/cleanup:modules/<m>/compat.sbt`; `git tag -l` (this session) |
| No fatal-warning flag; no `concurrentRestrictions`; `root.aggregate(core, zio, cats, examples)`; the `check` alias | `git show origin/chore/cleanup:build.sbt` (this session); DK F-3, F-5 |
| `ModuleVersioning` paths are `modules/<m>` and `project/Versions.scala` | `origin/chore/cleanup:build.sbt` `module(...)` |
| `.work/` is git-ignored (line 42) | `git show origin/chore/cleanup:.gitignore` |
| `docs.yml` runs only on `docs/**`; Vercel `ignoreCommand` diffs only `docs/` | `origin/docs/docus:.github/workflows/docs.yml`, `docs/vercel.json` |
| PR 2 adds the rule "When a public API changes, change its page in the same pull request" | `git diff origin/chore/cleanup origin/docs/docus -- AGENTS.md` |
| `Scheduler.status()` is public and returns `currentWorkers`; `busyWorkers()` is new in RC7 | DK F-1, F-2; J-D section 2 (checked) |
| The generated mirror imports the hand-written `Bridge` | DK F-6; `proto/kyoui-facades/zio/generated/ZioUi.scala:8` |
| `GuardTest` tests the old `NotForeign` | DR section 9 item 2; J-M section 2 (checked) |
| The prototype on `wip/handoff` (commit `1d100ed`) is byte-identical to `.work/proto` | DR section 9 item 7; J-M section 2 |
| Kyo `observe` reads `current`, runs the callback, then registers `nextWith`; the repair interval is 1 s | `.work/scratch/kyo-src/kyo-core/kyo/Signal.scala` (`observe` Scaladoc; `defaultRepairInterval`) |
| kyo-ui handlers are `Any < Async` (`onClick(action: => Any < Async)`, `onKeyDown(f: KeyboardEvent => Any < Async)`) | `.work/scratch/kyo-src/kyo-ui/kyo/UI.scala:771-788` |
| Cloud toolchain: JDK 25 in `/usr/lib/jvm/java-25-openjdk-amd64`; `sbt`, `scala-cli` in `/usr/local/bin`; 4 CPUs | `which`, `ls /usr/lib/jvm`, `nproc` (this session) |
| This session cannot search getkyo/kyo issues (the GitHub access is bound to yuri4n/tui4s) | `gh api search/issues`: HTTP 403 (this session) |

---

## 10. How this plan used the drafts and the verdicts

Base: both winners. DR (reviewer lens winner, J-M) gives the PR shape, the process rules and the per-item placement.
DK (risk lens winner, J-D) gives the spike phase, the build facts (F-1..F-13) and the guards that stay.

| Source | Graft or missing item | Status | Where, or why |
| --- | --- | --- | --- |
| J-M 1 | Real-terminal CI in the terminal PR | TAKEN | T1 carries the kit, launchers, Counter and the CI job; T2 adds the `TuiApp` rows. Change: the Ctrl-C row is in T1 (Ctrl-C as a key is a runner feature, C3) |
| J-M 2 | Quit in its final shape before gate 1 | TAKEN | C2 |
| J-M 3 | PR 2 kept open; merge-up at D | TAKEN | D-7 (a); option (b) names the AGENTS.md rule change |
| J-M 4, J-D 13 | Warning policy before C1 | TAKEN | D-8, P-13, C1 |
| J-M 5 | `Tags.limit` and a contention run | TAKEN | D-9, C2, section 6.0 |
| J-M 6 | Bench right after the app PR | TAKEN | P1 is PR 7 |
| J-M 7, missing 5 | Close comments with all 8 threads | TAKEN, CHANGED | Section 4.1. Two "obsolete" threads keep a lesson: `TuiTest.scala:166` -> C3 paste test; `Keymap.scala:159` -> T1 canonical events |
| J-M 8 | Canary over every probe call; `status()` option | TAKEN | S1, C2 |
| J-M 9 | Remove the 11 `claude <date>` marks | TAKEN | P-7 step 2 |
| J-M 10 | Guards with an owner PR | TAKEN | Section 5.5 |
| J-M 11 | Human look check in the release checklist | TAKEN | Section 4.5 |
| J-M 12 | Split W9 into two workflows | TAKEN | F3 and F4 are W10 and W11 |
| J-M 13 | Drop `GuardTest`; doubles to test sources | TAKEN | T1, T2 |
| J-M missing 1 | Branch protection question | TAKEN | D-5 (TO VERIFY: plan level) |
| J-M missing 2 | Turn off squash and rebase merges | TAKEN | D-4 (c) |
| J-M missing 3 | New projects in `root.aggregate` | TAKEN | P-14; F1, P1 |
| J-M missing 4 | `examples/**` in `docs.yml`; Vercel check | TAKEN | D; K21 |
| J-M missing 5 | PR template | TAKEN | C1 |
| J-M missing 6 | Owner of each GitHub write | TAKEN | Section 4.4 |
| J-D 1 | `delete_branch_on_merge` false | TAKEN | D-4 (b); section 4.3 |
| J-D 2 | P-6, P-7, P-8, P-12 | TAKEN | Section 6.0 |
| J-D 3 | Gates and the WIP limit | TAKEN | Section 5.4 |
| J-D 4 | Split DK P-a on DR lines | TAKEN | C1-C4 |
| J-D 5 | `GuardTest`, doubles, `git show` carry | TAKEN | Section 4.2, T1 |
| J-D 6 | Grapheme editing replaces UTF-16 in the carry PR; keep `wip/handoff` | TAKEN, CHANGED | C3; delta guides cite commit `1d100ed`, not the branch |
| J-D 7 | Local PTY run first; CI job green 3 times | TAKEN | T1 |
| J-D 8, missing 3 | An R2 proof that can fail | TAKEN | S2 seam in the watcher callback; C2 test |
| J-D 9 | Negative control per regression test | TAKEN | P-6 |
| J-D 10, missing 4 | Settle under both scheduler configs | TAKEN | S1 (3); C2 one-off run; P1 `GlitchProbe` |
| J-D 11 | G1, G3, G7 NO-GO -> ask the user | TAKEN, EXTENDED | Also G2 NO-GO on R2 (it touches the strict kit) |
| J-D 12 | One frame per event, not DK D-16 | TAKEN | D-23 (a) |
| J-D 14 | Apache-2.0 headers; review the Cats code as new | TAKEN | F1, F3 |
| J-D missing 1 | Facade `Tui.run` cancel test | TAKEN | S2 (4); F2, F4 |
| J-D missing 2 | A writer for the upstream draft | TAKEN, CHANGED | Written now with this plan; agent M of W0 adds the numbers |
| J-D missing 5 | Cloud toolchain in the prompts | TAKEN | D-11, section 6.0 |
| J-D missing 6 | Generated code and the strict flags | TAKEN | D-26 (targeted `@nowarn`; blanket `-Wconf` only if S5 needs it) |
| J-M graft 8 note / J-D | Remove the scheduler reflection | CONDITIONAL | Only if G1 is GO with `status()` (D-17) |
| DK spike schedule | "C and D start when A or B ends" | REJECTED as written | It can run 3 builders at once (J-M section 3.2 defect 5). Section 5.3: a builder starts only when a builder ends |
| DK carry method | `git checkout <ref> -- <path>` | REJECTED | It stages ignored `.work/` files and keeps the old paths (J-M section 2) |
| DK D-16 | Batch in the session, one frame per key in `Headless` | REJECTED | The kit would not run the production path (J-D graft 12) |
| DK P-a / P-b size | Two PRs of about 4000+ lines | REJECTED | Not one sitting (J-M section 3.2 defect 1); C1-C4 and T1/T2 split them |
| DK P-gen | A generator PR with an empty `mirrorsCheck` | REJECTED | The reviewer sees no real output (J-M section 3.2 defect 7); F1 ships the generator with the ZIO mirror and runtime (DK F-6 holds) |
| DR PD-4 (a) + PD-19 | Merge PR 2 early and write docs at the end | REJECTED as the recommendation | It breaks the PR 2 docs rule in every code PR (J-M section 3.1 defect 3); kept as D-7 (b) with the rule change |
| DR W9 | F3 and F4 in one 14-agent workflow | REJECTED | Two workflows (J-M 12) |
| DR R | A separate rules PR | CHANGED | Folded into D (D-31), because P-10 keeps the rules true in each PR |
| DR F1 pre-flight | Cats full mirror in scratch during F1 | REPLACED | S5 does it before C1 |

---

## Progress log

- 2026-10-03: read DECISIONS.md, HANDOFF.md, AGENTS.md, both drafts, both verdicts, the three inventories, and the
  report sections on findings, upstream proposals, open items and questions.
- 2026-10-03: checked with read-only `gh api` and `git show`: repo merge settings, branch protection, open PRs and
  bases, branch heads, the 8 unresolved threads and their text, `compat.sbt`, `build.sbt`, `Versions.scala`, CI and
  docs workflows, Vercel config, `.gitignore`, the AGENTS.md diffs of PR 2 and PR 5, `SchedulerDefaults`, the Kyo
  `observe` Scaladoc, the kyo-ui handler types, the cloud toolchain. A search of getkyo/kyo issues is not possible
  from this session.
- 2026-10-03: wrote sections 1-10. DONE.
- 2026-10-03: completeness critic pass (next section).

---

## Critic pass (2026-10-03)

<!-- claude 2026-10-03: created this section (completeness critic; planning only: no build, no checkout, no commit, no push, no GitHub write) -->

Role: completeness critic. Question: "what is missing or wrong?". I ran no build and no test. I did no checkout, no
commit, no push and no GitHub write. Read-only checks: `git show`, `git ls-tree`, `git log`, and `gh api` GET calls
(repo settings, open PRs, `ccr/review_threads`, `branches/main/protection`, `rulesets`). Notes:
`.work/design/plan/notes-critic-completeness.md`.

### What I checked

| Check | Result |
| --- | --- |
| Every OS-1..50, RC-1..36, FC-1..38 id has a row in section 7 | PASS: all 124 ids have a row (script). |
| IR R1-R5 and IR F1-F3 | PASS: 7.4. |
| FR O1-O8, FR F1-F8, FR Q1-Q3 | PASS: 7.5. |
| RR N1-N10, RR O1-O7, RR open questions 1-8, RR upstream proposals | GAP, FIXED: the RR skeptic items "refuted" 1, 3, 4, 5, 6, 8, 10 and the Dashboard note had no row; open question 1 also asks "open the upstream discussion?", which had no place (now A2). 7.6. |
| IR open questions 1-4; IR "Gaps for the real modules" | GAP, FIXED: Q1-Q4 were placed; "Gaps" had no row. 7.7. |
| Decision ids of the inventories (OS D1-D12, RC D1-D12, FC-D1..D11) | GAP, FIXED: all had a plan decision, but no table showed it. New 7.9. |
| 14 ZIO findings, 4 Cats issues, 8 review threads, HANDOFF open items, judge grafts and "missing" items | PASS: 7.7, 4.1, section 10. |
| Each PR base exists or comes from an earlier PR; the merge order | GAP, FIXED: `origin/main` is only the initial commit (79b9d9c), so C1 on `main` needs PR 1 merged first. Section 1 table and 4.3. The rest is consistent (L1/L2 drop cases included). |
| No history rewrite without a warning and an alternative | PASS: D-2 (B) and D-7 (d) are the only rewrites; both are named as rewrites and not recommended. |
| No merge, release or deploy without the user | GAP, FIXED: 4.4 had no owner for the pushes to `docs/docus` at D (it is not a branch that this plan creates); a push there can start a Vercel PREVIEW deploy. 4.4, 6.14, K16. |
| AGENTS.md rules | PASS for `compat.sbt` (all `None`, verified), `Versions.scala` use and release rules. GAP, FIXED: the toolchain line said "one sbt process per worktree" (AGENTS.md: one in the repo); P-14 did not repeat "no `version`, no `publishTo`". |
| DECISIONS rounds 1-5 | PASS for kyo-test in core, the test kit in core, the scheduler flags, output capture, the facade packages, the vendored Apache-2.0 bridge (Kyo has no NOTICE file: `feat/cats` `CatsBridge.scala` header). GAP, FIXED: D-25 FC-D2 (a)/(b) leave Kyo DATA types reachable, a partial exception to round 5, and the plan did not say so; T1/T2 did not state the capture default and the scheduler opt-out. |
| Internal consistency | GAP, FIXED: T1 carried a `focusEvents` option while RC-14 defers terminal focus events (D-30): an option with no effect (P-4, P-5). W14 said "0 sbt" but D runs `sbt check`. Gate 3 named L2 only. The legend said "K-n, S-n, G-n" (tables use K1, S1, G1) and did not separate PR F1-F4 from IR/FR F-ids. |
| Durability of the work | GAP, NEW DECISION D-32: `.work/` is git-ignored and the container is not durable. This plan, `plan/*` and the upstream draft are not on `wip/handoff`. |
| `upstream-kyo-ui-issue.md` | PASS: "DRAFT: NOT POSTED" in lines 1-4 and the title. GAP, FIXED: it gave "3 to 131 split frames in 2000 events" as measured; that number is desktop-only with no log here (RR skeptic "refuted" 10). It now gives the range with both sources. |

### Spot checks of facts (read only, 2026-10-03)

| # | Claim in the plan | Result | Evidence |
| --- | --- | --- | --- |
| 1 | Branch heads (`chore/cleanup` 6f70179, `docs/docus` 8f485c2, PR 3-5 heads, `feat/zio`, `feat/cats`, `wip/handoff` 1d100ed, `first-take`, `chore/cleanup-7p2rll`) | CONFIRMED | `git rev-parse origin/<b>` |
| 2 | All three `compat.sbt` are `Compatibility.None` | CONFIRMED | `git show origin/chore/cleanup:modules/<m>/compat.sbt` |
| 3 | `.work/` is git-ignored at line 42 | CONFIRMED | `git show origin/chore/cleanup:.gitignore` |
| 4 | No fatal-warning flag, no `concurrentRestrictions`, `root.aggregate(core, zio, cats, examples)` | CONFIRMED | `origin/chore/cleanup:build.sbt` lines 23-29, 49 |
| 5 | Repo private; `delete_branch_on_merge` false; squash and rebase allowed; `main` not protected | CONFIRMED | `gh api repos/yuri4n/tui4s`; `.../branches/main/protection`: 404 |
| 6 | 8 unresolved threads at the paths and lines of 4.1 | CONFIRMED (path, line, PR); the author "bot" was not checked again | `gh api .../pulls/{3,4,5}/ccr/review_threads` |
| 7 | Kyo `observe`: read `current`, run `f`, then `nextWith`; repair interval 1 s | CONFIRMED | `.work/scratch/kyo-src/kyo-core/kyo/Signal.scala:101-107, 275` |
| 8 | kyo-ui `Href.Absolute(url: HttpUrl)`; handlers `Any < Async`; `handler.get.unit` | CONFIRMED | kyo-ui `UI.scala:499`, `UI.scala:771-788`, `internal/ReactiveUI.scala:337, 341` |
| 9 | `Mount.scala:117` keys `Component` state by class name; `:128, 147` use `Fiber.initUnscoped` | CONFIRMED (the key is the tree path plus the class name) | proto `internal/Mount.scala` |
| 10 | `Paint.scala:224-227` placeholders; `Runner.scala:145-146` stderr print | CONFIRMED (the print runs only when `SchedulerProbe.debug` is on) | proto `internal/Paint.scala`, `Runner.scala` |
| 11 | Sizes of the carried PR 5 files and tests; `feat/zio` and `feat/cats` files | CONFIRMED (all 26 + 6 numbers) | `git show <ref>:<path> \| wc -l` |
| 12 | `SchedulerDefaults` values at lines 22-27; opt-out property | CONFIRMED (opt-out at lines 16-18) | PR 5 `spi/SchedulerDefaults.scala` |
| 13 | `Scheduler.scala` RC5 -> RC7: 194 diff lines; `busyWorkers()` new in RC7 | CONFIRMED (`diff \| wc -l` = 194; RC7 line 442; no match in RC5) | `.work/scratch/kyo-src{,-rc5}/kyo-scheduler/kyo/scheduler/Scheduler.scala` |
| 14 | 56 effect points in the full ZIO mirror | CONFIRMED (also 17 leak lines) | FR `logs/fixer-generate-full.txt` line 8 |
| 15 | `docs.yml` runs on `docs/**` only; Vercel `ignoreCommand`; the PR 2 docs rule | CONFIRMED | `origin/docs/docus:.github/workflows/docs.yml`, `docs/vercel.json`; `git diff` of AGENTS.md |
| 16 | CI runs on stacked PRs | CONFIRMED (`on: pull_request` has no branch filter) | `origin/chore/cleanup:.github/workflows/ci.yml` |
| 17 | "`KyoSignallingRef` 111" | CORRECTED: 111 is the whole proto file `cats/src/Bridge.scala`; the class starts at line 62 | `wc -l`, `grep -n` |
| 18 | "11 `claude` marks" in the prototype | CLARIFIED: 11 in the runner `src/`, plus 4 in `test/Apps.test.scala` | `grep -c "// claude"` |
| 19 | Cloud toolchain: JDK 25 default, wrappers in `/usr/local/bin`, 4 CPUs | CONFIRMED (the `sbt` wrapper sets `JAVA_HOME` to JDK 25) | `java -version`, `which`, `nproc`, `/usr/local/bin/sbt` |

### What I changed (small, precise edits)

- "How to read": Kn, Sn, Gn; a bare F1-F4 is a PR; IR and FR F-ids keep their prefix.
- Section 1 table: the C1 base names the PR 1 condition (D-6).
- 3.1: A1-A4 are confirmed with 3.1, before W0. D-5: the `rulesets` hint. New D-32 (durable notes).
- D-25: FC-D2 states the partial exception to DECISIONS round 5; FC-D3 states what "every effect point" then means.
- 4.3: a C1 bullet (PR 1 first). 4.4: owners for the pushes to `docs/docus` and to `wip/handoff`. 5.4: gate 3 when
  L1 and L2 drop.
- 6.0: P-7 mark count; P-14 "no `version`, no `publishTo`"; the toolchain line (one sbt process at a time; the
  attribution lines).
- T1: no `focusEvents` field; `captureOutput` default `true`; no mouse or focus-change test cases. T2: the scheduler
  opt-out stays. F3: the `KyoSignallingRef` evidence. 6.14: Vercel preview deploys. 6.15: W14 builds.
- 7.6: 6 new rows (RR skeptic items) and the open question 1 row. 7.7: IR Q4 row made precise; a row for IR "Gaps
  for the real modules". New 7.9 (decision ids of the inventories). K16: preview deploys.
- `upstream-kyo-ui-issue.md`: the hop-count claim and its evidence row (still DRAFT: NOT POSTED).

### What remains (for the user, or for a later pass)

1. D-32 is new and urgent: this plan and its inputs exist only in this container. The user decides before the
   session ends (REC: one new commit on `wip/handoff`, no force push).
2. The agent limit "at most 2 when building" (DECISIONS round 1). The plan reads it as "at most 2 agents that build,
   at most 4 at once": W0 runs 3 agents with 2 builders, and shape 8 runs 3 skeptics at once while one of them runs
   sbt. Precedent: HANDOFF W4 "at most 4 at once, 1 build". If the user means "at most 2 agents at once while any
   agent builds", W0 and shape 8 need fewer parallel agents (more wall time; same tokens). The user confirms.
3. D-25 FC-D2 is now an explicit exception to DECISIONS round 5. The user confirms it or takes option (c).
4. Still TO VERIFY by the user: rulesets on this private repo (D-5); Vercel previews and files outside the Root
   Directory (6.14, K21); the CI runner size (K11).
5. Facts that the plan takes from the drafts and judges, which I did not check again: DK F-1, F-4, F-8, F-12; J-M
   section 2 ("a CI run takes about 3 minutes"); the "bot" author of the 8 threads.
6. Token and agent numbers are estimates (section 6.15). I re-added them: 108 agents and about 28.7-34.5 M tokens
   agree with the table.
