<!-- claude 2026-10-09: created this file from the structured output of W0 (wf_bf024cec-c9f); the subagent harness refuses report files -->

Not applied to `upstream-kyo-ui-issue.md` yet. Apply when the user decides on A2. The draft stays NOT POSTED.

## W0 numbers for the upstream draft (agent M, 2026-10-09)

Put these lines into `.work/design/upstream-kyo-ui-issue.md`. The draft stays DRAFT: NOT POSTED. Only the user posts
it. Each item says where its text goes. All numbers: kyo and kyo-ui 1.0.0-RC7, Scala 3.9.0, JDK 25, Linux, 4 CPUs,
quiet machine. The evidence paths start at `.work/spikes/`.

### 1. Section "1. A synchronous change notice", first bullet: replace "[W0 S1: hop-count splits on Linux]"

> On Linux (4 CPUs), one hop gave 44 split frames in 40 000 events with no load (11 per 10 000), and 2021 in 40 000
> under CPU contention (505 per 10 000).

Evidence: `s1-settle/logs/m1-extra/glitch-none-kyo-reflect-hops1.log`, `glitch-2x2-kyo-reflect-hops1.log`.

### 2. The same bullet: replace "With the scheduler probe we got 0 split frames in 18 000 events under CPU contention, but we cannot prove that it is exact. [W0 S1: split rate per 10 000 events, with no load and under load]"

> A longer measurement shows that the probe is not exact. We used the public `Scheduler.status()` as the probe, in two
> runs with 20 000 events per cell. With no load, there were 0 split frames in 80 000 events. Under load, there were
> 0.25 to 0.67 per 10 000 events with longer scheduler intervals (`cycleIntervalNs` 10 ms,
> `blockingMonitorIntervalNs` 20 ms, `concurrencyCollectIntervalMs` 100). With the Kyo defaults, there were 0.62 to
> 1.50 per 10 000 (1.50 = 36 in 240 000 events, with 3 JVMs on 2 CPUs). A probe on `loadAvg`, `currentWorkers` and
> `busyWorkers()` got up to 5.6 per 10 000 in the same setup. We see two causes:
>
> - A task is in no counter between `queue.poll()` and `runTask`, in a local variable after a preemption, or while
>   the scheduler drains a stalled worker. When the OS stops the thread in such a gap, the gap is some milliseconds
>   long.
> - Derived signals lose wakeups. `combineLatest` re-arms with `awaitAny` after it reads both values. A handler
>   writes `a` and then `b`. The observer can read the new `a` with the old `b`, and re-arm after the write of `b`.
>   Then nothing is pending, and the screen shows the intermediate value until the next change or the repair timer.
>   Under load, 92 of 147 logged split frames were this case.

Evidence: `s1-settle/logs/run5-quiet/glitch-summary.txt`, `s1-settle/logs/m1/glitch-summary.txt`; the split details
are the `splitAt=` fields of `logs/m1/glitch-*.log`.

### 3. Second bullet ("It costs time ..."): add after the sentence

> With the public `Scheduler.status()`, the settle costs 55 to 68 us more per frame (p50) than with the private
> fields. The cause: `Worker.status()` calls `Thread.getStackTrace()` on each mounted worker (11-12 us per call).

Evidence: `s1-settle/logs/m1/glitch-none-*.log` (`settleP50us`, `probeP50ns`); `kyo-scheduler/kyo/scheduler/Worker.scala`
(`status()`).

### 4. The "related window" paragraph: replace "[W0 S2: result with a verify pass]"

> We confirmed the window with a test seam that holds the observer callback for 300 ms after its first value. The
> frame of the first key was wrong in 20 of 20 rounds, and it became right only after 1.3 s (the repair timer). A
> verify pass in our runner re-reads `current` of the new regions after the first key. With it, the frame was right
> in 20 of 20 rounds. Without the seam, the first key was late in 6 of 8000 starts with no load and in 39 of 4800
> starts under CPU contention. With the verify pass, it was late in 0 and 0.

Evidence: `s2-r1r2/logs/m1/suites-ensure-armkey-1.log` and `logs/run2-quiet/suites-ensure-armkey-1.log` (lines
`r2-seam`); `logs/m1/split-*.log` and `logs/run2-quiet/split-*.log`.

### 5. "Also worth a look", first bullet: replace "[W0 S3: changed names RC5 -> RC7]"

> From RC5 to RC7, 27 names changed on the surface that we copy (`UI.Ast` types, `Attrs` fields, `Style.Prop` cases:
> 11 in RC6 and 16 in RC7, all of them additions). Two rules changed. In RC6, `stopPropagation` stops the bubble
> (opt-in). In RC7, a bound element at the root of a `Reactive` or `Foreach` content becomes its own region
> (`walkStatic`, a new default). A TASTy check finds such changes, but we must still port each one by hand.

Evidence: `s3-drift/diffs/names/changes.tsv`, `diffs/rules.RC5-RC6.txt`, `diffs/rules.RC6-RC7.txt`,
`guard/out/check-RC5-RC6.txt`, `guard/out/check-RC6-RC7.txt`.

### 6. "Also worth a look", second bullet: replace "[W0 S4: classes loaded at start]"

> In a terminal Counter app, 0 of about 3550 loaded classes come from kyo-http, kyo-net, kyo-ffi, kyo-system,
> kyo-schema or kyo-schema-json, and these jars start no thread. These 6 jars are 9.3 MB of the class path. kyo-ui
> and the jars that it brings add 7 jars, 15.0 MB. The app runs without the 6 jars, but `UI.Href.Absolute` then fails
> with `NoClassDefFoundError: kyo/HttpUrl$`.

Evidence: `s4-weight/logs/s4-summary.log`, `logs/run-232628/s4-summary.log`, `logs/e2-xcheck/summary.log`,
`logs/probe-probes.HrefProbe-nohttp.log`.

### 7. New bullet under "Also worth a look" (or a separate issue)

> - `Signal.observe` keeps memory for each change. Each value runs one `Async.race` (`holdUntilChanged`). A race in a
>   fiber that lives long keeps 540-720 B until the fiber ends. One observer keeps about 1.7 KB per change. With
>   `-Xmx48m`, it stops with `OutOfMemoryError` after 20 000 to 30 000 changes. The Scaladoc says that no waiter
>   accumulates across changes. A loop on `currentWith`/`nextWith` without the race stays flat, but it can lose a
>   write.

Evidence: `s2-r1r2/logs/observe-bound-observe-100000-48m.log`, `observe-bound-race-100000-48m.log`,
`observe-bound-ownloop-100000-48m.log`, `observe-bound-ownloop-100000-48m-stalled.log`.

### 8. Changes in "Notes for the user" (the evidence table)

- Row "Scheduler probe: 0 split frames in 18 000 events ...": the evidence is now W0 S1 (`s1-settle/logs/run5-quiet/`,
  `logs/m1/`) and the claim of item 2.
- Row "Counting scheduler hops ...": add "Linux, 1 hop: W0 S1 `s1-settle/logs/m1-extra/`".
- Row "First key 0.2-1.5 s late ...": add W0 S2 (item 4).
- Row "kyo-ui adds about 15.2 MB": change it to "7 jars, 15.0 MB (W0 S4)".
- New rows for items 3 and 7.
- Delete the line "After W0, agent M replaces each `[W0 ...]` mark ...", after you put the items in.

### 9. Text that is out of date (not a number)

- Section 2, second bullet: "we now generate a full mirror of the DSL with ZIO or `F[_]` types at each handler". Round
  6 removed the generator. Use: "we now write a ZIO DSL and a Cats DSL by hand, with their own types at each handler".
