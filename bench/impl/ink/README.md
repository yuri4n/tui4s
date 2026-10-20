<!-- claude 2026-10-20: created: the ink stack of the bench (L5) -->
# ink

The two tasks in JavaScript with ink and React: function components with
hooks (`useInput`, `useFocus`, `useAnimation`, `useWindowSize`). esbuild turns
the JSX into one file per task in `dist/`.

| Item | Version |
| ---- | ------- |
| ink | 8.0.0 |
| react | 19.3.1 |
| esbuild (build only) | 0.28.2 |
| Node.js | 22 |
| Formatter | prettier 3.9.10 |
| Lock file | `pnpm-lock.yaml` |

```
bench/impl/ink/build.sh            # pnpm install --frozen-lockfile, esbuild
bench/impl/ink/run.sh counter
bench/impl/ink/run.sh dashboard
```

## Notes

- ink gives the characters of one read as one input: `+++` in one write is one
  call of the `useInput` handler with `"+++"`. The counter handles each
  character, or the spec step "`+++++` in one write" loses keys.
- ink has no gauge, sparkline or list widget; the dashboard draws them with
  text. The panel titles are the first row inside the border (ink borders
  have no title).
- `alternateScreen: true` is an option of `render` in ink 8. ink restores the
  terminal on SIGTERM and SIGINT (signal-exit). The forced failure (Ctrl-X)
  is an uncaught exception: Node prints it and exits with 1, and the terminal
  is restored.
- ink renders nothing until the end when the environment variable `CI` is set
  (it detects a CI run). The harness does not pass `CI` to the programs.
