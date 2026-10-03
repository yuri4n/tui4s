<!-- claude 2026-10-03: created this file (completeness critic of PLAN-kyoui.md; planning only: no build, no checkout, no commit) -->
# Notes: completeness critic of PLAN-kyoui.md

Status: DONE (2026-10-03).
Task: find what is missing or wrong in `.work/design/PLAN-kyoui.md`; fix small gaps in place; append
"## Critic pass (2026-10-03)".

## Progress
- read DECISIONS.md, notes-final.md, PLAN sections 1-4.4.
- read PLAN sections 5-10, the upstream draft, IR (works / does not work / must change / gaps / questions), FR
  (findings, open items, questions, carries, skeptic, fixes), RR (findings, upstream, carries, questions, skeptic).
- CHECK ids: all OS-1..50, RC-1..36, FC-1..38 have a row in PLAN section 7 (script). Inventory decision ids (OS D1-D12,
  RC D1-D12, FC-D1..D11) map to plan D-n, but the plan has no map table -> add 7.9.
- CHECK facts (spot, 2026-10-03, read only): branch heads (11 refs) OK; compat.sbt all None OK; .gitignore:42 `.work/`
  OK; build.sbt flags/no Werror/no concurrentRestrictions/aggregate OK; gh api settings (private, auto-delete false,
  squash+rebase on), main protection 404, rulesets [] OK; open PR bases OK; kyo `Signal.scala:101-107`,
  `defaultRepairInterval` 1 s, kyo-ui `UI.scala:499` HttpUrl, `UI.scala:771-788` Any < Async, `ReactiveUI.scala:337,341`
  `.unit` OK; proto `Mount.scala:117` (key = path + class name), `:128,147` initUnscoped, `Paint.scala:117,224-227`,
  `Runner.scala:145-146` (stderr under `SchedulerProbe.debug`), `Dispatch.scala:143-146`, apps `initRef` lines OK;
  carried PR 5 file sizes OK; feat/zio, feat/cats file sizes OK; gen 781 OK; ZioUi.scala:8 imports Bridge OK;
  SchedulerDefaults:22-27 OK; docs.yml paths, vercel ignoreCommand, PR 2 docs rule OK; CI runs on every pull_request OK.
- GAPS so far:
  1. origin/main = "Initial commit" 79b9d9c only: C1 base `main` needs PR 1 merged (D-6 a) -> state in table + 4.3.
  2. FC-D2 (b) leaves Kyo data types reachable: a partial exception to DECISIONS round 5; not flagged -> flag it.
  3. Upstream draft cites "3 to 131 split frames" = desktop-only, UNVERIFIED (RR skeptic refuted 10) -> fix draft.
  4. RR skeptic refuted 1, 3-6, 8, 10 not placed in 7.6 -> add rows.
  5. RR open question 1 also asks "open the upstream discussion?" -> A2.
  6. toolchain line "one sbt process per worktree" is weaker than AGENTS.md "one sbt process in this repo" -> fix.
  7. T1 `captureOutput` default true and T2 opt-out not stated (DECISIONS round 4) -> add.
  8. Vercel PREVIEW deploys on pushes to docs/docus at D (Git link) not named -> add to D / K16.
  9. IR "Gaps for the real modules" not a row -> add to 7.7.
  10. P-7 says 11 claude marks: 11 in src/ + 4 in test/Apps.test.scala -> precise.
  11. No durability step: spike reports, gates.md and impl notes live only in git-ignored `.work/` of an ephemeral
      container (precedent: `.work/` pushed to `wip/handoff`, commit 1d100ed; RR verifier logs only in the scratchpad)
      -> new D-32 + owner row in 4.4.
  12. 4.4 has no owner for pushes to `docs/docus` (D step 1 and 3; not a branch "this plan creates") -> add row.
  13. T1 carries `focusEvents` while RC-14 row defers terminal focus events (D-30): a dead option -> drop it in T1.
  14. A1-A4 confirmation time not stated (A4 shapes S5, A3 shapes S7) -> confirm with 3.1, before W0.
  15. 6.15 W14 says "0 sbt" but D acceptance runs `sbt check` -> 1.
  16. Gate 3 names L2; if D-30 drops L1/L2 it is F4 -> say so.
  17. Legend says K-n/S-n/G-n; tables use K1/S1/G1; PR F1-F4 clash with IR F1-F3 / FR F1-F8 -> legend note.
  18. P-14 does not repeat "never set version/publishTo" for codegen/bench -> add.
  19. agent prompts do not name commit/PR attribution lines -> add to toolchain line.
  20. F3 "`KyoSignallingRef` 111": 111 = whole proto `cats/src/Bridge.scala`; the class starts at line 62 -> precise.
- Facts verified extra: 8 unresolved threads (ccr route), Versions.scala, zio/cats build literals, PR 3 -Wconf line 32,
  JDK 25 default, 4 CPUs, 56 effect points, 17 leak lines, Scheduler RC5->RC7 diff 194 lines, busyWorkers RC7:442.
- Interpretation to flag (not edit): "<= 2 when building" read as "at most 2 BUILDING agents" (W0 runs 3 agents with 2
  builders; shape 8 runs 3 reviewers while 1 builds). HANDOFF W4 precedent "at most 4 at once, 1 build". User confirms.
- NEXT: apply edits E1-E20 to PLAN-kyoui.md and the draft fix; append "## Critic pass (2026-10-03)".
- applied the edits to PLAN-kyoui.md (legend, C1 base, 3.1 A1-A4 timing, D-5 hint, D-32, D-25 FC-D2/FC-D3 notes, 4.3,
  4.4 two rows, 5.4 gate 3, P-7, P-14, toolchain line, T1 focusEvents/captureOutput/tests, T2 opt-out, F3 evidence,
  6.14 previews, 6.15 W14, 7.6 rows, 7.7 rows, 7.9 new, K16) and to upstream-kyo-ui-issue.md (hop-count claim).
- extra finding while editing: PLAN-kyoui.md, plan/* and the upstream draft are NOT on wip/handoff (git ls-tree) -> D-32.
- appended "## Critic pass (2026-10-03)" to PLAN-kyoui.md. Tables re-checked: 0 bad rows. DONE.
