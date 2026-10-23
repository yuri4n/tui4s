# Domain modeling in Scala

Scala encodings for strongly typed domains. Read the domain-modeling
guidance of the `functional-design` skill for the design reasons (AND/OR
types, illegal states, state machines, aggregates). Sources: PFP ch. 1 and 6; FEDA ch. 4.

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
arguments (PFP ch. 1; FEDA ch. 4). Options, from weakest to strongest:

| Option | Distinguishes | Validates | Cost | Verdict |
|---|---|---|---|---|
| raw `String` / `Int` | no | no | none | only in tiny private helpers |
| value class (`extends AnyVal`) | yes | Scala 2: no (`copy` survives a private constructor); Scala 3: a private constructor also hides `copy`, but you still need a smart constructor | allocates in arrays, generics, pattern matches | avoid, because of the allocations |
| `case class X(v: A) derives Eq, Show, ...` | yes | with a smart constructor | boxing | good default for most applications |
| opaque type | yes | with a smart constructor or Iron | zero | Scala 3 default for many newtypes |
| opaque type over an Iron type | yes | yes (compile time for literals, run time for inputs) | ~zero | for values with invariants |
| Scala 2: `@newtype` + refined | yes | yes | ~zero | the PFP default; dated for Scala 3 |

A shared base class gives each newtype a zero-cost opaque type and its
instances (FEDA ch. 4 builds a fuller one). This sketch reuses an instance of
the raw type only when that instance exists:

```scala
abstract class Wrap[Raw]:
  opaque type T = Raw
  def from(raw: Raw): T = raw
  extension (t: T) def raw: Raw = t
  given (using ev: Eq[Raw]): Eq[T] = ev
  given (using ev: Show[Raw]): Show[T] = ev
  given (using ev: Encoder[Raw]): Encoder[T] = ev
  given (using ev: Decoder[Raw]): Decoder[T] = ev

type Sku = Sku.T
object Sku extends Wrap[String]
```

Trade-off: a shared base gives every newtype the same instances, and that
does not fit every type; Volpe himself advises caution with his version.
Decide with the team whether every instance fits every newtype, or use a
library (Monix Newtypes, Neotype; check what the project uses). A
`case class` with `derives` costs one allocation, which is rarely the
bottleneck.

## 2. Refinement types and validation

- One refinement per rule; several concepts can share one predicate (PFP
  ch. 1). With Iron in Scala 3 (FEDA ch. 4), decode the raw wire shape first,
  then refine each field:

  ```scala
  type Iso4217 = DescribedAs[Match["^[A-Z]{3}$"], "currency: three capital letters"]
  type Cents   = DescribedAs[GreaterEqual[0L], "amount: not negative"]

  final case class Price(currency: String :| Iso4217, cents: Long :| Cents)
  final case class PriceIn(currency: String, cents: Long) derives Decoder

  def toPrice(in: PriceIn): EitherNel[String, Price] =
    (in.currency.refineNel[Iso4217], in.cents.refineNel[Cents]).parMapN(Price.apply)
  ```

- Literals are checked at compile time; inputs at run time with
  `refineEither`, `refineNel` or the equivalent. Iron cannot always inline a
  custom `String` constraint; such a check then runs only at run time.
- **Accumulate errors** for independent fields: `EitherNel` + `parMapN`, or
  `ValidatedNel` + `mapN`. A monadic chain stops at the first error; use it
  only when later checks depend on earlier ones (PFP ch. 1).
- **Validate at the boundary** (HTTP decoding, configuration, database rows)
  and trust the types inside.
- Do not create a validated type with `coerce`, `unsafeFrom` or
  `Refined.unsafeApply` outside tests, except where the invariant holds by
  construction (a literal constant, a value derived from validated values) or
  inside one generic, tested validation helper (PFP ch. 1, 2 and 8). Test
  generators built with unsafe constructors can produce invalid values; keep
  them honest.
- In ZIO projects, ZIO Prelude `Newtype` / `Subtype` with assertions is an
  alternative; Iron works with every stack.

## 3. Closing the `copy` hole

A smart constructor does not protect a case class:
`Email.from("a@b.co").map(_.copy(value = ""))` builds an invalid value (FEDA
ch. 4). Fixes, best first:

1. Refined fields (`value: String :| Not[Blank]`): `copy` must also satisfy
   the type.
2. `final case class Email private (value: String)`: in Scala 3 this also
   makes `apply` and `copy` private.
3. `sealed abstract case class Email(value: String)`: more boilerplate.

## 4. ADTs and enums

- Use `enum` (Scala 3) or `sealed trait` hierarchies for choices; the compiler
  checks exhaustiveness.
- Put fields common to every case as abstract members of the enum, so generic
  code can read them (FEDA ch. 1 and 6):

  ```scala
  enum PaymentEvent derives Codec.AsObject:
    def eventId: EventId
    def paymentId: PaymentId
    def at: Timestamp
    case Authorized(eventId: EventId, paymentId: PaymentId, amount: Money, at: Timestamp)
    case Declined(eventId: EventId, paymentId: PaymentId, reason: Reason, at: Timestamp)
  ```

- Errors are ADTs too: `sealed trait CheckoutError extends NoStackTrace` (PFP
  ch. 1).
- Separate command types for creation and update (`CreateItem`,
  `UpdateItem`) instead of one type with optional fields (PFP ch. 4).
- Money with a currency-safe type, so currencies can be added later (PFP
  ch. 4); a `BigDecimal`-backed opaque `Money` is often enough.

## 5. Derivation and instances

Sources: PFP ch. 6; FEDA ch. 4.

- **Derive** `Eq`, `Order`, `Show` and JSON codecs instead of writing them.
  Scala 3: `derives Eq, Order, Show` (Kittens) and `derives Codec.AsObject`
  (Circe); jsoniter-scala is an alternative for speed. Scala 2: derevo
  annotations, which are dated.
- **Keep instances in companion objects** so they are found without imports
  and coherence holds.
- **Orphan instances** (instances for types you do not own, such as
  `Instant` or `Money`) go in one agreed place. Scala 3:
  `object OrphanInstances` and `export OrphanInstances.given` in the domain
  package file. Keep JVM-only instances (configuration decoders) out of a
  domain module that Scala.js also compiles.
- **JSON codecs as type classes are a compromise.** The same data often needs
  different encodings for HTTP and storage. Use a separate wire type with its
  own codecs, or explicit codec values (as Skunk does).
- **Law-test every hand-written instance** (`Monoid[Money]`) and every `Iso`
  (PFP ch. 7 and 8).
- **Capability traits with Scala 3 givens:** a trait, a summoner, a default
  given in the companion, and a fixed instance for tests:

  ```scala
  trait Today[F[_]]:
    def date: F[LocalDate]

  object Today:
    def apply[F[_]](using t: Today[F]): Today[F] = t
    given [F[_]: Sync]: Today[F] with
      def date = Sync[F].delay(LocalDate.now(ZoneOffset.UTC))
    def fixed[F[_]: Applicative](d: LocalDate): Today[F] = new Today[F]:
      def date = d.pure[F]
  ```

  Scala 3.6 changed context-bound and given syntax; follow the project's
  version.

## 6. Booleans, collections and non-empty types

- **Boolean blindness:** `F[Boolean]` results and `ifM` let you swap branches
  and still compile. In critical code, return a two-case ADT with domain names
  and match on it. If you do not own the API, wrap it in a small proxy
  algebra that returns the ADT (PFP ch. 1). Do not wrap every Boolean in the
  code base; agree on a team rule.
- **No `Seq` in interfaces:** it hides whether the value is a `List`, a
  `Vector` or a possibly infinite `LazyList`. Choose `List` (small, strict),
  `Vector` (indexed), `Chain` (cheap append), `NonEmptyList` (emptiness is
  invalid) or `fs2.Stream` (large or unbounded) (PFP ch. 1, 4 and 7).
- Use non-empty types where the domain forbids emptiness
  (`Orders.create(..., items: NonEmptyList[CartItem])`) (PFP ch. 4).

## 7. Advanced Scala 3 tools

Source: FEDA ch. 4.

- **Match types** tie type parameters together so that wrong combinations do
  not compile (FEDA ch. 8 applies them to its state machines):

  ```scala
  type StateOf[In] = In match
    case CartCommand    => CartState
    case PaymentCommand => PaymentState
  ```

  Use them only when there is a unique mapping between the types.
- **Context functions** (`Ctx ?=> IO[A]`) suit DSLs and passing one effectful
  context (for example a `Supervisor`). Do not use them as the main wiring
  tool; group dependencies into modules instead.
- **Universal apply:** routes can be plain classes, created without `new`:
  `ItemRoutes[F](items).routes`.

## 8. Optics

- Use Monocle lenses (`Focus`) for deep immutable updates, prisms for one
  case of a sum type, and `At` and `Index` for maps; lens + prism gives an
  optional (PFP bonus chapter; FEDA ch. 6).
- Prefer a plain `copy` when the update is shallow.
- Law-check custom optics in tests.

## 9. Anti-patterns

Sources: PFP ch. 1, 6 and 7; FEDA ch. 4.

- Raw `String` or `Int` for domain values; value classes for safety
  (section 1).
- `coerce` or unsafe constructors outside tests (section 2).
- A smart constructor with a public `copy` (section 3).
- Domain errors that extend `Exception` with stack traces instead of
  `NoStackTrace` (section 4).
- `Seq` or `ifM` in interfaces (section 6), and monad transformers in
  interfaces (`algebras-and-programs.md` section 1).
- Instances scattered outside companions without one agreed orphan module
  (section 5).
- A hand-written `Monoid` or `Iso` without a law test (section 5).
- `Arbitrary` instances for domain types (see `testing.md`).
