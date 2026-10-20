<!-- claude 2026-10-20: created: the bug classes of the mutant suite (L5) -->
# The mutant suite

Each bug class is one sentence. For each stack, `bench/mutants/<stack>/<class>.patch`
puts one small bug of that class into the counter (`bench/impl/<stack>/counter`).
The harness (`bench/harness/bench.py mutants`) applies the patch, builds the
counter, runs the key script of `bench/tasks/counter.md` and then the failure
probe (Ctrl-X), compares the result with the run of the counter without the
patch, and removes the patch.

## Classes

| Class | The bug |
| ----- | ------- |
| `no-restore-on-exception` | An exception (or a panic) in a key handler leaves the terminal in raw mode or on the alternate screen. |
| `wrong-thread` | The UI or its state changes from a thread that is not the thread of the UI loop. |
| `no-resize` | The layout keeps the size of the start and does not follow a resize. |
| `off-by-one` | A row of the layout is one row away from its place (the help row is not on the last row). |
| `blocking-handler` | A key handler blocks the UI loop (a sleep of 3 s), so the next frames come late. |
| `leaked-timer` | A timer that the program starts and never stops (the standard timer of the language, not the timer of the UI library). |
| `non-exhaustive-key` | The match on the key has no case for a key that the program can get. |
| `null-read` | The view reads an absent value (null, None, nil) when the count is 3. |
| `stale-closure` | A function keeps the value of the count of an earlier time and uses it later. |
| `wrong-unit` | A size in the wrong unit: percent or pixels for cells, bytes for rows. |
| `stdout-write` | The program writes to stdout while the UI is on the screen. |
| `missing-binding` | A key of the specification has no binding (ArrowUp). |
| `lost-update` | A state change is lost when two key events come together (in one read, or before an earlier change is written). |

## Outcomes

| Outcome | Meaning |
| ------- | ------- |
| `compile-error` | The compiler rejects the mutant. |
| `runtime-crash` | The program ends before the key script ends. |
| `test-or-oracle-catches` | A step of the oracle that passes without the mutant fails with it (a wrong screen, a late frame, no exit after `q`, a terminal that is not restored). |
| `defused` | Every step passes, and the defuse probe of the class shows that the bug has no effect (see below). |
| `silent-wrong-behaviour` | Every step passes, and the class has no defuse probe, or its probe sees the bug: the bug is there, and the oracle does not see it. |
| `n/a` | The class cannot be written in that stack, or it does not exist there; `bench/harness/mutants.py` (`NOT_APPLICABLE`) gives the reason. |
| `not-written` | The class can be written in that stack, but the stage has no patch for it. |

<!-- claude 2026-10-20: changed: the outcomes defused and not-written, and the defuse probes (review fix) -->
## Defuse probes

A probe runs only on a mutant that passes every step of the oracle. The
probes are the same for every stack (`PROBES` in `bench/harness/bench.py`).
`NOTES` in `bench/harness/mutants.py` adds an explanation to a result, and it
never changes the outcome.

| Class | The probe | Defused when |
| ----- | --------- | ------------ |
| `stdout-write` | The output of the key script: where the text `count N` of the write is. | The text never reaches the screen of the UI (the alternate screen, or the main screen of a stack that never enters the alternate screen). |
| `no-resize`, `wrong-unit`, `off-by-one` | The screen of the counter at 100x30, then after a resize to 120x40, 80x24 and 50x12. | Each size shows the screen of the specification. |
| `non-exhaustive-key` | The keys x, z, Home, Delete, F1 and F5, then `+`. | The program runs, and `+` gives `Count: 1`. |
| `lost-update` | 20 `+` in one write, then 20 writes of `+` with no wait. | `Count: 40`. |

The other classes have no probe: a mutant of those classes that passes the
oracle is `silent-wrong-behaviour`.

## Coverage

The five stacks of the full suite are tui4s, ratatui, bubbletea,
jatatui-plain and TamboUI (12 patches; `no-restore-on-exception` is n/a).
Textual, ink and layoutz have the classes that fit in stage 1 (9, 9 and 7
patches). The results are in
`bench/results/<machine>/mutants-<stack>.json`, and the table is in
`bench/README.md`.
<!-- claude 2026-10-20: changed: the TamboUI classes are not-written (review fix); the TamboUI patches (L6) -->

The oracle reads the screen, the exit code and the terminal modes. It does not
read the logs, the memory or the threads of a program that ended: a mutant that
only wastes a resource until the process ends is "silent" (the class
`leaked-timer` has no defuse probe).
<!-- claude 2026-10-20: changed: a leaked resource is silent (review fix) -->

## Notes on single mutants

- tui4s `lost-update`: the first form of the mutant started the late write
  with `Fiber.init`, and the compiler rejected it: in a key handler there is no
  `Scope`. The mutant now uses `Fiber.initUnscoped`, which a developer must
  choose on purpose.
- tui4s `wrong-unit` changes the gap row (`margin(1.px, ...)` to `1.pct`), the
  same site as the other stacks (a percent in place of one cell).
  <!-- claude 2026-10-20: changed: the site of the tui4s wrong-unit mutant (review fix) -->
- tui4s `wrong-thread` writes the count from a raw Java thread with an unsafe
  run of the effect (`AllowUnsafe`, `Sync.Unsafe.evalOrThrow`), as the Textual
  mutant writes it from `threading.Thread`.
  <!-- claude 2026-10-20: created: the note of the tui4s wrong-thread mutant (review fix) -->
- TamboUI `non-exhaustive-key`: the handler becomes a switch expression on
  `key.code()` with cases for `UP`, `DOWN` and `CHAR` only. javac rejects a
  switch expression on an enum that does not cover each constant.
  <!-- claude 2026-10-20: created: the note of the TamboUI non-exhaustive-key mutant (L6) -->
- TamboUI `wrong-thread` draws from a new thread with `TuiRunner.draw`.
  `Terminal.draw` does not check the thread (only the loop of `TuiRunner`
  does), and the frame shows the right count, so the mutant is silent.
  <!-- claude 2026-10-20: created: the note of the TamboUI wrong-thread mutant (L6) -->
- TamboUI `null-read`: `TuiRunner` catches the exception of the renderer and
  shows its error screen. The program does not end, so the outcome is the
  oracle (a wrong screen), not a crash.
  <!-- claude 2026-10-20: created: the note of the TamboUI null-read mutant (L6) -->
- TamboUI `lost-update` reads the count, then writes it 5 ms later on the
  scheduler of the runner through `runOnRenderThread`, as the Textual
  mutant awaits between the read and the write.
  <!-- claude 2026-10-20: created: the note of the TamboUI lost-update mutant (L6) -->
- layoutz `off-by-one`: the program without the mutant already fails the start
  step (it cannot use the last row), so the oracle cannot see a row that moves
  one more row up.
- layoutz `stdout-write`: the text shows until the next frame (at most 50 ms)
  and is then overwritten; the oracle reads a settled screen and does not see
  it.
