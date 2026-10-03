# core-2: sources, engine, render loop and live test kit (C3 + C4) - implementation notes

Worktree: .work/worktrees/core, branch feat/core-2-engine (on top of feat/core-1-model-view 850a83c).
Agent: implementer. Started 2026-10-01.

## Plan (first draft, refined below)

1. Read PR 3 code (Source, Engine, TuiTest, Painter) and the skeleton (core/tui4s/{Source,Tui}, internal/{Engine,
   Supervisor,Runner,Runtime,Items,Macros}, spi/, testkit/).
2. C3 main: Source (full private ctor: label, body, fold, kind, recover, keyed), Source.For, Dispatch,
   NotForeign + internal Macros, CallSite, spi.Sources/Emitter, Items (Fold/Done/Probe/Failed), Supervisor
   (Starter abstraction: recording starter for TuiTest, fiber starter for the loop), Engine.fold over Item
   (rules of 2.13 + trace line), TuiTest complete (sources/started/emit/fail/fold/lastTrace/trace/viewTree).
3. C3 tests: SourceTest (diff, #n, current fold, keyed, recover, quit from fold, quit+tasks), GuardTest,
   Dashboard fixture + DashboardTest sync, Todo fixture with load/save sources, TodoTest sync, trace format.
4. C4 main: RenderLoop, real Supervisor fibers, SignalRef watcher, TraceWriter + TuiTest.replay, InputThread
   over spi.InputPort, ScriptedInput, spi.Headless, TerminalControl seam + RecordingControl, Tui.runWith,
   TuiTest.live/liveBlocking/LiveTest.
5. C4 tests: FrameContract C4-C9, RestoreOrderTest, live Dashboard, watcher 10k writes / 4 fibers, slow line,
   SupervisorTest (10k tasks -> empty maps), replay.
6. fmt + check, commits.

## Progress
- 13:20 read SPEC 2.8-2.13, 4.1-4.4, 5, 6, 7.2, 8.2, 9, 10; VALIDATION; PR 3 code (Source, Engine, TuiTest,
  Painter, NextTest uses new Source(...)/takePending: to be rewritten); skeleton (Source, Items, Macros,
  Engine, Supervisor+Launcher, Runner/RenderLoop, Runtime (HeadlessControl/InputThread), spi, testkit).
  Kyo facts checked in sources: TimeControl.awaitPendingSleepers counts !done queue entries; advance(d, 0)
  does executor.schedule(0); Channel.putBatch is not atomic for a concurrent drain; close() returns the
  leftover items asynchronously; SignalRef set-then-swap protocol as SPEC 5.8; Promise.Unsafe.init[A, S]().
  kyo-test: Test[Any], `override def config` (RunConfig.parallelism), typeCheckFailure(code)(msg), intercept.
  Design choices so far:
  * TerminalControl seam = enter(opts): Backend / leave(opts) / step(name); Session (generic) owns the
    input thread + inbox close; RecordingControl (testkit, private[tui4s]) logs enter, input-started,
    input-stopped, input-joined, leave-raw-mode. C5 plugs a crossterm control + CrosstermInput.
  * Supervisor = bookkeeping only (toStart/toStop); TuiTest records, Launcher starts with Fiber.initUnscoped
    (Locals kept: time control, facade ambient runtimes). A start cancelled in the same batch is skipped.
  * Probe(promise, untilIdle): settle's probe stays until no `task` body runs (standing or one-shot).
  * Observer hook on the render fiber (TuiHandle states/stats, live kit screen copy); zero cost without it.
  * One trace line per draw decision (a paced second drain joins the record).
- 13:25 main sources of C3 + C4 written and compiling (0 warnings after one unused import):
  Source.scala (Source full ctor, Kind, constructors, For, Dispatch, NotForeign, CallSite), Tui.scala (Tui.runWith /
  startWith seams, TuiHandle), spi/{Sources (+Emitter), InputPort, Headless}, internal/{Items, Macros, Watch,
  Supervisor (+Launcher, TokenEmitter), Engine (Item fold, trace record, slow lines, probes), Trace (Tracer,
  MemoryTrace, TraceWriter, Json, EventCode encode/decode), Session (TerminalControl, HeadlessControl, Session,
  InputThread), RenderLoop (Observer, RenderLoop, Runner, HandleObserver)}, testkit/{TuiTest (complete + replay +
  live/liveBlocking), LiveTest (+LiveView, LiveRun), ScriptedInput, RecordingControl, ViewTree}.
  Next: fixtures (Todo with load/save, Dashboard), tests, fix NextTest (old Source ctor / takePending).
- 13:41 COMMIT a94d654 "core: sources, tasks and the engine fold of every inbox item (C3)" (C3 main + tests:
  SourceTest, EngineTest, GuardTest, TraceTest, DashboardTest sync, TodoTest with load/save, CounterTest trace,
  NextTest ported). Verified alone (stash of C4 files): core/test green. Incremental-compile check of 15 files
  in package tui4s: found a NEW cycle `dsl.scala` `import scala.reflect.ClassTag` (package + dsl recompiled
  together) -> fixed with `_root_.scala...`; Keymap/TextField imports also `_root_`. All 15 touch-compiles pass.
  C4 files (Tui, RenderLoop, Session, Headless, InputPort, LiveTest, RecordingControl, ScriptedInput,
  TuiTest.live) remain uncommitted; next: C4 tests.
- 13:52 C4 tests written: RuntimeTest (run seam, C8 restore order, handle, Ctrl-C, SourceFailed, AppFailed, input
  failure, Dashboard real time, trace file + replay), RuntimeContractTest (C4, C5 with captured emitters, C6 with
  an Observer on the supervisor, C7, C9, frame cap burst, slow line in the trace file), WatcherTest (10 rounds of
  10 000 writes from 4 fibers; current value first; equal write silent), LiveKitTest (Dashboard 5 samples/s,
  250 ms steps, Counter frames, Todo settle waits for load and save tasks, failure propagation, liveBlocking
  from a platform thread). Fixes found by them: probes after a quit in the same batch were lost (settle hung) ->
  kept; observer.ended before probes complete (quit visible after settle); probe-only batches (settle) are not
  counted as batches / skips. All 22 runtime leaves green.
- 13:55 `sbt -batch fmt` + `sbt -batch check` green (24 suites, ~180 leaves, versionPolicyCheck ok).
  COMMIT dae8e81 "core: render loop, supervisor fibers, run seam and live test kit (C4)".
  Next: core/doc link check, review of the diff against SPEC, stress-run the live/runtime suites a few times.
- 14:03 core/doc: no link warnings. Stress: core/test x3 green (load ~6.5). Added an inline-viewport test on the
  run seam (untested branch of Session.open). `sbt -batch fmt` + `sbt -batch check` green: 24 suites, 180 leaves,
  versionPolicyCheck ok. COMMIT 8b952d8 "test(core): an inline viewport on the run seam draws in its rows". DONE.

## Commits on feat/core-2-engine
- a94d654 core: sources, tasks and the engine fold of every inbox item (C3)
- 504939d core: render loop, supervisor fibers, run seam and live test kit (C4)
- 8b952d8 test(core): an inline viewport on the run seam draws in its rows

## Deviations from SPEC (also in the structured result)
- Supervisor = bookkeeping only (plans toStart/toStop); Launcher starts with Fiber.initUnscoped, interrupts,
  stopAll. TuiTest is the "recording starter" (it records the plan). A start stopped in the same step is skipped.
- Probe(promise, untilIdle). settle waits while ANY `task` body runs (standing or one-shot), not only one-shot
  tasks: a standing load task (Todo) is folded after settle. Probe-only batches are not counted (batches,
  skippedSame) and write no trace line. Probes after a quit in the same batch are still released.
- Done.result: Result[Throwable, Any]; an extra Stop item (TuiHandle.stop).
- Seam: TerminalControl(enter(opts): Backend, step(name), resize(w, h), leave(opts)); Session owns the input
  thread, the inbox close and the release order; Tui.runWith / Tui.startWith (private[tui4s]); object Tui is
  public with no public member until C5. RecordingControl(width, height) private[tui4s] in tui4s.testkit.
- Runner.startUnscoped: TuiHandle[S] < Async (waits for the first frame so states starts at init); when the app
  fails before its first frame, states holds null.
- Trace: one line per draw decision (a paced drain joins the record); optional fields only when non-empty; frame
  codes drawn/skip-same/skip-equal-view/skip-quit/skip-failed; quit reasons update/fold/ctrl-c/stop/error/
  interrupted (+ "error" message); slow lines for the fold (slowest item), "view" and "sources"; budget 1/fps
  (1/60 when fps <= 0). File truncated per run; written out after 100 ms, before the render fiber parks, and at
  the release; a trace file that cannot be opened or written is skipped silently.
- WorkOnly batches count as skippedSame (the state is the same object).
- TuiTest.emit: one element for a stream source; value + Done in one batch for a task; emit/fail/fold after the
  end are ignored; the error lists the running labels.
- LiveTest.send uses one putBatch: usually one batch, not guaranteed (Kyo batch puts are not atomic for a drain).
- Live harness: no input thread, RecordingControl backend, a settle before `test`, stop after (+ interrupt as a
  safety net); advance re-reads the sleeper count each step (0 after the end).
- ScriptedInput maps keys like a terminal (Key.sent); ScriptedInput.never added (= empty).
- CallSite equals/hashCode; NotForeign message names tui4s.interop.zio / .cats and a per-type fix.
- HeadlessControl resizes a TestBackend on a folded Resize.
- `_root_` imports for every import in package-tui4s files (dsl.scala ClassTag cycled when package.scala and
  dsl.scala recompiled together).
- EventCode decode: "↑" reads as Key.Up, "shift+tab" as Key.BackTab (Key.show is not injective).
- jatatui IOException while drawing -> TuiError.TerminalIO (crossterm's RuntimeException mapping: C5).
