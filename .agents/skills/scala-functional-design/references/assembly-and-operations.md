# Assembly and operations

How to wire a Scala service together and run it: configuration, resources,
modules, the entry point, logging, tracing, metrics, packaging and CI.
Sources: Volpe PFP ch. 9-10, FEDA ch. 5, 6 and 8. Cats Effect names; ZIO wires
the same parts as layers in one `provide` (see `effect-systems.md`).

## Contents

1. Configuration
2. Resources
3. Modules and the entry point
4. Logging
5. Tracing
6. Metrics and health
7. Packaging, local stack and CI
8. Anti-patterns

## 1. Configuration

Source: PFP ch. 9, unless noted.

- **Load and validate everything at start-up, and fail early.**
- **Configuration as typed code** (Ciris): values known at build time live in
  source and are type-checked; secrets come only from environment variables
  or system properties, wrapped in `Secret` so logs hide them. Combine values
  with `parMapN` so all errors are reported:

  ```scala
  def load[F[_]: Async]: F[Settings] =
    env("LIB_STAGE").as[Stage].default(Stage.Local).flatMap(forStage[F]).load[F]

  private def forStage[F[_]](stage: Stage): ConfigValue[F, Settings] =
    val hosts = stage match
      case Stage.Local => Hosts(db = "localhost", redis = "redis://localhost", kafka = "localhost:9092")
      case Stage.Live  => Hosts(db = "db.internal", redis = "redis://cache.internal", kafka = "kafka.internal:9092")
    (env("LIB_DB_PASSWORD").secret, env("LIB_SMTP_TOKEN").as[SmtpToken].secret)
      .parMapN((pass, smtp) => Settings(hosts, Db(pass, poolMax = 10), smtp, port = port"8080", remindEvery = 1.day))
  ```

- Configuration types are domain types too: newtypes with validation
  (`CheckoutConfig(retriesLimit: PosInt, retriesBackoff: FiniteDuration)`).
- Trade-off: configuration files can change without a rebuild; Volpe argues
  that changes go through version control and CI anyway. Follow the
  project's operations model.
- Keep configuration-library instances out of a domain module that Scala.js
  also compiles (FEDA ch. 4).
- ZIO: `ZIO.config` with a `ConfigProvider`, or zio-config with derivation.

## 2. Resources

Source: PFP ch. 9.

- **Create every shared resource once, in one place** (`AppResources`): HTTP
  client, database pool, Redis connection, broker client. Compose them as one
  `Resource`, acquired in parallel and released safely:

  ```scala
  final case class Infra[F[_]](
      db: Resource[F, Session[F]],                     // the session pool
      cache: RedisCommands[F, String, String],
      events: KafkaProducer[F, String, Array[Byte]]
  )

  object Infra:
    def make[F[_]: Async: Logger: Network: Console](s: Settings): Resource[F, Infra[F]] =
      (
        sessionPool[F](s.hosts.db, s.db).evalTap(logDbVersion[F]),
        Redis[F].utf8(s.hosts.redis).evalTap(logRedisVersion[F]),
        KafkaProducer.resource(ProducerSettings[F, String, Array[Byte]].withBootstrapServers(s.hosts.kafka))
      ).parMapN(Infra.apply)
  ```

- Check each connection at acquisition (`evalTap` that logs the server
  version).
- Prefer capabilities (`MkHttpClient`, `MkRedis`, `MkHttpServer`) to `Async`
  where you can; `Async` is acceptable for resource creation.
- Build the HTTP server last, because it needs the finished `HttpApp`.

## 3. Modules and the entry point

Source: PFP ch. 9, unless noted.

- Modules (`Services`, `HttpClients`, `Programs`, `Security`, `HttpApi`) are
  sealed abstract classes with private constructors and a `make` that does the
  wiring. See `algebras-and-programs.md`.
- `HttpApi` composes route groups with `<+>`, prefixes them with the API
  version, and applies middlewares and loggers.
- The entry point is one `Resource` from configuration to the running
  server; release runs in reverse order:

  ```scala
  object Server extends IOApp.Simple:
    given Logger[IO] = Slf4jLogger.getLogger[IO]

    def run: IO[Unit] =
      val service = for
        settings <- Resource.eval(Settings.load[IO])
        tasks    <- Supervisor[IO]                         // backs the Background capability
        infra    <- Infra.make[IO](settings)
        app      <- Resource.eval(Assembly.httpApp[IO](settings, infra, tasks))
        _        <- EmberServerBuilder.default[IO].withPort(settings.port).withHttpApp(app).build
      yield ()
      service.useForever

  // Assembly.httpApp puts `given Supervisor[F]` in scope, builds the security
  // module (an effect), then the clients, services, programs and routes.
  ```

- Event-driven services follow the same shape: one `Resource`
  for-comprehension acquires configuration, the broker client (with
  transactions if handlers use them), topics, producers, consumers, stores
  and the server; `run` starts the server and the consumers with `parJoin` or
  `concurrently` (FEDA ch. 6).

## 4. Logging

- **Log what matters for operations** (retries, failures, state changes);
  every extra line adds noise and storage cost (PFP ch. 9).
- Logging is a capability (`Logger[F]`), so tests can use a no-op or
  recording logger.
- In event-driven services, give every producer and consumer the same
  **structured JSON log** (flow in or out, topic, payload), through a shared
  constructor (FEDA ch. 5).

## 5. Tracing

Sources: PFP ch. 9; FEDA ch. 8, unless noted.

- Adopt distributed tracing when the system is distributed and full
  visibility is critical; it costs boilerplate.
- Library: otel4s (Typelevel OpenTelemetry, stable since 1.0) is the current
  direction; Natchez in the books is older. ZIO: ZIO Telemetry.
- Choose the tracing depth on purpose:

  | Situation | Approach | Gain | Cost |
  |---|---|---|---|
  | a service with few dependencies | trace only the HTTP edge | almost free | no inner spans |
  | a small app | run everything in a tracing effect (`Kleisli[IO, Span[IO], *]`) | inner spans | every layer carries the span type |
  | a few critical components | build in `F`, run them in a tracing `G` | inner spans where needed | the split of effect types spreads through layers |
  | message-driven services | send the trace kernel in message properties | traces across services | tracing code in each service |
  | one message in, one message out; services must stay tracing-free | a central service that correlates messages by correlation ID | no tracing code in the services | complex; hard when one input gives many outputs |

- Put the trace kernel in **message properties**, not in the payload.
- Fiber-local tracing context (the book's `Trace.ioTrace` keeps the span in
  an `IOLocal`) ties the code to concrete `IO` and has the scoping limits of
  MTL `Local` and `Kleisli.local`: a dependency built once with it keeps the
  span it was built with, not the span of the current request. Map it to
  otel4s, which carries context through `cats.mtl.Local[F, Context]` (backed
  by `IOLocal` for `IO`), or to ZIO `FiberRef`, before you add `Kleisli`
  layers (verify the current APIs).
- Every message carries a correlation ID copied from its cause; use it for
  correlation and log searches (FEDA ch. 1, 6).

## 6. Metrics and health

- Every service exposes a health route and Prometheus metrics (`GET /metrics`,
  for example through an http4s metrics middleware); Prometheus scrapes,
  Grafana shows JVM and application dashboards; on-call alerts are built from
  these metrics (FEDA ch. 6, 8).
- Health checks ping each dependency with a timeout (see
  `http-and-persistence.md`). A health endpoint may answer non-2xx when
  unhealthy; decide with the operators (PFP ch. 5).

## 7. Packaging, local stack and CI

Sources: PFP ch. 10; FEDA ch. 8.

- One deployable module per service, packaged as a container image
  (sbt-native-packager) on a slim JRE base image, not a JDK; add `curl` if
  container health checks need it.
- Declare external dependencies (Postgres with an init script, Redis, the
  broker) in docker-compose with health checks, and use the same file locally
  and in CI.
- CI runs unit and integration tests against those containers; on the main
  branch it publishes images, then runs smoke tests against them.
- A Nix shell or flake makes the development and CI toolchains identical
  (optional).
- GraalVM native images are smaller but bring many trade-offs.
- Dated: sbt's `IntegrationTest` configuration (`it:test`) is deprecated in
  recent sbt; use a separate test project. Check plugin syntax for the
  project's sbt version.

## 8. Anti-patterns

Sources: PFP ch. 9; FEDA ch. 8.

- Secrets in source code; configuration logged without `Secret` wrappers.
- Creating a resource (pool, client) more than once, or inside an interpreter
  that others also need.
- Logging everything.
- Tracing context threaded through the whole application only for tracing,
  or the trace kernel inside the payload.
- `unsafeRun*` anywhere except the entry point and tests.
