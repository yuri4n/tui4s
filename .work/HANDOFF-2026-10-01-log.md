# Handoff — where to start (written 2026-09-30; updated 2026-10-01)

## Progress log (newest first)
- 2026-10-01 ~10:30: DESIGN PANEL DONE (9 agents, 3.5M tokens, 2.5 h). Winner "free" (three pure functions
  + one runtime) 7.53 > elm 7.35 > signals 6.95 > components 5.73; 16 grafts. SPEC: .work/design/SPEC.md
  (sec 8 = PR plan C1..C7, Z1..Z3, K1..K3, docs). Validator: core + ZIO + Cats skeleton compile, all tests
  pass; skeleton in .work/scratch/design-validate/ (core/ zio/ cats/ samples/). Log: .work/design/VALIDATION.md.
  Open questions Q1-Q6 in SPEC sec 9 -> asked the user Q1-Q3; Q4-Q6 take the defaults unless the user objects.
  User answered Q1-Q3: packages tui4s.interop.zio / tui4s.interop.cats (SPEC renamed), capture on, flags on.
  IMPLEMENTATION PLAN (told the user: ~40 agents, ~12M tokens, 5 workflows, one at a time):
    W1 core-1 model+view (C1+C2) -> W2 core-2 sources/engine/loop (C3+C4) -> W3 core-3 terminal/entry/
    examples/checks/bench (C5-C7) -> W4 zio + cats in parallel worktrees -> W5 docs guide pages.
  Reusable workflow script (args: key,title,branch,base,worktree,scope,extra):
    ~/.claude/projects/-Users-yuri4n-Developer-projects-tui4s/5cb543b2-f521-4487-ae5b-1fedd18ac66c/workflows/scripts/tui4s-impl-pr-wf_6df1d964-e03.js
  W1 DONE: PR 3 https://github.com/yuri4n/tui4s/pull/3 (base chore/cleanup), 113 tests, 15/21 findings fixed.
  W2 DONE: PR 4 https://github.com/yuri4n/tui4s/pull/4 (base feat/core-1-model-view), 196 tests x3 runs, 21/25 fixed.
  W3 DONE: PR 5 https://github.com/yuri4n/tui4s/pull/5 (base feat/core-2-engine). 19/22 fixed. I added
    `sbt examples/launchers` (sbt run loses keys: sbt reads the keyboard too; -batch -> stdin pipe) and
    examples/ptycheck/run.sh (expect + mini VT emulator, 47/47 pass on macOS) + CI job `terminal` (Linux).
    Copies of my probe scripts: .work/scratch/pty/. Do NOT kill PID 7740 (Metals sbt server in main repo).
  W4 RUNNING: zio + cats facades, worktrees .work/worktrees/{zio,cats}, branches feat/zio + feat/cats from
    feat/core-3-terminal (both PRs will base on feat/core-3-terminal). 16 agents (told the user). Run wf_c92a2d58-bee, script ~/.claude/projects/-Users-yuri4n-Developer-projects-tui4s/5cb543b2-f521-4487-ae5b-1fedd18ac66c/workflows/scripts/tui4s-facades-wf_c92a2d58-bee.js
  (old) W1: run wf_6df1d964-e03, worktree .work/worktrees/core, branch feat/core-1-model-view (from
  chore/cleanup). Agent notes: .work/impl/core-1-*.md. After it: push, open PR (base chore/cleanup).
- 2026-10-01: Must-fix list done. Commit 49d2012 (.jvmopts -Xss16m, JDK 25 run flags, scalafmt glob fix
  `glob:**/project/*.scala` + no Scala-3 rewrites in the meta-build, removed .vscode/launch.json,
  untracked .vscode/mcp.json). `sbt versions check` green on JDK 25. Release guard re-tested in a clone
  (5 scenarios OK). Pushed; PR 1 = https://github.com/yuri4n/tui4s/pull/1 (base main), bound in the app.
  Open questions for the user are in the PR body (POM email, private repo).
  kyo-test decision deferred to the core PR (no tests in the cleanup PR).
  Idle-CPU probe done: kyo-rc7.md F13 (Kyo idle ~13% of a core; scheduler flags -> 4-8%).
  Design panel RUNNING (2026-10-01): workflow run wf_593a3108-b2b, script
  ~/.claude/projects/-Users-yuri4n-Developer-projects-tui4s--work/5cb543b2-f521-4487-ae5b-1fedd18ac66c/workflows/scripts/tui4s-design-panel-wf_593a3108-b2b.js
  Shared brief .work/design/CONTEXT.md. Outputs: .work/design/candidates/{elm,components,signals,free}.md,
  judging/{dx,perf,fit}.md, SPEC.md, VALIDATION.md, skeleton in .work/scratch/design-validate/.
  If stopped: resume with Workflow({scriptPath, resumeFromRunId: "wf_593a3108-b2b"}).
  PR 1 self-review: release.sh dry-run scenarios OK (added to PR body); CI green.
  Docs: git worktree .work/worktrees/docs on branch docs/docus (from chore/cleanup, NOT pushed):
  docs/ Docus 5.13.0 config for Vercel (no base path), landing + installation + versions + AI pages,
  .github/workflows/docs.yml (build check only), AGENTS.md "Documentation" section. Guide pages wait
  for the core API. Build logs: .work/logs/docs-{install,build}.log.
  -> committed f1dbabc, pushed, PR 2 = https://github.com/yuri4n/tui4s/pull/2 (base chore/cleanup).
  Workarounds in it: own /llms-full.txt route (nuxt-llms crashes on bold text), llms.domain from
  VERCEL_PROJECT_PRODUCTION_URL else localhost, docus.assistant.enabled=false (would bill AI Gateway).
  User 2026-10-01: email oss.juli@pm.me (commit 5c490f6 on PR 1).
  Release setup DONE by the user: env maven-central has PGP_SECRET, PGP_PASSPHRASE, SONATYPE_USERNAME,
  SONATYPE_PASSWORD; GPG key 324A392C93F3DBD99D7BFE3D1DFA8C612CFC6DA2 on keyserver.ubuntu.com. Env accepts
  only tags core-v*/zio-v*/cats-v*. Required reviewers = 422 (private repo, Pro plan) -> manual gate is
  Central -> user then chose FULLY AUTOMATED: sonaRelease + normal GitHub release (commit after d2d90fb).
  Repo stays private for now. Agents never publish/drop on Central.


Read in this order: `.work/DECISIONS.md` -> this file -> `.work/reports/*.md`.
Toolchain: `source .work/env.sh` (JDK 25, sbt, node 24, pnpm 10; forces IPv4).

## Git state
- Branch `chore/cleanup` (local only, NOT pushed, NOT compiled yet). Commits on top of `first-take`:
  - `48a6dd9` wip: snapshot of the user's first-take (Flutter-style widgets, CE prototype) — preserved history.
  - `fb1864b` chore: restructure into independently released core/zio/cats modules.
- `first-take` and `main` are untouched. No PRs are open. Nothing was pushed.
- Deleted on disk (untracked, verified byte-identical to tui_3 0.0.7 sources): the vendored `tui/` folder.

## Status
- [done] Research: `.work/reports/{kyo,tui-scala,docus,publishing}.md` (complete).
- [done] Decisions with the user (see DECISIONS.md): jatatui 0.31, JDK 25 + Kyo 1.0.0-RC7 + Scala 3.9.0,
  Apache-2.0, independent per-module versions, groupId io.github.yuri4n, design panel picks the core
  model, Docus hosted on Vercel (config only, never deploy without asking).
- [done] Temurin 25.0.4+1.1 installed via SDKMAN (user default JDK still 17).
- [partial] Verify workflow (stopped by user request before it finished):
  - `.work/reports/jatatui.md` (~99 KB) — effectively complete; section 0 has the summary.
  - `.work/reports/kyo-rc7.md` — findings F1..F12 verified; TL;DR and the event-loop / compile-cost
    sections are NOT written. Probes: `.work/scratch/kyo-rc7/` (CatsBridge.scala + 13 passing munit
    tests, zio/ZioBridge.scala with AmbientZIOs, loop.scala — loop status unknown, check loop*.log).
- [todo] Everything below.

## Must-fix before pushing chore/cleanup (found by the verify agents)
1. Kyo's inline for-comprehensions overflow the compiler stack (kyo-rc7 F1). Add a repo `.jvmopts`
   with `-Xss16m` (Kyo's own build uses -Xss10M) and bump `-Xss` in `.work/env.sh` SBT_OPTS.
2. JDK 25 startup warnings print to the terminal: add to `examples` `run / javaOptions`:
   `--enable-native-access=ALL-UNNAMED` (already there) AND `--sun-misc-unsafe-memory-access=allow` (F4).
3. `-Yexplicit-nulls` is fine with jatatui (zero `.nn` needed; jatatui.md §8) — keep it.
4. Consider kyo-test (F6: `io.getkyo::kyo-test-runner` 1.0.0-RC7 +
   `Test / testFrameworks += new TestFramework("kyo.test.runner.SbtFramework")`) for core tests
   instead of munit.
5. Then compile: `sbt versions check`. Unverified risks: `versionPolicyCheck` on modules with no tag
   yet (previous version may resolve to 0.0.0 — if it fails, skip it until the first release or set
   `versionPolicyPreviousVersions := Nil` in compat.sbt); `.sbt` files in `modules/<m>/` (verified to
   load in the probe). Fix `.vscode/launch.json` (still points to the deleted `tui4s.Demo`).
6. Push `chore/cleanup`, open PR 1 (base `main`). PR body: explain the 48a6dd9 snapshot, the
   jatatui switch, JDK 25, the release model, and the developer email in build.sbt (admin@yurian.land,
   taken from git history — ask the user to confirm it is OK in a public POM).

## Key facts for the next phases (verified)
- jatatui: Layout cache is a bounded LRU (no leak); Buffer/Cell mutable; `Buffer.diff` returns live
  cells; widget states (ListState, TableState, ScrollbarState) are mutated by render; TestBackend is
  published and safe; `Jatatui.init/runIo` must NOT be used (replaces the global uncaught-exception
  handler and adds a shutdown hook) — do Scope-based raw-mode/alt-screen acquire/release ourselves.
  Typical 120x40 frame ~2 ms / ~1.7 MiB alloc even when unchanged -> render only when dirty.
- Kyo: no `Sync.blocking`; scheduler tolerates blocking, but a JNI `read()` cannot be interrupted ->
  input fiber loops `poll(short)` then `read()`. `Stream.repeatPresent` default chunkSize 4096 buffers
  UI events -> use chunkSize 1 or a Channel. SignalRef = model + change notification (dedup).
- Cats: kyo-cats RC5 is binary-INCOMPATIBLE with RC7 (F2) -> vendor the ported bridge from
  `.work/scratch/kyo-rc7/CatsBridge.scala` with the Apache-2.0 attribution (F5). Create any CE
  `Dispatcher` with `await = false` (F10: `await = true` can lose a cancel in CE 3.7.1).
- ZIO: kyo-zio RC7 works; `ZIOs.get` ignores the caller's runtime — use the `AmbientZIOs` pattern
  (F12) so FiberRefs / executor of the caller are kept.
- Docus: docus 5.13.0 (pin exactly), Nuxt 4.5.2; scaffold `npx create-docus docs`; add
  `scala` to highlight langs; on Vercel set Root Directory = docs (llms.txt, raw md, MCP all work).
  Full drop-in file set in docus.md §8 (not build-tested).

## Next steps (planned, in order)
1. Finish the cleanup PR (above).
2. Design panel workflow (planned 9 agents, <= 4 parallel, only 1 compiling): 4 designers
   (Elm/TEA, components + local state incl. the user's Flutter-style first take at 48a6dd9,
   reactive signals like kyo-ui, free-form "simplest and most beautiful"), each with 3 sample apps
   (counter; todo with text input + list; dashboard with tabs/gauge/sparkline fed by async ticks)
   written for Kyo, ZIO and Cats users -> 3 judges (DX/beauty, performance/runtime correctness,
   Kyo/ZIO/CE fit + testability) -> synthesizer writes `.work/design/SPEC.md` -> 1 validator
   compiles the API skeleton against Kyo RC7 + jatatui 0.31 and amends the spec.
   Weights from the user's words: declarative/simple/beautiful 35%, performance 25%,
   ecosystem fit 25%, testability/agent-friendliness 15%.
3. Implement core (branch `feat/kyo-core`, base `chore/cleanup`), then zio and cats
   (`feat/zio`, `feat/cats`, both based on core; max 2 builds at once), review workflow per PR
   (find -> adversarial verify -> fix), Docus site (`docs/docus`), stacked PRs.

## Constraints (user AGENTS.md)
<= 4 agents in parallel, <= 2 when building; one workflow at a time; agents save reports early;
announce runs > 10 agents. Two long-running sbt JVMs (PIDs 14620, 50224) belong to the user
(Metals/IDE) — do not kill them.
