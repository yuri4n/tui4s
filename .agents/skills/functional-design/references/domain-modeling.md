# Domain modeling with types

How to turn a domain into types and functions so that the code documents the
domain and the compiler enforces its rules. Sources: Wlaschin (*Domain Modeling
Made Functional*, F#), Ghosh (*Functional and Reactive Domain Modeling*,
Scala 2), Crichton (*Typed Design Patterns for the Functional Era*, Rust),
Granin.

## Contents

1. Model with AND and OR
2. Simple and constrained types
3. Make illegal states unrepresentable
4. Life cycles and state machines
5. Entities, values, aggregates
6. Workflows
7. Typed design patterns (Crichton)
8. Where each rule lives
9. Evolving the model
10. Where the sources disagree
11. Checklist and anti-patterns

## 1. Model with AND and OR

- Use only two operators for data: AND (records, product types) and OR (choice
  types, sum types). The same notation works in requirement notes and in code,
  so the types are the documentation and cannot drift (Wlaschin ch. 2, 4).
- Express behavior as function types:
  `type ValidateBooking = UnvalidatedBooking -> Either [ValidationError] ValidatedBooking`.
  The sketches use Haskell `Either err ok`; F#'s `Result ok err` puts the
  success first.
- Write the types before the implementation. Use placeholder types
  (`Undefined`) for what is not known yet, so the model compiles early. Domain
  experts can read such types with little training, so show them
  (Wlaschin ch. 5).
- Keep separate types for concepts that the business names separately (billing
  and shipping address), even when their structure is the same today.
- Separate state from behavior: immutable data types plus modules of pure
  functions. The object-oriented question "which class owns `transfer`?"
  disappears (Ghosh ch. 1).

## 2. Simple and constrained types

- **Wrap domain primitives:** `OrderId`, `CustomerId`, `UnitQuantity`, not
  `Int` or `String`. A wrapper stops mix-ups (Wlaschin ch. 4-5). In hot
  loops, measure first; a single wrapper around a whole array is an option.
- **Constrained types:** make the raw constructor private and expose
  `create :: Raw -> Either Error T` that checks range, format or length. Pass
  the field name for useful error messages. After creation the value is valid
  everywhere, so the context needs no defensive checks (Wlaschin ch. 6).
- **Validate once at the boundary, then trust.** This is "parse, don't
  validate".
- **Units:** separate kilograms from pounds and seconds from milliseconds with
  distinct types (or units of measure where the language has them).
- **Smart constructors for aggregates** return an effect with the reason for
  failure. Use accumulating validation when the user must see every error, and
  fail-fast validation when later checks depend on earlier ones (Ghosh
  ch. 3-4).
- **Encapsulation choices** (Granin ch. 3): everything public for stable
  plain data; hidden constructors plus smart constructors when invalid values
  are possible; an `Internal` module when tests need the representation.
- **Total functions: the type shows every outcome** (Wlaschin ch. 8). When a
  function can fail on some inputs, either **restrict the input** (a
  constrained type such as `NonZeroInteger`, when the caller can produce the
  valid value or it was validated at the boundary) or **extend the output**
  (an Option or Result, when the bad input is a normal outcome the caller must
  handle). Never leave the failure out of the type.

## 3. Make illegal states unrepresentable

Replace flags and optional fields with types that allow only legal
combinations (Wlaschin ch. 6):

```haskell
-- "locker OR door OR locker with door fallback": three cases, not two optionals
data DeliveryTarget
  = ToLocker LockerId
  | ToDoor PostalAddress
  | LockerThenDoor LockerId PostalAddress

-- only the confirmation flow can create a ConfirmedPhone
data PhoneNumber = Unconfirmed RawPhone | Confirmed ConfirmedPhone
sendLoginCode :: ConfirmedPhone -> IO ()
```

- List the legal cases, and make exactly those the constructors.
- Use a non-empty list for "at least one".
- A function that needs a stronger state takes the stronger type.
- The compiler then checks the rule; it needs no unit test.
- Encode each fact once. Two fields that can disagree allow invalid values
  (Granin ch. 3). Remedies, in order: (1) change the model so that it stores
  the fact once; (2) if a model change costs too much, export only smart
  constructors, and make each one reject invalid input; (3) never rely on
  colleagues to avoid invalid values. Types cannot stop a person from
  entering wrong data; validate such input.
- Do not encode rules that are dynamic data (limits that change at run time),
  and stop when the encoding makes the model unreadable. Use a smart
  constructor or a check in the aggregate instead.

## 4. Life cycles and state machines

- **One type per state** with only that state's data, and a choice type for the
  whole entity. Transitions are functions from one state to the next state or
  to an error (Wlaschin ch. 7):

  ```haskell
  data Parcel = Packing OpenParcel | Sealed SealedParcel | Shipped ShippedParcel
  addItem :: Item -> Parcel -> Either ParcelError Parcel   -- a sealed parcel refuses items
  ```

- Pipeline steps take and return specific states, so the stages cannot run in
  the wrong order. Never give a later stage's field a default value in an
  earlier stage.
- Ask edge-case questions for each state: confirm a phone number that is
  already confirmed? Remove an item from an empty parcel?
- **Phantom marker types** (`Document Draft`, `Document Published`) suit
  linear life cycles where every state carries the same data (Ghosh ch. 4).
  When the states carry different data, use one type per state.
- **Typestate** (Crichton § 2.2) adds one more step: each transition consumes
  the old value, so a stale state cannot be used again. In Rust, ownership
  gives this invalidation. Without move or linear types the guarantee is
  weaker, so say so, and add a run-time guard if misuse is likely (practice).

## 5. Entities, values, aggregates

- **Value object:** no identity, immutable, equal by content.
- **Entity:** an identity that persists while its data changes. Updates return
  a new copy with the same ID. Compare entities by ID, not by all fields; put
  the ID inside each case of a choice-type entity (Wlaschin ch. 5).
- Whether something is an entity depends on the context (Ghosh ch. 1).
- **Aggregate:** a consistency boundary, a unit of persistence and a unit of
  transfer. Update only through the root. Refer to other aggregates by ID.
  Load, save, transact and serialize whole aggregates (Wlaschin ch. 5).
- Prefer deriving values (a total) over storing them. If you store one,
  recompute it in the root's update function.
- If two aggregates always change in one transaction, look for a hidden entity
  (a `MoneyTransfer`). "One aggregate per transaction" is a guideline: when
  the business sees the work as one transaction, both aggregates may join it,
  but the transaction usually deserves its own ID and entity. Define a new
  aggregate for a use case rather than reusing one only because it exists
  (Wlaschin ch. 6).
- Keep aggregates thin; use lenses or optics for deep immutable updates, and
  expose them only at the root (Ghosh ch. 3).
- **Prefer eventual consistency between aggregates and contexts.**
  Consistency is a business decision, so ask what level the business needs
  (Wlaschin ch. 6). Use events, then reconciliation or compensation. Avoid
  distributed transactions. Default to one aggregate per transaction, with
  the exception given above.

## 6. Workflows

- A workflow is one function: `Command -> Either Error [Event]`, plus the
  project's async or IO type when it does I/O. It returns events; it does not
  publish them. Do not raise domain events inside a context with hidden
  listeners; add the follow-up as the next step (Wlaschin ch. 1, 3).
- Steps are stateless, with dependencies first and data last (see
  `functional-interfaces.md`, section 5).
- Commands carry metadata (user, time) through a generic `Command data`;
  commands from one input channel form a choice type routed at the edge.
- **Effects in signatures:** a step that can fail returns a Result; a step that
  does I/O returns the async type. Name an alias for the combination. A step
  that is "obviously safe" may still need a Result (a negative total). Do not
  return a bare boolean where the result matters; return a two-case type
  (`Sent | NotSent`) (Wlaschin ch. 7).
- **Pure core:** load the data, call a pure function that returns a decision
  as a value (`FullyPaid | PartiallyPaid Invoice`), then act on the decision
  (Wlaschin ch. 12).
- **Long-running workflows (sagas):** when a step waits for a person or a slow
  system, persist the state between steps and model each wake-up as a
  transition. Add a process manager when routing grows (Wlaschin ch. 7).
- Compose effectful steps as a pipeline (Kleisli composition) so the code
  reads like the specification (Ghosh ch. 4).
- **Organize a context by vertical slices,** one per workflow with all the
  code it needs, each arranged as an onion (domain at the center, I/O only at
  the start or end). Horizontal layers make one workflow change touch every
  layer; do not add a "service layer" that every workflow must pass through
  (Wlaschin ch. 3).
- **Serialization is the outermost stage:** JSON to DTO to domain, then the
  workflow, then domain to DTO to JSON. The infrastructure sees only bytes or
  strings, never domain types (Wlaschin ch. 11).
- **Commands and events differ** (Ghosh ch. 1). Take a booking example:
  - `ReserveSeat` is a command. It asks for a change before the change
    happens, one handler owns it, and it can be refused with a typed error.
    Name commands as imperatives; they are the inputs of workflows.
  - `SeatReserved` is an event. It records a change that already happened,
    so any number of subscribers can react to it, and none can refuse it. A
    consumer can only react or issue a compensating command. Name events in
    the past tense, and make them immutable and self-contained; they are the
    outputs of workflows.
- **Decide for each edge between contexts whether it is an asynchronous
  boundary.** It is one when the contexts are not collocated, run under
  different time constraints (a nightly job and a 24/7 service), or have
  different models (Ghosh ch. 6). Say which side owns the message format
  (consumer-driven or conformist). Inside a modular monolith, a queue and a
  direct call through the context's public API are both acceptable, provided
  the contexts stay decoupled (Wlaschin ch. 3).

## 7. Typed design patterns (Crichton)

A pattern maps a domain concept to language features so that misuse becomes a
compile error. Pick the least complex encoding that makes the dangerous
mistakes impossible (Crichton § 1, § 2.3).

| Situation | Pattern | Needs |
|---|---|---|
| An action is allowed only after a check (permission, validated input) | **Witness:** a proof value that only the checking function can create; the action requires it | private constructor, module privacy |
| An object has ordered states and legal transitions | **Typestate:** one type per state, or a state marker parameter; transitions return the next state | move semantics or linear types for the full guarantee |
| Two sequences must stay aligned (format holes and arguments, route parameters and handler parameters) | **Parallel lists:** a type-level list | type-level lists, recursive type classes |
| An open set of names with typed payloads (events, plugins) | **Registry keyed by type** instead of by string; dynamic typing hidden in one module | type identity at run time |
| A fixed small set of names | a sum type | sum types |

- A witness proves that the check happened, not that the condition still
  holds. If the condition can change, include the session or time in the
  witness, or check again (practice; the paper does not discuss staleness).
- Type-level encodings produce hard error messages. Weigh that against the
  risk, and keep the readable version where mistakes are harmless
  (Crichton § 2.3).

## 8. Where each rule lives

| Rule shape | Put it in |
|---|---|
| Range or format of one value | the smart constructor of a simple type |
| "Either A or B or both", "at least one" | a choice type or a non-empty list |
| Needs a service (address exists, product exists) | a validation step with a dependency function; the result is a `Validated*` type |
| Spans the parts of one aggregate | the root's update function, or derive it on read |
| Spans aggregates in one context | a shared type or a shared pure function; otherwise eventual consistency |
| Spans contexts | events plus reconciliation or compensation |
| Changes often (zones, tariffs) | separate categorization (which zone) from calculation (what price) |
| Only an authority may assert it (verified email) | a wrapper with a private constructor that only the authority creates |

Flags or types:

| Situation | Choose |
|---|---|
| States differ in data or in allowed operations | one type per state plus a choice type |
| Two independent dimensions (VIP status, loyalty level) | one small choice type per dimension, as fields |
| Two states with the same data and no rule difference | a plain enum |

## 9. Evolving the model

Sources: Wlaschin ch. 13, unless marked.

- Change the model first, then follow the compiler errors in order. Do not
  silence them with defaults or wildcard matches on domain choice types.
- Add a pipeline stage (`PricedOrder -> PricedOrderWithShipping`) instead of
  editing a stable stage.
- **Store the inputs of business rules, not their outputs:** store VIP status,
  not a "free shipping" flag.
- Wrap a whole workflow with a function that keeps its signature (business
  hours only, retries, logging).
- When a sub-area grows its own vocabulary, team and data, move it to its own
  context. A view that needs data from several contexts is a new context fed by
  events.
- Expect many refactorings of the domain model as understanding grows
  (Granin ch. 3).
- Do not over-model. Wlaschin says that his type for every concept in one
  workflow (about 30 types) is a deliberate extreme (ch. 7). Give the core
  domain the full treatment and the rest a lighter one. For semistructured
  data or rule algebras, check other approaches first (maps for flexible
  data, an algebra-first design) (Wlaschin, preface).

## 10. Where the sources disagree

| Topic | Wlaschin | Ghosh | Crichton | Choose |
|---|---|---|---|---|
| dependencies | function parameters, partial application at the composition root | repository as Reader/Kleisli, supplied at the end | registry keyed by type for open sets | the same idea at different binding times: composition root for static wiring; an environment when many composed operations share it; a typed registry only for plugin-style open sets. Follow the project's effect system first |
| life-cycle states | one record per state plus a choice type | Option fields filled later plus phantom stage markers | typestate with consuming transitions | one type per state when states carry different data; phantom markers when only the order matters; consuming transitions only where the language enforces moves |
| events inside a context | no hidden in-process listeners; append the next step | events are central; event sourcing | not covered | no conflict: workflows return events as values; publishing and event sourcing happen at the edge |
| program as data (free monad) | not used | advanced, rarely used | not covered | plain functions or the project's effect abstraction, unless several interpreters are a real requirement |
| edges between contexts | a queue, or in a monolith a direct function call | an asynchronous boundary when contexts are apart in space or time | not covered | asynchronous between services; inside a modular monolith, either, through the context's public API |
| validation | Result with bind; an applicative to collect errors | applicative validation by default for independent checks | witness types after the check | accumulate at the boundary, return witness or constrained types, then fail fast inside the workflow |

## 11. Checklist and anti-patterns

Per feature:

- [ ] Every domain primitive has a type; every constrained type has a smart
      constructor.
- [ ] No flag or optional pair encodes a state or a rule.
- [ ] Each life-cycle state has its own type, and each step accepts only the
      state it needs.
- [ ] Entity IDs are stable; aggregate boundaries are agreed with the user.
- [ ] Each workflow is command in, events or error out; its effects show in
      its signature.
- [ ] Each rule lives in the place the table in section 8 gives.
- [ ] Laws and invariants are written down and tested as properties.

Anti-patterns:

- One record with boolean flags and optional fields for a whole life cycle
  (Wlaschin ch. 7).
- `isVerified: Bool` on an email; two optional contact fields (Wlaschin
  ch. 6).
- Embedding another aggregate instead of its ID; entity equality by all
  fields (Wlaschin ch. 5).
- Functions that return a plain type but can throw (Wlaschin ch. 8).
- Domain events raised inside a context with listeners (Wlaschin ch. 3).
- Sharing code or tables between contexts; one model for the whole company
  (Wlaschin ch. 1, 12).
- Mutable aggregates with setters (Ghosh ch. 1).
- Pattern matching outside the owning module on an aggregate whose
  representation is hidden behind smart constructors. Expose operations or a
  view type instead (Ghosh ch. 3 gives this warning for Scala and suggests
  extractors). This does not apply to public domain choice types (states,
  commands, events, errors): callers are meant to match those exhaustively
  (section 9).
- Over-encoding: type-level tricks that nobody can read for a low-risk rule
  (Crichton § 2.3).
