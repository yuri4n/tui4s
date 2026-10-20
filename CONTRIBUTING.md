<!-- claude 2026-10-20: created (L6) -->
# Contributing to tui4s

[AGENTS.md](AGENTS.md) is the rule book, for humans and for AI agents. Read
it before a change. This file is the short form.

## Set up

- JDK 25 (Kyo 1.0.0-RC7 needs it), sbt 1.13, Node.js 22 (the Scala.js tests
  and the docs site).
- Clone with the submodule: `git clone --recurse-submodules`, or run
  `git submodule update --init --recursive` in a checkout.
- With Nix: `nix develop` gives the tools (JDK 25, sbt, Node.js, `expect`,
  `python3`).

## Check a change

```sh
sbt fmt                                # format
sbt check                              # format, compile, lint, test, compatibility
bash scripts/version-order-test.sh     # the version order of the release script
bash scripts/edge-check.sh             # the edge files and the scalafix suppressions
examples/ptycheck/run.sh               # the real-terminal checklist, in a pseudo-terminal
```

CI compiles with `-Werror`: run `tpolecatCiMode` in sbt, or set
`SBT_TPOLECAT_CI=1`, to see the same errors. Run the PTY kit after a change
to the terminal code or to the examples. Never start a terminal UI with
`sbt run`: use the start scripts (`sbt examples/launchers`, then
`examples/target/bin/<app>`).

## Write docs

- Write the docs, the comments and the Scaladoc in ASD-STE100 (Simplified
  Technical English): short sentences, one instruction in each.
- A Scala block of a docs page (`docs/content`), of `README.md` or of
  `examples/README.md` is the text of a region of a compiled source:
  `MarkdownRegionsSuite` checks it. Add the region to the source first, then
  copy its text.
- The docs site: `pnpm install --frozen-lockfile && pnpm run build` in `docs`.

## Marks

A change by an AI agent carries a mark at the smallest unit that it changed:
`// claude <date>: created|changed: <what>` (an HTML comment in Markdown).

## Run the bench

`bench/run.sh` publishes the tui4s artifacts locally, builds the eight
stacks and measures them (`bench/README.md`). Run it alone on the machine.

## Report a problem

Use the issue forms. A security problem goes to [SECURITY.md](SECURITY.md).
The planned features are issues: <https://github.com/yuri4n/tui4s/issues>.
