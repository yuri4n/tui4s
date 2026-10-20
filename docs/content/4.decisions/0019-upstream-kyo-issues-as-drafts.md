---
title: "0019. Upstream issues are drafts that the user posts"
description: "The session writes upstream bug reports and feature requests as drafts; only the user posts them; tui4s works around each bug in its own code until upstream fixes it."
---

<!-- claude 2026-10-20: created: ADR 0019 in the docs site (L6) -->

## Status

Accepted

## Date

2026-10-03 (the first draft, A2), 2026-10-11 (gate 0: "we need issues at kyo and kyoui"), 2026-10-17 (D13).

## Context

- tui4s found bugs and missing APIs in Kyo RC7, kyo-ui RC7 and jatatui 0.31.0 while it built the runner, the widgets and the web app.
- A post on another project's tracker speaks for the user. The cloud session could also not search getkyo/kyo for duplicates (its GitHub access was bound to yuri4n/tui4s).
- kyo-ui is a module of getkyo/kyo, so its issues go to getkyo/kyo.

## Decision

- The session writes drafts only (D13; plan A2). The user searches for duplicates and posts each one. Nothing is posted without the user.
- The drafts cite only upstream code and tui4s numbers.
- Each workaround in tui4s has a TODO with a GitHub issue of tui4s where it must go away ([#8](https://github.com/yuri4n/tui4s/issues/8), [#9](https://github.com/yuri4n/tui4s/issues/9)).
- The drafts and the workarounds, as of 2026-10-20:

| Draft (target) | Workaround in tui4s |
|---|---|
| `Signal.observe` keeps about 1.7 KB per change (getkyo/kyo) | Own `currentWith`/`nextWith` watcher loop and a 1 s repair tick ([#8](https://github.com/yuri4n/tui4s/issues/8); [ADR 0005](/decisions/0005-soft-frame-contract-and-own-watcher-loop)) |
| A write between `current` and `nextWith` waits for the repair; `combineLatest` widens it (getkyo/kyo) | Settle re-check, pull check, SOFT contract, CI load flag ([#9](https://github.com/yuri4n/tui4s/issues/9); [ADR 0005](/decisions/0005-soft-frame-contract-and-own-watcher-loop)) |
| kyo-ui: a synchronous change notice on `Signal`, and handler types that reject lifted values (2026-10-03; not updated since W0) | The settle probe; `LiftTrapTest`; the AGENTS.md rule "type a handler as `Any < Async`" |
| kyo-kernel RC7 has JMH in compile scope (getkyo/kyo; [#40](https://github.com/yuri4n/tui4s/issues/40)) | `excludeAll(ExclusionRule("org.openjdk.jmh"))` in every module |
| A runner outside `kyo` cannot honour `UI.scrollIntoView` (getkyo/kyo; [#41](https://github.com/yuri4n/tui4s/issues/41)) | Own follow-focus rule (CSSOM `nearest`) and `aria-activedescendant` |
| `NativeLoader` leaves one `/tmp/jni-*` folder per start (jatatui; [#42](https://github.com/yuri4n/tui4s/issues/42)) | `internal.NativeCopy` deletes the copy after the load, Linux only |
| L4: a DOM keydown on a field inside a nested reactive region skips the field's handlers (kyo-ui JS; [#26](https://github.com/yuri4n/tui4s/issues/26)) | Not recorded |
| L4: no `preventDefault` on a handled keydown (kyo-ui JS; [#27](https://github.com/yuri4n/tui4s/issues/27)) | Alt chords on the web; the browser keeps most Ctrl chords and F1 |
| L4: any Enter (also Shift-Enter) submits the enclosing form (kyo-ui JS; [#28](https://github.com/yuri4n/tui4s/issues/28)) | The web composer is outside a form; Enter runs `send` |
| L4: a `Stylesheet` cannot override inline element styles (kyo-ui; [#29](https://github.com/yuri4n/tui4s/issues/29)) | Not recorded (seen on the `Panel` corners) |
| D13: an input bound with `.value(ref)` loses keys 10 ms or less apart (kyo-ui JS; [#37](https://github.com/yuri4n/tui4s/issues/37)) | Fewer bound round trips (read the text on submit) |
| L4 review: a bound `.value(ref)` writes the value into the DOM `value` attribute, in clear text (kyo-ui JS; [#38](https://github.com/yuri4n/tui4s/issues/38)) | The key field of the web is not bound |
| D13, not drafted: an Origin or auth hook for `runHandlers` | None needed: the web target has no server ([ADR 0011](/decisions/0011-web-target-standalone-scalajs-and-one-theme)) |
| D13: a cell or `ch` length unit ([#39](https://github.com/yuri4n/tui4s/issues/39)) | `Surface.rows` and `Surface.cols`; `em` in shared views |
| Not drafted: the JNI cuts a typed non-BMP character to 16 bits (jatatui) | A bracketed paste keeps it |

## Alternatives considered

- **The session posts the issues.** Rejected: only the user posts (gate 0; the standing rule).
- **No upstream issues; workarounds only.** Rejected by the user at gate 0: "we need issues at kyo and kyoui if they are not there yet".
- **Patch Kyo in a fork or in package `kyo`.** Rejected by [ADR 0004](/decisions/0004-public-kyo-apis-only).

## Consequences

- Good: each workaround has a named exit, and the drafts are ready to post.
- Bad: until the user posts them, upstream does not know; the workarounds stay (two TODOs in the runner).
- Bad: the notes leave the repository, so the draft texts move into GitHub issues (the issues with the label `upstream-kyo`: [#26](https://github.com/yuri4n/tui4s/issues/26) to [#29](https://github.com/yuri4n/tui4s/issues/29) and [#37](https://github.com/yuri4n/tui4s/issues/37) to [#42](https://github.com/yuri4n/tui4s/issues/42)).

## References

- Decision log: eighth round (upstream issues). Plan: A2. Roadmap: D13. Issues [#8](https://github.com/yuri4n/tui4s/issues/8), [#9](https://github.com/yuri4n/tui4s/issues/9).
- Handoff: L1a (scroll-into-view), P1 (JMH, JNI copy), L4 (four web findings), T1 (non-BMP).
