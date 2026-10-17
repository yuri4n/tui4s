# Effect systems: find the project's stack and map the libraries

The books behind this skill use the Typelevel stack (Cats Effect, FS2,
http4s, Skunk, Redis4Cats, Ciris, Circe, Weaver). Their design ideas do not
depend on that stack. Scala has several effect systems, and each has its own
libraries and connectors. Before you write code, find the effect system that
the project uses or that the user prefers, then use that system's libraries
and translate the patterns of this skill to it.

## Contents

1. Find the effect system
2. Find the equivalent libraries (the Scala Compendium first)
3. Translate the patterns
4. Semantic differences that matter
5. ZIO idioms (from *Effect Oriented Programming*)
6. Cross a boundary between ecosystems
7. When no equivalent exists

## 1. Find the effect system

Look in this order. Stop when the answer is clear.

1. **The user's instructions:** CLAUDE.md, AGENTS.md, memory, or the request.
   A stated preference wins for new code.
2. **The build:** `build.sbt`, `project/*.scala`, `build.mill`, `build.sc`,
   `project.scala`, or `//> using dep` lines (Scala CLI).

   | Dependency organization | Effect system |
   |---|---|
   | `org.typelevel` (`cats-effect`); `co.fs2` (`fs2-core`); `org.http4s`; `org.tpolecat` (`skunk-core`, `doobie-core`) | Cats Effect (Typelevel) |
   | `dev.zio` (`zio`, `zio-streams`, `zio-http`) | ZIO |
   | `io.getkyo` (`kyo-core`, `kyo-prelude`) | Kyo |
   | `com.softwaremill.ox` | Ox (direct style; Scala 3 and JDK 21+) |
   | `com.typesafe.akka` / `org.apache.pekko` | Akka / Apache Pekko (actors, streams); two distinct ecosystems |
   | none of these; `scala.concurrent.Future` in signatures | standard-library Future |

3. **The code:** `IOApp`, `F[_]: Async`, `Resource[F, *]` mean Cats Effect.
   `ZIOAppDefault`, `ZLayer`, `ZIO[R, E, A]` mean ZIO. `KyoApp` and the pending
   type `A < S` mean Kyo. `supervised { ... }` and `fork` mean Ox.
4. **The versions and platforms:** the Scala version (2.13 or 3.x), the JDK,
   the effect system's major version (Cats Effect 2 vs 3, ZIO 1 vs 2), and the
   targets (JVM, Scala.js, Scala Native). They limit the choice of libraries.
   Ox needs Scala 3 and JDK 21+; a JVM-only library does not run in a browser.
5. **The style inside the system:** tagless final (`F[_]`) or concrete `IO`;
   ZIO with for-comprehensions or with zio-direct (`defer { ... .run }`; its
   last release is 1.0.0-RC7 from 2023, so check that it compiles with the
   project's Scala and ZIO versions);
   typed errors through `EitherT` or error ADTs in `MonadThrow`. Follow it.

If the project mixes systems, find the one that owns `main` and the runtime;
the others enter through bridges (section 6).

If there is no project yet and no stated preference, ask the user one
question: which effect system. Do not pick the books' stack silently. If the
user has no preference, recommend one with a short reason (team experience,
typed errors, libraries needed, platform) and continue.

## 2. Find the equivalent libraries

Use **The Scala Compendium** first: <https://thescalacomp.dev>. It is the
user's own map of the Scala ecosystem. It places libraries in their ecosystem
(ZIO, Typelevel, Kyo, Akka, Pekko, Future, Ox, Turbolift, shared tools), shows
the documented bridges, and gives compatibility notes. The catalogue is plain
data in the repository `yuri4n/the-scala-compendium`, file
`shared/utils/atlas.ts` (fields: `name`, `kind`, `category`, `ecosystems`,
`compatibility`, `url`), and its practical guides are in
`shared/data/guides.json`:

```bash
gh api 'repos/yuri4n/the-scala-compendium/contents/shared/utils/atlas.ts' --jq '.content' | base64 -d
```

Then confirm each choice in the library's primary documentation: the current
version, the Scala version, the platform artifacts, and compatibility with the
runtime version.

Starting map by role. Entries marked † are not in the Compendium at the time
of writing; verify them before use.

| Role | Typelevel (Cats Effect) | ZIO | Kyo | Direct style / Future |
|---|---|---|---|---|
| Effect runtime | Cats Effect `IO`, or `F[_]` with type classes | `ZIO[R, E, A]` | Kyo, `A < S` | Ox (structured concurrency); `Future` |
| Streams | FS2 | ZIO Streams | Kyo Stream | Ox `Flow` †; Akka / Pekko Streams |
| HTTP server | http4s (Ember) | ZIO HTTP | kyo-http | Tapir with a sync server interpreter (check the interpreter †); Play; Pekko HTTP |
| HTTP client | http4s client; sttp | ZIO HTTP client; sttp | kyo-http | sttp |
| Endpoint descriptions | Tapir | Tapir | kyo-http routes (with OpenAPI) | Tapir |
| JSON | Circe | ZIO JSON | kyo-schema + a codec module | uPickle; jsoniter-scala † |
| SQL (Postgres) | Skunk; Doobie (JDBC) | ZIO Quill; Doobie through zio-interop-cats | kyo-sql + the kyo-sql-postgres driver | Slick, Quill; Magnum † |
| Kafka | FS2 Kafka | ZIO Kafka | the Java client inside the effect, or ZIO Kafka through kyo-zio † | Ox Kafka module † |
| Pulsar | Neutron † (Volpe; no release since 2022); pulsar4s-cats-effect / pulsar4s-fs2 † | pulsar4s-zio † | none; the Java client behind an algebra | Pulsar Java client † |
| Redis | Redis4Cats † | ZIO Redis † | check † | Java client (Lettuce, Jedis) † |
| Configuration | Ciris †; PureConfig † | ZIO Config; `ZIO.config` | kyo-config | PureConfig † |
| Schemas, derivation | Kittens †; Circe derivation | ZIO Schema | kyo-schema | Chimney (transformations) |
| Logging | log4cats † | ZIO logging (`ZIO.logInfo`), ZIO Logging | Kyo `Log` † | SLF4J; scribe † |
| Tracing, metrics | otel4s † (Natchez in PFP is older) | ZIO Telemetry † | kyo-stats-otlp with kyo-stats-registry † | OpenTelemetry Java † |
| Retry, resilience | cats-retry †; upperbound † (rate limit); `circuit` † | `Schedule` (built in); rezilience † (rate limit, bulkhead, breaker) | `Retry` / `Schedule` † | Ox `retry` and resilience † |
| Caching | `Ref` + `Deferred` memo; Mules † | ZIO Cache † | Kyo `Cache` in kyo-core (`Cache.init`, `Cache.memo`) | Caffeine † |
| STM | cats-stm † | ZIO STM (built in) | kyo-stm | none |
| CLI | Decline | ZIO CLI | kyo-case-app † | mainargs † |
| Testing | Weaver †; munit-cats-effect †; `TestControl` | ZIO Test; `TestClock`, `TestRandom` | kyo-test (artifact `kyo-test-runner`; `Test[S]`) | MUnit; ScalaTest |
| Property tests | ScalaCheck (with a framework integration) | ZIO Test `Gen` | kyo-test-prop (artifact `kyo-test-prop`; `PropertyTest[S]`, `Gen`, `Shrink`) | ScalaCheck |
| Newtypes, refinement | opaque types + Iron † | opaque types + Iron †; ZIO Prelude `Newtype` † | opaque types + Iron † | opaque types + Iron † |

<!-- claude 2026-10-17: changed the Testing and Property tests rows of the Kyo column: kyo-test-prop has the property tests (purity plan A3) -->
Shared tools work with every stack through an interpreter or a backend:
Tapir, sttp, uPickle, Chimney, Slick, Quill, MUnit, ScalaTest, ScalaCheck.
Choose the interpreter or backend for the project's runtime. Iron † works
with every stack because it is only types.

## 3. Translate the patterns

The skill's patterns use Cats Effect names. This is how they map.

| Pattern | Cats Effect | ZIO | Kyo | Ox / direct style |
|---|---|---|---|---|
| Effect in a signature | `F[A]` with the smallest constraint, or `IO[A]` | `ZIO[R, E, A]`, `IO[E, A]`, `UIO[A]` | `A < (Async & Abort[E])` | a plain `A`; `using Ox` when the method forks |
| Algebra (service interface) | `trait Users[F[_]]` | `trait Users` with methods returning `IO[E, A]` | `trait Users` with methods returning `A < S` | `trait Users` with plain methods |
| Interpreter | `object Users { def make[F[_]: ...](deps): Users[F] }` | `final class UsersLive(deps) extends Users` + a `ZLayer` in the companion | a class + a `Layer` † | a class with constructor parameters |
| Wiring | constructor calls composed in `Resource` in `Main` | one `.provide(...)` of layers; the compiler checks the graph | `Env` + `Layer` † | constructor calls in `main` |
| Region of sharing (one instance for all users) | build once in `Main` and pass the value | layers in one `provide` are built once (`.fresh` for a separate instance; `provideShared` in tests) | layers in one `Env.runLayer` are built once (memoized; run inside `Memo.run`) | build once in `main` |
| Typed domain errors | an error ADT; `F[Either[E, A]]`, `EitherT`, or `MonadThrow` with `E extends NoStackTrace` | the `E` channel; Scala 3 unions of error types | `Abort[E]` | `Either[E, A]`; Ox `either { ... .ok() }` † |
| Wrap code that throws | `IO(...)`, `Sync[F].delay` | `ZIO.attempt` (never `ZIO.succeed`) | `Sync` / `Abort.catching` † | `try` at the boundary, Ox `expr.catching[E]` (import `ox.either.catching`) or `either.catchAll { ... }` |
| Wrap a `Future` | `IO.fromFuture(IO(makeFuture()))` | `ZIO.fromFuture(_ => makeFuture())` | `Async.fromFuture` † | `Await` only at the edge |
| Blocking call | `IO.blocking`; `IO.interruptible` when safe to kill | `ZIO.attemptBlocking`; `attemptBlockingInterrupt` when safe to kill | `Sync.defer` (the scheduler detects blocking) | a plain call on a virtual thread (on JDK 21-23 do not block inside `synchronized`, JEP 491); `abandonOnInterrupt` for calls that ignore interrupts (java.io, JDBC) |
| Resources | `Resource.make`, `Resource.fromAutoCloseable` | `ZIO.acquireRelease`, `ZLayer.scoped`, `Scope` | `Scope` effect (`Scope.acquireRelease`, `Scope.run`); `Sync.acquireReleaseWith` inline | `useInScope`, `releaseAfterScope` † |
| Concurrent state | `Ref` (pure updates), `AtomicCell` (effectful updates) | `Ref` (pure updates), `Ref.Synchronized` (effectful updates) | `AtomicRef` † | atomics, Ox channels |
| One-shot signal | `Deferred` | `Promise` | `Promise` † | `CompletableFuture`, Ox channel |
| Queues | `Queue` (bounded, unbounded, dropping, circularBuffer, synchronous) | `Queue`, `Hub` | `Channel`, `Hub` † | Ox `Channel` |
| Limit parallelism | `Semaphore`, `parTraverseN` | `Semaphore`, `foreachPar` + `withParallelism` | `Async.foreach(seq, concurrency = n)`; `Meter.initSemaphore(n)` | `parLimit(n)(...)`, `mapPar(n)` |
| Background work | `Supervisor`, `fa.background` (a `Resource`) | `forkScoped`; `forkIn(scope)` on an explicit `Scope` (`zio.Supervisor` only monitors fibers) | `Fiber.init` (scoped to `Scope`; `Fiber.initUnscoped` is not) | `fork` inside `supervised` |
| Race, timeout | `IO.race` (first to finish), `timeout`, `timeoutTo` | `raceFirst` (first to finish; `race` returns the first success), `timeoutFail`, `timeout` | `Async.raceFirst` (first to finish; `Async.race` is first success), `Async.timeout` | `raceSuccess` / `raceResult`, `timeout` (`timeoutOption`, `timeoutEither`) |
| Retry, repeat | cats-retry; `fs2.Stream.retry` | `retry(Schedule)`, `repeat(Schedule)` | `Retry` † | `retry(Schedule)` † |
| Time in tests | `TestControl` | `TestClock` (adjust it concurrently with the effect) | `Clock.withTimeControl` (drive it from one fiber) | inject a clock |

Keep the design rule the same in every system: the algebra says what, the
interpreter decides how, and only the entry point knows all concrete
implementations.

## 4. Semantic differences that matter

Check these when you translate; a compiling translation can behave
differently.

- **Error channel.** Cats Effect `IO` fails only with `Throwable`; typed
  errors need `EitherT`, `F[Either[E, A]]`, or error ADTs that extend
  `NoStackTrace` inside `MonadThrow`. ZIO has a typed `E` and separates
  failures from defects. Kyo has `Abort[E]`. Ox uses `Either` values and
  exceptions. This is the largest gap.
- **Laziness.** Effects in Cats Effect, ZIO and Kyo are descriptions that run
  later. `Future` starts at once. Ox code runs directly. Retry, timeout and
  fallback operators exist because effects are values; in direct style they
  take a function or a block.
- **Cancellation.** Fiber interruption in Cats Effect and ZIO, scope
  cancellation in Ox, and none in `Future`. Wrapping a `Future` does not make
  its work cancellable.
- **Atomic updates.** `Ref.update` may run its function several times under
  contention (compare-and-swap). Keep it pure. For effectful updates, use
  `AtomicCell` (Cats Effect) or `Ref.Synchronized` (ZIO).
- **Dependency wiring.** ZIO checks the layer graph at compile time and
  shares layers within one `provide`. Cats Effect apps wire by plain
  constructor calls inside `Resource`, so sharing is explicit.
- **Resource scopes.** `Resource` (Cats Effect), `Scope` (ZIO), scopes in Ox:
  all release in reverse order on success, failure and cancellation. Check
  that a translation keeps that order.

## 5. ZIO idioms (from *Effect Oriented Programming*)

Frasure, Eckel and Ward (2024) teach effect-oriented design with ZIO 2 and
Scala 3. Their rules, with section numbers:

- **Add behavior by wrapping effect values**, not by editing the callee:
  `retry`, `orElseFail`, `timeoutFail`, `orElse`, `withFinalizer`, `timed`,
  `when` (§ 3). **Order matters:** a timeout outside a retry bounds the whole
  loop and is not retried; put the retry outside the timeout to retry
  timeouts. State the choice in a comment when it is not obvious (§ 3.3).
- **Services:** a trait; an implementation class with constructor
  dependencies; a `ZLayer` in the companion object. Effects are verbs; layers
  are nouns or adjectives (`Bread.homeMade`, `Oven.heated`) (§ 4.2-4.6).
- **Two layers of the same type are ambiguous.** Make the consumer ask for a
  more specific type; do not "fix" it by removing a layer, which silently
  gives every consumer the other one (§ 4.7).
- **Scoped layers for cleanup**; construction may fail, so add `orElse`
  fallback layers or retries; keep tuning values in a config case class
  that is itself a dependency (§ 4.9-4.11).
- **Never put code that throws inside `ZIO.succeed`.** A throw then becomes a
  defect that typed handlers do not see. Use `ZIO.attempt`, then narrow the
  error (§ 6.7).
- **Convert foreign types once at the boundary** and attach a domain error at
  once: `Future`, `Option`, `Either`, `Try`, `AutoCloseable`, blocking calls
  (§ 7).
- **Handle errors by specific type** with `catchAll` / `catchSome`; do not turn
  failures into success values with error text (§ 6.5).
- **`Ref.update` takes a pure function**; use `Ref.Synchronized.updateZIO` when
  the update runs an effect (§ 8.3-8.5).
- **Resilience policies are shared values:** one rate limiter, bulkhead or
  circuit breaker for all callers of a service, provided through a layer
  (§ 9).
- **Hedging:** `call.race(call.delay(hedgeDelay))`. Hedge only calls that are
  safe to duplicate (idempotent; the book implies this but does not say it).
  The book sets the delay to the average latency of the fastest half of
  requests, which duplicates at least half of the calls (§ 9.5). In
  production, start the hedge at a high percentile of measured latency
  (p90-p99), limit the number of hedges, and measure the extra load.
- **Tests:** only the last assertion of a ZIO test counts; adjust
  `TestClock` concurrently with the effect (`zipPar` or `fork`); feed
  `TestRandom`; put a timeout aspect on suites (§ 5, § 9.6).
- **Style:** the book uses zio-direct (`defer { ... .run }`), a separate macro
  library. Most ZIO code uses for-comprehensions. Follow the project.

## 6. Cross a boundary between ecosystems

From the Compendium's guide "Connect ecosystems deliberately" and its bridge
entries (kyo-compat, kyo-reactive-streams):

- **A Cats Effect library inside ZIO:** `zio-interop-cats` gives the Cats
  Effect type-class instances for ZIO. It works for libraries that are
  polymorphic in `F[_]` (FS2, Doobie, http4s). It does not help with a library
  that uses `cats.effect.IO` directly.
- **ZIO and Kyo:** `kyo-zio` converts effects, streams and layers in both
  directions. Provide the ZIO environment before you lift an effect into
  Kyo. A compound ZIO `Cause` becomes one Kyo error, so some diagnostic
  information is lost.
- **One library for many runtimes:** `kyo-compat` is a portability API for
  library authors, with backends for Kyo, ZIO, Future, Twitter Future and Ox
  (artifacts `kyo-compat-<backend>`; the portable error type is `Throwable`).
  It does not convert arbitrary effects, and Cats Effect is not a documented
  backend.
- **Streams between ecosystems:** a Reactive Streams adapter
  (`kyo-reactive-streams`, the FS2 interop module, the Akka or Pekko
  adapters). Check buffering, cancellation and error mapping.
- **An API that returns `Future`:** create the `Future` inside the runtime's
  suspension operation. Wrapping does not add cancellation to the work that
  the `Future` started; keep the `ExecutionContext` and blocking policy
  explicit.
- **Endpoint descriptions:** describe endpoints once with Tapir and choose the
  server interpreter for the runtime. This does not convert the business
  logic.
- **Akka to Pekko:** a migration, not a bridge. Audit artifacts, imports,
  configuration prefixes, serializers and persistence plugins, and rehearse
  state recovery. Shared ancestry does not mean drop-in or mixed-cluster
  compatibility.

At each boundary, check and test: cancellation and interruption, resource
finalization, the mapping of typed errors, diagnostic information, who owns
the runtime and its thread pools, and the version compatibility matrix. A
successful compilation does not prove equal runtime behavior. Choose the
narrowest documented integration: type-class instances, effect conversion and
protocol adapters are different mechanisms.

## 7. When no equivalent exists

1. Search the ecosystem's primary documentation and Scaladex
   (<https://index.scala-lang.org>) for the role.
2. Prefer a library that the ecosystem's organization maintains, that has
   artifacts for the project's Scala version and platform, and that has recent
   releases.
3. If there is none, wrap the Java client yourself behind an algebra: run
   blocking calls on the blocking pool (`Sync[F].blocking`,
   `ZIO.attemptBlocking`), acquire clients as resources, and convert the
   client's exceptions to the domain's error types at the boundary.
4. If the project's effect system lacks a small primitive (a cache with
   request coalescing, a rate limiter), prefer a small local helper built from
   the system's own primitives (`Ref`, `Deferred`, `Semaphore`) over a new
   dependency, and say so.
5. Tell the user about the gap. A missing role or bridge may belong in the
   Compendium.
