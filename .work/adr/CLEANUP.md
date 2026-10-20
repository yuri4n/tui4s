# Cleanup before the public release

Read-only survey of 2026-10-20. Repository `/home/user/tui4s` at e41fd0f (branch `chore/cleanup-7p2rll`). Branch tips read with `git fetch origin <branch>` and the GitHub REST API. Nothing was changed.

## 1. Branches of github.com/yuri4n/tui4s

Keep: `main` (cd6a877, an ancestor of the work branch) and `chore/cleanup-7p2rll` (e41fd0f).

"In target" means: the branch tip is an ancestor of `chore/cleanup-7p2rll`, or its content reached `main` by a squash.

| Branch | Tip (now) | In target? | What it holds | Worth keeping before the delete |
|---|---|---|---|---|
| `archive/kyoui-02-regions-2026-10-14` | 0646343 | Content yes (squash #11 on main) | The old tip of C2 before the stack was re-parented | Nothing |
| `archive/kyoui-03-input-2026-10-14` | a6c4598 | Content yes (squash #13) | The old tip of C3 (round 2) | Nothing |
| `archive/kyoui-04-terminal-2026-10-14` | b912d3b | Content yes (squash #14) | The old tip of T1 (round 2) | Nothing |
| `archive/kyoui-05-components-2026-10-14` | 9047a50 | Content yes (squash #15) | The old tip of C4 | Nothing |
| `archive/kyoui-06-app-examples-2026-10-14` | ade774f | Content yes: its tree equals main 2d5354d (0 differing files) | The old tip of T2 | Nothing |
| `chore/claude-rules` | be0d305 | No (1 commit) | `CLAUDE.md` with the rule on the model of subagents | Nothing for the product: the rule lives in the user's own `~/.claude/CLAUDE.md` (ADR 0016) |
| `chore/cleanup` | adbe3a6 | Yes (ancestor) | PR #1 (build, CI, release automation) | Nothing |
| `claude/epic-bohr-eflkfz` (open PR #7) | d8b9bc8 | No (2 commits) | `.github/rulesets/` (3 JSON files, a README), 18 lines of AGENTS.md | YES, in part: `release-tags.json` (blocks deletion and move of a release tag). It lists only `core-v*`, `zio-v*`, `cats-v*`: update it to `runner-v*`, `app-v*`, `testkit-v*`, `core-v*`, `use-v*`, `widgets-v*`. `main-invariants.json` requires a PR, squash merges and the check "Format, compile, test, compatibility" (the job is now named "Format, compile, test, compatibility, packaging"). The user removed every ruleset on 2026-10-14 because the review rule blocked the author. Proposal: fold an updated `release-tags.json` into the cleanup, then close PR #7 with a comment that names the commit |
| `docs/docus` (PR #2, closed) | 8f485c2 | No (1 commit) | The Nuxt Docus scaffold, `docs.yml`, a 15k-line `pnpm-lock.yaml`; text for the old DSL that claims ZIO and Cats Effect modules | L5 copies only `docs/` from it. Delete after the L5 docs land on the work branch |
| `feat/cats` | 9f4cc05 | No (45 commits) | The Cats Effect facade on the OLD DSL (`Source[S]`, `Next[S]`, `tui4s.interop.cats`), not on kyo-ui: `CatsBridge` (the vendored kyo-cats bridge adapted to RC7), `AmbientDispatcher`, `LiveTuiTest`, suites | YES, as a reference only: name the sha 9f4cc05 in the Cats Effect issue (PLANNED-FEATURES item 2). GitHub keeps an unreferenced commit reachable by its sha for an unknown time: copy `internal/CatsBridge.scala` and its suite into the issue or a gist if they matter |
| `feat/zio` | 0c8c00d | No (39 commits) | The ZIO facade on the OLD DSL (`tui4s.interop.zio`): `AmbientZIOs` (the F12 workaround), `ZioRunner`, `LiveTuiTest`, ZIO examples, a parity suite | YES, as a reference only: name 0c8c00d in the ZIO issue (item 1); copy `internal/AmbientZIOs.scala` if it matters |
| `feat/core-1-model-view` | 850a83c | No (10 commits) | PR #3 (closed): the old Elm-style model and view | Nothing (replaced by kyo-ui, ADR 0001) |
| `feat/core-2-engine` | 5a441a5 | No (16 commits) | PR #4 (closed): the old engine and live test kit | Nothing |
| `feat/core-3-terminal` | 666a8e3 | No (34 commits) | PR #5 (closed): the old terminal, examples, PTY kit, and `bench/` (IdleProbe, LatencyProbe, BurstProbe, QuitProbe, `BASELINE.md`) | The terminal and PTY kit were carried into T1 and T2. The old `bench/` probes were not carried; the L5 bench stream can read them before the delete (they use the old DSL) |
| `feat/kyo-state-nix-tooling` | 3c31de8 | Yes (ancestor; merged by 0f72ae2) | The user's native Kyo state patterns, Nix flake, pini4s submodule | Nothing |
| `first-take` | 05599dd | Yes (ancestor) | The user's own Flutter-style first take | Nothing is lost. BUT the notes say "`first-take` is the user's own branch: do not touch". Ask the user before the delete |
| `wip/handoff` | 988183e (not 1367f99: it moved on 2026-10-20) | No (76 commits) | The working notes `.work/` | Delete LAST, after the ADRs and the GitHub issues of PLANNED-FEATURES exist. Before the delete, save anything that a planned issue still cites (PLANNED-FEATURES already copies the upstream drafts and the input-race reproducer) |

Local hygiene (not a remote action): the local clone still has remote-tracking refs for `feat/kyoui-01-render` to `feat/kyoui-06-app-examples`, which no longer exist on GitHub. `git fetch --prune` removes them.

## 2. Stale content in the repository at e41fd0f

### 2.1 The empty zio and cats modules (ADR 0014: remove before 0.1)

- `modules/zio/` and `modules/cats/`: only `build.sbt` and `compat.sbt`, no source.
- `build.sbt`: header lines 10-11; `aggregate` lines 180-181; lines 213-215 (`lazy val zio`, `lazy val cats`); the comment at line 366 ("The zio and cats modules are empty in this version"); the `versions` alias (line 475) shows `zio/version` and `cats/version`.
- `scripts/release.sh`: lines 7, 8, 11 and the default `MODULES` (line 26).
- `.github/workflows/release.yml`: lines 3, 13, 25, 41, 42 (`zio-v*`, `cats-v*`, the tag regex).
- `AGENTS.md`: the table rows 27-28; lines 41-43 ("The `zio` and `cats` modules depend on the runner. They will let ..."); line 640 ("the zio and cats coverage tests"); line 663; line 706 (release order); line 721 (tag patterns of `maven-central`).
- `README.md`: lines 9-10 ("Integrations for ZIO and Cats Effect are planned"); the module table rows 1694-1695.
- `project/Versions.scala`: comments on `kyo` ("kyo-ui and kyo-zio use it too"), `munit` ("zio and cats tests"; munit is now used by the examples), and `tastyQuery` ("The zio and cats coverage tests").
- `scripts/version-order-test.sh` and `scripts/edge-check.sh`: no mention (checked).
- The docs of L5: check the copied Docus pages for ZIO and Cats Effect claims (the `docs/docus` branch has them in `index.md`, `installation.md`, `versions.md`, `app.config.ts`, `nuxt.config.ts`, `package.json`).

### 2.2 The deprecated useSignal (ADR 0014: remove before 0.1)

- `modules/runner/src/main/scala/tui4s/Hooks.scala:40-56` (the Scaladoc example "not useSignal(0)" and the method).
- `modules/core/src/test/scala/tui4s/HooksTest.scala:17-24` (`deprecatedUseSignal`) and the leaf at line 51.
- `modules/core/src/test/scala/tui4s/NameClashTest.scala:42-43` (a comment).
- `AGENTS.md:73` ("the old `useSignal` only renames it and is deprecated") and the mark at line 97.
- `README.md:30` and `README.md:72` (marks that name it).
- `examples/README.md:11`: the Counter row says "one hook (`useSignal`)". This is wrong today: the Counter uses `Signal.initRef`.
- `examples/src/main/scala/examples/counter/Counter.scala:5-10`: the history comment names `useSignal`.
- Check `PublicApiTest` and MiMa: the removal is a binary change of `tui4s-runner` (unreleased, so `Compatibility.None` already holds).

### 2.3 TODO, FIXME, planned, deprecated (tracked files, outside `vendor/`, `.work/`, `target/`, `node_modules/`)

- `modules/runner/src/main/scala/tui4s/internal/Mount.scala:48`, `Runner.scala:88` (`TODO(#8)`), `Runner.scala:248`, `Runner.scala:277` (`TODO(#9)`): KEEP. They name open issues #8 and #9.
- `examples/ptycheck/run.sh:24` (`XXXXXX` in `mktemp`) and `examples/src/main/scala/examples/todo/Todo.scala:303` (`TODO_FILE`): false matches.
- All other matches are the zio, cats and useSignal items above.

### 2.4 Other stale or internal content

- `experiments/ui-state/**` (35 files): the user's own API experiments (commit 3c31de8 by the user, 2026-10-16): Frame, store-env, generic-effects, grouped-scope, grouped-var. README.md "Contributing" links its README; AGENTS.md does not. CI does not build them, and they need the pini4s submodule. One fixture is a source file in package `kyo` (`experiments/ui-state/frame/src/main/scala/kyo/framespike/FrameApis.scala`, marked "isolated diagnostic experiment"), which a reader can mistake for a breach of ADR 0004. Proposal: keep (it is the evidence of AGENTS.md "UI architecture and API design"), state in its README that CI does not build it; the user decides.
- `.envrc` (`use flake`): keep. README "Development setup" documents direnv.
- `.vscode/settings.json` (`files.watcherExclude` for `target`, Metals X-ray inlay hints): a personal editor setting. Proposal: drop it, or keep only the watcher exclusion. The user decides.
- `.jvmopts` (`-Xss16m`): keep (Kyo needs the deep stack).
- `CLAUDE.md` (`@AGENTS.md`): keep; harmless, and it helps agent users.
- `.github/pull_request_template.md`: one section per plan rule P-1 to P-14, and line 1 names `.work/design/PLAN-kyoui.md`. Stale for outside contributors. Proposal: a short template (what changes, the tests, the checks of AGENTS.md).
- Test comments that cite the notes: `modules/core/src/test/scala/tui4s/HeadlessTest.scala:114` (`.work/impl/`), `modules/core/src/test/scala/tui4s/stress/ObserveLeakProbe.scala:7` (`.work/spikes/...`), `modules/runner/src/test/scala/tui4s/KyoUiSurface.scala:32` (`.work/spikes/s3-drift/...`). Proposal: cite the ADR or the issue instead.
- `.gitignore:46-47` (`.work/`) and `.gitignore:49-51` (`.claude/skills/`): harmless; keep or drop with the notes.
- The marks `// claude <date>: created|changed (...)`: about 1,800 in 280 tracked files (count by `git grep`). They are history, not documentation. Options: keep them (AGENTS.md uses the same marks), or strip them in one formatting commit before 0.1 and keep the history in git. The user decides (ADR 0016).
- `README.md:12-17` (the "Status" block): "under construction, first usable version ... Nothing is published yet". Update it at the release.
- AGENTS.md and README.md cite note and phase ids (D-17 c, D25, RR N9, OS-34, spike S1, K29, A5, P1b, L2a, ...): about 72 lines of AGENTS.md and 21 of README.md (most in the `claude` marks). With the ADRs in the docs, the rules that cite an id need a pointer to the ADR (the ADR index explains the ids).
- `modules/assistant/shared/src/main/scala/examples/assistant/Settings.scala`: the default model ids are data that nobody checked (PLANNED-FEATURES item 20).

## 3. Files that a public repository should have

| File | Exists? | Note |
|---|---|---|
| `LICENSE` | Yes | Apache-2.0 |
| `README.md` | Yes | Has a "Contributing" section that points to AGENTS.md |
| `AGENTS.md` | Yes | The rule book for humans and agents |
| `.github/pull_request_template.md` | Yes | Stale (see 2.4) |
| `SECURITY.md` | No | Needed: the assistant handles API keys (env, a 0600 file, browser `localStorage`). Say how to report a problem privately |
| `CHANGELOG.md` | No | Needed for 0.1.0-RC1 (planned in L6). One section per module tag, or one file per module |
| `CONTRIBUTING.md` | No | README "Contributing" plus AGENTS.md cover it. Proposal: a short file that points to AGENTS.md, the toolchain (JDK 25, Nix), `sbt check` and the PTY kit |
| `CODE_OF_CONDUCT.md` | No | Optional |
| `.github/ISSUE_TEMPLATE/` | No | Proposal: a bug report (JDK, OS, terminal, `TuiOptions.logFile` output) and a feature request |
| `.github/CODEOWNERS`, `dependabot.yml` | No | Optional |
| `NOTICE` | No | Not recorded as needed. The vendored kyo-cats bridge (Apache-2.0, with attribution) was planned for the cats module, which leaves before 0.1 |
