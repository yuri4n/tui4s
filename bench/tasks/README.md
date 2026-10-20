<!-- claude 2026-10-20: created: the tasks of the bench program (L5) -->
# The tasks of the bench

Each task is an exact specification. Every implementation follows it, and the
harness checks it in a pseudo-terminal of 100 columns and 30 rows.

## Stage 1 (implemented)

| Task | Specification | What it measures |
| ---- | ------------- | ---------------- |
| counter | [counter.md](counter.md) | The smallest app: state, keys, quit, resize, the restore contract. The mutant suite runs on it. |
| dashboard | [dashboard.md](dashboard.md) | A list of 1,000 rows with a viewport, two timers (100 ms and 1 s), a gauge, a sparkline, a status bar and two focus targets. |

[concepts.md](concepts.md) lists the concepts that a reader needs for each
task and stack.

## Stage 2 (specifications to write; no implementations yet)

**form.** A sign-up form of five fields: a name (text), an email (text with a
check), a password (masked), a role (a select of three values) and a
checkbox for the terms. Tab and Shift-Tab move the focus. Each field shows
its error under it after it loses the focus. Enter on the last field submits
the form when all the fields are valid, and the screen then shows a summary.
It measures the inputs, the focus order, the validation and the text editing
(the caret, Backspace, Ctrl-U).

**todo with persistence.** A list of tasks that a file keeps between runs:
add (a text input), toggle done (Space), delete (`d`), filter (all, open,
done). The file is written after each change and read at the start; a
corrupt file gives an error row, not a crash. It measures the state that
lives longer than the process, the file effects and their errors, and a
write that must not block the UI.

**100,000-line log viewer.** A viewer of a log file of 100,000 lines that
follows the end of the file while a writer appends 100 lines per second.
`/` starts a search; `n` and `N` move between the hits; `f` turns the follow
mode on and off. It measures a very long list (only the visible rows may be
built), the scroll, a search over the whole file without a frozen UI, and the
memory at a steady state.

**streaming chat.** A chat screen whose replies come from a local fake
server as a stream of tokens (20 per second, with pauses). The reply grows in
place and wraps; the user can scroll up while it streams, and Esc cancels the
stream. It measures a growing text that wraps, the cancellation of a
long-running effect, and the follow of the end of a scroll view.

**wide cells.** A table of names in Latin, CJK and emoji text, with
combining marks and zero-width joiners, in columns that are cut with an
ellipsis. A text input edits such a name. It measures the cell width of each
grapheme (no split wide character, a caret at the correct cell) and the cut of
a text at a column border.
