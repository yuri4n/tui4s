<!-- claude 2026-10-09: created this file (S8 moved out of the plan at the user's request) -->
# Parked: S8, a State-monad UI as a baseline

Status: PARKED (2026-10-09). The user cancelled S8 and asked to keep it apart from the plan. It is not part of
PLAN-kyoui.md. Do not run it unless the user asks.

Why it stopped: kyo-ui stays the public model (ecosystem reasons), so S8 can change no decision. Its only value was
to measure what the reactive model costs. The user's rule is the sweet spot of cost x benefit.

The text below is the old section 0.6 of the plan, unchanged.

### 0.6 S8: a State-monad UI as a baseline (DECISIONS round 7)

<!-- claude 2026-10-09: changed: S8 is conditional -->
CONDITIONAL: S8 runs only if S1 or S2 is SOFT or NO-GO at gate 0 (DECISIONS round 7). One agent, after W0 ends (a
quiet machine, one workflow at a time). Local only, in `.work/spikes/s8-state-ui/`.
scala-cli. About 0.3-0.4 M tokens. S8 has no gate: it gives numbers, and the user reads them at gate 0.

- Question: what does the reactive model of kyo-ui (signals, regions, settle) buy, compared with a State-monad UI?
- Build (new): the BUFFER variant (immediate mode, as Dear ImGui). `UI[A] = A < Var[Frame]` (Kyo `Var` is the State
  effect). `Frame` = the buffer, the layout cursor or area, the input of the last frame, and widget memory by id.
  Widgets give results: `button(label): UI[Boolean]`, `textInput(id): UI[String]`. An app is `S => UI[S]`, run once
  per frame. Pure painting; one mutable write to the jatatui backend per frame.
- Show (small): the MODEL variant. `update: (S, Event) => S` is the same as `Event => State[S, Unit]`: this is the
  old Elm core of PRs 3-5. Write only a Counter on Kyo `Var[S]` to show it, and take the numbers of PR 5 head
  (`666a8e3`: `bench/BASELINE.md`, `examples/ptycheck`). Also a Counter as `ZIO[ZState[...]]` (shape only, no numbers).
- Apps: Counter and Todo, with the behaviour of the kyo-ui runner prototype apps.
- Measure, against the kyo-ui runner prototype (its REPORT and the W0 S1 numbers): main lines (library and apps);
  split frames (S1 method; expected 0 by design); key-to-frame latency p50/p99, and the cases with a one-frame lag;
  cost per event on 100x30; idle CPU; an identity probe (Todo: add an item above a focused text input: does the typed
  text stay with its item?); tests (can a headless test check the item list and the focus, or only cells?).
