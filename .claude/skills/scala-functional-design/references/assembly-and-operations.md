# Assembly and operations

How to wire a Scala service together and run it: configuration, resources,
modules, the entry point, logging, tracing, metrics, packaging and CI.
Sources: Volpe PFP ch. 9-10, FEDA ch. 5 and 8. Cats Effect names; ZIO wires the
same parts as layers in one `provide` (see `effect-systems.md`).

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

- **Load and validate everything at start-up, and fail early** (PFP
  p. 241-245).
- **Configuration as typed code** (Ciris): values known at build time live in
  source and are type-checked; secrets come only from environment variables
  or system properties, wrapped in `Secret` so logs hide them. Combine values
  with `parMapN` so all errors are reported:

  ```scala
  def load[F[_]: Async]: F[AppConfig] =
    env("SC_APP_ENV").as[AppEnvironment].flatMap {
      case AppEnvironment.Test => default[F](redisUri = RedisURI("redis://localhost"))
      case AppEnvironment.Prod => default[F](redisUri = RedisURI("redis://prod-redis"))
    }.load[F]

  private def default[F[_]](redisUri: RedisURI): ConfigValue[F, AppConfig] =
    (env("SC_JWT_SECRET_KEY").as[JwtSecretKey].secret, env("SC_PASSWORD_SALT").as[PasswordSalt].secret)
      .parMapN((jwt, salt) => AppConfig(jwt, salt, CheckoutConfig(retriesLimit = 3, retriesBackoff = 10.millis), redisUri))
  ```

- Configuration types are domain types too: newtypes with validation
  (`CheckoutConfig(retriesLimit: PosInt, retriesBackoff: FiniteDuration)`).
- Trade-off: configuration files can change without a rebuild; Volpe argues
  that changes go through version control and CI anyway (p. 241). Follow the
  project's operations model.
- Keep configuration-library instances out of a domain module that Scala.js
  also compiles (FEDA p. 83).
- ZIO: `ZIO.config` with a `ConfigProvider`, or zio-config with derivation.

## 2. Resources

- **Create every shared resource once, in one place** (`AppResources`): HTTP
  client, database pool, Redis connection, broker client. Compose them as one
  `Resource`, acquired in parallel and released safely (PFP p. 254-256):

  ```scala
  final case class AppResources[F[_]](
      http: Client[F],
      postgres: Resource[F, Session[F]],   // the session pool
      redis: RedisCommands[F, String, String]
  )

  object AppResources:
    def make[F[_]: Concurrent: Logger: MkHttpClient: MkRedis: Network: Console](cfg: AppConfig): Resource[F, AppResources[F]] =
      val http     = MkHttpClient[F].newEmber(cfg.httpClient)
      val postgres = sessionPool(cfg).evalTap(logServerVersion)
      val redis    = redisConnection(cfg).evalTap(pingRedis)
      (http, postgres, redis).parTupled.map((h, p, r) => AppResources(h, p, r))
  ```

- Check each connection at acquisition (`evalTap` that logs the server
  version).
- Prefer capabilities (`MkHttpClient`, `MkRedis`, `MkHttpServer`) to `Async`
  where you can; `Async` is acceptable for resource creation (p. 254-257).
- Build the HTTP server last, because it needs the finished `HttpApp`
  (p. 256-257).

## 3. Modules and the entry point

- Modules (`Services`, `HttpClients`, `Programs`, `Security`, `HttpApi`) are
  sealed abstract classes with private constructors and a `make` that does the
  wiring (PFP p. 246-253). See `algebras-and-programs.md`.
- `HttpApi` composes route groups with `<+>`, prefixes them with the API
  version, and applies middlewares and loggers (p. 251-253).
- The entry point is one `Resource` from configuration to the running
  server; release runs in reverse order (p. 258-259):

  ```scala
  object Main extends IOApp.Simple:
    given Logger[IO] = Slf4jLogger.getLogger[IO]

    private val app: Resource[IO, Unit] =
      for
        cfg <- Resource.eval(Config.load[IO])
        sup <- Supervisor[IO]                               // backs the Background capability
        res <- AppResources.make[IO](cfg)
        api <- Resource.eval(Wiring.make[IO](cfg, res, sup))
        _   <- MkHttpServer[IO].newEmber(cfg.httpServer, api.httpApp)
      yield ()

    def run: IO[Unit] = app.useForever

  // Wiring.make puts `given Supervisor[F]` in scope, builds Security (an effect),
  // then HttpClients, Services, Programs and HttpApi, and returns the HttpApi.
  ```

- Event-driven services follow the same shape: one `Resource`
  for-comprehension acquires configuration, the broker client (with
  transactions if handlers use them), topics, producers, consumers, stores
  and the server; `run` starts the server and the consumers with `parJoin` or
  `concurrently` (FEDA p. 134-135, 145).

## 4. Logging

- **Log only critical information** (retries, failures, state changes);
  extra logs add noise and storage cost (PFP p. 237).
- Logging is a capability (`Logger[F]`), so tests can use a no-op or
  recording logger.
- In event-driven services, give every producer and consumer the same
  **structured JSON log** (flow in or out, topic, payload), through a shared
  constructor (FEDA p. 112-114).

## 5. Tracing

- Adopt distributed tracing when the system is distributed and full
  visibility is critical; it costs boilerplate (PFP p. 239-240).
- Library: otel4s (Typelevel OpenTelemetry, stable since 1.0) is the current
  direction; Natchez in the books is older. ZIO: ZIO Telemetry.
- Choose the tracing depth on purpose (FEDA p. 218-238):

  | Approach | Pros | Cons | Use when |
  |---|---|---|---|
  | trace only the HTTP edge | trivial | no inner spans | few dependencies |
  | run everything in a tracing effect (`Kleisli[IO, Span[IO], *]`) | inner spans | invasive | small apps |
  | build in `F`, run in a tracing `G` for selected components | inner spans where needed | the split spreads through layers | a few critical components |
  | trace kernel in message properties | traces across services | tracing code in each service | message-driven services |
  | a central service that correlates messages by correlation ID | other services stay tracing-free | complex; hard when one input gives many outputs | one message in, one message out |

- Put the trace kernel in **message properties**, not in the payload
  (FEDA p. 227).
- Fiber-local tracing context (`Trace.ioTrace` on `IOLocal` in the book) works
  only for concrete `IO` and has the limits of MTL `Local` and
  `Kleisli.local`; a dependency built once with it stays on the span it was
  created with, not the current request's (FEDA p. 218, 222). Map it to
  otel4s, which carries context through `cats.mtl.Local[F, Context]` (backed
  by `IOLocal` for `IO`), or to ZIO `FiberRef`, before you add `Kleisli`
  layers (FEDA p. 218-225; verify the current APIs).
- Every message carries a correlation ID copied from its cause; use it for
  correlation and log searches (FEDA p. 22, 131).

## 6. Metrics and health

- Every service exposes a health route and Prometheus metrics (`GET /metrics`,
  for example through an http4s metrics middleware); Prometheus scrapes,
  Grafana shows JVM and application dashboards; on-call alerts are built from
  these metrics (FEDA p. 135, 244-245).
- Health checks ping each dependency with a timeout (see
  `http-and-persistence.md`). A health endpoint may answer non-2xx when
  unhealthy; decide with the operators (PFP p. 111-112).

## 7. Packaging, local stack and CI

- One deployable module per service, packaged as a container image
  (sbt-native-packager) on a slim JRE base image, not a JDK; add `curl` if
  container health checks need it (PFP p. 263-265; FEDA p. 240).
- Declare external dependencies (Postgres with an init script, Redis, the
  broker) in docker-compose with health checks, and use the same file locally
  and in CI (PFP p. 266-267; FEDA p. 241).
- CI runs unit and integration tests against those containers; on the main
  branch it publishes images, then runs smoke tests against them (FEDA
  p. 241-242).
- A Nix shell or flake makes the development and CI toolchains identical
  (optional) (PFP p. 268-269).
- GraalVM native images are smaller but bring many trade-offs (PFP p. 268).
- Dated: sbt's `IntegrationTest` configuration (`it:test`) is deprecated in
  recent sbt; use a separate test project. Check plugin syntax for the
  project's sbt version.

## 8. Anti-patterns

- Secrets in source code; configuration logged without `Secret` wrappers
  (PFP p. 241, 245).
- Creating a resource (pool, client) more than once, or inside an interpreter
  that others also need (p. 254).
- Logging everything (p. 237).
- Tracing context threaded through the whole application only for tracing,
  or the trace kernel inside the payload (FEDA p. 218, 227).
- `unsafeRun*` anywhere except the entry point and tests.
