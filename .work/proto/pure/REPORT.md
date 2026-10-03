# Prototype "pure": type-member components with pure handlers

Status: IN PROGRESS (2026-10-02). Author: prototype agent, variant 1.

## Plan (written at the start; updated as I go)

- Own `tui4s.Signal[A]`: a pure description of a value derived from component states (map, zip, const).
  Components hold no Kyo types; a Signal is evaluated by the runtime against an immutable store snapshot.
- `abstract class Component extends View { type State; type Out; def initial; def view(state: Signal[State]): View;
  def keys: PartialFunction[Key, Update]; def start: Update; def sources: Seq[Feed] }`
  `type Update = Sample ?=> State => Next[State, Out]` (pure; `signal.now` reads the event's snapshot).
- `Next[S, O]`: new state + emitted outs + tasks to start + quit.
- `on(child)(out => update)` in a parent's view: dependent method type on `child.Out`.
- `Task[A]` (one-shot effect) and `Source[A]` (stream of values): the only places with effect types.
- Runtime: mount the view ONCE into regions (leaf widgets bound to signals). One event -> apply pure
  handlers (bubbling, outs up the tree) -> re-evaluate only the regions whose deps changed -> repaint
  changed regions into a persistent jatatui Buffer -> diff only the dirty rects against the front buffer
  -> Backend.draw(changed cells) -> flush. Terminal.draw is NOT used (it resets the back buffer).
- Event loop: one Kyo fiber, `Loop` over an immutable `World`; input fiber poll/read -> Channel;
  tasks and sources are fibers that post results to the Channel.
- Tests: munit, headless harness (TestBackend), the three apps. Bench: counting backend, 100x30.

## Log
