---
title: "0018. pini4s is a vendored Git submodule"
description: "The optional keyed shared-state library pini4s lives in vendor/pini4s as a Git submodule and an sbt ProjectRef; only the examples use it."
---

<!-- claude 2026-10-20: created: ADR 0018 in the docs site (L6) -->

## Status

Accepted. The move to a published artifact is PROPOSED (see below).

## Date

2026-10-16 (the user's commit 3c31de8 adds the submodule); 2026-10-19 (D22: pini4s is public).

## Context

- pini4s ("Pinia-inspired shared state for Scala and Kyo") is a small library of the same author. It keeps named `SignalRef` values in an explicit application registry, with an optional `useState("key")(initial)` helper. It depends on `kyo-core` only and has no UI module.
- CONTRIBUTING.md: "Local state and controlled components need no store library." Components own their state by default; pini4s adds explicit, application-owned sharing by key. <!-- claude 2026-10-20: changed: CONTRIBUTING.md, not AGENTS.md (ADR 0021) -->
- The tui4s build reads it as `ProjectRef(file("vendor/pini4s"), "root")`. Only the `examples` project depends on it, and only one suite uses it (`examples/src/test/scala/examples/widgets/StateStoreSuite.scala`).
- pini4s has no published artifact: its build sets `version := "0.1.0-SNAPSHOT"` and `publish / skip := true`.
- While `yuri4n/pini4s` was private, the CI checkout of the submodule failed, so CI was red on `main` from 2026-10-16. After the user made pini4s public, CI run #43 (2026-10-19) was green; D22 calls it "the first green run since 10-14".
- The tui4s CI also runs `sbt check` and the packaged demo of pini4s.

## Decision

- pini4s stays a Git submodule in `vendor/pini4s`, read by the build as a source project.
- A new checkout uses `git clone --recurse-submodules`; an existing one runs `git submodule update --init --recursive`.
- No published module depends on pini4s. Component events, bindings, widgets and renderer code do not go into it.
- When pini4s changes, run `sbt check` in `vendor/pini4s` and the relevant tui4s checks.

## Alternatives considered

- **A Maven dependency.** Not possible today: pini4s publishes no artifact.
- **Copy the sources into tui4s.** Not recorded.
- **Drop pini4s from tui4s.** Not recorded. The user added it with the native Kyo state patterns (3c31de8).
- The notes do not record why the user chose a submodule. PROPOSAL (the obvious reason, not confirmed): pini4s is unpublished, so a source dependency is the only way to build against it, and a submodule pins one commit.

## Consequences

- Good: the examples show optional keyed sharing next to native Kyo state, at a pinned commit.
- Bad: every clone needs the submodule step, and CI depends on the pini4s repository being public.
- Bad: the tui4s CI spends time on the pini4s checks and demo.

PROPOSAL (2026-10-20, now that pini4s is public): when pini4s publishes `io.github.yuri4n::pini4s`, replace the submodule and the `ProjectRef` with a `Test`-scope dependency of `examples`, and remove the pini4s steps from `ci.yml`. Until then, keep the submodule.

## References

- Decision log: D22. Handoff: "CI #43 on 95bcf2a GREEN".
- Commit 3c31de8 ("Add native Kyo state patterns and reproducible developer tooling").
- CONTRIBUTING.md: "UI architecture and API design" (the pini4s items). <!-- claude 2026-10-20: changed: CONTRIBUTING.md, not AGENTS.md (ADR 0021) -->
