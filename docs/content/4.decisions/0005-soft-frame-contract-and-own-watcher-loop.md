---
title: "0005. A SOFT frame contract and an own watcher loop"
description: "One frame per event with all its consequences, 0 split frames with no load and at most 1 per 10 000 under load; the runner watches signals with its own loop and a repair tick because of two Kyo RC7 Signal bugs."
---

<!-- claude 2026-10-20: created: ADR 0005 in the docs site (L6) -->

## Status

Accepted

<!-- codex 2026-10-23: changed: qualify the historical scheduler policy. -->
The default scheduler policy below is superseded by bounded local batching
and input-time reconciliation. Explicit `Settle.Quiescent` retains the
probe. Watchers now publish change notices; the runner reads current signal
state on consumption. See [input diagnostics](/guide/diagnostics) for the
workloads and measurement boundaries.

## Date

2026-10-11 (gate 0: D-17 c, K29 option 2). Changed on 2026-10-19 (C2, D21, D23).

## Context

- A frame must show all the consequences of one event, or the screen shows a half-updated state ("split frame").
- Kyo signals give no change notice. The runner "settles": it reads the scheduler until it is quiet, then paints.
- Spike S1 (20 000 events per cell): with no load, 0 split frames. Under load (3 JVMs on 2 CPUs), 1.50 per 10 000 with the Kyo scheduler values, and 0.67 with the tui4s values. With the tui4s values every run met the SOFT words. Settle cost: about +55 to +70 us per frame.
- 92 of 147 split frames under load were one cause: a lost wakeup in `Signal.combineLatest` (a write between `current` and `nextWith` waits for the 1 s repair). Nothing is pending then, so no probe can see it.
- Risk K29: one `Signal.observe` watcher keeps about 1.7 KB per change (one `Async.race` per value). 5 regions at 10 changes per second keep about 300 MB per hour. A `currentWith`/`nextWith` loop with no race stays flat (at most 14 B per change over 100 000 changes).
- With Kyo's defaults the idle scheduler uses about 13% of a core. Kyo's blocking monitor also measures its CPU counter for 86 to 102 ms at start.

## Decision

- The frame contract is SOFT (D-17 c): one frame per event with all its consequences; 0 split frames with no load; at most 1 per 10 000 under load. A frame is a batch: writes that land inside one settle share one frame.
- The settle uses the public `Scheduler.status()` probe with `confirm = 2`, a re-check after the settle, and a pull check of the bound and `Foreach` regions.
- The runner watches each region with its own `currentWith`/`nextWith` loop (no race) and a repair tick every 1 s (K29 option 2). TODOs in the code point at issues [#8](https://github.com/yuri4n/tui4s/issues/8) and [#9](https://github.com/yuri4n/tui4s/issues/9).
- `TuiApp`, the start scripts and the test JVM set four scheduler values: `cycleIntervalNs` 10 ms, `blockingMonitorIntervalNs` 20 ms, `blockingMonitorMinIntervalNs` 20 ms (C2: no start measurement), `concurrencyCollectIntervalMs` 100.
- CI sets `TUI4S_LOAD=1`: the glitch tests then allow 1 split frame in 500 events (gate 1, item 2).
- `SchedulerDefaults.defaults` stays `Seq[(String, String)]` (D21): `tui4s.spi` must load no Kyo class.
- The monotonic clock of the runner stays `System.nanoTime` (D23): Kyo RC7 `Clock.nowMonotonic` maps a negative value to 0.

## Alternatives considered

- **D-17 (a), a strict contract.** It needs G1 GO. G1 was NO-GO on RC7 because of the lost wakeup.
- **D-17 (d), "eventually consistent within `max`" with auto-wait kits.** It reverses the strict test kit. Not chosen.
- **Pin Kyo with the upstream fix as a 1.0 blocker.** Not chosen; the fix is tracked in [#9](https://github.com/yuri4n/tui4s/issues/9).
- **K29 option 1, wait for an upstream fix of `observe`.** Not possible for the first release; the draft is not posted.
- **K29 option 3, accept the leak and document it.** It loses 300 MB per hour in a long run.
- **Compare `Reactive` content another way** (gate 1, question 3). A region whose UI holds a closure never compares equal, so the 1 s tick renders it again. Not chosen: that render changes no cells and sends no bytes. Gate 1 accepted it until [#8](https://github.com/yuri4n/tui4s/issues/8) closes.
- **`Chunk` for `SchedulerDefaults.defaults`** (A5 a). It loads Kyo before the properties are set.
- **Kyo `Clock` for the settle bounds** (A11). A negative `nanoTime` would break the settle bounds and the pending timeout.

## Consequences

- Good: 0 split frames with no load; the glitch tests pin it (500 writes, 500 keys).
- Good: the heap stays flat in a long run, and idle CPU drops to about 4 to 8% of a core.
- Bad: the runner has a second watcher loop and two extra checks to remove when Kyo fixes `observe` ([#8](https://github.com/yuri4n/tui4s/issues/8), [#9](https://github.com/yuri4n/tui4s/issues/9)).
- Bad: a frame can arrive up to 1 s late under load. The test kit waits with `awaitScreen`, and core suites run one at a time.
- Bad: an app with its own `main` must set the scheduler values itself.

## References

- Decision log: eighth round (D-17 c, K29), eleventh round (gate 1), sixteenth round (D21, D23).
- Plan: D-16 (armkey), D-17; gates.md G1, K29. Issues [#8](https://github.com/yuri4n/tui4s/issues/8), [#9](https://github.com/yuri4n/tui4s/issues/9).
- CONTRIBUTING.md: "Rules for code" (the frame contract, the repair tick, the scheduler values). <!-- claude 2026-10-20: changed: CONTRIBUTING.md, not AGENTS.md (ADR 0021) -->
