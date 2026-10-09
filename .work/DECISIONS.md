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
- Spec: PLAN-kyoui.md section 0.6.
- Later the same day (claude 2026-10-09: added): S8 is CONDITIONAL. It runs only if S1 or S2 of W0 is SOFT or NO-GO
  (then the cost of the reactive model is real, and a baseline helps a decision). If both are GO, S8 does not run:
  kyo-ui stays the model either way, and the C1 painter style needs no spike. Rule of the user: the sweet spot of cost
  x benefit. The user can say "run it anyway".
