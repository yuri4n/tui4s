# Functional interfaces and dependency inversion

How business logic reaches its dependencies and effects without knowing their
implementations. Sources: Granin (main), Wlaschin, Ghosh. The sketches are
Haskell-like and use one small shipping domain; translate them to the
project's language.

## Contents

1. Kinds of interfaces
2. The mechanisms
3. Granin's comparisons
4. Algebra-first design (Ghosh)
5. Dependencies as parameters (Wlaschin)
6. Decision procedure
7. Mocks and test implementations

## 1. Kinds of interfaces

Granin classifies interfaces by how callers use them (Granin ch. 13):

- **Scenario-like:** calls in sequence, later calls depend on earlier results.
  Monadic languages, effect types, interpretable programs.
- **API-like:** independent calls, no chaining. Most object-oriented
  interfaces. Service handles, command types.
- **Combinatorial:** combinators that compose into bigger values of the same
  kind (streams, parsers, lenses, validators). Combinator libraries.

Interfaces also differ in abstraction kind (Granin ch. 6).
**Genericity-like** abstractions (functor, monoid) describe mathematical
properties and exist at compile time. **Interface-like** abstractions describe
domain actions, carry domain meaning, and separate the lifetimes of caller and
implementation. Do not confuse the two: a type class for "things that can be
summed" is not a substitute for a service boundary.

## 2. The mechanisms

### Module with abstract types and functions

```haskell
module Parcel (Parcel, emptyParcel, addItem, weight) where
```

The simplest interface: an export list over an abstract type (Granin ch. 3).
Use it for local encapsulation and MVPs. You cannot swap it at run time, and
it gives no mocks.

### Service handle (record of functions)

```haskell
data Courier = Courier
  { bookPickup  :: Parcel   -> IO (Either CourierError PickupId)
  , trackParcel :: PickupId -> IO (Either CourierError Status) }

mkCourier :: CourierConfig -> Courier        -- picks the vendor API from config
dispatch  :: Courier -> Order -> IO Outcome  -- business logic sees only the handle
```

Sources: Granin ch. 4 and ch. 13.

- The record is the interface; a value is an implementation; the constructor
  closes over the implementation's own dependencies.
- It works in any language with first-class functions. Granin recommends it
  as the default for business-level services, and ranks it as the simplest
  interface after a bare function.
- Variants: a handle of handles that delegates (try vendor A, then vendor B).
- Costs: it does not restrict effects when its operations are plain IO; many
  handles become hard to group and pass; handles that need type-level features
  grow out of control (Granin ch. 4, 15).

### Type-class service

A class indexed by the handle type with a callback method, and instances next
to the types. It gives polymorphic access to several kinds of inner handles
without pattern matching on internals (Granin ch. 4). It is an advanced
feature; keep it inside implementation sublayers.

### Reader environment (ReaderT pattern)

The same record stored in a reader environment over the application monad
(Granin ch. 13). It removes one argument but adds `ask`, lifting and
unwrapping. Granin thinks it is probably not worth it only to remove an
argument, and says "keep it simple". In languages where the environment is
built into the effect type (ZIO's `R`, Kleisli in Scala, an environment
parameter), the cost is lower; follow the project.

### Free monad (interpretable language)

```haskell
data StockF next = OnHand Sku (Int -> next) | Reserve Sku Int next
  deriving Functor
type Stock = Free StockF

onHand sku    = liftF (OnHand sku id)      -- onHand  :: Sku -> Stock Int
reserve sku n = liftF (Reserve sku n ())   -- reserve :: Sku -> Int -> Stock ()

reserveIfAvailable :: Sku -> Int -> Stock Bool
reserveIfAvailable sku n = do
  have <- onHand sku
  if have < n then pure False else reserve sku n >> pure True

runDb   :: Pool -> Stock a -> IO a                      -- production interpreter
runPure :: Map Sku Int -> Stock a -> (Map Sku Int, a)   -- pure test interpreter
```

Sources: Granin ch. 6, unless another chapter is named. Three parts: the
interface (algebra, functor, smart constructors), the implementations
(interpreters), and business logic (scenarios).

Recipe:

1. An algebra with a `next` parameter: one constructor per method, a
   continuation `(R -> next)` for results.
2. A functor instance over the continuation.
3. A type alias over the free type.
4. One smart constructor per method that lifts it into the free monad
   (`liftF` with `id` or `()` as the continuation, or the `Free` constructor
   with `Pure`).
5. A runner plus a step interpreter for each target (production, test).
6. For sublanguages, an embedding method with a per-call result type (HFM, see
   `architecture.md`).

Benefits: computation separated from interpretation, several interpreters
(production, test, simulation, sequential or concurrent), controlled effects,
value-level interfaces that can be passed and returned (Granin ch. 2, 6). In
Granin's testability comparison it is the only approach where business logic
cannot bypass the restricted effects (Granin ch. 15).

Costs: monad literacy, functor and constructor boilerplate, interpreters that
must stay consistent with the algebra, performance that depends on the
encoding (use an efficient encoding on hot paths), and a poor fit with
algebras that need GADTs, phantom types or type-level logic.

Use it when: scenario-like interfaces between business logic and subsystems,
interpretation must vary, testability matters a lot, or you build a framework.
Do not use it for a simple API-like service inside business logic: a handle is
enough (Granin ch. 13). Dated: in 2016 Ghosh called free monads powerful but
advanced and rare in Scala code (Ghosh ch. 5).

Granin separates two decisions: to build the whole application on a
free-monadic *framework*, and to use free monads for every business-level
service (Granin ch. 13).

### Command type (GADT)

```haskell
data CourierCmd a where
  BookPickup :: Parcel -> CourierCmd (Either CourierError PickupId)
runCourier :: CourierConfig -> CourierCmd a -> App a
```

One constructor per operation, indexed by its result type; inject the runner
like an interpreter (Granin ch. 13). Good for API-like interfaces that must be
interpretable values. It cannot sequence calls into scenarios.

Sources for the rest of this part: Granin ch. 6. The compiler checks that the
interpreter returns each command's own result type. To use commands inside
scenarios, embed the command type in a monadic language through one embedding
method plus high-level smart constructors. Granin rates GADTs the weaker
choice for a DSL, and reports that an extensible GADT command type did not
work out. Many free-monad libraries take exactly this encoding as their
algebra (cases that extend `Op[Result]` in Scala's `cats.free.Free`), which
needs no functor instance; follow the library that the project uses.

### Tagless final, mtl, capability type classes

```haskell
class Monad m => MonadCourier m where
  bookPickup :: Parcel -> m (Either CourierError PickupId)

dispatch :: (MonadCourier m, MonadLog m) => Order -> m Outcome
```

Operations are type-class methods; the concrete monad chooses the
implementation. It is the dominant style in parts of the Haskell and Scala
communities (Granin ch. 13).

Granin's critique of the variant that he shows, recorded as his opinion
(Granin ch. 13, 15):

- configuration can leak into the interface (selector types for HTTP or TCP);
- business logic and instances share one environment, which couples them;
- constraint lists mix domain capabilities with infrastructure ones;
- type-based injection is the least testable of the injection options.

What other practitioners do (for example Volpe in Scala, see the
`scala-functional-design` skill): keep each algebra as an explicit value
passed to constructors (a service handle with an abstract effect type), keep
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

**Simplicity, for one API-like service** (Granin ch. 13). Granin finds the
service handle and a GADT command type the lightest and most convenient
choices for this task. Each of the other mechanisms adds a cost: free monads
add boilerplate, tagless final adds boilerplate and type-level features, and
ReaderT adds lifting. He offers this as a starting point for analysis, not a
final verdict.

**Testability** (Granin ch. 15). His argument: an implementation that you
inject as a value is easier to replace than one that the type checker
selects, and only an interpretable language stops business logic from
bypassing its interfaces. So he ranks free monads first, ranks handles,
ReaderT and tagless final over IO well below them, and ranks bare IO last.

How to read the two together: the service handle is the simplest and most
convenient for one service; the free monad gives the strongest isolation for
a whole framework. Granin expects value-based injection to test more easily
than type-based injection; he states this as an expectation, not as a
measurement (Granin ch. 15). Skinner says the opposite for mtl style: a test
monad is easy to swap in, and that is one of its benefits (Skinner ch. 13).
When the algebra is passed as an explicit value (section 2, "What other
practitioners do"), both views agree.

## 4. Algebra-first design (Ghosh)

Sources: Ghosh ch. 3, unless another chapter is named.

- **Write the algebra first:** a module of operations in domain words over
  abstract types, plus the laws that relate them. Write interpreters later,
  separately; one algebra can have several.
- **Derive composite operations inside the module** from the primitives (a
  transfer from a debit and a credit). If you cannot derive a composite, the
  algebra is missing an operation or its types do not line up.
- **Laws are part of the model.** For example: a debit that undoes an equal
  credit leaves the balance as it was; an account cannot close before it
  opens. Laws document business rules and become property-based tests.
- **Return an effect, not a bare value or an exception**, so operations
  compose. Ghosh's phrase is "abstract early, evaluate late" (Ghosh ch. 3,
  10).
- **Pick the weakest abstraction that works:** an applicative for independent
  steps, a monad only for dependent steps. An applicative collects all errors
  only with an accumulating type such as Validation, whose error type has a
  Semigroup; Either's applicative still stops at the first error
  (Ghosh ch. 4).
- **Repository as an algebra plus Reader/Kleisli injection:** each operation
  returns a function of the environment, so you build the composition first
  and supply the environment once. Bundle several dependencies into one
  environment type.
- **Choose implementations only at the end of the world** (the application),
  and do not let implementations import each other (Ghosh ch. 5).

## 5. Dependencies as parameters (Wlaschin)

Sources: Wlaschin ch. 7 and ch. 9, unless another chapter is named.

- Dependencies are function parameters, not interfaces in an IoC container.
  Put dependencies first and data last, so partial application fixes them at
  the composition root.
- A dependency is a function of the narrowest shape that the step needs
  (`CustomerId -> Maybe CreditLimit`), not a repository with dozens of methods
  (Wlaschin ch. 7, 12).
- Public workflow signatures hide dependencies; internal step signatures show
  them.
- With too many dependencies: split the step, then group dependencies into a
  record, then pre-build child functions with their own configuration so that
  intermediate layers do not forward it.
- Tests pass stub functions inline. No mocking library is needed.

## 6. Decision procedure

1. Does the project already use a mechanism? Use it, unless it causes a
   concrete problem that you can name.
2. Is the interface scenario-like, API-like or combinatorial? (Section 1.)
3. Start with the lightest tools: make it a function, then abstract a
   parameter (pass the operation or its result) (Parsons ch. 7). Only then
   use a record of functions (service handle), and after that an interface or
   type class. Consider promoting a record to a type class when it models
   well-defined operations of something outside the program and has at least
   two instances. Skinner's "rule of threes" is to abstract after the third
   time you would rewrite the code; if most instances would be newtype
   wrappers, keep a record (Skinner ch. 6). Records capture run-time values
   and allow local overrides; a class gives one canonical behavior per type
   and is easy to find, but is hard to swap for an inner scope (Parsons ch. 6).
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
   stack or capability set that it needs, and widen it at the call site. For
   example, a statistics step can run in State over IO with no access to the
   configuration. Hide repeated lifts behind one named helper (Granin
   appendices B-C).
8. **Do not invent a monad or effect** by copying and cutting another's
   `bind`: you probably rebuild State, Reader or Writer, or break the monad
   laws. Check whether an existing one fits; if you write one, test its laws.
   Do not use Writer as the application logger; use a logging interface
   (Granin appendix A).

## 7. Mocks and test implementations

- **Handle mocks** (Granin ch. 4): build a handle value whose functions return
  canned values chosen by input, with defined behavior for unknown inputs. To
  count calls, close over a mutable counter. Give fakes identities so that you
  can tell several apart. A mock must return the real result types.
- **Stateful mock lists** (Granin ch. 15): a test interpreter pops a
  predefined value for each call. This drives logic into specific branches and
  simulates an outage in the middle of a flow.
- **Step-scripted test runtime** (Granin ch. 15): for each framework call, a
  step says "return this mock", "descend into the nested script", or "run the
  real implementation once". Record the call stack and assert it.
- **In-memory implementations** (Ghosh ch. 9): for repository algebras, a
  map-based implementation is often better than a mock.
- See `testing.md` for when white-box tests with mocks pay off.
