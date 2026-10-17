# Algebras, interpreters and programs

How to structure business logic, state, errors and concurrency in a
functional Scala application. Source: Volpe PFP ch. 1-4, 9 and the bonus
chapter, with FEDA ch. 4-5. Cats Effect names; for ZIO, Kyo and Ox see
`effect-systems.md`.

## Contents

1. Tagless final, Scala flavor
2. Capability traits
3. Implicit or explicit dependencies; modules
4. State
5. Errors
6. Programs and failure policies
7. Retries and background work
8. Streams and concurrency
9. Finite state machines
10. MTL, aspects, higher-kinded data
11. Tagless final or concrete IO

## 1. Tagless final, Scala flavor

Three parts (PFP p. 49-56):

- **Algebra:** a trait over `F[_]` that describes a business capability. It has
  **no type-class constraints**. It is not a type class: a type class has one
  coherent instance per type, while an algebra can have many interpreters
  (p. 50).
- **Interpreter:** an implementation, in the companion's `make`. Usually two:
  the real one and a test one (p. 52).
- **Program:** business logic that composes algebras and other programs, and
  carries the constraints. "Program" is Volpe's term (p. 54).

```scala
trait Items[F[_]]:
  def find(id: ItemId): F[Option[Item]]
  def create(item: CreateItem): F[ItemId]

object Items:
  def make[F[_]: MonadCancelThrow: GenUUID](pool: Resource[F, Session[F]]): Items[F] =
    new Items[F]:
      def find(id: ItemId) = pool.use(_.option(ItemSQL.byId)(id))
      def create(item: CreateItem) = ???

// a program: no state, only the constraint it needs (PageViews is in section 4)
final class ItemPage[F[_]: Apply](items: Items[F], views: PageViews[F]):
  def show(id: ItemId, page: PageId): F[Option[Item]] = items.find(id) <* views.record(page)
```

Rules:

- If an algebra seems to need `Monad`, write a program instead (p. 51).
- An interpreter whose dependency has a lifecycle returns
  `Resource[F, Alg[F]]`; the client (a Redis connection, an HTTP client)
  becomes private state of that interpreter (p. 53).
- **Least power:** ask for the weakest type class that works: `Apply` for
  `*>`, `FlatMap` when sequencing matters, `Functor` + `Ref.Make` instead of
  `Sync` (p. 30, 55).
- What a program may do (Volpe's rule set; agree on it as a team): combine
  pure computations through algebras and programs, do only what its
  constraints allow, and log or print only through an algebra (p. 55-56).
- Name algebras consistently (`Items`, `ItemService`, `ItemAlg`: pick one) and
  keep interpreters as anonymous instances in `make` or as named classes with
  private constructors; be consistent (p. 30, 51).

## 2. Capability traits

Small traits for "common effects" that hold no business logic: `Time`,
`GenUUID`, `Retry`, `Background`, `Log`, `MkHttpClient`, `MkRedis`. Each has
a summoner, a default given built on `Sync` or `Temporal` in the companion, and
explicit alternatives for tests (PFP p. 60-66, 101, 254):

```scala
trait GenUUID[F[_]]:
  def make: F[UUID]
object GenUUID:
  def apply[F[_]](using g: GenUUID[F]): GenUUID[F] = g
  given [F[_]: Sync]: GenUUID[F] with
    def make: F[UUID] = Sync[F].delay(UUID.randomUUID())
```

In new code, CE's `cats.effect.std.UUIDGen[F]` gives the same capability.

Why: `Sync` and `Async` grant arbitrary side effects; a capability grants one
operation and can be faked in tests (fixed time, fixed IDs, a recording retry).
A program constrained by `Time: GenUUID: MonadThrow` cannot print, sleep or
fork.

## 3. Implicit or explicit dependencies; modules

| Dependency kind | Pass it |
|---|---|
| lawful type class (`Monad`, `Parallel`, `Concurrent`) | implicitly (context bound) |
| capability trait with no business logic | implicitly |
| business algebra (`Users`, `Orders`, `ShoppingCart`) | explicitly, as a constructor argument |
| resource-backed client (HTTP, Kafka, Redis, DB pool) | explicitly |
| many explicit dependencies | group them into modules |

(PFP p. 57-60.) A context-bound list of business algebras
(`[F[_]: Users: Items: Kafka: ...]`) is an anti-pattern (p. 57-58).

**Modules** group related algebras. Make each one a sealed abstract class
with a private constructor and a `make`, so nobody else can build or extend
it (PFP p. 58-60, 246-253):

```scala
sealed abstract class Services[F[_]] private (val items: Items[F], val orders: Orders[F], val cart: ShoppingCart[F])

object Services:
  def make[F[_]: MonadCancelThrow: GenUUID](postgres: Resource[F, Session[F]], redis: RedisCommands[F, String, String]): Services[F] =
    val items = Items.make[F](postgres)
    new Services[F](items, Orders.make[F](postgres), ShoppingCart.make[F](items, redis)) {}
```

Interpreters that share a dependency get the same instance (`items` passed to
`ShoppingCart` and also exposed).

## 4. State

- **State belongs inside the interpreter.** The algebra knows nothing about
  it; `make` allocates the `Ref`, and it never escapes (PFP p. 29-30):

  ```scala
  trait PageViews[F[_]]:
    def record(page: PageId): F[Unit]
    def count(page: PageId): F[Long]
  object PageViews:
    def make[F[_]: Functor: Ref.Make]: F[PageViews[F]] =
      Ref[F].of(Map.empty[PageId, Long]).map { hits =>
        new PageViews[F]:
          def record(page: PageId) = hits.update(m => m.updated(page, m.getOrElse(page, 0L) + 1))
          def count(page: PageId) = hits.get.map(_.getOrElse(page, 0L))
      }
  ```

  `make` returns `F[...]` because allocating a `Ref` is an effect; each call
  creates a new, empty tracker. Never take the `Ref` as an argument.
- **Sequential vs concurrent state:** the State monad cannot be shared between
  fibers; shared state needs an atomic `Ref` (PFP p. 32).
- **Regions of sharing:** create concurrent structures (`Ref`, `Semaphore`,
  `Deferred`, `Queue`) in `F` and share them inside one visible `flatMap`
  block. A global `lazy val sem = Semaphore[IO](1).unsafeRunSync()` is "leaky
  state": unknown code can take the permit and never release it (PFP
  p. 34-36).
- **Pure updates.** `Ref.update` may run its function several times under
  contention. Keep it pure; for updates that run effects, use `AtomicCell`
  (Cats Effect) or `Ref.Synchronized` (ZIO). Use the atomic `update` or
  `modify` for read-modify-write; never `get` then `set`.
- **Test interpreters may take their `Ref` as an argument** so the test can
  inspect the state; "never accept the Ref from outside" applies to
  production interpreters (PFP p. 52, 205-233).
- **Narrow capability interfaces** (a `LimitedConcurrency` instead of
  `Concurrent`) keep the effect library out of programs. With capabilities,
  application code does not depend on the Cats Effect major version (the
  version still matters to library authors), and a migration, to CE3 or to
  another effect system, changes only the default instances (PFP p. 54, 61).
- **One-shot signals and gates:** `Deferred` (ZIO `Promise`), for example a
  start-up gate that holds a consumer until a replay finishes (FEDA
  p. 158-159).

## 5. Errors

| Situation | Use |
|---|---|
| the caller only needs success; unknown failures go to the framework (HTTP 500) | `F[A]`, raising a sealed ADT that extends `NoStackTrace` |
| some errors map to responses | `F[A]` and `recoverWith` on the known cases at the edge |
| the caller's logic branches on the error, and you want exhaustiveness | `F[Either[E, A]]`; accept the lifting cost |
| absence is normal | `F[Option[A]]` (not `OptionT` in the interface) |
| expose a few error types from a raising call | `fa.attemptNarrow[DuplicateUser]` with an explicit type (FEDA p. 226) |
| ZIO | the typed `E` channel; Scala 3 unions of error types |
| Kyo | `Abort[E]` |

(PFP p. 44-47.)

- Ask: "what am I going to do with the error information?" The answer picks
  the encoding (PFP p. 45).
- Code the happy path; handle business errors where they become responses;
  let the framework turn the rest into a 500 (p. 45).
- Translate infrastructure errors with domain meaning in the interpreter:
  `SqlState.UniqueViolation` becomes `UserNameInUse` (p. 171-172).
- Map retried failures to domain errors with `adaptError` (p. 100).
- Typed error channels inside `F` (classy prisms, Cats MTL `Raise`) are
  possible; Volpe now calls them experimental (p. 47).
- `attemptNarrow` does not fit transactional code: errors must propagate for
  the transaction to roll back (FEDA p. 226).
- Do not raise or handle errors with `Exception` subclasses that carry stack
  traces for domain errors: stack traces are costly and add little (PFP
  p. 39).

## 6. Programs and failure policies

For a critical workflow, decide the reaction to a failure at each step, and
let the business choose among the options you present (PFP p. 95-98). The
checkout example:

| Step | Failure | Policy |
|---|---|---|
| read the cart | missing, or DB error | fail; nothing to do |
| check the cart | empty | raise `EmptyCartError` (or require `NonEmptyList`) |
| call the payment API | network error, timeout, 408/429/5xx | retry with the same payment ID and a configured policy; when retries run out the outcome is unknown: keep the order pending for a reconciler, do not cancel |
| call the payment API | other 4xx (declined, invalid) | definite failure: raise `PaymentError`; release what you reserved |
| call the payment API | succeeded, but the response was lost | retry; the idempotent API answers 409 with the payment ID; treat it as success |
| create the order | DB failure | retry a few times; the customer is already charged, so reschedule order creation in the background |
| delete the cart | failure | ignore (`.attempt.void`); the cart expires anyway |

Decide what the caller sees for deferred work and encode it as a type
(`Created(orderId)` or `Pending`), with a test. In the book the code reschedules
and then raises `OrderError`, which the route maps to 400, although the
order will be created later (PFP p. 97, 100, 119, 208).

Failure-policy rules the book implies (critic notes, not stated as rules in
the book):

- **Retry only transient errors.** A retry-on-all-errors policy also retries
  permanent failures (a 4xx). Classify errors before you retry: timeouts,
  connection errors, 408, 425, 429 and 5xx are transient.
- **An exhausted retry is not a decline.** After a timeout the charge may
  have happened. Persist the order as pending before the first call, and let
  a reconciler query the provider; compensate only on a definite failure.
- **Make retried writes idempotent.** A unique payment ID protects against a
  double insert, but if the unique violation is not mapped to "already done",
  a retry after a lost acknowledgment loops forever.
- **Background work kept in memory** (a `Supervisor`, a queue) **is lost on
  restart, crash or deploy.** For work that must happen after an effect that
  cannot be undone (the customer was charged), persist a pending record (an
  outbox row) and process it from there. Bound the background retry loop.
- **Validate on the back end** before you call a remote system that costs
  money or has side effects, even when the front end validates (p. 119-120).

The same kind of program for an order workflow, with the policy of each step
in a comment (the book's version is `Checkout`, p. 102):

```scala
final class PlaceOrder[F[_]: MonadThrow: Retry: Background](
    stock: Stock[F], payments: Payments[F], orders: Orders[F], mail: Mailer[F], policy: RetryPolicy[F]):

  def apply(buyer: BuyerId, lines: List[Line]): F[Placed] =
    for
      items   <- NonEmptyList.fromList(lines).liftTo[F](EmptyOrder)
      hold    <- stock.reserve(items)                     // fail: nothing is charged yet
      pending <- orders.createPending(buyer, hold)        // its ID is the payment's idempotency key
      placed  <- charge(pending)                          // see the table: decline, paid, or still pending
      _       <- mail.confirm(buyer, placed).attempt.void // best effort
    yield placed
```

Here `charge` retries transient errors with the same key, marks the order
paid on success, releases the hold on a decline, and returns
`Placed.Pending` when the outcome is still unknown; a reconciler finishes
pending orders. This differs from the book, which charges before it writes
the order.

Write `.void` explicitly when you discard a result in a for-comprehension
(p. 98). Test each branch with test interpreters (`testing.md`).

## 7. Retries and background work

- **Retry as a capability** (PFP p. 98-100): `Retry[F].retry(policy,
  retriable)(fa)`, with a default instance on cats-retry that logs "will
  retry" and "giving up". `Retriable` is an ADT that names the action in
  logs. Policies compose: `limitRetries(3) |+| exponentialBackoff(10.millis)`;
  their values come from configuration. (cats-retry 4, Scala 3 only:
  `retryingOnErrors(fa)(policy, errorHandler)`, and the handler runs on every
  attempt; `|+|` is `join`; `RetryDetails(retriesSoFar, cumulativeDelay,
  nextStepIfUnsuccessful)`. ZIO: `retry(Schedule...)`.)
- **Background as a capability** (p. 101-102):
  `Background[F].schedule(fa, delay)`, with a default instance on a
  `Supervisor` (the fiber's lifetime is bound to the supervisor, not to the
  caller).
- **Better: background work as a `Resource`** built from a queue and a stream,
  so cancellation and errors are handled and you keep control of the process
  (p. 293-296). This version uses a bounded queue, so `schedule` waits when
  the queue is full:

  ```scala
  final case class Job[F[_]](after: FiniteDuration, run: F[Unit])

  def background[F[_]: Temporal](capacity: Int): Resource[F, Background[F]] =
    Stream.eval(Queue.bounded[F, Job[F]](capacity)).flatMap { jobs =>
      val runner = Stream.fromQueueUnterminated(jobs)
        .map(job => Stream.sleep_[F](job.after) ++ Stream.exec(job.run.attempt.void))
        .parJoinUnbounded
      val handle = new Background[F]:
        def schedule[A](fa: F[A], d: FiniteDuration) = jobs.offer(Job(d, fa.void))
      Stream.emit(handle).concurrently(runner)
    }.compile.resource.lastOrError
  ```

## 8. Streams and concurrency

- **Prefer streams to raw fibers.** Streams handle scoping, interruption and
  resource safety (PFP p. 286-288, 294-296).
- **Relationships between processes** (PFP p. 286-288; FEDA p. 106-108):

  | Relationship | Combinator |
  |---|---|
  | unrelated long-running processes (HTTP server and a consumer) | `Stream(a, b).parJoin(n)`; any failure stops all |
  | B exists only to serve A | `a.concurrently(b)`; B stops when A ends |
  | an unbounded number of child streams | `parJoinUnbounded` |
  | two effects, both needed | `(fa, fb).parTupled` |
  | a server in a stream | `Stream.eval(server.useForever)` |

- **Producer and consumer:** a bounded `Queue` between them; in-process
  fan-out with `fs2.concurrent.Topic`; pause and resume with `pauseWhen` on a
  `SignallingRef`; stop with `interruptWhen(deferred)`; clean up with
  `onFinalize` (PFP p. 285-292).
- **Pipeline stages** (FEDA p. 99-102): ordered effects with `evalMap` or
  `parEvalMap(n)`; unordered with `parEvalMapUnordered(n)`; batch slow sinks
  with `chunkN(n)` or `groupWithin(n, d)` and one write per chunk; keep
  real-time and batch stages separate (separate subscribers of a `Topic`, or
  separate consumers).
- **Uncancelable regions:** wrap a wait that may never complete in `poll`,
  otherwise a canceled fiber hangs forever (PFP p. 292-293). A step after a
  polled wait does not run on cancellation; attach cleanup with `onCancel` or
  `guarantee`, or use `Resource`:

  ```scala
  Async[F].uncancelable { poll =>
    acquire.flatMap(r => poll(gate.get).onCancel(release(r)) *> release(r))
  }
  Resource.make(acquire)(release).surround(gate.get) // simpler; also releases on error
  ```

- **Low-level constructs:** build them from `Ref` (atomic state), `Deferred`
  (one-shot signal), `Queue`, `Semaphore` (limits) and `Resource` (lifetimes).
  Give each one an algebra and keep the primitives private. Follow the
  checklist and construct catalog in `functional-design`'s
  `concurrency-constructs.md` (Marlow): every fork has an owner and a scope
  (`background`, `Supervisor`, not bare `start`); child errors reach the
  waiter; `Ref.update` stays pure (`AtomicCell` for effectful updates); keep
  `uncancelable` regions small and `poll` every wait inside them; never retry
  from inside an error handler; default to bounded queues and test them with
  capacity 1.

## 9. Finite state machines

A pure FSM is the best shape for stateful message processing (PFP
p. 296-298; FEDA p. 94-96):

```scala
final case class FSM[F[_], S, In, Out](step: (S, In) => F[(S, Out)]):
  def nextState(using Functor[F]): (S, In) => F[S] = (s, in) => step(s, in).map(_._1)

object FSM:
  def pure[S, In, Out](f: (S, In) => (S, Out)): FSM[Id, S, In, Out] = FSM[Id, S, In, Out](f)

type Stamp = (EventId, Timestamp) => CartEvent

val cart = FSM.pure[CartState, CartCommand, Stamp] {
  case (open: CartState.Open, CartCommand.Add(sku, qty)) => (open.add(sku, qty), CartEvent.Added(_, sku, qty, _))
  case (open: CartState.Open, CartCommand.Close)         => (open.close, CartEvent.Closed(_, _))
  case (st, cmd)                                         => (st, CartEvent.Rejected(_, cmd, _))
}

// the service wraps the pure engine and supplies IDs and time
def liveCart[F[_]: Monad: GenUUID: Time]: FSM[F, CartState, CartCommand, CartEvent] =
  FSM { (st, cmd) =>
    val (next, stamp) = cart.step(st, cmd)
    (GenUUID[F].make.map(EventId(_)), Time[F].timestamp).mapN(stamp).tupleLeft(next)
  }

commands.evalMapAccumulate(CartState.empty)(liveCart[IO].step)
```

- Prefer `F = Id` and return a function of `(EventId, Timestamp)`, so IDs and
  time come from the caller and the FSM stays pure and table-testable.
- Write the transition table (state, input) -> (new state, output) before the
  code; each row becomes one case (FEDA p. 132-133).
- Put the shared pure engine in a core module and wrap it per service with the
  effects (IDs, time, producers, acks, transactions) (FEDA p. 135-138).
- ZIO: `ZStream.mapAccumZIO`.

## 10. MTL, aspects, higher-kinded data

- **Cats MTL** (`Stateful`, `Ask`) expresses effects as fine-grained type
  classes. Materialize them with a `Ref`-backed or value-backed instance on
  `IO` instead of `StateT` or `Kleisli` (faster, concurrent-safe). Still prefer
  small interfaces over raw state (PFP p. 273-276).
- **Aspects** (logging, metrics) around an algebra: Tofu's `Mid` combines
  `Alg[Mid[F, *]]` decorators; the order of composition decides which wraps
  which (PFP p. 281-284). A plain decorator interpreter does the same and has
  no extra dependency:

  ```scala
  def logged[F[_]: FlatMap: Logger](inner: Users[F]): Users[F] = new Users[F]:
    def register(u: NewUser) = Logger[F].info(s"register ${u.name}") *> inner.register(u)
  ```

- **Higher-kinded data** for record variants (Granin's HKD):
  `case class User[F[_]](id: F[UserId], name: UserName)` with
  `User[Option]` before insertion and `User[Id]` after.
- **cats-tagless** (`FunctorK`, `mapK`) changes the effect of a whole algebra,
  for example to add tracing (`F` to `Kleisli[F, Span[F], *]`).

## 11. Tagless final or concrete IO

Present this as a choice with trade-offs, not a rule (PFP p. 62-67):

- **Abstract `F` with capabilities:** least power, parametricity, test
  instances swap freely; Volpe finds that juniors learn it faster. Costs:
  more type parameters and instances, and Scala does not enforce purity
  inside `F` code, so review still matters.
- **Concrete `IO` with injected interfaces:** testable and simpler, but every
  function has full `IO` power ("IO is quite permissive").
- **ZIO services, Kyo effects, Ox direct style:** follow the project; the
  algebra / interpreter / program split still applies (see
  `effect-systems.md`).
- Granin's comparison in `functional-design` rates type-based injection as
  the least testable; Volpe's style (algebras passed explicitly as values,
  type classes only for generic capabilities) avoids most of that critique.
