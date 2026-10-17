# Skill review of the purity plan (Opus agent, 2026-10-17), condensed

Verdict: direction right; four changes.

Amendments:
- A1 (P3, M): synchronous release STACK (ReleaseStack: Open(List[Entry])/Closed under its own monitor; push(step)(release) < Sync runs release at once on a closed stack; runAll LIFO, idempotent, catches all but VirtualMachineError, re-sets interrupt, no Kyo scheduler). Each acquire = one Sync.defer { acquire; push(release) }. Push order: hook remove, sink.finish, capture.stop+R3 warning, raw mode off, leave modes+flush, input stop/inbox close/join, canvas.close (pushed last, runs first = G1). Deletes Release.all, Acquire/Undo tuples, Entered AtomicRef, var undo, Session.safely, 2 of 3 sink.finish sites. ~50 lines + one model test (random failure step k, concurrent runAll: every release exactly once, reverse order). Reason: Kyo RC7 Finalizer.close only spawns the drain fiber (Scope.scala:346-383), so a JVM hook cannot run Scope finalizers.
- A2 (P3, S): one hook per run = first stack entry: set stopping, interrupt run fiber (no wait), runAll(), wait <= 2 s. Deletes tui4s-stop hook (TuiApp.scala:160), restored latch (:109), probe hook (TerminalIO.scala:36-45). PTY rows 143/130 decide.
- A3 (P0, S): property tests with io.getkyo %% kyo-test-prop % Versions.kyo % Test (VERIFIED on Maven Central: kyo/test/prop/{PropertyTest,Gen,Shrink}). Explicit Gen values. No ScalaCheck. The skill row in effect-systems.md names the wrong artifact (kyo-test-runner) -> fix.
- A4 (P1b, S): TuiError extends Exception(message, cause) with NoStackTrace; non-null cause; Scaladoc taxonomy (4 cases infra in Abort[TuiError]; app failures pass through; panics caught once in TuiApp.report; edge converts native exceptions).
- A5 (P1b, M): final public shapes: Chunk for TuiApp.args, Headless.typeText/paste, SchedulerDefaults.defaults; Headless.key(k, mods: Modifiers); FrameStats.focus: Option[String]; CellColor enum; Hooks return < (Sync & Scope) (compatible: Async <: Sync); deprecate useSignal; TabsView(first, rest*).
- A6 (P2): opaque Loc = Vector[String] with child(i)/content/item(key). Not cells/columns/Rect.
- A7 (P2, S): Gauge Open(Set)/Closed in one AtomicRef; add on Closed interrupts at once; interruptAll = getAndSet(Closed); spawn+register in one Sync step.
- A8: ADT where a Boolean selects runtime behavior (settleAndVerify input, important, submits, wrap, readOnly); keep option toggles/predicates. One line in AGENTS.md.
- A9: old painter kept in test sources as permanent oracle (~40-line buffer shim); Layout perf PRs use today's Layout as oracle.
- A10 (P1, S): validate TuiOptions once (normalized view); delete 4 clamps (Tui.scala:143, Headless.scala:316, Session.scala:194, Runner.scala:572).
- A11: no new capability traits; Kyo Clock is the time capability.

Cuts: no purity-check.sh/ratchet/TODO headers; scalafix DisableSyntax only, file-level `// scalafix:off DisableSyntax // EDGE: <API>` in edge files + one CI grep; no 9 git mv into internal.edge; kernels only Canvas.write and Canvas.diff; P7 out of this rewrite (2 casts at Runner.scala:362/:379 hold by construction, comment them); PR checklist 4 lines.

Wrong verdicts: kyo-test RC7 property API exists (kyo-test-prop); Session.close one-function = violates principle 10 -> REPLACE (A1); Session.open -> REPLACE; LineSplit not a kernel; "P6 riskiest, skippable" -> with A1 merging hooks is cheap; Gauge incomplete without closed state.

Final phases: P0 ~120 | P1 ~750 | P1b ~400 | P2 ~650 (skeptic) | P3 ~1,000 (skeptic, 1 PTY) | P4 ~770 (skeptic for Headless) | P5 ~1,250 (skeptic for Canvas monitor) | P6 sinks only ~500 (1 PTY) | total ~5,440.
