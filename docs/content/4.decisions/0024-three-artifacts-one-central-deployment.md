---
title: "0024. Three artifacts, one Central deployment"
description: "The published artifacts are tui4s-runner, tui4s-core and tui4s-testkit. The core jar holds TuiApp, the composables and the widgets. One release run uploads one bundle to Maven Central."
---

<!-- cursor 2026-10-29: created: ADR 0024 (three artifacts, one Central deployment) -->

## Status

Accepted. It changes the published set of [ADR 0008](/decisions/0008-artifact-split-runner-app-testkit-core) and [ADR 0009](/decisions/0009-use-and-widgets-cross-built), and the upload of [ADR 0020](/decisions/0020-release-from-the-actions-form).

## Date

2026-10-29.

## Context

- Maven Central counts each publish operation as one release. The free monthly threshold is tight. A release of all six modules called `sonaRelease` six times.
- `tui4s-app` is one class, `TuiApp`. `tui4s-core` was an empty jar whose POM depended on the runner, the app and the test kit.
- `tui4s-use` and `tui4s-widgets` are plain kyo-ui code for the JVM and for Scala.js. The assistant web app compiles the Scala.js projects. It does not need a published Scala.js coordinate.
- `0.1.0-RC1` of the old coordinates is on Maven Central. A published coordinate cannot be removed.
- sbt writes every non-snapshot module into `target/sona-staging`. One `sonaRelease` zips that directory. The root project is a snapshot, so `sonaRelease` runs in a module project.
- A GitHub Release is the notes of a module. Its body is the changelog subsection. An existing Release stays. That does not skip the Maven publish. Maven Central is the check for a second upload.

## Decision

- The published artifacts are `tui4s-runner`, `tui4s-testkit` and `tui4s-core`.
- The jar of `tui4s-core` holds `TuiApp`, `tui4s.use` and `tui4s.widgets`, and the object `tui4s.Core`. The POM depends on `tui4s-runner` and `tui4s-testkit`. It does not depend on `tui4s-app`, `tui4s-use` or `tui4s-widgets`.
- The sbt projects `app`, `use` and `widgets` stay. `use` and `widgets` still build for Scala.js, so the assistant web app compiles. Those projects are not published. Their version is the version of `core`. `versionPolicyCheck` does not compare them with `0.1.0-RC1`. The check of that code is the check of `core`.
- A change in `modules/app`, `modules/use` or `modules/widgets` makes `core` unreleased. The release order is `runner`, then `testkit`, then `core`.
- One run of the Release workflow pushes the tags and creates the GitHub Releases, then uploads every module that is not yet on Maven Central in one `sonaRelease`. A module that is already on Central stays out of the bundle.
- New runs do not create `app-v*`, `use-v*` or `widgets-v*` tags. The ruleset still blocks a move or a deletion of those old tags.

## Alternatives considered

- **Keep six `sonaRelease` calls and only bundle the upload.** Rejected: the file count of the extra coordinates stays, and a release of one quiet module still spends a deployment when the maintainer selects `all`.
- **One version for every module.** Rejected by [ADR 0014](/decisions/0014-release-per-module-tags-and-rc1). The runner and the test kit keep their own tags.
- **Publish the Scala.js artifacts of use and widgets.** Rejected for this change: the published set is the three JVM artifacts. The web app in this repository still compiles the sources.
- **Shade the runner and the test kit into the core jar.** Rejected: a library still takes `tui4s-runner` alone, and a test takes `tui4s-testkit` in the `Test` scope.

## Consequences

- Good: a release of all three modules is one Central publish operation.
- Good: an app depends on `tui4s-core` and gets `TuiApp`, the composables, the widgets, the runner and `Headless`.
- Good: the assistant web app still builds.
- Bad: `tui4s-app`, `tui4s-use` and `tui4s-widgets` get no new version. A user of those coordinates moves to `tui4s-core`. `0.1.0-RC1` stays on Central.
- Bad: there is no published Scala.js coordinate for the composables or the widgets.
- Bad: a bundle that repeats a coordinate is refused, and that refusal drops the other modules in the same zip. A re-run waits until `repo1.maven.org` shows the new POMs.

## References

- `.github/workflows/release.yml`, `scripts/release-lib.sh`, `project/CorePackaging.scala`, `build.sbt`.
- CONTRIBUTING.md: "Releasing". ARCHITECTURE.md: the module table.
- [ADR 0008](/decisions/0008-artifact-split-runner-app-testkit-core), [ADR 0009](/decisions/0009-use-and-widgets-cross-built), [ADR 0020](/decisions/0020-release-from-the-actions-form).
