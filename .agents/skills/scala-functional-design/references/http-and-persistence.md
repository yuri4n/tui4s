# HTTP and persistence

The edges of a Scala service: HTTP routes, authentication, HTTP clients,
SQL, transactions and Redis. Sources: Volpe PFP ch. 5 and 7, FEDA ch. 2 and
7. Library names are from the Typelevel stack; map them with
`effect-systems.md` (ZIO HTTP, Tapir, ZIO Quill, zio-redis, ...). The
general rules (three data models, DTOs, trust boundaries) are in the
persistence and boundaries guidance of the `functional-design` skill.

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

Source: PFP ch. 5, unless noted.

- **Routes are partial functions:** `Request => F[Option[Response]]` (http4s
  `HttpRoutes`, which compose with `<+>`); `HttpApp` always answers
  (`orNotFound`).
- **One routes class per resource group**, taking algebras and programs:

  ```scala
  final class AuthorRoutes[F[_]: Monad](authors: Authors[F]) extends Http4sDsl[F]:
    private val endpoints = HttpRoutes.of[F] {
      case GET -> Root / UUIDVar(id) =>
        authors.find(AuthorId(id)).flatMap(_.fold(NotFound())(Ok(_)))
    }
    val routes: HttpRoutes[F] = Router("/authors" -> endpoints)
  ```

- Secured routes receive the auth middleware as a parameter instead of
  creating it.
- **Middlewares are functions** over routes or apps. Compose route-level
  middlewares (auto-slash, CORS, timeout) in one function and app-level ones
  (request and response loggers) in another, with `andThen`.
- **Version the API in the URL** from the first day (`/v1/...`) (PFP ch. 3
  and 9; FEDA ch. 3).
- Response style: `Ok(fa)` for one simple outcome;
  `fa.flatMap(Ok(_)).recoverWith { ... }` when several outcomes exist, so
  the success response and each failure response are in one place.
- **Tapir** describes endpoints once and interprets them for http4s, ZIO
  HTTP, Netty or Pekko HTTP, with OpenAPI documentation. It is a good choice
  when the project needs generated docs or several runtimes (practice).

## 2. Request validation and error mapping

Source: PFP ch. 5, unless noted.

- Decode request bodies into **parameter types** (refined or validated
  fields) with a `toDomain` conversion; invalid input gives 400.
- Map business errors to statuses at the HTTP layer only:

  | Error kind | Status | Shop example |
  |---|---|---|
  | the entity does not exist | 404 | `CartNotFound` |
  | the request breaks a business rule | 400 | `EmptyCartError`, `OrderOrPaymentError` |
  | the request conflicts with stored data | 409 | `UserNameInUse` |
  | a failed login, for any reason | 403 for all reasons | `UserNotFound`, `InvalidPassword` |
  | an unexpected error | 500, from the framework | |

- **Do not leak information on open auth endpoints:** the same 403 for an
  unknown user and a wrong password, so attackers cannot learn which users
  exist.
- JSON codecs on domain types are a good trade-off for simple services (PFP
  ch. 6); use separate wire types when the wire format must evolve on its
  own, differs between HTTP and storage, or crosses team boundaries (PFP
  ch. 5, 7).

## 3. Authentication

Source: PFP ch. 5; token storage ch. 7.

- An auth middleware turns authenticated routes into plain routes:
  `AuthedRoutes[User, F] => HttpRoutes[F]`. Use one middleware per role
  (common user, admin) and compose role routes with `<+>`; keep admin routes
  in their own package.
- Store tokens with an expiry (Redis `setEx`); logout deletes the token.
- **Hash passwords one way** (bcrypt, scrypt, argon2). The book's reversible
  `Crypto` is not current practice; do not copy it.

## 4. HTTP clients for remote APIs

Source: PFP ch. 5, unless noted.

- **Every remote API is an algebra** (`PaymentClient[F]`). Its interpreter
  takes the abstract `Client[F]` and a configuration (also PFP ch. 8-9).
- **Branch on status codes that carry meaning.** For an idempotent API, a
  success and a 409 (already processed) both return the ID; network
  failures propagate so the caller can retry. The book raises one error for
  every other code. Split that catch-all so the retry policy can classify:
  408, 425, 429 (respect `Retry-After`) and 5xx are retryable; other 4xx are
  permanent (`algebras-and-programs.md`, "Retry only transient errors").
  Among the permanent ones, 401, 403 and 404 on a fixed endpoint mean that
  our key or URL is wrong: that is a defect to alert on, not a refusal to
  show the customer. A response that you cannot classify, or a status of
  `processing`, is an unknown outcome: keep the order pending and reconcile:

  ```scala
  def book(req: LabelRequest): F[LabelId] =
    client.run(POST(req, cfg.uri / "labels").putHeaders("Idempotency-Key" -> req.key.value)).use { resp =>
      resp.status match
        case Status.Created | Status.Conflict             => resp.asJsonDecode[LabelId]   // new, or booked before
        case st if Retryable(st)                          => CarrierBusy(st.code).raiseError[F, LabelId]
        case st if Set(401, 403, 404)(st.code)            => CarrierMisconfigured(st.code).raiseError[F, LabelId] // alert
        case st if st.responseClass == Status.ClientError => LabelRefused(st.code).raiseError[F, LabelId]
        case st                                           => LabelUnknown(st.code).raiseError[F, LabelId] // reconcile
    }

  // 408 Request Timeout, 425 Too Early, 429 Too Many Requests, and every 5xx
  object Retryable:
    def apply(st: Status): Boolean =
      Set(408, 425, 429).contains(st.code) || st.responseClass == Status.ServerError
  ```

- If only one algebra uses an HTTP client, you can hide the client inside that
  algebra's `Resource`. Sharing one client through `AppResources` (PFP ch. 9)
  is also fine. Clients own a connection pool, so they are resources.
- Give every remote boundary a timeout and a size from typed configuration:
  route timeout, client timeout and idle time, pool maximum, health-probe
  timeout (PFP ch. 5, 7, 9).
- Test the real interpreter without a network: `Client.fromHttpApp` over fake
  routes that answer success, 409 or 500 (PFP ch. 8), plus a 429 for the
  throttled case and a 4xx for the permanent case.

## 5. SQL: library choice and layout

Sources for this section: PFP ch. 7; FEDA ch. 7.

| Library | Model | Choose when |
|---|---|---|
| Skunk | non-blocking Postgres protocol; explicit codec values; transactions are `Resource`s | Postgres only (Volpe's pick, 2021) |
| Doobie | JDBC wrapper; blocking pool hidden; `ConnectionIO` | any JDBC database; long production record |
| ZIO Quill, Magnum, Slick | other ecosystems and styles | see `effect-systems.md` |

Layout:

- Create the **session pool once** in `AppResources`, with a connection check
  at acquisition (`evalTap` that logs the server version). Interpreters take
  the pool and `use` one session per operation.
- Keep **codecs per domain field** in one shared file
  (`val authorId: Codec[AuthorId] = uuid.imap(AuthorId(_))(_.value)`), and
  SQL statements in a private object next to the interpreter:

  ```scala
  private object AuthorSQL:
    val author: Codec[Author] = (authorId *: penName *: country).to[Author]
    val byCountry: Query[Country, Author] =
      sql"SELECT id, pen_name, country FROM authors WHERE country = $country".query(author)
    val upsert: Command[Author] =
      sql"""INSERT INTO authors VALUES ($author)
            ON CONFLICT (id) DO UPDATE SET pen_name = EXCLUDED.pen_name""".command
  ```

- Use a decoder (not a codec) when the read and write shapes differ (joins,
  extra columns), and `contramap` an encoder for command input.
- **Translate database errors with domain meaning** inside the interpreter:
  unique violation becomes `UserNameInUse` or `DuplicateEventId`. SQLState
  codes are database-specific (Postgres 23505 unique, 23503 foreign key);
  verify them for the database.
- JDBC is blocking. Doobie hides it: on CE3 it runs JDBC calls on the blocking
  pool (`Sync[F].blocking`). The fixed pool given to the transactor (FEDA
  uses 32 threads) waits for connections, so size it like the connection
  pool. Never call JDBC on the compute pool.
- Run schema migrations (Flyway) at start-up and in integration tests, so
  the schema is tested too.

## 6. Large results

Source: PFP ch. 7.

| Option | Signature | Cost |
|---|---|---|
| compile to a list | `F[List[A]]` | memory risk; small tables only |
| `LIMIT n` | `F[List[A]]` with a limit parameter | callers page by hand |
| stream | `Stream[F, A]` | streaming leaks into callers; http4s can stream the response |
| cursor pagination | `F[Paginated[A]]` | more code; bounded results |

Never `compile.toList` an unbounded query.

## 7. Transactions

- **Atomicity cannot be added afterwards** when each algebra method takes its
  own session. For a multi-entity write, create a dedicated transactional
  algebra whose interpreter prepares the reused statements on one session and
  runs them in one transaction (PFP ch. 7):

  ```scala
  trait TxMembers[F[_]]:
    def register(m: NewMember): F[MemberId]

  // interpreter (Skunk 0.6+: prepareR; older versions: prepare)
  def register(m: NewMember): F[MemberId] =
    pool.use { s =>
      (s.prepareR(MemberSQL.insert), s.prepareR(CardSQL.insert)).tupled.use { (members, cards) =>
        s.transaction.surround {
          MemberId.random[F].flatTap(id => members.execute(Member(id, m.name)) *> cards.execute(Card(id, m.card)))
        }
      }
    }
  ```

- **Doobie transactions as a `Resource` of a natural transformation**
  (FEDA ch. 7): `transaction(xa): Resource[F, ConnectionIO ~> F]`. A
  transactional store applies the transformation to each `ConnectionIO`, so
  callers can compose `save *> outbox` inside one `tx.use` without leaking
  `ConnectionIO` into the algebra.
- **Keep other I/O out of a database transaction** (HTTP calls, broker
  sends): a rollback does not undo it, so its failures become your problem
  (FEDA ch. 7).
- **Never pair a database transaction with a broker transaction** as if they
  were one; use the transactional outbox (`event-driven.md`).

## 8. Redis and expiring data

- Put data that must not outlive a session (carts, tokens) in a store with
  native expiry, and refresh the expiry on every write (PFP ch. 4, 7).
- Set a TTL on every cache key; cached data must not live forever (FEDA
  ch. 7). For keys that a restart depends on (snapshots read at start-up),
  set the TTL longer than the longest outage you plan for and align it with
  topic retention (`event-driven.md` § 7). An expired snapshot forces a
  rewind to the earliest retained message.
- A Redis connection is a `Resource` with a connection check (`info` returns
  the server version) (PFP ch. 7).
- An interpreter may depend on another algebra (the cart looks items up
  through `Items[F]`); in integration tests, replace that dependency with an
  in-memory interpreter (PFP ch. 7-8).
- **Distributed lock** on Redis (FEDA ch. 2): `SET key me NX PX ttl`,
  retry after a short sleep, release only if the value is still yours, as
  `Resource.make(acquire)(_ => release)`. Make the release an atomic
  compare-and-delete (a Lua script); get-then-delete is not atomic. Finish the
  work before the TTL, or use fencing tokens when two holders for a moment
  would corrupt data.

## 9. Health checks

Ping each dependency with a timeout, map the result to up or down, and run
the checks in parallel (PFP ch. 7):

```scala
def probe[F[_]: Temporal](name: String, check: F[Unit]): F[(String, Health)] =
  check.as(Health.Up).timeout(1.second).handleError(_ => Health.Down).tupleLeft(name)

val report: F[List[(String, Health)]] = List(
  probe("redis", redis.ping.void),
  probe("postgres", pool.use(_.unique(sql"SELECT 1".query(int4))).void)
).parSequence
```

## 10. Anti-patterns

- Business logic inside route handlers.
- Returning different statuses for "unknown user" and "wrong password".
- `fetchAs` against an API whose non-200 codes carry data.
- An HTTP error mapping that drifts from the errors really raised (the book
  maps `CartNotFound` but never raises it); test each mapped error (PFP
  ch. 1, 4-5, 7).
- `compile.toList` on unbounded queries.
- Expecting atomicity from several algebra calls that each open a session.
- Blocking JDBC calls on the compute pool.
- Cache keys without expiry; a snapshot TTL shorter than an outage;
  non-atomic lock release.
- One catch-all client error that makes the caller retry permanent failures,
  or a split that treats 429 as permanent.
- Reversible password encryption.
