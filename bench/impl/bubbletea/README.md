<!-- claude 2026-10-20: created: the bubbletea stack of the bench (L5) -->
# bubbletea

The two tasks in Go: one module, one main package per task, in the Elm
architecture of bubbletea (a model, `Update`, `View`).

| Item | Version |
| ---- | ------- |
| bubbletea | 1.3.10 |
| bubbles (the progress bar of the dashboard) | 1.0.0 |
| lipgloss (the borders) | 1.1.0 |
| Go | 1.24.7 |
| Formatter | gofmt (Go 1.24.7) |
| Lock file | `go.sum` |

```
bench/impl/bubbletea/build.sh      # go build into bin/
bench/impl/bubbletea/run.sh counter
bench/impl/bubbletea/run.sh dashboard
```

## Notes

- bubbletea gives the runes of one read as one key message: `+++` in one
  write is one `KeyMsg` with three runes. The counter splits it into one
  update per rune, or the spec step "`+++++` in one write" loses keys.
- The list of the dashboard is written by hand: the list of bubbles pages
  (the selection jumps to a new page), and the spec asks for the minimum
  scroll. The sparkline is written by hand too (bubbles has none).
- bubbletea restores the terminal on SIGTERM and SIGINT, and on a panic in
  `Update` (the forced failure ends the program with code 1).
- At the start, bubbletea (termenv) asks the terminal for its background
  colour and the cursor position; the harness answers as a terminal does.
