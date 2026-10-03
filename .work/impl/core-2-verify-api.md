# core-2 verify (lens api)

Skeptic pass on the api-lens findings for the C4 PR (worktree .work/worktrees/core, HEAD 8b952d8).
Verdicts are added as each one is traced.

## api-1 - confirmed
LiveRun.apply (LiveTest.scala:186-189): `handle.stop.andThen(Abort.get(result))`; TuiHandle.stop (Tui.scala:71-72)
is `put(Stop)` (Closed after the end) then `fiber.getResult ... .unit`, so the run's Failure(AppFailed) is dropped.
Engine.fold (Engine.scala:174-181) records AppFailed for a throwing update -> Step.Failed -> RenderLoop.failed ->
the ensure in Runner.runOn closes the inbox and calls LiveView.ended (quit = true). Later press/settle put into a
closed inbox and are dropped (Abort.run[Closed]); screen keeps the last drawn frame ("Count: 1"). LiveTest has no
error accessor. SPEC 2.10 does not list `error` on LiveTest, but it does not say that live hides app failures, and
`live` already has Abort[Throwable]; adding the accessor / failing live is additive, not a contradiction.

## api-2 - confirmed
Supervisor.busyTasks (Supervisor.scala:173-178) counts every Kind.Task in liveMap (standing and one-shot);
Engine.completeProbes (Engine.scala:438-447) keeps an untilIdle probe while busyTasks > 0; settle always uses
untilIdle = true (LiveTest.scala:123). Async.sleep = Clock.sleep (kyo Async.scala:161-163); task fibers are
Fiber.initUnscoped, so they keep the controlled clock; Clock.withTimeControl never advances by itself.
(a) A standing `source.task(Async.sleep(2.seconds)...)` in sources(init): the settle before `test`
(LiveTest.scala:187) is held forever; the test body never runs. This comes from the declared deviation (SPEC 6.2
says one-shot tasks only).
(b) A one-shot task that sleeps 300 ms, started by a periodic fold at 1000 ms: the next per-step settle
(LiveTest.scala:145) is held until the task ends, and the task ends only after the next control.advance, which
runs after settle returns -> deadlock. The SPEC 6.2 step algorithm has the same deadlock, so this is a real gap,
not a contradiction (the "parked on the controlled clock counts as idle" fix stays inside SPEC).

## api-3 - confirmed
Runner.startUnscoped (RenderLoop.scala:270-276) starts the run with Fiber.initUnscoped ("Deliberately
unparented", kyo Fiber.scala:177-192) and parks in obs.ready.safe.get. Runner.start registers Scope.ensure(h.stop)
only after the handle returns (290-291); LiveRun registers Sync.ensure(handle.kill) only after it returns
(LiveTest.scala:184-186). Async.timeout runs the body in a child fiber and interrupts it (kyo Async.scala:195-214);
the interrupt wakes the parked get, no finalizer exists yet, and the run fiber (plus the tui4s-input thread for
Headless) keeps running. The window is the time to the first frame: narrow, but reachable, and it breaks the
"end of the scope stops it" contract of Headless.start.

## api-4 - confirmed (with corrections)
Reachable now: Headless.start with an app whose init throws -> RenderLoop.start fails before published() ->
fiber ends -> onComplete -> obs.release() -> a TuiHandle is returned whose observer.states is null; TuiHandle.states
(Tui.scala:56-62) builds a NEW SignalRef(null) per call. `current` gives null typed as S (NPE in a `.map(_.count)`),
`next` never completes. SPEC 2.9 says states is a Signal[S] that starts at init, so a null is outside the SPEC.
Corrections: (1) the facade hang is not specific to this case: kyo Signal.next waits for a change, so
`ZStream.repeatZIO(core.states.next)` also hangs after any app end; (2) the primary fix (`TuiHandle[S] < (Async &
Abort[TuiError])`) changes the SPEC 2.9 / 2.11 signatures of Tui.start and Headless.start; a fix that keeps them
must panic with the TuiError or keep the documented null. Severity: minor-to-major, declared deviation.

## api-5 - confirmed
LiveRun.typeText (LiveTest.scala:116-117) puts TuiTest.typed(text) items with no Key.sent; TuiTest.typed maps a
one-char grapheme "\n" to Key.Char('\n'). TuiTest.typeText goes through input (TuiTest.scala:239-251), which maps
Key.Char('\n') to Key.Enter (Event.scala:81-87). Nothing in Engine maps keys. Todo's inputKeys binds Key.Enter
only; Key.Char('\n') misses the keymap and TextField.edit ignores ISO control chars (TextField.scala:45-47), so no
item is added in live while TuiTest adds it. (Small correction: the char does not go into the field; it is
ignored.)

## api-6 - confirmed
TuiTest.emit sets engine.rethrowClassCast = true (TuiTest.scala:192); Engine.fold catches
`ClassCastException if rethrowClassCast` around foldValue (Engine.scala:217-222), which also wraps the user's fold
body, and rethrows it; emit turns any CCE into "does not fit the fold". A CCE thrown inside the fold body for a
value of the right type is reported as a type mismatch, error stays None, and the counts are undone. SPEC 6.2
says user code that throws is recorded as AppFailed, so the finding follows the SPEC. Minor.

## api-7 - refuted
EventCode (Trace.scala:186, 324-345) drops Mouse.mods, as the finding says, but this is the event-code format
that SPEC 5.16 fixes: `mouse:<kind>:<button>:<x>,<y>` (no modifiers). The code implements the SPEC format
verbatim; adding a modifier suffix is a SPEC change (worth proposing), not a defect of this PR. No mouse source
with modifiers exists in C4 (EventMapping is C5).

## api-8 - refuted
TuiTest.replay (TuiTest.scala:313-333) uses its width/height arguments; SPEC 2.10 gives exactly this signature,
`replay(app, trace, width: Int = 80, height: Int = 24)`, and says nothing about reading the start line. The
caller can pass the recorded size. The proposed sentinel defaults change the SPEC'd default; a Scaladoc note that
the size must match the recording would be a doc nicety, not a defect fix.

## api-9 - refuted
The coverage gap is real (GuardTest tests zio.ZIO and Future only), but no failure is reachable: the table names
in Macros.scala:9-16 are the true full names (zio.ZIO, zio.stream.ZStream, cats.effect.IO, fs2.Stream,
scala.concurrent.Future), and SPEC 6.3's GuardTest requires only the zio.ZIO case (the PR already adds Future).
The scenario needs a typo that does not exist. Extra stand-ins are a nice-to-have.

## api-10 - refuted
No failure is reachable in this PR: the crossterm control is C5. TerminalControl is private[tui4s]; C5 can add
the viewport to leave without touching the public API, and the facades' runWith only passes a TerminalControl
value (they implement none), so "the facades must change" is wrong. "Cannot" is also too strong: the control
creates the backend in enter and can observe the inline area through it (cursor query, appendLines, draws). A
good note for C5 (pass terminal.getFrame().area() to leave), not a defect here.
