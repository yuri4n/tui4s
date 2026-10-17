# Application architecture

How to structure one application or service in a functional style: layers, the
application layer and the runtime, eDSLs and interpreters, hierarchies of
languages, and subsystem design. Source: Granin, unless another author is named.

## Contents

1. Layers
2. The application layer and the runtime
3. eDSLs and interpreters
4. Hierarchical languages and capability restriction
5. Designing a subsystem
6. Framework bridges (HTTP, CLI)
7. Anti-patterns

## 1. Layers

The base shape is a pyramid: pure, deterministic functions at the bottom, a
thin impure layer at the top. Effect types mark impurity up to `main`
(p. 51-53). Granin calls these the pure layer, which declares, and the impure
layer, which runs. Keep the impure layer as small as possible (p. 78).

The working shape of a service has three layers plus one wiring point
(p. 131-132, 206):

```text
Business logic   scenarios written only against interfaces        (pure, or effects only through interfaces)
Interfaces       languages, handles, algebras, their types/errors (pure)
Implementation   interpreters, handle values, the runtime         (impure)
Application      main: config, resources, runtime, wiring         (impure)
```

- Business logic may use only pure state; implementations may use any state.
  This rule tells you where code goes (p. 207-208). Granin gets it for free,
  because free-monadic scripts cannot run IO (p. 208). With a service handle
  or any other mechanism in IO, it is a review rule: business code must not
  create mutable references, threads or connections itself.
- Ask "what is the interface of this thing?" before you write code. Is it a new
  subsystem with its own language, or does an existing interface cover it?
  (p. 208)
- Put subsystem boundaries at languages or handles. Let the business-logic
  layer be the only place where subsystem languages meet (p. 84).
- Inside an implementation, decouple sublayers with records of functions or
  type classes. Granin puts interpretable languages between the big layers and
  simpler handles inside them (p. 132). This is his framework-level choice.
  For a single business-level service he still recommends a service handle
  (p. 357-359). Follow SKILL.md Principle 6 unless the project already uses an
  interpretable framework.
- Business logic can have its own sublayers: scenarios call smaller scenarios,
  and SRP applies to them as to any function (p. 190-193, 353).
- A layer is a logical slice with one purpose or one abstraction level, never a
  physical one. A pile of utility functions is pure code but not a layer: a
  layer takes part in design decisions. Name each layer by its purpose and its
  interfaces; do not create a "utils layer" (p. 77-78).
- Business logic is the asset. Keep it explicit, so it reads like a
  description of the business, and keep framework and infrastructure code in
  separate modules, so the framework can be reused or published without the
  business rules (p. 367-368).

A fuller list of layers for a large application (p. 77-78):

| Layer | Contents | Purity |
|---|---|---|
| Application | config, arguments, initialization, resources, threads, lifecycle | impure |
| Presentation | GUI, console, web UI | impure |
| Interoperability | bus, events, streams, serialization, networking, logging | impure |
| Business logic | scenarios over the subsystem languages, validation, event handling | pure where possible |
| Persistence | storage interfaces and mapping | pure or impure by choice: pure means functional interfaces and declarative storage descriptions; the real storage work then runs in another layer |
| Domain model | domain types, DSLs, domain logic | completely pure |

## 2. The application layer and the runtime

- **One wiring point.** The entry point reads configuration, acquires
  resources (one scope each, nested), builds the runtime and the
  implementations, and runs the top scenario. It is the only place that knows
  all concrete implementations (p. 206).

  ```haskell
  main = do
    cfg <- loadConfig
    withLogger cfg $ \logger ->
      withDatabase cfg $ \db -> do
        rt <- createRuntime cfg logger db
        runAppL rt appScenario   -- interpreter: Runtime -> App a -> IO a
  ```

- **The runtime record** holds everything implementations need: operational
  state, connections, thread pools, configuration. It is visible only to the
  implementation layer. An interpreter has the shape
  `Runtime -> Program a -> IO a` (p. 225-226). In concurrent programs its
  mutable fields must be thread-safe (p. 227).
- **Configuration comes from outside.** Pass every configuration value (paths,
  URLs, credentials) in from the application layer. Scenarios with hard-coded
  production configuration make integration tests dangerous (p. 399).
- **Declarative top level.** Name top-level operations as declarations
  (`process`, `scenario`, `server`), so the top script reads as a description
  of the application, not as a list of steps (p. 259).
- **The top level prepares; the business level computes.** Keep the entry
  point and wiring free of domain decisions. In Granin's framework, top-level
  scripts run sequentially and only business-level scripts run in parallel,
  so every new mutable or resource operation at the business level is where
  races come back; review it before you add it (p. 256, 262).

## 3. eDSLs and interpreters

Granin's view: the essence of functional design is to encode actions as pure
values and to interpret them with an impure interpreter, so "everything is an
eDSL" (p. 78, 149). Domain types, business logic and many libraries are
already small embedded languages.

Steps from simple to powerful:

1. **ADT plus interpreter.** List the domain actions as constructors; a script
   is a list of actions; one impure interpreter maps each action to real calls
   and keeps its own state hidden (p. 79-80). It cannot pass the result of one
   step to the next.
2. **Continuation fields and avatars.** Give a method a continuation
   `(Result -> next)`. The result is an avatar, a typed reference to a runtime
   instance, that dependent methods require (p. 158-160). Invalid orderings
   cannot be written, but nesting grows.
3. **Parameterized languages.** Subordinate languages take their continuation
   type as a parameter, and the top language instantiates them. This breaks
   type and module cycles between languages (p. 163-165).
4. **Free monad.** Wrap the algebra in a free monad. Scripts become ordinary
   sequential code that can return values to callers (p. 171-189). See
   `functional-interfaces.md`.

Design rules for languages:

- **Domain-specific, not domain-centric.** A literal list of domain commands
  (domain-centric) is readable but does not compose, cannot check validity,
  and grows badly. Prefer orthogonal, composable parts. Use the literal form
  only for small, stable domains where readability for experts matters most
  (p. 151-153).
- **One responsibility per language.** Do not mix describing, acting and
  reporting in one language (p. 159-161).
- **No cycles between languages.** A subordinate language must not know its
  parent. Granin calls cyclic languages "the strongest design smell" (p. 161-165).
- **Typed avatars, not strings.** Refer to runtime objects through typed
  references returned by the creating operation, never through bare string
  keys. Wrap meaningful primitives in newtypes (p. 156-158).
- **Methods that produce values return them** to the script (p. 175, 190).
- **No implementation types in the algebra** (connections, runtime handles),
  and no crashes (`error`) inside interpreters for expected failures
  (p. 188-189).
- **At least two interpreters:** production and test (p. 174, 177).
- **Cross-cutting concerns go into interpreters**, not scripts: add them to
  each branch, wrap the step interpreter in an outer one, or write a second
  interpreter (p. 81-84).
- **Design the language from its use.** Imagine the language from the usage
  side; write real business logic with it; then make it compile; then make it
  work (p. 259). Implement it several times and assess it in several use
  cases (p. 319). The production and test interpreters are two of those uses
  (p. 174, 177).

Interpreter state (p. 168-170):

| Option | When |
|---|---|
| Argument passing (`Runtime -> X -> IO Runtime`) | small interpreters; explicit data flow |
| A record of mutable references | interpreters already in IO; shorter signatures |
| Concurrent primitives (MVar, TVar, Ref) | the runtime is shared between threads |

## 4. Hierarchical languages and capability restriction

**Hierarchical free monads (HFM)** (p. 193-199). Each subsystem has its own
language. A unifying language embeds subordinate scripts through methods that
carry a per-call result type:

```haskell
data AppF next where
  EvalDb     :: DbL a     -> (a -> next) -> AppF next
  EvalLogger :: LoggerL a -> (a -> next) -> AppF next
type App a = Free AppF a
```

One interpreter per language; the higher interpreter calls the lower ones.

**Restrict capabilities by level** (p. 255-259). Give each level only what it
needs:

- The top language (Granin's `AppL`) initializes state, opens connections,
  reads configuration and declares processes. It holds no domain logic.
- The business language (`LangL`) has logic, state, logging, database
  queries, randomness and HTTP calls. It **cannot fork threads**, so unbounded
  forking cannot be written.
- Leaf languages (state, logger, key-value, SQL) are nested inside.

Granin's warning about flat effect lists (p. 257): do not put domain notions
(a reporting service) in the same flat list of "effects" as infrastructure
(database, config, concurrency). That mixes abstraction levels and breaks SRP
and ISP. He prefers the words "subsystem" and "component". This is his
opinion; in a codebase built on an effect system, apply the same separation
through modules and interfaces.

## 5. Designing a subsystem

Use this checklist for every new subsystem (logging, storage, messaging, an
external API):

- [ ] It has its own small interface, nested at the right level of the
      hierarchy (p. 208-209).
- [ ] All its operations sit at one abstraction level. Each operation's
      meaning is obvious, and the set is complete: a save has a load (p. 241).
- [ ] The interface has no implementation details: no file names, native
      connection types or native error types (p. 278).
- [ ] No operation introduces a race or unbounded resource use by design
      (p. 261-262).
- [ ] Blocking and failure semantics are documented for each operation
      (p. 271, 345).
- [ ] The native library is visible only to the implementation, which catches
      and converts all of its exceptions (p. 341-346).
- [ ] Its resources are initialized in the application layer, one scope each
      (p. 296).
- [ ] The connection policy is decided: keep-alive or per query, maximum
      connections, idle timeout, reconnect, loss detection (p. 305).
- [ ] A test implementation exists.

Useful subsystem patterns:

- **"Only one implementation" is not a reason to skip an interface.**
  Interfaces decouple and simplify business logic and enable tests (p. 278).
- **Own facades over libraries** (a state interface over STM, a logger
  interface over a logging library). Then you can add tracking, delayed
  logging, metrics or mocks without touching business code (p. 286-293).
- **Typed-Untyped.** Give users a typed interface (typed handles, smart
  constructors) over an untyped implementation (serialized values, a top type
  with casts). Type safety holds because only the framework creates handles
  and each typed method carries the type (p. 285-289). Costs:
  serialization bugs and casts inside the runtime.
- **Forbidding an operation is a valid design.** Granin's key-value subsystem
  has no "close connection": connections close at program end, so double close
  and use after close cannot happen (p. 306).
- **Abstract protocols between actors.** Define requests and responses as a
  domain contract. Actors do not know whether they talk over TCP, a broker or
  a simulator (p. 229-231).
- **Same scripts, many interpreters.** Real, simulator and test interpreters
  for the same scripts. Keep their module layouts parallel so that readers can
  navigate them (p. 231-232).

## 6. Framework bridges (HTTP, CLI)

- **Build business logic channel-agnostic** (CLI first, then inject HTTP or
  TCP through a handle) (p. 368).
- **No business logic in web-framework handlers.** A handler validates input,
  runs a scenario through the runtime (held in a reader environment), and
  converts errors and escaped exceptions to HTTP responses. Handlers are an
  error-domain boundary (p. 378-384).

  ```haskell
  type AppHandler = ReaderT Runtime Handler
  runApp :: App a -> AppHandler a   -- ask the runtime, run, map failures to HTTP errors
  ```

- **Share one typed API schema** between server and client when the stack
  allows it. Group routes by resource. Avoid huge, coarse-grained requests;
  split uploads into parts (p. 371-379).
- **API types follow SRP and ISP:** complete but not redundant. Prefer
  specialized types with required fields over one type with many optional
  fields, and watch for packs of near-duplicate types (p. 369-371).

## 7. Anti-patterns

- A domain language with an auxiliary method (logging, "Report") in it
  (p. 208-209, 241).
- A raw "run any IO" method in a business language: a black hole for control,
  testing and thread safety. If it must exist, do not export it, and flag every
  use (p. 273).
- A god language, or a god module forced by cyclic languages (p. 159-163).
- Continuation-passing style visible in business logic (p. 165).
- Implementation types or runtime handles in the algebra (p. 188).
- Global mutable singletons (a global logger) (p. 280-281, 298).
- Business logic inside web handlers (p. 380).
- Type-level machinery that business-logic authors must handle (p. 318).
- Over-generalizing: type-level extensibility for a few expected cases. Edit a
  list by hand instead (p. 374).
