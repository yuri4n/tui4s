---
title: "0001. kyo-ui is the DSL"
description: "tui4s has no UI DSL of its own: kyo-ui describes the screen, and tui4s is its terminal runner."
---

<!-- claude 2026-10-20: created: ADR 0001 in the docs site (L6) -->

## Status

Accepted

## Date

2026-10-02 (the fifth round of the decision log). The kyo-ui stack merged on 2026-10-14.

## Context

- The first goal (2026-10-01) was "a Kyo core that wraps the underlying Scala library", declarative and fast, with ZIO and Cats Effect modules.
- A design panel scored four designs. The winner, "free", had an own model: `TuiApp[S]` with `init`, `update(S, Event): Next[S]`, `view(S): View`, tui4s `View` case-class nodes and a `Keymap` (spec section 1). Scores: free 7.53, elm 7.35, signals 6.95, components 5.73.
- The panel's core was built as PRs 3 to 5. On 2026-10-02 the user reviewed it: it was not a functional wrapper of jatatui, and `internal/` was about 3.5k lines of mutable code (the engine had 25 `var`s).
- The user then pointed at kyo-ui. kyo-ui already has the model that the work moved towards: pure UI values, signals with fine-grained subscriptions, handlers of type `Any < Async`, two-way `SignalRef` bindings. Its README invites custom backends, and upstream has no terminal runner (only DOM, server push and HTML stream).

## Decision

- tui4s is a terminal runner for kyo-ui: it lays out a kyo-ui `UI` value in terminal cells and paints it with jatatui.
- tui4s adds no UI DSL. An app imports `kyo.UI.*` for the screen and `tui4s` only to run, test and mount.
- tui4s adds only what a terminal needs: the components in two styles (hooks and the `Component` class), `Tui.run`, `TuiApp` and the headless kit.
- The own View/Keymap/Engine model of the spec is dropped. PRs 3, 4 and 5 were closed, and their branches stay only as history.

## Alternatives considered

- **The own DSL of the 2026-10-01 spec** (`App[S]`, `View` nodes, `Keymap`, `Next[S]`, `sources(S)`). It won the panel. It lost because the user found its core not functional, and because kyo-ui already gives a richer UI model that tui4s would otherwise copy and maintain.
- **The other panel designs** (elm, signals, components). They scored lower. The panel found a near-fatal DX flaw in components (Kyo effect rows in user code) and a learnability flaw in signals (`map` against `at`, torn frames) (spec 1.1).
- **The user's Flutter-style first take** (Widget, Element, State on Cats Effect; branch `first-take`). It was one candidate of the panel and was not chosen. The reason for this candidate alone is not recorded.
- **Three prototype styles on jatatui** (pure, mutable, streams; in the notes). The run stopped at the plan stage when the user pointed at kyo-ui.
- **A State-monad UI** (spike S8, 2026-10-09: "the UI as State monads that change the buffer"). The user cancelled it ("let's not do it"). The spec is parked.
- **tui-scala as the base of the DSL**. tui-scala 0.0.10 is a tui-rs (2022) API with no input or event system, and its author calls jatatui its successor. See [ADR 0002](/decisions/0002-jatatui-renders-the-cells).

## Consequences

- Good: one UI value runs in the terminal and in a browser ([ADR 0011](/decisions/0011-web-target-standalone-scalajs-and-one-theme)). The user's "small Flutter" goal comes from kyo-ui, not from new code.
- Good: tui4s has no DSL to design, document or keep in step with kyo-ui.
- Bad: tui4s depends on the AST, the attributes and the private rules of kyo-ui. The runner copies the dispatch and region rules, and a surface guard pins them ([ADR 0003](/decisions/0003-pin-kyo-rc7-and-jdk-25), [ADR 0004](/decisions/0004-public-kyo-apis-only)).
- Bad: kyo-ui is designed for the DOM. Units (`px`), mouse events, SVG and charts do not map to cells. The runner ignores 29 of 76 style properties and warns once per name.
- Bad: the ZIO and Cats Effect modules must mirror the kyo-ui DSL by hand. They are not built ([ADR 0014](/decisions/0014-release-per-module-tags-and-rc1)).

## References

- Decision log: fifth round (2026-10-02), sixth round (2026-10-09), seventh round (S8 cancelled).
- Handoff: "2026-10-02: DIRECTION CHANGE".
- Spec: sections 1.1 to 1.4 and 9 (most of its API shapes were dropped by this decision).
- PRs #3, #4, #5 (closed); PRs #10 to #16 (the kyo-ui stack, merged 2026-10-14).
- ARCHITECTURE.md: "What tui4s is". <!-- claude 2026-10-20: changed: ARCHITECTURE.md, not AGENTS.md (ADR 0021) -->
