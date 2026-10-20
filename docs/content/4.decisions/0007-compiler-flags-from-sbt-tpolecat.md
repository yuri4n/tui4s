---
title: "0007. Compiler flags from sbt-tpolecat"
description: "sbt-tpolecat sets the scalac options; warnings stay warnings on a developer machine, and CI (SBT_TPOLECAT_CI) turns them into errors."
---

<!-- claude 2026-10-20: created: ADR 0007 in the docs site (L6) -->

## Status

Accepted

## Date

2026-10-11 (gate 0: D-8 changed by the user). The CI mode was ASSUMED on 2026-10-12 (the user did not answer) and is in `ci.yml` since.

## Context

- The first build had hand-written flags: `-Yexplicit-nulls`, `-Wunused:all`, `-Wvalue-discard`, `-Wnonunit-statement`, but no fatal-warning flag. A green `sbt check` did not prove that the code had no warnings (D-8 facts).
- The plan recommended `-Werror` in every build (D-8 a). Its cost: an unused import in work-in-progress code stops the compile.
- The user changed it: "warnings of unused and stuff are warnings, use sbt-tpolecat".
- Spike S6 found gaps of the strict flags at the Java boundary: `-Wnonunit-statement` does not see a Java call in statement position, and `-Yexplicit-nulls` gives flexible types to Java results.
- `-Ycheck-all-patmat` reports a match on Kyo's opaque `Maybe` as not exhaustive. That is a false alarm.

## Decision

- sbt-tpolecat 0.5.7 sets the scalac options (`build.sbt`, "Compiler options").
- The default mode is `DevMode`: on a developer machine the warnings stay warnings.
- GitHub Actions sets `SBT_TPOLECAT_CI=1`, which adds `-Werror`. A local `tpolecatCiMode` gives the CI behaviour.
- The build adds `-Yexplicit-nulls` and one `-Wconf` rule for the noise of inlined Kyo `@nowarn`, and excludes `-Ycheck-all-patmat`.
- One exception stays fatal in every mode: a non-exhaustive match in `EventMapping.scala` is an error, so a new crossterm event case stops the build on a developer machine too.
- The code stays free of warnings. A null check is written by hand where a Java source or JNI contract allows null.

## Alternatives considered

- **Hand-written flags with `-Werror` everywhere** (D-8 a, the plan's recommendation). The user rejected it: unused-code warnings must not stop local work.
- **Fatal only when the `CI` variable is set, with the hand-written flags** (D-8 b). Close to the decision. The user named sbt-tpolecat instead of a hand-kept flag list; the notes give no other reason.
- **No fatal mode, a warning count in each PR body** (D-8 c). Warnings can pile up while CI is green.
- **Warnings everywhere, also in CI.** The mode was ASSUMED (dev locally, fatal in CI) when the user did not answer; one build line flips it.

## Consequences

- Good: CI holds the line at 0 warnings, and local work is not blocked by an unused import.
- Good: the flag set follows sbt-tpolecat upgrades, not a hand-kept list.
- Bad: a local `sbt check` is weaker than CI unless the developer runs `tpolecatCiMode`. A warning can reach CI and fail there.
- Bad: the flags do not see some Java-boundary mistakes (spike S6). Reviews must check for null at those calls.

## References

- Decision log: eighth round (D-8 changed); handoff "Open items for the user" (the mode, assumed).
- Plan: D-8, D-26; gates.md G6 (S6).
- CONTRIBUTING.md: "Toolchain" (sbt-tpolecat), "Rules for code" (the Java boundary). <!-- claude 2026-10-20: changed: CONTRIBUTING.md, not AGENTS.md (ADR 0021) -->
