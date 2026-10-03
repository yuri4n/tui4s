# Kyo research report (for tui4s)

Date: 2026-09-30. Author: research subagent (topic: KYO).
Legend: **VERIFIED** = seen in source / Maven metadata / compiled+ran. **UNVERIFIED** = memory or secondary source.

## TL;DR (decisions the build needs)
1. Latest Kyo = **1.0.0-RC7** (2026-09-28); no 1.0.0 final; last non-RC 0.19.0 (2025-05, obsolete API).
2. **RC7 needs Scala 3.9.0 and JDK 25** (Java 25 bytecode; RC6 notes "Kyo now requires JDK 25"). Even **RC5** needs JDK 25 (its kyo-data jar is Java 25 bytecode). The **newest Kyo that works on JDK 21 is 1.0.0-RC4** (Scala 3.8.3). This machine has only JDK 17/21, so RC7 could not be compiled here. Decide: install JDK 25 (sdkman `25.x-tem`) and target RC7, or stay on JDK 21 with RC4.
3. **kyo-cats was removed upstream in RC6**; last kyo-cats is RC5 (cats-effect 3.7.0). For tui4s-cats, vendor the ~40-line `Cats.get/run` bridge (Apache-2.0) instead of depending on kyo-cats. kyo-zio is maintained (RC7, ZIO 2.1.26).
4. Probe with Var, Emit, Stream, Env, Abort, Scope, Channel, Fiber, Signal, Latch, Meter, Loop, Clock, KyoApp, ZIO and CE bridges **compiled and ran on RC4/JDK 21** (section 6). Every call has the same signature in RC7 source.
5. Reactive core: `Signal`/`SignalRef` (dedup, `streamChanges`, `observe`, `combineLatest`) is the natural model primitive; kyo-ui (web) is the reference design. Input: never block unboundedly in JNI; loop `poll(timeout)` + `read()` in `Sync.defer` (scheduler compensates for blocking, but `Thread.interrupt` cannot stop a JNI call).

## 1. Versions, Scala/JDK, modules

### 1.1 Versions on Maven Central (checked 2026-09-30)
- **VERIFIED** `io.getkyo:kyo-core_3` maven-metadata: `<latest>`/`<release>` = **1.0.0-RC7** (lastUpdated 2026-09-28). There is **no 1.0.0 final** yet.
- **VERIFIED** Last non-RC release is **0.19.0** (POM last-modified 2025-05-12). It is old (≈16 months) and its API is very different; do not target it.
- **VERIFIED** Publish dates: 1.0-RC1 2025-07-10, 1.0.0-RC2 2026-05-11, RC3 2026-06-17, RC4 2026-06-18, RC5 2026-07-01, RC6 2026-08-04, **RC7 2026-09-28**.
- Recommendation (mine): pin **1.0.0-RC7** for every kyo module except kyo-cats, **only if the project moves to JDK 25 and Scala 3.9.0** (see 1.2); otherwise pin 1.0.0-RC4. Kyo is still pre-1.0; expect breaking changes between RCs.

### 1.2 Scala / JDK requirement
- **VERIFIED** kyo-core_3 1.0.0-RC7 POM depends on `org.scala-lang:scala3-library_3:3.9.0`. So the **consumer must compile with Scala >= 3.9.0** (a Scala 3 compiler cannot read TASTy from a newer minor version). This affects tui4s: the build must move to Scala 3.9.x.
- **VERIFIED** kyo-cats_3 1.0.0-RC5 POM depends on scala3-library 3.8.4 (older RC was built with 3.8).
- **VERIFIED (critical) JDK requirement from class-file versions** (read the first class of each jar from Maven Central):
  - kyo-core/kyo-data/kyo-kernel/kyo-prelude/kyo-zio **1.0.0-RC6 and RC7: class major 69 = Java 25 bytecode**. They **cannot load on JDK 21** (JDK 21 reads up to major 65).
  - kyo-core **1.0.0-RC5 and RC2: major 61 = Java 17 bytecode**. kyo-cats RC5: major 61.
  - kyo-scheduler RC7: major 61 (Java 17).
  - **VERIFIED** by failure: compiling the probe with Scala 3.9.0 + kyo RC7 on JDK 21 fails inside the compiler (macro expansion loads kyo classes): `java.lang.UnsupportedClassVersionError: kyo/Frame$package$Frame$ has been compiled by a more recent version of the Java Runtime (class file version 69.0), this version of the Java Runtime only recognizes class file versions up to 65.0` (from scala-cli's bloop daemon log). scala-cli reported "exit 0" after being killed, so check the log, not only the exit code.
  - **VERIFIED full histogram (python, every .class in each jar)**:
    - kyo-data: RC2/RC3/RC4 = 61 (Java 17); **RC5 = 69 (Java 25)**; RC7 = 69.
    - kyo-kernel / kyo-prelude / kyo-core: RC2..RC5 = 61; RC6 (core) = 69; RC7 = 69.
    - kyo-scheduler RC7 = 61. kyo-cats RC5 = 61. kyo-zio RC7 = 69.
  - **VERIFIED by failure**: the RC5 probe (Scala 3.8.4, JDK 21) ALSO crashes in the compiler's inlining phase with the same `UnsupportedClassVersionError: kyo/Frame$package$Frame$ ... (class file version 69.0)`, because kyo-data RC5 (a mandatory transitive dependency) is Java 25 bytecode. This looks like a publishing slip in RC5 (kernel/prelude/core RC5 are Java 17), but the effect is real.
  - So: **Kyo RC5, RC6, RC7 all need JDK 25+ (compile JVM and runtime). The newest Kyo that runs on JDK 21 is 1.0.0-RC4** (all core jars Java 17 bytecode).
- The getkyo README still says "Requires Scala 3 and JDK 21+"; that is stale for RC5+ (see bytecode above). The scheduler docs mention "Requires JDK 21+" only for `-Dkyo.scheduler.virtualizeWorkers` (Loom) (**VERIFIED** in kyo-scheduler/Scheduler.scala). Minimum JDK for normal use: **JDK 25 for RC5+**, JDK 17 for RC2..RC4 (from bytecode, VERIFIED).

### 1.3 **Important version skew: kyo-cats stops at RC5**
- **VERIFIED** `kyo-cats_3` versions: ... 0.19.0, 1.0-RC1, 1.0.0-RC2..**RC5**. There is **no RC6 or RC7** of kyo-cats, kyo-scheduler-cats or kyo-compat-ce.
- **VERIFIED** `kyo-zio_3`, `kyo-scheduler-zio_3`, `kyo-compat-zio_3`, `kyo-zio-test_3` are at RC7.
- **VERIFIED** kyo-zio 1.0.0-RC7 depends on `dev.zio:zio_3:2.1.26` and `dev.zio:zio-streams_3:2.1.26`.
- **VERIFIED** kyo-cats 1.0.0-RC5 depends on `org.typelevel:cats-effect_3:3.7.0` and `kyo-core_3:1.0.0-RC5`.
- **VERIFIED (GitHub release notes v1.0.0-RC6)**: "kyo-cats, kyo-scheduler-cats, and kyo-compat's cats-effect (ce) binding are removed, with no in-repo replacement" (maintainers invite the community to maintain them outside the repo). So kyo-cats RC5 is the **last** kyo-cats; it will not follow Kyo forward.
- **VERIFIED (release notes RC6)**: "Kyo now requires JDK 25. The build's default compilation target moved from `-release 17` to `-release 25`" (some compat modules still target 17).
- **VERIFIED (release notes RC7)**: "Kyo now builds on Scala 3.9.0, so a project depending on the main modules needs 3.9 too."
- **VERIFIED (API diff RC5 -> RC7, from source jars)**: `Async.mask` -> `Async.uninterruptible`; `Fiber#mask` -> `Fiber#uninterruptible`; `Promise.Unsafe.initMasked` -> `initUninterruptible`; `Channel#close` now `Maybe[Seq[A]] < Async` (was `< Sync`) and `closeDiscard` added; `Sync.defer` body changed from `Safepoint ?=> A < S` to `=> A < S`; `Async.race/gather/foreach` gained an `Isolate[S, ...]` using-clause so extra effects `S` can cross forks; `Sync.acquireReleaseWith` added; `Fiber#interruptAwait` added. Earlier renames (RC1, from release notes): `IO` -> `Sync`, `Resource` -> `Scope`, `IO.apply`/`Async.apply` -> `.defer`.
- Consequence for tui4s: a tui4s-cats module that uses kyo-cats RC5 with kyo-core RC7 risks binary incompatibility (eviction RC5 -> RC7). This combination was **not tested** (it needs JDK 25, not available here). Alternative: write our own tiny CE bridge on top of kyo-core public API (Fiber/Promise + `IO.async`), which is only ~40 lines (see section 3).

### 1.4 Module list (io.getkyo, `_3` JVM artifacts; `_sjs1_3`, `_native0.5_3`, `_sjs1-wasm_3` cross builds also exist)
All **VERIFIED** from the Maven Central directory listing and source jars (RC7 unless stated):
| Module | Content (from source jar file list) |
|---|---|
| kyo-data | Pure data types, no effects: `Maybe`, `Result`, `Chunk`, `Span`, `Duration`, `Instant`, `Tag`/`ConcreteTag`/`ShallowTag`, `Frame`, `Dict`, `Record`, `Schedule`, `Ansi`, `Render`, lock-free unsafe queues (Spsc/Mpsc/Mpmc) |
| kyo-kernel | Effect runtime: `<` (Pending), `ArrowEffect`, `ContextEffect`, `Loop`, `Isolate`, `Bracket`, `Kyo` companion (`Kyo.foreach`, etc.), Safepoint, debugger |
| kyo-prelude | Pure effects: `Abort`, `Env`, `Var`, `Emit`, `Poll`, `Stream`, `Sink`, `Pipe`, `Choice`, `Batch`, `Memo`, `Local`, `Layer`, `Check`, `Aspect` |
| kyo-core | Side-effecting effects: `Sync`, `Async`, `Fiber`/`Promise`, `Scope`, `Clock`, `Console`, `Log`, `Random`, `Channel`, `Queue`, `Hub`, `Signal`, `Latch`, `Meter`, `Gate`, `Exchange`, `AtomicInt/Long/Boolean/Ref`, `Adder`, `Cache`, `Retry`, `Timeout`, `KyoApp`, stream core extensions |
| kyo-scheduler | Adaptive work-stealing scheduler (blocking monitor, admission/concurrency regulators, optional Loom). Depended on by kyo-core |
| kyo-scheduler-zio / -cats / -pekko / -finagle | Run ZIO / CE / Pekko / Finagle on the Kyo scheduler (cats variant stops at RC5) |
| kyo-direct | Direct syntax: `direct { ... v.now ... }` (`transparent inline def direct`, `.now`; built on dotty-cps-async: imports `cps.CpsMonad`) |
| kyo-combinators | ZIO-like extension methods on `A < S` (e.g. `.forever`, `.fork`, `.repeat(schedule)`, `.repeatWhile`, Abort/Env/Emit/Stream combinators) |
| kyo-zio | `ZIOs.get`/`ZIOs.run`, `ZStreams`, `ZLayers` |
| kyo-cats | `Cats.get`/`Cats.run` (RC5 only) |
| kyo-compat-zio / kyo-compat-ce / -future / -ox / -kyo | "C*" facade types (`CIO`, `CFiber`, `CChannel`...) for writing code portable across effect systems |
| kyo-stm | `STM`, `TRef`, `TMap`, `TChunk`, `TTable` |
| kyo-offheap | `Memory` (off-heap memory) |
| kyo-ffi | Kyo FFI (native library binding, codegen plugin) |
| kyo-system | `Process`, `Command`, `Path`, `FileSystem`, `PathWatch`, `System` |
| kyo-ui | **Web UI** DSL (`UI`, `Style`, `Chart`, `Svg`, reactive regions, `UIServer`) — HTML, not terminal |
| kyo-actor, kyo-http, kyo-sql*, kyo-schema*, kyo-stats-*, kyo-llm, kyo-mcp, kyo-lsp, kyo-parse, kyo-sttp, kyo-tapir, kyo-caliban, kyo-reactive-streams, kyo-flow, kyo-config, ... | Other ecosystem modules (not needed by tui4s) |
| kyo-test, kyo-os-lib, kyo-tag | **Stale**: kyo-test 0.15.1, kyo-os-lib 0.10.2, kyo-tag 0.12.2 — do not use |


## 2. Core API (version-exact)

Source of truth: `-sources.jar` of **1.0.0-RC7** from Maven Central, unpacked in `.work/scratch/kyo-src/` (RC5 in `kyo-src-rc5/`, RC4 in `kyo-src-rc4/`). Everything below is **VERIFIED in RC7 source** unless marked. "Compiled" status is in section 6.
Single import: `import kyo.*` (**VERIFIED** README: covers effects, `Maybe`, `Result`, `Chunk`, `Fiber`, `Channel`, `AtomicRef`, ...).

### 2.1 Pending type `A < S`
- `opaque type <[+A, -S] = A | Pending[A, S] | Nested[A]` (kyo-kernel `Pending.scala`); `type <[+A, -S] = kernel.<[A, S]` exported in package `kyo`.
- Plain values lift automatically: `val x: Int < Abort[String] = 42`.
- Extension methods (all `inline`, take `(using Frame)`): `map`, `flatMap` (same as map; exists for for-comprehensions), `andThen(next)`, `unit`, `ensureMap`, `handle(f1, f2, ...)` (left-to-right handler pipeline, up to 10), `flatten` on `A < S < S2`, and `eval` only on `A < Any` (fully handled).
- Effect row intersection: `A < (Sync & Abort[E] & Var[Int])`. `Any` = no effects.
- Style from CONTRIBUTING.md (**VERIFIED**): use `.map` not `.flatMap`; `.andThen(next)` not `.map(_ => next)`; `.unit` to discard; `.handle(Abort.run, Env.run(x))` for handler pipelines; avoid nested `(A < S1) < S2`.
```scala
val prog: Int < (Abort[String] & Var[Int]) =
  Var.get[Int].map(n => if n < 0 then Abort.fail("neg") else n * 2)
val r: Result[String, Int] = prog.handle(Var.run(21), Abort.run).eval
```

### 2.2 Sync (was `IO` before 1.0-RC1)
- `opaque type Sync <: Abort[Nothing]`. `Sync.defer(body)` suspends side effects (inline, by-name). Sync code "runs to completion without parking".
- `Sync.ensure(finalizer)(v)`, `Sync.ensure((err: Maybe[Error[Any]]) => ...)(v)`, `Sync.acquireReleaseWith(acquire)(release)(use)` (RC7 only), `Sync.withLocal(local)(f)`.
- Unsafe tier: `Sync.Unsafe.defer { (u: AllowUnsafe) ?=> ... }`, `Sync.Unsafe.evalOrThrow(v)`, `Sync.Unsafe.run(v)`.
- **There is no `Sync.blocking` / `Async.blocking`** (grep of kyo-core RC7: no such method). See 4.4.
```scala
def readClock: Long < Sync = Sync.defer(java.lang.System.nanoTime())
```

### 2.3 Async
- `opaque type Async <: (Sync & Async.Join)`. No `Async.run`: handled by `KyoApp`, `KyoApp.runAndBlock`, `Fiber.init*`, or integrations.
- `Async.defer`, `Async.sleep(d)`, `Async.delay(d)(v)`, `Async.never`, `Async.timeout(d)(v): A < (Abort[E | Timeout] & Async & S)`, `Async.timeoutWithError`, `Async.race(a, b, ...)` / `Async.race(seq)` (first **success**), `Async.raceFirst` (first to finish, success or failure), `Async.gather`, `Async.zip(a, b, ...)` up to 10, `Async.foreach(iter, concurrency)(f)`, `foreachDiscard`, `foreachIndexed`, `filter`, `collect`, `collectAll`, `fill`, `Async.memoize`, `Async.uninterruptible(v)` (was `Async.mask` up to RC5), `Async.fromFuture(Future)`, JVM: `Async.fromCompletionStage`, `Async.fromCompletableFuture` (cancels the CF on interrupt).
- Default concurrency for collection ops = 2 x CPUs; override with `-Dkyo.async.concurrency.default`.
```scala
val raced: String < Async = Async.race(Async.sleep(50.millis).andThen("slow"), Async.sleep(1.millis).andThen("fast"))
val t: String < (Async & Abort[Timeout]) = Async.timeout(10.millis)(Async.sleep(1.second).andThen("late"))
val both: (Int, Int) < Async = Async.zip(Async.defer(1), Async.defer(2))
```

### 2.4 Abort
- `Abort.fail(e)`, `Abort.panic(ex)`, `Abort.error(err)`, `Abort.when(cond)(e)`, `Abort.unless`, `Abort.ensuring`, `Abort.get(either|option|try|result|maybe)`.
- Handlers: `Abort.run[E](v): Result[E, A] < S`, `Abort.runWith`, `Abort.runPartial`, `Abort.recover`, `Abort.recoverError`, `Abort.fold`, `Abort.foldError`, `Abort.catching[E <: Throwable](v)`, `Abort.ignore`, `Abort.tap`, `Abort.tapError`, `Abort.literal.fail["x"]` for singleton errors.
- `Result[E, A]` = `Success | Failure(e) | Panic(throwable)`.

### 2.5 Env
- `Env.get[R]: R < Env[R]` (needs `Tag[R]`, and `R` must not be an intersection), `Env.use[R](f)`, `Env.getAll[R]: TypeMap[R]`, `Env.run(value)(v)`, `Env.runAll(typeMap)(v)`. Layers: `Layer`, `Memo` in kyo-prelude.

### 2.6 Var (pure state)
- `Var.get[V]`, `Var.use[V](f)`, `Var.set(v): V < Var[V]` (returns previous), `Var.setDiscard`, `Var.setWith(v)(f)`, `Var.update[V](f): V < Var[V]` (returns new), `Var.updateDiscard`, `Var.updateWith(f)(g)`.
- Handlers: `Var.run(init)(v): A < S`, `Var.runTuple(init)(v): (V, A) < S`.
- Crossing fibers: `Var.isolate.update[V]`, `Var.isolate.merge[V](f)`, `Var.isolate.discard[V]` (an `Isolate` given for `Async.foreach` etc.).
- Every op needs `Tag[Var[V]]` (derived automatically for concrete V).
```scala
val counter: Int < Var[Int] = Var.update[Int](_ + 1).andThen(Var.get[Int])
val (finalState, out) = Var.runTuple(0)(counter).eval  // (1, 1)
```

### 2.7 Emit
- `Emit.value(v): Unit < Emit[V]`, `Emit.valueWhen(cond)(v)`, `Emit.valueWith(v)(next)` (emit then continue, used inside `Loop`).
- Handlers: `Emit.run[V](v): (Chunk[V], A) < S`, `Emit.runFold[V](acc)(f)(v)`, `Emit.runDiscard[V](v)`, `Emit.runForeach[V](v)(f)`, `Emit.runWhile[V](v)(f): Maybe[A]` (false stops the emitter).
- `Emit.isolate.merge / discard`.

### 2.8 Stream (kyo-prelude; fiber-based extensions in kyo-core)
- `abstract class Stream[+V, -S]` whose `emit: Unit < (Emit[Chunk[V]] & S)`; streams are chunked.
- Constructors: `Stream(emitEffect)` (inline apply), `Stream.empty`, `Stream.init(seq, chunkSize = 4096)`, `Stream.repeatPresent(v: => Maybe[Seq[V]] < S, chunkSize = 4096)`, `Stream.range(start, end, step)`, `Stream.unfold(acc)(f)`, `Stream.unwrap(streamEffect)`; kyo-core adds `Stream.fromIterator`, `Stream.fromIteratorCatching`, `Stream.fromInputStream`, `Stream.collectAll`, `Stream.collectAllHalting`; `Channel#stream(maxChunkSize)`, `Channel#streamUntilClosed`, `Hub.Listener#stream`, `Signal#streamChanges`, `Signal#streamCurrent`.
- **No `Stream.repeat*` / `Stream.tick` / `Stream.fromQueue` in RC7** (grep). A periodic/polling stream is written with `Stream(Loop.forever(...Emit.value(Chunk(x))...))` or `repeatPresent`.
- Ops: `map`, `mapPure`, `mapChunk`, `flatMap`, `tap`, `take`, `drop`, `takeWhile`, `dropWhile`, `filter`, `filterPure`, `collect`, `collectWhile`, `changes` (dedupe consecutive), `rechunk`, `concat`, `splitAtWith`, `into(pipe|sink)`, `find`; kyo-core: `merge`, `mergeHalting*`, `mapPar`, `mapParUnordered`, `mapChunkPar`, `broadcast2..5/N`, `broadcasted`, `broadcastDynamic`, `groupedWithin(maxSize, maxTime)`.
- Terminal: `run: Chunk[V] < S`, `fold(acc)(f)`, `foldPure`, `foreach(f)`, `foreachChunk(f)`, `discard`.
- **Gotcha (VERIFIED in source)**: `repeatPresent` ends with `.rechunk(chunkSize)` and `rechunk` buffers until `chunkSize` elements (or an empty chunk / end). With the default 4096, a slow event source would not emit anything until 4096 events arrive. For UI events always pass `chunkSize = 1` or build the stream with `Stream(Loop...)` emitting `Chunk(ev)`.
```scala
val evens: Stream[Int, Any] = Stream.init(1 to 10).map(_ * 2).filter(_ % 4 == 0)
val sum: Int < Any = evens.fold(0)(_ + _)
def polling(q: java.util.Queue[Ev]): Stream[Ev, Sync] =
  Stream.repeatPresent(Sync.defer(Maybe(q.poll()).map(Seq(_))), chunkSize = 1)
```

### 2.9 Channel / Queue / Hub (kyo-core)
- `Channel.init[A](capacity, access = Access.MultiProducerMultiConsumer): Channel[A] < (Sync & Scope)` (closed when the Scope closes), `Channel.initWith`, `Channel.use`, `Channel.initUnscoped`. Ops: `put` (Async, backpressure), `putBatch`, `take`, `takeWith`, `takeExactly(n)`, `offer`/`offerDiscard`/`poll` (non-blocking), `drain`, `drainUpTo(max)`, `close: Maybe[Seq[A]] < Async` (RC6+; was `< Sync`), `closeDiscard`, `closeAwaitEmpty`, `stream(maxChunkSize)`, `streamUntilClosed`. Errors: `Abort[Closed]`.
- `Queue.init[A](capacity)` (Sync-only ops, no parking), `Queue.Unbounded.init`, `initDropping`, `initSliding` (sliding is useful for "latest N events").
- `Hub.init[A](capacity)` broadcast; `hub.listen` / `listen(filter)` gives a `Listener` with `take`/`stream`. Caution from README: a stalled listener blocks all publishers.
- `Access` lets you pick SPSC/MPSC/etc. for lock-free specialisation.

### 2.10 Signal (reactive value, kyo-core) — the key primitive for a reactive TUI
- `Signal.initRef[A](initial): SignalRef[A] < Sync` (needs `CanEqual[A, A]`), `Signal.initRefWith`, `Signal.initConst`, `Signal.initRaw`.
- `Signal[A]`: `current: A < Sync`, `currentWith(f)`, `next: A < Async` (parks until a DIFFERENT value), `nextWith(f)`, `observe(f: A => Unit < (S & Async & Scope))` (runs f for current and each change, each in a fresh Scope that is closed before the next value: switch-with-resources; runs forever, so fork it), `map`, `switchMap`, `zip` (both changed), `combineLatest` (either changed), `Signal.combineLatestAll(seq)`, `Signal.zipAll`, `Signal.awaitAny(seq)`, `streamChanges: Stream[A, Async]`, `streamCurrent`.
- `SignalRef[A]` adds `get`, `use`, `set`, `getAndSet`, `compareAndSet`, `getAndUpdate`, `updateAndGet`, `waiters`, `.unsafe` (lock-free, backed by an atomic ref + promise).
- Semantics (**VERIFIED** doc comment): setting an equal value notifies nobody (dedup is built in); no `filter` on Signal; `streamChanges` may skip intermediate values (latest-value semantics, good for rendering; use a Channel if every change matters).
```scala
val demo: Chunk[Int] < Async = Signal.initRef(0).map { ref =>
  Fiber.initUnscoped(ref.streamChanges.take(3).run).map { f =>
    ref.set(1).andThen(ref.set(1)).andThen(ref.set(2)).andThen(f.get)
  }
}
```

### 2.11 Scope (was `Resource` before 1.0-RC1)
- `Scope.acquireRelease(acquire)(release: A => Any < (Async & Abort[Throwable])): A < (Scope & Sync & S)`, `Scope.acquire(autoCloseable)`, `Scope.ensure(finalizer)`, `Scope.ensure((err: Maybe[Error[Any]]) => ...)`, `Scope.run(v): A < (Async & S)`, `Scope.run(closeParallelism)(v)`.
- Finalizers run in reverse order when `Scope.run` completes (success, failure or interrupt). `KyoApp.run` provides a Scope.
```scala
def rawMode: Unit < (Scope & Sync) =
  Scope.acquireRelease(Sync.defer(enableRawMode()))(_ => Sync.defer(disableRawMode()))
```

### 2.12 Fiber / Promise
- `Fiber.init(v): Fiber[A, ...] < (Sync & S & Scope)` (**structured**: interrupted when the enclosing Scope closes), `Fiber.use(v)(f)` (bracket: interrupted after f), `Fiber.initUnscoped(v)` (no automatic interrupt), `Fiber.succeed/fail/panic/never/fromFuture/fromResult`.
- There is no `Fiber.run` or `join`: start with `Fiber.init`/`initUnscoped`, join with `fiber.get` (or `getResult`, `use`).
- Fiber ops: `get`, `use`, `getResult`, `useResult`, `onComplete`, `onInterrupt`, `interrupt: Boolean < Sync`, `interruptAwait` (RC6+), `block(timeout)` (JVM only), `poll`, `done`, `map`, `flatMap`, `mapResult`, `uninterruptible` (was `mask`), `toFuture`.
- `Promise.init[A, S]`, `Promise.initWith`, `complete`, `completeDiscard`, `completeUnit`, `become`, `poll`; `Promise.Unsafe.init()`.

### 2.13 Clock
- `Clock.now: Instant < Sync`, `Clock.nowMonotonic: Duration < Sync`, `Clock.stopwatch` (`sw.elapsed`), `Clock.deadline(d)`, `Clock.sleep(d)` (returns a Fiber), `Clock.repeatWithDelay(delay)(task)`, `Clock.repeatAtInterval(interval)(task)` / `(startAfter, interval)`: both return `Fiber[Unit, ...] < Sync` (unscoped fiber; interrupt it yourself), `Clock.withTimeControl(f)` (virtual time for tests: `set`, `advance`), `Clock.withTimeShift`, `Clock.let(clock)(v)`.
- Durations: `10.millis`, `2.seconds`, `1.second` (only literal 1) from `kyo.Duration` extensions.

### 2.14 Loop (kyo-kernel, exported as `kyo.Loop`)
- `Loop(init)(s => if ... then Loop.continue(next) else Loop.done(result))`, 1 to 4 state values (`Loop(a, b)((a, b) => Loop.continue(a2, b2))`: no tuple allocation per round), `Loop.indexed(i => ...)`, `Loop.indexed(init)((i, s) => ...)`, `Loop.foreach(body returning Loop.continue / Loop.done)`, `Loop.repeat(n)(body)`, `Loop.forever(body): Nothing < S`, `Loop.whileTrue(cond)(body)`.
- Doc (**VERIFIED**): "Recursion in kyo is already stack-safe ... an explicit loop is cheaper and reads better".
```scala
val sum: Int < Any = Loop(0, 0)((i, acc) => if i < 10 then Loop.continue(i + 1, acc + i) else Loop.done(acc))
```

### 2.15 Latch / Meter / Gate
- `Latch.init(n): Latch < Sync`, `Latch.initWith(n)(f)`, `latch.release: Unit < Sync`, `latch.await: Unit < Async`, `latch.pending`.
- `Meter.initMutex: Meter < (Sync & Scope)`, `Meter.useMutex(f)`, `Meter.initSemaphore(n)`, `Meter.useSemaphore(n)(f)`, `Meter.initRateLimiter(rate, period)`, `*Unscoped` variants, `Meter.pipeline(m1, m2, ...)`. Ops: `run(v): A < (S & Async & Abort[Closed])`, `tryRun`, `availablePermits`, `close`.
- `Gate.init(parties)` = CyclicBarrier-like.

### 2.16 Atomics
- `AtomicInt.init(v)`, `AtomicLong.init(v)`, `AtomicBoolean.init(v)`, `AtomicRef.init(v)`: `... < Sync`; `initWith(v)(f)`. Ops (all inline, `< Sync`): `get`, `use(f)`, `set`, `lazySet`, `getAndSet`, `compareAndSet`, `getAndUpdate`, `updateAndGet`, plus `incrementAndGet` etc. for numbers. `LongAdder`/`DoubleAdder` for write-heavy counters. `.unsafe` exposes the raw `java.util.concurrent.atomic` object.

### 2.17 KyoApp (main)
```scala
object Main extends KyoApp:
  run {                                  // A < (Async & Scope & Abort[Any]); several run blocks run in order
    Console.printLine("hello")           // a non-Unit result is printed to stdout, so end in Unit
  }
```
- `KyoApp(v)` builds one from a value. `args: Chunk[String]`. `KyoApp.runAndBlock(timeout)(v): A < (Abort[E | Timeout] & Sync & S)`; `KyoApp.Unsafe.runAndBlock(timeout)(v)(using AllowUnsafe): Result[Throwable, A]` for embedding (tests, other runtimes).


## 3. kyo-zio and kyo-cats

### 3.1 kyo-zio (1.0.0-RC7, compiled against ZIO 2.1.26) — **VERIFIED from source `kyo-zio/kyo/ZIOs.scala`**
```scala
object ZIOs:
  def get[E, A](v: => ZIO[Any, E, A])(using Frame, zio.Trace): A < (Abort[E] & Async)
  inline def get[R: zio.Tag, E, A](v: ZIO[R, E, A])(using Tag[Env[R]], Frame, zio.Trace): A < (Env[R] & Abort[E] & Async)
      // = compiletime.error("ZIO environments are not supported yet...")
  def run[A, E](v: => A < (Abort[E] & Async))(using Frame, zio.Trace): ZIO[Any, E, A]
  extension (exit: Exit[E, A]) def toResult: Result[E, A]
  extension (cause: Cause[E]) def toError: Result.Error[E]
  extension (err: Result.Error[E]) def toCause: Cause[E]
  extension (r: Result[E, A]) def toExit: Exit[E, A]
object ZStreams:
  def get[E, A](stream: => ZStream[Any, E, A])(using Frame, Trace, Tag[Emit[Chunk[A]]]): Stream[A, Abort[E] & Async]
  def run[E, A](stream: => Stream[A, Abort[E] & Async])(using Frame, Trace, Tag[Emit[Chunk[A]]], ShallowTag[A]): ZStream[Any, E, A]
object ZLayers:
  def get[E, A: ZTag: Tag](layer: => ZLayer[Any, E, A])(using Frame, Trace): Layer[A, Abort[E] & Async & Scope]
  def run[A: ZTag: Tag, E](layer: Layer[A, Abort[E] & Async])(using Frame, zio.Trace): ZLayer[Any, E, A]
```
- **Allowed effects for `ZIOs.run`: only `Abort[E] & Async`** (Async includes Sync). Handle `Scope` (`Scope.run`), `Env`, `Var`, `Emit`, etc. before. Scope maps as: run `Scope.run(kyo)` inside the Kyo computation; or, to tie a Kyo resource to a ZIO scope, wrap with `ZIO.acquireRelease(ZIOs.run(acquire))(r => ZIOs.run(release(r)).orDie)`. `ZLayers.get` gives a Kyo `Layer` that needs `Scope` (the ZIO scope is closed by a Kyo `Scope.ensure`).
- `ZIOs.get` forks the ZIO on **`zio.Runtime.default`** (not the caller's runtime; custom ZIO runtime config such as loggers is not used). Kyo interrupt -> `f.unsafe.interrupt(...)` on the ZIO fiber.
- `ZIOs.run` starts the Kyo computation with `Fiber.initUnscoped` on Kyo's scheduler, inside `ZIO.uninterruptibleMask`; ZIO interruption interrupts the Kyo fiber **and waits** for it to finish (so Kyo finalizers run before the ZIO fiber reports interrupted).
- ZIO interruption becomes `Result.Panic(Interrupted(...))` on the Kyo side (README).
- Optional: `kyo-scheduler-zio` (RC7) lets ZIO itself run on the Kyo scheduler (**UNVERIFIED** usage details; not needed for tui4s).

### 3.2 kyo-cats (last version 1.0.0-RC5, compiled against cats-effect 3.7.0, Scala 3.8.4) — **VERIFIED from source `kyo-cats/kyo/Cats.scala`** (68 lines)
```scala
object Cats:
  def get[A](io: => cats.effect.IO[A])(using Frame): A < (Abort[Nothing] & Async)
  def run[A](v: => A < (Abort[Throwable] & Async))(using Frame): cats.effect.IO[A]
```
- **Allowed effects for `Cats.run`: only `Abort[Throwable] & Async`.** Errors other than Throwable must be mapped first (e.g. `Abort.run` then `Abort.fail(new Exception(...))`); Scope must be closed with `Scope.run` first.
- `Cats.get` runs the IO with `unsafeToFutureCancelable()` on **`cats.effect.unsafe.implicits.global`** (hard-coded global IORuntime, not the caller's). IO failure -> `Result.Panic` (not a typed Abort, the row is `Abort[Nothing]`). Kyo interrupt -> cancels the IO.
- `Cats.run` = `IO.defer` + `Fiber.initUnscoped` + `IO.async` whose finalizer interrupts the Kyo fiber and waits for completion (cancel-and-await).
- No `Resource` or `fs2.Stream` bridges exist in kyo-cats (only IO). Streams would be tui4s's own code (e.g. fs2 via a Kyo `Channel`, or `Stream` -> `fs2.Stream.unfoldChunkEval`).
- **Removed upstream in RC6** (see 1.3). Mixing kyo-cats RC5 with kyo-core RC7 means kyo-core is evicted to RC7 under code compiled against RC5 (with inlined kernel internals). Binary compatibility is **not guaranteed and was not verified** (no JDK 25 on this machine).
- **Recommendation for tui4s-cats**: do not depend on kyo-cats. Vendor the ~40 lines of `Cats.get`/`Cats.run` (Apache-2.0, keep the notice) into the tui4s-cats module and compile them against the Kyo version tui4s uses. The code only uses public RC7 API that kyo-zio RC7 also uses (`Sync.Unsafe.defer`, `Promise.Unsafe.init`, `onInterrupt`, `Fiber.initUnscoped`, `fiber.unsafe.onComplete`, `fiber.unsafe.interrupt()`, `.handle(Sync.Unsafe.evalOrThrow)`). Improve it by taking an `IORuntime` parameter instead of the global one, and add `Resource`/fs2 bridges if wanted.
- Alternative CE-side approach with no Kyo interop module: expose tui4s-cats as a CE-native API (`IO`, `Resource`, `fs2.Stream`) implemented on top of the tui4s core by running Kyo programs through the vendored bridge. The same shape for tui4s-zio (`ZIO`, `Scope`, `ZStream`) via kyo-zio.


## 4. Idiomatic style and performance

### 4.1 House style (VERIFIED: getkyo/kyo `CONTRIBUTING.md` and `AGENTS.md` on main, 2026-09-30)
- Think in values: a Kyo computation is a value; keep the effect row honest (`< Sync`, `< Async`, `< Abort[E]`), never widen it to shorten a signature.
- Use `.map` (not `.flatMap`), `.andThen`, `.unit`; prefer `.map` chains, use for-comprehensions when many dependent steps; `.handle(Abort.run, Env.run(x))` for handler pipelines; prefer `Abort.recover` over `Abort.run` + match.
- Kyo types: `Maybe` not `Option`, `Result` not `Either`, `Chunk` not `List`, `Span` over `Array` for immutable arrays.
- Required scalac flags from the README (**VERIFIED**): `-Wvalue-discard`, `-Wnonunit-statement`, `-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error`, `-language:strictEquality` (catches silently dropped effects, e.g. a `Sync.defer(...)` statement that is never sequenced; `strictEquality` means your types need `derives CanEqual`, which Signal needs anyway).
- `Scope`-managed resources are the default (`init`/`initWith`, then `initUnscoped` only for a reason).
- Using-clause order: inline methods `Tag` before `Frame`; non-inline `Frame` before evidence; `AllowUnsafe` last. Use `(using Frame)` as a type-parameter separator: `def get[E](using Frame)[A](...)`.
- `Frame` on every method that suspends or handles effects (not on pure accessors).

### 4.2 Inline guidance (VERIFIED CONTRIBUTING.md "Inline Guidelines")
- Inline the **creation** path (where effects are born: `Abort.fail`, `Var.set`, `Emit.value`) and methods with function/by-name parameters (avoids a `Function1` allocation). Do **not** inline handlers (`Abort.run`, `Var.run`, `Emit.runFold`) or methods with only value params.
- "Inline as little as possible": mark the function params `inline` and keep the body small; for a non-trivial body, delegate to a local non-inline `def`.
- "When in doubt, don't inline" (code bloat and slower compile).
- For tui4s: widget constructors and the `render` hot path should be plain methods on final classes; only small combinators taking lambdas (`onKey(f)`, `use(f)`) should be `inline`.

### 4.3 Allocation and loops (VERIFIED)
- `Loop` over recursion: recursion is stack-safe but `Loop` is cheaper; `Loop(a, b, c, d)` carries up to 4 state values without tuple allocation.
- Pure fast paths: `mapPure`, `filterPure`, `foldPure` on Stream when no suspension is needed; check degenerate cases (empty, single) first.
- `final class` / `abstract class` over `trait` (interface dispatch is slower on the JVM).
- `AllowUnsafe` for zero-allocation side effects: one `Sync.Unsafe.defer` around a block that calls many `(using AllowUnsafe)` methods, instead of one `Sync.defer` per call. This is the right shape for the tui4s render loop: `Sync.Unsafe.defer { terminal.draw(frame => ...) }` once per frame, with the tui-scala widget rendering done as plain imperative Scala inside.
- Bit-pack atomic composite state; avoid the "erased tag" pattern.

### 4.4 Var vs AtomicRef vs SignalRef (VERIFIED semantics; recommendation is mine)
- `Var[V]`: pure, single-fiber, handled by `Var.run`; zero synchronization; it does **not** cross fibers unless you pass `Var.isolate.*`. Good for an Elm-style `update(msg): Unit < Var[Model]` reducer that is run inside one event loop.
- `AtomicRef[A]`: shared mutable cell across fibers, `< Sync`, lock-free CAS; no change notification.
- `SignalRef[A]`: `AtomicRef` + change notification (`next`, `streamChanges`, `observe`), dedups equal values. Best fit for "model changed -> redraw".
- Suggested tui4s core shape: model in `SignalRef[Model]`; input fiber turns terminal events into messages; `update` is a pure function or `Var`-based reducer run per message; a render fiber consumes `model.streamChanges` (latest-value semantics naturally coalesce bursts) and draws. Frame-rate cap via `Async.sleep` between draws or `groupedWithin`.

### 4.5 Blocking calls (JNI reads) and the scheduler (VERIFIED in kyo-scheduler source/README)
- There is **no `Sync.blocking`**. The Kyo scheduler detects blocked workers by sampling per-thread **user CPU time** (BlockingMonitor, default every 2 ms): a worker whose CPU time does not advance is marked blocked, new tasks are routed away, and the pool grows (up to `maxWorkers`, default `coreWorkers * 100`). "tasks can freely perform blocking operations without concerning themselves with thread management." So a blocking JNI call inside `Sync.defer` is tolerated.
- Interruption: the monitor calls `Thread.interrupt()` on a blocked worker only for tasks that opt in (`needsInterrupt()`); Kyo's own `IOTask` wires this for fiber cancellation. **A thread blocked inside native (JNI) code does not react to `Thread.interrupt()`**, so interrupting a fiber stuck in a blocking crossterm `read()` will not unblock it (**UNVERIFIED for crossterm specifically; true for JNI in general**).
- Recommendation for tui4s input: never do an unbounded blocking read. Loop `crossterm.poll(timeout = 10-50 ms)` then `read()` only when `poll` says an event is ready, inside `Sync.defer`, driven by `Loop.forever` in a dedicated fiber that pushes into a `Channel`/`Queue` or sets a `SignalRef`. Interruption then lands between polls. Alternatively use `Async.fromCompletableFuture` over a dedicated single platform thread for reads (still needs poll timeouts to stop the thread).
- Loom: `-Dkyo.scheduler.virtualizeWorkers=true` plus `--add-opens=java.base/java.lang=ALL-UNNAMED` (JDK 21+) makes blocking calls unmount virtual threads; but a JNI call pins the carrier anyway, so it does not help crossterm.
- On non-HotSpot JVMs the blocking detection is disabled (README note).


## 5. Tag / Frame / compile gotchas

- **Tag (VERIFIED `kyo-data/Tag.scala`, `TagMacro.scala`)**: parametric effects need a `Tag` of the effect type: `Var[V]` needs `Tag[Var[V]]`, `Emit[V]` needs `Tag[Emit[V]]`, `Env[R]` needs `Tag[R]`, `Stream[V, S]` ops need `Tag[Emit[Chunk[V]]]`. For concrete types the macro derives them. In **generic code** the macro needs a Tag for the type parameter, else the error is `Please provide an implicit kyo.Tag[S] parameter.` Fix: `def step[M](using Tag[Var[M]], Frame): Unit < Var[M]` or `[M: Tag]`.
- Dynamic tags (built at runtime from other tags) are refused **inside package `kyo`** ("Dynamic tags aren't allowed in the kyo package for performance reasons"). Do not put tui4s code in package `kyo`.
- **Opaque-type trap**: inside an opaque type's template/companion, `Env.get[X]`/`Var.get[X]` would derive the tag of the underlying type; the macro refuses with `[Tag.opaque.collapsed]` / `[Tag.opaque.given]` / `[Tag.opaque.unwalkable]`. Remedy: pass `Tag.derive[X]` explicitly or move the code out of the companion. Relevant if tui4s models `Rect`, `Style` etc. as opaque types and uses them in `Var`/`Env`.
- `Env.get[R]` requires `NotIntersection[R]` (ask for each service separately).
- **Frame**: `Frame` is a macro-materialized implicit (source position) required by almost every method; in your own generic helpers add `(using Frame)` (inline methods: `(using inline frame: Frame)`), otherwise the Frame points at your helper instead of the caller.
- **CanEqual**: Signal requires `CanEqual[A, A]`; with `-language:strictEquality` your enums/case classes need `derives CanEqual`. `Signal.initRef` has an `@implicitNotFound(missingCanEqual)` message.
- **Effect row inference**: sometimes needs a type ascription or `Kyo.lift` (CONTRIBUTING.md). The probe gave explicit type args to `Stream.repeatPresent[Ev, Sync]` and `Emit.value[Ev]` so that the enum case is widened to `Ev` (otherwise `Emit.value(Ev.Tick)` would infer `Emit[Ev.Tick.type]`-style singleton/case types) (**UNVERIFIED** that it fails without them; it compiled with them).
- `Async.race/gather/foreach/timeout` in RC7 take `using Isolate[S, Abort[E] & Async, S]`: with extra effects like `Var` in the forked computation you must provide an `Isolate` (e.g. `Var.isolate.update[Int]`), else a compile error.
- **KyoApp `run` prints non-Unit results**: end the block in `Unit`.
- **Compile-time / tooling costs (VERIFIED)**: Kyo's `Frame` and `Tag` are macros; the compiler JVM must be able to **load Kyo classes** (Java 25 bytecode from RC5-data / RC6+), so sbt/bloop/Metals must run on JDK 25 for RC5+ (not only the runtime). Failure mode seen: compiler crash in phase `inlining` with `UnsupportedClassVersionError`, and `scala-cli run` then **hangs** instead of exiting (had to kill it).
- The README says "Requires Scala 3 and JDK 21+" but that line is **stale** for RC5+ (bytecode says Java 25).
- Metals is recommended over IntelliJ's own Scala 3 support (README).


## 6. Probe script (compiled and run)

### 6.1 What was run
| Variant | File | Scala | Kyo | JDK | Result |
|---|---|---|---|---|---|
| RC7 (target) | `.work/scratch/kyo-probe.scala` | 3.9.0 | core/zio RC7 + cats RC5 | 21 | **Compiler crash** `UnsupportedClassVersionError` (class file 69 = Java 25). Not runnable here. |
| RC5 | `.work/scratch/rc5/kyo-probe-rc5.scala` | 3.8.4 | RC5 (core, zio, cats) | 21 | **Same compiler crash** (kyo-data RC5 is Java 25 bytecode); `scala-cli run` then hung and had to be killed. |
| **RC4** | `.work/scratch/rc4/kyo-probe-rc4.scala` | 3.8.3 | RC4 (core, zio, cats) + zio 2.1.26 + cats-effect 3.7.0 | 21.0.8 (bloop on Temurin 21.0.12) | **VERIFIED: compiled on the first try and ran, exit 0.** |

- The three files differ only in the `//> using` header. **Every API call in the probe exists with the same signature in RC7** (grep of RC7 source jars: `Var.update/updateDiscard/get/run/runTuple`, `Emit.value/run/runFold`, `Stream.init/repeatPresent/map/filter/fold/run/takeWhile`, `Env.use/run`, `Abort.fail/run`, `Scope.acquireRelease/run`, `Channel.init/put/take`, `Latch.init/release/await`, `Fiber.init/get`, `Signal.initRef/set/streamChanges`, `Async.sleep/timeout/race/zip`, `Meter.useMutex/run`, `Loop/Loop.indexed`, `Clock.stopwatch/elapsed`, `AtomicRef.init/updateAndGet/get`, `KyoApp`, `KyoApp.Unsafe.runAndBlock`, `ZIOs.get/run`, `Cats.get/run`). **Compiling it against RC7 is UNVERIFIED** (needs JDK 25). Main RC7 risk: `Async.race/zip/timeout` gained an `Isolate` using-clause (macro-derived given, should be transparent for `S = Any`).
- scala-cli note: with `//> using jvm 21` scala-cli downloaded its own Temurin 21.0.12 for the bloop server (x64 build, this is an Intel Mac). The RC7 file no longer has a `using jvm` line, because `using jvm 25` would make scala-cli fetch a JDK through coursier.

### 6.2 Observed output (VERIFIED, RC4 on JDK 21)
```
[Kyo] counter=(11,11) emitted=Chunk(Key(a), Tick, Key(b))/emitted folded=2 ticks=(1,emitted)
stream=Chunk.Indexed(4, 8, 12, 16, 20) sum=60 polled=Chunk.Indexed(Key(p), Tick)
env=TUI4S bad=Failure(empty title)
async=keys=2 signal=Chunk.Indexed(0, 1, 2) timeout=true race=hare zip=(1,2) mutex=in-mutex loopIdx=5 termOpenInsideScope=true
log=Chunk(async elapsed ~530ms, released)
[ZIO] zio-bridge 42 | counter=(11,11) emitted=Chunk(Key(a), Tick, Key(b))/emitted folded=2 ticks=(1,emitted)
[CE] ce-bridge 21 | counter=(11,11) emitted=Chunk(Key(a), Tick, Key(b))/emitted folded=2 ticks=(1,emitted)
```
What it proves:
- `Var.runTuple(0)(counter)` -> `(11, 11)`: update/updateDiscard/get work and runTuple returns `(state, result)`.
- `Emit.run[Ev]` collects `Chunk(Key(a), Tick, Key(b))` plus the result; `Emit.runFold` returns `(acc, result)`; folding events into a `Var` works (`folded=2`).
- `Stream.init(...).map.filter.run` and `.fold`; `Stream.repeatPresent(..., chunkSize = 1)` over a Java queue + `takeWhile` works as a polling source.
- `Env.run` + `Abort.run` -> `TUI4S` and `Failure(empty title)`.
- `Scope.acquireRelease`: resource open inside the scope, `released` logged after the scope closed (finalizer order ok).
- `Channel` + scoped `Fiber.init` producer + `Loop` consumer; `Latch`; `Signal.streamChanges` gave `Chunk(0, 1, 2)` (the repeated `set(1)` was deduplicated); `Async.timeout` failed with `Timeout`; `Async.race` picked the fast one; `Async.zip`; `Meter.useMutex`; `Loop.indexed`.
- `KyoApp.Unsafe.runAndBlock` works as a plain entry point; `ZIOs.run` inside a ZIO runtime and `ZIOs.get` of a ZIO inside Kyo; `Cats.run` + `unsafeRunSync()` and `Cats.get` of an IO inside Kyo.
- scala-cli hint: cats-effect **3.7.1** is the latest (VERIFIED Maven metadata, 2026-08-24); ZIO latest **2.1.26** (2026-05-06); fs2 latest 3.14.0.

### 6.3 Working probe source (RC4 header; body identical to the RC7 file)
```scala
//> using scala 3.8.3
//> using jvm 21
//> using dep io.getkyo::kyo-core:1.0.0-RC4
//> using dep io.getkyo::kyo-zio:1.0.0-RC4
//> using dep io.getkyo::kyo-cats:1.0.0-RC4
//> using dep dev.zio::zio:2.1.26
//> using dep org.typelevel::cats-effect:3.7.0
//> using mainClass probe

// Kyo 1.0.0-RC4 API probe (last Kyo with Java 17 bytecode; runs on JDK 21) for tui4s. Run with:
//   source .work/env.sh && scala-cli run .work/scratch/kyo-probe.scala
import kyo.*

// ---------- Plain data used by the probe ----------
final case class Config(title: String)
enum Ev derives CanEqual:
    case Key(c: Char)
    case Tick
    case Quit

// ---------- 1. Var: a counter, pure state ----------
val counter: Int < Var[Int] =
    for
        _ <- Var.update[Int](_ + 1)
        _ <- Var.updateDiscard[Int](_ + 10)
        n <- Var.get[Int]
    yield n

// ---------- 2. Emit: push events, then collect or fold ----------
val emitter: String < Emit[Ev] =
    Emit.value[Ev](Ev.Key('a'))
        .andThen(Emit.value[Ev](Ev.Tick))
        .andThen(Emit.value[Ev](Ev.Key('b')))
        .andThen("emitted")

// Emit + Var together: an update loop that folds events into state.
def reduce(ev: Ev): Unit < Var[Int] = ev match
    case Ev.Key(_) => Var.updateDiscard[Int](_ + 1)
    case _         => ()

// ---------- 3. Stream: init, map/filter/fold/run, polling source ----------
val evens: Stream[Int, Any] = Stream.init(1 to 10).map(_ * 2).filter(_ % 4 == 0)

// A polling source: drains a mutable Java queue until empty. chunkSize = 1 so each item is emitted
// immediately (the default 4096 makes rechunk buffer until 4096 items or end-of-stream).
def pollingStream(q: java.util.concurrent.ConcurrentLinkedQueue[Ev]): Stream[Ev, Sync] =
    Stream.repeatPresent[Ev, Sync](Sync.defer(Maybe(q.poll()).map(Seq(_))), chunkSize = 1)

// ---------- 4. Env + Abort ----------
val needsConfig: String < (Env[Config] & Abort[String]) =
    Env.use[Config] { cfg =>
        if cfg.title.isEmpty then Abort.fail("empty title") else cfg.title.toUpperCase
    }

// ---------- 5. Scope: acquire/release a resource (e.g. raw mode / alt screen) ----------
final class FakeTerminal:
    @volatile var open = true
    def close(): Unit  = open = false

def terminalResource(log: AtomicRef[Chunk[String]]): FakeTerminal < (Scope & Sync) =
    Scope.acquireRelease(Sync.defer { new FakeTerminal })(t =>
        Sync.defer(t.close()).andThen(log.updateAndGet(_.append("released")).unit)
    )

// ---------- 6. Async + Fiber + Channel + Signal + Clock + Loop + Latch + Meter ----------
def asyncPart(log: AtomicRef[Chunk[String]]): String < (Async & Abort[Closed] & Scope) =
    for
        start   <- Clock.stopwatch
        channel <- Channel.init[Ev](16)                       // scoped: closed when Scope closes
        latch   <- Latch.init(1)
        producer <- Fiber.init {                              // scoped fiber: interrupted at Scope close
            Kyo.foreachDiscard(Seq(Ev.Key('x'), Ev.Tick, Ev.Key('y'), Ev.Quit))(e =>
                Async.sleep(5.millis).andThen(channel.put(e))
            ).andThen(latch.release)
        }
        // Consume until Quit with Loop (state = count of keys)
        keys <- Loop(0) { n =>
            channel.take.map {
                case Ev.Quit   => Loop.done(n)
                case Ev.Key(_) => Loop.continue(n + 1)
                case Ev.Tick   => Loop.continue(n)
            }
        }
        _       <- latch.await
        // Signal: reactive value, streamChanges drops duplicates
        sig     <- Signal.initRef(0)
        watcher <- Fiber.init(sig.streamChanges.take(3).run)
        _       <- Async.sleep(20.millis)                    // let the watcher subscribe
        _       <- sig.set(1)
        _       <- Async.sleep(20.millis)
        _       <- sig.set(1)                                // same value: no notification
        _       <- sig.set(2)
        seen    <- watcher.get
        // timeout + race
        slow    <- Abort.run[Timeout](Async.timeout(10.millis)(Async.sleep(1.second).andThen("slow")))
        winner  <- Async.race(Async.sleep(50.millis).andThen("tortoise"), Async.sleep(1.millis).andThen("hare"))
        par     <- Async.zip(Async.sleep(10.millis).andThen(1), Async.sleep(10.millis).andThen(2))
        // Meter as a mutex
        mutexed <- Meter.useMutex(m => m.run(Sync.defer("in-mutex")))
        idx     <- Loop.indexed(i => if i < 5 then Loop.continue else Loop.done(i))
        elapsed <- start.elapsed
        _       <- log.updateAndGet(_.append(s"async elapsed ~${elapsed.toMillis}ms"))
    yield s"keys=$keys signal=$seen timeout=${slow.isFailure} race=$winner zip=$par mutex=$mutexed loopIdx=$idx"

// ---------- 7. A full "app" program: Async & Scope & Abort ----------
def kyoProgram: String < (Async & Abort[Throwable]) =
    AtomicRef.init(Chunk.empty[String]).map { log =>
        Scope.run {
            for
                term    <- terminalResource(log)
                counted <- Var.runTuple(0)(counter)
                (evs, r) <- Emit.run[Ev](emitter)
                folded  <- Var.run(0)(Kyo.foreachDiscard(evs)(reduce).andThen(Var.get[Int]))
                viaFold <- Emit.runFold[Ev](0)((acc, ev) => if ev == Ev.Tick then acc + 1 else acc)(emitter)
                stream  <- evens.run
                sum     <- evens.fold(0)(_ + _)
                q = java.util.concurrent.ConcurrentLinkedQueue[Ev](java.util.List.of(Ev.Key('p'), Ev.Tick, Ev.Quit))
                polled  <- pollingStream(q).takeWhile(_ != Ev.Quit).run
                cfg     <- Abort.run[String](Env.run(Config("tui4s"))(needsConfig))
                bad     <- Abort.run[String](Env.run(Config(""))(needsConfig))
                asyncR  <- Abort.run[Closed](asyncPart(log))
                open    = term.open
            yield s"""counter=$counted emitted=$evs/$r folded=$folded ticks=$viaFold
                     |stream=$stream sum=$sum polled=$polled
                     |env=$cfg bad=$bad
                     |async=$asyncR termOpenInsideScope=$open""".stripMargin
        }.map(s => log.get.map(l => s + "\nlog=" + l))
    }

// ---------- 8. KyoApp entry point (not the default main here) ----------
object ProbeApp extends KyoApp:
    run {
        kyoProgram.map(s => Console.printLine(s"[KyoApp] $s"))
    }

// ---------- 9. Default main: run from Kyo, from ZIO and from cats-effect ----------
@main def probe(): Unit =
    import AllowUnsafe.embrace.danger
    // 9a. Kyo's own runner (blocking, only at the edge)
    val r1 = KyoApp.Unsafe.runAndBlock(10.seconds)(kyoProgram)
    println(s"[Kyo] $r1")

    // 9b. Run a Kyo program from ZIO; lift a ZIO into Kyo
    val zioProg: zio.ZIO[Any, Throwable, String] =
        ZIOs.run {
            ZIOs.get(zio.ZIO.succeed(21)).map(n => Async.sleep(5.millis).andThen(s"zio-bridge ${n * 2}"))
        }.zipWith(ZIOs.run(kyoProgram))((a, b) => s"$a | ${b.linesIterator.next()}")
    val r2 = zio.Unsafe.unsafe(implicit u => zio.Runtime.default.unsafe.run(zioProg).getOrThrowFiberFailure())
    println(s"[ZIO] $r2")

    // 9c. Run a Kyo program from cats-effect IO; lift an IO into Kyo
    import cats.effect.unsafe.implicits.global
    val ceProg: cats.effect.IO[String] =
        Cats.run {
            Cats.get(cats.effect.IO.pure(20)).map(n => Async.sleep(5.millis).andThen(s"ce-bridge ${n + 1}"))
        }.flatMap(a => Cats.run(kyoProgram).map(b => s"$a | ${b.linesIterator.next()}"))
    val r3 = ceProg.unsafeRunSync()
    println(s"[CE] $r3")
end probe
```


## 7. Kyo UI/TUI/reactive examples in the wild

- **No Kyo-based terminal UI found** (**VERIFIED** negative, as far as searched): GitHub repository search for `kyo tui`, `kyo terminal language:Scala`, `"io.getkyo" tui`, `kyo crossterm`, `kyo-core ratatui` returned nothing related; web search found only non-Kyo Scala TUIs (tui-scala, kevin-lee/stui, panopticon-tui). The getkyo/kyo tree (7474 paths) has no terminal/TUI module. tui4s would be the first.
- **kyo-ui (official, 1.0.0-RC7)** is the closest reference design (**VERIFIED** `kyo-ui/README.md` and sources): a **web** UI (Scala.js DOM, server-driven HTML over WebSocket, or SSR stream), not a terminal. Its model is directly reusable for tui4s:
  - "A `Signal[A]` is a value of type `A` everywhere kyo-ui takes one": setters and child slots accept `A`, `Signal[A]` (read-only, re-renders on change) or `SignalRef[A]` (two-way binding). `when(cond: Signal[Boolean])(ui)`, list rendering from `Signal[Chunk[A]]`, `.style(Signal[Style])`.
  - The UI tree is an immutable value of case classes (`UI.Ast.*`), built once; each place a signal appears becomes a subscription; "No virtual DOM, no component re-execution", only the bound subtree re-renders.
  - Event handlers are `Any < Async`.
  - Implementation: each reactive region runs `signal.observe(renderValue)` in a scoped observer fiber (`kyo-ui/internal/ReactiveUI.scala` lines ~1113-1153); `observe` gives each value a fresh `Scope`, so work forked for a stale value is interrupted automatically.
  - For a terminal, a cheaper variant fits better: tui-scala already diffs full frame buffers, so tui4s can re-render the whole frame on any change and let the buffer diff minimise output. Combine signals with `combineLatest`/`Signal.combineLatestAll` or a single `SignalRef[Model]`, consume with `streamChanges` (latest-value semantics coalesce bursts), cap FPS.
- **Kyo docs on Signal/Stream reactive patterns** (**VERIFIED** `kyo-core/README.md`, also published at https://getkyo.io/latest/kyo-core/): Signal section "reactive value with change streams" (`current`, `next`, `streamChanges`, `streamCurrent`, `map`, `combineLatest`, `zip`, `observe`), with the caution that `streamChanges` may skip intermediate values ("For capture-every-change cases use a `Channel` instead"). Streams: `StreamCoreExtensions` (merge, mapPar, broadcast, `groupedWithin`) for fan-in of input/tick/resize event sources.
- **kyo-compat (official, RC7)**: a different packaging model worth knowing for "independently deployable effect integrations" (**VERIFIED** `kyo-compat/README.md`): write a library once against `kyo.compat.*` (`CIO[A]`, `CFiber`, `CChannel`, `CStream`, ...) and the sbt plugin `io.getkyo % kyo-compat-plugin % 1.0.0-RC7` (on top of `sbt-projectmatrix`) cross-publishes one artifact per backend (Kyo, ZIO, Future, Ox, Twitter Future). `CIO[A]` = `A < (Abort[Throwable] & Async)` on Kyo, `ZIO[Any, Throwable, A]` on ZIO. Every op is `inline` (no adapter layer). The **cats-effect binding (`kyo-compat-ce`) stops at RC5** (removed in RC6), but "external bindings" can be published from another repo (`CompatBackendAxis.external(...)`). Trade-off: only a Throwable error channel and a lowest-common-denominator API; it is not a "Kyo core + integrations" design, so it fits tui4s only if the core itself should be effect-agnostic.


## 8. Sources
- Maven Central metadata and POMs: https://repo1.maven.org/maven2/io/getkyo/ (kyo-core_3, kyo-zio_3, kyo-cats_3, kyo-compat-*, class files inspected directly).
- Source jars `*-sources.jar` for RC7 / RC5 / RC4 (unpacked in `.work/scratch/kyo-src*`).
- GitHub releases: https://github.com/getkyo/kyo/releases (v1.0.0-RC6 and RC7 notes).
- getkyo/kyo main: README.md, AGENTS.md, CONTRIBUTING.md, kyo-core/README.md, kyo-zio/README.md, kyo-ui/README.md, kyo-scheduler/README.md, kyo-compat/README.md (copies in `.work/scratch/kyo-docs/`).
- Docs site: https://getkyo.io/latest/kyo-core/ , https://getkyo.io/latest/kyo-ui/

## 9. Open questions
- Install JDK 25 for the project (sdkman) and target RC7 + Scala 3.9.0, or pin RC4 + Scala 3.8.3 on JDK 21? RC4 lacks RC6/RC7 fixes (Channel.close semantics, Isolate on race/gather, uninterruptible rename, fiber/scope ordering fixes).
- Does tui-scala (com.olvind.tui 0.0.10) work on JDK 25 (JNI crossterm native lib loading)? Not checked here.
- Binary compatibility of kyo-cats RC5 with kyo-core RC7 was not tested (needs JDK 25). Recommendation stands: vendor the bridge.
