<!-- claude 2026-10-11: created this file (gate 0). DRAFT: NOT POSTED. -->
# DRAFT: NOT POSTED. Two upstream issues for getkyo/kyo (kyo-core `Signal`)

Status: DRAFT: NOT POSTED. Only the user posts them, or tells Claude in the chat to post them. The user asked for
them at gate 0 (DECISIONS.md, eighth round): "we need issues at kyo and kyoui if they are not there yet".

## Notes for the user (not part of the issues)

- kyo-ui is a module of the getkyo/kyo repository, and both bugs are in kyo-core `Signal`. Thus both issues go to
  getkyo/kyo. There is no separate kyo-ui repository to post to.
- Before you post, search for a duplicate. This cloud session cannot search that repository (its GitHub access is
  bound to yuri4n/tui4s; the search API and the web page answer 403). Two searches:
  - https://github.com/getkyo/kyo/issues?q=observe+memory+OR+leak+OR+race
  - https://github.com/getkyo/kyo/issues?q=combineLatest+OR+%22lost+wakeup%22+OR+nextWith
- Issue 1 has a tested minimal reproducer: `ObserveLeak.scala` next to this file (post it as a gist or inline).
- Issue 2 has no minimal reproducer yet. Its evidence is the split-frame statistics of spike S1 (a 6-region UI). The
  Kyo Scaladoc of `observe` documents the window, so the issue is a request to close it, not only a bug report. If the
  Kyo team asks for a reproducer, a small one is: two refs, `a.combineLatest(b).observe(record)`, a writer that sets
  `a` then `b` in one handler under load, and a check that the recorded pair shows `b`'s new value within 10 ms.
- Our numbers are from Kyo 1.0.0-RC7 on JDK 25 (Linux, 4 CPUs). Check the newest Kyo release first.
- tui4s is private. The issues cite only Kyo code and our numbers. No tui4s link.

---

## Issue 1: `Signal.observe` keeps about 1.7 KB per value change for the life of the watcher

### Title

`Signal.observe` retains ~1.7 KB per value change until the observing fiber ends (OOM in a long-lived watcher)

### Body

**Version:** kyo-core 1.0.0-RC7, Scala 3.9.0, JDK 25 (Linux x86_64).

**What happens.** A single `ref.observe(f)` watcher that runs for a long time keeps memory on every value change. The
growth is linear, about 1.7 KB per change, and it is only released when the observing fiber ends. With `-Xmx48m`, one
watcher dies with `OutOfMemoryError` after 20 000 to 30 000 changes.

**What I expected.** The Scaladoc of `observe` says: "no waiter or fiber accumulates across changes". A watcher that
lives for the whole life of an application (a UI binding, a metrics exporter) should run at a flat heap.

**Reproducer** (scala-cli; one ref, one watcher; each write waits until the watcher saw it, then pauses 200 µs so
the watcher re-arms before the next write):

```scala
//> using scala 3.9.0
//> using dep io.getkyo::kyo-core:1.0.0-RC7
import kyo.*

object ObserveLeak:
    def usedKb(): Long =
        val rt = java.lang.Runtime.getRuntime
        (1 to 3).foreach(_ => { java.lang.System.gc(); Thread.sleep(50) })
        (rt.totalMemory - rt.freeMemory) / 1024

    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger
        val n = args.lift(0).map(_.toInt).getOrElse(30000)
        val r = Abort.run[Any](Sync.Unsafe.run(KyoApp.runAndBlock(1.hour) {
            for
                ref  <- Signal.initRef(0)
                seen <- AtomicInt.init(0)
                _    <- Fiber.initUnscoped(ref.observe(v => seen.set(v)))
                k0   <- Sync.defer(usedKb())
                _ <- Kyo.foreachDiscard(1 to n) { i =>
                    ref.set(i)
                        .andThen(Loop.foreach(seen.get.map(s => if s >= i then Loop.done(()) else Async.sleep(20.micros).andThen(Loop.continue))))
                        .andThen(Async.sleep(200.micros))
                        .andThen(
                            if i % (n / 10) == 0 then
                                Sync.defer {
                                    val k = usedKb()
                                    println(f"writes=$i heapKB=$k bytesPerWrite=${(k - k0) * 1024.0 / i}%.0f")
                                }
                            else Kyo.unit
                        )
                }
            yield ()
        })).eval
        println(s"end: $r")
        java.lang.System.exit(0)
end ObserveLeak
```

Run: `scala-cli run --jvm system ObserveLeak.scala -J -Xmx48m -- 30000`

Output on my machine: [W0-REPRO: paste the lines of the run here]

**Where the memory goes.** `observe` holds each value with `holdUntilChanged`, which runs one
`Async.race(Seq(nextWith(_ => ()), Async.sleep(repairInterval)))` per change (`Signal.scala`, the `observe(repairInterval)`
overload). Two other measurements point at the race:

- One fiber that runs `Async.race(Seq(Async.sleep(Duration.Zero), Async.sleep(1.second)))` 100 000 times keeps 540 to
  720 B per race while it lives, and the heap returns to the baseline when the fiber ends.
- The same watcher written as a `currentWith` / `nextWith` loop with no race stays flat: at most 14 B per change
  over 100 000 changes.

So each race leaves something behind in the long-lived fiber (a finished-sleep entry or the loser's waiter) until the
fiber ends, and the Scope per value adds the rest.

**Why it matters.** A UI with 5 bound regions that each change 10 times per second keeps about 300 MB per hour. The
GC pauses that this causes also make the lost-wakeup window of `observe` (issue 2) visible more often.

**Possible fix.** A race should release its entries when it ends, or `holdUntilChanged` should not allocate a race per
change: for example re-arm `nextWith` once and use one timer fiber for the repair.

---

## Issue 2: a write between `current` and `nextWith` is only repaired after `repairInterval`; `combineLatest` widens the window

### Title

`Signal.observe` / `combineLatest`: a write in the read-then-arm window is not delivered until `repairInterval` (1 s); request for a lost-wakeup-free `nextWith`

### Body

**Version:** kyo-core 1.0.0-RC7, Scala 3.9.0, JDK 25 (Linux x86_64, 4 CPUs).

**What happens.** `observe` reads `current`, runs `f`, and then registers `nextWith`. The Scaladoc documents that a
write in the window between the read and the registration is missed by the immediate wakeup and repaired when the
repair timer fires (default `repairInterval` = 1 s). `combineLatest` makes the window wider: its `nextWith` is
`Signal.awaitAny(Seq(self, other)).andThen(self.currentWith(a => other.currentWith(b => ...)))`, so a watcher that
wakes for `a` reads `a` and `b`, and a change of `b` that lands after that read and before the next registration is
not seen until the repair.

In a terminal UI (6 bound regions, one key handler that writes 4 refs, under CPU load from other processes) this
shows as a frame where every region updated except the `a.combineLatest(b)` region, which shows an intermediate pair.
Nothing is pending in the scheduler at that moment, so no "settle" probe can see that a frame is stale. The screen
stays wrong until the next event or the 1 s repair.

**Numbers** (20 000 events per run, scheduler with `cycleIntervalNs` = 10 ms; 2 CPUs held busy by another process):

- 92 of 147 stale frames across the runs are this case: all regions updated, only the `combineLatest` region holds an
  intermediate value, and no timeout or pending work.
- With Kyo's default scheduler values the count is higher, and the other stale frames come from worker-stall gaps.
- With no competing load: 0 in 20 000.

**What I expected.** A watcher that is registered before the write, or a `nextWith` that cannot miss a write that
happens between `current` and its registration. Then the worst case is one extra wakeup, not one `repairInterval`.

**Proposal.** Give the raw signal protocol a version (a monotonic change counter):

- `currentWith` also provides the version it read.
- `nextWith(sinceVersion)` fires at once when the version already moved, else it waits.

`observe` then re-arms with the version of the value it delivered, and the window closes. `combineLatest` can wait on
the pair of versions. The repair timer stays as a safety net, but correctness no longer depends on it. A smaller step:
let `observe(repairInterval)` accept a much smaller interval without the per-change allocation of issue 1, so that a
UI can repair within one frame.

**Related:** issue 1 (the per-change allocation of `observe`), which makes a small `repairInterval` expensive today.
