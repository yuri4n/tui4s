<!-- claude 2026-10-20: created: the specification of the counter task (L5, stage 1) -->
# Task: counter

Every implementation of the counter obeys this specification. The harness
(`bench/harness`) checks it with a screen oracle in a pseudo-terminal of 100
columns and 30 rows.

## Screen

Rows are numbered from 1. The terminal has R rows (30 at the start).

| Row | Text | Notes |
| --- | ---- | ----- |
| 1   | `Counter` | It starts at column 1. Style is free. |
| 3   | `Count: N` | It starts at column 1. N is the count in decimal, with `-` for a negative count. |
| R   | `+/- or Up/Down change the count, q quits` | The last row of the terminal. It starts at column 1. |

All the other rows are blank. The screen has no border and no other text.
Colours and bold text are free; the oracle reads only the characters.

The count starts at 0. There is no lower or upper limit.

## Keys

| Key | Effect |
| --- | ------ |
| `+` or ArrowUp | The count increases by 1. |
| `-` or ArrowDown | The count decreases by 1. |
| `q` | The program ends with exit code 0. |
| Ctrl-C | The program ends with exit code 0, or with 130 when the stack gives Ctrl-C to the program as SIGINT. |
| Ctrl-X | The forced failure (a hidden key): the key handler throws an exception or panics. See "Failure". |
| Any other key | No effect. |

Each key is one event. When the terminal gives more than one key in one read
(for example `+++` in one write), each key has its effect: no key is lost.
The screen shows the effect of a key in the next frame.

## Resize

When the terminal changes its size, the next frame uses the new size: the help
row moves to the new last row, and rows 1 and 3 stay. The count does not
change.

## Terminal modes

While the program runs, the terminal is in raw mode, the program draws on the
alternate screen, and the cursor is hidden.

## Restore contract

When the program ends, the terminal is restored. These conditions apply:

1. The terminal is not in raw mode: the line discipline flags (ICANON, ECHO,
   ISIG) are the same as before the start.
2. The program left the alternate screen: after the last "enter alternate
   screen" sequence (`ESC [ ? 1049 h`, or 1047, or 47), the output has a
   "leave alternate screen" sequence.
3. The cursor is visible: after the last "hide cursor" sequence
   (`ESC [ ? 25 l`), the output has a "show cursor" sequence (`ESC [ ? 25 h`).
4. No process of the program stays alive (the harness looks at the process
   tree and at the session of the program, not at names).

The contract applies in each of these cases:

| Case | How the harness causes it | Expected exit |
| ---- | ------------------------- | ------------- |
| quit | the key `q` | 0 |
| Ctrl-C | the key Ctrl-C (byte 0x03) | 0 or 130 |
| SIGTERM | `kill -TERM <pid>` | 0 or 143, or the end by signal 15 |
| SIGINT | `kill -INT <pid>` | 0 or 130, or the end by signal 2 |
| failure | the key Ctrl-X (byte 0x18) | see "Failure" |
| kill mid-frame | a burst of keys, and SIGTERM in the same millisecond | as SIGTERM |

The program ends at most 2 seconds after the key or the signal. For quit the
exit code must be 0. For the other cases the harness records the exit code,
and a code outside the list is a note, not a failure (for example, bubbletea
ends with 1 after SIGINT). SIGKILL is not in the contract: no program can
restore the terminal after SIGKILL.

## Failure

The key Ctrl-X makes the key handler throw an exception (or panic, in Rust and
Go). The stack does one of these two things, and both are correct:

- The program ends with a non-zero exit code. The restore contract applies.
- The runtime catches the failure, the program goes on, and the next keys
  have their effects. The restore contract applies when the program ends
  later (the harness sends `q`).

The harness records which of the two happened ("ended" or "contained").

## Key script of the oracle

The harness runs this script and checks the screen after each step. Each step
must show its effect in 1.5 seconds.

1. Start: rows 1, 3 and R show `Counter`, `Count: 0` and the help text.
2. `+` three times: `Count: 3`.
3. `-`: `Count: 2`.
4. ArrowUp: `Count: 3`. ArrowDown: `Count: 2`.
5. `+++++` in one write: `Count: 7`.
6. `x` (not bound): after 300 ms, `Count: 7` and no other change.
7. Resize to 80 columns and 24 rows: the help text is on row 24, and
   `Count: 7` is on row 3.
8. `q`: the program ends with exit code 0, and the restore contract holds.

At each step the screen has no text outside rows 1, 3 and R.

## Change request (version 2)

"The counter also shows the count in words below the number."

Row 4 shows `In words: W`. W is the English word of the count from minus
twenty to twenty (`zero`, `three`, `twelve`, `minus seven`). Outside that
range W is the number in digits (`21`, `-25`). The other rows do not change.
The harness applies `change.patch` of each implementation, builds it, and
checks row 4 after the steps 1 to 5 (`zero`, `three`, `two`, `three`, `two`,
`seven`).
