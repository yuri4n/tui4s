---
title: "0015. Packaging: the AOT cache by default, native-image as an option"
description: "The JVM launchers ship the JDK 25 AOT cache and the startup flags by default; tui4s-runner carries native-image metadata for the crossterm JNI, a script builds a native image, and CI smoke-tests it on GraalVM Community."
---

<!-- claude 2026-10-20: created: ADR 0015 in the docs site (L6) -->

## Status

Accepted (2026-10-20). The AOT launchers, the native-image metadata and the GraalVM CE smoke job landed on 2026-10-20 (L5b). The numbers below are the measurements of the spike of 2026-10-17.

## Date

2026-10-17 (D16 with the numbers), 2026-10-19 (GraalVM Community in CI), 2026-10-20 (the L5b plan).

## Context

First frame from process start, PTY 100x30, median of 15 runs, 4 vCPU Linux:

| Mode | Counter | Dashboard | RSS (counter) |
|---|---|---|---|
| Launcher today (JVM) | 1,101 ms | 1,233 ms | 125 MB |
| Plain Java jatatui program (JVM floor) | 147 ms | n/a | 56 MB |
| JDK 25 AOT cache only | 521 ms | 588 ms | 113 MB |
| AOT cache + launcher flags | 361 ms | 433 ms | 94 MB |
| AOT + background preload + flags | 320 ms | 395 ms | 93 MB |
| GraalVM native-image | 101 ms | 104 ms | 31 MB |

- native-image worked the first time. The restore was correct on q, Ctrl-C, SIGTERM (143), SIGINT (130) and SIGHUP (129). The metadata is 2 entries and one Unsafe flag. A binary is 21.5 to 33 MB.
- Kyo's blocking monitor slept 86 to 102 ms at start; `blockingMonitorMinIntervalNs` removes it (done in P1, [ADR 0005](/decisions/0005-soft-frame-contract-and-own-watcher-loop)).
- `Tty.isatty` let `MissingForeignRegistrationError` escape (a `LinkageError`); fixed in P1.
- D16 as approved on 2026-10-17 made native-image per platform the release format, with Windows in CI. Oracle GraalVM or Community was open; the user chose Community (2026-10-19).

## Decision

- The JVM launchers ship the JDK 25 AOT cache and the startup flags by default.
- `tui4s-runner` carries the native-image reachability metadata for the crossterm JNI in `META-INF/native-image`.
- A script builds a native image of an app. It is an option, not the default distribution.
- CI has a smoke job on GraalVM Community Edition that builds one native image and runs it in a PTY.
- The kitty keyboard protocol flags stay deferred ([ADR 0017](/decisions/0017-deferred-templates-mouse-and-kitty)).
- This narrows D16: native-image is no longer the release format per platform.
- The per-platform native binaries of the earlier plan are deferred. tui4s is a library, so a native binary belongs to an app, and each app builds its own. native-image cannot cross-compile, so each OS and CPU needs its own CI runner and a long build, and only Linux has a PTY check ([ADR 0017](/decisions/0017-deferred-templates-mouse-and-kitty)).

## Alternatives considered

- **JVM only.** Rejected: 1,101 ms to the first frame is visible to users, and the AOT cache cuts it to 361 ms with no code change.
- **native-image as the default distribution** (D16 as approved). Not chosen: it loses the plain `java -jar` path, and the builds are long and per platform.
- **CRaC.** Not recorded in the notes.
- **Oracle GraalVM in CI.** The spike used Oracle GraalVM 25.0.4. The notes left the Oracle license as the open question; the user chose Community (2026-10-19).
- **AppCDS.** Rejected in spike S4: it writes an error line into the terminal.

## Consequences

- Good: a JVM user gets about a 3x faster start with the same artifacts.
- Good: a user who needs a fast start (a CLI tool) can build a 100 ms native binary.
- Bad: the native metadata is one more thing to keep in step with jatatui and Kyo upgrades.
- Bad: the JNI copy still leaves a `/tmp/jni-*` folder per start on macOS and Windows ([ADR 0019](/decisions/0019-upstream-kyo-issues-as-drafts)).

## References

- Roadmap: 2.7, D16 (and its Spanish copy). Decision log: sixteenth round (D16 edition).
- Handoff: "INSTRUCTION (user, 2026-10-20)" (the L5b plan).
