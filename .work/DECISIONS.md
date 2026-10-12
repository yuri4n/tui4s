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
