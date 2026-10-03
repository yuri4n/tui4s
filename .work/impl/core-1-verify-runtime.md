# core-1 verify: runtime lens (skeptic pass)

Worktree: /Users/yuri4n/Developer/projects/tui4s/.work/worktrees/core (HEAD c30282b)
Method: read code only; no builds, no edits. Jar sources read from the Coursier cache.

## Verdicts

### runtime-1: CONFIRMED
- Painter.scala:141 tags the slot with `paint.getClass`; slot() (105-114) returns the kept state when
  `s.tag eq tag`, with an unchecked `asInstanceOf`. No check of the state type anywhere.
- dsl.stateful (152-158) passes the user's lambda as it is (cast only), so the tag is the class of the
  user's lambda. A JVM/LMF lambda has one class per call site, so a generic helper gives one tag for
  every state type.
- jatatui 0.31.0: `StatefulWidget<State>` is generic; `List implements StatefulWidget<ListState>`;
  javap shows the bridge `render(Rect, Buffer, Object)`, which casts to ListState -> ClassCastException
  for a kept ScrollbarState.
- Position check: column = 1, child 1 = 1*31+1+1 = 33. tabs change -> view != lastView -> frame ->
  same tag at 33 -> wrong state -> CCE -> drawIfNeeded catch -> AppFailed("paint").
- Minor nit: jatatui has `ScrollbarState.of(100)`, not `ScrollbarState(100)`. Does not change the result.
- SPEC 5.7 keys Stateful slots by node kind only (weaker). The impl claim at dsl.scala:153-154 is false
  for generic helpers. Fix: ClassTag in the signature changes SPEC 2.7; the cheap fix is to document the
  limit and drop the claim.

### runtime-2: REFUTED
- The premise "`() => fresh` is a new closure on each call" is false in the compiled code. javap of
  modules/core/target/scala-3.9.0/classes/tui4s/dsl$.class (built 11:23, after the last dsl.scala edit
  at 11:20): `stateful` is `aload_1; aload_2; invokevirtual View$Stateful$.apply`. Scala 3 passes the
  by-name thunk through; there is no wrapper closure. The `package$` export forwarder passes it through too.
- At the call site (PainterTest `stateful(AtomicInteger())(counter)`), the thunk is
  `invokedynamic apply:()Lscala/Function0;` with no captured argument, so LambdaMetafactory gives one
  constant instance per call site. With a hoisted `paint` val, both fields are the same references in
  each view build, so `View.Stateful` is `==` and level 2 skips. The finding's own scenario
  (`stateful(ScrollbarState(10))(paintBar)`) is non-capturing, so it gets skippedEqualView += 1.
- Only a capturing `fresh` (for example `ScrollbarState.of(s.n)`) gives a new reference each build;
  SPEC 5.6 accepts that ("by reference for functions: correct, only slower").

### runtime-5: CONFIRMED (minor; developer machines only)
- modules/core/build.sbt (new in this PR) adds kyo-test-runner with no `Test / fork`. The root build.sbt,
  project/*.scala and ~/.sbt do not set fork or turbo.
- sbt 1.13.0 Defaults.scala:2652-2654: `classLoaderLayeringStrategy` = ScalaLibrary when turbo is off.
  ClassLoaders.buildLayers: with ScalaLibrary, `layerDependencies` is false, so Kyo and jatatui go into
  a new LayeredClassLoader for each test run.
- kyo-scheduler RC7: `object Scheduler { val get = new Scheduler() }`, one per class loader; the cycle task
  loops with parkNanos until `shutdown()`; the threads are daemons (util/Threads.scala).
- kyo-test-runner RC7: no bytecode calls `shutdown`. LeafPool's own doc says its workers "outlive any single
  suite" and that "no shutdown mechanism is needed", so it relies on the JVM exit.
- Correction to the finding: the runner does reference `Scheduler.get` (LeakCheck$ reads loadAvg,
  busyWorkers, status). It never shuts the scheduler down, so the conclusion holds.
- Does not conflict with SPEC 6.4 / Q4 (kyo-test stays). `Test / fork := true` fixes it.

### runtime-3: CONFIRMED (minor; a SPEC 5.7 formula defect that the impl copies literally)
- Painter.scala:401-402 `parent * 31 + index + 1`. Stack and Layers call walk() for each child with
  childPosition(pos, i), also for a zero-size area, so the index always counts.
- Arithmetic: row = 1, column A = 32, column B = 33; list A = 32*31+31+1 = 1024;
  list B = 33*31+0+1 = 1024. With indexes 0..30 the map is bijective base-31 (unique); index >= 31 collides
  with child i-31 of the next sibling (p+1), and so do all their descendants on the same path.
- slot() (105-114) returns the same ListState to both (same ListTag); nothing detects a second visit.
- jatatui List.render/getItemsBounds: from B's offset 0 with selected 40 and height 20, the window
  becomes 21..40 and `state.setOffset(21)`; B then writes its own offset. So A's kept offset is lost in
  each frame, as the finding says. Needs a terminal tall enough for both lists to get rows
  (for example 31 `.size(1)` rows + a 20-row list).
- `idPosition` uses String.hashCode: "Aa" and "BB" are both 2112, so they share a slot. A repeated
  `.id("files")` sharing one state is arguably by design (same identity means same state), so that subcase
  is weaker.
- No SPEC user decision is affected. The fix changes the SPEC 5.7 formula, or adds a dev check for a
  second visit.

### runtime-4: REFUTED
- In this PR the only caller of Engine.drawIfNeeded is testkit/TuiTest.scala:130. Its draw function
  runs Terminal.draw on CountingBackend(TestBackend), which does no I/O. So a crossterm "IO error"
  cannot reach drawIfNeeded in this PR, and today every exception from `draw` is user escape-hatch
  code or a jatatui bug, for which AppFailed("paint") is right.
- The real terminal and its error mapping are C5 work (SPEC 8.2: TerminalIO, CrosstermInput). Engine is
  private[tui4s] and provisional: its own doc says C3 widens it. C5 supplies its own `draw` function and
  can map IOException there, or change this internal class. "They cannot only extend it" is a design
  note for C5, not a failure in this PR.
- To keep for C5: drawIfNeeded's single catch would map I/O errors to AppFailed if C5 throws them
  through it.

## Summary
runtime-1 confirmed; runtime-2 refuted (bytecode: no wrapper; the non-capturing thunk is a singleton);
runtime-3 confirmed (SPEC formula); runtime-4 refuted (cannot happen before C5); runtime-5 confirmed.
