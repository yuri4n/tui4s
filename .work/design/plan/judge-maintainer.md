<!-- claude 2026-10-03: judge "maintainer / reviewer" lens. Planning only: no build, no checkout, no commit, no push, no GitHub write. -->
# Judge verdict: maintainer / reviewer lens

Status: DONE (2026-10-03).

Lens: Is each PR reviewable, green on its own, with a clear purpose? Are the history and the GitHub state (open PRs,
branches) clean and easy to understand? Do the plans keep the AGENTS.md rules (versioning, compat, release) and the
user constraints (agents, builds, no history rewrite, no merge or release without the user)? Do they keep test and CI
coverage (Linux `terminal` job, ptycheck, bench)?

Drafts:

- R = `.work/design/plan/draft-reviewable.md` (15 small PRs, one linear stack).
- K = `.work/design/plan/draft-risk.md` (7 spikes and gates first, then 9 PRs).

## 1. Result

| Draft | Score (0-10) | One line |
| --- | --- | --- |
| R (reviewable) | 8 | Best PR shape, best GitHub hygiene. It misses CI-stability facts, and it has the terminal PR without a CI terminal gate. |
| K (risk) | 6.5 | Good CI facts and early terminal and bench coverage. Its two first PRs are about 4000+ lines each, and it has errors about GitHub retargets and the carry method. |

WINNER: R. Use R as the base of the final plan, and add the grafts in section 4.

## 2. Facts I checked (read only)

| Fact | Evidence | Effect |
| --- | --- | --- |
| `delete_branch_on_merge` is `false`. Squash and rebase merges are also allowed. | `gh api repos/yuri4n/tui4s` (`allow_squash_merge: true`, `allow_rebase_merge: true`) | R is correct (PD-5). K section 7 says "GitHub moves a stacked PR to the new base when its base branch is merged and deleted". That does not occur by itself today. |
| `main` has no branch protection and no required checks. | `gh api repos/yuri4n/tui4s/branches/main/protection` -> 404 "Branch not protected" | Neither draft says this. "Green before merge" depends only on the user. |
| CI runs on every `pull_request` with no branch filter. So stacked PRs get CI. | `origin/chore/cleanup:.github/workflows/ci.yml` | Both drafts can say "green in CI" for each stacked PR. |
| The `terminal` CI job exists only on the PR 5 head, not on the base. | `ci.yml` at `origin/feat/core-3-terminal` vs `origin/chore/cleanup` | The new stack has no real-terminal CI until a PR adds the job again. R adds it in T2 (PR 6). K adds it in P-b (PR 2). |
| The build has no fatal-warning flag. `-Wunused:all`, `-Wvalue-discard` and `-Wnonunit-statement` are warnings only. | `origin/chore/cleanup:build.sbt` `scalacOptions` | K F-3 is correct. R treats "strict flags" as a gate (K-1, P-7), but a green `sbt check` does not prove them. |
| No `concurrentRestrictions`. The test JVMs of all aggregated projects run at the same time. | `origin/chore/cleanup:build.sbt` | K F-5 is correct. R does not cover it. |
| The `bench` project is in `root.aggregate` on PR 5, so `sbt check` compiles it. | `git diff origin/chore/cleanup..origin/feat/core-3-terminal -- build.sbt` | R removes bench compile coverage until PR 13 (P1). K brings it back in PR 3 (P-c). |
| 8 bot review threads are unresolved on PRs 3-5, not 3. | `gh api repos/yuri4n/tui4s/pulls/{3,4,5}/ccr/review_threads`: PR 3 `Keymap.scala:159`, `TuiTest.scala:166`; PR 4 `Trace.scala:186`, `Session.scala:150`, `LiveTest.scala:121`; PR 5 `CountingBackend.scala:33`, `CounterApp.scala:8`, `Crossterm.scala:103` | Both drafts place the 3 threads on carried code (correct, OS section 0). Neither gives a disposition for the other 5 in the close comments. |
| PR 2 adds the AGENTS.md rule "When a public API changes, change its page in the same pull request." | `git diff origin/chore/cleanup..origin/docs/docus -- AGENTS.md` | R PD-4(a) (merge PR 2 early) plus PD-19 (one docs PR at the end) breaks this rule in every code PR from C1 to P1. K keeps PR 2 open until the end, so the rule does not bind the code PRs. |
| The docs workflow runs only on `docs/**`. Vercel skips a build when `docs/` did not change (`ignoreCommand: git diff --quiet HEAD^ HEAD -- .`). | `origin/docs/docus:.github/workflows/docs.yml`, `docs/vercel.json` | Both drafts take docs code from `examples/` by file include. A change in `examples/` then does not run the docs build. Neither draft covers this. |
| `.work/` is in `.gitignore` (line 42). | `git show origin/chore/cleanup:.gitignore`; `git check-ignore -v` | K 5.1 says "Carried files come with `git checkout <old ref> -- <path>`". For the prototype on `wip/handoff` that stages ignored `.work/proto/...` files into the PR branch, and the paths and packages change. R uses `git show <ref>:<path> > <new path>`, which is correct. |
| `GuardTest.scala` (PR 5 head) tests the old `NotForeign` on `Source.task`. | `git show origin/feat/core-3-terminal:modules/core/src/test/scala/tui4s/GuardTest.scala` | R section 9 item 2 is correct (drop). K carries RC-27 whole in P-b, so it carries a test that cannot compile. |
| The prototype runner on `origin/wip/handoff` is byte-identical to the local `.work/proto` (13 of 13 files). | `git show ... \| cmp` | R delta guides can cite git refs. |
| The PR 5 code has no `claude <date>` marks; the runner prototype has 11. | `git grep 'claude 2026' origin/feat/core-3-terminal -- modules examples` (0); `grep` in `proto/kyoui-runner/src` | K F-12 is correct. R does not say to remove them. |
| CI runs take about 3 minutes (PR 5 head, both jobs). | `gh api repos/yuri4n/tui4s/actions/runs` | CI cost of merge-ups through a long stack is low. Not a risk. |
| No PR template in the repo. | `git ls-tree origin/chore/cleanup .github` | R PR-body rules (P-7, P-8, P-12) have no template to enforce them. |

## 3. Scores per draft

### 3.1 R (draft-reviewable): 8 of 10

Strengths:

- Each PR has one purpose, written as "what works after the merge" (P-1). Sizes aim at about 1500 hand-written main
  lines (P-2). The largest PR (T1) is mostly carried code with a delta guide.
- Strong PR discipline: commit map (carry, format, flag fixes, one commit per behaviour change, P-7); a delta guide
  with exact `git diff <ref>:<path> HEAD:<path>` commands (P-8); "Not in this PR" (P-12); fix with the code, in its own
  commit, with a regression test (P-6); never port code that a later PR replaces (P-5).
- Correct GitHub facts: `delete_branch_on_merge: false` and PD-5; merge commits and why squash breaks the stack (PD-3,
  K-13); two branches that the inventories missed (`chore/cleanup-7p2rll`, `first-take`).
- Non-destructive moves: close PRs 3-5 (option A), keep the old branches as delta-guide sources, review fixes go up by
  `git merge` (P-9). Only the user closes, merges or deletes.
- A WIP limit (P-11: at most 2 PRs ahead of the user's review) and 3 review gates bound the ripple of an API change.
- Every inventory item has a place (section 4), including the 3 bot threads on carried code and the 14 ZIO findings.
- Smaller binary surface: `RecordingControl` and `ScriptedInput` move to test sources (section 9 item 8).
- Versioning is correct: all `compat.sbt` stay `None` (verified); `tastyQuery` goes to `project/Versions.scala`
  because three projects use it; `codegen` is outside the `ModuleVersioning` paths; no release.
- User constraints: at most 4 agents, at most 2 builds, worktrees for a second sbt, one workflow at a time, the one run
  of 14 agents (W9) is marked "announce".

Defects:

1. T1 brings the real-terminal code, but the CI `terminal` job comes one PR later (T2). T1 is "green" with a local PTY
   log only. The reviewer of the terminal PR cannot see a CI real-terminal result.
2. Gate 1 (after C2) reviews the public run API, but quit as a value (`Headless.start(...)(quit => ui)`, R5) lands in
   C3. That changes the `Headless.start` signature after the gate, against the plan's own rule P-4.
3. PD-4(a) (merge PR 2 early) and PD-19 (one docs PR at the end) are both RECOMMENDED. Together they break the PR 2
   AGENTS.md rule "When a public API changes, change its page in the same pull request" in each code PR. Section 9
   item 5 sees the rule but the recommendations do not resolve it. An early merge can also start a Vercel deploy.
4. No warning policy. Warnings do not fail the build (verified), so "strict flags" (K-1, P-7) is not a gate.
5. No test-concurrency setting. The timing-sensitive suites (C2-C4, T1, F1-F4) run in parallel forked JVMs on a
   private-repo CI runner. "3 local runs alone" does not model CI. This threatens "green on its own".
6. Bench coverage: the bench project comes back only in P1 (PR 13). PR 5 had it in `root.aggregate`. For 12 PRs there
   is no bench and no bench compile check while the runner changes.
7. 15 PRs: high review load for one user (K-17), up to 14 base edits if PD-5 is not taken, and a long merge-up chain
   for a low fix. P-11 and the gates reduce it; they do not remove it.
8. Close comments list 3 bot threads. GitHub shows 8 unresolved threads. The other 5 need one line each.
9. W9 puts F3 and F4 in one workflow with 2 builds on two dependent branches: an F3 fix must merge up into F4 during
   the run. It is announced, but it is the one place where the plan leaves its own P-11 rhythm.

### 3.2 K (draft-risk): 6.5 of 10

Strengths:

- New, correct facts for "green on its own": F-3 (no fatal warnings, D-7 policy) and F-5 (no `concurrentRestrictions`,
  D-8 `Tags.limit(Tags.Test, 1)`; acceptance "one run under the 2x2 contention").
- F-7: the terminal PR (P-b) carries the PTY kit, the launchers, the Counter and the CI `terminal` job. So the real
  terminal has a CI gate in the same PR. The bench comes in P-c (PR 3). Coverage of `terminal`, ptycheck and bench
  returns early.
- PR 2 stays open until the end and takes the stack by a merge commit (no rewrite). The docs rule does not bind the
  code PRs, and there is no early deploy.
- Spikes are local only (`.work/spikes/`, a local worktree, a local branch that is never pushed). The GitHub state stays
  clean. The spikes leave guards in CI (section 9 table).
- F-6 is correct: a mirror needs its hand-written runtime, so a mirror cannot land in a PR without it.
- Fewer PRs (9), so fewer retargets and fewer merge-ups.
- The commit order of P-a (5.3) separates move, format and logic.

Defects:

1. P-a (about 4400 lines, about 2700 main) and P-b (about 4200) have several purposes each and the same size as the
   old PR 3 (+4871) and PR 5 (+5041). One sitting is not possible. The split (D-35) is optional, and P-b has no split.
2. Merge order (section 7) relies on GitHub retargets after a merge and delete. `delete_branch_on_merge` is `false`, so
   that does not occur by itself. No decision for the setting.
3. The carry method `git checkout <old ref> -- <path>` is wrong for the prototype files (new path and package; and the
   source path `.work/` is ignored, so the command stages ignored files into the PR branch).
4. P-b carries RC-27 whole, with `GuardTest` (old `NotForeign` test, verified). It also keeps `ScriptedInput` and
   `RecordingControl` in the public test kit (larger binary surface).
5. Spike schedule (4.3): agents C and D both "start when A or B ends". If only A ends, B (sbt) plus C and D
   (scala-cli) build at the same time: 3 builds, more than the user limit of 2.
6. No WIP limit and no review gate inside the 9 PRs. "The next PR's workflow can start while the user reviews the PR
   before it" does not say how far ahead the work can go.
7. P-gen ships a generator with an empty `mirrorsCheck` (no targets). The reviewer does not see real generated output
   until P-zio.
8. No per-PR delta guide or "Not in this PR" rule. Only P-a has a commit order.
9. Close comments list 3 bot threads, not 8 (same as R).

## 4. Grafts for the final plan (on top of R)

1. Real-terminal CI in the terminal PR (K F-7): T1 carries the PTY kit (`vt.py`, `check.py`), the `launchers` task, a
   Counter fixture app, and the CI `terminal` job with the Counter rows that need no `TuiApp` (keys, `q`, `stty`
   restored, a pipe gives `NotATerminal`, native-access message, WINCH). T2 adds the `TuiApp` rows (Ctrl-C exit 0,
   `kill -TERM` 143, `kill -INT` 130, early-`println` output probe) and the other apps.
2. Quit in its final shape before gate 1: C2 lands `Headless.start(...)(quit => ui)` (a test calls `quit`; no keys
   needed). C3 adds only the key path and `Tui.quit`. Or move gate 1 after C3. Either way P-4 stays true.
3. PR 2 (docs): take K's method as the recommendation. Keep PR 2 open; at D, merge the stack tip into `docs/docus` by a
   merge commit and write the content in new commits. If the user merges PR 2 early, the fix commit must change the
   AGENTS.md docs rule to "until the first release, PR D brings the pages", so no code PR breaks a project rule.
4. Warning policy before C1 (K F-3, D-7). Recommended: fatal warnings for hand-written sources in CI (`-Werror`, or
   `-Wconf:any:e` with a `-Wconf` silence for `src/*/generated` and the inlined-Kyo `@nowarn` noise). Each PR body
   gives "0 warnings". Without this, "strict flags" is not a gate.
5. Test concurrency in C2 (K F-5, D-8): `Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)` when the first
   timing-sensitive suites land. Acceptance adds one local run under CPU contention (2 CPUs, `taskset -c 0,1`) as a
   model of the private-repo CI runner.
6. Bench earlier: move P1 (bench project in `root.aggregate`, JMH `Layout`/`Paint`, probes, `BASELINE.md`) to right
   after T2, before F1 (as K P-c). L1 and L2 then add the "Todo grow" row and re-record their numbers. The trace can
   stay in P1 or go with T1.
7. Close comments on PRs 3-5 list all 8 unresolved bot threads, each with its place: 3 fixed in T1; `Keymap.scala:159`,
   `TuiTest.scala:166`, `LiveTest.scala:121` obsolete (code dropped); `Trace.scala:186` obsolete (new trace schema; a
   note for the day mouse support comes: keep the modifiers); `CounterApp.scala:8` becomes a T2 acceptance item (the
   example Scaladoc names the launchers, not `sbt runMain`).
8. Settle canary from K F-2: the C2 canary tests every probe call that the settle uses (`busyWorkers()` is new in RC7),
   not only the private field. K F-1 (public `Scheduler.status()`) is an option for C2 that can remove the reflection.
9. K F-12: the format commit of C1-C4 removes the 11 `claude <date>` marks of the prototype, so the carried code
   matches PRs 3-5 (0 marks).
10. K section 9 (guards that stay) as a checklist in the final plan, so each guard has an owner PR.
11. K deferred row: the macOS Terminal / iTerm2 look check by a human goes into the release checklist (HANDOFF open
    items), not into a PR.
12. Fix R W9: run F3 and F4 in two workflows (6 + 8 agents, no announcement) unless the user wants speed; that keeps
    P-11.

## 5. Gaps that neither draft covers

1. `main` has no branch protection and no required checks (verified). Ask the user: add a ruleset that requires the
   `sbt` and `terminal` checks before a merge (TO VERIFY: a private repo may need a paid GitHub plan for this). Also
   ask the user to turn off squash and rebase merges for the time of the stack (both are on today), so a wrong button
   cannot break the stack.
2. New sbt projects must join `root.aggregate`: `codegen` (and `bench`), or `check` must name `codegen/test`. Without
   that, `sbt check` does not run the generator tests. `publish / skip` keeps `versionPolicyCheck` green (precedent:
   `examples`, and `bench` on PR 5).
3. The docs take code from `examples/` by file include, but `docs.yml` runs only on `docs/**` and Vercel skips a build
   when `docs/` did not change. Add `examples/**` to the `docs.yml` paths in D (or PR 2), and check that the Vercel
   project can read files outside `docs/` (TO VERIFY in the Vercel settings; the user does this, no deploy).
4. A PR template (`.github/pull_request_template.md`) with the R sections (purpose, delta guide, commit map, "Not in
   this PR", acceptance, real-terminal check). It makes 15 PR bodies the same shape. It can land in C1.
5. The disposition of the 5 bot threads on dropped code (section 4, graft 7).
6. A stated owner for each GitHub write that the plans need (close PRs 3-5, edit a PR base, change a repo setting,
   delete a branch): the user, or Claude after an explicit yes in the chat. R says this for the close only.

## 6. Reading log

- Read DECISIONS.md, AGENTS.md, both drafts in full, the inventories (sections on PR split, RC-27, FC 5), the runner
  report "What carries into PRs 3-5", HANDOFF "Merge order".
- Checked with git and `gh api` (read only): branches and heads, base tree, `build.sbt`, `Versions.scala`, the three
  `compat.sbt` (all `None`), `ci.yml` on base and PR 5, PR 5 `build.sbt` diff, PR 2 AGENTS.md diff, `docs.yml`,
  `vercel.json`, `.gitignore`, repo merge settings, branch protection, unresolved review threads, CI run times,
  `GuardTest`, prototype sizes and long lines, prototype identity on `wip/handoff`, code marks.
