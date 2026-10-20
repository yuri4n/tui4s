---
title: "0004. Public Kyo APIs only"
description: "The runner calls no private[kyo] member and uses no reflection; where kyo-ui keeps a rule private, the runner copies its semantics and the surface guard pins what it reads."
---

# 0004. Public Kyo APIs only

## Status

Accepted

## Date

2026-10-13 (the rule of C3, review item RR N9). The rule grew with L1b on 2026-10-19.

## Context

- kyo-ui keeps its own runner rules private. The key dispatch and the region walk are in `kyo.internal`, and the type `Attrs` of the element attributes is `private[kyo]`.
- The compiler rejects a direct call to a `private[kyo]` member from package `tui4s`. Two ways around it exist: a source file in package `kyo`, or reflection.
- The prototype runner copied about 400 lines of these rules (`Dispatch` 242 lines, `Mount` 181 lines at the time).
- The kyo-ui AST is sealed, so tui4s cannot add its own node types. A component node is a kyo-ui `Reactive` whose signal is in a global weak registry (`internal.ComponentSpec`).
- `UI.scrollIntoView` reaches only a `private[kyo]` sink. Under tui4s it does nothing.

## Decision

- The runner uses only public Kyo and kyo-ui APIs. It calls no `private[kyo]` member, has no source file in package `kyo`, and uses no reflection.
- It reads the attributes through the public member `Element.attrs` and never names the type `Attrs`.
- Where kyo-ui keeps a rule private, the runner copies its semantics (`internal.Dispatch`, `internal.Mount`) and tests them.
- The surface guard (`kyo-ui-surface.txt`, a tasty-query test) pins each member that the runner reads. For L1b it also pins `Attrs.focusRestore`, `Attrs.cssClasses`, the cases of `Position`, `Display`, `FlexWrap` and `Stylesheet.Entry`, and the CSS of `position` and `display` in kyo-ui's `CssStyleRenderer`.
- Where a private rule cannot be copied (`scrollIntoView`), tui4s implements the browser rule itself (follow-focus, `aria-activedescendant`) and writes an upstream draft (ADR 0019).

## Alternatives considered

- **A source file in package `kyo`.** It compiles. Rejected: "not an option for a library that uses only public APIs" (the scroll-into-view draft). The notes give no other reason.
- **Reflection.** Rejected by the same rule. Spike S1 also measured a reflection probe for the settle. Under load (3 JVMs on 2 CPUs) it gave 5.58 split frames per 10 000 events with the Kyo scheduler values, and 8 of 10 test runs passed. The public `Scheduler.status()` probe passed 10 of 10 (ADR 0005).
- **A runner SPI upstream first** (the draft "a synchronous change notice on `Signal`", 2026-10-03). Not posted. tui4s does not wait for it; the draft stays a request.

## Consequences

- Good: a Kyo bump cannot break tui4s silently through a private member. The guard shows each change of a pinned member (ADR 0003).
- Good: tui4s is an example of a third-party kyo-ui backend that uses only the public surface.
- Bad: the copied rules can drift from kyo-ui. The guard reduces this risk but does not remove it.
- Bad: `UI.scrollIntoView` is a no-op in the terminal, with no error. An app uses the focus or `aria-activedescendant` instead.
- Note: `experiments/ui-state/frame` has a diagnostic fixture in package `kyo.framespike`. It is not part of a published module.

## References

- Review item RR N9 (runner report); the C3 rule; L1b (2026-10-19).
- Upstream drafts: kyo-ui scroll-into-view; kyo-ui change notice (ADR 0019).
- AGENTS.md: "UI architecture and API design" ("Use only Kyo's public APIs"); "Rules for code" (the RR N9 item).
