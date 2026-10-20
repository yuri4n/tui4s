---
title: "0003. Pin Kyo 1.0.0-RC7 and JDK 25"
description: "Kyo and kyo-ui stay at 1.0.0-RC7 for the first release, on JDK 25 and Scala 3.9.0, and a tasty-query surface guard pins the kyo-ui members that the runner reads."
---

<!-- claude 2026-10-20: created: ADR 0003 in the docs site (L6) -->

## Status

Accepted

## Date

2026-09-30 (the toolchain, second round); 2026-10-11 (the pin, D-19, gate 0).

## Context

- Kyo 1.0.0-RC7 needs JDK 25 to compile and to run: its macros load Kyo classes (class file version 69). An sbt on JDK 21 fails with `UnsupportedClassVersionError`.
- Scala 3.9.0 is the LTS line, and Kyo RC7 is built with it.
- The runner copies rules that kyo-ui keeps private: the key dispatch and the region walk ([ADR 0004](/decisions/0004-public-kyo-apis-only)). A change of these rules in a new kyo-ui version changes the behaviour without a compile error.
- Spike S3 (W0, 2026-10-09) compared RC5, RC6 and RC7. RC5 to RC6 changed a dispatch rule (`stopPropagation`, `keepBubbling`; opt-in). RC6 to RC7 changed a region rule (`walkStatic`: a bound element at the root of a `Reactive` or `Foreach` becomes its own region; a new default). 11 and 16 names changed on the guard surface; all were additions.
- By the gate words this was NO-GO for "bump freely": rules change between release candidates.
- The guard of S3 (a shape hash of the TASTy, not a raw hash: the raw hash gave 8 false alarms) found both rule changes, 27 of 27 names and 14 of 14 mutants, with 0 false alarms.

## Decision

- Kyo, kyo-ui and the Kyo modules stay at 1.0.0-RC7 for the first release (`project/Versions.scala`). D-19 (b).
- JDK 25, Scala 3.9.0 and sbt 1.13.0 are the toolchain. The change of one of them happens in all modules at once.
- A tasty-query surface guard (`KyoUiSurfaceTest`, `kyo-ui-surface.txt`) pins each kyo-ui member and rule that the runner reads. A Kyo bump is its own change, with the guard diff and `Compatibility.None`.
- The JMH jars that kyo-kernel RC7 lists in compile scope are excluded from every module (3.0 MB that no run loads).

## Alternatives considered

- **Bump Kyo freely, with the guard as the only check** (D-19 a). It was the recommendation if G3 was GO or SOFT. It lost because G3 was NO-GO: a release candidate can change a default rule.
- **Wait for Kyo 1.0 final.** Not recorded as an option. The release of tui4s is an RC while Kyo is an RC ([ADR 0014](/decisions/0014-release-per-module-tags-and-rc1)).
- **JDK 21.** Not possible: Kyo RC7 does not run on it.
- **Keep the JMH dependency.** It lost: no class of the seven jars loads in a class-load log of two apps. An upstream draft asks Kyo to fix the POM ([ADR 0019](/decisions/0019-upstream-kyo-issues-as-drafts)).

## Consequences

- Good: the copied rules match one known kyo-ui. The guard fails when a pinned name or rule changes, so a bump is a visible, reviewed step.
- Good: kyo-ui adds 7 jars and 15.0 MB, and the first frame stays under 1 s (S4: 866 to 919 ms median).
- Bad: every user of tui4s needs JDK 25. The README and the start scripts say so.
- Bad: tui4s inherits the RC7 bugs: the `Signal.observe` leak and the lost wakeup ([ADR 0005](/decisions/0005-soft-frame-contract-and-own-watcher-loop)), and the web bugs of [ADR 0019](/decisions/0019-upstream-kyo-issues-as-drafts).
- Bad: a Kyo bump changes the binary API of all modules (`project/` marks all modules unreleased).

## References

- Decision log: second round; eighth round (D-19 yes).
- Plan: D-18, D-19; gates.md G3 (S3), G4 (S4).
- Upstream draft: kyo-kernel JMH in compile scope.
- AGENTS.md: "Toolchain", "Dependencies and versions"; the rule on `private[kyo]` (RR N9).
