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
  so the types are the documentation and cannot drift (Wlaschin p. 45, 76-78).
- Express behavior as function types:
  `type ValidateOrder = UnvalidatedOrder -> Either [ValidationError] ValidatedOrder`.
  The sketches use Haskell `Either err ok`; Wlaschin's F# `Result ok err`
  puts the success first.
- Write the types before the implementation. Use placeholder types (`Undefined`)
  for what is not known yet, so the model compiles early (p. 88-112).
- Keep separate types for concepts that the business names separately (billing
  and shipping address), even when their structure is the same today.
- Domain experts can read such types with little training (p. 112). Show them.
- Separate state from behavior: immutable data types plus modules of pure
  functions. The object-oriented question "which class owns `transfer`?"
  disappears (Ghosh p. 44-46).

## 2. Simple and constrained types

- **Wrap domain primitives:** `OrderId`, `CustomerId`, `UnitQuantity`, not
  `Int` or `String`. A wrapper stops mix-ups (Wlaschin p. 78, 90-93). In hot
  loops, measure first; a single wrapper around a whole array is an option.
- **Constrained types:** make the raw constructor private and expose
  `create :: Raw -> Either Error T` that checks range, format or length. Pass
  the field name for useful error messages. After creation the value is valid
  everywhere, so the context needs no defensive checks (p. 115-118).
- **Validate once at the boundary, then trust.** This is "parse, don't
  validate".
- **Units:** separate kilograms from pounds and seconds from milliseconds with
  distinct types (or units of measure where the language has them).
- **Smart constructors for aggregates** return an effect with the reason for
  failure. Use accumulating validation when the user must see every error, and
  fail-fast validation when later checks depend on earlier ones (Ghosh
  p. 111-114, 142-153).
- **Encapsulation choices** (Granin p. 113-114): everything public for stable
  plain data; hidden constructors plus smart constructors when invalid values
  are possible; an `Internal` module when tests need the representation.
- **Total functions: a signature must not lie** (Wlaschin p. 162-163). When a
  function can fail on some inputs, either **restrict the input** (a
  constrained type such as `NonZeroInteger`, when the caller can produce the
  valid value or it was validated at the boundary) or **extend the output**
  (an Option or Result, when the bad input is a normal outcome the caller must
  handle). Never leave the failure out of the type.

## 3. Make illegal states unrepresentable

Replace flags and optional fields with types that allow only legal
combinations (Wlaschin p. 118-123):

```haskell
-- "email OR postal address OR both": three cases, not two optionals
data ContactInfo
  = EmailOnly EmailContact
  | PostalOnly PostalContact
  | EmailAndPostal EmailContact PostalContact

-- a verified email can only be created by the verification service
data CustomerEmail = Unverified EmailAddress | Verified VerifiedEmailAddress
sendPasswordReset :: VerifiedEmailAddress -> IO ()
```

- List the legal cases, and make exactly those the constructors.
- Use a non-empty list for "at least one".
- A function that needs a stronger state takes the stronger type.
- The compiler then checks the rule; it needs no unit test.
- Encode each fact once. Two fields that can disagree allow invalid values
  (Granin p. 112). Remedies, in order (p. 112-114): (1) fix the model so the
  fact is stored once; (2) if reworking the model is not wanted, hide the
  constructors and allow only smart constructors that never return an
  invalid value; (3) never "hope colleagues do not build invalid values".
  Wrong data that users enter is a logic error that types cannot prevent;
  validate it.
- Do not encode rules that are dynamic data (limits that change at run time),
  and stop when the encoding makes the model unreadable. Use a smart
  constructor or a check in the aggregate instead.

## 4. Life cycles and state machines

- **One type per state** with only that state's data, and a choice type for the
  whole entity. Transitions are functions from one state to the next state or
  to an error (Wlaschin p. 132-138):

  ```haskell
  data Cart = Empty | Active ActiveCart | Paid PaidCart
  addItem :: Item -> Cart -> Either CartError Cart   -- adding to a paid cart is an error
  ```

- Pipeline steps take and return specific states, so the stages cannot run in
  the wrong order. Never give a later stage's field a default value in an
  earlier stage.
- Ask edge-case questions for each state: verify an email that is already
  verified? Remove an item from an empty cart?
- **Phantom marker types** (`Loan Applied`, `Loan Approved`) suit linear life
  cycles where every state carries the same data (Ghosh p. 169-172). When the
  states carry different data, use one type per state.
- Crichton's **typestate** adds one more step: each transition consumes the old
  value, so a stale state cannot be used again. Crichton gets the
  invalidation from Rust ownership (p. 4). Without move or linear types the
  guarantee is weaker, so say so, and add a run-time guard if misuse is likely
  (our inference).

## 5. Entities, values, aggregates

- **Value object:** no identity, immutable, equal by content.
- **Entity:** an identity that persists while its data changes. Updates return
  a new copy with the same ID. Compare entities by ID, not by all fields; put
  the ID inside each case of a choice-type entity (Wlaschin p. 99-105).
- Whether something is an entity depends on the context (Ghosh p. 32, 34).
- **Aggregate:** a consistency boundary, a unit of persistence and a unit of
  transfer. Update only through the root. Refer to other aggregates by ID.
  Load, save, transact and serialize whole aggregates (Wlaschin p. 105-109).
- Prefer deriving values (a total) over storing them. If you store one,
  recompute it in the root's update function.
- If two aggregates always change in one transaction, look for a hidden entity
  (a `MoneyTransfer`) (p. 127-128). "One aggregate per transaction" is a
  guideline: when the business sees the work as one transaction, both
  aggregates may join it, but the transaction usually deserves its own ID and
  entity. Define a new aggregate for a use case rather than reusing one only
  because it exists (p. 127-128).
- Keep aggregates thin; use lenses or optics for deep immutable updates, and
  expose them only at the root (Ghosh p. 114-122).
- **Prefer eventual consistency between aggregates and contexts.**
  Consistency is a business decision, so ask what level the business needs
  (Wlaschin p. 124-125). Use events, then reconciliation or compensation.
  Avoid distributed transactions. Default to one aggregate per transaction,
  with the exception given above (p. 127-128).

## 6. Workflows

- A workflow is one function: `Command -> Either Error [Event]`, plus the
  project's async or IO type when it does I/O. It returns events; it does not
  publish them. Do not raise domain events inside a context with hidden
  listeners; add the follow-up as the next step (Wlaschin p. 28, 64-66).
- Steps are stateless, with dependencies first and data last (see
  `functional-interfaces.md`, section 5).
- Commands carry metadata (user, time) through a generic `Command data`;
  commands from one input channel form a choice type routed at the edge.
- **Effects in signatures:** a step that can fail returns a Result; a step that
  does I/O returns the async type. Name an alias for the combination. A step
  that is "obviously safe" may still need a Result (a negative total)
  (p. 144-146).
- Do not return a bare boolean where the result matters; return a two-case
  type (`Sent | NotSent`) (p. 142).
- **Pure core:** load the data, call a pure function that returns a decision
  as a value (`FullyPaid | PartiallyPaid Invoice`), then act on the decision
  (p. 247-251).
- **Long-running workflows (sagas):** when a step waits for a person or a slow
  system, persist the state between steps and model each wake-up as a
  transition. Add a process manager when routing grows (p. 150-152).
- Compose effectful steps as a pipeline (Kleisli composition) so the code
  reads like the specification (Ghosh p. 164-168).
- **Organize a context by vertical slices,** one per workflow with all the
  code it needs, each arranged as an onion (domain at the center, I/O only at
  the start or end). Horizontal layers make one workflow change touch every
  layer; do not add a "service layer" that every workflow must pass through
  (Wlaschin p. 66-68).
- **Serialization is the outermost stage:** JSON to DTO to domain, then the
  workflow, then domain to DTO to JSON. The infrastructure sees only bytes or
  strings, never domain types (Wlaschin p. 231).
- **Commands and events differ** (Ghosh p. 64-66):

  | | Command (`Debit`) | Event (`DebitOccurred`) |
  |---|---|---|
  | time | before the effect | after the effect |
  | handlers | usually one | any number of subscribers |
  | can fail? | yes, with a typed error | no; it already happened |
  | effect | changes state | notification only |

  Name commands as imperatives and events in the past tense. An event is
  immutable and self-contained; a consumer cannot "reject" it, only react to
  it or issue a compensating command.
- **Decide for each edge between contexts whether it is an asynchronous
  boundary.** It is one when the contexts are not collocated, run under
  different time constraints (a nightly job and a 24/7 service), or have
  different models (Ghosh p. 221-222). Say which side owns the message format
  (consumer-driven or conformist). Inside a modular monolith, a queue and a
  direct call through the context's public API are both acceptable, provided
  the contexts stay decoupled (Wlaschin p. 59).

## 7. Typed design patterns (Crichton)

A pattern maps a domain concept to language features so that misuse becomes a
compile error. Pick the least complex encoding that makes the dangerous
mistakes impossible (Crichton p. 2, 6).

| Situation | Pattern | Needs |
|---|---|---|
| An action is allowed only after a check (permission, validated input) | **Witness:** a proof value that only the checking function can create; the action requires it | private constructor, module privacy |
| An object has ordered states and legal transitions | **Typestate:** one type per state, or a state marker parameter; transitions return the next state | move semantics or linear types for the full guarantee |
| Two sequences must stay aligned (format holes and arguments, route parameters and handler parameters) | **Parallel lists:** a type-level list | type-level lists, recursive type classes |
| An open set of names with typed payloads (events, plugins) | **Registry keyed by type** instead of by string; dynamic typing hidden in one module | type identity at run time |
| A fixed small set of names | a sum type | sum types |

- A witness proves that the check happened, not that the condition still
  holds. If the condition can change, include the session or time in the
  witness, or check again. (Our caveat; the paper, p. 2-3, does not discuss
  staleness.)
- Type-level encodings produce hard error messages. Weigh that against the
  risk, and keep the readable version where mistakes are harmless (p. 6).

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

- Change the model first, then follow the compiler errors in order. Do not
  silence them with defaults or wildcard matches on domain choice types
  (Wlaschin p. 272-289).
- Add a pipeline stage (`PricedOrder -> PricedOrderWithShipping`) instead of
  editing a stable stage.
- **Store the inputs of business rules, not their outputs:** store VIP status,
  not a "free shipping" flag (p. 277).
- Wrap a whole workflow with a function that keeps its signature (business
  hours only, retries, logging).
- When a sub-area grows its own vocabulary, team and data, move it to its own
  context. A view that needs data from several contexts is a new context fed by
  events.
- Expect many refactorings of the domain model as understanding grows
  (Granin p. 112).
- Do not over-model: about 30 types for one workflow was a deliberate extreme
  in Wlaschin's book (p. 152). Give the core domain the full treatment and the
  rest a lighter one. For semistructured data or rule algebras, check other
  approaches first (maps for flexible data, an algebra-first design) (p. 13).

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
  (Wlaschin p. 133).
- `isVerified: Bool` on an email; two optional contact fields (p. 119-122).
- Embedding another aggregate instead of its ID (p. 107-108).
- Entity equality by all fields (p. 102-104).
- Functions that return a plain type but can throw (p. 162-163).
- Domain events raised inside a context with listeners (p. 66).
- Sharing code or tables between contexts; one model for the whole company
  (p. 31, 36, 256-257).
- Mutable aggregates with setters (Ghosh p. 41-42).
- Pattern matching outside the owning module on an aggregate whose
  representation is hidden behind smart constructors. Expose operations or a
  view type instead (Ghosh p. 116, a Scala warning that suggests extractors).
  This does not apply to public domain choice types (states, commands,
  events, errors): callers are meant to match those exhaustively (section 9).
- Over-encoding: type-level tricks that nobody can read for a low-risk rule
  (Crichton p. 6).
