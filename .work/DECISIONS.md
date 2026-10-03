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
