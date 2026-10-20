<!-- claude 2026-10-20: created: the ratatui stack of the bench (L5) -->
# ratatui

The two tasks in Rust: one Cargo workspace, one binary per task. Each task is
one loop: draw, wait for an event (at most until the next tick), handle it.

| Item | Version |
| ---- | ------- |
| ratatui | 0.30.2 |
| crossterm | 0.29.0 |
| signal-hook | 0.4.5 |
| chrono (the clock of the dashboard) | 0.4.45 |
| Rust | 1.97.0 (stable), edition 2024 |
| Formatter | rustfmt 1.9.0 (`cargo fmt`) |
| Lock file | `Cargo.lock` |

```
bench/impl/ratatui/build.sh        # cargo build --release --locked
bench/impl/ratatui/run.sh counter
bench/impl/ratatui/run.sh dashboard
```

## Notes

- `ratatui::init()` enters raw mode and the alternate screen and installs a
  panic hook that restores the terminal. The forced failure (Ctrl-X) is a
  panic: the hook restores the terminal, and the process ends with code 101.
- A Rust process that gets SIGTERM or SIGINT ends at once, with no restore.
  The implementations register the two signals with `signal-hook` (a flag
  that the loop reads at least every 100 ms); this is the cost of the restore
  contract in Rust, and it counts in the compactness table.
