# Kyo 1.0.0-RC7 on JDK 25: probe, ZIO/Cats bridges, event loop, testing

Date: 2026-09-30. Author: research subagent (topic: KYO RC7 ON JDK 25).
Builds on `.work/reports/kyo.md` (do not repeat its content).
Legend: **VERIFIED** = compiled/ran or seen in source. **UNVERIFIED** = not checked here.
Scratch files: `.work/scratch/kyo-rc7/`.

## TL;DR
(filled at the end)

## 1. RC7 probe on JDK 25 + Scala 3.9.0
(in progress)

## 2. ZIO bridge (kyo-zio RC7, zio 2.1.26)
(in progress)

## 3. Cats bridge (port of kyo-cats RC5 to RC7, cats-effect 3.7.1)
(in progress)

## 4. Event-loop skeleton
(in progress)

## 5. Testing Kyo code
(in progress)

## 6. Compile-time cost and compiler flags
(in progress)

## 7. Log of findings (appended as verified)

### F1. Compiler stack overflow with default thread stack (VERIFIED)
- Unmodified `.work/scratch/kyo-probe.scala` (copy in `kyo-rc7/probe-asis/`), Scala 3.9.0, JDK 25.0.4.1 (Temurin), scala-cli 1.9.1 `--server=false`: **`java.lang.StackOverflowError` in the compiler**, phase staging (`dotty.tools.dotc.staging.CrossStageSafety.transform` recursion over a very deep tree). Cause: Kyo's `map`/`flatMap` are `inline`, so a 13-step for-comprehension becomes one very deep inlined tree. JDK default `ThreadStackSize` = 1024 KB (checked with `-XX:+PrintFlagsFinal`).
- `JAVA_TOOL_OPTIONS=-Xss16m` does NOT help (the java launcher sizes the main thread before it reads JAVA_TOOL_OPTIONS). `JDK_JAVA_OPTIONS=-Xss16m` DOES help (launcher option): compile succeeded, exit 0.
- Kyo's own build docs use `-Xss10M` (`kyo-docs/CONTRIBUTING.md` line 100, `AGENTS.md` `JAVA_OPTS="... -Xss10M ..."`).
- **Consequence for tui4s**: the sbt JVM must run with a big stack (env.sh already has `SBT_OPTS=-Xss4m`; add `-Xss8m`+ or put `-J-Xss16m` in `.jvmopts`/`.sbtopts`), and users of tui4s who write long for-comprehensions need the same. Document it in the README "Getting started". With scala-cli: `export JDK_JAVA_OPTIONS=-Xss16m` and `--server=false`, or `--bloop-java-opt -Xss16m` with Bloop.

### F2. kyo-cats RC5 is binary-incompatible with kyo-core RC7 (VERIFIED)
- The as-is probe (kyo-core/kyo-zio RC7 + kyo-cats RC5 + cats-effect 3.7.0) compiles, and the Kyo and ZIO parts run, but the first `Cats.run` fails at runtime:
  `java.lang.NoSuchMethodError: 'kyo.kernel.internal.Safepoint kyo.kernel.internal.Safepoint$.get()'`
  (kyo-cats RC5 has kernel internals inlined into it). So kyo-cats RC5 **cannot** be used with RC7; vendoring/porting the bridge is mandatory (confirms DECISIONS.md).

### F3. RC7 Kyo + ZIO probe output on JDK 25 (VERIFIED, run)
```
[Kyo] counter=(11,11) emitted=Chunk(Key(a), Tick, Key(b))/emitted folded=2 ticks=(1,emitted)
stream=Chunk.Indexed(4, 8, 12, 16, 20) sum=60 polled=Chunk.Indexed(Key(p), Tick)
env=TUI4S bad=Failure(empty title)
async=keys=2 signal=Chunk.Indexed(0, 1, 2) timeout=true race=hare zip=(1,2) mutex=in-mutex loopIdx=5 termOpenInsideScope=true
log=Chunk(async elapsed ~333ms, released)
[ZIO] zio-bridge 42 | counter=(11,11) ...
```
Same output as RC4 on JDK 21: no source changes were needed for the Kyo/ZIO part of the probe (the RC5->RC7 API diff in kyo.md does not touch the calls it makes).

### F4. JDK 25 prints a `sun.misc.Unsafe` warning at startup (VERIFIED)
```
WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
WARNING: sun.misc.Unsafe::objectFieldOffset has been called by scala.runtime.LazyVals$ (.../scala-library-3.9.0.jar)
```
Printed to stderr once per JVM. For a TUI this lands on the terminal before the alt screen opens. Fix at launch: `--sun-misc-unsafe-memory-access=allow` (JDK 24+ flag). Source of the call: see F5 (which library triggers it).

### F5. Attribution for the vendored Cats bridge (VERIFIED)
- Source: `kyo-cats/shared/src/main/scala/kyo/Cats.scala` at tag `v1.0.0-RC5` (byte-identical to the file in `kyo-cats_3-1.0.0-RC5-sources.jar`). 68 lines. **The file has no per-file license header.**
- Repo license: `LICENSE.txt`, Apache-2.0, appendix line **"Copyright 2015 Flavio Brasil"** (GitHub API `repos/getkyo/kyo/license`). No `NOTICE`/`NOTICE.txt` in the repo (404), so Apache-2.0 4(d) adds nothing to carry.
- POM: license Apache-2.0, developer Flavio Brasil (fwbrasil), url https://getkyo.io, scm github.com/getkyo/kyo.
- Commit authors of that file up to RC5: mostly Flavio Brasil; also Adam Hearn, johnhungerford, Jonathan Winandy.
- Header to keep (used in `kyo-rc7/CatsBridge.scala`): "Derived from kyo-cats 1.0.0-RC5 ... Original work: Copyright 2015 Flavio Brasil and the Kyo contributors, licensed under the Apache License, Version 2.0" + a list of changes (Apache-2.0 4(b) asks for prominent notices of modification). A copy of the license text is in `kyo-rc7/kyo-LICENSE.txt`.

### F6. Kyo has its own published test framework at RC7: kyo-test (VERIFIED: Maven metadata, README at tag v1.0.0-RC7)
- Artifacts (all 1.0.0-RC5..RC7): `io.getkyo::kyo-test-api`, `kyo-test-runner` (sbt `TestFramework("kyo.test.runner.SbtFramework")`; JS `JsFramework`, Native `NativeFramework`), `kyo-test-prop` (property tests), `kyo-test-snapshot`. sbt plugins `io.getkyo:sbt-kyo-test` and `sbt-kyo-test-publish` (`_2.12_1.0`, RC7). The old `kyo-test_3` (0.15.1) is a different, stale artifact.
- Kyo's own build wires every module's tests to kyo-test (`project/WithKyoTest.scala`); ScalaTest 3.2.20 is still in the build (`scalaTestVersion`), e.g. as a test dep of kyo-test-runner itself.
- Shape: `class FooTest extends Test[Any]: "group" - { "leaf" in { ...Unit < (Async & Abort[Any] & Scope)... } }`; power-`assert`, `fail`, `cancel`, `assume`, decorators `.retry(n).timeout(d)`, `aroundLeaf`; `PropertyTest[S]`, `SnapshotTest[S]`. README copy: `kyo-rc7/docs/kyo-test-README.md`.
- sbt setup (README): `libraryDependencies += "io.getkyo" %% "kyo-test-runner" % "1.0.0-RC7" % Test` and `Test / testFrameworks += new TestFramework("kyo.test.runner.SbtFramework")` (use `+=`, not `:=`).

### F7. Cats bridge port: compile fixes for RC7 (VERIFIED, compiled)
- The RC5 body compiles against RC7 almost unchanged. Only fix needed outside package `kyo`: **`kyo.discard` is `private[kyo]`** (`kyo-data/kyo/data.scala:8`), so the bridge defines its own `private inline def discard[A](inline v: => A): Unit`.
- `Promise.Unsafe.init[A, Abort[Throwable]]()`, `p.completeDiscard(Result...)`, `p.safe.get`, `Fiber.initUnscoped(v)`, `fiber.unsafe.onComplete`, `fiber.unsafe.interrupt()`, `.handle(Sync.Unsafe.evalOrThrow)` all have the same shape in RC7 (`Fiber.initUnscoped` now takes a leading `using Isolate[S, Sync, S2]`; derived automatically for `S = Any`).
- File: `.work/scratch/kyo-rc7/CatsBridge.scala` (package `tui4s.interop`; imports use `_root_.cats` so a future `tui4s.cats` package cannot shadow them).

### F8. Cats bridge behaviour, 13 munit tests pass (VERIFIED: `scala-cli test`, run `cats-test6.log`)
Tests in `.work/scratch/kyo-rc7/CatsBridge.test.scala`:
- `run`: value; Kyo `Abort.fail(ex)` -> IO error `Left(ex)`.
- `get`: `IO.pure` with the global runtime given; failed IO -> `Result.fail(ex)` (typed `Abort[Throwable]`); `IO.canceled` -> `Panic(Interrupted)`.
- **`get` uses the GIVEN `IORuntime`**: with a custom `IORuntime.builder().setCompute(namedPool)` the IO ran on thread `custom-ce-*`.
- **`runWithDispatcher` + `getF`: the F runs on the CALLER's runtime** (thread `caller-ce-*`), no global runtime anywhere. This is the polymorphic answer: `runF` needs only `Async[F]`; `getF` needs a `Dispatcher[F]`, which `runWithDispatcher` creates from the caller's runtime.
- **cancel IO -> Kyo fiber interrupted** (`outcome.isCanceled`), and Kyo finalizers:
  - `Sync.ensure(fin)(body)`: **finalizer HAS run when `fiber.cancel` returns** (cancel-and-await works).
  - `Scope.ensure(syncFin)` and `Scope.ensure(asyncFin)`: **NOT yet run when cancel returns**; run within 300 ms. Reason (VERIFIED in RC7 source `Scope.scala` ~line 349, "Open:" comment, and `Fiber.interruptAwait` doc): on abnormal exit (interrupt) a Scope's finalizers are drained on a **detached fiber that is not awaited**; "A fiber's result follows its synchronous releases" (`Sync.ensure`, `Sync.acquireReleaseWith`) only. kyo-zio's `ZIOs.run` has the same limit.
- **interrupt Kyo -> IO cancelled**, IO `onCancel` finalizer ran (both `get` and `getF`).
- Stress: 300 rounds "interrupt right after the IO started" (IORuntime) and 100 rounds through a Dispatcher: no leaked IO, no hang.

### F9. Suspected race "interrupt between effect start and join" — NOT reproduced (VERIFIED negative)
- Hypothesis while debugging the hang (the hang turned out to be F10): kyo-cats RC5 / kyo-zio RC7 `get` start the foreign effect and register `p.onInterrupt(cancel)` in one `Sync.Unsafe.defer`, then join `p`; an interrupt landing before the join might skip the cancel.
- Test (`zio/ZioBridge.scala` section 7): upstream `ZIOs.get`, interrupt the Kyo fiber IMMEDIATELY after `Fiber.initUnscoped`: 500 rounds, 81 ZIOs had started, **0 leaked**; interrupt after the ZIO started: 300 rounds, 0 leaked. RC7's IOTask registers the interrupt cascade before reading the promise state (`IOTask.scala` ~line 97), which explains it.
- The tui4s port still uses `Sync.ensure { outcome => if interrupted and not settled then cancel() } { start; join }` (RC7 doc: `ensuringWith` "is a region from the start", so it also covers an abandoned remainder). This is **defensive, not required by an observed bug**. Cost: for a generic error type `E` (ZIO), `Sync.ensure` with an outcome needs `ConcreteTag[E]`; work-around used: join with `p.safe.getResult` inside the region, then `Abort.get(result)` after it. Keeping upstream's simpler `p.onInterrupt` shape is also acceptable (smaller diff to upstream).

### F10. cats-effect 3.7.1 `Dispatcher.parallel(await = true)` can lose a cancel (VERIFIED, no Kyo involved)
- `dbg/PureDispatcher.scala`: a foreign thread calls `d.unsafeToFutureCancelable(IO.never.onCancel(...))`, waits until the task started, calls `cancel()`, and the `use` body ends right away. With `await = true`: **3 of the first 10 rounds hung** (onCancel never ran, cancel Future never completed, release waited forever; `IO.timeout` cannot help because release is uncancelable). With `await = false`: **0 failures in 1000 rounds**.
- Same symptom first seen as a hanging munit test (`cats-hang*-jstack.txt`: every thread idle, main in `unsafeRunSync`).
- Consequence: tui4s-cats must create its Dispatcher with **`await = false`** (release then cancels anything still running). Worth an upstream cats-effect issue (UNVERIFIED whether it is known).

### F11. kyo-zio RC7 + ZIO 2.1.26 behaviour (VERIFIED, run: `zio/ZioBridge.scala`, log `zio/zio5.log`)
```
[zio] get/run value: Success(21)
[zio] get of ZIO.fail -> Kyo: Success(Failure(boom))                       // typed Abort[String]
[zio] run of Abort.fail -> ZIO: Failure(Fail(kyo-error,StackTrace.none))   // typed ZIO error
[zio] get of ZIO.die -> Kyo: Success(Panic(java.lang.RuntimeException: died))
[zio] ZStreams.get(ZStream(1,2,3)*10).run = Chunk.Indexed(10, 20, 30)
[zio] ZStreams.run(Stream).runCollect = Success(Chunk(A,B,C))
[zio] zlayer released                                                       // ZLayer scope closed by Kyo Scope
[zio] ZLayers.get -> Env.runLayer: from-zlayer
[zio] ZLayers.run -> provide: Success(from-kyo-layer)
[zio] interrupt ZIO -> Kyo (sync-ensure):        finalizedWhenInterruptReturned=true,  zioInterrupted=true
[zio] interrupt ZIO -> Kyo (scope-ensure-sync):  finalizedWhenInterruptReturned=false, finalizedAfter300ms=true
[zio] interrupt ZIO -> Kyo (scope-ensure-async): finalizedWhenInterruptReturned=false, finalizedAfter300ms=true
[zio] interrupt Kyo -> ZIO: kyoResult=Panic(kyo.Interrupted: ...) zioFinalizerRan=true
[zio] upstream ZIOs.get under caller FiberRef=42 + caller executor: (fiberRef, thread)=(0, ZScheduler-Worker-3)
[zio] AmbientZIOs.get (caller runtime via ZIO.runtime + Local):  (fiberRef, thread)=(42, caller-zio-...)
[zio] AmbientZIOs.get from a forked Kyo fiber:                  (fiberRef, thread)=(7, caller-zio-...)
```
- API notes: `ZLayers.get(layer)` gives `Layer[A, Abort[E] & Async & Scope]`; using it needs **`Memo.run(Env.runLayer(layer)(...))`** (without `Memo.run` the row keeps `Memo` and `KyoApp.Unsafe.runAndBlock` rejects it: compile error "Found String < (Async & Scope & Memo)"). `ZIOs.get` with a ZIO that needs an environment `R` is a compile error by design (`compiletime.error`).
- **ZIO interruption -> Kyo**: works, `ZIOs.run` waits for the Kyo fiber's result (interrupt-and-await). As with Cats: `Sync.ensure` finalizers are done when `interrupt` returns; `Scope` finalizers are drained detached and finish shortly after (F8).
- **Kyo interruption -> ZIO**: the ZIO fiber is interrupted and its `onInterrupt` runs (signalled, not awaited).

### F12. Running on the CALLER's ZIO runtime (VERIFIED)
- kyo-zio RC7 has **no overload taking a Runtime**: `ZIOs.get` hard-codes `Runtime.default.unsafe.fork(v)` (`kyo-zio/kyo/ZIOs.scala`). Shown above: under `fr.locally(42)` and `.onExecutor(callerExec)` the upstream `get` sees FiberRef 0 and runs on `ZScheduler-Worker-*` (the default runtime), i.e. caller FiberRefs (log annotations, config, executor overrides) are lost.
- `ZIOs.run` already starts the Kyo fiber from inside the ZIO (when the ZIO runs, in `ZIO.suspendSucceed`), on Kyo's scheduler. Kyo fibers cannot run on the ZIO executor (Kyo's scheduler is global); the reverse is what `kyo-scheduler-zio` offers (ZIO on the Kyo scheduler, UNVERIFIED).
- **tui4s-zio solution (prototype `AmbientZIOs` in `zio/ZioBridge.scala`, ~40 lines)**: `run` does `ZIO.runtime[Any].flatMap(rt => ZIOs.run(callerRuntime.let(Present(rt))(v)))` where `callerRuntime` is an inheritable `kyo.Local[Maybe[zio.Runtime[Any]]]`; `get` reads the Local and forks on that runtime (`rt.unsafe.fork(v)`), falling back to `Runtime.default` outside `run`. `ZIO.runtime` captures the caller fiber's FiberRefs (including the executor override), so `get` saw FiberRef 42 and the caller's executor, also from a Kyo fiber forked inside the program (Local is inherited by forks).

### F13. Idle CPU of the Kyo scheduler, and the flags that lower it (VERIFIED, run 2026-10-01)
- Probe `.work/scratch/idle/` (`idle.scala`, `run.sh`, output `idle-output.txt`): process CPU over a 3 s window
  after 2 s warm-up, one Kyo fiber in `Async.sleep`, per-thread CPU via ThreadMXBean. Two rounds, 8-CPU Mac, JDK 25.
- Plain JVM idle: 0.02% of one core. **Kyo defaults: 13.1-13.2% of one core**; `kyo-scheduler-timer` ~315 ms/3 s,
  `kyo-scheduler-clock` ~40-80 ms/3 s.
- Causes (RC7 source `kyo-scheduler`): worker cycle every `cycleIntervalNs` = 100 us (10k wakeups/s) on the timer
  thread; `blockingMonitorIntervalNs` = 2 ms; `regulator.concurrencyCollectIntervalMs` = 10 ms; `InternalClock` parks
  1 ms in a loop (hard-coded, no flag).
- Tuned: `-Dkyo.scheduler.cycleIntervalNs=10000000` 5.8-7.9%; + `blockingMonitorIntervalNs=20000000` 5.1-6.6%;
  + `regulator.concurrencyCollectIntervalMs=100` 7.2-7.9% (noise); + `coreWorkers=2` 4.1-6.5%. Floor = the 1 ms clock.
- Flags are `StaticFlag`s: the system property key is the fully qualified object name (`kyo.scheduler.cycleIntervalNs`),
  read once at class init. An entry point can set defaults with `System.setProperty` before the first Kyo class loads.
- Follow-up idea: upstream issue/PR to Kyo for a configurable clock interval or an idle-parking clock.
