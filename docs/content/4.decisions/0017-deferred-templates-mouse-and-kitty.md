---
title: "0017. Deferred: templates, mouse, the kitty keyboard protocol and Windows"
description: "Four features stay out of 0.1: a ui\"\"\"...\"\"\" template interpolator (intent only), mouse input, the kitty keyboard protocol (so Shift-Enter is unreported in most terminals) and a supported Windows port."
---

<!-- claude 2026-10-20: created: ADR 0017 in the docs site (L6) -->

## Status

Accepted (deferred). Each item has a GitHub issue: templates [#21](https://github.com/yuri4n/tui4s/issues/21), mouse [#20](https://github.com/yuri4n/tui4s/issues/20), the kitty keyboard protocol [#23](https://github.com/yuri4n/tui4s/issues/23) and Windows [#24](https://github.com/yuri4n/tui4s/issues/24).

## Date

2026-10-09 (D-30, no mouse), 2026-10-17 (D7, D9, D12), 2026-10-19 (L1c found the Shift-Enter gap).

## Context

- The budget is small (the user, 2026-10-17: "No tengo tantos créditos"), and 0.1 must claim only what works.
- **Templates.** The user asked for a spike of single-file components or JSX for Scala ("scalax"). Research: a `ui"""..."""` Scala 3 quotes macro is feasible and runner-neutral (it expands to kyo-ui calls). XML literals are a dead end: Scala 3 drops them, and the replacement interpolator is unmaintained.
- **Mouse.** Mouse capture stops the text selection of the terminal (D-30). The research ranked mouse last: it unblocks 1 of 28 common widgets.
- **Kitty keyboard protocol.** Without the keyboard-enhancement flags most terminals do not report Shift with Enter. The runner pushes no flags today.
- **Windows.** The crossterm jar ships a Windows x86_64 library. The PTY kit uses `expect` and runs on Linux; the Nix shell covers Linux and macOS.

## Decision

- **Templates: intent only (D9).** No spike now. If it comes back: a `ui"""..."""` interpolator with `${...}` holes, Metals support first, in `experiments/scalax`, after L2.
- **No mouse for 0.1 (D-30, D7).** kyo-ui mouse events are dropped.
- **No kitty keyboard flags for 0.1.** A textarea takes Alt-Enter and Ctrl-J for a new line, and Shift-Enter only where the terminal reports it (D7).
- **Windows is experimental (D12).** It is not in the CI matrix and not in the PTY kit.

## Alternatives considered

- **A template spike now** (`ui"""..."""`). Rejected by the user for the budget.
- **XML literals for templates.** Rejected: a dead end in Scala 3.
- **Mouse in 0.1.** Rejected: mouse capture breaks text selection, and it unblocks few widgets.
- **Push the kitty flags in 0.1.** The reason to defer is not recorded. The notes list it as an open item of L1c, and the L5b plan defers it.
- **A supported Windows port.** The reason for "experimental" is not recorded beyond D12.

## Consequences

- Good: 0.1 claims only what the PTY kit checks.
- Bad: on the web, `RadioGroup` has no click handler; a terminal user cannot click anything.
- Bad: Shift-Enter is unreported in most terminals; the help texts name Alt-Enter and Ctrl-J.
- Bad: the JNI copy cannot be deleted on Windows while it is loaded, so one `jni-*` folder stays in the temp directory per start there.

What brings each back (the notes record only the first item; the other three are PROPOSALS of this ADR):

- Templates: the budget, and a decision on where the layer lives (it emits plain kyo-ui, so it may fit upstream or in its own repository).
- Mouse: an opt-in capture mode, so text selection still works by default, and a widget that needs it.
- Kitty flags: a terminal-capability check, and a restore of the flags in the release stack.
- Windows: a Windows CI job and a PTY check on ConPTY.

## References

- Plan: D-30, FC-D3. Roadmap: 2.3, 2.6 (templates), D7, D9, D12, S1.
- Handoff: L1c (open items). Upstream draft: jatatui JNI copy (Windows note).
