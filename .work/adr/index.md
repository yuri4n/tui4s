---
title: "Architecture decision records"
description: "The decisions that shaped tui4s, with the alternatives that lost and why."
---

# Architecture decision records

Each record states one decision: the forces, the decision, the alternatives that lost, and the consequences. The ids in the references (D-1 to D-32, D1 to D25, S1 to S8, K29, RR N9) are the ids of the working notes of the project, which are not in the repository. The ADRs keep what a reader needs from them.

| # | Title | Status | Decision in one line |
|---|---|---|---|
| [0001](0001-kyo-ui-is-the-dsl.md) | kyo-ui is the DSL | Accepted | tui4s has no UI DSL: kyo-ui describes the screen, and tui4s is its terminal runner. |
| [0002](0002-jatatui-renders-the-cells.md) | jatatui renders the cells | Accepted | jatatui 0.31.0 and its crossterm JNI are the backend; tui-scala never goes on the classpath. |
| [0003](0003-pin-kyo-rc7-and-jdk-25.md) | Pin Kyo 1.0.0-RC7 and JDK 25 | Accepted | Kyo and kyo-ui stay at RC7 for 0.1, on JDK 25; a tasty-query guard pins what the runner reads. |
| [0004](0004-public-kyo-apis-only.md) | Public Kyo APIs only | Accepted | No `private[kyo]` member and no reflection; the runner copies private rules and the guard pins them. |
| [0005](0005-soft-frame-contract-and-own-watcher-loop.md) | A SOFT frame contract and an own watcher loop | Accepted | One frame per event, 0 splits with no load, at most 1 per 10 000 under load; own watcher loop and a 1 s repair tick. |
| [0006](0006-purity-edge-files-and-lint.md) | Purity: edge files, lint and a pure painter | Accepted | Impure code only in 9 edge files and 15 KEEP sites, checked by scalafix; a pure painter, a release stack, a fiber gauge. |
| [0007](0007-compiler-flags-from-sbt-tpolecat.md) | Compiler flags from sbt-tpolecat | Accepted | Warnings stay warnings on a developer machine; CI adds `-Werror`. |
| [0008](0008-artifact-split-runner-app-testkit-core.md) | The artifact split: runner, app, testkit and core | Accepted | Four artifacts in one repository and the same packages; `tui4s-core` is the composite; `tui4s.internal` is sealed. |
| [0009](0009-use-and-widgets-cross-built.md) | tui4s-use and tui4s-widgets, cross-built | Accepted | Composables and widgets are plain kyo-ui for the JVM and Scala.js, with no dependency on the core. |
| [0010](0010-the-flagship-assistant-as-blueprint.md) | The flagship assistant is the blueprint of a real app | Accepted | One AI chat app shows the app structure; API keys only; no redirects; no CLI backend in published code. |
| [0011](0011-web-target-standalone-scalajs-and-one-theme.md) | The web target: a standalone Scala.js app and one theme | Accepted | The web app is static Scala.js from the shared module, with no server; one `Theme` gives both looks. |
| [0012](0012-documentation-is-example-first.md) | The documentation is example-first | Accepted | Every public API has a compiled example; doc blocks are tested regions; Docus on Vercel; no PRs in the program. |
| [0013](0013-benchmarks-in-repo-with-own-build.md) | Benchmarks in the repository, with their own build | Accepted | `bench/` has its own build; the headline is compactness, expressiveness, correctness and resources; performance on its own page. |
| [0014](0014-release-per-module-tags-and-rc1.md) | Release per module, by tag, as 0.1.0-RC1 | Accepted | One tag per module, 0.1.0-RC1 while Kyo is RC, a human approves each tag; zio, cats and `useSignal` go before 0.1. |
| [0015](0015-packaging-aot-cache-and-native-image.md) | Packaging: the AOT cache by default, native-image as an option | Accepted (work planned) | Launchers ship the AOT cache and flags; the runner carries native-image metadata; a GraalVM CE smoke job in CI. |
| [0016](0016-process-lean-mode-and-agent-roles.md) | Process: lean mode and agent roles | Accepted | Implementer, one reviewer, fixer; a skeptic only for concurrency and restore code; the notes leave the repository. |
| [0017](0017-deferred-templates-mouse-and-kitty.md) | Deferred: templates, mouse, the kitty keyboard protocol and Windows | Accepted (deferred) | No template interpolator, no mouse, no kitty flags and no supported Windows port in 0.1. |
| [0018](0018-pini4s-as-a-vendored-submodule.md) | pini4s is a vendored Git submodule | Accepted (one part proposed) | Optional keyed shared state in `vendor/pini4s`, used only by the examples; a published artifact later. |
| [0019](0019-upstream-kyo-issues-as-drafts.md) | Upstream issues are drafts that the user posts | Accepted | The session drafts upstream issues; the user posts them; each bug has a workaround with a named exit. |

Format: MADR-lite. Each record has the sections Status, Date, Context, Decision, Alternatives considered, Consequences and References. The text uses ASD-STE100. When a source does not record a reason, the record says "not recorded"; a reason that the record adds is marked PROPOSAL.
