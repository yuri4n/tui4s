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

- **Definition.** Software design turns business requirements into a
  structure, with a systematic engineering approach aimed at goals. Its output
  is decisions, diagrams, interfaces and high-level code structure
  (Granin p. 29, 57).
- **Main task.** Keep accidental complexity as low as possible without
  sacrificing the goals or the requirements (Granin p. 36). Essential
  complexity comes from the domain. Accidental complexity comes from tools,
  tricks, and hard solutions chosen without a reason (p. 34-35). Too few
  abstractions cause coupling; too many cause overdesign (p. 36).
- **Three factors to balance:** goals accomplished, requirements met,
  simplicity kept. Design is risk management: a risk is low when the problem
  is cheap to solve (p. 31-32). For each significant decision, say which factor
  it serves and what it costs.
- **FP is not a guarantee.** Functional code can be as coupled and as
  overdesigned as object-oriented code (p. 30, 39-40). The usual principles
  apply in functional form:

  | Principle | Functional form |
  |---|---|
  | SRP | one theme per module, one responsibility per language or service |
  | ISP | small export lists, small languages, small handles |
  | DIP / IoC | business logic depends on a functional interface, never on an implementation |
  | LSP | every implementation honors the interface's types: no hidden exceptions, no undeclared effects |
  | OCP | add a vendor or a backend as data or as a new implementation, not by editing the core |

- **The FP-specific principle.** Look for the algebraic properties of domain
  types (monoid, functor, foldable, monad). A lawful instance brings the whole
  library of that abstraction with it (p. 42-43). Only use lawful instances.
- **What each FP property buys:**

  | Property | Buys | Costs |
  |---|---|---|
  | Immutability | no shared-mutable-state bugs | mutation sometimes needed, kept local |
  | Purity, determinism | predictable layers, easy tests | effects must move to a thin top layer; without effect tracking it is discipline only |
  | Effects in types | the compiler confines impurity | impurity spreads to every caller up to `main` |
  | Strong static types | errors at compile time, safe large refactorings | type-level design is hard; use it where it pays |
  | ADTs | readable domain models, even for non-programmers | many small types |
  | eDSLs | the domain's essence without accidental complexity | a bad DSL restricts and annoys its users |

- **Levels of architecture.** Enterprise (many products), solution (many
  services as one product: monolith or microservices, brokers, CQRS, data
  flow) and application (one service: layers, subsystems, interfaces). The
  paradigm matters most at the application level (p. 61-62). Architecture
  decisions are the most expensive to get wrong (p. 63-64).
- **Choose the least powerful tool** from the functional toolbox, and write
  down why a stronger one was not chosen (p. 35-36, 55, 64-70):

  | Tool | Use when | Cost |
  |---|---|---|
  | Lenses / optics | deep updates in nested immutable records | one more vocabulary; generated lenses hide code |
  | Monad transformers / effect stacks | one block needs several effects | lifting noise; give each function the smallest stack it needs |
  | Laziness | heavy transformations of which only part is used | hard to reason about cost; "in the hands of a master" |
  | Phantom, existential, rank-N types, GADTs, type-level logic | the compiler must check a rule on every build | type-level design is not yet an engineering discipline |
  | STM | shared concurrent state; transactions must compose | none named for that use |
  | FRP | time-varying values whose dependents update (GUIs) | Granin dropped it: it did not pay for its complexity (p. 106) |
  | Reactive streams | event streams to map, merge, split | none named |
  | Metaprogramming | generated boilerplate | the generated code is invisible |

- **Contracts the compiler cannot enforce** (p. 48-49, 52-53). An interface
  promises a behavior contract: no hidden exceptions, no global mutation, no
  file access outside the contract. Put as much of it as possible in types
  (error results, effect types); list the rest in the review checklist. In
  languages without effect tracking (Scala, F#), a function that returns a
  plain type can still do I/O; keep "effects only in the effect type" as a
  review rule.
- **Translate an object-oriented design element by element** before you
  invent a new mechanism (p. 41-47, 107, 127-139):

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

Collect requirements before you design.

- **Questions and answers** find out what the customer wants, but give weak
  structure.
- **User scenarios** list steps, preconditions, postconditions and alternative
  paths. They are concrete, and the customer can check them.
- **Mind maps** are good for brainstorming: one central notion, levels of
  detail, cross-links. Keep each map small (Granin p. 73-75).
- Write the **nonfunctional requirements** (performance, availability, data
  volume, latency) and what is explicitly **out of scope** (p. 30-31, 91).
- Review the **project risks** at the start: budget, changing or misunderstood
  requirements, time, overcomplicated code, wrong tools (p. 32-33).

Domain discovery (Wlaschin):

- Start from **business events and workflows**, not from tables or classes.
  Events are past-tense facts; commands are requests (Wlaschin p. 21, 27).
- **Event storming:** put everyone who has questions or answers together. Map
  events on a timeline, then add commands, workflows, external systems and
  reporting needs. Follow the events to the edges. Write open questions down.
- Describe each workflow with this template:

  ```text
  workflow "Place order"
    triggered by:   OrderForm received
    primary input:  OrderForm
    other input:    product catalog, address checker
    output events:  OrderPlaced, BillableOrderPlaced, AcknowledgmentSent
    side effects:   acknowledgment email
  ```

- **Bounded contexts.** One context per area of coherent knowledge, with its
  own dialect of the shared vocabulary, an explicit API (commands in, events
  out) and its own storage. The same word ("Order") can mean different data in
  two contexts (p. 30-36; storage ownership: p. 256).
- **Context map.** Show direction (upstream, downstream) and the relationship:
  shared kernel, customer/supplier (consumer-driven contract), conformist, or
  anti-corruption layer.
- **Where to spend effort.** Core domain first; supporting and generic domains
  get simpler treatment (Wlaschin p. 34-35). Start as a modular monolith;
  split into services only when the boundaries are stable (p. 58-59).
- Keep a **glossary**. Use its words for types and functions. Avoid Manager,
  Helper and Factory names that a domain expert would not recognize.
- If a rule is unclear, ask the expert (the user). Record open questions as
  explicit placeholder types in the model.

## 3. From scenarios to languages

Granin's "mnemonic analysis" (p. 96-99):

1. Write two or three key scenarios in the users' words, in a readable
   pseudo-language: header, description, inputs, steps, result.
2. Rewrite the same behavior in other forms: sequential steps, a stream of
   events, a monitor that reacts to changes.
3. Keep the form that reads most naturally and that suggests the best
   functional idioms. It becomes the shape of the business-logic language.

Heuristics:

- Granin gives every domain model a formal representation, which he calls an
  eDSL. In his broad sense, a set of domain ADTs and functions over them
  already counts (p. 92, 149). It also documents the domain. Build an
  interpreted language only when SKILL.md Principle 6 calls for one.
- Look for recursive structure (devices that contain devices), and note what
  the type allows that the world does not (p. 94-95).
- A scenario that looks imperative may read better as a stream or a state
  machine. Try before you decide (p. 98-99).
- Know the users' mental model. Engineers who write scripts may prefer
  statement-like languages; that is still declarative, because a script does
  nothing until an interpreter runs it (p. 156).

## 4. Components and architecture diagrams

Granin uses three informal diagrams (p. 100-106). Text or Mermaid is fine; use
them as thinking tools, not as goals.

| Diagram | Purpose | Rules |
|---|---|---|
| Necessity diagram | the big parts the system needs | components from the requirements; relations "uses" and "interacts with" |
| Elements diagram | brainstorm how to build one component | all elements equal (concepts, libraries, layers, data); unconnected elements allowed; each new element should give a new requirement or idea |
| Architecture diagram | the final structure | a tree with no cycles and no isolated elements; each component shows its name, its parts and its implementation stack |

- A **component** is physical (a library or a project with one function and an
  interface) and may span layers. A **layer** is logical (p. 99-100).
- If a central component would talk to every other component, put an
  interaction abstraction between them: a bus, a queue, streams or actors.
  Choose the lightest one; Granin dropped FRP because it did not pay for its
  complexity (p. 101, 106).
- Remove technology that does not pay for its complexity.

## 5. MVP and end-to-end design

- **Integrate early.** Teams that build parts with no common architecture and
  no early integration create a "Frankenstein's monster" (p. 90). An MVP is a
  real program with limited but sufficient features, the base of later work
  (p. 90-91).
- **Shape before content.** Build the whole path first (definitions,
  interfaces, implementation, a consumer) with fake content. Do not polish one
  subsystem to production quality while others do not exist (p. 119-130).
- **Tests are the final consumer.** If there is no real client yet, tests and
  simulators are the consumers. They show wrong dependencies, poor names and
  model inconsistencies while these are cheap to fix (p. 119-121, 129).
  Granin recommends a relaxed TDD: tests shape the design, but strict
  test-first can be too limiting (p. 129).
- **List the open questions** after the naive version (who calls this, who
  initializes it, where its state lives). They drive the next design step
  (p. 117).
- **Start simple.** For an MVP, a module with abstract types and functions, or
  an ADT with an interpreter, is enough. Do not skip interfaces (p. 118).
- **Refactor early.** Strong static types make large refactorings safe
  (p. 124). Expect several rewrites of the domain model as understanding
  grows (p. 112).
- **Phase deliverables** (p. 71-72). Use the classic phases only as a list of
  activities and their artifacts, then iterate. For a new service, produce at
  least: a requirements list including the nonfunctional ones, an
  architecture sketch, the top-level types and interfaces, and tests for both
  kinds of requirements. Go back to earlier artifacts when something changes.

## 6. Modules and the dependency rule

Module patterns (Granin p. 107-116):

- **Smart constructors and abstract types.** Export the type without its
  constructors, and export functions that build only valid values. Keep stable
  plain data public: "everything public" is the simplest option when nothing
  can be invalid (p. 113-114).
- **Internal module.** Put the full representation in `X.Internal` for tests
  and experts, with no stability promise. Never re-export it.
- **Top re-exporting module.** Give each context one module that only
  re-exports its parts. Clients and tests import that module, so internal
  moves do not break them (p. 108-110).
- **Implementation types apart from behavior.** Put implementation types in
  a module such as `X.Impl.Device.Types`, and their behavior in sibling `Impl`
  modules. That types module must export its constructors, so its types
  cannot be abstract. This is acceptable only because business code never
  imports `Impl` (p. 136). Domain types with invariants still use smart
  constructors (above).
- **Blueprint and runtime instance.** Describe a thing as stateless data (a
  definition), and interpret it into a runtime value through the runtime
  module's interface. Name types after their meaning: a definition is not the
  device (p. 110-115).

The dependency rule (p. 121-124):

- Interfaces (types, languages, service definitions) depend on nothing
  concrete.
- Implementations depend on interfaces, never on concrete assets.
- Concrete data (vendors, tenants, device definitions, rule tables) is
  configuration, injected at the top. Adding one does not edit the core:

  ```haskell
  makeDevice :: VendorComponents -> Hdl -> Either DeviceError Device
  allComponents = vendorA `Map.union` vendorB   -- joined in the application layer
  ```

- Nothing outside an implementation imports its `Impl` or `Internal` modules.
- Module dependencies equal layer dependencies. Draw them and look for arrows
  that point the wrong way.

## 7. Quality checks for an architecture

A good functional architecture is (Granin p. 76):

- **Modular:** many parts, each with a few strongly motivated responsibilities.
- **Simple:** clear interfaces; each part is quick to understand.
- **Consistent:** no contradictory decisions.
- **Expressive:** supports the known requirements and many future ones.
- **Robust:** a broken part does not crash the rest.
- **Extensible:** new behavior is easy to add where the domain needs it.

Test it on a newcomer: can they understand the ideas, use the languages, and
not break things by accident?

## 8. Process mistakes

- Overdesign: enterprise ceremony around a simple problem, in either paradigm
  (p. 29-30).
- One effectful function that reads a device, converts units, fixes a vendor
  quirk with a magic constant and sends the result: untestable and fragile.
  Split it behind interfaces (p. 37-41).
- Hard solutions without justification, for example a type-level mapping of a
  native API when a small eDSL would do (p. 35).
- Debugger-driven development instead of designing behavior explicitly
  (p. 53-54).
- Abstractions over abstractions; leaky abstractions that show their
  implementation and spread through the project (p. 64-65).
- Database-driven or class-driven modeling from the first interview
  (Wlaschin p. 43-45).
- Pushing one part to production quality before the whole is assembled
  (p. 130).
- Keeping a heavy technology that does not pay off (p. 106).
- Implementations that import concrete assets or vendors, and dispatch chains
  that grow in the core with each new case (p. 121-122).
- Magic constants and unclear abbreviations in definitions (p. 125).
