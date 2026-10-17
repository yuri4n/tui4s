---
name: scala-functional-design
description: "Design, write and review functional Scala applications (Scala 3 first, Scala 2.13 when the project uses it): tagless-final algebras, interpreters and programs, capability traits, strongly typed domains with opaque types, Iron and derives, typed errors, Resource-safe wiring, Ref/Deferred/Supervisor concurrency, fs2 or ZIO streams, HTTP services, Postgres and Redis persistence, testing, configuration, observability, and event-driven systems on Kafka or Pulsar (idempotency, outbox, FSM services, schema evolution). It first finds the project's effect system (Cats Effect, ZIO, Kyo, Ox, Future) and maps every library and connector to that ecosystem with the Scala Compendium. Use it whenever the user works on Scala code or architecture, mentions Cats Effect, ZIO, Kyo, Typelevel, http4s, fs2, Skunk, Doobie, Tapir, tagless final, ZLayer, or asks how to structure, test or scale a Scala service, even if they do not ask for 'design'. Use it together with functional-design."
---

# Functional design in Scala

This skill applies the `functional-design` skill to Scala. Its sources are
Gabriel Volpe's *Practical FP in Scala* (2nd ed., 2021) and *Functional
Event-Driven Architecture* (2023), Frasure, Eckel and Ward's *Effect Oriented
Programming* (2024, ZIO), and the user's Scala Compendium for the library
map. `references/sources.md` lists them.

Load `functional-design` too when the task involves architecture, domain
modeling, error domains, testing strategy or algorithms. Its SKILL.md is at
`~/.claude/skills/functional-design/SKILL.md`. This skill adds the Scala
encodings, the libraries, and the event-driven material.

## Step 0: find the effect system (always first)

Volpe's books use Cats Effect and the Typelevel stack. The design rules work
with any effect system, but the code does not. Before you write or review
code:

1. Find the effect system that the project uses or that the user prefers:
   user instructions, then the build file, then the code. If nothing decides it
   for new code, ask the user one question.
2. Record the Scala version, the effect system's version, the JDK and the
   platforms (JVM, Scala.js, Native).
3. Map each library role (HTTP, SQL, Kafka, Redis, JSON, config, logging,
   tests) to that ecosystem. Use **The Scala Compendium**
   (<https://thescalacomp.dev>, data in the GitHub repository
   `yuri4n/the-scala-compendium`) first, then the primary documentation.
4. Translate the patterns of this skill to that system (`F[_]` and `Resource`
   in Cats Effect; `ZIO[R, E, A]` and `ZLayer` in ZIO; `A < S` in Kyo; direct
   style in Ox).

`references/effect-systems.md` has the full procedure, the role-to-library
map, the pattern translation table, the semantic differences that break naive
translations, and the bridges between ecosystems. Read it before you write
code in an unfamiliar stack.

## Application blueprint

Volpe's layered shape (Cats Effect names; translate as above):

```text
HTTP layer      routes (one class per resource group), auth middleware, error -> status mapping
Programs        business workflows that compose algebras (Checkout); failure policy per step
Algebras        traits over F[_] with no constraints (Items[F], Orders[F], PaymentClient[F])
Interpreters    in the companion `make`: Postgres, Redis, HTTP clients (in-memory test interpreters live in test sources)
Resources       AppResources: HTTP client, DB pool, Redis, built once as one Resource
Main            load config -> resources -> modules -> HTTP server -> useForever
```

```text
src/main/scala/shop/
  domain/        newtypes, ADTs, errors, codecs, orphan instances
  algebras/      Items.scala (trait + object Items { def make ... })
  programs/      Checkout.scala
  http/          routes/, auth/, clients/
  modules/       Services, Programs, HttpApi, Security, HttpClients
  resources/     AppResources, MkHttpServer
  config/        AppConfig, Config.load
  effects/       capability traits: GenUUID, Time, Retry, Background
  Main.scala
```

## Rules

Each rule names its reference file. The design reasons are in
`functional-design`.

**Domain types** (`domain-modeling.md`)

1. Give every identifier and meaningful primitive its own type. In Scala 3, use
   an opaque type (with Iron for invariants) or `case class X(v: A) derives ...`.
   Do not use value classes. Validate at the boundary; build a validated type
   with an unchecked cast only in tests or where the invariant holds by
   construction.
2. Accumulate validation errors for independent fields (`EitherNel` +
   `parMapN`, or `ValidatedNel`); chain monadically only for dependent checks.
3. Model commands, events and states as `enum`s and sealed ADTs. Replace
   Booleans with two-case ADTs in critical code. Never expose `Seq`; choose
   `List`, `Vector`, `Chain`, `NonEmptyList` or a stream by intent.

**Algebras, interpreters, programs** (`algebras-and-programs.md`)

4. An algebra is a trait over `F[_]` with no type-class constraints. If it
   seems to need `Monad`, you are writing a program.
5. Put interpreters in the companion `make`. Allocate private state (`Ref`,
   connections) inside `make`; return `F[Alg[F]]` for effectful creation and
   `Resource[F, Alg[F]]` for anything with a lifecycle. A production
   interpreter never accepts its `Ref` from outside (test interpreters may, so
   tests can inspect it).
6. Ask for the weakest constraint. Replace `Sync`, `Async` and `Temporal` in
   programs with small capability traits (`Time`, `GenUUID`, `Retry`,
   `Background`) that tests can fake.
7. Pass business algebras and resource-backed clients explicitly; pass lawful
   type classes and capabilities implicitly. Group many algebras into modules.
8. Keep monad transformers out of public signatures.

**Errors** (`algebras-and-programs.md`)

9. By default, raise a sealed error ADT that extends `NoStackTrace` in `F`, and
   map the known cases at the edge (HTTP status, ack or nack). Use
   `F[Either[E, A]]` only when the caller's logic branches on the error. In ZIO
   use the typed `E` channel; in Kyo `Abort[E]`.
10. Translate infrastructure errors that carry domain meaning inside the
    interpreter (unique violation becomes `UserNameInUse`).
11. For every step of a critical workflow, decide and document the failure
    policy: fail, retry with a configured policy, recover a known code,
    reschedule in the background, or ignore. Retry only transient errors, make
    retried writes idempotent, and persist work that must survive a restart
    (an outbox row), because in-memory background work is lost. A timeout
    after a side-effecting call is an unknown outcome, not a failure: keep
    the entity pending and reconcile; never compensate before you know.

**State, concurrency, resources** (`algebras-and-programs.md`)

12. Shared state lives in a `Ref` behind an algebra. Create concurrent
    structures in `F` and share them inside one visible region; never allocate
    them globally with `unsafeRun`. Keep `Ref.update` functions pure.
13. Everything with a lifecycle is a `Resource` (or `Scope` / scoped layer):
    pools, clients, servers, transactions, test fixtures, background
    processes. Compose them once.
14. Prefer streams to raw fibers: `concurrently` for a process that serves
    another, `parJoin` for independent processes. Wrap waits inside
    `uncancelable` regions with `poll`.

**Edges** (`http-and-persistence.md`)

15. Routes and middlewares are functions; routes take algebras and programs.
    Decode request bodies into validating parameter types. Do not leak which
    users exist on open auth endpoints.
16. Model every remote API as an algebra over the abstract HTTP client, and
    branch on status codes that carry meaning.
17. Create the database pool once; keep SQL codecs as explicit values; stream
    or paginate unbounded queries; use a dedicated transactional algebra for
    atomic multi-entity writes.

**Testing** (`testing.md`)

18. Test programs with in-memory or scripted interpreters, one per failure
    scenario; record typed values (retry details, scheduled jobs) in a `Ref`,
    never log strings. Use generators, not `Arbitrary` instances for domain
    types. The exception is law tests: Discipline needs an `Arbitrary`, so
    pass it explicitly. Law-test every hand-written instance. Run integration
    tests per external system against real containers.

**Assembly and operations** (`assembly-and-operations.md`)

19. Load and validate configuration first; keep non-secret configuration as
    typed code; read secrets from the environment and wrap them. Build all
    resources once, then modules, then the server.
20. Expose health and metrics; log critical events in a structured way;
    choose the tracing depth deliberately.

**Event-driven systems** (`event-driven.md`)

21. Propose EDA only when a monolith does not meet the non-functional
    requirements.
22. Assume at-least-once delivery and make every handler idempotent.
23. Never treat a database transaction and a broker transaction as one; use an
    outbox or CDC.
24. Write each stateful service as a pure FSM, and plan its scaling,
    rebalances, snapshots and schema evolution before you write code.

## Mapping `functional-design` concepts to Scala

| functional-design concept | Scala encoding |
|---|---|
| Service handle (record of functions) | an algebra `trait X[F[_]]` passed as a constructor argument |
| Interpreter / implementation | `X.make` in the companion; test interpreters in tests |
| Scenario / business logic | a program class that takes algebras and has capability constraints |
| Free monad / interpretable language | `cats.free.Free` (rare in practice); Kyo's effects and handlers are the modern take |
| ReaderT environment | `Kleisli`, Cats MTL `Ask`, ZIO's `R` |
| Bracket | `Resource`, ZIO `Scope` / `acquireRelease`, Ox scopes |
| STM | cats-stm, ZIO STM, kyo-stm |
| Synchronized cell, request-response | `Deferred` or `Promise` inside the request; `Queue` |
| Error domains with conversion | error ADTs per layer; `adaptError` and `recoverWith` at each boundary |
| Higher-kinded data | `case class User[F[_]](id: F[UserId], ...)` with `Id` and `Option` |
| Application layer and runtime | `Main` with `AppResources` and modules |
| Specification as test oracle | a reference implementation in tests plus ScalaCheck properties |

## Review checklist (Scala specific)

- [ ] Effect system, versions and libraries are consistent; no second effect
      system was added without reason (Step 0).
- [ ] No raw primitives for domain values; no `copy` that bypasses
      validation; no `Seq`, transformers or Booleans in critical interfaces.
- [ ] Algebras have no constraints; interpreters hide their state; programs
      ask for the weakest capabilities.
- [ ] No `unsafeRun*` outside `Main` and tests; no global concurrent state.
- [ ] Every resource is acquired in a `Resource` or scope; nothing is acquired
      twice by accident.
- [ ] Code that throws is wrapped (`Sync[F].delay`, `ZIO.attempt`; never
      `ZIO.succeed`); blocking calls run on the blocking pool.
- [ ] Errors: domain ADTs, conversion at boundaries, a documented failure
      policy per critical step; unknown outcomes stay pending; retries are
      bounded and classify 408/429/5xx as transient.
- [ ] Fibers and streams have an owner and a stop path; the process exits on
      a shutdown signal.
- [ ] The answer fits the request: no libraries or services the user did not
      ask for without a stated reason; core code complete, not stubs.
- [ ] Tests cover every failure branch of critical programs; there are law
      tests for hand-written instances.
- [ ] For event-driven code: idempotent handlers, the atomicity plan,
      subscription types matched to the scaling plan, schema compatibility.

## Dated advice

Volpe's PFP targets Scala 2.13 (2021). When you reuse its code, translate:
`@newtype` and refined become opaque types and Iron; derevo becomes `derives`
(Kittens, Circe); CE2's `Blocker` and `ContextShift` become
`Sync[F].blocking`; Natchez becomes otel4s; the sbt `it:test` configuration
is deprecated; enumeratum becomes Scala 3 `enum`. FEDA (2023) predates Scala
3.6's given syntax, Kafka 4 without ZooKeeper, and stable Circe derivation in
Scala 3. Check current versions before you recommend a library.

## Reference files

- `references/effect-systems.md`: find the effect system; role-to-library map
  (Compendium); pattern translation for Cats Effect, ZIO, Kyo, Ox; semantic
  differences; ZIO idioms; bridges between ecosystems.
- `references/domain-modeling.md`: Scala 3 newtypes, Iron, validation, enums,
  derivation, orphan instances, Booleans, collections, match types, optics.
- `references/algebras-and-programs.md`: tagless final, capabilities, modules,
  state, errors, retries, background work, streams, FSMs, MTL, aspects.
- `references/http-and-persistence.md`: http4s routes, auth, clients;
  Skunk, Doobie, transactions, pagination; Redis.
- `references/testing.md`: generators, test interpreters, expectations, law
  tests, route and client tests, integration tests, virtual time.
- `references/assembly-and-operations.md`: configuration, resources, modules,
  `Main`, logging, tracing, metrics, packaging, CI.
- `references/event-driven.md`: EDA decisions, message envelope, delivery
  and idempotency, atomicity, deduplication, FSM services, subscriptions and
  scaling, snapshots, schema evolution, failure scenarios, tracing, testing.
- `references/sources.md`: the books and the page-reference convention.
