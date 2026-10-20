---
title: "0012. The documentation is example-first"
description: "Every public API ships with a compiled example; README and docs code blocks are regions of tested files; the docs site is Nuxt Docus on Vercel; work lands on one branch with CI on demand."
---

# 0012. The documentation is example-first

## Status

Accepted. The docs site is in progress (L5).

## Date

2026-10-19 (D17, D18; D11 host). The Docus choice is from 2026-09-30.

## Context

- The user reviews only the usage examples, in the code and outside it (D17: "Documentation = usage examples").
- `ReadmeSuite` already checks README blocks marked as regions against the example files. The README quick start is a region of `examples/counter/Counter.scala`.
- The branch `docs/docus` (2026-10-01) predates kyo-ui. It describes the old DSL and claims ZIO and Cats Effect modules that do not exist.
- The PTY kit (`examples/ptycheck`) already drives every example in a pseudo-terminal, so it can record casts.
- The web build of the assistant (ADR 0011) can run in a page as a live demo.
- The stacked pull requests of 2026-10-14 needed manual rebases and a GitHub stack merge that only the web UI could do.

## Decision

- Every public API ships with a compiled example: a snippet suite, a Scaladoc example, or a region of an example app.
- README and docs code blocks are regions of compiled and tested files. `ReadmeSuite` checks the README; a Markdown regions suite checks `docs/content` (L5).
- The docs site is Nuxt Docus in `docs/` on `main`. Only `docs/` is copied from `docs/docus`, and its text is rewritten; the branch is not merged.
- The site leads with the examples, shows asciinema casts that the PTY kit records, and shows the web assistant in a frame with the fake provider.
- The host is Vercel. The session creates the Vercel project when the docs land (D11). The domain is open.
- No pull requests during the program (D18): all work lands on `chore/cleanup-7p2rll`; CI runs on demand (`workflow_dispatch`). The user merges.

## Alternatives considered

- **Prose-first docs.** Rejected by D17: the user reviews only the examples. The notes give no other reason.
- **Scaladoc only.** Not recorded as an option. Scaladoc examples are one channel of D17, not the whole site.
- **mdoc.** Not chosen: it compiles slower with Kyo macros, and its support of Scala 3.9.0 on JDK 25 is unverified. The region approach already exists.
- **Merge the `docs/docus` branch.** Rejected: stale, and it overclaims the ZIO and Cats Effect modules.
- **One pull request per phase, stacked** (the kyo-ui stack, PRs #10 to #16). Replaced by D18. D18 gives no reason. The handoff records the cost of the stack: on 2026-10-14 every branch had to be re-parented by hand before GitHub merged it.

## Consequences

- Good: a snippet in the docs cannot drift from the code without a red test.
- Good: the casts and the web demo come from the same code as the tests.
- Bad: every public change needs an example; the cost is in each phase.
- Bad: with no pull requests, review happens in the session's workflow (implementer, reviewer, fixer), not on GitHub.
- Bad: the domain and the Vercel token owner are human steps.

## References

- Decision log: first round (Docus on Vercel), sixteenth round (D17, D18, D11).
- Roadmap: 2.6 (the stale docs branch), D11, section 5.
- AGENTS.md: "Daily commands" (`ReadmeSuite`, the PTY kit).
