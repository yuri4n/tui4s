---
title: "0006. Purity: edge files, lint and a pure painter"
description: "The functional rewrite (L0, phases P1 to P6) keeps impure code in nine edge files and a few marked KEEP sites, checked by scalafix DisableSyntax, with a pure painter, a synchronous release stack and one fiber gauge."
---

# 0006. Purity: edge files, lint and a pure painter

## Status

Accepted

## Date

2026-10-17 (D1, D2, the purity plan); landed on 2026-10-19 (L0 done).

## Context

- The user asked for a cost-effective functional rewrite, with the skills `functional-design` and `scala-functional-design` as the rules: impure code only for performance, and only behind an effect.
- The audit of 2026-10-17 found 93 impure sites. "Whiles everywhere" was not correct (about 12 real loops, most in edge code). The shape was the problem: about 23 volatile or nullable fields, four copies of "run every step, keep the first error", exceptions for expected outcomes, a painter that wrote into the jatatui buffer while it decided, and `java.util.concurrent` where Kyo has primitives.
- Classification: 29 sites stay behind one seam each, 2 kernels stay, about 50 are replaced and 13 are deleted.
- A JVM shutdown hook must restore the terminal. Kyo RC7 `Finalizer.close` only spawns a fiber to drain a `Scope`, so a hook cannot run `Scope` finalizers.
- L1 (scroll, overlays) changes the painter, and on a pure painter these features are cheaper and testable with properties. So L0 came before L1 (D1).

## Decision

- Impure code is only in an edge file or on a line with `// scalafix:ok DisableSyntax.<id>; KEEP: <reason>`. An edge file wraps one foreign API: line 1 `// scalafix:off DisableSyntax`, line 2 `// EDGE: <API>`. There are 9 edge files, all in `tui4s.internal`, and 15 KEEP sites.
- scalafix DisableSyntax checks the main sources in `sbt check`; `scripts/edge-check.sh` checks the headers and the comments in CI.
- The painter is pure: `Paint` gives a `Plan` (ops and damaged row spans). `Canvas.scala` has the only two loops over cells, `Canvas.write` and `Canvas.diff`, each with a law and a property test (P5). The old painter stays in the tests as the oracle.
- The release of a run is one synchronous stack (`ReleaseStack`, P3): each acquire pushes its release; `runAll` runs them once, last first; a failing step does not stop the next. One shutdown hook per run.
- The live fibers of a run are one Kyo `AtomicRef` gauge (`FiberGauge`, P2); start and add are one bracket.
- An enum, not a Boolean, selects runtime behaviour. Property tests use kyo-test-prop with explicit `Gen` values. `TuiError` is `NoStackTrace` with a non-null cause (A4).

## Alternatives considered

- **Stay with the imperative runner.** The user rejected the shape of the code as "badly written", and L1 needed a testable painter.
- **A Kyo `Scope` for the terminal release.** Not possible from a JVM hook: `Finalizer.close` only spawns a drain fiber (Kyo `Scope.scala:346-383`).
- **A purity script, a ratchet and TODO headers.** Cut by the skill review: scalafix DisableSyntax and one CI grep are enough.
- **Move the edge files into a package `internal.edge`.** Cut by the skill review (nine `git mv` for no behaviour).
- **ScalaCheck.** Not used: kyo-test-prop exists for RC7.
- **P7, a typed data model.** Out of the rewrite (D2 c). Two casts hold by construction.
- **A third kernel over cells.** Forbidden: a new write is a new `Op`.

## Consequences

- Good: the branch shrank by 2,149 lines net (151 files, +6,843/-8,992). Suppressions went 105, 49, 47, 38, 29, 19, then 15 KEEP sites.
- Good: the restore order is one list, and `ReleaseStackTest` checks it with 100 seeded rounds and a negative control.
- Good: the oracles and property tests pin real fixes: the clip counts cells, not UTF-16 chars (`ClipTest`), and an incremental frame equals a full frame (`StepPropertyTest`).
- Bad: the edge files and KEEP sites need review by hand; the lint cannot judge them.
- Bad: `Component.initial` is pure, so a per-mount effect value arrives through `onMount`.

## References

- Roadmap: 2.4, D1, D2. Skill review amendments A1 to A11.
- Handoff: P0 to P6 and "L0 DONE 2026-10-19". Decision log: D20, D23, D24 (open).
- AGENTS.md: "Purity", "Rules for code" (the release stack, the fiber gauge).
