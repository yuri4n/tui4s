<!-- claude 2026-10-20: created: the specification of the dashboard task (L5, stage 1) -->
# Task: dashboard

Every implementation of the dashboard obeys this specification. The harness
(`bench/harness`) checks it with a screen oracle in a pseudo-terminal of 100
columns and 30 rows. The terminal has C columns and R rows.

## Screen

```
Dashboard                                                                                   12:34:56
┌Items─────────────────────────────────────────┐┌Load──────────────────────────────────────────────┐
│> Item 0001                                    ││Ticks: 42                                         │
│  Item 0002                                    ││██████████████████████████░░░░░░░░░░░░░░  65%     │
│  Item 0003                                    ││▁▂▃▅▆▇██▇▆▅▃▂▁▁▂▃▅▆▇██▇▆▅▃▂▁                       │
│  ...                                          ││                                                  │
└───────────────────────────────────────────────┘└──────────────────────────────────────────────────┘
Selected 0001/1000 | Focus: list | Tab focus, q quit
```

The picture is an example. The rules below are the specification.

- **Header (row 1).** The text `Dashboard` starts at column 1. The clock
  `HH:MM:SS` (local time, 24 hours) is at the right end of the row. The clock
  changes each second.
- **Body (rows 2 to R-1).** Two panels side by side. The list panel is the
  left half (C/2 columns, rounded down); the load panel is the rest. A panel
  can have a border. Its title (`Items`, `Load`) is in its top border or on
  its first row. The panel that has the focus has a different border or title
  style (free; the oracle does not read styles).
- **List panel.** One row for each item, `Item 0001` to `Item 1000` (four
  digits, with leading zeros), as many as fit. The selected row starts with
  the marker `>` (or `›`) and one space; the other rows start with two spaces.
  At the start, the selected item is `Item 0001` and it is the first visible
  row.
- **Load panel.** Three rows, in this order:
  1. `Ticks: T`. T is the tick count. It starts at 0 and increases by 1 at
     each tick of a timer of 100 ms.
  2. The gauge of the value v(T) in percent: a bar (free style) and the text
     `P%` (P = v(T)) on the same row.
  3. The sparkline of the last 40 values of v (fewer at the start), newest
     at the right, one cell per value, with the eight block characters
     `▁▂▃▄▅▆▇█` scaled from 0 to 100 (a widget that shows a space for a value
     under one eighth of the scale is correct).

  The value is v(T) = round(50 + 40 × sin(T / 5)), an integer from 10 to 90.
  One frame shows T and v(T) of the same tick.
- **Status bar (row R).** `Selected NNNN/1000 | Focus: F | Tab focus, q quit`
  (spaces before it are allowed).
  NNNN is the position of the selected item (four digits, leading zeros). F
  is `list` or `load`.

## Keys

| Key | Effect |
| --- | ------ |
| ArrowDown, ArrowUp | With the focus on the list: the selection moves 1 item down or up. It stops at the first and the last item. |
| PageDown, PageUp | With the focus on the list: the selection moves one page down or up. A page is the number of visible item rows. It stops at the first and the last item. |
| Tab | The focus moves from the list to the load panel, or back. |
| `q` | The program ends with exit code 0. |
| Ctrl-C | As in the counter task (exit code 0 or 130). |
| Ctrl-X | The forced failure, as in the counter task. |
| Any other key | No effect. With the focus on the load panel, the arrows and the page keys have no effect. |

**Viewport.** The selected row is always visible. When a move puts the
selection below the last visible row, the list scrolls the minimum distance:
the selected row becomes the last visible row. When a move puts it above the
first visible row, it becomes the first visible row.

## Timers

The tick timer (100 ms) and the clock (1 s) run on their own. Each tick gives
a frame. A key does not wait for a tick, and a tick does not wait for a key.
When the program ends, the timers stop (no timer keeps the process alive).

## Resize

The next frame uses the new size: the panels take the new width, the list
shows as many rows as fit (the selected row stays visible), and the status bar
is on the new last row.

## Terminal modes, restore contract, failure

The same as in the counter task (`counter.md`): raw mode, the alternate
screen, a hidden cursor while the program runs; the restore contract on quit,
Ctrl-C, SIGTERM, SIGINT, the forced failure and a kill mid-frame.

## Key script of the oracle

Each step must show its effect in 1.5 seconds.

1. Start: `Dashboard` on row 1, `Items` and `Load` in rows 2 to 4, the marker
   on `Item 0001`, and the status bar `Selected 0001/1000 | Focus: list` on
   row R.
2. ArrowDown three times: `Selected 0004/1000`, the marker on `Item 0004`.
3. PageDown: `Selected` is 4 + the page (the number of visible item rows),
   and the marker is on that item (the list scrolled).
4. PageUp: `Selected 0004/1000`. ArrowUp: `Selected 0003/1000`.
5. Tab: `Focus: load`. ArrowDown: still `Selected 0003/1000`. Tab:
   `Focus: list`.
6. In 2 seconds, T increases by 10 to 30, and each frame that the harness
   reads shows `P%` = v(T) for its T.
7. The clock changes in 1.5 seconds.
8. Resize to 80 columns and 24 rows: the status bar is on row 24, and the
   selection is still `0003`.
9. `q`: the program ends with exit code 0, and the restore contract holds.

## Change request (version 2)

"The dashboard list gets a filter input that narrows the rows."

- The list panel has a filter row under its title: `Filter: TEXT`.
- Tab moves the focus in the cycle list, filter, load. The status bar shows
  `Focus: filter` when the filter has the focus.
- With the focus on the filter, a printable character goes to the end of
  TEXT (also `q`), and Backspace removes the last character.
- The list shows only the items whose label contains TEXT. When TEXT
  changes, the selection goes to the first item that is shown.
- The status bar shows `Selected NNNN/MMMM`: the position in the shown items
  and the number of shown items (four digits each). With no item shown, it is
  `Selected 0000/0000`.

The harness checks: Tab, then `99`: `Selected 0001/0019` and the marker on
`Item 0099`. Backspace: `Selected 0001/0271`.
