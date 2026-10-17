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
functions (Granin p. 321-323, 369):

```text
HTTP / messages      API types (DTOs)       value types, no logic, only at the network edge
business logic       domain types           protected by interfaces and layer boundaries
storage              database entities      keys, foreign keys, DB timestamps, DB primitive types
```

- Design the domain model first and the database model second. Starting from
  the database gives relational-biased, unintuitive types (Granin p. 320-321).
- Never let database or wire types leak into business logic. Convert only
  through functions (`fromDbMeteor`, `toDbMeteor`). "The thinner the edge",
  the easier it is to absorb changes (p. 323).
- The database model can live in its own module or package and evolve on its
  own schedule (p. 321).
- In large systems the three models are almost unavoidable. Watch for packs of
  near-duplicate types per use case. If duplication grows, a query language
  such as GraphQL may fit better than many REST shapes (p. 369-371).

## 2. DTOs and trust boundaries

Wlaschin's gates (Wlaschin p. 60-61, 229-246):

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

| Domain shape | DTO shape |
|---|---|
| Simple wrapper type | its primitive |
| Option | nullable or absent field |
| Record | record of converted fields |
| List, set | array |
| Map | JSON object, or an array of key-value pairs |
| Enum-like choice | string or enum; reject unknown values on read |
| Choice with data | a tag field plus one field per case (only the chosen one set), or a one-entry map keyed by the tag; check tag and data on read |
| Tuple | a named record |

## 3. Storage interfaces

Granin's key-value subsystem as a model for any storage interface
(p. 299-319):

- **Start with the smallest store-agnostic interface:** bytes in, bytes out,
  an explicit error result. A universal interface must cut store-specific
  features; do not try to unify everything (p. 301-306).
- **Tag each database with a phantom type** so that queries for one database
  cannot run against another (p. 303).
- **Keep connections out of query interfaces.** Open connections in the top
  level, return an abstract handle (never the native connection type), and run
  query batches against a handle in a transactional context (p. 303-305):

  ```haskell
  initKVDB :: KVDBConfig db -> App (Either DbError (DbHandle db))   -- top level
  evalKVDB :: DbHandle db -> KVDB db a -> Lang a                    -- business level
  ```

- **Add a typed layer over the raw one** when there are many entities and key
  mix-ups are a real risk: one generic `loadEntity :: Key e -> Query db (Result d)`
  driven by type classes that pair each entity with its key and value types.
  Hide this machinery from business code (p. 306-319).
- A raw bytes interface covers most needs and is simple and fast, but unsafe:
  garbage bytes load "successfully" (p. 306).
- Return errors as values (`Either DbError a`); never crash on a decode
  failure (p. 308-309).

## 4. Keys, IDs and record variants (HKD)

- **Surrogate keys** (integer, UUID, hash). Never floating-point keys; avoid
  natural and composite keys unless strongly justified (Granin p. 323-324).
- **The ID problem:** a new value has no ID before insertion. Granin names
  three forms and does not rank them (p. 324-325):
  - **A type parameter for the ID field:** `()` before insertion, the ID type
    after. Granin calls this HKD, but the parameter has kind `Type`, so it is
    not the uniform higher-kinded data that Parsons criticizes below.

    ```haskell
    data Meteor' id = Meteor { meteorId :: id, size :: Int, mass :: Int }
    type NewMeteor = Meteor' ()
    type Meteor    = Meteor' MeteorId
    ```

  - **Separate plain types per form** (`Meteor`, `MeteorPK`): simple, but
    with DB models they triple the number of types.
  - **An optional ID field:** do not use it on domain types. Every reader must
    handle absence, and code that "knows" the ID is set ends in `fromJust`
    crashes (Parsons p. 297-299; `production.md` section 9).
- **Many backends** (domain, SQLite, Postgres field types): HKD with a type
  selector that maps each field to a backend type, or code generation. The
  selector table is closed: adding a backend edits it (p. 325-329).
- **Counterpoint (Parsons p. 303-308):** polymorphic records do not scale; a
  type parameter per varying field grows fast, higher-kinded data varies all
  fields uniformly and cannot add or remove fields, and phase-indexed type
  families need an entry per field per phase. Use the small ID-parameter form
  when it fits; otherwise write separate plain types with conversions.

**Database operations as data** (Parsons p. 308-326; details in
`production.md`): a create-input type; a small query type interpreted to SQL;
field-update lists or an update-action sum type instead of `record -> record`
or whole records from clients; deletes that reuse the query type and let the
database cascade.

## 5. Choosing an SQL library

- Decide in this order (Granin p. 329-330):
  1. the requirements; wrong or incomplete needs "can easily scupper the
     project";
  2. the storage, the access library, the management approach, the schema
     (tables, relations, normalization, indexes), and how much logic goes into
     stored procedures and views;
  3. where the relational complexity lives: it cannot be removed, but it can
     sit in the database, in an intermediate mapping layer (ORM-like), or in
     the database model types. ORMs are overkill for small programs and hard
     to avoid in big ones; bare string queries without a schema description
     are hard to maintain.
- Do not write your own type-level SQL library (p. 330).
- Before you adopt a library, check whether its types leak into your schema,
  queries and business logic. Highly coupled, type-heavy libraries leak
  through every layer and are hard to put behind a framework (p. 331-336).
- Isolate the chosen library behind your own interface and connection type,
  even if the interface is imperfect. Map its connection type to your own so
  that business logic stays testable (p. 334-335).
- Trade-offs between type safety, convenience and simplicity are unavoidable.
  Choose consciously and write the reason down.

## 6. Mapping domain types to storage

Wlaschin's rules (Wlaschin p. 258-271):

| Domain shape | SQL | Document store |
|---|---|---|
| Choice type | one table with case flags and nullable columns (default); a table per case when case data is large and different | a JSON DTO with a tag |
| Entity inside an aggregate | its own table with a foreign key | embedded in the aggregate document |
| Value object | inline columns | embedded |
| Aggregate | one transaction | one document |

- Treat the database as an untrusted source: read rows through a validating
  `toDomain`.
- ORMs cannot rebuild constrained types or choice types; plan explicit
  conversions (p. 268).
- Persistence functions are single-purpose function types at the edge
  (`SaveOrder = Order -> IO (Either DbError ())`), not a repository with dozens
  of methods (p. 247-252).
- Default to one aggregate per transaction (for the exception, see
  `domain-modeling.md` section 5). Across services, use compensation, not
  distributed transactions.

## 7. CQRS and event sourcing

- **Command-query separation:** queries return data and change nothing;
  commands change state and return little (Wlaschin p. 252-256).
- **Separate read and write types** (`WriteModel.Customer`,
  `ReadModel.Customer`) because queries denormalize, read several aggregates,
  and evolve on their own. Separate stores only when needed; a relational read
  side can be a set of views.
- **Each context owns its storage.** Nobody else reads its tables. Reporting is
  another context, fed by events (p. 256-258).
- **Event sourcing** (Ghosh p. 257-281):
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
  p. 280).

## 8. Anti-patterns

- Database, wire or native-library types (including native error types) in
  business logic (Granin p. 323, 340).
- One type for reads and writes when their shapes differ (Wlaschin p. 254).
- Sharing tables between contexts (p. 256-257).
- Serializers that auto-map rich domain types; implicit contracts (p. 232, 241).
- Floats or volatile natural keys as primary keys (Granin p. 323-324).
- A type-level storage layer visible to scenario authors (p. 318).
- Re-running side effects during event replay (Ghosh p. 266-267).
- Treating event sourcing as free: no versioning plan, no operations plan
  (p. 280).
