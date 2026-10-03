# Prototype "mut": type-member components, state change is an effect (Mut[S])

Status: IN PROGRESS (started 2026-10-02).

## Plan

1. Read background: Kyo RC7 Signal/SignalRef/Stream/Scope/Env, calico concepts, kyo-ui, jatatui Buffer/Backend.
2. Library layer (small):
   - `Component { type State; type Out; def initial: State; def view(state: Signal[State]): View < (Async & Scope & ...)}`.
   - `Mut[S]` = an effect over `Env[SignalRef[S]]`: `Mut.get`, `Mut.set`, `Mut.update` are atomic on the shared ref.
   - Fine-grained view: a `View` tree whose leaves can be bound to a `Signal`; each bound leaf is a region;
     a change repaints only that region into a kept jatatui `Buffer`, then flushes only the changed cells.
   - One render fiber, glitch-free: all signal changes from one event are applied before the frame is flushed.
3. Three apps: Counter (+ two independent counters), Todo (input + list, file load/save), Dashboard (tabs, sampler, log stream).
4. Headless tests (munit) with a TestBackend / counting backend.
5. Measurements: cells flushed, cells repainted, us per event; idle check.
6. Optional: real PTY run of the Counter with expect.

## Progress log

- 2026-10-02: plan written; reading background.
