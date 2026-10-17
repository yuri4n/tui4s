# Errors and validation

How to represent, propagate and convert failures across a functional
application. Sources: Granin (error domains), Wlaschin (taxonomy, railway
composition), Ghosh (choice of failure type).

## Contents

1. Definitions
2. Error domains and boundary conversion
3. Classify each error
4. Choose the failure type
5. Compose failing steps
6. Validation
7. Exceptions inside a functional language
8. Checklist and anti-patterns

## 1. Definitions

Granin separates four terms (p. 338-339):

- **Error:** an expected, probable outcome under known circumstances. It is
  explicit and related to the domain. Model it as a value.
- **Exception:** a language mechanism that stops normal control flow. It is
  implicit and does not know about the domain. Use it for bugs and truly
  unexpected conditions.
- **Exceptional situation:** an unexpected condition, usually a bug.
- **Native error:** an error from outside your code (libraries, the runtime).
  If it reaches business logic unchanged, it couples that logic to the
  library: a library upgrade then ripples through your code, and tests
  sometimes cannot construct the native error at all, when its type is opaque
  (p. 340-341).

Granin's position: exceptions hide control flow; he would prohibit them if he
could (p. 349, 366). Wlaschin and Ghosh agree for expected failures, and both
keep exceptions for panics.

## 2. Error domains and boundary conversion

An **error domain** is a region of the program with one error-handling
mechanism and a guarantee that nothing else escapes it (p. 341). Typical
domains, from the outside in:

```text
native library      native exceptions, native error types
implementation      runtime-level errors
subsystem interface the subsystem's own error types
business logic      domain error types
application         application error types, the last catch-all
```

Rules:

- **Convert everything that crosses a boundary** into the receiving domain's
  type. Business code knows only its own error type, so transport errors can
  change without touching it (p. 341, 354-355).
- **Choose one exception policy per codebase**, and keep it:
  - (a) Total gatekeeping (Granin p. 345-346): each implementation catches
    every exception from the outside world and converts it into the
    interface's own error type, with a catch-all case such as `OtherError`
    when needed (p. 342). No exception can then reach business code. The
    business logic, not the implementation, decides which errors are domain
    errors.
  - (b) Selective (Wlaschin p. 202, 214; Parsons p. 159-173 for Haskell):
    convert only the expected failures, and let every other exception,
    wrapped with context, reach one top-level handler.
- Where the conversion happens (p. 341-345):

  | Option | Verdict |
  |---|---|
  | The implementation catches and returns the interface's error type | preferred |
  | The implementation catches; a smart constructor converts the native result | allowed if the native-returning constructor stays private |
  | The implementation catches and drops the error; the operation returns unit | only with a documented weaker contract ("no guarantee of success") |

- Do not raise asynchronous exceptions from your own code or libraries you
  control: they are hard to reason about and crash an innocent thread
  (p. 345). Where the effect system uses interruption for cancellation, keep it
  at explicit, documented boundaries.
- **Handlers are boundaries.** An HTTP handler or a CLI entry point converts
  domain errors and escaped exceptions into responses or exit codes, or runs a
  compensating action (p. 382).

```haskell
-- the implementation converts a native exception into the interface's error type
writeFileImpl :: FilePath -> Text -> IO (Either FsError ())
writeFileImpl p s = first toFsError <$> try (Native.writeFile p s)
```

## 3. Classify each error

Wlaschin's taxonomy (Wlaschin p. 200-203):

| Kind | Examples | Handling |
|---|---|---|
| Domain error | invalid product code, out of stock, payment rejected | a choice type in the workflow's result; discuss it with the domain expert |
| Infrastructure error | timeout, authentication failure, database down | model it when the business must react (often in monoliths); otherwise let it propagate to the top handler |
| Panic | out of memory, impossible state, broken invariant | throw; catch at the top; do not model |

- Model domain errors as a sum type with one case per kind that the business
  cares about, named in the business's words. Adding a case makes the
  compiler point at every handler, which starts the right conversation.
- Do not enumerate every possible error up front, and do not turn every
  exception into a domain error (p. 201-202, 214).
- When unsure whether something is a domain error, ask the user.
- Modeling an infrastructure error explicitly forces the question "what should
  the business do when the address service is down?" (p. 202).

### Unknown outcomes and retries

An infrastructure error after a call with a side effect has a third kind of
result: **unknown**. A timeout, a dropped connection or a status that the
client does not recognize does not tell you whether the charge, shipment or
message happened. This rule comes from practice, not from one of the books;
Volpe's checkout (`scala-functional-design`) applies it with an idempotent
payment API.

- Model the outcome as three cases, not two: `Succeeded`, `Failed` (definite,
  for example a decline), `Unknown`.
- On `Unknown`, keep the entity in a pending state and find the truth: retry
  with the same idempotency key, query the other system's status, or let a
  reconciler do it later. Compensate (cancel, refund, release stock) only on
  a definite failure.
- Classify before you retry. Transient: timeouts, connection errors, HTTP 408,
  425, 429 (wait for `Retry-After`) and 5xx. Permanent: other 4xx, validation
  and authorization errors. A 409 from an idempotent API means "already done";
  read the existing result.
- Bound every retry loop (attempts and total time), and persist the pending
  state before the first attempt, so a crash does not lose it.
- A reconciler treats intermediate statuses (`processing`, `pending`) as
  unknown and looks again later; it never maps them to failure.

## 4. Choose the failure type

Use the narrowest type that fits (Ghosh p. 101-105, 210-212). Map the names to
the project's language and effect system.

| Need | Type |
|---|---|
| Absence only | `Maybe` / `Option` |
| Wrap legacy code that throws | `Try` (convert once at the boundary) |
| Typed error, fail fast | `Either` / `Result` |
| Report all errors | an accumulating validation type (applicative) |
| Latency | the project's async or IO type |
| Latency plus typed error | the effect system's error channel, or a single alias for the stack |

Keep one alias per module for its effect stack (`type Valid a = ...`), so the
stack changes in one place.

**Choose typed or untyped errors by how cheaply the language composes them**
(Parsons p. 159-173). Where typed errors compose cheaply (ZIO's error channel,
F# and Rust results, Scala 3 unions), use them as this file describes. In
Haskell application code, Parsons prefers runtime exceptions in small
hierarchies per component, with scoped context annotations, because typed
error composition is clumsy there. In both cases:

- never one monolithic application error type: functions then claim errors
  they cannot raise, and handled cases cannot be removed from the type;
- structured fields that let you write a test or fix the input; never only a
  string (Parsons also prefers one constructor per error type, named like the
  type: his preference, not a law);
- wrap lower-level errors with context instead of passing them up bare.

**Layer order changes semantics** (Skinner p. 500-506). With
`ExceptT e (State s)` (it runs to `s -> (Either e a, s)`), a failed
alternative keeps the state it changed. With `StateT s (Either e)` (it runs
to `s -> Either e (a, s)`), each alternative starts from the original state.
Decide what a failure must do to the other effects, then order the layers.

## 5. Compose failing steps

Railway-oriented programming (Wlaschin p. 204-228):

- `bind` / `flatMap` chains steps that return a Result; `map` lifts a plain
  function onto the success track; `mapError` unifies error types at the
  seams. The success type can change along the track; the error type must be
  one type.
- `tee` (pass the value through) fits dead-end functions such as logging into
  the pipeline.
- When wrapping a library under policy (b) (section 2), catch only the
  exceptions that matter to the domain, and return a Result with service
  information.
- `traverse` / `sequence` turn a list of Results into a Result of a list
  (first error only, unless you use an accumulating applicative).
- do-notation, for-comprehensions or computation expressions hide the
  plumbing; the happy path stays readable.

```haskell
placeOrder :: UnvalidatedOrder -> Either PlaceOrderError [Event]
placeOrder =
  first Validation . validateOrder
    >=> first Pricing . priceOrder
    >=> pure . createEvents . acknowledge
```

## 6. Validation

- **Never trust input from the network, files or the database.** Validate
  every incoming request; convert API types to domain types only after
  validation (Granin p. 384; Wlaschin p. 230-231).
- **Accumulate errors for client-facing input.** Fail-fast validation makes
  clients fix one error per request. Compose validators with an accumulating
  applicative (Validation, ValidatedNec, ZIO.validate), not Either's
  applicative, so every field is visited and all errors are reported
  (Granin p. 386; Ghosh p. 162).
- **Report a path for nested errors** ("address.zip: too short"), so clients
  can find the field (Granin p. 386-387).
- Use fail-fast (monadic) validation only when later checks depend on earlier
  results (Ghosh p. 154).
- **Where validation lives:** simple checks at the edge; checks that need a
  service (does the product exist?) in a validation step with a dependency
  function; checks that must log or raise domain errors inside the business
  scenario (Granin p. 385-386).
- Read database rows through a validating conversion too. A missing row is a
  Result error; a duplicate row where the schema forbids it is a panic
  (Wlaschin p. 258-271).

```haskell
validateCustomer :: CustomerDto -> Validation [FieldError] Customer
validateCustomer d =
  Customer <$> field "name"  (mkName d.name)
           <*> field "email" (mkEmail d.email)
           <*> nested "address" (validateAddress d.address)
```

## 7. Exceptions inside a functional language

When a domain-level exception is really needed inside an interpretable
language, add two explicit operations (Granin p. 346-350):

```haskell
throwException :: Exception e => e -> Lang a
runSafely      :: Exception e => Lang a -> Lang (Either e a)
```

The interpreter throws for the first and catches for the second. With them you
can build bracket, finally and on-exception at the language level. Costs: they
hide control flow, `runSafely` catches only the named exception type, and
refactoring between them changes the call tree that white-box mocks depend on
(p. 409-410).

## 8. Checklist and anti-patterns

- [ ] Every interface operation's expected failures are in its result type.
- [ ] Every implementation follows the chosen policy: under (a) it converts
      all native exceptions; under (b) it converts the expected ones, and the
      others reach the top handler with context.
- [ ] Each boundary converts errors into the receiving domain's type.
- [ ] Domain errors are a named sum type agreed with the user.
- [ ] Panics are caught once at the top and logged.
- [ ] Client-facing validation accumulates errors with paths.
- [ ] A method that silences failures says so in its name or docs.
- [ ] Side-effecting remote calls distinguish success, definite failure and
      unknown; retries are bounded, idempotent and only for transient errors.

Anti-patterns:

- Native error types in business logic, or, under policy (a), native
  exceptions that reach it (Granin p. 340).
- `error`, partial functions or a crash for decode or database failures
  (p. 308-309).
- Turning every exception into a domain error, or, under policy (b), catching
  exceptions you cannot handle (Wlaschin p. 201-202, 214).
- A function whose type says it succeeds but that throws.
- Mixing several error mechanisms in one codebase without boundaries. Granin
  calls Haskell's many competing error mechanisms "a total disaster", and
  says that mixing them in one codebase confuses everything (p. 339).
- Fail-fast-only validation for client-facing APIs (p. 386).
