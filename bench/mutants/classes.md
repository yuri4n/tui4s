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
| `defused` | Every step passes, and the stack makes the bug harmless; `bench/harness/mutants.py` (`DEFUSED`) gives the reason. |
| `silent-wrong-behaviour` | Every step passes, and nothing makes the bug harmless: the bug is there, and the oracle does not see it. |
| `n/a` | The class cannot be written in that stack, or it does not exist there; `bench/harness/mutants.py` (`NOT_APPLICABLE`) gives the reason. |

## Coverage

The four stacks of the full suite are tui4s, ratatui, bubbletea and
jatatui-plain. Textual, ink and layoutz have the classes that fit in stage 1
(9, 9 and 7 patches). TamboUI has no mutants in stage 1 (13 dropped). The
results are in `bench/results/<machine>/mutants-<stack>.json`, and the table
is in `bench/README.md`.

The oracle reads the screen, the exit code and the terminal modes. It does not
read the logs, the memory or the threads of a program that ended: a mutant that
only wastes a resource until the process ends is "defused" or "silent".

## Notes on single mutants

- tui4s `lost-update`: the first form of the mutant started the late write
  with `Fiber.init`, and the compiler rejected it: in a key handler there is no
  `Scope`. The mutant now uses `Fiber.initUnscoped`, which a developer must
  choose on purpose.
- tui4s `no-resize` and `wrong-unit`: a root with a fixed height (the height of
  the start, or 100 rows) is defused, because the screen is a flex column and
  the root shrinks to it.
- layoutz `off-by-one`: the program without the mutant already fails the start
  step (it cannot use the last row), so the oracle cannot see a row that moves
  one more row up.
- layoutz `stdout-write`: the text shows until the next frame (at most 50 ms)
  and is then overwritten; the oracle reads a settled screen and does not see
  it.
