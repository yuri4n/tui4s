# Everyday functional code

Functional thinking at the level of functions and modules, in any language:
separating actions from calculations, keeping data immutable, layering
functions, and refactoring legacy code step by step. Sources: Normand,
*Grokking Simplicity* (2021, JavaScript); Skinner, *Effective Haskell* (2023).
For timelines and concurrency, see `state-concurrency-resources.md`.

## Contents

1. Actions, calculations and data
2. Make inputs and outputs explicit
3. Immutability in a mutable language
4. Stratified design
5. First-class functions and functional tools
6. Architecture: reactive and onion
7. Types as a design tool (Skinner)
8. Refactoring moves for legacy code

## 1. Actions, calculations and data

- **Classify all code** (Normand p. 36-40, 62):
  - **actions** depend on when or how many times they run (I/O, reading shared
    mutable state, the current time);
  - **calculations** map inputs to outputs and can run any time, any number of
    times;
  - **data** is inert, recorded facts.
- **Prefer data over calculations over actions.** Data can be serialized,
  compared and interpreted in many ways; its cost is that it must be
  interpreted (p. 38, 67).
- **Actions spread:** a function that calls an action is an action, up to
  `main`. Actions also hide in expressions: reading a global, assignment,
  `new Date()` (p. 85-87). Check the spreading rule whenever you add an effect.
- **Separate deciding from doing:** decisions and planning are calculations;
  the plan is data; a thin action executes it (p. 64-66, 76-82):

  ```js
  const planEmails = (subs, coupons) => subs.map(s => emailFor(s, coupons)); // calculation
  const sendAll = emails => emails.forEach(sendEmail);                      // the only action
  ```

  For big inputs, batch the action (page through subscribers) without
  changing the calculation. Avoid this when each decision needs the result of
  the previous effect.
- Use fewer actions, keep them small, keep them at the boundary, and make
  them depend less on time (p. 88).
- **Never put logging, metrics or I/O inside a pure function.** Logging "add
  to cart" inside a pure `addItem` makes it and all its callers actions, and
  logs hypothetical carts computed for display. Put it in the nearest action
  at the top (the click handler) (p. 246-248).
- Most software is distributed: messages arrive out of order, zero or many
  times. Data and calculations are immune; isolate actions and give them care
  (p. 41).

## 2. Make inputs and outputs explicit

- Explicit inputs are arguments; the explicit output is the return value.
  Everything else is implicit. **Testability and reusability have the same
  root:** both improve when implicit inputs and outputs become explicit
  (p. 95-99).
- **Extract a calculation** (p. 100-112): extract the code into a function;
  list its implicit inputs and outputs; turn inputs into arguments and outputs
  into return values; the caller keeps the effect.
- Do the same for actions that must stay actions: pass the global in, return
  the result, move the read to one top caller (p. 121-124).
- **Shape signatures around the business question:** `getsFreeShipping(cart)`,
  not `(total, itemPrice)` (p. 118-119).
- **Pull functions apart by what they know:** split a function that knows two
  structures (cart and item) so each part knows one; extract the generic
  algorithm under a generic name (p. 129-131). Keep together parts that always
  change together.
- Review signal (Parsons p. 89): an effectful function with **no arguments
  or a unit result** hides its inputs and outputs; decompose it.

## 3. Immutability in a mutable language

| | Copy-on-write | Defensive copying |
|---|---|---|
| Use for | data you own and modify | exchanges with untrusted or legacy code |
| Where | everywhere inside your safe zone | at its borders |
| Copy | shallow (cheap; shares structure) | deep (expensive; shares nothing) |
| Rule | copy, modify the copy, return it | deep-copy data going in and out |

(Normand p. 142-190.)

- Wrap copy-on-write operations in small utilities and always use them
  (p. 142, 175). Split read-and-write operations (`shift`) into a pure read
  and a non-mutating write (p. 152-154).
- **Nested data:** copy the changed leaf and every container up to the root;
  share everything else (structural sharing) (p. 170-173).
- **Swap:** one mutable reference points to the current immutable value;
  update it by read, compute, write back. Undo becomes easy (p. 162).
- Prefer persistent collections when the language has them. Copies cost time
  and memory; optimize only measured hot spots (p. 163).
- Use a vetted deep-copy facility, never a hand-rolled one (p. 190).

## 4. Stratified design

Draw the call graph down to language features. All arrows point down, and
each layer has a one-line purpose (business rules, cart operations, item
operations, copy-on-write operations, language) (Normand p. 197-230). Four
patterns:

1. **Straightforward implementation:** a function body that calls things at
   very different levels (a business rule plus a `for` loop plus array
   indexing) needs lower-level helpers so the body reads at one level. Do not
   just hide the mess in helpers; every layer must itself be straightforward
   (p. 205-229).
2. **Abstraction barrier:** a layer of functions that lets everything above it
   ignore one detail (the cart's representation); nothing above may reach
   below it. Use it for real uncertainty, bug-prone details, team decoupling
   or focus; never only because "it might change" (most data structures never
   change), and do not hide bad performance behind a clean interface
   (p. 234-241).
3. **Minimal interface:** if a feature can be written with a layer's existing
   operations, write it above the layer. Each addition grows the contract
   (p. 245-249).
4. **Comfortable layers:** stop adding layers when the team is comfortable;
   do not add them for sport (p. 250-251).

Position in the call graph (p. 252-259):

| Property | Rule | Consequence |
|---|---|---|
| maintainability | fewer functions above = easier to change | put frequently changing rules at the top |
| testability | more functions above = more valuable tests | test the bottom first; those tests last |
| reusability | fewer functions below = more reusable | extract general helpers downward |

## 5. First-class functions and functional tools

- **Smell: an implicit argument in a function name** (`setPriceByName`,
  `setQuantityByName`). Express the argument explicitly:
  `setFieldByName(cart, name, field, value)`, and check the field name with a
  type (enum, sum type, literal union) or a run-time allowlist (p. 264-272).
- **Replace body with callback:** for a repeated before / body / after shape
  around syntax that is not first-class (loops, try/catch, copy-then-return),
  extract the whole shape and pass the body as a function (p. 283-302).
  Normand derives his copy-on-write helper `withArrayCopy(array, modify)` this
  way. The same shape around try/finally:

  ```js
  const timed = (label, body) => {
    const t0 = Date.now();
    try { return body(); } finally { log(label, Date.now() - t0); }
  };
  ```

- Higher-order functions are "too pleasant to write": keep one only if it is
  clearer than the straightforward code (p. 317).
- **Chains of map, filter and reduce** over calculations: the whole expression
  is then a calculation. Name the callbacks (preferred) or the steps; take
  many small steps; debug by printing between steps; fuse steps by hand only
  for a measured bottleneck (p. 295-371).
- For `reduce`, choose the initial value by asking: where does the computation
  start, what should an empty input give, is there a business rule? Local
  mutation that never escapes is still a calculation (p. 338-344).
- **Fold over recorded operations:** record user operations as data and
  compute state with `reduce(ops, initial, apply)`. This gives undo, replay,
  time-travel debugging and an audit trail; it is the seed of event sourcing
  (p. 343, 375-377). Rebuild time grows with history; keep periodic snapshots
  when the log gets long (not in the book).
- **Nested updates:** `update(obj, key, f)` replaces get-modify-set;
  `nestedUpdate(obj, path, f)` recurses along a path. Hide long paths behind
  named domain operations once the model is stable; use lenses where the
  language has them (p. 358-417).

## 6. Architecture: reactive and onion

- **Onion architecture** (p. 557-569): an interaction layer (all actions that
  touch the world), a domain layer (calculations in domain terms) and a
  language layer (generic utilities). Calls point inward; inner layers know
  nothing of outer ones.
  - The database moves to the side: the top action fetches, calls the pure
    domain, and writes. In a classic layered stack with the database at the
    bottom, every layer becomes an action and needs a database to test
    (p. 560-563).
  - **Placement test:** domain rules use domain terms (product, price,
    discount). Technical logic (retry, fallback, caching, batching) belongs in
    the interaction layer (p. 565).
  - If a calculation needs data in the middle, fetch it first at the top and
    enrich the input (p. 567).
  - Pragmatism: when an action reads better than its calculation (language,
    libraries, legacy), accept it, isolate it in the interaction layer, and
    note the cleanup (p. 566).
- **Reactive architecture** (p. 540-553): invert "do X then Y" into "when X,
  do Y". One mutable cell holds the state, updated only with calculations
  that keep it valid; watchers react. A state with n causes and m effects goes
  from n × m edits to n + m. Use it only when there is a center of cause and
  effect; otherwise it hurts readability.
- These match Granin's pure layer and impure layer, Wlaschin's "pure core,
  I/O at the edges", and Parsons's three layers (`production.md`).

## 7. Types as a design tool (Skinner)

- **Stub with signatures first:** write the types of the functions with
  placeholder bodies, let the compiler check the plumbing, look at the stub
  types for existing library functions, then fill in the bodies (Skinner
  p. 125-129).
- **Alias, newtype or data:** an alias only for true synonyms or to fix a type
  parameter (`type AppResult = Either AppError`); a newtype (or opaque type)
  for each distinct meaning; `type Meters = Double` gives "a false sense of
  safety" (p. 169-173).
- **Never put case-specific fields on a record shared by a sum type:**
  selectors become partial and throw. Give each case its own record (p. 152-154).
- **Smart constructors:** hide the raw constructor; export a function that
  returns a result or an error; keep everything else total over the validated
  type (p. 213-216). Also make sure no other path bypasses the check (not in
  the book; its own `SortedList` example exports the field): in Haskell,
  export a getter function, not the record field, because record update needs
  only the field name; in Scala 2, a case class with a private constructor
  still has a public `copy` and `apply`; in Scala 3 they are private, and an
  opaque type has neither.
- **Phantom tags for permissions:** `User Anon` vs `User Authed`, with the tag
  types not exported, so only `authenticate` can create the privileged value;
  use a runtime check when states are many or data-dependent (p. 216-220).
- **Procedural shell, functional core, recursively:** each effectful function
  gathers input, calls pure logic, and acts on the result. Decode raw input
  once into small domain types (p. 304-345).
- **Laws are part of an interface.** The compiler does not check them; test
  them for every instance. Hidden effects (counters, logging) in "pure"
  operations break them (p. 371-380).
- **Decoding is parsing:** one primitive plus standard combinators (map,
  sequence, choice, repetition, fail); applicative for fixed shapes, monadic
  only when a later step depends on an earlier value (a tag, a length). Own
  the wire format: length-prefix variable fields, tag sum cases, and
  round-trip test every codec (p. 420-482).
- **Keep executables thin;** put the logic in a library that tests can import
  (p. 177).

## 8. Refactoring moves for legacy code

Improve one step per change (Normand p. 579-580):

- remove one global mutable variable;
- fix one race with a timeline diagram;
- extract one calculation from an action;
- make one implicit input or output explicit;
- replace one `for` loop with map, filter or reduce.

Two more moves in the same spirit (not from the book): replace one flag with
a sum type or one primitive with a wrapper type; put one native API behind a
small interface and convert its errors.

For larger moves, see the refactoring list in SKILL.md.
