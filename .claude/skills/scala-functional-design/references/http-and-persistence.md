# HTTP and persistence

The edges of a Scala service: HTTP routes, authentication, HTTP clients,
SQL, transactions and Redis. Sources: Volpe PFP ch. 5 and 7, FEDA ch. 2 and
7. Library names are from the Typelevel stack; map them with
`effect-systems.md` (ZIO HTTP, Tapir, ZIO Quill, zio-redis, ...). The
general rules (three data models, DTOs, trust boundaries) are in
`functional-design`'s `persistence-and-boundaries.md`.

## Contents

1. Routes
2. Request validation and error mapping
3. Authentication
4. HTTP clients for remote APIs
5. SQL: library choice and layout
6. Large results
7. Transactions
8. Redis and expiring data
9. Health checks
10. Anti-patterns

## 1. Routes

- **A server is a function:** `Request => F[Option[Response]]` (http4s
  `HttpRoutes`, which compose with `<+>`); `HttpApp` always answers
  (`orNotFound`) (PFP p. 106).
- **One routes class per resource group**, taking algebras and programs
  (PFP p. 108-109):

  ```scala
  final class BrandRoutes[F[_]: Monad](brands: Brands[F]) extends Http4sDsl[F]:
    private val prefix = "/brands"
    private val httpRoutes = HttpRoutes.of[F] { case GET -> Root => Ok(brands.findAll) }
    val routes: HttpRoutes[F] = Router(prefix -> httpRoutes)
  ```

- Secured routes receive the auth middleware as a parameter instead of
  creating it (p. 116-117).
- **Middlewares are functions** over routes or apps. Compose route-level
  middlewares (auto-slash, CORS, timeout) in one function and app-level ones
  (request and response loggers) in another, with `andThen` (p. 128-129).
- **Version the API in the URL** from the first day (`/v1/...`) (PFP p. 252;
  FEDA p. 67).
- Response style: `Ok(fa)` for one simple outcome;
  `fa.flatMap(Ok(_)).recoverWith { ... }` when several outcomes exist, so
  happy and unhappy paths are both visible (PFP p. 109).
- **Tapir** describes endpoints once and interprets them for http4s, ZIO
  HTTP, Netty or Pekko HTTP, with OpenAPI documentation. It is a good choice
  when the project needs generated docs or several runtimes.

## 2. Request validation and error mapping

- Decode request bodies into **parameter types** (refined or validated
  fields) with a `toDomain` conversion; invalid input gives 400 (PFP p. 110,
  117-125).
- Map business errors to statuses at the HTTP layer only (p. 118-122):

  | Error | Status |
  |---|---|
  | `CartNotFound` | 404 |
  | `EmptyCartError`, `OrderOrPaymentError` | 400 |
  | `UserNameInUse` | 409 |
  | `UserNotFound` or `InvalidPassword` on login | 403 for both |
  | anything else | 500, from the framework |

- **Do not leak information on open auth endpoints:** the same 403 for an
  unknown user and a wrong password, so attackers cannot learn which users
  exist (p. 121).
- JSON codecs on domain types are a good trade-off for simple services (Volpe,
  p. 142); use separate wire types when the wire format must evolve on its
  own, differs between HTTP and storage, or crosses team boundaries (p. 110,
  120, 159).

## 3. Authentication

- An auth middleware turns authenticated routes into plain routes:
  `AuthedRoutes[User, F] => HttpRoutes[F]`. Use one middleware per role
  (common user, admin) and compose role routes with `<+>`; keep admin routes
  in their own package (PFP p. 113-123).
- Store tokens with an expiry (Redis `setEx`); logout deletes the token
  (p. 121-122, 179-180).
- **Hash passwords one way** (bcrypt, scrypt, argon2). The book's reversible
  `Crypto` is not current practice; do not copy it.

## 4. HTTP clients for remote APIs

- **Every remote API is an algebra** (`PaymentClient[F]`). Its interpreter
  takes the abstract `Client[F]` and a configuration (PFP p. 132-134, 215,
  247).
- **Branch on status codes that carry meaning.** For an idempotent payment
  API, 200 and 409 (already processed) both return the payment ID; network
  failures propagate so the caller can retry (p. 133). The book raises one
  `PaymentError` for every other code. Split that catch-all so the retry
  policy can classify: 408, 425, 429 (respect `Retry-After`) and 5xx are
  retryable; other 4xx are permanent (`algebras-and-programs.md`, "Retry only
  transient errors"). A response that you cannot classify, or a status of
  `processing`, is an unknown outcome: keep the order pending and reconcile:

  ```scala
  def process(p: Payment): F[PaymentId] =
    client.run(POST(p, cfg.uri / "payments")).use { resp =>
      resp.status match
        case Status.Ok | Status.Conflict => resp.asJsonDecode[PaymentId]
        case st if Transient(st) => PaymentUnavailable(st.reason).raiseError[F, PaymentId]   // retryable
        case st if st.responseClass == Status.ClientError => PaymentRejected(st.reason).raiseError[F, PaymentId]
        case st => PaymentUnavailable(st.reason).raiseError[F, PaymentId]   // 5xx: retryable
    }

  // 408 Request Timeout, 425 Too Early, 429 Too Many Requests
  object Transient:
    def apply(st: Status): Boolean = Set(408, 425, 429).contains(st.code)
  ```

- If only one algebra uses an HTTP client, you can hide the client inside that
  algebra's `Resource` (p. 134). The book's final application instead shares
  one client through `AppResources` (p. 247, 254); both are fine. A client is
  a resource because it owns a connection pool.
- Give every remote boundary a timeout and a size from typed configuration:
  route timeout, client timeout and idle time, pool maximum, health-probe
  timeout (p. 128, 156, 181, 242, 255).
- Test the real interpreter without a network: `Client.fromHttpApp` over fake
  routes that answer 200, 409 or 500 (p. 214-216), plus a 429 for the
  throttled case and a 4xx for the permanent case.

## 5. SQL: library choice and layout

| Library | Model | Choose when |
|---|---|---|
| Skunk | non-blocking Postgres protocol; explicit codec values; transactions are `Resource`s | Postgres only (Volpe's pick, 2021) |
| Doobie | JDBC wrapper; blocking pool hidden; `ConnectionIO` | any JDBC database; long production record |
| ZIO Quill, Magnum, Slick | other ecosystems and styles | see `effect-systems.md` |

(PFP p. 155, 183; FEDA p. 195-196.)

Layout (PFP p. 155-161):

- Create the **session pool once** in `AppResources`, with a connection check
  at acquisition (`evalTap` that logs the server version). Interpreters take
  the pool and `use` one session per operation.
- Keep **codecs per domain field** in one shared file
  (`val brandId: Codec[BrandId] = uuid.imap(BrandId(_))(_.value)`), and SQL
  statements in a private object next to the interpreter:

  ```scala
  private object BrandSQL:
    val codec: Codec[Brand] = (brandId *: brandName).to[Brand]
    val selectAll: Query[Void, Brand] = sql"SELECT * FROM brands".query(codec)
    val insert: Command[Brand] = sql"INSERT INTO brands VALUES ($codec)".command
  ```

- Use a decoder (not a codec) when the read and write shapes differ (joins,
  extra columns), and `contramap` an encoder for command input (p. 163-169).
- **Translate database errors with domain meaning** inside the interpreter:
  unique violation becomes `UserNameInUse` or `DuplicateEventId`. SQLState
  codes are database-specific (Postgres 23505 unique, 23503 foreign key);
  verify them for the database (PFP p. 171-172; FEDA p. 197-198).
- JDBC is blocking. Doobie hides it: on CE3 it runs JDBC calls on the blocking
  pool (`Sync[F].blocking`). The fixed pool given to the transactor (32
  threads in FEDA p. 196) waits for connections, so size it like the
  connection pool. Never call JDBC on the compute pool (FEDA p. 196; PFP
  p. 183).
- Run schema migrations (Flyway) at start-up and in integration tests, so
  the schema is tested too (FEDA p. 196-197, 214).

## 6. Large results

| Option | Signature | Cost |
|---|---|---|
| compile to a list | `F[List[A]]` | memory risk; small tables only |
| `LIMIT n` | `F[List[A]]` with a limit parameter | callers page by hand |
| stream | `Stream[F, A]` | streaming leaks into callers; http4s can stream the response |
| cursor pagination | `F[Paginated[A]]` | more code; bounded results |

(PFP p. 166-167.) Never `compile.toList` an unbounded query.

## 7. Transactions

- **Atomicity cannot be added afterwards** when each algebra method takes its
  own session. For a multi-entity write, create a dedicated transactional
  algebra whose interpreter prepares the reused statements on one session and
  runs them in one transaction (PFP p. 184-185):

  ```scala
  trait TxItems[F[_]]:
    def create(item: ItemCreation): F[ItemId]
  // interpreter: prepare the commands on one session, then execute them in the transaction
  // pool.use(s => (s.prepareR(insertBrand), s.prepareR(insertCategory), s.prepareR(insertItem)).tupled
  //   .use((ib, ic, ii) => s.transaction.surround(ib.execute(brand) *> ic.execute(category) *> ii.execute(item).as(itemId))))
  // (prepareR in Skunk 0.6+; prepare before)
  ```

- **Doobie transactions as a `Resource` of a natural transformation**
  (FEDA p. 198-202): `transaction(xa): Resource[F, ConnectionIO ~> F]`. A
  transactional store applies the transformation to each `ConnectionIO`, so
  callers can compose `save *> outbox` inside one `tx.use` without leaking
  `ConnectionIO` into the algebra.
- **Do not run unrelated I/O inside a database transaction**; you become
  responsible for it when the transaction fails (FEDA p. 199).
- **Never pair a database transaction with a broker transaction** as if they
  were one; use the transactional outbox (`event-driven.md`).

## 8. Redis and expiring data

- Put data that must not outlive a session (carts, tokens) in a store with
  native expiry, and refresh the expiry on every write (PFP p. 93, 173-180).
- Give every cache key an expiration; storing cache data forever is bad
  practice (FEDA p. 180). For keys that a restart depends on (snapshots read
  at start-up, p. 177-180), set the TTL longer than the longest outage you
  plan for and align it with topic retention (`event-driven.md` § 7). An
  expired snapshot forces a rewind to the earliest retained message.
- A Redis connection is a `Resource` with a connection check (`info` returns
  the server version) (PFP p. 173-174).
- An interpreter may depend on another algebra (the cart looks items up
  through `Items[F]`); in integration tests, replace that dependency with an
  in-memory interpreter (p. 174-176, 228-233).
- **Distributed lock** on Redis (FEDA p. 47-48): `SET key me NX PX ttl`,
  retry after a short sleep, release only if the value is still yours, as
  `Resource.make(acquire)(_ => release)`. Make the release an atomic
  compare-and-delete (a Lua script); get-then-delete is not atomic. Finish the
  work before the TTL, or use fencing tokens when two holders for a moment
  would corrupt data.

## 9. Health checks

Ping each dependency with a timeout, map the result to `Okay` or
`Unreachable`, and combine the checks in parallel (PFP p. 181-182):

```scala
val redisStatus = redis.ping.as(Status.Okay).timeout(1.second).orElse(Status.Unreachable.pure[F])
val pgStatus = pool.use(_.execute(sql"SELECT pid FROM pg_stat_activity".query(int4)))
  .as(Status.Okay).timeout(1.second).orElse(Status.Unreachable.pure[F])
(redisStatus, pgStatus).parMapN(AppStatus.apply)
```

## 10. Anti-patterns

- Business logic inside route handlers.
- Returning different statuses for "unknown user" and "wrong password".
- `fetchAs` against an API whose non-200 codes carry data.
- An HTTP error mapping that drifts from the errors really raised (the book
  maps `CartNotFound` but never raises it); test each mapped error (p. 46, 95,
  118, 175).
- `compile.toList` on unbounded queries.
- Expecting atomicity from several algebra calls that each open a session.
- Blocking JDBC calls on the compute pool.
- Cache keys without expiry; a snapshot TTL shorter than an outage;
  non-atomic lock release.
- One catch-all client error that makes the caller retry permanent failures,
  or a split that treats 429 as permanent.
- Reversible password encryption.
