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
<!-- claude 2026-10-09: added the agent limits of the cloud environment (user asked to record them) -->
- AGENT LIMITS (user: "the sweet spot of cost x benefit for each environment"; claude 2026-10-09: changed this item).
  Model: an agent is LLM-bound (read, review, write: almost no local CPU) or build-bound (scalac on Kyo, test JVMs:
  all cores). The token cost almost does not change with concurrency; concurrency buys time. The hidden cost is CPU
  contention: timing suites then fail falsely, and an agent spends tokens on a ghost. Thus: many LLM-bound agents,
  few builds, and timing work alone.
  - CLOUD (4 CPUs, 15 GB RAM): at most 2 agents at once (a hard cap: the workflow runtime runs at most
    min(16, CPUs - 2) agents at once). Both can compile and run plain tests (memory is sufficient: sbt about 3 GB,
    Bloop 2.2 GB). Do not add Agent-tool agents next to a workflow to pass the cap: on 4 cores they only add
    contention.
  - LOCAL (Mac): at most 4 agents at once; at most 2 that build, and not more than cores/4 (`sysctl -n hw.ncpu`).
    More than 4 buys only time and uses the usage window faster. Never kill the Metals sbt server of the user.
  - BOTH: timing-sensitive suites (plan 6.0: C2, C3, C4, T1, F1-F4) and all timing numbers run with no other build
    on the machine. One workflow at a time. Announce a run of more than 10 agents. In a new container, check nproc
    first.
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
<!-- claude 2026-10-09: added this item (PR 1 merged, W0 started) -->
- 2026-10-09 later: the user squash-merged PR 1 (`main` = 863db67) after the Codex fixes (version order like Maven,
  `build.sbt` and `project/` watched). The user closed PRs 2, 3 and 4 without a merge. PR 5 is still open. Then the
  user said "done. continue": W0 started with the RECs D-1 (a), D-11 (a), D-32 (a). The other 3.1 decisions
  (D-3..D-10, D-12, D-13) go to gate 0, with 3.2. Effects on the plan: D-4 (the user used squash, so the next stacked
  branch needs a rebase or a merge after each squash); D-7 is now option (c) (new docs branch at D; `docs/docus`
  still exists).
- STANDING RULE (user, 2026-10-09): "go simpler" on small tasks. A small task (a review reply, a small fix, a
  question) gets no workflow and no agents: do it directly and report in a few lines.
<!-- claude 2026-10-09: added this item (S8) -->
- 2026-10-09: DECISIONS round 7: after W0 (run wf_bf024cec-c9f), run spike S8 (one agent; State-monad UI baseline;
  PLAN section 0.6). NEXT after W0: push `.work/` to `wip/handoff`, give `gates.md` in the chat. S8 is CANCELLED and parked
  (`design/parked/S8-state-ui.md`); do not run it unless the user asks.
<!-- claude 2026-10-09: added this item (W0 restart) -->
- 2026-10-09 21:09: the container restarted during W0. B (S6), C and E (S3, S4) were done and stay cached in the
  journal; A (S1, S2) was cut; M had not started. Resumed the same run (`resumeFromRunId: wf_bf024cec-c9f`): A
  continues from its files, then M. FACT for all workflows: the subagent harness REFUSES report .md files ("Subagents
  should return findings as text"). Give each agent an output field for the report text; the orchestrator writes the
  file. The REPORT.md files of s6-flags, s3-drift and s4-weight were written this way.
<!-- claude 2026-10-09: added this item (W0 done) -->
- 2026-10-09 23:41: W0 DONE. Results: `design/plan/gates.md` (M), spike REPORT.md files, `design/upstream-w0-numbers.md`
  (not applied to the draft yet). G1 NO-GO (Kyo values) / SOFT (tui4s values): ASK D-17. G2: R1 option (b); R2 GO
  with "armkey". G3 NO-GO: ASK D-19 (pin RC7). G4 GO. G6 GO. NEW RISK K29: `Signal.observe` keeps about 1.7 KB per
  change (about 300 MB per hour at 5 regions x 10 changes/s): decide at gate 0 for C2.
  LESSON: a resume caches only the UNCHANGED PREFIX of agent() calls. I edited agent A (the first call), so B, C and E
  ran again (1.4 M tokens in the resumed run) and spoiled some A timings. To resume, edit only calls after the last
  cached one.
  NEXT: gate 0. The user answers D-17, D-19, K29, D-4 (merge method), and the 3.1 RECs; then C1 (W1).
- 2026-10-11: GATE 0 (partly answered; DECISIONS.md eighth round). D-17 (c) + TODO on the Kyo fix of the lost
  wakeup; D-19 yes; D-4 (c) squash + rebase of the next branch; D-8 CHANGED to sbt-tpolecat (unused warnings stay
  warnings; CI mode open); K29 = option (2) own loop + TODO (user 2026-10-12: "ignore 3c"). The user wants upstream issues at
  getkyo/kyo for the lost wakeup and the `observe` leak if none exist (drafts first; the user approves each post),
  and TODOs and notes on GitHub Projects and the wiki "as much as you can" (from the cloud: issues yes; Projects v2
  no tool; wiki no credentials). NEXT: the user answers K29 and the CI-mode question; then the upstream drafts, the
  GitHub issues for the TODOs, and C1 (W1) with sbt-tpolecat.
<!-- claude 2026-10-11: added this item -->
- 2026-10-11 later: the observe-leak reproducer `design/upstream/ObserveLeak.scala` is tested (1.7 KB per change; OOM
  before 27 000 changes at -Xmx48m; the JVM hangs after the OOM, so run it under `timeout`). The two upstream drafts
  are complete in `design/upstream/kyo-signal-issues.md` (NOT POSTED). TOOL LESSON: `pgrep -f <word>` or
  `ps | grep <word>` in a Bash tool call matches the call's own command line when <word> is in it, and a kill loop
  then kills the shell (happened twice). Find JVMs with `pgrep -x java` and read `/proc/<pid>/cmdline`.
<!-- claude 2026-10-11: added this item -->
- (superseded) NEXT: the user reads the side-by-side page and answers the blocking questions; then plan the rework of PRs 3-5
  on kyo-ui (runner proposal: (a) runner core, (b) terminal + app from PR 5, (c) examples/ptycheck/bench;
  close or replace PRs 3 and 4).

<!-- claude 2026-10-13: added this item (gate 1 closed) -->
- 2026-10-13: GATE 1 CLOSED. The user: "go for your solution and move ahead. want to see that usable version asap".
  DECISIONS eleventh round: the gate 1 answers (seal tui4s.internal in C3; CI sets the glitch load flag; the repair
  tick re-render and the self-cancelling canary are accepted; D-14 and D-15 confirmed; frame = batch and the
  single-driver kit stand), the WIP limit lifted until the terminal PR is open, the T1 RECs D-21/22/24 (a), and the
  NEW ORDER C3 -> T1 -> C4 -> T2 (T1 before C4: the terminal Counter needs no hooks). Branches: feat/kyoui-03-input,
  feat/kyoui-04-terminal, feat/kyoui-05-components, feat/kyoui-06-app-examples. chore/claude-rules pushed plain;
  PR 5 closed with the plan 4.1 comment. W3 (C3) runs with Opus agents (Fable only for the runtime reviewer R2 and
  its skeptic K2); script tui4s-w3-c3-input-*.js in the session workflows dir.

<!-- claude 2026-10-13: added this item (W3 done) -->
- 2026-10-13 15:50 UTC: W3 DONE: workflow wf_1719fd7f-36b (7 agents, Opus except R2/K2 on Fable, 2.21 M tokens,
  1 h 50 min). C3 tip f29b9ff (23 commits + the merge-up of C2's 0646343); 109 tests, 0 warnings, 3 final runs green
  (one under contention), the CI load flag works. PR #13 `feat(core): keys reach the UI` against
  feat/kyoui-02-regions; this session watches it. PR #11 got the test-only commit 0646343 (the flaky torn-frame leaf;
  DECISIONS twelfth round). Logs: .work/impl/c3-*.log, c3-notes.txt, c3-pr-body.txt. Script:
  tui4s-w3-c3-input-wf_1719fd7f-36b.js in the session workflows dir (reuse for C4 with the C4 content).
  NEXT: W5 = T1 feat/kyoui-04-terminal (worktree .work/worktrees/feat-kyoui-04-terminal from f29b9ff; shape 8 + V;
  the script draft is scratchpad/tui4s-w5-t1-terminal.draft.js), then W4 = C4 feat/kyoui-05-components on T1.

<!-- claude 2026-10-13: added this item (C3 round 2 queued) -->
- 2026-10-13 15:35 UTC: Codex reviewed PR #13 (f29b9ff): three P2 findings in Dispatch.scala (a keyboard click does
  not bubble to the ancestors' onClick; onSubmitEvt never runs; onFocusEvt/onBlurEvt never run). Checked against
  kyo-ui RC7 ReactiveUI.dispatchToElement (.work/scratch/kyo-src): the focus/blur rule of kyo-ui itself runs both
  forms (a real mismatch); the keydown rule runs only the target's plain onClick and only f.onSubmit, but the Click rule
  bubbles and runs both submit forms, and in a browser Enter on a button IS a click. DECISION: build all three as "a
  keyboard activation of a button is a click: kyo-ui's Click rule over the chain; every submit and focus change runs
  both handler forms". The C3 P-12 "deferred (D-30)" for the Evt forms was a stretch (D-30 is mouse and terminal
  focus events). QUEUED behind W5 (one sbt at a time): the brief is scratchpad/c3-round2-brief.md (copy in
  .work/impl/c3-round2-brief.md); shape "fix round" (fixer Opus, skeptic Fable), then push C3, merge-up into T1,
  sbt check there, push T1, open T1's PR. CI is green on #11 (0646343) and #13 (f29b9ff).

<!-- claude 2026-10-13: added this item (C3 round 2 done; W5 partial) -->
- 2026-10-13 17:15 UTC: C3 ROUND 2 DONE by the session itself (Fable; the Opus budget was out): 583a0c8 (the
  dispatcher: a keyboard activation of a button runs kyo-ui's Click rule over the chain; onSubmit, onFocus, onBlur run
  both forms), 2eaf081 (guard pins), a6c4598 (docs); check green, 112 core tests (c3-r2-check.log); pushed; PR #13
  body updated; the three Codex threads answered and resolved. The W3 shape "fixer + skeptic" was NOT used (no
  Opus): an Opus skeptic can re-check 583a0c8 later if wanted.
  W5 (T1) PARTIAL: wf_f2ccf226-44b ran I, R1-R3, K1-K3 (7 agents, 2.49 M tokens, 85 min), then F and V FAILED on the
  org's monthly spend limit ("resets 6pm UTC"). T1 tip b7ac3ec on feat/kyoui-04-terminal (14 commits; 170 core + 4
  examples tests; PTY kit 16/16 rows at the tip, t1-pty-tip.log). Findings: R1 5 (1 major: the kit's upper-case
  letter has no shift, the terminal's has), R2 6, R3 9, mostly confirmed minors. A send_later at 18:06 UTC resumes
  the run (Workflow resumeFromRunId wf_f2ccf226-44b: the 7 cached, F and V live). AFTER W5: merge feat/kyoui-03-input
  (a6c4598) into T1, sbt check, push T1, open its PR against feat/kyoui-03-input, then W4 = C4 on T1.

<!-- claude 2026-10-13: added this item (W5b) -->
- 2026-10-13 18:14 UTC: the container RESTARTED at 18:09 (uptime 1 min at the resume); the Workflow tool then refused
  the saved script path and the in-memory resume cache was gone. The 7 W5 results were extracted from the journal
  into .work/impl/t1-w5-cache.json (156 KB). W5b = workflow wf_066520fc-8e5 (2 agents, Opus): the fixer merges
  feat/kyoui-03-input (a6c4598, C3 round 2) into T1 first (plan 4.3), then fixes the confirmed findings (R1-1 major:
  the kit's upper-case letter must carry shift as EventMapping gives it), then V does the 3 test runs and 3 PTY runs.
  The fixer's partial edit before the budget cut is saved as .work/impl/t1-fixer-partial.patch (the worktree was
  restored to b7ac3ec). LESSON: a container restart kills the workflow resume cache; the journal on disk is the
  recovery path (extract the 'result' lines, hand a JSON file to a continuation script).

<!-- claude 2026-10-13: added this item (T1 done) -->
- 2026-10-13 18:50 UTC: W5b DONE (2 agents, 0.63 M tokens, 27 min): merge-up 0ed0710 (C3 round 2 into T1; one
  conflict in AGENTS.md marks, both kept), 7 review-fix commits (R1-1: the kit types an upper-case letter with Shift
  through EventMapping.typed; R2-1 poll floor 1 ms; R2-3 the innermost cause; R2-4 every error but a
  VirtualMachineError ends the run; docs; marks; CI and kit nits), tip 0893800; 177 core + 4 examples tests, 0
  warnings; 3 test runs and 3 PTY runs green, one of each under contention (17/17 rows). PUSHED; PR #14
  `feat(core): Tui.run on the real terminal, with the PTY check in CI` against feat/kyoui-03-input; subscribed.
  THE FIRST USABLE VERSION: `sbt examples/launchers`, then `examples/target/bin/counter` (JDK 25 in JAVA_HOME or
  on PATH). Open question for the user: a jatatui issue draft (the JNI cuts a typed non-BMP character to 16 bits).
  NEXT: W4 = C4 feat/kyoui-05-components (worktree created from 0893800), shape 6 + V, Opus except R2/K2; then T2.
  Logs: .work/impl/t1-*; the body t1-pr-body.txt (346 lines, 61.8 KB: just under GitHub's limit).

<!-- claude 2026-10-13: added this item (W4 launched) -->
- 2026-10-13 18:58 UTC: W4 = C4 launched: workflow wf_f99c07c9-d80 (shape 6 + V: I, R1, R2, K1, K2, F, V; Opus except
  R2/K2), worktree .work/worktrees/feat-kyoui-05-components from T1 0893800; script
  tui4s-w4-c4-components-wf_f99c07c9-d80.js in the session workflows dir. A send_later check-in fires at about
  21:48 UTC. AFTER W4: push, PR against feat/kyoui-04-terminal, subscribe, notes; then W6 = T2
  (feat/kyoui-06-app-examples: TuiApp, SchedulerDefaults, all examples, all PTY rows; plan 6.6; shape 6).

<!-- claude 2026-10-13: added this item (T1 round 2 queued) -->
- 2026-10-13 18:50 UTC: Codex on PR #14: one P1 (TerminalIO.scala:138): the release steps catch only NonFatal, so a
  LinkageError of a native call at leave would skip disableRawMode (raw mode left). CONFIRMED by reading; the same
  gap the T1 fixer closed in the input thread (R2-4). The fix and the test are written, UNCOMMITTED, in the T1 worktree;
  they wait for sbt (W4 owns it). The steps: .work/impl/t1-round2-brief.txt. Then merge-up into C4.

<!-- claude 2026-10-13: added these three items (T1 round 2 done, W4 done and PR #15, W6 launched) -->
- 2026-10-13 21:15 UTC: T1 ROUND 2 DONE by the session (Fable): b912d3b `fix(terminal): a LinkageError in a release step
  does not stop the restore (PR #14 review: Codex)`. Negative control t1-r2-negative-control.log (the new leaf fails
  with the escaped UnsatisfiedLinkError); t1-r2-test.log; t1-r2-check.log (CI mode, 0 warnings, 178 core leaves);
  version order 55/55; t1-r2-pty.log (17 terminal rows, 16 screen checks). PUSHED; the Codex thread answered and
  resolved; a round-2 comment on PR #14; CI green on b912d3b (both jobs).
- 2026-10-13 21:20 UTC: W4 DONE (wf_f99c07c9-d80, 7 agents, Opus except R2/K2): C4 code tip 46fe338 (25 commits: 17 of
  the first round + 8 review fixes; 5 prototype bugs and 6 review defects fixed, each with a negative control); the
  merge-up of T1 round 2 = 9047a50 (`git merge`; check green: c4-mergeup-check.log, 209 core leaves, 0 warnings).
  PUSHED; PR #15 `feat(core): components in two styles, hooks and a Component class` against feat/kyoui-04-terminal;
  subscribed; CI at the time of writing: terminal green, sbt running. Body c4-pr-body.txt (60.8 KB). Two questions
  for the user in the body: keep "onMount once per mount" (React-like) or restart onMount on a new instance; log a
  failing hook body or onMount with Log.error (follow-up or in C4). The W4 check-in trigger was deleted (its work is
  done). LESSON: `cp c4-pr-body.txt c4-pr-body-round2.txt` overwrote the fixer's body copy; the body says so.
- 2026-10-13 21:30 UTC: W6 = T2 LAUNCHED: workflow wf_cbfa3587-619 (shape 6 + V: I, R1, R2, K1, K2, F, V; Opus except
  R2/K2 on Fable), worktree .work/worktrees/feat-kyoui-06-app-examples from C4 9047a50 (T2 stacks on C4, not on T1:
  the order C3 -> T1 -> C4 -> T2 of the eleventh round, and the CounterStyles example needs the components). Script:
  tui4s-w6-t2-app-examples-wf_cbfa3587-619.js (the workflows dir of the projects entry named after the T2 worktree
  path). Brief: plan 6.6 and D-24 (a); TuiApp in its final shape (ui, options, args, onExit, main, the runMain seam
  through Tui.runWith; the constructor calls SchedulerDefaults.applyIfUnset() and OutputCapture.install()); the
  examples from the prototype apps, one TuiApp main per package (CounterKyoApp moves to its own package); all PTY
  rows (kill -TERM 143, kill -INT 130, the println row, Todo, Dashboard, CounterStyles, a resize, the class-load INFO
  line); a ReadmeSuite pins the quick-start blocks to the files. A send_later check-in fires at about 00:20 UTC.
  AFTER W6: push, PR against feat/kyoui-05-components, subscribe, logs and notes; then P1 (W7, shape 4), L1, L2, D.

<!-- claude 2026-10-14: added this item (W6 done, PR #16, lean mode) -->
- 2026-10-14 12:30 UTC: W6 DONE (wf_cbfa3587-619, 7 agents, 2.26 M tokens, 109 min): T2 tip 4f174ed (20 commits: 15 +
  5 review fixes; 14 findings: 12 confirmed and fixed, 2 refuted). Final runs all green: clean check CI mode 0
  warnings, 228 core + 35 example tests, 3 test runs and 3 PTY runs (53 terminal rows + 68 screen checks each), one
  of each under contention; class-load of the real Counter launcher about 4090 classes. PUSHED; PR #16 `feat(core):
  TuiApp, every example in the real terminal, the full PTY checklist` against feat/kyoui-05-components (opened by an
  Opus agent that typed the body from t2-pr-body.txt; a diff check against the file follows); subscribed. Logs
  .work/impl/t2-*. The design defect found in 4a (SIGTERM: the restore hook ended the run as a quit, so onExit and
  "interrupted" raced the JVM halt) is fixed with internal.Hooks.shuttingDown. Known gap: a span does not wrap at 40
  columns (Dashboard header): L2.
- 2026-10-14 12:10 UTC: LEAN MODE (user): "use Fable very sparsely and cut corners a little bit (not in the api,
  behind the scenes)". DECISIONS fourteenth round; the user rules file has the same rule. From here: Opus for all
  delegable work (also R2/K2), shape 4, one final run, one PTY run, one-screen PR bodies, negative controls only for
  bug fixes. NEXT: the user runs the examples on feat/kyoui-06-app-examples and merges the stack (#10, #11, #13, #14,
  #15, #16: squash, then Claude merges or rebases the next branch, D-4 c). P1, L1, L2, D wait for the user's word.

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

## State of the work (rewritten 2026-10-12)

<!-- claude 2026-10-12: rewrote this section and the next two. The old text described the Elm stack (PRs 2-5, feat/zio,
feat/cats, W4, W5). That stack is parked since the direction change of 2026-10-02. -->

- Direction (2026-10-02): tui4s is a terminal runner for kyo-ui. Plan: `design/PLAN-kyoui.md` (section 0 wins where
  the sections do not agree). Kyo phase first (C1-C4, T1, T2, P1, L1, L2, D), then the ZIO phase (after spike SZ),
  then Cats.
- `main` = 863db67 (PR 1 squash-merged: build, CI, release automation, AGENTS.md). Nothing else is merged.
- PRs 2, 3 and 4 are closed (old stack; their branches stay, plan 4.2). PR 5 stays open until the user closes it (C1 is
  open as PR #10 since 2026-10-12; C2 as PR #11 against feat/kyoui-01-render, 2026-10-12 evening; this session
  watches both). PR 7 (rulesets for main and the release tags, branch `claude/epic-bohr-eflkfz`) is
  open and is NOT from this session: do not touch it (plan 4.4: repo settings are the user's).
- Old-stack branches (`feat/core-*`, `feat/zio`, `feat/cats`, `docs/docus`): unchanged. `feat/zio` and `feat/cats` are
  backups of work that is not merged. `first-take` is the user's own branch: do not touch.
- W0 is done: `design/plan/gates.md`. Gate 0 is answered: DECISIONS.md eighth round (D-17 c, D-19 yes, K29 option 2,
  D-4 c, D-8 -> sbt-tpolecat). Upstream drafts: `design/upstream/` (NOT POSTED). GitHub issues: #8 (TODO: observe
  leak), #9 (TODO: lost wakeup). Labels: `upstream-kyo`, `todo`.
- Notes live on `wip/handoff` (never merge). Cloud worktree for the notes: `scratchpad/wt-handoff`; `.work` is
  git-ignored, so `git add -f`.
- Spike worktrees under `.work/worktrees/`: `spike-sbt` (local branch `spike/sbt-port`, 4cdeebd: the whole runner
  carried into `modules/core`, strict-flag fixes, `sbt fmt`), `spike-sbt-carry` (fe06611, the carry only),
  `spike-sbt-p7` (8e4d75f). Never push them. fe06611 is the reference for the carry commit of C1 (P-7 step 1).
- Spike build leftovers deleted on 2026-10-12 (190 MB). The spike sources and logs stay.
- W1 DONE (claude 2026-10-12): workflow wf_1048293e-046, 6 agents, 1.29 M tokens, 73 min. Branch feat/kyoui-01-render in
  worktree .work/worktrees/feat-kyoui-01-render, head 66d9299 (16 commits). PUSHED 2026-10-12 after the user's go
  ("ok let's continue"); PR #10 `feat(core): a kyo-ui value renders to cells` against `main`, body = c1-pr-body.txt with
  the logs' location added. This session is subscribed to the PR events (CI, reviews). Review fixes: new commits only. 26 tests, 0 warnings, sbt check green in CI mode, 55/55 version-order cases. All 14 review
  findings minor (11 fixed, 1 disclosed, 2 refuted). Logs and notes: .work/impl/c1-*.log, c1-notes.txt; the PR body:
  .work/impl/c1-pr-body.txt. Open: the implementer excluded -Ycheck-all-patmat from the tpolecat set (false "not
  exhaustive" on Kyo's opaque Maybe); the C2 API review decides whether tui4s.internal becomes private[tui4s]. The
  one-PR workflow script is tui4s-w1-c1-render-wf_1048293e-046.js in the session workflows dir (shape 6; reuse it for
  C3, C4, T2, L1). FACT: `sbt -batch` is not valid with this machine's sbt wrapper; use `sbt -Dsbt.supershell=false`.

## Next steps, in order

0. DONE 2026-10-12 about 18:00 UTC (claude): the C1 fix round for the 6 Codex (review bot) P2 findings on PR #10, all in
   the carried Layout/Props/Paint code and all REAL by reading: (F1) `wrap` splits a long word by chars, not cells
   (Layout.scala:114); (F2) flex-shrink `ceil` over-cuts (3 one-cell items in 2 cells render blank; :399); (F3) table
   relayout compares `tableCols == ctx.cols`, a tautology (:703); (F4) `rowReverse`/`columnReverse` lose the reverse
   flag (Props.scala:303); (F5) a percentage height resolves against the screen, not the parent (Layout.scala:264);
   (F6) a constant `RangeInput` value is read from the scene only (Paint.scala:393). Fixes AND regression tests are
   SAVED as `.work/impl/c1-codexfix.patch` (the full diff, 4 files + the new RelayoutTest.scala; `git apply` it in
   the C1 worktree) and as the script `.work/impl/c1-codexfix.py` (per fix: tests | code). The worktree is clean
   (the stop hook asked for no uncommitted changes; unverified code is not pushed to a PR branch). NOT compiled yet: W2's agents
   own sbt (one sbt on the machine). When W2 ends: (1) negative control: a temp worktree at 66d9299 with ONLY the
   tests (`codexfix.py <wt> all tests`), run `core/testOnly tui4s.GoldenScreenTest tui4s.RelayoutTest` -> 6 failures,
   log `.work/impl/c1-fix-negative-control.log`; (2) in the C1 worktree: `sbt fmt`, `SBT_TPOLECAT_CI=1 sbt check`,
   fix compile errors; (3) ONE commit per finding (test + fix; P-6), commit messages name the Codex thread; (4) push
   `feat/kyoui-01-render`, reply one line per Codex thread and resolve; update the PR body (P-6 and P-7 rows);
   (5) merge `feat/kyoui-01-render` into `feat/kyoui-02-regions` (plan 4.3), `sbt check` there, push. Paint.scala
   may conflict with C2's `discard` change (different hunks, should merge clean).

1. DONE 2026-10-12 (PR #10 open, CI pending at the time of writing). **W1 = C1 `feat/kyoui-01-render`** (plan 6.1; shape 6; about 1.8-2.1 M): Geometry, Props, Layout, Paint carried from
   `.work/proto/kyoui-runner/src` into `modules/core` with the P-7 commit map; build changes RC-16 with sbt-tpolecat
   instead of `-Werror` (D-8 changed); kyo-test replaces munit in core; the surface guard (tasty-query); golden screen
   tests; AGENTS.md and README lines; PR template. Branch from `origin/main`. ASK before the push: the session's
   designated branch is `chore/cleanup-7p2rll`; the plan names `feat/kyoui-*` (plan 4.2 and 4.4).
2. DONE 2026-10-12 17:20 UTC (claude): W2 = workflow wf_4ed01b5c-abd (2.62 M tokens, 3 h 03 min, 9 agents, 466 tool
   calls). C2 tip 6b72f3b (23 commits: 16 + 7 review fixes; 27 findings + 3 extras, all confirmed, 30 fixed, 4 not fixed
   with reasons); then the merge of C1's round 2 = 5537217, check green (c2-merge-check.log); pushed; PR #11 opened against feat/kyoui-01-render. ROUND 2 on PR #11 (Codex, 3 findings): the torn screen read
   fixed (2aa65a0: the backend's monitor in Canvas.flush/resize and Headless.screen/lines; Knobs.flushSeam; test);
   AGENTS.md reworded (79ac684: a frame is a batch); 'separate frames for independent writes' and 'concurrent
   resize callers' NOT built (replies on the threads; the threads stay open for the user). Tip 79ac684, check green
   (c2-fix2-check.log)
   (see the chat). GATE 1 questions: the chat report of 2026-10-12 evening and DECISIONS tenth round. Was: 9 agents (I, R1-R3, K1-K3, F, V), script
   tui4s-w2-c2-regions-wf_4ed01b5c-abd.js in the session workflows dir (shape 8 + a final-runs agent V: 3 core test runs,
   one under taskset contention, the glitch suite with and without the scheduler values). Branch feat/kyoui-02-regions from
   feat/kyoui-01-render 66d9299, worktree .work/worktrees/feat-kyoui-02-regions. D-14 (a) and D-15 (a) TAKEN as RECs by
   the 3.1 rule (gates.md: 'REC (a) stays'); the user can object. If this session is lost: read the journal.jsonl in the
   workflow transcript dir before a resume. **C2 (W2, shape 8)** with the gate 0 outcomes: R1 option (b) (AtomicReference last world + unmountAll in
   `Sync.ensure`), armkey (D-16), the own watcher loop with the repair tick (K29 option 2; TODO issue #8), the settle
   conditions of D-17 (c) (public scheduler probe, tui4s scheduler values in `TuiApp` and the test JVM, re-check after
   settle, `awaitScreen`; TODO issue #9).
3. DONE 2026-10-13: gate 1 closed (DECISIONS eleventh round); W3 = C3 `feat/kyoui-03-input` DONE (PR #13, tip
   a6c4598 after round 2); W5 = T1 `feat/kyoui-04-terminal` DONE (PR #14, tip 0893800). Then **W5 = T1 `feat/kyoui-04-terminal`** (plan 6.5; the
   first usable version), then **W4 = C4 `feat/kyoui-05-components`** (plan 6.4), then T2, P1, L1, L2, D. The WIP limit
   P-11 is lifted until T1 is open (the user wants a usable version first).
   UPDATE 2026-10-13 21:30 UTC: C4 DONE (PR #15, 9047a50); W6 = T2 RUNNING (wf_cbfa3587-619); then P1, L1, L2, D.
   UPDATE 2026-10-14 12:30 UTC: T2 DONE (PR #16, 4f174ed). LEAN MODE from here (DECISIONS 14). The user merges and runs the demo; P1, L1, L2, D wait for the user's word.
4. **Merge mechanics (D-4 c):** the user squash-merges; Claude then rebases the next branch of the stack (own branches
   only), runs `sbt check`, pushes. Never rewrite a branch that is not Claude's.
5. **Tracking:** one GitHub issue per TODO (labels `upstream-kyo`, `todo`). The user creates the GitHub Project with
   the "auto-add" workflow. The wiki is for the Mac session (no credentials from the cloud).

## Open items for the user (none blocking)

- sbt-tpolecat: confirm the mode (ASSUMED: dev mode by default; `SBT_TPOLECAT_CI` in GitHub Actions).
- Search getkyo/kyo for duplicates of the two drafts in `design/upstream/kyo-signal-issues.md`; post them (user only);
  link them in issues #8 and #9.
- Create the GitHub Project (auto-add workflow). Say whether the wiki is enabled (a private repo needs GitHub Pro).
- (done 2026-10-13) PR 5 closed by Claude with the plan 4.1 comment. PRs #10 and #11 accepted by the user's "go for
  your solution" (2026-10-13); the merges are the user's (squash, then Claude rebases the next branch, D-4 c).
- The repo rule on the model of subagents: branch `chore/claude-rules` (one commit from main) is pushed; the user opens
  its PR or asks Claude to.
- Publishing: deferred until the user brings it up (do not ask).

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

MODEL OF SUBAGENTS (user rule, 2026-10-12): subagents and workflow agents run on Opus (`model: 'opus'` in
agent() and Agent calls) unless the task needs Fable's deep reasoning (a design or plan panel, a gate review, the
adversarial check of concurrency or runtime code, a root-cause hunt with no known cause). Implementers, checklist
reviewers, carries, searches and log reading: Opus. W2 (wf_4ed01b5c-abd) was launched before the rule and runs
all its agents on Fable; the C1 fix round and C3 onward apply it. Recorded in the repo's CLAUDE.md (local branch
`chore/claude-rules` from main; the push waits for the user: the designated branch needs a force push) and in
this container's ~/.claude/CLAUDE.md.
NAMES (user rule, 2026-10-13): say "CatsFx" for Cats Effect in the chat and in the notes; the library keeps its
own name in code, docs and PR text that its users read.
<= 4 agents in parallel, <= 2 when building; one workflow at a time; announce runs > 10 agents; agents save
notes early (`.work/impl/`); keep durable copies in `.work/`; never deploy docs; release only with approval;
never type credentials into anything.
