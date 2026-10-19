# tui4s rewrite — decisions (2026-09-30)

User answers (AskUserQuestion, 2026-09-30):
- Versioning: INDEPENDENT per-module versions. Tags like core-v0.2.0, zio-v0.1.0, cats-v0.1.0.
  One command / GitHub Action publishes only one module. Guard: block releasing zio/cats
  against an unreleased (snapshot) core.
- Maven groupId: io.github.yuri4n. POM developer email: oss.juli@pm.me (user, 2026-10-01).
- Core design: a design panel decides (Elm-style, component+state, reactive signals, the
  user's Flutter-style Widget/Element/State first take). Winner written up as a design doc
  in the core PR.
- Docs: Nuxt Docus, hosted on VERCEL (config only; do not deploy without asking).

Standing constraints (user AGENTS.md):
- <= 4 agents in parallel; <= 2 when compiling/building/testing/installing.
- One workflow at a time. Announce runs > 10 agents first.
- Agents save reports early and incrementally. Durable copies live in .work/ (git-excluded).
- Toolchain is NOT on the default PATH: `source .work/env.sh` (JDK 21, sbt 1.11.7, node 24, pnpm 10).

Second round (AskUserQuestion, 2026-09-30, after research):
- Backend library: jatatui 0.31.0 (com.olvind.jatatui: jatatui-core, jatatui-widgets, jatatui-crossterm,
  crossterm). It is the maintained successor of tui-scala (same repo, renamed). Do NOT depend on
  com.olvind.tui (same tui.crossterm package -> classpath clash). jatatui-react is prior art only.
- JDK 25 + Kyo 1.0.0-RC7 + Scala 3.9.0 (LTS). Install Temurin 25 via SDKMAN; do not change the
  user's default JDK. Consumers of tui4s need JDK 25.
- License: Apache-2.0. tui4s-cats vendors Kyo's discontinued Apache-2.0 Cats bridge (kyo-cats RC5)
  with attribution, adapted to RC7.

Third round (2026-10-01):
- POM developer email: oss.juli@pm.me.
- Publishing is deferred: no Maven Central setup, no release tags, repo stays private for now. Do not ask again until the user brings it up.

Fourth round (2026-10-01, design panel open questions):
- Facade packages: tui4s.interop.zio / tui4s.interop.cats.
- Output capture (System.out/err) on by default, opt-out.
- TuiApp sets Kyo scheduler flags when unset, opt-out -Dtui4s.schedulerDefaults=false.
- Defaults accepted: kyo-test for core, munit elsewhere; bench project with sbt-jmh; testkit inside core.
- Releases fully automated after the tag push (sonaRelease); maven-central env accepts only release tags.

Fifth round (2026-10-02, after reviewing the core):
- tui4s becomes a terminal BACKEND for kyo-ui (UI -> jatatui). Our own View/Keymap/Engine model is dropped.
- Both component styles: hooks (`for count <- useSignal(0) yield ...`, useSignal = Signal.initRef) and the type-member `Component` class (runner allocates state at mount).
- ZIO/Cats users must NOT see Kyo types: facades = a generated mirror of the kyo-ui DSL with ZIO / F[_] types at every effect point (handlers, signals, streams, components), building the same kyo-ui AST. Facade users import only the mirror (avoids the Kyo lift trap: kyo-ui handlers are `Any < Async`, a ZIO value would compile and never run).

Sixth round (2026-10-09, after the kyo-compat review; recorded by Claude):
- Order: the Kyo library (core) comes first. The ZIO module comes after it. The Cats module comes last.
- The ZIO bindings are written by hand ("manual conversions"). There is no code generator. This replaces
  "a generated mirror" in the fifth round. The goal of the fifth round stays: ZIO and Cats users see only
  types of their own ecosystem.
- Context: Kyo removed all its Typelevel modules (getkyo/kyo#1778, PR #1779, 2026-07-27). Only its JMH
  benches still use cats-effect. The Cats bridge of tui4s stays vendored (second round). Prior art for one
  artifact per backend with native types: ghostdogpr/sage and gruggiero/typesafe4s (kyo-compat, with a
  vendored Cats Effect backend).
- ZIO API: a hand-written ZIO DSL. ZIO users import only it. A coverage test fails when kyo-ui has a setter
  that the DSL does not have. (Not call-site conversions, not a hybrid.)
- C3 (input) sends all bound writes through one runner function, so that the ZIO state designs Z2 and Z3 stay open.
- The ZIO state design (Z1 own ref type, Z2 SubscriptionRef primary + redirected writes, Z3 SubscriptionRef
  primary + one-way copy + controlled inputs) is picked by a spike (SZ) at the start of the ZIO phase.
- The ZIO phase starts after D merges (all Kyo work done).
- Plan: PLAN-kyoui.md section 0 ("Revision 1") has the changes.

<!-- claude 2026-10-09: added the seventh round -->
Seventh round (2026-10-09, after the State-monad question; recorded by Claude):
- The user asked to explore "the UI as State monads (`ZIO[State[...`) that change the buffer". It was never prototyped
  (the closest was `proto/mut`: State over the app model, stopped when kyo-ui came).
- Decision: option (b). kyo-ui stays the model. After W0, one spike (S8) builds a State-monad UI as a baseline, to
  show what the reactive complexity of kyo-ui buys. No GO/NO-GO: the user reads the numbers at gate 0.
- The user is "not sure" if State goes over the buffer or over the app model. S8 shows both: the buffer variant is
  new; the model variant is the old Elm core of PRs 3-5 (`update: (S, Event) => S` is `Event => State[S, Unit]`).
- Spec (parked): design/parked/S8-state-ui.md.
- Later the same day (claude 2026-10-09: changed): S8 is CANCELLED. The user said "let's not do it" and asked to keep
  it apart: the spec is in design/parked/S8-state-ui.md, not in the plan. The text below is the earlier step.
- Earlier step: S8 was CONDITIONAL. It runs only if S1 or S2 of W0 is SOFT or NO-GO
  (then the cost of the reactive model is real, and a baseline helps a decision). If both are GO, S8 does not run:
  kyo-ui stays the model either way, and the C1 painter style needs no spike. Rule of the user: the sweet spot of cost
  x benefit. The user can say "run it anyway".

<!-- claude 2026-10-11: added the eighth round (gate 0) -->
Eighth round (2026-10-11, gate 0, after W0; recorded by Claude from the user's answers in the chat):
- D-17 (half-updated frames): option (c): accept SOFT with the conditions of `design/plan/gates.md`. The user adds a
  TODO tied to the Kyo release that fixes the lost wakeup in `Signal.combineLatest`: when it ships, re-measure S1
  and remove the conditions that the fix makes unneeded. The TODO lives on GitHub (see "Tracking" below).
- D-19 (pin Kyo and kyo-ui to 1.0.0-RC7 for the first release): yes.
- K29 (`Signal.observe` leak): option (2), the runner's own watcher loop (`currentWith`/`nextWith`, no race) plus
  the repair tick, with a TODO: remove the own loop when Kyo fixes `observe`. (The user first wrote both "A but TODO
  if kyo fixes it" and "3. C"; on 2026-10-12 the user said "ignore 3c".) Plus the upstream issue (claude 2026-10-12:
  changed this item).
- Upstream issues (user): "we need issues at kyo and kyoui if they are not there yet" for the lost wakeup and the
  leak. Both bugs are in kyo-core `Signal`, and kyo-ui is a module of getkyo/kyo, so both issues go to getkyo/kyo.
  Drafts first; the user approves each post. The standing rule stays: nothing is posted without the user.
- D-4 (merge method of the stack): option (c): squash merges; Claude rebases the next branch of the stack after each
  merge. A history rewrite only on Claude's own branches; one CI re-run per rebase.
- D-8 (-Werror everywhere) is CHANGED. User: "warnings of unused and stuff are warnings, use sbt-tpolecat". The scalac
  flags come from sbt-tpolecat 0.5.7. ASSUMED (claude 2026-10-12; asked, no answer): default mode = dev (warnings
  stay warnings on a developer machine); GitHub Actions sets `SBT_TPOLECAT_CI` (fatal warnings in CI). One build
  line flips it if the user wants warnings everywhere.
- Tracking (user: "use GitHub Projects and the wiki as much as you can"): TODOs go to GitHub issues (and a Project);
  notes go to the wiki, as far as a session can reach them. From this cloud session: issues yes (GitHub MCP);
  Projects v2: no tool here (the user creates the Project with an auto-add workflow, and new issues land on it);
  wiki: no credentials here (private repo; the proxy serves only the main repo).
- The 3.1 RECs (D-3, D-5, D-9, D-10, D-12, D-13, D-16, D-18): proposed as "I take my recommendations unless you
  object". The user did not object.

## Ninth round (2026-10-12, after gate 0; claude)

<!-- claude 2026-10-12: created this section -->

- The user said "ok let's continue" to the W1 report that asked for a yes to push `feat/kyoui-01-render` and open its
  PR. Taken as the yes: the branch is pushed; PR #10 `feat(core): a kyo-ui value renders to cells` is open against
  `main`; CI green. The stack uses the plan's branch names (plan 4.2, 4.4), not the session's designated branch.
- D-14 (duration type) and D-15 (quit as a value): TAKEN as REC (a) by the 3.1 rule ("I take my recommendations unless
  you object"; gates.md "The decisions of section 3.2": "REC (a) stays", no W0 data). ASSUMED: the user has not
  answered these two by name. C2 (W2) builds on them. An objection costs a fix round on C2.
- W2 (C2) started without an explicit "go C2": the plan's order and the WIP limit P-11 (2 PRs above `main`) allow it,
  and the user's "continue" covers the plan's next step. The user can stop it.

## Tenth round (2026-10-12 evening, gate 1 is OPEN; claude)

<!-- claude 2026-10-12: created this section -->

- C1 round 2: the six Codex findings on PR #10 are real bugs of the carried layout and paint code; fixed in six commits
  (d028b2c, 49dfcf0, d0ac460, 707fd10, d46f2d0, f525808), each with a regression test and the negative control
  (the tests fail at 66d9299). Decisions taken inside the fixes, open to the user: (a) flex-shrink rounding: the
  largest fractions take the extra cuts, the LAST item first when equal (leading content survives, as with overflow);
  (b) a percentage height under an auto-height parent is `auto` (CSS), definite = the screen, an explicit height, or a
  size the flex parent imposed (`Layout.definiteContentH`); (c) reverse containers flip `justify` start/end (CSS).
- Gate 1 questions (from W2; the user decides, none taken yet):
  1. Seal `tui4s.internal` with `private[tui4s]` on its top-level types in C3 (REC of the API review), or leave it
     bytecode-public and unsealed until the first release.
  2. The glitch test: 0 split frames in 500 events, and 1 allowed only with `-Dtui4s.load=1` (the contention run).
     On a 2-CPU CI runner under load the S1 rate gives a 1-3 percent chance of 1 split per run: keep the strict 0 in
     CI, or set the flag in ci.yml?
  3. The repair tick (1 s) re-renders a Reactive region whose UI holds handlers or nested signals once per second
     (two renders never compare equal; no cells change; Kyo's own observe repair does the same). Accept until Kyo
     fixes observe (#8), or compare Reactive content another way in C3?
  4. The settle canary cancels itself on a JVM with one Kyo worker (a 2-CPU runner under load). Acceptable in CI, or
     require a second worker (a fourth scheduler value beyond D-17 c)?
  5. D-14 (a) java.time.Duration and D-15 (a) the quit value are now built and tested (TuiOptions, Headless.start):
     confirm or object (an objection is a fix round on C2).
- C2 round 2 (PR #11, Codex): (a) the kit's screen read holds the backend's monitor (a real torn read, shown with a
  widened flush: 29 of 1856 torn without the monitor, 0 with it; the natural window is microseconds). (b) NOT built,
  the user decides: separate frames for independent writes that land inside one settle (Kyo signals carry no cause,
  so a render per message brings back split frames; AGENTS.md now says a frame is a batch), and per-request replies
  for two fibers that call `resize` at once (the kit is single-driver by its Scaladoc).

## Eleventh round (2026-10-13, gate 1 CLOSED; claude)

<!-- claude 2026-10-13: created this section -->

- The user: "go for your solution and move ahead. want to see that usable version asap" (after the state report of
  2026-10-13). Taken as: gate 1 passed with Claude's recommendations; PRs #10 and #11 accepted as they stand (the two
  open Codex threads on #11 keep Claude's answers; nothing more is built for them); the WIP limit P-11 is lifted until
  the terminal PR is open (the user wants a usable version first); the T1 RECs D-21 (a) crossterm `Event` as the
  `InputPort` element, D-22 (a) inline viewport deferred, D-24 (a) Ctrl-C key = quit with exit 0 are taken.
- Gate 1 answers (Claude's solutions, as the user asked):
  1. `tui4s.internal` is sealed in C3: `private[tui4s]` on its top-level types (the REC of the C2 API review).
  2. The glitch test keeps the strict 0 as the default, and CI sets the load flag for the forked test JVM, so that CI
     allows 1 split frame in 500 events. Reason: CI is "under load", and the SOFT contract itself says at most 1 per
     10 000 under load; at that rate 500 events give about 5 percent chance of 1 split, and a false red per 20 CI runs
     costs more than the flag. A real regression shows many splits, not 1. The local 3-run acceptance stays strict.
  3. The repair tick re-renders a Reactive region whose UI holds closures once per second (no cells change, no flush):
     ACCEPTED until Kyo fixes `observe` (#8). C3 does not change it.
  4. The settle canary that cancels itself on a one-worker JVM: ACCEPTED in CI. No fourth scheduler value.
  5. D-14 (a) `java.time.Duration` and D-15 (a) quit as a value: CONFIRMED.
  6. Frame = batch (Codex P1 on #11): STANDS. 7. Single-driver kit (Codex P2 on #11): STANDS.
- Order of the next PRs (claude 2026-10-13, for "usable asap"): C3 -> T1 -> C4 -> T2 -> P1 -> L1 -> L2 -> D. T1 (the
  terminal) moves before C4 (components and hooks): the terminal Counter uses plain `Signal.initRef` and handlers
  (C3), nothing of C4, so the first usable version (a kyo-ui app in the real terminal) is one workflow earlier. C4 then
  stacks on T1. Branch names follow the new order: `feat/kyoui-03-input`, `feat/kyoui-04-terminal`,
  `feat/kyoui-05-components`, `feat/kyoui-06-app-examples`. The plan's section ids (6.3, 6.4, 6.5) do not change.
- The repo rule on the model of subagents (`CLAUDE.md` = `@AGENTS.md` + the rule): branch `chore/claude-rules` pushed
  plain, no PR (plan 4.4 covers only the stack PRs; the user opens it or asks for it).
- PR 5 (old stack, `feat/core-3-terminal`): CLOSED by Claude with the plan 4.1 comment (the user's "move ahead"
  covers the "yes" of plan 4.4).
- Model of the agents from W3 on: Opus for the implementer, the plan and API reviewer, the skeptic of that review, the
  fixer and the final-runs agent; Fable for the runtime reviewer and its skeptic (the adversarial check of handler and
  fiber code). The user repeated the rule on 2026-10-13 ("use opus subagents when fable is overkill").

## Twelfth round (2026-10-13, after W3; claude)

<!-- claude 2026-10-13: created this section -->

- W3 (C3) review R1-1: the C2 leaf `HeadlessTest` "screen is never a torn frame" is flaky (3 failures in 5 runs of the
  C2 code on this machine; the busy reader makes each settle run to its max). Decision (Claude, under "go for your
  solution"): the test-only fix (1000 writes instead of 300) goes on PR #11 as a new commit 0646343 (plan 4.3: a fix on
  the lower branch, then a merge-up), and `feat/kyoui-02-regions` is merged into C3 (f29b9ff; check green,
  `c3-merge-check.log`). No code change on C2.
- The commit trailers of the W3 agents say "Co-Authored-By: Claude Opus 5.5": the agents ran on Opus and their harness
  gave that attribution. Accurate and kept (P-9: no rewrite). The PR body says it.
- Gate 1 items in C3 as decided: the seal (6b9424d, with a tasty-query `PublicApiTest`, because Scala 3 does not
  reject a public member whose signature names a `private[tui4s]` type), the CI load flag (fe3d937; `TUI4S_LOAD=1`
  in ci.yml, forwarded to the forked test JVM).
- Found in C3, deferred: a textarea is a `TextInput` in kyo-ui RC7 and edits as one line (multi-line editing waits);
  a character outside the BMP cannot be a kyo-ui `Keyboard.Char` (one UTF-16 char): `TextEdit` inserts printable
  `Keyboard.Unknown` text, and T1's crossterm mapping must do the same.

## Thirteenth round (2026-10-13; claude)

<!-- claude 2026-10-13: created this section -->

- PARKED, not decided (the user: "esto no lo hagas ahora"): ZIO as the "compat monad": one upstream bridge Kyo to ZIO
  (kyo-zio) and CatsFx through zio-interop-cats, instead of a vendored Kyo-to-CatsFx bridge. Claude's reading: true for
  the effect layer (zero vendored interop), false for the reactive-state layer (interop-cats has no SubscriptionRef to
  SignallingRef; three runtimes and three cells for a CatsFx user; the settle sees none of the hops); CatsFx users would
  carry ZIO as a dependency; the CatsFx DSL stays hand-written either way. Options A (plan), B (hub by ZIO), C (a small
  runtime capability interface with three instances), D (measure it in spike SZ as part SZ-5). REC: D. Open
  questions for the user: which part of the CatsFx facade they want gone (the effect bridge or the DSL with the state),
  and whether ZIO is an acceptable dependency for CatsFx users. Goes to gate Z.
- Name (user, 2026-10-13): "CatsFx" for Cats Effect in the chat and the notes.

## Fourteenth round (2026-10-14; claude)

<!-- claude 2026-10-14: created this section -->

- LEAN MODE (user, 2026-10-14: "Bro I would really like to see this is action. Please use Fable very sparsely and
  cut curners a little bit (not in the api, behind the scenes)"). Decided: Fable (the session) steers only; every
  delegable task runs on Opus, also the runtime reviews (R2/K2 were on Fable in W3 to W6). Process cuts from here:
  workflow shape 4 (implementer, one reviewer, fixer; no skeptics) unless the code is concurrency or terminal-restore
  code; one final test run and one PTY run, not three; PR bodies of about one screen (P-1 to P-14 in one line each,
  the numbers that matter, no full row lists); negative controls only for a bug fix; no per-step logs. NOT cut: the
  public API (final shapes P-4, Scaladoc), the seal of tui4s.internal, 0 warnings, green CI, the merge mechanics
  (D-4 c), the no-push-without-green rule. The user's rules file (~/.claude/CLAUDE.md on the Mac) gets the same rule.
- T2 details decided by Claude (no user decision needed): the span-wrap gap of the Dashboard at 40 columns goes to
  L2; the SchedulerDefaults Scaladoc change of review R1-5 (commit 4f174ed, Scaladoc only) stays.
- The user wants to SEE the result: after T2 the next step is the user's run of the examples (`sbt examples/launchers`;
  `examples/target/bin/{counter,twocounters,counterstyles,todo,dashboard}`) on the branch feat/kyoui-06-app-examples,
  and the merges of the stack (#10, #11, #13, #14, #15, T2). P1 (trace and bench) and L1/L2 wait for the user's word.

## Fifteenth round (2026-10-17; claude)

<!-- claude 2026-10-17: created this section -->

- The next phase follows `.work/design/ROADMAP-2026-10.md` (Spanish copy: `ROADMAP-2026-10.es.md`). The user
  answered the decisions D1-D16 on 2026-10-17:
  - D5 changed by the user: the terminal app and the web app must NOT communicate ("I just want a flutter style
    paradigm of coding once and generating for multiple platforms"). The web target is a standalone Scala.js build
    from a shared module (crossProject JVM+JS, kyo-ui only); platform services are traits with one implementation
    for each platform, given through Layer. The web chat bot uses the user's own API key, kept only in the browser,
    plus the fake provider for docs demos.
  - D9 changed by the user: templates ("scalax") are intent only; no spike now ("No tengo tantos créditos").
  - D10 changed by the user: the benchmarks live in this repo, in `bench/` with their own sbt build outside the root
    build.sbt, other languages in `bench/impl/<language>/`, their own CI job. The headline of the comparison is
    compactness, expressiveness, correctness and resource management; performance is published honestly on its own
    page.
  - All other decisions: the recommendations, approved by the user ("for the rest, approve"). In short: L0 (the
    functional rewrite, phases P0-P6 of the skill-reviewed plan) before L1; the `// claude` marks of rewritten code
    move to the PR text; final public shapes A4/A5 accepted; P7 out; one flagship app (examples.assistant); providers
    = Anthropic and OpenAI API keys plus a fake provider, then Codex app-server, never the Claude Code CLI in
    published code; tui4s-widgets and tui4s-use as published modules (JVM+JS, no core dependency); no mouse for 0.1;
    both web themes from one Theme value; docs copied onto main; 0.1.0-RC1 while Kyo is RC, no empty zio/cats
    artifacts, Windows experimental; upstream Kyo issues as drafts only; lean mode stays (skeptic only for concurrency
    and terminal-restore code); the skills stay on main as project skills, and the effect-systems.md row that names
    kyo-test-runner for property tests is fixed to kyo-test-prop.
  - Still open: D10 who gives a bare-metal machine; D11 the docs domain and the Vercel token owner; D16 packaging waits
    for the startup measurements (AOT cache, native-image) of workflow wf_5ed848c4-01d.
- The user is careful with credits ("No tengo tantos créditos"): keep workflows small (shape 4), no extra spikes.

## Sixteenth round (2026-10-19): launch of the full program

The user answered the five launch questions (chat, 2026-10-19):

- D17 Documentation = usage examples. The user reviews only the usage
  examples, in the code and outside it. Every public API ships with a
  compiled example (a snippet suite, Scaladoc examples), and the docs site
  and the README lead with the example. L1, L2, L3 and L5 prompts carry this.
- D18 No pull requests. All work lands on `chore/cleanup-7p2rll`; CI runs on
  demand with `workflow_dispatch` (added 4ab4068). The user merges when they
  want to; the session never creates a PR.
- D10 (machine): the user's default AWS account, bootstrapped with alchemy
  (alchemy.run) from `bench/infra/`: a bounded instance, auto-terminate,
  results to S3. The cloud session has `AWS_*` credentials in its
  environment (never print them). Lands with L5.
- D11 (host): the session creates the Vercel project when the docs land. The
  domain stays open.
- D16 (edition): GraalVM Community in CI.
- D19 Skills: `.claude/skills` left the repo (4ab4068). Source of truth:
  <https://github.com/yuri4n/functional-design-skills> (v0.2.0); the repo
  copy was stale by about 4,600 lines. The kyo-test-prop row fix went to
  that repo's main (926cf69). In the cloud container the published skills
  are linked into `~/.claude/skills`.
- Orchestration: Fable orchestrates; Opus runs every delegable agent,
  including the reviewers. Fable itself only for a gate review or the skeptic
  on concurrency and terminal-restore code (unchanged lean-mode rule).
<!-- claude 2026-10-19: created this round -->
- D20 Cheap cadence (user, 2026-10-19: "yeah go cheaper"). L0 runs as six
  workflows, not nine: P1 (running, 3 agents) -> PF-a + P1b (1 agent:
  implement, verify, push) -> P2, P3, P5 (3 agents each; the Fable skeptic is
  the only review seat) -> P4 + P6 (1 agent, one PTY run). PF-b folds into
  the layout work of L1. A single-agent phase still does the one full check
  and the PTY run when terminal code changed; the cut is the review seat, not
  the verification.
<!-- claude 2026-10-19: created D20 -->
- D21 (2026-10-19, orchestrator): `SchedulerDefaults.defaults` stays `Seq[(String, String)]`.
  A5(a) asked for Chunk, but `tui4s.spi` is Kyo-free (D-14 a) and the object
  must load no Kyo class before the scheduler properties are set. A Kyo-free
  List would be a public change outside A5 for no behavior; not worth it.
- D22 (2026-10-19, fact): `yuri4n/pini4s` is public; CI #43 is the first green
  run since 10-14. h34s got the Apache 2.0 license (321784b on its main).
<!-- claude 2026-10-19: created D21, D22 -->
- D23 (2026-10-19, P2 skeptic): the monotonic time source of the runner stays
  `System.nanoTime` in `Sync.defer` with a KEEP comment, not Kyo `Clock`.
  Kyo RC7 `Clock.nowMonotonic` maps a negative nanoTime to 0 (allowed by the
  JDK spec), which would break the settle bounds and the pending timeout
  (reviews R1-3, R2-3). A11 ("Kyo Clock is the time capability") holds for
  every other time use.
<!-- claude 2026-10-19: created D23 -->
- D24 (2026-10-19, OPEN for the user, from the P3 skeptic): AGENTS.md rule
  OS-34 "Never join the run fiber from an interruptible wait" rests on a
  premise that Kyo RC7 no longer has: IOTask.interrupt/abandon
  (kyo-core IOTask.scala:163-191, 346-371) gives up a join on an interrupt.
  Tui.run therefore keeps a promise copy of the run result (an Unsafe block
  in the ReleaseStack edge file) only as a second guard; no test needs it.
  Option: drop the copy, use `fiber.get`, and reword OS-34. Harmless as is.
<!-- claude 2026-10-19: created D24 -->
- D25 (2026-10-19, user, TO DISCUSS LATER; tracked as a GitHub issue): split
  the artifacts in this repo into `tui4s-runner` (renderer + embedder +
  component model, `Tui.run`, `TuiOptions`, `Tui.size`, `spi`), `tui4s-app`
  (`TuiApp`), `tui4s-testkit` (`Headless`, `Screen`), and a composite
  `tui4s-core` (a POM-only artifact that depends on the three: "the tui4s
  experience"). A kyo-ui user who wants only the runner takes
  `tui4s-runner`. In-repo, not a separate repository. Not `kyo-ui-*`: that is
  upstream's namespace. Timing: before L3 and 0.1 (free now, breaking later).
  Open points: the test kit in the compile scope of the composite; zio and
  cats depend on the runner; release order (runner, app, testkit before
  core); PublicApiTest and MiMa across jars.
<!-- claude 2026-10-19: created D25 -->
