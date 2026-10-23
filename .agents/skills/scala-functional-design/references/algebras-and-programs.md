# Algebras, interpreters and programs

How to structure business logic, state, errors and concurrency in a
functional Scala application. Sources: mainly PFP ch. 1-4, 9 and the bonus
chapter; FEDA ch. 4-6. Cats Effect names; for ZIO, Kyo and Ox see
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

Three parts (PFP ch. 2):

- **Algebra:** a trait over `F[_]` that describes a business capability. It has
  **no type-class constraints**. Do not confuse it with a type class:
  coherence demands one instance per type, but an algebra is meant to have
  several interpreters.
- **Interpreter:** an implementation, in the companion's `make`. Usually two:
  the real one and a test one. PFP uses one module. In a multi-module build
  whose core has no infrastructure dependencies, put an interpreter that
  needs infrastructure on its implementation in the infrastructure module
  (`object PostgresBooks { def make ... }`), not in the algebra's companion
  in core (practice).
- **Program:** business logic that composes algebras and other programs, and
  carries the constraints. "Program" is Volpe's term.

```scala
trait Books[F[_]]:
  def find(isbn: Isbn): F[Option[Book]]
  def add(book: NewBook): F[Unit]

object Books:
  def make[F[_]: MonadCancelThrow](pool: Resource[F, Session[F]]): Books[F] =
    new Books[F]:
      def find(isbn: Isbn) = pool.use(_.option(BookSQL.byIsbn)(isbn))
      def add(book: NewBook) = pool.use(_.execute(BookSQL.insert)(book)).void

// a program: no state, only the constraint it needs (PageViews is in section 4)
final class BookPage[F[_]: Apply](books: Books[F], views: PageViews[F]):
  def show(isbn: Isbn, page: PageId): F[Option[Book]] = books.find(isbn) <* views.record(page)
```

Rules:

- If an algebra seems to need `Monad`, write a program instead.
- An interpreter whose dependency has a lifecycle returns
  `Resource[F, Alg[F]]`; the client (a Redis connection, an HTTP client)
  becomes private state of that interpreter.
- **No monad transformers in algebra or program signatures.** Return
  `F[Option[A]]` or `F[Either[E, A]]`, not `OptionT` or `EitherT`: a
  transformer in an interface makes every caller unwrap and rewrap it, and
  two different transformers do not compose. Use one locally, inside an
  implementation, when it makes that code shorter (PFP ch. 1).
- **Least power:** ask for the weakest type class that works: `Apply` for
  `*>`, `FlatMap` when sequencing matters, `Functor` + `Ref.Make` instead of
  `Sync` (PFP ch. 1 and 2).
- Agree as a team on what a program may do. A workable rule set: a program
  reaches the outside world only through algebras, other programs and the
  capabilities in its constraints, and it logs or prints only through an
  algebra.
- Name algebras consistently (`Items`, `ItemService`, `ItemAlg`: pick one) and
  keep interpreters as anonymous instances in `make` or as named classes with
  private constructors; be consistent (PFP ch. 1 and 2).

## 2. Capability traits

Small traits for common effects that hold no business logic: `Time`,
`GenUUID`, `Retry`, `Background`, `Log`, `MkHttpClient`, `MkRedis` (PFP ch. 2,
4 and 9). Each has a summoner, a default given in the companion (built on
`Sync`, `Temporal` or a smaller capability), and explicit alternatives for
tests. A capability can return a domain type directly:

```scala
trait OrderIds[F[_]]:
  def next: F[OrderId]

object OrderIds:
  def apply[F[_]](using ids: OrderIds[F]): OrderIds[F] = ids
  given [F[_]: Functor: UUIDGen]: OrderIds[F] with
    def next = UUIDGen[F].randomUUID.map(OrderId(_))
  def counting[F[_]: Functor](n: Ref[F, Long]): OrderIds[F] = new OrderIds[F]:
    def next = n.updateAndGet(_ + 1).map(i => OrderId(new UUID(0L, i)))
```

For raw UUIDs, Cats Effect already has `cats.effect.std.UUIDGen[F]`; build
domain ID capabilities on it.

Why: `Sync` and `Async` grant arbitrary side effects; a capability grants one
operation and can be faked in tests (fixed time, fixed IDs, a recording retry).
A program constrained by `Time: GenUUID: MonadThrow` cannot print, sleep or
fork.

## 3. Implicit or explicit dependencies; modules

Source: PFP ch. 2 (modules also ch. 9).

| Dependency kind | Pass it |
|---|---|
| lawful type class (`Monad`, `Parallel`, `Concurrent`) | implicitly (context bound) |
| capability trait with no business logic | implicitly |
| business algebra (`Users`, `Orders`, `ShoppingCart`) | explicitly, as a constructor argument |
| resource-backed client (HTTP, Kafka, Redis, DB pool) | explicitly |
| many explicit dependencies | group them into modules |

A context-bound list of business algebras (`[F[_]: Users: Items: Kafka: ...]`)
is an anti-pattern.

**Modules** group related algebras. Make each one a sealed abstract class
with a private constructor and a `make`, so nobody else can build or extend
it:

```scala
sealed abstract class Lending[F[_]] private (val books: Books[F], val loans: Loans[F], val reminders: Reminders[F])

object Lending:
  def make[F[_]: MonadCancelThrow](db: Resource[F, Session[F]], mail: Mailer[F]): Lending[F] =
    val loans = Loans.make[F](db)
    new Lending[F](Books.make[F](db), loans, Reminders.make[F](loans, mail)) {}
```

Interpreters that share a dependency get the same instance (`loans` goes to
`Reminders` and is also exposed).

## 4. State

Source: PFP ch. 1, unless noted.

- **State belongs inside the interpreter.** The algebra knows nothing about
  it; `make` allocates the `Ref`, and it never escapes:

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
  fibers; shared state needs an atomic `Ref`.
- **Regions of sharing:** create concurrent structures (`Ref`, `Semaphore`,
  `Deferred`, `Queue`) in `F` and share them inside one visible `flatMap`
  block. A global `lazy val permits = Semaphore[IO](1).unsafeRunSync()` is
  what PFP calls "leaky state": unknown code can take the permit and never
  release it.
- **Pure updates.** `Ref.update` may run its function several times under
  contention. Keep it pure; for updates that run effects, use `AtomicCell`
  (Cats Effect) or `Ref.Synchronized` (ZIO). Use the atomic `update` or
  `modify` for read-modify-write; never `get` then `set`.
- **Test interpreters may take their `Ref` as an argument** so the test can
  inspect the state; "never accept the Ref from outside" applies to
  production interpreters (PFP ch. 2 and 8).
- **Narrow capability interfaces** (a `LimitedConcurrency` instead of
  `Concurrent`) keep the effect library out of programs. With capabilities,
  application code does not depend on the Cats Effect major version (the
  version still matters to library authors), and a migration, to CE3 or to
  another effect system, changes only the default instances (PFP ch. 2).
- **One-shot signals and gates:** `Deferred` (ZIO `Promise`), for example a
  start-up gate that holds a consumer until a replay finishes (FEDA ch. 6).

## 5. Errors

Source: PFP ch. 1, unless noted.

| Situation | Use |
|---|---|
| the caller only needs success; unknown failures go to the framework (HTTP 500) | `F[A]`, raising a sealed ADT that extends `NoStackTrace` |
| some errors map to responses | `F[A]` and `recoverWith` on the known cases at the edge |
| the caller's logic branches on the error, and you want exhaustiveness | `F[Either[E, A]]`; accept the lifting cost |
| absence is normal | `F[Option[A]]` (not `OptionT` in the interface) |
| expose a few error types from a raising call | `fa.attemptNarrow[DuplicateUser]` with an explicit type (FEDA ch. 8) |
| ZIO | the typed `E` channel; Scala 3 unions of error types |
| Kyo | `Abort[E]` |

- Ask what the caller will do with the error information. The answer picks
  the encoding.
- Write the success path; handle business errors where they become
  responses; let the framework turn the rest into a 500.
- Translate infrastructure errors with domain meaning in the interpreter:
  `SqlState.UniqueViolation` becomes `UserNameInUse` (PFP ch. 7).
- Map retried failures to domain errors with `adaptError` (PFP ch. 4).
- Typed error channels inside `F` (classy prisms, Cats MTL `Raise`) are
  possible; the 2nd edition of PFP leaves them out as experimental.
- `attemptNarrow` does not fit transactional code: errors must propagate for
  the transaction to roll back (FEDA ch. 8).
- Do not raise or handle errors with `Exception` subclasses that carry stack
  traces for domain errors: stack traces are costly and add little.

## 6. Programs and failure policies

For a critical workflow, decide the reaction to a failure at each step, and
let the business choose among the options you present (PFP ch. 4). Example
for a checkout, the workflow that PFP ch. 4 analyzes; some rows apply the
rules that follow the table:

| Step | Failure | Policy |
|---|---|---|
| read the cart | missing, or DB error | fail; nothing to do |
| check the cart | empty | raise `EmptyCartError` (or require `NonEmptyList`) |
| create the pending order | the caller retries with the same request key | return the stored order: its earlier result, or continue it while it is pending; never start a second order or charge |
| create the pending order | DB failure | fail; nothing is charged yet |
| call the payment API | network error, timeout, 408/425/429/5xx | retry with the same payment ID and a configured policy; when retries run out the outcome is unknown: keep the order pending for a reconciler, do not cancel |
| call the payment API | 401, 403 or 404 (our key or URL is wrong) | a defect: alert and keep the order pending; it is not a decline |
| call the payment API | other 4xx (declined, invalid) | definite failure: raise `PaymentError`; release what you reserved |
| call the payment API | succeeded, but the response was lost | retry; the idempotent API answers 409 with the payment ID; treat it as success |
| mark the order paid | DB failure | retry a few times; the customer is already charged, so the order stays pending for the reconciler (PFP, which creates the order only after the charge, reschedules order creation in the background here) |
| delete the cart | failure | ignore (`.attempt.void`); the cart expires anyway |

Decide what the caller sees for deferred work and encode it as a type
(`Created(orderId)` or `Pending`), with a test. PFP's checkout reschedules the
order and still raises `OrderError`, which its route maps to 400, although
the order is created later (PFP ch. 4, 5 and 8).

Failure-policy rules (practice):

- **Retry only transient errors.** A retry-on-all-errors policy also retries
  permanent failures (a 4xx). Classify errors before you retry: timeouts,
  connection errors, 408, 425, 429 and 5xx are transient.
- **An exhausted retry is not a decline.** After a timeout the charge may
  have happened. Persist the order as pending before the first call, and let
  a reconciler query the provider; compensate only on a definite failure.
- **Make retried writes idempotent.** A unique payment ID protects against a
  double insert, but if the unique violation is not mapped to "already done",
  a retry after a lost acknowledgment loops forever.
- **Take the idempotency key from the caller.** An ID that the program makes
  in the same call (a new order ID, `GenUUID`) covers only the retries inside
  that call. When the customer retries the whole checkout after a lost
  response, the program makes a new ID and the provider charges again. Take
  a client-supplied request key (an `Idempotency-Key` header, a client
  checkout ID) or a stable natural key (cart ID and version), make it unique
  per buyer in the orders table (`UNIQUE (buyer_id, request_key)`), and for
  a repeated key return the stored result. A key unique across all buyers
  lets one buyer's key return another buyer's order; a repeated key with a
  different cart is an error, not the stored result. A "one pending order
  per user" index is not enough: it blocks a second checkout only while the
  first one is pending, so a retry after a completed checkout whose
  response was lost charges again.
- **The reconciler races with a late retry.** It fails a pending order only
  when the provider has no charge for the key and no attempt can still be
  running. The charge path and the reconciler both settle the order with a
  conditional update (`WHERE status = 'pending'`); a charge confirmed for an
  order that is no longer pending is refunded or alerted on, never dropped.
- **Background work kept in memory** (a `Supervisor`, a queue) **is lost on
  restart, crash or deploy.** For work that must happen after an effect that
  cannot be undone (the customer was charged), persist a pending record (an
  outbox row) and process it from there. Bound the background retry loop.
- **Validate on the back end** before you call a remote system that costs
  money or has side effects, even when the front end validates (PFP ch. 5).

The same kind of program for an order workflow, with the policy of each step
in a comment (PFP ch. 4 has a `Checkout` program of this kind):

```scala
final class PlaceOrder[F[_]: MonadThrow: Retry: Background](
    stock: Stock[F], payments: Payments[F], orders: Orders[F], mail: Mailer[F], policy: RetryPolicy[F, Throwable]):

  // key: the caller's request key (an Idempotency-Key header); a retry sends it again
  def apply(key: RequestKey, buyer: BuyerId, lines: List[Line]): F[Placed] =
    for
      items  <- NonEmptyList.fromList(lines).liftTo[F](EmptyOrder)
      hold   <- stock.reserve(buyer, key, items)          // keyed per buyer: a retry gets the same hold; nothing is charged yet
      order  <- orders.createPending(key, buyer, hold)    // unique per (buyer, key): a retry gets the stored order
      placed <- order match
                  case p: OrderRecord.Pending => charge(p)        // payment key = p's stored ID: one charge
                  case s: OrderRecord.Settled => s.placed.pure[F] // a retry after a lost response: the earlier result
      _      <- mail.confirm(buyer, placed).attempt.void  // best effort
    yield placed
```

The caller's key protects the whole workflow. `createPending` inserts with
`ON CONFLICT (buyer_id, request_key) DO NOTHING` and reads the row back, so
a retry after a lost response gets the stored result, and a retry while the
outcome is unknown charges again with the stored order's ID as the payment
key, which the provider deduplicates. That ID is stable because the row is
found by the caller's key, and unlike the bare request key it is unique
across buyers. An order ID made fresh in each call of `apply` would not do
this: the retry would make a second order with a new key, and a second
charge. `charge` retries
transient errors with the same key, marks the order paid on success,
releases the hold on a decline, and returns `Placed.Pending` when the
outcome is still unknown; a reconciler finishes pending orders. This differs
from PFP, which charges before it writes the order and takes no key from the
caller.

Write `.void` explicitly when you discard a result in a for-comprehension
(PFP ch. 4). Test each branch with test interpreters (`testing.md`).

## 7. Retries and background work

- **Retry as a capability** (PFP ch. 4): `Retry[F].retry(policy,
  retriable)(fa)`, with a default instance on cats-retry that logs each retry
  and the final give-up. `Retriable` is an ADT that names the action in
  logs. Policies compose: `limitRetries(3) |+| exponentialBackoff(10.millis)`;
  their values come from configuration. (cats-retry 4, Scala 3 only:
  `retryingOnErrors(fa)(policy, errorHandler)`, and the handler runs on every
  failed attempt; its policy is a `RetryPolicy[F, Throwable]`; `|+|` is
  `join`; `RetryDetails(retriesSoFar, cumulativeDelay,
  nextStepIfUnsuccessful)`. ZIO: `retry(Schedule...)`.)
- **Background as a capability** (PFP ch. 4):
  `Background[F].schedule(fa, delay)`, with a default instance on a
  `Supervisor` (the fiber's lifetime is bound to the supervisor, not to the
  caller).
- **Better: background work as a `Resource`** built from a queue and a stream,
  so cancellation and errors are handled and you keep control of the process
  (PFP bonus chapter). This sketch uses a bounded queue, so `schedule` waits
  when the queue is full, and it stops the runner when the resource closes:

  ```scala
  final case class Job[F[_]](after: FiniteDuration, run: F[Unit])

  def background[F[_]: Temporal](capacity: Int): Resource[F, Background[F]] =
    for
      jobs <- Resource.eval(Queue.bounded[F, Job[F]](capacity))
      _    <- Stream.fromQueueUnterminated(jobs)
                .map(job => Stream.exec(job.run.attempt.void).delayBy(job.after))
                .parJoinUnbounded
                .compile.drain.background
    yield new Background[F]:
      def schedule[A](fa: F[A], d: FiniteDuration) = jobs.offer(Job(d, fa.void))
  ```

## 8. Streams and concurrency

Sources: PFP bonus chapter; FEDA ch. 5.

- **Prefer streams to raw fibers.** Streams handle scoping, interruption and
  resource safety.
- **Relationships between processes:**

  | Relationship | Combinator |
  |---|---|
  | unrelated long-running processes (HTTP server and a consumer) | `Stream(a, b).parJoin(n)`; any failure stops all |
  | B exists only to serve A | `a.concurrently(b)`; B stops when A ends |
  | an unbounded number of child streams | `parJoinUnbounded` |
  | two effects, both needed | `(fa, fb).parTupled` |
  | a server in a stream | `Stream.eval(server.useForever)` |

- **Producer and consumer:** put a bounded `Queue` between them. For
  in-process fan-out use `fs2.concurrent.Topic`; to pause and resume, use
  `pauseWhen` on a `SignallingRef`; to stop, use `interruptWhen(deferred)`;
  to clean up, use `onFinalize`.
- **Pipeline stages** (FEDA ch. 5): ordered effects with `evalMap` or
  `parEvalMap(n)`; unordered with `parEvalMapUnordered(n)`; batch slow sinks
  with `chunkN(n)` or `groupWithin(n, d)` and one write per chunk; keep
  real-time and batch stages separate (separate subscribers of a `Topic`, or
  separate consumers).
- **Uncancelable regions:** wrap a wait that may never complete in `poll`,
  otherwise a canceled fiber hangs forever (PFP bonus chapter). A step after
  a polled wait does not run on cancellation; attach cleanup with `onCancel`
  or `guarantee`, or use `Resource`:

  ```scala
  Async[F].uncancelable { poll =>
    acquire.flatMap(r => poll(gate.get).onCancel(release(r)) *> release(r))
  }
  Resource.make(acquire)(release).surround(gate.get) // simpler; also releases on error
  ```

- **Low-level constructs:** build them from `Ref` (atomic state), `Deferred`
  (one-shot signal), `Queue`, `Semaphore` (limits) and `Resource` (lifetimes).
  Give each one an algebra and keep the primitives private. Follow the
  concurrency-constructs checklist and catalog of the `functional-design`
  skill (from Marlow): every fork has an owner and a scope
  (`background`, `Supervisor`, not bare `start`); child errors reach the
  waiter; `Ref.update` stays pure (`AtomicCell` for effectful updates); keep
  `uncancelable` regions small and `poll` every wait inside them; never retry
  from inside an error handler; default to bounded queues and test them with
  capacity 1.

## 9. Finite state machines

A pure FSM is the best shape for stateful message processing (PFP bonus
chapter; FEDA ch. 5 and 6). The engine below returns a function that still
needs an event ID and a time, so it stays pure:

```scala
final case class FSM[F[_], S, In, Out](step: (S, In) => F[(S, Out)])

type Stamp = (EventId, Timestamp) => CartEvent

val cart: FSM[Id, CartState, CartCommand, Stamp] = FSM {
  case (CartState.Open(ls), CartCommand.Add(sku, n)) => (CartState.Open(ls.add(sku, n)), CartEvent.Added(_, sku, n, _))
  case (CartState.Open(ls), CartCommand.Close)       => (CartState.Closed(ls), CartEvent.Closed(_, _))
  case (st, cmd)                                     => (st, CartEvent.Rejected(_, cmd, _))
}

// the service supplies IDs and time around the pure engine
def stamped[F[_]: Monad: UUIDGen: Clock](st: CartState, cmd: CartCommand): F[(CartState, CartEvent)] =
  val (next, stamp) = cart.step(st, cmd)
  (UUIDGen[F].randomUUID.map(EventId(_)), Clock[F].realTimeInstant.map(Timestamp(_))).mapN(stamp).tupleLeft(next)

commands.evalMapAccumulate(CartState.empty)(stamped[IO])
```

- Prefer `F = Id` and return a function of `(EventId, Timestamp)`, so IDs and
  time come from the caller and the FSM stays pure and table-testable.
- Write the transition table (state, input) -> (new state, output) before the
  code; each row becomes one case (FEDA ch. 6).
- Put the shared pure engine in a core module and wrap it per service with the
  effects (IDs, time, producers, acks, transactions) (FEDA ch. 6).
- ZIO: `ZStream.mapAccumZIO`.

## 10. MTL, aspects, higher-kinded data

- **Cats MTL** (`Stateful`, `Ask`) expresses effects as fine-grained type
  classes. Materialize them with a `Ref`-backed or value-backed instance on
  `IO` instead of `StateT` or `Kleisli` (faster, concurrent-safe). Still prefer
  small interfaces over raw state (PFP bonus chapter).
- **Aspects** (logging, metrics) around an algebra: Tofu's `Mid` combines
  `Alg[Mid[F, *]]` decorators; the order of composition decides which wraps
  which (PFP bonus chapter). A plain decorator interpreter does the same and
  has no extra dependency:

  ```scala
  def logged[F[_]: FlatMap: Logger](inner: Users[F]): Users[F] = new Users[F]:
    def register(u: NewUser) = Logger[F].info(s"register ${u.name}") *> inner.register(u)
  ```

- **Higher-kinded data** for record variants (Granin ch. 12; see the
  `functional-design` skill):
  `case class User[F[_]](id: F[UserId], name: UserName)` with
  `User[Option]` before insertion and `User[Id]` after.
- **cats-tagless** (`FunctorK`, `mapK`) changes the effect of a whole algebra,
  for example to add tracing (`F` to `Kleisli[F, Span[F], *]`).

## 11. Tagless final or concrete IO

Present this as a choice with trade-offs, not a rule (PFP ch. 2):

- **Abstract `F` with capabilities:** least power, parametricity, test
  instances swap freely; in Volpe's experience, beginners learn it faster.
  Costs: more type parameters and instances, and Scala does not enforce
  purity inside `F` code, so review still matters.
- **Concrete `IO` with injected interfaces:** testable and simpler, but every
  function has full `IO` power, and Volpe warns that this makes mistakes
  easy.
- **ZIO services, Kyo effects, Ox direct style:** follow the project; the
  algebra / interpreter / program split still applies (see
  `effect-systems.md`).
- Granin's comparison (Granin ch. 13 and 15; see the functional-interfaces
  guidance of the `functional-design` skill) rates type-based injection as
  the least testable; Volpe's style (algebras passed explicitly as values, type classes
  only for generic capabilities) avoids most of that critique.
