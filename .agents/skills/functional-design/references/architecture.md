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
(Granin ch. 1). Granin calls these the pure layer, which declares, and the
impure layer, which runs. Keep the impure layer as small as possible
(Granin ch. 2).

The working shape of a service has three layers plus one wiring point
(Granin ch. 4, 7):

```text
Business logic   scenarios written only against interfaces        (pure, or effects only through interfaces)
Interfaces       languages, handles, algebras, their types/errors (pure)
Implementation   interpreters, handle values, the runtime         (impure)
Application      main: config, resources, runtime, wiring         (impure)
```

- Business logic may use only pure state; implementations may use any state.
  This rule tells you where code goes (Granin ch. 7). An interpretable
  language enforces it, because its scripts cannot run IO. With a service
  handle or any other mechanism in IO, it is a review rule: business code must
  not create mutable references, threads or connections itself.
- Before you write code, name the interface of the new thing. Is it a new
  subsystem with its own language, or does an existing interface cover it?
  (Granin ch. 7)
- Put subsystem boundaries at languages or handles. Let the business-logic
  layer be the only place where subsystem languages meet (Granin ch. 2).
- Inside an implementation, decouple sublayers with records of functions or
  type classes. Granin puts interpretable languages between the big layers and
  simpler handles inside them (Granin ch. 4). This is his framework-level
  choice. For a single business-level service he still recommends a service
  handle (Granin ch. 13). Follow SKILL.md Principle 6 unless the project
  already uses an interpretable framework.
- Business logic can have its own sublayers: scenarios call smaller scenarios,
  and SRP applies to them as to any function (Granin ch. 6, 13).
- A layer is a logical slice with one purpose or one abstraction level, never
  a physical one. A pile of utility functions is pure code but not a layer: a
  layer takes part in design decisions. Name each layer by its purpose and its
  interfaces; do not create a "utils layer" (Granin ch. 2).
- Business logic is the asset. Keep it explicit, so it reads like a
  description of the business. Keep framework and infrastructure code in
  separate modules, so you can reuse or publish the framework without the
  business rules (Granin ch. 14).

A fuller set of layers for a large application (Granin ch. 2), from the
impure top to the pure bottom:

| Layer | Put here | Purity |
|---|---|---|
| Application | configuration, arguments, start-up, resources, threads, lifecycle | impure |
| Presentation | GUI, console, web UI | impure |
| Interoperability | bus, events, streams, serialization, networking, logging | impure |
| Business logic | scenarios over the subsystem languages, validation, event handling | pure where possible |
| Persistence | storage interfaces and the mapping to the data model | your choice: pure when storage is described declaratively behind an interface, and the real I/O sits in an implementation |
| Domain model | domain types, DSLs, domain logic | completely pure |

## 2. The application layer and the runtime

Sources: Granin ch. 7 and ch. 9, unless another chapter is named.

- **One wiring point.** The entry point reads configuration, acquires
  resources (one scope each, nested), builds the runtime and the
  implementations, and runs the top scenario. It is the only place that knows
  all concrete implementations.

  ```haskell
  main = do
    settings <- readSettings
    withMetrics settings $ \metrics ->
      withPool settings $ \pool -> do
        env <- buildEnv settings metrics pool
        runProgram env topScenario   -- interpreter: Env -> Program a -> IO a
  ```

- **The runtime record** (`Env` in the sketch) holds everything that
  implementations need: operational state, connections, thread pools,
  configuration. Only the implementation layer can see it. An interpreter has
  the shape `Env -> Program a -> IO a`. In concurrent programs its mutable
  fields must be thread-safe.
- **Configuration comes from outside.** Pass every configuration value (paths,
  URLs, credentials) in from the application layer. Scenarios with hard-coded
  production configuration make integration tests dangerous (Granin ch. 15).
- **Declarative top level.** Name top-level operations as declarations
  (`process`, `scenario`, `server`), so the top script reads as a description
  of the application, not as a list of steps.
- **The top level prepares; the business level computes.** Keep the entry
  point and wiring free of domain decisions. In Granin's framework only
  business-level scripts run in parallel. So each new mutable or resource
  operation at the business level can bring races back; review it before you
  add it.

## 3. eDSLs and interpreters

Granin's view: the essence of functional design is to encode actions as pure
values and to interpret them with an impure interpreter. In this view
"everything is an eDSL" (Granin ch. 2, 5). Domain types, business logic and
many libraries are already small embedded languages.

Steps from simple to powerful (Granin ch. 2, 5-6):

1. **ADT plus interpreter.** List the domain actions as constructors; a script
   is a list of actions; one impure interpreter maps each action to real calls
   and keeps its own state hidden. It cannot pass the result of one step to
   the next.
2. **Continuation fields and avatars.** Give a method a continuation
   `(Result -> next)`. The result is an avatar, a typed reference to a runtime
   instance, that dependent methods require. Invalid orderings cannot be
   written, but nesting grows.
3. **Parameterized languages.** Subordinate languages take their continuation
   type as a parameter, and the top language instantiates them. This breaks
   type and module cycles between languages.
4. **Free monad.** Wrap the algebra in a free monad. Scripts become ordinary
   sequential code that can return values to callers (Granin ch. 6). See
   `functional-interfaces.md`.

Design rules for languages (Granin ch. 5-6, unless another chapter is named):

- **Domain-specific, not domain-centric.** A literal list of domain commands
  (domain-centric) is readable, but it does not compose, cannot check
  validity, and grows badly. Prefer orthogonal, composable parts. Use the
  literal form only for small, stable domains where readability for experts
  matters most.
- **One responsibility per language.** Do not mix describing, acting and
  reporting in one language.
- **No cycles between languages.** A subordinate language must not know its
  parent. Granin calls cyclic languages "the strongest design smell".
- **Typed avatars, not strings.** Refer to runtime objects through typed
  references that the creating operation returns, never through bare string
  keys. Wrap meaningful primitives in newtypes.
- **Methods that produce values return them** to the script.
- **No implementation types in the algebra** (connections, runtime handles),
  and no crashes (`error`) inside interpreters for expected failures.
- **At least two interpreters:** production and test.
- **Cross-cutting concerns go into interpreters**, not scripts: add them to
  each branch, wrap the step interpreter in an outer one, or write a second
  interpreter (Granin ch. 2).
- **Design the language from its use.** Imagine it from the caller's side;
  write real business logic with it; then make it compile; then make it work
  (Granin ch. 9). Build several versions and try each one on more than one use
  case (Granin ch. 11). The production and test interpreters are two of those
  uses.

Interpreter state (Granin ch. 5):

| Option | When |
|---|---|
| Argument passing (`Runtime -> X -> IO Runtime`) | small interpreters; explicit data flow |
| A record of mutable references | interpreters already in IO; shorter signatures |
| Concurrent primitives (MVar, TVar, Ref) | the runtime is shared between threads |

## 4. Hierarchical languages and capability restriction

**Hierarchical free monads (HFM)** (Granin ch. 6). Each subsystem has its own
language. A unifying language embeds subordinate scripts through methods that
carry a per-call result type:

```haskell
data OpsF next where
  WithCache :: CacheL a -> (a -> next) -> OpsF next
  WithMail  :: MailL a  -> (a -> next) -> OpsF next
type Ops a = Free OpsF a
```

One interpreter per language; the higher interpreter calls the lower ones.

**Restrict capabilities by level** (Granin ch. 9). Give each level only what
it needs:

- The top language (Granin's `AppL`) initializes state, opens connections,
  reads configuration and declares processes. It holds no domain logic.
- The business language (`LangL`) has logic, state, logging, database
  queries, randomness and HTTP calls. It **cannot fork threads**, so nobody
  can write unbounded forking.
- Leaf languages (state, logger, key-value, SQL) are nested inside.

Granin warns against flat effect lists: do not put domain notions (a
reporting service) in the same flat list of "effects" as infrastructure
(database, config, concurrency). That mixes abstraction levels and breaks SRP
and ISP. He prefers the words "subsystem" and "component". This is his
opinion; in a codebase built on an effect system, apply the same separation
through modules and interfaces.

## 5. Designing a subsystem

Use this checklist for every new subsystem (logging, storage, messaging, an
external API):

- [ ] It has its own small interface, nested at the right level of the
      hierarchy (Granin ch. 7).
- [ ] All its operations sit at one abstraction level. Each operation's
      meaning is obvious, and the set is complete: a save has a load
      (Granin ch. 8).
- [ ] The interface has no implementation details: no file names, native
      connection types or native error types (Granin ch. 10).
- [ ] No operation introduces a race or unbounded resource use by design
      (Granin ch. 9).
- [ ] Blocking and failure semantics are documented for each operation
      (Granin ch. 9, 13).
- [ ] The native library is visible only to the implementation, which catches
      and converts all of its exceptions (Granin ch. 13).
- [ ] Its resources are initialized in the application layer, one scope each
      (Granin ch. 10).
- [ ] The connection policy is decided: keep-alive or per query, maximum
      connections, idle timeout, reconnect, loss detection (Granin ch. 11).
- [ ] A test implementation exists.

Useful subsystem patterns:

- **Give a subsystem an interface even when it has one implementation.**
  Interfaces decouple and simplify business logic and enable tests
  (Granin ch. 10).
- **Own facades over libraries** (a state interface over STM, a logger
  interface over a logging library). Then you can add tracking, delayed
  logging, metrics or mocks without touching business code (Granin ch. 10).
- **Typed-Untyped.** Give users a typed interface (typed handles, smart
  constructors) over an untyped implementation (serialized values, a top type
  with casts). Type safety holds because only the framework creates handles
  and each typed method carries the type (Granin ch. 10). Costs:
  serialization bugs and casts inside the runtime.
- **Forbidding an operation is a valid design.** A key-value interface with no
  "close connection" operation (connections close at program end) cannot have
  a double close or a use after close (Granin ch. 11).
- **Abstract protocols between actors.** Define requests and responses as a
  domain contract. Actors do not know whether they talk over TCP, a broker or
  a simulator (Granin ch. 8).
- **Same scripts, many interpreters.** Real, simulator and test interpreters
  for the same scripts. Keep their module layouts parallel so that readers can
  navigate them (Granin ch. 8).

## 6. Framework bridges (HTTP, CLI)

Sources: Granin ch. 14.

- **Build business logic channel-agnostic** (CLI first, then inject HTTP or
  TCP through a handle).
- **No business logic in web-framework handlers.** A handler validates input,
  runs a scenario through the runtime (held in a reader environment), and
  converts errors and escaped exceptions to HTTP responses. Handlers are an
  error-domain boundary.

  ```haskell
  type WebM = ReaderT Env Handler
  runScenario :: Scenario a -> WebM a   -- ask the env, run, map failures to HTTP errors
  ```

- **Share one typed API schema** between server and client when the stack
  allows it. Group routes by resource. Avoid huge, coarse-grained requests;
  split uploads into parts.
- **API types follow SRP and ISP:** complete but not redundant. Prefer
  specialized types with required fields over one type with many optional
  fields, and watch for packs of near-duplicate types.

## 7. Anti-patterns

- A domain language with an auxiliary method (logging, "Report") in it
  (Granin ch. 7-8).
- A raw "run any IO" method in a business language: a black hole for control,
  testing and thread safety. If it must exist, do not export it, and flag every
  use (Granin ch. 9).
- A god language, or a god module forced by cyclic languages (Granin ch. 5).
- Continuation-passing style visible in business logic (Granin ch. 5).
- Implementation types or runtime handles in the algebra (Granin ch. 6).
- Global mutable singletons (a global logger) (Granin ch. 10).
- Business logic inside web handlers (Granin ch. 14).
- Type-level machinery that business-logic authors must handle
  (Granin ch. 11).
- Over-generalizing: type-level extensibility for a few expected cases. Edit a
  list by hand instead (Granin ch. 14).
