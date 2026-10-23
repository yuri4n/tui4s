# Persistence and boundaries

How data crosses the edges of a functional application: API and database
models, DTOs, key-value and relational storage, CQRS and event sourcing.
Sources: Granin ch. 11-12 and 14, Wlaschin ch. 11-12, Ghosh ch. 8.

## Contents

1. Three data models
2. DTOs and trust boundaries
3. Storage interfaces
4. Keys, IDs and record variants (HKD)
5. Choosing an SQL library
6. Mapping domain types to storage
7. CQRS and event sourcing
8. Anti-patterns

## 1. Three data models

Keep three separate models and convert between them with small, total
functions (Granin ch. 12, 14):

```text
edge (HTTP, messages)   API types (DTOs)     plain values for the wire; no logic
core                    domain types         the only types business logic sees
storage                 database rows        keys, foreign keys, DB timestamps, column types
```

- Design the domain model first and the database model second. Starting from
  the database gives relational-biased, unintuitive types.
- Never let database or wire types leak into business logic. Convert only
  through functions (`fromDbOrder`, `toDbOrder`). Keep that edge thin, so it
  absorbs changes easily.
- The database model can live in its own module or package and evolve on its
  own schedule.
- In large systems the three models are almost unavoidable. Watch for packs of
  near-duplicate types per use case. If duplication grows, a query language
  such as GraphQL may fit better than many REST shapes (Granin ch. 14).

## 2. DTOs and trust boundaries

Treat each edge of a context as a gate (Wlaschin ch. 3, 11):

- **Input gate:** deserialize to a DTO of primitives (this fails only for
  corrupt data), then convert to domain types with a function that validates
  and returns a Result. This also acts as an anti-corruption layer.
- **Output gate:** convert domain to DTO with a function that cannot fail and
  that drops private data (card numbers, internal flags).
- Both conversion functions live with the DTO; the domain does not know DTOs.
- **Define DTOs explicitly.** Do not let a serializer auto-map rich domain
  types, and do not let a library generate the contract between contexts
  implicitly. Version DTOs.
- Send downstream contexts what they asked for and no more
  (consumer-driven contracts).

DTO mapping rules (language-agnostic):

| Domain shape | Wire shape | On read |
|---|---|---|
| Simple wrapper type | its primitive | run the smart constructor |
| Enum-like choice | a string or enum | reject unknown values |
| Option | a nullable or absent field | treat both as absence |
| Record | a record of converted fields | convert each field |
| Tuple | a named record | as for a record |
| List, set | an array | convert each element |
| Map | a JSON object, or an array of key-value pairs | convert keys and values |
| Choice with data | a tag field plus one field per case (only the chosen one set), or a one-entry map keyed by the tag | check that the tag and the data agree |

## 3. Storage interfaces

Granin's key-value subsystem is a good model for any storage interface
(Granin ch. 11):

- **Start with the smallest store-agnostic interface:** bytes in, bytes out,
  an explicit error result. A universal interface must cut store-specific
  features; do not try to unify everything.
- **Tag each database with a phantom type** so that queries for one database
  cannot run against another.
- **Keep connections out of query interfaces.** Open connections in the top
  level, return an abstract handle (never the native connection type), and run
  query batches against a handle in a transactional context:

  ```haskell
  openStore :: StoreConfig db -> App (Either DbError (StoreHandle db))   -- top level
  runStore  :: StoreHandle db -> StoreQuery db a -> Lang a               -- business level
  ```

- **Add a typed layer over the raw one** when there are many entities and key
  mix-ups are a real risk: one generic `loadEntity :: Key e -> Query db (Result d)`
  driven by type classes that pair each entity with its key and value types.
  Hide this machinery from business code.
- A raw bytes interface covers most needs and is simple and fast, but unsafe:
  garbage bytes load "successfully".
- Return errors as values (`Either DbError a`); never crash on a decode
  failure.

## 4. Keys, IDs and record variants (HKD)

- **Surrogate keys** (integer, UUID, hash). Never floating-point keys; avoid
  natural and composite keys unless strongly justified (Granin ch. 12, which
  is also the source of this section unless marked).
- **The ID problem:** a new value has no ID before insertion. Granin names
  three forms and does not rank them:
  - **A type parameter for the ID field:** `()` before insertion, the ID type
    after. Granin calls this HKD, but the parameter has kind `Type`, so it is
    not the uniform higher-kinded data that Parsons criticizes below.

    ```haskell
    data Invoice' id = Invoice { invoiceId :: id, amount :: Money, dueOn :: Day }
    type NewInvoice  = Invoice' ()
    type Invoice     = Invoice' InvoiceId
    ```

  - **Separate plain types per form** (`Invoice`, `InvoiceWithId`): simple,
    but with DB models they triple the number of types.
  - **An optional ID field:** do not use it on domain types. Every reader must
    handle absence, and code that "knows" the ID is set ends in `fromJust`
    crashes (Parsons ch. 18; `production.md` section 9).
- **Many backends** (domain, SQLite, Postgres field types): HKD with a type
  selector that maps each field to a backend type, or code generation. The
  selector table is closed: adding a backend edits it.
- **Counterpoint (Parsons ch. 18):** polymorphic records do not scale; a
  type parameter per varying field grows fast, higher-kinded data varies all
  fields uniformly and cannot add or remove fields, and phase-indexed type
  families need an entry per field per phase. Use the small ID-parameter form
  when it fits; otherwise write separate plain types with conversions.

**Database operations as data** (Parsons ch. 18; details in
`production.md`): a create-input type; a small query type interpreted to SQL;
field-update lists or an update-action sum type instead of `record -> record`
or whole records from clients; deletes that reuse the query type and let the
database cascade.

## 5. Choosing an SQL library

- Decide in this order (Granin ch. 12, the source of this section):
  1. Get the requirements clear first. A wrong or partial picture of the
     needs is the most expensive mistake here.
  2. Then choose the store, the access library and the way you manage the
     schema. Design the schema (tables, relations, normalization, indexes),
     and decide how much logic goes into stored procedures and views.
  3. Decide where the relational complexity lives. It does not go away; it
     moves into the database, into a mapping layer (ORM-like), or into the
     database model types. A mapping layer is overhead in a small program and
     hard to avoid in a large one; raw query strings with no schema
     description are hard to maintain.
- Do not write your own type-level SQL library.
- Before you adopt a library, check whether its types leak into your schema,
  queries and business logic. Highly coupled, type-heavy libraries leak
  through every layer and are hard to put behind a framework.
- Isolate the chosen library behind your own interface and connection type,
  even if the interface is imperfect. Map its connection type to your own so
  that business logic stays testable.
- Trade-offs between type safety, convenience and simplicity are unavoidable.
  Choose consciously and write the reason down.

## 6. Mapping domain types to storage

Map domain shapes to storage as follows (Wlaschin ch. 12, the source of this
section):

| Domain shape | SQL | Document store |
|---|---|---|
| Choice type | one table with case flags and nullable columns (default); a table per case when case data is large and different | a JSON DTO with a tag |
| Entity inside an aggregate | its own table with a foreign key | embedded in the aggregate document |
| Value object | inline columns | embedded |
| Aggregate | one transaction | one document |

- Data read back from the database gets the same validation as any other
  input: convert rows with a `toDomain` function that can fail.
- ORMs cannot rebuild constrained types or choice types; plan explicit
  conversions.
- Persistence functions are single-purpose function types at the edge
  (`SaveOrder = Order -> IO (Either DbError ())`), not a repository with dozens
  of methods.
- Default to one aggregate per transaction (for the exception, see
  `domain-modeling.md` section 5). Across services, use compensation, not
  distributed transactions.

## 7. CQRS and event sourcing

- **Command-query separation:** queries return data and change nothing;
  commands change state and return little (Wlaschin ch. 12).
- **Separate read and write types** (`WriteModel.Customer`,
  `ReadModel.Customer`) because queries denormalize, read several aggregates,
  and evolve on their own. Separate stores only when needed; a relational read
  side can be a set of views.
- **Each context owns its storage.** Nobody else reads its tables. Reporting is
  another context, fed by events (Wlaschin ch. 12).
- **Event sourcing** (Ghosh ch. 8):
  - events are immutable, past-tense ADTs;
  - one pure `applyEvent :: State -> Event -> State`, shared by command
    handling and replay; a snapshot is a fold over events;
  - side effects run only in command handlers, never during replay;
  - an append-only store with "events for an aggregate", "append" and "all";
  - projections fold events into read models; rebuild a projection beside the
    old one, then switch;
  - plan event versioning from the first day.
- Choose CRUD unless the business needs history, replay, auditing, very high
  write scale, or many read shapes. Event sourcing is a new paradigm with
  operational costs and eventual consistency; say so in the design (Ghosh
  ch. 8).

## 8. Anti-patterns

- Database, wire or native-library types (including native error types) in
  business logic (Granin ch. 12-13).
- One type for reads and writes when their shapes differ; sharing tables
  between contexts (Wlaschin ch. 12).
- Serializers that auto-map rich domain types; implicit contracts (Wlaschin
  ch. 11).
- Floats or volatile natural keys as primary keys (Granin ch. 12).
- A type-level storage layer visible to scenario authors (Granin ch. 11).
- Re-running side effects during event replay (Ghosh ch. 8).
- Treating event sourcing as free: no versioning plan, no operations plan
  (Ghosh ch. 8).
