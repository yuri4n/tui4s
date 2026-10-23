# Testing functional applications

How to make an architecture testable, which kind of test reduces which risk,
and how to use property-based tests, mocks and test interpreters. Sources:
Granin ch. 15 and appendix D (main), Ghosh, Wlaschin, Pearls.

## Contents

1. What tests are for
2. A testable architecture
3. Test kinds by risk
4. Property-based tests
5. Mocks, test interpreters and white-box tests
6. Integration tests
7. Hygiene and anti-patterns

## 1. What tests are for

Sources: Granin ch. 15; Ghosh ch. 9.

- Tests do not prove that code has no bugs. They reduce project risks by
  checking that important requirements, functional and nonfunctional, are met.
- Functional code is easier to test (purity, determinism), but tests assume
  that the test environment behaves like the real one, which is not always
  true.
- More meaningful tests lower risk and raise maintenance cost. Choose tests by
  the risk they reduce.
- Use the type system for rules it can express, and tests for the rest. Do not
  test what the compiler already guarantees (Ghosh ch. 9).

## 2. A testable architecture

Granin's rule: "the architecture can't be good if it's untestable"
(Granin ch. 15). Check:

- [ ] Each subsystem and external service has an interface, and business
      logic uses only these interfaces.
- [ ] No direct effect and no shortcut in business logic goes around an
      interface.
- [ ] A test implementation can replace each interface, with no change to
      the logic.
- [ ] Configuration (database paths, URLs) arrives from outside.

A bare effectful function can secretly do anything; a function written in a
restricted language can do only what the language allows.

Testability by injection style, from Granin's comparison (ch. 15): in his
view, value injection (handles, interpreters) tests more easily than
type-based injection (Skinner ch. 13 disagrees for mtl style); bare IO with
direct effects cannot be tested in general; only a free-monadic design
isolates the implementation so that it cannot be bypassed. See
`functional-interfaces.md`, section 3.

If you build an application framework, ship a testing framework with it so
that users can test their scenarios.

## 3. Test kinds by risk

| Risk | Test kind |
|---|---|
| A pure algorithm breaks an invariant | property-based tests (small, pure, CPU-bound code) |
| A pure function is wrong on examples or edge cases | example tests (arrange, act, assert) |
| An optimized version differs from the specification | property tests that compare it with the simple specification (see `algorithms.md`) |
| Subsystems misbehave together; a real service behaves differently | integration tests with test configuration and test databases |
| A fake behaves differently from the real store, so workflow tests pass for the wrong reason | one contract suite run against the fake and the real store (section 5; practice) |
| Logic mishandles a failure in the middle of a flow; sensitive logic regresses | white-box tests with mock implementations |
| Exact behavior must never change | golden tests; automatic record and replay |
| The product does not solve the business problem | acceptance tests |

(Granin ch. 15.)

## 4. Property-based tests

Sources: Granin ch. 15, unless marked.

- Best for small, pure, algorithmic code with invariants: parsers, codecs,
  transformations, data structures, laws.
- **Laws as properties** (Ghosh ch. 3, 9): round trips (encode then decode;
  credit then debit), invariants (close date after open date), idempotence,
  and the laws of your own instances (monoid, lens, monad).
- **Specification as oracle:** keep the simple, obviously correct version of an
  algorithm and check the optimized version against it on generated inputs.
  In Bird's convex-hull pearl, such a check exposed a bug with collinear
  points (Pearls ch. 23).
- Write generators that produce valid, invalid and boundary values with
  controlled frequencies. Generated inputs cluster near small values and
  edges; a bug above a large threshold can pass.
- Do not filter generated values heavily with preconditions: the test gives
  up. Use narrower types, better generators, or treat negative cases as part
  of the property.
- Granin finds property tests of effectful scenarios not worth the effort in
  his example. Prefer running properties against a pure test interpreter or
  an in-memory model, and use state-machine (model-based) property tests for
  stateful components. Avoid property tests that call real external systems.
- Keep a few example tests next to properties: they are easier to read and
  reproduce.

## 5. Mocks, test interpreters and white-box tests

- **Prefer pure or in-memory implementations to mocks.** A map-backed
  repository or a stub function passed inline is often enough (Ghosh ch. 9;
  Wlaschin ch. 9).
- **A fake models the behavior that the property depends on, and the test
  asserts the business outcome.** To show "a lost response does not charge
  twice", the fake provider must deduplicate by idempotency key and record
  charges; then assert one charge, not only that the same key was sent twice
  (practice).
- **Tie each fake to the real implementation with one contract suite.** The
  workflow tests trust the fake, so the fake must keep the behaviors that
  they depend on: a unique key rejects a duplicate, a missing row is "not
  found", a claimed row is not claimed twice. Write the interface's tests
  once, as a function of the implementation, and run them against the fake
  in every build and against the real store in the integration suite. Then
  the fake cannot drift from the store unnoticed. A separate integration
  test of the real store does not do this, because nothing checks the fake
  (practice).

  ```haskell
  storeContract :: IO OrderStore -> Spec          -- the same tests for every implementation
  storeContract mkStore = do
    it "rejects a second order with the same request key" $ do
      store <- mkStore
      _     <- insertPending store key1 order1
      insertPending store key1 order2 `shouldReturn` Left (DuplicateKey key1)

  spec :: Spec
  spec = do
    describe "in-memory store" (storeContract newMemoryStore)
    describe "postgres store"  (storeContract (newPostgresStore testDb))  -- integration suite
  ```
- **Where mocking lives:** by default, write separate test implementations.
  Put recording hooks into the real implementations only when you build record
  and replay (Granin ch. 15). Mocks assume that the test environment behaves
  like the real one, so keep at least one integration test for each real
  external edge.
- **Handle mocks:** a handle value whose functions return canned values by
  input, with defined behavior for unknown inputs, optional call counters and
  named fakes. A mock returns the real result types (Granin ch. 4).
- **Count calls where they arrive.** To test a retry bound or "exactly one
  charge", count the calls at the fake of the external system. A recording
  retry hook shows what the policy decided, and a test interpreter of the
  retry replaces the real policy; neither shows how many calls reached the
  provider (practice).
- **Test interpreters:** an alternative interpreter of the same language. A
  stateful list of mock values, one popped per call, drives logic into any
  branch and simulates an outage at a precise point (Granin ch. 15, also for
  the next three items).
- **Step-scripted test runtime:** for each framework call, a step says "return
  this mock", "descend into the nested script", or "run the real
  implementation once". Record the call stack (operation and data) and assert
  it. Mocks stored as untyped values fail at run time when mistyped; that is
  acceptable in tests.
- **Why white-box tests:** they fix exact behavior, protect sensitive logic,
  simulate outages, and document. **Costs:** they are expensive and brittle
  under refactoring, and Granin calls full white-box coverage "often
  senseless"; it can slow a project a lot. Limit them to logic that must not
  change.
- **Automatic record and replay (golden tests)** (Granin, appendix D): the
  framework records every operation (inputs and outputs) of a real run; a
  player replays the recording as mocks and reports the first step that does
  not match. Per-step modes allow skipping verification or running the real
  effect. Requirements: every operation recordable and every result
  serializable; setup is clumsy for state, threads and databases.
- **Traces for debugging:** when the program is an interpretable description,
  a recording of every operation is a complete fingerprint of one run (inputs,
  queries, results). Use it before you add debug logging to business code
  (Granin, appendix D).

## 6. Integration tests

Sources: Granin ch. 15.

- They give the most confidence because they are closest to production, but
  they are slow, unreliable and costly to set up.
- Pass every configuration value from outside so that tests use a test
  database, never production.
- Commit embedded databases (SQLite, RocksDB) as test data; give standalone
  services (Redis, Postgres) setup and teardown, ideally shared across a suite
  as one resource.
- Test the edges between subsystems and the behavior of the real external
  system. A controlled test clock helps with timing logic, but it does not
  replace a test against the real system.
- **Test each atomic write once against the real database.** When two
  writes must commit together (a business row and its outbox row), make the
  second write fail inside the transaction (a duplicate key, or a step that
  raises) and check that the first row is not there. A fake has no
  transactions, so it cannot show this (practice).

## 7. Hygiene and anti-patterns

Hygiene (Granin ch. 15):

- Arrange, act, assert; one responsibility per test.
- Tests are independent; no shared mutable state between tests (it breaks
  parallel runs).
- Isolate effects, which needs a clear separation of concerns.
- Test the whole structure a constructor builds, not only that one part exists
  (Granin ch. 4).

Anti-patterns:

- Hard-coded production configuration in scenarios; heavy precondition
  filtering in property tests; chasing full white-box coverage (Granin
  ch. 15).
- A mocking library where a pure function or an in-memory implementation would
  do; only a few hand-picked examples for a domain algebra with laws
  (Ghosh ch. 9).
- Tests that repeat the implementation's internal steps instead of checking
  visible behavior.
