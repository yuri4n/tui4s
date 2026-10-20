---
title: "0014. Release per module, by tag, as 0.1.0-RC1"
description: "Each module has its own version and tag; the first release is 0.1.0-RC1 while Kyo is an RC; a human approves every tag; the empty zio and cats modules and the deprecated useSignal go before 0.1."
---

# 0014. Release per module, by tag, as 0.1.0-RC1

## Status

Accepted. The two removals of 2026-10-20 are planned in L6.

## Date

2026-09-30 (per-module versions), 2026-10-17 (D12), 2026-10-20 (the removals).

## Context

- The user wants independent versions per module, one command to release one module, and no release of an integration against an unreleased core.
- Kyo and kyo-ui are at 1.0.0-RC7 (ADR 0003). A tui4s 0.1.0 final on an RC base would promise more than it can keep.
- A Maven Central artifact cannot be removed. The release workflow publishes on a tag push.
- coursier (and sbt) put `M` below `alpha` and `beta`; Maven puts it above.
- The modules `zio` and `cats` have only a `build.sbt` and a `compat.sbt`, no source. The ZIO and Cats Effect facades of the spec (`tui4s.interop.zio`, `tui4s.interop.cats`) were built only on the old DSL (branches `feat/zio`, `feat/cats`), never on kyo-ui.
- `Hooks.useSignal` only renames `Signal.initRef` and is deprecated since P1b. No released user exists.

## Decision

- Each module has its own version and tag `<module>-v<semver>` (`runner`, `app`, `testkit`, `core`, `use`, `widgets`). sbt-ci-release and `ModuleVersioning` set the version; no build file sets it.
- `scripts/release.sh <module> <version>` checks the module, the clean tree, CI, the version order (Maven order: `alpha` < `beta` < `M` < `RC` < final) and unreleased dependencies, then pushes one tag. Release order: runner, app and testkit, core; use, widgets.
- The first release is `0.1.0-RC1` while Kyo is an RC (D12). Windows is experimental.
- Early-semver with sbt-version-policy: each module has a `compat.sbt` intention; CI runs `versionPolicyCheck`.
- An agent runs `--dry-run` at any time, but needs a human's approval in the chat to push a tag, and never uses `--yes` on its own.
- A human adds each tag pattern to the GitHub environment `maven-central` (`runner-v*`, `app-v*`, `testkit-v*`, `core-v*`, `use-v*`, `widgets-v*`).
- NEW (2026-10-20): the empty `zio` and `cats` modules leave the build, the release script, the workflow and the docs before the first release. They come back as modules when they exist (a GitHub issue each).
- NEW (2026-10-20): the deprecated `useSignal` is removed before 0.1.

## Alternatives considered

- **One version for all modules.** Rejected by the user (2026-09-30): independent versions.
- **0.1.0 final.** Rejected by D12: Kyo is an RC.
- **Keep the zio and cats placeholders as "planned".** Rejected: a placeholder module in the build, the release script and the docs helps nobody and overclaims.
- **Publish empty zio and cats artifacts.** Rejected by D12 ("no empty zio/cats artifacts").
- **Keep `useSignal` deprecated for one release.** Rejected: no released user exists, and a deprecated alias in a first release is clutter.
- **Release without a human gate.** Rejected: a public artifact cannot be removed.

## Consequences

- Good: a fix in one module releases only that module.
- Good: the first release claims only what exists.
- Bad: several tags per release, pushed one at a time (GitHub ignores a push of more than three tags).
- Bad: `M` versions after `alpha` or `beta` of the same version sort wrong in coursier; the rule says not to release them.
- Bad: ZIO and Cats Effect users get no integration in 0.1; the facade design (a hand-written DSL, the vendored Cats bridge) waits for its issue.

## References

- Decision log: first, third and fourth rounds; sixth round (ZIO and Cats order); fifteenth round (D12).
- Roadmap: D12, L6. Handoff: "INSTRUCTION (user, 2026-10-20)".
- AGENTS.md: "Releasing", "Compatibility".
