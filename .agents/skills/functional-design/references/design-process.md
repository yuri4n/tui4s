# Design process

How to go from requirements to a functional architecture. Mostly Granin's
"functional declarative design" (FDD), with Wlaschin's domain discovery.

## Contents

1. What design is for
2. Requirements and domain discovery
3. From scenarios to languages
4. Components and architecture diagrams
5. MVP and end-to-end design
6. Modules and the dependency rule
7. Quality checks for an architecture
8. Process mistakes

## 1. What design is for

Sources: Granin ch. 1, unless another chapter is named.

- **Definition.** Design decides, on purpose and toward stated goals, the
  structure that will satisfy the business requirements. Record its results as
  decisions, diagrams, interfaces and the top-level code layout.
- **Main task.** Spend complexity only on the problem itself. Essential
  complexity comes from the domain. Accidental complexity comes from tools,
  tricks and hard solutions that nobody justified. Reduce it, but never by
  dropping a goal or a requirement. Too few abstractions couple the code; too
  many overdesign it.
- **Three factors to balance:** goals accomplished, requirements met,
  simplicity kept. Treat design as risk management: a risk is low when the
  problem is cheap to solve. For each significant decision, say which factor
  it serves and what it costs.
- **FP is not a guarantee.** Functional code can be as coupled and as
  overdesigned as object-oriented code. The usual principles apply in
  functional form:

  | Principle | Functional form |
  |---|---|
  | SRP | one theme per module, one responsibility per language or service |
  | ISP | small export lists, small languages, small handles |
  | DIP / IoC | business logic depends on a functional interface, never on an implementation |
  | LSP | every implementation honors the interface's types: no hidden exceptions, no undeclared effects |
  | OCP | add a vendor or a backend as data or as a new implementation, not by editing the core |

- **The FP-specific principle.** Look for the algebraic properties of domain
  types (monoid, functor, foldable, monad). A lawful instance brings the whole
  library of that abstraction with it. Only use lawful instances.
- **What each FP property buys:**

  | Property | Buys | Costs |
  |---|---|---|
  | Immutability | no bugs from shared mutable state | some mutation is still needed; keep it local |
  | Purity, determinism | predictable layers, easy tests | effects move to a thin top layer; without effect tracking this is discipline only |
  | Effects in types | the compiler confines impurity | impurity spreads to every caller up to `main` |
  | Strong static types | errors at compile time, safe large refactorings | type-level design is hard; use it where it pays |
  | ADTs | domain models that non-programmers can read | many small types |
  | eDSLs | the domain's essence without accidental complexity | a bad DSL restricts and annoys its users |

- **Levels of architecture** (Granin ch. 2). Enterprise (many products),
  solution (many services as one product: monolith or microservices, brokers,
  CQRS, data flow) and application (one service: layers, subsystems,
  interfaces). The paradigm matters most at the application level. Mistakes
  in architecture decisions cost the most.
- **Choose the least powerful tool** from the functional toolbox, and write
  down why you did not choose a stronger one (Granin ch. 1-2). The table goes
  from light to heavy:

  | Tool | Use when | Cost |
  |---|---|---|
  | Lenses / optics | deep updates in nested immutable records | one more vocabulary; generated lenses hide code |
  | Metaprogramming | boilerplate that a generator can write | you cannot see the generated code |
  | Monad transformers / effect stacks | one block needs several effects | lifting noise; give each function the smallest stack it needs |
  | Laziness | large transformations of which only a part is used | the cost is hard to predict; it needs experience |
  | Reactive streams | event streams to map, merge and split | (practice) backpressure, error and termination rules to learn |
  | STM | shared concurrent state; transactions must compose | (practice) no I/O inside a transaction; retries under contention |
  | FRP | time-varying values whose dependents update (GUIs) | Granin removed it from his design because it did not pay for its complexity (Granin ch. 3) |
  | Phantom, existential, rank-N types, GADTs, type-level logic | the compiler must check a rule on every build | type-level design has no established engineering method yet |

- **Contracts the compiler cannot enforce.** An interface promises a behavior
  contract: no hidden exceptions, no global mutation, no file access outside
  the contract. Put as much of it as possible in types (error results, effect
  types); list the rest in the review checklist. In languages without effect
  tracking (Scala, F#), a function that returns a plain type can still do I/O;
  keep "effects only in the effect type" as a review rule.
- **Translate an object-oriented design element by element** before you
  invent a new mechanism (Granin ch. 1, 3-4):

  | Object-oriented element | Functional counterpart |
  |---|---|
  | interface with implementations | record of functions (service handle), or a language with interpreters |
  | constructor injection | the function takes the handle as an argument |
  | composition root | top-level wiring (`main`, test setup) |
  | adapter | a wrapper function or handle value |
  | command, strategy | higher-order functions and closures |
  | factory method | smart constructor |
  | class encapsulation | module export list, abstract (opaque) types |
  | stateful object holding connections | descriptive domain types plus an implementation runtime passed from outside |
  | mock object | fake functions that return the real result types |

## 2. Requirements and domain discovery

Collect requirements before you design (Granin ch. 1-3).

- **Questions and answers** find out what the customer wants, but give weak
  structure.
- **User scenarios** list steps, preconditions, postconditions and alternative
  paths. They are concrete, and the customer can check them.
- **Mind maps** are good for brainstorming: one central notion, levels of
  detail, cross-links. Keep each map small (Granin ch. 2).
- Write the **nonfunctional requirements** (performance, availability, data
  volume, latency) and what is explicitly **out of scope**.
- Review the **project risks** at the start: budget, changing or misunderstood
  requirements, time, overcomplicated code, wrong tools.

Domain discovery (Wlaschin ch. 1-2, unless another chapter is named):

- Start from **business events and workflows**, not from tables or classes.
  Events are past-tense facts; commands are requests.
- **Event storming:** put everyone who has questions or answers together. Map
  events on a timeline, then add commands, workflows, external systems and
  reporting needs. Follow the events to the edges. Write open questions down.
- Describe each workflow with a short template like this one:

  ```text
  workflow "Book appointment"
    triggered by:   BookingRequested
    primary input:  booking request
    other input:    clinician calendar, patient registry
    output events:  AppointmentBooked, ReminderScheduled
    side effects:   confirmation message to the patient
  ```

- **Bounded contexts.** One context per area of coherent knowledge, with its
  own dialect of the shared vocabulary, an explicit API (commands in, events
  out) and its own storage (storage ownership: Wlaschin ch. 12). The same word
  ("Order") can mean different data in two contexts.
- **Context map.** Show direction (upstream, downstream) and the relationship:
  shared kernel, customer/supplier (consumer-driven contract), conformist, or
  anti-corruption layer.
- **Where to spend effort.** Core domain first; supporting and generic domains
  get simpler treatment. Start as a modular monolith; split into services only
  when the boundaries are stable (Wlaschin ch. 3).
- Keep a **glossary**. Use its words for types and functions. Avoid Manager,
  Helper and Factory names that a domain expert would not recognize.
- If a rule is unclear, ask the expert (the user). Record open questions as
  explicit placeholder types in the model.

## 3. From scenarios to languages

Granin's "mnemonic analysis" (Granin ch. 3):

1. Write two or three key scenarios in the users' words, in a readable
   pseudo-language: header, description, inputs, steps, result.
2. Rewrite the same behavior in other forms: sequential steps, a stream of
   events, a monitor that reacts to changes.
3. Keep the form that reads most naturally and that suggests the best
   functional idioms. It becomes the shape of the business-logic language.

Heuristics:

- Granin gives every domain model a formal representation, which he calls an
  eDSL. In his broad sense, a set of domain ADTs and functions over them
  already counts (Granin ch. 3, 5). It also documents the domain. Build an
  interpreted language only when SKILL.md Principle 6 calls for one.
- Look for recursive structure (a component that contains components), and
  note what the type allows that the world does not.
- A scenario that looks imperative may read better as a stream or a state
  machine. Try before you decide.
- Know the users' mental model. Engineers who write scripts may prefer
  statement-like languages. That is still declarative, because a script does
  nothing until an interpreter runs it (Granin ch. 5).

## 4. Components and architecture diagrams

Granin uses three informal diagrams (Granin ch. 3). Text or Mermaid is fine.
Use them as thinking tools, not as goals.

| Diagram | Purpose | Rules |
|---|---|---|
| Necessity diagram | the large parts that the system needs | take components from the requirements; relations are "uses" and "interacts with" |
| Elements diagram | brainstorm how to build one component | every kind of element is welcome and equal (concepts, libraries, layers, data); loose elements are allowed; each new element should bring a new requirement or idea |
| Architecture diagram | the final structure | a tree with no cycles and no isolated elements; each component shows its name, its parts and its implementation stack |

- A **component** is physical (a library or a project with one function and an
  interface) and can span layers. A **layer** is logical.
- If a central component would talk to every other component, put an
  interaction abstraction between them: a bus, a queue, streams or actors.
  Choose the lightest one. Granin removed FRP from his design because it did
  not pay for its complexity.
- Remove technology that does not pay for its complexity.

## 5. MVP and end-to-end design

Sources: Granin ch. 3-4, unless another chapter is named.

- **Integrate early.** Parts that teams build with no shared architecture and
  no early integration do not fit together (Granin's "Frankenstein's
  monster"). An MVP is a real program with a small but useful feature set,
  and later work builds on it.
- **Shape before content.** Build the whole path first (definitions,
  interfaces, implementation, a consumer) with fake content. Do not polish one
  subsystem to production quality while others do not exist.
- **Tests are the final consumer.** If there is no real client yet, tests and
  simulators are the consumers. They show wrong dependencies, poor names and
  model inconsistencies while these are cheap to fix. Granin recommends a
  relaxed TDD: tests shape the design, but strict test-first can be too
  limiting.
- **List the open questions** after the naive version (who calls this, who
  initializes it, where its state lives). They drive the next design step.
- **Start simple.** For an MVP, a module with abstract types and functions, or
  an ADT with an interpreter, is enough. Do not skip interfaces.
- **Refactor early.** Strong static types make large refactorings safe. Expect
  several rewrites of the domain model as understanding grows.
- **Phase deliverables** (Granin ch. 2). Use the classic phases only as a list
  of activities and their artifacts, then iterate. For a new service, produce
  at least: a requirements list including the nonfunctional ones, an
  architecture sketch, the top-level types and interfaces, and tests for both
  kinds of requirements. Go back to earlier artifacts when something changes.

## 6. Modules and the dependency rule

Module patterns (Granin ch. 3, unless another chapter is named):

- **Smart constructors and abstract types.** Export the type without its
  constructors, and export functions that build only valid values. Keep
  stable plain data public: when no value can be invalid, exporting
  everything is the simplest choice.
- **Internal module.** Put the full representation in `X.Internal` for tests
  and experts, with no stability promise. Never re-export it.
- **Top re-exporting module.** Give each context one module that only
  re-exports its parts. Clients and tests import that module, so internal
  moves do not break them.
- **Implementation types apart from behavior.** Put implementation types in a
  module such as `X.Impl.Types`, and their behavior in sibling `Impl`
  modules. That types module must export its constructors, so its types
  cannot be abstract. This is acceptable only because business code never
  imports `Impl` (Granin ch. 4). Domain types with invariants still use smart
  constructors (above).
- **Blueprint and runtime instance.** Describe a thing as stateless data (a
  definition), and interpret it into a runtime value through the runtime
  module's interface. Name types after their meaning: a definition is not the
  running thing.

The dependency rule (Granin ch. 4):

- Interfaces (types, languages, service definitions) depend on nothing
  concrete.
- Implementations depend on interfaces, never on concrete assets.
- Concrete data (vendors, tenants, rule tables, definitions) is
  configuration, injected at the top. Adding one does not edit the core:

  ```haskell
  priceOrder :: TaxRules -> Order -> Either PricingError PricedOrder
  allRules   = euRules `Map.union` usRules   -- joined in the application layer
  ```

- Nothing outside an implementation imports its `Impl` or `Internal` modules.
- Module dependencies equal layer dependencies. Draw them and look for arrows
  that point the wrong way.

## 7. Quality checks for an architecture

Ask these questions about a functional architecture (Granin ch. 2 names
similar qualities):

- **Simple:** can a newcomer understand each part quickly from its interface?
- **Modular:** is it made of many parts, each with a few responsibilities that
  have a clear reason?
- **Consistent:** do any two decisions contradict each other?
- **Robust:** does a failure in one part stay in that part?
- **Expressive:** can it meet today's requirements, and most of the likely
  next ones, without a redesign?
- **Extensible:** can you add behavior where the domain grows, without edits
  to the core?

Then test it on a newcomer: can they understand the ideas, use the languages,
and not break things by accident?

## 8. Process mistakes

- Overdesign: enterprise ceremony around a simple problem, in either paradigm
  (Granin ch. 1).
- One effectful function that reads a sensor, converts units, patches a vendor
  quirk with a magic number and sends the result. It is untestable and
  fragile. Split it behind interfaces (Granin ch. 1).
- Hard solutions without justification, for example a type-level mapping of a
  native API when a small eDSL would do (Granin ch. 1).
- Debugger-driven development instead of designing behavior explicitly
  (Granin ch. 1).
- Abstractions over abstractions; leaky abstractions that show their
  implementation and spread through the project (Granin ch. 2).
- Database-driven or class-driven modeling from the first interview
  (Wlaschin ch. 2).
- Pushing one part to production quality before the whole is assembled
  (Granin ch. 4).
- Keeping a heavy technology that does not pay off (Granin ch. 3).
- Implementations that import concrete assets or vendors, and dispatch chains
  that grow in the core with each new case (Granin ch. 4).
- Magic constants and unclear abbreviations in definitions (Granin ch. 4).
