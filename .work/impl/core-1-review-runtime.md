# Review of "core: model and view (C1 + C2)": runtime, concurrency and performance lens

Status: complete (read-only review, no build run).

## runtime-1 (major) Stateful slot keyed by the paint class gives a kept state to the wrong paint

- File: modules/core/src/main/scala/tui4s/internal/Painter.scala:138-141 (and dsl.scala:152-158)
- The slot tag of a `View.Stateful` is `paint.getClass`. A lambda has one class per call
  site, not per state type. A generic helper such as
  `def statefulWidget[St](w: StatefulWidget[St], fresh: => St) = stateful(fresh)((a, b, st) => w.render(a, b, st))`
  makes every node it builds share one paint class.
- Scenario: `column(tabs(..., s.tab).size(1), if s.tab == 0 then statefulWidget(scrollbar, ScrollbarState(100)) else statefulWidget(jlist, ListState.empty()))`.
  Frame 1 keeps a ScrollbarState at position 33. Press Right: frame 2 finds the slot with the
  same tag, passes the ScrollbarState to `List.render(area, buf, ListState)`, the bridge
  method throws ClassCastException, the run ends with AppFailed("paint").
- The declared deviation says "a kept state never reaches the wrong paint"; that is false for
  this case.
- Fix: put the state type in the identity, for example `stateful[St: ClassTag]` and a tag of
  (paint class, runtimeClass), or check `slot.state.getClass` against the class of a fresh state
  when the tags match. If the signature must stay as in SPEC 2.7, document the limit and drop
  the claim.

## runtime-2 (minor) A stateful node never equals itself across view builds, so level 2 never skips

- File: modules/core/src/main/scala/tui4s/dsl.scala:155-158, View.scala:283-286
- `dsl.stateful` stores `() => fresh`, a new closure on every call. Case-class equality
  compares it by reference, so two `View.Stateful` from two `view` calls are never equal, even
  when the user hoists the paint function. The Scaladoc says "Equality is by function reference".
- Scenario: `view(s) = column(text("cpu"), stateful(ScrollbarState(10))(paintBar))` with
  `paintBar` a `val`; an update that changes a field the view does not show. Expected (doc):
  `skippedEqualView += 1`, no frame. Actual: a full frame on every change (each Dashboard tick).
- Fix: override `equals`/`hashCode` in `View.Stateful` to compare `paint` only (`fresh` is used
  only for a new slot, which happens only on a draw).

## runtime-3 (minor) Widget-state positions collide: more than 31 children, or a repeated .id

- File: modules/core/src/main/scala/tui4s/internal/Painter.scala:401-408, 105-114
- `childPosition(p, i) = 31p + i + 1`, so child 31 of p equals child 0 of p + 1. `.id(name)`
  replaces the position with a value of `name.hashCode` only, so a reusable component with a
  fixed id used twice (or ids "Aa" / "BB") shares one slot.
- Scenario: `row(column(<31 text rows>, list(a, selA)), column(list(b, selB)))`: both lists are
  at position 1024 and share one ListState. Each frame list B overwrites the offset that list A
  keeps, so A's scroll jumps (selection 40 in window 31..50 becomes window 21..40). With two
  different kinds at one position, the slot is replaced twice per frame and no state survives.
- Fix: detect a slot that is visited twice in one frame (`visited` already true, or a replaced
  slot that was visited this frame) and derive a distinct position (mix in the parent position
  and the visit count), or fail fast in tests (dev check like Keymap duplicates). Use a 64-bit
  mixer (e.g. `(p * 0x9E3779B97F4A7C15L) ^ (i + 1)` then a murmur finalizer) instead of `31p + i + 1`.

## runtime-4 (minor) drawIfNeeded reports a terminal I/O failure as AppFailed("paint")

- File: modules/core/src/main/scala/tui4s/internal/Engine.scala:140-152, 172-173
- `drawIfNeeded` wraps the whole `draw(view)` (user escape-hatch code AND `Terminal.draw`:
  diff, backend draw, cursor, flush) in one `catch NonFatal => fail("paint", t)`, and `fail`
  always builds `AppFailed`. SPEC 5.6 / 5.12: jatatui IOException and crossterm
  RuntimeException("IO error ...") must become `TuiError.TerminalIO`.
- Scenario (C4/C5 reuse this Engine): the SSH session drops, crossterm flush throws
  "IO error: Broken pipe"; `Tui.run` fails with `AppFailed("paint", ...)`, the app class
  prints "paint threw" and blames user code. C4 must change this internal API instead of
  extending it.
- Fix: catch user code only where it runs (Widget / Stateful / Draw in the Painter) and mark
  it (for example a private `UserPaintFailed(cause)`), map that to AppFailed("paint"), and map
  IOException / other exceptions from the terminal to `TerminalIO` (or let them propagate).

## runtime-5 (minor) kyo-test runs in the sbt JVM: each test run leaks a Kyo scheduler

- File: modules/core/build.sbt:12 (no `Test / fork`)
- sbt 1.13 uses `ClassLoaderLayeringStrategy.ScalaLibrary` (turbo is off), so Kyo is loaded
  in a fresh class loader for each `core/test`. `kyo.scheduler.Scheduler.get` is a per-loader
  singleton whose daemon timer and clock threads run until `shutdown()`, and neither Kyo nor
  kyo-test-runner calls it (checked: no reference in the runner jar).
- Scenario: an sbt shell, `~core/test` watch mode, or a Metals BSP sbt server. Each run leaves
  one more idle scheduler (SPEC 5.14: ~13% of a core each with default flags) plus its threads
  and its class loader (metaspace). After 10 runs about 1.3 cores stay busy, on the 8-CPU Mac
  that already hit a load average above 100. `sbt -batch` is not affected (the JVM exits).
- Fix: `Test / fork := true` in modules/core/build.sbt (also isolates the later C5 tests that
  set JVM-global system properties and replace System.out), optionally with the 5.14 scheduler
  flags in `Test / javaOptions`.

Status: done. 5 findings (1 major, 4 minor).
