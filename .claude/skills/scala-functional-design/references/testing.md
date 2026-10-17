# Testing functional Scala

How to test algebras, programs, routes, clients, FSMs and interpreters.
Sources: Volpe PFP ch. 8, FEDA ch. 6-8, *Effect Oriented Programming* ch. 5.
General strategy (which test for which risk, testable architecture) is in
`functional-design`'s `testing.md`.

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

- Volpe's advice is to pick **one** effect-aware framework, although he notes
  that the Weaver maintainers recommend using it next to MUnit (PFP
  p. 188-189):
  - Cats Effect: Weaver (effects first, parallel suites, shared resources,
    `forall` for effectful properties, expectations as values) or MUnit with
    munit-cats-effect (plus scalacheck-effect for effectful properties: three
    libraries, more upgrade risk).
  - ZIO: ZIO Test.
  - Kyo: kyo-test.
- Testing in `IO` is fine: `IO` is one more interpreter of the constraints,
  and a test is another "end of the world" (PFP p. 189).

## 2. Generators

- Write one `Gen` per domain type, in one generators file; compose them with
  for-comprehensions and tuples (PFP p. 190-195).
- **Avoid `Arbitrary`** except where a library needs it (law tests): type-class
  coherence allows one instance per type, but tests need several generators
  per type (adults, minors, invalid) (p. 190-191).
- Generate valid values directly; `suchThat` filters are slow (generate a
  length, then build the string) (p. 192).
- Use unsafe refinement constructors only in tests, and only when there is no
  alternative (p. 194).

## 3. Programs with test interpreters

Test each critical program once per failure scenario, with fake interpreters
(PFP p. 196-209):

- Build a base fake whose methods are `???`, and override only what a
  scenario needs.
- Scenarios for a checkout: happy path; empty cart; payment client always
  failing (expect the maximum number of retries, and that the order stays
  pending, not cancelled, when the failures were transient); a definite
  decline (expect the hold released); payment
  client recovering on the second attempt; order creation always failing
  (expect one background reschedule with its delay, and assert the value the
  caller receives: `Pending` or a typed failure, see
  `algebras-and-programs.md`; the book's code returns `OrderError` although
  the card was charged, p. 208); cart deletion failing (expect success
  anyway).
- Run each scenario as a property over generated inputs (user, cart,
  payment ID, card).
- **Record typed values, not log strings.** A test `Retry` stores the
  `RetryDetails` in a `Ref`; a test `Background` counts scheduled actions and
  delays. Asserting on log strings is fragile (p. 204).

```scala
def failingPayments: PaymentClient[IO] = new PaymentClient[IO]:
  def process(p: Payment) = IO.raiseError(PaymentUnavailable("boom"))   // retryable

// test givens for Background and Logger (no-op) are in scope for the whole suite.
// cats-retry 4: TestRetry.givingUp stores the RetryDetails whose
// nextStepIfUnsuccessful is NextStep.GiveUp (the book's 3.x API has GivingUp.totalRetries)
test("unreachable payment client") {
  forall(gen) { (uid, total, card) =>
    for
      retries <- Ref.of[IO, Option[RetryDetails]](None)
      result <- {
        given Retry[IO] = TestRetry.givingUp(retries)
        Checkout[IO](failingPayments, cartWith(total), noOrders, policy).process(uid, card).attempt
      }
      details <- retries.get
    yield result match
      case Left(PaymentUnavailable(_)) =>
        details.fold(failure("expected give-up details"))(d => expect.same(d.retriesSoFar, MaxRetries))
      case other => failure(s"expected PaymentUnavailable, got $other")
  }
}
```

## 4. Capabilities in tests

Capability traits pay off here: put test instances (no-op logger, counting
background, recording retry, fixed clock, fixed UUIDs) in local scope; they
win over the defaults (PFP p. 196-208). This is why programs should ask for
capabilities, not `Sync` or `Temporal`.

## 5. Expectations and equality

- Return expectations as values and combine them (Weaver's `Expectations`
  form a monoid); do not assert with side effects in the middle of an
  effectful test (PFP p. 199-202).
- Compare with `Eq` (`expect.same`, `===`), not universal `==` (p. 200).
- In ZIO Test, only the last assertion of a test counts; combine assertions
  with `&&` (EOP § 5.1).

## 6. Law tests

- Every hand-written type-class instance gets a Discipline law check
  (`checkAll("Monoid[Money]", MonoidTests[Money].monoid)`); here `Arbitrary`
  is required (PFP p. 217-219).
- Check isomorphisms and other optics with Monocle's law tests (p. 219-220).
- Add round-trip (golden) tests for JSON protocols shared with clients
  (FEDA p. 163).

## 7. Routes and clients

- **Routes are functions:** build the routes class with a "by-pass"
  interpreter (it returns the data it was built with), run
  `routes.run(request).value`, and check status and body (PFP p. 210-212).
- Secured routes: pass a fake `AuthMiddleware` that always returns a test
  user (p. 212-213).
- **Clients:** test the real interpreter against `Client.fromHttpApp` with fake
  routes, one per meaningful status (200, 409, 500 in the book, p. 214-216;
  add a 429 and a permanent 4xx when the client splits them).

## 8. FSMs and handlers

- **Pure FSMs:** feed a scripted sequence of inputs and compare each new state
  and each output (applied to fixed IDs and timestamps) with the expected
  values (FEDA p. 139).
- **Handlers with failure injection:** generate inputs; use fakes that record
  writes in a `Ref` or always fail; assert what was written and what was
  acknowledged. For example: a failing writer means no ack (FEDA
  p. 182-183).
- **Stateless handlers:** in-memory stores built on `Ref` capture outbox
  events (FEDA p. 194-195).
- **WebSocket handlers:** drive `receive` with a scripted list of frames, gate
  the close on a `Deferred`, collect `send` output in a `Ref`, and assert
  without depending on the order of racing messages (FEDA p. 166-167).

## 9. Integration tests

- Test **each external interpreter in isolation** against the real service
  (one Postgres suite, one Redis suite), not the whole application in a
  staging environment (PFP p. 221). Volpe finds this enough for the shopping
  cart; in bigger applications he requires tests of the interaction between
  components, decided case by case (p. 235). Add them for critical flows
  where several services or stores must agree.
- Share one resource per suite (Weaver `IOSuite` with `sharedResource`;
  ZIO `provideShared`). Clean the data before the tests; respect foreign keys
  when you insert fixtures (p. 221-228).
- Replace dependencies on other stores with in-memory interpreters, so each
  suite tests one system (p. 228-233).
- Run migrations in the SQL suite so the schema is tested too; use the
  production database engine (FEDA p. 214).
- Stateful stores: use deterministic round trips, not properties with many
  runs (or reset the store per run) (FEDA p. 213-214).
- Integration tests find real codec bugs that unit tests miss (PFP p. 235).
- Start the services with docker-compose (or Testcontainers) both locally and
  in CI (PFP p. 225, 266-268).

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
  locally (PFP p. 267-268).
- For event-driven systems, add smoke tests: start a few services against the
  real broker, publish a fixed set of commands, and assert what clients
  receive. Do not build a full-system simulation (FEDA p. 213, 242-243).
