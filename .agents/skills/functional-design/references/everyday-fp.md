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

- **Classify all code** (Normand ch. 1, 3):
  - **actions**: their result or effect changes with the moment they run or
    the number of runs (I/O, reading shared mutable state, the current time);
  - **calculations**: they compute outputs from inputs only, so you can run
    them at any time, any number of times;
  - **data**: inert facts that record events.
- **Prefer data over calculations over actions.** Data can be serialized,
  compared and interpreted in many ways; its cost is that it must be
  interpreted.
- **Actions spread:** a function that calls an action is an action, up to
  `main`. Actions also hide in expressions: reading a global, assignment,
  `new Date()`. Check the spreading rule whenever you add an effect.
- **Separate deciding from doing:** decisions and planning are calculations;
  the plan is data; a thin action executes it (Normand ch. 3):

  ```js
  const planReminders = (invoices, today) =>
    invoices.filter(i => isOverdue(i, today)).map(reminderFor); // calculation
  const sendAll = reminders => reminders.forEach(send);           // the only action
  ```

  For big inputs, batch the action (page through the invoices) without
  changing the calculation. Avoid this when each decision needs the result of
  the previous effect.
- Use fewer actions, keep them small, keep them at the boundary, and make
  them depend less on time.
- **Never put logging, metrics or I/O inside a pure function.** A log call
  turns the function and all its callers into actions, and it also logs
  values that were computed only for a preview. Put it in the nearest action
  at the top (the click handler) (Normand ch. 9).
- Most software is distributed: messages arrive out of order, zero or many
  times. Data and calculations are immune; isolate actions and give them care
  (Normand ch. 1).

## 2. Make inputs and outputs explicit

Sources: Normand ch. 4-5, unless marked.

- Arguments are the explicit inputs and the return value is the explicit
  output; every other input or output is implicit. **Testability and
  reusability have the same root:** both improve when implicit inputs and
  outputs become explicit.
- **Extract a calculation:** extract the code into a function; list its
  implicit inputs and outputs; turn inputs into arguments and outputs into
  return values; the caller keeps the effect.
- Do the same for actions that must stay actions: pass the global in, return
  the result, move the read to one top caller.
- **Shape signatures around the business question:**
  `isEligibleForRefund(order)`, not `(paidAt, amount)`.
- **Pull functions apart by what they know:** split a function that knows two
  structures (an order and a line item) so each part knows one; extract the
  generic algorithm under a generic name. Keep together parts that always
  change together.
- Review signal (Parsons ch. 7): an effectful function with **no arguments
  or a unit result** hides its inputs and outputs; decompose it.

## 3. Immutability in a mutable language

Choose the copy discipline by who owns the data (Normand ch. 6-7, the source
of this section):

- **Copy-on-write** for data that your own code owns and changes. Never
  change it in place: make a shallow copy (cheap, and it shares the unchanged
  parts), change the copy, return it. Apply this everywhere inside the code
  that you control.
- **Defensive copying** where data crosses to or from code that you do not
  trust (legacy modules, third-party libraries). Make a deep copy on the way
  in and on the way out, so neither side can change the other's data. A deep
  copy shares nothing and costs more, so use it only at those borders.

Further rules:

- Wrap copy-on-write operations in small utilities and always use them.
  Split read-and-write operations (`shift`) into a pure read and a
  non-mutating write.
- **Nested data:** copy the changed leaf and every container up to the root;
  share everything else (structural sharing).
- **Swap:** one mutable reference points to the current immutable value;
  update it by read, compute, write back. Undo becomes easy.
- Prefer persistent collections when the language has them. Copies cost time
  and memory; optimize only measured hot spots.
- Use a vetted deep-copy facility, never a hand-rolled one.

## 4. Stratified design

Draw the call graph down to language features. All arrows point down, and
each layer has a one-line purpose, for example business rules, then order
operations, then line-item operations, then copy-on-write helpers, then the
language itself (Normand ch. 8-9). Four patterns:

1. **Straightforward implementation:** a function body that calls things at
   very different levels (a business rule plus a `for` loop plus array
   indexing) needs lower-level helpers so the body reads at one level. Do not
   just hide the mess in helpers; every layer must itself be straightforward
   (Normand ch. 8).
2. **Abstraction barrier:** a layer of functions that lets everything above it
   ignore one detail (how an order is stored); nothing above may reach
   below it. Use it for real uncertainty, bug-prone details, team decoupling
   or focus; never only because "it might change" (most data structures never
   change), and do not hide bad performance behind a clean interface
   (Normand ch. 9, also for items 3 and 4).
3. **Minimal interface:** if a feature can be written with a layer's existing
   operations, write it above the layer. Each addition grows the contract.
4. **Comfortable layers:** stop adding layers when the team is comfortable;
   do not add them for sport.

Use the position of a function in the call graph to place code (Normand
ch. 9):

- Put rules that change often near the top. A function with few functions
  above it is cheap to change.
- Test the bottom first. Much code depends on a low function, so its tests
  protect more and stay valid longer.
- Push general helpers down. A function with little beneath it is easy to
  reuse.

## 5. First-class functions and functional tools

- **Smell: an implicit argument in a function name** (`setEmailById`,
  `setPhoneById`). Express the argument explicitly:
  `setFieldById(contacts, id, field, value)`, and check the field name with a
  type (enum, sum type, literal union) or a run-time allowlist
  (Normand ch. 10).
- **Replace body with callback:** for a repeated before / body / after shape
  around syntax that is not first-class (loops, try/catch, copy-then-return),
  extract the whole shape and pass the body as a function (Normand
  ch. 10-11). A copy-on-write helper that takes the change as a callback
  comes from this move. The same shape around try/finally:

  ```js
  const timed = (label, body) => {
    const t0 = Date.now();
    try { return body(); } finally { log(label, Date.now() - t0); }
  };
  ```

- Normand warns that higher-order functions are "too pleasant to write":
  keep one only if it is clearer than the straightforward code
  (Normand ch. 11).
- **Chains of map, filter and reduce** over calculations: the whole expression
  is then a calculation. Name the callbacks (preferred) or the steps; take
  many small steps; debug by printing between steps; fuse steps by hand only
  for a measured bottleneck (Normand ch. 12-13).
- For `reduce`, choose the initial value by asking: where does the computation
  start, what should an empty input give, is there a business rule? Local
  mutation that never escapes is still a calculation (Normand ch. 12).
- **Fold over recorded operations:** record user operations as data and
  compute state with `reduce(ops, initial, apply)`. This gives undo, replay,
  time-travel debugging and an audit trail; it is the seed of event sourcing
  (Normand ch. 12-13). Rebuild time grows with history; keep periodic
  snapshots when the log gets long (practice).
- **Nested updates:** `update(obj, key, f)` replaces get-modify-set;
  `nestedUpdate(obj, path, f)` recurses along a path. Hide long paths behind
  named domain operations once the model is stable; use lenses where the
  language has them (Normand ch. 14).

## 6. Architecture: reactive and onion

Sources: Normand ch. 18.

- **Onion architecture:** an interaction layer (all actions that touch the
  world), a domain layer (calculations in domain terms) and a language layer
  (generic utilities). Calls point inward; inner layers know nothing of outer
  ones.
  - The database moves to the side: the top action fetches, calls the pure
    domain, and writes. In a classic layered stack with the database at the
    bottom, every layer becomes an action and needs a database to test.
  - **Placement test:** domain rules use domain terms (product, price,
    discount). Technical logic (retry, fallback, caching, batching) belongs in
    the interaction layer.
  - If a calculation needs data in the middle, fetch it first at the top and
    enrich the input.
  - Pragmatism: when an action reads better than its calculation (language,
    libraries, legacy), accept it, isolate it in the interaction layer, and
    note the cleanup.
- **Reactive architecture:** invert "do X then Y" into "when X, do Y". One
  mutable cell holds the state, updated only with calculations that keep it
  valid; watchers react. A state with n causes and m effects goes from n × m
  edits to n + m. Use it only when many causes and many effects meet at one
  piece of state; otherwise it hurts readability.
- These match Granin's pure layer and impure layer, Wlaschin's "pure core,
  I/O at the edges", and Parsons's three layers (`production.md`).

## 7. Types as a design tool (Skinner)

- **Stub with signatures first:** write the types of the functions with
  placeholder bodies, let the compiler check the plumbing, look at the stub
  types for existing library functions, then fill in the bodies (Skinner
  ch. 3).
- **Alias, newtype or data:** an alias only for true synonyms or to fix a type
  parameter (`type AppResult = Either AppError`); a newtype (or opaque type)
  for each distinct meaning. Skinner notes that `type Meters = Double` gives
  "a false sense of safety" (Skinner ch. 4).
- **Never put case-specific fields on a record shared by a sum type:**
  selectors become partial and throw. Give each case its own record
  (Skinner ch. 4).
- **Smart constructors:** hide the raw constructor; export a function that
  returns a result or an error; keep everything else total over the validated
  type (Skinner ch. 5). Also make sure no other path bypasses the check
  (practice; the book's own `SortedList` example exports the field): in
  Haskell, export a getter function, not the record field, because record
  update needs only the field name; in Scala 2, a case class with a private
  constructor still has a public `copy` and `apply`; in Scala 3 they are
  private, and an opaque type has neither.
- **Phantom tags for permissions:** `User Anon` vs `User Authed`, with the tag
  types not exported, so only `authenticate` can create the privileged value;
  use a runtime check when states are many or data-dependent (Skinner ch. 5).
- **Procedural shell, functional core, recursively:** each effectful function
  gathers input, calls pure logic, and acts on the result. Decode raw input
  once into small domain types (Skinner ch. 8).
- **Laws are part of an interface.** The compiler does not check them; test
  them for every instance. Hidden effects (counters, logging) in "pure"
  operations break them (Skinner ch. 9).
- **Decoding is parsing:** one primitive plus standard combinators (map,
  sequence, choice, repetition, fail); applicative for fixed shapes, monadic
  only when a later step depends on an earlier value (a tag, a length). Own
  the wire format: length-prefix variable fields, tag sum cases, and
  round-trip test every codec (Skinner ch. 11-12).
- **Keep executables thin;** put the logic in a library that tests can import
  (Skinner ch. 5).

## 8. Refactoring moves for legacy code

Improve one step per change. Each move below fits in one small review
(Normand ch. 19, except the last two, which come from practice):

- Shrink an action: extract one calculation from it, or make one of its
  implicit inputs or outputs explicit.
- Remove one source of shared-state bugs: one global mutable variable, or one
  race that a timeline diagram shows.
- Replace one `for` loop with map, filter or reduce.
- Replace one flag with a sum type, or one primitive with a wrapper type
  (practice).
- Put one native API behind a small interface and convert its errors
  (practice).

For larger moves, see the refactoring list in SKILL.md.
