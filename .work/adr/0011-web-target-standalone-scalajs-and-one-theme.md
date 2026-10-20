---
title: "0011. The web target: a standalone Scala.js app and one theme"
description: "The web build of the assistant is a static Scala.js app from the shared module, with no server and no link to the terminal app; one Theme value renders both looks to one Stylesheet."
---

# 0011. The web target: a standalone Scala.js app and one theme

## Status

Accepted

## Date

2026-10-17 (D5 as changed by the user, D8). Landed on 2026-10-19 (L4).

## Context

- The user asked for "a small Flutter": write the app once, build a terminal app and a web app.
- A JVM spike ran one kyo-ui value in the terminal and in headless Chromium at the same time, with live shared state (23 of 24 checks). The kyo-ui WebSocket accepts any Origin and has no auth hook (cross-site WebSocket hijacking).
- A Scala.js spike compiled one source file in the JVM build and in the JS build; a headless tui4s test and a browser test both passed.
- The user then said: the targets must NOT communicate ("I just want a flutter style paradigm of coding once and generating for multiple platforms").
- `px` is one cell in tui4s and one CSS pixel in a browser. The default Scala.js bundle is 33 MB (2.9 MB gzip); the CLDR locale database is 61% of it.

## Decision

- The web target is a standalone Scala.js app (`examples/web`, project `assistantWeb`) built from the shared module of the assistant (a `crossProject` JVM+JS with kyo-ui, tui4s-use and tui4s-widgets only). It is static files: one page and one script. There is no server.
- Platform services are traits with one interpreter per platform, given through layers. The browser has `FetchHttp` (streamed reader, `AbortController`, `redirect=error`, `credentials=omit`, no referrer), `BrowserKeys` over `localStorage`, and a console log.
- The user's own key stays in the browser (one entry per provider, masked, with Forget and a warning). The page policy (CSP) allows only the two provider hosts. The fake provider serves the docs demos.
- One `Theme` value has two looks (terminal, web). It renders to a palette of colour variables (the terminal) and to one full `Stylesheet` (the web). The assistant has a look switch.
- A `Surface` service gives the units: `rows(n)` and `cols(n)` are cells in a terminal and lines or characters on the web; the web has its own Alt chords.
- The bundle uses the minimal en-US locale database: 9.74 MB (1.20 MB gzip); it mounts in about 0.9 s.

## Alternatives considered

- **A server-rendered kyo-ui app over its WebSocket runner** (the JVM spike). Rejected by the user: the targets must not communicate. It also needs an Origin check that kyo-ui RC7 does not have.
- **A small proxy server that holds the key** (an option of D5). Not chosen: the user approved the recommendation (the user's own key in the browser). The reason against the proxy is not recorded.
- **The fake provider only on the web.** Kept for the demos, but not as the only provider.
- **One look only** (native web or terminal-like). D8 chose both from one value.
- **A shared source directory for two builds** (the spike). A hack; the repository uses `sbt-crossproject`.

## Consequences

- Good: the same domain, store and UI code run in the terminal and in the browser, and each target runs alone.
- Good: the key never leaves the browser except to its provider.
- Bad: the key is readable by extensions and other pages of the same origin. Anthropic needs the header `anthropic-dangerous-direct-browser-access`. The README says so.
- Bad: kyo-ui bugs on the web force workarounds: the composer is outside a form (any Enter submits), the key field is not bound (a bound value goes into the DOM `value` attribute), and Esc does not close the rename prompt while its field has focus (ADR 0019).
- Bad: the widgets have no unit scale for the web (ADR 0009).

## References

- Roadmap: 2.1, 2.2, D5, D8. Decision log: fifteenth round. Handoff: L4.
- README: "The same app on the web". Scripts: `scripts/web-build.sh`, `scripts/web-smoke.mjs` (not in CI).
