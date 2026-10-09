# Handoff: where to start (rewritten 2026-10-01 evening, session paused at the user's request)

Say to the next session: **"continue from .work/HANDOFF.md"**.
Read in this order: this file -> `.work/DECISIONS.md` -> `.work/design/SPEC.md` (section 9 top = user decisions,
section 8 = PR plan) -> `.work/impl/*.md` for the module you touch. Old, detailed log: `.work/HANDOFF-2026-10-01-log.md`.
Toolchain: `source .work/env.sh` (JDK 25, sbt, node 24, pnpm 10, IPv4, -Xss16m). Do NOT kill PID 7740 (or any sbt
started from the main checkout): it is the user's Metals sbt server.

<!-- claude 2026-10-03: created this section (cloud session resume) -->
## 2026-10-03: RESUMED IN A CLOUD SESSION (read first)

- The desktop pushed `.work/` to branch `wip/handoff` (commit 2044c03). The cloud session extracted it with
  `git archive origin/wip/handoff .work | tar x`. Do not merge `wip/handoff`.
- Cloud toolchain (not in env.sh; env.sh is for the Mac): JDK 25 from apt (`/usr/lib/jvm/java-25-openjdk-amd64`),
  sbt launcher wrapper `/usr/local/bin/sbt`, scala-cli wrapper `/usr/local/bin/scala-cli` (sets JDK 25, proxy
  truststore, proxy port from `$HTTPS_PROXY`, `JDK_JAVA_OPTIONS=-Xss16m -Xmx2g`). Always use `--jvm system`.
  Maven Central sends HTTP 429 under parallel downloads: retry with backoff.
- Baseline on the cloud machine (4 CPUs) before the resume: runner 7/8 (stale colour assertion at
  `test/Apps.test.scala:123`); Cats facade 9/9; ZIO facade FLAKY (a different test fails on each run).
- Resume run: workflow `wf_6bb71261-308` (6 agents, max 2 at once): resume runner + facades -> one skeptic each
  (fix if refuted) -> integration (facade apps on the terminal runner, `.work/proto/kyoui-integration/`) +
  side-by-side code (`.work/proto/SIDE-BY-SIDE.md`, `side-by-side.json`).
- RESULT of `wf_6bb71261-308` (7 agents, about 2.0M tokens, all on the 4-CPU cloud machine):
  - Runner: DONE. 9/9 tests x 3 runs; settle bug fixed (probe the scheduler before draining the inbox;
    `Settle.Quiescent(100.millis, confirm = 2)`); overflow layout bug fixed; bench p50 0.3-0.75 ms per event;
    real PTY Counter + Todo pass (16/16 terminal, 20/20 screen checks; stty restored). Skeptic: 0 blocking.
  - Facades: DONE. ZIO flakiness root cause = two state cells (zio SubscriptionRef + kyo SignalRef) synced by
    pumps; fix = facade-owned `SignalRef[A]` on the one kyo-ui cell (ZIO seals Ref/SubscriptionRef). ZIO 5/5, Cats
    5/5 runs; full 52-element mirror compiles and runs. Skeptic: 1 blocking (REPORT missing) -> fixed.
  - Integration: DONE (`.work/proto/kyoui-integration/`). Unchanged ZIO/Cats apps run on the terminal runner:
    12/12 x 3, PTY 10/10 + 22/22. Runner problems found, NOT fixed: R1 watcher leak on interrupt (~1.75 MB/run),
    R2 first-key race, R3 OutputGuard misses scala.Console.out; R4/R5 Kyo-free options, quit as a value.
  - Side by side: `.work/proto/SIDE-BY-SIDE.md`, `side-by-side.json` (generator `side-by-side-gen.py DONE`).
    Page: https://claude.ai/artifact/5caZtjTZk8uohrUy3QoRei (source in `.work/proto/side-by-side-page/`).
- Open questions for the user are at the end of each REPORT.md (runner 8, facades 8, integration 4).
- 2026-10-03 later: `.work/` pushed to `wip/handoff` (commit 1d100ed, without .scala-build/.bsp). The user said "I like
  it, go ahead": planning run `wf_ccf6b226-c52` (9 agents, read-only) writes `.work/design/plan/*` and
  `.work/design/PLAN-kyoui.md` + `upstream-kyo-ui-issue.md` (DRAFT, never post without the user). Assumptions A1-A4
  (PR shape a/b/c; upstream issue draft only; ZIO SignalRef; checked-in mirrors + drift CI) await confirmation.
<!-- claude 2026-10-09: added this item (round 6) -->
- 2026-10-09: ROUND 6 (see DECISIONS.md). Kyo phase first (C1-C4, T1, T2, P1, L1, L2, D); the ZIO phase starts after D
  merges, the Cats phase after it. No generator: the ZIO and Cats DSLs are hand-written, with a coverage test. C3
  routes all bound writes through one runner function. The ZIO state design (Z1/Z2/Z3) is picked by spike SZ at the
  ZIO start. Plan: `design/PLAN-kyoui.md` section 0. Kyo removed its Typelevel modules (getkyo/kyo#1778); our Cats
  bridge is already vendored. Prior art: ghostdogpr/sage, gruggiero/typesafe4s (kyo-compat + vendored CE backend).
  NEXT: the user confirms A1-A4 (A3 and A4 are now superseded by round 6) and D-1 (run W0 first); then W0 (5 agents).
- (superseded) NEXT: the user reads the side-by-side page and answers the blocking questions; then plan the rework of PRs 3-5
  on kyo-ui (runner proposal: (a) runner core, (b) terminal + app from PR 5, (c) examples/ptycheck/bench;
  close or replace PRs 3 and 4).

## 2026-10-02: DIRECTION CHANGE

The user reviewed the core and said it is not a functional wrapper of jatatui and that the internals are not
functional (true: internal/ is ~3.5k lines of mutable, Java-style code; Engine has 25 vars). They also asked
how Elm-style updates work (whole view rebuilt + whole buffer repainted, only changed cells flushed).
Discussion: calico (Scala.js, armanbilge/calico: components = Resource, state = SignallingRef, fine-grained
signal bindings, glitch-free). The user wants type-member components:
`abstract class Component { type State; type Out; def initial: State; def view(state: Signal[State]): View ... }`
and is unsure between pure handlers, a state-mutation effect, and a stream-based design.
RUNNING: prototype workflow run wf_05f0fcb5-0fe (3 agents; script ~/.claude/projects/-Users-yuri4n-Developer-projects-tui4s/5cb543b2-f521-4487-ae5b-1fedd18ac66c/workflows/scripts/tui4s-prototypes-wf_05f0fcb5-0fe.js) that builds the Counter / Todo / Dashboard three ways on jatatui with
partial repaint, under .work/proto/{pure,mut,streams}/ with REPORT.md each. The user will pick by reading code.
ALL other work (facades W4, docs W5) is PAUSED until the user picks; PRs 3-5 will be reworked on the chosen model.
UPDATE: the user pointed at kyo-ui. It already IS the model we were converging to (pure UI values, signals,
fine-grained subscriptions per signal, handlers `Any < Async`, SignalRef two-way binding, and its README invites
custom backends; runners today: DOM, server-push, HTML stream; no terminal runner upstream). The prototype run
was STOPPED at plan stage (notes only in .work/proto/{pure,mut}/REPORT.md). Proposed new direction: tui4s =
a terminal runner for kyo-ui (UI -> jatatui). USER AGREED (see DECISIONS.md fifth round).
STOPPED (user asked to finish later, 2026-10-02): kyo-ui prototype workflow run wf_b03f7ee5-dfd (2 agents; script ~/.claude/projects/-Users-yuri4n-Developer-projects-tui4s--work-scratch-kyo-src-kyo-ui-kyo-internal/5cb543b2-f521-4487-ae5b-1fedd18ac66c/workflows/scripts/tui4s-kyoui-prototypes-wf_b03f7ee5-dfd.js): (A) terminal runner for kyo-ui with partial repaint, the three
apps in plain kyo-ui + useSignal + Component class, PTY run -> .work/proto/kyoui-runner/REPORT.md;
(B) ZIO/Cats facade mirror feasibility (generated from kyo-ui API with tasty-query) -> .work/proto/kyoui-facades/REPORT.md.
Partial work saved in .work/proto/kyoui-runner/ (8 files, REPORT.md with plan + findings so far) and
.work/proto/kyoui-facades/ (9 files). NEXT: resume the run (same session: Workflow({scriptPath, resumeFromRunId});
new session: re-run the script and tell both agents to continue from their folder and REPORT.md), then show the
user the user-facing code side by side, then plan the rework of PRs 3-5 on kyo-ui.

## State of the work

All work is committed and pushed (feat/zio and feat/cats are pushed as a backup, with no PR yet). All worktrees are clean.
No workflow or agent is running.

| PR | Branch (base) | Content | CI |
| --- | --- | --- | --- |
| [1](https://github.com/yuri4n/tui4s/pull/1) | chore/cleanup (main) | restructure, build, release (fully automated sonaRelease), AGENTS.md | green |
| [2](https://github.com/yuri4n/tui4s/pull/2) | docs/docus (chore/cleanup) | Docus 5.13.0 site for Vercel, landing/install/versions/AI pages | green |
| [3](https://github.com/yuri4n/tui4s/pull/3) | feat/core-1-model-view (chore/cleanup) | core C1+C2: model, view, painter, sync TuiTest | green |
| [4](https://github.com/yuri4n/tui4s/pull/4) | feat/core-2-engine (feat/core-1-model-view) | core C3+C4: sources, engine, loop, live kit | green |
| [5](https://github.com/yuri4n/tui4s/pull/5) | feat/core-3-terminal (feat/core-2-engine) | core C5-C7: terminal, TuiApp, examples, launchers, ptycheck, bench | green (incl. Linux `terminal` job) |
| (not open) | feat/zio (feat/core-3-terminal) | Z1-Z3 implemented, 5 commits, check green; review done; FIX PENDING | - |
| (not open) | feat/cats (feat/core-3-terminal) | K1-K3 implemented, 11 commits, check green; review NOT started | - |

Worktrees (git-excluded, under `.work/worktrees/`): `core` (feat/core-3-terminal), `zio`, `cats`, `docs`.
The main checkout is on chore/cleanup.

## Next steps, in order

1. **Finish W4 (facades).** The workflow was stopped on purpose while the ZIO fixer had just started (it made
   no edits). Finished agents: 2 implementers, 3 ZIO reviewers, 3 ZIO skeptics. Still to run: the ZIO fixer,
   the Cats review (3 reviewers + 3 skeptics) and the Cats fixer: 8 agents, at most 4 at once, 1 build.
   - Same session: `Workflow({scriptPath: "~/.claude/projects/-Users-yuri4n-Developer-projects-tui4s/5cb543b2-f521-4487-ae5b-1fedd18ac66c/workflows/scripts/tui4s-facades-wf_c92a2d58-bee.js", resumeFromRunId: "wf_c92a2d58-bee"})`
     replays the finished agents from cache.
   - New session (resume is same-session only): copy that script, drop the implement phase (both branches are
     implemented and committed), feed the ZIO fixer the findings from `.work/impl/zio-review-*.md` and
     `zio-verify-*.md`, and run the Cats review -> verify -> fix as written.
   Pending ZIO findings (14 confirmed): live-kit settle hangs on tasks that sleep on zio-test's TestClock, and
   the first settle runs inside an uninterruptible acquire (api-1, runtime-1, runtime-3, spec-2); dead Kyo
   waiters leak on `done` for each `states` pull / interrupted `await` (runtime-2, spec-1); an interrupted
   `TuiHandle.stop` kills `await` with kyo.Interrupted (runtime-4, spec-5); ZIO TuiApp has no `args` (api-2);
   `LiveTest` import ambiguity (api-3); Console/println capture under a user's own ZIOAppDefault (api-6,
   spec-6); default loggers stay removed when start fails (spec-3); two weak tests (spec-4). Refuted: api-4,
   api-5.
2. **After W4:** verify each facade myself (`sbt check` in its worktree; an expect check of the ZIO and Cats
   examples like `examples/ptycheck`; the Cats implementer already passed a scratch expect script). Push
   feat/zio and feat/cats; open PR 6 (zio) and PR 7 (cats), both based on feat/core-3-terminal. Check for
   conflicts: both add `examples/<m>.sbt` (no conflict by design); commit `f84f6f8` on feat/cats renamed the
   example start scripts after the package: check that `examples/ptycheck/checklist.exp` still finds
   `counter`, `todo`, `dashboard`.
3. **Shared follow-ups after both facade PRs** (small PR or in W5): add the ziocounter / ziodashboard /
   catscounter / catsdashboard rows to examples/README.md and to examples/ptycheck/checklist.exp; move the ZIO
   version literal to project/Versions.scala; troubleshooting entry for the `fs2.*` import clash; ZIO TestClock
   note ("adjust one period at a time after TestClock.sleeps shows the source asleep").
4. **W5 docs** (SPEC 8.5) on the docs/docus branch (worktree `.work/worktrees/docs`, PR 2; rebase it on the
   latest stack first): getting started per ecosystem; concepts (state, update, view, sources, tasks, keymaps,
   focus); testing (TuiTest, live kits); performance (frame rules, Stats, trace, scheduler flags);
   troubleshooting (do not use `sbt run`, use a launcher or a packager; `-batch` gives a pipe; import rules;
   NotForeign messages; the 4096 chunk trap). Include code from `examples/` so it cannot drift. `pnpm build`;
   never deploy (Vercel deploys).
5. **Merge order when the user approves:** 1 -> 2 and 3 -> 4 -> 5 -> 6 / 7 (GitHub retargets stacked bases when
   a base branch is merged and deleted). Never merge or release without the user.

## Open items for the user (none blocking)

- PR 4: `settle` also waits for standing `task` sources (the spec says one-shot tasks only); a task asleep on the
  virtual clock counts as idle. Default kept unless the user objects.
- PR 5: look and feel in macOS Terminal / iTerm2 still needs a human (the automatic checklist passed 47/47).
- bench/BASELINE.md: some budgets missed on a busy machine (B1, B3 frame times, B4 p99, B6 CPU); re-measure idle.
- Publishing: secrets are set, releases are fully automated after a tag push; nothing released. Repo private.

## Facts the next session must keep

- User decisions: `.work/DECISIONS.md` (packages tui4s.interop.zio / .cats; capture on; scheduler flags on;
  kyo-test core / munit elsewhere; bench; testkit in core; email oss.juli@pm.me; fully automated releases).
- Do not start a TUI with `sbt run` (sbt reads the keyboard too); use the `sbt examples/launchers` scripts.
- Real-terminal checks: `examples/ptycheck/run.sh` (expect + mini VT emulator). Scratch copies: `.work/scratch/pty/`.
- Package-tui4s files import with `_root_.` (incremental-compile cycle through `export dsl.*`).
- kyo-test's final "0 tests" summary line is cosmetic; a failing test does fail sbt (checked).
- Reusable one-PR workflow script (implement -> 3 reviews -> 3 skeptics -> fix; args key, title, branch, base,
  worktree, scope, extra): `~/.claude/projects/-Users-yuri4n-Developer-projects-tui4s/5cb543b2-f521-4487-ae5b-1fedd18ac66c/workflows/scripts/tui4s-impl-pr-wf_6df1d964-e03.js`.
- Token use on 2026-10-01: design panel 3.5M, W1 2.1M, W2 2.4M, W3 2.4M, W4 (partial) about 1.5M.

## Constraints (user AGENTS.md)

<= 4 agents in parallel, <= 2 when building; one workflow at a time; announce runs > 10 agents; agents save
notes early (`.work/impl/`); keep durable copies in `.work/`; never deploy docs; release only with approval;
never type credentials into anything.
