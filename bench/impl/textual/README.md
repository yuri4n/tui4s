<!-- claude 2026-10-20: created: the Textual stack of the bench (L5) -->
# Textual

The two tasks in Python with Textual: an `App` each, with CSS for the layout,
bindings for the keys and reactive attributes for the state.

| Item | Version |
| ---- | ------- |
| textual | 8.2.8 (and rich 15.0.0; all the packages are in `requirements.lock`) |
| Python | 3.11 |
| Formatter | black 26.5.1 |
| Lock file | `requirements.lock` (pip freeze of the virtual environment) |

```
bench/impl/textual/build.sh        # .venv with the locked packages
bench/impl/textual/run.sh counter
bench/impl/textual/run.sh dashboard
```

## Notes

- The list of the dashboard is a widget that renders only its visible rows
  (`ItemList`). `ListView` with 1,000 `ListItem` widgets starts slowly, and
  `OptionList` has no `>` marker.
- Textual does not restore the terminal on SIGTERM: Python ends at once. The
  apps add a signal handler on the asyncio loop that calls `App.exit` (two
  lines). Ctrl-C does not quit in Textual 8 by default; a priority binding
  makes it quit.
- The forced failure (Ctrl-X) ends the app: Textual restores the terminal and
  prints the traceback; the exit code is 1.
