---
title: "0009. tui4s-use and tui4s-widgets, cross-built"
description: "Composables (tui4s-use) and widgets (tui4s-widgets) are published modules of this repository, plain kyo-ui code for the JVM and Scala.js, with no dependency on tui4s-core."
---

<!-- claude 2026-10-20: created: ADR 0009 in the docs site (L6) -->

## Status

Accepted

## Date

2026-10-17 (D6). Landed on 2026-10-19 (L2a, L2b).

## Context

- The user asked for "enough widgets to build most terminal applications", a hook library "like VueUse", and one widget tree for the terminal and the web.
- Research of 2026-10-17: most of VueUse is 1 to 3 lines of native Kyo. About 13 composables earn their place. Of 28 common terminal widgets, 5 worked, 22 had only a workaround, and 1 (textarea) could not be built before L1.
- kyo-ui runs one UI value in the browser (Scala.js `runMount`). A widget written as a plain kyo-ui function, with no tui4s import, works on both targets.
- Model and event wrappers did not simplify the real call site.
  <!-- cursor 2026-10-29: changed: drop the path of the removed experiments. -->

## Decision

- `tui4s-use` (package `tui4s.use`) has 13 composables over `SignalRef`, `Signal` and `Scope`: Pacing, Selection, History, ScrollWindow, TimeAgo, Keymap, AsyncState, Dialog, Storage, useProcess and others. Each takes its lifetime from the caller's `Scope`.
- `tui4s-widgets` (package `tui4s.widgets`) has 31 widgets on `tui4s-use`: containers, data, input, feedback and navigation families.
- Both are cross-built (JVM and Scala.js, `CrossType.Full`) and depend on kyo-ui only, not on `tui4s-core`. Each has its own tag (`use-v*`, `widgets-v*`).
- A widget takes typed props, a `Model` for its value (`Local`: the widget owns it; `Bound`: the parent's `SignalRef`, written before `onChange`; `Controlled`: the parent's `Signal`, and `onChange` asks), and function slots.
- A widget takes its colours from the `--tui4s-*` custom properties (`Colors`, 8 `Color.Var` names) and also shows its state with a mark (`›`, `[x]`, bold).
- The README section "You may not need a composable" comes first: it shows the native Kyo line for each common hook.
- The gallery example shows each widget; `GallerySuite` and `ReadmeSuite` check them.

## Alternatives considered

- **A separate UI core repository.** Rejected: "There is no separate UI core repository" (CONTRIBUTING.md). tui4s owns the terminal rendering and its mount identity; the widgets stay plain kyo-ui. The reason for the repository choice is not recorded beyond that. <!-- claude 2026-10-20: changed: CONTRIBUTING.md, not AGENTS.md (ADR 0021) -->
- **Widgets inside `tui4s-core`.** Rejected by D6 ("no dependency on core"). The notes do not state the reason. The obvious one: `tui4s-core` is JVM-only (the crossterm JNI), and a widget must also run on Scala.js.
- **An own render engine for the web and the terminal.** Estimated at 6k to 12k lines (engine 3-5k, widgets 2-4k, web client 1-2k) and several months. Rejected for the hybrid: plain kyo-ui widgets, and units and a theme fixed instead.
- **Model and event wrappers over Kyo signals.** Rejected by the user's experiments: no simpler call site.
- **pini4s for widget state.** Rejected: a widget owns its local state by default; pini4s stays optional ([ADR 0018](/decisions/0018-pini4s-as-a-vendored-submodule)).

## Consequences

- Good: the same widget runs in the terminal and in the browser, and the assistant uses them on both ([ADR 0010](/decisions/0010-the-flagship-assistant-as-blueprint), [ADR 0011](/decisions/0011-web-target-standalone-scalajs-and-one-theme)).
- Good: users without tui4s-core (a pure kyo-ui web app) can use the widgets.
- Bad: the widgets have no unit scale for the web. `px` and `em` are cells in the terminal; on the web a list or a pager clips about one line in five ([#25](https://github.com/yuri4n/tui4s/issues/25)).
- Bad: `RadioGroup` has no click handler, so it is keyboard-only on the web; `Toasts` and `Spinner` render again on each tick.
- Bad: sbt-scalajs in `project/` marks all modules unreleased (accepted before 0.1).

## References

- Roadmap: 2.6, D6, D7, D8. Handoff: L2a, L2b.
- CONTRIBUTING.md: "UI architecture and API design" (the use and widgets items). <!-- claude 2026-10-20: changed: CONTRIBUTING.md, not AGENTS.md (ADR 0021) -->
- README: "Composables (tui4s-use)", "Widgets (tui4s-widgets)".
