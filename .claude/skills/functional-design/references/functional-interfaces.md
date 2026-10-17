# Functional interfaces and dependency inversion

How business logic reaches its dependencies and effects without knowing their
implementations. Sources: Granin (main), Wlaschin, Ghosh. The sketches are
Haskell-like; translate them to the project's language.

## Contents

1. Kinds of interfaces
2. The mechanisms
3. Granin's comparisons
4. Algebra-first design (Ghosh)
5. Dependencies as parameters (Wlaschin)
6. Decision procedure
7. Mocks and test implementations

## 1. Kinds of interfaces

Granin classifies interfaces by how they are used (p. 351-352):

- **Scenario-like:** calls in sequence, later calls depend on earlier results.
  Monadic languages, effect types, interpretable programs.
- **API-like:** independent calls, no chaining. Most object-oriented
  interfaces. Service handles, command types.
- **Combinatorial:** combinators that compose into bigger values of the same
  kind (streams, parsers, lenses, validators). Combinator libraries.

Interfaces also differ in abstraction kind (p. 172). **Genericity-like**
abstractions (functor, monoid) describe mathematical properties and exist at
compile time. **Interface-like** abstractions describe domain actions, carry
domain meaning, and separate the lifetimes of caller and implementation. Do
not confuse the two: a type class for "things that can be summed" is not a
substitute for a service boundary.

## 2. The mechanisms

### Module with abstract types and functions

```haskell
module Device (Device, blankDevice, addSensor, readPart) where
```

The simplest interface: an export list over an abstract type (p. 115-116). Use
it for local encapsulation and MVPs. It cannot be swapped at run time and gives
no mocks.

### Service handle (record of functions)

```haskell
data Reporter = Reporter
  { reportMeteor   :: MeteorTemplate -> IO (Either ReportError MeteorId)
  , reportAsteroid :: AsteroidTemplate -> IO (Either ReportError AsteroidId) }

mkReporter :: Channel -> Reporter          -- picks HTTP or TCP from config
logic      :: Reporter -> Input -> IO Out  -- business logic sees only the handle
```

- The record is the interface; a value is an implementation; the constructor
  closes over the implementation's own dependencies (p. 132-138, 354-356).
- It works in any language with first-class functions. It is Granin's
  recommended default for business-level services (p. 357, 359): "the
  simplest FP interface possible, not including the bare function itself"
  (p. 366).
- Variants: a handle of handles that delegates (try vendor A, then vendor B)
  (p. 138).
- Costs: it does not restrict effects when its operations are plain IO; many
  handles become hard to group and pass; handles that need type-level features
  become monsters (p. 134-135, 415).

### Type-class service

A class indexed by the handle type with a callback method, and instances next
to the types. It gives polymorphic access to several kinds of inner handles
without pattern matching on internals (p. 141-144). It is an advanced feature;
keep it inside implementation sublayers.

### Reader environment (ReaderT pattern)

The same record stored in a reader environment over the application monad
(p. 356-357). It removes one argument but adds `ask`, lifting and unwrapping.
Granin: probably not worth it only to remove an argument; "keep it simple". In
languages where the environment is built into the effect type (ZIO's `R`,
Kleisli in Scala, an environment parameter), the cost is lower; follow the
project.

### Free monad (interpretable language)

```haskell
data TerminalF next = ReadLine (String -> next) | PrintLine String next
type Terminal = Free TerminalF

echo :: Terminal ()
echo = readLine >>= printLine

runIO   :: Terminal a -> IO a          -- production interpreter
runTest :: [String] -> Terminal a -> ([String], a)   -- pure test interpreter
```

Three parts: the interface (algebra, functor, smart constructors), the
implementations (interpreters), and business logic (scenarios) (p. 171-189).

Recipe (p. 179-189):

1. An algebra with a `next` parameter: one constructor per method, a
   continuation `(R -> next)` for results.
2. A functor instance over the continuation.
3. A type alias over the free type.
4. One smart constructor per method that lifts it into the free monad
   (`liftF`, with `Pure` or `id` as the continuation).
5. A runner plus a step interpreter for each target (production, test).
6. For sublanguages, an embedding method with a per-call result type (HFM, see
   `architecture.md`).

Benefits: computation separated from interpretation, several interpreters
(production, test, simulation, sequential or concurrent), controlled effects,
value-level interfaces that can be passed and returned (p. 83, 173-177). In
Granin's testability comparison it is the only approach where effects are
restricted and cannot be bypassed (p. 415).

Costs: monad literacy, functor and constructor boilerplate, interpreters that
must stay consistent with the algebra, performance that depends on the
encoding (use an efficient encoding on hot paths), and a poor fit with
algebras that need GADTs, phantom types or type-level logic (p. 177-178, 184).

Use it when: scenario-like interfaces between business logic and subsystems,
interpretation must vary, testability matters a lot, or you build a framework.
Do not use it for a simple API-like service inside business logic: a handle is
enough (p. 359). Ghosh (2016) also calls free monads advanced and, at that
time, not frequently used in the Scala community, though powerful (Ghosh
p. 194, 203).

Granin's distinction: a free-monadic *framework* as the base of the
application is one decision; using free monads for every business-level
service is another (p. 359).

### Command type (GADT)

```haskell
data ReporterCmd a where
  ReportMeteor :: MeteorTemplate -> ReporterCmd (Either ReportError MeteorId)
runReporter :: Channel -> ReporterCmd a -> App a
```

One constructor per operation, indexed by its result type; inject the runner
like an interpreter (p. 360-361). Good for API-like interfaces that must be
interpretable values. It cannot sequence calls into scenarios.

The compiler checks that the interpreter returns each command's own result
type (p. 197-198). To use commands inside scenarios, embed the command type
in a monadic language through one embedding method plus high-level smart
constructors (p. 198). Granin calls GADTs the weaker pattern for DSLs, and his
attempt at an extensible GADT command type failed (p. 197, 200). Many
free-monad libraries take exactly this encoding as their algebra (cases that
extend `Op[Result]` in Scala's `cats.free.Free`), which needs no functor
instance; follow the library that the project uses.

### Tagless final, mtl, capability type classes

```haskell
class Monad m => MonadReporter m where
  reportMeteor :: MeteorTemplate -> m (Either ReportError MeteorId)

logic :: (MonadReporter m, MonadLogger m) => Input -> m Out
```

Operations are type-class methods; the implementation is chosen by the
concrete monad. It is the dominant style in parts of the Haskell and Scala
communities (p. 362).

Granin's critique of the variant he shows (p. 362-365, 414), recorded as his
opinion:

- configuration can leak into the interface (selector types for HTTP or TCP);
- business logic and instances share one environment, which couples them;
- constraint lists mix domain capabilities with infrastructure ones;
- type-based injection is the least testable of the injection options.

What other practitioners do (for example Volpe in Scala, see
`scala-functional-design`): keep each algebra as an explicit value passed to
constructors (a service handle with an abstract effect type), keep
configuration out of the algebra, and use type-class constraints only for
generic capabilities (concurrency, time, random). Used this way, most of the
critique does not apply, and parametricity limits what each function can do.

### Effect systems

Typed effect systems (ZIO's environment and error channels, Kyo's pending
effects, Effect-TS, algebraic effects with handlers) put requirements and
failures in the effect type and provide them at the edge. Treat them like the
other mechanisms: keep domain interfaces separate from infrastructure
capabilities, keep each interface at one abstraction level, and give each a
test implementation. If the project uses one, follow it instead of adding
another mechanism.

## 3. Granin's comparisons

**Simplicity, for one API-like service (p. 365).** Granin rates the service
handle and a GADT command type as the simplest options, with almost no
boilerplate. Free monads and tagless final are simple too, but carry some
boilerplate, and tagless final also needs type classes, phantom types and
wrapped monads. ReaderT is simple but adds much lifting. He finds only the handle and the command type
convenient for that task. He calls this a starting point for analysis, not a
final verdict.

**Testability (p. 414-415).** Only free monads restrict effects so that
business logic cannot bypass them, and he rates them best. A handle or ReaderT
over IO is value-based but does not restrict effects; tagless final over IO
is type-based. He rates all three low, and bare IO very low.

How to read the two together: the service handle is the simplest and most
convenient for one service; the free monad gives the strongest isolation for
a whole framework. Granin's view (p. 414, which he hedges with "should" and
"must be"): value-based injection tests more easily than type-based
injection. Skinner (p. 522) says the opposite for mtl style: swapping in a
test monad is one of its benefits. When the algebra is passed as an explicit
value (section 2, "What other practitioners do"), both views agree.

## 4. Algebra-first design (Ghosh)

- **Write the algebra first:** a module of operations in domain words over
  abstract types, plus the laws that relate them. Write interpreters later,
  separately; one algebra can have several (Ghosh p. 99-108).
- **Derive composite operations inside the module** from the primitives
  (`transfer` from `debit` and `credit`). If a composite cannot be derived, the
  algebra is missing an operation or its types do not line up (p. 105).
- **Laws are part of the model.** Credit then debit restores the balance; a
  close date is not before the open date. They document business rules and
  become property-based tests (p. 106).
- **Return an effect, not a bare value or an exception**, so operations
  compose ("abstract early, evaluate late") (p. 101-105).
- **Use the least powerful abstraction:** an applicative for independent
  steps, a monad only for dependent steps (p. 154). An applicative collects
  all errors only with an accumulating type such as Validation, whose error
  type has a Semigroup; Either's applicative still stops at the first error
  (p. 162).
- **Repository as an algebra plus Reader/Kleisli injection:** each operation
  returns a function of the environment, so a composition is built first and
  the environment is supplied once. Bundle several dependencies into one
  environment type (p. 122-128).
- **Choose implementations only at the end of the world** (the application),
  and do not let implementations import each other (p. 176, 186-188).

## 5. Dependencies as parameters (Wlaschin)

- Dependencies are function parameters, not interfaces in an IoC container.
  Put dependencies first and data last, so partial application fixes them at
  the composition root (Wlaschin p. 138-140, 188-190).
- A dependency is a function of the narrowest shape the step needs
  (`ProductCode -> Maybe Price`), not a repository with dozens of methods
  (p. 138-141, 247-252).
- Public workflow signatures hide dependencies; internal step signatures show
  them (p. 147-148).
- With too many dependencies: split the step, then group dependencies into a
  record, then pre-build child functions with their own configuration so that
  intermediate layers do not forward it (p. 191-193).
- Tests pass stub functions inline. No mocking library is needed (p. 188-194).

## 6. Decision procedure

1. Does the project already use a mechanism? Use it, unless it causes a
   concrete problem that you can name.
2. Is the interface scenario-like, API-like or combinatorial? (Section 1.)
3. Start with the lightest tools: make it a function, then abstract a
   parameter (pass the operation or its result) (Parsons p. 97-98). Only then
   use a record of functions (service handle), and after that an interface or
   type class. Consider promoting a record to a type class when it models
   well-defined operations of something outside the program and has at least
   two instances. Skinner's "rule of threes" is to abstract after the third
   time you would rewrite the code; if most instances would be newtype
   wrappers, keep a record (Skinner p. 230-233, 280-281). Records capture
   run-time values and allow local overrides; a class gives one canonical
   behavior per type and is easy to find, but is hard to swap for an inner
   scope (Parsons p. 83-85).
   Concrete effect types or capability-polymorphic code: see the comparison
   table in `production.md`, section 10.
4. Move to an interpretable language or an effect system only for a named
   need: effect restriction that cannot be bypassed, several interpretations,
   inspection, record and replay, or a framework for many teams.
5. Do not mix domain capabilities and infrastructure capabilities in one flat
   list. Group them by abstraction level.
6. Whatever you choose, check: business logic is written only against the
   interface; no "run anything" escape; expected failures are in result types;
   a test implementation exists.
7. **Least capability per function.** Give each function the smallest effect
   stack or capability set it needs (statistics collection runs in State over
   IO and cannot see the configuration), and widen it at the call site; hide
   repeated lifts behind one named helper (Granin p. 429-437).
8. **Do not invent a monad or effect** by copying and cutting another's
   `bind`: you probably rebuild State, Reader or Writer, or break the monad
   laws. Check whether an existing one fits; if you write one, test its laws.
   Do not use Writer as the application logger; use a logging interface
   (Granin p. 419-421).

## 7. Mocks and test implementations

- **Handle mocks** (Granin p. 138-141): build a handle value whose functions
  return canned values chosen by input, with defined behavior for unknown
  inputs. To count calls, close over a mutable counter. Give fakes identities
  so several can be told apart. A mock must return the real result types.
- **Stateful mock lists** (p. 404-406): a test interpreter pops a predefined
  value for each call. This drives logic into specific branches and simulates
  an outage in the middle of a flow.
- **Step-scripted test runtime** (p. 407-414): for each framework call, a step
  says "return this mock", "descend into the nested script", or "run the real
  implementation once". Record the call stack and assert it.
- **In-memory implementations** (Ghosh p. 287-303): for repository algebras, a
  map-based implementation is often better than a mock.
- See `testing.md` for when white-box tests with mocks pay off.
