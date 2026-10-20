---
title: "0021. The rulebook is CONTRIBUTING.md"
description: "CONTRIBUTING.md is the rulebook for humans and for AI agents; ARCHITECTURE.md tells how the runner works; AGENTS.md is short, holds the rules for agents only, and imports the two other files."
---

<!-- claude 2026-10-20: created: ADR 0021 (the rulebook is CONTRIBUTING.md) -->

## Status

Accepted.

## Date

2026-10-20.

## Context

- `AGENTS.md` held 873 lines (821 lines that are not blank). It mixed two kinds of text: the rules (the toolchain, the commands, the rules for code, the purity rules, the docs, the versions and the release process) and the narrative of how the runner works (the modules, the keys, the components, the layers, the viewports and the text flow).
- GitHub shows `CONTRIBUTING.md` to a first contributor: the page of a new issue and of a new pull request links to it. `CONTRIBUTING.md` was only a short form that sent the reader to `AGENTS.md`.
- Agent tools read `AGENTS.md`. Claude Code reads `CLAUDE.md`, which holds one line, `@AGENTS.md`, and it follows such `@` imports.

## Decision

- Three files at the root of the repository.
- `CONTRIBUTING.md` is the rulebook, for humans and for AI agents. It starts with a quick path: set up, check a change, write docs, the release notes, the marks, the bench, report a problem. Each part links to its full section. The full sections follow. They moved from `AGENTS.md` with the same text and the same marks: "Toolchain", "Daily commands", "UI architecture and API design", "Rules for code", "Purity", "Docs", "Benchmarks", "Dependencies and versions", "Branch and tag rules", "Compatibility and versioning" (it was "Compatibility") and "Releasing".
- "Compatibility and versioning" adds two procedures: how to choose the next version of a module, and the pull request after a release.
- `ARCHITECTURE.md` holds the section "What tui4s is" of `AGENTS.md`, with the same text: the module table and how the runner works. A change of behaviour changes it in the same pull request.
- `AGENTS.md` is short (under 60 lines). It tells an agent to read the two other files, and it imports them with the lines `@CONTRIBUTING.md` and `@ARCHITECTURE.md`. It holds the rules for agents only: the marks, no terminal UI in the shell of an agent, one sbt process for each build root, the human gate of a release, no force push, no model name, ASD-STE100, the file to update for a change of behaviour or of process, and the skills.
- The import chain: `CLAUDE.md` (`@AGENTS.md`), then `AGENTS.md` (`@CONTRIBUTING.md`, `@ARCHITECTURE.md`). Claude Code loads the three files. Another agent reads `AGENTS.md`, and its first sentence names the two other files.
- No sentence is lost. A script compared each line of the old `AGENTS.md` that is not blank with the three files. Only the old title, the old intro, the old title "Compatibility" and the docs row of the module table (with its mark) changed on purpose.
- The page "Contributing" of the docs site is the quick path, with links to the three files on GitHub. It does not copy the rules.
- A reference that names a rule of today names `CONTRIBUTING.md` or `ARCHITECTURE.md`. A reference in an older record or in a mark that tells the past keeps `AGENTS.md`.

## Alternatives considered

- **One file, `CONTRIBUTING.md`, with everything** (option a). Rejected: a human who fixes a typo scrolls past the architecture.
- **`AGENTS.md` as the source and `CONTRIBUTING.md` as the short form** (option c, the state before this record). Rejected: the rulebook was invisible to humans on GitHub.
- **The docs site as the only rulebook** (option d). Rejected: an agent needs the rules in the checkout, also offline.

## Consequences

- Good: GitHub shows the rulebook to a first contributor, and the quick path comes first.
- Good: the narrative of the runner has its own file, so a change of behaviour has one clear place to document.
- Good: an agent gets the same rules as a human through the import chain, plus the rules for agents only.
- Bad: three files instead of one. A change must find the right file; `AGENTS.md` tells which file a change of behaviour or of process updates.
- Bad: an agent tool that does not follow `@` imports reads only the short `AGENTS.md`. It must read the two files that the first sentence names.
- Bad: the older records keep `AGENTS.md` where they tell the past, so a reader can see both names.

## References

- `CONTRIBUTING.md`, `ARCHITECTURE.md`, `AGENTS.md`, `CLAUDE.md`.
- [ADR 0012](/decisions/0012-documentation-is-example-first), [ADR 0016](/decisions/0016-process-lean-mode-and-agent-roles), [ADR 0020](/decisions/0020-release-from-the-actions-form).
