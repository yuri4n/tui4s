# core-2 verify (lens: spec)

Skeptic pass over the spec-lens findings. Worktree: .work/worktrees/core (HEAD 8b952d8).
Status: in progress. Each verdict is added when it is decided.

## spec-1: confirmed (with a SPEC nuance)
- Engine.completeProbes keeps an untilIdle probe while sup.busyTasks > 0; busyTasks counts every live
  generation of Kind.Task, standing or one-shot (Supervisor.scala:173-178). Under Clock.withTimeControl a task
  that calls Async.sleep parks; nothing else reaches the inbox, so the probe is never completed.
- Standing task that sleeps: LiveRun.apply runs `run.settle` before `test` (LiveTest.scala:186-188) -> hangs
  before the test body. This is caused by the implementation's own deviation (wait for standing tasks too);
  SPEC 6.2/2.10 wait only for one-shot tasks, so under SPEC this case works with advance(d, sleepers = 1).
  LiveTest.advance's own scaladoc says "pass it for ... a task that sleeps", which cannot work.
- One-shot toast scenario: press hangs. NOTE: SPEC 6.2 literally says settle "repeats while a one-shot task is
  running. A task that never ends makes settle wait", so this half is SPEC-prescribed; fixing it needs a SPEC
  change (SPEC 2.10 advance "fires every sleeper due within d" conflicts with it).

## spec-2: confirmed
- TuiHandle.stop = Abort.run[Closed](inbox.put(Stop)).andThen(fiber.getResult).unit (Tui.scala:71-72): the
  run's Result is dropped. LiveRun.apply then returns Abort.get(result) of the test only. An AppFailed /
  SourceFailed run ends with completeAllProbes + closed inbox, so later settle/advance return at once and the
  live run succeeds. Only `t.quit == true` shows that the app ended; nothing shows why. SPEC is silent on the
  live run's result; LiveTest in SPEC 2.10 has no `error` (so that half is an API addition, not a SPEC breach),
  but the SPEC principle "recorded, not thrown, readable in the test" (6.2) is not met by the live kit.

## spec-3: confirmed (minor)
- Quit(last) = Next.Then(last, Nil, quit = true) (App.scala:103-105); applyNext sets engine.state = last.
  RenderLoop Step.Quit -> Loop.done(engine.state) (lines 51, 141) with no observer.published. In Runner.runOn's
  ensure, observer.ended -> HandleObserver.ended / LiveView.ended copy stats only. So states / LiveTest.state
  never show `last` (nor an earlier change in the same quitting batch). SPEC 2.9 "the state after each batch";
  "Quit draws no final frame" (5.6) is about the frame, not the state.

## spec-4: confirmed (minor)
- Supervisor.sync keeps kept entries in place in the LinkedHashMap and appends new labels; `declared` iterates
  the map. [cpu, logs] -> [logs] -> [cpu, logs] gives declared = [logs, cpu]. SPEC 6.2: "the labels the current
  state declares, after the diff, in order". Fixture Dashboard declares [logs, cpu] / [logs], so the tests do
  not see it.

## spec-5: confirmed (minor, narrow window)
- Launcher.startOne: Fiber.initUnscoped(body).map { fiber => Sync.Unsafe.defer { r.fiber = fiber; onComplete } }.
  In Kyo RC7 an interrupt of a running fiber is a Safepoint stop seen at the next poll; the remainder is
  dropped after the finalizers. Kyo's own Fiber.use comment (kyo-core RC7 Fiber.scala:167-168) names this
  exact window: "in a mapped function a window opens where the child runs with nothing to stop it".
  stopAll -> stopEverything marks r stopped, interruptPlanned skips r.fiber == null. initUnscoped is
  deliberately unparented. TokenEmitter.apply swallows Closed, so `every` / `poll` loop forever after the
  release. SPEC 5.8: stopAll "interrupts every fiber".

## spec-6: confirmed (minor; SPEC 5.3 sketch has the same bug, SPEC 5.12 contract is broken)
- InputThread catch: inbox.unsafe.offer(Failed(...)); Kyo RC7 buffered Channel.offer = queue.offer, which
  returns Success(false) when full (Channel.scala:735-739). The Failed item is dropped and the thread ends.
  Nothing else reports the failure until the release. The inbox can be full: emitters put with backpressure
  and the render fiber drains only between batches. For a TTY that went away a later draw would usually fail
  with TerminalIO anyway; for a read/mapping failure on a live terminal the app silently loses all input.
  The fix departs from the 5.3 code listing in favour of the 5.12 error table.

## spec-7: confirmed
- LiveRun.typeText puts TuiTest.typed(text) events with put(e), no Key.sent; press and send map Key.sent, and
  TuiTest.input maps every Key through Key.sent (TuiTest.scala:239-250, decided in core-1 fix a89bb84 / api-3).
  typed("milk\n") -> Key.Char('\n'); Keymap rejects binding control Key.Char; TextField.edit drops ISO control
  chars (TextField.scala:45-47). So Enter/Tab/Esc never arrive in the live kit. LiveTest's doc says "as
  TuiTest.typeText".

Status: done. 7 confirmed (spec-1 with the SPEC 6.2 nuance), 0 refuted, 0 uncertain.
