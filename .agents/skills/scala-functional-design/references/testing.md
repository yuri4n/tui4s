# Testing functional Scala

How to test algebras, programs, routes, clients, FSMs and interpreters.
Sources: Volpe PFP ch. 8, FEDA ch. 6-8, EOP ch. 5 and § 9.6. The general
strategy (which test for which risk, testable architecture) is in the
testing guidance of the `functional-design` skill.

## Contents

1. Framework choice
2. Generators
3. Programs with test interpreters
4. Capabilities in tests
5. Expectations and equality
6. Law tests
7. Routes and clients
8. FSMs and handlers
9. Integration tests
10. Time, randomness and timeouts
11. Smoke tests and CI

## 1. Framework choice

Source: PFP ch. 8.

- Volpe's advice is to pick **one** effect-aware framework, although he notes
  that the Weaver maintainers recommend using it next to MUnit:
  - Cats Effect: Weaver (effects first, parallel suites, shared resources,
    `forall` for effectful properties, expectations as values) or MUnit with
    munit-cats-effect (plus scalacheck-effect for effectful properties: three
    libraries, more upgrade risk).
  - ZIO: ZIO Test.
  - Kyo: kyo-test.
- Testing in `IO` is fine: `IO` is one more interpreter of the constraints,
  and a test is one more "end of the world" (Volpe's term), like `main`.

## 2. Generators

Source: PFP ch. 8.

- Write one `Gen` per domain type, in one generators file; compose them with
  for-comprehensions and tuples.
- **Avoid `Arbitrary`** except where a library needs it (law tests): type-class
  coherence allows one instance per type, but tests need several generators
  per type (adults, minors, invalid).
- Generate valid values directly; `suchThat` filters are slow (generate a
  length, then build the string).
- Use unsafe refinement constructors only in tests, and only when there is no
  alternative.

## 3. Programs with test interpreters

Test each critical program once per failure scenario, with fake interpreters
(PFP ch. 8):

- Build a base fake whose methods are `???`, and override only what a
  scenario needs.
- Derive the scenarios from the program's steps: for each external call,
  success, a transient failure every time, a transient failure and then
  recovery, and a permanent failure where the API has one; then each
  business-rule error. For a checkout this gives: happy path; empty cart;
  payment client always failing (expect the maximum number of retries, and
  that the order stays pending, not cancelled, when the failures were
  transient); a definite decline (expect the hold released); payment client
  recovering on the second attempt; marking the order paid always failing
  after the charge (expect the order to stay pending for the reconciler, and
  assert the value the caller receives: `Pending` or a typed failure, see
  `algebras-and-programs.md`; the book's code, which creates the order only
  after the charge, reschedules it in memory and returns `OrderError` although
  the card was charged); cart deletion failing (expect success anyway).
- Run each scenario as a property over generated inputs (user, cart,
  payment ID, card).
- **Assert the business outcome with a fake that models it.** For "a lost
  response does not charge twice", the fake provider deduplicates by
  idempotency key and records charges in a `Ref`; assert one charge, not
  only the keys that were sent (practice).
- **Tie each in-memory interpreter to the real one with a contract suite.**
  The program tests trust the fake store, so it must keep the behaviors that
  they depend on: a duplicate key is rejected (or returns the stored row), a
  missing row is not found, a claimed outbox row is not claimed twice. Write
  the store's tests once as a function of a `Resource` (or a layer) that
  builds the store, and run them against the in-memory interpreter in every
  build and against Postgres in the integration suite (section 9). An
  integration test of the real store alone does not check the fake
  (practice).

  ```scala
  abstract class OrdersContract(name: String, orders: Resource[IO, Orders[IO]]) extends IOSuite:
    type Res = Orders[IO]
    def sharedResource = orders

    test(s"$name: a repeated request key returns the stored order") { o =>
      for
        first  <- o.createPending(key1, buyer, hold)
        second <- o.createPending(key1, buyer, hold)
      yield expect.same(first, second)
    }

  object InMemoryOrdersSuite extends OrdersContract("in-memory", Resource.eval(Orders.inMemory[IO]))
  object PostgresOrdersSuite extends OrdersContract("postgres", TestDb.session.map(Orders.postgres[IO]))
  ```

  In ZIO Test, write `def contract(name: String) = suite(name)(...)` with
  `Orders` in the environment, and provide it once with the in-memory layer
  and once with the Postgres layer (a Testcontainers layer, shared with
  `provideShared`).
- **Record typed values, not log strings.** A test `Retry` stores the
  `RetryDetails` in a `Ref`; a test `Background` counts scheduled actions and
  delays. Asserting on log strings is fragile.
- **Count calls at the fake of the external system.** The recording `Retry`
  shows what the policy decided; only the fake shows how many calls reached
  the provider. Assert the bound (or "exactly one charge") there, and run the
  production policy once under `TestControl` (practice).

```scala
def mailerDown(calls: Ref[IO, Int]): Mailer[IO] = new Mailer[IO]:
  def send(m: Mail) = calls.update(_ + 1) *> IO.raiseError(MailerUnavailable("smtp timeout"))   // retryable

// TestRetry.recording appends each RetryDetails to the Ref (cats-retry 4;
// the book's 3.x API has a GivingUp case with totalRetries instead)
test("mailer down: retry up to the limit, then give up") {
  forall(mailGen) { mail =>
    for
      calls  <- Ref.of[IO, Int](0)
      seen   <- Ref.of[IO, Vector[RetryDetails]](Vector.empty)
      result <- { given Retry[IO] = TestRetry.recording(seen); Receipts[IO](mailerDown(calls), policy).send(mail).attempt }
      sent   <- calls.get
      steps  <- seen.get
    yield (result, steps.lastOption) match
      case (Left(MailerUnavailable(_)), Some(RetryDetails(n, _, NextStep.GiveUp))) =>
        expect.same(MaxRetries, n) && expect.same(MaxRetries + 1, sent)   // counted at the fake: first call + retries
      case other => failure(s"expected a give-up after $MaxRetries retries, got $other")
  }
}
```

## 4. Capabilities in tests

Capability traits pay off here: put test instances (no-op logger, counting
background, recording retry, fixed clock, fixed UUIDs) in local scope; they
win over the defaults (PFP ch. 8). This is why programs should ask for
capabilities, not `Sync` or `Temporal`.

## 5. Expectations and equality

- Return expectations as values and combine them (Weaver's `Expectations`
  form a monoid); do not assert with side effects in the middle of an
  effectful test (PFP ch. 8).
- Compare with `Eq` (`expect.same`, `===`), not universal `==`.
- In ZIO Test, only the last assertion of a test counts; combine assertions
  with `&&` (EOP § 5.1).

## 6. Law tests

- Every hand-written type-class instance gets a Discipline law check
  (`checkAll("Monoid[Money]", MonoidTests[Money].monoid)`); here `Arbitrary`
  is required (PFP ch. 8).
- Check isomorphisms and other optics with Monocle's law tests (PFP ch. 8).
- Add round-trip (golden) tests for JSON protocols shared with clients
  (FEDA ch. 6).

## 7. Routes and clients

Source: PFP ch. 8.

- **Routes are functions:** build the routes class with a "by-pass"
  interpreter (Volpe's term: it returns the data it was built with), run
  `routes.run(request).value`, and check status and body.
- Secured routes: pass a fake `AuthMiddleware` that always returns a test
  user.
- **Clients:** test the real interpreter against `Client.fromHttpApp` with fake
  routes, one per meaningful status (200, 409, 500 in the book; add a 429
  and a permanent 4xx when the client splits them).

## 8. FSMs and handlers

- **Pure FSMs:** feed a scripted sequence of inputs and compare each new state
  and each output (applied to fixed IDs and timestamps) with the expected
  values (FEDA ch. 6).
- **Handlers with failure injection:** generate inputs; use fakes that record
  writes in a `Ref` or always fail; assert what was written and what was
  acknowledged. For example: a failing writer means no ack (FEDA ch. 7).
- **Stateless handlers:** in-memory stores built on `Ref` capture outbox
  events (FEDA ch. 7).
- **WebSocket handlers:** drive `receive` with a scripted list of frames, gate
  the close on a `Deferred`, collect `send` output in a `Ref`, and assert
  without depending on the order of racing messages (FEDA ch. 6).

## 9. Integration tests

Sources: PFP ch. 8; FEDA ch. 7.

- Test **each external interpreter in isolation** against the real service
  (one Postgres suite, one Redis suite), not the whole application in a
  staging environment. PFP finds this enough for a small service; in bigger
  applications it calls tests of the interaction between components
  necessary, with the scope decided case by case. Add them for critical
  flows where several services or stores must agree.
- Share one resource per suite (Weaver `IOSuite` with `sharedResource`;
  ZIO `provideShared`). Clean the data before the tests; respect foreign keys
  when you insert fixtures.
- Replace dependencies on other stores with in-memory interpreters, so each
  suite tests one system.
- Run migrations in the SQL suite so the schema is tested too; use the
  production database engine.
- Stateful stores: use deterministic round trips, not properties with many
  runs (or reset the store per run).
- Integration tests find real codec bugs that unit tests miss.
- **Test each atomic write once against the real database.** Run the
  entity insert and a failing outbox insert (a duplicate event ID, or a
  step that raises) in one transaction, then check that no entity row is
  there. An in-memory fake has no transactions, so it cannot show this
  (practice).
- Start the services with docker-compose (or Testcontainers) both locally and
  in CI (also PFP ch. 10).

## 10. Time, randomness and timeouts

- Cats Effect: `TestControl` runs a program on virtual time (`executeEmbed`,
  or `execute` with `tick` and `advance`).
- ZIO: `TestClock` does not advance by itself; adjust it **concurrently** with
  the effect (`zipPar` or `fork`), or the test hangs. Feed `TestRandom` for
  randomness (EOP § 5.3).
- Give suites a timeout (`@@ TestAspect.timeout`; `munitIOTimeout` in
  munit-cats-effect, `munitTimeout` for plain MUnit) so a hung test fails
  with a name instead of a killed CI job (EOP § 9.6).
- Avoid automatic retries of flaky tests; remove the cause (shared live
  services, thread-unsafe code, CI resource limits).

## 11. Smoke tests and CI

- Run unit and integration tests in CI with the same container definitions as
  locally (PFP ch. 10).
- For event-driven systems, add smoke tests: start a few services against the
  real broker, publish a fixed set of commands, and assert what clients
  receive. Do not build a full-system simulation (FEDA ch. 7-8).
