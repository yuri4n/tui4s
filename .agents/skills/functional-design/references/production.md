# Production practice

What changes when functional code runs in production with a team: budgets
for complexity and novelty, application structure, testing without heavy
mocking, errors, logging, persistence types, compile times, performance, and
type-level code. Sources: Parsons, *Production Haskell* (2023); Skinner,
*Effective Haskell* (2023). The examples are Haskell; the lessons are
general.

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

Sources: Parsons, "Principles" (the opening chapter), unless marked.

- **Treat complexity as the main risk;** Parsons ranks managing it as the
  most important and the hardest task. A project has a **complexity budget**
  (spent on advanced techniques; raised by paying experts) and a **novelty
  budget** (spent on unfamiliar technology; hard to raise: hiring, learning,
  documentation).
- Complexity accretes in small, individually justified steps; its costs
  multiply across interacting parts while its benefits only add.
- The business needs **predictability**; complexity hurts the precision of
  estimates. A slower system that behaves predictably is worth more than a
  faster one that fails at random.
- Safety features forbid invalid programs; when requirements change what
  "invalid" means, they become obstacles.
- Ask whether the advocate of a technique ran it in production and had to fix
  it. Strong types make people confident enough to over-build.
- **If the language is already the novelty, keep everything else boring:**
  Postgres, not a new graph database.
- **Cohesion:** one way per concern: tool-enforced style, one effect system,
  one logging library with adapters. Do not add a second way; write an
  adapter.
- **Empathy:** for your future self and teammates (write code easier to
  understand than seems necessary; mark advanced techniques), and for the
  business (code that can evolve without a rewrite).

Before you propose an advanced technique (type-level encodings, free monads,
effect systems, custom operators, code generation), state its cost, who will
maintain it, and the simpler alternative. Normand: enthusiasm for a new skill
grows faster than mastery, so practice new techniques in exercises, side
projects or throwaway branches, and use them in production once you know
their limits (Normand ch. 19). Working rule (practice, extended from his
side-project advice): introduce at most one unfamiliar technique per change,
and only where the team can maintain it.

## 2. Growing the application structure

Start simple and restructure when it hurts; a strong type checker makes
guided refactors cheap (Parsons ch. 5, the source of this section). In order
of added complexity:

| Step | Gains | Costs |
|---|---|---|
| explicit parameters | explicit, efficient | noisy diffs, long lists |
| a newtype for the application effect; wrapped libraries | one place to change | lifting boilerplate |
| an environment record | less noise; documented dependencies | hidden dependencies for newcomers |
| `ReaderT Env IO` | one simple monad | transformer complexity |
| more transformers or exotic effects | fine-grained effects | significant complexity and lock-in |

- Put a value in the environment when it is created at start-up, rarely
  changes, reaches most functions, or is mostly passed along unused.
- **Embed, don't stack:** `ReaderT Env IO` is enough. State lives in a
  mutable reference in the environment, early exit uses exceptions, logging
  uses a direct instance.
- **Never interleave effects with different transactional semantics:** a
  Redis write inside a database transaction survives a rollback, and the
  stores disagree. Parsons runs each such effect type as its own small
  language and embeds it in the application effect. Sequencing the second
  write after the commit, or an outbox, are the usual fixes (practice).

## 3. Three layers

Parsons's three-layer design (Parsons ch. 6) is a form of Bernhardt's
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

(Parsons ch. 6-7; the bullets below draw on the same chapters unless marked.)

- Good abstractions: a distributed lock by key (Redis in production, an
  in-memory map in tests); a data-access interface with only the queries you
  need. Bad: `MonadRedis`, `MonadDatabase`, `MonadFilesystem`; Parsons calls
  that "a fool's errand".
- **Do not mock what you cannot fake faithfully.** If the application
  depends on features of one database engine, test against the real engine;
  a mock database must reimplement a database, and each difference becomes a
  test/production mismatch.
- **Do not mock the clock** with a constant: that breaks the law that time
  moves forward. Pass the time as a value; a narrow time capability is still
  useful to avoid granting full I/O. (The `scala-functional-design` skill
  shows a narrow `Time` capability.)
- **Abstraction ladder:** function, then parameter, then record of functions,
  then interface. A version that takes its effectful operations as arguments
  is a pure specification: run it with real functions, or with a recorder in
  tests.
- **Start with a record of functions;** promote it to a type class when it
  models something outside the program with well-defined operations, is used
  in more than one or two places, and would have more than one instance
  (Skinner's "rule of threes": abstract after the third time you would
  rewrite the code). Keep the record when most instances would be newtype
  wrappers (Skinner ch. 6). A class gives one canonical instance per type:
  easy to locate, hard to override in an inner scope (Parsons ch. 6).
- **Effects as data, weakest structure first:** before a monad, ask whether a
  Monoid, an Applicative, a command sum type or a list of commands is enough;
  free monads are complicated (Parsons ch. 6).

## 5. Invert your mocks

Sources: Parsons ch. 7, unless marked.

- Signal: an action of type `App ()`: no arguments (inputs come from
  effects) and a unit result (outputs are effects). Testing it needs mocks of
  every service it touches, and mocks make the whole codebase harder to work
  with.
- Steps: split off the input effects and pass their results to a helper;
  repeat until the helper only does output effects; compute the arguments of
  those effects purely; property-test the pure parts; write a few
  integration tests for the thin shell.

  ```haskell
  syncPrices :: App ()
  syncPrices = do
    products <- listProducts                       -- input effects
    quotes   <- traverse fetchQuote products
    for_ (priceUpdates (zip products quotes)) (uncurry storePrice)  -- output effects

  priceUpdates :: [(Product, Quote)] -> [(Sku, Price)]  -- pure and tested
  -- storePrice :: Sku -> Price -> App ()
  ```

- Cost: several passes instead of one; a streaming library keeps one pass
  with separate, testable stages.
- If you must fake, fake one level up (a small query interface, not a
  database), and make the fake produce empty and missing cases. Even
  Parsons's own simple fake left out the "no such user" case.
- **When a test is hard to write, add an intermediate data type** and test
  each side (a `Template` of semantic parts between data and rendered text),
  instead of searching output text, which Parsons calls "data archaeology"
  (Parsons ch. 16).

## 6. Push safety to the edges

Sources: Parsons ch. 10-11, unless marked.

- `Maybe` and `Either` results push failure **forward**: every caller must
  handle it. Push it **back** instead with precise inputs (`NonEmpty`,
  `NonZero`, keys proven present) built by smart constructors.
- A precise input type moves failure to the edges. For example, a non-empty
  list of order items removes `Maybe` from every function that reads the
  items; the failure moves to JSON decoding (reply 400) and to database
  decoding (inner join, foreign keys).
- Restricting the domain simplifies; expanding the range adds options. Parse,
  don't validate.
- Avoid it when the invariant belongs to a business rule that is likely to
  change (Parsons, "Principles").

## 7. Errors and exceptions

Sources: Parsons ch. 12-13, unless marked.

- **No monolithic application error type.** One big `AppError` sum lets
  functions claim errors they cannot raise, breaks exhaustive handlers when
  cases are added, and cannot shrink when cases are handled.
- **One constructor per error type, named like the type, with structured
  fields** that let you write a test or fix the input; never only a string.
  Parsons presents this as his own preference, not as a law.
- **Choose typed or untyped errors by how cheaply the language composes
  them.** In Haskell application code Parsons prefers runtime exceptions with
  small hierarchies per component and scoped context annotations, because
  typed error composition is clumsy there. In languages with cheap typed
  errors (ZIO's error channel, F# and Rust results, Scala 3 union types), the
  typed approach in `errors-and-validation.md` is the default.
- Wrap lower-level exceptions with context; attach context (user ID, route)
  with a scoped annotation instead of threading it into every error type.
- Asynchronous exceptions arrive almost anywhere; avoid uninterruptible masks
  (they break `race` and can hang). See
  `concurrency-constructs.md`.
- For a command-line tool, one error channel at the boundary can be enough:
  turn `Left` values into IO exceptions and catch them once at the top to
  print a friendly message (Skinner ch. 8). When callers must react to
  cases, throw a structured exception type or keep the `Either`;
  `userError . show`, as in the book's example, loses the structure. As a
  general rule (practice): typed results for expected failures, exceptions
  for exceptional ones.

## 8. Logging

Sources: Parsons ch. 17.

- Keep effectful code step by step where you may need to log; point-free
  effectful pipelines make adding a log line a refactor.
- **Structured logging contexts:** set context (user, route, request ID) at
  the router or authentication scope; every log line inside carries it, even
  from libraries. Messages get short and keys consistent.
- One logging backend; bridge library logs into it.

## 9. Persistence types and database operations as data

Sources: Parsons ch. 18.

- **Separate database, domain and API types** with explicit conversions. One
  type for all three makes database-only fields optional on the domain type,
  and "always present after the database" leads to crashes; start split.
  When a type does not fit a new need, write a new data type.
- **Parsons warns against polymorphic records** for this: a type parameter
  per varying field does not scale; higher-kinded data varies all fields
  uniformly and cannot add or remove fields; and phase-indexed type families
  need an entry per field per phase. In his view this is much complexity to
  avoid a few plain, separate data types, although he grants that
  higher-kinded data fits other applications. Granin uses an ID type
  parameter for the "no ID yet" case (`persistence-and-boundaries.md`). Use
  that small form when it fits; write separate plain types otherwise.
- **Describe database operations as values**, so that one module turns them
  into SQL and tests can inspect them:
  - Selection: a small filter type (field conditions joined with And and Or)
    that one interpreter renders as parameterized SQL. This gives one point
    of control, tests without a database and no injection. Updates and
    deletes pick their rows with the same type. Use the full query library
    only for the rare query that the type cannot express.
  - Insert: a separate input type without the columns that the database
    fills in (IDs, timestamps).
  - Change: a list of field assignments, or a sum type of named changes
    (`RenameAccount`, `AddMember`) that you can apply to an in-memory value
    and also render as SQL. Do not use an update function on the record: you
    cannot inspect it, store it or turn it into SQL. Do not accept a whole
    record from a client either: it rewrites every column and its indexes,
    and it lets the client set a privileged field (a `role`) that the form
    never showed.
  - Delete: pick the rows with the selection type, and declare foreign-key
    cascades in the schema instead of deleting dependent rows by hand.

## 10. Effect stacks and application monads

Parsons and Skinner differ here. For the application monad, follow section 2
(`ReaderT Env IO`; embed, don't stack). Use the stack rules below only where
a stack is justified, for example a parser or small interpreter that needs
backtracking state and typed failure (Skinner ch. 13, the source of this
section).

- **Direct style or capability-polymorphic style:**

  | Concern | Concrete effect type | Capability constraints (mtl, tagless final) |
  |---|---|---|
  | know which effects run | yes | no |
  | reuse in unforeseen contexts | low | high |
  | dependency injection, test doubles | manual | natural |
  | error messages | clearer | harder |
  | compile time in large apps | lower | higher |

  Choose per module; libraries benefit most from polymorphism.
- **Wrap the stack in a named type** (a newtype, not an alias) with one runner
  and derived capabilities, instead of manual lifting.
- **The order of layers decides semantics.** With `ExceptT e (State s)`,
  where `ExceptT` is the outermost transformer, a failed alternative keeps the
  state it changed. With `StateT s (Except e)`, each alternative starts from
  the original state (backtracking). For each pair of effects, decide what a
  failure does to the other, order the layers by that, and test choice and
  repetition.
- Do not use the "fail" of pattern matching for error handling.

## 11. Performance

A tuning loop (Skinner ch. 14):

1. Measure on realistic data. A toy input can hide a slowdown of several
   orders of magnitude: Skinner's naive spellchecker was instant on a tiny
   dictionary and took minutes on a full word list.
2. Profile: read call counts as well as time and allocation; billions of
   inner calls show repeated work. Change one thing at a time and keep a table
   of results per version.
3. Fix the algorithm first. In the spellchecker, memoization gave a far
   larger gain than all later tuning together.
4. Then data layout: contiguous, unboxed storage for indexed tables; strict
   loop variables.
5. Then local mutation behind a pure interface (ST) for well-known imperative
   algorithms; avoid unsafe escapes.
6. Scope memo caches to the call; a top-level cache is never collected.

Laziness: a timing step that "costs nothing" while a later step is slow is a
sign of deferred work; force values when you store them in long-lived cells;
use strict fields and strict containers for accumulators (Skinner ch. 10).
See `algorithms.md` section 11 and `data-structures.md` section 6.

## 12. Type-level programming

- Use it only when a rule cannot be stated with ordinary types; the normal
  approach is top-down ordinary types.
- It buys compile-time guarantees; it costs expressiveness, readability and
  very hard error messages. Budget for custom compile errors (Skinner ch. 15,
  for this and the previous bullet).
- Parsons: open type families are extensible but not exhaustive; closed ones
  the reverse; a missed case gets stuck and surfaces as a confusing error
  elsewhere (Parsons ch. 21-22). Keep type-level machinery for widely reused
  library features with library-grade maintainers; avoid it in application
  code where plain types work.
- Granin agrees: hide type-level machinery inside framework layers; business
  code must not need it (`architecture.md`).

## 13. Modules, compile times and API evolution

- Compile time is superlinear in module size; caching and parallelism work per
  module. Break a giant types module into groups of related types (Parsons's
  untested intuition: 50-200 lines; each module has a fixed compile cost, so
  do not make one per type), starting from the bottom of the dependency
  graph. Keep the graph broad and shallow (Parsons ch. 9, also for the next
  bullet).
- Split packages only for real libraries or slow, stable parts. Isolate
  generated code in small modules.
- A project prelude that re-exports common modules documents local idioms;
  each difference from the standard adds novelty (Parsons ch. 8).
- **Library APIs evolve additively:** a new function instead of a new
  parameter; hidden record constructors; one record per sum constructor;
  deprecate first, then remove with an upgrade path. Applications own all
  their call sites and can lean on compiler-guided refactors (Parsons
  ch. 15).

## 14. Teams and onboarding

- Management cares about variance; each advanced feature raises it. Prefer the
  simplest code you can get away with (Parsons ch. 1).
- A team grows from juniors only if the code stays easy to enter; expert-only
  code forces senior-only hiring, and then the business drops the language
  (Parsons ch. 3).
- Teach the smallest closure of concepts first, with real problems early;
  write code a newcomer can be trained on; flag and document advanced
  techniques (Parsons, "Principles"; ch. 2-3).
- When you introduce functional programming to a company, start with a slow,
  buggy core service and show business results (Parsons ch. 1).
