# Production practice

What changes when functional code runs in production with a team: budgets
for complexity and novelty, application structure, testing without heavy
mocking, errors, logging, persistence types, compile times, performance, and
type-level code. Sources: Parsons, *Production Haskell* (2023); Skinner,
*Effective Haskell* (2023). The examples are Haskell; the lessons are
general. Page numbers are PDF pages (Parsons: printed + 24; Skinner: see
`sources.md`).

## Contents

1. Budgets, cohesion and empathy
2. Growing the application structure
3. Three layers
4. Abstract only small capabilities
5. Invert your mocks
6. Push safety to the edges
7. Errors and exceptions
8. Logging
9. Persistence types and database operations as data
10. Effect stacks and application monads
11. Performance
12. Type-level programming
13. Modules, compile times and API evolution
14. Teams and onboarding

## 1. Budgets, cohesion and empathy

- **Managing complexity is the most important and most difficult task**
  (Parsons p. 14). A project has a **complexity budget** (spent on advanced
  techniques; raised by paying experts) and a **novelty budget** (spent on
  unfamiliar technology; hard to raise: hiring, learning, documentation)
  (p. 14-18).
- Complexity accretes in small, individually justified steps; its costs
  multiply across interacting parts while its benefits only add (p. 14-15).
- The business needs **predictability**; complexity hurts the precision of
  estimates. Prefer the reliable slow car to the fast car that breaks down
  (p. 15-16).
- Safety features forbid invalid programs; when requirements change what
  "invalid" means, they become obstacles (p. 15).
- Ask whether the advocate of a technique ran it in production and had to fix
  it (p. 16). Strong types make people confident enough to over-build (p. 17).
- **If the language is already the novelty, keep everything else boring:**
  Postgres, not a new graph database (p. 18).
- **Cohesion:** one way per concern: tool-enforced style, one effect system,
  one logging library with adapters. Do not add a second way; write an
  adapter (p. 18-21).
- **Empathy:** for your future self and teammates (write code easier to
  understand than seems necessary; mark advanced techniques), and for the
  business (code that can evolve without a rewrite) (p. 21-24).

Before you propose an advanced technique (type-level encodings, free monads,
effect systems, custom operators, code generation), state its cost, who will
maintain it, and the simpler alternative. Normand: enthusiasm for a new skill
grows faster than mastery, so practice new techniques in exercises, side
projects or throwaway branches, and use them in production once you know
their limits (p. 575-576). Working rule (extended from his side-project
advice, p. 577): introduce at most one unfamiliar technique per change, and
only where the team can maintain it.

## 2. Growing the application structure

Start simple and restructure when it hurts; a strong type checker makes
guided refactors cheap (Parsons p. 62). In order of added complexity
(p. 62-77):

| Step | Gains | Costs |
|---|---|---|
| explicit parameters | explicit, efficient | noisy diffs, long lists |
| a newtype for the application effect; wrapped libraries | one place to change | lifting boilerplate |
| an environment record | less noise; documented dependencies | hidden dependencies for newcomers |
| `ReaderT Env IO` | one simple monad | transformer complexity |
| more transformers or exotic effects | fine-grained effects | significant complexity and lock-in |

- Put a value in the environment if most functions use it, it rarely
  changes, it is forwarded unchanged, or it is created at start-up (p. 70).
- **Embed, don't stack:** `ReaderT Env IO` is enough. State lives in a
  mutable reference in the environment, early exit uses exceptions, logging
  uses a direct instance (p. 75-76).
- **Never interleave effects with different transactional semantics:** a
  Redis write inside a database transaction survives a rollback, and the
  stores disagree. Parsons runs each such effect type as its own small
  language and embeds it in the application effect (p. 76). (Sequencing the
  second write after the commit, or an outbox, are the usual fixes; they are
  not from the book.)

## 3. Three layers

Parsons's "Three Layer Cake" (p. 78-87), the same idea as Bernhardt's
functional core, imperative shell:

1. **Orchestration:** the application effect where everything finally runs;
   operational concerns (concurrency, metrics, logging state). Its tests are
   integration tests; keep few, and move logic up when you need many.
2. **Capabilities:** small swappable interfaces for external services that
   are annoying in development and tests (records of functions or capability
   classes). Keep this layer thin: it has boilerplate, and readers must trace
   which implementation is in play.
3. **Business logic:** pure functions and simple data; inputs acquired before,
   effects done after; only as much power as needed. Most code and most tests
   live here.

This matches Granin's layers (`architecture.md`) and Normand's onion
(`everyday-fp.md`).

## 4. Abstract only small capabilities

| Question | Yes | No |
|---|---|---|
| Can the logic take plain values instead? | decompose (section 5) | next question |
| Is the needed API small and domain-shaped? | candidate | do not abstract |
| Is the service annoying in development or tests? | abstract it | integration-test it |
| Are run-time values or local overrides needed? | record of functions | type class or trait |

(Parsons p. 80-86, 99-101.)

- Good abstractions: a distributed lock by key (Redis in production, an
  in-memory map in tests); a data-access interface with only the queries you
  need. Bad: `MonadRedis`, `MonadDatabase`, `MonadFilesystem` ("a fool's
  errand") (p. 81-85).
- **Do not mock what you cannot fake faithfully.** If the application
  depends on features of one database engine, test against the real engine;
  a mock database must reimplement a database, and each difference becomes a
  test/production mismatch (p. 81, 99-100).
- **Do not mock the clock** with a constant: that breaks the law that time
  moves forward. Pass the time as a value; a narrow time capability is still
  useful to avoid granting full I/O (p. 80-81). (Volpe's `Time` capability in
  `scala-functional-design` is the narrow-capability case.)
- **Abstraction ladder:** function, then parameter, then record of functions,
  then interface (p. 87, 97). A version that takes its effectful operations as
  arguments is a pure specification: run it with real functions, or with a
  recorder in tests (p. 97-99).
- **Start with a record of functions;** promote it to a type class when it
  models something outside the program with well-defined operations, is used
  in more than one or two places, and would have more than one instance
  (Skinner's "rule of threes": abstract after the third time you would
  rewrite the code). Keep the record when most instances would be newtype
  wrappers (Skinner p. 230-233, 280-281). A class gives one canonical
  instance per type: easy to locate, hard to override in an inner scope
  (Parsons p. 85).
- **Effects as data, weakest structure first:** before a monad, ask whether a
  Monoid, an Applicative, a command sum type or a list of commands is enough;
  free monads are complicated (Parsons p. 87).

## 5. Invert your mocks

- Signal: `doWork :: App ()`: no arguments (inputs come from effects) and a
  unit result (outputs are effects). Testing it needs mocks of HTTP, the
  database and Redis, and mocking "complicates every aspect" (Parsons
  p. 88-89).
- Steps (p. 90-92): split off the input effects and pass their results to a
  helper; repeat until the helper only does output effects; compute the
  arguments of those effects purely; property-test the pure parts; write a
  few integration tests for the thin shell.

  ```haskell
  doWork = do
    users  <- getUsers                          -- input effects
    things <- traverse getSomething users
    for_ (plan (zip things users)) (uncurry writeRedis)  -- output effects
  plan :: [(Thing, User)] -> [(RedisKey, Result)] -- pure and tested
  -- writeRedis :: RedisKey -> Result -> App ()
  ```

- Cost: several passes instead of one; a streaming library keeps one pass
  with separate, testable stages (p. 92-96).
- If you must fake, fake one level up (a small query interface, not a
  database), and make the fake produce empty and missing cases: Parsons's own
  trivial fake forgot "no such user" (p. 99-101).
- **When a test is hard to write, add an intermediate data type** and test
  each side (a `Template` of semantic parts between data and rendered text),
  instead of searching output text ("data archaeology") (p. 270-275).

## 6. Push safety to the edges

- `Maybe` and `Either` results push failure **forward**: every caller must
  handle it. Push it **back** instead with precise inputs (`NonEmpty`,
  `NonZero`, keys proven present) built by smart constructors (Parsons
  p. 143-155).
- Example: an order with a possibly empty item list forced `Maybe`
  everywhere; a non-empty list removed it, and failure moved to JSON decoding
  (reply 400) and database decoding (inner join, foreign keys) (p. 147-148).
- Restricting the domain simplifies; expanding the range adds options. Parse,
  don't validate (p. 149-153).
- Avoid it when the invariant belongs to a business rule that is likely to
  change (p. 15).

## 7. Errors and exceptions

- **No monolithic application error type.** One big `AppError` sum lets
  functions claim errors they cannot raise, breaks exhaustive handlers when
  cases are added, and cannot shrink when cases are handled (Parsons
  p. 159-161).
- **One constructor per error type, named like the type, with structured
  fields** that let you write a test or fix the input; never only a string.
  Parsons presents this as his preference ("stuff I like"), not as a law
  (p. 161, 168-170).
- **Choose typed or untyped errors by how cheaply the language composes
  them** (p. 165). In Haskell application code Parsons prefers runtime
  exceptions with small hierarchies per component and scoped context
  annotations, because typed error composition is clumsy there. In languages
  with cheap typed errors (ZIO's error channel, F# and Rust results, Scala 3
  union types), the typed approach in `errors-and-validation.md` is the
  default.
- Wrap lower-level exceptions with context; attach context (user ID, route)
  with a scoped annotation instead of threading it into every error type
  (p. 171-173).
- Asynchronous exceptions arrive almost anywhere; avoid uninterruptible masks
  (they break `race` and can hang) (p. 186-189). See
  `concurrency-constructs.md`.
- Skinner's command-line example keeps one error channel at the boundary: it
  turns `Left` values into IO exceptions and catches them once at the top to
  print a friendly message (p. 316-318). When callers must react to cases,
  throw a structured exception type or keep the `Either`; `userError . show`
  loses the structure. As a general rule (not from the book): typed results
  for expected failures, exceptions for exceptional ones.

## 8. Logging

- Keep effectful code step by step where you may need to log; point-free
  effectful pipelines make adding a log line a refactor (Parsons p. 287-289).
- **Structured logging contexts:** set context (user, route, request ID) at
  the router or authentication scope; every log line inside carries it, even
  from libraries. Messages get short and keys consistent (p. 289-293).
- One logging backend; bridge library logs into it (p. 293-295).

## 9. Persistence types and database operations as data

- **Separate database, domain and API types** with explicit conversions. One
  type for all three makes database-only fields optional on the domain type,
  and "always present after the database" leads to crashes; start split
  (Parsons p. 297-301). "Got a problem? Write a new data type." (p. 300).
- **Parsons warns against polymorphic records** for this: a type parameter
  per varying field does not scale; higher-kinded data varies all fields
  uniformly and cannot add or remove fields; and phase-indexed type families
  need an entry per field per phase. He calls this "a lot of complexity" to
  avoid writing a few plain, separate data types, while granting that
  higher-kinded data fits other applications (p. 303-308). Granin uses an ID
  type parameter for the "no ID yet" case (`persistence-and-boundaries.md`).
  Use that small form when it fits; write separate plain types otherwise.
- **Database operations as data** (p. 308-326):
  - create: a dedicated input type; the database sets IDs and timestamps;
  - read: a small query type (filters with And and Or) interpreted to SQL:
    one point of control, testable, safe from injection; keep the full
    library for special queries;
  - update: not `rec -> rec` (opaque: cannot be serialized or turned into
    SQL) and never whole records from clients (slow, and unsafe mass
    assignment of fields such as `isAdmin`). Use field-update lists, or an
    update-action sum type that can be interpreted both as a function and as
    SQL;
  - delete: reuse the query type; let the database cascade.

## 10. Effect stacks and application monads

Parsons and Skinner differ here. For the application monad, follow section 2
(`ReaderT Env IO`; embed, don't stack). Use the stack rules below only where
a stack is justified, for example a parser or small interpreter that needs
backtracking state and typed failure, as in Skinner's archiver (p. 507).

- **Direct style or capability-polymorphic style** (Skinner p. 521-522):

  | Concern | Concrete effect type | Capability constraints (mtl, tagless final) |
  |---|---|---|
  | know which effects run | yes | no |
  | reuse in unforeseen contexts | low | high |
  | dependency injection, test doubles | manual | natural |
  | error messages | clearer | harder |
  | compile time in large apps | lower | higher |

  Choose per module; libraries benefit most from polymorphism.
- **Wrap the stack in a named type** (a newtype, not an alias) with one runner
  and derived capabilities, instead of manual lifting (p. 507-527).
- **The order of layers decides semantics.** With `ExceptT e (State s)`,
  where `ExceptT` is the outermost transformer, a failed alternative keeps the
  state it changed. With `StateT s (Except e)`, each alternative starts from
  the original state (backtracking). For each pair of effects, decide what a
  failure does to the other, order the layers by that, and test choice and
  repetition (p. 500-506).
- Do not use the "fail" of pattern matching for error handling (p. 520).

## 11. Performance

Skinner's loop (p. 537-568):

1. Measure on realistic data: with a 4-word dictionary the naive checker
   answered in 0.035 s; one misspelled word checked against the 123,000-word
   SCOWL list took almost 3 minutes (the summary table lists the naive
   version at 680 s) (p. 537-543, 563).
2. Profile: read call counts as well as time and allocation; billions of
   inner calls show repeated work. Change one thing at a time and keep a table
   of results per version.
3. Fix the algorithm first: a lazy memo table took 680 s to 1.3 s; a mutable
   vector to 0.5 s; micro-optimizations to 0.05 s. The algorithm change
   dwarfed everything else.
4. Then data layout: contiguous, unboxed storage for indexed tables; strict
   loop variables.
5. Then local mutation behind a pure interface (ST) for well-known imperative
   algorithms; avoid unsafe escapes.
6. Scope memo caches to the call; a top-level cache is never collected.

Laziness: a timing step that "costs nothing" while a later step is slow is a
sign of deferred work; force values when you store them in long-lived cells;
use strict fields and strict containers for accumulators (p. 403-418). See
`algorithms.md` section 11 and `data-structures.md` section 6.

## 12. Type-level programming

- Use it only when a rule cannot be stated with ordinary types; the normal
  approach is top-down ordinary types (Skinner p. 569-570).
- It buys compile-time guarantees; it costs expressiveness, readability and
  very hard error messages. Budget for custom compile errors (p. 601).
- Parsons: open type families are extensible but not exhaustive; closed ones
  the reverse; a missed case gets "stuck" and surfaces as a confusing error
  elsewhere (p. 384-400). Keep type-level machinery for widely reused library
  features with library-grade maintainers; avoid it in application code where
  plain types work.
- Granin agrees: hide type-level machinery inside framework layers; business
  code must not need it (`architecture.md`).

## 13. Modules, compile times and API evolution

- Compile time is superlinear in module size; caching and parallelism work per
  module. Break a giant types module into groups of related types (Parsons's
  untested intuition: 50-200 lines; each module has a fixed compile cost, so
  do not make one per type), starting from the bottom of the dependency
  graph. Keep the graph broad and shallow (Parsons p. 124-141).
- Split packages only for real libraries or slow, stable parts (p. 126-128).
  Isolate generated code in small modules (p. 131, 139-141).
- A project prelude that re-exports common modules documents local idioms;
  each difference from the standard adds novelty (p. 102-123).
- **Library APIs evolve additively:** a new function instead of a new
  parameter; hidden record constructors; one record per sum constructor;
  deprecate first, then remove with an upgrade path. Applications own all
  their call sites and can lean on compiler-guided refactors (p. 233-261).

## 14. Teams and onboarding

- Management cares about variance; each advanced feature raises it. Prefer the
  simplest code you can get away with (Parsons p. 28-29).
- A team grows from juniors only if the code stays easy to enter; expert-only
  code forces senior-only hiring, and then the business drops the language
  (p. 47-49).
- Teach the smallest closure of concepts first, with real problems early;
  write code a newcomer can be trained on; flag and document advanced
  techniques (p. 23-24, 34-38, 47).
- When you introduce functional programming to a company, start with a slow,
  buggy core service and show business results (p. 26).
