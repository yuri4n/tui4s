<!-- claude 2026-10-03: created this file (final-plan agent). DRAFT: NOT POSTED. -->
<!-- claude 2026-10-03: critic pass: the hop-count claim now names its sources (desktop vs Linux). Still DRAFT: NOT POSTED. -->
# DRAFT: NOT POSTED. Upstream issue for getkyo/kyo (kyo-ui)

Status: DRAFT: NOT POSTED. Only the user posts it, or tells Claude in the chat to post it (ASSUMED A2 in
`.work/design/PLAN-kyoui.md`). No PR of the plan depends on it.

## Notes for the user (not part of the issue)

- Before you post:
  1. Search the getkyo/kyo issues and PRs for a duplicate. This session could not search that repo (its GitHub access
     is bound to yuri4n/tui4s).
  2. Read kyo PR #1649 (the kyo-ui PR). We could not read its text (RR skeptic "refuted" 7), so the issue does not
     cite it.
  3. Check the newest kyo-ui release. Our numbers are from 1.0.0-RC7.
- After W0, agent M replaces each `[W0 ...]` mark with the measured number (S1, S2, S3, S4), or deletes the mark.
  Until then, the numbers come from the prototype.
- tui4s is a private repo now. The issue cites only kyo code and our numbers, with no tui4s link. If you want a
  reproducer, a small public scala-cli gist is an option. You decide.
- Evidence for each claim (internal):

| Claim in the issue | Evidence |
| --- | --- |
| Settle = about half of a small frame (328 of 626 us p50) | RR "Measurements", run B, row "Counter: `+` key" |
| Counting scheduler hops: 2 to 131 split frames per 2000 events, by hop count and machine | Desktop (macOS, 2026-10-02): RR progress log "FINDING (glitch-free)" (hops=1: 19, hops=2: 7, hops=3: 3..131, hops=6: 26); no log on the Linux machine (RR skeptic "refuted" 10). Linux, 1 hop: 3 in 500 and 2 in 2000 (RR verifier, `lateprobe-{1,2}.log` in the session scratchpad, not kept in `.work/`). W0 S1 measures again (critic pass, 2026-10-03) |
| Scheduler probe: 0 split frames in 18 000 events under contention; not provably exact | RR findings N1, N2 |
| `busyWorkers()` is new in RC7 | DK F-2; `.work/scratch/kyo-src-rc5` vs `kyo-src` `Scheduler.scala` |
| The `observe` window and the 1 s repair interval | `.work/scratch/kyo-src/kyo-core/kyo/Signal.scala` (`observe` Scaladoc, `defaultRepairInterval`) |
| First key 0.2-1.5 s late: 0-4 of 500 starts with no load; up to 13 of 300 under load (ZIO path) | IR "What does not work" 2 (table) |
| Derived signals compute `current` synchronously | DK F-4; `kyo-core/kyo/Signal.scala` (`map`, `zip`, `combineLatest`) |
| Handler types `Any < Async` | `.work/scratch/kyo-src/kyo-ui/kyo/UI.scala:771-788` |
| A handler result is discarded with `.unit` | `.work/scratch/kyo-src/kyo-ui/kyo/internal/ReactiveUI.scala:337, 341`; FR F1 and skeptic C1 |
| The untyped `val` handler trap | RR finding O5; `proto/kyoui-runner/apps/Todo.scala:85-94` |
| About 400 lines of copied dispatch and region rules | RR finding N9; `Dispatch.scala` 242 + `Mount.scala` 181 |
| kyo-ui adds about 15.2 MB; `Href.Absolute` holds `HttpUrl` | RC section 3.1; kyo-ui `UI.scala:499` |

---

## Title

kyo-ui: a synchronous change notice on `Signal`, and handler types that reject lifted values (for custom backends)

## Body

### Context

We are writing a terminal backend for kyo-ui on the JVM. It turns a kyo-ui `UI` value into terminal cells (with
jatatui, a Java port of ratatui). The kyo-ui README invites custom backends, and most of it works well: the backend
reads attributes through `el.attrs`, mounts `Reactive`, `Foreach` and bound-ref regions, and repaints only the cells
that changed. We used kyo and kyo-ui 1.0.0-RC7.

Two points make a correct backend hard. Do you see them as in scope? Which shape do you prefer? We can help with a PR.

### 1. A synchronous change notice on `Signal`

**Problem.** A backend must know when all the consequences of an event are known, so that it paints ONE frame that
shows all of them. Today `SignalRef.set` completes a promise, and each observer resumes later as a scheduled task.
Derived signals add more steps. There is no public way to know that every observer has its new value.

**What we do now.** After each event we "settle": we read the Kyo scheduler until it looks idle (two quiet reads, one
scheduler hop apart, at most 100 ms), and then we paint. This works in practice, but:

- It is a heuristic. The worker scan is not atomic: a task is not visible between `queue.poll()` and `runTask`, or
  during a steal. When we counted scheduler hops instead, frames were split with each hop count that we tried (1, 2, 3
  and 6 hops: 2 to 131 split frames per 2000 events, by hop count and machine). [W0 S1: hop-count splits on Linux]
  With the scheduler probe we got 0 split frames in 18 000 events under CPU contention, but we cannot prove that it is
  exact.
  [W0 S1: split rate per 10 000 events, with no load and under load]
- It costs time: about half of a small frame (328 of 626 us p50 for a one-cell change on a 100x30 screen).
- It reads scheduler internals that change between RCs (`busyWorkers()` is new in RC7).

A related window: `Signal.observe` reads `current`, runs `f`, then registers `nextWith`. Its Scaladoc says that a
write in that window is reconciled by the repair timer (`defaultRepairInterval`, 1 s). In a backend this shows as a
first key whose result comes 0.2 to 1.5 s late. We saw it in 0 to 4 of 500 starts with no load, and in up to 13 of
300 starts under load. [W0 S2: result with a verify pass] A change notice at write time would also close this window
for a backend.

**Proposal.** Any one of these would let us remove the settle. We have no strong preference:

- (a) A change listener on `SignalRef` that runs synchronously inside `set` (for example in `SignalRef.Unsafe`), so a
  backend can mark a region as changed at write time.
- (b) A batch or transaction API that returns when all derived signals have their new values.
- (c) A version number on each signal, and a public way to get the sources of a derived signal. Derived signals
  already compute `current` synchronously from their sources, so a backend could then read exact values after a
  handler.

### 2. Handler types that do not accept a lifted value

**Problem.** Handlers take `Any < Async`, for example `onClick(action: => Any < Async)` and
`onKeyDown(f: KeyboardEvent => Any < Async)`. Every plain value conforms to `Any < Async`. Two results:

- In plain Kyo, a handler `val` with no type ascription can widen to `Any`. Its effects are then a plain value, and
  they never run. There is no error and no warning:

  ```scala
  val keys = (e: KeyboardEvent) =>
    e.key match
      case Keyboard.ArrowUp => move(-1) // move(d) is a Kyo effect (a `< Sync` value)
      case _                => ()
  // keys: KeyboardEvent => Any. It compiles, and move(-1) never runs.
  div.onKeyDown(keys)
  ```

  The fix in user code is the ascription `: Any < Async` on the `match`.
- With effect-system interop, a `zio.ZIO` or a `cats.effect.IO` value passed to `onClick` compiles, and kyo-ui
  discards it (`ReactiveUI` calls `handler.get.unit`). To protect ZIO and Cats users, we now generate a full mirror
  of the DSL with ZIO or `F[_]` types at each handler.

**Proposal.** Type handlers as `Unit < Async` (callers add `.unit` where needed), or keep `Any < Async` and reject a
plain non-Kyo value at the call site with an implicit check. We know that the first option is a source change. We did
not try it against kyo-ui, so we do not know if it rejects every case.

### Also worth a look (we can open separate issues)

- A public backend SPI: the region walk (`ReactiveUI.walkStatic` / `subscribe`), the event dispatcher with its
  bubbling and default actions, a read-only `Attrs` type, and `Foreach.applyTyped`. Today a backend copies these
  rules (about 400 lines for us) and must follow each change by hand. [W0 S3: changed names RC5 -> RC7]
- A module split: a terminal app gets kyo-http, kyo-net and kyo-ffi through kyo-ui (about 15 MB). `UI.Href.Absolute`
  holds a `kyo.HttpUrl`, so these jars cannot be excluded. [W0 S4: classes loaded at start]

Environment: kyo and kyo-ui 1.0.0-RC7, Scala 3.9.0, JDK 25, Linux, 4 CPUs. The numbers come from a prototype on a
shared machine. We can share the probe code.
