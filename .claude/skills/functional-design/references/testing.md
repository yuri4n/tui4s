# Testing functional applications

How to make an architecture testable, which kind of test reduces which risk,
and how to use property-based tests, mocks and test interpreters. Sources:
Granin ch. 15 and appendix D (main), Ghosh, Wlaschin, Bird.

## Contents

1. What tests are for
2. A testable architecture
3. Test kinds by risk
4. Property-based tests
5. Mocks, test interpreters and white-box tests
6. Integration tests
7. Hygiene and anti-patterns

## 1. What tests are for

- Tests do not prove that code has no bugs. They reduce project risks by
  checking that important requirements, functional and nonfunctional, are met
  (Granin p. 389-391).
- Functional code is easier to test (purity, determinism), but tests assume
  that the test environment behaves like the real one, which is not always true
  (p. 390-391).
- More meaningful tests lower risk and raise maintenance cost. Choose tests by
  the risk they reduce (p. 415).
- Use the type system for rules it can express, and tests for the rest. Do not
  test what the compiler already guarantees (Ghosh p. 287-299).

## 2. A testable architecture

"The architecture can't be good if it's untestable" (Granin p. 402). Check:

- [ ] Every subsystem and external service is behind an interface.
- [ ] Business logic is written only against interfaces.
- [ ] Nothing bypasses an interface; there are no bare effects in business
      logic.
- [ ] Every interface can get a test implementation without changes to the
      logic.
- [ ] Configuration (database paths, URLs) comes from outside (p. 399, 401).

A bare effectful function can secretly do anything; a function written in a
restricted language can do only what the language allows (p. 402).

Testability by injection style, from Granin's comparison (p. 414-415): in
Granin's view, value injection (handles, interpreters) tests more easily than
type-based injection (Skinner p. 522 disagrees for mtl style); bare IO with
direct effects cannot be tested in general; only a free-monadic design
isolates the implementation so that it cannot be bypassed. See
`functional-interfaces.md`, section 3.

If you build an application framework, ship a testing framework with it so
that users can test their scenarios (p. 415).

## 3. Test kinds by risk

| Risk | Test kind |
|---|---|
| A pure algorithm breaks an invariant | property-based tests (small, pure, CPU-bound code) |
| A pure function is wrong on examples or edge cases | example tests (arrange, act, assert) |
| An optimized version differs from the specification | property tests that compare it with the simple specification (see `algorithms.md`) |
| Subsystems misbehave together; a real service behaves differently | integration tests with test configuration and test databases |
| Logic mishandles a failure in the middle of a flow; sensitive logic regresses | white-box tests with mock implementations |
| Exact behavior must never change | golden tests; automatic record and replay |
| The product does not solve the business problem | acceptance tests |

(Granin p. 389-415.)

## 4. Property-based tests

- Best for small, pure, algorithmic code with invariants: parsers, codecs,
  transformations, data structures, laws (Granin p. 394-398).
- **Laws as properties** (Ghosh p. 106, 292-299): round trips (encode then
  decode; credit then debit), invariants (close date after open date),
  idempotence, and the laws of your own instances (monoid, lens, monad).
- **Specification as oracle:** keep the simple, obviously correct version of an
  algorithm and check the optimized version against it on generated inputs.
  Bird's "Inside the convex hull" pearl found a real edge case (collinear
  points) this way (Bird, Pearls, p. 210-211).
- Write generators that produce valid, invalid and boundary values with
  controlled frequencies. Generated inputs cluster near small values and
  edges; a bug above a large threshold can pass (Granin p. 395).
- Do not filter generated values heavily with preconditions: the test gives
  up. Use narrower types, better generators, or treat negative cases as part
  of the property (p. 397-398).
- Granin finds property tests of effectful scenarios not worth the effort in
  his example (p. 398). Prefer running properties against a pure test
  interpreter or an in-memory model, and use state-machine (model-based)
  property tests for stateful components. Avoid property tests that call
  real external systems.
- Keep a few example tests next to properties: they are easier to read and
  reproduce.

## 5. Mocks, test interpreters and white-box tests

- **Prefer pure or in-memory implementations to mocks.** A map-backed
  repository or a stub function passed inline is often enough (Ghosh p. 288-290;
  Wlaschin p. 188-194).
- **Where mocking lives:** by default, write separate test implementations.
  Put recording hooks into the real implementations only when you build record
  and replay (Granin p. 403-404). Mocks assume that the test environment
  behaves like the real one, so keep at least one integration test for each
  real external edge (p. 390, 398-399).
- **Handle mocks:** a handle value whose functions return canned values by
  input, with defined behavior for unknown inputs, optional call counters and
  named fakes. A mock returns the real result types (Granin p. 138-141).
- **Test interpreters:** an alternative interpreter of the same language. A
  stateful list of mock values, one popped per call, drives logic into any
  branch and simulates an outage at a precise point (p. 403-406).
- **Step-scripted test runtime:** for each framework call, a step says "return
  this mock", "descend into the nested script", or "run the real
  implementation once". Record the call stack (operation and data) and assert
  it (p. 407-414). Mocks stored as untyped values fail at run time when
  mistyped; that is acceptable in tests.
- **Why white-box tests:** they fix exact behavior, protect sensitive logic,
  simulate outages, and document. **Costs:** they are expensive and brittle
  under refactoring, and full white-box coverage is "often senseless" and can
  slow a project a lot. Limit them to logic that must not change (p. 406-407).
- **Automatic record and replay (golden tests)** (appendix D, p. 438-442): the
  framework records every operation (inputs and outputs) of a real run; a
  player replays the recording as mocks and reports the first step that does
  not match. Per-step modes allow skipping verification or running the real
  effect. Requirements: every operation recordable and every result
  serializable; setup is clumsy for state, threads and databases.
- **Traces for debugging:** when the program is an interpretable description,
  a recording of every operation is a complete fingerprint of one run (inputs,
  queries, results). Use it before you add debug logging to business code
  (p. 441).

## 6. Integration tests

- They give the most confidence because they are closest to production, but
  they are slow, unreliable and costly to set up (Granin p. 398-400).
- Pass every configuration value from outside so that tests use a test
  database, never production (p. 399).
- Commit embedded databases (SQLite, RocksDB) as test data; give standalone
  services (Redis, Postgres) setup and teardown, ideally shared across a suite
  as one resource.
- Test the edges between subsystems and the behavior of the real external
  system. A controlled test clock helps with timing logic, but it does not
  replace a test against the real system.

## 7. Hygiene and anti-patterns

Hygiene (Granin p. 393-394):

- Arrange, act, assert; one responsibility per test.
- Tests are independent; no shared mutable state between tests (it breaks
  parallel runs).
- Isolate effects, which needs a clear separation of concerns.
- Test the whole structure a constructor builds, not only that one part exists
  (p. 131).

Anti-patterns:

- Hard-coded production configuration in scenarios (p. 399).
- Heavy precondition filtering in property tests (p. 397).
- Chasing full white-box coverage (p. 406).
- A mocking library where a pure function or an in-memory implementation would
  do (Ghosh p. 288).
- Only a few hand-picked examples for a domain algebra with laws
  (Ghosh p. 291-292).
- Tests that repeat the implementation's internal steps instead of checking
  visible behavior.
