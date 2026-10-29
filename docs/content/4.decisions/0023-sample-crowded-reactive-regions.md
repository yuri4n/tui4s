---
title: "0023. Sample crowded reactive regions"
description: "An input frame reads 16 reactive children per parent. A parent with more than 2 forks no reactive watcher."
---

<!-- cursor 2026-10-28: created: ADR 0023, sample crowded reactive regions. -->

## Status

Accepted.

## Date

2026-10-28.

## Context

- ADR 0005 gives each region its own watcher loop. The loop uses `Signal.nextWith`.
- A derived signal built with `combineLatest` still runs one `Async.race` per change inside Kyo. Issue #8 records that limit.
- The settlement fixture mounts one derived region per status row. At 128 rows, input stayed near the 250 ms CI gate. At 512 rows, input waited for seconds. Thread dumps showed the Kyo evaluator draining effect remainders, not a stalled inbox.
- One capacity waiter reduced the 512-row delay and then missed keys and failed shutdown. That experiment stays a draft.

## Decision

- An input frame reads the first 16 reactive children of each parent. It also reads every bound region, every `Foreach`, and a sampled region that holds the focus.
  <!-- cursor 2026-10-28: changed: watch two reactive regions; cursor 2026-10-28: changed: the cap is per parent; cursor 2026-10-28: changed: the 16 are an input read. -->
- A parent with 2 reactive children or fewer gives each of those children a watcher fiber.
  <!-- cursor 2026-10-28: created: a small parent keeps the ADR 0005 loop. -->
- A parent with 3 reactive children or more gives those children no watcher fiber. The child that crosses the limit stops the fibers the parent already started. A background frame reads the current value of 8 of those regions. The idle runner waits at most 16 ms for the next sample.
  <!-- cursor 2026-10-28: created: a crowded parent samples every reactive child. -->
- When input is already queued, the runner paints that input before the next background frame.
- Bound regions and `Foreach` regions keep their watchers. The 1 s repair tick stays.

## Alternatives considered

- **Wake one admission waiter.** It cut the 512-row delay in one run and then dropped keys and failed to shut down. Rejected.
- **Drop the input pull of every reactive region, including small screens.** That breaks the armed-region check on an input frame. Rejected.
- **Keep one watcher on every region and shorten settlement only.** Settlement already has a 16 ms budget. Layout and the watcher races sit outside that budget. Rejected.

## Consequences

- A parent with 2 reactive children or fewer keeps the ADR 0005 watcher behavior.
  <!-- cursor 2026-10-28: changed: watch two reactive regions; cursor 2026-10-28: changed: the limit is the children of one parent. -->
- A crowded parent samples its reactive children.
  <!-- cursor 2026-10-28: created: the fanout has no watcher fiber. -->
- The first 16 reactive children of a crowded parent still change on the input frame.
  <!-- cursor 2026-10-28: created: forms and dashboards keep the key frame. -->
- A sampled region can show its new value on a later frame. Input text and the focused region stay on the input frame.
- The CI input gate remains 250 ms for the continuous 128-region workload. This record does not claim a 100 ms bound.

## References

- ADR 0005. Issue #8. `tui4s.internal.ReactiveBudget`.
