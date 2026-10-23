---
name: functional-design
description: "Design, review and refactor software in a functional style, from application architecture to algorithms: a pure core with a thin effectful shell, layers, eDSLs and interpreters, functional interfaces for dependency inversion (service handle, ReaderT, free monad, tagless final, effect systems), domain modeling with types, error domains, state, concurrency, resources, persistence, testable architecture, and functional algorithm and data-structure design. Use it whenever the user designs, plans, reviews or refactors an application, service, module or library in Haskell, Scala, F#, OCaml, Elm, PureScript, Clojure, Elixir, Kotlin with Arrow, TypeScript with fp-ts or Effect, or functional Rust; asks how to structure business logic, inject dependencies, model a domain, handle errors, effects or state, or make functional code testable; or needs an efficient purely functional algorithm or data structure, even if they never say 'functional design'. For Scala, use it together with scala-functional-design."
---

# Functional design and architecture

This skill helps you design, write and review programs in a functional style,
from the shape of a whole application down to the choice of an algorithm.

The main source is Granin, *Functional Design and Architecture* (2024). Other
sources: Wlaschin, Ghosh and Crichton (domain modeling with types); Normand and
Parsons (everyday simplicity, production practice); Hughes, Bird & Gibbons and
Bird (algorithm design); Okasaki and Stone (purely functional data structures).
`references/sources.md` lists them and explains the chapter citations.

## Before you start

- **Learn what the project already does.** Find the language and its version,
  the effect system, the architecture style, the error handling and the test
  tools. Follow sound existing conventions. The books disagree on some points
  (Granin prefers free monads; other authors prefer tagless final or an effect
  system). Present those choices as options with trade-offs. Do not impose one
  on a codebase that already uses another.
- **Translate the idea, not the syntax.** Sketches in this skill use a
  Haskell-like notation. Write the project's language and idioms.
- **Scale the design to the task.** A script needs pure functions and clear
  types, not layers and interpreters. Give the full treatment to the core of a
  product and a lighter one to supporting code. For one developer or a first
  version, prefer concrete handles in the base effect type to abstract monad
  parameters, and fewer, larger modules.
- **Keep external contracts.** A refactor does not silently change what
  callers send or receive (units, field names, status codes, error shapes).
  Convert at the boundary, or name the change as a separate decision.
- **For Scala, also use the `scala-functional-design` skill.** It maps these
  ideas to Scala 3 and to the project's effect system and libraries.

## Shape of the answer

This skill holds much more than one answer needs. Use it to decide well, not
to write more: a long answer costs the reader time and hides the decisions.

- **Match the size that the user asks for.** Words such as "short", "a
  sketch", "before I write any code" or "a quick review" set the size. A
  short design document has at most 300 lines; plan it at about 200-250
  lines, because a draft grows while you write it. Without such a word,
  give the smallest answer that a developer can act on.
- **Decisions first.** Start with the decisions and their reasons (a table
  helps), then the risks and open questions. Add only the detail that changes
  what the reader will do.
- **Show code once.** When you write code to files, do not paste those files
  again in the answer. Name the files, show the few lines that carry the
  idea, and say what you verified (compiled, tested, or not).
- **Sketch for design questions.** For an architecture or design request,
  give types, signatures and the key functions, not a full implementation,
  unless the user asks for one. When you do write code, write the core and
  its most important test completely; a short complete answer is better than
  a long one full of stubs.
- **Budget a rewrite.** When a review rewrites a file or a function, keep the
  result in proportion to what it replaces. For code of up to about 100
  lines, aim for at most about 400 lines of new code, at most about 250
  lines of tests, and a reply of at most about 120 lines. When the fixes
  add background workers (an outbox relay, a reconciler), their code may
  add up to about 150 lines more; write the workers in full, because they
  are fixes and their alerts live in that code. The fixes for high-risk
  findings come first in this budget, and they stay (see "Review
  checklist"). Cut ceremony, not fixes:
  - In an adapter, write in full only the code paths that a fix changes
    (the transaction with its outbox row, the lookup by the caller's key,
    the classification of errors, the relay and its alerts). Give the rest
    as signatures with a one-line comment: row mapping, plain reads, client
    setup, and adapters that no fix touches.
  - Make a signature fail loudly. Put it in a port that the composition
    root fills in, or give it a body that throws an error that names it
    (`throw new Error("not written: rowToOrder")`; Scala `???`). Do not
    `declare` a function that the code calls: the file compiles, and it
    fails only when it runs. List the parts that are not written in the
    reply.
  - Show the wiring and the resource lifetimes in a few lines.
  - Write a comment only where the code cannot say why. Add no section
    banners.
  - Rewrite the code that the user named. For other code, name the finding
    and the change in the reply.

  If the fixes do not fit in the budget, keep them, and say what made the
  rewrite longer.
- **Check against the references; do not recite them.** Cover the points
  that matter for this task. Name the others in one line as follow-ups.
- **Do not add scope.** Add no library, service, consumer or job that the
  user did not ask for, unless you name the problem that it solves. A fix
  for a high-risk finding in code that you rewrite is not added scope (see
  "Review checklist").

## Principles

These apply to every task. Each one has a reason. When the project makes a
principle hard to follow, keep the reason and adapt the form.

1. **Design manages accidental complexity.** The goal is to meet the goals and
   requirements with as little accidental complexity as possible. Before you
   add an abstraction, name the essential problem it solves. Treat each large
   decision as a trade-off between goals, requirements and simplicity.
2. **Pure core, thin effectful shell.** Keep domain logic pure and
   deterministic. Push I/O to the edges: load, decide with a pure function that
   returns the decision as a value, then act. Make the effectful layer as small
   as possible. Where the language does not track effects, enforce this through
   structure and review.
3. **Business logic depends only on interfaces.** Use three layers: business
   logic (scenarios), interfaces (languages, handles, algebras) and
   implementations (interpreters, runtime). One application layer, the entry
   point, builds the implementations and wires everything together. No other
   code imports implementations.
4. **Types carry the domain.** Model data with AND (records) and OR (sum
   types). Wrap meaningful primitives. Validate once with smart constructors.
   Make illegal states unrepresentable. Give each life-cycle state its own
   type. When an action is allowed only after a check, require proof of the
   check (a witness type).
5. **Workflows are pipelines.** A workflow takes a command and returns events
   or an error. Its steps are small functions with dependencies first and data
   last. Effects show in the signatures.
6. **Use the simplest interface that gives the decoupling you need.** Start
   with a record of functions (a service handle). Move to an interpretable
   language (free monad, effect handlers) only when you need strict effect
   restriction (no bypass), interpretations that a record of functions cannot
   give (a simulator, a dry run, record and replay of one sequenced program),
   or programs you can inspect. A service handle already gives a real and a
   test implementation. When a design chooses the lighter mechanism, write
   the trigger for the heavier one next to the choice ("move to X when Y",
   with a Y that handles do not meet). "No need now" is not a trigger.
7. **Errors are values; exceptions are for bugs.** Divide the program into
   error domains. Each boundary converts errors into the receiving domain's
   type. Implementations catch every exception from native libraries. Classify
   each error as domain, infrastructure or panic. An outage that you let
   propagate leaves the implementation as the application's own type
   (`StoreUnavailable`), so no handler, not even the HTTP edge, matches on a
   library type such as `SqlError`.
8. **State has a lifetime and a home.** Classify each piece of state by
   lifetime (local, operational, external) and by purity. Keep business state
   pure. Keep mutable operational state inside implementations. Use atomic or
   transactional primitives for shared state.
9. **Design concurrency up front.** Business logic does not fork raw threads.
   Bound concurrency in the runtime, tie cleanup to termination, prefer STM or
   atomic references to locks, and document what blocks. Give every
   long-running loop a stop path, and check that the process exits on a
   shutdown signal (a worker that loops forever next to a server that stops
   keeps the process alive). Also decide what a crashed worker does to the
   process: restart it under a supervisor, or end the process so that the
   orchestrator restarts it. A worker must not die silently while the
   server keeps serving.
10. **One resource, one scope.** Acquire each resource with its own bracket or
    scoped resource in the application layer, and nest them. Never write one
    function that initializes or releases several resources.
11. **Keep separate models at boundaries.** API DTOs, domain types and database
    entities are different types with explicit conversions. Validate input on
    the way in; drop private data on the way out.
12. **Testability is a property of the architecture.** Every external service
    is behind an interface, nothing bypasses it, every interface can be
    replaced in tests, and configuration comes from outside. Choose each kind
    of test by the risk that it reduces.
13. **Specify first, then optimize with laws.** Write the obvious correct
    version first. Keep it as a test oracle, derive the efficient version with
    laws, and compare the two with property-based tests.
14. **Use advanced types only where they pay.** Use type-level machinery only
    where the domain needs proof-level safety, and hide it inside framework
    layers. Do not generalize for cases that do not exist yet.
15. **Spend complexity and novelty like budgets.** Before you propose an
    advanced technique, state its cost, who will maintain it, and the simpler
    alternative. Follow the project's existing choices; do not add a second
    way to do one thing. Write code a newcomer can learn.
16. **An unknown outcome is not a failure.** When a call with an external side
    effect (a charge, a shipment, an email) times out, or its answer is
    ambiguous, the effect may have happened. Record the outcome as pending,
    find the truth (a status query, a reconciler, an idempotent retry), and
    compensate (cancel, refund) only after you know the effect failed. Retry
    only transient errors (timeouts, throttling, unavailability), with an
    idempotency key and a bound, and treat a status that you do not
    recognize as unknown, not as failed. Take the key from the caller (a
    client-supplied key or a stable natural key): a key made inside the call
    does not survive the caller's retry.

## Task guide

Read only the reference files that the task needs.

| Task | Start with | Reference |
|---|---|---|
| Design a new application or subsystem | Design workflow, below | `design-process.md`, `architecture.md` |
| Model a domain | Principles 4-5 | `domain-modeling.md` |
| Decide how business logic reaches effects (DI) | Interface table, below | `functional-interfaces.md` |
| Errors and validation, retries, remote side effects, outbox relays | Principles 7, 16 | `errors-and-validation.md` |
| State, concurrency, resources, logging | Principles 8-10 | `state-concurrency-resources.md` |
| Build or review concurrent constructs (locks, queues, futures, timeouts, races, pools, cancellation) | Principle 9 | `concurrency-constructs.md` |
| Persistence, HTTP APIs, serialization, CQRS | Principle 11 | `persistence-and-boundaries.md` |
| Testing strategy | Principle 12 | `testing.md` |
| Review or refactor existing code | Review checklist, below | `everyday-fp.md` for refactoring moves |
| Design or speed up an algorithm | Principle 13 | `algorithms.md` |
| Choose a data structure | | `data-structures.md` |
| Project organization, performance, teams | | `production.md` |

## Design workflow

Work from the top down. Go back to an earlier step whenever a later step
shows a problem; this is normal, not a failure.

1. **Goals and requirements.** Write the goals; the functional requirements as
   user scenarios (steps, preconditions, postconditions, alternative paths);
   the nonfunctional requirements (performance, availability, data volume,
   latency); what is out of scope; and the main risks. If a business rule is
   ambiguous, ask the user. Do not invent one.
2. **Domain discovery.** List the events, commands, workflows, external systems
   and read needs. Group them into bounded contexts, each with its own
   vocabulary. Keep a glossary and use its words in the code.
3. **Scenario sketches.** Write two or three key scenarios in the domain's
   words, as if the ideal API already existed. Try more than one form
   (sequential steps, a stream, an event handler) and keep the one that reads
   best. The sketch becomes the business-logic API.
4. **Components.** Name the subsystems that the scenarios need (storage,
   messaging, external services, logging, state, UI or API) and their
   relations. Draw them as a tree with no cycles, and record the technology
   choice for each one.
5. **Types.** Write the domain types, the state types and the function type of
   each workflow and step, with errors and effects, before any
   implementation. Use placeholder types for what is still unknown.
6. **Interfaces and layers.** For each subsystem, define its interface (one
   abstraction level), its implementations and its error type. Choose the
   interface mechanism with the table below. Decide which layer may use which
   capability.
7. **Cross-cutting decisions.** Decide the error domains and their
   conversions, where each piece of state lives and what a restart or a
   crash does to it (kept, rebuilt from stored data, or lost), the
   concurrency model, the resource scopes, the data models and their
   conversions, configuration loading and logging.
8. **End-to-end skeleton.** Connect all parts with fake implementations and
   write tests as the first consumer. Fix names, dependencies and models now,
   while they are cheap to change.
9. **Iterate.** Replace the fakes one at a time. Add tests by risk. Remove
   technology that does not pay for its complexity.

When you present a design, put the decisions first ("Shape of the answer"),
then use this outline and scale its depth to the request:

```markdown
## Goals and scope
## Domain model (types, states, workflows)
## Architecture (layers, components, dependency direction)
## Interfaces (for each subsystem: operations, errors, implementations)
## Effects, errors, state, concurrency and resources
## Data models and boundaries
## Testing strategy
## Trade-offs and open questions
```

## Choosing a functional interface

First classify the interface:

- **Scenario-like:** calls run in sequence and depend on earlier results. Use
  a monadic language, an effect type or an interpretable program.
- **API-like:** independent calls. Use a service handle, or a command type
  when the calls must be interpretable values.
- **Combinatorial:** small pieces combine into bigger pieces of the same kind
  (streams, parsers, validators, lenses). Use a combinator library.

| Mechanism | Use when | Costs |
|---|---|---|
| Module with abstract types and functions | one implementation; local encapsulation | no substitution at run time |
| Service handle (record of functions) | the default for services; API-like interfaces; easy mocks | does not restrict effects; many handles become hard to manage |
| Reader environment (ReaderT, Kleisli, an environment type) | many functions share the same dependencies | plumbing and lifting; little gain over one argument |
| Tagless final, mtl, capability type classes | the project's idiom; parametricity limits what code can do | type-based injection; domain and infrastructure capabilities can mix in one constraint list |
| Free monad or eDSL with interpreters | scenario-like interfaces that need several interpretations, strict effect restriction, inspection, or record and replay | boilerplate, interpretation overhead, learning curve |
| Command type (GADT) | an API-like interface that must be interpretable | no sequencing inside |
| Effect system with handlers or an environment | the project already uses it | ecosystem lock-in; keep domain capabilities separate from infrastructure ones |

Rules for every interface:

- Keep each interface at one abstraction level. Auxiliary concerns such as
  logging and metrics get their own interfaces.
- Never put a "run any effect" operation in a business-level interface. It
  defeats control, testing and thread safety.
- Put expected failures in the result type of each operation.
- Give every interface over effects or external systems at least two
  implementations: the real one and a test one. Run one contract suite
  against both, so the test one cannot drift from the real behavior
  (`testing.md`). A pure module needs no test double; test it directly.

`functional-interfaces.md` summarizes how Granin compares the mechanisms
(simplicity, boilerplate, convenience, testability), gives the other authors'
views, and has sketches of each mechanism.

## Review checklist

Use this list when you review or refactor code. For each finding, give the
location, the risk, and the smallest change that fixes it. Do not redesign the
architecture when a local fix is enough.

When you rewrite code that has a high-risk finding, fix the finding in the
rewrite. A new table, a transaction or an outbox that the fix needs is part
of the fix; write its migration too. Do not move a high-risk finding to a
list of follow-ups. Leave it open only when the fix needs a decision that is
not yours (a change to an external contract, or to a system that another
team owns). Then name it as an open risk, with the reason and what can go
wrong until it is fixed (practice). To stay in the rewrite budget ("Shape
of the answer"), cut ceremony, not fixes.

High risk:

- Business logic does I/O directly or through a "run anything" escape hatch.
- Native library types or exceptions reach business logic.
- Shared mutable state crosses threads without atomic or transactional
  primitives; forking is unbounded; code polls with sleeps.
- Resources are acquired without a bracket or scope, or several resources
  share one init or release function.
- Partial operations (`error`, `.get`, unchecked casts) handle expected
  failures.
- Unvalidated external input becomes domain values.
- Effects with different transactional semantics are interleaved (a cache or
  queue write inside a database transaction survives a rollback).
- An amortized data structure is reused from an old version, so its bound no
  longer holds.
- A timeout or an ambiguous answer from a side-effecting call is handled as a
  definite failure (the order is cancelled although the card may be charged);
  a retry has no idempotency key or no bound, or it also retries permanent
  errors (a decline, a validation error).
- The idempotency key is made inside the call (the ID of a new order, a
  random UUID), so a caller's retry after a lost response starts a second
  charge. Take a client-supplied key or a stable natural key, scope it to
  the caller (unique per customer and key), and return the earlier result
  for a repeated key.
- A follow-up effect (a shipment, an email, an event) is sent after the
  commit with no outbox, or its relay retries forever and only records the
  last error, so a lasting failure stays invisible. Keep transient failures
  pending with an alert on the oldest pending row; park only rejected rows,
  with an alert (`errors-and-validation.md`).
- A background loop has no stop path, so shutdown hangs or work is cut off
  in the middle; or the loop can die silently while the server keeps
  running.
- A database transaction stays open across a call to another system (a
  broker publish, an HTTP request).

Design smells:

- Boolean flags and optional fields encode states; one record serves every
  life-cycle stage.
- Primitive obsession; bare strings act as keys or references.
- A god interface; methods at mixed abstraction levels; an incomplete interface
  (save without load).
- Database or wire types in the domain; one type forced to serve reads and
  writes.
- Dependencies hidden in globals or containers; modules that import
  implementation details.
- Type-level machinery visible to business code; generality for cases that do
  not exist.
- Tests share mutable state; mocks replace what a pure function or an
  in-memory implementation could replace.
- An effectful function with no arguments or a unit result: it hides its
  inputs and outputs; decompose it ("invert your mocks").
- One monolithic application error type; errors that carry only a string.
- A second way to do something the project already does (a second effect
  system, logger or JSON library).

Refactoring moves, smallest first:

1. Extract calculations from actions; make implicit inputs and outputs
   explicit.
2. Replace a flag or an optional field with a sum type; add a wrapper type with
   a smart constructor.
3. Push I/O to the edges and return decisions as values.
4. Put a service handle around a native API and convert its errors at the
   boundary.
5. Split a god interface by responsibility; move auxiliary operations to their
   own interfaces.
6. Wrap each resource in its own scope; move initialization to the
   application layer.
7. Add a pipeline stage instead of editing a stable one.
8. Introduce an interpretable language only when the simpler moves are not
   enough.

## Reference files

- `references/design-process.md`: Granin's design method, requirements,
  scenario sketches, component diagrams, MVP and end-to-end design, modules and
  dependency rules.
- `references/architecture.md`: layers, the application layer and the
  runtime, eDSLs and interpreters, hierarchical languages, subsystem design,
  framework bridges.
- `references/functional-interfaces.md`: each dependency-inversion mechanism
  with sketches, how the authors compare them, mocking.
- `references/domain-modeling.md`: types, states, workflows, aggregates,
  bounded contexts, typed design patterns.
- `references/errors-and-validation.md`: error domains, error taxonomy,
  railway composition, accumulating validation, unknown outcomes and retry
  classification, idempotency keys from the caller, outbox relays.
- `references/state-concurrency-resources.md`: state placement, STM, actors,
  thread pools, timelines, resources, logging.
- `references/concurrency-constructs.md`: building and reviewing locks,
  channels, queues, futures, timeouts, races and pools; cancellation and
  masking; structured lifetimes; parallelism; translation to Cats Effect, ZIO
  and JVM direct style.
- `references/persistence-and-boundaries.md`: data models, DTOs, key-value and
  relational design, HKD, CQRS and event sourcing, HTTP edges.
- `references/testing.md`: testable architecture, test kinds by risk,
  property-based tests, mocks and test interpreters.
- `references/algorithms.md`: specification-first design, laws, fusion,
  greedy, thinning, dynamic programming, search.
- `references/data-structures.md`: persistence, amortization, a catalog of
  purely functional structures.
- `references/everyday-fp.md`: actions, calculations and data, explicit
  inputs and outputs, immutability in mutable languages, stratified design,
  first-class functions, onion and reactive architecture, types as a design
  tool, refactoring legacy code.
- `references/production.md`: complexity and novelty budgets, growing the
  application structure, three layers, small capabilities, inverting mocks,
  errors and logging in production, database operations as data, effect
  stacks, performance, type-level programming, compile times, teams.
- `references/sources.md`: the books, how the citations work, and a note on
  copyright.
