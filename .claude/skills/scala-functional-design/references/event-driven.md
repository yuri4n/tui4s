# Event-driven systems

How to design, build and operate event-driven Scala services. Source: Volpe,
*Functional Event-Driven Architecture* (2023), which builds a trading system
on Apache Pulsar with fs2, plus a Kafka interpreter. The design rules hold
for any broker and effect system; map the libraries with `effect-systems.md`.
Items marked "(note)" are not from the book.

## Contents

1. Should you use EDA?
2. Messages
3. Delivery and idempotency
4. Atomicity across stores
5. Designing one service
6. Subscriptions, scaling and single writers
7. State over time: compaction, snapshots, replay
8. Schema evolution
9. Broker abstraction and choice
10. Failure scenarios and remedies
11. Testing and deployment
12. Translating to Kafka and to ZIO

## 1. Should you use EDA?

- Decide from the **non-functional requirements** (throughput, availability,
  audit, replay). If a monolith meets the SLA, EDA is overkill; say so and do
  not add a broker (FEDA p. 22-23).
- Good reasons: demanding availability, auditability, observability, state
  replay. Costs: more moving parts, harder observability, coordinated
  roll-outs, eventual consistency (p. 23-26, 33).
- **CQRS** (separate write and read sides) and **event sourcing** (state as a
  left fold over an append-only log) are different tools: pick one, both or a
  hybrid. Event sourcing needs no framework: a function, pattern matching and
  a fold (p. 27-31).
- **Request-response operations stay synchronous** (sign-in, an ATM
  withdrawal that must check funds now): process them directly, then publish
  the fact (`SignedIn`) for downstream consumers. Do not wrap them in a
  command and wait for a reply event (p. 31).
- **Fan-out on a domain event:** the critical service does only the critical
  work and emits one event; side tasks (counters, notifications, audit)
  become consumers that scale on their own. This removes the "which status
  code when step 5 of 6 failed?" problem of synchronous HTTP chains
  (p. 19-25).
- **Listen to yourself:** a service may consume its own events in a concurrent
  stream. You get the decoupling now and can move the consumer to its own
  service later (p. 24, 26).

## 2. Messages

- An **event** is a past fact; consumers only react. A **command** is a request
  that produces zero or more events. A **query** produces none (p. 19,
  29-30).
- Every message carries **its own ID, a correlation ID** (copied from its
  cause and shared by the whole business transaction) **and a creation
  time**. Put the common fields as abstract members of the enum (p. 21-22,
  28-29, 131).
- When one command yields exactly one event, **reuse the command ID as the
  event ID**, so a redelivered command hits a unique constraint (p. 191-194).
- **Emit failure events too** (`SignInFailed`, `NotRegistered`): they make the
  system pluggable, because new consumers can react to failures. Queries
  produce no events; the HTTP layer is the query handler (p. 24, 29-30, 126).
- Split interfaces into reader and writer (`UserCacheReader`,
  `UserCacheWriter`) when only one service may write (p. 30).
- Version public HTTP APIs in the URL from the first day (p. 67).

## 3. Delivery and idempotency

| Guarantee | How | Use when |
|---|---|---|
| at most once | no acks | a newer message supersedes the older one (latest values, IoT) |
| at least once | acks and retries | the default; pair it with idempotent handlers |
| exactly once | not achievable end to end; approximate with broker transactions, deduplication and idempotent side effects | |

(FEDA p. 40, 54.)

- **Assume at-least-once delivery and make every handler idempotent:**
  deterministic event IDs, unique constraints, deduplication tables, natural
  keys or upserts (p. 54, 191-194).
- **Deduplication strategies** (p. 41-42, 57-58):

  | Situation | Strategy |
  |---|---|
  | one producer that retries | broker producer deduplication (sequence IDs) with a stable, unique producer name |
  | a fixed number of producers, each uniquely named | still broker deduplication |
  | a variable number of producers, or crash risk in consume-process-produce | broker transactions; otherwise consumer-side deduplication |
  | consumer-side deduplication | a registry of processed IDs over a time window (for example 30 minutes); in memory for one active consumer, persisted for shared or key-shared subscriptions |

- Broker deduplication and transactions are often **off by default** on the
  server and the client (Pulsar); enable them explicitly (p. 57-58).
- **Classify handler outcomes** (p. 114-115, 191-192):
  - expected business error (duplicate name, missing entity): log and ack (a
    retry will not help);
  - duplicate of an already processed message: ack;
  - unexpected error: nack (retry later);
  - undecodable message: decode-error policy plus a dead-letter topic after N
    redeliveries; ack (drop) only when newer messages supersede it;
  - rejected or ignored inputs that produce no output: still ack them
    (p. 148, 181).

## 4. Atomicity across stores

| Need | Tool | Cost |
|---|---|---|
| atomic update inside one process | `Ref` (compare-and-swap), `Semaphore` | none |
| consume + produce + ack atomically | broker transactions (Kafka, Pulsar) | latency; broker operations only |
| database write + event | transactional outbox read by CDC (Debezium) | an extra process; the database can become the bottleneck |
| several writes that can be undone | saga with compensations | complexity |
| every step idempotent | retry everything | design effort |
| exclusive access across processes | distributed lock (Redis, ZooKeeper) | TTL tuning; fencing |

(FEDA p. 41-48.)

- **Broker transactions cover only broker operations**, never your database
  or cache (p. 41, 140).
- **Never pair a database transaction with a broker transaction** as two
  resources (`(brokerTx, dbTx).tupled.use`): one commit can still fail and
  atomicity is lost. Use the outbox (p. 141).
- **Change data capture:** write only to the database; a CDC connector reads
  the transaction log and publishes the event; other writes (Redis) become
  consumers of that event, which gives a linear chain (p. 44-45).
- **Transactional outbox:** in one database transaction, insert the entity and
  an `outbox` row; CDC publishes the row. Do not overuse it: the database
  becomes the bottleneck that EDA tries to avoid; listen-to-yourself can be
  the better option (p. 46).
- **Outbox relay transactions are short.** Do not hold a database
  transaction (or a row lock) open while you publish to the broker. Claim a
  batch in one short transaction (`FOR UPDATE SKIP LOCKED`, or a lease
  column), publish outside it, and mark the rows sent in a second
  transaction. A crash between the steps republishes, which is safe because
  consumers are idempotent. (Practice; FEDA uses CDC for this step.)
- **Database write inside a broker-transaction handler:** make it idempotent
  (natural primary key, upsert) or use CRDTs, because the broker transaction
  may abort after the write succeeded (p. 140-141).
- **Distributed lock** on Redis: see `http-and-persistence.md`; release with
  an atomic compare-and-delete, and use fencing tokens if two holders for a
  moment would corrupt data (note).

## 5. Designing one service

Ask these questions for every service (chapter 6 of FEDA):

1. **Inputs and outputs:** which commands or events, from which topics, with
   which subscription types; which events or alerts, to which topics, with
   which keys.
2. **Critical or not?** A non-critical consumer may be down and catch up from
   the log. Give stateless critical services extra instances (p. 35-36).
3. **Stateless or stateful?** A service is stateful only if it needs state
   from storage to start. A compacted topic or a snapshot often removes that
   need. Writing to a database does not make a service stateful, and neither
   does local state that a framework restores for you (Kafka Streams tables);
   ask who restores the state at start (p. 51-53). Prefer stateless
   processors with the state in the broker, and a separate sink service that
   persists results (p. 51). In the book, the DB-backed forecasts handler runs
   one active writer (Failover), because concurrent writers would conflict
   and make transactions more expensive, and it writes on every command
   because traffic is low (p. 205). Before you scale out a DB writer, ask
   whether writes contend; if they do, use one active writer or partition the
   writes by key; batch at high traffic (p. 36, 100-101).
4. **Tables first:** write (state, input) -> output and (state, input) ->
   state change as tables; each row becomes one case of a pure FSM (p. 132-133,
   147). Not every service needs an FSM: a stateless command handler over
   database stores with an outbox is fine for low traffic (p. 188-194).
5. **"What if?" for every effect in the handler:** what if this line fails?
   What if it succeeds and the reply is lost? Is the retry safe? Write the
   answers in a design note (p. 140-141).
6. **Handler shape** for "process a command, publish an event": one broker
   transaction per command; run the pure FSM; create the event ID and time;
   publish with the transaction; ack with the transaction; return the new
   state. On any error, log and **keep the old state**; the transaction aborts
   and the command is redelivered (p. 138-140):

   ```scala
   def handle(st: TradeState, msg: Msg[TradeCommand]): F[(TradeState, Unit)] =
     txResource.use { tx =>
       val (next, mkEvent) = TradeEngine.fsm.run(st, msg.payload)
       (GenUUID[F].make[EventId], Time[F].timestamp).mapN(mkEvent)
         .flatMap(producer.send(_, tx)) *> acker.ack(msg.id, tx).as(next -> ())
     }.handleErrorWith(e => Logger[F].warn(e.getMessage).as(st -> ()))
   // uses the transactional send/ack of § 9
   ```

7. **Scaling and start-up:** which key shards the work, what happens on a
   rebalance, where the start state comes from, and whether replay order
   matters between topics (sections 6 and 7).
8. **Pipelines:** keep real-time work, batching and analytics in separate
   stages or consumers; batch slow writes (`chunkN`, `groupWithin`). Do not
   put an in-memory hop (an fs2 `Topic` or `Queue`) between consume and ack
   when messages matter: with manual acks, a failed ack duplicates downstream
   (p. 172); (note) with auto-ack, a crash loses everything buffered in
   memory. (note) Use an in-memory fan-out only for data you can lose or
   recompute; otherwise give each branch its own subscription, or ack after
   all branches finish (p. 99-107, 169, 172).

## 6. Subscriptions, scaling and single writers

Pulsar subscription types (p. 56-57):

| Type | Consumers | Delivery | Typical use |
|---|---|---|---|
| Exclusive | one | all messages, in order | each service its own subscription (pub-sub) |
| Failover | many attached, one active | in order; a standby takes over | one active instance with a hot standby |
| Shared | many | round robin, no order | stateless work queue |
| Key_Shared | many | by ordering key | parallel work with order per key |

Scaling a consumer of keyed events (p. 142-157):

| Service kind | Subscription | Rebalance handling |
|---|---|---|
| output depends only on a small global state | Key_Shared by entity key + an Exclusive subscription per instance on a compacted control topic | nothing; start empty and read the control state |
| per-key aggregate state | Key_Shared + share state updates between instances on a compacted topic, tagged with the instance ID | each instance applies other instances' updates |
| per-key state, simple operations | Failover | none; no horizontal scale on one topic |
| per-key state, fixed fleet | Key_Shared with sticky hashing | none; fixed count; stalls if one instance is down (dated detail) |
| must see every message (control and state topics) | Exclusive per instance with a unique name | not applicable |

- **Subscription names decide who shares a cursor:** instances of a
  Key_Shared, Shared or Failover consumer use the service name; per-instance
  Exclusive consumers use the instance ID; per-connection consumers use
  generated names with non-durable mode. An Exclusive subscription with a
  global name allows only one instance (p. 133-134, 151, 168-179, 248).
- **Single writer** (one active writer at a time) (p. 176-178, 205):
  - stateless writer: a Failover subscription;
  - stateful writer that must load a snapshot before processing: an
    Exclusive, non-durable subscription per instance plus a distributed lock;
    only the lock holder loads the snapshot, rewinds and processes. A
    Failover standby gets no "you are now active" signal, so it cannot
    prepare its state.
- **A command that touches several keys** (an order with many SKUs) cannot
  be routed to one per-key owner. Choose and state one approach:
  - split it into per-key commands and coordinate the results (a saga with a
    release step);
  - key by the command's own ID and serialize per key in the database (lock
    the rows in a fixed order, with a `CHECK` constraint as the last guard);
  - route it to the owner of a group key when keys never cross groups.
  The single-writer rules above apply to the first and third choices; the
  second moves the single-writer guarantee into the database (practice).
- **Periodic writers** (snapshots): keep pending message IDs in the FSM state,
  write on a tick, ack in a batch after a successful write, keep the pending
  IDs when the write or the ack fails. A tick stream can also refresh the
  lock's TTL (p. 180-184).
- **Per-client fan-out** (WebSocket clients): one non-durable, exclusive
  broker subscription per connection, reading the compacted topic from the
  earliest message, so the broker handles deduplication. Remove it when the
  client disconnects. A single topic may then become a bottleneck; start with
  a partitioned topic of one partition (p. 168-172).

## 7. State over time: compaction, snapshots, replay

- **Compaction:** give each message a key; the broker keeps only the newest
  message per key. Consumers rebuild latest-value state quickly. Never compact
  the event-sourced audit log (p. 58-59, 63).
- **Snapshots with short retention:** a dedicated service periodically writes
  the state plus the ID of the last event it includes; services load the
  latest snapshot and replay only later events. Keep long-term history in a
  warehouse or database, not in a huge topic (p. 63-64).
- **Rewind and gate:** when a service replays one topic from a snapshot and
  also reads compacted topics, hold the compacted consumers behind a
  `Deferred` gate that the replay stream completes when it reaches the last
  message ID present at start-up. Otherwise the latest values are applied to
  old state (p. 158-159). (note) Handle the edge case where there is no last
  message ID, or the gate never opens.
- **Pulsar key naming:** the ordering key routes Key_Shared messages; the
  message key drives compaction (and routing when there is no ordering key)
  (p. 110-111).
- **Choose the compaction key by asking "the latest value of what?"** Keying
  switch messages by kind ("start", "stop") keeps one message per kind and
  then depends on log order to know the current state; a single key for "the
  current switch state" would not (p. 58-59, 143; design note).
- **Retention must cover every planned replay.** By default Pulsar keeps only
  unacknowledged messages, so snapshot rewinds, a first start from the
  earliest message and non-durable subscriptions see only what is retained.
  (note) Set retention to at least the snapshot interval plus the longest
  outage plus a margin, keep the snapshot key's TTL above the same window, and
  alert on snapshot age (p. 58, 63-64, 158, 177-180).

## 8. Schema evolution

- Design schema changes up front; later is already too late (p. 65).
- **Compatible changes:** add fields as optional with defaults (`Option` in
  shared Scala models; in Avro a union with null and a default). Across teams
  or languages, use a schema format (Avro, Protobuf) with a registry that
  checks compatibility at start-up and keep the schemas in version control
  (p. 65-69). Backward compatibility (new readers read old data) is usually
  enough for messaging.
- **Breaking changes:** publish to a new versioned topic. Deploy the consumer
  that reads both versions first, then the producer, then remove the old code
  and topic. Deploying the producer first creates a backlog with no consumer
  (p. 67-69).
- During rolling deployments, message schemas must stay backward compatible
  (p. 250).
- **JSON bytes get no registry check.** The book's services exchange Circe
  JSON as bytes, so nothing checks compatibility at start-up. Within one team
  and a shared domain module, use codec round-trip and golden tests plus
  consumer-first deployments; across teams or languages, use Avro or
  Protobuf with a registry (p. 66, 69, 112, 163, 250).

## 9. Broker abstraction and choice

- Small transport algebras (`Producer`, `Consumer`, `Acker`) with an in-memory
  interpreter are good for tests, prototypes and house defaults (structured
  logging, dead-letter policy) (p. 106-109):

  ```scala
  trait Producer[F[_], A]:
    def send(a: A): F[Unit]
    def send(a: A, props: Map[String, String]): F[Unit]

  trait Acker[F[_], A]:
    def ack(id: MsgId): F[Unit]
    def nack(id: MsgId): F[Unit]

  trait Consumer[F[_], A] extends Acker[F, A]:
    def receiveM: Stream[F, Msg[A]]   // manual ack
    def receive: Stream[F, A]         // auto ack

  // transactional variants (Pulsar interpreter, p. 112, 115):
  //   def send(a: A, tx: Txn): F[Unit];  def ack(id: MsgId, tx: Txn): F[Unit]
  ```

- **Do not promise that Kafka and Pulsar are interchangeable.** Their semantics
  differ (no nack in Kafka consumer groups, offsets vs message IDs, keys vs
  ordering keys, subscription types). Pick the broker, learn its semantics,
  and use its library directly where they differ (p. 118, 121).
- Volpe's comparison (he maintains a Pulsar client, a declared bias): Kafka
  with ZooKeeper is the most battle-tested (he had no KRaft experience), with
  Kafka Streams and Connect; Pulsar has easier defaults, optional partitions,
  subscription types, a built-in schema registry, geo-replication and tiered
  storage (p. 60-62). The book expects ZooKeeper's removal in Kafka 4
  (p. 55). (note) Kafka 4.0 (2025) removed it, and share groups
  (production-ready from Kafka 4.2) narrow the gap with Pulsar's shared
  subscriptions; check current versions.

## 10. Failure scenarios and remedies

| Scenario | Remedy |
|---|---|
| synchronous HTTP chain: the main step succeeds, a side step fails | publish an event; side steps become consumers (p. 20) |
| a non-critical consumer is down | eventual consistency; it catches up from the log (p. 25, 36) |
| a remote call is retried after a timeout or crash | an idempotent API (409 returns the existing ID) (p. 40) |
| a producer retry creates duplicates | broker deduplication with stable, unique producer names (p. 41, 57) |
| a variable number of producers, or producers without stable unique names, on one topic | broker transactions, or a consumer-side ID registry over a time window (p. 41-42, 58) |
| crash after produce, before ack | produce and ack in one broker transaction; otherwise consumer deduplication (p. 57-58, 140) |
| the input was processed but its ack was lost | at-least-once delivery and idempotent handlers (p. 54) |
| write to the database and the cache and publish, with no atomicity | CDC, outbox, listen-to-yourself, saga, or idempotent steps (p. 44-46) |
| a database write inside a broker-transaction handler fails, or succeeds with a lost reply | idempotent write; never pair the transactions; outbox (p. 140-141) |
| a command is redelivered after the database commit | event ID = command ID; unique constraint; ack the duplicate (p. 191-192) |
| a side effect must happen once per event (a vote) | a deduplication table keyed by event ID in the same database transaction (p. 194) |
| the handler's transaction fails | keep the previous in-memory state; the message is redelivered (p. 138) |
| an undecodable message | decode-error handler and a dead-letter topic (p. 114-115) |
| a handler's retries run out for one message | park the message (retry topic or dead letter with an alert) and continue; do not fail the whole consumer stream, which restarts every partition on that instance (practice) |
| a breaking schema change | versioned topic; consumers first (p. 67-69) |
| a rebalance in a stateful sharded service gives stale state for new keys | share state updates between instances, use Failover, or a fixed fleet (p. 156-157) |
| start-up replays too much history | compaction; snapshots with short retention (p. 58, 63) |
| replay from a snapshot races with compacted-topic consumers | a `Deferred` gate opened at the last message ID (p. 158-159) |
| a Failover standby takes over with stale state | Exclusive per instance + distributed lock; load and rewind first (p. 177-178) |
| a snapshot write or a batch ack fails | keep the pending IDs; retry on the next tick (p. 181-182) |
| an in-memory rebroadcast between consume and ack duplicates on ack failure | one broker subscription per client (p. 172) |
| a fast producer and a slow database | batch writes; separate real-time and batch stages (p. 100-102) |
| related messages from different topics arrive out of order | buffer and match by correlation ID; re-run pending items on a tick; expire unmatched entries (p. 231-235) |
| a second replica of a consumer with an exclusive global name | it crash-loops; give unique names or use Failover (p. 248) |

## 11. Testing and deployment

- **Test pyramid** (p. 139, 182, 194, 213-215, 242): pure FSM tests with
  scripted inputs; handler tests with in-memory fakes, including failing
  fakes; integration tests per store interpreter (Redis; SQL with migrations);
  smoke tests of a few services through the real broker in CI. No full-system
  simulation. See `testing.md`.
- **Feed services** generate realistic traffic from ScalaCheck generators
  (weighted with `Gen.frequency`, referencing existing IDs) for manual tests
  (p. 209-212).
- **Deployment follows subscription types** (p. 246-250):
  - Key_Shared and unique exclusive subscriptions scale with replicas;
  - an exclusive subscription with a global name is a single-instance service
    by design (a second pod crash-loops), and rolling restarts cause seconds
    of downtime, because the old pod must stop first. The book's claim that
    Kubernetes handles this depends on the rollout strategy and readiness
    probes, which it does not show; use a "Recreate" strategy (no surge), or a
    Failover subscription with two or more replicas (critic note);
  - Failover services move traffic to the standby when the active pod dies.
- Every service exposes health and metrics, and logs every produced and
  consumed message in a structured way (see `assembly-and-operations.md`).

## 12. Translating to Kafka and to ZIO

(note) The concepts map, but the semantics differ; verify for the Kafka
version in use:

| Pulsar (book) | Kafka |
|---|---|
| Key_Shared by entity key | partitions keyed by entity, one consumer group |
| Exclusive subscription per instance | a unique consumer group per instance |
| Failover | one active consumer per partition in a group |
| broker transactions | Kafka transactions (exactly-once within Kafka) |
| compaction | log compaction |
| `lastMsgId`, rewind to a message ID | offsets: `seek`, `endOffsets` |
| Shared subscription | share groups (Kafka 4.2+, production-ready; use 4.2.1 or later) |

- Libraries: fs2-kafka (Cats Effect; `org.typelevel` from 4.x), ZIO Kafka
  (ZIO), the Ox Kafka module (direct style); Neutron (Pulsar, Cats Effect; no
  release since 0.0.10 in Dec 2022, targets Pulsar 2.10; prefer
  pulsar4s-cats-effect / pulsar4s-fs2, or the Java client behind an algebra).
  Use the Compendium (`effect-systems.md`).
- ZIO: FSMs run with `ZStream.mapAccumZIO`; `Resource` becomes `Scope` or a
  scoped layer; `Deferred` becomes `Promise`; `Ref` stays `Ref`.
- Kafka consumer groups have no nack: retry topics, dead-letter topics and
  offset commits replace it. Auto-commit conflicts with manual-ack designs
  (p. 117). Share groups (Kafka 4.2+) do have per-record acknowledgements
  (`RELEASE` redelivers, `REJECT` drops).
