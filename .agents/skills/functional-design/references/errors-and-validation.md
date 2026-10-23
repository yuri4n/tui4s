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

Granin separates four terms (Granin ch. 13):

- **Error:** an expected, probable outcome under known circumstances. It is
  explicit and related to the domain. Model it as a value.
- **Exception:** a language mechanism that stops normal control flow. It is
  implicit and does not know about the domain. Use it for bugs and truly
  unexpected conditions.
- **Exceptional situation:** an unexpected condition, usually a bug.
- **Native error:** an error from outside your code (libraries, the runtime).
  If it reaches business logic unchanged, it couples that logic to the
  library: a library upgrade then ripples through your code, and tests
  sometimes cannot construct the native error at all, when its type is opaque.

Granin's position: exceptions hide control flow, and he would ban them if he
could. Wlaschin and Ghosh agree for expected failures, and both keep
exceptions for panics.

## 2. Error domains and boundary conversion

An **error domain** is a region of the program with one error-handling
mechanism and a guarantee that nothing else escapes it (Granin ch. 13; the
rest of this section draws on the same chapter unless marked). A typical
arrangement, from the outside in, with the job of each part:

```text
outside world    library exceptions and error types; never seen above the adapter
adapter          catches them; may keep its own internal errors
interface        returns its own error type (FsError, DbError)
business logic   returns domain errors named in the business's words
entry point      maps whatever is left to a response or exit code; the last catch-all
```

Rules:

- **Convert everything that crosses a boundary** into the receiving domain's
  type. Business code knows only its own error type, so transport errors can
  change without touching it.
- **Choose one exception policy per codebase**, and keep it:
  - (a) Total gatekeeping (Granin): each implementation catches every
    exception from the outside world and converts it into the interface's
    own error type, with a catch-all case such as `OtherError` when needed.
    No exception can then reach business code. The business logic, not the
    implementation, decides which errors are domain errors.
  - (b) Selective (Wlaschin ch. 10; Parsons ch. 12-13 for Haskell): convert
    only the expected failures, and let every other exception, wrapped with
    context, reach one top-level handler.
- Where the conversion happens:

  | Option | Verdict |
  |---|---|
  | The implementation catches and returns the interface's error type | preferred |
  | The implementation catches; a smart constructor converts the native result | allowed if the native-returning constructor stays private |
  | The implementation catches and drops the error; the operation returns unit | only with a documented weaker contract ("no guarantee of success") |

- Do not raise asynchronous exceptions from your own code or libraries you
  control: they are hard to reason about and crash an innocent thread. Where
  the effect system uses interruption for cancellation, keep it at explicit,
  documented boundaries.
- **Handlers are boundaries.** An HTTP handler or a CLI entry point converts
  domain errors and escaped exceptions into responses or exit codes, or runs a
  compensating action (Granin ch. 14).

```haskell
-- the adapter turns a vendor exception into the interface's error type
sendSmsImpl :: Phone -> Text -> IO (Either SmsError ())
sendSmsImpl to msg = first toSmsError <$> try (Vendor.send to msg)
```

## 3. Classify each error

Sort each failure into one of three kinds (Wlaschin ch. 10):

| Kind | Examples | Handling |
|---|---|---|
| Domain error | seat already taken, coupon expired, card declined | a choice type in the workflow's result; discuss it with the domain expert |
| Infrastructure error | connection refused, expired credentials, broker unavailable | model it when the business must react (often in monoliths); otherwise let it propagate to the top handler |
| Panic | resource exhaustion, impossible branch, broken invariant | throw; catch at the top; do not model |

- Model domain errors as a sum type with one case per kind that the business
  cares about, named in the business's words. Adding a case makes the
  compiler point at every handler, which starts the right conversation.
- Do not enumerate every possible error up front, and do not turn every
  exception into a domain error.
- When unsure whether something is a domain error, ask the user.
- Modeling an infrastructure error explicitly forces a business question:
  what should happen when the payment provider is down?

### Unknown outcomes and retries

An infrastructure error after a call with a side effect has a third kind of
result: **unknown**. A timeout, a dropped connection or a status that the
client does not recognize does not tell you whether the charge, shipment or
message happened (practice). The `scala-functional-design` skill applies this
rule to a checkout with an idempotent payment API.

- Model the outcome as three cases, not two: `Succeeded`, `Failed` (definite,
  for example a decline), `Unknown`.
- On `Unknown`, keep the entity in a pending state and find the truth: retry
  with the same idempotency key, query the other system's status, or let a
  reconciler do it later. Compensate (cancel, refund, release stock) only on
  a definite failure.
- Classify before you retry. Transient: timeouts, connection errors, HTTP 408,
  425, 429 (wait for `Retry-After`) and 5xx. Permanent: other 4xx. A 409 from
  an idempotent API means "already done"; read the existing result.
- Split the permanent errors by whose fault they are. The request or the
  customer (validation errors, a documented decline) is a domain result.
  Our own setup (401, 403, or 404 on a fixed endpoint: a wrong key or URL) is
  a bug: alert, keep the entity pending, and never report it to the customer
  as a decline.
- Translate only the errors that you can name. A catch-all that maps every
  library error to one domain error (every SQL error becomes "unavailable")
  hides bugs; let the unnamed ones pass as infrastructure errors or defects.
- Bound every retry loop (attempts and total time), and persist the pending
  state before the first attempt, so a crash does not lose it.
- **Take the idempotency key from the caller.** A key that the call makes
  itself (the ID of an order that it inserts, a random UUID) covers only the
  retries inside that call. When the caller retries after a lost response,
  the new call makes a new key, and the provider sees a second charge. Use a
  key that the caller sends again on its retry: a client-supplied request
  key (an `Idempotency-Key` header, a client order ID) or a stable natural
  key (a cart ID and version). Store it with a unique constraint, and for a
  repeated key return the earlier result (or continue the pending work with
  the same downstream key) instead of starting again. A guard such as "one
  pending order per customer" is not enough: when the first call completed
  and only its response was lost, the retry starts a second order (practice).
- **Scope the key to the caller.** Make it unique per caller (customer and
  key), not across all callers; otherwise one customer's key can return
  another customer's order. A repeated key with a different request (other
  items, another customer) is an error, not a reason to return the stored
  result (practice).
- A reconciler treats intermediate statuses (`processing`, `pending`) as
  unknown and looks again later; it never maps them to failure.
- **The reconciler races with late attempts.** End an order as failed only
  when the other system has no record for the key and no attempt can still
  be running (the last attempt started longer ago than the longest request
  time). Every path that charges checks the state first and records the
  result with a conditional update (`WHERE status = 'pending'`). A charge
  that the provider confirms for an order that is no longer pending is
  refunded or raised as an alert, never dropped (practice).

### Follow-up effects: outbox and relay

When a state change must cause an effect in another system (ship the order,
send the email, publish the event), do not call that system after the
commit and hope. Write a pending record of the effect (an outbox row) in the
same transaction as the state change, and let a relay deliver it (practice;
`scala-functional-design` has the broker form).

- **Short transactions.** The relay claims rows in one short transaction
  (`FOR UPDATE SKIP LOCKED`, or a lease column), calls the other system
  outside it, and marks the row done in a second transaction. A crash between
  the steps sends the row again, so send the row's ID as the idempotency key
  and let the receiver deduplicate.
- **Transient errors keep the row pending.** A timeout, a connection error,
  408, 429 or 5xx gets a capped backoff, and the row stays pending; a timeout
  is an unknown outcome, and the resend is safe. Do not park rows one by one
  during an outage, because each parked row then needs a manual replay.
  Alert on the age of the oldest pending row instead.
- **Park only rejections, with an alert.** A rejection is about this row (a
  4xx that will not change for this payload, such as an invalid address or a
  payload the receiver refuses), and a retry does not make it better. Mark
  the row failed or parked at the first rejection and raise an alert (an
  error-level log that pages, a metric with an alert rule).
- **A setup error is not a rejection.** A 401 or 403, an expired credential,
  or a wrong URL or topic fails every row in the same way. Keep the rows
  pending and alert, as in an outage; to park them would park every row.
- **A lasting failure reaches a person.** A relay that retries forever and
  only records the last error hides the failure until a customer complains.
  Implement at least one of the two alerts above in the relay, not only in
  the design notes.
- **Order per key.** When order per key matters, a failed or parked row
  blocks the later rows of its key.

## 4. Choose the failure type

Use the narrowest type that fits (Ghosh ch. 3, 6). Map the names to the
project's language and effect system.

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
(Parsons ch. 12-13). Where typed errors compose cheaply (ZIO's error channel,
F# and Rust results, Scala 3 unions), use them as this file describes. In
Haskell application code, Parsons prefers runtime exceptions in small
hierarchies per component, with scoped context annotations, because typed
error composition is clumsy there. In both cases:

- never one monolithic application error type: functions then claim errors
  they cannot raise, and handled cases cannot be removed from the type;
- structured fields that let you write a test or fix the input; never only a
  string (Parsons also likes one constructor per error type, named like the
  type; that is a preference, not a law);
- wrap lower-level errors with context instead of passing them up bare.

**Layer order changes semantics** (Skinner ch. 13). With
`ExceptT e (State s)` (it runs to `s -> (Either e a, s)`), a failed
alternative keeps the state it changed. With `StateT s (Either e)` (it runs
to `s -> Either e (a, s)`), each alternative starts from the original state.
Decide what a failure must do to the other effects, then order the layers.

## 5. Compose failing steps

Railway-oriented programming (Wlaschin ch. 10):

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
register :: RawSignup -> Either SignupError [Event]
register =
  first BadInput . checkSignup
    >=> first NoSeat . reserveSeat
    >=> pure . announce
```

## 6. Validation

- **Never trust input from the network, files or the database.** Validate
  every incoming request; convert API types to domain types only after
  validation (Granin ch. 14; Wlaschin ch. 11).
- **Accumulate errors for client-facing input.** Fail-fast validation makes
  clients fix one error per request. Compose validators with an accumulating
  applicative (Validation, ValidatedNec, ZIO.validate), not Either's
  applicative, so every field is visited and all errors are reported
  (Granin ch. 14; Ghosh ch. 4).
- **Report a path for nested errors** ("address.zip: too short"), so clients
  can find the field (Granin ch. 14).
- Use fail-fast (monadic) validation only when later checks depend on earlier
  results (Ghosh ch. 4).
- **Where validation lives:** simple checks at the edge; checks that need a
  service (does the product exist?) in a validation step with a dependency
  function; checks that must log or raise domain errors inside the business
  scenario (Granin ch. 14).
- Read database rows through a validating conversion too. A missing row is a
  Result error; a duplicate row where the schema forbids it is a panic
  (Wlaschin ch. 12).

```haskell
validateShipment :: ShipmentDto -> Validation [FieldError] Shipment
validateShipment d =
  Shipment <$> field "weight"    (mkWeight d.weight)
           <*> field "sender"    (mkParty d.sender)
           <*> nested "recipient" (validateRecipient d.recipient)
```

## 7. Exceptions inside a functional language

When a domain-level exception is really needed inside an interpretable
language, add two explicit operations, as Granin's framework does
(Granin ch. 13):

```haskell
throwException :: Exception e => e -> Lang a
runSafely      :: Exception e => Lang a -> Lang (Either e a)
```

The interpreter throws for the first and catches for the second. With them you
can build bracket, finally and on-exception at the language level. Costs: they
hide control flow, `runSafely` catches only the named exception type, and
refactoring between them changes the call tree that white-box mocks depend on
(Granin ch. 15).

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
- [ ] Idempotency keys come from the caller (a client-supplied key or a
      stable natural key) and are unique per caller, so a caller's retry
      gets the earlier result and never another caller's.
- [ ] Follow-up effects go through an outbox. The relay keeps transient
      failures and setup errors (401, 403) pending with an alert on the
      oldest pending row, and parks only rejected rows, with an alert.

Anti-patterns:

- Native error types in business logic, or, under policy (a), native
  exceptions that reach it (Granin ch. 13).
- `error`, partial functions or a crash for decode or database failures
  (Granin ch. 11).
- Turning every exception into a domain error, or, under policy (b), catching
  exceptions you cannot handle (Wlaschin ch. 10).
- A function whose type says it succeeds but that throws.
- Mixing several error mechanisms in one codebase without boundaries. Granin
  calls Haskell's many competing mechanisms "a total disaster" and warns that
  mixing them in one codebase confuses everyone (Granin ch. 13).
- Fail-fast-only validation for client-facing APIs (Granin ch. 14).
