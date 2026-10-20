---
title: "0002. jatatui renders the cells"
description: "jatatui 0.31.0 (a Java port of ratatui) with its crossterm JNI library is the terminal backend; tui-scala never goes on the classpath."
---

<!-- claude 2026-10-20: created: ADR 0002 in the docs site (L6) -->

## Status

Accepted

## Date

2026-09-30 (the second round of the decision log).

## Context

- The user first asked for a wrapper of "the Scala terminal library", which was tui-scala (`com.olvind.tui`).
- Research on 2026-09-30 found that the tui-scala repository now redirects to `oyvindberg/jatatui`. The release notes of jatatui 0.30.0 call it "the successor to tui-scala". jatatui ports ratatui 0.30; tui-scala 0.0.10 ports tui-rs of 2022 (no `Line`, no Scrollbar, no Calendar).
- jatatui publishes a `TestBackend`, so a test can render without a terminal. tui-scala publishes none, and its unpublished one has a cell-aliasing bug.
- tui-scala has hazards that a probe showed: a global unbounded `Layout.LAYOUT_CACHE` (one entry per frame), live cell references from `Buffer.diff`, and 329 KiB per frame for one full-screen block.
- Both libraries load a native crossterm library (Rust, JNI) from the jar. Both use the Java package `tui.crossterm` with the same class names, under different Maven coordinates, so eviction cannot remove one of them.
- A typical 120x40 jatatui frame costs about 2 ms and 1.7 MiB, also when nothing changed. Buffers, cells and widget states are mutable.

## Decision

- tui4s uses `com.olvind.jatatui` 0.31.0: `jatatui-core` and `jatatui-crossterm` (which brings `crossterm`). `jatatui-widgets` is not used (D-12 a).
- `com.olvind.tui` (tui-scala) never goes on the classpath.
- tui4s does the terminal setup itself (raw mode, alternate screen, restore). It does not call `Jatatui.init` or `runIo`, because they install a global exception handler and a shutdown hook.
- All drawing happens on one fiber. Only the input thread reads the terminal, with `poll(<short timeout>)` then `read()`, because the native `read()` blocks and an interrupt cannot stop it.
- No jatatui type is in the public API of tui4s: the test kit has its own `CellStyle` and `CellColor` (D-13 a).
- A run in the terminal passes `--enable-native-access=ALL-UNNAMED`. Without it JDK 25 prints a warning into the terminal; with `--illegal-native-access=deny` the run fails with `TuiError.NativeAccessDenied`.

## Alternatives considered

- **tui-scala (`com.olvind.tui` 0.0.10)**. It lost: development moved to jatatui, its API is older, it has no published test backend, and its layout cache leaks.
- **An own ANSI writer.** Not recorded. The decision log names no evaluation of it.
- **Lanterna.** Not recorded.
- **JLine.** Not recorded as a backend. The research names JLine only as the reason that `sbt run` fights raw mode.

## Consequences

- Good: the cells, the diff and the backend come from a maintained port of ratatui. `TestBackend` makes headless tests possible (`tui4s.testkit.Headless`).
- Good: tui4s owns the painter (`internal.Paint` gives a `Plan`), and only the edge file `internal/Canvas.scala` touches the jatatui buffers ([ADR 0006](/decisions/0006-purity-edge-files-and-lint)).
- Bad: jatatui is 0.x with one maintainer. Its JNI copy leaves one 904 KB `/tmp/jni-*` folder per start; tui4s deletes it on Linux through `/proc/self/maps` ([ADR 0019](/decisions/0019-upstream-kyo-issues-as-drafts)).
- Bad: the JNI cuts a typed character outside the Basic Multilingual Plane to 16 bits. A bracketed paste keeps it.
- Bad: the native library ships for macOS (arm64, x86_64), Linux (x86_64, arm64, glibc 2.34 or later) and Windows x86_64 only. There is no musl or Windows arm64 build.

## References

- Decision log: second round (2026-09-30).
- Plan: D-12, D-13. Research reports on tui-scala and jatatui of 2026-09-30 (in the notes; the facts above are copied from them).
- AGENTS.md: "Rules for code" (one fiber, the input thread, the JVM flag, no tui-scala).
