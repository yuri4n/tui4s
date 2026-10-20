---
title: "0016. Process: lean mode and agent roles"
description: "The session steers and agents do the work in a small shape (implementer, one reviewer, fixer); a skeptic reviews only concurrency and terminal-restore code; the working notes are not part of the product."
---

# 0016. Process: lean mode and agent roles

## Status

Accepted

## Date

2026-10-14 (lean mode), 2026-10-17 (D14), 2026-10-19 (D19, D20), 2026-10-20 (the notes leave the repository).

## Context

- tui4s was built by an agent session for one maintainer. The session runs agents in workflows on a cloud machine with 4 CPUs (at most 2 agents at once) or on the user's Mac.
- The first workflows had 6 to 9 agents (an implementer, three reviewers, three skeptics, a fixer, a final-runs agent) and cost about 1.3 to 2.6 M tokens each.
- On 2026-10-14 the user asked to "use [the session model] very sparsely and cut corners a little bit (not in the api, behind the scenes)". On 2026-10-17: "No tengo tantos créditos". On 2026-10-19: "go cheaper".
- The skills `functional-design` and `scala-functional-design` were copied into `.claude/skills` as "temporary" (D15 kept them). By 2026-10-19 the repository copy was stale by about 4,600 lines.
- The notes (`.work/`, git-ignored) live on the branch `wip/handoff` (D-32): decisions, plans, spike reports, upstream drafts.

## Decision

- Lean mode (D14): the session steers only. Every task that can be delegated runs as an agent on a cheaper model, also the reviews.
- Workflow shape 4: an implementer, one reviewer, a fixer. A skeptic (an adversarial reviewer) only for concurrency or terminal-restore code. A single-agent phase still runs the full check and the PTY run when terminal code changed (D20).
- Not cut: the public API in its final shape, Scaladoc, the seal of `tui4s.internal`, 0 warnings, green CI.
- Parallel streams use one git worktree each (L5: docs and bench).
- Changed text carries a mark `// claude <date>: created|changed (<phase>)`. For code that a phase rewrites, the history moves to the commit text (D2 a).
- AGENTS.md and the docs use ASD-STE100 (Simplified Technical English).
- The skills left the repository (D19). The source of truth is <https://github.com/yuri4n/functional-design-skills>.
- The notes are not part of the product. The ADRs keep the decisions; then `wip/handoff` is deleted.

## Alternatives considered

- **Full review panels** (shape 6 or 8 with three skeptics). Used for C1 to T2. Dropped for cost; the skeptic seat stays only where a bug is hard to see (concurrency, terminal restore).
- **The skills in the repository as project skills** (D15, 2026-10-17). Reversed by D19: the copy went stale by about 4,600 lines.
- **The notes kept as a wiki.** The user asked for "GitHub Projects and the wiki as much as you can" (2026-10-11). The cloud session had no wiki credentials, and a private repository wiki needs a paid plan. The ADRs replace the notes.
- **The notes kept on `wip/handoff` forever.** Rejected on 2026-10-20 under the user's instruction to clean up the branches, the docs and the stale content.

## Consequences

- Good: each phase costs a fraction of a full panel, and the checks that protect users stay.
- Good: the ADRs give a reader the "why" without the agent notes.
- Bad: one reviewer can miss what three reviewers and their skeptics find.
- Bad: the code holds about 1,800 `// claude <date>` marks in 280 files. They are history, not documentation; a cleanup decision is open (see CLEANUP).
- Bad: the PR template still names the plan's P-1 to P-14 rules and a notes path.

## References

- Decision log: eleventh round (agent roles), fourteenth round (lean mode), fifteenth round (D14, D15), sixteenth round (D18, D19, D20). Plan: D-32.
- AGENTS.md: the ASD-STE100 note at the top, "Toolchain" (the skills item).
