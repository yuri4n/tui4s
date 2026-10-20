---
title: "0013. Benchmarks in the repository, with their own build"
description: "The comparison lives in bench/ with its own sbt build and other languages in bench/impl/<language>; its headline is compactness, expressiveness, correctness and resource management, and performance is published honestly on its own page."
---

<!-- claude 2026-10-20: created: ADR 0013 in the docs site (L6) -->

## Status

Accepted. Stage 1 landed on 2026-10-20 (L5): two tasks in eight stacks, with the mutant suite; the TamboUI mutants came in L6. The run on a dedicated machine is [#51](https://github.com/yuri4n/tui4s/issues/51), and the review by a person of each ecosystem is [#34](https://github.com/yuri4n/tui4s/issues/34).

## Date

2026-10-17 (D10 as changed by the user); 2026-10-19 (the machine).

## Context

- The user asked for benchmarks "against Scala and other languages, and a comparison of expressiveness and correctness".
- Research of 2026-10-17: tui4s will very likely lose on startup, memory, size and idle CPU. It can win on bug classes caught at compile time, cleanup guarantees (scoped fibers, terminal restore) and code size.
- A JVM peer and a plain jatatui program split the cost: the JVM floor, then Scala and Kyo, then kyo-ui and tui4s. A plain Java jatatui counter starts in 147 ms against 1,101 ms for the tui4s launcher ([ADR 0015](/decisions/0015-packaging-aot-cache-and-native-image)).
- A change in the root `build.sbt` or in `project/` marks every published module unreleased.
- CI runners are noisy. Headline numbers need a dedicated machine.
- Competitor code written by people outside an ecosystem can be unidiomatic (vendor bias).

## Decision

- The benchmarks live in `bench/` with their own sbt build, outside the root build. They use the `publishLocal` artifacts. Other languages go in `bench/impl/<language>/`. They have their own CI job (`bench.yml`).
- The tasks are rosetta tasks with one JSON acceptance scenario each. Stage 1 is Counter (T1) and Dashboard (T4) in tui4s, plain jatatui, ratatui, Bubble Tea, Textual, Ink, TamboUI (a JVM peer) and layoutz.
- The headline axes are compactness, expressiveness, correctness and resource management. A mutant suite (13 bug classes) and a resilience suite (signals, crash restore) measure correctness.
- Performance is published honestly on its own page ("where tui4s loses"), with the same charts as the wins.
- The machine is the user's AWS account, set up with alchemy from `bench/infra/`: one bounded instance, no inbound ports, IMDSv2, `shutdown -h +90`, terminate on shutdown, results to S3.
- Results are published after 0.1.

## Alternatives considered

- **A separate repository** (for example `yuri4n/tui4s-bench`), easier for competitor maintainers to send pull requests to. Not chosen: the user put it in this repository. The reason is not recorded.
- **Performance as the headline.** Rejected: tui4s loses on it; the headline is what it can win, and performance gets its own honest page.
- **No dedicated machine (container or CI numbers only).** Rejected for headline numbers: CI runners are noisy. The user gave the AWS account (D10, 2026-10-19).
- **The benchmarks inside the root build.** Rejected: a bench change would mark every published module unreleased.

## Consequences

- Good: no published module becomes unreleased because of a bench change.
- Good: the comparison is reproducible from the repository, with Nix for the toolchains.
- Bad: several languages and toolchains to maintain; the competitor set may be too large for a lean program.
- Bad: competitor implementations need a review by a person of each ecosystem before the results are published ([#34](https://github.com/yuri4n/tui4s/issues/34)).
- Bad: the AWS run costs money and needs credentials; it must stay bounded and auto-terminate.

## References

- Roadmap: 2.6 (benchmarks), 2.7 (startup numbers), D10. Decision log: fifteenth round, sixteenth round (D10 machine).
- Handoff: "L5 LAUNCHED 2026-10-20".
