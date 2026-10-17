# Domain modeling in Scala

Scala encodings for strongly typed domains. Read `functional-design`'s
`domain-modeling.md` for the design reasons (AND/OR types, illegal states,
state machines, aggregates). Sources: Volpe PFP ch. 1 and 6, FEDA ch. 4.

## Contents

1. Wrappers and newtypes
2. Refinement types and validation
3. Closing the `copy` hole
4. ADTs and enums
5. Derivation and instances
6. Booleans, collections and non-empty types
7. Advanced Scala 3 tools
8. Optics
9. Anti-patterns

## 1. Wrappers and newtypes

Make functions strongly typed so the compiler rejects swapped or invalid
arguments (PFP p. 20-28). Options, from weakest to strongest:

| Option | Distinguishes | Validates | Cost | Verdict |
|---|---|---|---|---|
| raw `String` / `Int` | no | no | none | only in tiny private helpers |
| value class (`extends AnyVal`) | yes | Scala 2: no (`copy` survives a private constructor, PFP p. 21); Scala 3: a private constructor also hides `copy`, but you still need a smart constructor | allocates in arrays, generics, pattern matches | avoid, because of the allocations (PFP p. 22) |
| `case class X(v: A) derives Eq, Show, ...` | yes | with a smart constructor | boxing | good default for most applications (FEDA p. 78) |
| opaque type | yes | with a smart constructor or Iron | zero | Scala 3 default for many newtypes |
| opaque type over an Iron type | yes | yes (compile time for literals, run time for inputs) | ~zero | for values with invariants |
| Scala 2: `@newtype` + refined | yes | yes | ~zero | Volpe's PFP default; dated for Scala 3 |

A reusable base for opaque newtypes with instances (FEDA p. 75-77). This
sketch leaves out the book's `Order`, `Ordering` and `Wrapper` instances:

```scala
abstract class Newtype[A](using eq: Eq[A], sh: Show[A], enc: Encoder[A], dec: Decoder[A]):
  opaque type Type = A
  inline def apply(a: A): Type = a
  extension (t: Type) inline def value: A = t
  given Eq[Type] = eq
  given Show[Type] = sh
  given Encoder[Type] = enc
  given Decoder[Type] = dec

type Age = Age.Type
object Age extends Newtype[Int]
```

Trade-off: Volpe calls this base highly opinionated and says to use it with
caution, because it derives the common instances (Eq, Order, Show, JSON
codecs) for every newtype (FEDA p. 76). Decide with the team whether every
instance fits every newtype, or use a library (Monix Newtypes, Neotype;
check what the project uses) (FEDA p. 78).

## 2. Refinement types and validation

- One refinement per rule; several concepts can share one predicate (PFP
  p. 24). With Iron in Scala 3 (FEDA p. 79-80):

  ```scala
  type SkuR = DescribedAs[Match["^[A-Z]{3}-[0-9]{4}$"], "SKU must look like ABC-1234"]
  type QtyR = DescribedAs[Greater[0] & Less[1000], "quantity must be 1-999"]

  final case class OrderLine(sku: String :| SkuR, qty: Int :| QtyR) derives Codec.AsObject, Eq, Show

  object OrderLine:
    def parse(rawSku: String, rawQty: Int): EitherNel[String, OrderLine] =
      (rawSku.refineNel[SkuR], rawQty.refineNel[QtyR]).parMapN(OrderLine.apply)
  ```

- Literals are checked at compile time; inputs at run time with
  `refineEither`, `refineNel` or the equivalent. Custom String constraints
  often cannot be inlined, so they check only at run time (FEDA p. 79).
- **Accumulate errors** for independent fields: `EitherNel` + `parMapN`, or
  `ValidatedNel` + `mapN`. A monadic chain stops at the first error; use it
  only when later checks depend on earlier ones (PFP p. 25-26).
- **Validate at the boundary** (HTTP decoding, configuration, database rows)
  and trust the types inside.
- Do not create a validated type with `coerce`, `unsafeFrom` or
  `Refined.unsafeApply` outside tests, except where the invariant holds by
  construction (a literal constant, a value derived from validated values) or
  inside one generic, tested validation helper (PFP p. 22-28, 65, 194). Test
  generators built with unsafe constructors can produce invalid values; keep
  them honest.
- In ZIO projects, ZIO Prelude `Newtype` / `Subtype` with assertions is an
  alternative; Iron works with every stack.

## 3. Closing the `copy` hole

`Pet.make("x").map(_.copy(name = ""))` skips the smart constructor (FEDA
p. 80-81). Fixes, best first:

1. Refined fields (`name: String :| Not[Blank]`): `copy` must also satisfy the
   type.
2. `final case class Pet private (name: String)`: in Scala 3 this also makes
   `apply` and `copy` private.
3. `sealed abstract case class Pet(name: String)`: more boilerplate.

## 4. ADTs and enums

- Use `enum` (Scala 3) or `sealed trait` hierarchies for choices; the compiler
  checks exhaustiveness.
- Put fields common to every case as abstract members of the enum, so generic
  code can read them (FEDA p. 21-22; the book's TradeEvent enum is at
  p. 130-131):

  ```scala
  enum PaymentEvent derives Codec.AsObject:
    def eventId: EventId
    def paymentId: PaymentId
    def at: Timestamp
    case Authorized(eventId: EventId, paymentId: PaymentId, amount: Money, at: Timestamp)
    case Declined(eventId: EventId, paymentId: PaymentId, reason: Reason, at: Timestamp)
  ```

- Errors are ADTs too: `sealed trait CheckoutError extends NoStackTrace` (PFP
  p. 39, 44).
- Separate command types for creation and update (`CreateItem`,
  `UpdateItem`) instead of one type with optional fields (PFP p. 87-92).
- Money with a currency-safe type, so currencies can be added later (PFP
  p. 89); a `BigDecimal`-backed opaque `Money` is often enough.

## 5. Derivation and instances

- **Derive** `Eq`, `Order`, `Show` and JSON codecs instead of writing them.
  Scala 3: `derives Eq, Order, Show` (Kittens) and `derives Codec.AsObject`
  (Circe); jsoniter-scala is an alternative for speed (FEDA p. 73-75). Scala
  2: derevo annotations, which are dated (PFP p. 137-141).
- **Keep instances in companion objects** so they are found without imports
  and coherence holds (PFP p. 137-142).
- **Orphan instances** (instances for types you do not own, such as
  `Instant` or `Money`) go in one agreed place. Scala 3:
  `object OrphanInstances` and `export OrphanInstances.given` in the domain
  package file (FEDA p. 82-83). Keep JVM-only instances (configuration
  decoders) out of a domain module that Scala.js also compiles (FEDA p. 83).
- **JSON codecs as type classes are a compromise.** The same data often needs
  different encodings for HTTP and storage. Use a separate wire type with its
  own codecs, or explicit codec values (as Skunk does) (PFP p. 142).
- **Law-test every hand-written instance** (`Monoid[Money]`) and every `Iso`
  (PFP p. 176, 217-220).
- **Capability traits with Scala 3 givens** (FEDA p. 84):

  ```scala
  trait Time[F[_]]:
    def timestamp: F[Timestamp]
  object Time:
    def apply[F[_]](using t: Time[F]): Time[F] = t
    given [F[_]: Sync]: Time[F] with
      def timestamp: F[Timestamp] = Sync[F].delay(Timestamp(Instant.now()))
  ```

  Scala 3.6 changed context-bound and given syntax; follow the project's
  version.

## 6. Booleans, collections and non-empty types

- **Boolean blindness:** `F[Boolean]` results and `ifM` let you swap branches
  and still compile. In critical code, return a two-case ADT with domain names
  and match on it. If you do not own the API, wrap it in a small proxy
  algebra that returns the ADT (PFP p. 39-43). Do not wrap every Boolean in
  the code base; agree on a team rule.
- **No `Seq` in interfaces:** it hides whether the value is a `List`, a
  `Vector` or a possibly infinite `LazyList`. Choose `List` (small, strict),
  `Vector` (indexed), `Chain` (cheap append), `NonEmptyList` (emptiness is
  invalid) or `fs2.Stream` (large or unbounded) (PFP p. 37, 90, 166).
- Use non-empty types where the domain forbids emptiness
  (`Orders.create(..., items: NonEmptyList[CartItem])`) (PFP p. 90).

## 7. Advanced Scala 3 tools

- **Match types** tie type parameters together so that wrong combinations do
  not compile (FEDA p. 90-91, 236):

  ```scala
  type StateOf[In] = In match
    case CartCommand    => CartState
    case PaymentCommand => PaymentState
  ```

  Use them only when there is a unique mapping between the types.
- **Context functions** (`Ctx ?=> IO[A]`) suit DSLs and passing one effectful
  context (a `Supervisor`, a logger built on a `Ref`). Do not use them as the
  main wiring tool; group dependencies into modules instead (FEDA p. 86-89).
- **Universal apply:** routes can be plain classes, `HealthRoutes[F].routes`
  (FEDA p. 85).

## 8. Optics

- Use Monocle lenses (`Focus`) for deep immutable updates, prisms for one
  case of a sum type, and `At` and `Index` for maps; lens + prism gives an
  optional (PFP p. 277-280; FEDA p. 136-137).
- Prefer a plain `copy` when the update is shallow.
- Law-check custom optics in tests.

## 9. Anti-patterns

- Raw `String` or `Int` for domain values; value classes for safety (PFP
  p. 20-22).
- `coerce` or unsafe constructors outside tests (PFP p. 22).
- A smart constructor with a public `copy` (FEDA p. 80).
- Domain errors that extend `Exception` with stack traces instead of
  `NoStackTrace` (PFP p. 39).
- `Seq`, monad transformers or `ifM` in interfaces (PFP p. 37-41).
- Instances scattered outside companions without one agreed orphan module
  (PFP p. 142).
- A hand-written `Monoid` or `Iso` without a law test (PFP p. 176).
- `Arbitrary` instances for domain types (see `testing.md`).
