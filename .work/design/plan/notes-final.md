<!-- claude 2026-10-03: created this file (final-plan agent; no build, no checkout, no commit) -->
# Notes: final plan agent

Status: IN PROGRESS (2026-10-03).
Task: write `.work/design/PLAN-kyoui.md` and `.work/design/upstream-kyo-ui-issue.md` (DRAFT, not posted).
Inputs: inventories (OS, RC, FC), drafts DR (reviewable) and DK (risk), two judge verdicts (DR wins delivery 8 vs 6.5;
DK wins maintainer/risk 8 vs 6). Start from both winners: DR for the PR shape and process, DK for the spikes and
build facts.

## Progress
- read DECISIONS.md, HANDOFF.md, draft-reviewable.md (all).
- read draft-risk.md (all), judge-delivery.md (risk lens, DK wins), judge-maintainer.md (review lens, DR wins).
- Shape decision so far: DR 15-PR shape as the base, with DK spikes (local, before C1), DK build facts (F-1..F-13),
  and all grafts. Bench earlier (after T2), terminal CI job in T1, quit in C2, PR 2 kept open to D.
- read the three inventories (OS-1..50, RC-1..36, FC-1..38). Next: runner REPORT upstream proposals + measurements
  for the issue draft; verify a few facts (gh repo settings, review threads) read only.
- verified (read only, 2026-10-03): delete_branch_on_merge=false, squash+rebase merges allowed, main not protected
  (404), repo private; 8 OPEN bot threads (PR3 Keymap:159, TuiTest:166; PR4 Trace:186, Session:150, LiveTest:121;
  PR5 CountingBackend:33, CounterApp:8, Crossterm:103); all compat.sbt None; no tags; .work/ in .gitignore:42;
  docs.yml paths docs/** only; vercel ignoreCommand diff of docs/ only; prototype commit 1d100ed on wip/handoff.
  TuiTest:166 lesson (paste when paste mode off) and Keymap:159 lesson (canonical key events) still carry.
  gh search of getkyo/kyo issues is NOT possible from this session (repo-bound): the user checks duplicates.
- final shape: W0 spikes (6 agents, <=3 at once, <=2 build) -> C1 C2 C3 C4 T1 T2 P1 F1 F2 F3 F4 L1 L2 -> PR 2 (D,
  rules folded in). Gates: G0 after spikes, gate 1 after C2 (quit lands in C2), gate 2 after F1, gate 3 final.
- R2 reproducer: a delay seam inside the watcher callback f (Kyo observe: read current -> f -> nextWith) widens the
  window; red without the verify pass, green with it.
- NEXT: write PLAN-kyoui.md, then upstream-kyo-ui-issue.md.
- wrote PLAN sections 1-5 (summary, assumptions, decisions D-1..D-31, PRs/branches/merge/owners, spikes/gates/guards).
- NEXT: section 6 (per PR), 7 (traceability), 8 (risks), 9 (facts), 10 (grafts), then the issue draft.
- wrote section 6 (per PR, rules P-1..P-14, shapes, tokens: ~108 agents, ~29-35 M). NEXT: 7 traceability.
- wrote section 7 (traceability, all OS/RC/FC ids + R/F/O/N/Q, threads, ZIO, Cats, HANDOFF). NEXT: 8 risks, 9 facts, 10 grafts.
- wrote sections 8-10 and the progress log; wrote `.work/design/upstream-kyo-ui-issue.md` (DRAFT: NOT POSTED).
- checks: every OS/RC/FC id has a row in section 7 (script); all Markdown tables have consistent columns; fixed the
  D-8 -> D-26 reference; renamed W0 agents C/E (no clash with PR ids C1/C2).
Status: DONE (2026-10-03).
