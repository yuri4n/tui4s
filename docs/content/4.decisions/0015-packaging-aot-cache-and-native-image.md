---
title: "0015. Packaging: the AOT cache in the start scripts, native-image as an option"
description: "The JVM start scripts have the startup flags by default and use a JDK 25 AOT cache after one run of bin/aot-train; tui4s-runner carries native-image metadata for the crossterm JNI, a script builds a native image, and CI smoke-tests it on GraalVM Community."
---

<!-- claude 2026-10-20: created: ADR 0015 in the docs site (L6); changed: the numbers of the shipped start scripts and of the bench next to the numbers of the spike, and the cache after one training (L6 loose ends) -->

## Status

Accepted (2026-10-20). This work landed on 2026-10-20 (L5b):

- the AOT cache in the start scripts of the examples;
- the native-image metadata of `tui4s-runner`;
- the script `scripts/native-image.sh`;
- the smoke job `.github/workflows/native.yml` on GraalVM CE.

A local build with GraalVM CE 25 made the native Counter. The first run of the smoke job is in CI.

The table below has the measurements of the spike of 2026-10-17. The numbers of the shipped start scripts and of the bench (2026-10-20) follow it.

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

The shipped start scripts (2026-10-20, the same kind of machine: the 4-vCPU Linux build container `container-4vcpu` of the bench, PTY 100x30, two rounds of 5 runs, the median of each; `examples/README.md`):

| Mode | Counter | Dashboard | RSS (counter) |
|---|---|---|---|
| Start script without the cache (the flags of before) | 1,118 to 1,144 ms | 1,344 to 1,351 ms | about 150 MB |
| Start script with the AOT cache and the flags | 511 to 526 ms | 584 to 631 ms | 104 MB |

Thus the shipped cache makes the start about 2.2x faster, not about 3x as in the spike (361 ms). The bench on the same machine (`bench/PERFORMANCE.md`, the median of 3 runs) measured the native Counter at 25 ms and 40 MB of RSS, and the native Dashboard at 49 ms and 80 MB.

- native-image worked the first time. The restore was correct on q, Ctrl-C, SIGTERM (143), SIGINT (130) and SIGHUP (129). The metadata is 2 entries and one Unsafe flag. A binary is 21.5 to 33 MB.
- Kyo's blocking monitor slept 86 to 102 ms at start; `blockingMonitorMinIntervalNs` removes it (done in P1, [ADR 0005](/decisions/0005-soft-frame-contract-and-own-watcher-loop)).
- `Tty.isatty` let `MissingForeignRegistrationError` escape (a `LinkageError`); fixed in P1.
- D16 as approved on 2026-10-17 made native-image per platform the release format, with Windows in CI. Oracle GraalVM or Community was open; the user chose Community (2026-10-19).

## Decision

- The JVM start scripts have the startup flags (`-XX:TieredStopAtLevel=1`, `-XX:+UseSerialGC`) by default.
- The distribution ships `bin/aot-train`, not a cache: a cache is valid only for the JDK that made it. After one training run, a start script uses the JDK 25 AOT cache of its app, when the same JDK made it; else it starts without the cache.
- `tui4s-runner` carries the native-image reachability metadata for the crossterm JNI in `META-INF/native-image`.
- A script builds a native image of an app. It is an option, not the default distribution.
- CI has a smoke job on GraalVM Community Edition that builds one native image and runs it in a PTY.
- The kitty keyboard protocol flags stay deferred ([ADR 0017](/decisions/0017-deferred-templates-mouse-and-kitty)).
- This narrows D16: native-image is no longer the release format per platform.

## Alternatives considered

- **JVM only.** Rejected: 1,101 ms to the first frame is visible to users, and the AOT cache cuts it to 361 ms in the spike (511 to 526 ms with the shipped start scripts) with no code change.
- **native-image as the default distribution** (D16 as approved). Not chosen: it loses the plain `java -jar` path, and the builds are long and per platform.
- **CRaC.** Not recorded in the notes.
- **Oracle GraalVM in CI.** The spike used Oracle GraalVM 25.0.4. The notes left the Oracle license as the open question; the user chose Community (2026-10-19).
- **AppCDS.** Rejected in spike S4: it writes an error line into the terminal.

## Consequences

- Good: a JVM user gets about a 2.2x faster start with the same artifacts, after one run of `bin/aot-train` (the spike measured about 3x).
- Good: a user who needs a fast start (a CLI tool) can build a native binary: 101 ms in the spike, 25 ms for the Counter of the bench.
- Bad: the native metadata is one more thing to keep in step with jatatui and Kyo upgrades.
- Bad: the JNI copy still leaves a `/tmp/jni-*` folder per start on macOS and Windows ([ADR 0019](/decisions/0019-upstream-kyo-issues-as-drafts)).

## References

- Roadmap: 2.7, D16 (and its Spanish copy). Decision log: sixteenth round (D16 edition).
- Handoff: "INSTRUCTION (user, 2026-10-20)" (the L5b plan).
