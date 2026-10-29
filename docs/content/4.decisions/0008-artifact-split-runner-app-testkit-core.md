---
title: "0008. The artifact split: runner, app, testkit and core"
description: "tui4s-core becomes a composite of three artifacts in this repository: tui4s-runner, tui4s-app and tui4s-testkit, each with its own version and tag, in the same packages, with tui4s.internal sealed."
---

<!-- claude 2026-10-20: created: ADR 0008 in the docs site (L6) -->

## Status

Accepted

## Date

2026-10-19 (D25, issue [#17](https://github.com/yuri4n/tui4s/issues/17); the user: "do it before L3"). Done at 132358f.

## Context

- `tui4s-core` held three layers under one name: the runner (renderer, embedder, component model), the app shell (`TuiApp`) and the test kit (`Headless`, `Screen`).
- A kyo-ui user who wants only "run this UI in a terminal" (a library, or an app with its own `main`) had no artifact that says so.
- The spec of 2026-10-01 put the test kit inside core (Q6: "one artifact fewer to version and release").
- Each module already had its own version and tag (`<module>-v<semver>`, [ADR 0014](/decisions/0014-release-per-module-tags-and-rc1)).
- Nothing was published, so the split was free before 0.1 and a breaking change after it.

## Decision

- The repository publishes four artifacts for the terminal: `tui4s-runner` (Layout, Paint, Canvas, Runner, Mount, Dispatch, the terminal code, `Hooks`, `Component`, `Tui.run`, `TuiOptions`, `Tui.size`, `Screen`, `tui4s.spi`), `tui4s-app` (`TuiApp`), `tui4s-testkit` (`Headless`, aliases of the `Screen` types) and the composite `tui4s-core`.
- CHANGED by [ADR 0024](/decisions/0024-three-artifacts-one-central-deployment) (2026-10-29): the published artifacts are `tui4s-runner`, `tui4s-testkit` and `tui4s-core`. The core jar holds `TuiApp`, `tui4s.use` and `tui4s.widgets`. The POM of core depends on the runner and the test kit.
  <!-- cursor 2026-10-29: created: this item -->
- The jar of `tui4s-core` holds only `object tui4s.Core`. It depends on the three, and its test sources are the integration suites.
- All artifacts use the same packages (`tui4s`, `tui4s.internal`, `tui4s.spi`, `tui4s.testkit`), so imports do not change.
- `tui4s.internal` is sealed: each top-level definition is `private[tui4s]`. `PublicApiTest` checks the seal across the four jars, and that no public signature names an internal type.
- The release order is runner, then app and testkit, then core. The composite watches the three directories for the version policy.
- The names stay `tui4s-*`, not `kyo-ui-*`.

## Alternatives considered

- **One artifact `tui4s-core` with the test kit inside** (spec Q6, the default of 2026-10-01). Superseded: the runner had no artifact of its own.
- **A separate repository for the runner.** D25 says "in-repo, not a separate repository". The reason is not recorded beyond that.
- **Core depends on an external `kyo-ui-terminal-runner`.** Rejected for the name: `kyo-ui-*` is the namespace of upstream. Other reasons are not recorded.
- **Split after 0.1.** Rejected: the split is free now and breaking later.

## Consequences

- Good: a kyo-ui user takes `tui4s-runner` alone, and `tui4s-testkit` in the `Test` scope.
- Good: `TuiApp` and `Headless` evolve with their own versions.
- Bad: four tags to release in order, and three more deployment tag patterns in the `maven-central` environment (`runner-v*`, `app-v*`, `testkit-v*`): a human step.
- Bad: `private[tui4s]` is public in the bytecode. MiMa sees the internal types, so an internal change can break binary compatibility across the jars.
- Note: the planned zio and cats modules depended on the runner. They were removed on 2026-10-20 ([ADR 0014](/decisions/0014-release-per-module-tags-and-rc1)).

## References

- Decision log: D25. Issue [#17](https://github.com/yuri4n/tui4s/issues/17) (closed). Spec section 9, Q6 (superseded).
- Gate 1 answer 1 (the seal of `tui4s.internal`, 2026-10-13).
- ARCHITECTURE.md: "What tui4s is" (the module table). CONTRIBUTING.md: "Compatibility and versioning", "Releasing". <!-- claude 2026-10-20: changed: ARCHITECTURE.md and CONTRIBUTING.md, not AGENTS.md (ADR 0021) -->
